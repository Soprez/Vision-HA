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
    "micro_plancher_dbfs": -90,  # un micro plus bas que ça (avant calibration) ne capte rien : compté comme éteint (l'auteur, agent 1.93.5)
}
# Un PC (agent Windows, 2.5.12.3) : des alertes et des messages, jamais de coupure par défaut ; 3 s suffisent.
DEFAUTS_PC: dict[str, Any] = {"alertes_avant_coupure": 0, "sensibilite_s": 3, "repos_min": 2}
BORNES: dict[str, tuple[float, float]] = {
    "seuil_db": (30, 100), "seuil_nuit_db": (30, 100), "sensibilite_s": (1, 120), "repos_min": (0, 120),
    "ferme_apres_min": (1, 120), "alertes_avant_coupure": (0, 10), "coupure_min": (1, 240), "calme_min": (1, 240),
    "calibration_db": (-30, 30), "micro_fige_s": (2, 300), "micro_plancher_dbfs": (-120, -40),
}
ETATS_MICRO = ("actif", "muet", "silence", "sous_plancher", "fige", "sans_donnees")  # état d'un micro (agent 1.93.5)
MAX_DEPASSEMENTS = 20  # par relevé : un appareil qui s'emballe ne remplit pas le journal


def actif_module(store) -> bool:
    return not modules.coupe(store, MODULE)


def est_pc(pc: dict[str, Any]) -> bool:
    return matrice.type_appareil(pc or {}) == "pc"


def defauts(pc: dict[str, Any]) -> dict[str, Any]:
    """Les défauts de cet appareil : ceux du module, ajustés pour un PC."""
    return {**REGLAGES_DEFAUT, **(DEFAUTS_PC if est_pc(pc) else {})}


def reglages(pc: dict[str, Any]) -> dict[str, Any]:
    base = defauts(pc)
    brut = (pc.get("son") or {}).get("reglages") or {}
    r = {**base, **{k: v for k, v in dict(brut).items() if k in base}}
    for k, (bas, haut) in BORNES.items():
        try:
            r[k] = int(min(haut, max(bas, float(r[k]))))
        except (TypeError, ValueError):
            r[k] = base[k]
    r["actif"] = bool(r["actif"])
    r["prevenir_responsable"] = bool(r["prevenir_responsable"])
    r["nuit"] = "heures" if str(r.get("nuit")) == "heures" else "plages"
    for k in ("nuit_debut", "nuit_fin"):
        r[k] = str(r[k]) if _minutes(r.get(k)) is not None else base[k]
    r["plages"] = [p for p in (r["plages"] if isinstance(r["plages"], list) else []) if _plage_valide(p)]
    return r


def regler(pc: dict[str, Any], changements: dict[str, Any]) -> dict[str, Any]:
    """Pose des réglages (les clés inconnues sont ignorées, les valeurs bornées) ; rend les réglages effectifs."""
    son = pc.setdefault("son", {})
    son["reglages"] = {**(son.get("reglages") or {}), **{k: v for k, v in changements.items() if k in REGLAGES_DEFAUT}}
    son["reglages"] = reglages(pc)
    return son["reglages"]


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
        if planner.de_portee(regles, PORTEE_SESSION):
            return planner.is_locked(regles, moment)
    return dans_plages([{"debut": r["nuit_debut"], "fin": r["nuit_fin"]}], moment)


def seuil_effectif(store, pc: dict[str, Any], moment: datetime | None = None) -> int:
    r = reglages(pc)
    return int(r["seuil_nuit_db"] if est_nuit(store, pc, moment) else r["seuil_db"])


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
        "actif": True, "seuil_db": int(r["seuil_nuit_db"] if nuit else r["seuil_db"]), "nuit": nuit,
        "sensibilite_s": r["sensibilite_s"], "plages": r["plages"], "calibration_db": r["calibration_db"],
        "micro_fige_s": r["micro_fige_s"], "micro_plancher_dbfs": r["micro_plancher_dbfs"],
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


def _message(hass: HomeAssistant, store, pc: dict[str, Any], cle: str, **champs: Any) -> None:
    """Un message à l'écran de l'appareil, dans sa langue (l'agent PC l'affiche en notification Windows)."""
    try:
        langue = textes.pour(hass, pc)
        store.add_message(str(pc.get("id")), textes.t(cle, langue, **champs), titre=textes.t("son.message_titre", langue),
                          urgent=False)
    except Exception as err:  # noqa: BLE001 - le relevé passe avant tout
        _LOGGER.info("Vision : message « %s » non déposé sur %s : %s", cle, pc.get("id"), err)


def _prevenir_responsables(hass: HomeAssistant, store, pc: dict[str, Any], niveau: float | None, n_jour: int) -> None:
    """2.5.12.3 : les responsables, par leurs canaux « alertes » (comme protection.py), dans leur langue."""
    from . import canaux, roles

    nom = str(pc.get("name") or pc.get("id"))
    phrase = textes.Phrase("son.responsable", nom=nom, n=n_jour, niveau=(dbfs(niveau) if niveau is not None else "?"))
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
        rapproche = bool(dernier) and quand - dernier <= r["ferme_apres_min"] * 60
        e["niveau_alerte"] = min(NIVEAU_RESPONSABLE, int(e["niveau_alerte"]) + 1) if rapproche else NIVEAU_DOUX
        e["alertes"] = int(e["alertes"]) + 1
        e["aujourd_hui"] = int(e["aujourd_hui"]) + 1
        e["dernier"] = quand
        n, seuil_coupure = e["alertes"], r["alertes_avant_coupure"]
        seuil = int(r["seuil_nuit_db"] if est_nuit(store, pc, instant) else r["seuil_db"])
        db = _nombre(d.get("db"))
        hass.bus.async_fire(EV_SEUIL, {
            "appareil": pc.get("id"), "nom": nom, "niveau": db, "niveau_dbfs": dbfs(db), "seuil": seuil,
            "seuil_dbfs": seuil - 100, "nuit": est_nuit(store, pc, instant), "alerte": n,
            "alertes_avant_coupure": seuil_coupure, "gradation": e["niveau_alerte"], "aujourd_hui": e["aujourd_hui"],
        })
        # 2.5.12.3 : la réaction graduée. Les PC n'ont pas d'alerte locale : c'est ce message qui s'affiche.
        if e["niveau_alerte"] == NIVEAU_DOUX:
            _message(hass, store, pc, "son.doux")
        else:
            _message(hass, store, pc, "son.ferme")
        if e["niveau_alerte"] >= NIVEAU_RESPONSABLE and r["prevenir_responsable"]:
            _prevenir_responsables(hass, store, pc, db, e["aujourd_hui"])
            _LOGGER.warning("Vision : trop de bruit sur « %s » (%s dBFS, %d fois aujourd'hui) : responsables prévenus.",
                            nom, dbfs(db), e["aujourd_hui"])
        if seuil_coupure and n >= seuil_coupure:
            store.set_override(pc, MODE_LOCKED, r["coupure_min"])
            hass.bus.async_fire(EV_COUPURE, {"appareil": pc.get("id"), "nom": nom, "minutes": r["coupure_min"], "alertes": n})
            e["alertes"] = 0
    return change
