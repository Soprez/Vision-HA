"""1.93.06.1 (l'auteur, tablettes utilisées comme tableau de bord) : « Afficher la liste des applications », par appareil.

- ``liste_applis`` : vrai par défaut. Faux : l'accueil de l'appareil Android ne montre que les widgets (sans dock, sans
  tiroir, sans colonne ni rangée d'applis) ; l'appareil garde la liste s'il n'a aucun widget (jamais un accueil vide),
  et les paramètres restent atteignables par un appui long sur l'heure, derrière le code ou l'empreinte.

Réglé par le service ``pc_parental.accueil_regler`` (administrateurs et automatisations), ou sur l'appareil lui-même
(Réglages) ; envoyé dans le bloc ``accueil_options`` du relevé avec ``depuis`` (l'heure du réglage) : comme
``musique_options``, l'appareil applique un réglage de Home Assistant plus récent que le dernier reçu, et garde sinon le
sien.
"""
from __future__ import annotations

import time
from typing import Any

REGLAGES_DEFAUT: dict[str, Any] = {"liste_applis": True}


def reglages(pc: dict[str, Any]) -> dict[str, Any]:
    brut = pc.get("accueil_options") if isinstance(pc.get("accueil_options"), dict) else {}
    return {"liste_applis": bool(brut.get("liste_applis", REGLAGES_DEFAUT["liste_applis"]))}


def regler(pc: dict[str, Any], changements: dict[str, Any]) -> dict[str, Any]:
    """Pose des réglages (clés inconnues ignorées) ; rend les réglages effectifs."""
    nouveaux = {**reglages(pc), **{k: bool(v) for k, v in changements.items() if k in REGLAGES_DEFAUT}}
    avant = int((pc.get("accueil_options") or {}).get("depuis") or 0) if isinstance(pc.get("accueil_options"), dict) else 0
    pc["accueil_options"] = {**nouveaux, "depuis": max(int(time.time() * 1000), avant + 1)}
    return reglages(pc)


def pour_releve(pc: dict[str, Any]) -> dict[str, Any] | None:
    """Rien tant que Home Assistant n'a rien réglé : l'appareil garde ses choix."""
    brut = pc.get("accueil_options")
    if not isinstance(brut, dict) or not brut.get("depuis"):
        return None
    return {**reglages(pc), "depuis": int(brut["depuis"])}
