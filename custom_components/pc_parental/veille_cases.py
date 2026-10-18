"""Les cases typées de l'écran de veille (2.2.12, docs/veille-contrat.md §4.2).

Une carte de tableau de bord qui n'est pas une simple valeur devient une case « typée », la même pour le PC et la
télé : météo (prévisions quotidiennes), agenda, image (picture-elements : fond et textes positionnés), radar,
caméra, iframe, derniers passages Frigate.

Deux temps, comme le reste des tableaux de bord :

- `carte_typee` lit la carte une fois, à l'enregistrement (rangée dans `fige`) ;
- `resoudre` remplit la case à chaque relevé, en mode léger : prévisions et agenda gardés quelques minutes,
  tailles bornées, aucune requête lourde.

Les images ne sortent jamais par leur adresse d'origine : chaque image reçoit une clé opaque, donnée à l'appareil
qui a fait le relevé, et servie par `/api/pc_parental/veille/media/<clé>` (secret de l'appareil ou ticket de la
veille PC). Seules les sources écrites par l'administrateur dans la carte peuvent ainsi sortir.
"""
from __future__ import annotations

import datetime
import hashlib
import logging
import os
import re
import time
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

_LOGGER = logging.getLogger(__name__)

URL_MEDIA = "/api/pc_parental/veille/media"
GENRES = ("meteo", "agenda", "image", "radar", "camera", "iframe", "clips")
CARTES_FRIGATE = ("custom:frigate-card", "custom:advanced-camera-card")
MAX_PREVISIONS = 5
MAX_EVENEMENTS = 8
MAX_ELEMENTS = 12
MAX_CALQUES = 4
MAX_CLIPS = 8
AGENDA_JOURS = 7
CACHE_METEO_S = 600
CACHE_AGENDA_S = 300
CACHE_ECHEC_S = 120
DUREE_CLE_S = 30 * 60   # renouvelée à chaque relevé ; un retrait de carte n'ouvre plus rien au-delà
DUREE_MAX_S = 24 * 3600  # au plus, même utilisée sans arrêt
MAX_CLES = 600
MAX_OCTETS = 8 * 1024 * 1024
EXTENSIONS = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp",
              ".gif": "image/gif", ".svg": "image/svg+xml"}

# Les objets de Frigate : catalogue « veille.objet.<objet> » (six langues) ; un objet inconnu garde le mot de Frigate.
OBJETS = ("person", "car", "dog", "cat", "bicycle", "motorcycle", "bird", "truck", "bus", "package", "face", "license_plate")
_cache_meteo: dict[str, tuple[float, list[dict[str, Any]]]] = {}
_cache_agenda: dict[tuple[str, ...], tuple[float, list[dict[str, Any]]]] = {}
# clé opaque → {"source": "local:/local/x.png" | "camera:camera.x" | "image:image.x", "pcs": {pc_id: fin}}
_medias: dict[str, dict[str, Any]] = {}


# --- Lecture d'une carte (à l'enregistrement) ---------------------------------------------------


def _pourcent(v: Any) -> float | None:
    m = re.fullmatch(r"\s*(-?\d+(?:\.\d+)?)\s*%\s*", str(v or ""))
    return round(float(m.group(1)), 1) if m else None


def _fond_local(v: Any) -> str:
    """Seulement un fichier du dossier www (« /local/… ») ; jamais une adresse externe ou interne."""
    s = str(v or "").split("?", 1)[0].strip()
    return s if s.startswith("/local/") and ".." not in s and os.path.splitext(s)[1].lower() in EXTENSIONS else ""


def _camera(v: Any) -> str:
    s = str(v or "").strip()
    return s if re.fullmatch(r"camera\.[a-z0-9_]+", s) else ""


def _base(carte: dict[str, Any], genre: str, largeur: int | None, defaut: int, hauteur: int) -> dict[str, Any]:
    from .veille_dash import _hauteur, _largeur

    titre = carte.get("title") or carte.get("name")
    return {
        "genre": genre,
        "libelle": str(titre)[:40] if isinstance(titre, str) else "",
        "largeur": largeur or _largeur(carte, defaut),
        "hauteur": _hauteur(carte) or hauteur,
    }


def _elements(liste: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Les textes (state-label, state-icon, state-badge) et les calques de caméra d'une picture-elements."""
    textes: list[dict[str, Any]] = []
    calques: list[dict[str, Any]] = []
    for el in liste if isinstance(liste, list) else []:
        if not isinstance(el, dict):
            continue
        style = el.get("style") if isinstance(el.get("style"), dict) else {}
        x, y = _pourcent(style.get("left")), _pourcent(style.get("top"))
        if x is None or y is None:
            continue
        genre = str(el.get("type") or "")
        if genre in ("state-label", "state-icon", "state-badge") and isinstance(el.get("entity"), str):
            textes.append({
                "entite": el["entity"], "x": x, "y": y,
                "attribut": str(el.get("attribute") or "") if genre == "state-label" else "",
                "prefixe": str(el.get("prefix") or "")[:20] if genre == "state-label" else "",
                "suffixe": str(el.get("suffix") or "")[:20] if genre == "state-label" else "",
            })
        elif genre == "image" and _camera(el.get("camera_image")):
            calques.append({"entite": _camera(el["camera_image"]), "x": x, "y": y,
                            "largeur": _pourcent(style.get("width")) or 100.0})
    return textes[:MAX_ELEMENTS], calques[:MAX_CALQUES]


def carte_typee(carte: dict[str, Any], largeur: int | None) -> list[dict[str, Any]] | None:
    """Les cases typées d'une carte, ou None si la carte n'est pas typée (elle reste une valeur)."""
    genre = str(carte.get("type") or "")
    entite = carte.get("entity") if isinstance(carte.get("entity"), str) else ""
    if genre == "weather-forecast":
        if not entite.startswith("weather."):
            return []
        return [{**_base(carte, "meteo", largeur, 12, 2), "entite": entite}]
    if genre == "calendar":
        entites = []
        for e in carte.get("entities") or []:
            eid = e if isinstance(e, str) else (e.get("entity") if isinstance(e, dict) else "")
            if isinstance(eid, str) and eid.startswith("calendar."):
                entites.append(eid)
        if not entites:
            return []
        return [{**_base(carte, "agenda", largeur, 12, 2), "entite": entites[0], "entites": entites[:6]}]
    if genre == "picture-elements":
        textes, calques = _elements(carte.get("elements"))
        fond = _fond_local(carte.get("image"))
        fond_cam = _camera(carte.get("camera_image"))
        if not fond and not fond_cam:
            return []  # sans fond, rien à dessiner
        case = _base(carte, "image", largeur, 12, 3)
        case.update(entite=fond_cam, fond=fond or "", fond_camera=fond_cam, elements=textes, calques=calques)
        if fond and len(calques) == 1 and not textes:
            case.update(genre="radar", entite=calques[0]["entite"])
        return [case]
    if genre == "picture":
        fond = _fond_local(carte.get("image"))
        if not fond:
            return []
        return [{**_base(carte, "image", largeur, 12, 2), "entite": "", "fond": fond, "fond_camera": "",
                 "elements": [], "calques": []}]
    if genre in ("picture-entity", "picture-glance"):
        cam = _camera(carte.get("camera_image")) or _camera(entite)
        if cam:
            return [{**_base(carte, "camera", largeur, 12, 2), "entite": cam}]
        fond = _fond_local(carte.get("image"))
        if not fond:
            return []
        textes = [{"entite": entite, "x": 50.0, "y": 88.0, "attribut": "", "prefixe": "", "suffixe": ""}] if entite else []
        return [{**_base(carte, "image", largeur, 12, 2), "entite": "", "fond": fond, "fond_camera": "",
                 "elements": textes, "calques": []}]
    if genre == "iframe":
        url = str(carte.get("url") or "").strip()
        if not re.match(r"^https?://[^\s/]+", url) or len(url) > 500:
            return []  # seulement l'adresse absolue écrite dans la carte
        return [{**_base(carte, "iframe", largeur, 12, 3), "entite": "", "url": url}]
    if genre in CARTES_FRIGATE:
        cams = []
        for c in carte.get("cameras") or []:
            cam = _camera(c.get("camera_entity")) if isinstance(c, dict) else ""
            if cam:
                cams.append(cam)
        if not cams:
            return []
        case = _base(carte, "clips", largeur, 12, 2)
        return [{**case, "entite": cams[0], "cameras": cams[:8]}]
    if _camera(entite) and not genre.startswith("custom:"):
        return [{**_base(carte, "camera", largeur, 6, 2), "entite": entite}]
    return None


# --- Images servies par l'intégration ------------------------------------------------------------


def _cle(source: str) -> str:
    return hashlib.sha256(("vision-veille|" + source).encode()).hexdigest()[:24]


def media(source: str, pc_id: str) -> str:
    """L'adresse servie par l'intégration pour cette source, valable pour CET appareil seulement."""
    maintenant = time.time()
    if len(_medias) > MAX_CLES:
        for k in [k for k, v in _medias.items() if max(v["pcs"].values(), default=0) < maintenant]:
            _medias.pop(k, None)
    if source.startswith("camera:"):
        noter_camera(pc_id, source[len("camera:"):])
    cle = _cle(source)
    entree = _medias.setdefault(cle, {"source": source, "pcs": {}})
    entree["pcs"][pc_id] = maintenant + DUREE_CLE_S
    entree.setdefault("debut", {})[pc_id] = maintenant   # droit confirmé par ce relevé : 24 h de plus au plus
    return f"{URL_MEDIA}/{cle}"


def source_permise(cle: str, pc_id: str) -> str | None:
    entree = _medias.get(str(cle))
    maintenant = time.time()
    if entree is None or not pc_id or entree["pcs"].get(pc_id, 0) < maintenant:
        return None
    # Une page de veille reste ouverte longtemps (la passerelle garde sa page) : une clé encore valide et utilisée
    # par SON appareil se prolonge, jamais au-delà de 24 h après sa première émission (un retrait de carte finit
    # toujours par fermer l'accès, même si la page ne se reconnecte pas).
    debut = entree.setdefault("debut", {}).setdefault(pc_id, maintenant)
    entree["pcs"][pc_id] = max(entree["pcs"][pc_id], min(maintenant + DUREE_CLE_S, debut + DUREE_MAX_S))
    return entree["source"]


# Les caméras des cases typées, par appareil : seulement celles résolues pour CET appareil (tableaux qui le visent,
# cartes retenues), avec leur flux léger. Relecture sécurité : plus de liste blanche commune à tous les appareils
# (une caméra d'un tableau réservé aux Décideurs n'était pas à ouvrir depuis la télé d'un Demandeur).
_cameras_pc: dict[str, dict[str, float]] = {}


def noter_camera(pc_id: str, camera: str) -> None:
    if not pc_id or not _camera(camera):
        return
    fin = time.time() + DUREE_CLE_S
    cams = _cameras_pc.setdefault(pc_id, {})
    if len(cams) > MAX_CLES:
        cams.clear()
    cams[camera] = fin
    cams[camera + "_sub"] = fin


def camera_permise(pc_id: str, camera: str) -> bool:
    return bool(pc_id) and (_cameras_pc.get(pc_id) or {}).get(camera, 0) >= time.time()


# --- Résolution à chaque relevé ------------------------------------------------------------------


def _nom(hass: HomeAssistant, c: dict[str, Any], eid: str, defaut: str = "") -> str:
    if c.get("libelle"):
        return str(c["libelle"])
    etat = hass.states.get(eid) if eid else None
    return str(etat.attributes.get("friendly_name") or "") if etat is not None else defaut


def _iso_local(v: Any) -> str:
    d = dt_util.parse_datetime(str(v or "")) if v else None
    if d is None:
        return ""
    if d.tzinfo is None:
        d = d.replace(tzinfo=dt_util.get_default_time_zone())
    return dt_util.as_local(d).isoformat(timespec="seconds")


def _nombre(v: Any) -> float | None:
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _objet(objet: str, lang: str) -> str:
    from . import textes as catalogue

    return catalogue.t(f"veille.objet.{objet}", lang) if objet in OBJETS else objet.replace("_", " ").capitalize()


def _ciel(etat: str, lang: str) -> str:
    """Le ciel dans la langue de l'appareil (catalogue « meteo.<état> » de la 2.5, six langues) ; jamais l'anglais brut."""
    from . import textes as catalogue
    from .veille_tableaux import METEO

    return catalogue.t(f"meteo.{etat}", lang) if etat in METEO else ""


async def _previsions(hass: HomeAssistant, eid: str) -> list[dict[str, Any]]:
    connu = _cache_meteo.get(eid)
    if connu and time.time() < connu[0]:
        return connu[1]
    sortie: list[dict[str, Any]] = []
    duree = CACHE_METEO_S
    try:
        reponse = await hass.services.async_call("weather", "get_forecasts", {"type": "daily"},
                                                 target={"entity_id": eid}, blocking=True, return_response=True)
        for p in ((reponse or {}).get(eid) or {}).get("forecast") or []:
            sortie.append({"quand": _iso_local(p.get("datetime")), "etat": str(p.get("condition") or ""),
                           "max": _nombre(p.get("temperature")), "min": _nombre(p.get("templow")),
                           "pluie": _nombre(p.get("precipitation_probability"))})
            if len(sortie) >= MAX_PREVISIONS:
                break
    except Exception as err:  # noqa: BLE001 - sans prévisions, la météo du moment suffit
        _LOGGER.debug("Prévisions de %s : %s", eid, err)
        duree = CACHE_ECHEC_S
    _cache_meteo[eid] = (time.time() + duree, sortie)
    return sortie


async def _meteo(hass: HomeAssistant, c: dict[str, Any], lang: str) -> dict[str, Any] | None:
    eid = str(c.get("entite") or "")
    etat = hass.states.get(eid)
    if etat is None or etat.state in ("unavailable", "unknown"):
        return None
    a = etat.attributes
    temp = _nombre(a.get("temperature"))
    unite = str(a.get("temperature_unit") or "°")
    ciel = _ciel(str(etat.state), lang)
    nom = _nom(hass, c, eid)
    if nom.lower().startswith("forecast"):
        nom = nom[len("forecast"):].strip() or nom
    return {
        "entite": eid, "nom": nom, "etat": str(etat.state), "etat_texte": ciel, "temperature": temp, "unite": unite,
        "previsions": await _previsions(hass, eid),
        "texte": " · ".join(x for x in (ciel, f"{temp:.0f}{unite}" if temp is not None else "") if x),
    }


async def _evenements(hass: HomeAssistant, entites: list[str]) -> list[dict[str, Any]]:
    cle = tuple(sorted(entites))
    connu = _cache_agenda.get(cle)
    if connu and time.time() < connu[0]:
        return connu[1]
    maintenant = dt_util.now()
    sortie: list[dict[str, Any]] = []
    duree = CACHE_AGENDA_S
    try:
        reponse = await hass.services.async_call(
            "calendar", "get_events",
            {"start_date_time": maintenant.isoformat(), "end_date_time": (maintenant + datetime.timedelta(days=AGENDA_JOURS)).isoformat()},
            target={"entity_id": list(cle)}, blocking=True, return_response=True)
        for eid, bloc in (reponse or {}).items():
            etat = hass.states.get(eid)
            cal = str(etat.attributes.get("friendly_name") or "") if etat is not None else ""
            for ev in (bloc or {}).get("events") or []:
                debut, fin = str(ev.get("start") or ""), str(ev.get("end") or "")
                journee = len(debut) == 10
                sortie.append({
                    "debut": debut if journee else _iso_local(debut), "fin": fin if journee else _iso_local(fin),
                    "titre": str(ev.get("summary") or "")[:60], "journee": journee, "calendrier": cal[:40],
                })
    except Exception as err:  # noqa: BLE001 - un agenda muet : une case vide, pas d'erreur
        _LOGGER.debug("Agenda %s : %s", cle, err)
        duree = CACHE_ECHEC_S
    sortie.sort(key=lambda e: (e["debut"][:10], not e["journee"], e["debut"], e["titre"]))
    sortie = sortie[:MAX_EVENEMENTS]
    _cache_agenda[cle] = (time.time() + duree, sortie)
    return sortie


def _texte_etat(hass: HomeAssistant, eid: str, attribut: str, lang: str) -> str | None:
    from . import veille_tableaux as vt

    etat = hass.states.get(eid)
    if etat is None or vt.exclue(eid):
        return None
    if attribut:
        v = etat.attributes.get(attribut)
        return None if v is None else str(v)[:40]
    if etat.state in ("unavailable", "unknown"):
        return None
    date = vt.date_lisible(str(etat.state), lang)
    if date is not None:
        return date
    n = vt._nombre(etat.state)
    if n is not None:
        unite = vt.unite_locale(str(etat.attributes.get("unit_of_measurement") or ""), lang)
        texte = (str(int(round(n))) if abs(n - round(n)) < 0.05 else f"{n:.1f}")
        if lang == "fr":
            texte = texte.replace(".", ",")
        return f"{texte} {unite}".strip()
    return vt._libelle_etat(etat)[:40] or None


def _image(hass: HomeAssistant, c: dict[str, Any], pc_id: str, lang: str) -> dict[str, Any] | None:
    fond = "local:" + c["fond"] if c.get("fond") else ("camera:" + c["fond_camera"] if c.get("fond_camera") else "")
    if not fond:
        return None
    elements = []
    for el in c.get("elements") or []:
        t = _texte_etat(hass, str(el.get("entite") or ""), str(el.get("attribut") or ""), lang)
        if t is None:
            continue
        elements.append({"x": el["x"], "y": el["y"], "texte": f"{el.get('prefixe') or ''}{t}{el.get('suffixe') or ''}"[:60]})
    calques = [{"x": k["x"], "y": k["y"], "largeur": k.get("largeur") or 100.0, "entite": k["entite"],
                "url": media("camera:" + k["entite"], pc_id)}
               for k in c.get("calques") or [] if hass.states.get(k["entite"]) is not None]
    sortie = {"entite": str(c.get("entite") or ""), "nom": str(c.get("libelle") or ""), "url": media(fond, pc_id),
              "elements": elements[:MAX_ELEMENTS], "texte": " · ".join(e["texte"] for e in elements[:3])}
    if c.get("genre") == "radar":
        if not calques:
            return None
        sortie["calque"] = {"url": calques[0]["url"], "entite": calques[0]["entite"]}
        sortie["texte"] = sortie["nom"]
    else:
        sortie["calques"] = calques
    return sortie


def _camera_case(hass: HomeAssistant, c: dict[str, Any], pc_id: str) -> dict[str, Any] | None:
    eid = str(c.get("entite") or "")
    etat = hass.states.get(eid)
    if etat is None:
        return None
    direct = eid + "_sub" if hass.states.get(eid + "_sub") is not None else ""
    nom = _nom(hass, c, eid)
    return {"entite": eid, "nom": nom, "direct": direct, "url": media("camera:" + eid, pc_id), "texte": nom}


def frigate_present(hass: HomeAssistant) -> bool:
    return any(getattr(e.state, "value", str(e.state)) == "loaded" for e in hass.config_entries.async_entries("frigate"))


def _clips(hass: HomeAssistant, c: dict[str, Any], pc_id: str, lang: str) -> dict[str, Any] | None:
    """Derniers passages : la dernière image de chaque objet par caméra, que Frigate publie dans Home Assistant."""
    if not frigate_present(hass):
        return None
    from homeassistant.helpers import entity_registry as er

    reg = er.async_get(hass)
    cameras = {}
    for cam in c.get("cameras") or []:
        e = reg.async_get(cam)
        etat = hass.states.get(cam)
        if e is not None and e.device_id and etat is not None:
            cameras[e.device_id] = (cam, str(etat.attributes.get("friendly_name") or cam), str(etat.attributes.get("camera_name") or ""))
    liste = []
    for e in reg.entities.values():
        if e.domain != "image" or e.platform != "frigate" or e.device_id not in cameras:
            continue
        etat = hass.states.get(e.entity_id)
        quand = _iso_local(etat.state) if etat is not None and etat.state not in ("unknown", "unavailable") else ""
        if not quand:
            continue
        cam, nom_cam, nom_frigate = cameras[e.device_id]
        fin = str(e.unique_id or "").rsplit(":", 1)[-1]
        objet = fin[len(nom_frigate) + 1:] if nom_frigate and fin.startswith(nom_frigate + "_") else str(e.original_name or "").lower()
        liste.append({"camera": nom_cam, "entite": cam, "quand": quand, "objet": objet,
                      "objet_nom": _objet(objet, lang),
                      "vignette": "image:" + e.entity_id})
    liste.sort(key=lambda x: x["quand"], reverse=True)
    liste = liste[:MAX_CLIPS]
    for x in liste:
        x["vignette"] = media(x["vignette"], pc_id)
    from .veille_tableaux import date_lisible

    premier = liste[0] if liste else None
    texte = f"{premier['objet_nom']} · {premier['camera']} · {date_lisible(premier['quand'], lang)}" if premier else ""
    return {"entite": str(c.get("entite") or ""), "nom": str(c.get("libelle") or ""), "liste": liste, "texte": texte}


async def resoudre(hass: HomeAssistant, c: dict[str, Any], pc_id: str) -> dict[str, Any] | None:
    """Une case typée remplie pour cet appareil, ou None (rien à montrer : la case n'est pas envoyée)."""
    from .veille_tableaux import langue

    genre = c.get("genre")
    lang = langue(hass)
    sortie: dict[str, Any] | None
    if genre == "meteo":
        sortie = await _meteo(hass, c, lang)
    elif genre == "agenda":
        entites = [e for e in c.get("entites") or [] if hass.states.get(e) is not None]
        if not entites:
            return None
        evenements = await _evenements(hass, entites)
        prochain = evenements[0] if evenements else None
        texte = ""
        if prochain:
            from .veille_tableaux import date_lisible
            quand = prochain["debut"] if prochain["journee"] else (date_lisible(prochain["debut"], lang) or "")
            texte = f"{quand} {prochain['titre']}".strip()
        sortie = {"entite": entites[0], "entites": entites, "nom": _nom(hass, c, entites[0]),
                  "evenements": evenements, "texte": texte}
    elif genre in ("image", "radar"):
        sortie = _image(hass, c, pc_id, lang)
    elif genre == "camera":
        sortie = _camera_case(hass, c, pc_id)
    elif genre == "iframe":
        url = str(c.get("url") or "")
        sortie = {"entite": "", "url": url, "nom": str(c.get("libelle") or ""), "texte": str(c.get("libelle") or "")} if url else None
    elif genre == "clips":
        sortie = _clips(hass, c, pc_id, lang)
    else:
        return None
    if sortie is None:
        return None
    return {"genre": genre, "rendu": "texte", **sortie}


# --- La porte des images ---------------------------------------------------------------------------


def _lire_fichier(dossier: str, relatif: str) -> bytes | None:
    racine = os.path.realpath(dossier)
    chemin = os.path.realpath(os.path.join(racine, relatif))
    if not chemin.startswith(racine + os.sep) or not os.path.isfile(chemin) or os.path.getsize(chemin) > MAX_OCTETS:
        return None
    with open(chemin, "rb") as f:
        return f.read()


async def servir(hass: HomeAssistant, source: str) -> web.Response | None:
    genre, _, quoi = source.partition(":")
    if genre == "local":
        relatif = quoi[len("/local/"):]
        octets = await hass.async_add_executor_job(_lire_fichier, hass.config.path("www"), relatif)
        if octets is None:
            return None
        return web.Response(body=octets, content_type=EXTENSIONS.get(os.path.splitext(relatif)[1].lower(), "image/jpeg"),
                            headers={"Cache-Control": "max-age=600"})
    if genre == "camera":
        from homeassistant.components.camera import async_get_image

        image = await async_get_image(hass, quoi, timeout=10, width=960)
        return web.Response(body=image.content, content_type=image.content_type, headers={"Cache-Control": "no-store"})
    if genre == "image":
        composant = hass.data.get("image")
        entite = composant.get_entity(quoi) if composant is not None else None
        octets = await entite.async_image() if entite is not None else None
        if not octets:
            return None
        return web.Response(body=octets, content_type=str(getattr(entite, "content_type", "") or "image/jpeg"),
                            headers={"Cache-Control": "no-store"})
    return None


class PcParentalVeilleMediaView(HomeAssistantView):
    """Une image d'une case typée : seulement par la clé donnée à CET appareil par son relevé."""

    url = URL_MEDIA + "/{cle}"
    name = "api:pc_parental:veille_media"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def _repondre(self, cle: str, corps: dict[str, Any]) -> web.Response:
        from . import modules, veille_passerelle
        from .veille import _coordinateur

        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        moi = None
        if corps.get("id") and corps.get("secret"):
            moi = coord.store.by_secret(str(corps.get("id")), str(corps.get("secret")))
        elif corps.get("ticket"):
            moi = veille_passerelle.verifier(coord.store, str(corps.get("ticket")))
        if moi is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        if refus := modules.refus(self, coord.store, "veille"):
            return refus
        source = source_permise(cle, str(moi.get("id") or ""))
        if source is None:
            return self.json({"ok": False, "error": "media"}, status_code=404)
        try:
            reponse = await servir(self.hass, source)
        except Exception:  # noqa: BLE001 - une caméra muette ne casse pas l'écran
            _LOGGER.debug("Veille : média indisponible", exc_info=True)
            return self.json({"ok": False, "error": "indisponible"}, status_code=502)
        return reponse or self.json({"ok": False, "error": "media"}, status_code=404)

    async def post(self, request: web.Request, cle: str) -> web.Response:
        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        return await self._repondre(cle, corps if isinstance(corps, dict) else {})
