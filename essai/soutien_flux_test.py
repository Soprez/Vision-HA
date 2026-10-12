"""Soutien du projet, dans un vrai Home Assistant : Configurer → Soutenir le projet → code, puis le relevé.

Code valide (clé d'essai substituée à la clé embarquée), invalide, falsifié ; « J'ai offert un café » ;
l'état part aux appareils dans le relevé, sans identifiant ni date.

    python -m pytest essai/soutien_flux_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import base64

from cryptography.hazmat.primitives import serialization as _s
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from gestion_attaques_test import _actuel, _monter
from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

FLUX = "/api/config/config_entries/options/flow"
_CLE = Ed25519PrivateKey.generate()
_PUB = base64.b64encode(_CLE.public_key().public_bytes(_s.Encoding.Raw, _s.PublicFormat.Raw)).decode()


def _module():
    import sys

    return next(m for n, m in sys.modules.items() if n.endswith("pc_parental.soutien"))


def _code(cle=_CLE) -> str:
    message = bytes([1]) + bytes.fromhex("0102030405ff") + (647).to_bytes(2, "big")
    return _module().encoder(message + cle.sign(b"vision-soutien-v1" + message))


async def _releve(client):
    r = await client.post("/api/pc_parental/poll", json=ENFANT)
    assert r.status == 200, await r.text()
    return (await r.json())["soutien"]


async def _ouvrir_soutien(admin, entree):
    flux = await (await admin.post(FLUX, json={"handler": entree.entry_id})).json()
    assert "soutien" in flux["menu_options"]
    return await (await admin.post(f"{FLUX}/{flux['flow_id']}", json={"next_step_id": "soutien"})).json()


async def test_code_invalide_falsifie_puis_valide(hass, hass_storage, hass_client, monkeypatch):
    admin = await hass_client()
    coord = await _monter(hass, hass_storage)
    monkeypatch.setattr(_module(), "CLE_PUBLIQUE_B64", _PUB)
    assert await _releve(admin) == {"actif": False}

    menu = await _ouvrir_soutien(admin, coord.config_entry)
    assert menu["step_id"] == "soutien" and menu["menu_options"] == ["soutien_code", "soutien_cafe"]
    form = await (await admin.post(f"{FLUX}/{menu['flow_id']}", json={"next_step_id": "soutien_code"})).json()
    assert form["step_id"] == "soutien_code"

    for texte, erreur in (("pas un code", "soutien_format"), (_code(Ed25519PrivateKey.generate()), "soutien_signature")):
        r = await (await admin.post(f"{FLUX}/{form['flow_id']}", json={"code": texte})).json()
        assert r["type"] == "form" and r["errors"] == {"code": erreur}
    assert await _releve(admin) == {"actif": False}

    r = await (await admin.post(f"{FLUX}/{form['flow_id']}", json={"code": _code().lower()})).json()
    assert r["type"] == "abort" and r["reason"] == "soutien_merci"
    await hass.async_block_till_done()
    assert hass_storage[DOMAIN]["data"]["soutien"]["mode"] == "code"
    assert await _releve(admin) == {"actif": True, "mode": "code"}

    menu = await _ouvrir_soutien(admin, coord.config_entry)
    assert menu["step_id"] == "soutien_actif" and "soutien_retirer" in menu["menu_options"]


async def test_cafe_sur_l_honneur(hass, hass_storage, hass_client):
    admin = await hass_client()
    coord = await _monter(hass, hass_storage)
    menu = await _ouvrir_soutien(admin, coord.config_entry)
    r = await (await admin.post(f"{FLUX}/{menu['flow_id']}", json={"next_step_id": "soutien_cafe"})).json()
    assert r["type"] == "abort" and r["reason"] == "soutien_merci"
    assert await _releve(admin) == {"actif": True, "mode": "honneur"}
    _actuel(hass).store.soutien = {}
    assert await _releve(admin) == {"actif": False}
