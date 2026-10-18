"""Double autorisation (2.4.4) : une appli autorisée et ses sites liés, et des accords qui se chevauchent sur un PC.

Règles (CONCEPTION-autorisations.md § 2 et § 5, règle du projet du 2026-10-12) :
- autoriser une appli (pour un temps, en exception ou « toujours disponible ») ouvre aussi les sites dont elle a
  besoin (Roblox : roblox.com, rbxcdn.com…), pour tout le PC, et les referme quand l'accord finit ;
- un site lié bloqué nommément reste fermé ;
- « pour un temps » ne vaut que sur la machine d'où vient l'accord ;
- la fin d'un accord ne coupe jamais un autre accord, et rien ne reste ouvert par erreur.

Les gestes passent par parent.agir, comme depuis l'appli d'un Décideur ou le tableau de bord. L'échéance d'un
« pour un temps » est simulée en reculant son heure de fin.

    python -m pytest essai/double_autorisation_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import sys
import time

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

ROBLOX_SITES = ("roblox.com", "rbx.com", "rbxinfra.com", "rbxcdn.com", "robloxlabs.com")
MINECRAFT_SITES = ("minecraft.net", "mojang.com", "minecraftservices.com")
ENFANT2 = {"id": "w2", "secret": "d" * 32}


def _gestes():
    return next(m for n, m in sys.modules.items() if n.endswith("pc_parental.parent"))


async def _installer(hass, hass_storage, **w1):
    """PC-LOU (Windows) et PC-LOU-2 (même personne) ; « Jeux » fermé pour Lou par une automatisation."""
    jeux_apps = ("roblox", "robloxplayerbeta", "minecraft", "minecraft.windows", "steam")
    jeux_sites = ROBLOX_SITES + MINECRAFT_SITES + ("steampowered.com",)
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "etiquettes": {"apps": {a: ["Jeux"] for a in jeux_apps}, "sites": {s: ["Jeux"] for s in jeux_sites}},
        "fermetures": {"person.lou": {"jeux|": {"etiquette": "Jeux", "raison": "Fermé par une règle.",
                                                   "source": "", "depuis": 1}}},
        "pcs": {
            "w1": _fiche("w1", "PC-LOU", ENFANT["secret"], person="person.lou", platform="windows",
                         apps_vues={"robloxplayerbeta": {"premier": 1}, "minecraft.windows": {"premier": 1}},
                         sites_vus={"roblox.com": {"premier": 1}, "minecraft.net": {"premier": 1}}, **w1),
            "w2": _fiche("w2", "PC-LOU-2", ENFANT2["secret"], person="person.lou", platform="windows"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    for p in ("lou", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def _geste(hass, coord, decision, nom, minutes=None, pc="w1"):
    corps = {"action": "autoriser", "nom": nom, "genre": "apps", "decision": decision}
    if minutes:
        corps["minutes"] = minutes
    return await _gestes().agir(hass, coord, coord.store.pcs[pc], corps, "Nolan", "p1")


def _eff(coord, pc="w1"):
    coord.store._memos = {}  # le calcul est gardé 5 s : on veut l'état de cet instant
    return coord.store.effectif(coord.store.pcs[pc])


def _echeance(coord, cle, pc="w1"):
    """Le « pour un temps » `cle` arrive à son terme."""
    coord.store.pcs[pc]["laissez_passer"][cle] = time.time() - 1


def _ouvert(eff, app, sites):
    return app not in eff["apps"] and not set(sites) & set(eff["sites"]) and set(sites) <= set(eff["exceptions"])


def _ferme(eff, app, sites):
    return app in eff["apps"] and set(sites) <= set(eff["sites"]) and not set(sites) & set(eff["exceptions"])


# ------------------------------------------------------------------ 1. Appli + sites liés


async def test_1a_pour_un_temps_ouvre_l_appli_et_ses_sites_puis_les_referme(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    assert _ferme(_eff(coord), "roblox", ROBLOX_SITES)
    await _geste(hass, coord, "temporaire", "roblox", 30)
    assert _ouvert(_eff(coord), "roblox", ROBLOX_SITES), _eff(coord)
    _echeance(coord, "app:roblox")
    assert _ferme(_eff(coord), "roblox", ROBLOX_SITES), _eff(coord)


async def test_1a_demande_acceptee_pour_un_temps(hass, hass_storage):
    """Le même cas par le chemin d'une vraie demande : le Demandeur demande Roblox, le Décideur accorde 1 h."""
    coord = await _installer(hass, hass_storage)
    demandes = next(m for n, m in sys.modules.items() if n.endswith("pc_parental.demandes"))
    pc = coord.store.pcs["w1"]
    d = demandes.deposer(hass, coord, pc, "apps", "robloxplayerbeta", "Roblox", duree="1h")
    assert d and "id" in d, d
    await _gestes().agir(hass, coord, pc, {"action": "acces", "demande": d["id"], "decision": "temporaire", "minutes": 60},
                         "Nolan", "p1")
    assert _ouvert(_eff(coord), "roblox", ROBLOX_SITES), _eff(coord)


async def test_1b_exception_ouvre_puis_oublier_referme(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "toujours", "roblox")
    assert _ouvert(_eff(coord), "roblox", ROBLOX_SITES)
    await _geste(hass, coord, "oublier", "roblox")
    assert _ferme(_eff(coord), "roblox", ROBLOX_SITES), _eff(coord)


async def test_1c_toujours_disponible_ouvre_puis_oublier_referme(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "disponible", "roblox")
    assert _ouvert(_eff(coord), "roblox", ROBLOX_SITES)
    await _geste(hass, coord, "oublier", "roblox")
    assert _ferme(_eff(coord), "roblox", ROBLOX_SITES), _eff(coord)


async def test_1d_site_lie_bloque_nommement_reste_ferme_pendant_un_temps(hass, hass_storage):
    coord = await _installer(hass, hass_storage, bloques=["rbx.com"])
    await _geste(hass, coord, "temporaire", "roblox", 30)
    eff = _eff(coord)
    assert "roblox" not in eff["apps"] and "rbx.com" in eff["sites"] and "rbx.com" not in eff["exceptions"]
    assert "rbxcdn.com" not in eff["sites"]


async def test_1e_pour_un_temps_ne_vaut_que_sur_ce_pc(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "temporaire", "roblox", 30)
    assert _ouvert(_eff(coord), "roblox", ROBLOX_SITES)
    assert _ferme(_eff(coord, "w2"), "roblox", ROBLOX_SITES), _eff(coord, "w2")


# ------------------------------------------------------------------ 3. Accords qui se chevauchent sur un PC


async def test_3a_temps_et_exception(hass, hass_storage):
    """Roblox pour un temps, Minecraft en exception : la fin du temps ne referme que Roblox."""
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "toujours", "minecraft")
    await _geste(hass, coord, "temporaire", "roblox", 30)
    eff = _eff(coord)
    assert _ouvert(eff, "roblox", ROBLOX_SITES) and _ouvert(eff, "minecraft", MINECRAFT_SITES), eff
    _echeance(coord, "app:roblox")
    eff = _eff(coord)
    assert _ferme(eff, "roblox", ROBLOX_SITES) and _ouvert(eff, "minecraft", MINECRAFT_SITES), eff


async def test_3a_temps_et_exception_sur_la_meme_appli(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "toujours", "roblox")
    await _geste(hass, coord, "temporaire", "roblox", 30)
    _echeance(coord, "app:roblox")
    assert _ouvert(_eff(coord), "roblox", ROBLOX_SITES), _eff(coord)


async def test_3b_deux_temps_sur_deux_applis(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "temporaire", "roblox", 30)
    await _geste(hass, coord, "temporaire", "minecraft", 60)
    _echeance(coord, "app:roblox")
    eff = _eff(coord)
    assert _ferme(eff, "roblox", ROBLOX_SITES) and _ouvert(eff, "minecraft", MINECRAFT_SITES), eff
    _echeance(coord, "app:minecraft")
    eff = _eff(coord)
    assert _ferme(eff, "roblox", ROBLOX_SITES) and _ferme(eff, "minecraft", MINECRAFT_SITES), eff


async def test_3b_un_second_temps_plus_court_ne_raccourcit_pas_le_premier(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "temporaire", "roblox", 120)
    fin_longue = coord.store.pcs["w1"]["laissez_passer"]["app:roblox"]
    await _geste(hass, coord, "temporaire", "roblox", 15)
    assert coord.store.pcs["w1"]["laissez_passer"]["app:roblox"] >= fin_longue
    # Et un second temps plus long prolonge.
    await _geste(hass, coord, "temporaire", "roblox", 240)
    assert coord.store.pcs["w1"]["laissez_passer"]["app:roblox"] > fin_longue + 60 * 60


async def test_3b_temps_d_appli_et_temps_de_site_lie(hass, hass_storage):
    """Roblox pour un temps et roblox.com pour un temps : la fin de l'un laisse l'autre ouvert, puis tout se referme."""
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "temporaire", "roblox", 30)
    await _gestes().agir(hass, coord, coord.store.pcs["w1"],
                         {"action": "autoriser", "nom": "roblox.com", "genre": "sites", "decision": "temporaire", "minutes": 60},
                         "Nolan", "p1")
    _echeance(coord, "app:roblox")
    eff = _eff(coord)
    assert "roblox" in eff["apps"] and "roblox.com" not in eff["sites"] and "rbx.com" in eff["sites"], eff
    _echeance(coord, "roblox.com")
    assert _ferme(_eff(coord), "roblox", ROBLOX_SITES), _eff(coord)


async def test_3c_exception_et_categorie_fermee(hass, hass_storage):
    """Minecraft en exception sous « Jeux » fermé ; la catégorie se rouvre puis se referme : l'exception tient,
    et le reste de la catégorie se referme."""
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "toujours", "minecraft")
    eff = _eff(coord)
    assert _ouvert(eff, "minecraft", MINECRAFT_SITES) and _ferme(eff, "roblox", ROBLOX_SITES), eff
    coord.store.ouvrir_categorie("person.lou", "Jeux", "")
    eff = _eff(coord)
    assert "roblox" not in eff["apps"] and "minecraft" not in eff["apps"], eff
    coord.store.fermer_categorie("person.lou", "Jeux", "Fermé par une règle.", "")
    eff = _eff(coord)
    assert _ouvert(eff, "minecraft", MINECRAFT_SITES) and _ferme(eff, "roblox", ROBLOX_SITES), eff


async def test_3d_toujours_disponible_et_fin_d_un_temps(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "disponible", "minecraft")
    await _geste(hass, coord, "temporaire", "minecraft", 30)
    await _geste(hass, coord, "temporaire", "roblox", 30)
    _echeance(coord, "app:minecraft")
    _echeance(coord, "app:roblox")
    eff = _eff(coord)
    assert _ouvert(eff, "minecraft", MINECRAFT_SITES) and _ferme(eff, "roblox", ROBLOX_SITES), eff


async def test_3e_rien_ne_reste_ouvert_apres_tous_les_accords(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    await _geste(hass, coord, "temporaire", "roblox", 30)
    await _geste(hass, coord, "toujours", "minecraft")
    await _geste(hass, coord, "temporaire", "steam", 30)
    _echeance(coord, "app:roblox")
    _echeance(coord, "app:steam")
    await _geste(hass, coord, "oublier", "minecraft")
    eff = _eff(coord)
    assert _ferme(eff, "roblox", ROBLOX_SITES) and _ferme(eff, "minecraft", MINECRAFT_SITES), eff
    assert "steam" in eff["apps"] and "steampowered.com" in eff["sites"], eff
    assert not coord.store.laissez_passer_actifs(coord.store.pcs["w1"])
