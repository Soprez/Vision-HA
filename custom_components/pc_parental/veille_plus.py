"""Le reste de l'écran de veille : réglages (thème, nuit, radio), école (Pronote),
agenda, pluie dans l'heure, demandes des enfants, chauffage et fioul, photos.

Tout est lu dans Home Assistant au moment de la demande ; rien n'est stocké
à part les réglages. Les photos viennent d'un dossier du disque de Home
Assistant (`/media/vision_photos`) : on y dépose des fichiers, c'est tout.
"""

from __future__ import annotations

import datetime
import os
import re
import time
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from .const import DOMAIN
from .coordinator import PcParentalCoordinator

# Des radios sans publicité : SomaFM et Radio Paradise vivent des dons, FIP est le service public.
RADIOS: list[tuple[str, str]] = [
    ("Silence", ""),
    ("SomaFM Fluid · lofi, hip-hop instrumental", "https://ice1.somafm.com/fluid-128-mp3"),
    ("SomaFM Groove Salad · ambient, downtempo", "https://ice1.somafm.com/groovesalad-128-mp3"),
    ("SomaFM Lush · électro douce, voix", "https://ice1.somafm.com/lush-128-mp3"),
    ("SomaFM Beat Blender · deep house", "https://ice1.somafm.com/beatblender-128-mp3"),
    ("SomaFM Drone Zone · ambiance", "https://ice1.somafm.com/dronezone-128-mp3"),
    ("SomaFM Secret Agent · lounge", "https://ice1.somafm.com/secretagent-128-mp3"),
    ("Radio Paradise Mellow", "https://stream.radioparadise.com/mellow-128"),
    ("Radio Paradise", "https://stream.radioparadise.com/mp3-128"),
    ("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3"),
    ("FIP Jazz", "https://icecast.radiofrance.fr/fipjazz-midfi.mp3"),
    ("FIP Électro", "https://icecast.radiofrance.fr/fipelectro-midfi.mp3"),
    ("FIP Groove", "https://icecast.radiofrance.fr/fipgroove-midfi.mp3"),
    ("FIP Pop", "https://icecast.radiofrance.fr/fippop-midfi.mp3"),
]
RADIO_PERSONNALISEE = "Personnalisée"
THEMES = ["Beige", "Sombre", "Bleu nuit", "Sauge", "Rose poudré"]
THEME_DEFAUT = "Beige"
NUIT_DEFAUT = "23:00-07:00"
PHOTOS_DOSSIERS = ("/media/vision_photos",)
PHOTOS_EXT = (".jpg", ".jpeg", ".png", ".webp")


def radio_nom(url: str) -> str:
    for nom, u in RADIOS:
        if u == url:
            return nom
    return RADIO_PERSONNALISEE if url else "Silence"


def _coordinateur(hass: HomeAssistant) -> PcParentalCoordinator | None:
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


def _jour_long(d: datetime.date) -> str:
    """« lundi 12 octobre », dans la langue de l'appareil (`textes.parler` dans les vues)."""
    from . import textes

    return textes.t("veille.jour_long", None, jour=textes.t(f"planner.jour_{d.weekday()}"), num=d.day,
                    mois=textes.t(f"format.mois_{d.month}"))


def reglages(coord: PcParentalCoordinator) -> dict[str, Any]:
    s = coord.store
    return {
        "theme": str(getattr(s, "veille_theme", "") or THEME_DEFAUT),
        "nuit": str(getattr(s, "veille_nuit", NUIT_DEFAUT) or ""),
        "musique": str(getattr(s, "veille_musique", "") or ""),
        "radios": [{"nom": n, "url": u} for n, u in RADIOS],
        "themes": THEMES,
        "tableaux": _tableaux_legers(coord),
    }


def _tableaux_legers(coord: PcParentalCoordinator) -> list[dict[str, Any]]:
    """La liste des tableaux pour les réglages de la télé : nom, actif, durée (sans les cases)."""
    from . import veille_tableaux as vt

    noms = {c: vt.nom_integre(c, coord) for c, _n, _d, _e in vt.INTEGRES}
    sortie = []
    for t in vt.configuration(coord):
        sortie.append({
            "code": t["code"], "id": t.get("id", ""),
            "nom": t["titre"] if t["code"] == "entites" else noms.get(t["code"], t["code"]),
            "actif": t["actif"], "duree": t["duree"],
        })
    try:
        from . import veille_dash
        sortie.extend(veille_dash.legers(coord.hass, coord))
    except Exception:  # noqa: BLE001
        pass
    return sortie


# --- École (Pronote) ---------------------------------------------------------


def _evaluation() -> str:
    from . import textes

    return textes.t("veille.evaluation")


def _joli_matiere(m: str) -> str:
    m = (m or "").strip()
    if not m:
        return ""
    bas = m.lower()
    remplacements = {
        "mathematiques": "Maths", "francais": "Français", "histoire-geographie": "Histoire-géo",
        "anglais lv1": "Anglais", "espagnol lv2": "Espagnol", "allemand lv2": "Allemand",
        "sciences vie & terre": "SVT", "physique-chimie": "Physique-chimie", "education musicale": "Musique",
        "arts plastiques": "Arts plastiques", "technologie": "Techno", "ed.physique & sport.": "EPS",
        "education physique et sportive": "EPS", "latin": "Latin",
    }
    if bas in remplacements:
        return remplacements[bas]
    return m[:1].upper() + m[1:].lower()


def ecole(hass: HomeAssistant) -> list[dict[str, Any]]:
    """Le prochain jour de classe de chaque enfant : horaires, cours, devoirs, contrôles."""
    sortie = []
    for etat in hass.states.async_all("sensor"):
        if not etat.entity_id.endswith("_next_day_s_timetable") or "pronote" not in etat.entity_id:
            continue
        base = etat.entity_id[: -len("_next_day_s_timetable")]
        attrs = etat.attributes
        cours_bruts = list(attrs.get("lessons") or [])
        if not cours_bruts:
            continue
        try:
            jour = dt_util.parse_datetime(str(cours_bruts[0].get("start_at"))).date()
        except Exception:  # noqa: BLE001
            continue
        cours = [
            {
                "debut": str(c.get("start_time") or ""),
                "fin": str(c.get("end_time") or ""),
                "matiere": _joli_matiere(str(c.get("lesson") or "")),
                "salle": str(c.get("classroom") or ""),
                "annule": bool(c.get("canceled")),
                "couleur": str(c.get("background_color") or ""),
            }
            for c in cours_bruts
        ]
        devoirs = []
        dev = hass.states.get(base + "_homework")
        for h in (dev.attributes.get("homework") if dev else None) or []:
            if str(h.get("date")) == jour.isoformat() and not h.get("done"):
                devoirs.append({"matiere": _joli_matiere(str(h.get("subject") or "")), "texte": str(h.get("short_description") or h.get("description") or "")[:110]})
        controles = []
        ev = hass.states.get(base + "_evaluations")
        for e in (ev.attributes.get("evaluations") if ev else None) or []:
            if str(e.get("date")) == jour.isoformat():
                controles.append({"matiere": _joli_matiere(str(e.get("subject") or "")),
                                  "nom": str(e.get("name") or e.get("description") or _evaluation())[:60]})
        # Les devoirs qui parlent d'une évaluation comptent aussi comme contrôle.
        for d in devoirs:
            if re.search(r"\b(eval|évaluation|contrôle|controle|interro|ds)\b", d["texte"].lower()) and not any(c["matiere"] == d["matiere"] for c in controles):
                controles.append({"matiere": d["matiere"], "nom": _evaluation()})
        debut = str(attrs.get("day_start_at") or "")[11:16] or (cours[0]["debut"] if cours else "")
        fin = str(attrs.get("day_end_at") or "")[11:16] or (cours[-1]["fin"] if cours else "")
        sortie.append({
            "prenom": str(attrs.get("nickname") or attrs.get("full_name") or "").split(" ")[0] or "?",
            "jour": _jour_long(jour),
            "date": jour.isoformat(),
            "demain": (jour - dt_util.now().date()).days == 1,
            "debut": debut,
            "fin": fin,
            "cours": cours[:10],
            "devoirs": devoirs[:6],
            "controles": controles[:4],
        })
    sortie.sort(key=lambda e: e["prenom"].lower())
    return sortie


# --- Agenda --------------------------------------------------------------------

async def agenda(hass: HomeAssistant) -> list[dict[str, Any]]:
    """Les rendez-vous d'aujourd'hui et de demain, tous calendriers sauf Pronote."""
    ids = [s.entity_id for s in hass.states.async_all("calendar") if "pronote" not in s.entity_id]
    if not ids:
        return []
    maintenant = dt_util.now()
    debut = maintenant.replace(hour=0, minute=0, second=0, microsecond=0)
    fin = debut + datetime.timedelta(days=2)
    try:
        reponse = await hass.services.async_call(
            "calendar", "get_events",
            {"entity_id": ids, "start_date_time": debut.isoformat(), "end_date_time": fin.isoformat()},
            blocking=True, return_response=True,
        )
    except Exception:  # noqa: BLE001
        return []
    sortie = []
    for eid, bloc in (reponse or {}).items():
        etat = hass.states.get(eid)
        nom_cal = str(etat.name) if etat is not None else eid
        for ev in (bloc or {}).get("events") or []:
            d = str(ev.get("start") or "")
            journee = len(d) == 10
            try:
                quand = dt_util.parse_datetime(d) if not journee else datetime.datetime.fromisoformat(d).replace(tzinfo=maintenant.tzinfo)
            except Exception:  # noqa: BLE001
                continue
            if quand is None:
                continue
            if quand.tzinfo is None:
                quand = quand.replace(tzinfo=maintenant.tzinfo)
            f = str(ev.get("end") or "")
            if not journee and f and dt_util.parse_datetime(f) and dt_util.parse_datetime(f) < maintenant:
                continue  # déjà passé
            sortie.append({
                "titre": str(ev.get("summary") or "")[:60],
                "calendrier": nom_cal,
                "ts": int(quand.timestamp()),
                "journee": journee,
                "demain": quand.date() > maintenant.date(),
                "heure": "" if journee else quand.astimezone(maintenant.tzinfo).strftime("%H:%M"),
                "lieu": str(ev.get("location") or "")[:40],
            })
    sortie.sort(key=lambda e: (e["ts"], e["titre"]))
    return sortie[:10]


# --- Pluie dans l'heure ----------------------------------------------------------

NIVEAUX_PLUIE = {"temps sec": 0, "pluie faible": 1, "pluie modérée": 2, "pluie forte": 3}


def pluie(hass: HomeAssistant) -> dict[str, Any] | None:
    for etat in hass.states.async_all("sensor"):
        if not etat.entity_id.endswith("_next_rain"):
            continue
        prevision = etat.attributes.get("1_hour_forecast") or {}
        if not isinstance(prevision, dict) or not prevision:
            continue
        points = []
        for cle, valeur in prevision.items():
            m = re.match(r"(\d+)", str(cle))
            if not m:
                continue
            points.append({"min": int(m.group(1)), "niveau": NIVEAUX_PLUIE.get(str(valeur).lower().strip(), 1 if "pluie" in str(valeur).lower() else 0), "texte": str(valeur)})
        points.sort(key=lambda p: p["min"])
        dans = None
        if etat.state not in ("unknown", "unavailable", "", None):
            try:
                quand = dt_util.parse_datetime(str(etat.state))
                if quand:
                    dans = max(0, int((quand - dt_util.utcnow()).total_seconds() // 60))
            except Exception:  # noqa: BLE001
                dans = None
        return {"points": points, "dans": dans, "pluie": any(p["niveau"] > 0 for p in points)}
    return None


# --- Demandes des enfants ------------------------------------------------------------

def demandes(hass: HomeAssistant, coord: PcParentalCoordinator) -> list[dict[str, Any]]:
    """1.97.8 : l'accueil d'un décideur lit la même source que la carte et l'appli (accès, temps, déverrouillage)."""
    from . import parent

    return [{
        "prenom": str(d.get("prenom") or ""), "libelle": str(d.get("libelle") or d.get("cle") or "")[:60],
        "cle": str(d.get("cle") or "")[:120], "nom": str(d.get("nom") or "")[:120], "genre": str(d.get("genre") or ""),
        "ts": int(float(d.get("ts") or 0)), "pc": str(d.get("pc") or ""), "id": str(d.get("id") or ""),
        "minutes": int(d.get("minutes") or 0) if str(d.get("minutes") or "0").isdigit() or isinstance(d.get("minutes"), int) else 0,
    } for d in parent.demandes_attente(hass, coord)][:4]


# --- Chauffage et fioul ------------------------------------------------------------

def _premier(hass: HomeAssistant, domaine: str, *motifs: str) -> Any:
    for s in hass.states.async_all(domaine):
        if all(m in s.entity_id for m in motifs) and s.state not in ("unavailable", "unknown"):
            return s
    return None


def _nombre(etat: Any) -> float | None:
    """Un nombre à partir d'un état Home Assistant, ou d'une valeur brute."""
    if etat is None:
        return None
    brut = getattr(etat, "state", etat)
    try:
        return float(str(brut).replace(",", "."))
    except (ValueError, TypeError):
        return None


_cache_fioul: dict[str, Any] = {"quand": 0.0, "valeur": []}


async def _fioul_journalier(hass: HomeAssistant, entite: str, jours: int = 14) -> list[dict[str, Any]]:
    """Litres par jour, à partir du compteur cumulé (dernière valeur de chaque journée)."""
    if time.time() - _cache_fioul["quand"] < 3600 and _cache_fioul.get("entite") == entite:
        return _cache_fioul["valeur"]
    from homeassistant.components.recorder import get_instance, history

    fin = dt_util.now()
    debut = (fin - datetime.timedelta(days=jours + 1)).replace(hour=0, minute=0, second=0, microsecond=0)

    def _lire():
        return history.state_changes_during_period(hass, debut, fin, entite, include_start_time_state=True, no_attributes=True)

    try:
        brut = await get_instance(hass).async_add_executor_job(_lire)
    except Exception:  # noqa: BLE001
        return []
    par_jour: dict[datetime.date, float] = {}
    for s in brut.get(entite) or []:
        try:
            v = float(s.state)
        except (ValueError, TypeError):
            continue
        par_jour[dt_util.as_local(s.last_changed).date()] = v
    jours_tries = sorted(par_jour)
    sortie = []
    for i in range(1, len(jours_tries)):
        d = jours_tries[i]
        delta = par_jour[d] - par_jour[jours_tries[i - 1]]
        if 0 <= delta < 500:
            sortie.append({"jour": d.isoformat(), "litres": round(delta, 1)})
    sortie = sortie[-jours:]
    _cache_fioul.update({"quand": time.time(), "valeur": sortie, "entite": entite})
    return sortie


async def chauffage(hass: HomeAssistant, coord: PcParentalCoordinator) -> dict[str, Any] | None:
    clim = _premier(hass, "climate", "confort") or _premier(hass, "climate")
    bruleur = _premier(hass, "sensor", "bruleur")
    mensuel = _premier(hass, "sensor", "fioul_mensuel")
    saison = _premier(hass, "sensor", "fioul_saison")
    cumul = _premier(hass, "sensor", "consommation_fioul_estimee_litres")
    if clim is None and mensuel is None and cumul is None:
        return None
    courbe = list(getattr(coord.store, "veille_courbe", []) or [])
    dehors = hass.states.get(courbe[0].split("|")[0]) if courbe else None
    dedans = hass.states.get(courbe[1].split("|")[0]) if len(courbe) > 1 else None
    # Sans courbe choisie, « dehors » vient de la météo.
    dehors_val = _nombre(dehors) if dehors else None
    if dehors_val is None:
        for m in hass.states.async_all("weather"):
            dehors_val = _nombre(m.attributes.get("temperature"))
            if dehors_val is not None:
                break
    return {
        "consigne": (clim.attributes.get("temperature") if clim else None),
        "mode": (str(clim.state) if clim else ""),
        "action": (str(clim.attributes.get("hvac_action") or "") if clim else ""),
        "dedans": _nombre(dedans) if dedans else (clim.attributes.get("current_temperature") if clim else None),
        "dehors": dehors_val,
        "bruleur": str(bruleur.state) if bruleur else "",
        "bruleur_on": bool(bruleur and str(bruleur.state).lower() in ("on", "allumé", "allume", "marche", "true")),
        "mois": _nombre(mensuel),
        "saison": _nombre(saison),
        "jours": await _fioul_journalier(hass, cumul.entity_id) if cumul else [],
    }


# --- Photos ------------------------------------------------------------------------

def _dossier_photos(hass: HomeAssistant) -> str | None:
    for d in PHOTOS_DOSSIERS + (hass.config.path("www", "vision_photos"),):
        if os.path.isdir(d):
            return d
    return None


_cache_photos: dict[str, Any] = {"quand": 0.0, "liste": []}


async def photos(hass: HomeAssistant) -> list[str]:
    if time.time() - _cache_photos["quand"] < 300:
        return _cache_photos["liste"]

    def _lister() -> list[str]:
        # 2.5.12.14.2 (revue de HACS) : le dossier se cherche sur le disque, dans l'executor.
        dossier = _dossier_photos(hass)
        if not dossier:
            return []
        noms = [n for n in os.listdir(dossier) if n.lower().endswith(PHOTOS_EXT) and not n.startswith(".")]
        noms.sort()
        return noms[:200]

    liste = await hass.async_add_executor_job(_lister)
    _cache_photos.update({"quand": time.time(), "liste": liste})
    return liste


class _VueVeille(HomeAssistantView):
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def _corps(self, request: web.Request) -> tuple[dict[str, Any] | None, web.Response | None, PcParentalCoordinator | None, Any]:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return None, self.json({"ok": False, "error": "json"}, status_code=400), None, None
        coord = _coordinateur(self.hass)
        if coord is None:
            return None, self.json({"ok": False, "error": "loading"}, status_code=503), None, None
        moi = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if moi is None:
            return None, self.json({"ok": False, "error": "auth"}, status_code=401), None, None
        from . import textes

        textes.parler(textes.pour(self.hass, moi))
        from . import modules

        if refus := modules.refus(self, coord.store, "veille"):  # 1.82.0
            return None, refus, None, None
        return corps, None, coord, moi


class PcParentalVeillePhotoView(_VueVeille):
    """Une photo du dossier, par son nom (et rien d'autre : pas de chemin)."""

    url = "/api/pc_parental/veille/photo"
    name = "api:pc_parental:veille_photo"

    async def post(self, request: web.Request) -> web.Response:
        corps, erreur, _coord, _moi = await self._corps(request)
        if erreur:
            return erreur
        nom = os.path.basename(str(corps.get("nom") or ""))
        liste = await photos(self.hass)
        dossier = await self.hass.async_add_executor_job(_dossier_photos, self.hass)
        if not dossier or nom not in liste:
            return self.json({"ok": False, "error": "photo"}, status_code=404)
        chemin = os.path.join(dossier, nom)
        octets = await self.hass.async_add_executor_job(lambda: open(chemin, "rb").read())
        genre = "image/png" if nom.lower().endswith(".png") else "image/webp" if nom.lower().endswith(".webp") else "image/jpeg"
        return web.Response(body=octets, content_type=genre, headers={"Cache-Control": "max-age=86400"})


class PcParentalVeilleReglagesView(_VueVeille):
    """Les réglages que la télé peut changer elle-même : radio, thème, nuit."""

    url = "/api/pc_parental/veille/reglages"
    name = "api:pc_parental:veille_reglages"

    async def post(self, request: web.Request) -> web.Response:
        corps, erreur, coord, moi = await self._corps(request)
        if erreur:
            return erreur
        from .veille import est_parent

        # Changer les réglages de la veille : l'appareil d'un parent, pas celui d'un enfant.
        if not est_parent(self.hass, coord, moi):
            return self.json({"ok": False, "error": "parent"}, status_code=403)
        from .entity import appliquer

        musique = corps.get("musique")
        theme = corps.get("theme")
        nuit = corps.get("nuit")
        if musique is not None and str(musique) and not str(musique).startswith(("http://", "https://")):
            return self.json({"ok": False, "error": "musique"}, status_code=400)
        if theme is not None and str(theme) not in THEMES:
            return self.json({"ok": False, "error": "theme"}, status_code=400)
        if nuit is not None and str(nuit) and not re.fullmatch(r"\d\d:\d\d-\d\d:\d\d", str(nuit)):
            return self.json({"ok": False, "error": "nuit"}, status_code=400)

        # Depuis la télé : afficher ou non un tableau, et sa durée. L'ordre et le contenu restent dans Home Assistant.
        maj_tableaux = corps.get("tableaux")
        if maj_tableaux is not None and not isinstance(maj_tableaux, list):
            return self.json({"ok": False, "error": "tableaux"}, status_code=400)

        def _faire() -> None:
            if musique is not None:
                coord.store.veille_musique = str(musique).strip()
            if theme is not None:
                coord.store.veille_theme = str(theme)
            if nuit is not None:
                coord.store.veille_nuit = str(nuit)
            if maj_tableaux is not None:
                from . import veille_tableaux as vt

                config = vt.configuration(coord)
                for m in maj_tableaux:
                    if not isinstance(m, dict):
                        continue
                    for t in config:
                        meme = (t["code"] == m.get("code")) and (t["code"] != "entites" or t.get("id") == m.get("id"))
                        if not meme:
                            continue
                        if "actif" in m:
                            t["actif"] = bool(m["actif"])
                        if "duree" in m:
                            try:
                                t["duree"] = max(vt.DUREE_MIN, min(vt.DUREE_MAX, int(m["duree"])))
                            except (TypeError, ValueError):
                                pass
                coord.store.veille_tableaux = config

        await appliquer(coord, _faire)
        return self.json(dict(ok=True, **reglages(coord)))


# --- Batteries des appareils ----------------------------------------------------------

_cache_batteries: dict[str, Any] = {"quand": 0.0, "valeur": []}


def _nom_appareil(hass: HomeAssistant, etat) -> str:
    """Le nom de l'appareil (téléphone, vanne), sans « Battery level » ni « Batterie »."""
    nom = str(etat.attributes.get("friendly_name") or etat.entity_id)
    try:
        from homeassistant.helpers import device_registry as dr, entity_registry as er

        ent = er.async_get(hass).async_get(etat.entity_id)
        if ent and ent.device_id:
            dev = dr.async_get(hass).async_get(ent.device_id)
            if dev and (dev.name_by_user or dev.name) and not re.match(r"^_?TZ", dev.name or ""):
                nom = dev.name_by_user or dev.name
    except Exception:  # noqa: BLE001
        pass
    nom = re.sub(r"(?i)\b(battery level|battery|batterie|niveau de batterie)\b", "", nom).strip(" -:·")
    return nom or etat.entity_id


async def _pentes_batteries(hass: HomeAssistant, entites: list[str], heures: int = 3) -> dict[str, float]:
    """Pour chaque batterie, la variation en points par heure sur les dernières heures (négatif = se vide)."""
    from homeassistant.components.recorder import get_instance, history

    fin = dt_util.now()
    debut = fin - datetime.timedelta(hours=heures)

    def _lire():
        return {e: history.state_changes_during_period(hass, debut, fin, e, include_start_time_state=True, no_attributes=True).get(e, []) for e in entites}

    try:
        brut = await get_instance(hass).async_add_executor_job(_lire)
    except Exception:  # noqa: BLE001
        return {}
    pentes: dict[str, float] = {}
    for eid, etats in (brut or {}).items():
        points = []
        for e in etats:
            try:
                points.append((e.last_changed, float(e.state)))
            except (ValueError, TypeError):
                continue
        if len(points) < 2:
            continue
        (t0, v0), (t1, v1) = points[0], points[-1]
        dh = (t1 - t0).total_seconds() / 3600
        if dh >= 0.5:
            pentes[eid] = round((v1 - v0) / dh, 1)
    return pentes


def _appli_compagnon(hass: HomeAssistant, entity_id: str) -> bool:
    try:
        from homeassistant.helpers import entity_registry as er

        ent = er.async_get(hass).async_get(entity_id)
    except Exception:  # noqa: BLE001
        return False
    return ent is not None and ent.platform == "mobile_app"


async def batteries(hass: HomeAssistant) -> list[dict[str, Any]]:
    """Les batteries de la maison : niveau, en charge ou non, et le rythme (points par heure) pour
    estimer l'autonomie. Téléphones (appli compagnon) et appareils Zigbee confondus."""
    # Deux minutes de cache, et jamais d'une liste vide (au démarrage, les capteurs ne sont pas tous là).
    if _cache_batteries["valeur"] and time.time() - _cache_batteries["quand"] < 120:
        return _cache_batteries["valeur"]
    sortie = []
    entites = []
    for s in hass.states.async_all("sensor"):
        if str(s.attributes.get("device_class") or "") != "battery":
            continue
        niveau = _nombre(s.state)
        if niveau is None:
            continue
        entites.append(s.entity_id)
        prefixe = re.sub(r"_(battery_level|battery|batterie|niveau_de_batterie)$", "", s.entity_id.split(".", 1)[1])
        etat_charge = hass.states.get(f"sensor.{prefixe}_battery_state")
        charge = None
        if etat_charge is not None:
            charge = str(etat_charge.state).lower() in ("charging", "full", "en charge", "pleine")
        else:
            b = hass.states.get(f"binary_sensor.{prefixe}_is_charging") or hass.states.get(f"binary_sensor.{prefixe}_charging")
            if b is not None:
                charge = str(b.state) == "on"
        # Un téléphone ou une tablette : une entité de l'appli compagnon (mobile_app), quel que soit son nom.
        telephone = _appli_compagnon(hass, s.entity_id) or "_battery_level" in s.entity_id or etat_charge is not None
        try:
            nom = _nom_appareil(hass, s)
        except Exception:  # noqa: BLE001
            nom = s.entity_id
        sortie.append({
            "id": s.entity_id,
            "nom": nom,
            "niveau": int(round(niveau)),
            "charge": charge,
            "telephone": bool(telephone),
            "pente": None,
            "vu": dt_util.as_local(s.last_updated).isoformat(timespec="minutes"),
        })
    try:
        pentes = await _pentes_batteries(hass, entites) if entites else {}
    except Exception:  # noqa: BLE001
        pentes = {}
    for b in sortie:
        b["pente"] = pentes.get(b["id"])
    # Les plus faibles d'abord, puis les téléphones, puis le reste.
    sortie.sort(key=lambda b: (b["niveau"] > 20, not b["telephone"], b["niveau"]))
    _cache_batteries.update({"quand": time.time(), "valeur": sortie})
    return sortie
