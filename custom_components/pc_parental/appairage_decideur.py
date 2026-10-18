"""Liaison d'un appareil SANS code à l'écran (2.4.7, décision du projet du 2026-10-12).

L'appareil à relier (une télé surtout) fait sa demande comme avant (`appairage.py`) ; il n'affiche plus ni QR ni
code, seulement « En attente de validation… » et son nom. La demande part sur les appareils des {decideur}s (rôle qui
peut « Gérer ») : une notification « Nouvel appareil », avec Accepter / Refuser et le choix du {demandeur} (ou écran
partagé du {lieu}). Le QR et le code restent en secours, derrière « Autre méthode » sur l'appareil.

- `POST /api/pc_parental/appairage/decideur/liste` (appareil {decideur}) : {id, secret} → les demandes en attente.
- `POST /api/pc_parental/appairage/decideur/decision` (appareil {decideur}) :
  {id, secret, demande, action: accepter | refuser, personne: person.x | "partage"} → {ok, etat}.

Protection contre les rafales : au plus [RAFALE_MAX] demandes par adresse en [RAFALE_S] (en plus des limites
d'`appairage.py` : 10 en cours, 2 par adresse) ; une nouvelle demande du même appareil remplace l'ancienne (qui
ne peut plus être acceptée). Le jeton d'attente ne sort jamais vers les {decideur}s.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from aiohttp import web
from homeassistant.components import persistent_notification
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import appairage, lieu, roles, textes
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

FONCTION = "gerer"
RAFALE_MAX = 3
RAFALE_S = 10 * 60
PARTAGE = "partage"


# ------------------------------------------------------------------ rafales


def _historique(hass: HomeAssistant) -> dict[str, list[float]]:
    return hass.data.setdefault(f"{appairage.CLE_DONNEES}_rafales", {})


def trop_de_demandes(hass: HomeAssistant, adresse: str, maintenant: float | None = None) -> bool:
    """Au plus RAFALE_MAX demandes par adresse en RAFALE_S (glissant)."""
    maintenant = time.time() if maintenant is None else maintenant
    h = [t for t in _historique(hass).get(adresse, []) if maintenant - t < RAFALE_S]
    _historique(hass)[adresse] = h
    return len(h) >= RAFALE_MAX


def noter_demande(hass: HomeAssistant, adresse: str, maintenant: float | None = None) -> None:
    _historique(hass).setdefault(adresse, []).append(time.time() if maintenant is None else maintenant)


def remplacer_anciennes(hass: HomeAssistant, nouvelle: dict[str, Any]) -> int:
    """Une nouvelle demande du même appareil (même uid, ou même nom d'hôte depuis la même adresse) remplace les
    anciennes encore en attente : elles ne peuvent plus être acceptées."""
    n = 0
    for d in appairage._demandes(hass).values():
        if d is nouvelle or d["etat"] != "en_attente":
            continue
        meme = (nouvelle["uid"] and d["uid"] == nouvelle["uid"]) or (d["host"] == nouvelle["host"] and d["adresse"] == nouvelle["adresse"])
        if meme:
            d["etat"] = "remplacee"
            persistent_notification.async_dismiss(hass, f"{DOMAIN}_appairage_{d['code']}")
            n += 1
    return n


# ------------------------------------------------------------------ vers les décideurs


def _personnes(hass: HomeAssistant) -> list[dict[str, str]]:
    return sorted(({"id": s.entity_id, "nom": s.name} for s in hass.states.async_all("person")),
                  key=lambda p: p["nom"].lower())


def pour_decideur(hass: HomeAssistant, coord, d: dict[str, Any], langue: str) -> dict[str, Any]:
    """Ce qu'un {decideur} voit d'une demande : jamais le jeton d'attente, ni le code."""
    existant = appairage._existant(coord, d)
    return {
        "demande": d["id"],
        "nom": d["nom"] or d["host"],
        "plateforme": d["platform"],
        "genre": d.get("genre") or "",
        "depuis": int(d["cree"]),
        "expire": int(d["expire"]),
        "existant": coord.store.pcs[existant]["name"] if existant else None,
        "personnes": _personnes(hass),
        "lieu": lieu.nom(coord.store, langue),
    }


def notifier(hass: HomeAssistant, coord, d: dict[str, Any]) -> int:
    """La demande part sur chaque appareil {decideur} (rôle « Gérer ») ; renvoie leur nombre."""
    from . import canaux

    n = 0
    for pid in roles.avec(coord.store, FONCTION):
        # 2.5.6 : dans l'appli Vision des Décideurs qui l'ont choisie pour les demandes (canaux.py).
        if not canaux.vision_pour(hass, coord.store, pid, "demandes"):
            continue
        langue = textes.pour(hass, coord.store.pcs.get(pid))
        texte = textes.t("app.nouvel_texte", langue, nom=d["nom"] or d["host"])
        try:
            msg = coord.store.add_message(pid, texte, titre=textes.t("app.nouvel_titre", langue))
        except ValueError:
            continue
        msg["nouvel_appareil"] = pour_decideur(hass, coord, d, langue)
        msg["expire"] = d["expire"]
        n += 1
    return n


# ------------------------------------------------------------------ la décision


class Refus(Exception):
    def __init__(self, statut: int, erreur: str) -> None:
        super().__init__(erreur)
        self.statut, self.erreur = statut, erreur


async def decider(hass: HomeAssistant, coord, pc: dict[str, Any], demande: str, action: str, personne: str) -> dict[str, Any]:
    if not roles.a(coord.store, pc, FONCTION):
        _LOGGER.warning("%s : « %s » a tenté de relier un appareil sans le rôle qui peut gérer.", PRODUIT, pc.get("name"))
        raise Refus(403, "role")
    d = appairage._demandes(hass).get(str(demande or ""))
    if d is None or d["etat"] != "en_attente":
        return {"ok": False, "etat": (d or {}).get("etat") or "expiree"}
    qui = str(pc.get("name") or pc["id"])
    if action == "refuser":
        d["etat"] = "refusee"
        persistent_notification.async_dismiss(hass, f"{DOMAIN}_appairage_{d['code']}")
        _LOGGER.info("%s : liaison de « %s » refusée par « %s ».", PRODUIT, d["nom"] or d["host"], qui)
        return {"ok": True, "etat": "refusee"}
    if action != "accepter":
        raise Refus(400, "action")
    personne = "" if personne in ("", PARTAGE) else personne
    try:
        appairage._verifier_personne(hass, personne)
    except ValueError as err:
        raise Refus(400, "personne") from err
    reponse = await appairage._attribuer(hass, coord, appairage._existant(coord, d), d, personne)
    d.update(etat="validee", reponse=reponse)
    persistent_notification.async_dismiss(hass, f"{DOMAIN}_appairage_{d['code']}")
    _LOGGER.info("%s : liaison de « %s » acceptée par « %s ».", PRODUIT, reponse["name"], qui)
    return {"ok": True, "etat": "validee", "nom": reponse["name"], "cree": reponse["created"]}


class _VueDecideur(HomeAssistantView):
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = appairage._coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        try:
            return self.json(await self.traiter(coord, pc, corps))
        except Refus as r:
            return self.json({"ok": False, "error": r.erreur}, status_code=r.statut)

    async def traiter(self, coord, pc: dict[str, Any], corps: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError


class ListeView(_VueDecideur):
    url = "/api/pc_parental/appairage/decideur/liste"
    name = "api:pc_parental:appairage_decideur_liste"

    async def traiter(self, coord, pc, corps):
        if not roles.a(coord.store, pc, FONCTION):
            raise Refus(403, "role")
        langue = textes.pour(self.hass, pc)
        return {"ok": True, "demandes": [pour_decideur(self.hass, coord, d, langue)
                                         for d in appairage._demandes(self.hass).values() if d["etat"] == "en_attente"]}


class DecisionView(_VueDecideur):
    url = "/api/pc_parental/appairage/decideur/decision"
    name = "api:pc_parental:appairage_decideur_decision"

    async def traiter(self, coord, pc, corps):
        return await decider(self.hass, coord, pc, str(corps.get("demande") or ""), str(corps.get("action") or ""),
                             str(corps.get("personne") or ""))
