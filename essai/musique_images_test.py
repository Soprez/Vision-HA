"""2.3.5 (l'auteur : les pochettes ne se chargent pas dans les playlists) — une image de Music Assistant donnée par un chemin
interne et son fournisseur (éléments library://, fichiers locaux) passe par l'imageproxy de Music Assistant ; une
adresse http(s) reste telle quelle ; un chemin sans fournisseur ou sans adresse de Music Assistant ne sort jamais.

    python -m pytest essai/musique_images_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from urllib.parse import parse_qs, urlsplit

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)

BASE = "http://192.168.1.11:8095"


async def test_chemin_interne_par_l_imageproxy(hass, hass_storage, monkeypatch):
    await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique

    # Adresse déjà servie (service get_library) : inchangée.
    assert musique._image({"image": f"{BASE}/imageproxy/abc?size=0"}, BASE) == f"{BASE}/imageproxy/abc?size=0"
    # library:// (client Music Assistant) : chemin interne + fournisseur → imageproxy, chemin encodé.
    piste = {"uri": "library://track/12", "name": "Titre", "media_type": "track",
             "image": {"type": "thumb", "path": "/media/Musique/Disney/cover 1&2.jpg", "provider": "filesystem_local--x1",
                       "remotely_accessible": False}}
    url = musique._image(piste, BASE)
    assert url.startswith(BASE + "/imageproxy?")
    q = parse_qs(urlsplit(url).query)
    assert q["path"] == ["/media/Musique/Disney/cover 1&2.jpg"] and q["provider"] == ["filesystem_local--x1"]
    # Pas d'« image » mais des métadonnées : la vignette (thumb) d'abord.
    album = {"metadata": {"images": [{"type": "fanart", "path": "fan.jpg", "provider": "p"},
                                     {"type": "thumb", "path": "thumb.jpg", "provider": "p"}]}}
    assert parse_qs(urlsplit(musique._image(album, BASE)).query)["path"] == ["thumb.jpg"]
    # Jamais un chemin local brut : sans fournisseur, ou sans adresse de Music Assistant, rien.
    assert musique._image({"image": "/data/cover.jpg"}, BASE) == ""
    assert musique._image({"image": {"path": "cover.jpg", "provider": "p"}}, "") == ""
    assert musique._image({}, BASE) == "" and musique._image(None, BASE) == ""  # type: ignore[arg-type]


async def test_element_de_playlist_avec_pochette(hass, hass_storage, monkeypatch):
    await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique

    monkeypatch.setattr(musique, "_BASE_MA", BASE)
    e = musique.element({"uri": "library://playlist/17", "name": "Disney classique", "media_type": "playlist",
                         "metadata": {"images": [{"type": "thumb", "path": "playlist17.png", "provider": "library"}]}})
    assert e is not None and e["image"].startswith(BASE + "/imageproxy?") and "playlist17.png" in e["image"]
    # L'album d'un titre sert de repli.
    t = musique.element({"uri": "library://track/3", "name": "Hakuna Matata", "media_type": "track",
                         "album": {"name": "Le Roi lion", "image": {"path": "roi.jpg", "provider": "library"}}})
    assert t is not None and "roi.jpg" in t["image"]


async def test_base_retenue_depuis_l_integration(hass, hass_storage, monkeypatch):
    await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique

    class _Entree:
        data = {"url": "http://d5369777-music-assistant:8094/"}

    monkeypatch.setattr(musique, "_client", lambda _h: None)
    monkeypatch.setattr(musique, "entree", lambda _h: _Entree())
    assert musique.retenir_base(hass) == "http://d5369777-music-assistant:8094"

    class _Info:
        base_url = BASE

    class _Mass:
        server_info = _Info()

    monkeypatch.setattr(musique, "_client", lambda _h: _Mass())
    assert musique.retenir_base(hass) == BASE
