"""Essai des messages riches (docs/messages.md), dans un vrai Home Assistant (banc officiel).

Ce qu'on vérifie :
- `pc_parental.envoyer` : réservé aux administrateurs, met le message dans la file du bon appareil,
  applique les réglages par appareil, refuse un média hors des dossiers média, un format ou un
  type incohérent ;
- le relevé délivre le message sans jamais le chemin du fichier, avec une URL de média propre à
  l'appareil et au message ;
- le média n'est servi qu'avec son jeton, à cet appareil, avant l'expiration ;
- l'accusé : authentifié, émet l'événement, journalise les levées d'un bloquant ;
- `pc_parental.liberer_message` : un bloquant « parent » est libéré au relevé suivant ;
- un ancien agent lit toujours titre et texte.

Banc : le même que essai/securite_test.py.
    python -m pytest essai/messages_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import logging
import pathlib
import shutil
import sys
import tempfile
import time

import pytest
from homeassistant.core import Context, HomeAssistant
from homeassistant.exceptions import HomeAssistantError, Unauthorized
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_capture_events

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-messages-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
_MEDIA = _banc / "media"
(_MEDIA / "annonces").mkdir(parents=True)
IMAGE = b"\xff\xd8\xff\xe0" + b"jpeg" * 100
(_MEDIA / "annonces" / "diner.jpg").write_bytes(IMAGE)
(_MEDIA / "annonces" / "note.txt").write_text("pas un média")
(_MEDIA / "annonces" / "son.mp3").write_bytes(b"ID3" + b"\x00" * 50)
(_banc / "secret.jpg").write_bytes(IMAGE)


def _fiche(pc_id: str, nom: str, secret: str, **plus) -> dict:
    return {"id": pc_id, "name": nom, "host": nom, "secret": secret, "enabled": True, "rules": [],
            "bloques": [], "autorises": [], "override": None, "last_seen": 0, "agent": {},
            "person": "", "update_asked": False, "tags": [], "messages": [], "next_message_id": 1, **plus}


TELE = ("t1", "Télé du salon", "a" * 32)
PC = ("p1", "PC de Lou", "b" * 32)


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    hass.config.media_dirs = {"local": str(_MEDIA)}
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


async def _monter(hass: HomeAssistant, hass_storage):
    hass_storage[DOMAIN] = {
        "version": 1, "minor_version": 1, "key": DOMAIN,
        "data": {"pcs": {
            TELE[0]: _fiche(*TELE, msg_position="haut_droite", msg_taille="petite"),
            PC[0]: _fiche(*PC),
        }, "enroll_key": "cle-essai-1234", "enroll_key_v": 2, "enroll_open": False},
    }
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree, hass.data[DOMAIN][entree.entry_id]


async def _rodage(hass, client):
    """Un premier relevé de chaque appareil : sur un banc neuf, il crée les étiquettes de Vision
    (ce qui la recharge) ; dans une maison déjà installée elles existent."""
    for appareil in (TELE, PC):
        await _relever(client, appareil)
    await hass.async_block_till_done()
    return next(iter(hass.data[DOMAIN].values()))


async def _envoyer(hass, **donnees):
    rep = await hass.services.async_call(DOMAIN, "envoyer", donnees, blocking=True, return_response=True)
    # Le banc recharge parfois Vision juste après une première écriture : on attend qu'il soit posé.
    await hass.async_block_till_done()
    return rep


async def _relever(client, appareil):
    r = await client.post("/api/pc_parental/poll", json={"id": appareil[0], "secret": appareil[2], "version": "1.78.0"})
    assert r.status == 200
    return await r.json()


async def test_envoi_releve_et_media(hass, hass_storage, hass_client_no_auth):
    _entree, coord = await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    coord = await _rodage(hass, client)
    rep = await _envoyer(hass, pc=TELE[1], titre="À table", message="Le dîner est prêt",
                         media="media-source://media_source/local/annonces/diner.jpg", duree=20)
    assert [m["pc"] for m in rep["messages"]] == [TELE[0]]
    assert not coord.store.pcs[PC[0]]["messages"]

    releve = await _relever(client, TELE)
    (m,) = releve["notify"]
    assert m["type"] == "image" and m["title"] == "À table" and m["text"] == "Le dîner est prêt"
    # Les réglages de l'appareil s'appliquent quand l'envoi ne précise rien.
    assert m["position"] == "haut_droite" and m["taille"] == "petite" and m["duree"] == 20
    assert m["bloquant"] == "non" and m["urgent"] is False
    assert str(_MEDIA) not in str(releve) and "chemin" not in str(releve) and "jeton" not in m["media"]
    url = m["media"]["url"]
    assert url.startswith("/api/pc_parental/media/") and m["media"]["mime"] == "image/jpeg"
    # Délivré une fois.
    assert "notify" not in await _relever(client, TELE)

    r = await client.get(url)
    assert r.status == 200 and await r.read() == IMAGE
    uid, jeton = url.rsplit("/", 2)[-2:]
    assert (await client.get(f"/api/pc_parental/media/{uid}/{'x' * len(jeton)}")).status == 404
    assert (await client.get(f"/api/pc_parental/media/{'0' * 16}/{jeton}")).status == 404
    # Expiré : plus servi.
    for medias in [coord.store.pcs[TELE[0]]["medias"]]:
        for m2 in medias.values():
            m2["jusqua"] = time.time() - 1
    assert (await client.get(url)).status == 404


async def test_ancien_agent_et_bloquant(hass, hass_storage, hass_client_no_auth):
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    coord = await _rodage(hass, client)
    await _envoyer(hass, pc=PC[1], message="Range ta chambre", bloquant="parent", limite=20, position="bas")
    (m,) = (await _relever(client, PC))["notify"]
    # Un bloquant est en plein écran ; un ancien agent l'affiche en grand (urgent) avec titre et texte.
    assert m["position"] == "plein_ecran" and m["urgent"] is True and m["limite"] == 20
    assert m["title"] == "Vision" and m["text"] == "Range ta chambre"
    assert m["expire"] > time.time()


async def test_refus(hass, hass_storage):
    await _monter(hass, hass_storage)
    for donnees, attendu in (
        ({"pc": TELE[1], "media": str(_banc / "secret.jpg")}, "dossier média"),
        ({"pc": TELE[1], "media": "media-source://media_source/local/../../secret.jpg"}, "dossier média"),
        ({"pc": TELE[1], "media": "/media/annonces/note.txt"}, "Format"),
        ({"pc": TELE[1], "media": "https://exemple.org/x.jpg"}, "Média"),
        ({"pc": TELE[1], "media": "media-source://camera/camera.entree"}, "locaux"),
        ({"pc": TELE[1], "type": "video", "media": "/media/annonces/diner.jpg"}, "video"),
        ({"pc": TELE[1], "message": ""}, "vide"),
        ({"pc": "Inconnu", "message": "x"}, ""),
    ):
        with pytest.raises(HomeAssistantError) as err:
            await _envoyer(hass, **donnees)
        assert attendu in str(err.value), (donnees, str(err.value))


async def test_reserve_aux_administrateurs(hass, hass_storage, hass_read_only_user):
    _entree, coord = await _monter(hass, hass_storage)
    assert not hass_read_only_user.is_admin
    for service, donnees in (("envoyer", {"pc": TELE[1], "message": "x"}), ("liberer_message", {"pc": PC[1]})):
        with pytest.raises(Unauthorized):
            await hass.services.async_call(DOMAIN, service, donnees, blocking=True,
                                           context=Context(user_id=hass_read_only_user.id),
                                           return_response=service == "envoyer")
    assert not coord.store.pcs[TELE[0]]["messages"]


async def test_accuse_evenement_et_journal(hass, hass_storage, hass_client_no_auth, caplog):
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    coord = await _rodage(hass, client)
    evenements = async_capture_events(hass, f"{DOMAIN}_message")
    rep = await _envoyer(hass, pc=PC[1], titre="Devoirs", message="Au travail", bloquant="parent")
    mid = rep["messages"][0]["id"]
    await _relever(client, PC)

    corps = {"id": PC[0], "secret": PC[2], "message": mid, "etat": "affiche"}
    assert (await client.post("/api/pc_parental/message", json={**corps, "secret": "z" * 32})).status == 401
    assert (await client.post("/api/pc_parental/message", json={**corps, "id": TELE[0], "secret": TELE[2]})).status == 404
    assert (await client.post("/api/pc_parental/message", json={**corps, "etat": "invente"})).status == 400
    assert (await client.post("/api/pc_parental/message", json=corps)).status == 200
    # 2.2.1 (faille 2.2-1) : sur un PC, la limite de sécurité doit être vraiment passée depuis l'affichage.
    suivi = next(iter(hass.data[DOMAIN].values())).store.pcs[PC[0]]["messages_suivi"][str(mid)]
    assert suivi["affiche_a"] > 0
    suivi["affiche_a"] -= 31 * 60
    with caplog.at_level(logging.WARNING):
        r = await client.post("/api/pc_parental/message", json={**corps, "etat": "libere_auto"})
        assert r.status == 200
    await hass.async_block_till_done()
    assert [e.data["etat"] for e in evenements] == ["affiche", "libere_auto"]
    assert evenements[0].data["uid"] == rep["messages"][0]["uid"] and evenements[0].data["titre"] == "Devoirs"
    assert "levé tout seul à la limite de sécurité" in caplog.text


async def test_liberer_au_releve_suivant(hass, hass_storage, hass_client_no_auth):
    _entree, coord = await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    coord = await _rodage(hass, client)
    rep = await _envoyer(hass, pc=PC[1], message="Viens", bloquant="parent")
    normal = await _envoyer(hass, pc=PC[1], message="Simple")
    mid = rep["messages"][0]["id"]
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call(DOMAIN, "liberer_message", {"pc": PC[1], "id": normal["messages"][0]["id"]}, blocking=True)
    await hass.services.async_call(DOMAIN, "liberer_message", {"pc": PC[1], "id": mid}, blocking=True)
    releve = await _relever(client, PC)
    assert releve["liberer"] == [mid]
    assert "liberer" not in await _relever(client, PC)
    await hass.services.async_call(DOMAIN, "liberer_message", {"pc": PC[1]}, blocking=True)
    assert (await _relever(client, PC))["liberer"] == ["*"]


async def test_reglages_par_appareil(hass, hass_storage):
    await _monter(hass, hass_storage)
    position = next(s for s in hass.states.async_all("select") if s.entity_id.endswith("position_des_messages") and "salon" in s.entity_id)
    assert position.state == "En haut à droite"
    taille = next(s for s in hass.states.async_all("select") if s.entity_id.endswith("taille_des_messages") and "lou" in s.entity_id)
    assert taille.state == "Moyenne"
    await hass.services.async_call("select", "select_option", {"entity_id": taille.entity_id, "option": "Grande"}, blocking=True)
    coord = next(iter(hass.data[DOMAIN].values()))
    assert coord.store.pcs[PC[0]]["msg_taille"] == "grande"
