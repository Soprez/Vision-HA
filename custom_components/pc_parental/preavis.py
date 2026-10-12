"""Préavis avant coupure (Vision 1.85.0, point 14.1 validé le 2026-10-09 ; délai réglable, lot Accueil).

Avant qu'un appareil ne se ferme, il décompte X minutes, puis 5 et 1 minute(s) si elles sont plus petites que X.
X est le réglage « Avertir avant fermeture » (number, `store.warn_minutes`, 0 à 60, défaut 5) : un seul
délai pour l'administrateur, qui règle aussi l'ancien avertissement `warn` et le préavis des plages
d'étiquettes. X = 0 : aucun préavis. Home Assistant calcule ici
**quand** l'appareil va se fermer et **pourquoi** ; l'appareil (appli, agent PC) affiche le décompte
lui-même, à l'heure dite, même entre deux relevés.

Ce qui ferme un appareil, et que l'on sait prévoir :
- la prochaine plage de l'agenda (module « Plages ») ;
- la fin d'un temps accordé (« +30 min », « jusqu'à la prochaine plage ») si l'agenda est fermé à ce
  moment-là ;
- la limite du jour d'une appli (module « Temps d'écran ») : l'appli seule se ferme, l'appareil non.

Relevé : `preavis` = {"fin": <horodatage en secondes>, "motif": "…", "seuils": [X, 5, 1]} quand une
fermeture tombe dans les X prochaines minutes (plus 30 s de marge entre deux relevés) ; `preavis_apps` =
[{"nom", "libelle", "reste_s", "seuils"}] pour les applis dont la limite approche. Module « Préavis » coupé : rien de tout cela (l'ancien `warn` reste
réglé par le module « Messages », pour les appareils d'avant).
"""

from __future__ import annotations

import time
from datetime import datetime
from typing import Any

from homeassistant.util import dt as dt_util

# Les rappels fixes, dits après le premier (X) s'ils sont plus petits que lui.
RAPPELS = (5, 1)
DELAI_MAX = 60
MARGE_S = 30


def delai(store) -> int:
    """X : le délai réglé, en minutes (0 à 60 ; illisible = le défaut)."""
    from .const import WARN_MINUTES

    try:
        x = int(getattr(store, "warn_minutes", WARN_MINUTES))
    except (TypeError, ValueError):
        x = WARN_MINUTES
    return max(0, min(DELAI_MAX, x))


def seuils(x: int) -> list[int]:
    """[X] puis 5 et 1 s'ils sont plus petits que X : 10 → [10, 5, 1] ; 5 → [5, 1] ; 3 → [3, 1] ; 1 → [1] ; 0 → []."""
    if x <= 0:
        return []
    return [x] + [r for r in RAPPELS if r < x]


def fenetre_s(x: int) -> int:
    """Combien de secondes avant la fermeture le relevé commence à l'annoncer (0 : jamais)."""
    return x * 60 + MARGE_S if x > 0 else 0


def _ts(moment: datetime) -> float:
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=dt_util.get_default_time_zone())
    return moment.timestamp()


def fermeture(store, pc: dict[str, Any], maintenant: datetime) -> tuple[float, str] | None:
    """(heure de fermeture, motif) de l'appareil entier, si elle est prévisible ; sinon None."""
    from . import modules, textes
    from .const import MODE_LOCKED, MODE_OPEN
    from .planner import is_locked, next_lock

    etat = store.evaluate(pc)
    if etat.get("locked"):
        return None
    regles = store.regles_pour(pc)
    candidats: list[tuple[float, str]] = []
    override = etat.get("override") or {}
    fin_derog = float(override.get("until") or 0)
    if override.get("mode") == MODE_OPEN and fin_derog:
        # Fin du temps accordé : l'appareil se ferme si l'agenda l'est à ce moment-là.
        moment_fin = dt_util.as_local(dt_util.utc_from_timestamp(fin_derog)).replace(tzinfo=None)
        if not modules.coupe(store, "plages") and is_locked(regles, moment_fin):
            candidats.append((fin_derog, textes.t("preavis.fin_temps", textes.pour(getattr(store, "hass", None), pc))))
    elif override.get("mode") != MODE_LOCKED and not modules.coupe(store, "plages"):
        prochaine = next_lock(regles, maintenant)
        if prochaine is not None:
            candidats.append((_ts(prochaine), textes.t("preavis.debut_plage", textes.pour(getattr(store, "hass", None), pc))))
    if not candidats:
        return None
    return min(candidats)


def calcul(store, pc: dict[str, Any], maintenant: datetime) -> dict[str, Any]:
    """Les champs du relevé : `preavis` et `preavis_apps` (vides si rien d'imminent ou module coupé)."""
    from . import modules

    sortie: dict[str, Any] = {}
    if modules.coupe(store, "preavis"):
        return sortie
    x = delai(store)
    if x <= 0:
        return sortie
    les_seuils, fenetre = seuils(x), fenetre_s(x)
    maintenant_s = time.time()
    f = fermeture(store, pc, maintenant)
    if f is not None and 0 < f[0] - maintenant_s <= fenetre:
        sortie["preavis"] = {"fin": int(f[0]), "motif": f[1], "seuils": list(les_seuils)}
    if not modules.coupe(store, "temps"):
        apps = []
        for nom, minutes in store.limites(pc).items():
            reste = minutes * 60 - store.temps_du_jour(pc, nom)
            if 0 < reste <= fenetre:
                apps.append({"nom": nom, "libelle": nom, "reste_s": int(reste), "seuils": list(les_seuils)})
        if apps:
            sortie["preavis_apps"] = sorted(apps, key=lambda a: a["reste_s"])
    return sortie
