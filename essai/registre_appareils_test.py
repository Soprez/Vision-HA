"""Registre des appareils : plus d'appel à ``async_get_device`` (supprimé en HA 2027.8).

Vision retrouve l'appareil d'un PC par ``async_get_device_by_identifier`` via
``pc_parental/registre.py`` ; ces tests vérifient l'utilitaire et qu'aucun
ancien appel ne subsiste dans le code de l'intégration.
"""

from __future__ import annotations

import pathlib
import re
import shutil
import sys
import tempfile

import logging

import pytest
from homeassistant.helpers import device_registry as dr
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"

_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-registre-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

from custom_components.pc_parental import labels  # noqa: E402
from custom_components.pc_parental.registre import (  # noqa: E402
    appareil_du_pc,
    appareil_par_identifiant,
    id_du_hub,
    identifiant_pc,
)

DOMAIN = "pc_parental"


def _entree(hass) -> MockConfigEntry:
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    return entree


async def test_retrouve_appareil_par_identifiant(hass):
    entree = _entree(hass)
    cree = dr.async_get(hass).async_get_or_create(
        config_entry_id=entree.entry_id, identifiers={(DOMAIN, "pc_salon")}, name="Salon"
    )
    assert identifiant_pc("salon") == (DOMAIN, "pc_salon")
    trouve = appareil_du_pc(hass, "salon")
    assert trouve is not None and trouve.id == cree.id
    assert appareil_par_identifiant(hass, (DOMAIN, "pc_salon")).id == cree.id
    # labels passe par le même utilitaire
    assert labels.appareil(hass, "salon").id == cree.id
    assert labels.noms_du_pc(hass, "salon") == []


async def test_none_si_absent(hass):
    entree = _entree(hass)
    dr.async_get(hass).async_get_or_create(
        config_entry_id=entree.entry_id, identifiers={(DOMAIN, "pc_salon")}, name="Salon"
    )
    assert appareil_du_pc(hass, "bureau") is None
    assert labels.appareil(hass, "bureau") is None


async def test_none_sans_entree_vision(hass):
    assert appareil_du_pc(hass, "salon") is None


async def test_ignore_appareil_d_une_autre_integration(hass):
    _entree(hass)
    autre = MockConfigEntry(domain="autre", data={})
    autre.add_to_hass(hass)
    dr.async_get(hass).async_get_or_create(
        config_entry_id=autre.entry_id, identifiers={(DOMAIN, "pc_salon")}, name="Intrus"
    )
    assert appareil_du_pc(hass, "salon") is None


@pytest.mark.parametrize("dossier", ["pc_parental"])
def test_plus_aucun_async_get_device(dossier):
    motif = re.compile(r"\basync_get_device\s*\(")
    restes = []
    for fichier in sorted((racine / dossier).rglob("*.py")):
        for numero, ligne in enumerate(fichier.read_text(encoding="utf-8").splitlines(), 1):
            if motif.search(ligne):
                restes.append(f"{fichier.relative_to(racine)}:{numero}: {ligne.strip()}")
    assert not restes, "async_get_device( subsiste :\n" + "\n".join(restes)


# --- Montage de l'intégration : ni via_device ni async_get_device ---------------------

@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


def _fiche(pc_id: str, nom: str) -> dict:
    return {"id": pc_id, "name": nom, "host": nom, "secret": pc_id * 32, "enabled": True,
            "rules": [], "bloques": [], "autorises": [], "override": None, "last_seen": 0,
            "agent": {}, "person": "", "update_asked": False, "tags": [], "messages": [],
            "next_message_id": 1}


async def test_montage_sans_avertissement_et_pc_rattaches_au_hub(
    hass, hass_storage, enable_custom_integrations, caplog
):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "pcs": {"a": _fiche("a", "PC-SALON"), "b": _fiche("b", "PC-BUREAU")}}}
    caplog.set_level(logging.WARNING)
    entree = _entree(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()

    hub = id_du_hub(hass, entree.entry_id)
    assert hub is not None
    for pc_id in ("a", "b"):
        appareil = appareil_du_pc(hass, pc_id)
        assert appareil is not None, pc_id
        assert appareil.via_device_id == hub, pc_id

    fautifs = [
        r.getMessage() for r in caplog.records
        if "via_device" in r.getMessage() or "async_get_device" in r.getMessage()
    ]
    assert not fautifs, " | ".join(fautifs)
    assert await hass.config_entries.async_unload(entree.entry_id)
    await hass.async_block_till_done()
