"""1.94 · Le journal « qui a fait quoi » des gestes d'autorisation (spec §0.7, règle 1).

Chaque geste du tableau (ouvrir, bloquer, lever un blocage, laisser passer, rattacher un site…) y est consigné : quand, qui
(le compte Home Assistant, ou l'appareil), quoi (site, logiciel, catégorie), pour qui (tout le monde, une personne, un
appareil), avant → après. Gardé 90 jours dans `.storage/pc_parental_journal_gestes`, repris dans le journal de bord de
Home Assistant et par l'événement `pc_parental_geste` (`{qui, action, cible, portee, avant, apres}`).

C'est ce journal qui remplace le code de déverrouillage pour un administrateur ou un Décideur : le geste est signé de son
compte, et reste lisible.
"""
from __future__ import annotations

import time
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.storage import Store

EVENEMENT = "pc_parental_geste"
_CLE = "pc_parental_journal_gestes"
JOURS = 90
MAX_LIGNES = 2000


def _etat(hass: HomeAssistant) -> dict[str, Any]:
    return hass.data.setdefault(_CLE, {"lignes": [], "store": None})


async def charger(hass: HomeAssistant) -> None:
    st = _etat(hass)
    st["store"] = Store(hass, 1, "pc_parental_journal_gestes")
    donnees = await st["store"].async_load() or {}
    st["lignes"] = [l for l in (donnees.get("lignes") or []) if isinstance(l, dict)]
    _elaguer(st)


def _elaguer(st: dict[str, Any], maintenant: float | None = None) -> None:
    limite = (maintenant or time.time()) - JOURS * 86400
    st["lignes"] = [l for l in st["lignes"] if float(l.get("ts") or 0) >= limite][-MAX_LIGNES:]


def noter(hass: HomeAssistant, qui: str, action: str, cible: str, portee: str, avant: str = "", apres: str = "",
          par: str = "compte", maintenant: float | None = None) -> dict[str, Any]:
    """Consigne un geste et le publie (événement + journal de bord). `par` : « compte » (un compte HA) ou « appareil »."""
    st = _etat(hass)
    ligne = {"ts": maintenant or time.time(), "qui": str(qui)[:80], "par": par, "action": str(action)[:30],
             "cible": str(cible)[:120], "portee": str(portee)[:80], "avant": str(avant)[:60], "apres": str(apres)[:60]}
    st["lignes"].append(ligne)
    _elaguer(st, maintenant)
    if st["store"] is not None:
        st["store"].async_delay_save(lambda: {"lignes": st["lignes"]}, 10)
    hass.bus.async_fire(EVENEMENT, {k: ligne[k] for k in ("qui", "action", "cible", "portee", "avant", "apres")})
    hass.bus.async_fire("logbook_entry", {"name": "Vision", "domain": "pc_parental", "message":
                        f"{ligne['qui']} : {ligne['action']} {ligne['cible']} ({ligne['portee']}) {ligne['avant']} → {ligne['apres']}".strip()})
    return ligne


def recents(hass: HomeAssistant, n: int = 50) -> list[dict[str, Any]]:
    return list(reversed(_etat(hass)["lignes"][-max(1, min(500, n)):]))


def dernier(hass: HomeAssistant, cible: str, actions: tuple[str, ...] = ()) -> dict[str, Any] | None:
    """Le dernier geste sur cette cible (éventuellement parmi ces actions), le plus récent d'abord."""
    bas = cible.strip().lower()
    for l in reversed(_etat(hass)["lignes"]):
        if str(l.get("cible") or "").strip().lower() == bas and (not actions or l.get("action") in actions):
            return l
    return None
