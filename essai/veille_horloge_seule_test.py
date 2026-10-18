"""1.93.05 (l'auteur, retour 35) : la page horloge de la veille ne montre que l'horloge — ni température ni ciel.

Côté Home Assistant : la page de veille ne nomme plus d'entité météo pour l'horloge, la passerelle ne la rend donc plus
lisible par l'appareil, et la veille web dessine l'horloge sans « ciel », quel que soit le style.

    python -m pytest essai/veille_horloge_seule_test.py -q
"""
from __future__ import annotations

import pathlib

RACINE = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental"


def test_page_sans_meteo():
    texte = (RACINE / "veille_page.py").read_text(encoding="utf-8")
    debut = texte.index("async def page(")
    corps = texte[debut:texte.index("\ndef ", debut)]
    assert '"meteo"' not in corps and 'async_all("weather")' not in corps


def test_veille_web_horloge_sans_ciel():
    js = (RACINE / "frontend" / "vision-veille-coeur.js").read_text(encoding="utf-8")
    assert "_ciel(" not in js
    assert "this._horloge(\"\")" in js


async def test_passerelle_ne_rend_plus_la_meteo_lisible(hass):
    from custom_components.pc_parental import veille_passerelle

    hass.states.async_set("weather.maison", "rainy", {"temperature": 12})
    hass.states.async_set("lock.cle_noa", "locked")
    # Une ancienne page qui nommerait encore la météo : elle n'ouvre plus rien.
    page = {"meteo": "weather.maison", "tableaux": [{"sections": [{"cartes": [{"type": "tile", "entity": "lock.cle_noa"}]}]}]}
    assert veille_passerelle.entites_de_la_page(hass, page) == {"lock.cle_noa"}
