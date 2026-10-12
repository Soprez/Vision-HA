"""2.1.0 — tout le catalogue des fournisseurs reliés à Music Assistant (« dès que j'associe la clé d'un service, je
dois avoir tout son catalogue »), avec la provenance de chaque résultat et une explication quand un fournisseur
ne trouve rien. Fournisseurs simulés : YouTube Music (répond) et Spotify (ne trouve rien).

    python -m pytest essai/musique_catalogue_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.core import ServiceCall, SupportsResponse

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    MA_SALON, PARENT, TELE, _integrations, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
)

YT = [{"media_type": "track", "uri": "ytmusic--phePBE7p://track/4D7u5KF7SP8", "name": "Get Lucky",
       "artists": [{"name": "Daft Punk"}], "album": {"name": "Random Access Memories"}},
      {"media_type": "track", "uri": "library://track/231", "name": "Around the World", "artists": [{"name": "Daft Punk"}]}]


async def _catalogue(hass, hass_storage, monkeypatch, fours):
    coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique

    async def search(call: ServiceCall):
        appels.append(("search", dict(call.data)))
        return {"tracks": YT, "playlists": [], "albums": [], "artists": [], "radio": []}
    hass.services.async_register("music_assistant", "search", search, supports_response=SupportsResponse.ONLY)
    monkeypatch.setattr(musique, "fournisseurs", lambda _h: fours)
    return appels


async def test_tout_le_catalogue_avec_la_provenance(hass, hass_storage, hass_client, monkeypatch):
    appels = await _catalogue(hass, hass_storage, monkeypatch, [{"domaine": "ytmusic", "nom": "YouTube Music"},
                                                                {"domaine": "spotify", "nom": "Spotify"}])
    client = await hass_client()
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk")
    assert s == 200
    recherche = [c for n, c in appels if n == "search"][-1]
    assert recherche["library_only"] is False and recherche["limit"] >= 20  # pas seulement la bibliothèque, ni 5 par type
    pistes = r["resultats"]["track"]
    assert [p["source"] for p in pistes] == ["YouTube Music", "Bibliothèque"]
    assert {"nom": "YouTube Music", "n": 1} in r["sources"]
    # 2.2.7 (retour d'essai) : un fournisseur sans résultat n'est jamais affiché (« Ambient sounds : rien trouvé… » était du bruit).
    assert "rien trouvé" not in r["conseil"] and "Spotify" not in r["conseil"]
    # La télé (décideur de la Party) voit la même chose.
    s, r = await _post(client, "musique", TELE, quoi="chercher", texte="daft punk")
    assert s == 200 and r["resultats"]["track"][0]["source"] == "YouTube Music"


async def test_sans_service_en_ligne_on_explique_quoi_faire(hass, hass_storage, hass_client, monkeypatch):
    await _catalogue(hass, hass_storage, monkeypatch, [{"domaine": "filesystem_local", "nom": "Fichiers"}])
    client = await hass_client()
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk")
    assert s == 200 and "Music Assistant ▸ Réglages ▸ Fournisseurs" in r["conseil"] and r["vides"] == []


async def test_la_page_invite_montre_la_provenance(hass, hass_storage, hass_client_no_auth, monkeypatch):
    await _catalogue(hass, hass_storage, monkeypatch, None)
    client = await hass_client_no_auth()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    s, r = await _post(client, "etat", TELE)
    chemin = r["qr"].split("exemple.org", 1)[1]
    r = await client.post(chemin + "/chercher", json={"texte": "daft"})
    j = await r.json()
    assert r.status == 200 and [t["source"] for t in j["titres"]] == ["YouTube Music", "Bibliothèque"]
    assert all("://" not in str(t) for t in j["titres"])  # toujours aucune adresse Music Assistant


async def test_filtres_type_et_plateforme(hass, hass_storage, hass_client, monkeypatch):
    """2.2.8 (l'auteur : « il faut pouvoir choisir et filtrer : plateforme, titre, playlist, album ») : filtres cumulables ;
    les plateformes sont celles réellement reliées (jamais « Ambient sounds ») ; chaque résultat dit sa plateforme."""
    appels = await _catalogue(hass, hass_storage, monkeypatch, [{"domaine": "ytmusic", "nom": "YouTube Music"},
                                                                {"domaine": "ambient_sounds", "nom": "Ambient sounds"}])
    client = await hass_client()
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk")
    assert [p["domaine"] for p in r["plateformes"]] == ["library", "ytmusic"]
    assert {x["domaine"] for x in r["resultats"]["track"]} == {"ytmusic", "library"}
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk", plateformes=["ytmusic"])
    assert [x["nom"] for x in r["resultats"]["track"]] == ["Get Lucky"]
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk", types=["album"])
    recherche = [c for n, c in appels if n == "search"][-1]
    assert recherche["media_type"] == ["album"] and "track" not in r["resultats"]
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="daft punk", types=["<script>"], plateformes=["../x"])
    assert s == 200 and "track" in r["resultats"]  # filtres inconnus ignorés


async def test_page_invite_filtre_plateforme(hass, hass_storage, hass_client, monkeypatch):
    """2.2.8 : la page invité a les puces de plateforme ; le type reste « titres »."""
    appels = await _catalogue(hass, hass_storage, monkeypatch, [{"domaine": "ytmusic", "nom": "YouTube Music"},
                                                                {"domaine": "ambient_sounds", "nom": "Ambient sounds"}])
    client = await hass_client()
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    from custom_components.pc_parental import party
    jeton = party.etat(hass)["jeton"]
    page = await (await client.get(f"/api/pc_parental/party/invite/{jeton}")).text()
    assert 'data-d="ytmusic"' in page and "Ambient" not in page and 'data-d="library"' in page
    r = await client.post(f"/api/pc_parental/party/invite/{jeton}/chercher", json={"texte": "daft punk", "plateformes": ["ytmusic"], "appareil": "abc"})
    j = await r.json()
    assert r.status == 200 and [t["titre"] for t in j["titres"]] == ["Get Lucky"]
