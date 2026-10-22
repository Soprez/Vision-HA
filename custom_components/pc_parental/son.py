"""Niveau sonore (backlog n° 31, validé en essai le 11/10) : le bruit de la pièce, mesuré par le micro de l'appareil.

Module activable, **coupé par défaut** (INACTIFS_PAR_DEFAUT), puis activé appareil par appareil (désactivé par défaut).

- L'appareil mesure **sur place** un niveau en dB relatifs (0 à 100, calibrable) ; il n'enregistre et n'envoie
  **jamais d'audio** : seuls des nombres remontent dans le relevé (`son` : niveau, maximum, pic, dépassements).
  100 = pleine échelle du micro (0 dBFS) : le capteur et les réglages de Home Assistant parlent en dBFS (niveau − 100).
- L'appareil détecte lui-même un dépassement (au-dessus du seuil pendant la durée de « sensibilité » : un pic court
  ne compte pas) et l'affiche aussitôt ; Home Assistant compte les alertes, pendant les plages actives seulement.
- 2.5.12.3 (l'auteur, 19/10 : « un membre crie dans sa chambre, parfois le jour, parfois la nuit ») :
  - un **seuil de nuit** distinct, la nuit étant la plage « Verrouiller la session » de l'appareil (celle qui existe
    déjà dans son planning), ou à défaut une plage de repli réglable ;
  - un **délai minimal entre deux alertes** (`repos_min`) ;
  - une **réaction graduée** sur l'appareil : message doux, puis message ferme si ça recommence dans les
    `ferme_apres_min`, puis les responsables sont prévenus (canaux « alertes ») ;
  - un **compteur du jour** ; le PC mesure avec son agent (tous les micros, WASAPI partagé, moyenne glissante, pic 10 s).
- À la N-ième alerte (3 par défaut sur Android, jamais par défaut sur un PC), coupure de l'appareil pendant la durée
  réglée : la même dérogation que le service `lock`. Le compteur revient à zéro après X minutes calmes, et après une
  coupure.
- Événements libres pour les automatisations : `vision_son_seuil_depasse` et `vision_son_coupure`.
- Capteur « Niveau sonore » par appareil (sensor.py) ; réglages par appareil en entités (number.py, switch.py) et
  par le service `pc_parental.son_regler`.
- Contrat avec les appareils : docs/son-contrat-appareils.md.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from . import matrice, modules, textes
from .const import MODE_LOCKED, PORTEE_SESSION

_LOGGER = logging.getLogger(__name__)

MODULE = "son"
EV_SEUIL = "vision_son_seuil_depasse"
EV_COUPURE = "vision_son_coupure"
NIVEAU_DOUX, NIVEAU_FERME, NIVEAU_RESPONSABLE = 1, 2, 3

# Réglages d'un appareil : défauts sûrs (module coupé ; une fois allumé, seuil prudent, coupure après des alertes).
REGLAGES_DEFAUT: dict[str, Any] = {
    "actif": False,              # l'appareil mesure (en plus du module, allumé pour toute l'installation)
    "seuil_db": 75,              # dB relatifs (0 = silence numérique, 100 = pleine échelle du micro) : le jour
    "seuil_nuit_db": 65,         # le même, la nuit (2.5.12.3)
    "nuit": "plages",            # « plages » : la nuit = les plages « Verrouiller la session » du planning de l'appareil ;
                                 # « heures » : la plage nuit_debut → nuit_fin ci-dessous
    "nuit_debut": "22:00",       # repli quand l'appareil n'a aucune plage de session (ou nuit = « heures »)
    "nuit_fin": "07:00",
    "sensibilite_s": 5,          # secondes au-dessus du seuil avant une alerte (un pic plus court est ignoré)
    "repos_min": 0,              # délai minimal entre deux alertes comptées (2.5.12.3) ; 2 min sur un PC
    "ferme_apres_min": 5,        # une alerte de plus dans ce délai : message ferme, puis les responsables (2.5.12.3)
    "prevenir_responsable": True,  # la 3e alerte rapprochée prévient les responsables (canaux « alertes »)
    "alertes_avant_coupure": 3,  # la coupure vient à cette alerte ; 0 = jamais de coupure (alertes seules)
    "coupure_min": 15,           # durée de la coupure
    "calme_min": 10,             # sans dépassement pendant ce temps, le compteur revient à zéro
    "plages": [],                # [{"debut": "HH:MM", "fin": "HH:MM"}] ; vide = toute la journée
    "calibration_db": 0,         # ajouté à la mesure de l'appareil (micros très différents)
    "micro_fige_s": 10,          # un micro dont le niveau ne bouge plus pendant ce temps est exclu de la moyenne (2.5.12.3)
    "reagir": True,              # réagir aux dépassements (messages, responsables, coupure) ; éteint = mesure seule (2.5.12.7)
    "micros_exclus": [],         # morceaux de noms de micros ignorés (ex. un casque collé à la bouche) (2.5.12.7)
    # 2.5.12.10 : le seuil autorisé, général (toute la journée) ou par plage Vision (la plus stricte des plages actives,
    # sinon le général). seuil_nuit_db / nuit / nuit_debut / nuit_fin ne servent plus qu'à la migration et au service.
    "seuil_mode": "general",     # « general » ou « plages »
    "seuils_plages": {},         # {id de la plage Vision : seuil relatif (0 à 100)}
    "plancher_manuel_dbfs": None,  # plancher de la pièce saisi à la main (dBFS) ; None : le plancher mesuré (24 h)
    "micro_plancher_dbfs": -90,  # un micro plus bas que ça (avant calibration) ne capte rien : compté comme éteint (l'auteur, agent 1.93.5)
}
# Un PC (agent Windows, 2.5.12.3) : des alertes et des messages, jamais de coupure par défaut ; 3 s suffisent.
DEFAUTS_PC: dict[str, Any] = {"alertes_avant_coupure": 0, "sensibilite_s": 3, "repos_min": 2}
BORNES: dict[str, tuple[float, float]] = {
    "seuil_db": (30, 100), "seuil_nuit_db": (30, 100), "sensibilite_s": (1, 120), "repos_min": (0, 120),
    "ferme_apres_min": (1, 120), "alertes_avant_coupure": (0, 10), "coupure_min": (1, 240), "calme_min": (1, 240),
    "calibration_db": (-30, 30), "micro_fige_s": (2, 300), "micro_plancher_dbfs": (-120, -40),
}
ETATS_MICRO = ("actif", "muet", "silence", "sous_plancher", "exclu", "fige", "sans_donnees")  # état d'un micro (agent 1.93.5)
MODES_SEUIL = ("general", "plages")
SEUIL_SESSION_DEFAUT = 72    # −28 dBFS : proposé aux plages « Verrouiller la session » en passant en mode par plage
PLANCHER_FENETRE_S = 24 * 3600   # le plancher mesuré : les 24 dernières heures
PLANCHER_PAS_S = 300             # un échantillon toutes les 5 min au plus (288 sur 24 h)
PLANCHER_MIN_ECH = 12            # une heure de mesure avant de donner un plancher
PLANCHER_QUANTILE = 0.10         # le bas de la distribution des moyennes sur 1 min (le calme, pas un silence isolé)
MAX_DEPASSEMENTS = 20
JOURNAL_MAX = 20     # avertissements et coupures gardés dans l'état (capteur « Niveau sonore », attribut journal)
EXCLUS_MAX = 8       # morceaux de noms de micros exclus, 80 caractères chacun  # par relevé : un appareil qui s'emballe ne remplit pas le journal


def actif_module(store) -> bool:
    return not modules.coupe(store, MODULE)


def est_pc(pc: dict[str, Any]) -> bool:
    return matrice.type_appareil(pc or {}) == "pc"


def defauts(pc: dict[str, Any]) -> dict[str, Any]:
    """Les défauts de cet appareil : ceux du module, ajustés pour un PC."""
    return {**REGLAGES_DEFAUT, **(DEFAUTS_PC if est_pc(pc) else {})}


def _plages_session(pc: dict[str, Any]) -> list[dict[str, Any]]:
    """Les plages « Verrouiller la session » de cet appareil (normalisées)."""
    from . import planner

    # Le code de portée du planning (« session »), pas le libellé de const.PORTEE_SESSION.
    return planner.de_portee(pc.get("rules") or [], planner.PORTEE_SESSION)


def _migrer(pc: dict[str, Any], brut: dict[str, Any], base: dict[str, Any]) -> dict[str, Any]:
    """2.5.12.10 : un appareil réglé avant le choix du mode garde exactement sa règle jour/nuit.

    Nuit = ses plages de session (réglage « plages ») et un seuil de nuit différent du jour : mode « par plage », le
    général reprend le jour et chaque plage de session reprend la nuit. Sinon : mode général avec le seuil du jour
    (le repli horaire « heures » n'a pas d'équivalent en plage : il est noté au journal par migrer())."""
    if not brut:
        return {}
    try:
        jour = int(float(brut.get("seuil_db", base["seuil_db"])))
        nuit = int(float(brut.get("seuil_nuit_db", base["seuil_nuit_db"])))
    except (TypeError, ValueError):
        return {"seuil_mode": "general"}
    session = _plages_session(pc)
    if str(brut.get("nuit") or "plages") == "plages" and session and nuit != jour:
        return {"seuil_mode": "plages", "seuils_plages": {str(r["id"]): nuit for r in session}}
    return {"seuil_mode": "general"}


def reglages(pc: dict[str, Any]) -> dict[str, Any]:
    base = defauts(pc)
    brut = dict((pc.get("son") or {}).get("reglages") or {})
    if "seuil_mode" not in brut:
        brut.update(_migrer(pc, brut, base))
    r = {**base, **{k: v for k, v in brut.items() if k in base}}
    for k, (bas, haut) in BORNES.items():
        try:
            r[k] = int(min(haut, max(bas, float(r[k]))))
        except (TypeError, ValueError):
            r[k] = base[k]
    r["actif"] = bool(r["actif"])
    r["prevenir_responsable"] = bool(r["prevenir_responsable"])
    r["reagir"] = bool(r["reagir"])
    exclus = r["micros_exclus"] if isinstance(r["micros_exclus"], list) else []
    r["micros_exclus"] = [str(x).strip()[:80] for x in exclus if isinstance(x, str) and str(x).strip()][:EXCLUS_MAX]
    r["nuit"] = "heures" if str(r.get("nuit")) == "heures" else "plages"
    r["seuil_mode"] = r["seuil_mode"] if r["seuil_mode"] in MODES_SEUIL else "general"
    seuils: dict[str, int] = {}
    for k, v in (r["seuils_plages"] if isinstance(r["seuils_plages"], dict) else {}).items():
        try:
            seuils[str(k)[:40]] = int(min(BORNES["seuil_db"][1], max(BORNES["seuil_db"][0], float(v))))
        except (TypeError, ValueError):
            continue
    r["seuils_plages"] = dict(list(seuils.items())[:32])
    try:
        pm = r["plancher_manuel_dbfs"]
        r["plancher_manuel_dbfs"] = None if pm is None else int(min(-10, max(-120, float(pm))))
    except (TypeError, ValueError):
        r["plancher_manuel_dbfs"] = None
    for k in ("nuit_debut", "nuit_fin"):
        r[k] = str(r[k]) if _minutes(r.get(k)) is not None else base[k]
    r["plages"] = [p for p in (r["plages"] if isinstance(r["plages"], list) else []) if _plage_valide(p)]
    return r


def regler(pc: dict[str, Any], changements: dict[str, Any]) -> dict[str, Any]:
    """Pose des réglages (les clés inconnues sont ignorées, les valeurs bornées) ; rend les réglages effectifs.

    Passer en mode « par plage » sans aucun seuil de plage propose SEUIL_SESSION_DEFAUT aux plages de session."""
    son = pc.setdefault("son", {})
    avant = reglages(pc)
    son["reglages"] = {**avant, **{k: v for k, v in changements.items() if k in REGLAGES_DEFAUT}}
    if (changements.get("seuil_mode") == "plages" and avant["seuil_mode"] != "plages"
            and not son["reglages"].get("seuils_plages")):
        son["reglages"]["seuils_plages"] = {str(r["id"]): SEUIL_SESSION_DEFAUT for r in _plages_session(pc)}
    son["reglages"] = reglages(pc)
    return son["reglages"]


def plage_id(pc: dict[str, Any], plage: Any) -> str | None:
    """L'identifiant d'une plage de cet appareil, donnée par son identifiant ou son nom (sans tenir compte de la casse)."""
    cle = str(plage or "").strip()
    for r in pc.get("rules") or []:
        if str(r.get("id")) == cle:
            return str(r["id"])
    for r in pc.get("rules") or []:
        if str(r.get("name") or "").strip().lower() == cle.lower() and cle:
            return str(r["id"])
    return None


def regler_service(pc: dict[str, Any], e: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
    """Le service son_regler (2.5.12.10) : les champs historiques, plus le mode, les seuils en dBFS, les seuils par
    plage (en dBFS ou en marge au-dessus du plancher) et le plancher saisi à la main. ValueError : plage inconnue."""
    changements = {k: v for k, v in data.items() if k in REGLAGES_DEFAUT}
    if "seuil_dbfs" in data:
        changements["seuil_db"] = int(round(float(data["seuil_dbfs"]))) + 100
    if "plancher_dbfs" in data:
        changements["plancher_manuel_dbfs"] = data["plancher_dbfs"]
    if data.get("plancher_auto"):
        changements["plancher_manuel_dbfs"] = None
    seuils = dict(reglages(pc)["seuils_plages"])
    toucher_plages = False
    if "seuil_nuit_db" in data:
        # Compatibilité : « la nuit » = les plages de session de l'appareil.
        for r in _plages_session(pc):
            seuils[str(r["id"])] = int(data["seuil_nuit_db"])
        toucher_plages = True
        changements.setdefault("seuil_mode", "plages")
    plancher = plancher_effectif_db(pc, e)
    for item in data.get("seuils_plages") or []:
        ident = plage_id(pc, item.get("plage"))
        if ident is None:
            raise ValueError(str(item.get("plage")))
        if item.get("seuil_dbfs") is not None:
            seuils[ident] = int(round(float(item["seuil_dbfs"]))) + 100
        elif item.get("marge_plancher_db") is not None:
            if plancher is None:
                raise ValueError("plancher")
            seuils[ident] = int(round(plancher + float(item["marge_plancher_db"])))
        toucher_plages = True
    for plage in data.get("plage_effacer") or []:
        ident = plage_id(pc, plage)
        if ident is None:
            raise ValueError(str(plage))
        seuils.pop(ident, None)
        toucher_plages = True
    if "marge_plancher_db" in data:
        if plancher is None:
            raise ValueError("plancher")
        changements["seuil_db"] = int(round(plancher + float(data["marge_plancher_db"])))
    if toucher_plages:
        changements["seuils_plages"] = seuils
    return regler(pc, changements)


def migrer(store) -> int:
    """Au démarrage : enregistre le mode des appareils réglés avant 2.5.12.10, et oublie les seuils de plages supprimées.
    Rend le nombre d'appareils changés."""
    n = 0
    for pc in list(store.pcs.values()):
        brut = (pc.get("son") or {}).get("reglages")
        if not brut:
            continue
        r = reglages(pc)
        ids = {str(x.get("id")) for x in pc.get("rules") or []}
        propres = {k: v for k, v in r["seuils_plages"].items() if k in ids}
        if "seuil_mode" in brut and propres == dict(brut.get("seuils_plages") or {}):
            continue
        if "seuil_mode" not in brut and str(brut.get("nuit")) == "heures" and r["seuil_nuit_db"] != r["seuil_db"]:
            _LOGGER.warning("Vision : niveau sonore de « %s » : la nuit par heures (%s → %s) n'existe plus ; seuil général "
                            "%s dBFS. Créez une plage pour un seuil de nuit.", pc.get("name"), r["nuit_debut"], r["nuit_fin"],
                            r["seuil_db"] - 100)
        r["seuils_plages"] = propres
        pc.setdefault("son", {})["reglages"] = r
        n += 1
    return n


def etat(pc: dict[str, Any]) -> dict[str, Any]:
    son = pc.setdefault("son", {})
    e = son.setdefault("etat", {})
    e.setdefault("alertes", 0)
    e.setdefault("dernier", 0.0)      # horodatage du dernier dépassement compté
    e.setdefault("vu_jusqua", 0.0)    # le plus récent dépassement déjà reçu (un relevé répété ne compte pas deux fois)
    e.setdefault("niveau", None)
    e.setdefault("maximum", None)
    e.setdefault("maj", 0.0)
    # 2.5.12.3
    e.setdefault("pic", None)         # le plus haut des 10 dernières secondes
    e.setdefault("instant", None)     # la dernière seconde
    e.setdefault("micros", None)      # nombre de micros écoutés (PC)
    e.setdefault("jour", "")          # date locale du compteur du jour
    e.setdefault("aujourd_hui", 0)    # alertes comptées aujourd'hui
    e.setdefault("niveau_alerte", 0)  # 0 rien, 1 doux, 2 ferme, 3 responsables prévenus
    # 2.5.12.3 (l'auteur, précision) : seuls les micros vraiment allumés comptent ; aucun = capteur indisponible.
    e.setdefault("micros_actifs", None)  # micros pris en compte (None : l'appareil ne le dit pas, ex. l'appli)
    e.setdefault("micros_liste", [])     # leurs noms
    e.setdefault("journal", [])          # avertissements et coupures : heure, type, rang, niveau (2.5.12.7)
    e.setdefault("micros_detail", [])    # chaque micro : nom, état, niveau relatif (agent 1.93.5)
    return e


def mesure_active(store, pc: dict[str, Any]) -> bool:
    return actif_module(store) and reglages(pc)["actif"]


# ----------------------------------------------------------------------------- Plages horaires


def _minutes(hhmm: Any) -> int | None:
    try:
        h, m = str(hhmm).split(":")
        v = int(h) * 60 + int(m)
        return v if 0 <= v <= 24 * 60 else None
    except (ValueError, AttributeError):
        return None


def _plage_valide(p: Any) -> bool:
    return isinstance(p, dict) and _minutes(p.get("debut")) is not None and _minutes(p.get("fin")) is not None


def dans_plages(plages: list[dict[str, Any]], moment: datetime) -> bool:
    """Vide : toujours. Une plage qui passe minuit (22:00 → 07:00) est permise."""
    if not plages:
        return True
    m = moment.hour * 60 + moment.minute
    for p in plages:
        d, f = _minutes(p["debut"]), _minutes(p["fin"])
        if d == f or (d < f and d <= m < f) or (d > f and (m >= d or m < f)):
            return True
    return False


def est_nuit(store, pc: dict[str, Any], moment: datetime | None = None) -> bool:
    """2.5.12.3 : la nuit de cet appareil. D'abord les plages « Verrouiller la session » de son planning (celles
    qui existent déjà : en essai, la plage « Nuit »), sinon la plage de repli des réglages. Rien en dur."""
    from . import planner

    r = reglages(pc)
    moment = moment or dt_util.now()
    if r["nuit"] == "plages" and store is not None:
        regles = store.regles_pour(pc)
        # 2.5.12.10 : le code de portée du planning ; avec le libellé de const, la nuit retombait toujours sur la
        # plage de repli (22:00 → 07:00), quelles que soient les plages de l'appareil.
        if planner.de_portee(regles, planner.PORTEE_SESSION):
            return planner.is_locked(regles, moment)
    return dans_plages([{"debut": r["nuit_debut"], "fin": r["nuit_fin"]}], moment)


def seuil_detail(store, pc: dict[str, Any], moment: datetime | None = None) -> tuple[int, dict[str, Any]]:
    """2.5.12.10 : le seuil du moment (relatif) et d'où il vient : {"type": "general"} ou {"type": "plage", "id", "nom"}.

    Mode « par plage » : parmi les plages Vision actives à cet instant qui ont un seuil, la plus stricte (la plus
    basse) ; aucune : le seuil général."""
    from . import planner

    r = reglages(pc)
    if r["seuil_mode"] == "plages" and r["seuils_plages"]:
        moment = moment or dt_util.now()
        regles = store.regles_pour(pc) if store is not None else (pc.get("rules") or [])
        retenue = None
        for regle in planner._actives(regles):
            ident = str(regle.get("id"))
            if ident in r["seuils_plages"] and planner.rule_covers(regle, moment):
                if retenue is None or r["seuils_plages"][ident] < retenue[0]:
                    retenue = (r["seuils_plages"][ident], {"type": "plage", "id": ident, "nom": regle.get("name") or ident})
        if retenue is not None:
            return retenue
    return int(r["seuil_db"]), {"type": "general"}


def seuil_effectif(store, pc: dict[str, Any], moment: datetime | None = None) -> int:
    return seuil_detail(store, pc, moment)[0]


def seuils_plages_lisibles(pc: dict[str, Any]) -> list[dict[str, Any]]:
    """Chaque plage de l'appareil avec son seuil en dBFS (None : le seuil général s'applique)."""
    r = reglages(pc)
    return [{"id": str(x.get("id")), "nom": x.get("name") or str(x.get("id")),
             "seuil_dbfs": (r["seuils_plages"][str(x.get("id"))] - 100) if str(x.get("id")) in r["seuils_plages"] else None}
            for x in pc.get("rules") or []]


# ----------------------------------------------------------------------------- Plancher mesuré (2.5.12.10)


def noter_plancher(e: dict[str, Any], niveau: float | None, t: float) -> None:
    """Un échantillon de la moyenne sur 1 min, toutes les PLANCHER_PAS_S au plus, gardé PLANCHER_FENETRE_S."""
    if niveau is None:
        return
    ech = [x for x in (e.get("plancher_ech") or []) if isinstance(x, list) and len(x) == 2 and t - float(x[0]) < PLANCHER_FENETRE_S]
    if not ech or t - float(ech[-1][0]) >= PLANCHER_PAS_S:
        ech.append([round(t), round(float(niveau), 1)])
    e["plancher_ech"] = ech


def plancher_mesure_db(e: dict[str, Any]) -> float | None:
    """Le plancher mesuré (relatif) : le bas (10 %) des moyennes sur 1 min des 24 dernières heures ; None avant une heure."""
    valeurs = sorted(float(x[1]) for x in (e.get("plancher_ech") or []) if isinstance(x, list) and len(x) == 2)
    if len(valeurs) < PLANCHER_MIN_ECH:
        return None
    return round(valeurs[int(PLANCHER_QUANTILE * (len(valeurs) - 1))], 1)


def plancher_effectif_db(pc: dict[str, Any], e: dict[str, Any] | None = None) -> float | None:
    """Le plancher qui sert aux marges : celui saisi à la main, sinon le mesuré (relatif)."""
    manuel = reglages(pc)["plancher_manuel_dbfs"]
    if manuel is not None:
        return float(manuel) + 100
    return plancher_mesure_db(e if e is not None else etat(pc))


# ----------------------------------------------------------------------------- Relevé


def pour_releve(store, pc: dict[str, Any]) -> dict[str, Any]:
    """Le bloc `son` de la réponse au relevé : `{"actif": false}` suffit à arrêter la mesure de l'appareil.

    `seuil_db` est le seuil **du moment** (jour ou nuit) : l'appareil n'a pas à connaître le planning."""
    if not mesure_active(store, pc):
        return {"actif": False}
    r = reglages(pc)
    e = etat(pc)
    nuit = est_nuit(store, pc)
    return {
        "actif": True, "seuil_db": seuil_effectif(store, pc), "nuit": nuit,
        "sensibilite_s": r["sensibilite_s"], "plages": r["plages"], "calibration_db": r["calibration_db"],
        "micro_fige_s": r["micro_fige_s"], "micro_plancher_dbfs": r["micro_plancher_dbfs"],
        "micros_exclus": r["micros_exclus"],
        "alertes": int(e["alertes"]), "alertes_avant_coupure": r["alertes_avant_coupure"],
    }


def _nombre(v: Any, bas: float = 0, haut: float = 130) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return round(min(haut, max(bas, f)), 1) if f == f else None


def dbfs(niveau: Any) -> float | None:
    """dB relatifs (0 à 100) → dBFS (−100 à 0), pour le capteur et les réglages de Home Assistant."""
    n = _nombre(niveau)
    return None if n is None else round(n - 100.0, 1)


def par_micro(e: dict[str, Any]) -> list[dict[str, Any]]:
    """Agent 1.93.5 : chaque micro pour les capteurs, niveau en dBFS (None : muet, silence ou sans données)."""
    return [{"nom": d.get("nom"), "etat": d.get("etat"), "niveau_dbfs": dbfs(d.get("niveau"))}
            for d in (e.get("micros_detail") or []) if isinstance(d, dict)]


def _noter(e: dict[str, Any], quand: float, genre: str, db: float | None, **plus: Any) -> None:
    """2.5.12.7 : un avertissement, une coupure ou un dépassement sans réaction, gardé dans l'état (les 20 derniers)."""
    entree = {"ts": round(float(quand), 1), "type": genre, "niveau_dbfs": dbfs(db), **plus}
    e["journal"] = (list(e.get("journal") or []) + [entree])[-JOURNAL_MAX:]


def journal_lisible(e: dict[str, Any]) -> list[dict[str, Any]]:
    """Le journal pour le capteur : l'heure locale à la place de l'horodatage."""
    sortie = []
    for x in e.get("journal") or []:
        if isinstance(x, dict):
            d = {k: v for k, v in x.items() if k != "ts"}
            d["heure"] = dt_util.as_local(dt_util.utc_from_timestamp(float(x.get("ts") or 0))).isoformat(timespec="seconds")
            sortie.append(d)
    return sortie


def _message(hass: HomeAssistant, store, pc: dict[str, Any], cle: str, **champs: Any) -> None:
    """Un message à l'écran de l'appareil, dans sa langue (l'agent PC l'affiche en notification Windows)."""
    try:
        langue = textes.pour(hass, pc)
        store.add_message(str(pc.get("id")), textes.t(cle, langue, **champs), titre=textes.t("son.message_titre", langue),
                          urgent=False)
    except Exception as err:  # noqa: BLE001 - le relevé passe avant tout
        _LOGGER.info("Vision : message « %s » non déposé sur %s : %s", cle, pc.get("id"), err)


def _prevenir_responsables(hass: HomeAssistant, store, pc: dict[str, Any], niveau: float | None, n_jour: int,
                           cle: str = "son.responsable", **champs: Any) -> None:
    """2.5.12.3 : les responsables, par leurs canaux « alertes » (comme protection.py), dans leur langue."""
    from . import canaux, roles

    nom = str(pc.get("name") or pc.get("id"))
    phrase = textes.Phrase(cle, nom=nom, n=n_jour, niveau=(dbfs(niveau) if niveau is not None else "?"), **champs)
    langue_ha = textes.ha(hass)
    titre_ha = textes.t("son.responsable_titre", langue_ha)
    hass.async_create_task(canaux.compagnon_simple(hass, store, "alertes", titre_ha, phrase.en(langue_ha), tag=f"vision_son_{pc.get('id')}"))
    hass.async_create_task(canaux.discord(hass, store, "alertes", titre_ha, phrase.en(langue_ha)))
    for pid in sorted(set(roles.avec(store, "gerer")) | set(roles.avec(store, "approuver"))):
        if pid == pc.get("id") or not canaux.vision_pour(hass, store, pid, "alertes"):
            continue
        langue = textes.pour(hass, store.pcs.get(pid))
        try:
            store.add_message(pid, phrase.en(langue), titre=textes.t("son.responsable_titre", langue))
        except ValueError:
            continue


def recevoir(hass: HomeAssistant, store, pc: dict[str, Any], bloc: Any, maintenant: float | None = None) -> bool:
    """Ce que l'appareil a mesuré. Rend True si le magasin a changé (alerte comptée, coupure, remise à zéro).

    Seuls des nombres sont lus (niveau, maximum, pic, instant, micros, horodatage et niveau de chaque dépassement) :
    tout autre champ est ignoré, et rien d'autre n'est gardé.
    """
    t = time.time() if maintenant is None else maintenant
    e = etat(pc)
    change = False
    r = reglages(pc)
    # Remise à zéro après une période calme (même sans relevé du module, pour ne pas garder un compteur périmé).
    if e["alertes"] and t - float(e["dernier"] or 0) >= r["calme_min"] * 60:
        e["alertes"] = 0
        change = True
    if e["niveau_alerte"] and t - float(e["dernier"] or 0) >= r["ferme_apres_min"] * 60:
        e["niveau_alerte"] = 0  # la gradation repart du message doux
        change = True
    if not isinstance(bloc, dict) or not mesure_active(store, pc):
        return change
    niveau = _nombre(bloc.get("niveau"))
    # 2.5.12.3 (l'auteur, précision) : l'agent ne fait la moyenne que sur les micros allumés (muets, coupés, débranchés, silence
    # numérique ou niveau figé exclus) et dit lesquels. Aucun micro actif : plus de niveau (capteur indisponible,
    # jamais 0) ; les noms sont bornés, rien d'autre n'est gardé.
    if "micros_actifs" in bloc:
        actifs = _nombre(bloc.get("micros_actifs"), 0, 64)
        e["micros_actifs"] = int(actifs) if actifs is not None else None
        liste = bloc.get("micros_liste") if isinstance(bloc.get("micros_liste"), list) else []
        e["micros_liste"] = [str(n)[:80] for n in liste[:16] if isinstance(n, str)]
        if e["micros_actifs"] == 0:
            niveau = None
            e["niveau"] = e["maximum"] = e["pic"] = e["instant"] = None
            micros = _nombre(bloc.get("micros"), 0, 64)
            e["micros"] = int(micros) if micros is not None else None
            e["maj"] = t
    # Agent 1.93.5 : chaque micro, avec son état et son niveau (dB relatifs) ; bornés, rien d'autre n'est gardé.
    if "micros_detail" in bloc:
        detail = bloc.get("micros_detail") if isinstance(bloc.get("micros_detail"), list) else []
        e["micros_detail"] = [
            {"nom": d["nom"][:80], "etat": d.get("etat") if d.get("etat") in ETATS_MICRO else "sans_donnees",
             "niveau": _nombre(d.get("niveau"))}
            for d in detail[:64] if isinstance(d, dict) and isinstance(d.get("nom"), str)
        ][:16]
    if niveau is not None:
        noter_plancher(e, niveau, t)
        e["niveau"] = niveau
        e["maximum"] = _nombre(bloc.get("maximum"))
        e["pic"] = _nombre(bloc.get("pic10"))
        e["instant"] = _nombre(bloc.get("instant"))
        micros = _nombre(bloc.get("micros"), 0, 64)
        e["micros"] = int(micros) if micros is not None else None
        e["maj"] = t
    nom = str(pc.get("name") or pc.get("nom") or pc.get("id"))
    moment = dt_util.now()
    aujourd_hui = dt_util.as_local(dt_util.utc_from_timestamp(t)).date().isoformat()
    if e["jour"] != aujourd_hui:
        e["jour"], e["aujourd_hui"] = aujourd_hui, 0
        change = True
    depassements = bloc.get("depassements") if isinstance(bloc.get("depassements"), list) else []
    for d in depassements[:MAX_DEPASSEMENTS]:
        if not isinstance(d, dict):
            continue
        quand = _nombre(d.get("ts"), 0, 1e13)
        if quand is None:
            continue
        quand = quand / 1000 if quand > 1e11 else quand  # millisecondes de l'appareil, ou secondes
        if quand <= float(e["vu_jusqua"] or 0) or quand > t + 300:
            continue
        e["vu_jusqua"] = quand
        change = True
        instant = dt_util.as_local(dt_util.utc_from_timestamp(quand)) if quand else moment
        if not dans_plages(r["plages"], instant):
            continue
        # 2.5.12.3 : pas deux alertes à moins de repos_min l'une de l'autre (l'appareil en envoie une par dépassement).
        dernier = float(e["dernier"] or 0)
        if dernier and quand - dernier < r["repos_min"] * 60:
            continue
        db = _nombre(d.get("db"))
        seuil = seuil_effectif(store, pc, instant)
        if not r["reagir"]:
            # 2.5.12.7 : mesure seule. Compté et journalisé, sans message, sans responsables, sans coupure.
            e["aujourd_hui"] = int(e["aujourd_hui"]) + 1
            e["dernier"] = quand
            _noter(e, quand, "depassement", db, seuil_dbfs=seuil - 100)
            _LOGGER.info("Vision : bruit sur « %s » (%s dBFS, seuil %s dBFS), sans réaction (mesure seule).",
                         nom, dbfs(db), seuil - 100)
            continue
        rapproche = bool(dernier) and quand - dernier <= r["ferme_apres_min"] * 60
        e["niveau_alerte"] = min(NIVEAU_RESPONSABLE, int(e["niveau_alerte"]) + 1) if rapproche else NIVEAU_DOUX
        e["alertes"] = int(e["alertes"]) + 1
        e["aujourd_hui"] = int(e["aujourd_hui"]) + 1
        e["dernier"] = quand
        n, seuil_coupure = e["alertes"], r["alertes_avant_coupure"]
        hass.bus.async_fire(EV_SEUIL, {
            "appareil": pc.get("id"), "nom": nom, "niveau": db, "niveau_dbfs": dbfs(db), "seuil": seuil,
            "seuil_dbfs": seuil - 100, "nuit": est_nuit(store, pc, instant), "alerte": n,
            "alertes_avant_coupure": seuil_coupure, "gradation": e["niveau_alerte"], "aujourd_hui": e["aujourd_hui"],
        })
        if seuil_coupure and n >= seuil_coupure:
            # 2.5.12.7 : la coupure, comme une fermeture Vision de N minutes (écran de blocage, réouverture seule à
            # l'échéance) ; un message à l'écran, les responsables prévenus ; la gradation repart de zéro.
            store.set_override(pc, MODE_LOCKED, r["coupure_min"])
            fin_coupure = time.time() + r["coupure_min"] * 60
            langue = textes.pour(hass, pc)
            _message(hass, store, pc, "son.coupure", n=n, minutes=r["coupure_min"], heure=textes.heure(fin_coupure, langue))
            if r["prevenir_responsable"]:
                _prevenir_responsables(hass, store, pc, db, e["aujourd_hui"], cle="son.responsable_coupure",
                                       minutes=r["coupure_min"], heure=textes.heure(fin_coupure, textes.ha(hass)))
            hass.bus.async_fire(EV_COUPURE, {"appareil": pc.get("id"), "nom": nom, "minutes": r["coupure_min"], "alertes": n})
            _noter(e, quand, "coupure", db, rang=n, seuil_dbfs=seuil - 100, minutes=r["coupure_min"])
            _LOGGER.warning("Vision : trop de bruit sur « %s » : coupure %d min après %d avertissements (%s dBFS, seuil %s dBFS).",
                            nom, r["coupure_min"], n, dbfs(db), seuil - 100)
            e["alertes"] = 0
            e["niveau_alerte"] = 0
            continue
        # La réaction graduée. Les PC n'ont pas d'alerte locale : c'est ce message qui s'affiche.
        _message(hass, store, pc, "son.doux" if e["niveau_alerte"] == NIVEAU_DOUX else "son.ferme")
        _noter(e, quand, "avertissement", db, rang=n, seuil_dbfs=seuil - 100)
        _LOGGER.warning("Vision : bruit sur « %s » : avertissement %d%s (%s dBFS, seuil %s dBFS).", nom, n,
                        f"/{seuil_coupure}" if seuil_coupure else "", dbfs(db), seuil - 100)
        if e["niveau_alerte"] >= NIVEAU_RESPONSABLE and r["prevenir_responsable"]:
            _prevenir_responsables(hass, store, pc, db, e["aujourd_hui"])
            _LOGGER.warning("Vision : trop de bruit sur « %s » (%s dBFS, %d fois aujourd'hui) : responsables prévenus.",
                            nom, dbfs(db), e["aujourd_hui"])
    return change
