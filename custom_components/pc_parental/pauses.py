"""Pause de la protection sur un appareil Android (Vision 1.70.0) : durée choisie, plafonnée, journalisée.

Le parent met Vision en pause sur son appareil (code parent, ou empreinte / visage) pour 5, 15, 30,
60 minutes ou « jusqu'à demain matin », jamais plus que la « Durée max de pause » de l'appareil
(fiche de l'appareil, administrateurs ; 60 min par défaut). L'appareil envoie ses pauses au relevé
(`pauses` : début, minutes, méthode) ; Home Assistant les journalise (avertissement + événement
`pc_parental_pause`).
"""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import HomeAssistant

from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

PAUSE_MAX_DEFAUT = 60
PAUSE_MAX_MIN, PAUSE_MAX_MAX = 5, 16 * 60  # 16 h : de quoi aller « jusqu'à demain matin »
EVENEMENT = f"{DOMAIN}_pause"
METHODES = {"code": "code de gestion", "biometrie": "empreinte ou visage", "deverrouillage": "déverrouillage à distance"}


def recevoir(hass: HomeAssistant, pc: dict[str, Any], pauses: Any, maximum: int) -> bool:
    """Les pauses rapportées par l'appareil : chacune journalisée une fois. True si du nouveau."""
    if not isinstance(pauses, list):
        return False
    vu = float(pc.get("derniere_pause") or 0)
    nouveau = False
    for p in pauses[:20]:
        if not isinstance(p, dict):
            continue
        try:
            debut = float(p.get("debut") or 0); minutes = int(p.get("minutes") or 0)
        except (TypeError, ValueError):
            continue
        if debut <= vu or minutes <= 0:
            continue
        methode = str(p.get("methode") or "code")
        depasse = minutes > maximum
        _LOGGER.warning("%s : pause de %d min sur « %s » (%s)%s.", PRODUIT, minutes, pc.get("name") or pc.get("id"),
                        METHODES.get(methode, methode), " — au-delà de la durée max !" if depasse else "")
        hass.bus.async_fire(EVENEMENT, {"pc": pc.get("id"), "nom": pc.get("name"), "minutes": minutes,
                                        "methode": methode, "debut": debut, "au_dela_du_max": depasse})
        vu = max(vu, debut)
        nouveau = True
    if nouveau:
        pc["derniere_pause"] = vu
    return nouveau
