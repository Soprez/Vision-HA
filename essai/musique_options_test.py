"""1.93.06 (retour d'essai) : la musique d'un appareil face au son des autres applis (focus audio), réglée depuis Home Assistant.

    python -m pytest essai/musique_options_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pytest
from homeassistant.core import Context
from homeassistant.exceptions import HomeAssistantError, Unauthorized

from son_test import ENFANT, ENFANT2, DOMAIN, _monter, _releve  # noqa: F401  (banc)
from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401


async def _regler(hass, contexte=None, **donnees):
    return await hass.services.async_call(DOMAIN, "musique_regler", {"pc": "TEL-A", **donnees}, blocking=True,
                                          return_response=True, context=contexte)


async def test_reglage_par_appareil(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    # Rien réglé : pas de bloc, l'appareil garde ses choix (pause « toujours », sans reprise).
    for qui in (ENFANT, ENFANT2):
        assert (await _releve(client, qui))["musique_options"] is None
    rep = await _regler(hass, focus="applis", applis=["com.google.android.youtube.tv", "com.netflix.ninja"], reprise=True)
    assert rep["reglages"] == {"focus": "applis", "applis": ["com.google.android.youtube.tv", "com.netflix.ninja"],
                               "reprise": True}
    bloc = (await _releve(client, ENFANT))["musique_options"]
    assert bloc["focus"] == "applis" and bloc["reprise"] is True and bloc["depuis"] > 0
    assert (await _releve(client, ENFANT2))["musique_options"] is None
    # Une liste écrite d'un trait (champ texte d'une automatisation) est découpée ; un réglage partiel garde le reste.
    await _regler(hass, applis="com.a.b, com.c.d")
    bloc2 = (await _releve(client, ENFANT))["musique_options"]
    assert bloc2["applis"] == ["com.a.b", "com.c.d"] and bloc2["focus"] == "applis" and bloc2["depuis"] > bloc["depuis"]
    await _regler(hass, focus="jamais")
    assert (await _releve(client, ENFANT))["musique_options"]["focus"] == "jamais"


async def test_nom_d_appli_invalide_refuse(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    await _regler(hass, focus="toujours")
    avant = (await _releve(client, ENFANT))["musique_options"]
    with pytest.raises(HomeAssistantError, match="youtube"):
        await _regler(hass, focus="applis", applis=["youtube"])
    # Rien n'a changé.
    assert (await _releve(client, ENFANT))["musique_options"] == avant


async def test_reserve_aux_administrateurs(hass, hass_storage, hass_read_only_user):
    await _monter(hass, hass_storage)
    with pytest.raises(Unauthorized):
        await _regler(hass, Context(user_id=hass_read_only_user.id), focus="jamais")


async def test_entrees_de_la_tele_dans_la_liste(hass, hass_storage, hass_client):
    """1.93.06 (point 6a) : « vision.entrees_tele » (HDMI, TV en direct) se met dans la liste comme une appli."""
    await _monter(hass, hass_storage)
    client = await hass_client()
    rep = await _regler(hass, focus="applis", applis=["vision.entrees_tele", "com.netflix.ninja"])
    assert rep["reglages"]["applis"] == ["vision.entrees_tele", "com.netflix.ninja"]
    assert "vision.entrees_tele" in (await _releve(client, ENFANT))["musique_options"]["applis"]
