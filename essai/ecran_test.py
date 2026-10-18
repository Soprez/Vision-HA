"""17-10 (l'auteur : « désactiver l'écran de veille par appareil ») : l'écran d'un appareil réglé depuis Home Assistant.

    python -m pytest essai/ecran_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pytest
from homeassistant.core import Context
from homeassistant.exceptions import Unauthorized

from son_test import ENFANT, ENFANT2, DOMAIN, _monter, _releve  # noqa: F401  (banc)
from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401


async def _regler(hass, contexte=None, **donnees):
    return await hass.services.async_call(DOMAIN, "ecran_regler", {"pc": "TEL-A", **donnees}, blocking=True,
                                          return_response=True, context=contexte)


async def test_defauts_surs_puis_reglage_par_appareil(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    # Par défaut : exactement comme avant (veille de Vision permise, écran non imposé), sur chaque appareil.
    for qui in (ENFANT, ENFANT2):
        r = await _releve(client, qui)
        assert r["ecran"] == {"veille_vision": True, "garder_allume": False}
    # Un réglage pour TEL-A seulement.
    rep = await _regler(hass, veille_vision=False, garder_allume=True)
    assert rep["reglages"] == {"veille_vision": False, "garder_allume": True}
    assert (await _releve(client, ENFANT))["ecran"] == {"veille_vision": False, "garder_allume": True}
    assert (await _releve(client, ENFANT2))["ecran"] == {"veille_vision": True, "garder_allume": False}
    # Un seul réglage changé : l'autre est gardé.
    await _regler(hass, garder_allume=False)
    assert (await _releve(client, ENFANT))["ecran"] == {"veille_vision": False, "garder_allume": False}


async def test_reserve_aux_administrateurs(hass, hass_storage, hass_read_only_user):
    await _monter(hass, hass_storage)
    with pytest.raises(Unauthorized):
        await _regler(hass, Context(user_id=hass_read_only_user.id), veille_vision=False)
