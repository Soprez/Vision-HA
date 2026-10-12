"""Volume de la Party (règles du projet, 2026-10-12, client Sendspin natif).

- Lecteur Vision (la télé sur laquelle on est, lecteur « · Vision ») : volume toujours à 100 %, aucun contrôle ;
  c'est la télé qui gère le son.
- Enceinte externe choisie (Sonos, Cast, autre lecteur de Music Assistant) : son volume reste réglable.
- Plusieurs enceintes ou un groupe : on règle le volume du GROUPE (« group volume » de Music Assistant, qui garde les
  écarts entre enceintes) ; le détail par enceinte est proposé en option. Une enceinte « · Vision » du groupe reste à
  100 % et n'a pas de réglage.

Et pour Music Assistant : le réglage MP3 des lecteurs « · Vision » (2.2.13) ne vaut que pour l'ancien lecteur
Squeezelite de Vision (identifiant = adresse MAC) ; un lecteur Sendspin choisit lui-même son format (FLAC).
"""
from __future__ import annotations

import logging
import re
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er

from . import musique

_LOGGER = logging.getLogger(__name__)

_MAC = re.compile(r"^[0-9a-f]{2}(:[0-9a-f]{2}){5}$", re.IGNORECASE)


def est_squeezelite(unique_id: str | None) -> bool:
    """Un lecteur Squeezelite de Music Assistant a pour identifiant une adresse MAC ; un lecteur Sendspin non."""
    return bool(unique_id) and bool(_MAC.match(str(unique_id)))


def est_vision(hass: HomeAssistant, eid: str) -> bool:
    """Un lecteur « · Vision » : la même définition que la règle des 100 % (musique.est_lecteur_vision)."""
    return bool(eid) and musique.est_lecteur_vision(hass, eid)


def membres(hass: HomeAssistant, eid: str) -> list[str]:
    """Les enceintes du groupe joué par [eid] (lui compris), ou [] s'il joue seul."""
    s = hass.states.get(eid) if eid else None
    m = s.attributes.get("group_members") if s is not None else None
    if not isinstance(m, (list, tuple)) or len(m) < 2:
        return []
    return [str(x) for x in m if isinstance(x, str) and x.startswith("media_player.")]


def _uid(hass: HomeAssistant, eid: str) -> str | None:
    e = er.async_get(hass).async_get(eid)
    return e.unique_id if e is not None and e.platform == musique.MA else None


def _volume(hass: HomeAssistant, eid: str) -> float | None:
    s = hass.states.get(eid)
    v = s.attributes.get("volume_level") if s is not None else None
    return float(v) if isinstance(v, (int, float)) else None


def volume_groupe(hass: HomeAssistant, eid: str) -> float | None:
    """Le volume du groupe : celui que tient Music Assistant, sinon la moyenne des enceintes réglables."""
    mass, uid = musique._client(hass), _uid(hass, eid)
    if mass is not None and uid and hasattr(getattr(mass, "players", None), "get"):
        try:
            p = mass.players.get(uid)
            gv = p.get("group_volume") if isinstance(p, dict) else getattr(p, "group_volume", None)
            if isinstance(gv, (int, float)):
                return max(0.0, min(1.0, gv / 100))
        except Exception:  # noqa: BLE001 - on retombe sur la moyenne
            pass
    vols = [v for m in membres(hass, eid) if not est_vision(hass, m) and (v := _volume(hass, m)) is not None]
    return round(sum(vols) / len(vols), 2) if vols else None


def etat(hass: HomeAssistant, eid: str) -> dict[str, Any]:
    """Ce que la Party montre du volume : le volume (groupe, enceinte, ou rien pour un lecteur Vision) et, pour un
    groupe, ses enceintes (nom, volume ; null pour une enceinte « · Vision »)."""
    m = membres(hass, eid)
    if m:
        return {"volume": volume_groupe(hass, eid), "groupe": True, "membres": [
            {"id": x, "nom": _nom(hass, x), "volume": None if est_vision(hass, x) else _volume(hass, x)} for x in m]}
    if est_vision(hass, eid):
        return {"volume": None, "groupe": False, "membres": []}
    return {"volume": _volume(hass, eid), "groupe": False, "membres": []}


def _nom(hass: HomeAssistant, eid: str) -> str:
    s = hass.states.get(eid)
    return str(s.attributes.get("friendly_name") or eid) if s is not None else eid


async def regler(hass: HomeAssistant, lecteur: str, v: float) -> bool:
    """Le bouton de volume de la Party. Vrai si c'est fait ici (groupe, ou lecteur Vision : rien à faire) ; faux pour
    une enceinte seule (l'appelant règle son volume)."""
    if membres(hass, lecteur):
        mass, uid = musique._client(hass), _uid(hass, lecteur)
        if mass is None or not uid or not hasattr(mass, "send_command"):
            raise musique.MusiqueIndisponible("mus.ma_injoignable")
        await mass.send_command("players/cmd/group_volume", player_id=uid, volume_level=round(v * 100))
        _LOGGER.info("Party : volume du groupe de %s à %d %%", lecteur, round(v * 100))
        return True
    if est_vision(hass, lecteur):
        await musique.tenir_volume(hass, lecteur)  # 2.4.0 : remis à 100 % s'il en était sorti
        return True
    return False


async def regler_membre(hass: HomeAssistant, lecteur: str, membre: str, v: float) -> None:
    """Le détail par enceinte : une enceinte du groupe, jamais une enceinte « · Vision » (la télé gère)."""
    if membre not in membres(hass, lecteur) or est_vision(hass, membre):
        raise musique.MusiqueIndisponible("party.volume")
    await hass.services.async_call("media_player", "volume_set", {"entity_id": membre, "volume_level": v}, blocking=True)
