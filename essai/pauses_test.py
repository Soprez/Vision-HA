"""Pause par code ou empreinte (Vision 1.70.0), dans un vrai Home Assistant.

Ce qu'on vérifie : « Durée max de pause » par appareil (administrateurs), envoyée au relevé ;
réglable depuis l'espace parents d'un appareil Parent, pas d'un appareil Enfant ; chaque pause
rapportée est journalisée une fois (avertissement + événement), une pause au-delà du maximum est signalée.
"""
from __future__ import annotations

import logging
import pathlib
import shutil
import sys
import tempfile
import time

import pytest
from homeassistant.core import Context
from homeassistant.exceptions import Unauthorized
from homeassistant.helpers import entity_registry as er
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_capture_events

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-pauses-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
ROBIN = {"id": "p1", "secret": "p" * 32}
LOU = {"id": "e1", "secret": "e" * 32}


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


def _fiche(pc_id, nom, secret, **plus):
    return {"id": pc_id, "name": nom, "host": nom, "secret": secret, "enabled": True, "rules": [],
            "bloques": [], "autorises": [], "override": None, "last_seen": 0, "agent": {},
            "person": "", "update_asked": False, "tags": [], "messages": [], "next_message_id": 1, **plus}


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "p1": _fiche("p1", "PIXEL-ROBIN", ROBIN["secret"], platform="android", role="parent"),
            "e1": _fiche("e1", "TEL-LOU", LOU["secret"], platform="android"),
        }}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def _releve(client, ident, **plus):
    return await (await client.post("/api/pc_parental/poll", json={"platform": "android", "type": "telephone", **ident, **plus})).json()


async def test_duree_max_par_appareil(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    client = await hass_client()
    assert (await _releve(client, ROBIN))["pause_max"] == 60
    nombre = er.async_get(hass).async_get_entity_id("number", DOMAIN, f"{DOMAIN}_pause_max_p1")
    enfant = await hass.auth.async_create_user("Lou", group_ids=["system-users"])
    with pytest.raises(Unauthorized):
        await hass.services.async_call("number", "set_value", {"entity_id": nombre, "value": 240}, blocking=True,
                                       context=Context(user_id=enfant.id))
    await hass.services.async_call("number", "set_value", {"entity_id": nombre, "value": 240}, blocking=True)
    coord = next(iter(hass.data[DOMAIN].values()))  # le premier relevé du banc a rechargé Vision
    assert coord.store.pcs["p1"]["pause_max"] == 240
    assert (await _releve(client, ROBIN))["pause_max"] == 240


async def test_espace_parents(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    client = await hass_client()
    r = await client.post("/api/pc_parental/parent", json={**ROBIN, "action": "pause_max", "pc": "p1", "minutes": 30})
    coord = next(iter(hass.data[DOMAIN].values()))
    assert r.status == 200 and coord.store.pcs["p1"]["pause_max"] == 30
    r = await client.post("/api/pc_parental/parent", json={**LOU, "action": "pause_max", "pc": "e1", "minutes": 900})
    assert r.status == 403 and "pause_max" not in coord.store.pcs["e1"]
    r = await client.post("/api/pc_parental/parent", json={**ROBIN, "action": "pause_max", "pc": "p1", "minutes": 2})
    assert r.status == 400


async def test_pauses_journalisees_une_fois(hass, hass_storage, hass_client, caplog):
    caplog.set_level(logging.WARNING)
    await _monter(hass, hass_storage)
    client = await hass_client()
    evenements = async_capture_events(hass, f"{DOMAIN}_pause")
    debut = time.time() - 60
    pauses = [{"debut": debut, "minutes": 30, "methode": "biometrie"}]
    await _releve(client, ROBIN, pauses=pauses)
    await _releve(client, ROBIN, pauses=pauses)  # rejouée (relevé perdu) : pas deux fois
    await _releve(client, ROBIN, pauses=[{"debut": debut + 10, "minutes": 120, "methode": "code"}])
    await hass.async_block_till_done()
    assert [(e.data["minutes"], e.data["methode"], e.data["au_dela_du_max"]) for e in evenements] == [
        (30, "biometrie", False), (120, "code", True)]
    assert "pause de 30 min sur « PIXEL-ROBIN » (empreinte ou visage)" in caplog.text
    assert "au-delà de la durée max" in caplog.text
