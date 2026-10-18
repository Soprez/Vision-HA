"""Mode concentration (1.88.0, point 14.5) : seules les applis choisies restent ouvertes.

    python -m pytest essai/concentration_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

VUES = {n: {"premier": 1, "secondes": s, "libelle": lib} for n, s, lib in (
    ("com.google.android.youtube", 900, "YouTube"), ("com.roblox.client", 1200, "Roblox"),
    ("com.google.android.apps.docs", 60, "Docs"), ("com.google.android.dialer", 30, "Téléphone"),
    ("org.soprez.vision", 10, "Vision"),
)}


async def _installer(hass, hass_storage, modules=None):
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone",
                         apps_vues=dict(VUES)),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}
    if modules is not None:
        donnees["modules"] = modules
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    for p in ("lou", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


def _fermees(coord, pc_id="e1"):
    eff = coord.store.effectif(coord.store.pcs[pc_id])
    return set(eff["apps"]), eff


async def test_seules_les_applis_choisies_restent(hass, hass_storage):
    from custom_components.pc_parental import concentration

    coord = await _installer(hass, hass_storage)
    pc = coord.store.pcs["e1"]
    avant, _ = _fermees(coord)
    assert "com.google.android.youtube" not in avant
    concentration.demarrer(coord.store, pc, 30, ["com.google.android.apps.docs"], "Nolan")
    coord.store._memos = {}
    fermees, eff = _fermees(coord)
    assert {"com.google.android.youtube", "com.roblox.client"} <= fermees
    # Gardées : l'appli choisie, et les essentielles (téléphone, Vision).
    assert not fermees & {"com.google.android.apps.docs", "com.google.android.dialer", "org.soprez.vision"}
    motif = eff["motifs"][eff["raisons"]["com.roblox.client"]]
    assert motif.startswith("concentration|Mode concentration jusqu'à")
    # Le dernier choix est retenu pour la personne.
    assert coord.store.concentration_choix["person.lou"] == ["com.google.android.apps.docs"]
    # Terminée (heure passée) : plus rien de fermé pour elle.
    pc["concentration"]["fin"] = time.time() - 1
    coord.store._memos = {}
    fermees, _ = _fermees(coord)
    assert "com.roblox.client" not in fermees


async def test_module_coupe(hass, hass_storage):
    from custom_components.pc_parental import concentration

    coord = await _installer(hass, hass_storage, modules={"concentration": False})
    concentration.demarrer(coord.store, coord.store.pcs["e1"], 30, [], "Nolan")
    coord.store._memos = {}
    fermees, _ = _fermees(coord)
    assert "com.roblox.client" not in fermees


async def test_geste_du_decideur(hass, hass_storage):
    from custom_components.pc_parental import parent

    coord = await _installer(hass, hass_storage)
    cible = coord.store.pcs["e1"]
    phrase = await parent.agir(hass, coord, cible, {"action": "concentration", "minutes": 45,
                                                  "apps": ["com.google.android.apps.docs"]}, "Nolan", "p1")
    assert "concentration sur TEL-LOU jusqu'à" in phrase and "1 appli gardée" in phrase
    coord.store._memos = {}
    fiche = parent._fiche(hass, coord, cible, ["p1"])
    assert fiche["concentration"]["fin"] > time.time()
    assert fiche["concentration"]["apps"] == ["com.google.android.apps.docs"]
    noms_proposes = [a["nom"] for a in fiche["concentration"]["proposees"]]
    assert noms_proposes[0] == "com.roblox.client" and "org.soprez.vision" not in noms_proposes
    # Les applis fermées par la concentration ne remplissent pas la liste des applis fermées une par une.
    assert all(a["nom"] != "com.roblox.client" for a in fiche["apps"])
    assert any("Concentration jusqu'à" in m.get("text", m.get("texte", "")) for m in cible["messages"])
    phrase = await parent.agir(hass, coord, cible, {"action": "concentration", "fin": True}, "Nolan", "p1")
    assert "a arrêté la concentration" in phrase and "concentration" not in cible


async def test_action_ha(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    coord.store.concentration_choix = {"person.lou": ["com.google.android.apps.docs"]}
    await hass.services.async_call(DOMAIN, "concentration", {"personne": "person.lou", "minutes": 20}, blocking=True)
    c = coord.store.pcs["e1"]["concentration"]
    assert c["apps"] == ["com.google.android.apps.docs"] and 19 * 60 < c["fin"] - time.time() <= 20 * 60
    await hass.services.async_call(DOMAIN, "concentration", {"personne": "person.lou", "arreter": True}, blocking=True)
    assert "concentration" not in coord.store.pcs["e1"]
