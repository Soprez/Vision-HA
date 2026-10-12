"""Réveil (lot 1) : le calcul pur des occurrences, sans Home Assistant.

Tout ce qui décide « quand sonne ce réveil » est ici, en fonctions pures testables seules
(essai/reveil_calcul_test.py) : normalisation d'un réveil, lecture d'une heure calculée, occurrences
sur un horizon, fenêtre de silence. Le module `reveil.py` y ajoute Home Assistant (magasin, entités,
services, événements, relevé).

Heures : un réveil est défini en heure LOCALE du Lieu (« 07:00 »). Une occurrence est rendue en
datetime avec fuseau ; l'appareil reçoit l'heure locale ET l'instant, et réarme en heure locale
(changement d'heure : `normaliser_local` décale une heure qui n'existe pas, 02:30 → 03:30, et prend
la première d'une heure qui existe deux fois).
"""

from __future__ import annotations

import copy
import hashlib
import json
import re
import secrets
from datetime import date, datetime, time, timedelta, timezone, tzinfo
from typing import Any

TYPES = ("hebdo", "ponctuel", "entite", "modele", "manuel")
SOURCES = ("sonnerie", "url", "ma", "video")
ETATS = ("inactif", "arme", "sonne", "reporte", "arrete", "echec", "non_synchronise")
RAISONS_ECHEC = ("source", "tv_non_allumee", "permission", "non_synchronise", "heure_invalide", "non_acquitte")

HORIZON_H = 48
NOM_DEFAUT = "Réveil"
HEURE_DEFAUT = "07:00"
JOURS_DEFAUT = [0, 1, 2, 3, 4]  # lundi → vendredi (0 = lundi, comme datetime.weekday)
BORNES_DEFAUT = ["05:00", "10:00"]
VOLUME_DEFAUT = {"debut": 10, "fin": 60, "montee_s": 180}
REPETITION_DEFAUT = {"minutes": 9, "max": 3}
NOM_MAX = 60

# Noms de jours acceptés par les services (fr, en, abrégés) → 0..6.
_JOURS = {
    "lun": 0, "lundi": 0, "mon": 0, "monday": 0,
    "mar": 1, "mardi": 1, "tue": 1, "tuesday": 1,
    "mer": 2, "mercredi": 2, "wed": 2, "wednesday": 2,
    "jeu": 3, "jeudi": 3, "thu": 3, "thursday": 3,
    "ven": 4, "vendredi": 4, "fri": 4, "friday": 4,
    "sam": 5, "samedi": 5, "sat": 5, "saturday": 5,
    "dim": 6, "dimanche": 6, "sun": 6, "sunday": 6,
}


class ReveilInvalide(ValueError):
    """Un champ de réveil refusé. `cle` sert de clé de traduction (exceptions.reveil_<cle>)."""

    def __init__(self, cle: str, message: str, **valeurs: Any) -> None:
        super().__init__(message)
        self.cle = cle
        self.valeurs = {k: str(v) for k, v in valeurs.items()}


# ----------------------------------------------------------------------------- Petits lecteurs


def lire_heure(valeur: Any, champ: str = "heure") -> str:
    """« 7:05 », « 07:05:00 », « 7h05 », time(7, 5) → « 07:05 »."""
    if isinstance(valeur, time):
        return f"{valeur.hour:02d}:{valeur.minute:02d}"
    texte = str(valeur or "").strip().lower().replace("h", ":")
    m = re.fullmatch(r"(\d{1,2}):(\d{2})(?::\d{2})?", texte) or re.fullmatch(r"(\d{1,2}):?", texte)
    if m:
        h = int(m.group(1))
        mi = int(m.group(2)) if m.lastindex and m.lastindex >= 2 else 0
        if 0 <= h <= 23 and 0 <= mi <= 59:
            return f"{h:02d}:{mi:02d}"
    raise ReveilInvalide("heure", f"Heure invalide pour « {champ} » : {valeur!r} (attendu HH:MM).", champ=champ, valeur=valeur)


def en_time(hhmm: str) -> time:
    h, m = hhmm.split(":")
    return time(int(h), int(m))


def lire_date(valeur: Any) -> str:
    if isinstance(valeur, datetime):
        return valeur.date().isoformat()
    if isinstance(valeur, date):
        return valeur.isoformat()
    try:
        return date.fromisoformat(str(valeur or "").strip()[:10]).isoformat()
    except ValueError:
        raise ReveilInvalide("date", f"Date invalide : {valeur!r} (attendu AAAA-MM-JJ).", valeur=valeur) from None


def lire_jours(valeur: Any) -> list[int]:
    """[0, 2], ["lun", "mer"], « lun,mer », 7 cases vrai/faux → liste triée de 0..6 (au moins un)."""
    if isinstance(valeur, str):
        valeur = [v for v in re.split(r"[\s,;]+", valeur) if v]
    brut = list(valeur or [])
    if len(brut) == 7 and all(isinstance(v, bool) for v in brut):
        jours = [i for i, v in enumerate(brut) if v]
    else:
        jours = []
        for v in brut:
            if isinstance(v, bool):
                raise ReveilInvalide("jours", "Jours invalides.", valeur=valeur)
            if isinstance(v, int) or str(v).strip().isdigit():
                n = int(v)
                if not 0 <= n <= 6:
                    raise ReveilInvalide("jours", f"Jour invalide : {v!r} (0 = lundi … 6 = dimanche).", valeur=v)
                jours.append(n)
            else:
                n = _JOURS.get(str(v).strip().lower().rstrip("."))
                if n is None:
                    raise ReveilInvalide("jours", f"Jour inconnu : {v!r}.", valeur=v)
                jours.append(n)
    jours = sorted(set(jours))
    if not jours:
        raise ReveilInvalide("jours", "Choisis au moins un jour.", valeur=valeur)
    return jours


def _borne(valeur: Any, bas: int, haut: int, champ: str) -> int:
    try:
        n = int(valeur)
    except (TypeError, ValueError):
        raise ReveilInvalide("nombre", f"Valeur invalide pour « {champ} » : {valeur!r}.", champ=champ, valeur=valeur) from None
    if not bas <= n <= haut:
        raise ReveilInvalide("nombre", f"« {champ} » doit être entre {bas} et {haut}.", champ=champ, valeur=valeur)
    return n


# ----------------------------------------------------------------------------- Normalisation d'un réveil


def defaut(demandeur: str, appareils: list[str] | None = None) -> dict[str, Any]:
    """Un réveil neuf : hebdo 07:00 du lundi au vendredi, sonnerie, actif."""
    return {
        "id": "",
        "nom": NOM_DEFAUT,
        "demandeur": demandeur,
        "appareils": list(appareils or []),
        "actif": True,
        "declenchement": {"type": "hebdo", "heure": HEURE_DEFAUT, "jours": list(JOURS_DEFAUT), "date": "",
                          "entite": "", "attribut": "", "modele": "", "decalage": 0,
                          "bornes": list(BORNES_DEFAUT), "repli": None},
        "sauter": [],
        "source": {"type": "sonnerie", "uri": "", "lecteur": "", "libelle": ""},
        "volume": dict(VOLUME_DEFAUT),
        "repetition": dict(REPETITION_DEFAUT),
        "impose": False,
        "allumer_tv": True,
    }


def nouvel_id() -> str:
    return "rv" + secrets.token_hex(4)


def _declenchement(brut: dict[str, Any], avant: dict[str, Any]) -> dict[str, Any]:
    d = dict(avant)
    d.update({k: v for k, v in brut.items() if v is not None or k in ("repli", "bornes")})
    t = str(d.get("type") or "hebdo")
    if t not in TYPES:
        raise ReveilInvalide("type", f"Type de déclenchement inconnu : {t!r} ({', '.join(TYPES)}).", valeur=t)
    sortie: dict[str, Any] = {"type": t}
    sortie["heure"] = lire_heure(d.get("heure") or HEURE_DEFAUT)
    sortie["jours"] = lire_jours(d.get("jours") if d.get("jours") not in (None, "", []) else JOURS_DEFAUT)
    sortie["date"] = lire_date(d["date"]) if d.get("date") else ""
    sortie["entite"] = str(d.get("entite") or "").strip()
    sortie["attribut"] = str(d.get("attribut") or "").strip()
    sortie["modele"] = str(d.get("modele") or "")
    sortie["decalage"] = _borne(d.get("decalage") or 0, -720, 720, "decalage")
    bornes = d.get("bornes")
    if bornes in (None, "", []):
        sortie["bornes"] = None
    else:
        if not isinstance(bornes, (list, tuple)) or len(bornes) != 2:
            raise ReveilInvalide("bornes", "Les bornes sont deux heures [min, max].", valeur=bornes)
        b0, b1 = lire_heure(bornes[0], "bornes"), lire_heure(bornes[1], "bornes")
        if b0 > b1:
            raise ReveilInvalide("bornes", "La borne basse doit précéder la borne haute.", valeur=bornes)
        sortie["bornes"] = [b0, b1]
    repli = d.get("repli")
    sortie["repli"] = None if repli in (None, "", "aucune", "none") else lire_heure(repli, "repli")
    if t == "ponctuel" and not sortie["date"]:
        raise ReveilInvalide("date", "Un réveil ponctuel a besoin d'une date.", valeur="")
    if t == "entite" and "." not in sortie["entite"]:
        raise ReveilInvalide("entite", "Un réveil « entité » a besoin d'une entité horodatée.", valeur=sortie["entite"])
    if t == "modele" and not sortie["modele"].strip():
        raise ReveilInvalide("modele", "Un réveil « modèle » a besoin d'un modèle.", valeur="")
    return sortie


def _source(brut: dict[str, Any], avant: dict[str, Any]) -> dict[str, Any]:
    s = {**avant, **{k: v for k, v in brut.items() if v is not None}}
    t = str(s.get("type") or "sonnerie")
    if t not in SOURCES:
        raise ReveilInvalide("source", f"Source inconnue : {t!r} ({', '.join(SOURCES)}).", valeur=t)
    uri = str(s.get("uri") or "").strip()
    lecteur = str(s.get("lecteur") or "").strip()
    if t in ("url", "video") and not re.match(r"^https?://", uri, re.I):
        raise ReveilInvalide("source_uri", "Une source « url » ou « vidéo » a besoin d'une adresse http(s).", valeur=uri)
    if t == "ma" and not uri:
        raise ReveilInvalide("source_uri", "Une source Music Assistant a besoin d'un titre, d'une playlist ou d'une radio.", valeur=uri)
    if lecteur and not lecteur.startswith("media_player."):
        raise ReveilInvalide("source_lecteur", "Le lecteur doit être une entité media_player.", valeur=lecteur)
    return {"type": t, "uri": "" if t == "sonnerie" else uri, "lecteur": lecteur if t == "ma" else "",
            "libelle": str(s.get("libelle") or "").strip()[:80]}


def normaliser(brut: dict[str, Any], avant: dict[str, Any] | None = None) -> dict[str, Any]:
    """Applique `brut` (complet ou partiel) sur `avant` (ou le défaut) et rend un réveil propre.

    Lève ReveilInvalide sur toute valeur refusée : rien n'est appliqué à moitié.
    Les champs internes (calcul, etat, synchro…) de `avant` sont conservés tels quels.
    """
    base = copy.deepcopy(avant) if avant else defaut(str(brut.get("demandeur") or ""))
    r = copy.deepcopy(base)
    if "nom" in brut:
        r["nom"] = str(brut.get("nom") or "").strip()[:NOM_MAX] or NOM_DEFAUT
    if "demandeur" in brut:
        r["demandeur"] = str(brut.get("demandeur") or "").strip()
    if not str(r.get("demandeur") or "").startswith("person."):
        raise ReveilInvalide("demandeur", "Un réveil appartient à une personne (person.*).", valeur=r.get("demandeur"))
    if "appareils" in brut:
        r["appareils"] = list(dict.fromkeys(str(a) for a in (brut.get("appareils") or []) if str(a)))
    if "actif" in brut:
        r["actif"] = bool(brut["actif"])
    if "impose" in brut:
        r["impose"] = bool(brut["impose"])
    if "allumer_tv" in brut:
        r["allumer_tv"] = bool(brut["allumer_tv"])
    if isinstance(brut.get("declenchement"), dict) or "declenchement" not in brut:
        r["declenchement"] = _declenchement(dict(brut.get("declenchement") or {}), base.get("declenchement") or defaut("x")["declenchement"])
    else:
        raise ReveilInvalide("type", "Le déclenchement doit être un dictionnaire.", valeur=brut.get("declenchement"))
    if "source" in brut:
        src = brut["source"]
        if isinstance(src, str):
            src = {"type": src}
        if not isinstance(src, dict):
            raise ReveilInvalide("source", "Source invalide.", valeur=src)
        r["source"] = _source(src, base.get("source") or defaut("x")["source"])
    else:
        r["source"] = _source({}, base.get("source") or defaut("x")["source"])
    vol = {**VOLUME_DEFAUT, **(base.get("volume") or {}), **dict(brut.get("volume") or {})}
    r["volume"] = {"debut": _borne(vol["debut"], 0, 100, "volume.debut"), "fin": _borne(vol["fin"], 0, 100, "volume.fin"),
                   "montee_s": _borne(vol["montee_s"], 0, 1800, "volume.montee_s")}
    rep = {**REPETITION_DEFAUT, **(base.get("repetition") or {}), **dict(brut.get("repetition") or {})}
    r["repetition"] = {"minutes": _borne(rep["minutes"], 1, 30, "repetition.minutes"), "max": _borne(rep["max"], 0, 10, "repetition.max")}
    if "sauter" in brut:
        r["sauter"] = sorted({lire_date(d) for d in (brut.get("sauter") or [])})
    else:
        r["sauter"] = sorted({lire_date(d) for d in (r.get("sauter") or [])})
    return r


# ----------------------------------------------------------------------------- Heures locales


def normaliser_local(jour: date, hhmm: str, tz: tzinfo) -> datetime:
    """La date et l'heure locales en datetime avec fuseau, correctes aux changements d'heure.

    Une heure qui n'existe pas (passage à l'heure d'été, 02:30) devient l'heure qui suit (03:30) ;
    une heure qui existe deux fois (passage à l'heure d'hiver) est la première.
    """
    # fold=0 : une heure inexistante prend le décalage d'avant le changement ; l'aller-retour par UTC la
    # ramène à une heure réelle (02:30 → 03:30). Une heure normale ou double ne bouge pas (la première).
    brut = datetime.combine(jour, en_time(hhmm)).replace(tzinfo=tz, fold=0)
    return brut.astimezone(timezone.utc).astimezone(tz)


def lire_horodatage(texte: Any, maintenant: datetime) -> datetime | None:
    """Une heure lue dans une entité → datetime avec fuseau, ou None.

    Accepte : ISO avec fuseau (capteur timestamp), « AAAA-MM-JJ HH:MM[:SS] » sans fuseau (input_datetime,
    attribut start_time d'un agenda : heure locale), « HH:MM[:SS] » seul (input_datetime heure : la
    prochaine fois que cette heure arrive). Une date seule, « unknown », « unavailable » → None.
    """
    if texte is None:
        return None
    if isinstance(texte, datetime):
        dt = texte
    else:
        s = str(texte).strip()
        if not s or s.lower() in ("unknown", "unavailable", "none", "null"):
            return None
        if re.fullmatch(r"\d{1,2}:\d{2}(:\d{2})?", s):
            hhmm = lire_heure(s)
            jour = maintenant.date()
            dt = normaliser_local(jour, hhmm, maintenant.tzinfo)  # type: ignore[arg-type]
            if dt <= maintenant:
                dt = normaliser_local(jour + timedelta(days=1), hhmm, maintenant.tzinfo)  # type: ignore[arg-type]
            return dt
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", s):
            return None
        try:
            dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        except ValueError:
            return None
    if dt.tzinfo is None:
        dt = normaliser_local(dt.date(), f"{dt.hour:02d}:{dt.minute:02d}", maintenant.tzinfo)  # type: ignore[arg-type]
    return dt.astimezone(maintenant.tzinfo).replace(second=0, microsecond=0)


def appliquer_calcul(declenchement: dict[str, Any], valeur: datetime | None, maintenant: datetime) -> dict[str, Any]:
    """Pour les types entite / modele : l'heure retenue à partir de la valeur lue.

    Rend {"heure": iso ou "", "raison": calculee|repli|passee|invalide, "valeur": iso brute ou ""}.
    - valeur + décalage, si elle tombe dans les bornes (heure locale) → « calculee » ;
    - sinon l'heure de repli (le jour de la valeur, ou la prochaine fois) → « repli » ;
    - sans repli → « invalide » (pas d'occurrence ; l'événement d'échec part la veille) ;
    - une valeur valide mais déjà passée → « passee » (rien à sonner, pas d'échec : la source se mettra à jour).
    """
    tz = maintenant.tzinfo
    brute = valeur.isoformat() if valeur else ""
    retenue: datetime | None = None
    if valeur is not None:
        retenue = valeur + timedelta(minutes=int(declenchement.get("decalage") or 0))
        bornes = declenchement.get("bornes")
        if bornes:
            hhmm = f"{retenue.hour:02d}:{retenue.minute:02d}"
            if not bornes[0] <= hhmm <= bornes[1]:
                retenue = None
        if retenue is not None:
            if retenue <= maintenant:
                return {"heure": "", "raison": "passee", "valeur": brute}
            return {"heure": retenue.isoformat(), "raison": "calculee", "valeur": brute}
    repli = declenchement.get("repli")
    if repli:
        jour = (valeur.astimezone(tz).date() if valeur is not None else maintenant.date())  # type: ignore[union-attr]
        dt = normaliser_local(jour, repli, tz)  # type: ignore[arg-type]
        if dt <= maintenant:
            dt = normaliser_local(maintenant.date() + timedelta(days=1), repli, tz)  # type: ignore[arg-type]
            if dt <= maintenant:
                dt = normaliser_local(maintenant.date() + timedelta(days=2), repli, tz)  # type: ignore[arg-type]
        return {"heure": dt.isoformat(), "raison": "repli", "valeur": brute}
    return {"heure": "", "raison": "invalide", "valeur": brute}


# ----------------------------------------------------------------------------- Occurrences


def occurrences(reveil: dict[str, Any], maintenant: datetime, horizon_h: int = HORIZON_H,
                inclure_sautees: bool = False) -> list[dict[str, Any]]:
    """Les occurrences à venir (strictement après `maintenant`, au plus `horizon_h` heures après).

    Chaque occurrence : {"id": "<reveil>@<date>", "date", "heure" (iso avec fuseau), "heure_locale", "raison",
    "sautee"}. Un réveil inactif ou manuel n'en a aucune. Les dates de `sauter` sont retirées (ou marquées
    `sautee` avec inclure_sautees=True). Pour entite / modele, l'heure vient de `reveil["calcul"]`, posé par HA.
    """
    if not reveil.get("actif", True):
        return []
    d = reveil.get("declenchement") or {}
    t = d.get("type")
    tz = maintenant.tzinfo
    fin = maintenant + timedelta(hours=horizon_h)
    brutes: list[tuple[datetime, str]] = []
    if t == "hebdo":
        jours = set(d.get("jours") or [])
        for k in range(0, horizon_h // 24 + 2):
            jour = maintenant.date() + timedelta(days=k)
            if jour.weekday() in jours:
                brutes.append((normaliser_local(jour, d.get("heure") or HEURE_DEFAUT, tz), "hebdo"))  # type: ignore[arg-type]
    elif t == "ponctuel" and d.get("date"):
        brutes.append((normaliser_local(date.fromisoformat(d["date"]), d.get("heure") or HEURE_DEFAUT, tz), "ponctuel"))  # type: ignore[arg-type]
    elif t in ("entite", "modele"):
        calc = reveil.get("calcul") or {}
        if calc.get("heure"):
            try:
                brutes.append((datetime.fromisoformat(calc["heure"]).astimezone(tz), str(calc.get("raison") or "calculee")))
            except ValueError:
                pass
    sauter = set(reveil.get("sauter") or [])
    sortie = []
    for dt, raison in sorted(brutes):
        if not maintenant < dt <= fin:
            continue
        jour = dt.date().isoformat()
        sautee = jour in sauter
        if sautee and not inclure_sautees:
            continue
        sortie.append({"id": f"{reveil.get('id')}@{jour}", "date": jour, "heure": dt.isoformat(),
                       "heure_locale": f"{dt.hour:02d}:{dt.minute:02d}", "raison": "sautee" if sautee else raison,
                       "sautee": sautee})
    return sortie


def prochaine(reveil: dict[str, Any], maintenant: datetime, horizon_h: int = 24 * 8) -> dict[str, Any] | None:
    """La prochaine occurrence réellement armée (hors sautées), sur une semaine et un jour."""
    liste = occurrences(reveil, maintenant, horizon_h)
    return liste[0] if liste else None


def prochaine_date(reveil: dict[str, Any], maintenant: datetime) -> str:
    """La date de la prochaine occurrence, sautées comprises (défaut de `reveil_sauter`) ; "" sinon."""
    liste = occurrences(reveil, maintenant, 24 * 8, inclure_sautees=True)
    for o in liste:
        if not o["sautee"]:
            return o["date"]
    return ""


def fenetre(reveils: list[dict[str, Any]], maintenant: datetime, avant_min: int = 60, apres_min: int = 30) -> dict[str, Any]:
    """La fenêtre de silence : de `avant_min` avant la première occurrence armée à `apres_min` après la dernière,
    en fusionnant les occurrences proches. Rend {"actif": bool, "debut": iso|"", "fin": iso|""} pour la fenêtre en
    cours, sinon la prochaine (sur 48 h)."""
    plages: list[list[datetime]] = []
    for r in reveils:
        for o in occurrences(r, maintenant - timedelta(minutes=apres_min), HORIZON_H):
            h = datetime.fromisoformat(o["heure"])
            plages.append([h - timedelta(minutes=avant_min), h + timedelta(minutes=apres_min)])
    plages.sort()
    fusion: list[list[datetime]] = []
    for p in plages:
        if fusion and p[0] <= fusion[-1][1]:
            fusion[-1][1] = max(fusion[-1][1], p[1])
        else:
            fusion.append(p)
    for debut, fin in fusion:
        if fin > maintenant:
            return {"actif": debut <= maintenant, "debut": debut.isoformat(), "fin": fin.isoformat()}
    return {"actif": False, "debut": "", "fin": ""}


# ----------------------------------------------------------------------------- Ce qu'on envoie à l'appareil


def signature(objet: Any) -> str:
    """Une empreinte courte et stable (l'appareil la renvoie pour dire ce qu'il a armé)."""
    brut = json.dumps(objet, sort_keys=True, ensure_ascii=False, default=str)
    return hashlib.sha1(brut.encode("utf-8")).hexdigest()[:12]
