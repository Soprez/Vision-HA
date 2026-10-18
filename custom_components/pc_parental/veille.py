"""Écran de veille Vision : ce que la télé affiche quand personne ne la regarde.

Une télé inscrite demande ici, toutes les minutes, de quoi remplir ses
tableaux : l'heure, la météo et la vigilance Météo-France, l'état de la
maison selon Vision, et les entités que la maison a choisies dans
« Écran de veille : entités » (une par ligne ou séparées par des virgules,
`entity_id|Titre` pour renommer). Même porte que le relevé : l'identifiant et
le secret de l'appareil, rien d'autre.
"""
from __future__ import annotations

import datetime
import logging
import time
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from . import labels
from . import modules
from . import roles
from . import textes
from .const import DOMAIN
from .coordinator import PcParentalCoordinator
from . import veille_plus as plus
from . import veille_tableaux as tableaux
from .esports import esports

_LOGGER = logging.getLogger(__name__)

URL_VEILLE = "/api/pc_parental/veille"
EN_LIGNE_S = 120

NIVEAUX = ("Vert", "Jaune", "Orange", "Rouge")
PHENOMENES = (
    "Vent violent", "Pluie-inondation", "Orages", "Neige-verglas", "Inondation",
    "Canicule", "Grand-froid", "Avalanches", "Vagues-submersion",
)

OUVERTURES = ("door", "garage_door", "opening", "window", "gate", "lock")


def _coordinateur(hass: HomeAssistant) -> PcParentalCoordinator | None:
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


def est_parent(hass: HomeAssistant, coord: PcParentalCoordinator, pc: dict[str, Any]) -> bool:
    """Un appareil portant l'étiquette « Parents » (posée par un administrateur)."""
    return roles.a(coord.store, pc, "veille")


def cameras_permises(coord: PcParentalCoordinator) -> set[str]:
    """Les seules caméras que l'écran de veille peut montrer : celles choisies pour lui
    (« Écran de veille : caméras »), et celles qu'un administrateur a déjà posées dans
    ses entités ou ses tableaux. Jamais une caméra que l'appareil nomme lui-même."""
    store = coord.store
    permises = set(store.veille_cameras)
    permises.update(e.split("|")[0].strip() for e in store.veille_entites)
    for t in store.veille_tableaux:
        for c in (t.get("cases") or []) if isinstance(t, dict) else []:
            if isinstance(c, dict):
                permises.add(str(c.get("entite") or "").strip())
    # Les caméras des cases typées ne sont pas ici : elles sont permises appareil par appareil (camera_permise).
    return {e for e in permises if e.startswith("camera.")}


def camera_permise_pour(coord: PcParentalCoordinator, moi: dict[str, Any], entite: str) -> bool:
    """Choisie pour la veille, ou résolue pour CET appareil dans une case typée (veille_cases)."""
    from . import veille_cases

    return entite in cameras_permises(coord) or veille_cases.camera_permise(str(moi.get("id") or ""), entite)


def _entite_meteo(hass: HomeAssistant) -> str:
    candidats = [s.entity_id for s in hass.states.async_all("weather")]
    for e in candidats:
        if "meteo_france" in e:
            return e
    return candidats[0] if candidats else ""


def _entite_vigilance(hass: HomeAssistant) -> str:
    for s in hass.states.async_all("sensor"):
        if any(p in s.attributes for p in PHENOMENES[:3]):
            return s.entity_id
    return ""


def _nombre(valeur: Any) -> float | None:
    try:
        return float(valeur)
    except (TypeError, ValueError):
        return None


def _joli(valeur: Any, decimales: int = 1) -> str:
    n = _nombre(valeur)
    if n is None:
        return str(valeur if valeur is not None else "")
    if abs(n - round(n)) < 0.05:
        return str(int(round(n)))
    return ("{:." + str(decimales) + "f}").format(n).replace(".", ",")


def _vigilance(hass: HomeAssistant) -> dict[str, Any]:
    eid = _entite_vigilance(hass)
    etat = hass.states.get(eid) if eid else None
    if etat is None:
        return {"niveau": "", "details": {}, "rang": 0}
    niveau = str(etat.state or "Vert")
    details = {p: str(etat.attributes.get(p)) for p in PHENOMENES if p in etat.attributes}
    rang = NIVEAUX.index(niveau) if niveau in NIVEAUX else 0
    return {"niveau": niveau, "details": details, "rang": rang}


async def _meteo(hass: HomeAssistant) -> dict[str, Any]:
    eid = _entite_meteo(hass)
    etat = hass.states.get(eid) if eid else None
    if etat is None:
        return {}
    a = etat.attributes
    sortie: dict[str, Any] = {
        "etat": str(etat.state),
        "temperature": _nombre(a.get("temperature")),
        "ressenti": _nombre(a.get("apparent_temperature")),
        "humidite": _nombre(a.get("humidity")),
        "vent": _nombre(a.get("wind_speed")),
        "rafales": _nombre(a.get("wind_gust_speed")),
        "pression": _nombre(a.get("pressure")),
        "previsions": [],
    }
    try:
        reponse = await hass.services.async_call(
            "weather", "get_forecasts", {"type": "daily"},
            target={"entity_id": eid}, blocking=True, return_response=True,
        )
        for p in ((reponse or {}).get(eid) or {}).get("forecast") or []:
            sortie["previsions"].append({
                "jour": str(p.get("datetime") or "")[:10],
                "etat": str(p.get("condition") or ""),
                "max": _nombre(p.get("temperature")),
                "min": _nombre(p.get("templow")),
                "pluie": _nombre(p.get("precipitation_probability")),
            })
            if len(sortie["previsions"]) >= 5:
                break
    except Exception:  # noqa: BLE001 - sans prévisions, la météo du moment suffit
        pass
    return sortie


# (clé du nom dans le catalogue serveur, classes, domaines, mots de l'identifiant) ; le nom se dit dans la langue
# de l'appareil (`textes.parler` dans les vues).
THEMES = (
    ("veille.theme_chauffage", ("temperature", "humidity"), ("climate",), ("chaudiere", "bruleur", "thermostat", "chauff", "radiateur", "temp")),
    ("veille.theme_ouvertures", OUVERTURES, (), ("portail", "portillon", "porte", "store", "volet", "fenetre", "garage")),
    ("veille.theme_energie", ("energy", "power", "gas", "water", "volume"), (), ("fioul", "electri", "linky", "conso", "eau", "ecs")),
)


def _theme(eid: str, classe: str, domaine: str) -> str:
    """Le thème d'une tuile, deviné d'après sa classe ou son nom : la télé
    fait un écran par thème plutôt qu'un fourre-tout."""
    bas = eid.lower()
    for nom, classes, domaines, mots in THEMES:
        if classe in classes or domaine in domaines or any(m in bas for m in mots):
            return textes.t(nom)
    return textes.t("veille.theme_autres")


def _tuile(hass: HomeAssistant, entree: str) -> dict[str, Any] | None:
    morceaux = [m.strip() for m in entree.split("|")]
    eid = morceaux[0]
    titre = morceaux[1] if len(morceaux) > 1 and morceaux[1] else ""
    theme_voulu = morceaux[2] if len(morceaux) > 2 and morceaux[2] else ""
    etat = hass.states.get(eid)
    if etat is None or tableaux.exclue(eid):
        return None  # 2.2.12 : une entité d'action ou de saisie n'est jamais une tuile
    a = etat.attributes
    classe = str(a.get("device_class") or "")
    domaine = eid.split(".")[0]
    valeur = str(etat.state)
    lang = tableaux.langue(hass)
    unite = tableaux.unite_locale(str(a.get("unit_of_measurement") or ""), lang)
    alerte = False
    if domaine == "climate":
        valeur = _joli(a.get("current_temperature"))
        unite = "°C"
        cible = a.get("temperature")
        detail = textes.t("veille.consigne", None, temp=_joli(cible)) if cible is not None else ""
        if a.get("hvac_action"):
            detail = "{} · {}".format(detail, a.get("hvac_action")) if detail else str(a.get("hvac_action"))
    elif domaine in ("binary_sensor", "input_boolean", "switch"):
        allume = etat.state == "on"
        if classe in OUVERTURES:
            valeur = textes.t("veille.ouvert" if allume else "veille.ferme")
            alerte = allume
        elif classe in ("problem", "smoke", "gas", "safety", "moisture"):
            valeur = textes.t("veille.alerte") if allume else "OK"
            alerte = allume
        else:
            valeur = textes.t("veille.actif" if allume else "veille.inactif")
        detail = ""
    else:
        date = tableaux.date_lisible(valeur, lang)
        if date is not None:
            valeur = date
        elif _nombre(etat.state) is not None:
            valeur = _joli(etat.state)
        elif valeur in ("unknown", "unavailable", "None"):
            valeur = "—"
        alerte = valeur.lower() in ("ouvert", "oui", "alerte", "allumé")
        detail = ""
    return {
        "id": eid,
        "titre": titre or str(a.get("friendly_name") or eid),
        "valeur": valeur,
        "unite": unite,
        "detail": detail,
        "icone": str(a.get("icon") or ""),
        "classe": classe,
        "alerte": alerte,
        "theme": theme_voulu or _theme(eid, classe, domaine),
    }


def _maison(hass: HomeAssistant, coord: PcParentalCoordinator) -> list[dict[str, Any]]:
    store = coord.store
    par_personne: dict[str, dict[str, Any]] = {}
    for pc in store.pcs.values():
        if not pc.get("enabled", True):
            continue
        personne = str(pc.get("person") or "")
        etat_p = hass.states.get(personne) if personne else None
        prenom = str(etat_p.name) if etat_p is not None and etat_p.name else textes.t("veille.sans_personne")
        if store.est_ecran_commun(personne):
            continue
        try:
            evalue = store.evaluate(pc)
            usage = store.usage_du_jour(pc)
        except Exception:  # noqa: BLE001
            continue
        vu = float(pc.get("last_seen") or 0)
        agent = pc.get("agent") or {}
        bloc = par_personne.setdefault(prenom, {"prenom": prenom, "appareils": [], "minutes": 0})
        bloc["minutes"] += int(usage.get("actif", 0)) // 60
        bloc["appareils"].append({
            "nom": str(pc.get("name") or ""),
            "android": pc.get("platform") == "android",
            "en_ligne": vu > 0 and time.time() - vu < EN_LIGNE_S,
            "verrouille": bool(evalue.get("locked")),
            "inactif_s": int(agent.get("idle_seconds") or 0),
        })
    return sorted(par_personne.values(), key=lambda p: p["prenom"].lower())


_cache_courbe: dict[str, Any] = {"quand": 0.0, "cle": "", "valeur": None}
COURBE_HEURES = 24
COURBE_PAS_S = 15 * 60


def _entites_courbe(hass: HomeAssistant, coord: PcParentalCoordinator) -> list[str]:
    """Les deux températures à comparer : celles choisies, sinon les deux
    premières tuiles de température (dehors puis dedans)."""
    choisies = [e.split("|")[0].strip() for e in (coord.store.veille_courbe or []) if e.strip()]
    if len(choisies) >= 2:
        return choisies[:2]
    temperatures = []
    for entree in coord.store.veille_entites:
        eid = entree.split("|")[0].strip()
        etat = hass.states.get(eid)
        if etat is not None and str(etat.attributes.get("device_class") or "") == "temperature":
            temperatures.append(eid)
    return temperatures[:2]


def _serie(hass: HomeAssistant, eid: str, debut, fin) -> list[list[float]]:
    """Une température toutes les quinze minutes sur la période : [horodatage, valeur]."""
    from homeassistant.components.recorder import history

    etats = history.state_changes_during_period(
        hass, debut, fin, eid, no_attributes=True, include_start_time_state=True,
    ).get(eid) or []
    points: list[tuple[float, float]] = []
    for s in etats:
        v = _nombre(s.state)
        if v is None:
            continue
        points.append((s.last_updated.timestamp(), v))
    if not points:
        return []
    # Échantillonnage régulier : la dernière valeur connue à chaque pas.
    sortie: list[list[float]] = []
    t = debut.timestamp()
    fin_s = fin.timestamp()
    i = 0
    courant = points[0][1]
    while t <= fin_s:
        while i < len(points) and points[i][0] <= t:
            courant = points[i][1]
            i += 1
        sortie.append([round(t), round(courant, 1)])
        t += COURBE_PAS_S
    return sortie


async def _courbe(hass: HomeAssistant, coord: PcParentalCoordinator) -> dict[str, Any]:
    entites = _entites_courbe(hass, coord)
    if len(entites) < 2:
        return {}
    cle = "|".join(entites)
    if _cache_courbe["valeur"] is not None and _cache_courbe["cle"] == cle and time.time() - _cache_courbe["quand"] < 300:
        return _cache_courbe["valeur"]
    fin = dt_util.utcnow()
    debut = fin - datetime.timedelta(hours=COURBE_HEURES)
    try:
        from homeassistant.components.recorder import get_instance

        series = [
            await get_instance(hass).async_add_executor_job(_serie, hass, eid, debut, fin)
            for eid in entites
        ]
    except Exception:  # noqa: BLE001 - sans historique, pas de courbe, rien d'autre ne casse
        return {}
    noms = []
    for eid in entites:
        titre = ""
        for entree in coord.store.veille_entites:
            morceaux = entree.split("|")
            if morceaux[0].strip() == eid and len(morceaux) > 1:
                titre = morceaux[1].strip()
        etat = hass.states.get(eid)
        noms.append(titre or str((etat.attributes.get("friendly_name") if etat else None) or eid))
    valeur = {
        "heures": COURBE_HEURES,
        "series": [
            {"id": eid, "nom": nom, "points": pts}
            for eid, nom, pts in zip(entites, noms, series)
        ],
    }
    _cache_courbe.update({"quand": time.time(), "cle": cle, "valeur": valeur})
    return valeur


def _demandes_accueil(hass: HomeAssistant, coord: PcParentalCoordinator, moi: dict[str, Any] | None) -> dict[str, Any]:
    """2.5.3 (l'auteur : « Aucune demande en attente » alors qu'une demande attend) : ce que le widget Demandes montre.
    - les demandes une à une (accès, temps, déverrouillage : la même source que « À décider »), et leur nombre total
      (la liste est coupée à quatre, le compte ne l'est plus) ;
    - pour un Décideur, aussi ce qui se traite par lots (appareil à valider, à classer, logiciels), que le widget
      ignorait : seul « À décider » le montrait.
    Une erreur ici ne coupe jamais le reste de l'accueil (avant : toute la réponse tombait, le widget disait « aucune »)."""
    sortie: dict[str, Any] = {"demandes": [], "demandes_total": 0, "a_decider_lots": []}
    if modules.coupe(coord.store, "demandes"):
        return sortie
    try:
        from . import parent

        toutes = parent.demandes_attente(hass, coord)
        sortie["demandes"] = plus.demandes(hass, coord)
        sortie["demandes_total"] = len(toutes)
    except Exception:  # noqa: BLE001 - jamais l'accueil entier pour une demande mal formée
        _LOGGER.exception("Vision : demandes de l'accueil")
    if moi is not None and roles.a(coord.store, moi, "approuver"):
        try:
            from . import a_decider

            sortie["a_decider_lots"] = a_decider.lots(hass, coord, textes.pour(hass, moi))
        except Exception:  # noqa: BLE001
            _LOGGER.exception("Vision : lots « À décider » de l'accueil")
    return sortie


def widgets_disponibles(d: dict[str, Any]) -> list[str]:
    """2.5.3 (retour d'essai) : les widgets intégrés de l'accueil que les données de CE lieu permettent, d'après ce que /veille
    envoie (aucun nom d'entité ici : Vision va dans d'autres foyers). L'appli ne propose que ceux-là ; ceux déjà posés
    restent. Toujours : le temps d'écran et les demandes (les modules les règlent déjà) ; l'horloge stylisée de la zone du
    haut n'est pas concernée (elle ne lit que l'heure). Pur."""
    disponibles = ["temps", "demandes"]
    # « Horloge, météo et pluie » : il lui faut une météo dans Home Assistant.
    if d.get("meteo"):
        disponibles.append("horloge")
    chauffage = d.get("chauffage") or {}
    if chauffage:
        disponibles.append("chauffage")
    # Le fioul : une source de consommation (jours, mois ou saison), pas seulement un thermostat.
    if chauffage and (chauffage.get("jours") or chauffage.get("mois") is not None or chauffage.get("saison") is not None):
        disponibles.append("fioul")
    if d.get("ecole"):
        disponibles += ["ecole", "infos", "devoirs"]
    if d.get("cameras"):
        disponibles.append("camera")
    if d.get("batteries"):
        disponibles.append("batteries")
    # Les personnes (et celles qui sont là) : des appareils inscrits, rangés par personne.
    if d.get("maison"):
        disponibles += ["enfants", "maison"]
    return disponibles


async def etat_veille(hass: HomeAssistant, coord: PcParentalCoordinator, moi: dict[str, Any] | None = None, ecran: str = "tele", locaux: Any = None) -> dict[str, Any]:
    tuiles = []
    for entree in coord.store.veille_entites:
        t = _tuile(hass, entree)
        if t:
            tuiles.append(t)
    etat = {
        "ok": True,
        "heure": dt_util.now().isoformat(),
        "meteo": await _meteo(hass),
        "vigilance": _vigilance(hass),
        "tuiles": tuiles,
        "maison": _maison(hass, coord),
        "courbe": await _courbe(hass, coord),
        "cameras": _cameras(hass, coord),
        "esports": esports(hass).demander(),
        "courses": esports(hass).courses_connues(),
        "avenir": esports(hass).avenir_connu(),
        "musique": str(getattr(coord.store, "veille_musique", "") or ""),
        "reglages": plus.reglages(coord),
        "ecole": plus.ecole(hass),
        "agenda": await plus.agenda(hass),
        "pluie": plus.pluie(hass),
        **_demandes_accueil(hass, coord, moi),
        "chauffage": await plus.chauffage(hass, coord),
        "photos": await plus.photos(hass),
        "batteries": await _sans_erreur(plus.batteries(hass), []),
        "tableaux": await _sans_erreur(tableaux.pour_appareil(hass, coord, moi, ecran), None),
        "moi": _moi(hass, coord, moi),
        "locaux": await _sans_erreur(tableaux.resoudre_locaux(hass, locaux), []),
        "erreurs": list(_dernieres_erreurs),
    }
    etat["widgets_disponibles"] = widgets_disponibles(etat)
    return etat


_dernieres_erreurs: list[str] = []


def _moi(hass: HomeAssistant, coord: PcParentalCoordinator, pc: dict[str, Any] | None) -> dict[str, Any] | None:
    """Qui tient l'appareil, et ce qui le concerne : prénom, public,
    verrou et temps d'écran du jour. L'accueil du téléphone s'en sert pour parler à la bonne personne."""
    if pc is None:
        return None
    qui = tableaux.profil(hass, coord, pc)
    personne = qui["personne"]
    etat_p = hass.states.get(personne) if personne else None
    store = coord.store
    sortie: dict[str, Any] = {
        "public": qui["public"],
        "personne": personne,
        "prenom": str(etat_p.name) if etat_p is not None and etat_p.name else "",
        "nom": str(pc.get("name") or ""),
    }
    try:
        # Le message d'accueil de l'appli, déjà rempli dans la langue de l'appareil (accueil.py) : affiché tel quel.
        from . import accueil

        sortie["accueil"] = accueil.pour_appareil(hass, coord, pc)
    except Exception:  # noqa: BLE001 - jamais au prix de l'écran
        import logging

        logging.getLogger(__name__).exception("Écran de veille : message d'accueil impossible.")
    try:
        ev = store.evaluate(pc)
        sortie["verrouille"] = bool(ev.get("locked"))
        suivant = ev.get("next_change")
        sortie["prochain"] = suivant.isoformat() if suivant else ""
        if not ev.get("locked"):
            from .planner import next_lock

            local = coord.local_now().replace(tzinfo=None)
            pv = next_lock(store.regles_pour(pc), local)
            sortie["prochain_verrou"] = pv.isoformat() if pv else ""
        usage = store.usage_du_jour(pc)
        sortie["minutes_actif"] = int(usage.get("actif", 0)) // 60
    except Exception:  # noqa: BLE001
        pass
    return sortie


async def _sans_erreur(coro, defaut):
    """Un morceau facultatif de l'écran ne doit jamais faire tomber tout l'écran."""
    try:
        return await coro
    except Exception as e:  # noqa: BLE001
        import logging
        import traceback
        logging.getLogger(__name__).error("Écran de veille : %s", e, exc_info=True)
        _dernieres_erreurs.append(traceback.format_exc()[-600:])
        del _dernieres_erreurs[:-5]
        return defaut


def _cameras(hass: HomeAssistant, coord: PcParentalCoordinator) -> list[dict[str, str]]:
    """Les caméras choisies pour la veille, par ordre alphabétique : la télé en met quatre par écran."""
    sortie = []
    for s in hass.states.async_all("camera"):
        if s.entity_id not in coord.store.veille_cameras or s.state in ("unavailable", "unknown"):
            continue
        sortie.append({"id": s.entity_id, "nom": str(s.attributes.get("friendly_name") or s.entity_id)})
    sortie.sort(key=lambda c: c["nom"].lower())
    return sortie[:8]


class PcParentalVeilleView(HomeAssistantView):
    url = URL_VEILLE
    name = "api:pc_parental:veille"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        moi = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if moi is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.parler(textes.pour(self.hass, moi))
        if refus := modules.refus(self, coord.store, "veille"):  # 1.82.0
            return refus
        sortie = await etat_veille(self.hass, coord, moi, str(corps.get("ecran") or "tele"), corps.get("locaux"))
        return self.json(sortie)


class PcParentalVeilleEntitesView(HomeAssistantView):
    """Le catalogue des entités, pour composer un tableau depuis l'appareil."""

    url = URL_VEILLE + "/entites"
    name = "api:pc_parental:veille_entites"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        moi = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if moi is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.parler(textes.pour(self.hass, moi))
        if refus := modules.refus(self, coord.store, "entites"):  # 1.82.0
            return refus
        # Toutes les entités de la maison : seulement pour l'appareil d'un parent.
        if not est_parent(self.hass, coord, moi):
            return self.json({"ok": False, "error": "parent"}, status_code=403)
        return self.json({"ok": True, "entites": tableaux.catalogue_entites(self.hass), "rendus": tableaux.catalogue(self.hass)["rendus"]})


class PcParentalVeilleImageView(HomeAssistantView):
    """Une image de caméra pour l'écran de veille, par la même porte que le reste.

    La télé n'a pas de jeton Home Assistant : elle présente son identifiant et
    son secret, et reçoit le JPEG du moment. Rien d'autre ne sort par ici.
    """

    url = URL_VEILLE + "/image"
    name = "api:pc_parental:veille_image"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        moi = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if moi is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.parler(textes.pour(self.hass, moi))
        if refus := modules.refus(self, coord.store, "veille"):  # 1.82.0
            return refus
        entite = str(corps.get("entite") or "")
        if not camera_permise_pour(coord, moi, entite) or self.hass.states.get(entite) is None:
            return self.json({"ok": False, "error": "camera"}, status_code=404)
        try:
            from homeassistant.components.camera import async_get_image

            image = await async_get_image(self.hass, entite, timeout=10, width=960)
        except Exception as err:  # noqa: BLE001 - une caméra muette ne casse pas l'écran
            return self.json({"ok": False, "error": str(err)[:120]}, status_code=502)
        return web.Response(body=image.content, content_type=image.content_type, headers={"Cache-Control": "no-store"})


class PcParentalVeilleFluxView(HomeAssistantView):
    """Le flux vidéo d'une caméra pour l'écran de veille : HLS servi par Home Assistant.

    Home Assistant remuxe la caméra (sans réencoder) et donne une adresse
    jetable `/api/hls/<jeton>/master_playlist.m3u8` : la télé la lit telle
    quelle, sans identifiants de caméra et sans jeton Home Assistant.
    """

    url = URL_VEILLE + "/flux"
    name = "api:pc_parental:veille_flux"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        moi = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if moi is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.parler(textes.pour(self.hass, moi))
        if refus := modules.refus(self, coord.store, "veille"):  # 1.82.0
            return refus
        entite = str(corps.get("entite") or "")
        if not camera_permise_pour(coord, moi, entite) or self.hass.states.get(entite) is None:
            return self.json({"ok": False, "error": "camera"}, status_code=404)
        try:
            chemin = await _flux_hls(self.hass, coord, entite)
        except Exception as err:  # noqa: BLE001 - pas de flux : la télé garde les images fixes
            return self.json({"ok": False, "error": str(err)[:120]}, status_code=502)
        return self.json({"ok": True, "url": chemin})


async def _flux_hls(hass: HomeAssistant, coord: PcParentalCoordinator, entite: str) -> str:
    """L'adresse HLS d'une caméra, de préférence son flux secondaire.

    Les caméras servent souvent deux flux : le principal (4K, lourd) et un
    secondaire léger. Une télé ne décode pas quatre flux 4K à la fois ; si un
    suffixe de flux secondaire est réglé (« _sub » chez Frigate / go2rtc), on
    demande à Home Assistant de remuxer cette source-là. Sans suffixe, ou si
    la caméra n'a pas d'adresse de flux, c'est le flux normal de l'entité.
    """
    from homeassistant.components.camera import async_request_stream, get_dynamic_camera_stream_settings
    from homeassistant.components.stream import HLS_PROVIDER, create_stream

    suffixe = str(getattr(coord.store, "veille_flux_suffixe", "") or "").strip()
    composant = hass.data.get("camera")
    cam = composant.get_entity(entite) if composant is not None else None
    source = await cam.stream_source() if cam is not None and suffixe else None
    if not source or "://" not in source:
        return await async_request_stream(hass, entite, "hls")
    base, sep, requete = source.partition("?")
    secondaire = base.rstrip("/") + suffixe + sep + requete
    flux: dict[str, Any] = hass.data.setdefault(f"{DOMAIN}_veille_flux", {})
    stream = flux.get(secondaire)
    if stream is None or not stream.available:
        stream = create_stream(
            hass,
            secondaire,
            options=dict(getattr(cam, "stream_options", {}) or {}),
            dynamic_stream_settings=await get_dynamic_camera_stream_settings(hass, entite),
            stream_label=f"{entite} (veille)",
        )
        flux[secondaire] = stream
    stream.add_provider(HLS_PROVIDER)
    await stream.start()
    return stream.endpoint_url(HLS_PROVIDER)
