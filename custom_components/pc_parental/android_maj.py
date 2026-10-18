"""Version de l'agent Android, livrée avec l'intégration.

L'APK et son manifeste vivent dans `custom_components/pc_parental/android/`
(installation.apk, installation.json = {"version", "sha256"}). Mettre à jour
l'intégration met donc à jour l'app : les appareils reçoivent l'annonce au
prochain appel, téléchargent l'APK auprès de Home Assistant et l'installent.

- installation.apk / installation.json : l'appli (org.soprez.vision). C'est elle
  qu'on installe sur un nouvel appareil, au lien stable
  /api/pc_parental/android/vision.apk (toujours la dernière version), et qu'on
  propose en mise à jour, à l'adresse versionnée
  /api/pc_parental/android/vision-<version>.apk ;
- essai.apk / essai.json : une version à l'essai, pour les appareils d'essai.

La première appli, sous un autre identifiant de paquet (jusqu'à la 1.48), n'est
plus servie depuis la 2.5.8 : plus aucun appareil ne l'avait.

Les fichiers ne sont jamais lus dans la boucle d'événements de Home Assistant
(1.60.0) : `async_rafraichir` les lit dans un fil à part et range le résultat ;
les fonctions qu'appellent la vue HTTP, le tableau de bord et les entités ne
lisent que ce rangement. Une publication est vue au plus tard au rafraîchissement
suivant (toutes les 60 s), comme avant.
"""

from __future__ import annotations

import json
import os
from typing import Any

from homeassistant.core import HomeAssistant

DOSSIER = os.path.join(os.path.dirname(__file__), "android")
# Le lien à donner aux gens : toujours la dernière version de l'appli.
LIEN_STABLE = "/api/pc_parental/android/vision.apk"
NOMS = ("essai.apk", "essai.json", "installation.apk", "installation.json")
MODELE_VERSIONNE = "/api/pc_parental/android/vision-{}.apk"
INTERVALLE_S = 60

_ETAT: dict[str, Any] = {"presents": set(), "essai": None, "installation": {}}


def _lire(chemin: str) -> dict[str, Any]:
    with open(chemin, encoding="utf-8") as f:
        d = json.load(f)
    if not isinstance(d, dict) or not d.get("version"):
        return {}
    return {"version": str(d["version"]), "sha256": str(d.get("sha256") or "").lower()}


def _lire_tout() -> dict[str, Any]:
    """Lit tout ce qui est publié. Bloquant : à appeler hors de la boucle."""
    presents = {nom for nom in NOMS if os.path.isfile(os.path.join(DOSSIER, nom))}
    essai = None
    try:
        if {"essai.json", "essai.apk"} <= presents:
            with open(os.path.join(DOSSIER, "essai.json"), encoding="utf-8") as f:
                d = json.load(f)
            if isinstance(d, dict) and d.get("version"):
                essai = {
                    "version": str(d["version"]),
                    "sha256": str(d.get("sha256") or "").lower(),
                    "appareils": {str(n).strip().lower() for n in (d.get("appareils") or [])},
                }
    except (OSError, ValueError):
        essai = None
    installation: dict[str, Any] = {}
    try:
        if {"installation.json", "installation.apk"} <= presents:
            installation = _lire(os.path.join(DOSSIER, "installation.json"))
            if installation:
                installation["path"] = MODELE_VERSIONNE.format(installation["version"])
    except (OSError, ValueError):
        installation = {}
    return {"presents": presents, "essai": essai, "installation": installation}


async def async_rafraichir(hass: HomeAssistant) -> None:
    """Relit les fichiers publiés, dans un fil à part."""
    _ETAT.update(await hass.async_add_executor_job(_lire_tout))


def fichier_stable() -> tuple[str | None, str]:
    """Ce que sert le lien stable : (chemin, nom proposé au téléchargement), chemin None si rien n'est publié."""
    inst = _ETAT["installation"]
    if inst and "installation.apk" in _ETAT["presents"]:
        return os.path.join(DOSSIER, "installation.apk"), "vision-{}.apk".format(inst["version"])
    return None, "vision.apk"


def fichier(nom: str) -> str | None:
    """Chemin d'un fichier livré (installation.*, essai.*), ou None."""
    if nom not in NOMS or nom not in _ETAT["presents"]:
        return None
    return os.path.join(DOSSIER, nom)


def fichier_versionne(nom: str) -> tuple[str | None, str]:
    """« vision-<version>.apk » : (chemin de l'APK, version publiée).

    Chemin None si ce nom n'est pas celui de la version publiée (ancien lien,
    ou rien de publié) ; la version publiée sert alors à rediriger.
    """
    inst = _ETAT["installation"]
    if not inst or "installation.apk" not in _ETAT["presents"]:
        return None, ""
    if nom == "vision-{}.apk".format(inst["version"]):
        return os.path.join(DOSSIER, "installation.apk"), inst["version"]
    return None, inst["version"]


def _tuple(version: str) -> tuple[int, ...]:
    morceaux = []
    for m in str(version or "").split("."):
        chiffres = "".join(c for c in m if c.isdigit())
        if not chiffres:
            break
        morceaux.append(int(chiffres))
    return tuple(morceaux)


def installation(hass: HomeAssistant) -> dict[str, Any]:
    """La dernière version à installer : {version, sha256, path}, ou {}."""
    return dict(_ETAT["installation"])


def infos_pour(hass: HomeAssistant, appareil: str, installee: str = "", ident: str = "",
               essai_ids: list[str] | tuple[str, ...] = ()) -> dict[str, Any]:
    """La version à proposer à cet appareil.

    Celle à l'essai s'il fait partie des appareils d'essai — par son nom (essai.json) ou par son
    identifiant (essai.json, ou les appareils d'essai choisis dans les réglages généraux) ; sinon
    la dernière version publiée. Un essai plus ancien que la version publiée est périmé : ignoré
    (l'essai 1.47.0 resté sur l'installation de référence est celui de la première appli, qu'il ne
    faut jamais réinstaller à côté de l'actuelle).
    """
    essai = _ETAT["essai"]
    publiee = _ETAT["installation"].get("version", "")
    if essai and publiee and _tuple(essai["version"]) < _tuple(publiee):
        essai = None
    choisi = bool(ident) and (ident in essai_ids or (essai is not None and ident.strip().lower() in essai["appareils"]))
    if essai and ((appareil or "").strip().lower() in essai["appareils"] or choisi):
        return {
            "version": essai["version"],
            "sha256": essai["sha256"],
            "path": "/api/pc_parental/android/essai.apk?v={}".format(essai["version"]),
        }
    return installation(hass)


def infos(hass: HomeAssistant) -> dict[str, Any]:
    """Dernière version Android disponible : {version, sha256, path}, ou {}."""
    return installation(hass)
