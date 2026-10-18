"""1.93.06 (sécurité, ETUDE-optimisation-pc faille 4) : l'inventaire des logiciels des PC n'est lisible que par un
administrateur de Home Assistant.

    python -m pytest essai/processus_admin_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


async def _installer(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "c1": _fiche("c1", "PC-LOU", ENFANT["secret"], person="person.lou"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def test_catalogue_reserve_aux_administrateurs(hass, hass_storage, hass_client, hass_read_only_user):
    coord = await _installer(hass, hass_storage)
    # Un logiciel relevé sur le PC de l'enfant.
    coord.processus.catalogue.noter("c1", [{"g": "processus", "id": "discord", "nom": "Discord", "exe": "discord",
                                             "chemin": r"C:\Users\lou\AppData\Local\Discord\Discord.exe",
                                             "signataire": "Discord Inc.", "editeur": "Discord Inc."}], 1000.0)
    admin = await hass_client()
    r = await admin.get("/api/pc_parental/processus")
    assert r.status == 200
    assert "Discord" in await r.text()
    # Un compte connecté mais non administrateur (celui d'un enfant, par exemple) : refusé, sans rien de l'inventaire.
    jeton = hass.auth.async_create_access_token(await hass.auth.async_create_refresh_token(hass_read_only_user, "https://exemple.invalid/"))
    lecteur = await hass_client(jeton)
    r = await lecteur.get("/api/pc_parental/processus")
    assert r.status == 403
    texte = await r.text()
    assert "Discord" not in texte and '"admin"' in texte
