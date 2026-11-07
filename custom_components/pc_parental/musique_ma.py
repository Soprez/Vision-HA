"""« Music Assistant » à l'accueil (1.94, demande de l'auteur) : une tuile qui ouvre l'interface de Music Assistant.

Depuis la 2.5.12.14.3, Vision ne fait plus lui-même ce que Music Assistant ne propose pas en service Home Assistant (file complète,
retirer ou déplacer un titre, fondu, ajout automatique, appairage Sendspin, retrait d'un favori) : il répond « à faire dans Music
Assistant ». Cette tuile mène en un geste à l'endroit où le faire.

Ce que Home Assistant dit à l'appareil (bloc `musique_ma` de /veille, jamais d'adresse interne ni de jeton) :
- `installe` : l'intégration Music Assistant est chargée (sinon, ni tuile ni bloc : « invisible si Music Assistant n'est pas là ») ;
- `ingress` : le chemin du panneau de l'extension dans Home Assistant (« /d5369777_music_assistant »), quand il existe : on s'y connecte
  avec la session Home Assistant, d'où qu'on soit ;
- `port` : le port de l'interface de Music Assistant (8095) : l'appareil le joint directement sur le réseau local, à l'adresse
  locale de Home Assistant qu'il connaît déjà (l'adresse interne de l'extension, elle, n'est pas joignable d'un appareil) ;
- `lecteur` : le lecteur « · Vision » de cet appareil, quand on le connaît (pour aller droit à sa file, si l'interface le permet).

Droit : la fonction de rôle « Ouvrir Music Assistant » (`musique_ma`), sensible ★ : l'interface de Music Assistant change la musique,
les comptes et les réglages de tout le lieu. Par défaut, seul le rôle Décideur l'a ; un Demandeur n'a ni la tuile ni le bloc.
"""
from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlparse

from homeassistant.core import HomeAssistant

from . import modules, musique, roles

FONCTION = "musique_ma"
PORT_DEFAUT = 8095
# La clé de la phrase que les réponses « à faire dans Music Assistant » portent : l'appli la rend cliquable (champ `ouvrir_ma`).
CLE_A_FAIRE = "mus.a_faire_dans_ma"


def installe(hass: HomeAssistant) -> bool:
    return musique.entree(hass) is not None


def panneau(hass: HomeAssistant) -> str:
    """Le chemin du panneau de l'extension Music Assistant dans la barre latérale de Home Assistant (« » s'il n'y en a pas)."""
    panneaux = hass.data.get("frontend_panels") or {}
    try:
        items = list(panneaux.items())
    except AttributeError:
        return ""
    for chemin, p in items:
        chemin = str(chemin or "")
        titre = str(getattr(p, "sidebar_title", "") or "")
        if re.sub(r"[^a-z]", "", chemin.lower()).endswith("musicassistant") or re.sub(r"[^a-z]", "", titre.lower()) == "musicassistant":
            return "/" + chemin.strip("/")
    return ""


def port(hass: HomeAssistant) -> int:
    """Le port de l'interface de Music Assistant d'après l'entrée d'intégration (8095 par défaut)."""
    e = musique.entree(hass)
    url = str(((getattr(e, "data", None) or {}).get("url")) or "")
    try:
        p = urlparse(url).port
    except ValueError:
        p = None
    return int(p) if p and 1 <= int(p) <= 65535 else PORT_DEFAUT


def autorise(store, pc: dict[str, Any] | None) -> bool:
    """Cet appareil peut-il ouvrir Music Assistant ? (module Accueil actif, fonction de rôle accordée)"""
    return pc is not None and not modules.coupe(store, "accueil") and roles.a(store, pc, FONCTION)


def infos(hass: HomeAssistant, coord, pc: dict[str, Any] | None) -> dict[str, Any] | None:
    """Le bloc `musique_ma` de /veille pour cet appareil, ou None (Music Assistant absent, module coupé, droit refusé)."""
    if not installe(hass) or not autorise(coord.store, pc):
        return None
    bloc: dict[str, Any] = {"installe": True, "port": port(hass), "ingress": panneau(hass)}
    try:
        from . import party

        lecteur = party.lecteur_propre(hass, coord.store, pc)[0]
    except Exception:  # noqa: BLE001 - sans lecteur connu, la tuile ouvre l'accueil de Music Assistant
        lecteur = ""
    if lecteur:
        bloc["lecteur"] = lecteur
    return bloc


def reponse_erreur(err: BaseException, langue: str | None) -> dict[str, Any]:
    """Le texte d'une erreur de musique pour l'appli ; la phrase « à faire dans Music Assistant » porte `ouvrir_ma` (cliquable)."""
    from . import textes

    sortie: dict[str, Any] = {"retour": textes.erreur(err, langue)}
    if getattr(err, "cle", "") == CLE_A_FAIRE:
        sortie["ouvrir_ma"] = True
    return sortie
