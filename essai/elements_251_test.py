"""2.5.1 — les autorisations par élément (réponses de l'auteur du 14-10 sur la maquette).

- « Suivre la catégorie » : le mode par défaut, qui retire le réglage propre d'un élément ;
- un service ou un site rangé sous un logiciel suit le logiciel ;
- un site vu pendant qu'un logiciel tourne lui est lié tout seul, avec les garde-fous (ni Adulte, Dangereux,
  Contournement, Pub & traçage, ni l'infrastructure commune, ni par un navigateur) ; le Décideur peut délier ;
- « On garde ce qui est bloqué » : un lien automatique ne débloque rien ;
- la recherche retrouve un élément où qu'il soit rangé, et dit pourquoi il est fermé ;
- les blocages récents (Décideur et Demandeur) et la demande groupée par logiciel, acceptée en bloc ou élément par
  élément.

    python -m pytest essai/elements_251_test.py -x -q -o asyncio_mode=auto
"""
from __future__ import annotations

import sys
import time

from autorisations_lot1_test import ROBLOX_SITES, _installer
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    _fiche,
    _integrations,
    expected_lingering_tasks,
    expected_lingering_timers,
)


def _module(fin):
    return next(m for n, m in sys.modules.items() if n.endswith("pc_parental." + fin))


async def _pret(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    store = coord.store
    store.etiquettes["sites"].update({"pistage.exemple": ["Pub & traçage"], "rbxnouveau.com": ["Jeux"]})
    w1 = store.pcs["w1"]
    w1["apps_vues"]["discord"] = {"premier": 1}
    w1["sites_vus"].update({
        "discordcdn2.net": {"premier": 1, "origines": {"discord": {"fois": 2, "dernier": 1}}},
        "pistage.exemple": {"premier": 1, "origines": {"discord": {"fois": 1, "dernier": 1}}},
        "cloudfront.net": {"premier": 1, "origines": {"discord": {"fois": 1, "dernier": 1}}},
        "journal.exemple": {"premier": 1, "origines": {"chrome": {"fois": 9, "dernier": 1}}},
    })
    store._memos = {}
    return coord, store, w1


async def _agir(hass, coord, corps):
    return await _module("parent").agir(hass, coord, coord.store.pcs["w1"], corps, "Nolan", "p1")


def _ouvert(store, pc, genre, nom):
    store._memos = {}
    return store.statut(pc, genre, nom) == "autorise"


# ------------------------------------------------------------------ liens automatiques


async def test_lien_automatique_et_garde_fous(hass, hass_storage):
    _, store, w1 = await _pret(hass, hass_storage)
    assert store.lier_auto(w1) >= 1
    assert "discordcdn2.net" in store.dependances("discord")
    lies = {s["nom"]: s for s in store.sites_du_logiciel("discord")}
    assert lies["discordcdn2.net"].get("auto") and not lies["discordcdn2.net"].get("attente")
    for interdit in ("pistage.exemple", "cloudfront.net"):
        assert interdit not in lies
    # Un navigateur parle à tous les sites : il ne lie rien.
    assert "chrome" not in (store.domaines_auto or {})
    assert store.famille_premier_plan("chrome.exe") == "" and store.famille_premier_plan("RobloxPlayerBeta.exe") == "roblox"


async def test_un_lien_automatique_ne_debloque_rien(hass, hass_storage):
    _, store, w1 = await _pret(hass, hass_storage)
    store.noter_alerte(w1, "rbxnouveau.com", "regle", "roblox")
    assert not _ouvert(store, w1, "sites", "rbxnouveau.com")
    store.lier_auto(w1)
    fiche = store.domaines_auto["roblox"]["rbxnouveau.com"]
    assert fiche["suit"] is False
    assert "rbxnouveau.com" not in store.dependances("roblox")
    assert {"nom": "rbxnouveau.com", "auto": True, "attente": True} in store.sites_du_logiciel("roblox")
    # Même Roblox accordé, le site en attente reste fermé : seul un geste du Décideur le fait suivre.
    store.accorder(w1, "apps", "roblox", "exception")
    store._memos = {}
    assert "rbxnouveau.com" not in store.effectif(w1)["exceptions"]
    store.lier_domaine("roblox", "rbxnouveau.com", True)
    store._memos = {}
    assert "rbxnouveau.com" in store.effectif(w1)["exceptions"]


async def test_delier_a_la_main(hass, hass_storage):
    _, store, w1 = await _pret(hass, hass_storage)
    store.lier_auto(w1)
    assert store.lier_domaine("discord", "discordcdn2.net", False)
    store.lier_auto(w1)
    assert "discordcdn2.net" not in store.dependances("discord")
    # Un site connu de Vision se délie aussi, et un nouveau lien le rétablit.
    assert store.lier_domaine("discord", "discord.gg", False)
    assert "discord.gg" not in store.dependances("discord")
    assert store.lier_domaine("discord", "discord.gg", True)
    assert "discord.gg" in store.dependances("discord")


# ------------------------------------------------------------------ choix du mode d'un élément


async def test_suivre_la_categorie_et_bloquer(hass, hass_storage):
    coord, store, w1 = await _pret(hass, hass_storage)
    await _agir(hass, coord, {"action": "accorder", "nom": "minecraft", "genre": "apps", "mode": "exception"})
    assert store.mode_accorde(w1, "apps", "minecraft") == "exception"
    await _agir(hass, coord, {"action": "accorder", "nom": "minecraft", "genre": "apps", "mode": "categorie"})
    assert store.mode_accorde(w1, "apps", "minecraft") is None
    assert not _ouvert(store, w1, "apps", "minecraft")  # « Jeux » est fermé : il suit sa catégorie
    await _agir(hass, coord, {"action": "accorder", "nom": "exemple.org", "genre": "sites", "mode": "bloquer"})
    assert store.etat_element(w1, "sites", "exemple.org")["mode"] == "bloquer"
    assert store.ferme_par(w1, "sites", "exemple.org") == "nomme"
    retour = await _agir(hass, coord, {"action": "accorder", "nom": "exemple.org", "genre": "sites", "mode": "categorie"})
    assert "exemple.org" in retour and _ouvert(store, w1, "sites", "exemple.org")
    retour = await _agir(hass, coord, {"action": "accorder", "nom": "exemple.org", "genre": "sites", "mode": "categorie"})
    assert "exemple.org" in retour


async def test_les_sites_et_services_suivent_le_logiciel(hass, hass_storage):
    _, store, w1 = await _pret(hass, hass_storage)
    d = store.detail_categorie(w1, "Jeux", langue="fr")
    roblox = next(x for x in d["logiciels"] if x["nom"] == "roblox")
    assert all(s["suit"] for s in roblox["services"])
    assert all(s.get("suit") and not s["ouvert"] for s in roblox["sites"] if s["nom"] in ROBLOX_SITES)
    assert roblox["raison"]
    store.accorder(w1, "apps", "roblox", "exception")
    store._memos = {}
    d = store.detail_categorie(w1, "Jeux")
    roblox = next(x for x in d["logiciels"] if x["nom"] == "roblox")
    assert roblox["mode"] == "exception"
    assert all(s["ouvert"] and s.get("par_logiciel") for s in roblox["sites"] if s["nom"] in ROBLOX_SITES)


# ------------------------------------------------------------------ recherche


async def test_recherche_ou_que_ce_soit(hass, hass_storage):
    _, store, w1 = await _pret(hass, hass_storage)
    r = store.rechercher(w1, "rbxcdn", "fr")
    assert r[0]["nom"] == "rbxcdn.com" and r[0]["logiciel"] == "roblox" and r[0]["ferme_par"] == "categorie"
    assert r[0]["raison"] and "Jeux" in r[0]["categories"]
    r = store.rechercher(w1, "robloxplayer", "fr")
    assert r[0]["genre"] == "apps" and r[0]["nom"] == "roblox" and r[0]["service"].startswith("robloxplayer")
    assert any(x["genre"] == "categorie" and x["nom"] == "Jeux" and x["fermee"] for x in store.rechercher(w1, "jeu"))
    assert any(x["nom"] == "exemple-adulte.org" and x["ferme_par"] == "securite" for x in store.rechercher(w1, "adulte"))
    assert store.rechercher(w1, "x") == []


# ------------------------------------------------------------------ blocages récents, demande groupée


async def test_blocages_recents_et_groupes(hass, hass_storage):
    _, store, w1 = await _pret(hass, hass_storage)
    store.noter_alerte(w1, "rbxcdn.com", "regle", "roblox")
    store.noter_alerte(w1, "rbxcdn.com", "regle", "roblox")
    store.noter_alerte(store.pcs["t1"], "rbx.com", "regle")
    store.noter_alerte(w1, "exemple-adulte.org", "regle")
    tout = store.blocages_recents(w1, "fr")
    par_nom = {x["nom"]: x for x in tout}
    assert par_nom["rbxcdn.com"]["fois"] == 2 and par_nom["rbxcdn.com"]["logiciel"] == "roblox"
    assert par_nom["rbx.com"]["appareil_nom"] == "TEL-LOU" and par_nom["rbx.com"]["logiciel"] == "roblox"
    assert par_nom["exemple-adulte.org"]["demandable"] is False
    demandeur = {x["nom"] for x in store.blocages_recents(w1, "fr", pour_demandeur=True)}
    assert "exemple-adulte.org" not in demandeur and {"rbxcdn.com", "rbx.com"} <= demandeur
    groupes = store.groupes_a_demander(w1, "fr")
    assert groupes[0]["logiciel"] == "roblox" and set(groupes[0]["sites"]) == {"rbxcdn.com", "rbx.com"}


async def test_demande_groupee_par_element(hass, hass_storage):
    coord, store, w1 = await _pret(hass, hass_storage)
    dem = _module("demandes")
    d = dem.deposer(hass, coord, w1, "apps", "roblox", "Roblox", sites=["rbxcdn.com", "exemple-adulte.org", "inconnu.com"])
    assert d["sites"] == ["rbxcdn.com"]
    await _agir(hass, coord, {"action": "acces", "demande": d["id"], "decision": "accorder", "mode": "temps",
                              "minutes": 15, "elements": ["rbxcdn.com"]})
    assert _ouvert(store, w1, "sites", "rbxcdn.com") and not _ouvert(store, w1, "apps", "roblox")


async def test_demande_groupee_en_bloc(hass, hass_storage):
    coord, store, w1 = await _pret(hass, hass_storage)
    store.noter_alerte(w1, "rbxnouveau.com", "regle", "roblox")
    store.lier_auto(w1)
    dem = _module("demandes")
    d = dem.deposer(hass, coord, w1, "apps", "roblox", "Roblox", sites=["rbxnouveau.com", "rbxcdn.com"])
    assert set(d["sites"]) == {"rbxnouveau.com", "rbxcdn.com"}
    retour = await _agir(hass, coord, {"action": "acces", "demande": d["id"], "decision": "accorder", "mode": "exception"})
    assert "2" in retour
    store._memos = {}
    assert _ouvert(store, w1, "apps", "roblox")
    assert {"rbxnouveau.com", "rbxcdn.com"} <= set(store.effectif(w1)["exceptions"])
    assert store.domaines_auto["roblox"]["rbxnouveau.com"]["suit"] is True


async def test_lectures_par_le_point_d_acces(hass, hass_storage, hass_client):
    _, store, w1 = await _pret(hass, hass_storage)
    store.noter_alerte(w1, "rbxcdn.com", "regle", "roblox")
    client = await hass_client()
    for corps, cle in (({"action": "recherche", "texte": "rbx"}, "resultats"), ({"action": "recents"}, "recents"),
                       ({"action": "categorie", "etiquette": "Jeux", "sites_max": 2}, "logiciels")):
        r = await client.post("/api/pc_parental/maison", json={"pc": "w1", **corps})
        assert r.status == 200, corps
        assert (await r.json())[cle]
    assert time.time() > 0


async def test_releve_de_l_agent_lie_et_renvoie_les_blocages(hass, hass_storage, hass_client):
    from gestion_attaques_test import ENFANT, PARENT

    _, store, w1 = await _pret(hass, hass_storage)
    client = await hass_client()
    ident = {"id": "w1", "secret": ENFANT["secret"], "version": "1", "host": "x", "platform": "windows"}
    r = await client.post("/api/pc_parental/poll", json={
        **ident, "focus": "RobloxPlayerBeta.exe",
        "alertes": [{"domaine": "c3.rbxnouveau.com", "source": "regle"}, {"domaine": "rbxcdn.com", "source": "regle"}]})
    assert r.status == 200, await r.text()
    corps = await r.json()
    # Le site bloqué pendant que Roblox tournait lui est lié, sans être ouvert.
    assert store.domaines_auto["roblox"]["rbxnouveau.com"]["suit"] is False
    assert not _ouvert(store, w1, "sites", "rbxnouveau.com")
    assert {"rbxnouveau.com", "rbxcdn.com"} <= {b["nom"] for b in corps["bloques_recents"]}
    assert corps["groupes"][0]["logiciel"] == "roblox"
    # L'appli d'un Décideur : recherche et blocages récents, sur le même point d'accès que ses gestes.
    r = await client.post("/api/pc_parental/parent", json={"id": "p1", "secret": PARENT["secret"], "pc": "w1",
                                                           "action": "recherche", "texte": "rbxnouveau"})
    assert r.status == 200, await r.text()
    assert (await r.json())["resultats"][0]["logiciel"] == "roblox"
