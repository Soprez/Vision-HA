"""Les tableaux de l'écran de veille, composés depuis Home Assistant.

Un tableau est soit *intégré* (horloge, météo, caméras… dessinés par l'appli)
soit *composé* : un titre et des cases, chacune une entité Home Assistant avec
son rendu (valeur, jauge, courbe, état, texte). Chaque tableau dit à qui il
s'adresse : quels écrans (télé, téléphone) et quels publics (affichage commun,
parents, enfants, ou des personnes précises). L'appli reçoit la liste déjà
filtrée et résolue pour l'appareil qui demande.
"""
from __future__ import annotations

import datetime
import re
import time
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from . import modules
from .const import DOMAIN
from . import lieu, roles, textes
from .coordinator import PcParentalCoordinator

# Les tableaux que l'appli sait dessiner elle-même, dans l'ordre d'un tour : (code, durée). Leur nom et leur
# explication sont dans le catalogue serveur (« tab.<code>.nom », « tab.<code>.expl ») ; le tableau « maison »
# (code historique) porte le nom du lieu.
_INTEGRES: list[tuple[str, int]] = [
    ("horloge", 15), ("vigilance", 15), ("meteo", 15), ("courbe", 40), ("maison", 15), ("tuiles", 15),
    ("cameras", 30), ("esports", 22), ("courses", 22), ("avenir", 22), ("ecole", 22), ("agenda", 22),
    ("chauffage", 22), ("batteries", 22), ("photos", 30),
]


def nom_integre(code: str, coord: Any = None, langue: str | None = None) -> str:
    """Le nom d'un tableau intégré dans une langue (sans langue : celle de la requête, sinon de HA)."""
    if code == "maison":
        return lieu.nom(coord.store, langue) if coord is not None else lieu.defaut(langue)
    return textes.t(f"tab.{code}.nom", langue)


# (code, nom, durée, explication) en français : la forme d'avant, pour qui la lit encore.
INTEGRES: list[tuple[str, str, int, str]] = [
    (c, lieu.DEFAUT if c == "maison" else textes.t(f"tab.{c}.nom", "fr"), d, textes.t(f"tab.{c}.expl", "fr"))
    for c, d in _INTEGRES
]
CODES_INTEGRES = {c for c, _n, _d, _e in INTEGRES}
ECRANS = ("tele", "telephone")
PUBLICS = ("affichage", "parent", "enfant")
RENDUS = ("auto", "valeur", "jauge", "courbe", "etat", "texte")
DUREE_MIN, DUREE_MAX = 5, 120
MAX_TABLEAUX, MAX_CASES = 30, 8

_cache_series: dict[str, tuple[float, list[list[float]]]] = {}


def _coordinateur(hass: HomeAssistant) -> PcParentalCoordinator | None:
    for valeur in (hass.data.get(DOMAIN) or {}).values():
        if isinstance(valeur, PcParentalCoordinator):
            return valeur
    return None


# --- Configuration ----------------------------------------------------------------------

def _entier(v: Any, defaut: int, mini: int, maxi: int) -> int:
    try:
        return max(mini, min(maxi, int(v)))
    except (TypeError, ValueError):
        return defaut


def _liste_de(v: Any, admis: tuple[str, ...]) -> list[str]:
    if not isinstance(v, (list, tuple)):
        return []
    return [str(x) for x in v if str(x) in admis]


def _nettoyer(t: dict[str, Any], defaut_duree: int = 22) -> dict[str, Any] | None:
    """Un tableau tel qu'il est enregistré : rien d'autre que ce qu'on connaît."""
    if not isinstance(t, dict):
        return None
    code = str(t.get("code") or "").strip()
    if code in CODES_INTEGRES:
        duree_defaut = next(d for c, _n, d, _e in INTEGRES if c == code)
        return {
            "code": code,
            "actif": bool(t.get("actif", True)),
            "duree": _entier(t.get("duree"), duree_defaut, DUREE_MIN, DUREE_MAX),
            "ecrans": _liste_de(t.get("ecrans"), ECRANS) or list(ECRANS),
            "publics": _liste_de(t.get("publics"), PUBLICS) or list(PUBLICS),
            "personnes": [str(p) for p in (t.get("personnes") or []) if str(p).startswith("person.")][:12],
        }
    # Un tableau composé : un identifiant stable, un titre, des cases.
    ident = re.sub(r"[^a-z0-9_-]", "", str(t.get("id") or "").lower())[:32] or f"t{int(time.time() * 1000) % 10_000_000}"
    cases = []
    for c in (t.get("cases") or [])[:MAX_CASES]:
        if not isinstance(c, dict):
            continue
        entite = str(c.get("entite") or "").strip()
        if "." not in entite:
            continue
        cases.append({
            "entite": entite,
            "libelle": str(c.get("libelle") or "").strip()[:40],
            "rendu": str(c.get("rendu") or "auto") if str(c.get("rendu") or "auto") in RENDUS else "auto",
        })
    return {
        "code": "entites",
        "id": ident,
        "titre": str(t.get("titre") or "Tableau").strip()[:40] or "Tableau",
        "actif": bool(t.get("actif", True)),
        "duree": _entier(t.get("duree"), defaut_duree, DUREE_MIN, DUREE_MAX),
        "ecrans": _liste_de(t.get("ecrans"), ECRANS) or list(ECRANS),
        "publics": _liste_de(t.get("publics"), PUBLICS) or list(PUBLICS),
        "personnes": [str(p) for p in (t.get("personnes") or []) if str(p).startswith("person.")][:12],
        "cases": cases,
    }


def configuration(coord: PcParentalCoordinator) -> list[dict[str, Any]]:
    """La liste complète et ordonnée : ce qui est enregistré, puis les intégrés manquants."""
    brut = list(getattr(coord.store, "veille_tableaux", []) or [])
    sortie: list[dict[str, Any]] = []
    vus: set[str] = set()
    for t in brut[:MAX_TABLEAUX]:
        n = _nettoyer(t)
        if n is None:
            continue
        cle = n["code"] if n["code"] != "entites" else "entites:" + n["id"]
        if cle in vus:
            continue
        vus.add(cle)
        sortie.append(n)
    for code, _nom, duree, _expl in INTEGRES:
        if code not in vus:
            sortie.append(_nettoyer({"code": code, "duree": duree}))
    # Des anciens tableaux il ne reste que l'horloge.
    return [t for t in sortie if t and t["code"] == "horloge"]


def enregistrer(coord: PcParentalCoordinator, liste: Any) -> list[dict[str, Any]]:
    if not isinstance(liste, list):
        raise ValueError("liste")
    propres = [n for n in (_nettoyer(t) for t in liste[:MAX_TABLEAUX]) if n is not None]
    coord.store.veille_tableaux = propres
    return configuration(coord)


def catalogue(hass: HomeAssistant) -> dict[str, Any]:
    """Ce dont l'éditeur a besoin : les intégrés, les publics, les personnes de la maison."""
    personnes = [{"id": s.entity_id, "nom": s.name} for s in hass.states.async_all("person")]
    personnes.sort(key=lambda p: p["nom"].lower())
    coord = _coordinateur(hass)
    # Le tableau « maison » porte le nom du lieu (« Maison », « École »…). Textes : langue de Home Assistant.
    lg = textes.ha(hass)
    return {
        "integres": [{"code": c, "nom": nom_integre(c, coord, lg), "duree": d, "explication": textes.t(f"tab.{c}.expl", lg)}
                     for c, d in _INTEGRES],
        "ecrans": [{"code": e, "nom": textes.t(f"tab.ecran_{e}", lg)} for e in ECRANS],
        "publics": [{"code": p, "nom": textes.t(f"tab.public_{p}", lg)} for p in PUBLICS],
        "rendus": [{"code": r, "nom": textes.t(f"tab.rendu_{r}", lg)} for r in RENDUS],
        "personnes": personnes,
        "duree_min": DUREE_MIN, "duree_max": DUREE_MAX,
    }


# --- Qui demande -------------------------------------------------------------------

def profil(hass: HomeAssistant, coord: PcParentalCoordinator, pc: dict[str, Any] | None) -> dict[str, str]:
    """Le public et la personne de l'appareil qui demande : parent (étiquette Parents),
    enfant (une personne), affichage (la télé du salon : sans personne, ou une personne choisie comme écran
    commun dans les réglages généraux ; rien de choisi : celle qui s'appelle « Affichage »)."""
    if pc is None:
        return {"public": "affichage", "personne": ""}
    personne = str(pc.get("person") or "")
    etat = hass.states.get(personne) if personne else None
    prenom = str(etat.name) if etat is not None and etat.name else ""
    try:
        from . import labels

        parents = roles.avec(coord.store, "gerer")
    except Exception:  # noqa: BLE001
        parents = []
    if pc.get("id") in parents:
        return {"public": "parent", "personne": personne}
    if personne and not ecran_commun(coord.store, personne, prenom):
        return {"public": "enfant", "personne": personne}
    return {"public": "affichage", "personne": personne}


def ecran_commun(store, personne: str, prenom: str) -> bool:
    """Vrai si cette personne de Home Assistant tient lieu d'écran commun (une télé de salon, un affichage)."""
    if hasattr(store, "est_ecran_commun"):
        return store.est_ecran_commun(personne)
    return prenom.strip().lower() == "affichage"


def _vise(t: dict[str, Any], ecran: str, qui: dict[str, str]) -> bool:
    if ecran not in t["ecrans"]:
        return False
    if t["personnes"]:
        return qui["personne"] in t["personnes"]
    return qui["public"] in t["publics"]


# --- Résolution des cases -------------------------------------------------------------

def _nombre(v: Any) -> float | None:
    try:
        return float(str(v).replace(",", "."))
    except (TypeError, ValueError):
        return None


def _rendu_auto(etat) -> str:
    domaine = etat.entity_id.split(".", 1)[0]
    a = etat.attributes
    if domaine in ("binary_sensor", "switch", "light", "lock", "cover", "input_boolean", "fan", "person", "device_tracker", "alarm_control_panel"):
        return "etat"
    if domaine == "climate":
        return "valeur"
    valeur = _nombre(etat.state)
    if valeur is None:
        return "texte"
    unite = str(a.get("unit_of_measurement") or "")
    if unite == "%" or str(a.get("device_class") or "") in ("battery", "humidity", "moisture"):
        return "jauge"
    if str(a.get("state_class") or "") in ("measurement", "total_increasing") or str(a.get("device_class") or "") in ("temperature", "power", "energy", "pressure", "illuminance"):
        return "valeur"
    return "valeur"


def _on(etat) -> bool | None:
    domaine = etat.entity_id.split(".", 1)[0]
    s = str(etat.state).lower()
    if domaine == "cover":
        return s == "open"
    if domaine == "lock":
        return s == "unlocked"
    if domaine in ("person", "device_tracker"):
        return s == "home"
    if domaine == "alarm_control_panel":
        return s != "disarmed"
    if s in ("on", "open", "home", "true", "playing", "heat", "cool", "unlocked", "detected", "wet", "motion"):
        return True
    if s in ("off", "closed", "not_home", "false", "idle", "paused", "standby", "locked", "clear", "dry", "unavailable", "unknown"):
        return False
    return None


def _lib(cle: str) -> str:
    """Un libellé d'état (« lib.<clé> ») dans la langue de l'appareil ; « ok » et « auto » restent tels quels."""
    return cle if cle in ("ok", "auto") else textes.t(f"lib.{cle}")


def _libelle_etat(etat) -> str:
    """L'état en mots courants, pour les cases « état » et « texte » (langue de l'appareil)."""
    domaine = etat.entity_id.split(".", 1)[0]
    s = str(etat.state)
    dc = str(etat.attributes.get("device_class") or "")
    bas = s.lower()
    if bas in ("unavailable",):
        return _lib("indisponible")
    if bas in ("unknown",):
        return _lib("inconnu")
    choix: dict[str, str] = {}
    if domaine == "cover":
        choix = {"open": "ouvert", "closed": "ferme", "opening": "s_ouvre", "closing": "se_ferme"}
    elif domaine == "lock":
        choix = {"locked": "verrouille", "unlocked": "deverrouille"}
    elif domaine in ("person", "device_tracker"):
        choix = {"home": "sur_place", "not_home": "absent"}
    elif domaine == "binary_sensor":
        ouverts = {"door": ("ouverte", "fermee"), "window": ("ouverte", "fermee"), "garage_door": ("ouverte", "fermee"), "opening": ("ouvert", "ferme"),
                   "motion": ("mouvement", "calme"), "occupancy": ("occupe", "libre"), "presence": ("present", "absent"), "moisture": ("humide", "sec"),
                   "smoke": ("fumee", "rien"), "connectivity": ("connecte", "deconnecte"), "power": ("alimente", "coupe"), "battery": ("faible", "ok"),
                   "lock": ("deverrouille", "verrouille"), "problem": ("probleme", "ok"), "running": ("en_marche", "arrete"), "plug": ("branche", "debranche")}
        oui, non = ouverts.get(dc, ("actif", "inactif"))
        choix = {"on": oui, "off": non}
    elif domaine in ("switch", "light", "input_boolean", "fan"):
        choix = {"on": "allume", "off": "eteint"}
    elif domaine == "climate":
        choix = {"heat": "chauffe", "off": "arret", "auto": "auto", "cool": "refroidit"}
    elif domaine == "media_player":
        choix = {"playing": "en_lecture", "paused": "en_pause", "idle": "au_repos", "off": "eteint", "standby": "en_veille", "on": "allume"}
    elif domaine == "weather":
        return textes.t(f"meteo.{bas}") if bas in METEO else ""
    return _lib(choix[bas]) if bas in choix else s


# Les états météo de Home Assistant (jamais l'anglais brut sur une veille) : catalogue serveur, « meteo.<état> ».
METEO = frozenset({"sunny", "clear-night", "partlycloudy", "cloudy", "rainy", "pouring", "fog", "snowy", "snowy-rainy",
                   "lightning", "lightning-rainy", "windy", "windy-variant", "hail", "exceptional"})


def _nom_lisible(etat, libelle: str) -> str:
    """Le nom montré : le libellé de la carte, sinon le nom convivial ; jamais l'identifiant technique.
    « Forecast Maison » (nom par défaut de la météo) devient « Météo Maison »."""
    nom = libelle or str(etat.attributes.get("friendly_name") or "")
    if etat.entity_id.startswith("weather.") and nom.lower().startswith("forecast"):
        nom = textes.t("tab.meteo_de", None, nom=nom[len("forecast"):].strip()).strip()
    return nom


def _bornes(etat, rendu: str) -> tuple[float, float]:
    a = etat.attributes
    mini = _nombre(a.get("min")); maxi = _nombre(a.get("max"))
    if mini is not None and maxi is not None and maxi > mini:
        return mini, maxi
    unite = str(a.get("unit_of_measurement") or "")
    if unite == "%":
        return 0.0, 100.0
    dc = str(a.get("device_class") or "")
    if dc == "temperature":
        return -10.0, 40.0
    return 0.0, max(1.0, (_nombre(etat.state) or 1.0) * 1.5)


async def _serie(hass: HomeAssistant, eid: str, heures: int = 24) -> list[list[float]]:
    """Une valeur par quart d'heure sur la période, [horodatage, valeur] ; dix minutes de cache."""
    cle = f"{eid}|{heures}"
    cache = _cache_series.get(cle)
    if cache and time.time() - cache[0] < 600:
        return cache[1]
    from homeassistant.components.recorder import get_instance, history

    fin = dt_util.now()
    debut = fin - datetime.timedelta(hours=heures)

    def _lire():
        return history.state_changes_during_period(hass, debut, fin, eid, no_attributes=True, include_start_time_state=True).get(eid, [])

    try:
        etats = await get_instance(hass).async_add_executor_job(_lire)
    except Exception:  # noqa: BLE001
        return []
    points = []
    for e in etats:
        v = _nombre(e.state)
        if v is not None:
            points.append((e.last_changed.timestamp(), v))
    if not points:
        return []
    pas = 15 * 60
    sortie: list[list[float]] = []
    t = debut.timestamp()
    k = 0
    courant = points[0][1]
    while t <= fin.timestamp():
        while k < len(points) and points[k][0] <= t:
            courant = points[k][1]
            k += 1
        sortie.append([round(t), courant])
        t += pas
    _cache_series[cle] = (time.time(), sortie)
    return sortie


async def _case(hass: HomeAssistant, c: dict[str, Any]) -> dict[str, Any]:
    etat = hass.states.get(c["entite"])
    if etat is None:
        return {"entite": c["entite"], "nom": c.get("libelle") or c["entite"], "rendu": "texte", "texte": "introuvable", "absent": True}
    rendu = c.get("rendu") or "auto"
    if rendu == "auto":
        rendu = _rendu_auto(etat)
    a = etat.attributes
    nom = _nom_lisible(etat, str(c.get("libelle") or ""))
    sortie: dict[str, Any] = {
        "entite": etat.entity_id,
        "nom": nom,
        "rendu": rendu,
        "valeur": _nombre(etat.state),
        "unite": str(a.get("unit_of_measurement") or ""),
        "texte": _libelle_etat(etat),
        "on": _on(etat),
        "classe": str(a.get("device_class") or ""),
        "depuis": dt_util.as_local(etat.last_changed).isoformat(timespec="minutes"),
    }
    if rendu == "jauge":
        mini, maxi = _bornes(etat, rendu)
        sortie["min"], sortie["max"] = mini, maxi
    if rendu == "courbe":
        sortie["serie"] = await _serie(hass, etat.entity_id)
    if not nom:
        sortie["masquer"] = True  # sans nom lisible, la case ne montrerait qu'un identifiant
    if etat.entity_id.startswith("weather."):
        temp = _nombre(a.get("temperature"))
        ciel = _libelle_etat(etat)
        if not ciel and temp is None:
            sortie["masquer"] = True
        sortie["rendu"] = "texte"
        sortie["texte"] = " · ".join(x for x in (ciel, f"{temp:.0f}{a.get('temperature_unit') or '°'}" if temp is not None else "") if x)
    if etat.entity_id.startswith("climate."):
        sortie["valeur"] = _nombre(a.get("current_temperature"))
        sortie["unite"] = "°"
        sortie["consigne"] = _nombre(a.get("temperature"))
    return sortie


async def pour_appareil(hass: HomeAssistant, coord: PcParentalCoordinator, pc: dict[str, Any] | None, ecran: str) -> dict[str, Any]:
    """Ce que cet appareil doit montrer : la liste ordonnée des tableaux, résolue."""
    ecran = ecran if ecran in ECRANS else "tele"
    qui = profil(hass, coord, pc)
    liste = []
    for t in configuration(coord):
        if t["code"] != "horloge" or not t["actif"] or not _vise(t, ecran, qui):
            continue
        if t["code"] != "entites":
            liste.append({"code": t["code"], "duree": t["duree"]})
            continue
        if modules.coupe(coord.store, "entites"):
            continue  # 1.82.0 : un tableau composé d'entités relève du module « Entités »
        cases = [await _case(hass, c) for c in t["cases"]]
        if not cases:
            continue
        liste.append({"code": "entites", "id": t["id"], "titre": t["titre"], "duree": t["duree"], "cases": cases})
    # Les tableaux de bord de Home Assistant retenus dans Vision.
    try:
        from . import veille_dash
        if modules.coupe(coord.store, "veille_tableaux"):
            return {"profil": qui["public"], "ecran": ecran, "liste": liste}
        liste.extend(await veille_dash.tableaux(hass, coord, ecran, qui))
    except Exception:  # noqa: BLE001
        import logging
        logging.getLogger(__name__).exception("Tableaux de bord sur l'écran de veille")
    return {"profil": qui["public"], "ecran": ecran, "liste": liste}


async def resoudre_locaux(hass: HomeAssistant, locaux: Any) -> list[dict[str, Any]]:
    return []  # les tableaux composés sur l'appareil n'existent plus
    """Les tableaux composés sur l'appareil lui-même : on résout leurs cases, sans rien enregistrer."""
    sortie = []
    if not isinstance(locaux, list):
        return sortie
    for t in locaux[:MAX_TABLEAUX]:
        n = _nettoyer(t) if isinstance(t, dict) else None
        if n is None or n["code"] != "entites" or not n["cases"]:
            continue
        cases = [await _case(hass, c) for c in n["cases"]]
        sortie.append({"code": "entites", "id": n["id"], "titre": n["titre"], "duree": n["duree"], "cases": cases, "local": True})
    return sortie


DOMAINES_UTILES = ("sensor", "binary_sensor", "climate", "light", "switch", "cover", "lock", "person", "device_tracker", "weather",
                   "media_player", "input_boolean", "input_number", "number", "fan", "vacuum", "alarm_control_panel", "water_heater", "humidifier", "camera")


def catalogue_entites(hass: HomeAssistant) -> list[dict[str, Any]]:
    """Ce que l'appareil peut mettre dans une case : les entités utiles, avec leur nom, domaine, unité et classe."""
    from homeassistant.helpers import entity_registry as er

    from .processus import CAPTEUR, CAPTEUR_ANCIEN

    registre = er.async_get(hass)
    sortie = []
    for s in hass.states.async_all():
        domaine = s.entity_id.split(".", 1)[0]
        if domaine not in DOMAINES_UTILES:
            continue
        nom = str(s.attributes.get("friendly_name") or s.entity_id)
        # Les entités de Vision lui-même n'ont rien à faire sur un écran : reconnues par leur
        # intégration, pas par leur nom (qui dépend de la maison et de la version).
        entree = registre.async_get(s.entity_id)
        if (entree is not None and entree.platform == DOMAIN) or s.entity_id in (CAPTEUR, CAPTEUR_ANCIEN):
            continue
        sortie.append({
            "id": s.entity_id,
            "nom": nom,
            "domaine": domaine,
            "unite": str(s.attributes.get("unit_of_measurement") or ""),
            "classe": str(s.attributes.get("device_class") or ""),
            "etat": str(s.state)[:24],
        })
    sortie.sort(key=lambda e: (e["domaine"], e["nom"].lower()))
    return sortie


# --- Vue d'administration ----------------------------------------------------------------

class PcParentalVeilleTableauxView(HomeAssistantView):
    """L'éditeur des tableaux dans Home Assistant : un administrateur, rien d'autre."""

    url = "/api/pc_parental/veille/tableaux"
    name = "api:pc_parental:veille_tableaux"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _admin(self, request: web.Request) -> web.Response | None:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        return None

    async def get(self, request: web.Request) -> web.Response:
        if refus := self._admin(request):
            return refus
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        if refus := modules.refus(self, coord.store, "veille"):
            return refus
        apercu = request.query.get("apercu")
        reponse: dict[str, Any] = {"ok": True, "tableaux": configuration(coord), **catalogue(self.hass)}
        if apercu:
            # L'aperçu d'un tableau composé, tel que la télé le recevrait.
            t = next((x for x in configuration(coord) if x["code"] == "entites" and x["id"] == apercu), None)
            reponse["apercu"] = [await _case(self.hass, c) for c in (t or {}).get("cases", [])]
        return self.json(reponse)

    async def post(self, request: web.Request) -> web.Response:
        if refus := self._admin(request):
            return refus
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        if refus := modules.refus(self, coord.store, "veille"):
            return refus
        from .entity import appliquer

        try:
            await appliquer(coord, lambda: enregistrer(coord, corps.get("tableaux")))
        except Exception as err:  # noqa: BLE001
            return self.json({"ok": False, "error": str(err)}, status_code=400)
        return self.json({"ok": True, "tableaux": configuration(coord), **catalogue(self.hass)})
