"""2.5.1 — l'écran d'une catégorie (maquette du 13-10) : chaque logiciel avec ses services et ses sites, les sites
sans logiciel, l'état de chaque ligne. Lecture seule : la lecture ne change aucun état (« On garde ce qui est
bloqué »).

    python -m pytest essai/detail_categorie_test.py -x -q -o asyncio_mode=auto
"""
from __future__ import annotations

import copy
import time

from autorisations_lot1_test import ROBLOX_SITES, _installer
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    _fiche,
    _integrations,
    expected_lingering_tasks,
    expected_lingering_timers,
)


async def _pret(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    store = coord.store
    store.etiquettes["sites"].update({f"jeu{i}.exemple": ["Jeux"] for i in range(12)})
    store._memos = {}
    return coord, store


async def test_logiciels_services_sites(hass, hass_storage):
    _, store = await _pret(hass, hass_storage)
    w1 = store.pcs["w1"]
    d = store.detail_categorie(w1, "Jeux")
    assert d["fermee"] is True
    noms = [l["nom"] for l in d["logiciels"]]
    assert noms == ["minecraft", "roblox"]
    roblox = d["logiciels"][1]
    assert roblox["ouvert"] is False and roblox["ferme_par"] == "categorie" and "mode" not in roblox
    assert "robloxplayerbeta" in [s["nom"] for s in roblox["services"]]
    assert {s["nom"] for s in roblox["sites"]} >= set(ROBLOX_SITES) - {"robloxlabs.com"}
    # Les sites de Roblox sont rangés sous lui, pas dans « Sites sans logiciel ».
    seuls = [s["nom"] for s in d["sites"]]
    assert not set(seuls) & set(ROBLOX_SITES)
    assert len(seuls) == 8 and d["autres"] == 4 + (1 if "robloxlabs.com" not in {s["nom"] for s in roblox["sites"]} else 0)


async def test_modes_visibles_sur_la_ligne(hass, hass_storage):
    _, store = await _pret(hass, hass_storage)
    w1 = store.pcs["w1"]
    store.accorder(w1, "apps", "minecraft", "exception", par="Nolan")
    w1.setdefault("laissez_passer", {})["app:roblox"] = time.time() + 1800
    store.accorder(w1, "sites", "jeu3.exemple", "toujours", par="Nolan")
    w1.setdefault("bloques", []).append("jeu5.exemple")
    store._memos = {}
    d = store.detail_categorie(w1, "Jeux", sites_max=20)
    lignes = {l["nom"]: l for l in d["logiciels"]}
    assert lignes["minecraft"]["mode"] == "exception" and lignes["minecraft"]["ouvert"]
    assert lignes["roblox"]["mode"] == "temps" and lignes["roblox"]["fin"] > time.time() and lignes["roblox"]["ouvert"]
    sites = {s["nom"]: s for s in d["sites"]}
    assert sites["jeu3.exemple"]["mode"] == "toujours" and sites["jeu3.exemple"]["ouvert"]
    assert sites["jeu5.exemple"]["ferme_par"] == "nomme"
    # Les fermés d'abord.
    assert [s["ouvert"] for s in d["sites"]] == sorted(s["ouvert"] for s in d["sites"])


async def test_la_lecture_ne_change_rien(hass, hass_storage, hass_client):
    _, store = await _pret(hass, hass_storage)
    avant = copy.deepcopy(store.pcs)
    client = await hass_client()
    r = await client.post("/api/pc_parental/maison", json={"action": "categorie", "pc": "w1", "etiquette": "Jeux"})
    assert r.status == 200
    corps = await r.json()
    assert corps["ok"] and corps["etiquette"] == "Jeux" and corps["logiciels"]
    r = await client.post("/api/pc_parental/maison", json={"action": "categorie", "pc": "w1"})
    assert r.status == 400
    r = await client.post("/api/pc_parental/maison", json={"action": "categorie", "pc": "inconnu", "etiquette": "Jeux"})
    assert r.status == 404
    assert store.pcs == avant
