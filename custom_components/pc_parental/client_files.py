"""Le client PowerShell, hébergé par l'intégration.

Un seul endroit contient l'agent : le dossier `client` de cette intégration.
Les PC le téléchargent à l'installation, puis se mettent à jour tout seuls
quand une nouvelle version y apparaît. Mettre à jour l'intégration met donc
à jour tous les PC de la maison.

Langues : les textes de l'agent, de l'installateur et du désinstalleur sont dans
`client/langues/<langue>.json` (fr, en, de, es, it, nl). Ils partent **dans** le script
servi : le marqueur `@@LANGUES@@` y est remplacé par tous les dictionnaires, avant le
calcul de l'empreinte. La mise à jour automatique, qui ne télécharge que le script et
vérifie son SHA-256, apporte donc ses dictionnaires avec lui, sans route de plus ni
second fichier à vérifier.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from homeassistant.core import HomeAssistant

from .const import CLIENT_DIR, CLIENT_FILES, CLIENT_VERSION

# Remplacé par les dictionnaires de toutes les langues, dans un here-string
# PowerShell (@'…'@) : le JSON y tient sur une ligne par langue, en ASCII.
MARQUEUR_LANGUES = "@@LANGUES@@"
DOSSIER_LANGUES = "langues"

# Contenus et empreintes, calculés une fois puis gardés en mémoire : la
# boucle d'événements de Home Assistant ne doit pas lire de fichier.
_CONTENUS: dict[str, str] = {}
_EMPREINTES: dict[str, str] = {}


def chemin(nom: str) -> Path | None:
    """Chemin du fichier client demandé, ou None s'il est inconnu."""
    fichier = CLIENT_FILES.get(nom)
    if not fichier:
        return None
    piste = Path(__file__).parent / CLIENT_DIR / fichier
    return piste if piste.is_file() else None


def dictionnaires() -> str:
    """Tous les dictionnaires du client, en un JSON {"fr": {...}, "en": {...}, …}.

    Une ligne par langue, caractères non ASCII échappés (\\u00e9) : rien ne peut
    fermer le here-string PowerShell ni dépendre de l'encodage du script. Le
    français d'abord, puis les autres par ordre alphabétique : le texte, donc
    l'empreinte, ne dépend pas de l'ordre du disque.
    """
    dossier = Path(__file__).parent / CLIENT_DIR / DOSSIER_LANGUES
    if not dossier.is_dir():
        return "{}"
    fichiers = sorted(dossier.glob("*.json"), key=lambda f: (f.stem != "fr", f.stem))
    lignes = []
    for fichier in fichiers:
        textes = json.loads(fichier.read_text(encoding="utf-8"))
        lignes.append(
            json.dumps(fichier.stem, ensure_ascii=True)
            + ": "
            + json.dumps(textes, ensure_ascii=True, separators=(",", ":"))
        )
    return "{\n" + ",\n".join(lignes) + "\n}"


def _charger(nom: str) -> None:
    """Lit un fichier client et retient son contenu et son empreinte."""
    piste = chemin(nom)
    if piste is None:
        return
    texte = piste.read_bytes().decode("utf-8")
    if MARQUEUR_LANGUES in texte:
        texte = texte.replace(MARQUEUR_LANGUES, dictionnaires())
    _CONTENUS[nom] = texte
    # L'empreinte porte sur ce qui est servi (dictionnaires compris) : c'est ce
    # que l'agent télécharge et vérifie avant de se remplacer.
    _EMPREINTES[nom] = hashlib.sha256(texte.encode("utf-8")).hexdigest()


async def async_precharger(hass: HomeAssistant) -> None:
    """À appeler au démarrage : tout est lu hors de la boucle."""

    def _tout() -> None:
        for nom in CLIENT_FILES:
            _charger(nom)

    await hass.async_add_executor_job(_tout)


def contenu(nom: str) -> str | None:
    return _CONTENUS.get(nom)


def empreinte(nom: str) -> str:
    """SHA-256 du fichier servi, pour que l'agent vérifie son téléchargement."""
    return _EMPREINTES.get(nom, "")


def url_base(hass: HomeAssistant) -> str:
    return "/api/pc_parental/client"


def info_maj(hass: HomeAssistant) -> dict[str, str]:
    """Ce que l'agent doit savoir pour se mettre à jour."""
    return {
        "version": CLIENT_VERSION,
        "url": f"{url_base(hass)}/agent",
        "sha256": empreinte("agent"),
    }
