"""2.5.7 — l'auteur (16/10) : « lire la musique locale du NUC » depuis un lecteur vide. L'onglet « Bibliothèque » de l'appli
demande `quoi="album"` : les albums de la bibliothèque de Music Assistant (où arrive la musique locale), par nom.

    bash lancer.sh musique_bibliotheque_albums_test.py
"""
from __future__ import annotations

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, PARENT, _integrations, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
)


async def test_albums_de_la_bibliotheque(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import widgets
    widgets._recentes.clear()
    _coord, appels, _files = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client()
    s, r = await _post(client, "musique", PARENT, quoi="album")
    assert s == 200 and r["ok"] and r["items"], r
    demande = [a[1] for a in appels if a[0] == "get_library"][-1]
    assert demande["media_type"] == "album" and demande["order_by"] == "name" and demande["limit"] == 200
    # Comme les autres onglets : réservé à qui peut choisir la musique.
    s, r = await _post(client, "musique", ENFANT, quoi="album")
    assert s == 403, r
    # Un onglet inconnu reste refusé avec une phrase.
    s, r = await _post(client, "musique", PARENT, quoi="artiste")
    assert s >= 400 and r.get("retour"), r
