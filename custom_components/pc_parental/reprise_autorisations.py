"""Reprise des autorisations (refonte, lot 2 — CONCEPTION-autorisations.md § 6).

Les réglages existants n'ont pas à être déplacés : « autorisé » est déjà l'exception (recopiée sur tous les
appareils de la personne), la liste « toujours » est déjà « toujours disponible », un laissez-passer est déjà
« pour un temps » (même heure de fin, même appareil). Ce qui change, c'est le moteur de décision. Il ne
s'applique donc qu'après :

1. **simulation** : ce que le nouveau moteur changerait, par Demandeur et par appli ou site, sans rien écrire ;
2. **rapport** au Décideur (notification et réponse du service) ;
3. **validation** : le Décideur choisit « Appliquer » ;
4. **sauvegarde** de l'état d'avant, puis bascule ; « Annuler » revient à l'ancien moteur.
"""
from __future__ import annotations

import copy
import time
from typing import Any

from . import textes
from .const import AUTORISE, ETIQUETTES_SECURITE

CHAMPS_SAUVES = ("autorises", "bloques", "toujours", "toujours_sites", "laissez_passer")


def _nom_personne(hass, pc: dict[str, Any]) -> str:
    etat = hass.states.get(str(pc.get("person") or "")) if hass is not None else None
    return str(etat.name) if etat is not None and etat.name else str(pc.get("name") or pc.get("id") or "")


def _a_examiner(store, pc: dict[str, Any]) -> list[tuple[str, str]]:
    """Les noms dont la décision peut changer : ce qui est accordé, bloqué ou ouvert pour un temps."""
    noms: dict[tuple[str, str], None] = {}
    for genre in ("apps", "sites"):
        for n in store.noms_durables(pc, genre):
            noms.setdefault((genre, n), None)
    for v in pc.get("bloques") or []:
        v = str(v).strip()
        if v and not store._est_etiquette(v):
            genre = store._genre_de(v)
            noms.setdefault((genre, store._cle(genre, v)), None)
    for cle_lp, _fin in store.laissez_passer_actifs(pc):
        noms.setdefault(("apps", cle_lp[4:]) if cle_lp.startswith("app:") else ("sites", cle_lp), None)
    return [k for k in noms if k[1]]


def _statuts(store, v2: bool, cibles: list[tuple[dict[str, Any], str, str]]) -> list[str]:
    avant = store.autorisations_v2
    store.autorisations_v2 = v2
    store._memos = {}
    try:
        return [store.statut(pc, genre, nom) for pc, genre, nom in cibles]
    finally:
        store.autorisations_v2 = avant
        store._memos = {}


def simuler(store, hass=None) -> dict[str, Any]:
    """Ce que le nouveau moteur changerait. Rien n'est écrit."""
    regles: list[dict[str, Any]] = []
    cibles: list[tuple[dict[str, Any], str, str]] = []
    vus_personne: set[tuple[str, str, str]] = set()
    for pc in store.pcs.values():
        personne = store.personne_de(pc)
        qui = _nom_personne(hass, pc)
        # Les règles, une fois par personne : « toujours disponible » passe désormais plages et temps épuisé.
        for genre in ("apps", "sites"):
            for n in store.noms_durables(pc, genre, "toujours"):
                if (personne, genre, n) not in vus_personne:
                    vus_personne.add((personne, genre, n))
                    regles.append({"type": "toujours", "personne": qui, "genre": genre, "nom": n})
        # Par appareil : une autorisation ou un temps qui perçait une catégorie de sécurité ou d'âge, ou la liste noire.
        for genre, n in _a_examiner(store, pc):
            cibles.append((pc, genre, n))
            accorde = store.mode_accorde(pc, genre, n) or ("temps" if store._laissez_passer(pc, genre, n) else None)
            if not accorde:
                continue
            for e in store.etiquettes_de(genre, n):
                if str(e).strip().lower() in ETIQUETTES_SECURITE and (
                        store._dans(pc.get("bloques"), e) or store.fermeture_de(pc, [e]) is not None):
                    regles.append({"type": "securite", "personne": qui, "appareil": pc.get("name"), "genre": genre,
                                   "nom": n, "categorie": e})
            if genre == "sites" and store.est_dangereux(n):
                regles.append({"type": "liste_noire", "personne": qui, "genre": genre, "nom": n})
    avant = _statuts(store, False, cibles)
    apres = _statuts(store, True, cibles)
    maintenant = [
        {"type": "maintenant", "personne": _nom_personne(hass, pc), "appareil": pc.get("name"), "genre": genre,
         "nom": n, "avant": a, "apres": b}
        for (pc, genre, n), a, b in zip(cibles, avant, apres) if a != b
    ]
    return {"date": time.time(), "deja_faite": bool(store.autorisations_v2), "regles": regles,
            "maintenant": maintenant}


def appliquer(store, par: str, hass=None) -> dict[str, Any]:
    """Validation du Décideur : sauvegarde de l'état d'avant, puis le nouveau moteur s'applique."""
    if store.autorisations_v2:
        raise textes.Erreur("reprise.deja_faite")
    rapport = simuler(store, hass)
    store.reprise_autorisations = {
        "date": time.time(), "par": str(par or ""),
        "appareils": {pc_id: {c: copy.deepcopy(pc.get(c)) for c in CHAMPS_SAUVES if c in pc}
                      for pc_id, pc in store.pcs.items()},
        "rapport": rapport,
    }
    store.autorisations_v2 = True
    store._memos = {}
    return rapport


def annuler(store, par: str) -> None:
    """Retour arrière : l'ancien moteur s'applique de nouveau. Les réglages restent (même format pour les deux
    moteurs) ; la sauvegarde d'avant la reprise est gardée."""
    if not store.autorisations_v2 or not store.reprise_autorisations:
        raise textes.Erreur("reprise.rien_a_annuler")
    store.autorisations_v2 = False
    store.reprise_autorisations = {**store.reprise_autorisations, "annulee": time.time(), "annulee_par": str(par or "")}
    store._memos = {}


def texte(rapport: dict[str, Any], langue: str | None) -> str:
    """Le rapport lisible par le Décideur."""
    lignes = [textes.t("reprise.titre", langue), ""]
    etat = {AUTORISE: textes.t("reprise.ouvert", langue)}
    ferme = textes.t("reprise.ferme", langue)
    for r in rapport["regles"]:
        if r["type"] == "toujours":
            lignes.append("- " + textes.t("reprise.toujours", langue, personne=r["personne"], nom=r["nom"]))
        elif r["type"] == "securite":
            lignes.append("- " + textes.t("reprise.securite", langue, personne=r["personne"], nom=r["nom"],
                                          appareil=r["appareil"], categorie=textes.categorie(r["categorie"], langue)))
        elif r["type"] == "liste_noire":
            lignes.append("- " + textes.t("reprise.liste_noire", langue, personne=r["personne"], nom=r["nom"]))
    for r in rapport["maintenant"]:
        lignes.append("- " + textes.t("reprise.maintenant", langue, appareil=r["appareil"], nom=r["nom"],
                                      avant=etat.get(r["avant"], ferme), apres=etat.get(r["apres"], ferme)))
    if len(lignes) == 2:
        lignes.append(textes.t("reprise.rien", langue))
    lignes += ["", textes.t("reprise.valider", langue)]
    return "\n".join(lignes)
