"""Livraison des demandes d'accès aux Décideurs : une demande déposée n'est jamais perdue en route.

Constat du 19/10 : des demandes faites pendant des redémarrages de Home Assistant n'ont jamais atteint les parents,
et le seul canal du Décideur (l'appli Vision) reçoit la demande par un message qui ne vivait que 15 minutes, remis
une seule fois, sans nouvel essai : un téléphone qui se déconnecte souvent la rate. Ce module :

- **file persistante** : le message « acces » vers chaque appareil Décideur vit tant que la demande est ouverte
  (`DUREE_S`, la même que `demandes.en_attente`), au lieu de `MESSAGE_TTL` ; un appareil hors ligne le reçoit à son
  prochain relevé ;
- **accusé de réception** : la remise au relevé est notée dans la demande (`livree`: {appareil: horodatage}) ; un
  message qui vise une demande déjà réglée (ou tombée) n'est pas remis ;
- **secours** : une demande ouverte qu'aucun appareil Décideur n'a reçue au bout de `SECOURS_S` part une fois sur
  l'appli Compagnon de **tous** les Décideurs (`secours`: horodatage), quel que soit leur choix de canal ;
- **notification Home Assistant** : recréée au démarrage pour chaque demande ouverte (Home Assistant ne garde pas les
  notifications persistantes d'un redémarrage à l'autre), retirée quand la demande est réglée ;
- **capteur** `sensor.vision_demandes_en_attente` (sensor.py), comme prévu par la SPEC-1.94 (§3 bis, point 5).
"""

from __future__ import annotations

import logging
import time
from typing import Any

from homeassistant.components import persistent_notification
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.event import async_track_time_interval

from . import demandes, textes
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

DUREE_S = 24 * 3600          # une demande ouverte vit 24 h (demandes.en_attente)
SECOURS_S = 5 * 60           # personne ne l'a reçue au bout de 5 min : appli Compagnon de tous les Décideurs
VERIFIER_S = 60              # contrôle des demandes ouvertes, toutes les minutes


def notif_id(pc_id: str, demande_id: str) -> str:
    return "{}_acces_{}_{}".format(DOMAIN, pc_id, demande_id)


def ouvertes(store) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    """Toutes les demandes d'accès ouvertes, avec leur appareil, de la plus ancienne à la plus récente."""
    sortie = []
    for pc in list(store.pcs.values()):
        for d in demandes.en_attente(pc):
            sortie.append((pc, d))
    return sorted(sortie, key=lambda x: float(x[1].get("ts") or 0))


def trouver(store, pc_id: str, demande_id: str) -> dict[str, Any] | None:
    pc = store.pcs.get(str(pc_id))
    if not pc:
        return None
    for d in demandes.en_attente(pc):
        if d.get("id") == demande_id:
            return d
    return None


def expiration(d: dict[str, Any]) -> float:
    """Jusqu'à quand le message de cette demande attend son appareil Décideur."""
    return float(d.get("ts") or time.time()) + DUREE_S


def texte(hass: HomeAssistant, pc: dict[str, Any], d: dict[str, Any], langue: str) -> str:
    """Le texte court d'une demande (secours, notification recréée) à partir de ce qui est gardé."""
    t = textes.t("dem.acces", langue, qui=d.get("prenom") or pc.get("name"), nom=d.get("libelle") or d.get("cle"))
    if d.get("motif"):
        t = "{} ({})".format(t, str(d["motif"])[:120])
    return t


def notifier_ha(hass: HomeAssistant, pc: dict[str, Any], d: dict[str, Any]) -> None:
    langue = textes.ha(hass)
    persistent_notification.async_create(
        hass, texte(hass, pc, d, langue) + "\n\n" + textes.ou_decider(langue), title=textes.t("dem.titre_ha", langue),
        notification_id=notif_id(pc["id"], d["id"]),
    )


def effacer_ha(hass: HomeAssistant, pc_id: str, demande_id: str) -> None:
    persistent_notification.async_dismiss(hass, notif_id(pc_id, demande_id))


def a_remettre(store, decideur_pid: str, messages: list[dict[str, Any]], maintenant: float | None = None) -> list[dict[str, Any]]:
    """Au relevé d'un appareil : retire les messages de demandes déjà réglées et note la remise des autres
    (accusé de réception). Rend les messages à remettre."""
    t = time.time() if maintenant is None else maintenant
    sortie = []
    for m in messages:
        acces = m.get("acces")
        if not isinstance(acces, dict):
            sortie.append(m)
            continue
        d = trouver(store, str(acces.get("pc") or ""), str(acces.get("id") or ""))
        if d is None:
            continue  # réglée ailleurs ou tombée : rien à sonner
        d.setdefault("livree", {})[str(decideur_pid)] = round(t, 1)
        sortie.append(m)
    return sortie


def livree(d: dict[str, Any]) -> bool:
    return bool(d.get("livree"))


async def _secours(hass: HomeAssistant, store, pc: dict[str, Any], d: dict[str, Any]) -> int:
    from . import canaux, notifs_mobiles

    services = canaux.compagnon_tous(hass, store)
    if not services:
        return 0
    return await notifs_mobiles.envoyer(hass, store, pc, d, texte(hass, pc, d, textes.ha(hass)), services=services)


async def async_verifier(hass: HomeAssistant, coord, maintenant: float | None = None) -> int:
    """Une demande ouverte qu'aucun Décideur n'a reçue depuis SECOURS_S part en secours (une fois). Rend le nombre
    de demandes envoyées en secours."""
    t = time.time() if maintenant is None else maintenant
    store = coord.store
    n = 0
    for pc, d in ouvertes(store):
        if livree(d) or d.get("secours") or t - float(d.get("ts") or t) < SECOURS_S:
            continue
        d["secours"] = round(t, 1)
        n += 1
        try:
            envoyes = await _secours(hass, store, pc, d)
        except Exception:  # noqa: BLE001 - le secours ne casse jamais le contrôle
            _LOGGER.debug("Secours de la demande %s impossible", d.get("id"), exc_info=True)
            envoyes = 0
        _LOGGER.warning("Vision : demande « %s » de « %s » reçue par aucun Décideur depuis %d min : secours sur %d "
                        "téléphone(s) (appli Compagnon), notification Home Assistant en place.",
                        d.get("libelle") or d.get("cle"), pc.get("name"), int((t - float(d.get("ts") or t)) // 60),
                        envoyes)
    if n:
        await store.async_save()
        coord.async_update_listeners()
    return n


def demarrer(hass: HomeAssistant, coord) -> Any:
    """Au démarrage : la notification HA de chaque demande ouverte (perdue au redémarrage), puis le contrôle
    périodique. Rend la fonction d'arrêt du contrôle."""
    for pc, d in ouvertes(coord.store):
        notifier_ha(hass, pc, d)

    @callback
    def _tic(_now: Any) -> None:
        hass.async_create_task(async_verifier(hass, coord))

    return async_track_time_interval(hass, _tic, _timedelta(VERIFIER_S))


def _timedelta(s: int):
    from datetime import timedelta

    return timedelta(seconds=s)


def pour_capteur(hass: HomeAssistant, store) -> list[dict[str, Any]]:
    """Les demandes ouvertes, pour sensor.vision_demandes_en_attente (SPEC-1.94 : id, type, appareil, personne,
    objet, depuis), plus leur livraison (appareils qui l'ont reçue, secours)."""
    from homeassistant.util import dt as dt_util

    sortie = []
    for pc, d in ouvertes(store):
        recu_par = [str((store.pcs.get(p) or {}).get("name") or p) for p in (d.get("livree") or {})]
        sortie.append({
            "id": d.get("id"), "type": d.get("genre"), "appareil": pc.get("name"), "personne": d.get("prenom") or "",
            "objet": d.get("libelle") or d.get("cle"),
            "depuis": dt_util.as_local(dt_util.utc_from_timestamp(float(d.get("ts") or 0))).isoformat(timespec="seconds"),
            "recue_par": recu_par, "secours": bool(d.get("secours")),
        })
    return sortie
