"""Les textes que Home Assistant fabrique lui-même : catalogue `i18n/serveur`, heures, langue des destinataires.

Qui lit quoi :
- un appareil (raison de blocage, message, réponse de l'appli ou de l'agent) : sa langue, `pour(hass, pc)` ;
- Home Assistant (notification persistante, journal de bord, erreur d'un service) : `ha(hass)`.

Une erreur levée loin de toute langue (le magasin, un module sans `hass`) est une `Erreur` : elle porte sa clé
et ses champs, et chacun la dit dans la langue de son lecteur (`erreur(err, langue)`). Lue telle quelle
(`str(err)`, une erreur de service par exemple), elle parle la langue de Home Assistant.
"""
from __future__ import annotations

import re
from contextvars import ContextVar
from datetime import datetime
from typing import Any

from . import langues

SERVEUR = langues.Catalogue("serveur")

# L'instance branchée (`brancher`) : la langue par défaut d'une `Erreur` lue sans langue.
_HASS: Any = None


def brancher(hass: Any) -> None:
    """Retient l'instance de Home Assistant, pour qu'une `Erreur` lue sans langue parle celle de Home Assistant."""
    global _HASS  # noqa: PLW0603 - une seule instance
    _HASS = hass


# La langue de la requête en cours (contextvars : propre à chaque requête, qui a sa propre tâche asyncio).
# Une vue qui sert un appareil la pose (`parler`) : tout texte demandé sans langue pendant la requête la parle.
_LANGUE_REQUETE: ContextVar[str | None] = ContextVar("vision_langue_requete", default=None)


def parler(langue: str | None) -> None:
    """Pour la requête en cours (vue d'un appareil) : les textes demandés sans langue parlent celle-ci."""
    _LANGUE_REQUETE.set(langues.normaliser(langue))


def langue_defaut() -> str:
    """Sans langue précisée : celle de la requête en cours (`parler`), sinon celle de Home Assistant, sinon le
    français (hors de Home Assistant)."""
    courante = _LANGUE_REQUETE.get()
    if courante:
        return courante
    return langues.langue_hass(_HASS) if _HASS is not None else langues.SOURCE


# Les champs du vocabulaire neutre ({decideur}, {Demandeurs}, {lieu}…), remplis tout seuls (vocabulaire.py).
_VOCABULAIRE = re.compile(r"\{(?:[dD]ecideurs?|[dD]emandeurs?|[lL]ieu)\}")


def _store() -> Any:
    """Le magasin de l'instance branchée (pour {lieu} et les mots personnalisés), ou None."""
    try:
        entrees = list(_HASS.data.get("pc_parental", {}).values())
    except AttributeError:
        return None
    return getattr(entrees[0], "store", None) if entrees else None


def t(cle: str, langue: str | None = None, /, **champs: Any) -> str:
    """Un texte du catalogue serveur ; sans langue, celle de Home Assistant (ou le français hors de HA).

    Les champs du vocabulaire neutre sont remplis d'office ; un champ passé explicitement l'emporte."""
    lg = langues.normaliser(langue) or langue_defaut()
    texte = SERVEUR.brut(cle, lg)
    if _VOCABULAIRE.search(texte):
        from . import vocabulaire

        champs = {**vocabulaire.termes(lg, _store()), **champs}
    if not champs:
        return texte
    try:
        return texte.format(**champs)
    except (KeyError, IndexError, ValueError):
        return texte


def n(cle: str, nombre: int, langue: str | None = None, /, **champs: Any) -> str:
    """Un texte au singulier ou au pluriel (`<clé>_one` / `<clé>_other`, champ `{n}`).

    En français, 0 et 1 sont au singulier."""
    lg = langues.normaliser(langue) or langue_defaut()
    un = nombre in (0, 1) if lg == "fr" else nombre == 1
    return t(f"{cle}_{'one' if un else 'other'}", lg, n=nombre, **champs)


def pour(hass: Any, pc: dict[str, Any] | None) -> str:
    """La langue d'un appareil (annoncée à son relevé), sinon celle de Home Assistant."""
    return langues.normaliser((pc or {}).get("langue")) or ha(hass)


def noter_langue(pc: dict[str, Any] | None, corps: dict[str, Any] | None) -> bool:
    """Range la langue annoncée par un appareil (champ « langue », BCP 47) dans pc["langue"].

    Rend True si la fiche a changé (à enregistrer). Sans champ « langue », rien ne change ; une langue que
    Vision ne parle pas efface la précédente : l'appareil suit alors la langue de Home Assistant.
    """
    if pc is None or not isinstance(corps, dict) or "langue" not in corps:
        return False
    langue = langues.normaliser(corps.get("langue"))
    if langue is None:
        return pc.pop("langue", None) is not None
    if pc.get("langue") == langue:
        return False
    pc["langue"] = langue
    return True


def normaliser_corps(corps: dict[str, Any] | None, hass: Any = None) -> str:
    """La langue annoncée dans un corps de requête (appareil pas encore inscrit), sinon celle de Home Assistant."""
    return langues.normaliser((corps or {}).get("langue") if isinstance(corps, dict) else None) or ha(hass)


def langue_requete(request: Any, hass: Any = None) -> str:
    """La langue d'une page vue par quelqu'un sans compte (invité d'une Party) : la première de son navigateur
    (en-tête Accept-Language) que Vision parle, sinon celle de Home Assistant."""
    try:
        entete = str(request.headers.get("Accept-Language") or "")
    except AttributeError:
        entete = ""
    choix: list[tuple[float, int, str]] = []
    for i, morceau in enumerate(entete.split(",")):
        code, _, poids = morceau.strip().partition(";q=")
        langue = langues.normaliser(code)
        if langue is None:
            continue
        try:
            q = float(poids) if poids else 1.0
        except ValueError:
            q = 0.0
        choix.append((-q, i, langue))
    return sorted(choix)[0][2] if choix else ha(hass)


def ha(hass: Any = None) -> str:
    """La langue de Home Assistant."""
    instance = hass if hass is not None else _HASS
    return langues.langue_hass(instance) if instance is not None else langues.SOURCE


# Où décider (HA-07) : la vue du jour du tableau de bord Vision. Le chemin reste « vision-maison/maison » (liens
# existants) ; dashboard.URL_PATH fait foi.
VUE_DECIDER = "maison"


def chemin_tableau() -> str:
    """Le chemin de la vue où se décident les demandes d'accès, de temps et de déverrouillage."""
    try:
        from .dashboard import URL_PATH
    except ImportError:  # pragma: no cover - tableau absent d'un banc réduit
        URL_PATH = "vision-maison"
    return f"/{URL_PATH}/{VUE_DECIDER}"


def chemin_fiche(hass: Any, pc_id: str) -> str:
    """Le chemin de la fiche Home Assistant d'un appareil (sinon, la page de l'intégration)."""
    try:
        from .registre import appareil_du_pc

        appareil = appareil_du_pc(hass, pc_id)
    except Exception:  # noqa: BLE001 - un lien de repli vaut mieux qu'une notification perdue
        appareil = None
    if appareil is not None:
        return f"/config/devices/device/{appareil.id}"
    from .const import DOMAIN

    return f"/config/integrations/integration/{DOMAIN}"


def ou_decider(langue: str | None, chemin: str | None = None, lien: str | None = None) -> str:
    """« Où décider : [tableau de bord Vision](/vision-maison/maison) », pour une notification persistante."""
    from .const import PRODUIT_AFFICHE

    return t("notif.ou_decider", langue, lien=lien or t("notif.lien_tableau", langue, produit=PRODUIT_AFFICHE),
             chemin=chemin or chemin_tableau())


def categorie(nom: str, langue: str | None) -> str:
    """Une catégorie (étiquette Home Assistant) dite dans la langue du lecteur ; inconnue, elle reste telle quelle."""
    return langues.categorie(nom, langue or langue_defaut())


def heure(moment: Any, langue: str | None = None) -> str:
    """Une heure lisible : « 18h30 » en français, « 18:30 » ailleurs.

    `moment` : un datetime (déjà à l'heure locale), un horodatage UNIX (mis à l'heure locale de Home Assistant)
    ou un couple (heures, minutes).
    """
    if isinstance(moment, tuple):
        h, m = int(moment[0]), int(moment[1])
    else:
        if not isinstance(moment, datetime):
            from homeassistant.util import dt as dt_util

            moment = dt_util.as_local(dt_util.utc_from_timestamp(float(moment)))
        h, m = moment.hour, moment.minute
    return t("format.heure", langue, h="{:02d}".format(h), m="{:02d}".format(m))


class Phrase:
    """Une phrase dite à plusieurs lecteurs (celui qui agit, l'appareil visé) : des clés et leurs champs.

    Un champ qui dépend de la langue (une heure, un libellé) est une fonction de la langue.
    """

    def __init__(self, cle: str | None = None, /, **champs: Any) -> None:
        self.morceaux: list[tuple[str, dict[str, Any]]] = []
        if cle:
            self.morceaux.append((cle, champs))

    def __call__(self, cle: str, /, **champs: Any) -> Phrase:
        self.morceaux.append((cle, champs))
        return self

    def __bool__(self) -> bool:
        return bool(self.morceaux)

    def en(self, langue: str | None) -> str:
        """Le texte dans une langue. Un champ « _n » choisit le singulier ou le pluriel (`n`)."""
        sortie = []
        for cle, champs in self.morceaux:
            valeurs = {k: (v(langue) if callable(v) else v) for k, v in champs.items() if k != "_n"}
            sortie.append(n(cle, int(champs["_n"]), langue, **valeurs) if "_n" in champs else t(cle, langue, **valeurs))
        return "".join(sortie)


class Erreur(ValueError):
    """Une erreur à montrer à quelqu'un : une clé du catalogue serveur et ses champs.

    Reste une ValueError : tout `except ValueError` existant la rattrape comme avant.
    """

    def __init__(self, cle: str, /, **champs: Any) -> None:
        super().__init__(cle)
        self.cle = cle
        self.champs = champs

    def texte(self, langue: str | None) -> str:
        """Le texte dans une langue ; un champ qui est une fonction reçoit la langue (un libellé à traduire)."""
        langue = langue or langue_defaut()
        return t(self.cle, langue, **{k: (v(langue) if callable(v) else v) for k, v in self.champs.items()})

    def __str__(self) -> str:
        return self.texte(None)


def erreur(err: BaseException, langue: str | None) -> str:
    """Le texte d'une erreur pour un lecteur : traduit si c'est une `Erreur`, sinon tel quel."""
    if isinstance(err, Erreur):
        return err.texte(langue)
    return str(err)
