"""Recherche instantanée de la Musique (retours d'essai du 15-10) : l'appli cherche pendant qu'on tape.

- un texte déjà cherché il y a moins d'une minute ne réinterroge pas Music Assistant (effacer une lettre, revenir) ;
- « Ma bibliothèque seulement » est demandé à Music Assistant (library_only) ;
- Music Assistant a 8 s : au-delà, une phrase claire (503) au lieu d'attendre la coupure de l'appli ;
- la recherche a son propre compteur (90 par minute et par appareil) : elle n'épuise pas celui des playlists ;
- des suggestions pour chercher sans taper (artistes puis titres les plus joués, sans doublon).

    python -m pytest essai/musique_recherche_instantanee_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import asyncio

from homeassistant.core import ServiceCall, SupportsResponse

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    PARENT, _integrations, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
)

YT = [{"media_type": "track", "uri": "ytmusic--phePBE7p://track/4D7u5KF7SP8", "name": "Get Lucky",
       "artists": [{"name": "Daft Punk"}], "album": {"name": "Random Access Memories"}}]


async def _preparer(hass, hass_storage, monkeypatch, lent: float = 0.0):
    coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique, widgets

    widgets._recentes.clear()  # le compteur vit dans le module : chaque essai repart de zéro

    async def search(call: ServiceCall):
        appels.append(("search", dict(call.data)))
        if lent:
            await asyncio.sleep(lent)
        return {"tracks": YT, "playlists": [], "albums": [], "artists": [], "radio": []}
    hass.services.async_register("music_assistant", "search", search, supports_response=SupportsResponse.ONLY)
    monkeypatch.setattr(musique, "fournisseurs", lambda _h: [{"domaine": "ytmusic", "nom": "YouTube Music"}])
    return appels, musique


def _recherches(appels):
    return [c for n, c in appels if n == "search"]


async def test_meme_texte_sans_rappeler_music_assistant(hass, hass_storage, hass_client, monkeypatch):
    appels, _m = await _preparer(hass, hass_storage, monkeypatch)
    client = await hass_client()
    for texte in ("daft", "daft p", "Daft  ", "daft"):
        s, r = await _post(client, "musique", PARENT, quoi="chercher", texte=texte)
        assert s == 200 and r["resultats"]["track"][0]["nom"] == "Get Lucky"
    # « daft », « daft p » ; « Daft  » et le retour à « daft » viennent de la mémoire (casse et espaces ignorés).
    assert [c["name"] for c in _recherches(appels)] == ["daft", "daft p"]


async def test_bibliotheque_seulement(hass, hass_storage, hass_client, monkeypatch):
    appels, _m = await _preparer(hass, hass_storage, monkeypatch)
    client = await hass_client()
    await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk", bibliotheque=True)
    await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk")
    assert [c["library_only"] for c in _recherches(appels)] == [True, False]


async def test_music_assistant_trop_lent(hass, hass_storage, hass_client, monkeypatch):
    appels, musique = await _preparer(hass, hass_storage, monkeypatch, lent=1.0)
    monkeypatch.setattr(musique, "RECHERCHE_DELAI_S", 0.2)
    client = await hass_client()
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk")
    assert s == 503 and "trop de temps" in r["retour"]


async def test_compteur_de_la_recherche(hass, hass_storage, hass_client, monkeypatch):
    appels, _m = await _preparer(hass, hass_storage, monkeypatch)
    client = await hass_client()
    for i in range(40):  # au-delà des 30 actions par minute d'avant
        s, _r = await _post(client, "musique", PARENT, quoi="chercher", texte=f"titre {i}")
        assert s == 200, i
    # Les playlists gardent leur compteur à elles.
    s, _r = await _post(client, "musique", PARENT, quoi="playlist")
    assert s != 429
    from custom_components.pc_parental import party
    for i in range(40, party.RECHERCHES_PAR_MINUTE):
        await _post(client, "musique", PARENT, quoi="chercher", texte=f"titre {i}")
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="encore")
    assert s == 429 and r["error"] == "trop_vite"


async def test_suggestions(hass, hass_storage, hass_client, monkeypatch):
    _appels, musique = await _preparer(hass, hass_storage, monkeypatch)

    async def joues(_h, n=12):
        return [{"nom": "Get Lucky", "artiste": "Daft Punk, Pharrell Williams"}, {"nom": "Around the World", "artiste": "Daft Punk"},
                {"nom": "Daft Punk", "artiste": "Inconnu"}][:n]
    monkeypatch.setattr(musique, "souvent_joues", joues)
    client = await hass_client()
    s, r = await _post(client, "musique", PARENT, quoi="suggestions")
    assert s == 200
    assert r["suggestions"] == ["Daft Punk", "Pharrell Williams", "Inconnu", "Get Lucky", "Around the World"]
