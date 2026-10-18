"""Refonte des autorisations, lot 1 (CONCEPTION-autorisations.md) : le moteur de décision et ses trois modes.

- « temps » : cet appareil seulement ; passe la catégorie fermée, la plage et le temps épuisé ;
- « exception » : toutes les machines du Demandeur ; passe la catégorie fermée, suit la plage et le temps ;
- « toujours » : toutes ses machines ; rien ne le ferme, sauf un blocage explicite ;
- blocage explicite (Lieu, Adulte et autres catégories de sécurité ou d'âge, liste noire, nom bloqué exprès) :
  aucune autorisation ne le passe, et le geste le dit ;
- le Décideur à part : ce qui lui est accordé ne vaut jamais pour un Demandeur, et inversement ;
- une appli accordée ouvre ses sites (Roblox), pour tout le PC ; « Retirer » referme tout.

Plage et temps d'écran sont simulés sur le store (on vérifie l'ordre de décision, pas le calcul des heures).

    python -m pytest essai/autorisations_lot1_test.py -x -q -o asyncio_mode=auto
"""
from __future__ import annotations

import sys

import pytest
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

ROBLOX_SITES = ("roblox.com", "rbx.com", "rbxinfra.com", "rbxcdn.com", "robloxlabs.com")


def _module(fin):
    return next(m for n, m in sys.modules.items() if n.endswith("pc_parental." + fin))


async def _installer(hass, hass_storage, w1=None):
    """PC-LOU et TEL-LOU (même Demandeur), PIXEL-NOLAN (Décideur) ; « Jeux » fermé pour Lou ; Adulte coupé
    sur PC-LOU."""
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "etiquettes": {"apps": {"roblox": ["Jeux"], "minecraft": ["Jeux"]},
                       "sites": {**{s: ["Jeux"] for s in ROBLOX_SITES}, "exemple-adulte.org": ["Adulte"],
                                 "cours.exemple.org": ["Travail"]}},
        "fermetures": {"person.lou": {"jeux|": {"etiquette": "Jeux", "raison": "Fermé par une règle.", "source": "",
                                                   "depuis": 1}}},
        "pcs": {
            "w1": _fiche("w1", "PC-LOU", ENFANT["secret"], person="person.lou", platform="windows",
                         **{"bloques": ["Adulte"], **(w1 or {})}),
            "t1": _fiche("t1", "TEL-LOU", "e" * 32, person="person.lou", platform="android"),
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
    coord.store.autorisations_v2 = True  # la reprise (lot 2) est faite : le nouveau moteur s'applique
    for pc in coord.store.pcs.values():
        pc.setdefault("apps_vues", {}).update({"roblox": {"premier": 1}, "minecraft": {"premier": 1}})
    return coord


def _statut(coord, pc, genre, nom):
    coord.store._memos = {}
    return coord.store.statut(coord.store.pcs[pc], genre, nom)


def _ouvert(coord, pc, nom, genre="apps"):
    return _statut(coord, pc, genre, nom) == "autorise"


async def _accorder(hass, coord, nom, mode, pc="w1", minutes=None, genre="apps"):
    corps = {"action": "accorder", "nom": nom, "genre": genre, "mode": mode}
    if minutes:
        corps["minutes"] = minutes
    return await _module("parent").agir(hass, coord, coord.store.pcs[pc], corps, "Nolan", "p1")


def _plage_sur_jeux(coord):
    coord.store.plage_ferme = lambda pc, etiquette, moment=None: {"name": "Soir"} if etiquette == "Jeux" else None


def _temps_epuise(coord, *noms):
    coord.store.limite_atteinte = lambda pc, genre, nom: (30, 1800) if genre == "apps" and nom in noms else None


# ------------------------------------------------------------------ les trois modes


async def test_sans_autorisation_jeux_ferme(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    assert not _ouvert(coord, "w1", "roblox") and not _ouvert(coord, "t1", "roblox")


async def test_temps_sur_cet_appareil_seulement(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", "temps", minutes=15)
    assert _ouvert(coord, "w1", "roblox") and not _ouvert(coord, "t1", "roblox")


async def test_exception_et_toujours_sur_toutes_ses_machines(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", "exception")
    await _accorder(hass, coord, "minecraft", "toujours", pc="t1")
    for pc in ("w1", "t1"):
        assert _ouvert(coord, pc, "roblox") and _ouvert(coord, pc, "minecraft"), pc


async def test_plage_exception_suit_toujours_et_temps_passent(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", "exception")
    await _accorder(hass, coord, "minecraft", "toujours")
    _plage_sur_jeux(coord)
    assert not _ouvert(coord, "w1", "roblox")      # l'exception suit la plage
    assert _ouvert(coord, "w1", "minecraft")       # « toujours disponible » la passe
    await _accorder(hass, coord, "roblox", "temps", minutes=15)
    assert _ouvert(coord, "w1", "roblox")          # « pour un temps » la passe


async def test_temps_epuise_exception_suit_toujours_et_temps_passent(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", "exception")
    await _accorder(hass, coord, "minecraft", "toujours")
    _temps_epuise(coord, "roblox", "minecraft")
    assert not _ouvert(coord, "w1", "roblox") and _ouvert(coord, "w1", "minecraft")
    await _accorder(hass, coord, "roblox", "temps", minutes=15)
    assert _ouvert(coord, "w1", "roblox")


async def test_ancienne_autorisation_reste_une_exception_de_la_personne(hass, hass_storage):
    """Avant la reprise (lot 2), l'ancien « autorisé » garde son effet : les règles d'une personne sont déjà
    recopiées sur tous ses appareils (harmoniser_personnes), il vaut donc comme une exception."""
    coord = await _installer(hass, hass_storage, w1={"autorises": ["roblox"]})
    assert _ouvert(coord, "w1", "roblox") and _ouvert(coord, "t1", "roblox")
    _plage_sur_jeux(coord)
    assert not _ouvert(coord, "w1", "roblox")      # et, comme une exception, il suit la plage


# ------------------------------------------------------------------ le blocage explicite gagne toujours


@pytest.mark.parametrize("mode", ["temps", "exception", "toujours"])
async def test_adulte_ferme_aucun_mode_ne_passe(hass, hass_storage, mode):
    coord = await _installer(hass, hass_storage)
    parent = _module("parent")
    with pytest.raises(parent._Refus) as err:
        await _accorder(hass, coord, "exemple-adulte.org", mode, genre="sites", minutes=15)
    assert err.value.status == 409 and err.value.erreur == "bloque", (err.value.status, err.value.erreur)
    assert not _ouvert(coord, "w1", "exemple-adulte.org", "sites")
    # Même écrit à la main dans le store, un accord ne le rouvre pas.
    coord.store.pcs["w1"].setdefault("laissez_passer", {})["exemple-adulte.org"] = 4e9
    assert not _ouvert(coord, "w1", "exemple-adulte.org", "sites")


async def test_bloquer_d_un_decideur_cede_a_un_accord(hass, hass_storage):
    """« Bloquer » posé par un Décideur : « pour un temps » le perce sur cette machine ; une exception le lève sur
    toutes les machines du Demandeur ; « Bloquer » à nouveau retire l'autorisation (le plus récent l'emporte)."""
    coord = await _installer(hass, hass_storage, w1={"bloques": ["Adulte", "roblox"]})
    assert not _ouvert(coord, "w1", "roblox") and not _ouvert(coord, "t1", "roblox")
    await _accorder(hass, coord, "roblox", "temps", minutes=15)
    assert _ouvert(coord, "w1", "roblox") and not _ouvert(coord, "t1", "roblox")
    await _accorder(hass, coord, "roblox", "exception", pc="t1")
    assert _ouvert(coord, "t1", "roblox") and _ouvert(coord, "w1", "roblox")
    assert not any(coord.store._dans(p.get("bloques"), "roblox") for p in coord.store.pcs.values())
    await _module("parent").agir(hass, coord, coord.store.pcs["w1"],
                                 {"action": "autoriser", "nom": "roblox", "genre": "apps", "decision": "bloquer"}, "Nolan", "p1")
    assert not _ouvert(coord, "w1", "roblox")
    assert coord.store.autorisations_de(coord.store.pcs["w1"]) == []


async def test_banni_pour_tout_le_lieu(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    coord.store.bannis.append("minecraft")
    parent = _module("parent")
    with pytest.raises(parent._Refus):
        await _accorder(hass, coord, "minecraft", "toujours")
    assert not _ouvert(coord, "w1", "minecraft")


async def test_liste_noire(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    coord.store.dangereux = {"exemple-piege.net"}
    parent = _module("parent")
    with pytest.raises(parent._Refus):
        await _accorder(hass, coord, "exemple-piege.net", "toujours", genre="sites")
    eff = coord.store.effectif(coord.store.pcs["w1"])
    assert "exemple-piege.net" not in eff["exceptions"]


# ------------------------------------------------------------------ le Décideur à part


async def test_decideur_a_part(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", "toujours", pc="p1")     # le Décideur s'accorde Roblox
    assert _ouvert(coord, "p1", "roblox")
    assert not _ouvert(coord, "w1", "roblox") and not _ouvert(coord, "t1", "roblox")
    await _accorder(hass, coord, "minecraft", "exception")          # accordé au Demandeur
    assert coord.store.mode_accorde(coord.store.pcs["p1"], "apps", "minecraft") is None


# ------------------------------------------------------------------ sites liés, retrait, liste, persistance


@pytest.mark.parametrize("mode", ["temps", "exception", "toujours"])
async def test_sites_lies_pour_les_trois_modes_puis_retrait(hass, hass_storage, mode):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", mode, minutes=15)
    coord.store._memos = {}
    eff = coord.store.effectif(coord.store.pcs["w1"])
    assert "roblox" not in eff["apps"] and not set(ROBLOX_SITES) & set(eff["sites"]), eff
    assert set(ROBLOX_SITES) <= set(eff["exceptions"])
    await _module("parent").agir(hass, coord, coord.store.pcs["w1"],
                                 {"action": "retirer_autorisation", "nom": "roblox", "genre": "apps"}, "Nolan", "p1")
    coord.store._memos = {}
    eff = coord.store.effectif(coord.store.pcs["w1"])
    assert "roblox" in eff["apps"] and set(ROBLOX_SITES) <= set(eff["sites"]), eff
    assert not set(ROBLOX_SITES) & set(eff["exceptions"])


async def test_un_mode_remplace_l_autre_et_liste_des_autorisations(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", "exception")
    await _accorder(hass, coord, "roblox", "toujours", pc="t1")
    await _accorder(hass, coord, "minecraft", "temps", minutes=30)
    liste = coord.store.autorisations_de(coord.store.pcs["w1"])
    assert [(f["nom"], f["mode"]) for f in liste] == [("minecraft", "temps"), ("roblox", "toujours")], liste
    assert [(f["nom"], f["mode"]) for f in coord.store.autorisations_de(coord.store.pcs["t1"])] == [("roblox", "toujours")]


async def test_retirer_un_seul_mode(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", "exception")
    await _accorder(hass, coord, "roblox", "temps", minutes=15)
    await _module("parent").agir(hass, coord, coord.store.pcs["w1"],
                                 {"action": "retirer_autorisation", "nom": "roblox", "genre": "apps", "mode": "temps"},
                                 "Nolan", "p1")
    assert _ouvert(coord, "w1", "roblox")          # l'exception reste
    _plage_sur_jeux(coord)
    assert not _ouvert(coord, "w1", "roblox")      # et suit la plage, le temps n'est plus là


async def test_persistance(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _accorder(hass, coord, "roblox", "toujours")
    await coord.store.async_save()
    await hass.async_block_till_done()
    data = hass_storage[DOMAIN]["data"]
    assert data["autorisations_v2"] is True
    assert "roblox" in data["pcs"]["w1"]["toujours"] and "roblox" in data["pcs"]["t1"]["autorises"]


async def test_mode_inconnu_et_categorie_ecole(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    with pytest.raises(_module("parent")._Refus):
        await _accorder(hass, coord, "roblox", "pour_de_bon")
    assert "École" in coord.store.toutes_etiquettes()
