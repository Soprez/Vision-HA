"""1.93.05 (l'auteur, retour 36) : ce que l'écran de veille d'un appareil Android montre en plus de ses pages.

- ``lecteur`` : le bandeau « Lecteur musique » (pochette, titre, artiste, lecture/pause) pendant la lecture de la
  musique de cet appareil ; non par défaut.
- ``qr`` (1.93.05, retour 39) : le coin du QR code des invités (mode soirée ouvert), ``bas_droite`` par défaut, ou
  ``cache`` ; ``logo`` : le coin de l'œil de Vision, ``bas_gauche`` (sa place d'avant) par défaut, ou ``cache``. Deux
  éléments ne partagent jamais un coin : un réglage qui en mettrait deux au même endroit est refusé (CoinPris).
- ``qr_taille`` (1.93.06) : la taille du QR des invités, sur la veille et sur le lecteur : ``petit`` (par défaut, à
  scanner de près, ≈ 1 m), ``moyen`` (≈ 1,5 m) ou ``grand`` (≈ 2 m, la taille d'avant).

Réglé par le service ``pc_parental.veille_regler`` (administrateurs et automatisations), ou sur la télé elle-même ;
envoyé dans le bloc ``veille_options`` du relevé avec ``depuis`` (l'heure du réglage) : l'appareil applique un réglage
de Home Assistant plus récent que le dernier reçu, et garde sinon son propre choix.
"""
from __future__ import annotations

import time
from typing import Any

COINS = ("haut_gauche", "haut_droite", "bas_gauche", "bas_droite")
PLACES = (*COINS, "cache")
TAILLES_QR = ("petit", "moyen", "grand")
REGLAGES_DEFAUT: dict[str, Any] = {"lecteur": False, "qr": "bas_droite", "logo": "bas_gauche", "qr_taille": "petit"}


class CoinPris(ValueError):
    """Le QR et le logo dans le même coin."""


def reglages(pc: dict[str, Any]) -> dict[str, Any]:
    brut = pc.get("veille_options") if isinstance(pc.get("veille_options"), dict) else {}
    qr = brut.get("qr") if brut.get("qr") in PLACES else REGLAGES_DEFAUT["qr"]
    logo = brut.get("logo") if brut.get("logo") in PLACES else REGLAGES_DEFAUT["logo"]
    taille = brut.get("qr_taille") if brut.get("qr_taille") in TAILLES_QR else REGLAGES_DEFAUT["qr_taille"]
    return {"lecteur": bool(brut.get("lecteur", REGLAGES_DEFAUT["lecteur"])), "qr": qr, "logo": logo, "qr_taille": taille}


def regler(pc: dict[str, Any], changements: dict[str, Any]) -> dict[str, Any]:
    """Pose des réglages (clés inconnues ignorées) ; rend les réglages effectifs."""
    nouveaux = {**reglages(pc), **{k: v for k, v in changements.items() if k in REGLAGES_DEFAUT}}
    nouveaux["lecteur"] = bool(nouveaux["lecteur"])
    if nouveaux["qr_taille"] not in TAILLES_QR:
        nouveaux["qr_taille"] = REGLAGES_DEFAUT["qr_taille"]
    if nouveaux["qr"] not in PLACES or nouveaux["logo"] not in PLACES:
        raise CoinPris(f"{nouveaux['qr']}|{nouveaux['logo']}")
    if nouveaux["qr"] != "cache" and nouveaux["qr"] == nouveaux["logo"]:
        raise CoinPris(nouveaux["qr"])
    # Toujours plus récent que le réglage d'avant (l'appareil n'applique que du plus récent).
    avant = int((pc.get("veille_options") or {}).get("depuis") or 0) if isinstance(pc.get("veille_options"), dict) else 0
    pc["veille_options"] = {**nouveaux, "depuis": max(int(time.time() * 1000), avant + 1)}
    return reglages(pc)


def pour_releve(pc: dict[str, Any]) -> dict[str, Any] | None:
    """Rien tant que Home Assistant n'a rien réglé : l'appareil garde ses choix."""
    brut = pc.get("veille_options")
    if not isinstance(brut, dict) or not brut.get("depuis"):
        return None
    return {**reglages(pc), "depuis": int(brut["depuis"])}
