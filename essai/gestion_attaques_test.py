"""Gestion depuis l'appli : un appareil sans la fonction ★ « Gérer » ne peut RIEN faire, même avec une appli
modifiée (le serveur est la frontière de sécurité, pas l'écran de l'appli). Signalé le 2026-10-08.

Pour chaque geste de gestion (accorder du temps, ouvrir, fermer, règles, plages, limites…), un appareil Enfant
qui connaît son propre secret, et l'identifiant de la cible, se voit refuser l'action, et rien ne change.

    python -m pytest essai/gestion_attaques_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import copy

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

GESTES = [
    {"action": "temps", "minutes": 60},
    {"action": "ouvrir", "minutes": 60},
    {"action": "fermer"},
    {"action": "annuler"},
    {"action": "acces"},
    {"action": "autoriser", "nom": "com.roblox.client"},
    {"action": "etiquette", "etiquette": "Jeux", "bloquer": False},
    {"action": "partage", "personnes": ["person.noa"]},
    {"action": "attribuer", "personne": "person.noa"},
    {"action": "limite", "nom": "com.roblox.client", "minutes": 600},
    {"action": "plage", "op": "supprimer", "regle": 1},
    {"action": "logiciel", "nom": "x", "decision": "autoriser"},
    {"action": "pause_max", "minutes": 900},
    {"action": "message", "texte": "coucou"},
]


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant",
                         rules=[{"id": 1, "name": "Soir", "start": 72000, "end": 25200, "weekdays": [True] * 7, "enabled": True}]),
            "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android", role="enfant"),
            "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent"),
            "p2": _fiche("p2", "PIXEL-MIA", "c" * 32, person="person.mia", platform="android", role="parent"),
        }}}
    for p in ("lou", "noa", "robin", "mia"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return next(iter(hass.data[DOMAIN].values()))


@pytest.mark.parametrize("cible", ["e1", "e2"], ids=["sur-lui-meme", "sur-un-autre"])
async def test_un_enfant_ne_gere_rien(hass, hass_storage, hass_client, cible):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    avant = copy.deepcopy(coord.store.pcs)
    for geste in GESTES:
        r = await client.post("/api/pc_parental/parent", json={**ENFANT, "pc": cible, **geste})
        assert r.status == 403, (geste, r.status, await r.text())
        assert (await r.json())["error"] == "parent"
    # Ni la lecture de l'espace gestion.
    assert (await client.post("/api/pc_parental/parent", json=ENFANT)).status == 403
    await hass.async_block_till_done()
    apres = _actuel(hass).store.pcs
    for pc_id in avant:
        for champ in ("override", "rules", "bloques", "autorises", "limites", "person", "partage", "pause_max", "messages"):
            assert apres[pc_id].get(champ) == avant[pc_id].get(champ), (pc_id, champ)


async def test_un_enfant_ne_repond_pas_a_une_demande_de_temps(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    coord.store.pcs["e2"]["demande"] = {"id": "d1", "minutes": 30, "ts": 0}
    r = await client.post("/api/pc_parental/reponse", json={**ENFANT, "pc": "e2", "demande": "d1", "minutes": 30})
    assert r.status == 403
    assert coord.store.pcs["e2"].get("override") is None and coord.store.pcs["e2"]["demande"]


async def test_un_role_parent_sans_gerer_ne_gere_rien(hass, hass_storage, hass_client):
    """Un rôle qui garde « Approuver » mais perd « Gérer » : plus aucun geste de gestion."""
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import roles

    # Robin passe à un rôle qui approuve sans gérer (Mia garde le rôle Parent : il en faut toujours un).
    ident = roles.creer(hass, coord.store, "Approbateur", "parent", "essai")
    roles.modifier(hass, coord.store, ident, "Approbateur", ["protege", "approuver"], 60, "essai")
    roles.attribuer_appareil(hass, coord.store, "p1", ident, "essai")
    r = await client.post("/api/pc_parental/parent", json={**PARENT, "pc": "e1", "action": "temps", "minutes": 60})
    assert r.status == 403


async def test_le_parent_gere(hass, hass_storage, hass_client):
    """Témoin : avec « Gérer », le même geste passe (le test n'est pas vide de sens)."""
    client = await hass_client()
    await _monter(hass, hass_storage)
    r = await client.post("/api/pc_parental/parent", json={**PARENT, "pc": "e1", "action": "temps", "minutes": 30})
    assert r.status == 200, await r.text()


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))
