"""Version de l'agent Android, livrée avec l'intégration.

L'APK et son manifeste vivent dans `custom_components/pc_parental/android/`
(vision.apk, vision.json = {"version", "sha256"}). Mettre à jour l'intégration
met donc à jour l'app : les appareils reçoivent l'annonce au prochain appel,
téléchargent l'APK auprès de Home Assistant et l'installent.

Repli : l'ancien emplacement www/vision.json, servi par /local/.

Deux applications cohabitent (1.64.0) :
- vision.apk / vision.json : l'ancienne appli (const.ANCIEN_PAQUET, jusqu'à
  la 1.48), pour les appareils qui ne sont pas encore passés à la nouvelle ;
- installation.apk / installation.json : la nouvelle (org.soprez.vision,
  1.49 et après). C'est elle qu'on installe sur un nouvel appareil, au lien
  stable /api/pc_parental/android/vision.apk (toujours la dernière version),
  et qu'on propose en mise à jour aux appareils déjà passés, à l'adresse
  versionnée /api/pc_parental/android/vision-<version>.apk.
  L'ancienne se met à jour à /api/pc_parental/android/ancienne.apk (1.65.0).
Les deux paquets portent des noms différents : proposer l'un à l'autre
installerait une seconde appli à côté de la première.

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
# L'ancienne appli se met à jour au chemin annoncé (1.28 et après) : elle a le sien.
CHEMIN_SERVI = "/api/pc_parental/android/ancienne.apk"
# Le lien à donner aux gens : toujours la dernière version de la nouvelle appli.
LIEN_STABLE = "/api/pc_parental/android/vision.apk"
ANCIEN_JSON = "www/vision.json"
ANCIEN_APK = "/local/vision.apk"
NOMS = (
    "vision.apk", "vision.json", "essai.apk", "essai.json",
    "installation.apk", "installation.json",
)
# La première version de la nouvelle appli (org.soprez.vision).
PREMIERE_NOUVELLE = (1, 49, 0)
MODELE_VERSIONNE = "/api/pc_parental/android/vision-{}.apk"
INTERVALLE_S = 60

_ETAT: dict[str, Any] = {"presents": set(), "vision": {}, "essai": None, "installation": {}}


def _lire(chemin: str) -> dict[str, Any]:
    with open(chemin, encoding="utf-8") as f:
        d = json.load(f)
    if not isinstance(d, dict) or not d.get("version"):
        return {}
    return {"version": str(d["version"]), "sha256": str(d.get("sha256") or "").lower()}


def _lire_tout(ancien_json: str) -> dict[str, Any]:
    """Lit tout ce qui est publié. Bloquant : à appeler hors de la boucle."""
    presents = {nom for nom in NOMS if os.path.isfile(os.path.join(DOSSIER, nom))}
    vision: dict[str, Any] = {}
    try:
        if {"vision.json", "vision.apk"} <= presents:
            vision = _lire(os.path.join(DOSSIER, "vision.json"))
            if vision:
                vision["path"] = "{}?v={}".format(CHEMIN_SERVI, vision["version"])
        if not vision and os.path.isfile(ancien_json):
            vision = _lire(ancien_json)
            if vision:
                vision["path"] = "{}?v={}".format(ANCIEN_APK, vision["version"])
    except (OSError, ValueError):
        vision = {}
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
    return {"presents": presents, "vision": vision, "essai": essai, "installation": installation}


async def async_rafraichir(hass: HomeAssistant) -> None:
    """Relit les fichiers publiés, dans un fil à part."""
    _ETAT.update(await hass.async_add_executor_job(_lire_tout, hass.config.path(ANCIEN_JSON)))


def fichier_stable() -> tuple[str | None, str]:
    """Ce que sert le lien stable : (chemin, nom proposé au téléchargement).

    La dernière version de la nouvelle appli ; tant qu'elle n'est pas publiée,
    l'ancienne, comme avant la 1.65.0.
    """
    inst = _ETAT["installation"]
    if inst and "installation.apk" in _ETAT["presents"]:
        return os.path.join(DOSSIER, "installation.apk"), "vision-{}.apk".format(inst["version"])
    return fichier("vision.apk"), "vision.apk"


def fichier(nom: str) -> str | None:
    """Chemin d'un fichier livré (vision.apk, vision.json, essai.*), ou None."""
    if nom not in NOMS or nom not in _ETAT["presents"]:
        return None
    return os.path.join(DOSSIER, nom)


def _tuple(version: str) -> tuple[int, ...]:
    morceaux = []
    for m in str(version or "").split("."):
        chiffres = "".join(c for c in m if c.isdigit())
        if not chiffres:
            break
        morceaux.append(int(chiffres))
    return tuple(morceaux)


def nouvelle_appli(version: str) -> bool:
    """Cette version est-elle celle de la nouvelle appli (org.soprez.vision) ?"""
    v = _tuple(version)
    return bool(v) and v >= PREMIERE_NOUVELLE


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


def installation(hass: HomeAssistant) -> dict[str, Any]:
    """La dernière version à installer sur un nouvel appareil : {version, sha256, path}, ou {}."""
    return dict(_ETAT["installation"])


def infos_pour(hass: HomeAssistant, appareil: str, installee: str = "", ident: str = "",
               essai_ids: list[str] | tuple[str, ...] = ()) -> dict[str, Any]:
    """La version à proposer à cet appareil.

    Celle à l'essai s'il fait partie des appareils d'essai — par son nom (essai.json) ou par son
    identifiant (essai.json, ou les appareils d'essai choisis dans les réglages généraux) ; sinon
    celle de son appli : la nouvelle s'il l'a déjà, l'ancienne sinon.
    """
    essai = _ETAT["essai"]
    choisi = bool(ident) and (ident in essai_ids or (essai is not None and ident.strip().lower() in essai["appareils"]))
    # L'essai ne vaut que pour la même appli : un appareil passé à la nouvelle
    # ne se voit pas proposer un essai de l'ancienne (et inversement).
    if (
        essai
        and ((appareil or "").strip().lower() in essai["appareils"] or choisi)
        and (not installee or nouvelle_appli(essai["version"]) == nouvelle_appli(installee))
    ):
        return {
            "version": essai["version"],
            "sha256": essai["sha256"],
            "path": "/api/pc_parental/android/essai.apk?v={}".format(essai["version"]),
        }
    if nouvelle_appli(installee):
        return installation(hass)
    return infos(hass)


def infos(hass: HomeAssistant) -> dict[str, Any]:
    """Dernière version Android disponible : {version, sha256, path}, ou {}."""
    return dict(_ETAT["vision"])
