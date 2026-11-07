"""1.94 · Découverte appli → sites : le lien vient de la MESURE (appareil + appli + domaine), jamais d'un nom.

Seuils (spec §0.4) : 5 demandes sur 2 jours au moins, 70 % du domaine, jamais à 3 applis ou plus ; domaines communs
exclus (et complétables) ; un domaine qui porte le nom d'une autre appli est à celle-là ; chaque lien garde sa preuve ;
un refus est retenu ; la liste fixe et le rapprochement par nom ne sont que des secours.

    python -m pytest essai/decouverte_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import sys
import time

from autorisations_lot1_test import _installer
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    ENFANT,
    _integrations,
    expected_lingering_tasks,
    expected_lingering_timers,
)

JOUR = 86400


def _module(fin):
    return next(m for n, m in sys.modules.items() if n.endswith("pc_parental." + fin))


async def _pret(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    store = coord.store
    # Aucun secours en jeu : ni appli vue, ni alerte (le rapprochement par nom ne doit rien lier ici).
    return coord, store, store.pcs["t1"]


def _mesurer(store, pc, domaine, qui, jours=(0,), par_jour=None, incertain=0):
    """`qui` : {paquet: demandes au total} réparties sur `jours` (il y a n jours), comme le ferait l'appli."""
    decouverte = _module("decouverte")
    for k in jours:
        releve = {domaine: {p: {"n": (par_jour if par_jour is not None else n), "incertain": incertain} for p, n in qui.items()}}
        decouverte.noter(pc, releve, _module("store")._nom_site,
                         lambda p: _module("const").FAMILLES_APPS.get(_module("store")._nom_app(p), _module("store")._nom_app(p)),
                         maintenant=time.time() - k * JOUR)


def _lies(store, famille):
    return (store.domaines_auto or {}).get(famille, {})


# ------------------------------------------------------------------ les seuils


async def test_quatre_demandes_ou_un_seul_jour_ne_lient_rien(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "cdn-un-jour.exemple", {"com.exemple.jeu": 4}, jours=(0, 1), par_jour=2)    # 4 demandes
    _mesurer(store, pc, "cdn-jour-seul.exemple", {"com.exemple.jeu": 9}, jours=(0,))                # 1 jour
    store.lier_auto(pc)
    assert not _lies(store, "com.exemple.jeu")


async def test_cinq_demandes_sur_deux_jours_lient_avec_leur_preuve(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "cdn-jeu.exemple", {"com.exemple.jeu": 3}, jours=(0, 2), par_jour=3)         # 6 sur 2 jours
    assert store.lier_auto(pc) == 1
    fiche = _lies(store, "com.exemple.jeu")["cdn-jeu.exemple"]
    assert fiche["preuve"] == {"n": 6, "jours": 2, "part": 1.0, "source": "mesure"} and "secours" not in fiche
    # La preuve est visible dans l'affichage du logiciel, avec le lien en attente (rien n'est ouvert pour autant).
    ligne = next(s for s in store.sites_du_logiciel("com.exemple.jeu") if s["nom"] == "cdn-jeu.exemple")
    assert ligne["auto"] and ligne["preuve"]["n"] == 6 and ligne["preuve"]["part"] == 1.0
    # Relancer ne crée rien de plus.
    assert store.lier_auto(pc) == 0


async def test_la_part_de_l_appli_doit_atteindre_70_pour_cent(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "partage-sept.exemple", {"com.exemple.a": 7, "com.exemple.b": 3}, jours=(0, 1), par_jour=None)
    store.lier_auto(pc)
    assert "partage-sept.exemple" in _lies(store, "com.exemple.a") and "partage-sept.exemple" not in _lies(store, "com.exemple.b")
    assert _lies(store, "com.exemple.a")["partage-sept.exemple"]["preuve"]["part"] == 0.7
    _mesurer(store, pc, "partage-six.exemple", {"com.exemple.a": 6, "com.exemple.b": 4}, jours=(0, 1))
    store.lier_auto(pc)
    assert "partage-six.exemple" not in _lies(store, "com.exemple.a") and "partage-six.exemple" not in _lies(store, "com.exemple.b")


async def test_trois_applis_ou_plus_jamais(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "traceur.exemple", {"com.exemple.a": 50, "com.exemple.b": 1, "com.exemple.c": 1}, jours=(0, 1, 2))
    store.lier_auto(pc)
    assert not any("traceur.exemple" in liens for liens in store.domaines_auto.values())


async def test_les_demandes_incertaines_ne_comptent_jamais(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "incertain.exemple", {"com.exemple.a": 0}, jours=(0, 1, 2), par_jour=0, incertain=40)
    store.lier_auto(pc)
    assert not _lies(store, "com.exemple.a")
    assert pc["mesures"]["incertain.exemple"]["com.exemple.a"]["incertain"] == 120  # gardé pour l'affichage


async def test_l_observation_vieille_de_plus_de_sept_jours_ne_compte_plus(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "ancien.exemple", {"com.exemple.a": 20}, jours=(9, 10))
    store.lier_auto(pc)
    assert not _lies(store, "com.exemple.a")


# ------------------------------------------------------------------ le filtre anti-bruit


async def test_domaines_communs_exclus_et_completables(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "googleapis.com", {"com.exemple.a": 30}, jours=(0, 1))
    _mesurer(store, pc, "service-maison.exemple", {"com.exemple.a": 30}, jours=(0, 1))
    store.lier_auto(pc)
    assert "googleapis.com" not in _lies(store, "com.exemple.a")
    assert "service-maison.exemple" in _lies(store, "com.exemple.a")
    # Le Décideur ajoute un domaine commun : il perd son lien et n'est plus relié.
    assert store.completer_domaines_communs("service-maison.exemple", True) is True
    assert "service-maison.exemple" not in _lies(store, "com.exemple.a")
    store.lier_auto(pc)
    assert "service-maison.exemple" not in _lies(store, "com.exemple.a")
    assert store.completer_domaines_communs("service-maison.exemple", True) is False
    assert store.completer_domaines_communs("service-maison.exemple", False) is True
    store.lier_auto(pc)
    assert "service-maison.exemple" in _lies(store, "com.exemple.a")


async def test_un_domaine_qui_porte_le_nom_d_une_autre_appli_n_est_lie_qu_a_celle_la(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    pc["apps_vues"]["com.linkedin.android"] = {"premier": 1}
    _mesurer(store, pc, "linkedin.com", {"com.discord": 40}, jours=(0, 1, 2))
    store.lier_auto(pc)
    assert not _lies(store, "discord") and not _lies(store, "com.discord")
    # LinkedIn lui-même, qui demande linkedin.com, le lie.
    _mesurer(store, pc, "linkedin.com", {"com.linkedin.android": 200}, jours=(0, 1, 2))
    store.lier_auto(pc)
    assert "linkedin.com" not in _lies(store, "discord")


# ------------------------------------------------------------------ refus, secours, à vérifier


async def test_un_refus_est_retenu(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "cdn-jeu.exemple", {"com.exemple.jeu": 10}, jours=(0, 1))
    store.lier_auto(pc)
    assert "cdn-jeu.exemple" in _lies(store, "com.exemple.jeu")
    assert store.lier_domaine("com.exemple.jeu", "cdn-jeu.exemple", False)
    _mesurer(store, pc, "cdn-jeu.exemple", {"com.exemple.jeu": 100}, jours=(0, 1, 2))
    store.lier_auto(pc)
    assert "cdn-jeu.exemple" not in _lies(store, "com.exemple.jeu")
    assert "cdn-jeu.exemple" not in store.dependances("com.exemple.jeu")


async def test_une_observation_suffisante_remplace_un_secours(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    store.noter_alerte(pc, "rbxnouveau.com", "regle", "roblox")   # le secours de 2.5.1 : bloqué pendant que Roblox tournait
    store.lier_auto(pc)
    assert _lies(store, "roblox")["rbxnouveau.com"].get("secours") is True
    ligne = next(s for s in store.sites_du_logiciel("roblox") if s["nom"] == "rbxnouveau.com")
    assert ligne["secours"] is True and "preuve" not in ligne
    _mesurer(store, pc, "rbxnouveau.com", {"robloxplayerbeta": 8}, jours=(0, 1))
    store.lier_auto(pc)
    fiche = _lies(store, "roblox")["rbxnouveau.com"]
    assert "secours" not in fiche and fiche["preuve"]["n"] == 16 and fiche["preuve"]["source"] == "mesure"


async def test_un_secours_que_la_mesure_contredit_est_a_verifier(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    store.noter_alerte(pc, "rbxnouveau.com", "regle", "roblox")
    store.lier_auto(pc)
    assert "a_verifier" not in _lies(store, "roblox")["rbxnouveau.com"]            # aucune mesure : on ne dit rien
    _mesurer(store, pc, "rbxnouveau.com", {"com.android.chrome": 12}, jours=(0, 1))  # demandé, mais par le navigateur
    store.lier_auto(pc)
    assert _lies(store, "roblox")["rbxnouveau.com"]["a_verifier"] is True
    ligne = next(s for s in store.sites_du_logiciel("roblox") if s["nom"] == "rbxnouveau.com")
    assert ligne["a_verifier"] is True
    # Si Roblox le demande à son tour, la marque disparaît.
    _mesurer(store, pc, "rbxnouveau.com", {"robloxplayerbeta": 30}, jours=(0,))
    store.lier_auto(pc)
    assert "a_verifier" not in _lies(store, "roblox")["rbxnouveau.com"]


async def test_un_secours_ne_contourne_pas_la_regle_des_applis_multiples(hass, hass_storage):
    _, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "rbxnouveau.com", {"a1": 3, "a2": 3, "a3": 3}, jours=(0, 1))
    store.noter_alerte(pc, "rbxnouveau.com", "regle", "roblox")
    store.lier_auto(pc)
    assert "rbxnouveau.com" not in _lies(store, "roblox")


# ------------------------------------------------------------------ le relevé (HTTP)


async def test_le_releve_android_alimente_les_mesures_sans_url_et_borne(hass, hass_storage, hass_client):
    client = await hass_client()
    coord, store, pc = await _pret(hass, hass_storage)
    pc["secret"] = ENFANT["secret"]
    releve = {"https://cdn-jeu.exemple/chemin?x=1": {"com.exemple.jeu": {"n": 4, "incertain": 2, "bloque": 1}}}
    releve.update({f"d{i}.exemple": {"com.exemple.jeu": {"n": 1}} for i in range(250)})
    r = await client.post("/api/pc_parental/poll", json={"id": "t1", "secret": pc["secret"], "origines": releve, "attribution": "uid"})
    assert r.status == 200, await r.text()
    pc = coord.store.pcs["t1"]
    assert pc["attribution"] == "uid"
    m = pc["mesures"]
    assert "cdn-jeu.exemple" in m and not any("/" in d or "?" in d or "http" in d for d in m)
    assert len(m) <= 200
    e = m["cdn-jeu.exemple"]["com.exemple.jeu"]
    assert e["n"] == 4 and e["incertain"] == 2 and e["bloque"] == 1
    r = await client.post("/api/pc_parental/poll", json={"id": "t1", "secret": pc.get("secret") or "e" * 32, "attribution": "n'importe quoi"})
    assert coord.store.pcs["t1"]["attribution"] == "uid", "une valeur inconnue ne remplace rien"


async def test_l_agent_pc_envoie_des_instantanes_qui_comptent_chacun_pour_une_demande(hass, hass_storage):
    _, store, w1 = await _pret(hass, hass_storage)
    w1["sites_vus"]["cdn-pc.exemple"] = {"premier": 1}
    for k in (0, 1, 2):
        for _ in range(3):
            _module("decouverte").noter(w1, {"cdn-pc.exemple": ["RobloxPlayerBeta.exe"]}, _module("store")._nom_site,
                                        lambda p: _module("const").FAMILLES_APPS.get(_module("store")._nom_app(p), _module("store")._nom_app(p)),
                                        maintenant=time.time() - k * JOUR)
    store.lier_auto(w1)
    fiche = _lies(store, "roblox")["cdn-pc.exemple"]
    assert fiche["preuve"]["n"] == 9 and fiche["preuve"]["jours"] == 3
    # Un navigateur ne lie rien, même très présent.
    for k in (0, 1):
        _module("decouverte").noter(w1, {"journal.exemple": ["chrome.exe"] * 5}, _module("store")._nom_site, lambda p: p.replace(".exe", ""),
                                    maintenant=time.time() - k * JOUR)
    store.lier_auto(w1)
    assert "chrome" not in (store.domaines_auto or {})


async def test_les_mesures_sont_gardees_au_redemarrage(hass, hass_storage):
    coord, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "cdn-jeu.exemple", {"com.exemple.jeu": 10}, jours=(0, 1))
    store.lier_auto(pc)
    await store.async_save()
    donnees = hass_storage[_module("const").DOMAIN]["data"]
    assert donnees["pcs"]["t1"]["mesures"]["cdn-jeu.exemple"]["com.exemple.jeu"]["n"] == 20
    assert donnees["domaines_auto"]["com.exemple.jeu"]["cdn-jeu.exemple"]["preuve"]["n"] == 20


# ------------------------------------------------------------------ le badge de l'appli (spec §0.5)


async def test_badge_rouge_orange_vert_selon_l_appli_et_ses_sites(hass, hass_storage):
    from autorisations_lot1_test import _accorder

    coord, store, w1 = await _pret(hass, hass_storage)

    def roblox(pc="w1"):
        store._memos = {}
        detail = store.detail_categorie(store.pcs[pc], "Jeux", 20, "fr")
        return next(lg for lg in detail["logiciels"] if lg["nom"] == "roblox")

    # Rouge : Roblox est fermé (« Jeux » fermé), avec la raison, sans liste de sites, et l'appareil regardé.
    r = roblox()
    assert r["badge"] == "rouge" and r["raison"] and r["sites_fermes"] == []
    assert r["appareil"] == {"id": "w1", "nom": "PC-LOU"}
    # Vert : accordé, ses sites suivent.
    await _accorder(hass, coord, "roblox", "exception")
    r = roblox()
    assert r["badge"] == "vert" and r["sites_fermes"] == []
    # Orange : un site lié reste fermé (lien automatique en attente, fermé au moment du lien).
    store.noter_alerte(w1, "rbxnouveau.com", "regle", "roblox")
    store.etiquettes["sites"]["rbxnouveau.com"] = ["Jeux"]
    store.lier_auto(w1)
    r = roblox()
    assert r["badge"] == "orange" and "rbxnouveau.com" in r["sites_fermes"]
    # Le même calcul change avec l'appareil regardé : sur la tablette, Roblox n'est pas accordé (exception : toutes ses machines).
    assert roblox("t1")["appareil"]["id"] == "t1"
    # Le Décideur fait suivre le site : le badge redevient vert.
    store.lier_domaine("roblox", "rbxnouveau.com", True)
    assert roblox()["badge"] == "vert"


async def test_le_geste_domaine_commun_est_restrictif_et_reserve_aux_decideurs(hass, hass_storage):
    coord, store, pc = await _pret(hass, hass_storage)
    _mesurer(store, pc, "service-maison.exemple", {"com.exemple.a": 30}, jours=(0, 1))
    store.lier_auto(pc)
    assert "service-maison.exemple" in _lies(store, "com.exemple.a")
    assert not _module("octroi").est_octroi({"action": "domaine_commun", "domaine": "service-maison.exemple"})  # aucun code à taper
    retour = await _module("parent").agir(hass, coord, coord.store.pcs["w1"], {"action": "domaine_commun", "domaine": "service-maison.exemple"}, "Nolan", "p1")
    assert "service-maison.exemple" in str(retour)
    assert "service-maison.exemple" not in _lies(store, "com.exemple.a")
    assert "service-maison.exemple" in store.domaines_communs


async def test_les_liens_automatiques_d_avant_la_mesure_sont_gardes_tels_quels(hass, hass_storage):
    """Mise à jour : un lien tout seul de 2.5.12.x (sans preuve ni marque) reste, suit toujours, et ne casse rien."""
    from autorisations_lot1_test import _installer

    coord = await _installer(hass, hass_storage)
    store = coord.store
    store.domaines_auto = {"roblox": {"ancien.exemple": {"depuis": 1.0, "suit": True}}}
    store.lier_auto(store.pcs["w1"])
    assert store.domaines_auto["roblox"]["ancien.exemple"] == {"depuis": 1.0, "suit": True}
    assert "ancien.exemple" in store.dependances("roblox")
    ligne = next(s for s in store.sites_du_logiciel("roblox") if s["nom"] == "ancien.exemple")
    assert ligne["auto"] is True and "preuve" not in ligne and "secours" not in ligne
    await store.async_save()   # et il se resauvegarde sans erreur
