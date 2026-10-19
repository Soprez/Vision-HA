"""2.5.12 (l'auteur, 18/10, point de sécurité) : « Protection désactivée sur <appareil> ».

Sur la TCL du salon, l'accessibilité de Vision était coupée depuis des heures sans que personne le sache : un
`am force-stop` de Vision suffit à ce qu'Android la retire (essayé sur le banc). Sans elle, Vision ne protège plus
rien sur un appareil Android (réglages, désinstallation, applis fermées à l'ouverture). Désormais :
- l'état se voit dans Home Assistant (capteur « Protection désactivée », classe problème, binary_sensor.py) ;
- les responsables sont prévenus par les canaux qu'ils ont choisis pour les alertes, avec une notification
  persistante et une ligne au journal, quand l'accessibilité reste coupée [DELAI_S] sur l'appareil d'un membre
  (pas d'alerte pendant une mise à jour ou un redémarrage de l'appli) ; « Protection rétablie » quand elle revient.

Appelé par le relevé de l'appareil (http.py), qui porte `protections` ({"accessibilite": bool, …}).
"""
from __future__ import annotations

import logging
import time
from typing import Any

from homeassistant.components import persistent_notification
from homeassistant.core import HomeAssistant

from . import roles, textes
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

DELAI_S = 120


def surveillee(store, pc: dict[str, Any]) -> bool:
    """Un appareil Android d'un membre (pas celui d'un responsable, qui n'a pas à être protégé)."""
    if str(pc.get("platform") or (pc.get("agent") or {}).get("platform") or "") != "android":
        return False
    return not (roles.a(store, pc, "gerer") or roles.a(store, pc, "approuver"))


def coupee(pc: dict[str, Any]) -> bool | None:
    """L'accessibilité de Vision est-elle coupée ? None : l'appareil ne l'a pas encore dit."""
    p = (pc.get("agent") or {}).get("protections") or {}
    return None if "accessibilite" not in p else not bool(p["accessibilite"])


def manquantes(pc: dict[str, Any]) -> list[str]:
    return sorted(k for k, v in ((pc.get("agent") or {}).get("protections") or {}).items() if not v)


def _prevenir(hass: HomeAssistant, coord, phrase: textes.Phrase, titre: str) -> None:
    from . import canaux

    langue_ha = textes.ha(hass)
    titre_ha = textes.t(titre, langue_ha)
    hass.async_create_task(canaux.compagnon_simple(hass, coord.store, "alertes", titre_ha, phrase.en(langue_ha)))
    hass.async_create_task(canaux.discord(hass, coord.store, "alertes", titre_ha, phrase.en(langue_ha)))
    for pid in sorted(set(roles.avec(coord.store, "approuver")) | set(roles.avec(coord.store, "gerer"))):
        if not canaux.vision_pour(hass, coord.store, pid, "alertes"):
            continue
        langue = textes.pour(hass, coord.store.pcs.get(pid))
        try:
            coord.store.add_message(pid, phrase.en(langue), titre=textes.t(titre, langue))
        except ValueError:
            continue


def releve(hass: HomeAssistant, coord, pc: dict[str, Any], maintenant: float | None = None) -> str:
    """Après chaque relevé : prévient si la protection tombe (ou revient). Rend « alerte », « retablie » ou ""."""
    maintenant = maintenant if maintenant is not None else time.time()
    etat = pc.setdefault("protection_alerte", {})
    c = coupee(pc)
    nom = str(pc.get("name") or pc.get("id"))
    ident = f"{DOMAIN}_protection_{pc.get('id')}"
    if c is None or not surveillee(coord.store, pc):
        return ""
    if c:
        depuis = etat.setdefault("coupee_depuis", maintenant)
        if etat.get("alertee") or maintenant - depuis < DELAI_S:
            return ""
        etat["alertee"] = maintenant
        phrase = textes.Phrase("prot.coupee", nom=nom)
        langue_ha = textes.ha(hass)
        persistent_notification.async_create(hass, phrase.en(langue_ha), title=textes.t("prot.titre", langue_ha), notification_id=ident)
        hass.bus.async_fire("logbook_entry", {"name": "Vision", "domain": DOMAIN, "message": phrase.en(langue_ha)})
        hass.bus.async_fire(f"{DOMAIN}_protection_desactivee", {"appareil": pc.get("id"), "nom": nom, "manquantes": manquantes(pc)})
        _LOGGER.warning("Vision : protection désactivée sur « %s » (accessibilité coupée depuis %d s).", nom, int(maintenant - depuis))
        _prevenir(hass, coord, phrase, "prot.titre")
        return "alerte"
    alertee = etat.get("alertee")
    pc["protection_alerte"] = {}
    if not alertee:
        return ""
    phrase = textes.Phrase("prot.retablie", nom=nom)
    langue_ha = textes.ha(hass)
    persistent_notification.async_dismiss(hass, ident)
    hass.bus.async_fire("logbook_entry", {"name": "Vision", "domain": DOMAIN, "message": phrase.en(langue_ha)})
    hass.bus.async_fire(f"{DOMAIN}_protection_retablie", {"appareil": pc.get("id"), "nom": nom})
    _LOGGER.warning("Vision : protection rétablie sur « %s ».", nom)
    _prevenir(hass, coord, phrase, "prot.titre_retablie")
    return "retablie"
