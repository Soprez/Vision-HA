"""Faille du 16/10 : le tableau accorde avec le code de déverrouillage d'un Décideur (octroi.py). Pour les essais qui
accordent depuis le tableau : poser le code de chaque Décideur, puis l'ajouter au corps des gestes qui accordent
({"code_octroi": CODE_OCTROI})."""
from __future__ import annotations

import importlib
import sys

CODE_OCTROI = "482913"


def _octroi():
    paquet = next(n for n in sys.modules if n.endswith("pc_parental") and n.count(".") <= 1)
    return importlib.import_module(paquet + ".octroi")


def poser_code_octroi(hass, code: str = CODE_OCTROI) -> None:
    octroi = _octroi()
    for coord in hass.data.get("pc_parental", {}).values():
        for personne in octroi.decideurs(coord.store) or {"person.essai_decideur"}:
            if not octroi.code_pose(coord.store, personne):
                octroi.poser(coord.store, personne, code)
