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
                                       "similaires": False,
                                       # 17-10 (l'auteur, point 8) : le mode soirée (invités par QR code), ouvert ou fermé.
                                       "invites_ouverts": True})


def peut_piloter(store, pc: dict[str, Any] | None) -> bool:
    """Lancer, arrêter, piloter : les décideurs (fonction « gerer »)."""
    return roles.a(store, pc, "gerer")


# 2.1.0 : la Party est ouverte à tous. Pendant une Party, tout appareil de la maison choisit la
# musique, met en pause, passe, règle le volume et retire un titre. Restent aux décideurs : lancer, arrêter, choisir
# l'enceinte, les invités et l'interrupteur Mode Fête. Garde-fou : un appareil fermé par ses règles (la nuit, une
# plage, une fermeture) ne participe pas — une Party ne rouvre pas un téléphone d'enfant à minuit.
ACTIONS_TOUS = {"jouer", "retirer", "volume", "volume_membre", "lecture", "suivant", "precedent", "ensuite_element", "position"}


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
    # 2.5.7 (l'auteur, 1.93.0 en production, 16/10 : « je choisis Télé du bureau, ça reste vide ») : une télé peut avoir
    # DEUX lecteurs « · Vision » du même nom dans Music Assistant (l'ancien Classique, hors ligne, et le Sendspin). Avant,
    # le dernier vu gagnait : le Cast coché était remplacé par le jumeau hors ligne, que « masquer hors ligne » retirait.
    # On garde celui qui marche, et la case du Cast vaut pour tous ses jumeaux.
    jumeaux: dict[str, dict[str, Any]] = {}
    tous_jumeaux: dict[str, set[str]] = {}
    for e in sortie:
        if e["ma"] and e["nom"].endswith(SUFFIXE_VISION):
            cle = e["nom"][: -len(SUFFIXE_VISION)].strip().lower()
            tous_jumeaux.setdefault(cle, set()).add(e["entity_id"])
            if cle not in jumeaux or (e["dispo"] and not jumeaux[cle]["dispo"]):
                jumeaux[cle] = e
    # Deux lecteurs Music Assistant du même nom, l'un disponible : le fantôme hors ligne n'est pas montré.
    noms_dispo = {e["nom"].strip().lower() for e in sortie if e["ma"] and e["dispo"]}
    sortie = [e for e in sortie if not (e["ma"] and not e["dispo"] and e["nom"].strip().lower() in noms_dispo)]
    # Le Cast d'une télé → le nom de la télé, qui mène à ses lecteurs « · Vision ».
    casts = {e["entity_id"]: e["nom"].strip().lower() for e in sortie
             if e["ma"] and not e["nom"].endswith(SUFFIXE_VISION) and e["nom"].strip().lower() in jumeaux}
    remplaces = {c: jumeaux[nom]["entity_id"] for c, nom in casts.items()}
    sortie = [e for e in sortie if e["entity_id"] not in remplaces]
    reglage = getattr(store, "musique", None) if store is not None else None
    if reglage is not None:
        coches = reglage.get("enceintes")
        if coches is not None:
            # Le lecteur Vision d'une télé hérite de la case de son lecteur Cast (ex. en essai : « Téléviseur du salon »).
            coches = set(coches)
            for c, nom in casts.items():
                if c in coches:
                    coches |= tous_jumeaux[nom]
        avant = sortie
        # Sans Music Assistant du tout (autres installations), les media_player restent proposés comme avant.
        if any(e["ma"] for e in sortie):
            sortie = [e for e in sortie if e["ma"] and (coches is None or e["entity_id"] in coches)]
        if reglage.get("masquer_hors_ligne"):
            sortie = [e for e in sortie if e["dispo"]]
        # 2.5.7 : des cases PÉRIMÉES (entités qui n'existent plus, renommées par Music Assistant, comme
        # « televiseur_du_salon_4 » en essai) ne laissent jamais une liste vide : tous les lecteurs disponibles. Un choix
        # voulu (rien de coché, ou seulement des enceintes éteintes) reste respecté : l'appareil dit où cocher.
        perimees = coches is not None and any(hass.states.get(c) is None for c in reglage.get("enceintes") or [])
        if perimees and not any(e["dispo"] for e in sortie):
            sortie = [e for e in avant if e["dispo"] and (e["ma"] or not any(x["ma"] for x in avant))]
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
    """2.2.13 (l'auteur : « je clique sur Disney classique, il ne se passe rien » sur la télé du bureau) : les enceintes
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


# 2.5.11.1 : le temps laissé au lecteur « · Vision » d'une télé pour revenir en ligne au lancement d'une soirée.
ATTENTE_LECTEUR_PROPRE_S = 8.0

EN_COURS = ("playing", "paused", "buffering")


def lecteur_propre(hass: HomeAssistant, store, pc: dict[str, Any] | None) -> tuple[str, str]:
    """Le lecteur « · Vision » de cette télé dans Music Assistant (Vision y joue le son lui-même) : (entity_id, état),
    ou ("", "") si la télé n'en a pas. Avec deux jumeaux du même nom (2.5.7), celui qui est en ligne."""
    if not (pc and est_tele(pc)):
        return "", ""
    propre = str(pc.get("name") or "").strip().lower() + SUFFIXE_VISION.lower()
    candidats = [x for x in lecteurs(hass, None) if x["ma"] and x["nom"].strip().lower() == propre]
    if not candidats:
        return "", ""
    x = max(candidats, key=lambda e: e["etat"] != "unavailable")
    return x["entity_id"], str(x["etat"])


async def attendre_lecteur_propre(hass: HomeAssistant, store, pc: dict[str, Any] | None) -> str:
    """2.5.11.1 (l'auteur, 18/10 : « remplacer » puis ▶ sur la TV du salon, rien ne joue). À 01:23:17 et à 10:35:24, au
    moment exact du « lancer_ici », le lecteur « Téléviseur du salon · Vision » était hors ligne pour quelques secondes
    (l'appli venait de se reconnecter à Music Assistant : « Duplicate connection detected ») ; il est revenu 0 à 6 s
    plus tard. La soirée partait alors sur une autre enceinte, sans le dire, et tout ce que l'auteur faisait ensuite visait
    cette enceinte-là. On laisse au lecteur propre jusqu'à ATTENTE_LECTEUR_PROPRE_S pour revenir.

    Rend son entity_id s'il est en ligne (tout de suite ou après l'attente), sinon ""."""
    eid, et = lecteur_propre(hass, store, pc)
    if not eid:
        return ""
    fin = hass.loop.time() + ATTENTE_LECTEUR_PROPRE_S
    while et == "unavailable" and hass.loop.time() < fin:
        await asyncio.sleep(0.25)
        eid, et = lecteur_propre(hass, store, pc)
    if et == "unavailable":
        _LOGGER.warning("%s : le lecteur %s de la télé %s est toujours hors ligne après %.0f s", PRODUIT, eid,
                        (pc or {}).get("name"), ATTENTE_LECTEUR_PROPRE_S)
        return ""
    return eid


def lecteur_par_defaut(hass: HomeAssistant, store, pc: dict[str, Any] | None = None) -> str:
    """L'enceinte d'une Party lancée sans choix : la dernière, sinon celle de Music Assistant de la même pièce
    (« Salon » pour « Téléviseur du salon »), jamais la télé elle-même.

    2.5.11.1 : depuis une télé dont le lecteur « · Vision » est en ligne, ce lecteur passe avant la dernière enceinte
    de la soirée, sauf si elle joue encore (2.2.3 : « quand je reviens, la musique doit continuer »)."""
    st = etat(hass)
    l = [x for x in lecteurs_pour(hass, store, pc) if not (pc and est_l_ecran(hass, store, x["entity_id"], str(pc.get("id"))))]
    if st["lecteur"] and any(x["entity_id"] == st["lecteur"] and x["ma"] for x in l):
        propre, etat_propre = lecteur_propre(hass, store, pc)
        dernier = hass.states.get(st["lecteur"])
        if not (propre and propre != st["lecteur"] and etat_propre != "unavailable"
                and (dernier is None or dernier.state not in EN_COURS)):
            return st["lecteur"]
        return propre
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
    ajoutes = [p for p in etat(hass).get("propositions", []) if p.get("etat") == "ajoute"]
    proposes = {p["uri"] for p in ajoutes}
    noms = {_cle_titre(p.get("titre"), p.get("artiste")) for p in ajoutes} - {None}

    def invite(x: dict[str, Any]) -> bool:
        # 2.5.9 (point 16) : Music Assistant range dans la file l'élément de la bibliothèque quand le titre y est déjà
        # (library://…), pas celui du fournisseur choisi par l'invité : l'adresse seule ne le retrouvait pas.
        return bool(x.get("uri") and x["uri"] in proposes) or _cle_titre(x.get("titre"), x.get("artiste")) in noms

    if proposes:
        # 2.5.9 (l'auteur, point 16 : « les titres de l'invité n'apparaissent jamais dans la file, ni à la fin ») : en fin de
        # file, derrière une playlist de 84 titres, le titre d'un invité était hors des 30 titres lus, donc jamais montré
        # (et pas compté par la limite d'invités). Les titres d'invités plus loin s'ajoutent à la fin de la liste.
        vus = {x.get("id") for x in l}
        l = l + [x for x in await musique.file(hass, eid, depuis_fin=FIN_DE_FILE_N)
                 if x.get("id") not in vus and invite(x)]
    # 2.4.6 (l'auteur : « il faudrait les précharger ») : la pochette en cours et celles des titres suivants sont lues par
    # Home Assistant dès que la file est connue ; quand l'appareil les demande, elles sont déjà là.
    prechauffer(hass, [musique.image_en_cours(hass, eid)] + [str(x.get("image") or "") for x in l[:PRECHAUFFAGE_N]])
    return [{**x, "invite": invite(x)} for x in l]


def _cle_titre(titre: Any, artiste: Any) -> tuple[str, str] | None:
    """Titre et artiste ramenés à une clé de comparaison (casse et espaces ignorés) ; None sans titre."""
    t = " ".join(str(titre or "").split()).casefold()
    return (t, " ".join(str(artiste or "").split()).casefold()) if t else None


PRECHAUFFAGE_N = 6
# 2.5.9 : les derniers titres de la file relus pour y retrouver ceux des invités (au-delà des 30 titres à suivre).
FIN_DE_FILE_N = 40
_EN_VOL: set[str] = set()


def prechauffer(hass: HomeAssistant, urls: list[str]) -> int:
    """Lit en arrière-plan les pochettes pas encore gardées (cache de party_invites). Rend le nombre lancé."""
    from . import party_invites

    n = 0
    for u in dict.fromkeys(u for u in urls if u):
        if not u.startswith(("http://", "https://")) or u in party_invites._images or u in _EN_VOL:
            continue
        _EN_VOL.add(u)

        async def _un(url: str = u) -> None:
            try:
                await party_invites.telecharger_image(hass, url)
            finally:
                _EN_VOL.discard(url)

        hass.async_create_background_task(_un(), name="vision_pochette")
        n += 1
    return n


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
        # 1.99.0 : un nouveau lien invité par Party ; l'ancien ne marche plus. 17-10 (l'auteur, point 8) : seulement si le mode
        # soirée est ouvert ; fermé, il n'y a pas de lien du tout.
        ouverts = st.get("invites_ouverts", True)
        st.update({"jeton": party_invites.nouveau_jeton() if ouverts else None, "jeton_ts": time.time() if ouverts else 0,
                   "refs": {}, "propositions": []})
    st.update({"actif": True, "ecran": ecran, "lecteur": lecteur, "par": par, "depuis": int(time.time()),
               # 2.5.9 (l'auteur, point 16) : le mode des invités choisi la dernière fois, même après un redémarrage de HA.
               "invites_mode": mode_invites_garde(store)})
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


# 2.4.6 (l'auteur : « choisir le moment de la musique ») : un saut dans le titre en cours. Music Assistant ne publie aucune
# position pour un lecteur « · Vision » en mode Classique : la télé recale son horloge sur le dernier saut, donné par
# l'état de la Party pendant SAUT_S secondes (« il_y_a » : l'âge du saut, pour ne dépendre d'aucune horloge commune).
SAUT_S = 30


def _position(v: Any, duree: Any) -> float:
    """La position demandée (s), bornée au titre ; ValueError si elle n'a pas de sens."""
    p = float(v)
    if p != p or p < 0:  # NaN ou négatif
        raise ValueError("position")
    if isinstance(duree, (int, float)) and duree > 0:
        p = min(p, max(0.0, float(duree) - 1.0))
    return round(p, 1)


def saut_recent(hass: HomeAssistant, lecteur: str, maintenant_ts: float | None = None) -> dict[str, Any] | None:
    """Le dernier saut demandé sur ce lecteur, s'il a moins de SAUT_S secondes : position et âge (s)."""
    s = etat(hass).get("saut")
    if not s or s.get("lecteur") != lecteur:
        return None
    age = (maintenant_ts if maintenant_ts is not None else time.time()) - float(s.get("ts") or 0)
    if age < 0 or age > SAUT_S:
        return None
    return {"position": s["position"], "il_y_a": round(age, 2)}


# La télé (sans être décideur) peut lancer la Party sur elle-même, choisir quoi jouer et piloter la lecture.
# 2.2.1 (l'auteur, test réel) : la télé de la Party a aussi le volume, l'enceinte et la file (retirer, jouer, ensuite).
# 17-10 (l'auteur, point 18b) : et « vider la file » (le titre en cours continue), depuis le menu d'un titre de la file.
# 17-10 (l'auteur, point 8) : la télé de la soirée ouvre et ferme aussi le mode soirée (invités), depuis son lecteur.
ACTIONS_TELE = {"lancer_ici", "jouer", "lecture", "suivant", "precedent", "position", "volume", "volume_membre", "enceinte", "retirer", "vider", "invites_ouverts", "jouer_element", "ensuite_element",
                "deplacer", "a_la_fin", "similaires", "enchainement"}


MODES_INVITES = ("validation", "ensuite", "direct")


def mode_invites_garde(store) -> str:
    """Le mode des invités rangé dans les réglages (2.5.9), « direct » (en fin de file) par défaut."""
    m = (getattr(store, "musique", None) or {}).get("invites_mode")
    return m if m in MODES_INVITES else "direct"


async def mes_titres(hass: HomeAssistant, appareil: str, langue: str | None = None) -> list[dict[str, str]]:
    """2.5.8 (l'auteur : « un invité qui ajoute un titre ne le voit apparaître nulle part ») : les titres proposés par cet
    appareil d'invité (les 10 derniers), chacun avec son état dit en clair dans sa langue."""
    if not appareil:
        return []
    st = etat(hass)
    miens = [p for p in st.get("propositions", []) if p.get("appareil") == appareil][-10:]
    if not miens:
        return []
    suite = [x.get("uri") for x in await file_attente(hass, st.get("lecteur") or "")] if st.get("lecteur") else []
    lect = hass.states.get(st.get("lecteur") or "") if st.get("lecteur") else None
    joue = lect is not None and lect.state in ("playing", "paused", "buffering")
    en_cours = str(lect.attributes.get("media_content_id") or "") if joue else ""
    titre_en_cours = str(lect.attributes.get("media_title") or "").strip().lower() if joue else ""
    sortie = []
    for p in reversed(miens):
        e = p.get("etat")
        if e == "attente":
            dit = textes.t("inv.mes_attente", langue)
        elif e == "refuse":
            dit = textes.t("inv.mes_refuse", langue)
        elif (en_cours and p.get("uri") == en_cours) or (titre_en_cours and str(p.get("titre") or "").strip().lower() == titre_en_cours):
            dit = textes.t("inv.mes_en_cours", langue)
        elif p.get("uri") in suite:
            rang = suite.index(p["uri"]) + 1
            dit = textes.t("inv.mes_ensuite", langue) if rang == 1 else textes.t("inv.mes_position", langue, pos=rang)
        else:
            dit = textes.t("inv.mes_joue", langue)
        sortie.append({"titre": p.get("titre", ""), "artiste": p.get("artiste", ""), "etat": dit, "attente": e == "attente"})
    return sortie


async def proposer(hass: HomeAssistant, t: dict[str, Any], langue: str | None = None, appareil: str = "") -> str:
    """Un titre proposé par un invité : ajouté à la file (ajout direct), ou mis en attente d'un décideur.

    La phrase rendue est dans la langue de l'invité (`langue`) ; sans langue, celle de Home Assistant."""
    st = etat(hass)
    # 2.5.8 (retour d'essai) : l'appareil de l'invité (tiré par sa page) : il retrouve ses titres et leur état (« Vos titres »).
    p = {"id": secrets.token_urlsafe(8), "uri": t["uri"], "titre": t["titre"], "artiste": t["artiste"],
         "ts": int(time.time()), "etat": "attente", "appareil": str(appareil or "")[:32]}
    if st.get("invites_mode") == "validation":
        st.setdefault("propositions", []).append(p)
        st["propositions"] = st["propositions"][-200:]
        return textes.t("party.merci_attente", langue)
    # Ajout direct : au plus N titres d'invités pas encore joués dans la file (pas d'inondation).
    en_file = [x for x in await file_attente(hass, st["lecteur"]) if x.get("invite")]
    if len(en_file) >= party_invites.INVITES_EN_FILE_MAX:
        raise party_invites.Complet(textes.t("party.complet", langue))
    # 2.5.8 (retour d'essai) : « jouer après » (juste après le titre en cours) ou « en fin de file ».
    ensuite = st.get("invites_mode") == "ensuite"
    await musique.jouer(hass, st["lecteur"], t["uri"], "track", "next" if ensuite else "add")
    p["etat"] = "ajoute"
    st.setdefault("propositions", []).append(p)
    st["propositions"] = st["propositions"][-200:]
    return textes.t("party.merci_ensuite" if ensuite else "party.merci_file", langue)


async def suivre_jumeau(hass: HomeAssistant) -> str:
    """2.5.8 (l'auteur, 16/10 : Sendspin choisi sur la télé → « Lancement… » sans fin, puis l'écran de recherche) : une
    télé qui change de moteur (Classique ↔ Sendspin) a deux lecteurs « · Vision » du même nom ; l'ancien se déconnecte
    et passe hors ligne. La soirée restait sur lui : Music Assistant jouait dans le vide. Elle passe sur le jumeau prêt,
    et la file le suit. Rend le lecteur retenu (inchangé s'il n'y a rien à faire)."""
    st = etat(hass)
    ancien = st.get("lecteur") or ""
    s = hass.states.get(ancien) if ancien else None
    if s is None or s.state != "unavailable":
        return ancien
    nom = str(s.attributes.get("friendly_name") or "").strip()
    if not nom.endswith(SUFFIXE_VISION):
        return ancien
    ma = musique.lecteurs_ma(hass)
    prets = [x.entity_id for x in hass.states.async_all("media_player")
             if x.entity_id != ancien and x.entity_id in ma and x.state != "unavailable"
             and str(x.attributes.get("friendly_name") or "").strip() == nom]
    if not prets:
        return ancien
    nouveau = prets[0]
    st["lecteur"] = nouveau
    _LOGGER.warning("%s : %s est hors ligne, la musique passe sur son jumeau %s (moteur changé sur la télé)", PRODUIT, ancien, nouveau)
    if st["actif"]:
        try:
            await musique.sans_ajout_auto(hass, nouveau, bool(st.get("similaires")))
            await musique.transferer(hass, ancien, nouveau)
        except Exception as err:  # noqa: BLE001 - la soirée est déjà sur le bon lecteur ; la file suivra au prochain choix
            _LOGGER.info("%s : file non transférée de %s vers %s : %s", PRODUIT, ancien, nouveau, err)
    return nouveau


async def commande(hass: HomeAssistant, store, pc: dict[str, Any], corps: dict[str, Any]) -> str:
    """Une commande de décideur (ou de la télé, ACTIONS_TELE). ValueError : une phrase pour l'appli, à dire dans
    la langue de l'appareil (`textes.erreur`) ; la phrase rendue est déjà dans cette langue."""
    lg = textes.pour(hass, pc)
    action = str(corps.get("action") or "")
    par = str(pc.get("name") or pc.get("id"))
    st = etat(hass)
    await suivre_jumeau(hass)
    from . import musique_arret
    if st.get("lecteur"):
        musique_arret.noter(st["lecteur"])  # 2.4.0 : une action sur la musique
    if action == "lancer_ici":
        if not est_tele(pc):
            raise textes.Erreur("party.tele_seule")
        if st["actif"] and st["ecran"] == str(pc.get("id")):
            return ""
        # 2.5.11.1 (l'auteur, 18/10) : le lecteur de la télé peut être en train de se reconnecter : on l'attend un peu, et si
        # la soirée part ailleurs, la télé dit sur quelle enceinte (le 18/10, rien ne disait « Télé du bureau »).
        propre = await attendre_lecteur_propre(hass, store, pc)
        lecteur = lecteur_par_defaut(hass, store, pc)
        retour = await lancer(hass, store, str(pc.get("id")), lecteur, par, pc, lg)
        if lecteur != propre:
            s = hass.states.get(lecteur)
            nom = str(s.attributes.get("friendly_name") or lecteur) if s is not None else lecteur
            return textes.t("party.lance_sur", lg, enceinte=nom)
        return retour
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
    if action == "vider":
        # 17-10 (l'auteur, point 18b) : tout ce qui suit le titre en cours est retiré ; le titre en cours continue. L'ajout
        # automatique de Music Assistant est coupé (sinon la file se remplirait aussitôt) : l'option de la soirée suit.
        n = await musique.vider_suite(hass, st["lecteur"] or lecteur_par_defaut(hass, store, pc))
        st["similaires"] = False
        return textes.t("party.file_videe", lg, n=n)
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
            # 2.5.11 : un titre déjà joué (ou en tampon) est relancé directement par Music Assistant (play_index) ;
            # « suivant » n'est enchaîné que pour un titre à venir, placé juste après celui en cours.
            if not await musique.rejouer_element(hass, st["lecteur"], str(corps.get("element") or "")):
                await _service(hass, "media_player", "media_next_track", st["lecteur"])
            return textes.t("party.cest_parti", lg)
        await musique.ensuite_element(hass, st["lecteur"], str(corps.get("element") or ""))
        return textes.t("party.joue_apres", lg)
    if action == "nouveau_lien":
        if not st["actif"]:
            raise textes.Erreur("party.est_arrete")
        st.update({"jeton": party_invites.nouveau_jeton(), "jeton_ts": time.time(), "refs": {}})
        return textes.t("party.nouveau_code", lg)
    if action == "invites_ouverts":
        # 17-10 (l'auteur, point 8 : « activer et désactiver facilement le mode soirée, sur la télé et le téléphone ; désactivé,
        # le QR code disparaît et l'URL des invités ne donne plus accès à rien ») : fermer révoque le lien tout de suite
        # (toute page ouverte ne peut plus rien) ; rouvrir en donne un NOUVEAU. Le choix vaut aussi pour la Party suivante.
        ouvrir = bool(corps.get("actif"))
        st["invites_ouverts"] = ouvrir
        if not ouvrir:
            st.update({"jeton": None, "jeton_ts": 0, "refs": {}})
            return textes.t("party.invites_fermes", lg)
        if st["actif"] and not st.get("jeton"):
            st.update({"jeton": party_invites.nouveau_jeton(), "jeton_ts": time.time(), "refs": {}})
        return textes.t("party.invites_ouverts", lg)
    if action == "invites":
        mode = str(corps.get("mode") or "")
        # 2.5.8 (retour d'essai) : trois modes — valider avant (« validation »), jouer après (« ensuite »), en fin de file (« direct »).
        if mode not in MODES_INVITES:
            raise textes.Erreur("commun.choix_inconnu")
        st["invites_mode"] = mode
        if isinstance(getattr(store, "musique", None), dict):
            store.musique["invites_mode"] = mode
            await store.async_save()
        return textes.t({"direct": "party.invites_direct", "validation": "party.invites_validation",
                         "ensuite": "party.invites_ensuite"}[mode], lg)
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
        # 2.5.8 : la liste PROPOSÉE (le lecteur « · Vision » d'un Cast coché en fait partie), pas les cases brutes.
        if (getattr(store, "musique", None) or {}).get("enceintes") is not None and \
                lecteur not in {x["entity_id"] for x in lecteurs_pour(hass, store, pc)}:
            raise textes.Erreur("party.enceinte_pas_proposee")
        ancien = st["lecteur"]
        st["lecteur"] = lecteur
        if st["actif"]:
            await musique.sans_ajout_auto(hass, lecteur, bool(st.get("similaires")))
        if st["actif"] and ancien and ancien != lecteur and await musique.transferer(hass, ancien, lecteur):
            await musique.sans_ajout_auto(hass, lecteur, bool(st.get("similaires")))
            return textes.t("party.enceinte_continue", lg)
        return textes.t("party.enceinte_choisie", lg)
    if action == "position":
        cible = hass.states.get(lecteur)
        try:
            p = _position(corps.get("position"), cible.attributes.get("media_duration") if cible else None)
        except (TypeError, ValueError) as e:
            raise textes.Erreur("party.position") from e
        t0 = time.monotonic()
        _mesurer(hass, lecteur, action, t0)
        await _service(hass, "media_player", "media_seek", lecteur, {"seek_position": p})
        st["saut"] = {"lecteur": lecteur, "position": p, "ts": time.time()}
        _LOGGER.info("%s : Musique saut à %.1f s — appel du service : %d ms", PRODUIT, p, (time.monotonic() - t0) * 1000)
        return ""
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


# Banc Musique (2026-10-12) : le lecteur « · Vision » de la Party qui perd le réseau en pleine lecture (Wi-Fi de la télé,
# appli relancée) revient « off » (Classique) et la musique ne repartait jamais. S'il revient dans REPRISE_MAX_S, HA
# relance la lecture (Sendspin se relance lui-même : rien n'est doublé, on attend REPRISE_DELAI_S et on vérifie).
REPRISE_MAX_S = 120
REPRISE_DELAI_S = 4
_COUPURES: dict[str, float] = {}
# 2.5.12 (incident du salon, 18/10 : après une réinstallation de l'appli, la soirée est repartie pendant qu'on regardait
# une appli de replay sur la télé) : ce que l'appareil dit au relevé — autre chose joue chez lui pendant que la musique de Vision ne
# joue pas (vidéo, TV en direct, HDMI). Gardé en mémoire (pas dans le magasin) ; récent = moins de AUTRE_SON_MAX_S.
AUTRE_SON_MAX_S = 60
_AUTRE_SON: dict[str, tuple[bool, float]] = {}


def noter_autre_son(pc_id: str, oui: bool, maintenant_ts: float) -> None:
    _AUTRE_SON[str(pc_id)] = (bool(oui), maintenant_ts)


def autre_son_recent(pc_id: str, maintenant_ts: float) -> bool:
    oui, ts = _AUTRE_SON.get(str(pc_id), (False, 0.0))
    return oui and maintenant_ts - ts <= AUTRE_SON_MAX_S


def reprise_apres_coupure(hass: HomeAssistant, eid: str, ancien: str | None, nouveau: str | None, maintenant_ts: float) -> bool:
    """Vrai quand il faut relancer la lecture : le lecteur de la Party en cours, « · Vision », revenu dans REPRISE_MAX_S
    après avoir disparu en pleine lecture. Note la coupure au passage."""
    st = etat(hass)
    if not st["actif"] or eid != st["lecteur"] or not musique.est_lecteur_vision(hass, eid):
        _COUPURES.pop(eid, None)
        return False
    if nouveau == "unavailable":
        if ancien == "playing":
            _COUPURES[eid] = maintenant_ts
        return False
    if ancien == "unavailable" and eid in _COUPURES:
        return maintenant_ts - _COUPURES.pop(eid) <= REPRISE_MAX_S
    return False


async def reprendre_apres_coupure(hass: HomeAssistant, eid: str) -> None:
    await asyncio.sleep(REPRISE_DELAI_S)
    s = hass.states.get(eid)
    if s is None or s.state in ("playing", "unavailable") or etat(hass)["lecteur"] != eid or not etat(hass)["actif"]:
        return
    # 2.5.12 : jamais par-dessus ce qui joue déjà sur la télé de la soirée (l'appli refuse aussi ce départ).
    if autre_son_recent(etat(hass)["ecran"], time.time()):
        _LOGGER.info("%s : Party — pas de relance sur %s : autre chose joue sur l'appareil", PRODUIT, eid)
        return
    try:
        if s.state == "off":
            await _service(hass, "media_player", "turn_on", eid)
        await _service(hass, "media_player", "media_play", eid)
        _LOGGER.info("%s : Party — lecture relancée sur %s après une coupure", PRODUIT, eid)
    except Exception as err:  # noqa: BLE001 - lecteur pas prêt : la Party reste comme elle est
        _LOGGER.info("%s : Party — relance après coupure impossible sur %s : %s", PRODUIT, eid, err)


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
        # 2.5.12 (essai 9.7 de la TCL : grande pochette en retard) : ce qui manque se lit dans le journal.
        _LOGGER.info("%s : pochette de %s introuvable (ni image du lecteur, ni titre en cours dans la file)", PRODUIT, eid)
        return None
    from . import party_invites
    image = await party_invites.telecharger_image(hass, url)
    if image is None:
        _LOGGER.info("%s : pochette de %s : l'image du titre en cours n'a pas pu être lue", PRODUIT, eid)
    return image


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
        await suivre_jumeau(self.hass)
        ici = bool(st["actif"] and st["ecran"] == str(pc.get("id")))
        ma = musique.lecteurs_ma(self.hass)
        noms = {e["id"]: e["nom"] for e in ecrans(store)}
        # 2.2.3 : soirée pas lancée (ou oubliée par un redémarrage) → on montre ce qui joue vraiment.
        # 2.5.11.1 : hors soirée, l'enceinte montrée est celle qu'un « lancer_ici » prendrait (le lecteur propre d'une télé
        # passe avant la dernière enceinte) ; lecteur_par_defaut garde la dernière enceinte dans les autres cas.
        vu = st["lecteur"] if st["actif"] else lecteur_par_defaut(self.hass, store, pc)
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
            # 2.4.6 : le dernier saut sur ce lecteur (recalage de l'horloge locale d'un lecteur « · Vision »).
            "saut": saut_recent(self.hass, vu),
            "file": (la_file := avec_refs(self.hass, await file_attente(self.hass, st["lecteur"])) if st["actif"] else []),
            # 2.5.8 (l'auteur : « la file doit défiler et suivre le morceau en cours ») : les derniers titres joués, du plus
            # ancien au plus récent ; l'appli les montre au-dessus du titre en cours.
            "file_passes": avec_refs(self.hass, await musique.file_passes(self.hass, st["lecteur"])) if st["actif"] else [],
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
            "invites_ouverts": bool(st.get("invites_ouverts", True)),
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


RECHERCHES_PAR_MINUTE = 90


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
        quoi = str(corps.get("quoi") or "")
        # Recherche instantanée (l'appli cherche pendant qu'on tape, après une pause) : son propre compteur, plus large.
        limite = ("recherche:", RECHERCHES_PAR_MINUTE) if quoi in ("chercher", "suggestions") else ("musique:", widgets.ACTIONS_PAR_MINUTE)
        if widgets._trop_vite(limite[0] + str(pc.get("id")), limite[1]):
            return self.json({"ok": False, "error": "trop_vite", "retour": textes.t("commun.reessaie_instant", lg)},
                             status_code=429)
        try:
            if quoi == "suggestions":
                return self.json({"ok": True, "suggestions": await musique.suggestions(self.hass)})
            if quoi == "chercher":
                texte = str(corps.get("texte") or "")
                # 2.2.8 (retour d'essai) : filtres type (Titres, Albums, Playlists, Artistes, Radios) et plateforme, cumulables ;
                # « Ma bibliothèque seulement » est demandé tel quel à Music Assistant.
                types, doms = musique.choix_filtres(corps)
                res = musique.filtrer(await musique.chercher(self.hass, texte, tuple(types) or musique.TYPES_CHOIX,
                                                             bibliotheque_seule=bool(corps.get("bibliotheque"))), types, doms)
                for liste in (res.values() if isinstance(res, dict) else []):
                    avec_refs(self.hass, liste)
                return self.json({"ok": True, "resultats": res, "plateformes": musique.plateformes(self.hass, lg),
                                  **musique.bilan(self.hass, texte, res, lg)})
            if quoi == "favori":
                # l'auteur (15/10) : le cœur de l'appli Musique, le même appel que le choix de la veille (musique.basculer_favori).
                favori = await musique.basculer_favori(self.hass, str(corps.get("uri") or ""), bool(corps.get("favori")))
                return self.json({"ok": True, "favori": favori})
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
