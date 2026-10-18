"""2.4.0 (l'auteur, 2026-10-12) — la pochette du widget Musique suit le titre en cours d'un lecteur « · Vision ».

Le lecteur « · Vision » (Squeezelite de Vision) ne donne pas d'entity_picture : l'empreinte « pochette » restait vide et
le widget gardait l'ancienne image. Elle suit maintenant l'image du titre en cours dans la file de Music Assistant.

    python -m pytest essai/widget_pochette_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.core import State

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    _integrations, expected_lingering_tasks, expected_lingering_timers,
)


async def test_pochette_suit_la_file_de_music_assistant(hass, monkeypatch):
    from custom_components.pc_parental import musique, widgets

    image = {"url": "https://exemple.invalid/a.jpg"}
    monkeypatch.setattr(musique, "image_en_cours", lambda _h, _e: image["url"])
    etat = State("media_player.tele_du_bureau_vision", "playing", {"media_title": "It's That Time"})
    sans_hass = widgets.attributs_utiles(etat)
    assert sans_hass["pochette"] == ""  # sans hass : comme avant
    a = widgets.attributs_utiles(etat, hass)
    assert a["pochette"]
    image["url"] = "https://exemple.invalid/b.jpg"
    b = widgets.attributs_utiles(etat, hass)
    assert b["pochette"] and b["pochette"] != a["pochette"]  # le titre change : l'empreinte aussi
    # Une image du lecteur passe toujours avant la file.
    avec = State("media_player.salon", "playing", {"entity_picture": "/api/media_player_proxy/x?token=t"})
    assert widgets.attributs_utiles(avec, hass)["pochette"] == widgets.attributs_utiles(avec)["pochette"] != ""
    # Une file illisible : pas de pochette, sans erreur.
    monkeypatch.setattr(musique, "image_en_cours", lambda _h, _e: 1 / 0)
    assert widgets.attributs_utiles(etat, hass)["pochette"] == ""
