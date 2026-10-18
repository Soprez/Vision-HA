"""1.93.05 (l'auteur, retour 36) : les options de la veille d'un appareil, réglées depuis Home Assistant.

    python -m pytest essai/veille_options_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pytest
from homeassistant.core import Context
from homeassistant.exceptions import Unauthorized

from son_test import ENFANT, ENFANT2, DOMAIN, _monter, _releve  # noqa: F401  (banc)
from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401


async def _regler(hass, contexte=None, **donnees):
    return await hass.services.async_call(DOMAIN, "veille_regler", {"pc": "TEL-A", **donnees}, blocking=True,
                                          return_response=True, context=contexte)


async def test_bandeau_lecteur_par_appareil(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    # Tant que Home Assistant n'a rien réglé : pas de bloc, l'appareil garde ses choix (bandeau non par défaut).
    for qui in (ENFANT, ENFANT2):
        assert (await _releve(client, qui))["veille_options"] is None
    rep = await _regler(hass, lecteur=True)
    assert rep["reglages"]["lecteur"] is True
    bloc = (await _releve(client, ENFANT))["veille_options"]
    assert bloc["lecteur"] is True and bloc["depuis"] > 0
    assert (await _releve(client, ENFANT2))["veille_options"] is None
    # Un nouveau réglage est plus récent (l'appareil l'applique).
    await _regler(hass, lecteur=False)
    bloc2 = (await _releve(client, ENFANT))["veille_options"]
    assert bloc2["lecteur"] is False and bloc2["depuis"] > bloc["depuis"]


async def test_reserve_aux_administrateurs(hass, hass_storage, hass_read_only_user):
    await _monter(hass, hass_storage)
    with pytest.raises(Unauthorized):
        await _regler(hass, Context(user_id=hass_read_only_user.id), lecteur=True)
