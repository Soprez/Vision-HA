"""1.94 · Autorisations refondues (spec §0 ter) : la lecture pour la carte, les gestes signés, le journal « qui a fait quoi »,
le code réservé aux comptes marqués partagés.

    python -m pytest essai/autorisations_refonte_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from autorisations_lot1_test import ROBLOX_SITES, _accorder, _installer  # noqa: F401
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    _integrations,
    expected_lingering_tasks,
    expected_lingering_timers,
)
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN, Credentials
from octroi_test import CODE, _poser_code

AUT = "/api/pc_parental/autorisations"
GESTE = "/api/pc_parental/autorisations/geste"


async def _pret(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    store = coord.store
    for pc in ("w1", "t1"):
        store.pcs[pc]["apps_vues"]["roblox"] = {"premier": 1}
    store.pcs["w1"]["sites_vus"].update({"coolmath.exemple": {"premier": 1}, "wikipedia.exemple": {"premier": 1}})
    store.etiquettes["sites"]["coolmath.exemple"] = ["Jeux"]
    store._memos = {}
    return coord, store


async def _compte(hass, hass_client, nom, groupe="system-admin", connexion=True):
    u = await hass.auth.async_create_user(nom, group_ids=[groupe])
    if connexion:
        await hass.auth.async_link_user(u, Credentials(auth_provider_type="homeassistant", auth_provider_id=None,
                                                       data={"username": nom.lower()}, is_new=False))
    jeton = await hass.auth.async_create_refresh_token(u, client_name="essai", token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN)
    return u, await hass_client(hass.auth.async_create_access_token(jeton))


def _appli(d, nom):
    return next(a for a in d["applis"] if a["nom"] == nom)


# ------------------------------------------------------------------ la lecture


async def test_lecture_rouge_puis_vert_avec_portee_ecrite(hass, hass_storage, hass_client):
    coord, store = await _pret(hass, hass_storage)
    _u, client = await _compte(hass, hass_client, "Gestion")
    d = await (await client.get(AUT)).json()
    assert d["ok"] and d["regarde"] == "Pour tout le monde"
    # Tout le monde : l'appareil d'un décideur n'est pas fermé, Roblox n'est donc que « partiellement bloqué ».
    assert _appli(d, "roblox")["badge"] == "orange"
    d = await (await client.get(AUT + "?personne=person.lou")).json()
    r = _appli(d, "roblox")
    assert r["badge"] == "rouge" and r["raison"] and r["portee"] and "Bloqué" in r["portee"]
    assert d["resume"]["rouge"] >= 1
    # Accordé sur toutes les machines de Lou : vert, portée « ouvert ».
    await _accorder(hass, coord, "roblox", "exception")
    d = await (await client.get(AUT + "?personne=person.lou")).json()
    assert d["regarde"] == "Pour Lou"
    r = _appli(d, "roblox")
    assert r["badge"] == "vert" and r["portee"] == "Ouvert pour Lou" and set(r["ouvert_sur"]) == {"w1", "t1"}
    # Un seul appareil regardé : l'appareil est écrit en tête.
    d = await (await client.get(AUT + "?appareil=t1")).json()
    assert d["regarde"] == "Sur TEL-LOU"
    assert (await client.get(AUT + "?appareil=inconnu")).status == 404


async def test_orange_quand_ouvert_sur_un_appareil_seulement_et_quand_un_site_lie_reste_ferme(hass, hass_storage, hass_client):
    coord, store = await _pret(hass, hass_storage)
    _u, client = await _compte(hass, hass_client, "Gestion")
    await _accorder(hass, coord, "roblox", "temps", minutes=30)       # « pour un temps » : sur w1 seulement
    d = await (await client.get(AUT + "?personne=person.lou")).json()
    r = _appli(d, "roblox")
    assert r["badge"] == "orange" and r["portee"] == "Ouvert pour Lou, fermé sur TEL-LOU"
    assert r["ouvert_sur"] == ["w1"] and r["ferme_sur"] == ["t1"]
    # Ouvert partout, mais un site lié (en attente) reste fermé : orange aussi.
    await _accorder(hass, coord, "roblox", "exception")
    store.etiquettes["sites"]["rbxnouveau.com"] = ["Jeux"]
    store.noter_alerte(store.pcs["w1"], "rbxnouveau.com", "regle", "roblox")
    store.lier_auto(store.pcs["w1"])
    d = await (await client.get(AUT + "?personne=person.lou")).json()
    r = _appli(d, "roblox")
    assert r["badge"] == "orange"
    site = next(s for s in r["sites"] if s["domaine"] == "rbxnouveau.com")
    assert site["badge"] != "vert" and site["lien"] == "secours" and site.get("attente")


async def test_le_lien_observe_porte_sa_preuve_et_les_sites_seuls_sont_a_part(hass, hass_storage, hass_client):
    coord, store = await _pret(hass, hass_storage)
    _u, client = await _compte(hass, hass_client, "Gestion")
    w1 = store.pcs["w1"]
    from custom_components.pc_parental import decouverte
    from custom_components.pc_parental.store import _nom_app, _nom_site

    for k in (0, 1):
        decouverte.noter(w1, {"cdn-jeu.exemple": {"roblox": {"n": 5}}}, _nom_site, _nom_app, maintenant=time.time() - k * 86400)
    store.lier_auto(w1)
    d = await (await client.get(AUT + "?personne=person.lou")).json()
    site = next(s for s in _appli(d, "roblox")["sites"] if s["domaine"] == "cdn-jeu.exemple")
    assert site["lien"] == "observe" and site["preuve"] == {"n": 10, "jours": 2, "part": 1.0, "source": "mesure"}
    seuls = {s["domaine"]: s for s in d["sites_seuls"]}
    assert "coolmath.exemple" in seuls and seuls["coolmath.exemple"]["badge"] == "rouge" and "wikipedia.exemple" in seuls
    assert "cdn-jeu.exemple" not in seuls
    assert {"nom": "Jeux"} .items() <= next(c for c in d["categories"] if c["nom"] == "Jeux").items()


async def test_la_lecture_est_reservee_aux_comptes_autorises(hass, hass_storage, hass_client):
    await _pret(hass, hass_storage)
    _u, client = await _compte(hass, hass_client, "Affichage", "system-users")
    assert (await client.get(AUT)).status == 403
    assert (await client.post(GESTE, json={"action": "bloquer", "cible": "roblox"})).status == 403


# ------------------------------------------------------------------ « Bloqué pour tous »


async def test_bloque_pour_tous_avec_raison_et_levee_confirmee(hass, hass_storage, hass_client):
    coord, store = await _pret(hass, hass_storage)
    vus = []
    hass.bus.async_listen("pc_parental_geste", lambda e: vus.append(dict(e.data)))
    _u, client = await _compte(hass, hass_client, "Gestion")
    r = await client.post(GESTE, json={"action": "bloquer", "cible": "4chan.exemple", "portee": "tous"})
    assert r.status == 200, await r.text()
    d = await (await client.get(AUT)).json()
    ligne = next(b for b in d["bloques_pour_tous"] if b["site"] == "4chan.exemple")
    assert ligne["levable"] and ligne["source"] == "lieu" and ligne["par"] == "Gestion" and "manuelle" in ligne["raison"]
    # Lever demande une confirmation.
    r = await client.post(GESTE, json={"action": "lever", "cible": "4chan.exemple"})
    assert r.status == 409 and (await r.json())["confirmer"] is True
    assert store.est_banni("4chan.exemple")
    r = await client.post(GESTE, json={"action": "lever", "cible": "4chan.exemple", "confirmer": True})
    assert r.status == 200 and not store.est_banni("4chan.exemple")
    await hass.async_block_till_done()
    assert [v["action"] for v in vus] == ["bloquer", "lever"] and vus[-1]["qui"] == "Gestion" and vus[-1]["avant"] and vus[-1]["apres"]
    # Un site de la liste noire de sécurité se voit, mais ne se lève pas ici.
    store.est_dangereux = lambda n: n == "omegle.exemple"
    store.pcs["w1"]["sites_vus"]["omegle.exemple"] = {"premier": 1}
    store._memos = {}
    d = await (await client.get(AUT)).json()
    noire = next(b for b in d["bloques_pour_tous"] if b["site"] == "omegle.exemple")
    assert noire["levable"] is False and noire["source"] == "liste_noire"
    r = await client.post(GESTE, json={"action": "lever", "cible": "omegle.exemple", "confirmer": True})
    assert r.status == 409 and (await r.json())["error"] == "non_levable"
    assert (await client.post(GESTE, json={"action": "lever", "cible": "wikipedia.exemple", "confirmer": True})).status == 404


# ------------------------------------------------------------------ les gestes par portée


async def test_gestes_par_portee_tous_personne_appareil(hass, hass_storage, hass_client):
    coord, store = await _pret(hass, hass_storage)
    _u, client = await _compte(hass, hass_client, "Gestion")

    def ouvert(pc):
        store._memos = {}
        return store.statut(store.pcs[pc], "sites", "coolmath.exemple") == "autorise"

    assert not ouvert("w1") and not ouvert("t1")                    # « Jeux » est fermé pour Lou
    r = await client.post(GESTE, json={"action": "ouvrir", "cible": "coolmath.exemple", "portee": "appareil:t1"})
    assert r.status == 200, await r.text()
    assert ouvert("t1")
    corps = await r.json()
    assert corps["portee"] == "Sur TEL-LOU" and corps["avant"] == "ferme" and corps["apres"] == "ouvert"
    # L'effet réel est dit pour tous les appareils de la personne (une exception vaut pour la personne).
    assert corps["effet"]["t1"] == "ouvert" and corps["effet"]["w1"] == ("ouvert" if ouvert("w1") else "ferme")
    r = await client.post(GESTE, json={"action": "ouvrir", "cible": "coolmath.exemple", "portee": "personne:person.lou"})
    assert r.status == 200 and ouvert("t1") and ouvert("w1")
    assert (await r.json())["portee"] == "Pour Lou"
    r = await client.post(GESTE, json={"action": "bloquer", "cible": "coolmath.exemple", "portee": "personne:person.lou"})
    assert r.status == 200 and not ouvert("t1") and not ouvert("w1")
    r = await client.post(GESTE, json={"action": "laisser_passer", "cible": "coolmath.exemple", "portee": "appareil:w1", "duree": 30})
    assert r.status == 200 and ouvert("w1") and not ouvert("t1")
    # Pour tout le monde : bloquer puis ouvrir.
    assert (await client.post(GESTE, json={"action": "bloquer", "cible": "coolmath.exemple", "portee": "tous"})).status == 200
    assert store.est_banni("coolmath.exemple") and not ouvert("w1")
    r = await client.post(GESTE, json={"action": "ouvrir", "cible": "coolmath.exemple", "portee": "tous"})
    assert r.status == 200 and not store.est_banni("coolmath.exemple")
    # Lever le blocage du Lieu ne perce pas la catégorie fermée pour Lou : l'effet réel le dit, sans rien promettre de plus.
    assert (await r.json())["effet"]["t1"] == "ferme" and not ouvert("t1")
    # Refus propres : portée ou action inconnues, essentiel jamais bloqué.
    assert (await client.post(GESTE, json={"action": "ouvrir", "cible": "x.exemple", "portee": "personne:person.nobody"})).status == 404
    assert (await client.post(GESTE, json={"action": "ouvrir", "cible": "x.exemple", "portee": "n'importe quoi"})).status == 400
    assert (await client.post(GESTE, json={"action": "detruire", "cible": "x"})).status == 400


async def test_rattacher_retirer_et_couper_le_lien_automatique(hass, hass_storage, hass_client):
    coord, store = await _pret(hass, hass_storage)
    _u, client = await _compte(hass, hass_client, "Gestion")
    r = await client.post(GESTE, json={"action": "rattacher", "app": "roblox", "cible": "wikipedia.exemple"})
    assert r.status == 200 and "wikipedia.exemple" in store.dependances("roblox")
    assert (await r.json())["avant"] == "non_lie" and (await r.json())["apres"] == "lie"
    r = await client.post(GESTE, json={"action": "retirer_lien", "app": "roblox", "cible": "wikipedia.exemple"})
    assert r.status == 200 and "wikipedia.exemple" not in store.dependances("roblox")
    # « Lier tout seul : non » : plus aucun lien automatique pour cette appli.
    await client.post(GESTE, json={"action": "lier_auto", "app": "roblox", "oui": False})
    store.etiquettes["sites"]["rbxnouveau.com"] = ["Jeux"]
    store.noter_alerte(store.pcs["w1"], "rbxnouveau.com", "regle", "roblox")
    assert store.lier_auto(store.pcs["w1"]) == 0 and "rbxnouveau.com" not in (store.domaines_auto.get("roblox") or {})
    await client.post(GESTE, json={"action": "lier_auto", "app": "roblox", "oui": True})
    assert store.lier_auto(store.pcs["w1"]) >= 1


# ------------------------------------------------------------------ le code et le journal


async def test_pas_de_code_pour_un_administrateur_et_le_geste_est_journalise(hass, hass_storage, hass_client):
    from custom_components.pc_parental import journal_gestes

    coord, store = await _pret(hass, hass_storage)
    _poser_code(hass)
    _u, client = await _compte(hass, hass_client, "Gestion")
    r = await client.post(GESTE, json={"action": "ouvrir", "cible": "coolmath.exemple", "portee": "personne:person.lou"})
    assert r.status == 200, await r.text()
    ligne = journal_gestes.recents(hass, 1)[0]
    assert ligne["qui"] == "Gestion" and ligne["action"] == "ouvrir" and ligne["cible"] == "coolmath.exemple"
    assert ligne["portee"] == "Pour Lou" and ligne["avant"] == "ferme" and ligne["apres"] == "ouvert"
    d = await (await client.get(AUT)).json()
    assert d["journal"][0]["qui"] == "Gestion"


async def test_un_compte_marque_partage_tape_le_code_pour_ouvrir_mais_pas_pour_bloquer(hass, hass_storage, hass_client):
    coord, store = await _pret(hass, hass_storage)
    _poser_code(hass)
    admin, client_admin = await _compte(hass, hass_client, "Gestion")
    partage, client = await _compte(hass, hass_client, "Mural")
    # Seul un administrateur marque un compte ; le marquage est journalisé.
    r = await client_admin.post(GESTE, json={"action": "compte_partage", "compte": partage.id, "partage": True})
    assert r.status == 200 and partage.id in store.comptes_partages
    # Ouvrir : le code est demandé, faux refusé, juste accepté.
    corps = {"action": "ouvrir", "cible": "coolmath.exemple", "portee": "appareil:t1"}
    r = await client.post(GESTE, json=corps)
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"
    r = await client.post(GESTE, json={**corps, "code_octroi": "0000"})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi_faux"
    r = await client.post(GESTE, json={**corps, "code_octroi": CODE})
    assert r.status == 200, await r.text()
    # Bloquer : jamais de code (restrictif).
    r = await client.post(GESTE, json={"action": "bloquer", "cible": "coolmath.exemple", "portee": "appareil:t1"})
    assert r.status == 200
    # Un compte partagé ne peut pas se démarquer sans le code (ce serait lever sa propre protection).
    demarquer = {"action": "compte_partage", "compte": partage.id, "partage": False}
    r = await client.post(GESTE, json=demarquer)
    assert r.status == 403 and (await r.json())["error"] == "code_octroi" and partage.id in store.comptes_partages
    # Un administrateur ordinaire le démarque sans code ; le compte n'a plus à taper le code.
    assert (await client_admin.post(GESTE, json=demarquer)).status == 200 and partage.id not in store.comptes_partages
    assert (await client.post(GESTE, json=corps)).status == 200
    # Un compte qui n'est pas administrateur ne peut pas marquer.
    _u, simple = await _compte(hass, hass_client, "Affichage", "system-users")
    assert (await simple.post(GESTE, json={"action": "compte_partage", "compte": admin.id})).status == 403
