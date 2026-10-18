"""Décision de l'auteur (16/10) : appli en 1.93.01, 1.93.02 … 1.93.99 (dernier chiffre sur deux positions).

Les comparaisons de versions côté HA (lien stable de l'appli, notes de version) doivent les ranger dans l'ordre :
« 1.93.01 » après « 1.93.0 », « 1.93.10 » après « 1.93.09 ». Fonctions pures : sans Home Assistant.
"""

from __future__ import annotations

import pathlib

RACINE = pathlib.Path(__file__).resolve().parents[1] / "custom_components" / "pc_parental"


def _fonction(fichier: str, nom: str):
    """Extrait une fonction pure d'un module de l'intégration (sans importer Home Assistant)."""
    import ast

    src = (RACINE / fichier).read_text(encoding="utf-8")
    arbre = ast.parse(src)
    noeud = next(n for n in arbre.body if isinstance(n, ast.FunctionDef) and n.name == nom)
    module = ast.Module(body=[noeud], type_ignores=[])
    espace: dict = {"Any": object}
    exec(compile(ast.fix_missing_locations(module), fichier, "exec"), {"__annotations__": {}, "Any": object}, espace)
    return espace[nom]


def test_lien_stable_de_l_appli():
    t = _fonction("android_maj.py", "_tuple")
    assert t("1.93.01") > t("1.93.0")
    assert t("1.93.10") > t("1.93.09")
    assert t("1.93.99") > t("1.93.10")
    assert t("1.94.0") > t("1.93.99")


def test_notes_de_version():
    c = _fonction("nouveautes.py", "cle_version")
    assert c("1.93.01") > c("1.93.0")
    assert c("1.93.10") > c("1.93.09")
    assert c("2.5.7") > c("2.5.6")
