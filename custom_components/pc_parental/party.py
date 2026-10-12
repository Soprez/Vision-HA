"""Point 14 bis (1.98.0) : le Mode Party.

Une télé de la maison affiche en plein écran ce qui passe sur une enceinte (ou un groupe) : pochette, titre,
artiste, et la suite si Music Assistant la donne. Un décideur le lance, l'arrête et le pilote depuis son téléphone :
choix de l'écran, de l'enceinte, lecture, volume.

2.2.0 : Vision ne touche à aucune entité propre à une maison (l'ancien « Mode Fête »). À la place,
la Party émet les événements Home Assistant `vision_party_started` et `vision_party_stopped` (écran, enceinte,
appareil et personne qui l'ont lancée ou arrêtée) : chaque famille y branche ses propres automatisations.

Rien ici ne touche au verrouillage : la télé reste une télé, Retour ou Accueil quittent l'écran Party.

Points d'accès (POST JSON `{id, secret, ...}`, comme le relevé) :
- `/api/pc_parental/party/etat`      ce qu'il faut afficher (télé) ou piloter (décideur) ;
- `/api/pc_parental/party/commande`  lancer, arreter, lecture, suivant, precedent, volume (décideur seulement) ;
- `/api/pc_parental/party/pochette`  l'image de ce qui passe (lecteur de la Party, ou entité d'un widget permis) ;
- `/api/pc_parental/party/musique`   1.99.0 : choisir quoi jouer (recherche, playlists, radios, favoris de Music
  Assistant) ; décideur, ou la télé de la Party.
1.99.0 : « jouer », « retirer » un titre de la file, invités (QR code → party_invites.py : page publique, jeton de
la Party, ajout direct ou validation par un décideur).
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import secrets
import time
import zlib
from collections import OrderedDict
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.const import EVENT_HOMEASSISTANT_STOP, EVENT_STATE_CHANGED
from homeassistant.core import Context, Event, HomeAssistant, callback

from . import clins, journal_musique, matrice, musique, party_invites, roles, textes, volume_party, widgets
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/party"

# 2.3.7 (APK 1.87.1, l'auteur : « dans la playlist les images ne se chargent pas ») : l'appli ne lit plus jamais l'adresse
# de Music Assistant (http, réseau local) ; HA lui donne une référence opaque et relaie l'image (/party/image).
_CLE_REFS = DOMAIN + "_images_appli"
REFS_MAX = 2000


def ref_image(hass: HomeAssistant, url: str) -> str:
    """Une référence opaque pour une image que HA donne lui-même à l'appli ; "" si pas d'image http(s)."""
    if not isinstance(url, str) or not url.startswith(("http://", "https://")):
        return ""
    d = hass.data.setdefault(_CLE_REFS, {"sel": secrets.token_hex(16), "refs": OrderedDict()})
    ref = hashlib.sha256((d["sel"] + url).encode()).hexdigest()[:32]
    d["refs"][ref] = url
    d["refs"].move_to_end(ref)
    while len(d["refs"]) > REFS_MAX:
        d["refs"].popitem(last=False)
    return ref


def url_de_ref(hass: HomeAssistant, ref: str) -> str:
    return str((hass.data.get(_CLE_REFS) or {}).get("refs", {}).get(str(ref or "")[:64]) or "")


def avec_refs(hass: HomeAssistant, liste: Any) -> Any:
    """Ajoute `image_ref` à chaque élément qui a une image ; `image` reste tel quel (APK 1.87.0)."""
    for x in liste or []:
        if isinstance(x, dict) and x.get("image"):
            ref = ref_image(hass, str(x["image"]))
            if ref:
                x["image_ref"] = ref
    return liste
EVENEMENT_DEBUT = "vision_party_started"
EVENEMENT_FIN = "vision_party_stopped"
_CLE = f"{DOMAIN}_party"
# La télé apprend le lancement par son relevé ; l'écran Party relit l'état à ce rythme.
RELIRE_S = 3

ACTIONS_LECTURE = {
    "lecture": "media_play_pause",
    "suivant": "media_next_track",
    "precedent": "media_previous_track",
}


def etat(hass: HomeAssistant) -> dict[str, Any]:
    """L'état de la Party, en mémoire (un redémarrage de Home Assistant l'arrête)."""
    return hass.data.setdefault(_CLE, {"actif": False, "ecran": "", "lecteur": "", "par": "", "depuis": 0,
                                       # 1.99.0 : invités (jeton de la Party, références de recherche, propositions).
                                       "jeton": None, "refs": {}, "propositions": [], "invites_mode": "direct",
                                       # 2.2.1 : « Continuer avec des titres similaires quand la file est vide » (non).
                                       "similaires": False})


def peut_piloter(store, pc: dict[str, Any] | None) -> bool:
    """Lancer, arrêter, piloter : les décideurs (fonction « gerer »)."""
    return roles.a(store, pc, "gerer")


# 2.1.0 : la Party est ouverte à tous. Pendant une Party, tout appareil de la maison choisit la
# musique, met en pause, passe, règle le volume et retire un titre. Restent aux décideurs : lancer, arrêter, choisir
# l'enceinte, les invités et l'interrupteur Mode Fête. Garde-fou : un appareil fermé par ses règles (la nuit, une
# plage, une fermeture) ne participe pas — une Party ne rouvre pas un téléphone d'enfant à minuit.
ACTIONS_TOUS = {"jouer", "retirer", "volume", "volume_membre", "lecture", "suivant", "precedent", "ensuite_element"}


def ferme_maintenant(store, pc: dict[str, Any] | None) -> bool:
    try:
        return bool((store.evaluate(pc) or {}).get("locked"))
    except Exception:  # noqa: BLE001 - dans le doute, l'appareil ne pilote pas
        return True


def peut_participer(hass: HomeAssistant, store, pc: dict[str, Any] | None) -> bool:
    """Un décideur, toujours ; tout autre appareil pendant une Party, s'il n'est pas fermé."""
    if peut_piloter(store, pc):
        return True
    return bool(etat(hass)["actif"]) and not ferme_maintenant(store, pc)


def est_tele(pc: dict[str, Any] | None) -> bool:
    return matrice.type_appareil(pc or {}) == "tv"


def ecrans(store) -> list[dict[str, Any]]:
    """Les télés de la maison où lancer la Party."""
    sortie = [{"id": str(pc["id"]), "nom": str(pc.get("name") or pc["id"])}
              for pc in store.pcs.values() if est_tele(pc)]
    return sorted(sortie, key=lambda e: e["nom"].lower())


def lecteurs(hass: HomeAssistant, store=None, langue: str | None = None) -> list[dict[str, Any]]:
    """Les enceintes et groupes (entités media_player) ; un groupe a plusieurs membres.

    1.99.0 : les lecteurs de Music Assistant d'abord (« ma » : on peut y choisir quoi jouer) ; le double Google Cast
    d'une enceinte déjà dans Music Assistant (même nom) est caché, pour ne pas montrer deux « Salon ».
    La raison d'un lecteur grisé est dite dans `langue` (celle de l'appareil ; sans langue, celle de la requête)."""
    ma = musique.lecteurs_ma(hass)
    sortie = []
    for s in hass.states.async_all("media_player"):
        membres = s.attributes.get("group_members")
        e = {"entity_id": s.entity_id, "nom": str(s.attributes.get("friendly_name") or s.entity_id),
             "etat": s.state, "groupe": isinstance(membres, (list, tuple)) and len(membres) > 1,
             "ma": s.entity_id in ma}
        # 2.2.2 (l'auteur : « je n'ai pas toutes les télés ») : rien n'est caché ; ce qui ne peut pas servir est grisé, avec
        # la raison en clair.
        if s.state == "unavailable":
            e["dispo"], e["raison"] = False, textes.t("party.raison_hors_ligne", langue)
        elif not e["ma"]:
            e["dispo"], e["raison"] = False, textes.t("party.raison_hors_ma", langue)
        else:
            e["dispo"], e["raison"] = True, ""
        sortie.append(e)
    noms_ma = {e["nom"].lower() for e in sortie if e["ma"]}
    sortie = [e for e in sortie if e["ma"] or e["nom"].lower() not in noms_ma]
    # 2.2.5 (retour d'essai) : seulement les lecteurs Music Assistant cochés dans les options (Vision > Musique ; tous par défaut),
    # et les hors ligne masqués si l'option le demande.
    # 2.2.9 (l'auteur : « on doit rester toujours dans notre lecteur ») : le lecteur « · Vision » d'une télé (Vision joue le
    # son lui-même) remplace le lecteur Cast de cette même télé, qui est masqué : plus de récepteur qui prend l'écran.
    jumeaux = {e["nom"][: -len(SUFFIXE_VISION)].strip().lower(): e for e in sortie if e["ma"] and e["nom"].endswith(SUFFIXE_VISION)}
    remplaces = {e["entity_id"]: jumeaux[e["nom"].strip().lower()]["entity_id"] for e in sortie
                 if e["ma"] and not e["nom"].endswith(SUFFIXE_VISION) and e["nom"].strip().lower() in jumeaux}
    sortie = [e for e in sortie if e["entity_id"] not in remplaces]
    reglage = getattr(store, "musique", None) if store is not None else None
    if reglage is not None:
        coches = reglage.get("enceintes")
        if coches is not None:
            # Le lecteur Vision d'une télé hérite de la case de son lecteur Cast (ex. en essai : « Téléviseur du salon »).
            coches = set(coches) | {v for c, v in remplaces.items() if c in coches}
        # Sans Music Assistant du tout (autres installations), les media_player restent proposés comme avant.
        if any(e["ma"] for e in sortie):
            sortie = [e for e in sortie if e["ma"] and (coches is None or e["entity_id"] in coches)]
        if reglage.get("masquer_hors_ligne"):
            sortie = [e for e in sortie if e["dispo"]]
    return sorted(sortie, key=lambda e: (not e["dispo"], not e["ma"], e["nom"].lower()))


SUFFIXE_VISION = " · Vision"


# L'aide sous la liste des enceintes : clé du catalogue serveur, dite dans la langue de l'appareil.
AIDE_LECTEURS = "party.aide_lecteurs"


def _mots(nom: str) -> set[str]:
    return {m for m in "".join(c if c.isalnum() else " " for c in str(nom or "").lower()).split() if len(m) > 2}


def est_l_ecran(hass: HomeAssistant, store, lecteur: str, ecran: str) -> bool:
    """2.2.0 (retour d'essai) : l'enceinte est-elle la télé de la Party elle-même ? Music Assistant y joue alors par
    Google Cast, dont l'appli prend tout l'écran de la télé : l'écran Party disparaît et la lecture repart à chaque
    retour de Vision. Une télé ne peut donc pas être à la fois l'écran et l'enceinte."""
    s = hass.states.get(lecteur)
    tele = store.pcs.get(ecran) if store is not None else None
    if s is None or not tele:
        return False
    return str(s.attributes.get("friendly_name") or "").strip().lower() == str(tele.get("name") or "").strip().lower()


def lecteurs_pour(hass: HomeAssistant, store, pc: dict[str, Any] | None, langue: str | None = None) -> list[dict[str, Any]]:
    """2.2.13 (l'auteur : « je clique sur Disney classique, il ne se passe rien » sur la télé des parents) : les enceintes
    cochées, plus, pour une télé, SON lecteur « · Vision » (la télé elle-même, sans Cast), même s'il n'est pas coché."""
    langue = langue or (textes.pour(hass, pc) if pc else None)
    l = lecteurs(hass, store, langue)
    if not (pc and est_tele(pc)):
        return l
    propre = str(pc.get("name") or "").strip().lower() + SUFFIXE_VISION.lower()
    if any(x["nom"].strip().lower() == propre for x in l):
        return l
    tous = [x for x in lecteurs(hass, None, langue) if x["ma"] and x["nom"].strip().lower() == propre]
    return sorted(l + tous, key=lambda e: (not e["dispo"], not e["ma"], e["nom"].lower()))


def lecteur_par_defaut(hass: HomeAssistant, store, pc: dict[str, Any] | None = None) -> str:
    """L'enceinte d'une Party lancée sans choix : la dernière, sinon celle de Music Assistant de la même pièce
    (« Salon » pour « Téléviseur du salon »), jamais la télé elle-même."""
    st = etat(hass)
    l = [x for x in lecteurs_pour(hass, store, pc) if not (pc and est_l_ecran(hass, store, x["entity_id"], str(pc.get("id"))))]
    if st["lecteur"] and any(x["entity_id"] == st["lecteur"] and x["ma"] for x in l):
        return st["lecteur"]
    mots = _mots((pc or {}).get("name"))
    # 2.2.3 (l'auteur : « quand je reviens, la musique doit continuer ») : un lecteur Music Assistant qui joue déjà (après un
    # redémarrage de HA, la soirée est oubliée mais la musique continue) passe avant tout, la même pièce d'abord ;
    # la télé elle-même compte aussi (elle peut être l'enceinte depuis la 2.2.2).
    joue = [x for x in lecteurs_pour(hass, store, pc) if x["ma"] and x["etat"] in ("playing", "paused", "buffering")]
    if not joue and pc and est_tele(pc):
        propre = str(pc.get("name") or "").strip().lower() + SUFFIXE_VISION.lower()
        for x in l:
            if x["ma"] and x["etat"] != "unavailable" and x["nom"].strip().lower() == propre:
                return x["entity_id"]
    if joue:
        return max(joue, key=lambda x: (x["etat"] == "playing", len(mots & _mots(x["nom"])), not x["groupe"]))["entity_id"]
    dispo = [x for x in l if x["ma"] and x["etat"] != "unavailable"]
    if mots and dispo:
        meilleur = max(dispo, key=lambda x: (len(mots & _mots(x["nom"])), not x["groupe"]))
        if mots & _mots(meilleur["nom"]):
            return meilleur["entity_id"]
    return dispo[0]["entity_id"] if dispo else ""


def _pochette(attrs, repli: str = "") -> str:
    """Une empreinte de l'image : elle change avec le morceau, sans jamais exposer son adresse (jeton).
    2.2.13 : sans image du lecteur, celle du titre en cours dans la file de Music Assistant."""
    img = str(attrs.get("entity_picture") or "") or repli
    return format(zlib.crc32(img.encode()) & 0xFFFFFFFF, "x") if img else ""


# 2.4.0 (l'auteur : la télé éteinte, le mini-lecteur affichait toujours « en cours », position 0) : un lecteur de Music
# Assistant « playing » dont la position n'a plus été mise à jour depuis FIGE_S secondes est exposé arrêté.
FIGE_S = 60


def fige(hass: HomeAssistant, eid: str, s=None, maintenant_ts: float | None = None) -> bool:
    """Lecture figée : « playing » sans nouvelle position depuis FIGE_S (lecteurs de Music Assistant seulement : un
    lecteur Cast ordinaire ne remonte pas sa position pendant la lecture)."""
    s = s if s is not None else hass.states.get(eid)
    if s is None or s.state != "playing" or eid not in musique.lecteurs_ma(hass):
        return False
    pos_ts = s.attributes.get("media_position_updated_at")
    if not hasattr(pos_ts, "timestamp"):
        return False
    return (maintenant_ts if maintenant_ts is not None else time.time()) - pos_ts.timestamp() > FIGE_S


def maintenant(hass: HomeAssistant, eid: str) -> dict[str, Any]:
    s = hass.states.get(eid) if eid else None
    if s is None:
        return {"etat": "unavailable"}
    a = s.attributes
    pos_ts = a.get("media_position_updated_at")
    return {
        "etat": "idle" if fige(hass, eid, s) else s.state,
        "titre": str(a.get("media_title") or ""),
        "artiste": str(a.get("media_artist") or a.get("media_album_artist") or ""),
        "album": str(a.get("media_album_name") or ""),
        "appli": str(a.get("app_name") or ""),
        "duree": a.get("media_duration") if isinstance(a.get("media_duration"), (int, float)) else None,
        "position": a.get("media_position") if isinstance(a.get("media_position"), (int, float)) else None,
        "position_ts": pos_ts.timestamp() if hasattr(pos_ts, "timestamp") else None,
        "pochette": _pochette(a, "" if a.get("entity_picture") else musique.image_en_cours(hass, eid)),
        # 2.2.5 (l'auteur : « play / pause en retard ») : quand cet état a été vu par Home Assistant (secondes, horloge HA).
        "maj": s.last_updated.timestamp(),
    } | volume_party.etat(hass, eid)


async def file_attente(hass: HomeAssistant, eid: str) -> list[dict[str, Any]]:
    """La suite : titres de Music Assistant, chacun avec son id (pour le retirer) et « invite » s'il a été proposé
    par un invité. Sans Music Assistant : rien, sans erreur."""
    l = await musique.file(hass, eid)
    proposes = {p["uri"] for p in etat(hass).get("propositions", []) if p.get("etat") == "ajoute"}
    return [{**x, "invite": bool(x.get("uri") and x["uri"] in proposes)} for x in l]


def pour_releve(hass: HomeAssistant, store, pc: dict[str, Any]) -> dict[str, Any]:
    """Ce que le relevé dit à un appareil : la télé choisie ouvre l'écran Party ; télés et décideurs voient la tuile
    (seulement s'il y a au moins une enceinte dans Home Assistant)."""
    st = etat(hass)
    tuile = (est_tele(pc) or peut_participer(hass, store, pc)) and bool(hass.states.async_entity_ids("media_player"))
    return {"tuile": tuile, "ecran": bool(st["actif"] and st["ecran"] == str(pc.get("id"))),
            "depuis": int(st["depuis"]), "relire": RELIRE_S, "musique_joue": musique_joue(hass, pc)}


def lecteurs_de_la_tele(hass: HomeAssistant, pc: dict[str, Any] | None) -> list[str]:
    """2.2.12 (retour d'essai) : les lecteurs « de » cette télé : celui qui porte son nom (Cast / Music Assistant), son lecteur
    « · Vision », et l'enceinte de la Party quand la Party est sur cette télé."""
    if not est_tele(pc):
        return []
    nom = str((pc or {}).get("name") or "").strip().lower()
    noms = {nom, nom + SUFFIXE_VISION.lower()} if nom else set()
    sortie = [s.entity_id for s in hass.states.async_all("media_player")
              if str(s.attributes.get("friendly_name") or "").strip().lower() in noms]
    st = etat(hass)
    if st["actif"] and st["ecran"] == str((pc or {}).get("id")) and st["lecteur"] and st["lecteur"] not in sortie:
        sortie.append(st["lecteur"])
    return sortie


def musique_joue(hass: HomeAssistant, pc: dict[str, Any] | None) -> bool:
    """2.2.12 (l'auteur : « pas de veille Vision pendant une lecture Musique sur cet appareil ») : un lecteur de cette télé
    est « playing », que la lecture vienne de la télé, d'un téléphone ou de Music Assistant."""
    for eid in lecteurs_de_la_tele(hass, pc):
        s = hass.states.get(eid)
        if s is not None and s.state == "playing":
            return True
    return False


async def _service(hass: HomeAssistant, domaine: str, service: str, eid: str, donnees: dict[str, Any] | None = None) -> None:
    # Contexte sans utilisateur : jamais les droits d'un administrateur.
    await hass.services.async_call(domaine, service, dict(donnees or {}), target={"entity_id": eid},
                                   blocking=True, context=Context())


def _qui(hass: HomeAssistant, pc: dict[str, Any] | None) -> dict[str, Any]:
    """L'appareil et la personne d'une action Party, pour les événements Home Assistant."""
    pc = pc or {}
    return {"appareil": str(pc.get("name") or pc.get("id") or ""), "appareil_id": str(pc.get("id") or ""),
            "personne": str(pc.get("person") or "")}


async def lancer(hass: HomeAssistant, store, ecran: str, lecteur: str, par: str, pc: dict[str, Any] | None = None,
                 langue: str | None = None) -> str:
    if ecran not in {e["id"] for e in ecrans(store)}:
        raise textes.Erreur("party.ecran_disparu")
    if not lecteur:
        raise textes.Erreur("party.aucune_enceinte_tele")
    if hass.states.get(lecteur) is None or not lecteur.startswith("media_player."):
        raise textes.Erreur("party.enceinte_disparue")
    # 2.2.2 (retour d'essai) : la télé de Musique peut aussi être l'enceinte ; le lecteur de Music Assistant prend alors l'écran.
    st = etat(hass)
    deja = bool(st["actif"])
    if not st["actif"] or not st.get("jeton"):
        # 1.99.0 : un nouveau lien invité par Party ; l'ancien ne marche plus.
        st.update({"jeton": party_invites.nouveau_jeton(), "jeton_ts": time.time(), "refs": {}, "propositions": []})
    st.update({"actif": True, "ecran": ecran, "lecteur": lecteur, "par": par, "depuis": int(time.time())})
    await musique.sans_ajout_auto(hass, lecteur, bool(st.get("similaires")))
    if not deja:
        noms = {e["id"]: e["nom"] for e in ecrans(store)}
        hass.bus.async_fire(EVENEMENT_DEBUT, {"ecran": ecran, "ecran_nom": noms.get(ecran, ""), "lecteur": lecteur, **_qui(hass, pc)})
    _LOGGER.info("%s : %s lance le Mode Party sur %s (%s)", PRODUIT, par, ecran, lecteur)
    return textes.t("party.lance", langue)


async def arreter(hass: HomeAssistant, par: str, pc: dict[str, Any] | None = None, langue: str | None = None) -> str:
    st = etat(hass)
    if not st["actif"]:
        return textes.t("party.deja_arrete", langue)
    ecran, lecteur = st["ecran"], st["lecteur"]
    # Le lien invité est révoqué tout de suite.
    st.update({"actif": False, "ecran": "", "par": par, "depuis": int(time.time()), "jeton": None, "jeton_ts": 0,
               "refs": {}, "propositions": []})
    hass.bus.async_fire(EVENEMENT_FIN, {"ecran": ecran, "lecteur": lecteur, **_qui(hass, pc)})
    _LOGGER.info("%s : %s arrête le Mode Party", PRODUIT, par)
    return textes.t("party.arrete", langue)


def _mesurer(hass: HomeAssistant, lecteur: str, quoi: str, t0: float) -> None:
    """Le temps entre l'appui (la commande reçue) et le changement d'état du lecteur, dans le journal (15 s au plus)."""
    from homeassistant.helpers.event import async_track_state_change_event

    fini: dict[str, Any] = {}

    @callback
    def _change(event: Event) -> None:
        avant, apres = event.data.get("old_state"), event.data.get("new_state")
        if fini or apres is None:
            return
        if avant is not None and avant.state == apres.state and avant.attributes.get("media_title") == apres.attributes.get("media_title"):
            return
        fini["ok"] = True
        _LOGGER.info("%s : Musique %s — état « %s » après %d ms", PRODUIT, quoi, apres.state, (time.monotonic() - t0) * 1000)
        arreter()

    arreter = async_track_state_change_event(hass, [lecteur], _change)

    @callback
    def _delai(_now=None) -> None:
        if not fini:
            _LOGGER.info("%s : Musique %s — pas de changement d'état en 15 s", PRODUIT, quoi)
            arreter()
    hass.loop.call_later(15, _delai)


class ConfirmationRequise(textes.Erreur):
    """2.2.11 (retour d'essai) : ajouter une grosse playlist ou un album (plus de 50 titres) demande un « oui » explicite.

    La question est dite dans la langue de l'appareil (`textes.erreur`)."""

    def __init__(self, nb: int) -> None:
        super().__init__("party.confirmer_ajout", nb=nb)
        self.nb = nb


SEUIL_CONFIRMATION = 50


def _volume(v: Any) -> float:
    if isinstance(v, bool):
        raise ValueError("volume")
    n = float(v)
    if not 0.0 <= n <= 1.0:
        raise ValueError("volume")
    return round(n, 2)


# La télé (sans être décideur) peut lancer la Party sur elle-même, choisir quoi jouer et piloter la lecture.
# 2.2.1 (l'auteur, test réel) : la télé de la Party a aussi le volume, l'enceinte et la file (retirer, jouer, ensuite).
ACTIONS_TELE = {"lancer_ici", "jouer", "lecture", "suivant", "precedent", "volume", "volume_membre", "enceinte", "retirer", "jouer_element", "ensuite_element",
                "deplacer", "a_la_fin", "similaires", "enchainement"}


async def proposer(hass: HomeAssistant, t: dict[str, Any], langue: str | None = None) -> str:
    """Un titre proposé par un invité : ajouté à la file (ajout direct), ou mis en attente d'un décideur.

    La phrase rendue est dans la langue de l'invité (`langue`) ; sans langue, celle de Home Assistant."""
    st = etat(hass)
    p = {"id": secrets.token_urlsafe(8), "uri": t["uri"], "titre": t["titre"], "artiste": t["artiste"],
         "ts": int(time.time()), "etat": "attente"}
    if st.get("invites_mode") == "validation":
        st.setdefault("propositions", []).append(p)
        st["propositions"] = st["propositions"][-200:]
        return textes.t("party.merci_attente", langue)
    # Ajout direct : au plus N titres d'invités pas encore joués dans la file (pas d'inondation).
    en_file = [x for x in await file_attente(hass, st["lecteur"]) if x.get("invite")]
    if len(en_file) >= party_invites.INVITES_EN_FILE_MAX:
        raise party_invites.Complet(textes.t("party.complet", langue))
    await musique.jouer(hass, st["lecteur"], t["uri"], "track", "add")
    p["etat"] = "ajoute"
    st.setdefault("propositions", []).append(p)
    st["propositions"] = st["propositions"][-200:]
    return textes.t("party.merci_file", langue)


async def commande(hass: HomeAssistant, store, pc: dict[str, Any], corps: dict[str, Any]) -> str:
    """Une commande de décideur (ou de la télé, ACTIONS_TELE). ValueError : une phrase pour l'appli, à dire dans
    la langue de l'appareil (`textes.erreur`) ; la phrase rendue est déjà dans cette langue."""
    lg = textes.pour(hass, pc)
    action = str(corps.get("action") or "")
    par = str(pc.get("name") or pc.get("id"))
    st = etat(hass)
    from . import musique_arret
    if st.get("lecteur"):
        musique_arret.noter(st["lecteur"])  # 2.4.0 : une action sur la musique
    if action == "lancer_ici":
        if not est_tele(pc):
            raise textes.Erreur("party.tele_seule")
        if st["actif"] and st["ecran"] == str(pc.get("id")):
            return ""
        return await lancer(hass, store, str(pc.get("id")), lecteur_par_defaut(hass, store, pc), par, pc, lg)
    if action == "jouer":
        # 2.2.1 (test réel de l'auteur : « Tidal Wave » est parti sur la télé elle-même) : pendant une Party, on joue TOUJOURS
        # sur l'enceinte de la Party ; changer d'enceinte passe par « enceinte ». Hors Party, un décideur choisit.
        lecteur = st["lecteur"] if st["actif"] and st["lecteur"] else (str(corps.get("lecteur") or st["lecteur"]) if peut_piloter(store, pc) else st["lecteur"])
        # 2.2.0 (retour d'essai) : par défaut on AJOUTE à la suite, sans couper le morceau en cours ; « ensuite » le joue juste
        # après ; « remplacer » remplace la file (demandé exprès pour une playlist). Rien ne joue : on lance.
        voulu = str(corps.get("comment") or "ajouter")
        comment = {"ajouter": "add", "ensuite": "next", "remplacer": "replace"}.get(voulu, "add")
        # Démarrer seulement quand il n'y a vraiment rien : ni titre en cours, ni file. Un lecteur qui passe un instant
        # en « idle » (Cast qui recharge) ne doit jamais transformer un ajout en lecture immédiate.
        if comment in ("add", "next"):
            m = maintenant(hass, lecteur)
            rien = not m.get("titre") and str(m.get("etat")) not in ("playing", "paused", "buffering")
            if rien:
                comment = "play"
        uri = str(corps.get("uri") or "")
        type_ = str(corps.get("type") or "")
        # 2.2.11 (l'auteur : « 500 titres en fin de file que je n'ai pas choisis ») : une playlist ou un album ajouté à la suite
        # au-delà de 50 titres demande confirmation ; le nombre part dans le journal.
        if type_ in ("playlist", "album"):
            nb = await musique.nb_titres(hass, uri, type_)
            corps["_nb"] = nb
            if comment in ("add", "next") and nb and nb > SEUIL_CONFIRMATION and not corps.get("confirme"):
                raise ConfirmationRequise(nb)
        # MusiqueIndisponible est une textes.Erreur (ValueError) : elle remonte telle quelle, dite dans la langue de
        # l'appareil par la vue.
        await musique.jouer(hass, lecteur, uri, type_, comment)
        if lecteur != st["lecteur"] and peut_piloter(store, pc):
            st["lecteur"] = lecteur
        if comment == "next":
            return textes.t("party.ajoute_ensuite", lg)
        if comment == "add":
            try:
                suite = await file_attente(hass, lecteur)
                pos = max((k + 1 for k, t in enumerate(suite) if t.get("uri") == uri), default=len(suite))
                return textes.t("party.ajoute_position", lg, pos=pos) if pos else textes.t("party.ajoute", lg)
            except Exception:  # noqa: BLE001
                return textes.t("party.ajoute", lg)
        return textes.t("party.cest_parti", lg)
    if action == "retirer":
        # 2.2.6 (retour d'essai) : la soirée oubliée (redémarrage de HA) → le lecteur qui joue, comme l'écran l'affiche.
        await musique.retirer(hass, st["lecteur"] or lecteur_par_defaut(hass, store, pc), str(corps.get("element") or ""))
        return textes.t("party.retire", lg)
    if action == "enchainement":
        # 2.2.4 (retour d'essai) : « sans blanc » ou « fondu de X s », sur le lecteur de la soirée ; décideurs et télé de la soirée.
        fondu = str(corps.get("mode") or "") == "fondu"
        try:
            duree = int(corps.get("duree") or 0) if fondu else None
        except (TypeError, ValueError) as e:
            raise textes.Erreur("commun.choix_inconnu") from e
        await musique.regler_enchainement(hass, st["lecteur"], fondu, duree)
        if fondu:
            return textes.t("party.enchainement_fondu", lg, s=max(1, min(15, duree or 0)))
        return textes.t("party.enchainement_sans_blanc", lg)
    if action == "similaires":
        st["similaires"] = bool(corps.get("actif"))
        if st["actif"]:
            await musique.sans_ajout_auto(hass, st["lecteur"], st["similaires"])
        return textes.t("party.similaires_oui" if st["similaires"] else "party.similaires_non", lg)
    if action in ("deplacer", "a_la_fin"):
        if action == "a_la_fin":
            await musique.a_la_fin(hass, st["lecteur"], str(corps.get("element") or ""))
            return textes.t("party.mis_a_la_fin", lg)
        try:
            decalage = int(corps.get("decalage") or 0)
        except (TypeError, ValueError) as e:
            raise textes.Erreur("commun.choix_inconnu") from e
        await musique.deplacer(hass, st["lecteur"], str(corps.get("element") or ""), decalage)
        return textes.t("party.deplace", lg)
    if action in ("jouer_element", "ensuite_element"):
        if action == "jouer_element":
            await musique.jouer_element(hass, st["lecteur"], str(corps.get("element") or ""))
            return textes.t("party.cest_parti", lg)
        await musique.ensuite_element(hass, st["lecteur"], str(corps.get("element") or ""))
        return textes.t("party.joue_apres", lg)
    if action == "nouveau_lien":
        if not st["actif"]:
            raise textes.Erreur("party.est_arrete")
        st.update({"jeton": party_invites.nouveau_jeton(), "jeton_ts": time.time(), "refs": {}})
        return textes.t("party.nouveau_code", lg)
    if action == "invites":
        mode = str(corps.get("mode") or "")
        if mode not in ("direct", "validation"):
            raise textes.Erreur("commun.choix_inconnu")
        st["invites_mode"] = mode
        return textes.t("party.invites_direct" if mode == "direct" else "party.invites_validation", lg)
    if action in ("accepter", "refuser"):
        p = next((x for x in st.get("propositions", []) if x["id"] == str(corps.get("proposition") or "") and x["etat"] == "attente"), None)
        if p is None:
            raise textes.Erreur("party.deja_traitee")
        if action == "accepter":
            await musique.jouer(hass, st["lecteur"], p["uri"], "track", "add")
            p["etat"] = "ajoute"
            return textes.t("party.ajoute", lg)
        p["etat"] = "refuse"
        return textes.t("party.ecartee", lg)
    if action == "lancer":
        return await lancer(hass, store, str(corps.get("ecran") or ""), str(corps.get("lecteur") or st["lecteur"]), par, pc,
                            lg)
    if action == "arreter":
        return await arreter(hass, par, pc, lg)
    lecteur = str(corps.get("lecteur") or st["lecteur"]) if peut_piloter(store, pc) else st["lecteur"]
    if hass.states.get(lecteur) is None or not lecteur.startswith("media_player."):
        raise textes.Erreur("party.choisir_enceinte")
    if action == "enceinte":
        # 2.2.1 (retour d'essai) : la télé de la Party choisit aussi ; jamais elle-même ; la file suit la musique.
        if est_tele(pc) and not peut_piloter(store, pc):
            lecteur = str(corps.get("lecteur") or st["lecteur"])
            if hass.states.get(lecteur) is None or not lecteur.startswith("media_player."):
                raise textes.Erreur("party.choisir_enceinte")
        cible = hass.states.get(lecteur)
        if cible is not None and cible.state == "unavailable":
            nom = str(cible.attributes.get("friendly_name") or "")
            raise textes.Erreur("party.enceinte_hors_ligne_nom", nom=nom) if nom else textes.Erreur("party.enceinte_hors_ligne")
        if st["actif"] and lecteur not in musique.lecteurs_ma(hass):
            raise textes.Erreur("party.enceinte_sans_ma")
        coches = (getattr(store, "musique", None) or {}).get("enceintes")
        if coches is not None and lecteur not in coches:
            raise textes.Erreur("party.enceinte_pas_proposee")
        ancien = st["lecteur"]
        st["lecteur"] = lecteur
        if st["actif"]:
            await musique.sans_ajout_auto(hass, lecteur, bool(st.get("similaires")))
        if st["actif"] and ancien and ancien != lecteur and await musique.transferer(hass, ancien, lecteur):
            await musique.sans_ajout_auto(hass, lecteur, bool(st.get("similaires")))
            return textes.t("party.enceinte_continue", lg)
        return textes.t("party.enceinte_choisie", lg)
    if action in ACTIONS_LECTURE:
        # 2.2.5 (retour d'essai) : l'appli dit ce qu'elle veut (« pause » ou « lecture ») : jamais une bascule à l'aveugle sur un
        # état périmé. Et on mesure le délai jusqu'au vrai changement d'état (journal), pour savoir d'où vient le retard.
        voulu = str(corps.get("voulu") or "")
        service = {"pause": "media_pause", "lecture": "media_play"}.get(voulu) if action == "lecture" else None
        t0 = time.monotonic()
        _mesurer(hass, lecteur, action + (" → " + voulu if voulu else ""), t0)
        await _service(hass, "media_player", service or ACTIONS_LECTURE[action], lecteur)
        _LOGGER.info("%s : Musique %s — appel du service : %d ms", PRODUIT, action, (time.monotonic() - t0) * 1000)
        return ""
    if action in ("volume", "volume_membre"):
        try:
            v = _volume(corps.get("volume"))
        except (TypeError, ValueError) as e:
            raise textes.Erreur("party.volume") from e
        # Règles de l'auteur (volume_party, seule règle) : un lecteur « · Vision » reste à 100 % sans réglage (la télé gère) ;
        # un groupe se règle d'un coup (volume du groupe de Music Assistant), une enceinte du groupe en option.
        if action == "volume_membre":
            await volume_party.regler_membre(hass, lecteur, str(corps.get("membre") or ""), v)
            return ""
        if await volume_party.regler(hass, lecteur, v):
            return ""
        await _service(hass, "media_player", "volume_set", lecteur, {"volume_level": v})
        return ""
    raise textes.Erreur("party.commande_inconnue")


async def pochette(hass: HomeAssistant, eid: str) -> tuple[bytes, str] | None:
    """L'image de ce qui passe sur ce lecteur, lue par Home Assistant lui-même (sans exposer son jeton)."""
    comp = hass.data.get("media_player")
    ent = comp.get_entity(eid) if comp is not None and hasattr(comp, "get_entity") else None
    img, type_ = None, None
    if ent is not None:
        try:
            img, type_ = await ent.async_get_media_image()
        except Exception as e:  # noqa: BLE001 - pas d'image : on essaie la file de Music Assistant
            _LOGGER.debug("Pochette de %s illisible : %s", eid, e)
    if img:
        return img, type_ or "image/jpeg"
    # 2.2.13 (l'auteur : « pochette vide » sur le titre en cours) : le lecteur « · Vision » ne donne pas d'image ; celle du
    # titre en cours dans la file de Music Assistant, lue par Home Assistant (jamais son adresse côté appareil).
    url = musique.image_en_cours(hass, eid)
    if not url:
        return None
    from . import party_invites
    return await party_invites.telecharger_image(hass, url)


# ----------------------------------------------------------------------------- Les portes


class _Porte(HomeAssistantView):
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def _ouvrir(self, request: web.Request):
        try:
            corps = await request.json()
        except ValueError:
            return None, None, None, self.json({"ok": False, "error": "json"}, status_code=400)
        if not isinstance(corps, dict):
            return None, None, None, self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord = entrees[0] if entrees else None
        if coord is None:
            return None, None, None, self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return None, None, None, self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.noter_langue(pc, corps)
        return coord, pc, corps, None


class PcParentalPartyEtatView(_Porte):
    url = URL + "/etat"
    name = "api:pc_parental:party_etat"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, _corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        store = coord.store
        piloter = peut_piloter(store, pc)
        participer = peut_participer(self.hass, store, pc)
        st = etat(self.hass)
        ici = bool(st["actif"] and st["ecran"] == str(pc.get("id")))
        ma = musique.lecteurs_ma(self.hass)
        noms = {e["id"]: e["nom"] for e in ecrans(store)}
        # 2.2.3 : soirée pas lancée (ou oubliée par un redémarrage) → on montre ce qui joue vraiment.
        vu = st["lecteur"] if st["actif"] or st["lecteur"] else lecteur_par_defaut(self.hass, store, pc)
        lect = self.hass.states.get(vu) if vu else None
        if st["actif"] and st["lecteur"] and musique.ajout_auto_actif(self.hass, st["lecteur"]) and not st.get("similaires"):
            _LOGGER.warning("%s : l'ajout automatique de Music Assistant s'était rallumé sur %s : coupé", PRODUIT, st["lecteur"])
            await musique.sans_ajout_auto(self.hass, st["lecteur"], False)
        sortie: dict[str, Any] = {
            "ok": True,
            "actif": st["actif"],
            "ecran": st["ecran"],
            "ecran_nom": noms.get(st["ecran"], ""),
            "ici": bool(st["actif"] and st["ecran"] == str(pc.get("id"))),
            "lecteur": vu,
            "lecteur_nom": str(lect.attributes.get("friendly_name") or vu) if lect else "",
            "maintenant": maintenant(self.hass, vu),
            "file": (la_file := avec_refs(self.hass, await file_attente(self.hass, st["lecteur"])) if st["actif"] else []),
            # 2.2.1 (retour d'essai) : file vide et musique arrêtée → le numéro du clin d'œil (le même sur tous les écrans), sinon -1.
            "fin_clin": clins.indice(st, lect.state if lect else "unavailable", bool(la_file)),
            # 2.2.1 : le vrai nombre de titres à suivre (la liste affichée s'arrête à 30).
            "file_total": await musique.total_file(self.hass, st["lecteur"]) if st["actif"] else 0,
            "similaires": bool(st.get("similaires")),
            "ajout_auto": musique.ajout_auto_actif(self.hass, st["lecteur"]) if st["actif"] else None,
            "enchainement": await musique.enchainement(self.hass, st["lecteur"]) if st["actif"] else None,
            # 1.99.0 : le QR code des invités (sur la télé de la Party, et chez les décideurs).
            "qr": party_invites.adresse(self.hass, st["jeton"]) if st["actif"] and st.get("jeton") and (ici or piloter) else "",
            "piloter": piloter,
            "relire": RELIRE_S,
            # 1.99.0 : choisir la musique (Music Assistant) ; la télé peut lancer la Party sur elle-même.
            "ma": musique.disponible(self.hass),
            "lecteur_ma": st["lecteur"] in ma,
            "choisir": piloter or est_tele(pc) or participer,
            # 2.1.0 : pendant une Party, chacun pilote la musique ; un appareil fermé voit pourquoi il ne peut pas.
            "participer": participer,
            "ferme": "" if participer or piloter or not st["actif"] else textes.t("party.ferme_participer", textes.pour(self.hass, pc)),
            "invites_mode": st.get("invites_mode", "direct"),
        }
        if piloter or ici:
            # 2.2.1 : la télé de la Party choisit aussi l'enceinte (jamais elle-même).
            sortie["lecteurs"] = []
            for x in lecteurs_pour(self.hass, store, pc):
                sortie["lecteurs"].append(x)
            sortie["aide_lecteurs"] = textes.t(AIDE_LECTEURS, textes.pour(self.hass, pc))
        if piloter:
            sortie["ecrans"] = ecrans(store)
            sortie["propositions"] = [{k: p[k] for k in ("id", "titre", "artiste", "ts")}
                                      for p in st.get("propositions", []) if p.get("etat") == "attente"]
        return self.json(sortie)


def _journal(hass: HomeAssistant, store, pc: dict[str, Any], corps: dict[str, Any], resultat: str,
             detail: str | None = None) -> None:
    """Une ligne du journal Musique pour cette commande (sans le volume ni la lecture/pause à chaque seconde).

    `resultat` est un code (journal_musique.RESULTATS : « ok », « refuse », « confirmer »…), dit dans la langue du
    lecteur du journal ; `detail` : la raison d'un refus, déjà dans la langue de Home Assistant."""
    action = str(corps.get("action") or "")
    qui = _qui(hass, pc)
    try:
        profil = roles.nom(store, pc)
    except Exception:  # noqa: BLE001
        profil = ""
    journal_musique.noter(
        hass, appareil=qui.get("appareil"), appareil_id=qui.get("appareil_id"), personne=qui.get("personne"),
        profil=profil, action=action, uri=str(corps.get("uri") or "")[:300] or None, type=str(corps.get("type") or "") or None,
        titre=str(corps.get("titre") or "")[:120] or None, mode=str(corps.get("comment") or "") or None,
        nb=corps.get("_nb"), element=str(corps.get("element") or "") or None, paquet=str(corps.get("paquet") or "")[:100] or None,
        lecteur=etat(hass).get("lecteur") or None, resultat=resultat, detail=detail)


class PcParentalPartyCommandeView(_Porte):
    url = URL + "/commande"
    name = "api:pc_parental:party_commande"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        lg = textes.pour(self.hass, pc)
        tele_ok = est_tele(pc) and str(corps.get("action") or "") in ACTIONS_TELE and (
            corps.get("action") == "lancer_ici" or (etat(self.hass)["actif"] and etat(self.hass)["ecran"] == str(pc.get("id"))))
        tous_ok = str(corps.get("action") or "") in ACTIONS_TOUS and peut_participer(self.hass, coord.store, pc)
        if str(corps.get("action") or "") == "premier_plan_perdu":
            _journal(self.hass, coord.store, pc, corps, "note")
            return self.json({"ok": True, "retour": ""})
        if not peut_piloter(coord.store, pc) and not tele_ok and not tous_ok:
            ferme = etat(self.hass)["actif"] and ferme_maintenant(coord.store, pc) and str(corps.get("action") or "") in ACTIONS_TOUS
            return self.json({"ok": False, "error": "party", "retour": textes.t("party.ferme_attendra", lg)
                              if ferme else textes.t("commun.reserve_decideurs", lg)}, status_code=403)
        if widgets._trop_vite("party:" + str(pc.get("id"))):
            return self.json({"ok": False, "error": "trop_vite", "retour": textes.t("commun.reessaie_instant", lg)},
                             status_code=429)
        try:
            retour = await commande(self.hass, coord.store, pc, corps)
            from . import musique_arret
            if etat(self.hass).get("lecteur"):
                musique_arret.noter(etat(self.hass)["lecteur"])  # 2.4.0 : y compris le lancement de la soirée
        except ConfirmationRequise as e:
            _journal(self.hass, coord.store, pc, corps, "confirmer")
            return self.json({"ok": False, "error": "confirmer", "confirmer": True, "nb": e.nb, "retour": textes.erreur(e, lg)},
                             status_code=409)
        except ValueError as e:
            _journal(self.hass, coord.store, pc, corps, "refuse", textes.erreur(e, textes.ha(self.hass)))
            return self.json({"ok": False, "error": "commande", "retour": textes.erreur(e, lg)}, status_code=400)
        except Exception as e:  # noqa: BLE001 - l'appli reçoit une phrase, le journal le détail
            _LOGGER.info("%s : commande Party %s échouée : %s", PRODUIT, corps.get("action"), e)
            _journal(self.hass, coord.store, pc, corps, "echec_enceinte")
            return self.json({"ok": False, "error": "service",
                              "retour": textes.t("party.enceinte_muette", lg)}, status_code=502)
        # Le volume et la lecture/pause ne remplissent pas le journal (trop fréquents) ; tout le reste y va.
        if str(corps.get("action") or "") not in ("volume",):
            _journal(self.hass, coord.store, pc, corps, "ok")
        return self.json({"ok": True, "retour": retour})


class PcParentalPartyPochetteView(_Porte):
    url = URL + "/pochette"
    name = "api:pc_parental:party_pochette"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        store = coord.store
        eid = str(corps.get("entite") or "")
        st = etat(self.hass)
        party_ok = eid == st["lecteur"] and (peut_participer(self.hass, store, pc) or (st["actif"] and st["ecran"] == str(pc.get("id"))))
        # 2.2.3 : soirée oubliée (redémarrage) mais musique qui joue : la pochette du mini-lecteur de l'accueil.
        if not party_ok and not st["actif"] and (est_tele(pc) or peut_piloter(store, pc)):
            party_ok = eid == lecteur_par_defaut(self.hass, store, pc)
        widget_ok = widgets.peut_voir(store, pc) and eid in widgets.visibles(store, pc)
        if not eid.startswith("media_player.") or not (party_ok or widget_ok):
            return self.json({"ok": False, "error": "entite"}, status_code=403)
        image = await pochette(self.hass, eid)
        if image is None:
            return web.Response(status=204)
        return web.Response(body=image[0], content_type=image[1].split(";")[0],
                            headers={"Cache-Control": "private, max-age=300"})


class PcParentalPartyMusiqueView(_Porte):
    """1.99.0 : choisir quoi jouer. Décideur, ou télé (pour la Party qu'elle affiche ou va lancer)."""

    url = URL + "/musique"
    name = "api:pc_parental:party_musique"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        lg = textes.pour(self.hass, pc)
        if not peut_participer(self.hass, coord.store, pc) and not est_tele(pc):
            return self.json({"ok": False, "error": "party", "retour": textes.t("commun.reserve_decideurs", lg)}, status_code=403)
        if widgets._trop_vite("musique:" + str(pc.get("id"))):
            return self.json({"ok": False, "error": "trop_vite", "retour": textes.t("commun.reessaie_instant", lg)},
                             status_code=429)
        quoi = str(corps.get("quoi") or "")
        try:
            if quoi == "chercher":
                texte = str(corps.get("texte") or "")
                # 2.2.8 (retour d'essai) : filtres type (Titres, Albums, Playlists, Artistes, Radios) et plateforme, cumulables.
                types, doms = musique.choix_filtres(corps)
                res = musique.filtrer(await musique.chercher(self.hass, texte, tuple(types) or musique.TYPES_CHOIX), types, doms)
                for liste in (res.values() if isinstance(res, dict) else []):
                    avec_refs(self.hass, liste)
                return self.json({"ok": True, "resultats": res, "plateformes": musique.plateformes(self.hass, lg),
                                  **musique.bilan(self.hass, texte, res, lg)})
            return self.json({"ok": True, "items": avec_refs(self.hass, await musique.bibliotheque(self.hass, quoi))})
        except musique.MusiqueIndisponible as e:
            return self.json({"ok": False, "error": "musique", "retour": textes.erreur(e, lg)}, status_code=503)


class PcParentalPartyImageView(_Porte):
    """2.3.7 : la pochette d'un titre ou d'un résultat, lue par HA (référence donnée par HA ; jamais une adresse venue
    de l'appareil : un champ « url » ou « image » du corps est ignoré)."""

    url = URL + "/image"
    name = "api:pc_parental:party_image"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        if not peut_participer(self.hass, coord.store, pc) and not est_tele(pc):
            return self.json({"ok": False, "error": "party"}, status_code=403)
        url = url_de_ref(self.hass, str(corps.get("ref") or ""))
        if not url:
            return web.Response(status=404)
        image = await party_invites.telecharger_image(self.hass, url)
        if image is None:
            return web.Response(status=204)
        return web.Response(body=image[0], content_type=image[1].split(";")[0],
                            headers={"Cache-Control": "private, max-age=86400"})


class PcParentalPartyFluxView(_Porte):
    """2.2.5 (l'auteur : réactivité) : un flux (text/event-stream) qui prévient l'appli dès que le lecteur de la soirée change
    (lecture, pause, titre, volume) — au lieu d'attendre la relecture suivante. Mêmes droits que /etat ; le flux se
    ferme si l'appareil est retiré ou son secret changé."""

    url = URL + "/flux"
    name = "api:pc_parental:party_flux"
    battement = 20

    async def post(self, request: web.Request) -> web.StreamResponse:
        coord, pc, _corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        hass = self.hass
        pc_id, secret = str(pc.get("id")), str(pc.get("secret") or "")
        file: asyncio.Queue = asyncio.Queue(maxsize=20)

        @callback
        def _sur_etat(event: Event) -> None:
            st = etat(hass)
            eid = event.data.get("entity_id")
            if eid != st.get("lecteur") and eid != lecteur_par_defaut(hass, coord.store, pc):
                return
            nouveau = event.data.get("new_state")
            try:
                file.put_nowait({"lecteur": eid, "etat": nouveau.state if nouveau else None,
                                 "maj": nouveau.last_updated.timestamp() if nouveau else time.time()})
            except asyncio.QueueFull:
                pass

        @callback
        def _arret(_event: Event) -> None:
            try:
                file.put_nowait(None)
            except asyncio.QueueFull:
                pass

        reponse = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-store", "X-Accel-Buffering": "no"})
        desabonner = hass.bus.async_listen(EVENT_STATE_CHANGED, _sur_etat)
        desabonner_arret = hass.bus.async_listen_once(EVENT_HOMEASSISTANT_STOP, _arret)
        try:
            await reponse.prepare(request)
            await reponse.write(b"retry: 3000\n\n")
            while True:
                try:
                    message = await asyncio.wait_for(file.get(), timeout=self.battement)
                except asyncio.TimeoutError:
                    message = ""
                if message is None:
                    break
                courant = coord.store.pcs.get(pc_id)
                if courant is None or str(courant.get("secret") or "") != secret:
                    await reponse.write(b"event: fin\ndata: {}\n\n")
                    break
                if message == "":
                    await reponse.write(b": battement\n\n")
                    continue
                await reponse.write(("data: " + json.dumps(message) + "\n\n").encode())
        except (ConnectionResetError, RuntimeError):
            pass
        finally:
            desabonner()
            try:
                desabonner_arret()
            except ValueError:
                pass
        return reponse


def enregistrer_vues(hass: HomeAssistant) -> None:  # noqa: D103
    hass.async_create_task(journal_musique.charger(hass))
    for vue in (PcParentalPartyEtatView, PcParentalPartyCommandeView, PcParentalPartyPochetteView, PcParentalPartyMusiqueView,
                PcParentalPartyImageView, PcParentalPartyFluxView):
        hass.http.register_view(vue(hass))
    party_invites.enregistrer_vues(hass, etat, proposer)
