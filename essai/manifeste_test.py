"""Point 11 (1.97.7) : l'intégration affiche la vraie version de Vision (Paramètres → Intégrations), et son icône
est livrée avec elle (servie par Home Assistant lui-même : Vision n'est pas dans le catalogue public des marques).

    python -m pytest essai/manifeste_test.py -q
"""
from __future__ import annotations

import json
import pathlib
import re

RACINE = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental"


def test_version_du_manifeste_suit_vision():
    manifeste = json.loads((RACINE / "manifest.json").read_text(encoding="utf-8"))
    version = re.search(r'^VERSION = "([^"]+)"', (RACINE / "const.py").read_text(encoding="utf-8"), re.M).group(1)
    assert manifeste["version"] == version


def test_icones_livrees():
    for nom, taille in (("icon.png", 256), ("icon@2x.png", 512)):
        octets = (RACINE / "brand" / nom).read_bytes()
        assert octets[:8] == b"\x89PNG\r\n\x1a\n"
        largeur, hauteur = int.from_bytes(octets[16:20], "big"), int.from_bytes(octets[20:24], "big")
        assert (largeur, hauteur) == (taille, taille), (nom, largeur, hauteur)
