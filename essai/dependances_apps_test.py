"""2.0.3 — Roblox sans avatars ni textures sur PC-LOU : « Jeux » fermé (moyenne sous 12) mais « roblox »
autorisé sur le PC. Le jeu se lançait, ses serveurs d'images (rbxcdn.com…) restaient bloqués. Une appli
autorisée garde désormais les sites dont elle dépend ; un site bloqué nommément reste bloqué.

    python -m pytest essai/dependances_apps_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

ROBLOX = ("roblox.com", "rbxcdn.com", "robloxlabs.com")


async def _installer(hass, hass_storage, autorises=(), bloques=()):
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "etiquettes": {"apps": {"roblox": ["Jeux"], "robloxplayerbeta": ["Jeux"]},
                       "sites": {"roblox.com": ["Jeux"], "rbx.com": ["Jeux"], "rbxinfra.com": ["Jeux"]}},
        "fermetures": {"person.lou": {"jeux|": {"etiquette": "Jeux", "raison": "Fermé tant que ta moyenne est sous 12.",
                                                   "source": "", "depuis": 1}}},
        "pcs": {
            "w1": _fiche("w1", "PC-LOU", ENFANT["secret"], person="person.lou", platform="windows",
                         autorises=list(autorises), bloques=list(bloques),
                         apps_vues={"robloxplayerbeta": {"premier": 1}},
                         sites_vus={"roblox.com": {"premier": 1, "adresses": ["c0.rbxcdn.com", "tr.rbxcdn.com"]}}),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    for p in ("lou", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    return coord.store.effectif(coord.store.pcs["w1"])


async def test_jeux_ferme_sans_autorisation_coupe_roblox_et_ses_serveurs(hass, hass_storage):
    eff = await _installer(hass, hass_storage)
    assert set(ROBLOX) <= set(eff["sites"])
    assert "roblox" in eff["apps"]


async def test_roblox_autorise_garde_ses_serveurs_d_images(hass, hass_storage):
    eff = await _installer(hass, hass_storage, autorises=["roblox"])
    assert "roblox" not in eff["apps"]
    assert not set(ROBLOX + ("rbx.com", "rbxinfra.com")) & set(eff["sites"]), eff["sites"]
    assert set(ROBLOX) <= set(eff["exceptions"])


async def test_un_site_bloque_nommement_reste_bloque(hass, hass_storage):
    eff = await _installer(hass, hass_storage, autorises=["roblox"], bloques=["rbx.com"])
    assert "rbx.com" in eff["sites"]
    assert "rbx.com" not in eff["exceptions"]
    assert "rbxcdn.com" not in eff["sites"]
