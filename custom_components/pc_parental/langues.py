"""Les langues de Vision : français (source), anglais (repli), allemand, espagnol, italien, néerlandais.

Un catalogue = un dossier de `pc_parental/i18n/` avec un fichier JSON plat par langue (`fr.json`, `en.json`…) :
clé → texte, avec des champs `{nom}` remplis par `str.format`. Les pluriels ont deux clés, `<clé>_one` et
`<clé>_other`. Une clé absente dans une langue prend l'anglais, puis le français, puis la clé elle-même : un texte
n'est jamais vide.

Les catégories (étiquettes Home Assistant de la maison) gardent leur nom d'origine dans les données ; seul
l'affichage les traduit, par la table `i18n/categories.json`.

Les fichiers sont lus une fois, à l'import du module (jamais dans la boucle d'événements de Home Assistant).
"""
from __future__ import annotations

import json
import os
import re
import unicodedata
from typing import Any

LANGUES = ("fr", "en", "de", "es", "it", "nl")
SOURCE = "fr"
REPLI = "en"
DOSSIER = os.path.join(os.path.dirname(__file__), "i18n")


def normaliser(code: Any) -> str | None:
    """« de-CH », « de_DE », « DE » → « de » ; une langue que Vision ne parle pas → None."""
    base = re.split(r"[-_]", str(code or "").strip().lower(), maxsplit=1)[0]
    return base if base in LANGUES else None


def langue_hass(hass: Any) -> str:
    """La langue de Home Assistant (Paramètres → Système → Général), ou l'anglais."""
    try:
        return normaliser(hass.config.language) or REPLI
    except AttributeError:
        return REPLI


def langue_appareil(hass: Any, pc: dict[str, Any] | None) -> str:
    """La langue d'un appareil : celle qu'il a annoncée à son dernier relevé, sinon celle de Home Assistant."""
    return normaliser((pc or {}).get("langue")) or langue_hass(hass)


def _lire_disque(chemin: str) -> dict[str, Any]:
    try:
        with open(chemin, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _tout_lire() -> dict[str, dict[str, Any]]:
    """Tous les fichiers JSON de `i18n/`, lus d'un coup, à l'import de ce module.

    2.5.12 : un catalogue (`Catalogue("tableau")`…) se crée à l'import du module qui s'en sert. Quand ce module
    était importé dans une fonction (`from .dashboard import …` au démarrage), la lecture se faisait dans la boucle
    d'événements et HA le signalait (« Detected blocking call to open … langues.py, line 48 »). Ce module-ci est
    importé hors de la boucle, avec l'intégration : la lecture se fait là, une seule fois (26 fichiers, 0,5 Mo)."""
    tout: dict[str, dict[str, Any]] = {}
    for racine, _dossiers, fichiers in os.walk(DOSSIER):
        for nom in fichiers:
            if nom.endswith(".json"):
                chemin = os.path.join(racine, nom)
                tout[os.path.normcase(os.path.abspath(chemin))] = _lire_disque(chemin)
    return tout


_FICHIERS = _tout_lire()


def _lire(chemin: str) -> dict[str, Any]:
    """Le contenu d'un fichier de `i18n/`, depuis la copie faite à l'import : jamais d'accès disque ici."""
    return _FICHIERS.get(os.path.normcase(os.path.abspath(chemin)), {})


class Catalogue:
    """Les textes d'un composant, dans les six langues."""

    def __init__(self, nom: str) -> None:
        self.nom = nom
        self.textes: dict[str, dict[str, str]] = {
            lg: {k: v for k, v in _lire(os.path.join(DOSSIER, nom, f"{lg}.json")).items() if isinstance(v, str)}
            for lg in LANGUES
        }

    def brut(self, cle: str, langue: str | None) -> str:
        for lg in (normaliser(langue) or REPLI, REPLI, SOURCE):
            texte = self.textes.get(lg, {}).get(cle)
            if texte:
                return texte
        return cle

    def t(self, cle: str, langue: str | None = None, /, **champs: Any) -> str:
        texte = self.brut(cle, langue)
        if not champs:
            return texte
        try:
            return texte.format(**champs)
        except (KeyError, IndexError, ValueError):
            return texte

    def n(self, cle: str, nombre: int, langue: str | None = None, /, **champs: Any) -> str:
        """Le pluriel : « 1 appareil », « 2 appareils ». En français, 0 et 1 sont au singulier."""
        lg = normaliser(langue) or REPLI
        un = nombre in (0, 1) if lg == "fr" else nombre == 1
        return self.t(f"{cle}_{'one' if un else 'other'}", lg, n=nombre, **champs)

    def cles(self, langue: str) -> set[str]:
        return set(self.textes.get(langue, {}))


def cle_categorie(nom: str) -> str:
    """« Réseaux sociaux » → « reseaux_sociaux » ; « Pub & traçage » → « pub_tracage »."""
    sans_accents = unicodedata.normalize("NFKD", str(nom or "")).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "_", sans_accents.lower()).strip("_")


_CATEGORIES: dict[str, dict[str, str]] = _lire(os.path.join(DOSSIER, "categories.json")).get("categories", {})


def categorie(nom: str, langue: str | None) -> str:
    """Le nom d'une catégorie dans la langue voulue ; une catégorie inconnue de la table reste telle quelle."""
    entree = _CATEGORIES.get(cle_categorie(nom))
    if not entree:
        return nom
    return entree.get(normaliser(langue) or REPLI) or entree.get(REPLI) or nom


def categories() -> dict[str, dict[str, str]]:
    return {k: dict(v) for k, v in _CATEGORIES.items()}
