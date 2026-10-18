"""Refonte UX, lot 6 : frictions restantes côté Home Assistant.

- HA-25 : « Actions permises » sans entité pilotable mène à l'étape qui manque (au lieu d'un abandon) ;
- HA-19 / EX-13 : Configurer sans doublon (« Appareils existants » et « Avancé » répétaient d'autres menus) ;
- la veille n'est plus annoncée « sur PC » (retirée des PC).

    python -m pytest essai/refonte_l6_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

RACINE = Path(__file__).resolve().parents[1] / "custom_components" / "pc_parental"


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "pcs": {"e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android")}}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree


async def test_ha25_actions_sans_entite_guide(hass, hass_storage):
    entree = await _monter(hass, hass_storage)
    om = hass.config_entries.options
    flux = await om.async_init(entree.entry_id)
    for etape in ("autres", "entites", "entites_actions"):
        flux = await om.async_configure(flux["flow_id"], {"next_step_id": etape})
    assert flux["type"] == "form" and flux["step_id"] == "entites_personnes"
    assert flux["errors"] == {"base": "entites_rien_a_piloter"}
    # Rouvert normalement, l'écran n'affiche plus l'erreur.
    om.async_abort(flux["flow_id"])
    flux = await om.async_init(entree.entry_id)
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "autres"})
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "entites"})
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "entites_personnes"})
    assert not flux["errors"]


def test_textes():
    for lg in ("fr", "en", "de", "es", "it", "nl"):
        tr = json.loads((RACINE / "translations" / f"{lg}.json").read_text(encoding="utf-8"))
        assert tr["options"]["error"]["entites_rien_a_piloter"]
        assert "avance" not in tr["options"]["step"] and "appareils" not in tr["options"]["step"]
        serveur = json.loads((RACINE / "i18n" / "serveur" / f"{lg}.json").read_text(encoding="utf-8"))
        assert not re.search(r"\bpc\b", serveur["module.veille.phrase"].lower())


def test_lot2_liens_a_decider():
    """Lot 2, fini au lot 6 : « À décider » et ses notifications mènent au tableau de Vision, plus à /lovelace-vision."""
    from custom_components.pc_parental import a_decider, dashboard

    assert a_decider.URL_PATH == dashboard.URL_PATH
    for f in RACINE.glob("*.py"):
        assert "lovelace-vision" not in f.read_text(encoding="utf-8"), f.name
