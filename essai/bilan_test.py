"""Bilan de la semaine (1.90.0, point 14.7).

    python -m pytest essai/bilan_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from datetime import timedelta

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from homeassistant.util import dt as dt_util
from pytest_homeassistant_custom_component.common import MockConfigEntry


def _usage(jours_secondes: dict[int, int], apps: dict[str, int] | None = None) -> dict:
    maintenant = dt_util.now()
    sortie = {}
    for decalage, s in jours_secondes.items():
        sortie[(maintenant - timedelta(days=decalage)).strftime("%Y-%m-%d")] = {"actif": s, "ouvert": s, "apps": dict(apps or {})}
    return sortie


async def _installer(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone",
                         usage={**_usage({0: 3600, 1: 3600, 2: 1800}, {"com.roblox.client": 4000, "com.google.android.youtube": 900}),
                                **_usage({8: 1800})},
                         apps_vues={"com.roblox.client": {"libelle": "Roblox"}, "com.google.android.youtube": {"libelle": "YouTube"}}),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}}
    for p in ("lou", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def test_bilan_envoye_aux_decideurs(hass, hass_storage):
    from custom_components.pc_parental import bilan, parent

    coord = await _installer(hass, hass_storage)
    lou = coord.store.pcs["e1"]
    # Une semaine de décisions : un accès refusé, 30 min ajoutées.
    lou.setdefault("demandes", []).append({"id": "d1", "genre": "apps", "cle": "com.roblox.client", "libelle": "Roblox",
                                             "ts": __import__("time").time(), "pc": "e1", "prenom": "Lou"})
    await parent.agir(hass, coord, lou, {"action": "acces", "demande": "d1", "decision": "non"}, "Nolan", "p1")
    await parent.agir(hass, coord, lou, {"action": "temps", "minutes": 30}, "Nolan", "p1")
    assert coord.store.bilan_compteurs["person.lou"] == {"refusee": 1, "minutes": 30}
    resumes = await bilan.async_envoyer(hass, coord)
    assert [r["personne"] for r in resumes] == ["person.lou"]  # pas le décideur
    texte = bilan.texte_personne(resumes[0])
    assert texte.startswith("Lou : 2 h 30 d'écran sur 7 jours (21 min par jour en moyenne), 2 h 00 de plus que la semaine d'avant.")
    assert "Le plus utilisé : Roblox (3 h 20), YouTube (45 min)." in texte
    assert "Demandes : 0 reçue, 0 accordée, 1 refusée." in texte and "Temps ajouté : 30 min." in texte
    nolan = coord.store.pcs["p1"]
    assert any(m["title"] == "Bilan de la semaine" and m["text"] == texte for m in nolan["messages"])
    assert not any(m.get("title") == "Bilan de la semaine" for m in lou["messages"])
    # Les compteurs repartent de zéro après l'envoi du dimanche.
    assert coord.store.bilan_compteurs == {}


async def test_module_coupe(hass, hass_storage):
    from custom_components.pc_parental import bilan, modules

    coord = await _installer(hass, hass_storage)
    modules.regler(hass, coord.store, set(modules.TOUS) - {"bilan"}, "essai")
    assert await bilan.async_envoyer(hass, coord) == []


async def test_action_ha(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await hass.services.async_call(DOMAIN, "bilan_hebdo", {}, blocking=True)
    assert any(m.get("title") == "Bilan de la semaine" for m in coord.store.pcs["p1"]["messages"])
