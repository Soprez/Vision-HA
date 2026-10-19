"""Essai du correctif de sécurité 1.61.0, dans un vrai Home Assistant (banc de test officiel).

Ce qu'on vérifie :
- la clé d'inscription n'est plus dans aucun état (ni entité, ni attribut) : un compte qui
  n'est pas administrateur ne peut pas la lire, ni par l'API REST, ni par le websocket ;
- elle ne se lit et ne se change que dans les options de l'intégration, que Home Assistant
  réserve aux administrateurs ; un non-administrateur y est refusé ;
- un non-administrateur ne peut rien changer par les entités ni par les services de Vision
  (une automatisation, sans utilisateur, le peut toujours) ;
- la clé d'avant la 1.61.0 est remplacée au premier démarrage, une seule fois ;
- l'inscription ne rend jamais le secret d'un appareil déjà inscrit ; un administrateur peut
  permettre une réinscription, qui donne un secret neuf ;
- la personne choisie dans l'appli n'est qu'une proposition ;
- l'écran de veille ne remet plus de jeton Home Assistant, et celui d'avant est révoqué ;
- caméras limitées à celles choisies ; réglages et catalogue réservés aux appareils « Parents » ;
- appairage par QR (1.62.0) : la demande ne donne rien, seul un administrateur valide, le secret
  ne se lit qu'une fois, la demande expire, la personne n'est fixée que par l'administrateur ;
- QR d'appairage généré par un administrateur (1.65.0) : jeton à usage unique et court, jamais la
  clé, jamais la fiche d'un autre appareil sans l'avoir choisie, image du QR réservée à l'admin.

Banc : Python 3.14 et pytest-homeassistant-custom-component==0.13.367 (Home Assistant 2026.9.4).
    python -m pytest essai/securite_test.py -q -o asyncio_mode=auto
(Home Assistant ne tourne que sous Linux : sous Windows, il faut des modules fcntl et resource
de carton et laisser les sockets locales ouvertes — rien de cela n'est dans le dépôt.)
"""
from __future__ import annotations

import json
import pathlib
import shutil
import sys
import tempfile
import time

import pytest
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
from homeassistant.core import Context, HomeAssistant
from homeassistant.exceptions import HomeAssistantError, Unauthorized
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import label_registry as lr
from homeassistant.setup import async_setup_component
from octroi_banc import CODE_OCTROI, poser_code_octroi
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"

# Home Assistant charge les intégrations depuis un paquet « custom_components » : on en monte
# un, à part, avec une copie du code de ce dépôt.
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402 - peut-être déjà importé par le banc : on y ajoute le nôtre

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
ANCIENNE_CLE = "cle-de-2026-fuitee"


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    # Ce que Home Assistant a toujours : le serveur web, l'API, le websocket, les réglages.
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    # Vision arme des minuteries périodiques (liste noire, notoriété) : le banc les tolère.
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


async def _jeton(hass: HomeAssistant, utilisateur) -> str:
    rafraichi = await hass.auth.async_create_refresh_token(
        utilisateur, client_name="essai", token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
    )
    return hass.auth.async_create_access_token(rafraichi)


async def _monter(hass: HomeAssistant, hass_storage, appareils=None, veille_jeton: str = ""):
    """Vision tel qu'une 1.60.0 l'a laissé : ancienne clé, sans génération de clé."""
    hass_storage[DOMAIN] = {
        "version": 1, "minor_version": 1, "key": DOMAIN,
        "data": {"pcs": appareils or {}, "enroll_key": ANCIENNE_CLE, "enroll_open": True},
    }
    if veille_jeton:
        hass_storage["pc_parental.veille_acces"] = {
            "version": 1, "minor_version": 1, "key": "pc_parental.veille_acces",
            "data": {"user_id": "x", "jeton": veille_jeton},
        }
    for p in ("camera.entree", "camera.chambre_parents"):
        hass.states.async_set(p, "idle", {"friendly_name": p})
    hass.states.async_set("person.enfant", "home", {"friendly_name": "Enfant"})
    hass.states.async_set("person.parent", "home", {"friendly_name": "Parent"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree, hass.data[DOMAIN][entree.entry_id]


def _actuel(hass: HomeAssistant):
    """Le coordinateur du moment : une création d'appareil ou d'étiquette recharge Vision."""
    return next(iter(hass.data[DOMAIN].values()))


def _fiche(pc_id: str, nom: str, secret: str, **plus) -> dict:
    return {"id": pc_id, "name": nom, "host": nom, "secret": secret, "enabled": True, "rules": [],
            "bloques": [], "autorises": [], "override": None, "last_seen": 0, "agent": {},
            "person": "", "update_asked": False, "tags": [], "messages": [], "next_message_id": 1, **plus}


def _texte_de_tous_les_etats(hass: HomeAssistant) -> str:
    return json.dumps([s.as_dict() for s in hass.states.async_all()], default=str)


async def _enfant(hass: HomeAssistant):
    return await hass.auth.async_create_user("Enfant", group_ids=["system-users"])


# ---------------------------------------------------------------------- La clé


async def test_cle_tournee_et_absente_des_etats(hass, hass_storage, hass_client):
    await hass_client()  # monte http et l'API
    _, coord = await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    cle = coord.store.enroll_key
    assert cle and cle != ANCIENNE_CLE, "l'ancienne clé (lisible par tous) doit être remplacée"
    assert hass_storage[DOMAIN]["data"]["enroll_key"] == cle
    assert hass_storage[DOMAIN]["data"]["enroll_key_v"] == 3
    assert hass.states.get("text.vision_cle_d_inscription") is None
    assert er.async_get(hass).async_get_entity_id("text", DOMAIN, f"{DOMAIN}_cle_inscription") is None
    texte = _texte_de_tous_les_etats(hass)
    assert cle not in texte and ANCIENNE_CLE not in texte


async def test_cle_pas_retournee_deux_fois(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN,
                            "data": {"pcs": {}, "enroll_key": "deja-tournee", "enroll_key_v": 2}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN)
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    assert hass.data[DOMAIN][entree.entry_id].store.enroll_key == "deja-tournee"


async def test_non_admin_ne_lit_la_cle_nulle_part(hass, hass_storage, hass_client, hass_ws_client):
    await hass_client()
    _, coord = await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    cle = coord.store.enroll_key
    jeton = await _jeton(hass, await _enfant(hass))

    # API REST : tous les états.
    client = await hass_client(jeton)
    reponse = await client.get("/api/states")
    assert reponse.status == 200
    assert cle not in await reponse.text()

    # Websocket : tous les états.
    ws = await hass_ws_client(hass, jeton)
    await ws.send_json({"id": 1, "type": "get_states"})
    msg = await ws.receive_json()
    assert msg["success"] and cle not in json.dumps(msg["result"])

    # Le catalogue (tout ce que la maison surveille) : réservé aux administrateurs.
    assert (await client.get("/api/pc_parental/catalogue")).status == 403

    # Les options de l'intégration (seul endroit où la clé se lit) : refusées.
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    reponse = await client.post("/api/config/config_entries/options/flow",
                                json={"handler": entree.entry_id})
    assert reponse.status in (401, 403)
    assert cle not in await reponse.text()


async def test_admin_lit_et_change_la_cle_dans_les_options(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, coord = await _monter(hass, hass_storage)
    cle = coord.store.enroll_key
    reponse = await admin.post("/api/config/config_entries/options/flow", json={"handler": entree.entry_id})
    assert reponse.status == 200
    flux = await reponse.json()
    assert flux["type"] == "menu"
    # La clé brute n'est plus qu'au fond : Configurer → Réparer, sauvegarder ou restaurer → Clé d'inscription.
    assert "cle" not in flux["menu_options"]
    await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}", json={"next_step_id": "reparer"})
    reponse = await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}", json={"next_step_id": "cle"})
    flux = await reponse.json()
    assert flux["description_placeholders"]["cle"] == cle
    assert f"-Cle {cle}" in flux["description_placeholders"]["commande"]

    # Rotation demandée par l'administrateur.
    reponse = await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                               json={"action": "nouvelle", "cle": ""})
    flux = await reponse.json()
    assert coord.store.enroll_key != cle
    assert flux["description_placeholders"]["cle"] == coord.store.enroll_key
    assert hass_storage[DOMAIN]["data"]["enroll_key"] == coord.store.enroll_key

    # Une clé choisie, mais trop courte : refusée.
    reponse = await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                               json={"action": "choisir", "cle": "abc"})
    flux = await reponse.json()
    assert flux["errors"] == {"cle": "cle_invalide"}

    reponse = await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                               json={"action": "garder", "cle": ""})
    assert (await reponse.json())["type"] == "create_entry"


async def test_tableau_de_bord_sans_cle(hass, hass_storage, hass_client):
    await hass_client()
    _, coord = await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    from custom_components.pc_parental import dashboard

    section = json.dumps(dashboard._section_installation(hass, coord), ensure_ascii=False)
    assert coord.store.enroll_key not in section
    assert "-Cle" not in section and "cle_inscription" not in section


# ------------------------------------------------- Entités et services : administrateurs


async def test_non_admin_ne_change_rien(hass, hass_storage, hass_client):
    await hass_client()
    _, coord = await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    enfant = await _enfant(hass)
    ctx_enfant = Context(user_id=enfant.id)
    registre = er.async_get(hass)
    inscriptions = registre.async_get_entity_id("switch", DOMAIN, f"{DOMAIN}_inscriptions")
    cameras = registre.async_get_entity_id("text", DOMAIN, f"{DOMAIN}_veille_cameras")
    assert inscriptions and cameras

    with pytest.raises(Unauthorized):
        await hass.services.async_call("switch", "turn_off", {"entity_id": inscriptions},
                                       blocking=True, context=ctx_enfant)
    assert coord.store.enroll_open is True
    with pytest.raises(Unauthorized):
        await hass.services.async_call("text", "set_value", {"entity_id": cameras, "value": "camera.entree"},
                                       blocking=True, context=ctx_enfant)
    assert coord.store.veille_cameras == []
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "ouvrir_categorie", {"personne": "person.enfant"},
                                       blocking=True, context=ctx_enfant)
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "create_pc", {"nom": "Intrus"}, blocking=True, context=ctx_enfant)
    assert coord.store.by_name("Intrus") is None

    # Un administrateur, et une automatisation (sans utilisateur), le peuvent.
    admin = await hass.auth.async_create_user("Parent", group_ids=["system-admin"])
    await hass.services.async_call("switch", "turn_off", {"entity_id": inscriptions},
                                   blocking=True, context=Context(user_id=admin.id))
    assert coord.store.enroll_open is False
    await hass.services.async_call("switch", "turn_on", {"entity_id": inscriptions}, blocking=True)
    assert coord.store.enroll_open is True
    await hass.services.async_call("text", "set_value", {"entity_id": cameras, "value": "camera.entree"},
                                   blocking=True, context=Context(user_id=admin.id))
    assert coord.store.veille_cameras == ["camera.entree"]


async def test_toutes_les_ecritures_sont_gardees(hass, hass_storage, hass_client):
    """Aucune entité de Vision n'échappe à la garde : chaque méthode d'écriture est enveloppée."""
    await hass_client()
    await _monter(hass, hass_storage, {"a1": _fiche("a1", "PC-ENFANT", "s" * 32)})
    poser_code_octroi(hass)
    from custom_components.pc_parental.entity import ECRITURES

    composants = [hass.data[d] for d in ("switch", "select", "number", "text", "button", "time", "update", "notify") if d in hass.data]
    vues = 0
    for composant in composants:
        for entite in composant.entities:
            if entite.platform is None or entite.platform.platform_name != DOMAIN:
                continue
            for classe in type(entite).__mro__:
                if not classe.__module__.startswith("custom_components"):
                    continue
                for nom in ECRITURES:
                    if nom in vars(classe):
                        assert getattr(vars(classe)[nom], "_vision_garde", False), f"{classe.__name__}.{nom} n'est pas gardée"
                        vues += 1
    assert vues > 50


# ----------------------------------------------------------------------- Inscription


async def test_inscription_ne_rend_jamais_un_secret_existant(hass, hass_storage, hass_client):
    client = await hass_client()
    vieux = "a" * 32
    _, coord = await _monter(hass, hass_storage, {"a1": _fiche("a1", "PC-PARENT", vieux)})
    poser_code_octroi(hass)
    cle = coord.store.enroll_key

    # Avec la clé, sous le nom d'un appareil connu : refusé, et rien n'a changé.
    reponse = await client.post("/api/pc_parental/enroll", json={"key": cle, "host": "PC-PARENT", "uid": "intrus"})
    assert reponse.status == 409
    texte = await reponse.text()
    assert vieux not in texte and "secret" not in texte
    assert coord.store.pcs["a1"]["secret"] == vieux and not coord.store.pcs["a1"].get("uid")

    # L'ancienne clé ne vaut plus rien.
    reponse = await client.post("/api/pc_parental/enroll", json={"key": ANCIENNE_CLE, "host": "PC-NEUF"})
    assert reponse.status == 403

    # Un administrateur permet la réinscription : un secret neuf, l'ancien ne vaut plus.
    admin = await hass.auth.async_create_user("Parent", group_ids=["system-admin"])
    bouton = er.async_get(hass).async_get_entity_id("button", DOMAIN, f"{DOMAIN}_reinscription_a1")
    await hass.services.async_call("button", "press", {"entity_id": bouton}, blocking=True,
                                   context=Context(user_id=admin.id))
    reponse = await client.post("/api/pc_parental/enroll", json={"key": cle, "host": "PC-PARENT"})
    assert reponse.status == 200
    neuf = (await reponse.json())["secret"]
    assert neuf != vieux and coord.store.by_secret("a1", vieux) is None and coord.store.by_secret("a1", neuf)
    # Une seule fois.
    reponse = await client.post("/api/pc_parental/enroll", json={"key": cle, "host": "PC-PARENT"})
    assert reponse.status == 409

    # Le bouton est, lui aussi, refusé à un enfant.
    enfant = await _enfant(hass)
    with pytest.raises(Unauthorized):
        await hass.services.async_call("button", "press", {"entity_id": bouton}, blocking=True,
                                       context=Context(user_id=enfant.id))


async def test_la_personne_ne_vient_pas_de_l_appareil(hass, hass_storage, hass_client):
    client = await hass_client()
    _, coord = await _monter(hass, hass_storage, {"a1": _fiche("a1", "TEL-ENFANT", "b" * 32, person="person.enfant")})
    poser_code_octroi(hass)
    reponse = await client.post("/api/pc_parental/poll", json={"id": "a1", "secret": "b" * 32, "person": "person.parent"})
    assert reponse.status == 200
    assert coord.store.pcs["a1"]["person"] == "person.enfant"
    assert coord.store.pcs["a1"]["personne_proposee"] == "person.parent"

    # Nouvel appareil : aucune personne tant qu'un parent ne l'a pas fixée.
    reponse = await client.post("/api/pc_parental/enroll",
                                json={"key": coord.store.enroll_key, "host": "PC-X", "user": "Parent"})
    pc_id = (await reponse.json())["id"]
    await hass.async_block_till_done()
    assert _actuel(hass).store.pcs[pc_id]["person"] == ""
    assert _actuel(hass).store.pcs[pc_id]["personne_proposee"] == "person.parent"


# ----------------------------------------------------------------------- Écran de veille


async def test_veille_sans_jeton_et_ancien_jeton_revoque(hass, hass_storage, hass_client):
    client = await hass_client()
    affichage = await hass.auth.async_create_user("Vision affichage", group_ids=["system-read-only"])
    ancien = await _jeton(hass, affichage)
    assert hass.auth.async_validate_access_token(ancien) is not None
    await _monter(hass, hass_storage, {"a1": _fiche("a1", "TEL-ENFANT", "c" * 32)}, veille_jeton=ancien)
    poser_code_octroi(hass)
    assert hass.auth.async_validate_access_token(ancien) is None, "le jeton remis aux appareils doit être révoqué"
    assert hass_storage["pc_parental.veille_acces"]["data"]["jeton"] == ""

    reponse = await client.post("/api/pc_parental/veille/acces", json={"id": "a1", "secret": "c" * 32})
    assert reponse.status == 200
    assert (await reponse.json())["jeton"] == ""
    assert not any(t.client_name.startswith("Vision") for u in await hass.auth.async_get_users()
                   for t in u.refresh_tokens.values() if t.client_name)


async def test_veille_cameras_et_reglages(hass, hass_storage, hass_client):
    client = await hass_client()
    _, coord = await _monter(hass, hass_storage, {
        "a1": _fiche("a1", "TEL-ENFANT", "d" * 32), "p1": _fiche("p1", "TEL-PARENT", "e" * 32)})
    enfant, parent = {"id": "a1", "secret": "d" * 32}, {"id": "p1", "secret": "e" * 32}

    # Une caméra que personne n'a choisie : refusée, même si elle existe.
    for chemin in ("/api/pc_parental/veille/image", "/api/pc_parental/veille/flux"):
        reponse = await client.post(chemin, json={**enfant, "entite": "camera.chambre_parents"})
        assert reponse.status == 404, chemin
    coord.store.veille_cameras = ["camera.entree"]
    reponse = await client.post("/api/pc_parental/veille", json=enfant)
    assert [c["id"] for c in (await reponse.json())["cameras"]] == ["camera.entree"]
    reponse = await client.post("/api/pc_parental/veille/image", json={**enfant, "entite": "camera.chambre_parents"})
    assert reponse.status == 404

    # Réglages et catalogue des entités : pas pour l'appareil d'un enfant.
    reponse = await client.post("/api/pc_parental/veille/reglages", json={**enfant, "theme": "Sombre"})
    assert reponse.status == 403
    reponse = await client.post("/api/pc_parental/veille/entites", json=enfant)
    assert reponse.status == 403

    # L'appareil d'un parent (1.69.0 : rôle « Parent (admin) » posé par un administrateur) : oui.
    role = er.async_get(hass).async_get_entity_id("select", DOMAIN, f"{DOMAIN}_role_p1")
    await hass.services.async_call("select", "select_option", {"entity_id": role, "option": "Décideur"}, blocking=True)
    await hass.async_block_till_done()
    reponse = await client.post("/api/pc_parental/veille/reglages", json={**parent, "theme": "Sombre"})
    assert reponse.status == 200 and _actuel(hass).store.veille_theme == "Sombre"
    reponse = await client.post("/api/pc_parental/veille/entites", json=parent)
    assert reponse.status == 200


# ----------------------------------------------------------- Appairage par QR (1.62.0)


async def _demander(client, **corps):
    reponse = await client.post("/api/pc_parental/appairage/demande",
                                json={"host": "TV-SALON", "nom": "Télé du salon", "platform": "android", **corps})
    return reponse.status, await reponse.json()


async def _attendre(client, d):
    reponse = await client.post("/api/pc_parental/appairage/attente",
                                json={"demande": d["demande"], "attente": d["attente"]})
    return reponse.status, await reponse.json()


async def test_appairage_valide_par_un_admin(hass, hass_storage, hass_client):
    client = await hass_client()  # administrateur ; les vues de l'appareil ignorent l'authentification
    _, coord = await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    cle = coord.store.enroll_key
    coord.store.enroll_open = False  # l'appairage ne dépend pas des inscriptions par clé

    statut, d = await _demander(client)
    assert statut == 200 and len(d["code"]) == 6
    assert d["qr"] == f"homeassistant://navigate/vision-appairage?code={d['code']}"
    assert cle not in json.dumps(d) and "secret" not in d
    assert (await _attendre(client, d))[1]["etat"] == "en_attente"

    # Un mauvais jeton d'attente ne dit rien.
    reponse = await client.post("/api/pc_parental/appairage/attente", json={"demande": d["demande"], "attente": "x" * 32})
    assert reponse.status == 404

    # L'administrateur voit la demande (sans le jeton d'attente) et valide en choisissant la personne.
    liste = await (await client.get("/api/pc_parental/appairage")).json()
    assert [x["code"] for x in liste["demandes"]] == [d["code"]]
    assert d["attente"] not in json.dumps(liste)
    reponse = await client.post("/api/pc_parental/appairage",
                                json={"code": d["code"].lower(), "action": "valider", "personne": "person.enfant"})
    # Faille du 16/10 : valider exige le code d'octroi, à chaque fois.
    assert reponse.status == 403 and (await reponse.json())["error"] == "code_octroi"
    reponse = await client.post("/api/pc_parental/appairage", json={
        "code_octroi": CODE_OCTROI, "code": d["code"].lower(), "action": "valider", "personne": "person.enfant"})
    assert reponse.status == 200 and (await reponse.json())["fait"]["created"] is True

    statut, r = await _attendre(client, d)
    assert r["etat"] == "validee" and r["poll"] and r["id"] and len(r["secret"]) == 32
    await hass.async_block_till_done()
    store = _actuel(hass).store
    assert store.by_secret(r["id"], r["secret"]) is not None
    assert store.pcs[r["id"]]["person"] == "person.enfant" and store.pcs[r["id"]]["platform"] == "android"
    # Usage unique : la réponse ne se relit pas.
    assert (await _attendre(client, d))[0] == 404
    # Le secret reçu ouvre le relevé.
    reponse = await client.post("/api/pc_parental/poll", json={"id": r["id"], "secret": r["secret"]})
    assert reponse.status == 200


async def test_appairage_sans_personne_et_refus(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    _, d = await _demander(client, user="Parent")
    await hass.services.async_call(DOMAIN, "valider_appairage", {"code": d["code"]}, blocking=True)
    _, r = await _attendre(client, d)
    await hass.async_block_till_done()
    pc = _actuel(hass).store.pcs[r["id"]]
    assert pc["person"] == "" and pc["personne_proposee"] == "person.parent"

    _, d2 = await _demander(client, host="PC-X")
    await hass.services.async_call(DOMAIN, "refuser_appairage", {"code": d2["code"]}, blocking=True)
    assert (await _attendre(client, d2))[1] == {"ok": True, "etat": "refusee"}
    assert (await _attendre(client, d2))[0] == 404
    assert _actuel(hass).store.by_host("PC-X") is None


async def test_appairage_refuse_aux_non_admins(hass, hass_storage, hass_client):
    client = await hass_client()
    _, coord = await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    _, d = await _demander(client)
    enfant = await _enfant(hass)
    client_enfant = await hass_client(await _jeton(hass, enfant))
    assert (await client_enfant.get("/api/pc_parental/appairage")).status == 403
    reponse = await client_enfant.post("/api/pc_parental/appairage", json={"code_octroi": CODE_OCTROI, "code": d["code"], "action": "valider"})
    assert reponse.status == 403
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "valider_appairage", {"code": d["code"]}, blocking=True,
                                       context=Context(user_id=enfant.id))
    assert (await _attendre(client, d))[1]["etat"] == "en_attente"
    assert coord.store.by_host("TV-SALON") is None


async def test_appairage_expire(hass, hass_storage, hass_client, monkeypatch):
    client = await hass_client()
    await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    _, d = await _demander(client)
    from custom_components.pc_parental import appairage

    depart = time.time()
    monkeypatch.setattr(appairage.time, "time", lambda: depart + appairage.DEMANDE_MINUTES * 60 + 1)
    assert (await _attendre(client, d))[1]["etat"] == "expiree"
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call(DOMAIN, "valider_appairage", {"code": d["code"]}, blocking=True)


async def test_appairage_appareil_deja_inscrit(hass, hass_storage, hass_client):
    client = await hass_client()
    vieux = "f" * 32
    _, coord = await _monter(hass, hass_storage, {"a1": _fiche("a1", "TV-SALON", vieux, uid="u-tv")})
    poser_code_octroi(hass)
    _, d = await _demander(client, uid="u-tv")
    liste = await (await client.get("/api/pc_parental/appairage")).json()
    assert liste["demandes"][0]["existant"] == "TV-SALON"
    # Tant qu'un administrateur n'a rien validé, la fiche existante ne bouge pas.
    assert coord.store.pcs["a1"]["secret"] == vieux
    await client.post("/api/pc_parental/appairage", json={"code_octroi": CODE_OCTROI, "code": d["code"], "action": "valider"})
    _, r = await _attendre(client, d)
    assert r["id"] == "a1" and r["created"] is False and r["secret"] != vieux
    assert coord.store.by_secret("a1", vieux) is None and len(coord.store.pcs) == 1


async def test_appairage_limite_les_demandes(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    assert (await _demander(client, host="A"))[0] == 200
    assert (await _demander(client, host="B"))[0] == 200
    assert (await _demander(client, host="C"))[0] == 429


async def test_appairage_par_les_options(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, _ = await _monter(hass, hass_storage)
    _, d = await _demander(admin)
    flux = await (await admin.post("/api/config/config_entries/options/flow", json={"handler": entree.entry_id})).json()
    flux = await (await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                                   json={"next_step_id": "appairage"})).json()
    assert d["code"] in flux["description_placeholders"]["demandes"]
    flux = await (await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                                   json={"code": "ZZZZZZ", "decision": "valider"})).json()
    assert flux["errors"] == {"code": "code_inconnu"}
    flux = await (await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                                   json={"code": d["code"], "personne": "person.parent", "decision": "valider"})).json()
    assert flux["type"] == "create_entry"
    _, r = await _attendre(admin, d)
    await hass.async_block_till_done()
    assert r["etat"] == "validee" and _actuel(hass).store.pcs[r["id"]]["person"] == "person.parent"


async def test_appairage_panneau_reserve_aux_admins(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    from homeassistant.components.frontend import DATA_PANELS

    panneau = hass.data[DATA_PANELS]["vision-appairage"]
    assert panneau.require_admin is True and panneau.show_in_sidebar is False
    module = panneau.config["_panel_custom"]["module_url"].split("?")[0]
    reponse = await client.get(module)
    assert reponse.status == 200 and "vision-appairage-panel" in await reponse.text()



# ------------------------------------------ QR d'appairage généré par Home Assistant (1.65.0)


def _lire_qr(texte: str) -> dict:
    from urllib.parse import parse_qs, urlsplit

    morceaux = urlsplit(texte)
    assert (morceaux.scheme, morceaux.netloc) == ("vision", "appairage"), texte
    return {k: v[0] for k, v in parse_qs(morceaux.query).items()}


async def _scanner(client, jeton, **corps):
    reponse = await client.post("/api/pc_parental/appairage/jeton",
                                json={"jeton": jeton, "host": "TEL-NEUF", "nom": "Téléphone neuf",
                                      "uid": "u-neuf", "platform": "android", **corps})
    return reponse.status, await reponse.json()


async def test_qr_genere_par_un_admin(hass, hass_storage, hass_client):
    admin = await hass_client()
    _, coord = await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    await hass.config.async_update(external_url="https://ha.exemple.org", internal_url="http://10.1.1.10:8123")
    reponse = await admin.post("/api/pc_parental/appairage/invitation", json={"code_octroi": CODE_OCTROI, "personne": "person.enfant"})
    assert reponse.status == 200
    inv = await reponse.json()
    qr = _lire_qr(inv["qr"])
    assert qr["v"] == "1" and qr["adresse"] == "https://ha.exemple.org" and qr["locale"] == "http://10.1.1.10:8123"
    assert len(qr["jeton"]) >= 32 and inv["expire_dans"] <= 300
    assert coord.store.enroll_key not in json.dumps(inv)
    assert inv["svg"].lstrip().startswith("<svg")

    statut, r = await _scanner(admin, qr["jeton"])
    assert statut == 200 and r["created"] is True and len(r["secret"]) == 32 and r["poll"]
    await hass.async_block_till_done()
    pc = _actuel(hass).store.pcs[r["id"]]
    assert pc["person"] == "person.enfant" and pc["uid"] == "u-neuf" and pc["platform"] == "android"
    assert (await admin.post("/api/pc_parental/poll", json={"id": r["id"], "secret": r["secret"]})).status == 200
    # Usage unique.
    assert (await _scanner(admin, qr["jeton"], host="AUTRE", uid="u-autre"))[0] == 404


async def test_qr_sans_personne_expire_et_limite(hass, hass_storage, hass_client, monkeypatch):
    admin = await hass_client()
    await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    invs = [await (await admin.post("/api/pc_parental/appairage/invitation", json={"code_octroi": CODE_OCTROI, })).json() for _ in range(5)]
    assert (await admin.post("/api/pc_parental/appairage/invitation", json={"code_octroi": CODE_OCTROI, })).status == 400  # 5 au plus
    _, r = await _scanner(admin, _lire_qr(invs[0]["qr"])["jeton"], user="Parent")
    await hass.async_block_till_done()
    pc = _actuel(hass).store.pcs[r["id"]]
    assert pc["person"] == "" and pc["personne_proposee"] == "person.parent"

    from custom_components.pc_parental import appairage

    depart = time.time()
    monkeypatch.setattr(appairage.time, "time", lambda: depart + appairage.APPAIRAGE_MINUTES * 60 + 1)
    assert (await _scanner(admin, _lire_qr(invs[1]["qr"])["jeton"], host="TARD", uid="u-tard"))[0] == 404
    assert (await _scanner(admin, "n-importe-quoi", host="X"))[0] == 404


async def test_qr_reserve_aux_admins(hass, hass_storage, hass_client, hass_client_no_auth):
    admin = await hass_client()
    await _monter(hass, hass_storage)
    poser_code_octroi(hass)
    enfant = await hass_client(await _jeton(hass, await _enfant(hass)))
    assert (await enfant.post("/api/pc_parental/appairage/invitation", json={"code_octroi": CODE_OCTROI, })).status == 403
    inv = await (await admin.post("/api/pc_parental/appairage/invitation", json={"code_octroi": CODE_OCTROI, })).json()
    from custom_components.pc_parental import appairage

    chemin = appairage.chemin_qr(inv["id"])
    assert (await enfant.get(chemin)).status == 403
    assert (await (await hass_client_no_auth()).get(chemin)).status == 401
    assert (await admin.get(chemin)).status == 200


async def test_qr_ne_prend_pas_la_fiche_d_un_autre(hass, hass_storage, hass_client):
    admin = await hass_client()
    vieux = "9" * 32
    _, coord = await _monter(hass, hass_storage, {"a1": _fiche("a1", "PIXEL-PARENT", vieux, uid="u-parent")})
    poser_code_octroi(hass)
    inv = await (await admin.post("/api/pc_parental/appairage/invitation", json={"code_octroi": CODE_OCTROI, })).json()
    jeton = _lire_qr(inv["qr"])["jeton"]
    # Un appareil déjà inscrit (même uid) : refusé, et sa fiche ne bouge pas ; le QR reste bon.
    assert (await _scanner(admin, jeton, host="PIXEL-PARENT", uid="u-parent"))[0] == 409
    assert coord.store.pcs["a1"]["secret"] == vieux
    assert (await _scanner(admin, jeton))[0] == 200
    await hass.async_block_till_done()  # un appareil créé : Vision se recharge, on l'attend

    # L'administrateur choisit de reprendre cet appareil : secret neuf, l'ancien ne vaut plus.
    inv = await (await admin.post("/api/pc_parental/appairage/invitation", json={"code_octroi": CODE_OCTROI, "appareil": "a1"})).json()
    statut, r = await _scanner(admin, _lire_qr(inv["qr"])["jeton"], host="PIXEL-PARENT", uid="u-parent")
    assert statut == 200 and r["id"] == "a1" and r["created"] is False and r["secret"] != vieux
    assert _actuel(hass).store.by_secret("a1", vieux) is None
    assert (await admin.post("/api/pc_parental/appairage/invitation", json={"code_octroi": CODE_OCTROI, "appareil": "inconnu"})).status == 400


async def test_qr_par_les_options(hass, hass_storage, hass_client, hass_client_no_auth):
    admin = await hass_client()
    entree, _ = await _monter(hass, hass_storage)
    flux = await (await admin.post("/api/config/config_entries/options/flow", json={"handler": entree.entry_id})).json()
    flux = await (await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                                   json={"next_step_id": "reparer"})).json()
    assert "qr" in flux["menu_options"]
    flux = await (await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                                   json={"next_step_id": "qr"})).json()
    flux = await (await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}",
                                   json={"personne": "person.parent"})).json()
    assert flux["step_id"] == "qr_affiche"
    image = flux["description_placeholders"]["image"]
    assert "authSig=" in image
    # L'image se charge par son chemin signé (le navigateur n'envoie pas d'en-tête) ; sans signature, non.
    anonyme = await hass_client_no_auth()
    reponse = await anonyme.get(image)
    assert reponse.status == 200 and "svg" in reponse.headers["Content-Type"]
    assert (await anonyme.get(image.split("?")[0])).status == 401
    flux = await (await admin.post(f"/api/config/config_entries/options/flow/{flux['flow_id']}", json={})).json()
    assert flux["type"] == "create_entry"
