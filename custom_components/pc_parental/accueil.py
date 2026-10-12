"""Les messages d'accueil de l'appli (Vision → Configurer → Accueil).

L'administrateur écrit ses messages, un par ligne, avec des variables :

    {salutation} {prenom} {lieu} {jour} {date} {heure} {meteo} {temperature}

remplies dans la langue de l'appareil, à l'heure locale de Home Assistant. Deux façons de choisir :

- `aleatoire` : un message au hasard, le même pour un appareil pendant une tranche de 30 minutes (pas un
  message différent à chaque relevé) ; les préfixes de moment sont ignorés ;
- `moment` : selon le moment de la journée (matin 5–12 h, après-midi 12–18 h, soir 18–22 h, nuit 22–5 h). Une
  ligne qui commence par `matin:`, `apres-midi:`, `soir:` ou `nuit:` (ou `morning:`, `afternoon:`, `evening:`,
  `night:`, et les mots courants des autres langues, voir PREFIXES) ne vaut que pour ce moment ; une ligne sans
  préfixe vaut pour tous. Plusieurs lignes possibles : même tirage stable que le mode aléatoire.

Pour les experts : un modèle Jinja Home Assistant, évalué ici avec les mêmes variables. En cas d'erreur, de
résultat vide ou trop long, on revient au message normal et on le note au journal.

Rien de configuré : le message par défaut, « {salutation} ! » dans la langue de l'appareil. Jamais « la
famille » (règle de vocabulaire neutre).

Stockage : `store.accueil` = {"messages": str, "mode": str, "meteo": str, "modele": str} ; absent = défaut.
"""
from __future__ import annotations

import hashlib
import logging
import re
import unicodedata
from datetime import datetime
from typing import Any

from . import langues, textes

_LOGGER = logging.getLogger(__name__)

ALEATOIRE, MOMENT = "aleatoire", "moment"
MODES = (ALEATOIRE, MOMENT)
MATIN, APRES_MIDI, SOIR, NUIT = "matin", "apres_midi", "soir", "nuit"
MOMENTS = (MATIN, APRES_MIDI, SOIR, NUIT)
LONGUEUR_MAX = 200
TRANCHE_S = 30 * 60
HEURE_SOIR = 18  # « Bonsoir » à partir de 18 h (et jusqu'à 5 h)

# Les préfixes reconnus, sans accents, ni majuscules, ni espaces, ni tirets (« Après-midi » → « apresmidi »).
PREFIXES: dict[str, str] = {
    # matin
    "matin": MATIN, "morning": MATIN, "morgen": MATIN, "vormittag": MATIN, "manana": MATIN, "mattina": MATIN,
    "mattino": MATIN, "ochtend": MATIN,
    # après-midi
    "apresmidi": APRES_MIDI, "aprem": APRES_MIDI, "afternoon": APRES_MIDI, "nachmittag": APRES_MIDI,
    "tarde": APRES_MIDI, "pomeriggio": APRES_MIDI, "middag": APRES_MIDI,
    # soir
    "soir": SOIR, "soiree": SOIR, "anochecer": SOIR, "evening": SOIR, "abend": SOIR, "sera": SOIR, "avond": SOIR,
    # nuit
    "nuit": NUIT, "night": NUIT, "nacht": NUIT, "noche": NUIT, "notte": NUIT,
}

# Un exemple de modèle pour l'aide de l'écran (les mêmes variables, en Jinja).
EXEMPLE_MODELE = "{{ salutation }} {{ prenom }} · {{ meteo }} {{ temperature }}"

_CHAMP = re.compile(r"\{(\w+)\}")
_PREFIXE = re.compile(r"^\s*([^\W\d][\w\s\-']{1,20}?)\s*:\s*(.*)$", re.DOTALL)


# ----------------------------------------------------------------------------- réglages


def reglages(store: Any) -> dict[str, str]:
    """Les réglages d'accueil, complets et propres (absent = défaut)."""
    brut = getattr(store, "accueil", None)
    return propre(brut if isinstance(brut, dict) else {})


def propre(saisie: dict[str, Any]) -> dict[str, str]:
    """Les réglages tels qu'on les enregistre."""
    lignes = [l.rstrip() for l in str(saisie.get("messages") or "").replace("\r\n", "\n").split("\n")]
    mode = str(saisie.get("mode") or ALEATOIRE)
    meteo = str(saisie.get("meteo") or "").strip()
    return {
        "messages": "\n".join(l for l in lignes if l.strip()),
        "mode": mode if mode in MODES else ALEATOIRE,
        "meteo": meteo if meteo.startswith("weather.") else "",
        "modele": str(saisie.get("modele") or "").strip(),
    }


def moment(heure: int) -> str:
    """Le moment de la journée d'une heure (0–23)."""
    if 5 <= heure < 12:
        return MATIN
    if 12 <= heure < 18:
        return APRES_MIDI
    if 18 <= heure < 22:
        return SOIR
    return NUIT


def _cle_prefixe(mot: str) -> str:
    sans_accents = unicodedata.normalize("NFKD", mot).encode("ascii", "ignore").decode()
    return re.sub(r"[\s\-_']+", "", sans_accents.lower())


def lignes(messages: str) -> list[tuple[str | None, str]]:
    """Les messages, une ligne chacun : (moment ou None, texte sans son préfixe)."""
    sortie: list[tuple[str | None, str]] = []
    for ligne in str(messages or "").splitlines():
        if not ligne.strip():
            continue
        quand = None
        m = _PREFIXE.match(ligne)
        if m and _cle_prefixe(m.group(1)) in PREFIXES:
            quand, ligne = PREFIXES[_cle_prefixe(m.group(1))], m.group(2)
        if ligne.strip():
            sortie.append((quand, ligne.strip()))
    return sortie


def _tirage(graine: str, tranche: int, nombre: int) -> int:
    """Un indice stable pour un appareil et une tranche de 30 minutes."""
    empreinte = hashlib.sha256(f"{graine}|{tranche}".encode()).hexdigest()
    return int(empreinte[:12], 16) % nombre


def choisir(reglage: dict[str, str], graine: str, maintenant: datetime) -> str | None:
    """Le message brut (variables pas encore remplies) pour cet appareil maintenant, ou None (défaut)."""
    toutes = lignes(reglage.get("messages", ""))
    if reglage.get("mode") == MOMENT:
        quand = moment(maintenant.hour)
        candidats = [t for m, t in toutes if m in (None, quand)]
    else:
        candidats = [t for _m, t in toutes]
    if not candidats:
        return None
    tranche = int(maintenant.timestamp() // TRANCHE_S)
    return candidats[_tirage(graine, tranche, len(candidats))]


# ----------------------------------------------------------------------------- variables


def _entite_meteo(hass: Any, choisie: str) -> str:
    if choisie and hass.states.get(choisie) is not None:
        return choisie
    candidats = [s.entity_id for s in hass.states.async_all("weather")]
    for e in candidats:
        if "meteo_france" in e:
            return e
    return candidats[0] if candidats else ""


def _nombre(valeur: Any, langue: str) -> str:
    try:
        n = float(valeur)
    except (TypeError, ValueError):
        return ""
    texte = str(int(round(n))) if abs(n - round(n)) < 0.05 else f"{n:.1f}"
    return texte if langue == "en" else texte.replace(".", ",")


def _meteo(hass: Any, choisie: str, langue: str) -> tuple[str, str]:
    """(état traduit, température avec son unité) ; ("", "") sans entité météo."""
    eid = _entite_meteo(hass, choisie)
    etat = hass.states.get(eid) if eid else None
    if etat is None or etat.state in ("unknown", "unavailable"):
        return "", ""
    cle = f"meteo.{etat.state}"
    nom = textes.t(cle, langue)
    if nom == cle:
        nom = str(etat.state)
    valeur = _nombre(etat.attributes.get("temperature"), langue)
    unite = str(etat.attributes.get("temperature_unit") or "°C")
    temperature = textes.t("format.temperature", langue, valeur=valeur, unite=unite) if valeur else ""
    return nom, temperature


def prenom(hass: Any, coord: Any, pc: dict[str, Any] | None) -> str:
    """Le prénom de la personne de l'appareil ; vide pour un écran commun (ou sans personne)."""
    if not pc:
        return ""
    from . import veille_tableaux

    qui = veille_tableaux.profil(hass, coord, pc)
    if qui["public"] == "affichage" or not qui["personne"]:
        return ""
    etat = hass.states.get(qui["personne"])
    return str(etat.name) if etat is not None and etat.name else ""


def variables(hass: Any, store: Any, langue: str, maintenant: datetime, nom: str = "") -> dict[str, str]:
    """Les valeurs des variables, dans une langue, à un instant (heure locale)."""
    from . import lieu

    reglage = reglages(store)
    meteo, temperature = _meteo(hass, reglage["meteo"], langue)
    soir = maintenant.hour >= HEURE_SOIR or maintenant.hour < 5
    return {
        "salutation": textes.t("accueil.salutation_soir" if soir else "accueil.salutation_jour", langue),
        "prenom": nom,
        "lieu": lieu.nom(store, langue),
        "jour": textes.t(f"format.jour_{maintenant.isoweekday()}", langue),
        "date": textes.t("accueil.date", langue, j=maintenant.day, mois=textes.t(f"format.mois_{maintenant.month}", langue)),
        "heure": textes.heure(maintenant, langue),
        "meteo": meteo,
        "temperature": temperature,
    }


# ----------------------------------------------------------------------------- rendu


_BLANCS = re.compile(r"[ \t  ]+")


def _blanc(m: re.Match) -> str:
    morceau = m.group(0)
    for insecable in (" ", " "):
        if insecable in morceau:
            return insecable
    return " "


def nettoyer(texte: str, langue: str) -> str:
    """Une phrase propre malgré un champ vide : espaces doubles, ponctuation orpheline.

    « Bonjour  ! » → « Bonjour ! » ; « Bonjour , » → « Bonjour, » ; « Hello, ! » → « Hello! »."""
    s = " ".join(str(texte or "").splitlines())
    s = _BLANCS.sub(_blanc, s).strip()
    # Une virgule orpheline (avant un point final, un !, un ?, ou en fin de phrase) disparaît.
    s = re.sub(r"[   ]*[,;]([   ]*)(?=[!?.…]|$)", r"\1", s)
    s = re.sub(r"^[,;:]\s*", "", s)
    # Pas d'espace avant une virgule ou un point ; en français, l'espace avant ! ? ; : reste (typographie).
    s = re.sub(r"[   ]+(?=[,.…)])", "", s)
    if langue != "fr":
        s = re.sub(r"[   ]+(?=[!?;:])", "", s)
    s = re.sub(r"([(¡¿])[   ]+", r"\1", s)
    return _BLANCS.sub(_blanc, s).strip()


def remplir(modele: str, valeurs: dict[str, str]) -> str:
    """Remplit les variables connues ; un champ inconnu reste tel quel."""
    return _CHAMP.sub(lambda m: str(valeurs[m.group(1)]) if m.group(1) in valeurs else m.group(0), modele)


def valider_modele(hass: Any, modele: str) -> str | None:
    """None si le modèle Jinja se compile ; sinon le message d'erreur (pour l'écran)."""
    if not str(modele or "").strip():
        return None
    from homeassistant.exceptions import TemplateError
    from homeassistant.helpers.template import Template

    try:
        Template(str(modele), hass).ensure_valid()
    except TemplateError as err:
        return str(err)[:300]
    return None


def _jinja(hass: Any, modele: str, valeurs: dict[str, str]) -> str | None:
    """Le rendu du modèle, ou None (erreur, vide, trop long : on prend le message normal)."""
    from homeassistant.helpers.template import Template

    try:
        rendu = Template(modele, hass).async_render(variables=dict(valeurs), parse_result=False)
    except Exception as err:  # noqa: BLE001 - un modèle d'expert ne casse jamais l'accueil
        _LOGGER.warning("Accueil : le modèle a échoué (%s) ; message normal à la place.", err)
        return None
    rendu = str(rendu or "").strip()
    if not rendu:
        _LOGGER.warning("Accueil : le modèle a rendu un texte vide ; message normal à la place.")
        return None
    if len(rendu) > LONGUEUR_MAX:
        _LOGGER.warning("Accueil : le modèle a rendu %s caractères (au plus %s) ; message normal à la place.",
                        len(rendu), LONGUEUR_MAX)
        return None
    return rendu


def rendre(hass: Any, store: Any, langue: str, graine: str, nom: str = "",
           maintenant: datetime | None = None) -> str:
    """Le message d'accueil final, dans une langue, pour un appareil (graine = son identifiant)."""
    from homeassistant.util import dt as dt_util

    lg = langues.normaliser(langue) or langues.REPLI
    quand = maintenant or dt_util.now()
    reglage = reglages(store)
    valeurs = variables(hass, store, lg, quand, nom)
    if reglage["modele"]:
        rendu = _jinja(hass, reglage["modele"], valeurs)
        if rendu is not None:
            return nettoyer(rendu, lg)
    brut = choisir(reglage, graine, quand) or textes.t("accueil.defaut", lg)
    return nettoyer(remplir(brut, valeurs), lg)


def pour_appareil(hass: Any, coord: Any, pc: dict[str, Any], maintenant: datetime | None = None) -> str:
    """Le message pour un appareil : sa langue, son prénom (vide pour un écran commun), l'heure de Home Assistant."""
    return rendre(hass, coord.store, textes.pour(hass, pc), str(pc.get("id") or ""), prenom(hass, coord, pc),
                  maintenant)
