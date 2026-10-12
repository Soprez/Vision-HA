"""Essai de « Autoriser 5 min » (inscription de nouveaux appareils), dans un vrai Home Assistant.

Ce qu'on vérifie :
- Vision → Configurer propose le réglage, avec l'état et le temps restant ;
- « Autoriser 5 min » ouvre, puis le serveur referme tout seul, et le journalise ;
- l'échéance fait foi même avant que le minuteur ne passe ;
- le minuteur survit à un rechargement ; une échéance passée pendant l'arrêt est refermée au démarrage ;
- l'interrupteur « Inscription de nouveaux appareils » suit, et l'ouvrir ou le fermer annule le minuteur.

Banc : le même que essai/securite_test.py.
    python -m pytest essai/inscriptions_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import logging
import pathlib
import shutil
import sys
import tempfile
import time
from datetime import timedelta

import pytest
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_fire_time_changed

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-inscriptions-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"


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


async def _monter(hass: HomeAssistant, entree=None):
    if entree is None:
        hass.states.async_set("person.parent", "home", {"friendly_name": "Parent"})
        entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
        entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree, hass.data[DOMAIN][entree.entry_id]


def _interrupteur(hass):
    for etat in hass.states.async_all("switch"):
        if etat.entity_id.endswith("inscription_de_nouveaux_appareils") or etat.entity_id.endswith("inscription_de_nouveaux_pc"):
            return etat
    raise AssertionError("interrupteur introuvable")


async def _inscrire(client, cle, nom):
    r = await client.post("/api/pc_parental/enroll", json={"key": cle, "host": nom, "user": "x", "uid": nom})
    return r.status


async def _options(hass, entree, mode):
    r = await hass.config_entries.options.async_init(entree.entry_id)
    assert r["type"] == FlowResultType.MENU and "securite" in r["menu_options"]
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "securite"})
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "inscriptions"})
    assert r["type"] == FlowResultType.FORM and r["step_id"] == "inscriptions"
    etat = r["description_placeholders"]["etat"]
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"inscriptions": mode})
    assert r["type"] == FlowResultType.CREATE_ENTRY
    await hass.async_block_till_done()
    return etat


async def test_autoriser_5_min_puis_refermeture(hass, hass_client_no_auth, freezer, caplog):
    caplog.set_level(logging.INFO)
    entree, coord = await _monter(hass)
    client = await hass_client_no_auth()
    cle = coord.store.enroll_key
    await _options(hass, entree, "fermer")
    assert _interrupteur(hass).state == "off"
    assert await _inscrire(client, cle, "PC-AVANT") == 403

    await _options(hass, entree, "temporaire")
    assert _interrupteur(hass).state == "on"
    assert "refermeture_automatique" in _interrupteur(hass).attributes
    # L'écran montre le temps restant.
    freezer.tick(timedelta(seconds=72))
    etat = await _options(hass, entree, "garder")
    assert etat.startswith("ouvertes encore 3 min 48 s"), etat
    assert await _inscrire(client, cle, "PC-PENDANT") == 200
    await hass.async_block_till_done()

    # Cinq minutes après l'ouverture : refermées par le serveur, et journalisé.
    coord = hass.data[DOMAIN][entree.entry_id]  # l'inscription a rechargé l'intégration
    freezer.tick(timedelta(seconds=229))
    async_fire_time_changed(hass)
    await hass.async_block_till_done()
    assert _interrupteur(hass).state == "off"
    assert coord.store.enroll_jusqua == 0 and not coord.store.enroll_open
    assert "refermées automatiquement (5 minutes écoulées)" in caplog.text
    assert await _inscrire(client, cle, "PC-APRES") == 403


async def test_l_echeance_fait_foi_avant_le_minuteur(hass, hass_client_no_auth, freezer):
    entree, coord = await _monter(hass)
    await _options(hass, entree, "temporaire")
    freezer.tick(timedelta(seconds=301))  # sans laisser passer le minuteur
    assert await _inscrire(await hass_client_no_auth(), coord.store.enroll_key, "PC-TARD") == 403


async def test_survit_au_rechargement(hass, freezer, caplog):
    caplog.set_level(logging.INFO)
    entree, coord = await _monter(hass)
    await _options(hass, entree, "temporaire")
    assert await hass.config_entries.async_reload(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    assert _interrupteur(hass).state == "on"
    freezer.tick(timedelta(seconds=301))
    async_fire_time_changed(hass)
    await hass.async_block_till_done()
    assert _interrupteur(hass).state == "off" and coord.store.enroll_jusqua == 0
    assert "refermées automatiquement (5 minutes écoulées)" in caplog.text


async def test_echeance_passee_pendant_l_arret(hass, caplog):
    caplog.set_level(logging.INFO)
    entree, coord = await _monter(hass)
    assert await hass.config_entries.async_unload(entree.entry_id)
    coord.store.enroll_open = False
    coord.store.enroll_jusqua = time.time() - 60
    await coord.store.async_save()
    _entree, coord = await _monter(hass, entree)
    await hass.async_block_till_done()
    assert coord.store.enroll_jusqua == 0 and _interrupteur(hass).state == "off"
    assert "échéance passée pendant l'arrêt" in caplog.text


async def test_interrupteur_annule_le_minuteur(hass, freezer):
    entree, coord = await _monter(hass)
    await _options(hass, entree, "temporaire")
    await hass.services.async_call("switch", "turn_on", {"entity_id": _interrupteur(hass).entity_id}, blocking=True)
    assert coord.store.enroll_open and coord.store.enroll_jusqua == 0
    freezer.tick(timedelta(seconds=301))
    async_fire_time_changed(hass)
    await hass.async_block_till_done()
    assert _interrupteur(hass).state == "on"  # ouvertes pour de bon : rien ne les referme
    await _options(hass, entree, "temporaire")
    await hass.services.async_call("switch", "turn_off", {"entity_id": _interrupteur(hass).entity_id}, blocking=True)
    assert not coord.store.inscriptions_ouvertes() and coord.store.enroll_jusqua == 0
