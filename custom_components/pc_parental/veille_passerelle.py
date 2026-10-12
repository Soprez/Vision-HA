"""Le vrai rendu des cartes sur la veille des appareils, sans jeton global (Vision 1.77.0, point 6).

Depuis la 1.61.0, plus aucun appareil ne reçoit de jeton Home Assistant : le jeton d'affichage ouvrait la
lecture de tout Home Assistant. Le PC (Edge) et Android/TV (WebView) retombaient donc sur un dessin natif
qui ne sait pas rendre les cartes Lovelace (météo en anglais, picture-elements absente, nombres bruts).

Ici, le frontend de Home Assistant lui-même dessine les cartes, mais il ne parle qu'à une passerelle :

- `/veille/acces` remet à l'appareil un **ticket** : signé avec le secret de CET appareil, valable 24 h,
  qui n'ouvre que la passerelle (aucune API de Home Assistant ne l'accepte).
- La page d'entrée redirige le websocket du frontend vers `/api/pc_parental/veille/ws`, puis charge le
  frontend sur le panneau `/vision-veille`.
- La passerelle authentifie le ticket et exécute les commandes au nom d'un utilisateur d'affichage
  **non administrateur, en lecture seule**, en ne laissant passer que :
  * les commandes sans entité utiles à l'affichage (configuration publique, thèmes, traductions, ressources) ;
  * les commandes sur des entités, si TOUTES sont parmi celles des cartes rendues disponibles pour cet
    appareil (états, historique, statistiques, prévisions météo, flux de caméra) ;
  * et les états et événements qui sortent sont filtrés à ces seules entités.
  Tout le reste est refusé : modèles (templates), scripts, appels de service, configuration, journal.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import re
import time
from typing import Any

from aiohttp import WSMsgType, web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import textes
from .const import PRODUIT

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/veille/ws"
DUREE_TICKET = 24 * 3600
NOM_UTILISATEUR = "Vision affichage (lecture)"
RE_ENTITE = re.compile(r"\b([a-z_]+\.[a-z0-9_]+)\b")

# Sans entité : ce dont le frontend a besoin pour démarrer et dessiner, rien qui écrive ni n'expose de données.
SANS_ENTITE = frozenset({
    "auth/current_user", "get_config", "get_panels", "ping", "supported_features",
    "frontend/get_themes", "frontend/subscribe_themes", "frontend/get_translations", "frontend/get_icons",
    "frontend/get_user_data", "frontend/subscribe_user_data", "frontend/get_system_data", "frontend/subscribe_system_data",
    "lovelace/resources", "get_services", "manifest/list", "manifest/get", "integration/descriptions",
    "config/area_registry/list", "config/floor_registry/list", "config/label_registry/list",
    "config/category_registry/list", "unsubscribe_events", "unsubscribe",
    "pc_parental/veille_page",
    # 1.78.2 : lecture seule, rien de la maison (état du recorder, jeton des logos de /api/brands).
    "recorder/info", "brands/access_token",
    # 1.78.4 : jeton du relais des tuiles de la carte (fond de carte seulement, rien de la maison).
    "map_tiles/access_token",
})
# Sur des entités : toutes doivent être autorisées pour cet appareil.
SUR_ENTITES = frozenset({
    "subscribe_entities", "get_states", "history/stream", "history/history_during_period",
    "recorder/statistics_during_period", "recorder/get_statistics_metadata", "weather/subscribe_forecast",
    "camera/stream", "camera/capabilities", "camera/get_prefs", "config/entity_registry/list_for_display",
    "config/device_registry/list", "todo/item/list", "todo/item/subscribe", "calendar/event/subscription",
})
EVENEMENTS = frozenset({
    # 1.78.2 : sans ces deux-là, le frontend n'a pas sa liste de services et reste sur « Chargement des données »
    # (ils ne portent que des noms de domaine et de service).
    "service_registered", "service_removed",
    "state_changed", "core_config_updated", "themes_updated", "panels_updated", "component_loaded",
    "lovelace_updated", "area_registry_updated", "device_registry_updated", "floor_registry_updated",
    "label_registry_updated", "entity_registry_updated",
})


# ----------------------------------------------------------------------------- Le ticket


def ticket(pc: dict[str, Any], maintenant: float | None = None) -> str:
    fin = int((maintenant or time.time()) + DUREE_TICKET)
    signe = hmac.new(str(pc["secret"]).encode(), f"veille|{pc['id']}|{fin}".encode(), hashlib.sha256).hexdigest()[:40]
    return f"vv1.{pc['id']}.{fin}.{signe}"


def verifier(store, brut: str) -> dict[str, Any] | None:
    """L'appareil du ticket, s'il est intact, pas expiré, et que l'appareil existe et est actif."""
    try:
        version, pc_id, fin, signe = str(brut or "").split(".")
        fin_i = int(fin)
    except ValueError:
        return None
    pc = store.pcs.get(pc_id)
    if version != "vv1" or pc is None or not pc.get("secret") or fin_i < time.time() or fin_i > time.time() + DUREE_TICKET + 60:
        return None
    attendu = hmac.new(str(pc["secret"]).encode(), f"veille|{pc_id}|{fin_i}".encode(), hashlib.sha256).hexdigest()[:40]
    return pc if hmac.compare_digest(attendu, signe) else None


# ----------------------------------------------------------------------------- Ce que l'appareil peut lire


def entites_de_la_page(hass: HomeAssistant, page: dict[str, Any]) -> set[str]:
    """Les entités citées par les cartes rendues disponibles pour cet appareil (et la météo de l'horloge)."""
    connues = {e.entity_id for e in hass.states.async_all()}
    brut = json.dumps([s["cartes"] for t in page.get("tableaux") or [] for s in t.get("sections") or []], ensure_ascii=False)
    trouvees = {m for m in RE_ENTITE.findall(brut) if m in connues}
    if page.get("meteo") in connues:
        trouvees.add(page["meteo"])
    return trouvees


def _entites_demandees(msg: dict[str, Any]) -> list[str] | None:
    """Les entités qu'une commande vise ; None si elle n'en nomme aucune (alors : refusée, sauf exceptions)."""
    vues: list[str] = []
    for cle in ("entity_id", "entity_ids", "statistic_ids"):
        v = msg.get(cle)
        if isinstance(v, str):
            vues.append(v)
        elif isinstance(v, list):
            vues.extend(str(x) for x in v)
    return vues or None


# 1.78.2 : ce que le frontend demande sans en avoir besoin pour dessiner : on répond ici, sans rien exécuter.
REPONSES_LOCALES: dict[str, Any] = {
    "repairs/list_issues": {"issues": []},
    # 1.78.3 : abonnements et préférences dont la veille n'a que faire (rien n'est enregistré).
    "labs/subscribe": None, "persistent_notification/subscribe": None, "frontend/set_user_data": None,
    # 1.86.4 : transmise à HA (1.84.0), sa réponse « tout est chargé » faisait RECHARGER la page au frontend,
    # qui perdait alors la passerelle et tombait sur l'écran de connexion (veille noire, « fermée après 0 min »).
    # Répondue ici : abonnement accepté, aucun événement (rien n'est « en cours de chargement »).
    "subscribe_bootstrap_integrations": None,
}
EVENEMENTS_MUETS = frozenset({"repairs_issue_registry_updated"})


# Ce qu'un modèle (template) peut contenir pour être rendu ici : du texte et des {{ states('entité') }}.
RE_MODELE_SIMPLE = re.compile(r"\{\{\s*states\(\s*['\"]([a-z_]+\.[a-z0-9_]+)['\"]\s*\)\s*\}\}")
# Les états rendus en clair dans un modèle : catalogue serveur, « veille.etat_<état> », langue de l'appareil.
ETATS_LISIBLES = frozenset({"home", "not_home", "on", "off", "open", "closed", "locked", "unlocked", "unavailable",
                            "unknown"})


def rendre_modele(hass: HomeAssistant, autorisees: set[str], modele: Any) -> str:
    """Un modèle Jinja n'est JAMAIS transmis à Home Assistant (il lirait toute la maison) : les seuls
    {{ states('x') }} d'entités de cette veille sont remplacés ici ; tout autre modèle donne une chaîne vide
    (jamais son texte source à l'écran)."""
    texte = str(modele or "")
    reste = RE_MODELE_SIMPLE.sub("", texte)
    if "{{" in reste or "{%" in reste or "{#" in reste:
        return ""

    def valeur(m: re.Match) -> str:
        eid = m.group(1)
        etat = hass.states.get(eid) if eid in autorisees else None
        if etat is None:
            return ""
        return textes.t(f"veille.etat_{etat.state}") if etat.state in ETATS_LISIBLES else etat.state

    return RE_MODELE_SIMPLE.sub(valeur, texte)


def reponse_locale(msg: dict[str, Any], hass: HomeAssistant | None = None,
                   autorisees: frozenset[str] | set[str] = frozenset()) -> tuple[bool, Any]:
    """(True, résultat) si la passerelle répond elle-même à ce message ; (False, None) sinon.
    Un résultat de forme {"__evenement__": …} envoie en plus cet événement (abonnement à un modèle)."""
    genre = str(msg.get("type") or "")
    if genre == "render_template" and hass is not None:
        rendu = rendre_modele(hass, set(autorisees), msg.get("template"))
        return True, {"__evenement__": {"result": rendu, "listeners": {"all": False, "entities": [], "domains": [], "time": False}}}
    if genre == "auth/sign_path" and hass is not None:
        # L'image d'une caméra de cette veille : l'adresse que Home Assistant publie déjà dans son état
        # (avec le jeton propre à la caméra). Jamais la signature d'un autre chemin (elle ouvrirait l'API).
        m = re.fullmatch(r"/api/camera_proxy/(camera\.[a-z0-9_]+)", str(msg.get("path") or ""))
        etat = hass.states.get(m.group(1)) if m and m.group(1) in autorisees else None
        image = etat.attributes.get("entity_picture") if etat is not None else None
        if image:
            return True, {"path": str(image)}
        return False, None
    if genre in REPONSES_LOCALES:
        return True, REPONSES_LOCALES[genre]
    if genre == "subscribe_events" and msg.get("event_type") in EVENEMENTS_MUETS:
        return True, None  # un abonnement qui ne recevra rien
    # Le frontend écrit ses propres erreurs dans le journal : jamais par la veille (on fait comme si).
    if genre == "call_service" and msg.get("domain") == "system_log" and msg.get("service") == "write":
        return True, None
    return False, None


class Filtre:
    """Décide pour chaque commande entrante et filtre chaque message sortant."""

    def __init__(self, autorisees: set[str]) -> None:
        self.autorisees = autorisees
        self.etats: set[int] = set()          # réponses de get_states à filtrer
        self.registre: set[int] = set()       # réponses d'entity_registry/list_for_display
        self.appareils: set[int] = set()      # réponses de device_registry/list
        self.evenements: set[int] = set()     # abonnements à filtrer (state_changed, entity_registry_updated)

    def entree(self, msg: dict[str, Any]) -> tuple[dict[str, Any] | None, str]:
        """Rend (message à exécuter, "") ou (None, raison du refus)."""
        genre = str(msg.get("type") or "")
        if genre == "subscribe_events":
            if msg.get("event_type") not in EVENEMENTS:
                return None, textes.t("pass.evenement")
            self.evenements.add(int(msg["id"]))
            return msg, ""
        if genre in SANS_ENTITE:
            return msg, ""
        if genre not in SUR_ENTITES:
            return None, textes.t("pass.commande")
        if genre == "subscribe_entities":
            voulues = msg.get("entity_ids")
            choisies = sorted(self.autorisees & set(voulues) if isinstance(voulues, list) else self.autorisees)
            # Une liste vide vaudrait « toutes les entités » pour Home Assistant : jamais.
            msg = {**msg, "entity_ids": choisies or ["sensor.vision_aucune_entite"]}
            return msg, ""
        if genre == "get_states":
            self.etats.add(int(msg["id"]))
            return msg, ""
        if genre == "config/entity_registry/list_for_display":
            self.registre.add(int(msg["id"]))
            return msg, ""
        if genre == "config/device_registry/list":
            self.appareils.add(int(msg["id"]))
            return msg, ""
        demandees = _entites_demandees(msg)
        if not demandees:
            return None, textes.t("pass.entite")
        if set(demandees) <= self.autorisees:
            return msg, ""
        # 1.84.0 : une carte d'historique ou de statistiques qui mêle une entité non autorisée ne perd que
        # celle-là (avant : toute la carte restait vide). Une commande sur une seule entité reste refusée.
        filtre_ok = False
        nouveau = dict(msg)
        for cle in ("entity_ids", "statistic_ids"):
            v = msg.get(cle)
            if isinstance(v, list):
                gardees = [x for x in v if str(x) in self.autorisees]
                if not gardees:
                    return None, textes.t("pass.entites", None, liste=", ".join(sorted(set(map(str, v)) - self.autorisees)))
                nouveau[cle] = gardees
                filtre_ok = True
        if filtre_ok and not isinstance(msg.get("entity_id"), str):
            return nouveau, ""
        return None, textes.t("pass.entites", None, liste=", ".join(sorted(set(demandees) - self.autorisees)))

    def sortie(self, msg: Any, appareils_permis: set[str]) -> Any:
        if not isinstance(msg, dict):
            return msg
        ident = msg.get("id")
        if msg.get("type") == "result" and ident in self.etats and isinstance(msg.get("result"), list):
            return {**msg, "result": [e for e in msg["result"] if isinstance(e, dict) and e.get("entity_id") in self.autorisees]}
        if msg.get("type") == "result" and ident in self.registre and isinstance(msg.get("result"), dict):
            r = msg["result"]
            return {**msg, "result": {**r, "entities": [e for e in r.get("entities") or [] if isinstance(e, dict) and e.get("ei") in self.autorisees]}}
        if msg.get("type") == "result" and ident in self.appareils and isinstance(msg.get("result"), list):
            return {**msg, "result": [d for d in msg["result"] if isinstance(d, dict) and d.get("id") in appareils_permis]}
        if msg.get("type") == "event" and ident in self.evenements:
            ev = msg.get("event") or {}
            eid = (ev.get("data") or {}).get("entity_id")
            if eid is not None and eid not in self.autorisees:
                return None
        return msg


# ----------------------------------------------------------------------------- L'utilisateur d'affichage


async def utilisateur(hass: HomeAssistant):
    """Un utilisateur système, non administrateur, du groupe « lecture seule » (créé une fois)."""
    for u in await hass.auth.async_get_users():
        if u.system_generated and u.name == NOM_UTILISATEUR:
            return u
    return await hass.auth.async_create_system_user(NOM_UTILISATEUR, group_ids=["system-read-only"])


# ----------------------------------------------------------------------------- La passerelle


class VeillePasserelleView(HomeAssistantView):
    url = URL
    name = "api:pc_parental:veille_ws"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.WebSocketResponse:
        from homeassistant.components.websocket_api.connection import ActiveConnection
        from homeassistant.const import __version__

        from . import veille_page

        ws = web.WebSocketResponse(heartbeat=55)
        await ws.prepare(request)
        await ws.send_json({"type": "auth_required", "ha_version": __version__})
        coord = veille_page._coord(self.hass)
        try:
            premier = await ws.receive_json(timeout=10)
        except Exception:  # noqa: BLE001
            await ws.close()
            return ws
        brut = str(premier.get("access_token") or "") if isinstance(premier, dict) else ""
        pc = verifier(coord.store, brut) if coord is not None else None
        # 1.78.0 : un ticket déjà remis ne vaut plus quand on éteint « Veille : vrai rendu des cartes ».
        if pc is not None and not getattr(coord.store, "veille_rendu", False):
            pc = None
        # 1.82.0 : la passerelle est fermée quand « Tableaux HA dans la veille » (ou la veille) est coupé.
        from . import modules

        if pc is not None and modules.coupe(coord.store, "veille_tableaux"):
            pc = None
        if pc is None:
            await ws.send_json({"type": "auth_invalid", "message": textes.t("pass.ticket", textes.ha(self.hass))})
            await ws.close()
            return ws
        textes.parler(textes.pour(self.hass, pc))
        await ws.send_json({"type": "auth_ok", "ha_version": __version__})

        # La page de CET appareil : les tableaux visés au téléphone n'ouvrent pas ceux de la télé, et inversement.
        ecran = "telephone" if pc.get("type") in ("telephone", "tablette") else "tele"
        page = await veille_page.page(self.hass, coord, pc, ecran)
        filtre = Filtre(entites_de_la_page(self.hass, page))
        from homeassistant.helpers import entity_registry as er

        reg = er.async_get(self.hass)
        appareils_permis = {e.device_id for eid in filtre.autorisees if (e := reg.async_get(eid)) is not None and e.device_id}
        file: asyncio.Queue = asyncio.Queue()

        def envoyer(m: Any) -> None:
            if isinstance(m, (bytes, str)):
                try:
                    m = json.loads(m)
                except ValueError:
                    return
            m = filtre.sortie(m, appareils_permis)
            if m is not None:
                file.put_nowait(m)

        # 1.78.1 : chaque ouverture et chaque fermeture se lisent dans le journal (vérifier le vrai rendu à distance).
        _LOGGER.warning("%s : veille de « %s » ouverte par la passerelle (%s, %d entités autorisées).",
                        PRODUIT, pc.get("name"), ecran, len(filtre.autorisees))
        refusees: dict[str, int] = {}
        locales: set[int] = set()
        debut = time.time()
        connexion = ActiveConnection(logging.LoggerAdapter(_LOGGER, {"connid": pc["id"]}), self.hass, envoyer,
                                     await utilisateur(self.hass), None, request.remote)

        async def _ecrire() -> None:
            while True:
                m = await file.get()
                if ws.closed:
                    return
                await ws.send_str(json.dumps(m, default=str))

        ecrivain = asyncio.create_task(_ecrire())
        # Le ticket vaut 24 h : la connexion aussi. L'appareil en redemande un par /veille/acces.
        fin = int(brut.split(".")[2])
        expiration = asyncio.get_running_loop().call_later(
            max(0.0, fin - time.time()), lambda: asyncio.ensure_future(ws.close()))
        try:
            async for brut in ws:
                if brut.type != WSMsgType.TEXT:
                    break
                try:
                    donnees = json.loads(brut.data)
                except ValueError:
                    continue
                for msg in donnees if isinstance(donnees, list) else [donnees]:
                    if not isinstance(msg, dict) or not isinstance(msg.get("id"), int):
                        continue
                    if msg.get("type") == "pc_parental/veille_page":
                        envoyer({"id": msg["id"], "type": "result", "success": True, "result": page})
                        continue
                    local, resultat = reponse_locale(msg, self.hass, filtre.autorisees)
                    if local:
                        evenement = resultat.pop("__evenement__", None) if isinstance(resultat, dict) else None
                        envoyer({"id": msg["id"], "type": "result", "success": True,
                                 "result": None if evenement is not None else resultat})
                        if evenement is not None:
                            envoyer({"id": msg["id"], "type": "event", "event": evenement})
                            locales.add(msg["id"])
                        continue
                    # 1.78.4 : le désabonnement d'un modèle servi ici ne concerne pas Home Assistant.
                    if msg.get("type") == "unsubscribe_events" and msg.get("subscription") in locales:
                        locales.discard(msg.get("subscription"))
                        envoyer({"id": msg["id"], "type": "result", "success": True, "result": None})
                        continue
                    a_faire, refus = filtre.entree(msg)
                    if a_faire is None:
                        _LOGGER.debug("%s : veille de « %s », refusé : %s (%s)", PRODUIT, pc.get("name"), msg.get("type"), refus)
                        cle_refus = str(msg.get("type"))
                        if " : " in refus:
                            cle_refus += " (" + refus.split(" : ", 1)[1][:80] + ")"
                        refusees[cle_refus] = refusees.get(cle_refus, 0) + 1
                        envoyer({"id": msg["id"], "type": "result", "success": False,
                                 "error": {"code": "unauthorized", "message": refus}})
                        continue
                    connexion.async_handle(a_faire)
        finally:
            _LOGGER.warning("%s : veille de « %s » fermée après %d min%s.", PRODUIT, pc.get("name"),
                            int((time.time() - debut) // 60),
                            "" if not refusees else " ; refusé : " + ", ".join(f"{k} ×{v}" for k, v in sorted(refusees.items())))
            expiration.cancel()
            connexion.async_handle_close()
            ecrivain.cancel()
            await ws.close()
        return ws
