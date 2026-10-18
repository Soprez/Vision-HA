"""15-10 (règle UX de l'auteur) : le sélecteur partagé « étiquettes ou liste déroulante » des cartes (vision-choix.js).

- 3 options au plus qui tiennent sur la largeur : étiquettes ; plus de 3, ou débordement mesuré : liste.
- Le champ d'un choix multiple résume « A, B +2 ».
- Chargé partout comme les cartes (cartes.py), avant elles.

    python -m pytest essai/choix_adaptatif_test.py -q
"""
from __future__ import annotations

import json
import pathlib
import shutil
import subprocess

import pytest

RACINE = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental"
SRC = (RACINE / "frontend" / "vision-choix.js").read_text(encoding="utf-8")


def _fonction(nom: str) -> str:
    debut = SRC.index(f"const {nom} = ")
    fin = SRC.index(";\n", debut) + 2
    return SRC[debut:fin]


def _node(script: str):
    node = shutil.which("node")
    if not node:
        pytest.skip("Node manque.")
    return json.loads(subprocess.run([node, "-"], input=script, capture_output=True, text=True, encoding="utf-8",
                                     check=True).stdout)


def test_etiquettes_ou_liste():
    r = _node("const MAX_ETIQUETTES = 3;\n" + _fonction("enEtiquettes") + """
console.log(JSON.stringify([
  enEtiquettes(3, 300, 360), enEtiquettes(1, 80, 360),
  enEtiquettes(4, 200, 1000),   // plus de trois : liste, même si ça tiendrait
  enEtiquettes(3, 420, 360),    // trois qui débordent : liste
  enEtiquettes(0, 0, 360),      // aucune option
  enEtiquettes(3, 5000, 0),     // largeur inconnue : on compte
]));""")
    assert r == [True, True, False, False, False, True]


def test_resume():
    r = _node(_fonction("resume") + """
console.log(JSON.stringify([resume([], "-"), resume(["A"], "-"), resume(["A", "B"], "-"), resume(["A", "B", "C", "D"], "-")]));""")
    assert r == ["-", "A", "A, B", "A, B +2"]


def test_charge_avant_les_cartes():
    # Lu dans le source (sans importer Home Assistant) : l'essai tourne seul comme dans la suite.
    import ast
    arbre = ast.parse((pathlib.Path(__file__).resolve().parents[1] / "custom_components" / "pc_parental" / "cartes.py").read_text(encoding="utf-8"))
    cartes = next(ast.literal_eval(n.value) for n in arbre.body if isinstance(n, ast.Assign) and getattr(n.targets[0], "id", "") == "CARTES")
    assert cartes[0] == "vision-choix.js"
    assert 'customElements.define("vision-choix"' in SRC
    # Accessibilité et télécommande : rôles radio / case, libellé du groupe, flèches gauche / droite, <select> natif.
    for morceau in ('role="${role}"', "aria-checked", "aria-label", "ArrowRight", "<select", "ResizeObserver"):
        assert morceau in SRC, morceau
