"""1.94 (point 4) : images des messages riches et GIF Giphy (option, désactivée par défaut).

- types vérifiés par les octets, réduction à 1280 px, GIF → WebP animé (480 px, 15 images/s, 6 s au plus), cache disque borné ;
- Giphy : classification (envoyée ET revérifiée), limite de 10 appels par minute, cache 24 h, sans clé → refus traduit, la clé
  ne ressort jamais ; hôtes Giphy seulement ;
- `pc_parental.envoyer` avec `gif` ou `image` (https) ; `giphy_regler` ; la recherche de l'appli (Décideurs seulement).

    python -m pytest essai/giphy_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import io
import os
import time

import pytest
from PIL import Image

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry



class _Module:
    """Le module de l'intégration, importé à l'usage : le banc recharge `custom_components` depuis une copie, et un import fait
    en tête de fichier désignerait l'ancienne (les exceptions de `textes` ne seraient plus les mêmes classes)."""

    def __init__(self, nom: str) -> None:
        self._nom = nom

    def __getattr__(self, attribut: str):
        import importlib

        return getattr(importlib.import_module(f"custom_components.pc_parental.{self._nom}"), attribut)


giphy, images, textes = _Module("giphy"), _Module("images"), _Module("textes")


def _gif(images_n: int = 3, duree_ms: int = 100, taille=(64, 48)) -> bytes:
    trames = [Image.new("RGB", taille, (30 * i % 255, 80, 160)) for i in range(images_n)]
    sortie = io.BytesIO()
    trames[0].save(sortie, format="GIF", save_all=True, append_images=trames[1:], duration=duree_ms, loop=0)
    return sortie.getvalue()


def _jpeg(taille=(2000, 1500)) -> bytes:
    sortie = io.BytesIO()
    Image.new("RGB", taille, (200, 30, 30)).save(sortie, format="JPEG")
    return sortie.getvalue()


# ------------------------------------------------------------------ images.py


def test_types_par_les_octets():
    assert images.detecter(_jpeg((10, 10))) == "image/jpeg"
    assert images.detecter(_gif()) == "image/gif"
    assert images.detecter(b"<svg onload=alert(1)>") is None
    assert images.detecter(b"MZ\x90\x00 exe") is None
    with pytest.raises(images.ImageRefusee):
        images.image_vers_webp(b"<svg onload=alert(1)>")
    with pytest.raises(images.ImageRefusee):
        images.image_vers_webp(_gif())          # un GIF passe par gif_vers_webp


def test_image_reduite_a_1280_px_et_recodee_en_webp():
    webp, mime = images.image_vers_webp(_jpeg((4000, 3000)))
    assert mime == "image/webp" and images.detecter(webp) == "image/webp"
    assert max(Image.open(io.BytesIO(webp)).size) == 1280
    petite, _ = images.image_vers_webp(_jpeg((300, 200)))
    assert Image.open(io.BytesIO(petite)).size == (300, 200)          # jamais agrandie


def test_bombe_de_decompression_refusee():
    sortie = io.BytesIO()
    Image.new("1", (9000, 9000)).save(sortie, format="PNG")            # 81 millions de pixels, quelques ko
    with pytest.raises(images.ImageRefusee):
        images.image_vers_webp(sortie.getvalue())


def test_gif_vers_webp_anime_coupe_a_6_secondes():
    # 20 images de 500 ms = 10 s : coupé à 6 s ; 480 px de large au plus ; une vignette fixe.
    anime, fixe, infos = images.gif_vers_webp(_gif(20, 500, (960, 540)))
    assert images.detecter(anime) == "image/webp" and images.detecter(fixe) == "image/webp"
    assert infos["coupe"] is True and infos["duree_ms"] <= 6000 and infos["largeur"] <= 480
    assert getattr(Image.open(io.BytesIO(anime)), "n_frames", 1) == infos["images"] > 1
    # Un GIF court : tel quel (même nombre d'images), pas coupé.
    _a, _f, court = images.gif_vers_webp(_gif(3, 200))
    assert court["images"] == 3 and court["coupe"] is False


def test_gif_trop_rapide_fusionne_les_images():
    # 40 images de 20 ms (50 i/s) : 15 images/s au plus, la durée totale est conservée.
    _a, _f, infos = images.gif_vers_webp(_gif(40, 20))
    assert infos["images"] < 40 and abs(infos["duree_ms"] - 800) <= 60


def test_vignette_fixe():
    v = images.vignette(_gif(), 40)
    assert images.detecter(v) == "image/webp" and max(Image.open(io.BytesIO(v)).size) <= 40


def test_cache_disque_lru_et_age(tmp_path):
    c = images.CacheDisque(str(tmp_path / "c"), max_octets=100)
    a = c.ecrire("a", b"x" * 40)
    time.sleep(0.02)
    b = c.ecrire("b", b"x" * 40)
    time.sleep(0.02)
    assert c.lire("a") == a                       # lire rajeunit « a » : « b » devient le plus ancien
    time.sleep(0.02)
    c.ecrire("c", b"x" * 40)                      # 120 octets : « b » part
    assert c.lire("b") is None and c.lire("a") and c.lire("c")
    assert c.taille() <= 100
    vieux = images.CacheDisque(str(tmp_path / "v"), max_octets=10_000, duree_max_s=60)
    chemin = vieux.ecrire("k", b"y")
    os.utime(chemin, (time.time() - 3600, time.time() - 3600))
    assert vieux.lire("k") is None and not os.path.exists(chemin)


# ------------------------------------------------------------------ giphy.py (fonctions pures)


def test_classification_jamais_au_dessus():
    assert giphy.rating_autorise("g", "g") and not giphy.rating_autorise("pg", "g")
    assert giphy.rating_autorise("PG", "pg-13") and giphy.rating_autorise("pg-13", "pg-13") and not giphy.rating_autorise("r", "pg-13")
    assert giphy.rating_autorise("r", "r")
    assert not giphy.rating_autorise("", "r") and not giphy.rating_autorise("nr", "r") and not giphy.rating_autorise(None, "r")
    assert giphy.rating_autorise("g", "n'importe quoi") and not giphy.rating_autorise("pg", "n'importe quoi")   # inconnu : « g »


def test_hotes_giphy_seulement():
    for ok in ("https://api.giphy.com/v1/gifs/search", "https://media3.giphy.com/media/abc/giphy.gif", "https://i.giphy.com/x.gif"):
        assert giphy.hote_giphy(ok), ok
    for ko in ("http://api.giphy.com/v1", "https://giphy.com.evil.example/x", "https://evil.example/api.giphy.com",
               "https://user@api.giphy.com/x", "https://xgiphy.com/x", "ftp://api.giphy.com/x", "https://localhost/x"):
        assert not giphy.hote_giphy(ko), ko


def test_identifiant_ou_mot_cle():
    assert giphy.identifiant("https://giphy.com/gifs/cat-dance-l0HlBO7eyXzSZkJri") == "l0HlBO7eyXzSZkJri"
    assert giphy.identifiant("https://media2.giphy.com/media/l0HlBO7eyXzSZkJri/giphy.gif") == "l0HlBO7eyXzSZkJri"
    assert giphy.identifiant("l0HlBO7eyXzSZkJri") == "l0HlBO7eyXzSZkJri"
    for mot in ("bravo", "bien joué", "congratulations", "félicitations", "https://exemple.org/gifs/x-abcdefghij1", ""):
        assert giphy.identifiant(mot) is None, mot


# ------------------------------------------------------------------ giphy.py (client, faux serveur Giphy)


class FauxGiphy:
    """Répond comme api.giphy.com ; compte les appels (par type) et garde les paramètres reçus."""

    def __init__(self, classements=(("AAAAAAAAAA1", "g"), ("BBBBBBBBBB2", "pg"), ("CCCCCCCCCC3", "r"))):
        self.classements = list(classements)
        self.appels: list[tuple[str, dict]] = []
        self.gif_octets = _gif(4, 150)

    def item(self, ident, rating):
        base = f"https://media1.giphy.com/media/{ident}"
        return {"id": ident, "title": f"GIF {ident}", "rating": rating, "images": {
            "original": {"url": f"{base}/giphy.gif", "size": "90000"},
            "fixed_width_small_still": {"url": f"{base}/100w_s.gif"}}}

    async def __call__(self, url, params, maxi):
        import json

        if "/search" in url:
            self.appels.append(("search", dict(params or {})))
            # Le faux serveur renvoie TOUT, même au-dessus de la classification demandée : c'est HA qui doit trier.
            return json.dumps({"data": [self.item(i, r) for i, r in self.classements]}).encode()
        if "/translate" in url:
            self.appels.append(("translate", dict(params or {})))
            i, r = self.classements[0]
            return json.dumps({"data": self.item(i, r)}).encode()
        if url.startswith("https://api.giphy.com/v1/gifs/"):
            self.appels.append(("get", dict(params or {})))
            ident = url.rsplit("/", 1)[1]
            r = dict(self.classements).get(ident)
            return json.dumps({"data": self.item(ident, r) if r else {}}).encode()
        self.appels.append(("fichier", {"url": url}))
        return self.gif_octets


async def _client(hass, tmp_path, store=None, faux=None, **reglages):
    class _Store:
        giphy: dict = {}
    s = store or _Store()
    s.giphy = {"actif": True, "cle": "k" * 24, "classification": "g", **reglages}
    faux = faux or FauxGiphy()
    t = [1000.0]
    g = giphy.Giphy(hass, s, str(tmp_path / "gc"), lambda: "fr", fetch=faux, horloge=lambda: t[0])
    return g, faux, t, s


async def test_desactive_ou_sans_cle_refus_traduit(hass, tmp_path):
    g, _f, _t, s = await _client(hass, tmp_path, actif=False)

    with pytest.raises(textes.Erreur) as e:
        await g.rechercher("chat")
    assert e.value.cle == "giphy.desactive"
    s.giphy = {"actif": True, "cle": "", "classification": "g"}
    with pytest.raises(textes.Erreur) as e:
        await g.gif("bravo")
    assert e.value.cle == "giphy.sans_cle"


async def test_recherche_ne_garde_que_la_classification_demandee(hass, tmp_path):
    g, faux, _t, _s = await _client(hass, tmp_path)                      # « g » : le serveur renvoie g, pg et r
    r = await g.rechercher("chat dansant")
    assert [x["id"] for x in r] == ["AAAAAAAAAA1"]
    assert r[0]["vignette"].startswith("data:image/webp;base64,")
    sent = faux.appels[0][1]
    assert sent["rating"] == "g" and sent["lang"] == "fr" and sent["api_key"] == "k" * 24
    g2, _f2, _t2, _s2 = await _client(hass, tmp_path, classification="pg")
    assert [x["id"] for x in await g2.rechercher("chat dansant")] == ["AAAAAAAAAA1", "BBBBBBBBBB2"]


async def test_recherche_gardee_24_heures(hass, tmp_path):
    g, faux, t, _s = await _client(hass, tmp_path)
    await g.rechercher("chat dansant")
    await g.rechercher("Chat  dansant")                                   # même recherche (casse, espaces)
    assert [a[0] for a in faux.appels].count("search") == 1
    t[0] += 25 * 3600
    await g.rechercher("chat dansant")
    assert [a[0] for a in faux.appels].count("search") == 2


async def test_limite_de_dix_appels_par_minute(hass, tmp_path):

    g, _f, t, _s = await _client(hass, tmp_path)
    for i in range(10):
        await g.rechercher(f"mot {i}")
    with pytest.raises(textes.Erreur) as e:
        await g.rechercher("onzième")
    assert e.value.cle == "giphy.limite"
    t[0] += 61
    await g.rechercher("onzième")                                         # une minute plus tard : permis


async def test_gif_par_mot_cle_en_webp_anime_et_cache(hass, tmp_path):
    g, faux, _t, _s = await _client(hass, tmp_path)
    m = await g.gif("bravo")
    assert m["mime"] == "image/webp" and m["type"] == "image" and m["octets"] > 0
    with open(m["chemin"], "rb") as f:
        assert images.detecter(f.read()) == "image/webp"
    avant = len(faux.appels)
    m2 = await g.gif("bravo")                                             # le même mot-clé : rien de plus chez Giphy
    assert m2["chemin"] == m["chemin"] and len(faux.appels) == avant


async def test_gif_au_dessus_de_la_classification_refuse_meme_par_identifiant(hass, tmp_path):

    g, _f, _t, _s = await _client(hass, tmp_path)                          # « g »
    with pytest.raises(textes.Erreur) as e:
        await g.gif("https://giphy.com/gifs/x-CCCCCCCCCC3")                # classé « r »
    assert e.value.cle == "giphy.classification_depassee"
    with pytest.raises(textes.Erreur) as e:
        await g.gif("ZZZZZZZZZZ9")                                         # inconnu de Giphy
    assert e.value.cle == "giphy.rien"


async def test_gif_trop_lourd_refuse(hass, tmp_path):

    class Lourd(FauxGiphy):
        def item(self, ident, rating):
            it = super().item(ident, rating)
            for nom in it["images"]:
                if "size" in it["images"][nom]:
                    it["images"][nom]["size"] = "9000000"
            return it

    g, _f, _t, _s = await _client(hass, tmp_path, faux=Lourd())
    # Les versions listées sont toutes au-dessus de 4 Mo : « original » seul est annoncé, il est écarté.
    with pytest.raises(textes.Erreur) as e:
        await g.gif("https://giphy.com/gifs/x-AAAAAAAAAA1")
    assert e.value.cle == "giphy.trop_lourd"


async def test_une_url_hors_giphy_n_est_jamais_telechargee(hass, tmp_path):
    class Pirate(FauxGiphy):
        def item(self, ident, rating):
            it = super().item(ident, rating)
            it["images"]["original"]["url"] = "https://evil.example/x.gif"
            return it


    faux = Pirate()
    g, _f, _t, _s = await _client(hass, tmp_path, faux=faux)
    with pytest.raises(textes.Erreur):
        await g.gif("https://giphy.com/gifs/x-AAAAAAAAAA1")
    assert not any(a[0] == "fichier" and "evil" in a[1]["url"] for a in faux.appels)


# ------------------------------------------------------------------ réglages, service, messages, recherche de l'appli


async def _installer(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-CAMILLE", ENFANT["secret"], person="person.camille", platform="android"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}}
    hass.states.async_set("person.camille", "home", {"friendly_name": "Camille"})
    hass.states.async_set("person.nolan", "home", {"friendly_name": "Nolan"})
    entree = MockConfigEntry(domain=DOMAIN, data={"tableau_voulu": True}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def test_service_giphy_regler_ne_renvoie_jamais_la_cle(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    assert giphy.etat(coord.store) == {"actif": False, "cle_posee": False, "classification": "g", "au_dessus_de_g": False}
    r = await hass.services.async_call(DOMAIN, "giphy_regler", {"actif": True, "cle": "a" * 32, "classification": "pg"},
                                       blocking=True, return_response=True)
    assert r == {"actif": True, "cle_posee": True, "classification": "pg", "au_dessus_de_g": True}
    assert "a" * 32 not in repr(r)
    from homeassistant.exceptions import HomeAssistantError

    with pytest.raises(HomeAssistantError):
        await hass.services.async_call(DOMAIN, "giphy_regler", {"cle": "trop court !"}, blocking=True)
    assert giphy.reglages(coord.store)["cle"] == "a" * 32               # la clé refusée n'a rien changé


async def test_envoyer_un_gif_par_mot_cle(hass, hass_storage, tmp_path):
    coord = await _installer(hass, hass_storage)
    giphy.regler(coord.store, actif=True, cle="k" * 24)
    hass.data[f"{DOMAIN}_giphy"] = giphy.Giphy(hass, coord.store, str(tmp_path / "gc"), lambda: "fr", fetch=FauxGiphy())
    r = await hass.services.async_call(DOMAIN, "envoyer", {"pc": "TEL-CAMILLE", "gif": "bravo", "message": "Bravo !"}, blocking=True, return_response=True)
    assert len(r["messages"]) == 1
    m = coord.store.pcs["e1"]["messages"][-1]
    assert m["type"] == "image" and m["media"]["mime"] == "image/webp" and m["media"]["octets"] > 0
    # Servi à l'appareil par jeton : jamais le chemin du fichier.
    assert "chemin" not in repr(m) and "pc_parental_giphy" not in repr(m)


async def test_envoyer_un_gif_sans_option_activee_refuse(hass, hass_storage):
    from homeassistant.exceptions import HomeAssistantError

    await _installer(hass, hass_storage)
    with pytest.raises(Exception) as e:
        await hass.services.async_call(DOMAIN, "envoyer", {"pc": "TEL-CAMILLE", "gif": "bravo"}, blocking=True)
    assert "Giphy" in str(e.value) or isinstance(e.value, HomeAssistantError)


async def test_envoyer_une_image_par_url_https(hass, hass_storage, tmp_path):

    coord = await _installer(hass, hass_storage)
    # Un faux téléchargement : HA décode, réduit et réencode ; l'appareil ne verra qu'un WebP de 1280 px.
    async def fetch(url, params, maxi):
        return _jpeg((3000, 2000))

    m = await giphy.image_par_url(hass, "https://exemple.org/photo.jpg", str(tmp_path / "ic"), fetch=fetch)
    assert m["mime"] == "image/webp"
    with open(m["chemin"], "rb") as f:
        assert max(Image.open(io.BytesIO(f.read())).size) == 1280
    for faux in ("http://exemple.org/p.jpg", "file:///etc/passwd", "https://user@exemple.org/p.jpg", "/media/p.jpg"):
        with pytest.raises(textes.Erreur) as e:
            await giphy.image_par_url(hass, faux, str(tmp_path / "ic"), fetch=fetch)
        assert e.value.cle == "images.url_https"

    async def pas_une_image(url, params, maxi):
        return b"<html>pas une image</html>"

    with pytest.raises(textes.Erreur) as e:
        await giphy.image_par_url(hass, "https://exemple.org/page", str(tmp_path / "ic"), fetch=pas_une_image)
    assert e.value.cle == "images.refusee"


async def test_recherche_de_l_appli_decideurs_seulement(hass, hass_storage, hass_client, tmp_path):
    coord = await _installer(hass, hass_storage)
    giphy.regler(coord.store, actif=True, cle="k" * 24)
    hass.data[f"{DOMAIN}_giphy"] = giphy.Giphy(hass, coord.store, str(tmp_path / "gc"), lambda: "fr", fetch=FauxGiphy())
    client = await hass_client()
    r = await client.post("/api/pc_parental/giphy", json={**PARENT, "q": "chat dansant"})
    corps = await r.json()
    assert r.status == 200 and corps["ok"] and [x["id"] for x in corps["resultats"]] == ["AAAAAAAAAA1"]
    assert corps["attribution"] == "Powered by GIPHY" and "k" * 24 not in repr(corps)
    r = await client.post("/api/pc_parental/giphy", json={**ENFANT, "q": "chat dansant"})       # un Demandeur : non
    assert r.status == 403
    r = await client.post("/api/pc_parental/giphy", json={"id": "x", "secret": "y", "q": "chat"})
    assert r.status == 401
    r = await client.post("/api/pc_parental/giphy", json={**PARENT, "q": "a"})                  # trop court : refus en clair
    assert r.status == 400 and (await r.json())["retour"]


# ------------------------------------------------------------------ messages V2 (bouton GIF de l'appli) et Configurer


async def test_message_v2_avec_un_gif(hass, hass_storage, tmp_path):
    coord = await _installer(hass, hass_storage)
    giphy.regler(coord.store, actif=True, cle="k" * 24)
    hass.data[f"{DOMAIN}_giphy"] = giphy.Giphy(hass, coord.store, str(tmp_path / "gc"), lambda: "fr", fetch=FauxGiphy())
    from importlib import import_module

    v2 = import_module("custom_components.pc_parental.messages_v2")
    r = await v2.envoyer(hass, coord, {"destinataires": ["e1"], "texte": "Bravo !", "gif": "AAAAAAAAAA1"}, {"user": "Nolan"})
    m = coord.store.pcs["e1"]["messages"][-1]
    assert r["messages"] and m["type"] == "image" and m["media"]["mime"] == "image/webp" and m["text"] == "Bravo !"
    # Sans GIF : un message de texte, comme avant.
    await v2.envoyer(hass, coord, {"destinataires": ["e1"], "texte": "Salut"}, {"user": "Nolan"})
    assert coord.store.pcs["e1"]["messages"][-1]["type"] == "texte" and "media" not in coord.store.pcs["e1"]["messages"][-1]


async def test_le_bouton_gif_n_existe_que_si_giphy_est_pret(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    corps = await (await client.post("/api/pc_parental/messages/app", json={**PARENT, "action": "lire"})).json()
    assert corps["giphy"] is False
    giphy.regler(coord.store, actif=True)
    assert (await (await client.post("/api/pc_parental/messages/app", json={**PARENT, "action": "lire"})).json())["giphy"] is False   # pas de clé
    giphy.regler(coord.store, cle="k" * 24)
    corps = await (await client.post("/api/pc_parental/messages/app", json={**PARENT, "action": "lire"})).json()
    assert corps["giphy"] is True and "k" * 24 not in repr(corps)


async def test_configurer_giphy(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    o = await hass.config_entries.options.async_init(entree.entry_id)
    o = await hass.config_entries.options.async_configure(o["flow_id"], {"next_step_id": "autres"})
    assert "giphy" in o["menu_options"]
    o = await hass.config_entries.options.async_configure(o["flow_id"], {"next_step_id": "giphy"})
    assert o["step_id"] == "giphy"
    # Une clé de mauvaise forme : refusée en clair, rien de changé.
    o = await hass.config_entries.options.async_configure(o["flow_id"], {"actif": True, "cle": "trop court !", "classification": "g"})
    assert o["type"] == "form" and o["errors"] == {"base": "giphy_cle"} and giphy.etat(coord.store)["actif"] is False
    o = await hass.config_entries.options.async_configure(o["flow_id"], {"actif": True, "cle": "a" * 32, "classification": "pg"})
    assert o["type"] == "abort" and o["reason"] == "giphy_ok"
    assert giphy.reglages(coord.store) == {"actif": True, "cle": "a" * 32, "classification": "pg"}
    # Clé laissée vide : l'actuelle est gardée.
    o = await hass.config_entries.options.async_init(entree.entry_id)
    o = await hass.config_entries.options.async_configure(o["flow_id"], {"next_step_id": "autres"})
    o = await hass.config_entries.options.async_configure(o["flow_id"], {"next_step_id": "giphy"})
    o = await hass.config_entries.options.async_configure(o["flow_id"], {"actif": False, "classification": "g"})
    assert giphy.reglages(coord.store) == {"actif": False, "cle": "a" * 32, "classification": "g"}
