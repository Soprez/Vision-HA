"""1.93.06 (retour d'essai) : la musique d'un appareil Android se met en pause quand une autre appli joue du son (focus audio).

- ``focus`` : ``toujours`` (défaut de l'appareil), ``applis`` (seulement si l'appli au premier plan est dans ``applis``)
  ou ``jamais`` (Vision ne demande pas le focus : comme avant la 1.93.06) ;
- ``applis`` : les noms de paquets Android (« com.google.android.youtube.tv »…) pour ``applis`` ; 1.93.06 : ``vision.entrees_tele`` désigne les entrées de la télé (HDMI, TV en direct) ;
- ``reprise`` : la musique repart d'elle-même quand l'autre son s'arrête ; non par défaut.

Réglé par le service ``pc_parental.musique_regler`` (administrateurs et automatisations), ou sur la télé elle-même
(Réglages › Musique) ; envoyé dans le bloc ``musique_options`` du relevé avec ``depuis`` (l'heure du réglage) : comme
``veille_options``, l'appareil applique un réglage de Home Assistant plus récent que le dernier reçu, et garde sinon le
sien.
"""
from __future__ import annotations

import re
import time
from typing import Any

MODES = ("toujours", "applis", "jamais")
REGLAGES_DEFAUT: dict[str, Any] = {"focus": "toujours", "applis": [], "reprise": False}
APPLIS_MAX = 50
# Un nom de paquet Android : des segments séparés par des points (lettres, chiffres, « _ »).
_PAQUET = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")


class PaquetInvalide(ValueError):
    """Un nom d'appli qui n'est pas un nom de paquet Android."""


def _applis(valeur: Any) -> list[str]:
    if isinstance(valeur, str):
        valeur = [v for v in re.split(r"[\s,;]+", valeur) if v]
    sortie: list[str] = []
    for v in valeur or []:
        v = str(v).strip()
        if not v:
            continue
        if not _PAQUET.match(v) or len(v) > 200:
            raise PaquetInvalide(v)
        if v not in sortie:
            sortie.append(v)
    return sortie[:APPLIS_MAX]


def reglages(pc: dict[str, Any]) -> dict[str, Any]:
    brut = pc.get("musique_options") if isinstance(pc.get("musique_options"), dict) else {}
    focus = brut.get("focus") if brut.get("focus") in MODES else REGLAGES_DEFAUT["focus"]
    try:
        applis = _applis(brut.get("applis"))
    except PaquetInvalide:
        applis = []
    return {"focus": focus, "applis": applis, "reprise": bool(brut.get("reprise", REGLAGES_DEFAUT["reprise"]))}


def regler(pc: dict[str, Any], changements: dict[str, Any]) -> dict[str, Any]:
    """Pose des réglages (clés inconnues ignorées) ; rend les réglages effectifs. Un nom d'appli invalide : refusé."""
    nouveaux = {**reglages(pc), **{k: v for k, v in changements.items() if k in REGLAGES_DEFAUT}}
    if nouveaux["focus"] not in MODES:
        raise ValueError(nouveaux["focus"])
    nouveaux["applis"] = _applis(nouveaux["applis"])
    nouveaux["reprise"] = bool(nouveaux["reprise"])
    avant = int((pc.get("musique_options") or {}).get("depuis") or 0) if isinstance(pc.get("musique_options"), dict) else 0
    pc["musique_options"] = {**nouveaux, "depuis": max(int(time.time() * 1000), avant + 1)}
    return reglages(pc)


def pour_releve(pc: dict[str, Any]) -> dict[str, Any] | None:
    """Rien tant que Home Assistant n'a rien réglé : l'appareil garde ses choix."""
    brut = pc.get("musique_options")
    if not isinstance(brut, dict) or not brut.get("depuis"):
        return None
    return {**reglages(pc), "depuis": int(brut["depuis"])}
