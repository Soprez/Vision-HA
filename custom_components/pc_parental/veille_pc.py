"""2.4.2 (l'auteur, 2026-10-12) : l'écran de veille Vision des PC Windows est retiré (agent 1.91.0).

Il reste un filtre : un PC Windows ne reçoit jamais les modules « veille » et « veille_tableaux ». Les agents qui ne
sont pas encore à jour (1.90.2 et avant) gardent le code de la veille : sans ce filtre, ils la rouvriraient. Les télés et
téléphones (Android) gardent leur écran de veille.
"""
from __future__ import annotations

from typing import Any

MODULES_VEILLE = ("veille", "veille_tableaux")


def modules_pour(pc: dict[str, Any], modules: list[str]) -> list[str]:
    """Les modules à envoyer à cet appareil : tous pour Android, sans ceux de la veille pour un PC."""
    if str(pc.get("platform") or "") == "android":
        return modules
    return [m for m in modules if m not in MODULES_VEILLE]
