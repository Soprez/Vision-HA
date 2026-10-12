"""Règles selon le lieu (1.91.0, point 14.8) : des catégories fermées tant que la personne est dans une zone.

    python -m pytest essai/regles_lieu_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


async def _installer(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone",
                         apps_vues={"com.roblox.client": {"libelle": "Roblox"}, "com.google.android.apps.docs": {"libelle": "Docs"}}),
            "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android", type="telephone",
                         apps_vues={"com.roblox.client": {"libelle": "Roblox"}}),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set("person.noa", "Collège", {"friendly_name": "Noa"})
    hass.states.async_set("zone.college", "0", {"friendly_name": "Collège"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    coord.store.definir_etiquettes("apps", "com.roblox.client", ["Jeux"])
    # « Jeux » est une étiquette neuve : Vision se recharge (voulu, __init__._etiquettes_changees). On attend la fin
    # du rechargement et on rend le NOUVEAU coordinateur : avec l'ancien, le parcours Configurer, lancé pendant le
    # rechargement, répondait « not_loaded » (échec de test_parcours_configurer élucidé le 2026-10-11).
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


def _fermees(coord, pc_id):
    coord.store._memos = {}
    return coord.store.effectif(coord.store.pcs[pc_id])


async def test_jeux_fermes_au_college(hass, hass_storage):
    from custom_components.pc_parental import regles_lieu

    coord = await _installer(hass, hass_storage)
    regles_lieu.poser(coord.store, "person.lou", "zone.college", ["Jeux"])
    # Lou est à la maison : rien ne change.
    assert "com.roblox.client" not in _fermees(coord, "e1")["apps"]
    # Lou arrive au collège : Roblox se ferme, Docs reste ; le motif dit pourquoi.
    hass.states.async_set("person.lou", "Collège", {"friendly_name": "Lou"})
    eff = _fermees(coord, "e1")
    assert "com.roblox.client" in eff["apps"] and "com.google.android.apps.docs" not in eff["apps"]
    assert eff["motifs"][eff["raisons"]["com.roblox.client"]] == "lieu|Fermée à « Collège » : règle de ce lieu, levée en le quittant."
    # Noa est aussi au collège, mais la règle est celle de Lou.
    assert "com.roblox.client" not in _fermees(coord, "e2")["apps"]
    # Il repart : tout redevient comme avant.
    hass.states.async_set("person.lou", "not_home", {"friendly_name": "Lou"})
    assert "com.roblox.client" not in _fermees(coord, "e1")["apps"]
    # Sans catégorie, la règle disparaît.
    regles_lieu.poser(coord.store, "person.lou", "zone.college", [])
    assert regles_lieu.regles(coord.store) == []


async def test_zone_maison(hass, hass_storage):
    from custom_components.pc_parental import regles_lieu

    coord = await _installer(hass, hass_storage)
    regles_lieu.poser(coord.store, "person.lou", "zone.home", ["Jeux"])
    assert "com.roblox.client" in _fermees(coord, "e1")["apps"]


async def test_parcours_configurer(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    om = hass.config_entries.options
    r = await om.async_init(coord.config_entry.entry_id)
    assert "regles_lieu" in r["menu_options"]
    r = await om.async_configure(r["flow_id"], {"next_step_id": "regles_lieu"})
    assert r["type"] == "form" and "Aucune règle" in r["description_placeholders"]["resume"]
    r = await om.async_configure(r["flow_id"], {"personne": "person.lou", "zone": "zone.college", "etiquettes": ["Jeux"]})
    assert r["type"] == "abort" and r["reason"] == "regles_lieu_ok"
    assert "Lou à « Collège » : Jeux fermée" in r["description_placeholders"]["resume"]
