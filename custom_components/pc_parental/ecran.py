"""17-10 (l'auteur : « désactiver l'écran de veille par appareil ») : l'écran d'un appareil Android, réglé depuis Home Assistant.

- ``veille_vision`` : l'écran de veille de Vision (horloge, photos, playlist) peut venir sur cet appareil ;
- ``garder_allume`` : l'accueil de Vision empêche la mise en veille de l'écran Android (FLAG_KEEP_SCREEN_ON).

Défauts sûrs : exactement le comportement d'avant (veille permise, écran non imposé). Réglé par le service
``pc_parental.ecran_regler`` (administrateurs et automatisations) ; envoyé dans le bloc ``ecran`` du relevé. Le module
« veille » coupé pour toute l'installation reste plus fort (il coupe la veille partout).
"""
from __future__ import annotations

from typing import Any

REGLAGES_DEFAUT: dict[str, Any] = {"veille_vision": True, "garder_allume": False}


def reglages(pc: dict[str, Any]) -> dict[str, Any]:
    brut = pc.get("ecran") if isinstance(pc.get("ecran"), dict) else {}
    return {k: bool(brut.get(k, d)) for k, d in REGLAGES_DEFAUT.items()}


def regler(pc: dict[str, Any], changements: dict[str, Any]) -> dict[str, Any]:
    """Pose des réglages (clés inconnues ignorées) ; rend les réglages effectifs."""
    pc["ecran"] = {**reglages(pc), **{k: bool(v) for k, v in changements.items() if k in REGLAGES_DEFAUT}}
    return reglages(pc)


def pour_releve(pc: dict[str, Any]) -> dict[str, Any]:
    return reglages(pc)
