"""Refonte des autorisations, lot 2 : la reprise de l'existant (CONCEPTION-autorisations.md § 6).

Simulation (rien n'est écrit), rapport, validation (« appliquer » : sauvegarde puis nouveau moteur), retour
arrière (« annuler »). Avant la reprise, l'ancien moteur décide ; une installation neuve démarre avec le nouveau.

    python -m pytest essai/reprise_autorisations_test.py -x -q -o asyncio_mode=auto
"""
from __future__ import annotations

import copy
import importlib
import sys

import pytest
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


def _module(fin):
    return next(m for n, m in sys.modules.items() if n.endswith("pc_parental." + fin))


async def _installer(hass, hass_storage, pcs=True):
    """L'existant d'une installation en service : PC-LOU et TEL-LOU (même Demandeur), « Jeux » fermé, Adulte
    coupé, Minecraft « toujours disponible », un site Adulte autorisé à tort, Roblox autorisé."""
    donnees = {"enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True}
    if pcs:
        donnees.update({
            "etiquettes": {"apps": {"roblox": ["Jeux"], "minecraft": ["Jeux"]},
                           "sites": {"exemple-adulte.org": ["Adulte"]}},
            "fermetures": {"person.lou": {"jeux|": {"etiquette": "Jeux", "raison": "Fermé par une règle.",
                                                       "source": "", "depuis": 1}}},
            "pcs": {
                "w1": _fiche("w1", "PC-LOU", ENFANT["secret"], person="person.lou", platform="windows",
                             bloques=["Adulte"], autorises=["roblox", "minecraft", "exemple-adulte.org"]),
                "t1": _fiche("t1", "TEL-LOU", "e" * 32, person="person.lou", platform="android",
                             bloques=["Adulte"], autorises=["roblox", "minecraft", "exemple-adulte.org"],
                             toujours=["minecraft"]),
                "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android",
                             role="parent"),
            }})
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    for p in ("lou", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


def _ouvert(coord, pc, nom, genre="apps"):
    coord.store._memos = {}
    return coord.store.statut(coord.store.pcs[pc], genre, nom) == "autorise"


def _plage_sur_jeux(coord):
    coord.store.plage_ferme = lambda pc, etiquette, moment=None: {"name": "Soir"} if etiquette == "Jeux" else None


async def test_installation_neuve_demarre_avec_le_nouveau_moteur(hass, hass_storage):
    coord = await _installer(hass, hass_storage, pcs=False)
    assert coord.store.autorisations_v2 is True


async def test_avant_la_reprise_l_ancien_moteur_decide(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    assert coord.store.autorisations_v2 is False
    assert _ouvert(coord, "w1", "exemple-adulte.org", "sites")   # l'ancien « autorisé » perçait Adulte
    _plage_sur_jeux(coord)
    assert not _ouvert(coord, "w1", "minecraft")                 # « toujours » ne passait pas la plage sur un PC


async def test_simuler_ne_change_rien_et_dit_ce_qui_change(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    reprise = importlib.import_module(type(coord).__module__.rsplit(".", 1)[0] + ".reprise_autorisations")
    avant = copy.deepcopy(coord.store.pcs)
    rapport = reprise.simuler(coord.store, hass)
    assert coord.store.pcs == avant and coord.store.autorisations_v2 is False
    types = {(r["type"], r["nom"]) for r in rapport["regles"]}
    assert ("toujours", "minecraft") in types
    assert ("securite", "exemple-adulte.org") in types
    assert sum(1 for r in rapport["regles"] if r["type"] == "toujours") == 1   # une fois par personne
    maintenant = {(r["appareil"], r["nom"], r["avant"], r["apres"]) for r in rapport["maintenant"]}
    assert ("PC-LOU", "exemple-adulte.org", "autorise", "bloque") in maintenant
    texte = reprise.texte(rapport, "fr")
    assert "minecraft" in texte and "exemple-adulte.org" in texte and "Adulte" in texte


async def test_appliquer_sauvegarde_puis_nouveau_moteur_puis_annuler(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    reprise = importlib.import_module(type(coord).__module__.rsplit(".", 1)[0] + ".reprise_autorisations")
    reprise.appliquer(coord.store, "Nolan", hass)
    assert coord.store.autorisations_v2 is True
    sauvegarde = coord.store.reprise_autorisations
    assert sauvegarde["par"] == "Nolan" and sauvegarde["appareils"]["w1"]["autorises"] == [
        "roblox", "minecraft", "exemple-adulte.org"]
    assert not _ouvert(coord, "w1", "exemple-adulte.org", "sites")   # Adulte n'est plus percé
    _plage_sur_jeux(coord)
    assert _ouvert(coord, "w1", "minecraft")                         # « toujours disponible » passe la plage
    assert not _ouvert(coord, "w1", "roblox")                        # l'exception suit la plage
    with pytest.raises(ValueError):
        reprise.appliquer(coord.store, "Nolan", hass)                # déjà faite
    reprise.annuler(coord.store, "Nolan")
    assert coord.store.autorisations_v2 is False
    assert not _ouvert(coord, "w1", "minecraft") and _ouvert(coord, "w1", "exemple-adulte.org", "sites")
    with pytest.raises(ValueError):
        reprise.annuler(coord.store, "Nolan")                        # rien à annuler


async def test_le_service_simule_applique_et_garde(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    r = await hass.services.async_call(DOMAIN, "reprise_autorisations", {"action": "simuler"}, blocking=True,
                                       return_response=True)
    assert r["nouveau_moteur"] is False and "minecraft" in r["texte"]
    notif = hass.states.get("persistent_notification.pc_parental_reprise_autorisations")
    assert notif is None or "minecraft" in str(notif.attributes)
    r = await hass.services.async_call(DOMAIN, "reprise_autorisations", {"action": "appliquer"}, blocking=True,
                                       return_response=True)
    assert r["nouveau_moteur"] is True
    await coord.store.async_save()
    await hass.async_block_till_done()
    data = hass_storage[DOMAIN]["data"]
    assert data["autorisations_v2"] is True and data["reprise_autorisations"]["appareils"]["t1"]["toujours"] == ["minecraft"]


async def test_oublier_retire_aussi_toujours_disponible(hass, hass_storage):
    """Avec le nouveau moteur, « Oublier » referme aussi une appli « toujours disponible »."""
    coord = await _installer(hass, hass_storage)
    coord.store.autorisations_v2 = True
    assert _ouvert(coord, "w1", "minecraft")
    parent = _module("parent")
    await parent.agir(hass, coord, coord.store.pcs["w1"],
                      {"action": "autoriser", "nom": "minecraft", "genre": "apps", "decision": "oublier"}, "Nolan", "p1")
    assert not _ouvert(coord, "w1", "minecraft") and not _ouvert(coord, "t1", "minecraft")
