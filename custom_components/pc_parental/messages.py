"""Messages riches sur les appareils (Vision 1.67.0) : docs/messages.md.

Texte, image, son ou vidéo, placé où l'expéditeur le veut, éventuellement bloquant, avec un
accusé renvoyé à Home Assistant. Les messages passent par la même file que `pc_parental.notify`
(store `messages`, délivrée au relevé) : un ancien agent y lit toujours le titre et le texte.

Les médias viennent seulement des dossiers média de Home Assistant. Chacun est servi, à un seul
appareil et pour un seul message, derrière un jeton aléatoire qui expire avec le message : ni le
chemin du fichier, ni une URL extérieure ne sortent d'ici.
"""

from __future__ import annotations

import logging
import mimetypes
import os
import secrets
import time
from typing import Any

import voluptuous as vol
from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv

from . import textes
from .const import DEFAULT_SOUND, DOMAIN, MAX_MESSAGES, PRODUIT, SOUNDS

_LOGGER = logging.getLogger(__name__)

TYPES = ("texte", "image", "audio", "video")
POSITIONS = (
    "plein_ecran", "centre", "haut", "bas", "gauche", "droite",
    "haut_gauche", "haut_droite", "bas_gauche", "bas_droite",
)
TAILLES = ("petite", "moyenne", "grande")
BLOQUANTS = ("non", "lu", "parent")
ETATS = ("affiche", "lu", "ferme", "libere_parent", "libere_auto", "expire", "erreur", "repondu")
POSITION_DEFAUT, TAILLE_DEFAUT = "centre", "moyenne"

# Ce qu'on accepte de servir : type de message, et taille maximale.
MIMES: dict[str, tuple[str, int]] = {
    "image/jpeg": ("image", 25_000_000),
    "image/png": ("image", 25_000_000),
    "image/webp": ("image", 25_000_000),
    "image/gif": ("image", 25_000_000),
    "audio/mpeg": ("audio", 100_000_000),
    "audio/mp4": ("audio", 100_000_000),
    "audio/aac": ("audio", 100_000_000),
    "audio/ogg": ("audio", 100_000_000),
    "audio/wav": ("audio", 100_000_000),
    "video/mp4": ("video", 500_000_000),
    "video/webm": ("video", 500_000_000),
}
_EXTENSIONS = {
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp",
    ".gif": "image/gif", ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".aac": "audio/aac",
    ".ogg": "audio/ogg", ".oga": "audio/ogg", ".wav": "audio/wav", ".mp4": "video/mp4",
    ".m4v": "video/mp4", ".webm": "video/webm",
}

URL_MEDIA = "/api/pc_parental/media/{uid}/{jeton}"
URL_ACCUSE = "/api/pc_parental/message"
EVENEMENT = f"{DOMAIN}_message"
SERVICE_ENVOYER = "envoyer"
SERVICE_LIBERER = "liberer_message"

SCHEMA_ENVOYER = vol.Schema(
    {
        vol.Optional("pc"): cv.string,
        vol.Optional("tag"): cv.string,
        vol.Optional("type"): vol.In(TYPES),
        vol.Optional("titre", default=PRODUIT): cv.string,
        vol.Optional("message", default=""): cv.string,
        vol.Optional("media"): cv.string,
        vol.Optional("position"): vol.In(POSITIONS),
        vol.Optional("taille"): vol.In(TAILLES),
        vol.Optional("duree", default=30): vol.All(vol.Coerce(int), vol.Range(min=0, max=3600)),
        vol.Optional("son", default=DEFAULT_SOUND): vol.In(list(SOUNDS)),
        vol.Optional("bloquant", default="non"): vol.In(BLOQUANTS),
        vol.Optional("limite", default=30): vol.All(vol.Coerce(int), vol.Range(min=1, max=240)),
        vol.Optional("expire", default=15): vol.All(vol.Coerce(int), vol.Range(min=1, max=7 * 24 * 60)),
    }
)
SCHEMA_LIBERER = vol.Schema({vol.Required("pc"): cv.string, vol.Optional("id"): vol.Coerce(int)})


# ---------------------------------------------------------------- Le média


def chemin_media(hass: HomeAssistant, media: str) -> str:
    """Le fichier désigné, sous un dossier média de Home Assistant. ValueError sinon."""
    dossiers = dict(getattr(hass.config, "media_dirs", None) or {})
    if not dossiers:
        dossiers = {"local": hass.config.path("media")}
    media = (media or "").strip()
    if media.startswith("media-source://"):
        reste = media[len("media-source://"):]
        if not reste.startswith("media_source/"):
            raise textes.Erreur("msg.media_local")
        dossier, _, relatif = reste[len("media_source/"):].partition("/")
        if dossier not in dossiers or not relatif:
            raise textes.Erreur("msg.dossier_inconnu", dossier=dossier)
        bases = [dossiers[dossier]]
        candidat = os.path.join(dossiers[dossier], relatif)
    elif media.startswith("/") or os.path.isabs(media):
        bases = list(dossiers.values())
        candidat = media
        # « /media/x.jpg » : relatif au dossier local quand le chemin n'est pas déjà dessous.
        if not any(_sous(os.path.realpath(media), os.path.realpath(b)) for b in bases)                 and media.startswith("/media/") and "local" in dossiers:
            candidat = os.path.join(dossiers["local"], media[len("/media/"):])
    else:
        raise textes.Erreur("msg.media_forme")
    vrai = os.path.realpath(candidat)
    if not any(_sous(vrai, os.path.realpath(b)) for b in bases):
        raise textes.Erreur("msg.hors_dossier")
    return vrai


def _sous(chemin: str, base: str) -> bool:
    try:
        return os.path.commonpath([os.path.abspath(chemin), os.path.abspath(base)]) == os.path.abspath(base)
    except ValueError:
        return False


def decrire_media(chemin: str) -> dict[str, Any]:
    """Type MIME, type de message et taille, après vérification. Bloquant : hors de la boucle."""
    if not os.path.isfile(chemin):
        raise textes.Erreur("msg.introuvable")
    mime = _EXTENSIONS.get(os.path.splitext(chemin)[1].lower()) or mimetypes.guess_type(chemin)[0] or ""
    if mime not in MIMES:
        raise textes.Erreur("msg.format", fichier=os.path.basename(chemin))
    genre, maximum = MIMES[mime]
    octets = os.path.getsize(chemin)
    if octets > maximum:
        raise textes.Erreur("msg.trop_lourd", taille=octets // 1_000_000, max=maximum // 1_000_000)
    return {"chemin": chemin, "mime": mime, "type": genre, "octets": octets}


# ---------------------------------------------------------------- L'envoi


def _reglage(pc: dict[str, Any], cle: str, valides: tuple[str, ...], defaut: str) -> str:
    v = str(pc.get(cle) or "")
    return v if v in valides else defaut


def preparer(store, pc_id: str, donnees: dict[str, Any], media: dict[str, Any] | None) -> dict[str, Any]:
    """Met un message riche dans la file de l'appareil ; le renvoie (avec ses champs internes)."""
    pc = store.pcs.get(pc_id)
    if pc is None:
        raise textes.Erreur("commun.appareil_disparu")
    genre = donnees.get("type") or (media["type"] if media else "texte")
    texte = (donnees.get("message") or "").strip()[:1000]
    if genre == "texte" and not texte:
        raise textes.Erreur("commun.message_vide")
    if genre != "texte" and (media is None or media["type"] != genre):
        raise textes.Erreur("msg.type_media", genre=genre)
    bloquant = donnees.get("bloquant", "non")
    position = "plein_ecran" if bloquant != "non" else (
        donnees.get("position") or _reglage(pc, "msg_position", POSITIONS, POSITION_DEFAUT))
    maintenant = time.time()
    expire = maintenant + int(donnees.get("expire", 15)) * 60
    limite = int(donnees.get("limite", 30)) if bloquant == "parent" else 0
    son = SOUNDS.get(donnees.get("son", DEFAULT_SOUND), SOUNDS[DEFAULT_SOUND])
    message: dict[str, Any] = {
        "id": int(pc.get("next_message_id", 1)),
        "uid": secrets.token_hex(8),
        "title": (donnees.get("titre") or PRODUIT).strip()[:60],
        "text": texte,
        "urgent": position == "plein_ecran",
        "sound": son[0],
        "loop": son[1],
        "ts": maintenant,
        "type": genre,
        "position": position,
        "taille": donnees.get("taille") or _reglage(pc, "msg_taille", TAILLES, TAILLE_DEFAUT),
        "duree": int(donnees.get("duree", 30)),
        "bloquant": bloquant,
        "limite": limite,
        "expire": expire,
    }
    if media:
        jeton = secrets.token_urlsafe(24)
        message["media"] = {"jeton": jeton, "mime": media["mime"], "octets": media["octets"]}
        medias = pc.setdefault("medias", {})
        medias[jeton] = {
            "uid": message["uid"], "chemin": media["chemin"], "mime": media["mime"],
            "jusqua": expire + limite * 60 + 3600,
        }
    pc["next_message_id"] = message["id"] + 1
    pc["messages"] = (pc.get("messages") or [])[-(MAX_MESSAGES - 1):]
    pc["messages"].append(message)
    # Ce qu'on sait de chaque message envoyé : pour l'accusé et la libération.
    suivi = pc.setdefault("messages_suivi", {})
    suivi[str(message["id"])] = {"uid": message["uid"], "titre": message["title"], "bloquant": bloquant,
                                 "etat": "en_attente", "ts": maintenant, "expire": expire, "limite": limite}
    _elaguer(pc)
    return message


def pour_appareil(message: dict[str, Any]) -> dict[str, Any]:
    """Ce que l'appareil reçoit : jamais le chemin du fichier."""
    sortie = {k: v for k, v in message.items() if k != "media"}
    if media := message.get("media"):
        sortie["media"] = {
            "url": URL_MEDIA.format(uid=message["uid"], jeton=media["jeton"]),
            "mime": media["mime"], "octets": media["octets"],
        }
    return sortie


def _elaguer(pc: dict[str, Any]) -> None:
    maintenant = time.time()
    medias = pc.get("medias") or {}
    for jeton in [j for j, m in medias.items() if float(m.get("jusqua") or 0) < maintenant]:
        medias.pop(jeton, None)
    suivi = pc.get("messages_suivi") or {}
    vieux = sorted(suivi, key=lambda k: float(suivi[k].get("ts") or 0))
    for cle in vieux[:-50]:
        suivi.pop(cle, None)


def liberer(store, pc_id: str, message_id: int | None, methode: str = "ha") -> list[Any]:
    """Demande la levée d'un bloquant « parent » (ou de tous) au relevé suivant.

    La levée est notée dans le suivi (`libere_ha`) : sur un PC, c'est la seule preuve qui fait
    accepter l'accusé `libere_parent` (faille 2.2-1, code_gestion.py).
    """
    pc = store.pcs.get(pc_id)
    if pc is None:
        raise textes.Erreur("commun.appareil_disparu")
    suivi = pc.get("messages_suivi") or {}
    if message_id is None:
        ids: list[Any] = ["*"]
        for s in suivi.values():
            if s.get("bloquant") == "parent":
                s["libere_ha"] = methode
    else:
        s = suivi.get(str(message_id))
        if s is None or s.get("bloquant") != "parent":
            raise textes.Erreur("msg.pas_bloquant")
        s["libere_ha"] = methode
        ids = [int(message_id)]
    pc["liberer"] = list(dict.fromkeys((pc.get("liberer") or []) + ids))
    return ids


def accuse_refuse(pc: dict[str, Any], suivi: dict[str, Any], etat: str, maintenant: float) -> str:
    """Faille 2.2-1 : sur un PC, la fenêtre tourne dans la session du Demandeur, qui peut déposer ses
    propres accusés. Sur un bloquant « parent », seuls sont crus : `affiche`, `repondu`, une levée faite
    par Home Assistant (`libere_parent`), ou la limite de sécurité vraiment passée depuis le premier
    affichage (`libere_auto`). Rend la raison du refus, ou "" si l'accusé est cru. Android et la télé
    gardent leur code de gestion local, protégé.
    """
    if suivi.get("bloquant") != "parent" or str(pc.get("platform") or "") == "android":
        return ""
    if etat in ("affiche", "repondu"):
        return ""
    if suivi.get("libere_ha") and etat in ("libere_parent", "libere_auto"):
        return ""
    if etat == "libere_auto":
        # Le premier affichage connu de Home Assistant, sinon l'envoi ; la limite du message, sinon 30 min
        # (messages envoyés avant ce suivi). Une minute de marge pour les horloges.
        depart = float(suivi.get("affiche_a") or suivi.get("ts") or 0)
        limite = int(suivi.get("limite") or 30) * 60
        if depart and maintenant - depart >= limite - 60:
            return ""
        return "limite"
    return "non_libere" if etat == "libere_parent" else "bloquant"


def prendre_liberations(pc: dict[str, Any]) -> list[Any]:
    ids = list(pc.get("liberer") or [])
    pc["liberer"] = []
    return ids


# ---------------------------------------------------------------- Les vues


class PcParentalMediaView(HomeAssistantView):
    """Un média de message, pour l'appareil et le message auxquels le jeton est lié."""

    url = URL_MEDIA
    name = "api:pc_parental:media"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request, uid: str, jeton: str) -> web.StreamResponse:
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        if not entrees:
            return web.Response(status=503)
        maintenant = time.time()
        for pc in entrees[0].store.pcs.values():
            m = (pc.get("medias") or {}).get(jeton)
            if m is not None and secrets.compare_digest(str(m.get("uid") or ""), uid):
                # 2.5.12.14.2 (revue de HACS) : la vérification du fichier se fait dans l'executor, pas dans la boucle.
                if float(m.get("jusqua") or 0) < maintenant or not await self.hass.async_add_executor_job(
                        os.path.isfile, m["chemin"]):
                    break
                return web.FileResponse(m["chemin"], headers={
                    "Content-Type": m["mime"], "Cache-Control": "private, no-store",
                })
        return web.Response(status=404, text=textes.t("msg.media_inconnu", textes.ha(self.hass)))


class PcParentalAccuseView(HomeAssistantView):
    """L'appareil dit ce qu'est devenu un message : affiché, lu, levé, périmé…"""

    url = URL_ACCUSE
    name = "api:pc_parental:message"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        if not entrees:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        coord = entrees[0]
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.noter_langue(pc, corps)
        etat = str(corps.get("etat") or "")
        if etat not in ETATS:
            return self.json({"ok": False, "error": "etat"}, status_code=400)
        try:
            mid = int(corps.get("message"))
        except (TypeError, ValueError):
            return self.json({"ok": False, "error": "message"}, status_code=400)
        suivi = (pc.get("messages_suivi") or {}).get(str(mid))
        if suivi is None:
            return self.json({"ok": False, "error": "message"}, status_code=404)
        # 2.0.0 (notifications v2) : « repondu » porte la réponse (bouton ou texte libre).
        detail = " ".join(str(corps.get("detail") or "").split())[:200 if etat == "repondu" else 60]
        if etat == "repondu" and not detail:
            return self.json({"ok": False, "error": "reponse"}, status_code=400)
        maintenant = time.time()
        refus = accuse_refuse(pc, suivi, etat, maintenant)
        if refus:
            _LOGGER.warning("Message « %s » sur « %s » : accusé « %s » refusé (%s) — levée non faite par "
                            "Home Assistant.", suivi.get("titre"), pc.get("name") or pc["id"], etat, refus)
            return self.json({"ok": False, "error": refus}, status_code=409)
        if etat == "affiche" and not suivi.get("affiche_a"):
            suivi["affiche_a"] = maintenant
        suivi["etat"], suivi["maj"] = etat, maintenant
        if detail:
            suivi["detail"] = detail
        if etat == "repondu":
            from . import messages_v2
            await messages_v2.reponse_recue(self.hass, coord, pc, suivi, detail)
        nom = str(pc.get("name") or pc["id"])
        if etat == "libere_parent":
            _LOGGER.warning("Message « %s » sur « %s » : levé par un gestionnaire (%s).", suivi.get("titre"), nom, detail or "?")
        elif etat == "libere_auto":
            _LOGGER.warning("Message « %s » sur « %s » : levé tout seul à la limite de sécurité.", suivi.get("titre"), nom)
        elif etat == "erreur":
            _LOGGER.warning("Message « %s » sur « %s » : impossible à afficher (%s).", suivi.get("titre"), nom, detail or "?")
        await coord.store.async_save()
        self.hass.bus.async_fire(EVENEMENT, {
            "pc": pc["id"], "nom": nom, "id": mid, "uid": suivi.get("uid"),
            "etat": etat, "detail": detail, "titre": suivi.get("titre"),
        })
        return self.json({"ok": True})


# ---------------------------------------------------------------- Les services


async def async_envoyer(hass: HomeAssistant, coord, donnees: dict[str, Any]) -> dict[str, Any]:
    """Le service `pc_parental.envoyer`."""
    media = None
    if donnees.get("media"):
        # 2.5.12.14.2 : `chemin_media` résout des chemins sur le disque (realpath) : dans l'executor.
        chemin = await hass.async_add_executor_job(chemin_media, hass, donnees["media"])
        media = await hass.async_add_executor_job(decrire_media, chemin)
    cible = donnees.get("pc") or donnees.get("tag")
    envoyes = []
    for pc_id in coord.store.destinataires(cible):
        m = preparer(coord.store, pc_id, donnees, media)
        envoyes.append({"pc": pc_id, "nom": coord.store.pcs[pc_id].get("name"), "id": m["id"], "uid": m["uid"]})
    await coord.async_ecrire()
    return {"messages": envoyes}


def enregistrer_vues(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalMediaView(hass))
    hass.http.register_view(PcParentalAccuseView(hass))
    from .code_gestion import PcParentalCodeView

    hass.http.register_view(PcParentalCodeView(hass))
