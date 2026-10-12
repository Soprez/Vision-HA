"""Liste noire : les domaines signalés comme dangereux par des tiers.

Rien à voir avec les règles de la maison. Une règle est une décision de
parent — « pas de jeux la semaine » — et elle se discute. Ici, c'est un
constat extérieur : ce domaine héberge un logiciel malveillant ou une page
d'hameçonnage, et il n'y a rien à trancher. La liste ne rentre donc pas dans
la file à classer, elle vaut pour tous les PC, et aucun laissez-passer ne
l'ouvre.

Elle est gardée dans son propre stockage : quelques dizaines de milliers de
noms n'ont rien à faire dans le fichier qui contient les PC et leurs plages,
réécrit à chaque décision.
"""

from __future__ import annotations

import hashlib
import logging
import time
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.storage import Store

from .const import (
    LISTE_NOIRE_HEURES,
    LISTE_NOIRE_HEURES_MAX,
    LISTE_NOIRE_HEURES_MIN,
    LISTE_NOIRE_SOURCES,
    NOIRE_EXTRAIT,
    STORAGE_KEY_NOIRE,
    STORAGE_VERSION,
)

_LOGGER = logging.getLogger(__name__)

# Ce qu'on refuse de bloquer même si une source le dit : une erreur sur l'un
# de ces noms couperait la maison entière, ou Vision lui-même. S'y ajoutent,
# à chaque téléchargement, les adresses de CE Home Assistant (hotes_home_assistant).
JAMAIS = {
    "localhost",
    "nabu.casa",
    "home-assistant.io",
    "microsoft.com",
    "windows.com",
    "google.com",
    "cloudflare.com",
    "abuse.ch",
    "phishing.army",
}


def hotes_home_assistant(hass: HomeAssistant) -> set[str]:
    """Les noms par lesquels on joint ce Home Assistant (adresses externe et interne configurées)."""
    from urllib.parse import urlsplit

    hotes: set[str] = set()
    for url in (getattr(hass.config, "external_url", None), getattr(hass.config, "internal_url", None)):
        try:
            hote = (urlsplit(str(url or "")).hostname or "").lower().rstrip(".")
        except ValueError:
            continue
        if hote and "." in hote and not hote.replace(".", "").isdigit():
            hotes.add(hote)
    return hotes


def _domaine(ligne: str, epargnes: set[str] | frozenset[str] = frozenset(JAMAIS)) -> str:
    """Extrait un domaine d'une ligne, quel que soit le format de la source.

    Les listes circulent sous trois habits : « 0.0.0.0 domaine » (hosts),
    « ||domaine^ » (adblock), ou le domaine nu. Les trois se lisent ici, ce
    qui évite un lecteur par source.
    """
    ligne = str(ligne or "").strip()
    if not ligne or ligne.startswith(("#", "!", "[")):
        return ""
    if ligne.startswith("||"):
        ligne = ligne[2:].split("^", 1)[0]
    else:
        morceaux = ligne.split()
        if len(morceaux) >= 2 and morceaux[0].count(".") == 3:
            ligne = morceaux[1]
        elif len(morceaux) >= 2 and morceaux[0].startswith("::"):
            ligne = morceaux[1]
        else:
            ligne = morceaux[0]
    ligne = ligne.strip().lower().rstrip(".")
    if "/" in ligne or "*" in ligne or ":" in ligne:
        return ""
    if "." not in ligne or len(ligne) > 253:
        return ""
    if any(ligne == n or ligne.endswith("." + n) for n in epargnes):
        return ""
    return ligne


class ListeNoire:
    """Télécharge, garde et sert les domaines signalés dangereux."""

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self._store: Store = Store(hass, STORAGE_VERSION, STORAGE_KEY_NOIRE)
        self.active: bool = True
        # Toutes les combien d'heures on retourne voir les sources.
        self.heures: int = LISTE_NOIRE_HEURES
        self.domaines: list[str] = []
        self.par_source: dict[str, int] = {}
        self.maj: float = 0
        self.version: str = ""
        self.erreur: str = ""
        # Un téléchargement en cours, et l'heure du dernier essai — réussi
        # ou non. Sans eux, trois sources injoignables relançaient trois
        # requêtes de soixante secondes toutes les quinze secondes, sans
        # que rien ne se termine jamais.
        self._en_cours: bool = False
        self._dernier_essai: float = 0

    # ------------------------------------------------------------------ E/S

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        self.active = bool(data.get("active", True))
        heures = int(data.get("heures") or LISTE_NOIRE_HEURES)
        self.heures = min(max(heures, LISTE_NOIRE_HEURES_MIN), LISTE_NOIRE_HEURES_MAX)
        self.domaines = list(data.get("domaines") or [])
        self.par_source = dict(data.get("par_source") or {})
        self.maj = float(data.get("maj") or 0)
        self.version = str(data.get("version") or "")
        self.erreur = ""

    async def async_save(self) -> None:
        await self._store.async_save(
            {
                "active": self.active,
                "heures": self.heures,
                "domaines": self.domaines,
                "par_source": self.par_source,
                "maj": self.maj,
                "version": self.version,
            }
        )

    # -------------------------------------------------------- Rafraîchir

    # Après un échec, on ne réessaie pas avant ce délai.
    REPOS_ECHEC = 900

    def perimee(self) -> bool:
        if self._en_cours:
            return False
        if (time.time() - self._dernier_essai) < self.REPOS_ECHEC:
            return False
        return (time.time() - self.maj) > (self.heures * 3600)

    async def async_rafraichir(self, force: bool = False) -> bool:
        """Recharge les sources. Rend True si la liste a changé.

        Une source injoignable ne vide pas la liste : on garde la
        précédente et on le dit. Une protection qui disparaît parce qu'un
        serveur a éternué serait pire que pas de protection du tout, parce
        qu'elle serait silencieuse.
        """
        if not force and not self.perimee():
            return False
        if self._en_cours:
            return False
        self._en_cours = True
        self._dernier_essai = time.time()
        try:
            return await self._async_rafraichir()
        finally:
            self._en_cours = False

    async def _async_rafraichir(self) -> bool:
        session = async_get_clientsession(self.hass)
        trouves: set[str] = set()
        compte: dict[str, int] = {}
        echecs: list[str] = []
        epargnes = JAMAIS | hotes_home_assistant(self.hass)

        for cle, source in LISTE_NOIRE_SOURCES.items():
            try:
                async with session.get(source["url"], timeout=60) as reponse:
                    reponse.raise_for_status()
                    texte = await reponse.text()
            except Exception as err:  # noqa: BLE001
                _LOGGER.warning("Liste noire, source %s : %s", cle, err)
                echecs.append(source["nom"])
                continue
            avant = len(trouves)
            for ligne in texte.splitlines():
                nom = _domaine(ligne, epargnes)
                if nom:
                    trouves.add(nom)
            compte[cle] = len(trouves) - avant

        from . import textes

        if not trouves:
            self.erreur = textes.t("noire.aucune_source", textes.ha(self.hass))
            return False

        self.erreur = (
            textes.t("noire.source_injoignable", textes.ha(self.hass), sources=", ".join(echecs))
            if echecs
            else ""
        )
        domaines = sorted(trouves)
        version = hashlib.sha256("\n".join(domaines).encode()).hexdigest()[:16]
        change = version != self.version
        self.domaines = domaines
        self.par_source = compte
        self.maj = time.time()
        self.version = version
        await self.async_save()
        return change

    # ------------------------------------------------------------ Service

    def contient(self, domaine: str) -> bool:
        return bool(domaine) and domaine.lower() in set(self.domaines)

    def chercher(self, terme: str) -> tuple[list[str], int]:
        """Les domaines qui contiennent ce morceau, et combien il y en a.

        On ne lit pas cinquante mille lignes : on demande. Sans recherche,
        on montre un extrait — de quoi voir que la liste est réelle, et à
        quoi elle ressemble.
        """
        terme = str(terme or "").strip().lower()
        if not terme:
            return self.domaines[:NOIRE_EXTRAIT], len(self.domaines)
        trouves = [d for d in self.domaines if terme in d]
        return trouves[:NOIRE_EXTRAIT], len(trouves)

    def pour_agent(self, plafond: int) -> dict[str, Any]:
        """Ce que l'agent reçoit : tout, plus ce qui tient dans hosts.

        Le fichier hosts ne peut pas porter cent mille lignes — le service
        DNS de Windows relit ce fichier sans arrêt. Au-delà du plafond, les
        domaines restent **connus** de l'agent, qui sait donc reconnaître
        une tentative et prévenir ; c'est le résolveur filtrant qui les
        bloque. Les deux nombres sont dits, jamais l'un pour l'autre.
        """
        if not self.active:
            return {"version": "", "connus": [], "hosts": [], "total": 0}
        return {
            "version": self.version,
            "connus": self.domaines,
            "hosts": self.domaines[:plafond],
            "total": len(self.domaines),
        }
