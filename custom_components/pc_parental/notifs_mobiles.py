"""Demandes d'accès : notification à boutons sur le téléphone des Décideurs (appli compagnon Home Assistant).

Refonte des autorisations (2.5.0) : le Décideur répond d'un geste, depuis la notification, avec les mêmes choix
que dans Vision : 15 min, 1 h, Exception, Toujours disponible, Refuser. Android affiche les trois premiers boutons
(limite de l'appli compagnon) ; toucher la notification ouvre le tableau de Vision, où sont les cinq.

Le geste revient par l'événement `mobile_app_notification_action`. Il n'est pris en compte que s'il vient d'un
utilisateur Home Assistant administrateur, ou lié à une personne qui a un appareil habilité à approuver.
"""
from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.util import slugify

from . import roles, textes
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)
PREFIXE = "VISION_ACCES"
# (choix, décision, mode, minutes) : l'ordre des boutons.
CHOIX = (
    ("t15", "temporaire", "temps", 15),
    ("t60", "temporaire", "temps", 60),
    ("exception", "accorder", "exception", 0),
    ("toujours", "accorder", "toujours", 0),
    ("non", "non", "", 0),
)


def _personnes_decideurs(store) -> list[str]:
    return sorted({str(store.pcs[p].get("person") or "") for p in roles.avec(store, "approuver") if p in store.pcs} - {""})


def services_notif(hass: HomeAssistant, store) -> list[str]:
    """Les services notify.mobile_app_* des téléphones des Décideurs (par leurs traceurs de l'appli compagnon).

    2.5.6 (l'auteur, 15/10) : seulement ceux des Décideurs qui ont choisi l'appli Compagnon pour les demandes (canaux.py :
    par défaut un seul canal, l'appli Vision s'ils l'ont) ; avant, la demande arrivait en double."""
    from . import canaux

    return canaux.compagnon(hass, store, "demandes")


def _services_decideurs(hass: HomeAssistant, store) -> list[str]:
    """2.5.0 : tous les téléphones des Décideurs (gardé pour effacer ce qui a déjà été envoyé)."""
    ents = er.async_get(hass)
    devs = dr.async_get(hass)
    services: list[str] = []
    for personne in _personnes_decideurs(store):
        etat = hass.states.get(personne)
        for traceur in (etat.attributes.get("device_trackers") or []) if etat is not None else []:
            entree = ents.async_get(traceur)
            if entree is None or entree.platform != "mobile_app" or not entree.device_id:
                continue
            appareil = devs.async_get(entree.device_id)
            if appareil is None:
                continue
            service = "mobile_app_" + slugify(appareil.name_by_user or appareil.name or "")
            if hass.services.has_service("notify", service) and service not in services:
                services.append(service)
    return services


def _libelle(cle: str, langue: str) -> str:
    return {
        "t15": textes.t("notif.bouton_15", langue), "t60": textes.t("notif.bouton_60", langue),
        "exception": textes.t("notif.bouton_exception", langue), "toujours": textes.t("notif.bouton_toujours", langue),
        "non": textes.t("notif.bouton_non", langue),
    }[cle]


async def envoyer(hass: HomeAssistant, store, pc: dict[str, Any], demande: dict[str, Any], texte: str) -> int:
    """Envoie la demande aux téléphones des Décideurs. Rend le nombre de téléphones prévenus."""
    langue = textes.ha(hass)
    # FAILLE DE SÉCURITÉ (l'auteur, 16/10) : accorder exige l'empreinte ou le code à chaque fois, ce qu'un bouton de
    # l'appli Compagnon ne demande pas : seul « Refuser » reste ; toucher la notification ouvre Vision.
    actions = [{"action": f"{PREFIXE}|{pc['id']}|{demande['id']}|{cle}", "title": _libelle(cle, langue)}
               for cle, *_ in CHOIX if cle == "non"]
    n = 0
    for service in services_notif(hass, store):
        try:
            await hass.services.async_call("notify", service, {
                "title": textes.t("dem.titre_ha", langue), "message": texte,
                "data": {"tag": f"vision_demande_{demande['id']}", "actions": actions, "channel": "Vision",
                         "clickAction": "/vision-maison/maison", "url": "/vision-maison/maison"},
            }, blocking=False)
            n += 1
        except Exception:  # noqa: BLE001 - une notification ratée ne bloque jamais la demande
            _LOGGER.debug("Notification de demande vers %s impossible", service, exc_info=True)
    return n


async def _habilite(hass: HomeAssistant, store, user_id: str | None) -> str | None:
    """Le nom de qui répond, s'il en a le droit : administrateur HA, ou lié à une personne Décideur."""
    if not user_id:
        return None
    utilisateur = await hass.auth.async_get_user(user_id)
    if utilisateur is None or not utilisateur.is_active:
        return None
    if utilisateur.is_admin:
        return utilisateur.name or textes.t("commun.un_gestionnaire", textes.ha(hass))
    for personne in _personnes_decideurs(store):
        etat = hass.states.get(personne)
        if etat is not None and etat.attributes.get("user_id") == user_id:
            return str(etat.name or utilisateur.name or "")
    return None


def ecouter(hass: HomeAssistant, coordinateur) -> Any:
    """Branche la réponse depuis la notification. Rend la fonction qui débranche."""

    async def _action(event: Event) -> None:
        brut = str(event.data.get("action") or "")
        if not brut.startswith(PREFIXE + "|"):
            return
        try:
            _, pc_id, demande_id, cle = brut.split("|", 3)
        except ValueError:
            return
        coord = coordinateur()
        if coord is None:
            return
        qui = await _habilite(hass, coord.store, event.context.user_id)
        if qui is None:
            _LOGGER.warning("Vision : réponse à une demande depuis une notification refusée (utilisateur non habilité).")
            return
        cible = coord.store.pcs.get(pc_id)
        choix = next((c for c in CHOIX if c[0] == cle), None)
        if cible is None or choix is None:
            return
        _, decision, mode, minutes = choix
        if decision != "non":
            # Un bouton d'accord d'une notification envoyée avant la correction : ignoré (empreinte ou code exigé).
            _LOGGER.warning("Vision : accord depuis une notification de l'appli Compagnon refusé (empreinte ou code exigé).")
            return
        from . import parent

        corps = {"action": "acces", "demande": demande_id, "decision": decision, "mode": mode, "minutes": minutes}
        try:
            await parent.agir(hass, coord, cible, corps, qui)
        except parent._Refus as refus:  # noqa: SLF001
            _LOGGER.warning("Vision : réponse depuis une notification refusée (%s).", refus.erreur)
            return
        await coord.async_ecrire()

    return hass.bus.async_listen("mobile_app_notification_action", _action)


@callback
def effacer(hass: HomeAssistant, store, demande_id: str) -> None:
    """Retire la notification des téléphones des Décideurs une fois la demande réglée (tag commun)."""
    for service in _services_decideurs(hass, store):
        hass.async_create_task(hass.services.async_call(
            "notify", service, {"message": "clear_notification", "data": {"tag": f"vision_demande_{demande_id}"}},
            blocking=False))
