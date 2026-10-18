"""Vision → Configurer → « Demandes de déverrouillage » (1.68.0), dans un vrai Home Assistant.

Ce qu'on vérifie : la liste des demandes en attente ; approuver par code (6 chiffres affichés,
groupés 3+3), par QR (image servie seulement au chemin signé, puis expirée), refuser ; une demande
déjà décidée ne repasse pas ; sans demande, l'écran le dit.

Banc : le même que essai/deverrouillage_test.py.
"""
from __future__ import annotations

import base64
import pathlib
import re
import secrets
import shutil
import sys
import tempfile

import pytest
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-dev-ecran-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
ENFANT = {"id": "e1", "secret": "a" * 32}


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


def _fiche(pc_id, nom, secret, **plus):
    return {"id": pc_id, "name": nom, "host": nom, "secret": secret, "enabled": True, "rules": [],
            "bloques": [], "autorises": [], "override": None, "last_seen": 0, "agent": {},
            "person": "", "update_asked": False, "tags": [], "messages": [], "next_message_id": 1, **plus}


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2,
        "pcs": {"e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android")}}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree


async def _demander(client, action="app:com.roblox.client", minutes=30):
    defi = base64.urlsafe_b64encode(secrets.token_bytes(16)).rstrip(b"=").decode()
    r = await client.post("/api/pc_parental/deverrouillage/demande",
                          json={**ENFANT, "action": action, "minutes": minutes, "defi": defi, "motif": "devoirs finis"})
    assert r.status == 200, await r.text()
    return (await r.json())["demande"]


async def _ecran(hass, entree):
    r = await hass.config_entries.options.async_init(entree.entry_id)
    assert r["type"] == FlowResultType.MENU and "ajouter" in r["menu_options"]
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "autres"})
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "securite"})
    assert r["menu_options"][0] == "deverrouillage"
    return await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "deverrouillage"})


async def test_approuver_par_code_puis_plus_rien(hass, hass_storage, hass_client_no_auth):
    entree = await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    demande = await _demander(client)
    r = await _ecran(hass, entree)
    assert r["step_id"] == "deverrouillage" and r["description_placeholders"]["nombre"] == "1"
    r = await hass.config_entries.options.async_configure(
        r["flow_id"], {"demande": demande, "decision": "approuver", "minutes": 20, "remise": "code"})
    assert r["step_id"] == "deverrouillage_resultat"
    assert re.search(r"# \d{3} \d{3}", r["description_placeholders"]["resultat"])
    assert "20 min" in r["description_placeholders"]["resultat"]
    r = await hass.config_entries.options.async_configure(r["flow_id"], {})
    assert r["type"] == FlowResultType.CREATE_ENTRY
    # Décidée : elle ne repasse pas.
    r = await _ecran(hass, entree)
    assert r["step_id"] == "deverrouillage_resultat" and "Aucune demande" in r["description_placeholders"]["resultat"]


async def test_approuver_par_qr_image_signee(hass, hass_storage, hass_client_no_auth, hass_client):
    entree = await _monter(hass, hass_storage)
    anonyme = await hass_client_no_auth()
    admin = await hass_client()  # comme l'interface : la session d'un administrateur
    demande = await _demander(anonyme, action="parent", minutes=15)
    r = await (await admin.post("/api/config/config_entries/options/flow", json={"handler": entree.entry_id})).json()
    r = await (await admin.post(f"/api/config/config_entries/options/flow/{r['flow_id']}", json={"next_step_id": "autres"})).json()
    r = await (await admin.post(f"/api/config/config_entries/options/flow/{r['flow_id']}", json={"next_step_id": "securite"})).json()
    r = await (await admin.post(f"/api/config/config_entries/options/flow/{r['flow_id']}", json={"next_step_id": "deverrouillage"})).json()
    r = await (await admin.post(f"/api/config/config_entries/options/flow/{r['flow_id']}",
                                json={"demande": demande, "decision": "approuver", "remise": "qr"})).json()
    texte = r["description_placeholders"]["resultat"]
    chemin = re.search(r"\]\((/api/pc_parental/deverrouillage/qr/[^)]+)\)", texte).group(1)
    assert "authSig=" in chemin
    # Sans la signature : refusé ; avec : le QR.
    assert (await anonyme.get(chemin.split("?")[0])).status == 401
    reponse = await anonyme.get(chemin)
    assert reponse.status == 200 and b"<svg" in await reponse.read()


async def test_refuser(hass, hass_storage, hass_client_no_auth):
    entree = await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    demande = await _demander(client)
    r = await _ecran(hass, entree)
    r = await hass.config_entries.options.async_configure(
        r["flow_id"], {"demande": demande, "decision": "refuser", "remise": "directe"})
    assert "refusée" in r["description_placeholders"]["resultat"]
    etat = await client.post("/api/pc_parental/deverrouillage/etat", json={**ENFANT, "demande": demande})
    assert (await etat.json())["etat"] == "refusee"


async def test_duree_trop_longue(hass, hass_storage, hass_client_no_auth):
    entree = await _monter(hass, hass_storage)
    demande = await _demander(await hass_client_no_auth(), minutes=10)
    r = await _ecran(hass, entree)
    r = await hass.config_entries.options.async_configure(
        r["flow_id"], {"demande": demande, "decision": "approuver", "minutes": 30, "remise": "directe"})
    assert r["errors"] == {"base": "deverrouillage_minutes"}
