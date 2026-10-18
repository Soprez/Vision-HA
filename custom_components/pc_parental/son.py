"""Niveau sonore (backlog n° 31, validé en essai le 11/10) : le bruit de la pièce, mesuré par le micro de l'appareil.

Module activable, **coupé par défaut** (INACTIFS_PAR_DEFAUT), puis activé appareil par appareil (désactivé par défaut).

- L'appareil mesure **sur place** un niveau en dB relatifs (0 à 100, calibrable) ; il n'enregistre et n'envoie
  **jamais d'audio** : seuls des nombres remontent dans le relevé (`son` : niveau, maximum, dépassements).
- L'appareil détecte lui-même un dépassement (au-dessus du seuil pendant la durée de « sensibilité » : un pic court
  ne compte pas) et l'affiche aussitôt ; Home Assistant compte les alertes, pendant les plages actives seulement.
- À la N-ième alerte (3 par défaut), coupure de l'appareil pendant la durée réglée : la même dérogation que le service
  `lock` (au rang des automatisations). Le compteur revient à zéro après X minutes calmes, et après une coupure.
- Événements libres pour les automatisations : `vision_son_seuil_depasse` et `vision_son_coupure`.
- Capteur « Niveau sonore » par appareil (sensor.py).
- Contrat avec les appareils : docs/son-contrat-appareils.md.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from . import modules
from .const import MODE_LOCKED

_LOGGER = logging.getLogger(__name__)

MODULE = "son"
EV_SEUIL = "vision_son_seuil_depasse"
EV_COUPURE = "vision_son_coupure"

# Réglages d'un appareil : défauts sûrs (module coupé ; une fois allumé, seuil prudent, coupure après des alertes).
REGLAGES_DEFAUT: dict[str, Any] = {
    "actif": False,              # l'appareil mesure (en plus du module, allumé pour toute l'installation)
    "seuil_db": 75,              # dB relatifs (0 = silence numérique, 100 = pleine échelle du micro)
    "sensibilite_s": 5,          # secondes au-dessus du seuil avant une alerte (un pic plus court est ignoré)
    "alertes_avant_coupure": 3,  # la coupure vient à cette alerte ; 0 = jamais de coupure (alertes seules)
    "coupure_min": 15,           # durée de la coupure
    "calme_min": 10,             # sans dépassement pendant ce temps, le compteur revient à zéro
    "plages": [],                # [{"debut": "HH:MM", "fin": "HH:MM"}] ; vide = toute la journée
    "calibration_db": 0,         # ajouté à la mesure de l'appareil (micros très différents)
}
BORNES: dict[str, tuple[float, float]] = {
    "seuil_db": (30, 100), "sensibilite_s": (1, 120), "alertes_avant_coupure": (0, 10),
    "coupure_min": (1, 240), "calme_min": (1, 240), "calibration_db": (-30, 30),
}
MAX_DEPASSEMENTS = 20  # par relevé : un appareil qui s'emballe ne remplit pas le journal


def actif_module(store) -> bool:
    return not modules.coupe(store, MODULE)


def reglages(pc: dict[str, Any]) -> dict[str, Any]:
    brut = (pc.get("son") or {}).get("reglages") or {}
    r = {**REGLAGES_DEFAUT, **{k: v for k, v in dict(brut).items() if k in REGLAGES_DEFAUT}}
    for k, (bas, haut) in BORNES.items():
        try:
            r[k] = int(min(haut, max(bas, float(r[k]))))
        except (TypeError, ValueError):
            r[k] = REGLAGES_DEFAUT[k]
    r["actif"] = bool(r["actif"])
    r["plages"] = [p for p in (r["plages"] if isinstance(r["plages"], list) else []) if _plage_valide(p)]
    return r


def regler(pc: dict[str, Any], changements: dict[str, Any]) -> dict[str, Any]:
    """Pose des réglages (les clés inconnues sont ignorées, les valeurs bornées) ; rend les réglages effectifs."""
    son = pc.setdefault("son", {})
    son["reglages"] = {**(son.get("reglages") or {}), **{k: v for k, v in changements.items() if k in REGLAGES_DEFAUT}}
    son["reglages"] = reglages(pc)
    return son["reglages"]


def etat(pc: dict[str, Any]) -> dict[str, Any]:
    son = pc.setdefault("son", {})
    e = son.setdefault("etat", {})
    e.setdefault("alertes", 0)
    e.setdefault("dernier", 0.0)      # horodatage du dernier dépassement compté
    e.setdefault("vu_jusqua", 0.0)    # le plus récent dépassement déjà reçu (un relevé répété ne compte pas deux fois)
    e.setdefault("niveau", None)
    e.setdefault("maximum", None)
    e.setdefault("maj", 0.0)
    return e


def mesure_active(store, pc: dict[str, Any]) -> bool:
    return actif_module(store) and reglages(pc)["actif"]


# ----------------------------------------------------------------------------- Plages horaires


def _minutes(hhmm: Any) -> int | None:
    try:
        h, m = str(hhmm).split(":")
        v = int(h) * 60 + int(m)
        return v if 0 <= v <= 24 * 60 else None
    except (ValueError, AttributeError):
        return None


def _plage_valide(p: Any) -> bool:
    return isinstance(p, dict) and _minutes(p.get("debut")) is not None and _minutes(p.get("fin")) is not None


def dans_plages(plages: list[dict[str, Any]], moment: datetime) -> bool:
    """Vide : toujours. Une plage qui passe minuit (22:00 → 07:00) est permise."""
    if not plages:
        return True
    m = moment.hour * 60 + moment.minute
    for p in plages:
        d, f = _minutes(p["debut"]), _minutes(p["fin"])
        if d == f or (d < f and d <= m < f) or (d > f and (m >= d or m < f)):
            return True
    return False


# ----------------------------------------------------------------------------- Relevé


def pour_releve(store, pc: dict[str, Any]) -> dict[str, Any]:
    """Le bloc `son` de la réponse au relevé : `{"actif": false}` suffit à arrêter la mesure de l'appareil."""
    if not mesure_active(store, pc):
        return {"actif": False}
    r = reglages(pc)
    e = etat(pc)
    return {
        "actif": True, "seuil_db": r["seuil_db"], "sensibilite_s": r["sensibilite_s"], "plages": r["plages"],
        "calibration_db": r["calibration_db"], "alertes": int(e["alertes"]), "alertes_avant_coupure": r["alertes_avant_coupure"],
    }


def _nombre(v: Any, bas: float = 0, haut: float = 130) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return round(min(haut, max(bas, f)), 1) if f == f else None


def recevoir(hass: HomeAssistant, store, pc: dict[str, Any], bloc: Any, maintenant: float | None = None) -> bool:
    """Ce que l'appareil a mesuré. Rend True si le magasin a changé (alerte comptée, coupure, remise à zéro).

    Seuls des nombres sont lus (niveau, maximum, horodatage et niveau de chaque dépassement) : tout autre champ est
    ignoré, et rien d'autre n'est gardé.
    """
    t = time.time() if maintenant is None else maintenant
    e = etat(pc)
    change = False
    r = reglages(pc)
    # Remise à zéro après une période calme (même sans relevé du module, pour ne pas garder un compteur périmé).
    if e["alertes"] and t - float(e["dernier"] or 0) >= r["calme_min"] * 60:
        e["alertes"] = 0
        change = True
    if not isinstance(bloc, dict) or not mesure_active(store, pc):
        return change
    niveau = _nombre(bloc.get("niveau"))
    if niveau is not None:
        e["niveau"] = niveau
        e["maximum"] = _nombre(bloc.get("maximum"))
        e["maj"] = t
    nom = str(pc.get("name") or pc.get("nom") or pc.get("id"))
    moment = dt_util.now()
    depassements = bloc.get("depassements") if isinstance(bloc.get("depassements"), list) else []
    for d in depassements[:MAX_DEPASSEMENTS]:
        if not isinstance(d, dict):
            continue
        quand = _nombre(d.get("ts"), 0, 1e13)
        if quand is None:
            continue
        quand = quand / 1000 if quand > 1e11 else quand  # millisecondes de l'appareil, ou secondes
        if quand <= float(e["vu_jusqua"] or 0) or quand > t + 300:
            continue
        e["vu_jusqua"] = quand
        change = True
        if not dans_plages(r["plages"], dt_util.as_local(dt_util.utc_from_timestamp(quand)) if quand else moment):
            continue
        e["alertes"] = int(e["alertes"]) + 1
        e["dernier"] = quand
        n, seuil_coupure = e["alertes"], r["alertes_avant_coupure"]
        hass.bus.async_fire(EV_SEUIL, {
            "appareil": pc.get("id"), "nom": nom, "niveau": _nombre(d.get("db")), "seuil": r["seuil_db"],
            "alerte": n, "alertes_avant_coupure": seuil_coupure,
        })
        if seuil_coupure and n >= seuil_coupure:
            store.set_override(pc, MODE_LOCKED, r["coupure_min"])
            hass.bus.async_fire(EV_COUPURE, {"appareil": pc.get("id"), "nom": nom, "minutes": r["coupure_min"], "alertes": n})
            e["alertes"] = 0
    return change
