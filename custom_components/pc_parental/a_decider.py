"""Refonte UX, lot 2 — « À décider » unique (etude-ux B1, A4).

Une seule boîte pour tout ce qui attend un Décideur :
  - les demandes qui se tranchent une par une : accès (appli, site), temps, déverrouillage — `parent.demandes_attente`
    reste leur source (cartes, appli, widget de l'accueil) ;
  - ce qui se traite par lots : appareils à valider (appairage), éléments à classer, logiciels à valider —
    `lots()`, avec le nombre et l'endroit où décider.

Mêmes réponses partout (carte, appli, notification) : Accorder ▾ 15 min, 30 min, 1 h, Toujours ; Refuser.

Notifications actionnables (appli Compagnon de HA) : chaque nouvelle demande part aussi vers les services
`notify.mobile_app_*` choisis dans Configurer, avec des boutons. La réponse (`mobile_app_notification_action`)
passe par `parent.agir`, exactement comme depuis la carte ou l'appli Vision ; seuls un administrateur de HA ou une
personne qui gère un appareil peuvent répondre ainsi.
"""
from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import Event, HomeAssistant, callback

from . import textes
from .const import DOMAIN

# Lot 2, fini au lot 6 : les liens mènent au tableau de Vision (/vision-maison), plus à l'ancien tableau.
URL_PATH = "vision-maison"  # = dashboard.URL_PATH (vérifié par l'essai)

_LOGGER = logging.getLogger(__name__)

PREFIXE = "VISION"
EVENEMENT_ACTION = "mobile_app_notification_action"
# Les réponses proposées partout (minutes ; « toujours » ; « non »).
DUREES = (15, 30, 60)


def lots(hass: HomeAssistant, coord, langue: str | None = None) -> list[dict[str, Any]]:
    """Ce qui se traite par lots : {genre, nombre, libelle, ou} (« ou » : l'endroit où décider dans HA)."""
    sortie: list[dict[str, Any]] = []
    try:
        from . import appairage
        attente = [d for d in appairage._demandes(hass).values() if d.get("etat") == "en_attente"]
        if attente:
            sortie.append({"genre": "appairage", "nombre": len(attente),
                           "libelle": textes.n("adec.appareils_a_valider", len(attente), langue),
                           "ou": "/vision-appairage", "codes": [d["code"] for d in attente]})
    except Exception:  # noqa: BLE001 - une source illisible ne cache pas les autres
        _LOGGER.debug("À décider : appairages illisibles", exc_info=True)
    try:
        n = int(coord.store.a_classer(None))
        if n:
            sortie.append({"genre": "classer", "nombre": n, "libelle": textes.n("adec.a_classer", n, langue),
                           "ou": f"/{URL_PATH}/listes"})
    except Exception:  # noqa: BLE001
        _LOGGER.debug("À décider : éléments à classer illisibles", exc_info=True)
    try:
        from .processus import CAPTEUR
        s = hass.states.get(CAPTEUR)
        n = int(float(s.state)) if s is not None and str(s.state).replace(".", "", 1).isdigit() else 0
        if n:
            sortie.append({"genre": "logiciels", "nombre": n, "libelle": textes.n("adec.logiciels", n, langue),
                           "ou": f"/{URL_PATH}/protection"})
    except Exception:  # noqa: BLE001
        _LOGGER.debug("À décider : logiciels illisibles", exc_info=True)
    return sortie


# ----------------------------------------------------------------------------- Notifications actionnables


def services_notif(store) -> list[str]:
    """Les services notify.mobile_app_* choisis dans Configurer (vide : pas de notification Compagnon)."""
    return [s for s in (getattr(store, "notif_decideurs", None) or []) if isinstance(s, str) and s.startswith("mobile_app_")]


def _action(genre: str, pc: str, ident: str, choix: str) -> str:
    return "|".join((PREFIXE, genre, pc, ident, choix))


def boutons(genre: str, pc: str, ident: str, minutes: int | None, langue: str | None) -> list[dict[str, str]]:
    """Les boutons d'une demande dans l'appli Compagnon.

    FAILLE DE SÉCURITÉ (l'auteur, 16/10) : accorder exige l'empreinte ou le code à chaque fois ; un bouton de notification
    de l'appli Compagnon n'en demande aucun. Il ne reste donc que « Refuser » ; toucher la notification ouvre Vision,
    où accorder passe par l'empreinte ou le code."""
    return [{"action": _action(genre, pc, ident, "non"), "title": textes.t("adec.refuser", langue)}]


async def notifier(hass: HomeAssistant, coord, genre: str, pc_id: str, ident: str, texte: str,
                   minutes: int | None = None) -> int:
    """Envoie la demande aux téléphones des Décideurs (appli Compagnon), avec ses boutons. Rend le nombre d'envois."""
    # 2.5.6 : les Décideurs qui ont choisi l'appli Compagnon pour les demandes (canaux.py), plus les téléphones
    # cochés dans Configurer avant 2.5.6 pour qui n'a encore rien choisi (canaux.defaut).
    from . import canaux

    services = canaux.compagnon(hass, coord.store, "demandes")
    langue = textes.ha(hass)
    await canaux.discord(hass, coord.store, "demandes", textes.t("adec.titre", langue), texte)
    if not services:
        return 0
    donnees = {"title": textes.t("adec.titre", langue), "message": texte,
               "data": {"tag": f"vision_{genre}_{ident}", "actions": boutons(genre, pc_id, ident, minutes, langue),
                        "channel": "Vision", "clickAction": f"/{URL_PATH}/maison", "url": f"/{URL_PATH}/maison"}}
    faits = 0
    for svc in services:
        try:
            await hass.services.async_call("notify", svc, donnees, blocking=False)
            faits += 1
        except Exception as err:  # noqa: BLE001 - une notification ratée ne bloque jamais la demande
            _LOGGER.warning("À décider : notification vers notify.%s impossible : %s", svc, err)
    return faits


async def effacer(hass: HomeAssistant, coord, genre: str, ident: str) -> None:
    """Retire la notification d'une demande tranchée (ailleurs) des téléphones des Décideurs."""
    from . import canaux

    for svc in list(dict.fromkeys(services_notif(coord.store) + canaux.compagnon_tous(hass, coord.store))):
        try:
            await hass.services.async_call("notify", svc, {"message": "clear_notification",
                                                           "data": {"tag": f"vision_{genre}_{ident}"}}, blocking=False)
        except Exception:  # noqa: BLE001
            pass


def decodage(action: str) -> dict[str, str] | None:
    """« VISION|acces|pc|id|60 » → {genre, pc, id, choix} ; None si ce n'est pas une réponse de Vision."""
    morceaux = str(action or "").split("|")
    if len(morceaux) != 5 or morceaux[0] != PREFIXE or morceaux[1] not in ("acces", "temps", "deverrouillage"):
        return None
    return {"genre": morceaux[1], "pc": morceaux[2], "id": morceaux[3], "choix": morceaux[4]}


def corps_agir(r: dict[str, str]) -> dict[str, Any]:
    """La réponse d'un bouton, dite comme celle de la carte ou de l'appli (action « acces » de parent.agir)."""
    corps: dict[str, Any] = {"action": "acces", "pc": r["pc"], "demande": r["id"]}
    if r["choix"] == "non":
        corps["decision"] = "non"
    elif r["choix"] == "toujours":
        corps["decision"] = "toujours"
    else:
        corps["decision"] = "temporaire"
        corps["minutes"] = max(1, min(24 * 60, int(r["choix"]) if r["choix"].isdigit() else 60))
    return corps


async def peut_repondre(hass: HomeAssistant, coord, user_id: str | None) -> str | None:
    """Le nom de qui répond s'il en a le droit (administrateur de HA, ou personne qui gère un appareil), sinon None."""
    if not user_id:
        return None
    user = await hass.auth.async_get_user(user_id)
    if user is None:
        return None
    if user.is_admin:
        return user.name or textes.t("commun.un_gestionnaire", textes.ha(hass))
    from . import roles
    personnes = [s.entity_id for s in hass.states.async_all("person") if s.attributes.get("user_id") == user_id]
    for pc in coord.store.pcs.values():
        if pc.get("person") in personnes and roles.a(coord.store, pc, "gerer"):
            return user.name or personnes[0]
    return None


async def sur_action(hass: HomeAssistant, event: Event) -> str | None:
    """Une réponse depuis une notification de l'appli Compagnon. Rend la phrase de retour, ou None si ignorée."""
    r = decodage(str(event.data.get("action") or ""))
    if r is None:
        return None
    entrees = list(hass.data.get(DOMAIN, {}).values())
    coord = entrees[0] if entrees else None
    if coord is None or r["pc"] not in coord.store.pcs:
        return None
    # FAILLE (l'auteur, 16/10) : une notification ne fait plus que refuser. Un bouton d'accord d'une notification envoyée
    # avant la correction est ignoré : accorder se fait dans Vision, avec l'empreinte ou le code.
    if str(r.get("choix")) != "non":
        _LOGGER.warning("Vision : accord depuis une notification de l'appli Compagnon refusé (empreinte ou code exigé).")
        return None
    qui = await peut_repondre(hass, coord, event.context.user_id)
    if qui is None:
        _LOGGER.warning("À décider : réponse de notification refusée (utilisateur sans droit de gestion)")
        return None
    from . import parent
    try:
        retour = await parent.agir(hass, coord, coord.store.pcs[r["pc"]], corps_agir(r), qui, langue=textes.ha(hass))
    except Exception as err:  # noqa: BLE001 - réponse déjà traitée, demande expirée…
        _LOGGER.info("À décider : réponse de notification %s non appliquée : %s", r, err)
        return None
    _LOGGER.info("À décider : %s a répondu depuis une notification (%s %s → %s)", qui, r["genre"], r["id"], r["choix"])
    return retour


def installer(hass: HomeAssistant) -> callable:
    """Écoute les réponses des notifications. Rend la fonction qui arrête l'écoute."""

    @callback
    def _ecouter(event: Event) -> None:
        hass.async_create_task(sur_action(hass, event))

    return hass.bus.async_listen(EVENEMENT_ACTION, _ecouter)
