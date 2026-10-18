"""Accueil : les widgets intégrés proposés selon les données du lieu (2.5.3, l'auteur : « rien en dur, Vision va dans
d'autres foyers »). /veille envoie « widgets_disponibles », calculé d'après ce qu'il envoie déjà.

    python -m pytest essai/widgets_disponibles_test.py -q
"""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import types


def _veille():
    """veille.widgets_disponibles est pur : on le charge sans Home Assistant (le reste du module n'est pas appelé)."""
    racine = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental" / "veille.py"
    source = racine.read_text(encoding="utf-8")
    debut = source.index("def widgets_disponibles(")
    fin = source.index("async def etat_veille(")
    espace: dict = {"Any": object}
    exec("from typing import Any\n" + source[debut:fin], espace)
    return types.SimpleNamespace(widgets_disponibles=espace["widgets_disponibles"])


V = _veille()


def test_un_lieu_sans_rien():
    # Un foyer sans météo, chauffage, école, caméra ni batterie : seuls le temps d'écran et les demandes.
    vide = {"meteo": {}, "chauffage": None, "ecole": [], "cameras": [], "batteries": [], "maison": []}
    assert V.widgets_disponibles(vide) == ["temps", "demandes"]


def test_chaque_donnee_ouvre_son_widget():
    d = {"meteo": {"temperature": 12}, "chauffage": {"dedans": 20, "jours": [], "mois": None, "saison": None},
         "ecole": [{"prenom": "Lou"}], "cameras": [{"entity_id": "camera.entree"}], "batteries": [{"nom": "Capteur", "niveau": 40}],
         "maison": [{"personne": "Lou"}]}
    dispo = V.widgets_disponibles(d)
    for w in ("horloge", "chauffage", "ecole", "infos", "devoirs", "camera", "batteries", "enfants", "maison"):
        assert w in dispo, w
    # Un thermostat seul ne fait pas un widget « fioul » : il faut une consommation.
    assert "fioul" not in dispo


def test_fioul_avec_une_consommation():
    for conso in ({"jours": [{"j": "2026-10-14", "l": 3.2}]}, {"mois": 120.0}, {"saison": 900.0}):
        d = {"chauffage": {"dedans": 20, "jours": [], "mois": None, "saison": None, **conso}}
        assert "fioul" in V.widgets_disponibles(d), conso


def test_donnees_absentes_ou_mal_formees():
    # Clés manquantes (ancien serveur, module coupé) : rien de plus que le minimum, jamais d'erreur.
    assert V.widgets_disponibles({}) == ["temps", "demandes"]
