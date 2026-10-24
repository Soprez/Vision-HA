"""2.3.7 (APK 1.87.1, l'auteur : « dans la playlist les images ne se chargent pas ») — HA relaie les pochettes à l'appli :
une référence opaque `image_ref` à côté de chaque image (recherche, bibliothèque, file de la Party), et la route
POST /api/pc_parental/party/image qui ne sert QUE des références créées par HA (jamais une adresse de l'appareil).

    python -m pytest essai/party_relais_images_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import re

from homeassistant.core import ServiceCall, SupportsResponse

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, PARENT, TELE, _integrations, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
)

IMG = "http://10.1.1.11:8095/imageproxy?path=disney.jpg&provider=library&size=300"
REF = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


async def _avec_images(hass):
    async def search(call: ServiceCall):
        return {"tracks": [{"media_type": "track", "uri": "library://track/1", "name": "Hakuna Matata", "image": IMG}],
                "playlists": [{"media_type": "playlist", "uri": "library://playlist/17", "name": "Disney classique",
                               "image": "https://img.example/disney.jpg"}],
                "albums": [], "artists": [], "radio": []}

    async def library(call: ServiceCall):
        return {"items": [{"media_type": call.data["media_type"], "uri": "library://playlist/17", "name": "Disney classique",
                           "image": IMG},
                          {"media_type": call.data["media_type"], "uri": "library://playlist/18", "name": "Sans image"}]}
    hass.services.async_register("music_assistant", "search", search, supports_response=SupportsResponse.ONLY)
    hass.services.async_register("music_assistant", "get_library", library, supports_response=SupportsResponse.ONLY)


async def test_references_et_relais(hass, hass_storage, hass_client, monkeypatch):
    await _monter(hass, hass_storage, monkeypatch)
    await _avec_images(hass)
    from custom_components.pc_parental import party, party_invites
    lus = []

    async def lire(_hass, url):
        lus.append(url)
        return (b"\x89PNG-image", "image/png") if url == IMG else None
    monkeypatch.setattr(party_invites, "telecharger_image", lire)
    client = await hass_client()

    s, r = await _post(client, "musique", PARENT, quoi="playlist")
    assert s == 200
    avec, sans = r["items"]
    assert avec["image"] == IMG and REF.match(avec["image_ref"])  # « image » gardé pour l'APK 1.87.0
    assert "image_ref" not in sans
    s, r = await _post(client, "musique", TELE, quoi="chercher", texte="disney")
    assert s == 200
    for liste in r["resultats"].values():
        for x in liste:
            assert not x.get("image") or REF.match(x["image_ref"])
    ref = avec["image_ref"]
    assert party.url_de_ref(hass, ref) == IMG

    # Le relais : référence connue → l'image ; jamais une adresse venue du corps de la demande.
    resp = await client.post("/api/pc_parental/party/image", json={**TELE, "ref": ref, "url": "http://exemple.invalid/x",
                                                                   "image": "http://exemple.invalid/y"})
    assert resp.status == 200 and resp.content_type == "image/png" and await resp.read() == b"\x89PNG-image"
    assert lus == [IMG]
    # Référence inconnue, adresse seule : 404 sans rien lire.
    for corps in ({"ref": "0" * 32}, {"url": IMG}, {"ref": IMG}):
        resp = await client.post("/api/pc_parental/party/image", json={**TELE, **corps})
        assert resp.status == 404, corps
    assert lus == [IMG]
    # Mauvais secret : 401 ; appareil sans droit Party ni télé : 403.
    resp = await client.post("/api/pc_parental/party/image", json={"id": TELE["id"], "secret": "x" * 32, "ref": ref})
    assert resp.status == 401
    resp = await client.post("/api/pc_parental/party/image", json={**ENFANT, "ref": ref})
    assert resp.status == 403
    # Image illisible : 204.
    monkeypatch.setattr(party_invites, "telecharger_image", lambda _h, _u: _rien())
    resp = await client.post("/api/pc_parental/party/image", json={**TELE, "ref": ref})
    assert resp.status == 204


async def _rien():
    return None


async def test_registre_borne(hass, hass_storage, monkeypatch):
    await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import party

    premiere = party.ref_image(hass, "http://ma/0")
    assert party.ref_image(hass, "http://ma/0") == premiere  # même adresse, même référence
    assert party.ref_image(hass, "/data/local.jpg") == "" and party.ref_image(hass, "") == ""
    for i in range(1, party.REFS_MAX + 1):
        party.ref_image(hass, f"http://ma/{i}")
    assert party.url_de_ref(hass, premiere) == ""  # la plus ancienne est sortie
    assert len(hass.data[party._CLE_REFS]["refs"]) == party.REFS_MAX


class _Reponse:
    """Une réponse HTTP qui arrive en morceaux, comme une pochette de 128 Ko servie par Google."""

    def __init__(self, morceaux, type_="image/jpeg"):
        self.status, self.headers, self._m = 200, {"Content-Type": type_}, list(morceaux)
        outer = self

        class _Contenu:
            async def read(self, n=-1):  # comme aiohttp : AU PLUS n octets, ici le premier morceau seulement
                return outer._m[0]

            async def iter_chunked(self, n):
                for m in outer._m:
                    yield m
        self.content = _Contenu()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _Session:
    def __init__(self, morceaux):
        self.morceaux, self.urls = morceaux, []

    def get(self, url, **_kw):
        self.urls.append(url)
        return _Reponse(self.morceaux)


async def test_pochette_lue_en_entier(hass, hass_storage, monkeypatch):
    """2.3.9 : content.read(n) ne rendait que le premier morceau ; une pochette Google de 128 Ko arrivait tronquée."""
    await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import party_invites

    morceaux = [b"\xff\xd8" + b"a" * 49_998, b"b" * 50_000, b"c" * 28_000 + b"\xff\xd9"]
    session = _Session(morceaux)
    monkeypatch.setattr(party_invites, "async_get_clientsession", lambda _h: session)
    party_invites._images.clear()
    image = await party_invites.telecharger_image(hass, "https://yt3.googleusercontent.com/bambam=w600-h600-p")
    assert image is not None and image[0] == b"".join(morceaux) and image[1] == "image/jpeg"
    # Trop gros : refusé.
    session2 = _Session([b"x" * 1_000_000, b"y" * 1_000_000])
    monkeypatch.setattr(party_invites, "async_get_clientsession", lambda _h: session2)
    assert await party_invites.telecharger_image(hass, "https://exemple.invalid/enorme.jpg") is None


async def test_pochette_d_un_titre_de_la_file(hass, hass_storage, hass_client, monkeypatch):
    """2.3.9 (photo de l'auteur : « BAM BAM » proposé par un invité, carré gris dans la file) : un titre library:// de la file,
    pochette venue de YouTube Music, a sa référence et le relais renvoie l'image entière."""
    from party_musique_test import MA_SALON, TELE as TELE1
    _coord, _appels, files = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import party_invites

    url = "https://yt3.googleusercontent.com/bambam=w600-h600-p"
    # 2.5.12.14.2 : la file n'est lue que par get_queue, qui ne donne que le titre suivant : c'est lui qu'on met ici.
    files.items[1] = ({"queue_item_id": "q2", "media_item": {
        "name": "BAM BAM", "uri": "library://track/414", "artists": [{"name": "Dj Towla"}],
        "image": {"type": "thumb", "path": url, "provider": "ytmusic--x", "remotely_accessible": True}}})
    morceaux = [b"\xff\xd8" + b"a" * 60_000, b"b" * 68_000 + b"\xff\xd9"]
    session = _Session(morceaux)
    monkeypatch.setattr(party_invites, "async_get_clientsession", lambda _h: session)
    party_invites._images.clear()
    client = await hass_client()
    s, _r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200
    s, r = await _post(client, "etat", TELE1)
    bam = next(t for t in r["file"] if t.get("titre") == "BAM BAM")
    assert bam["image"] == url and REF.match(bam["image_ref"])
    resp = await client.post("/api/pc_parental/party/image", json={**TELE1, "ref": bam["image_ref"]})
    assert resp.status == 200 and await resp.read() == b"".join(morceaux)
    assert session.urls == [url]
