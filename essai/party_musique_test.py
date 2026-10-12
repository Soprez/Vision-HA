"""Point 14 bis (1.99.0) : choisir la musique (Music Assistant) et la page invité (QR code).

Un faux Music Assistant (services search / get_library / play_media / get_queue et client de file) remplace le vrai.
Vérifie : lecteurs MA d'abord et doublon Cast caché ; recherche et bibliothèque pour décideur et télé, refus pour un
enfant ; « Jouer maintenant » / « Ajouter à la file » ; la télé lance la Party chez elle mais ne vise jamais une
autre enceinte ; page invité : jeton de la Party, révoqué à l'arrêt, références au lieu d'adresses, limites,
en-têtes de sécurité ; ajout direct ou validation ; retrait d'un titre.

    python -m pytest essai/party_musique_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import re
from types import SimpleNamespace

from homeassistant.core import ServiceCall, SupportsResponse
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

TELE = {"id": "t1", "secret": "c" * 32}
TELE2 = {"id": "t2", "secret": "d" * 32}
MA_SALON = "media_player.salon_2"
CAST_SALON = "media_player.salon"
MA_CHAMBRE = "media_player.chambre_ma"

PISTES = [{"media_type": "track", "uri": "library://track/145", "name": "Dancing Queen", "favorite": False,
           "image": "https://exemple.invalid/a.jpg", "artists": [{"name": "ABBA"}], "album": {"name": "Arrival"}},
          {"media_type": "track", "uri": "file:///etc/passwd", "name": "", "artists": []}]


class FauxFiles:
    def __init__(self):
        self.supprimes = []

        self.items = [{"queue_item_id": "q0", "media_item": {"name": "En cours", "uri": "library://track/1", "artists": []}},
                      {"queue_item_id": "q1", "media_item": {"name": "Dancing Queen", "uri": "library://track/145", "artists": [{"name": "ABBA"}]}}]
        self.courant = 0
        self.dstm = {}
        self.fondu = False
        self.envoyes = []

    def get(self, qid):
        return {"current_index": self.courant, "items": len(self.items), "flow_mode": True,
                "crossfade_enabled": self.fondu, "index_in_buffer": self.courant + 1,
                "autoplay_enabled": self.dstm.get(qid, False),
                "current_item": self.items[self.courant] if 0 <= self.courant < len(self.items) else None}

    async def get_queue_items(self, qid, limit=500, offset=0):
        return self.items[offset:offset + limit]

    async def move_item(self, qid, item, pos_shift=1):
        self.supprimes.append(("deplacer", qid, item, pos_shift))

    async def dont_stop_the_music(self, qid, actif):
        self.dstm[qid] = actif

    async def delete_item(self, qid, item):
        self.supprimes.append((qid, item))
        self.items = [x for x in self.items if x.get("queue_item_id") != item]

    async def play_index(self, qid, index):
        self.supprimes.append(("jouer", qid, index))

    async def move_next(self, qid, item):
        self.supprimes.append(("ensuite", qid, item))

    async def transfer(self, src, dst, auto_play=None):
        self.supprimes.append(("transfert", src, dst))


async def _monter(hass, hass_storage, monkeypatch):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant", type="telephone"),
            "t1": _fiche("t1", "Téléviseur du salon", TELE["secret"], platform="android", role="enfant", type="tv"),
            "t2": _fiche("t2", "Télé des parents", TELE2["secret"], platform="android", role="enfant", type="tv"),
            "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent"),
        }}}
    await hass.config.async_update(external_url="https://ha.exemple.org")
    # L'intégration Music Assistant elle-même n'est pas installée dans le banc : son entrée chargée est simulée.
    from custom_components.pc_parental import musique, party_invites
    files = FauxFiles()

    async def send_command(cmd, **kw):
        files.envoyes.append((cmd, kw))
        if cmd == "player_queues/crossfade":
            files.fondu = kw["crossfade_enabled"]
        if cmd == "player_queues/autoplay":
            files.dstm[kw["queue_id"]] = kw["autoplay_enabled"]
        if cmd == "config/player_queues/get_value":
            return 8
    ma = SimpleNamespace(entry_id="ma1", runtime_data=SimpleNamespace(mass=SimpleNamespace(player_queues=files, send_command=send_command)))
    monkeypatch.setattr(musique, "entree", lambda _hass: ma)
    party_invites._compteurs.clear()
    reg = er.async_get(hass)
    for uid, obj in (("salon", "salon_2"), ("chambre", "chambre_ma")):
        reg.async_get_or_create("media_player", "music_assistant", uid, suggested_object_id=obj)
    hass.states.async_set(CAST_SALON, "off", {"friendly_name": "Salon"})
    hass.states.async_set(MA_SALON, "idle", {"friendly_name": "Salon", "active_queue": "qid-salon"})
    hass.states.async_set(MA_CHAMBRE, "idle", {"friendly_name": "Chambre", "active_queue": "qid-chambre"})
    appels = []

    async def search(call: ServiceCall):
        appels.append(("search", dict(call.data)))
        return {"tracks": PISTES, "playlists": [{"media_type": "playlist", "uri": "library://playlist/10", "name": "La Famille"}],
                "albums": [], "artists": [], "radio": []}

    async def library(call: ServiceCall):
        appels.append(("get_library", dict(call.data)))
        return {"items": [{"media_type": call.data["media_type"], "uri": "library://playlist/17", "name": "Disney classique"}]}

    async def play_media(call: ServiceCall):
        appels.append(("play_media", dict(call.data)))

    async def get_queue(call: ServiceCall):
        return {}
    hass.services.async_register("music_assistant", "search", search, supports_response=SupportsResponse.ONLY)
    hass.services.async_register("music_assistant", "get_library", library, supports_response=SupportsResponse.ONLY)
    hass.services.async_register("music_assistant", "play_media", play_media)
    hass.services.async_register("music_assistant", "get_queue", get_queue, supports_response=SupportsResponse.ONLY)
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id], appels, files


async def _post(client, chemin, qui, **corps):
    r = await client.post("/api/pc_parental/party/" + chemin, json={**qui, **corps})
    return r.status, await r.json()


def _play(appels):
    return [c for n, c in appels if n == "play_media"]


async def test_lecteurs_ma_d_abord_sans_doublon(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental import party

    await _monter(hass, hass_storage, monkeypatch)
    l = party.lecteurs(hass)
    assert [x["entity_id"] for x in l][:2] == [MA_CHAMBRE, MA_SALON]
    assert CAST_SALON not in [x["entity_id"] for x in l]  # le double Google Cast de « Salon » est caché
    assert all(x["ma"] for x in l)


async def test_choisir_et_jouer(hass, hass_storage, hass_client, monkeypatch):
    _coord, appels, _f = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client()
    s, r = await _post(client, "musique", ENFANT, quoi="playlist")
    assert s == 403
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="queen")
    assert s == 200 and r["resultats"]["track"][0]["nom"] == "Dancing Queen"
    assert len(r["resultats"]["track"]) == 1  # l'élément sans nom (adresse de fichier) est écarté
    assert r["resultats"]["playlist"][0]["uri"] == "library://playlist/10"
    s, r = await _post(client, "musique", PARENT, quoi="playlist")
    assert s == 200 and r["items"][0]["nom"] == "Disney classique"
    # 2.2.0 : rien ne joue → le choix démarre ; quelque chose joue → il s'AJOUTE à la suite, sans couper.
    s, r = await _post(client, "commande", PARENT, action="jouer", lecteur=MA_SALON, uri="library://playlist/17", type="playlist")
    assert s == 200 and _play(appels)[-1]["enqueue"] == "play"
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon"})
    s, r = await _post(client, "commande", PARENT, action="jouer", lecteur=MA_SALON, uri="library://track/145", type="track")
    assert s == 200 and _play(appels)[-1]["enqueue"] == "add" and r["retour"] == "Ajouté — position 1."
    s, r = await _post(client, "commande", PARENT, action="jouer", lecteur=MA_SALON, uri="library://track/145", type="track", comment="ensuite")
    assert s == 200 and _play(appels)[-1]["enqueue"] == "next" and r["retour"] == "Ajouté — joue juste après."
    s, r = await _post(client, "commande", PARENT, action="jouer", lecteur=MA_SALON, uri="library://playlist/17", type="playlist", comment="remplacer")
    assert s == 200 and _play(appels)[-1]["enqueue"] == "replace"
    assert not [c for c in _play(appels) if c["enqueue"] == "replace" and c["media_type"] == "track"]  # un titre ne coupe jamais
    s, r = await _post(client, "commande", PARENT, action="jouer", lecteur=CAST_SALON, uri="library://track/145", type="track")
    assert s == 400 and "Music Assistant" in r["retour"]
    s, r = await _post(client, "commande", PARENT, action="jouer", lecteur=MA_SALON, uri="pas une adresse", type="track")
    assert s == 400


async def test_la_tele_lance_chez_elle_et_ne_vise_pas_ailleurs(hass, hass_storage, hass_client, monkeypatch):
    _coord, appels, _f = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client()
    s, r = await _post(client, "commande", TELE, action="jouer", uri="library://playlist/17", type="playlist")
    assert s == 403  # pas de Party sur cette télé
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    assert s == 200
    s, r = await _post(client, "etat", TELE)
    assert r["ici"] and r["lecteur"] == MA_SALON and r["choisir"] and r["ma"] and r["lecteur_ma"]
    s, r = await _post(client, "commande", TELE, action="jouer", lecteur=MA_CHAMBRE, uri="library://playlist/17", type="playlist")
    assert s == 200 and _play(appels)[-1]["entity_id"] in (MA_SALON, [MA_SALON])
    s, r = await _post(client, "commande", TELE, action="arreter")
    assert s == 403
    s, r = await _post(client, "commande", TELE2, action="jouer", lecteur=MA_CHAMBRE, uri="library://playlist/17", type="playlist")
    # 2.1.0 (Party ouverte à tous) : l'autre télé participe, mais sur l'enceinte de la Party, jamais ailleurs.
    assert s == 200 and _play(appels)[-1]["entity_id"] in (MA_SALON, [MA_SALON])
    s, r = await _post(client, "commande", TELE2, action="lancer_ici")
    assert s == 200  # elle peut aussi reprendre la Party chez elle (comme avant)


async def test_page_invite_de_bout_en_bout(hass, hass_storage, hass_client_no_auth, monkeypatch):
    _coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client_no_auth()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    s, r = await _post(client, "etat", TELE)
    m = re.fullmatch(r"https://ha\.exemple\.org(/api/pc_parental/party/invite/([A-Za-z0-9_-]{30,}))", r["qr"])
    assert m, r["qr"]
    chemin, jeton = m.group(1), m.group(2)
    s, r = await _post(client, "etat", ENFANT)
    assert s == 200 and r["qr"] == ""  # un enfant participe, mais ne voit pas le QR par l'API
    page = await client.get(chemin)
    texte = await page.text()
    assert page.status == 200 and "Proposer un titre" in texte
    csp = page.headers["Content-Security-Policy"]
    nonce = re.search(r"nonce-([A-Za-z0-9_-]+)", csp).group(1)
    assert "default-src 'none'" in csp and "frame-ancestors 'none'" in csp and f'nonce="{nonce}"' in texte
    assert page.headers["Cache-Control"] == "no-store" and page.headers["Referrer-Policy"] == "no-referrer"
    assert "innerHTML" not in texte and "library://" not in texte and DOMAIN not in texte.replace("pc_parental/party/invite", "")
    # i18n : la page parle la langue du navigateur de l'invité (Accept-Language), sinon celle de Home Assistant.
    de = await (await client.get(chemin, headers={"Accept-Language": "de-CH,de;q=0.9,en;q=0.5"})).text()
    assert 'lang="de"' in de and "Titel vorschlagen" in de and "Proposer un titre" not in de and "__T_" not in de
    assert 'lang="fr"' in texte and "__T_" not in texte and "__TEXTES__" not in texte
    fini = await (await client.get("/api/pc_parental/party/invite/" + "B" * len(jeton), headers={"Accept-Language": "nl"})).text()
    assert "Deze link is niet meer geldig" in fini and "__T_" not in fini
    # Recherche : des références, jamais d'adresse Music Assistant.
    r = await client.post(chemin + "/chercher", json={"texte": "queen"})
    j = await r.json()
    assert r.status == 200 and j["titres"] == [{"ref": j["titres"][0]["ref"], "titre": "Dancing Queen", "artiste": "ABBA", "source": "Bibliothèque", "img": True}]
    assert "://" not in str(j)
    # Une adresse inventée ne passe pas ; une référence valable s'ajoute à la file (ajout direct).
    r = await client.post(chemin + "/proposer", json={"ref": "library://track/1"})
    assert r.status == 400
    r = await client.post(chemin + "/proposer", json={"uri": "library://track/1"})
    assert r.status == 400
    r = await client.post(chemin + "/proposer", json={"ref": j["titres"][0]["ref"]})
    assert r.status == 200 and (await r.json())["message"] == "Merci ! Votre titre est dans la file."
    assert _play(appels)[-1]["enqueue"] == "add" and _play(appels)[-1]["media_id"] == "library://track/145"
    # Sur la télé : « proposé par un invité ».
    s, r = await _post(client, "etat", TELE)
    assert [(x["titre"], x["invite"]) for x in r["file"]] == [("Dancing Queen", True)]
    # Le décideur retire le titre.
    s, r = await _post(client, "commande", PARENT, action="retirer", element=r["file"][0]["id"])
    assert s == 200 and files.supprimes == [("qid-salon", "q1")]
    # Limite : 3 propositions par minute et par adresse.
    for _ in range(2):
        await client.post(chemin + "/proposer", json={"ref": j["titres"][0]["ref"]})
    r = await client.post(chemin + "/proposer", json={"ref": j["titres"][0]["ref"]})
    assert r.status == 429
    # Corps trop gros : refusé.
    r = await client.post(chemin + "/chercher", data=b'{"texte":"' + b"a" * 5000 + b'"}', headers={"Content-Type": "application/json"})
    assert r.status == 400
    # Faux jeton : page « plus valable ».
    r = await client.get("/api/pc_parental/party/invite/" + "A" * len(jeton))
    assert r.status == 404 and "plus valable" in await r.text()
    # Arrêt : le lien est révoqué ; une nouvelle Party a un autre lien.
    await _post(client, "commande", PARENT, action="arreter")
    assert (await client.get(chemin)).status == 404
    assert (await client.post(chemin + "/chercher", json={"texte": "queen"})).status == 404
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    s, r = await _post(client, "etat", TELE)
    assert jeton not in r["qr"] and (await client.get(chemin)).status == 404


async def test_validation_par_un_decideur(hass, hass_storage, hass_client_no_auth, monkeypatch):
    _coord, appels, _f = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client_no_auth()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    s, r = await _post(client, "commande", PARENT, action="invites", mode="validation")
    assert s == 200
    s, r = await _post(client, "etat", PARENT)
    chemin = r["qr"].split("exemple.org", 1)[1]
    j = await (await client.post(chemin + "/chercher", json={"texte": "queen"})).json()
    avant = len(_play(appels))
    r = await client.post(chemin + "/proposer", json={"ref": j["titres"][0]["ref"]})
    assert (await r.json())["message"] == "Merci ! Votre titre attend l'accord des hôtes."
    assert len(_play(appels)) == avant
    s, r = await _post(client, "etat", PARENT)
    p = r["propositions"][0]
    assert p["titre"] == "Dancing Queen" and "uri" not in p
    s, r = await _post(client, "commande", PARENT, action="accepter", proposition=p["id"])
    assert s == 200 and _play(appels)[-1]["enqueue"] == "add"
    s, r = await _post(client, "commande", PARENT, action="accepter", proposition=p["id"])
    assert s == 400 and r["retour"] == "Cette proposition a déjà été traitée."
    s, r = await _post(client, "commande", TELE, action="invites", mode="direct")
    assert s == 403


async def test_garde_fous_de_la_revue(hass, hass_storage, hass_client_no_auth, monkeypatch):
    """Revue de sécurité du 2026-10-10 : références qui s'effacent, plafond des titres d'invités en file, titres
    explicites écartés, limites par appareil (Wi-Fi partagé), expiration et nouveau lien, Content-Length exigé."""
    import time as _t

    from custom_components.pc_parental import party, party_invites

    _coord, appels, _f = await _monter(hass, hass_storage, monkeypatch)
    PISTES.append({"media_type": "track", "uri": "library://track/666", "name": "Explicite", "explicit": True, "artists": []})
    try:
        client = await hass_client_no_auth()
        await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
        s, r = await _post(client, "etat", TELE)
        chemin = r["qr"].split("exemple.org", 1)[1]
        # Titre explicite écarté.
        j = await (await client.post(chemin + "/chercher", json={"texte": "queen", "appareil": "a1"})).json()
        assert [t["titre"] for t in j["titres"]] == ["Dancing Queen"]
        # Références : les plus anciennes s'effacent, la recherche continue de marcher.
        monkeypatch.setattr(party_invites, "REFS_MAX", 2)
        for k in range(3):
            j2 = await (await client.post(chemin + "/chercher", json={"texte": "queen", "appareil": f"b{k}"})).json()
            assert j2["titres"], j2
        assert len(party.etat(hass)["refs"]) == 2
        r = await client.post(chemin + "/proposer", json={"ref": j["titres"][0]["ref"], "appareil": "a1"})
        assert r.status == 400  # référence effacée : il faut chercher à nouveau
        # Deux invités derrière la même adresse : chacun a ses 3 propositions.
        ref = j2["titres"][0]["ref"]
        for app in ("x", "y"):
            for _ in range(3):
                assert (await client.post(chemin + "/proposer", json={"ref": ref, "appareil": app})).status == 200
        assert (await client.post(chemin + "/proposer", json={"ref": ref, "appareil": "x"})).status == 429
        # Plafond : la file contient déjà un titre d'invité (Dancing Queen) → au plus 1 ici, rien de plus n'est ajouté.
        monkeypatch.setattr(party_invites, "INVITES_EN_FILE_MAX", 1)
        avant = len(_play(appels))
        r = await client.post(chemin + "/proposer", json={"ref": ref, "appareil": "z"})
        assert r.status == 429 and (await r.json())["message"].startswith("Beaucoup de titres des invités") and len(_play(appels)) == avant
        # Content-Length absent : refusé.
        async def morceaux():
            yield b'{"texte":"queen"}'
        r = await client.post(chemin + "/chercher", data=morceaux(), headers={"Content-Type": "application/json"})
        assert r.status == 400
        # Nouveau lien : l'ancien ne marche plus ; expiration après 12 h.
        s, r = await _post(client, "commande", PARENT, action="nouveau_lien")
        assert s == 200 and (await client.get(chemin)).status == 404
        s, r = await _post(client, "etat", TELE)
        nouveau = r["qr"].split("exemple.org", 1)[1]
        assert (await client.get(nouveau)).status == 200
        party.etat(hass)["jeton_ts"] = _t.time() - 13 * 3600
        assert (await client.get(nouveau)).status == 404
    finally:
        PISTES.pop()



async def test_page_invite_pochettes_et_titre_en_cours(hass, hass_storage, hass_client_no_auth, monkeypatch, aioclient_mock):
    """2.2.12 (l'auteur : « pas adapté au mobile, je ne vois pas la pochette ») : les pochettes passent par Home Assistant
    (référence de cette Party seulement, type image vérifié, mise en cache) ; le titre en cours en haut de page ;
    les couleurs du thème de Vision ; l'adresse vient de Home Assistant (rien en dur)."""
    from urllib.parse import urlparse

    from custom_components.pc_parental import party, party_invites
    _coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client_no_auth()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    s, r = await _post(client, "etat", TELE)
    assert r["qr"].startswith(hass.config.external_url.rstrip("/") + "/")
    chemin = urlparse(r["qr"]).path
    st = party.etat(hass)
    party_invites._images.clear()
    refs = party_invites.refs_de(st)
    refs["r1"] = {"uri": "library://track/1", "titre": "T", "artiste": "A", "image": "https://img.example/a.jpg"}
    refs["r2"] = {"uri": "library://track/2", "titre": "T", "artiste": "A", "image": "https://img.example/b"}
    aioclient_mock.get("https://img.example/a.jpg", content=b"JPEGDATA", headers={"Content-Type": "image/jpeg"})
    aioclient_mock.get("https://img.example/b", text="<html>", headers={"Content-Type": "text/html"})
    r = await client.get(chemin + "/image/r1")
    assert r.status == 200 and r.headers["Content-Type"] == "image/jpeg" and await r.read() == b"JPEGDATA"
    assert r.headers["X-Content-Type-Options"] == "nosniff"
    r = await client.get(chemin + "/image/r1")
    assert r.status == 200 and aioclient_mock.call_count == 1  # en cache
    assert (await client.get(chemin + "/image/r2")).status == 404  # pas une image
    assert (await client.get(chemin + "/image/inconnue")).status == 404
    assert (await client.get("/api/pc_parental/party/invite/" + "A" * 32 + "/image/r1")).status == 404
    # La page : images du même site seulement, viewport, couleurs du thème (Beige par défaut).
    page = await client.get(chemin)
    texte = await page.text()
    assert "img-src 'self'" in page.headers["Content-Security-Policy"]
    assert 'name="viewport" content="width=device-width' in texte and "--fond:#EFE6D6" in texte and "__" + "NONCE" not in texte
    # Le titre en cours : titre, artiste, empreinte de pochette ; jamais d'adresse.
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "media_title": "Dancing Queen", "media_artist": "ABBA",
                                                "entity_picture": "/api/media_player_proxy/x?token=SECRET"})
    r = await client.get(chemin + "/maintenant")
    j = await r.json()
    assert r.status == 200 and j["titre"] == "Dancing Queen" and j["artiste"] == "ABBA" and j["pochette"]
    assert "SECRET" not in str(j) and "://" not in str(j)
    assert (await client.get("/api/pc_parental/party/invite/" + "A" * 32 + "/maintenant")).status == 404


async def test_page_invite_souvent_joues_et_sans_music_assistant(hass, hass_storage, hass_client_no_auth, monkeypatch):
    """2.2.13 (retour d'essai) : « Souvent joués » avec pochettes dès l'ouverture (références, jamais d'adresse) ; la puce
    « Music Assistant » (fournisseur interne) n'est pas une plateforme ; remise à l'échelle si Chrome est en mode bureau."""
    from types import SimpleNamespace
    from urllib.parse import urlparse

    from homeassistant.core import ServiceCall, SupportsResponse

    from custom_components.pc_parental import musique
    _coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    musique._SOUVENT.update(ts=0.0, items=[])
    vus = []

    async def library(call: ServiceCall):
        vus.append(dict(call.data))
        if call.data.get("media_type") == "track":
            return {"items": [{"media_type": "track", "uri": "library://track/7", "name": "Sweat", "artists": [{"name": "Inner Circle"}],
                               "album": {"name": "A", "image": "https://img.example/sweat.jpg"}},
                              {"media_type": "track", "uri": "library://track/8", "name": "Gros mots", "explicit": True, "artists": []}]}
        return {"items": []}
    hass.services.async_register("music_assistant", "get_library", library, supports_response=SupportsResponse.ONLY, )
    client = await hass_client_no_auth()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    s, r = await _post(client, "etat", TELE)
    chemin = urlparse(r["qr"]).path
    r = await client.get(chemin + "/suggestions")
    j = await r.json()
    assert r.status == 200 and [t["titre"] for t in j["titres"]] == ["Sweat"] and j["titres"][0]["img"] is True
    assert "://" not in str(j) and vus[0].get("order_by") == "play_count_desc"
    # Plateformes : jamais « Music Assistant ».
    musique._client(hass).providers = [SimpleNamespace(type="music", domain="builtin", name="Music Assistant", available=True),
                                       SimpleNamespace(type="music", domain="ytmusic", name="YTM", available=True)]
    assert [p["domaine"] for p in musique.plateformes(hass)] == ["library", "ytmusic"]
    page = await (await client.get(chemin)).text()
    assert "Music Assistant</button>" not in page and "YouTube Music</button>" in page
    assert "style.zoom" in page and "/suggestions" in page
