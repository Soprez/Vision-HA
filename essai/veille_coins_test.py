"""1.93.05 (l'auteur, retour 39) : les coins de la veille d'un appareil (QR des invités, logo), réglés depuis Home Assistant.

    python -m pytest essai/veille_coins_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pathlib

import pytest
from homeassistant.exceptions import HomeAssistantError

from son_test import ENFANT, ENFANT2, DOMAIN, _monter, _releve  # noqa: F401  (banc)
from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401

JS = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental" / "frontend" / "vision-veille-coeur.js"


async def _regler(hass, **donnees):
    return await hass.services.async_call(DOMAIN, "veille_regler", {"pc": "TEL-A", **donnees}, blocking=True, return_response=True)


async def test_coins_par_defaut_puis_regles(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    rep = await _regler(hass, lecteur=False)
    # Par défaut : le QR en bas à droite, le logo à sa place d'avant (en bas à gauche).
    assert rep["reglages"] == {"lecteur": False, "qr": "bas_droite", "logo": "bas_gauche"}
    rep = await _regler(hass, qr="haut_gauche", logo="haut_droite")
    assert rep["reglages"]["qr"] == "haut_gauche" and rep["reglages"]["logo"] == "haut_droite"
    bloc = (await _releve(client, ENFANT))["veille_options"]
    assert bloc["qr"] == "haut_gauche" and bloc["logo"] == "haut_droite"
    # Masqués : permis tous les deux.
    rep = await _regler(hass, qr="cache", logo="cache")
    assert rep["reglages"]["qr"] == "cache" and rep["reglages"]["logo"] == "cache"


async def test_meme_coin_refuse(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    await _regler(hass, qr="bas_droite", logo="bas_gauche")
    with pytest.raises(HomeAssistantError):
        await _regler(hass, logo="bas_droite")
    # Refusé : rien n'a changé.
    bloc = (await _releve(client, ENFANT))["veille_options"]
    assert bloc["qr"] == "bas_droite" and bloc["logo"] == "bas_gauche"
    with pytest.raises(Exception):  # une place inconnue : refusée par le schéma
        await _regler(hass, qr="milieu")


def test_page_web_suit_le_coin_du_logo():
    js = JS.read_text(encoding="utf-8")
    assert 'this.setAttribute("data-logo"' in js
    assert ':host([data-logo="cache"]) .marque { display: none; }' in js
    assert "right: var(--v-adroite, 3.5vw)" in js and "var(--v-pdroite, 3.5vw)" in js
