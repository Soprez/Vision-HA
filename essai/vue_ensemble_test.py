"""2.5.12.14 : la vue d'ensemble (tous les appareils, toutes les personnes) et ses filtres.

Avec le catalogue de l'essai de performance (8 appareils, 1 500 sites, 150 logiciels, ~2 800 lignes) on vérifie que
- la première réponse ne porte qu'une tranche (150 lignes) et dit le total, le nombre de lignes par état et les choix ;
- chaque filtre (personne, appareil, catégorie, état, texte) ne rend que ce qui lui correspond, et se combine aux autres ;
- la suite se lit par `debut` ; une limite de temps apparaît dans la vue « limites » (et « limité » dans les logiciels) ;
- la lecture est réservée aux administrateurs, et les lectures suivantes servent le jeu gardé (rapide) ;
- la carte retient le filtre dans le navigateur et rend la liste par tranches.

    python -m pytest essai/vue_ensemble_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pathlib
import time

from perf_autorisations_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, NB_PC, _installer, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

MAISON = "/api/pc_parental/maison"
CARTE = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental" / "frontend" / "vision-ensemble-card.js"


async def _lire(admin, **corps):
    r = await admin.post(MAISON, json={"action": "ensemble", **corps})
    assert r.status == 200, await r.text()
    return await r.json()


async def test_premiere_tranche_et_totaux(hass, hass_storage, hass_client):
    await _installer(hass, hass_storage)
    admin = await hass_client()
    d = await _lire(admin, vue="logiciels")
    assert d["ok"] and len(d["lignes"]) == 150 and d["total"] > 2000, d["total"]
    assert sum(d["compte"].values()) == d["total"]
    assert d["compte"]["bloque"] > 0 and d["compte"]["ouvert"] > 0
    assert len(d["facettes"]["appareils"]) == NB_PC and len(d["facettes"]["personnes"]) == NB_PC
    assert "Musique" in d["facettes"]["categories"]
    assert all("_q" not in ligne for ligne in d["lignes"])
    # Les fermées d'abord.
    etats = [ligne["etat"] for ligne in d["lignes"]]
    assert etats == sorted(etats, key=["bloque", "limite", "ouvert"].index)
    # La suite se lit par `debut`, sans recouvrement.
    suite = await _lire(admin, vue="logiciels", debut=150, n=150)
    assert len(suite["lignes"]) == 150
    cles = lambda lignes: {(x["pc"], x["genre"], x["nom"]) for x in lignes}  # noqa: E731
    assert not cles(d["lignes"]) & cles(suite["lignes"])


async def test_filtres_seuls_et_combines(hass, hass_storage, hass_client):
    await _installer(hass, hass_storage)
    admin = await hass_client()
    tout = await _lire(admin, vue="logiciels")
    # État.
    bloques = await _lire(admin, vue="logiciels", filtres={"etat": "bloque"}, n=400)
    assert bloques["total"] == tout["compte"]["bloque"] and {x["etat"] for x in bloques["lignes"]} == {"bloque"}
    # Appareil.
    pc = await _lire(admin, vue="logiciels", filtres={"appareil": "pc3"}, n=400)
    assert pc["total"] > 0 and {x["pc"] for x in pc["lignes"]} == {"pc3"}
    # Personne (chaque appareil de l'essai a la sienne).
    pers = tout["facettes"]["appareils"][3]["personne"]
    p = await _lire(admin, vue="logiciels", filtres={"personne": pers}, n=400)
    assert p["total"] > 0 and {x["personne"] for x in p["lignes"]} == {pers}
    # Catégorie.
    jeux = await _lire(admin, vue="logiciels", filtres={"categorie": "Musique"}, n=400)
    assert jeux["total"] > 0 and all("Musique" in x["categories"] for x in jeux["lignes"])
    # Texte (nom, appareil, catégorie), sans casse.
    q = await _lire(admin, vue="logiciels", filtres={"q": "SITE0007"}, n=400)
    assert 0 < q["total"] <= NB_PC and all("site0007" in x["nom"] for x in q["lignes"])
    # Combiné : appareil + état + catégorie.
    c = await _lire(admin, vue="logiciels", filtres={"appareil": "pc3", "etat": "bloque", "categorie": "Musique"}, n=400)
    assert all(x["pc"] == "pc3" and x["etat"] == "bloque" and "Musique" in x["categories"] for x in c["lignes"])
    assert c["total"] <= min(pc["total"], bloques["total"], jeux["total"])
    # Les comptes par état ignorent le filtre d'état (ils servent à l'afficher) mais pas les autres filtres.
    assert sum(pc["compte"].values()) == pc["total"]
    # Un état invalide est ignoré ; un texte introuvable ne rend rien.
    assert (await _lire(admin, vue="logiciels", filtres={"etat": "n importe quoi"}))["total"] == tout["total"]
    assert (await _lire(admin, vue="logiciels", filtres={"q": "zzz-introuvable"}))["total"] == 0


async def test_vue_limites(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    admin = await hass_client()
    assert (await _lire(admin, vue="limites"))["total"] == 0
    coord.store.poser_limite(coord.store.pcs["pc2"], "Logiciel 004", 45)
    coord.store.poser_limite(coord.store.pcs["pc5"], "Logiciel 008", 30)
    await coord.store.async_save()
    lim = await _lire(admin, vue="limites")
    assert lim["total"] == 2
    assert {x["minutes"] for x in lim["lignes"]} == {45, 30}
    une = await _lire(admin, vue="limites", filtres={"appareil": "pc2"})
    assert une["total"] == 1 and une["lignes"][0]["minutes"] == 45
    # Dans les logiciels, l'appli limitée est « limité » (à moins d'être fermée).
    logiciels = await _lire(admin, vue="logiciels", filtres={"etat": "limite"}, n=400)
    assert logiciels["total"] >= 1 and all("minutes" in x for x in logiciels["lignes"])
    # Retirer la limite (même geste que la fiche d'une personne) la fait disparaître de la vue.
    r = await admin.post(MAISON, json={"action": "limite", "pc": "pc2", "nom": "Logiciel 004", "minutes": 0})
    assert r.status == 200
    assert (await _lire(admin, vue="limites"))["total"] == 1


async def test_reservee_aux_administrateurs(hass, hass_storage, hass_client, hass_read_only_user):
    await _installer(hass, hass_storage)
    jeton = hass.auth.async_create_access_token(
        await hass.auth.async_create_refresh_token(hass_read_only_user, "https://exemple.invalid/"))
    lecteur = await hass_client(jeton)
    r = await lecteur.post(MAISON, json={"action": "ensemble"})
    assert r.status == 403


async def test_jeu_garde_et_rapide(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    admin = await hass_client()
    t = time.perf_counter()
    await _lire(admin, vue="logiciels")
    premier = time.perf_counter() - t
    t = time.perf_counter()
    for _ in range(5):
        await _lire(admin, vue="logiciels", filtres={"etat": "bloque"})
    suivants = (time.perf_counter() - t) / 5
    assert suivants < 0.25 and suivants < premier, (premier, suivants)
    # Une écriture oublie le jeu : une nouvelle décision se voit tout de suite.
    avant = (await _lire(admin, vue="logiciels", filtres={"etat": "bloque"}))["total"]
    coord.store.bannir("site0001.example")
    await coord.store.async_save()
    apres = (await _lire(admin, vue="logiciels", filtres={"etat": "bloque"}))["total"]
    assert apres > avant, (avant, apres)


def test_carte_retient_le_filtre_et_pagine():
    src = CARTE.read_text(encoding="utf-8")
    assert "localStorage.getItem(this._cle)" in src and "localStorage.setItem(this._cle" in src
    assert "const PAS = 150" in src and 'action: "ensemble"' in src and "data-suite" in src
    # Tous par défaut : aucun filtre posé tant que personne n'a choisi.
    assert 'const VIDE = { personne: "", appareil: "", categorie: "", etat: "", q: "" }' in src
