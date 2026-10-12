"""Point 14 bis (1.99.0) : la musique de Music Assistant, pour la Party (choisir, jouer, file d'attente).

Tout passe par l'intégration officielle Music Assistant de Home Assistant : ses services (`search`, `get_library`,
`play_media`, `get_queue`) et, pour la file complète et le retrait d'un titre, son client déjà connecté
(`entry.runtime_data.mass`). Vision ne garde aucun identifiant de Music Assistant.

Sans l'intégration (ou lecteur hors Music Assistant) : listes vides et un message clair, jamais une erreur brute.
"""
from __future__ import annotations

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
    base = ""
    mass = _client(hass)
    info = getattr(mass, "server_info", None) if mass is not None else None
    base = str(getattr(info, "base_url", "") or "") if info is not None else ""
    if not base:
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


async def chercher(hass: HomeAssistant, texte: str, types: tuple[str, ...] = TYPES_CHOIX) -> dict[str, list[dict[str, Any]]]:
    """Recherche par titre, artiste, album ou playlist. Résultats groupés par type."""
    texte = " ".join(str(texte or "").split())[:MAX_TEXTE]
    if len(texte) < 2:
        return {}
    # Tout le catalogue de chaque fournisseur (library_only=False), et assez de résultats par type (5 par défaut).
    r = await _appel(hass, "search", {"name": texte, "media_type": list(types), "limit": MAX_RESULTATS, "library_only": False})
    sortie: dict[str, list[dict[str, Any]]] = {}
    for cle, type_ in (("tracks", "track"), ("playlists", "playlist"), ("albums", "album"), ("artists", "artist"), ("radio", "radio")):
        if type_ not in types:
            continue
        l = [x for x in (element(i) for i in (r.get(cle) or [])[:MAX_RESULTATS]) if x]
        if l:
            sortie[type_] = l
    return sortie


# Fournisseurs « musique » de Music Assistant qui ne sont pas de la musique à chercher (bruits d'ambiance, démos…).
NON_MUSICAUX = {"ambient_sounds", "rain_mood", "_demo_music_provider", "test"}


def fournisseurs(hass: HomeAssistant) -> list[dict[str, str]] | None:
    """Les fournisseurs de musique reliés à Music Assistant ([{domaine, nom}]), ou None si on ne peut pas le savoir."""
    mass = _client(hass)
    liste = getattr(mass, "providers", None) if mass is not None else None
    if liste is None:
        return None
    sortie: list[dict[str, str]] = []
    try:
        for p in list(liste):
            d = p if isinstance(p, dict) else {k: getattr(p, k, None) for k in ("type", "domain", "name", "available")}
            if str(getattr(d.get("type"), "value", d.get("type")) or "") != "music" or d.get("available") is False:
                continue
            dom = str(d.get("domain") or "")
            if dom and dom not in {x["domaine"] for x in sortie}:
                sortie.append({"domaine": dom, "nom": nom_fournisseur(dom)})
    except Exception:  # noqa: BLE001
        return None
    return sortie


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
    """Un fournisseur de musique en ligne relié à Music Assistant mais indisponible (compte expiré, réseau…)."""
    mass = _client(hass)
    liste = getattr(mass, "providers", None) if mass is not None else None
    try:
        for p in list(liste or []):
            d = p if isinstance(p, dict) else {k: getattr(p, k, None) for k in ("type", "domain", "available")}
            if (str(getattr(d.get("type"), "value", d.get("type")) or "") == "music" and d.get("available") is False
                    and str(d.get("domain") or "") not in SOURCES_LOCALES and str(d.get("domain") or "") not in NON_MUSICAUX):
                return True
    except Exception:  # noqa: BLE001
        return False
    return False


async def bibliotheque(hass: HomeAssistant, quoi: str) -> list[dict[str, Any]]:
    """Playlists, radios ou favoris (titres et playlists marqués favoris) de la bibliothèque."""
    if quoi == "favoris":
        sortie: list[dict[str, Any]] = []
        for t in ("playlist", "track", "album"):
            r = await _appel(hass, "get_library", {"media_type": t, "favorite": True})
            sortie += [x for x in (element(i) for i in (r.get("items") or [])[:MAX_RESULTATS]) if x]
        return sortie
    if quoi not in ("playlist", "radio"):
        raise MusiqueIndisponible("commun.choix_inconnu")
    r = await _appel(hass, "get_library", {"media_type": quoi})
    sortie = [x for x in (element(i) for i in (r.get("items") or [])[:50]) if x]
    if quoi == "playlist":
        # 2.2.11 (retour d'essai) : le nombre de titres de chaque playlist (en parallèle, au plus 4 à la fois, gardé 10 min).
        sem = asyncio.Semaphore(4)

        async def compter(x: dict[str, Any]) -> None:
            async with sem:
                x["nb"] = await nb_titres(hass, x["uri"], "playlist")
        await asyncio.gather(*(compter(x) for x in sortie))
    return sortie


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


_NB_CACHE: dict[str, tuple[float, int | None]] = {}


async def nb_titres(hass: HomeAssistant, uri: str, type_: str) -> int | None:
    """Combien de titres compte une playlist ou un album (None : inconnu, ou playlist sans fin)."""
    import time as _time
    if type_ not in ("playlist", "album") or "://" not in uri:
        return None
    vu = _NB_CACHE.get(uri)
    if vu and _time.monotonic() - vu[0] < 600:
        return vu[1]
    mass = _client(hass)
    if mass is None or not hasattr(mass, "music"):
        return None
    prov, reste = uri.split("://", 1)
    item_id = reste.split("/", 1)[1] if "/" in reste else ""
    if not item_id:
        return None
    try:
        if type_ == "playlist":
            l = await asyncio.wait_for(mass.music.get_playlist_tracks(item_id, prov), 8)
        else:
            l = await asyncio.wait_for(mass.music.get_album_tracks(item_id, prov), 8)
        n = len(l or [])
    except Exception as err:  # noqa: BLE001 - playlist dynamique ou service lent : on ne sait pas
        _LOGGER.debug("Nombre de titres de %s : %s", uri, err)
        n = None
    _NB_CACHE[uri] = (_time.monotonic(), n)
    return n


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
    # 2.2.10 (l'auteur : « 414 titres que je n'ai pas choisis ») : l'« Autoplay » de Music Assistant (mode auto ou
    # bibliothèque) ajoutait jusqu'à 150 titres au hasard à chaque relance ; il est coupé après CHAQUE lecture.
    await sans_ajout_auto(hass, lecteur, similaires_voulus(hass))


def _client(hass: HomeAssistant):
    e = entree(hass)
    rd = getattr(e, "runtime_data", None) if e is not None else None
    return getattr(rd, "mass", None)


def _file_id(hass: HomeAssistant, lecteur: str) -> str:
    s = hass.states.get(lecteur)
    return str(s.attributes.get("active_queue") or "") if s is not None else ""


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


def image_en_cours(hass: HomeAssistant, lecteur: str) -> str:
    """2.2.13 (l'auteur : « pochette vide » sur le titre en cours) : l'image du titre en cours dans la file de Music
    Assistant (titre, sinon son album), quand le lecteur lui-même n'en donne pas (lecteur « · Vision »)."""
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid or not hasattr(mass.player_queues, "get"):
        return ""
    retenir_base(hass)
    try:
        q = _vers_dict(mass.player_queues.get(qid))
    except Exception:  # noqa: BLE001
        return ""
    d = _vers_dict(q.get("current_item"))
    media = _vers_dict(d.get("media_item"))
    album = media.get("album") if isinstance(media.get("album"), dict) else {}
    return _image(media) or _image(d) or _image(album)


async def file(hass: HomeAssistant, lecteur: str, n: int = 30) -> list[dict[str, Any]]:
    """La suite après le titre en cours : [{id, titre, artiste, uri}]. Client MA si possible, sinon get_queue."""
    if not lecteur or lecteur not in lecteurs_ma(hass):
        return []
    mass, qid = _client(hass), _file_id(hass, lecteur)
    retenir_base(hass)
    if mass is not None and qid:
        try:
            q = _vers_dict(mass.player_queues.get(qid)) if hasattr(mass.player_queues, "get") else {}
            courant = q.get("current_index")
            # 2.2.1 (l'auteur : « une file infinie ») : on lit à partir du titre en cours ; avant, la lecture partait du
            # début et, loin dans une longue file (372 titres), l'historique passait pour « à suivre ».
            depart = courant + 1 if isinstance(courant, int) and courant >= 0 else 0
            items = await mass.player_queues.get_queue_items(qid, limit=n, offset=depart)
            sortie = []
            vus: set[str] = set()
            for it in items or []:
                d = _vers_dict(it)
                cle = str(d.get("queue_item_id") or "")
                if cle and cle in vus:  # jamais deux fois la même ligne
                    continue
                vus.add(cle)
                media = d.get("media_item") if isinstance(d.get("media_item"), dict) else {}
                img = _image(media) or _image(d) or _image(media.get("album") if isinstance(media.get("album"), dict) else {})
                sortie.append({"id": _texte(d.get("queue_item_id"), 80), "titre": _texte(media.get("name") or d.get("name"), 120),
                               "artiste": _artistes(media), "uri": _texte(media.get("uri"), 300), "image": img})
                if len(sortie) >= n:
                    break
            return [x for x in sortie if x["titre"]]
        except Exception as err:  # noqa: BLE001 - repli sur le service
            _LOGGER.debug("File Music Assistant (client) illisible : %s", err)
    if not hass.services.has_service(MA, "get_queue"):
        return []
    try:
        r = await hass.services.async_call(MA, "get_queue", {}, target={"entity_id": lecteur}, blocking=True,
                                           return_response=True, context=Context())
    except Exception as err:  # noqa: BLE001
        _LOGGER.debug("File Music Assistant de %s illisible : %s", lecteur, err)
        return []
    q = (r or {}).get(lecteur) or {}
    it = q.get("next_item") if isinstance(q, dict) else None
    if isinstance(it, dict):
        media = it.get("media_item") if isinstance(it.get("media_item"), dict) else it
        t = _texte(media.get("name") or it.get("name"), 120)
        if t:
            return [{"id": _texte(it.get("queue_item_id"), 80), "titre": t, "artiste": _artistes(media), "uri": _texte(media.get("uri"), 300)}]
    return []


async def _sur_file(hass: HomeAssistant, lecteur: str, quoi: str, faire) -> None:
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid:
        raise MusiqueIndisponible("mus.file_non_modifiable")
    try:
        await faire(mass.player_queues, qid)
    except Exception as err:  # noqa: BLE001
        _LOGGER.info("%s sur la file %s : %s", quoi, qid, err)
        raise MusiqueIndisponible("mus.echec_reessaie") from err


async def jouer_element(hass: HomeAssistant, lecteur: str, id_element: str) -> None:
    """2.2.1 : jouer tout de suite un titre déjà dans la file (sans rien retirer)."""
    if not id_element:
        raise MusiqueIndisponible("commun.choix_inconnu")
    await _sur_file(hass, lecteur, "play_index " + id_element, lambda pq, q: pq.play_index(q, id_element))


async def ensuite_element(hass: HomeAssistant, lecteur: str, id_element: str) -> None:
    """2.2.1 : placer un titre de la file juste après celui qui joue."""
    if not id_element:
        raise MusiqueIndisponible("commun.choix_inconnu")
    await _sur_file(hass, lecteur, "move_next " + id_element, lambda pq, q: pq.move_next(q, id_element))


async def deplacer(hass: HomeAssistant, lecteur: str, id_element: str, decalage: int) -> None:
    """2.2.1 (retour d'essai) : déplacer un titre de la file (décalage en rangs ; positif vers la fin), sans couper la lecture."""
    if not id_element or not decalage:
        raise MusiqueIndisponible("commun.choix_inconnu")
    await _sur_file(hass, lecteur, "move_item " + id_element, lambda pq, q: pq.move_item(q, id_element, int(decalage)))


async def a_la_fin(hass: HomeAssistant, lecteur: str, id_element: str) -> None:
    """2.2.1 (retour d'essai) : mettre un titre à la fin de la file."""
    async def faire(pq, q):
        items = await pq.get_queue_items(q, limit=2000, offset=0)
        ids = [str(_vers_dict(x).get("queue_item_id")) for x in items or []]
        if id_element not in ids:
            raise ValueError("absent")
        decalage = len(ids) - 1 - ids.index(id_element)
        if decalage > 0:
            await pq.move_item(q, id_element, decalage)
    await _sur_file(hass, lecteur, "a_la_fin " + id_element, faire)


async def sans_ajout_auto(hass: HomeAssistant, lecteur: str, similaires: bool = False) -> None:
    """2.2.1 (l'auteur : « une file infinie ») : pas d'ajout automatique de Music Assistant (« Don't stop the music »),
    sauf si l'option « Continuer avec des titres similaires quand la file est vide » est cochée."""
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid:
        return
    try:
        # Music Assistant 2.10 : « player_queues/autoplay » (l'ancien nom reste un alias).
        if hasattr(mass, "send_command"):
            try:
                await mass.send_command("player_queues/autoplay", queue_id=qid, autoplay_enabled=bool(similaires))
                return
            except Exception:  # noqa: BLE001 - serveur plus ancien : l'ancien nom
                pass
        await mass.player_queues.dont_stop_the_music(qid, bool(similaires))
    except Exception as err:  # noqa: BLE001 - ancienne version de Music Assistant : rien à couper
        _LOGGER.debug("Don't stop the music sur %s : %s", qid, err)


SUFFIXE_VISION = " · Vision"
CODEC_VISION = "mp3"
_CODEC_FAIT: set[str] = set()


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


async def regler_lecteurs_vision(hass: HomeAssistant) -> list[str]:
    """2.2.13 (l'auteur : « un morceau démarre 5 s puis se coupe ») : Music Assistant envoie du FLAC par défaut à un lecteur
    Squeezelite ; le lecteur de Vision (Android) ne lit en flux que le MP3 : il jouait les 4-5 s reçues puis coupait.
    Chaque lecteur « · Vision » de Music Assistant est donc réglé en MP3 (une fois par lecteur et par démarrage).

    2.3.8 (l'auteur : « pas de son », « le volume est géré par la TV ») : un lecteur « · Vision » recréé arrivait à 10 % de
    volume logiciel (son quasi nul). Son volume logiciel est tenu à 100 % ; c'est la télécommande qui règle le son."""
    reg = er.async_get(hass)
    for e in list(reg.entities.values()):
        if e.platform != MA or e.domain != "media_player":
            continue
        s = hass.states.get(e.entity_id)
        nom = str((s.attributes.get("friendly_name") if s else None) or e.original_name or "")
        if nom.endswith(SUFFIXE_VISION):
            _LECTEURS_VISION.add(e.entity_id)
            await tenir_volume(hass, e.entity_id)
    mass = _client(hass)
    if mass is None or not hasattr(mass, "send_command"):
        return []
    faits = []
    for e in list(reg.entities.values()):
        if e.platform != MA or e.domain != "media_player" or not e.unique_id or e.unique_id in _CODEC_FAIT:
            continue
        s = hass.states.get(e.entity_id)
        nom = str((s.attributes.get("friendly_name") if s else None) or e.original_name or "")
        if not nom.endswith(SUFFIXE_VISION):
            continue
        # Client Sendspin natif : le MP3 ne vaut que pour l'ancien lecteur Squeezelite de Vision (identifiant = adresse
        # MAC) ; un lecteur Sendspin choisit lui-même son format (FLAC) et sa config n'est jamais touchée.
        from .volume_party import est_squeezelite
        if not est_squeezelite(e.unique_id):
            continue
        try:
            actuel = None
            try:
                actuel = await mass.send_command("config/players/get_value", player_id=e.unique_id, key="output_codec")
            except Exception:  # noqa: BLE001 - ancienne version : on enregistre directement
                pass
            if str(actuel or "") != CODEC_VISION:
                await mass.send_command("config/players/save", player_id=e.unique_id, values={"output_codec": CODEC_VISION})
                _LOGGER.info("Music Assistant : %s réglé en %s (lecteur de Vision)", nom, CODEC_VISION)
            _CODEC_FAIT.add(e.unique_id)
            faits.append(e.entity_id)
        except Exception as err:  # noqa: BLE001 - on réessaiera au tour suivant
            _LOGGER.warning("Music Assistant : réglage MP3 de %s impossible : %s", nom, err)
    return faits


def similaires_voulus(hass: HomeAssistant) -> bool:
    """L'option « Continuer avec des titres similaires quand la file est vide » de la soirée (non par défaut)."""
    from .party import etat as etat_soiree
    return bool(etat_soiree(hass).get("similaires"))


def ajout_auto_actif(hass: HomeAssistant, lecteur: str) -> bool | None:
    """L'« Autoplay » de Music Assistant est-il actif sur la file de ce lecteur ? (None : on ne sait pas)."""
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid or not hasattr(mass.player_queues, "get"):
        return None
    q = _vers_dict(mass.player_queues.get(qid))
    v = q.get("autoplay_enabled", q.get("dont_stop_the_music_enabled"))
    return bool(v) if v is not None else None


async def vider_suite(hass: HomeAssistant, lecteur: str) -> int:
    """2.2.10 (retour d'essai) : retire tout ce qui suit le titre en cours (le titre en cours continue) ; l'ajout auto est coupé."""
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid:
        raise MusiqueIndisponible("mus.ma_injoignable")
    await sans_ajout_auto(hass, lecteur, False)
    q = _vers_dict(mass.player_queues.get(qid)) if hasattr(mass.player_queues, "get") else {}
    courant = q.get("current_index")
    depart = courant + 1 if isinstance(courant, int) and courant >= 0 else 0
    retires = 0
    while True:
        items = await mass.player_queues.get_queue_items(qid, limit=200, offset=depart)
        ids = [str(_vers_dict(x).get("queue_item_id") or "") for x in items or []]
        ids = [i for i in ids if i]
        if not ids:
            break
        for i in ids:
            try:
                await mass.player_queues.delete_item(qid, i)
                retires += 1
            except Exception as err:  # noqa: BLE001
                _LOGGER.info("Retrait de %s : %s", i, err)
        if len(ids) < 200 or retires > 5000:
            break
    _LOGGER.warning("Musique : %d titres retirés de la file de %s (le titre en cours continue)", retires, lecteur)
    return retires


async def total_file(hass: HomeAssistant, lecteur: str) -> int | None:
    """Combien de titres restent à suivre (le vrai nombre, pas seulement ceux affichés)."""
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid:
        return None
    try:
        q = _vers_dict(mass.player_queues.get(qid)) if hasattr(mass.player_queues, "get") else {}
        items, courant = q.get("items"), q.get("current_index")
        if isinstance(items, int) and isinstance(courant, int):
            return max(0, items - courant - 1)
    except Exception:  # noqa: BLE001
        pass
    return None


async def enchainement(hass: HomeAssistant, lecteur: str) -> dict[str, Any] | None:
    """2.2.4 (l'auteur : « trop d'attente entre deux musiques ») : comment Music Assistant enchaîne sur ce lecteur.

    flux : un seul flux continu (pas de blanc entre les titres, le suivant est préparé à l'avance) ; fondu : fondu
    enchaîné ; duree : sa durée (s) ; precharge : le titre suivant est déjà chargé. None si on ne sait pas."""
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid:
        return None
    try:
        q = _vers_dict(mass.player_queues.get(qid)) if hasattr(mass.player_queues, "get") else {}
        if not q:
            return None
        duree = None
        try:
            duree = await mass.send_command("config/player_queues/get_value", queue_id=qid, key="crossfade_duration")
        except Exception:  # noqa: BLE001 - ancienne version de Music Assistant
            pass
        courant, tampon = q.get("current_index"), q.get("index_in_buffer")
        return {"flux": bool(q.get("flow_mode")), "fondu": bool(q.get("crossfade_enabled")),
                "duree": int(duree) if isinstance(duree, (int, float)) else None,
                "precharge": isinstance(courant, int) and isinstance(tampon, int) and tampon > courant}
    except Exception as err:  # noqa: BLE001
        _LOGGER.debug("Enchaînement de %s illisible : %s", lecteur, err)
        return None


async def regler_enchainement(hass: HomeAssistant, lecteur: str, fondu: bool, duree: int | None = None) -> None:
    """« Sans blanc » (fondu coupé) ou « fondu de X s » sur la file du lecteur, à chaud (le titre suivant suit)."""
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid:
        raise MusiqueIndisponible("mus.ma_injoignable")
    try:
        if fondu and duree:
            d = max(1, min(15, int(duree)))
            try:
                await mass.send_command("config/player_queues/save", queue_id=qid,
                                        values={"crossfade_mode": "standard_crossfade", "crossfade_duration": d})
            except Exception:  # noqa: BLE001 - réglage de mode absent : la durée seule
                await mass.send_command("config/player_queues/save", queue_id=qid, values={"crossfade_duration": d})
        await mass.send_command("player_queues/crossfade", queue_id=qid, crossfade_enabled=bool(fondu))
    except MusiqueIndisponible:
        raise
    except Exception as err:  # noqa: BLE001
        _LOGGER.warning("Enchaînement de %s non réglé : %s", lecteur, err)
        raise MusiqueIndisponible("mus.reglage_refuse") from err


async def transferer(hass: HomeAssistant, depuis: str, vers: str) -> bool:
    """2.2.1 : la file passe d'une enceinte à l'autre (rien n'est perdu). Faux si ce n'est pas possible."""
    mass, src, dst = _client(hass), _file_id(hass, depuis), _file_id(hass, vers)
    if mass is None or not src or not dst or src == dst:
        return False
    try:
        await mass.player_queues.transfer(src, dst, auto_play=True)
        return True
    except Exception as err:  # noqa: BLE001
        _LOGGER.info("Transfert de la file %s vers %s : %s", src, dst, err)
        return False


async def retirer(hass: HomeAssistant, lecteur: str, id_element: str) -> None:
    mass, qid = _client(hass), _file_id(hass, lecteur)
    if mass is None or not qid or not id_element:
        raise MusiqueIndisponible("mus.retrait_impossible")
    avant = _vers_dict(mass.player_queues.get(qid)).get("items") if hasattr(mass.player_queues, "get") else None
    try:
        await mass.player_queues.delete_item(qid, id_element)
    except Exception as err:  # noqa: BLE001
        _LOGGER.info("Retrait de %s de la file %s : %s", id_element, qid, err)
        raise MusiqueIndisponible("mus.retrait_echec") from err
    # 2.2.6 (l'auteur : « le compteur descend puis revient ») : on répond quand Music Assistant a bien compté le retrait
    # (1,5 s au plus), pour que la relecture suivante ne montre jamais l'ancien nombre.
    if isinstance(avant, int):
        for _ in range(15):
            q = _vers_dict(mass.player_queues.get(qid))
            if not isinstance(q.get("items"), int) or q["items"] < avant:
                break
            await asyncio.sleep(0.1)
