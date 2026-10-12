"""Le code de gestion des PC (faille 2.2-1) : posé et vérifié par Home Assistant seulement.

Avant, le code qui libère un message bloquant « parent » sur un PC se choisissait dans l'onglet
« Réglages » de la fenêtre Vision, ouvert à tous, et vivait dans le profil Windows de l'utilisateur :
un Demandeur pouvait poser son propre code, puis se libérer.

Désormais :
- le code se règle dans les options de l'intégration (Sécurité › Code de gestion des PC), que
  Home Assistant réserve aux administrateurs. Jamais par un service : les données d'un appel de
  service partent sur le bus d'événements (`call_service`), donc dans l'historique ;
- Home Assistant n'en garde que l'empreinte (PBKDF2-HMAC-SHA256, sel aléatoire) ; le PC n'en a
  rien, ni en clair ni en empreinte ;
- le PC envoie le code tapé (`POST /api/pc_parental/code`, avec son identifiant et son secret) ;
  Home Assistant le vérifie, limite les essais faux, et lève lui-même le message (`liberer` au
  relevé suivant). Un accusé `libere_parent` qu'un PC Windows enverrait sans cette levée est refusé.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import logging
import secrets
import time
from typing import Any

from aiohttp import web
from homeassistant.components import persistent_notification
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import textes
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

URL_CODE = "/api/pc_parental/code"
ITERATIONS = 210_000
LONGUEUR_MIN, LONGUEUR_MAX = 6, 32
# Essais faux, par appareil sur une fenêtre glissante : au-delà, 429 le temps que la fenêtre passe.
ESSAIS_FAUX_MAX = 5
FENETRE_S = 15 * 60
# Et pour toute la maison, gardés dans le magasin (un redémarrage ne les efface pas) : au-delà, le code
# est verrouillé jusqu'à ce qu'un administrateur le change. Un Demandeur avec plusieurs PC ne multiplie
# donc pas ses essais.
ECHECS_MAISON_MAX = 15
ECHECS_MAISON_S = 24 * 3600
DONNEES = f"{DOMAIN}_code_gestion"


# ----------------------------------------------------------------------------- L'empreinte


def _empreinte(code: str, sel: bytes, iterations: int) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", code.encode("utf-8"), sel, iterations)


def nettoyer(code: Any) -> str:
    return str(code or "").strip()


def valide(code: str) -> bool:
    return LONGUEUR_MIN <= len(code) <= LONGUEUR_MAX


def fabriquer(code: str) -> dict[str, Any]:
    """L'empreinte à garder : jamais le code."""
    code = nettoyer(code)
    if not valide(code):
        raise ValueError("code")
    sel = secrets.token_bytes(16)
    return {
        "sel": base64.b64encode(sel).decode("ascii"),
        "empreinte": base64.b64encode(_empreinte(code, sel, ITERATIONS)).decode("ascii"),
        "iterations": ITERATIONS,
        "maj": time.time(),
    }


def correspond(fiche: dict[str, Any] | None, code: str) -> bool:
    if not fiche or not fiche.get("empreinte") or not fiche.get("sel"):
        return False
    code = nettoyer(code)
    if not valide(code):
        return False
    try:
        sel = base64.b64decode(fiche["sel"])
        attendu = base64.b64decode(fiche["empreinte"])
        essai = _empreinte(code, sel, int(fiche.get("iterations") or ITERATIONS))
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(essai, attendu)


def est_pose(store) -> bool:
    return bool((getattr(store, "code_gestion", None) or {}).get("empreinte"))


def etat(store) -> str:
    """Ce que les options en disent."""
    if not est_pose(store):
        return textes.t("code.etat_aucun")
    echecs = len(_echecs_maison(store))
    if echecs >= ECHECS_MAISON_MAX:
        return textes.t("code.etat_verrouille", None, n=echecs)
    return textes.t("code.etat_pose") + ((" " + textes.t("code.etat_echecs", None, n=echecs)) if echecs else "")


def poser(store, code: str) -> None:
    """Options de l'intégration (administrateurs) : un code neuf, ou "" pour le retirer."""
    code = nettoyer(code)
    store.code_gestion = fabriquer(code) if code else {}


# ----------------------------------------------------------------------------- Les essais


def _faux(hass: HomeAssistant, pc_id: str) -> list[float]:
    tous = hass.data.setdefault(DONNEES, {})
    limite = time.time() - FENETRE_S
    tous[pc_id] = [t for t in tous.get(pc_id, []) if t > limite]
    return tous[pc_id]


def _echecs_maison(store) -> list[float]:
    fiche = getattr(store, "code_gestion", None) or {}
    limite = time.time() - ECHECS_MAISON_S
    fiche["echecs"] = [float(t) for t in fiche.get("echecs") or [] if float(t) > limite]
    return fiche["echecs"]


def _prevenir(hass: HomeAssistant, pc: dict[str, Any], texte: str) -> None:
    # Une notification par appareil, remise à jour à chaque essai faux : les décideurs le savent dès le premier.
    persistent_notification.async_create(
        hass, texte, title=textes.t("code.notif_titre", None, produit=PRODUIT), notification_id=f"{DOMAIN}_code_gestion_{pc['id']}")


async def verifier(hass: HomeAssistant, coord, pc: dict[str, Any], code: str) -> tuple[int, dict[str, Any]]:
    """(statut HTTP, corps). Le code n'est jamais journalisé."""
    if not est_pose(coord.store):
        return 409, {"ok": False, "error": "aucun_code"}
    if len(_echecs_maison(coord.store)) >= ECHECS_MAISON_MAX:
        return 423, {"ok": False, "error": "verrouille"}
    faux = _faux(hass, pc["id"])
    if len(faux) >= ESSAIS_FAUX_MAX:
        return 429, {"ok": False, "error": "trop", "attente": int(faux[0] + FENETRE_S - time.time()) + 1}
    # L'essai est compté avant le calcul : des appels simultanés ne passent pas sous la limite.
    essai = time.time()
    faux.append(essai)
    fiche = dict(coord.store.code_gestion)
    # PBKDF2 coûte : hors de la boucle de Home Assistant.
    juste = await hass.async_add_executor_job(correspond, fiche, code)
    if juste:
        faux.clear()
        persistent_notification.async_dismiss(hass, f"{DOMAIN}_code_gestion_{pc['id']}")
        return 200, {"ok": True}
    echecs = _echecs_maison(coord.store)
    echecs.append(essai)
    await coord.store.async_save()
    nom = str(pc.get("name") or pc["id"])
    restants = max(0, ESSAIS_FAUX_MAX - len(faux))
    _LOGGER.warning("%s : code de gestion faux sur « %s » (%d essai(s) restant(s) ; %d sur 24 h pour la maison).",
                    PRODUIT, nom, restants, len(echecs))
    if len(echecs) >= ECHECS_MAISON_MAX:
        _prevenir(hass, pc, textes.t("code.notif_verrouille", None, n=len(echecs), nom=nom))
        return 423, {"ok": False, "error": "verrouille"}
    _prevenir(hass, pc, textes.t("code.notif_faux", None, nom=nom, n=len(faux), minutes=FENETRE_S // 60,
                                 total=len(echecs), max=ECHECS_MAISON_MAX))
    return 403, {"ok": False, "error": "code", "essais_restants": restants}


# ----------------------------------------------------------------------------- La vue


class PcParentalCodeView(HomeAssistantView):
    """Le PC fait vérifier le code de gestion tapé sur un message bloquant « parent ».

    Corps : {"id", "secret", "code", "message": <id>}. 200 : le message est levé par Home Assistant
    (il part au relevé dans `liberer`, et la réponse le dit déjà : `liberer`).
    """

    url = URL_CODE
    name = "api:pc_parental:code"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        if not isinstance(corps, dict):
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        if not entrees:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        coord = entrees[0]
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        try:
            mid = int(corps.get("message"))
        except (TypeError, ValueError):
            return self.json({"ok": False, "error": "message"}, status_code=400)
        suivi = (pc.get("messages_suivi") or {}).get(str(mid))
        if suivi is None or suivi.get("bloquant") != "parent":
            return self.json({"ok": False, "error": "message"}, status_code=404)
        statut, reponse = await verifier(self.hass, coord, pc, str(corps.get("code") or "")[:LONGUEUR_MAX + 8])
        if statut != 200:
            return self.json(reponse, status_code=statut)
        from . import messages

        messages.liberer(coord.store, pc["id"], mid, methode="code_gestion")
        _LOGGER.warning("Message « %s » sur « %s » : levé par le code de gestion, vérifié par Home Assistant.",
                        suivi.get("titre"), pc.get("name") or pc["id"])
        await coord.store.async_save()
        return self.json({"ok": True, "liberer": [mid]})
