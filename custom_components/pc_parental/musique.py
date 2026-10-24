"""Point 14 bis (1.99.0) : la musique de Music Assistant, pour la Party (choisir, jouer, file d'attente).

Tout passe par l'intégration officielle Music Assistant de Home Assistant, et par ses services seulement :
`search`, `get_library`, `play_media`, `get_queue`, `transfer_queue`, et les services `media_player` / `button` de ses
lecteurs. Depuis la 2.5.12.14.2 (revue de HACS), Vision n'utilise plus le client interne de l'intégration
(`runtime_data.mass`) : ce qui n'a pas de service (file complète et gestes sur la file, favoris d'un titre qui ne joue pas,
fondu, codec, ajout automatique, appairage Sendspin, volume de groupe de Music Assistant) se fait dans Music Assistant ;
la liste à lui proposer est dans docs/a-proposer-a-music-assistant.md. Vision ne garde aucun identifiant de Music Assistant.

Sans l'intégration (ou lecteur hors Music Assistant) : listes vides et un message clair, jamais une erreur brute.
"""
from __future__ import annotations

import re

import asyncio
import logging
from typing import Any

from homeassistant.core import Context, HomeAssistant
from homeassistant.helpers import entity_registry as er

from . import textes

_LOGGER = logging.getLogger(__name__)

MA = "music_assistant"
TYPES_CHOIX = ("playlist", "radio", "album", "artist", "track")
MAX_TEXTE = 80
MAX_RESULTATS = 20

# 2.1.0 (« dès que j'associe la clé d'un service, je dois avoir tout son catalogue ») : la recherche porte sur
# tous les fournisseurs reliés à Music Assistant, pas seulement la bibliothèque, et chaque résultat dit d'où il vient.
# Le fournisseur se lit dans l'adresse du résultat : « ytmusic--phePBE7p://track/… », « library://… ».
# « library » et « filesystem_* » : des noms communs, traduits (catalogue serveur, `nom_fournisseur`).
NOMS_FOURNISSEURS = {
    "library": "", "ytmusic": "YouTube Music", "spotify": "Spotify", "deezer": "Deezer", "tidal": "Tidal",
    "qobuz": "Qobuz", "apple_music": "Apple Music", "soundcloud": "SoundCloud", "tunein": "TuneIn",
    "radiobrowser": "Radio Browser", "plex": "Plex", "jellyfin": "Jellyfin", "opensubsonic": "Subsonic",
    "filesystem_local": "", "filesystem_smb": "", "builtin": "Music Assistant",
}
_NOMS_TRADUITS = {"library": "mus.biblio", "filesystem_local": "mus.fichiers", "filesystem_smb": "mus.fichiers"}
# Ces sources ne sont pas « tout un catalogue » : sans autre fournisseur, on le dit.
SOURCES_LOCALES = {"library", "filesystem_local", "filesystem_smb", "builtin", "radiobrowser", "tunein"}


def domaine_de(uri: str) -> str:
    """« ytmusic--phePBE7p://track/x » → « ytmusic » ; « library://… » → « library »."""
    tete = str(uri or "").split("://", 1)[0]
    return tete.split("--", 1)[0].lower() if "://" in str(uri or "") else ""


def nom_fournisseur(domaine: str, langue: str | None = None) -> str:
    """Le nom d'un fournisseur de musique ; « Bibliothèque », « Fichiers » dans `langue` (sinon celle de HA)."""
    if domaine in _NOMS_TRADUITS:
        return textes.t(_NOMS_TRADUITS[domaine], langue)
    return NOMS_FOURNISSEURS.get(domaine, domaine.replace("_", " ").capitalize() if domaine else "")


class MusiqueIndisponible(textes.Erreur):
    """Une phrase pour l'appli (clé du catalogue serveur), dite dans la langue de l'appareil (`textes.erreur`)."""


def entree(hass: HomeAssistant):
    """L'intégration Music Assistant chargée, ou None."""
    for e in hass.config_entries.async_entries(MA):
        if getattr(e.state, "value", str(e.state)) == "loaded":
            return e
    return None


def disponible(hass: HomeAssistant) -> bool:
    return entree(hass) is not None and hass.services.has_service(MA, "search")


def lecteurs_ma(hass: HomeAssistant) -> set[str]:
    """Les media_player fournis par Music Assistant (les seuls où l'on peut choisir quoi jouer)."""
    reg = er.async_get(hass)
    return {e.entity_id for e in reg.entities.values() if e.platform == MA and e.domain == "media_player"}


def _texte(v: Any, n: int = 200) -> str:
    return str(v or "")[:n]


def _artistes(item: dict[str, Any]) -> str:
    a = item.get("artists")
    if isinstance(a, list):
        return ", ".join(_texte(x.get("name") if isinstance(x, dict) else x, 60) for x in a[:3])
    return _texte(item.get("artist"), 60)


# 2.3.5 : adresse de Music Assistant pour son imageproxy, retenue à chaque appel (serveur, sinon intégration).
_BASE_MA = ""


def retenir_base(hass: HomeAssistant) -> str:
    """L'adresse du serveur Music Assistant vue par Home Assistant (pour /imageproxy)."""
    global _BASE_MA
    e = entree(hass)
    base = str((getattr(e, "data", None) or {}).get("url") or "") if e is not None else ""
    if base.startswith(("http://", "https://")):
        _BASE_MA = base.rstrip("/")
    return _BASE_MA


def _image(item: dict[str, Any], base: str | None = None) -> str:
    """Une image http(s) (jamais un chemin local).

    2.3.5 (l'auteur : pochettes absentes dans les playlists) : les éléments de la bibliothèque de Music Assistant
    (library://, fichiers locaux, fournisseurs) donnent un chemin interne et son fournisseur, pas une adresse ;
    ils passent par l'imageproxy de Music Assistant, comme le fait son interface.
    """
    if not isinstance(item, dict):
        return ""
    img = item.get("image")
    if not img:
        meta = item.get("metadata") if isinstance(item.get("metadata"), dict) else {}
        imgs = [i for i in (meta.get("images") or []) if isinstance(i, dict)]
        img = next((i for i in imgs if i.get("type") == "thumb"), imgs[0] if imgs else None)
    fournisseur = ""
    if isinstance(img, dict):
        fournisseur = _texte(img.get("provider"), 100)
        img = img.get("path") or img.get("url")
    img = _texte(img, 600)
    if img.startswith(("http://", "https://")):
        return img
    base = _BASE_MA if base is None else base
    if img and fournisseur and base:
        from urllib.parse import urlencode
        return f"{base}/imageproxy?" + urlencode({"path": img, "provider": fournisseur, "size": 300})
    return ""


def element(item: Any) -> dict[str, Any] | None:
    """Un élément de Music Assistant réduit à ce qu'affiche Vision."""
    if not isinstance(item, dict):
        return None
    uri, nom, type_ = _texte(item.get("uri"), 300), _texte(item.get("name"), 120), _texte(item.get("media_type"), 20)
    if not uri or not nom or type_ not in TYPES_CHOIX:
        return None
    album = item.get("album") if isinstance(item.get("album"), dict) else {}
    pms = item.get("provider_mappings") if isinstance(item.get("provider_mappings"), list) else []
    # 2.2.11 (retour d'essai) : les playlists que Music Assistant fabrique lui-même (« 500 Random tracks », « All favorited
    # tracks »…) portent une étiquette (jamais masquées).
    auto = domaine_de(uri) == "builtin" or any(isinstance(p, dict) and str(p.get("provider_domain") or "") == "builtin" for p in pms)
    if domaine_de(uri) not in ("", "library"):
        _FOURNISSEURS_VUS.add(domaine_de(uri))  # les services ne listent pas les fournisseurs : on retient ceux qu'on a vus
    return {"uri": uri, "nom": nom, "type": type_, "artiste": _artistes(item), "album": _texte(album.get("name"), 120),
            "auto": auto,
            "image": _image(item) or _image(album), "favori": bool(item.get("favorite")), "explicit": bool(item.get("explicit")),
            "source": nom_fournisseur(domaine_de(uri)), "domaine": domaine_de(uri)}


async def _appel(hass: HomeAssistant, service: str, donnees: dict[str, Any]) -> Any:
    e = entree(hass)
    if e is None:
        raise MusiqueIndisponible("mus.pas_relie")
    retenir_base(hass)
    try:
        r = await hass.services.async_call(MA, service, {"config_entry_id": e.entry_id, **donnees},
                                           blocking=True, return_response=True, context=Context())
    except Exception as err:  # noqa: BLE001 - l'appli reçoit une phrase, le journal le détail
        _LOGGER.info("Music Assistant %s : %s", service, err)
        raise MusiqueIndisponible("mus.ne_repond_pas") from err
    return r or {}


# Recherche instantanée (l'appli cherche pendant qu'on tape) : un texte déjà cherché il y a moins d'une minute est
# rendu tout de suite, et Music Assistant a 8 s pour répondre (le fournisseur le plus lent fixait la durée).
RECHERCHE_CACHE_S = 60
RECHERCHE_CACHE_MAX = 64
RECHERCHE_DELAI_S = 8


async def chercher(hass: HomeAssistant, texte: str, types: tuple[str, ...] = TYPES_CHOIX,
                   bibliotheque_seule: bool = False) -> dict[str, list[dict[str, Any]]]:
    """Recherche par titre, artiste, album ou playlist. Résultats groupés par type."""
    import time as _time

    texte = " ".join(str(texte or "").split())[:MAX_TEXTE]
    if len(texte) < 2:
        return {}
    # Gardé dans l'instance de Home Assistant (un rechargement ou un autre Home Assistant repart de zéro).
    recherches: dict[tuple[str, tuple[str, ...], bool], tuple[float, dict[str, list[dict[str, Any]]]]] = (
        hass.data.setdefault("pc_parental_recherches", {}))
    cle = (texte.casefold(), tuple(sorted(types)), bool(bibliotheque_seule))
    maintenant = _time.monotonic()
    if (garde := recherches.get(cle)) is not None and maintenant - garde[0] < RECHERCHE_CACHE_S:
        return {t: list(l) for t, l in garde[1].items()}
    # Tout le catalogue de chaque fournisseur (sauf « Ma bibliothèque seulement »), assez de résultats par type.
    try:
        r = await asyncio.wait_for(_appel(hass, "search", {"name": texte, "media_type": list(types), "limit": MAX_RESULTATS,
                                                           "library_only": bool(bibliotheque_seule)}), RECHERCHE_DELAI_S)
    except asyncio.TimeoutError as err:
        _LOGGER.info("Music Assistant : recherche « %s » au-delà de %d s", texte, RECHERCHE_DELAI_S)
        raise MusiqueIndisponible("mus.recherche_lente") from err
    sortie: dict[str, list[dict[str, Any]]] = {}
    for cle_ma, type_ in (("tracks", "track"), ("playlists", "playlist"), ("albums", "album"), ("artists", "artist"), ("radio", "radio")):
        if type_ not in types:
            continue
        l = [x for x in (element(i) for i in (r.get(cle_ma) or [])[:MAX_RESULTATS]) if x]
        if l:
            sortie[type_] = l
    for vieille in [k for k, v in recherches.items() if maintenant - v[0] >= RECHERCHE_CACHE_S]:
        recherches.pop(vieille, None)
    while len(recherches) >= RECHERCHE_CACHE_MAX:
        recherches.pop(next(iter(recherches)))
    recherches[cle] = (maintenant, sortie)
    return {t: list(l) for t, l in sortie.items()}


async def suggestions(hass: HomeAssistant, n: int = 8) -> list[str]:
    """De quoi chercher sans taper : les artistes, puis les titres, les plus joués de la bibliothèque (sans doublon)."""
    items = await souvent_joues(hass, 40)
    sortie: list[str] = []
    for x in items:
        for a in str(x.get("artiste") or "").split(","):
            a = a.strip()
            if a and a.casefold() not in {s.casefold() for s in sortie}:
                sortie.append(a)
    for x in items:
        t = str(x.get("nom") or "").strip()
        if t and t.casefold() not in {s.casefold() for s in sortie}:
            sortie.append(t)
    return sortie[:n]


# Fournisseurs « musique » de Music Assistant qui ne sont pas de la musique à chercher (bruits d'ambiance, démos…).
NON_MUSICAUX = {"ambient_sounds", "rain_mood", "_demo_music_provider", "test"}


_FOURNISSEURS_VUS: set[str] = set()


def fournisseurs(hass: HomeAssistant) -> list[dict[str, str]] | None:
    """Les fournisseurs de musique dont on a déjà vu des résultats ([{domaine, nom}]), ou None tant qu'on n'en a vu aucun :
    les services de Music Assistant ne listent pas ses fournisseurs (2.5.12.14.2 : plus de client interne)."""
    if not _FOURNISSEURS_VUS:
        return None
    return [{"domaine": d, "nom": nom_fournisseur(d)} for d in sorted(_FOURNISSEURS_VUS)]


def plateformes(hass: HomeAssistant, langue: str | None = None) -> list[dict[str, str]]:
    """2.2.8 (l'auteur : « filtrer par plateforme ») : la bibliothèque, puis chaque fournisseur de musique réellement relié à
    Music Assistant (jamais ceux qui ne sont pas de la musique : bruits d'ambiance, démos…)."""
    sortie = [{"domaine": "library", "nom": nom_fournisseur("library", langue)}]
    for f in fournisseurs(hass) or []:
        # 2.2.13 (retour d'essai) : « Music Assistant » (fournisseur interne « builtin ») n'est pas une plateforme à choisir.
        if f["domaine"] in NON_MUSICAUX or f["domaine"] in ("library", "builtin"):
            continue
        if f["domaine"] not in {x["domaine"] for x in sortie}:
            sortie.append(f)
    return sortie


def filtrer(resultats: dict[str, list[dict[str, Any]]], types: list[str] | None, domaines: list[str] | None) -> dict[str, list[dict[str, Any]]]:
    """Garde les types et les plateformes choisis (vide ou None : tout)."""
    sortie: dict[str, list[dict[str, Any]]] = {}
    for t, l in resultats.items():
        if types and t not in types:
            continue
        g = [x for x in l if not domaines or x.get("domaine") in domaines]
        if g:
            sortie[t] = g
    return sortie


def choix_filtres(corps: dict[str, Any], permis: tuple[str, ...] = TYPES_CHOIX) -> tuple[list[str], list[str]]:
    """Les filtres demandés par l'appli ou la page invité, nettoyés (types connus, plateformes au format domaine)."""
    types = [str(t) for t in (corps.get("types") or []) if str(t) in permis][:5] if isinstance(corps.get("types"), list) else []
    doms = [str(d)[:40] for d in (corps.get("plateformes") or []) if str(d).replace("_", "").isalnum()][:10] if isinstance(corps.get("plateformes"), list) else []
    return types, doms


def bilan(hass: HomeAssistant, texte: str, resultats: dict[str, list[dict[str, Any]]],
          langue: str | None = None) -> dict[str, Any]:
    """D'où viennent les résultats, quel fournisseur n'a rien trouvé, et quoi faire s'il n'y a que la bibliothèque."""
    compte: dict[str, int] = {}
    for liste in resultats.values():
        for it in liste:
            s = it.get("source") or ""
            compte[s] = compte.get(s, 0) + 1
    sources = [{"nom": n, "n": c} for n, c in sorted(compte.items(), key=lambda x: -x[1]) if n]
    fours = fournisseurs(hass)
    vides: list[str] = []
    conseil = ""
    if fours is not None:
        en_ligne = [f for f in fours if f["domaine"] not in SOURCES_LOCALES]
        vides = [f["nom"] for f in en_ligne if not compte.get(f["nom"])]
        if not en_ligne:
            conseil = textes.t("mus.conseil_biblio", langue)
    # 2.2.7 (retour d'essai) : jamais la liste des fournisseurs « vides » (« Ambient sounds : rien trouvé… ») ; seulement, si un vrai
    # service de musique est hors service, un message neutre et discret.
    if hors_service(hass):
        conseil = (conseil + " " if conseil else "") + textes.t("mus.hors_service", langue)
    return {"sources": sources, "vides": vides, "conseil": conseil}


def hors_service(hass: HomeAssistant) -> bool:
    """Un fournisseur de musique en ligne relié à Music Assistant mais indisponible (compte expiré, réseau…). Les services de
    Music Assistant ne le disent pas (2.5.12.14.2 : plus de client interne) : jamais signalé ici."""
    return False


async def bibliotheque(hass: HomeAssistant, quoi: str) -> list[dict[str, Any]]:
    """Playlists, radios, albums ou favoris (titres et playlists marqués favoris) de la bibliothèque."""
    if quoi == "favoris":
        sortie: list[dict[str, Any]] = []
        for t in ("playlist", "track", "album"):
            r = await _appel(hass, "get_library", {"media_type": t, "favorite": True})
            sortie += [x for x in (element(i) for i in (r.get("items") or [])[:MAX_RESULTATS]) if x]
        return sortie
    if quoi not in ("playlist", "radio", "album"):
        raise MusiqueIndisponible("commun.choix_inconnu")
    # 2.5.7 (l'auteur, 16/10 : « lire la musique locale du NUC ») : « album » = les albums de la bibliothèque de Music
    # Assistant, où arrive aussi la musique locale (fournisseur Fichiers) ; par nom, plus nombreux que les playlists.
    r = await _appel(hass, "get_library", {"media_type": quoi, **({"limit": 200, "order_by": "name"} if quoi == "album" else {})})
    sortie = [x for x in (element(i) for i in (r.get("items") or [])[:200 if quoi == "album" else 50]) if x]
    return sortie


_RE_BIBLIO = re.compile(r"^library://(track|album|artist|playlist|radio|audiobook|podcast)/(\w+)$")






async def playlists_bibliotheque(hass: HomeAssistant) -> list[dict[str, Any]]:
    """Toutes les playlists de la bibliothèque de Music Assistant (favorites ou non), sans compter leurs titres."""
    r = await _appel(hass, "get_library", {"media_type": "playlist", "limit": 200})
    return [x for x in (element(i) for i in (r.get("items") or [])[:200]) if x]


_SOUVENT: dict[str, Any] = {"ts": 0.0, "items": []}


async def souvent_joues(hass: HomeAssistant, n: int = 12) -> list[dict[str, Any]]:
    """2.2.13 (l'auteur : « rien avant la recherche ») : les titres les plus joués de la bibliothèque (sinon les derniers
    joués), sans titre « explicite » ; gardés 10 min."""
    import time as _time
    if _time.monotonic() - _SOUVENT["ts"] < 600 and _SOUVENT["items"]:
        return list(_SOUVENT["items"])[:n]
    items: list[dict[str, Any]] = []
    for ordre in ("play_count_desc", "last_played_desc", None):
        donnees: dict[str, Any] = {"media_type": "track", "limit": 40}
        if ordre:
            donnees["order_by"] = ordre
        try:
            r = await _appel(hass, "get_library", donnees)
        except MusiqueIndisponible:
            continue
        items = [x for x in (element(i) for i in (r.get("items") or [])) if x and not x.get("explicit")]
        if items:
            break
    _SOUVENT.update(ts=_time.monotonic(), items=items)
    return items[:n]


async def jouer(hass: HomeAssistant, lecteur: str, uri: str, type_: str, comment: str) -> None:
    """« Jouer maintenant » (replace) ou « Ajouter à la file » (add) sur un lecteur Music Assistant."""
    from . import musique_arret
    musique_arret.noter(lecteur)  # 2.4.0 : une action sur la musique (arrêt automatique après inactivité)
    if lecteur not in lecteurs_ma(hass):
        raise MusiqueIndisponible("mus.choisir_enceinte")
    if comment not in ("replace", "add", "next", "play"):
        raise MusiqueIndisponible("commun.choix_inconnu")
    if type_ not in TYPES_CHOIX or not uri or len(uri) > 300 or "://" not in uri:
        raise MusiqueIndisponible("mus.titre_indispo")
    try:
        await hass.services.async_call(MA, "play_media", {"media_id": uri, "media_type": type_, "enqueue": comment},
                                       target={"entity_id": lecteur}, blocking=True, context=Context())
    except Exception as err:  # noqa: BLE001
        _LOGGER.warning("Music Assistant play_media %s (%s, %s) sur %s : %s", uri, type_, comment, lecteur, err)
        raise MusiqueIndisponible("mus.enceinte_echec") from err
    _LOGGER.info("Musique : %s %s (%s) sur %s", comment, uri, type_, lecteur)






def _vers_dict(o: Any) -> dict[str, Any]:
    if isinstance(o, dict):
        return o
    for m in ("to_dict",):
        if hasattr(o, m):
            try:
                return getattr(o, m)()
            except Exception:  # noqa: BLE001
                pass
    return {}






FILE_PASSES_N = 10




















SUFFIXE_VISION = " · Vision"


# 2.3.8 : volume logiciel des lecteurs « · Vision » (la télé règle le son).
VOLUME_VISION = 1.0
# 2.4.0 (règle du projet : « l'appli ne gère pas le volume ») : les lecteurs « · Vision » connus de HA. Personne ne règle
# leur volume dans Vision (widgets, Party) ; HA le tient à 100 %.
_LECTEURS_VISION: set[str] = set()


def est_lecteur_vision(hass: HomeAssistant, eid: str) -> bool:
    """Un lecteur « · Vision » de Music Assistant (une télé ou un appareil Vision qui joue lui-même)."""
    if eid in _LECTEURS_VISION:
        return True
    s = hass.states.get(eid) if eid else None
    nom = str(s.attributes.get("friendly_name") or "") if s is not None else ""
    if nom.endswith(SUFFIXE_VISION):
        _LECTEURS_VISION.add(eid)
        return True
    return False


def lecteurs_vision() -> set[str]:
    return set(_LECTEURS_VISION)


async def tenir_volume(hass: HomeAssistant, eid: str) -> bool:
    """Remet à 100 % le volume logiciel d'un lecteur « · Vision » s'il n'y est pas (rien sinon)."""
    s = hass.states.get(eid)
    vol = s.attributes.get("volume_level") if s is not None else None
    if not est_lecteur_vision(hass, eid) or not isinstance(vol, (int, float)) or vol >= VOLUME_VISION - 0.005:
        return False
    try:
        await hass.services.async_call("media_player", "volume_set", {"entity_id": eid, "volume_level": VOLUME_VISION},
                                       blocking=True)
        _LOGGER.info("Music Assistant : %s remis à 100 %% (volume réglé par la télé)", eid)
        return True
    except Exception as err:  # noqa: BLE001 - on réessaiera au tour suivant
        _LOGGER.warning("Music Assistant : volume de %s non remis à 100 %% : %s", eid, err)
        return False


# ---------------------------------------------------------------------------------------------------------------------
# 2.5.12.14.2 (revue de HACS, hacs/default#12284) : plus de client interne de Music Assistant. Ce qui suit passe par les services de
# l'intégration officielle ; ce qui n'en a pas est retiré, avec un message clair (« à faire dans Music Assistant »).
# La liste de ce qu'on propose à Music Assistant : docs/a-proposer-a-music-assistant.md.

_IMAGES_EN_COURS: dict[str, str] = {}


def a_faire_dans_ma() -> MusiqueIndisponible:
    """La phrase de tout ce que Vision ne fait plus lui-même : à faire dans l'interface de Music Assistant."""
    return MusiqueIndisponible("mus.a_faire_dans_ma")


def image_en_cours(hass: HomeAssistant, lecteur: str) -> str:
    """L'image du titre en cours quand le lecteur lui-même n'en donne pas (lecteur « · Vision »), telle que la dernière
    lecture de la file (`file`, service get_queue) l'a vue."""
    return _IMAGES_EN_COURS.get(lecteur, "")


async def _file_service(hass: HomeAssistant, lecteur: str) -> dict[str, Any]:
    """Le service `music_assistant.get_queue` d'un lecteur : {current_item, next_item, items, …}, ou {}."""
    if not lecteur or lecteur not in lecteurs_ma(hass) or not hass.services.has_service(MA, "get_queue"):
        return {}
    try:
        r = await hass.services.async_call(MA, "get_queue", {}, target={"entity_id": lecteur}, blocking=True,
                                           return_response=True, context=Context())
    except Exception as err:  # noqa: BLE001
        _LOGGER.debug("File Music Assistant de %s illisible : %s", lecteur, err)
        return {}
    q = (r or {}).get(lecteur) or {}
    return q if isinstance(q, dict) else {}


def _ligne_file(it: Any) -> dict[str, Any] | None:
    if not isinstance(it, dict):
        return None
    media = it.get("media_item") if isinstance(it.get("media_item"), dict) else it
    titre = _texte(media.get("name") or it.get("name"), 120)
    if not titre:
        return None
    album = media.get("album") if isinstance(media.get("album"), dict) else {}
    return {"id": _texte(it.get("queue_item_id"), 80), "titre": titre, "artiste": _artistes(media),
            "uri": _texte(media.get("uri"), 300), "image": _image(media) or _image(it) or _image(album)}


async def file(hass: HomeAssistant, lecteur: str, n: int = 30, depuis_fin: int = 0) -> list[dict[str, Any]]:
    """La suite après le titre en cours, telle que le service `get_queue` la donne : le titre suivant seulement
    (la file complète n'a pas de service : à voir dans Music Assistant). `depuis_fin` : rien."""
    if depuis_fin:
        return []
    q = await _file_service(hass, lecteur)
    retenir_base(hass)
    courant = q.get("current_item")
    if isinstance(courant, dict):
        media = courant.get("media_item") if isinstance(courant.get("media_item"), dict) else {}
        album = media.get("album") if isinstance(media.get("album"), dict) else {}
        _IMAGES_EN_COURS[lecteur] = _image(media) or _image(courant) or _image(album)
    suivant = _ligne_file(q.get("next_item"))
    return [suivant] if suivant else []


async def file_passes(hass: HomeAssistant, lecteur: str, n: int = 10) -> list[dict[str, Any]]:
    """Les titres déjà joués : pas de service pour les lire (à voir dans Music Assistant)."""
    return []


async def total_file(hass: HomeAssistant, lecteur: str) -> int | None:
    """Le nombre de titres de la file (champ `items` de `get_queue`), None si inconnu."""
    n = (await _file_service(hass, lecteur)).get("items")
    return n if isinstance(n, int) else None


async def nb_titres(hass: HomeAssistant, uri: str, type_: str) -> int | None:
    """Le nombre de titres d'une playlist ou d'un album : pas de service, donc inconnu."""
    return None


async def basculer_favori(hass: HomeAssistant, uri: str, favori: bool) -> bool:
    """Le cœur de Vision : ajoute aux favoris de Music Assistant **le titre en cours** d'un de ses lecteurs, par le bouton
    « Ajouter la chanson en cours aux favoris » de ce lecteur (seul équivalent en service). Retirer un favori, ou aimer un
    titre qui ne joue pas, n'a pas de service : à faire dans Music Assistant (« mus.a_faire_dans_ma »)."""
    uri = str(uri or "").strip()
    if not uri or "://" not in uri or len(uri) > 300:
        raise MusiqueIndisponible("commun.choix_inconnu")
    if not favori:
        raise a_faire_dans_ma()
    reg = er.async_get(hass)
    for lecteur in sorted(lecteurs_ma(hass)):
        s = hass.states.get(lecteur)
        if s is None or str(s.attributes.get("media_content_id") or "") != uri:
            continue
        joueur = reg.async_get(lecteur)
        for e in list(reg.entities.values()):
            if (e.platform == MA and e.domain == "button" and joueur is not None and e.device_id == joueur.device_id
                    and str(e.unique_id or "").endswith("_favorite_now_playing")):
                try:
                    await hass.services.async_call("button", "press", {"entity_id": e.entity_id}, blocking=True,
                                                   context=Context())
                except Exception as err:  # noqa: BLE001 - l'appli reçoit une phrase, le journal le détail
                    _LOGGER.info("Music Assistant favori %s : %s", uri, err)
                    raise MusiqueIndisponible("mus.ne_repond_pas") from err
                return True
    raise a_faire_dans_ma()


async def transferer(hass: HomeAssistant, depuis: str, vers: str) -> bool:
    """Passe la file d'un lecteur à un autre : le service `music_assistant.transfer_queue`."""
    if not depuis or not vers or depuis == vers or not hass.services.has_service(MA, "transfer_queue"):
        return False
    try:
        await hass.services.async_call(MA, "transfer_queue", {"source_player": depuis, "auto_play": True},
                                       target={"entity_id": vers}, blocking=True, context=Context())
    except Exception as err:  # noqa: BLE001
        _LOGGER.info("Music Assistant : file de %s non transférée à %s : %s", depuis, vers, err)
        return False
    return True


# Tout ce qui suit n'a pas de service Home Assistant : plus fait par Vision (à voir dans Music Assistant).

async def rejouer_element(hass: HomeAssistant, lecteur: str, id_element: str) -> None:
    raise a_faire_dans_ma()


async def ensuite_element(hass: HomeAssistant, lecteur: str, id_element: str) -> None:
    raise a_faire_dans_ma()


async def deplacer(hass: HomeAssistant, lecteur: str, id_element: str, decalage: int) -> None:
    raise a_faire_dans_ma()


async def a_la_fin(hass: HomeAssistant, lecteur: str, id_element: str) -> None:
    raise a_faire_dans_ma()


async def retirer(hass: HomeAssistant, lecteur: str, id_element: str) -> None:
    raise a_faire_dans_ma()


async def vider_suite(hass: HomeAssistant, lecteur: str) -> int:
    raise a_faire_dans_ma()


async def regler_enchainement(hass: HomeAssistant, lecteur: str, fondu: bool, duree: int | None = None) -> None:
    raise a_faire_dans_ma()


async def enchainement(hass: HomeAssistant, lecteur: str) -> dict[str, Any] | None:
    """Le fondu enchaîné : pas de service pour le lire, donc inconnu."""
    return None


async def sans_ajout_auto(hass: HomeAssistant, lecteur: str, similaires: bool = False) -> None:
    """L'ajout automatique de titres de Music Assistant : pas de service pour le régler, donc rien ici."""
    return None


def ajout_auto_actif(hass: HomeAssistant, lecteur: str) -> bool | None:
    return None


async def couper_ajout_auto(hass: HomeAssistant, eid: str) -> bool:
    return False


def similaires_voulus(hass: HomeAssistant) -> bool:
    """L'option « Continuer avec des titres similaires » de la soirée (sans effet depuis la 2.5.12.14.2 : pas de service)."""
    from .party import etat as etat_soiree
    return bool(etat_soiree(hass).get("similaires"))


def nom_vision(hass: HomeAssistant, e) -> str:
    """Le nom « <appareil> · Vision » de ce lecteur tel que Home Assistant le voit ; vide si ce n'est pas un lecteur de
    Vision. Plus de renommage depuis la 2.5.12.14.2 : un lecteur dont le nom ne porte pas encore le suffixe (créé avant que
    Music Assistant ne l'ait renommé) est reconnu après le rechargement de l'intégration Music Assistant."""
    s = hass.states.get(e.entity_id)
    nom = str((s.attributes.get("friendly_name") if s else None) or e.original_name or "")
    return nom if nom.endswith(SUFFIXE_VISION) else ""


async def regler_lecteurs_vision(hass: HomeAssistant) -> list[str]:
    """Les lecteurs « · Vision » de Music Assistant sont repérés (par leur entity_id, retenu) et leur volume tenu à 100 %
    (service media_player.volume_set). Plus de codec ni d'ajout automatique à régler : à voir dans Music Assistant."""
    reg = er.async_get(hass)
    faits = []
    for e in list(reg.entities.values()):
        if e.platform != MA or e.domain != "media_player" or not nom_vision(hass, e):
            continue
        _LECTEURS_VISION.add(e.entity_id)
        await tenir_volume(hass, e.entity_id)
        faits.append(e.entity_id)
    return faits
