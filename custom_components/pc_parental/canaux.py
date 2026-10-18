"""Comment chaque Décideur reçoit ce que Vision lui envoie (l'auteur, 15/10 : « c'est l'utilisateur qui choisit »).

Trois évènements, trois canaux, autant de cases que l'on veut :
- évènements : « demandes » (accès, temps en plus, déverrouillage, nouvel appareil), « alertes » (protection :
  codes faux répétés…), « resume » (le bilan de la semaine) ;
- canaux : « vision » (l'appli Vision de ses appareils, qui montre la demande avec ses boutons), « compagnon »
  (notification de l'appli Compagnon de Home Assistant), « discord » (le salon Discord réglé dans Configurer).

Sans choix enregistré, un seul canal — jamais de doublon : l'appli Vision si la personne en a une qui gère, sinon
l'appli Compagnon si elle a un téléphone relié à Home Assistant, sinon l'appli Vision. Un lieu qui avait choisi des
téléphones dans Configurer (`notif_decideurs`, avant 2.5.6) garde ces notifications en plus de l'appli Vision.

Les appareils sans personne (une télé partagée de rôle Décideur) ne relèvent d'aucun réglage personnel : ils
reçoivent ce qu'ils recevaient.
"""
from __future__ import annotations

import logging
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.util import slugify

from . import roles

_LOGGER = logging.getLogger(__name__)

EVENEMENTS = ("demandes", "alertes", "resume")
CANAUX = ("vision", "compagnon", "discord")
FONCTIONS_DECIDEUR = ("gerer", "approuver")


def _choix_enregistres(store) -> dict[str, dict[str, list[str]]]:
    brut = getattr(store, "canaux_notif", None)
    if not isinstance(brut, dict):
        brut = {}
        store.canaux_notif = brut
    return brut


def nettoyer(brut: Any) -> dict[str, dict[str, list[str]]]:
    """Ce qui est relu du stockage : seulement des évènements et des canaux connus."""
    sortie: dict[str, dict[str, list[str]]] = {}
    for personne, choix in dict(brut or {}).items():
        if not str(personne).startswith("person.") or not isinstance(choix, dict):
            continue
        propre = {e: [c for c in CANAUX if c in (choix.get(e) or [])] for e in EVENEMENTS if isinstance(choix.get(e), list)}
        if propre:
            sortie[str(personne)] = propre
    return sortie


def _est_decideur(store, pc: dict[str, Any]) -> bool:
    return any(roles.a(store, pc, f) for f in FONCTIONS_DECIDEUR)


def decideurs(hass: HomeAssistant, store) -> list[str]:
    """Les personnes Décideurs : un appareil à leur nom qui gère ou approuve, ou un rôle Décideur choisi pour elles."""
    vues = {str(pc.get("person") or "") for pc in store.pcs.values() if _est_decideur(store, pc)}
    for personne, role_id in (getattr(store, "roles_personnes", None) or {}).items():
        fonctions = (store.roles.get(role_id) or {}).get("fonctions") or []
        if any(f in fonctions for f in FONCTIONS_DECIDEUR):
            vues.add(str(personne))
    return sorted(p for p in vues if p.startswith("person.") and hass.states.get(p) is not None)


def a_l_appli_vision(store, personne: str) -> bool:
    return any(str(pc.get("person") or "") == personne and _est_decideur(store, pc) for pc in store.pcs.values())


def services_compagnon(hass: HomeAssistant, personne: str) -> list[str]:
    """Les services notify.mobile_app_* des téléphones de la personne (ses traceurs de l'appli Compagnon).

    Le nom du service vient de l'inscription du téléphone (`device_name` de son entrée mobile_app), pas du nom
    affiché de l'appareil : renommer le téléphone dans Home Assistant ne change pas le service."""
    etat = hass.states.get(personne)
    if etat is None:
        return []
    ents, devs = er.async_get(hass), dr.async_get(hass)
    services: list[str] = []
    for traceur in etat.attributes.get("device_trackers") or []:
        entree = ents.async_get(traceur)
        if entree is None or entree.platform != "mobile_app" or not entree.device_id:
            continue
        noms = []
        if entree.config_entry_id and (ce := hass.config_entries.async_get_entry(entree.config_entry_id)) is not None:
            noms.append(str(ce.data.get("device_name") or ""))
        if (appareil := devs.async_get(entree.device_id)) is not None:
            noms += [str(appareil.name or ""), str(appareil.name_by_user or "")]
        for nom in noms:
            service = "mobile_app_" + slugify(nom) if nom else ""
            if service and hass.services.has_service("notify", service):
                if service not in services:
                    services.append(service)
                break
    return services


def defaut(hass: HomeAssistant, store, personne: str) -> list[str]:
    """Un seul canal (aucun doublon), sauf choix fait avant 2.5.6 dans Configurer (téléphones des Décideurs)."""
    anciens = set(getattr(store, "notif_decideurs", None) or [])
    vision = a_l_appli_vision(store, personne)
    compagnon = services_compagnon(hass, personne)
    if anciens and anciens & set(compagnon):
        return ["vision", "compagnon"] if vision else ["compagnon"]
    if vision or not compagnon:
        return ["vision"]
    return ["compagnon"]


def choix(hass: HomeAssistant, store, personne: str, evenement: str) -> list[str]:
    enregistre = _choix_enregistres(store).get(personne, {}).get(evenement)
    if isinstance(enregistre, list):
        return [c for c in CANAUX if c in enregistre]
    return defaut(hass, store, personne)


def poser(store, personne: str, evenement: str, canaux: list[str]) -> bool:
    if evenement not in EVENEMENTS or not str(personne).startswith("person."):
        raise ValueError("evenement")
    propres = [c for c in CANAUX if c in (canaux or [])]
    fiche = _choix_enregistres(store).setdefault(personne, {})
    if fiche.get(evenement) == propres:
        return False
    fiche[evenement] = propres
    return True


def vision_pour(hass: HomeAssistant, store, pid: str, evenement: str) -> bool:
    """L'appareil pid reçoit-il ce message dans l'appli Vision ? (Sans personne : oui, comme avant.)"""
    personne = str((store.pcs.get(pid) or {}).get("person") or "")
    if not personne:
        return True
    return "vision" in choix(hass, store, personne, evenement)


def compagnon(hass: HomeAssistant, store, evenement: str) -> list[str]:
    """Les téléphones (appli Compagnon) des Décideurs qui ont coché « compagnon » pour cet évènement."""
    sortie: list[str] = []
    attribues: set[str] = set()
    for personne in decideurs(hass, store):
        siens = services_compagnon(hass, personne)
        attribues.update(siens)
        if "compagnon" in choix(hass, store, personne, evenement):
            sortie += [s for s in siens if s not in sortie]
    # Avant 2.5.6 : des téléphones cochés dans Configurer, rattachés à aucun Décideur (pas de traceur à son nom) ;
    # ils reçoivent toujours ce qu'ils recevaient, rien n'est retiré à un lieu qui les avait choisis.
    for service in getattr(store, "notif_decideurs", None) or []:
        if (str(service).startswith("mobile_app_") and service not in attribues and service not in sortie
                and hass.services.has_service("notify", service)):
            sortie.append(service)
    return sortie


def compagnon_tous(hass: HomeAssistant, store) -> list[str]:
    """Tous les téléphones des Décideurs : pour effacer une notification déjà envoyée, quel que soit le choix."""
    sortie: list[str] = []
    for personne in decideurs(hass, store):
        sortie += [s for s in services_compagnon(hass, personne) if s not in sortie]
    return sortie


def discord_pret(hass: HomeAssistant, store) -> bool:
    reglage = getattr(store, "discord", None) or {}
    service = str(reglage.get("service") or "")
    return bool(service) and hass.services.has_service("notify", service)


def discord_voulu(hass: HomeAssistant, store, evenement: str) -> bool:
    return discord_pret(hass, store) and any(
        "discord" in choix(hass, store, p, evenement) for p in decideurs(hass, store))


async def discord(hass: HomeAssistant, store, evenement: str, titre: str, texte: str) -> bool:
    """Un message dans le salon Discord du lieu, une seule fois, si un Décideur l'a coché pour cet évènement."""
    if not discord_voulu(hass, store, evenement):
        return False
    reglage = getattr(store, "discord", None) or {}
    donnees: dict[str, Any] = {"message": f"**{titre}**\n{texte}" if titre else texte}
    if cible := str(reglage.get("cible") or "").strip():
        donnees["target"] = [c.strip() for c in cible.split(",") if c.strip()]
    try:
        await hass.services.async_call("notify", str(reglage["service"]), donnees, blocking=False)
        return True
    except Exception as err:  # noqa: BLE001 - un salon injoignable ne bloque jamais rien
        _LOGGER.warning("Vision : message Discord impossible : %s", err)
        return False


async def compagnon_simple(hass: HomeAssistant, store, evenement: str, titre: str, texte: str, tag: str = "") -> int:
    """Une notification sans boutons (alertes, résumé) vers les téléphones qui l'ont choisie."""
    n = 0
    for service in compagnon(hass, store, evenement):
        donnees: dict[str, Any] = {"title": titre, "message": texte, "data": {"channel": "Vision"}}
        if tag:
            donnees["data"]["tag"] = tag
        try:
            await hass.services.async_call("notify", service, donnees, blocking=False)
            n += 1
        except Exception:
            _LOGGER.debug("Vision : notification %s vers %s impossible", evenement, service, exc_info=True)
    return n


def etat(hass: HomeAssistant, store, personne: str) -> dict[str, Any]:
    """Ce qu'un écran de réglage montre pour une personne : ses choix, d'où ils viennent, ce qui est possible."""
    enregistres = _choix_enregistres(store).get(personne, {})
    return {
        "personne": personne,
        "choix": {e: choix(hass, store, personne, e) for e in EVENEMENTS},
        "par_defaut": [e for e in EVENEMENTS if not isinstance(enregistres.get(e), list)],
        "possibles": {"vision": a_l_appli_vision(store, personne), "compagnon": bool(services_compagnon(hass, personne)),
                      "discord": discord_pret(hass, store)},
    }


# ----------------------------------------------------------------------------- Réglage : panneau et appli Vision

URL = "/api/pc_parental/canaux"


def nom_personne(hass: HomeAssistant, personne: str) -> str:
    etat_p = hass.states.get(personne)
    return str(etat_p.name) if etat_p is not None and etat_p.name else personne.split(".")[-1]


def personne_du_compte(hass: HomeAssistant, user_id: str | None) -> str:
    if not user_id:
        return ""
    for etat_p in hass.states.async_all("person"):
        if etat_p.attributes.get("user_id") == user_id:
            return etat_p.entity_id
    return ""


async def regler(hass: HomeAssistant, coord, personne: str, corps: dict[str, Any], par: str) -> dict[str, Any]:
    """Pose un choix (évènement, canaux) pour une personne Décideur ; rend son nouvel état."""
    if personne not in decideurs(hass, coord.store):
        raise ValueError("personne")
    if poser(coord.store, personne, str(corps.get("evenement") or ""), list(corps.get("canaux") or [])):
        await coord.store.async_save()
        _LOGGER.warning("Vision : notifications de %s, %s → %s (par %s).", personne, corps.get("evenement"),
                        corps.get("canaux") or "aucune", par)
    return etat(hass, coord.store, personne)


async def depuis_appli(hass: HomeAssistant, coord, moi: dict[str, Any], corps: dict[str, Any]) -> tuple[dict[str, Any], int]:
    """L'appli Vision d'un Décideur : {"action": "canaux"} lit, avec "evenement" et "canaux" règle — pour la personne
    de cet appareil seulement."""
    personne = str(moi.get("person") or "")
    if not personne or personne not in decideurs(hass, coord.store):
        return {"ok": False, "error": "personne"}, 403
    try:
        if "evenement" in corps:
            await regler(hass, coord, personne, corps, str(moi.get("name") or moi.get("id")))
    except ValueError:
        return {"ok": False, "error": "evenement"}, 400
    return {"ok": True, "evenements": list(EVENEMENTS), "canaux": list(CANAUX), **etat(hass, coord.store, personne)}, 200


def _coord(hass: HomeAssistant):
    from .const import DOMAIN

    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


class PcParentalCanauxView(HomeAssistantView):
    """Réglages › Mes notifications (panneau). Chaque Décideur règle les siens ; un administrateur ceux de tous.

    GET → {moi, admin, decideurs: [{personne, nom}], evenements, canaux, etats: {personne: etat}}
    POST {personne, evenement, canaux} → l'état de cette personne."""

    url = URL
    name = "api:pc_parental:canaux"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _qui(self, request: web.Request, coord) -> tuple[str, bool, list[str]]:
        utilisateur = request.get("hass_user")
        admin = bool(utilisateur is not None and utilisateur.is_admin)
        moi = personne_du_compte(self.hass, utilisateur.id if utilisateur is not None else None)
        tous = decideurs(self.hass, coord.store)
        visibles = tous if admin else [p for p in tous if p == moi]
        return moi, admin, visibles

    async def get(self, request: web.Request) -> web.Response:
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        moi, admin, visibles = self._qui(request, coord)
        return self.json({"ok": True, "moi": moi, "admin": admin, "evenements": list(EVENEMENTS), "canaux": list(CANAUX),
                          "decideurs": [{"personne": p, "nom": nom_personne(self.hass, p)} for p in visibles],
                          "etats": {p: etat(self.hass, coord.store, p) for p in visibles}})

    async def post(self, request: web.Request) -> web.Response:
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        moi, _admin, visibles = self._qui(request, coord)
        personne = str(corps.get("personne") or moi)
        if personne not in visibles:
            return self.json({"ok": False, "error": "personne"}, status_code=403)
        utilisateur = request.get("hass_user")
        try:
            nouvel = await regler(self.hass, coord, personne, corps, str(getattr(utilisateur, "name", "") or "?"))
        except ValueError:
            return self.json({"ok": False, "error": "evenement"}, status_code=400)
        return self.json({"ok": True, **nouvel})
