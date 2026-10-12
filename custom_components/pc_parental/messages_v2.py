"""Point 8 (2.0.0) : notifications v2 — décisions du 2026-10-10 (docs/maquettes/08-notifications-v2.html).

1. « Important » ne bloque PAS : mis en avant (notification prioritaire, cadre doré), jamais plein écran forcé.
2. Pause de la télé : seulement si l'expéditeur coche « Mettre en pause la télé » (décoché par défaut).
3. Son : choisi par la personne qui reçoit (réglage de l'appareil, « silencieux » possible) ; le type propose le sien.
4. Types de messages : créés par l'utilisateur (nom, son, priorité, bloquant ou non) ; types système pré-créés,
   modifiables (pas supprimables).
5. Réponses : téléphone et PC, boutons et texte libre ; télé, seulement les réponses proposées (télécommande).

Les messages passent par la file existante (messages.preparer) : un ancien appareil les affiche comme avant.
"""
from __future__ import annotations

import logging
import secrets
import time
from typing import Any

from aiohttp import web
from homeassistant.components import persistent_notification
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import messages, roles, textes
from .const import DEFAULT_SOUND, DOMAIN, SOUNDS

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/messages"
PRIORITES = ("normale", "importante")
REPONSES_MAX = 3
REPONSE_TEXTE_MAX = 30
REPONSE_LIBRE_MAX = 200
ENVOIS_MAX = 30

# Le nom d'un type système vient du catalogue serveur (« msg2.type_<id> »), dit dans la langue du lecteur ; « nom »
# ici est le français, gardé pour les lecteurs sans langue. Les sons sont des clés de SOUNDS (identifiants).
TYPES_SYSTEME: list[dict[str, Any]] = [
    {"id": tid, "nom": textes.t(f"msg2.type_{tid}", "fr"), "son": son, "priorite": priorite, "bloquant": "non", "systeme": True}
    for tid, son, priorite in (
        ("message", DEFAULT_SOUND, "normale"),
        ("important", "Rappel", "importante"),
        ("temps", "Message", "normale"),
        ("alerte", "Alarme (insistant)", "importante"),
    )
]
# Les réponses proposées : quatre textes du catalogue (« msg2.rep_0 » à « msg2.rep_3 »), dans la langue du lecteur.
NB_REPONSES_PROPOSEES = 4


def reponses_proposees(langue: str | None = None) -> list[str]:
    return [textes.t(f"msg2.rep_{i}", langue) for i in range(NB_REPONSES_PROPOSEES)]


REPONSES_PROPOSEES = reponses_proposees("fr")


# ---------------------------------------------------------------- Types de messages


def types(store, langue: str | None = None) -> list[dict[str, Any]]:
    """Les types : système (modifiés éventuellement par l'utilisateur), puis ceux de l'utilisateur.

    Un type système que l'utilisateur n'a pas renommé porte son nom dans `langue` (sans langue : le français)."""
    propres = {str(t.get("id")): t for t in (getattr(store, "types_messages", None) or []) if isinstance(t, dict)}
    sortie = []
    for t in TYPES_SYSTEME:
        defaut = {**t, "nom": textes.t(f"msg2.type_{t['id']}", langue or "fr")}
        sortie.append({**defaut, **{k: v for k, v in propres.get(t["id"], {}).items() if k in ("nom", "son", "priorite", "bloquant")},
                       "systeme": True})
    for i, t in propres.items():
        if i not in {x["id"] for x in TYPES_SYSTEME}:
            sortie.append({"id": i, "nom": str(t.get("nom") or i), "son": t.get("son", DEFAULT_SOUND),
                           "priorite": t.get("priorite", "normale"), "bloquant": t.get("bloquant", "non"), "systeme": False})
    return sortie


def type_de(store, tid: str, langue: str | None = None) -> dict[str, Any]:
    return next((t for t in types(store, langue) if t["id"] == tid), types(store, langue)[0])


def regler_type(store, donnees: dict[str, Any]) -> dict[str, Any]:
    nom = " ".join(str(donnees.get("nom") or "").split())[:30]
    if not nom:
        raise textes.Erreur("msg2.nom_type")
    son = str(donnees.get("son") or DEFAULT_SOUND)
    if son not in SOUNDS:
        raise textes.Erreur("msg2.son_inconnu")
    priorite = str(donnees.get("priorite") or "normale")
    if priorite not in PRIORITES:
        raise textes.Erreur("msg2.priorite_inconnue")
    bloquant = str(donnees.get("bloquant") or "non")
    if bloquant not in messages.BLOQUANTS:
        raise textes.Erreur("msg2.bloquant_inconnu")
    tid = str(donnees.get("id") or "")
    liste = [t for t in (getattr(store, "types_messages", None) or []) if isinstance(t, dict)]
    if not tid:
        tid = "t" + secrets.token_hex(3)
    elif tid not in {str(t.get("id")) for t in liste} | {t["id"] for t in TYPES_SYSTEME}:
        raise textes.Erreur("msg2.type_disparu")
    t = {"id": tid, "nom": nom, "son": son, "priorite": priorite, "bloquant": bloquant}
    store.types_messages = [x for x in liste if str(x.get("id")) != tid] + [t]
    return next(x for x in types(store) if x["id"] == tid)


def supprimer_type(store, tid: str) -> None:
    if tid in {t["id"] for t in TYPES_SYSTEME}:
        raise textes.Erreur("msg2.type_systeme")
    avant = getattr(store, "types_messages", None) or []
    store.types_messages = [t for t in avant if str(t.get("id")) != tid]
    if len(store.types_messages) == len(avant):
        raise textes.Erreur("msg2.type_disparu")


# ---------------------------------------------------------------- Destinataires et envoi


def destinataires(hass: HomeAssistant, store) -> dict[str, list[dict[str, Any]]]:
    from . import matrice
    personnes: dict[str, str] = {}
    for pc in store.pcs.values():
        p = str(pc.get("person") or "")
        if p and p not in personnes:
            s = hass.states.get(p)
            personnes[p] = str(s.attributes.get("friendly_name") if s else p.split(".", 1)[-1].title())
    return {
        "personnes": sorted([{"id": k, "nom": v} for k, v in personnes.items()], key=lambda x: x["nom"].lower()),
        "appareils": sorted([{"id": str(pc["id"]), "nom": str(pc.get("name") or pc["id"]), "type": matrice.type_appareil(pc),
                              "personne": str(pc.get("person") or "")} for pc in store.pcs.values()],
                            key=lambda x: x["nom"].lower()),
    }


def _pcs_de(store, cibles: list[str]) -> list[str]:
    sortie: list[str] = []
    for c in cibles:
        c = str(c)
        if c in store.pcs:
            sortie.append(c)
        elif c.startswith("person."):
            sortie += [str(pc["id"]) for pc in store.pcs.values() if str(pc.get("person") or "") == c]
    return list(dict.fromkeys(sortie))


def _reponses(v: Any) -> list[str]:
    if not isinstance(v, list):
        return []
    sortie = []
    for r in v[:REPONSES_MAX]:
        r = " ".join(str(r or "").split())[:REPONSE_TEXTE_MAX]
        if r and r not in sortie:
            sortie.append(r)
    return sortie


async def envoyer(hass: HomeAssistant, coord, donnees: dict[str, Any], expediteur: dict[str, str]) -> dict[str, Any]:
    """Un envoi v2. expediteur : {"pc": id, "nom": …} (appli d'un décideur) ou {"user": nom} (Home Assistant)."""
    store = coord.store
    cibles = donnees.get("destinataires")
    if not isinstance(cibles, list) or not cibles:
        raise textes.Erreur("msg2.un_destinataire")
    pcs = _pcs_de(store, cibles)
    if not pcs:
        raise textes.Erreur("msg2.aucun_appareil")
    texte = str(donnees.get("texte") or "").strip()[:1000]
    if not texte:
        raise textes.Erreur("commun.message_vide")
    t = type_de(store, str(donnees.get("type") or "message"), textes.ha(hass))
    important = bool(donnees.get("important")) or t["priorite"] == "importante"
    de = str(expediteur.get("nom") or expediteur.get("user") or "Vision")[:40]
    envoi_id = secrets.token_hex(6)
    base = {
        "titre": str(donnees.get("titre") or de)[:60],
        "message": texte,
        "son": t["son"],
        # Décision 1 : « Important » ne bloque pas ; seul un type marqué bloquant bloque.
        "bloquant": t["bloquant"],
        "duree": 0 if important else 30,
        "expire": 60,
    }
    plus = {"v": 2, "de": de, "reponses": _reponses(donnees.get("reponses")), "important": important,
            "pause_tele": bool(donnees.get("pause_tele")), "voix": bool(donnees.get("voix")),
            "type_message": t["id"], "type_nom": t["nom"], "envoi": envoi_id, "libre": True}
    suivi = []
    for pc_id in pcs:
        m = messages.preparer(store, pc_id, base, None)
        # Le nom du type, dans la langue de l'appareil qui le reçoit.
        m.update(plus, type_nom=type_de(store, t["id"], textes.pour(hass, store.pcs.get(pc_id)))["nom"])
        s = store.pcs[pc_id]["messages_suivi"][str(m["id"])]
        s.update({"expediteur": expediteur, "envoi": envoi_id, "texte": texte[:120]})
        suivi.append({"pc": pc_id, "nom": store.pcs[pc_id].get("name"), "id": m["id"]})
    envois = list(getattr(store, "envois_messages", None) or [])
    envois.append({"id": envoi_id, "ts": time.time(), "texte": texte[:120], "de": de, "type": t["nom"],
                   "important": important, "pcs": [{"pc": x["pc"], "id": x["id"]} for x in suivi]})
    store.envois_messages = envois[-ENVOIS_MAX:]
    await coord.async_ecrire()
    _LOGGER.info("Message v2 « %s » de %s vers %d appareil(s)", texte[:40], de, len(suivi))
    return {"envoi": envoi_id, "messages": suivi}


# L'état d'un message → la clé de son libellé dans le catalogue serveur.
ETATS_LISIBLES = {"en_attente": "msg2.etat_en_attente", "affiche": "msg2.etat_affiche", "lu": "msg2.etat_lu",
                  "ferme": "msg2.etat_ferme", "repondu": "msg2.etat_repondu", "expire": "msg2.etat_expire",
                  "erreur": "msg2.etat_erreur", "libere_parent": "msg2.etat_libere", "libere_auto": "msg2.etat_libere"}


def suivi(store, n: int = 10, langue: str | None = None) -> list[dict[str, Any]]:
    sortie = []
    for e in reversed(list(getattr(store, "envois_messages", None) or [])[-n:]):
        lignes = []
        for x in e.get("pcs", []):
            pc = store.pcs.get(x["pc"]) or {}
            s = (pc.get("messages_suivi") or {}).get(str(x["id"])) or {}
            etat = str(s.get("etat") or "en_attente")
            lignes.append({"pc": x["pc"], "nom": str(pc.get("name") or x["pc"]), "etat": etat,
                           "etat_texte": textes.t(ETATS_LISIBLES[etat], langue) if etat in ETATS_LISIBLES else etat,
                           "reponse": str(s.get("reponse") or ""),
                           "maj": s.get("maj") or s.get("ts")})
        sortie.append({**{k: e.get(k) for k in ("id", "ts", "texte", "de", "type", "important")}, "appareils": lignes})
    return sortie


async def reponse_recue(hass: HomeAssistant, coord, pc: dict[str, Any], suivi_msg: dict[str, Any], reponse: str) -> None:
    """Une réponse arrive : elle est notée, et l'expéditeur est prévenu (son appli, ou Home Assistant)."""
    suivi_msg["reponse"] = reponse
    from . import musique_arret
    if musique_arret.reponse(suivi_msg):  # 2.4.0 : « Continuer » sur l'avertissement d'arrêt de la musique
        return
    nom = str(pc.get("name") or pc["id"])
    personne = str(pc.get("person") or "")
    if personne:
        s = hass.states.get(personne)
        nom = str(s.attributes.get("friendly_name")) if s else nom
    exp = suivi_msg.get("expediteur") or {}
    vers_appli = bool(exp.get("pc") and exp["pc"] in coord.store.pcs)
    # Dans la langue de l'expéditeur : son appareil, ou Home Assistant.
    langue = textes.pour(hass, coord.store.pcs.get(exp["pc"])) if vers_appli else textes.ha(hass)
    titre = textes.t("msg2.reponse_de", langue, nom=nom)
    texte = textes.t("msg2.reponse_texte", langue, reponse=reponse,
                     texte=str(suivi_msg.get('texte') or suivi_msg.get('titre') or '')[:60])
    if vers_appli:
        messages.preparer(coord.store, exp["pc"], {"titre": titre, "message": texte, "son": "Message",
                                                    "bloquant": "non", "duree": 30, "expire": 120}, None)
    else:
        persistent_notification.async_create(hass, texte, title=titre, notification_id=f"vision_reponse_{secrets.token_hex(3)}")


# ---------------------------------------------------------------- Portes


def _coord(hass: HomeAssistant):
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


async def _traiter(hass: HomeAssistant, coord, corps: dict[str, Any], expediteur: dict[str, str],
                   langue: str | None = None) -> dict[str, Any]:
    action = str(corps.get("action") or "")
    store = coord.store
    if action == "envoyer":
        r = await envoyer(hass, coord, corps, expediteur)
        return {"ok": True, "retour": textes.t("commun.message_envoye", langue), **r}
    if action == "type":
        t = regler_type(store, corps)
        await coord.async_ecrire()
        return {"ok": True, "retour": textes.t("msg2.type_enregistre", langue),
                "type": next((x for x in types(store, langue) if x["id"] == t["id"]), t)}
    if action == "supprimer_type":
        supprimer_type(store, str(corps.get("id") or ""))
        await coord.async_ecrire()
        return {"ok": True, "retour": textes.t("msg2.type_supprime", langue)}
    raise textes.Erreur("msg2.action_inconnue")


def _etat(hass: HomeAssistant, store, langue: str | None = None) -> dict[str, Any]:
    """Ce que la carte ou l'appli affichent, dans la langue de qui lit (sans langue : celle de Home Assistant)."""
    langue = langue or textes.ha(hass)
    return {"ok": True, "destinataires": destinataires(hass, store), "types": types(store, langue),
            "sons": list(SOUNDS), "priorites": list(PRIORITES), "bloquants": list(messages.BLOQUANTS),
            "reponses_proposees": reponses_proposees(langue), "envois": suivi(store, langue=langue)}


class PcParentalMessagesView(HomeAssistantView):
    """Carte « Messages » du tableau Vision (administrateurs de Home Assistant)."""

    url = URL
    name = "api:pc_parental:messages"

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.Response:
        if not request["hass_user"].is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        return self.json(_etat(self.hass, coord.store))

    async def post(self, request: web.Request) -> web.Response:
        user = request["hass_user"]
        if not user.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        langue = textes.ha(self.hass)
        try:
            return self.json(await _traiter(self.hass, coord, corps if isinstance(corps, dict) else {},
                                            {"user": user.name or "Home Assistant"}, langue))
        except ValueError as e:
            return self.json({"ok": False, "error": "valeur", "retour": textes.erreur(e, langue)}, status_code=400)


class PcParentalMessagesAppView(HomeAssistantView):
    """Le même, depuis l'appli d'un décideur (identifiant + secret de l'appareil)."""

    url = URL + "/app"
    name = "api:pc_parental:messages_app"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.noter_langue(pc, corps)
        langue = textes.pour(self.hass, pc)
        if not roles.a(coord.store, pc, "gerer"):
            return self.json({"ok": False, "error": "gerer", "retour": textes.t("commun.reserve_decideurs", langue)},
                             status_code=403)
        if str(corps.get("action") or "lire") == "lire":
            return self.json(_etat(self.hass, coord.store, langue))
        nom = str(pc.get("name") or pc["id"])
        p = str(pc.get("person") or "")
        if p and (s := self.hass.states.get(p)) is not None:
            nom = str(s.attributes.get("friendly_name") or nom)
        try:
            return self.json(await _traiter(self.hass, coord, corps, {"pc": str(pc["id"]), "nom": nom}, langue))
        except ValueError as e:
            return self.json({"ok": False, "error": "valeur", "retour": textes.erreur(e, langue)}, status_code=400)


def enregistrer_vues(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalMessagesView(hass))
    hass.http.register_view(PcParentalMessagesAppView(hass))
