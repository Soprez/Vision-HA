"""1.93.06.1 (l'auteur, tablettes tableau de bord) : « Afficher la liste des applications » d'un appareil, réglée depuis HA.

    python -m pytest essai/accueil_options_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pytest
import voluptuous as vol
from homeassistant.core import Context
from homeassistant.exceptions import Unauthorized

from son_test import ENFANT, ENFANT2, DOMAIN, _monter, _releve  # noqa: F401  (banc)
from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401


async def _regler(hass, contexte=None, **donnees):
    return await hass.services.async_call(DOMAIN, "accueil_regler", {"pc": "TEL-A", **donnees}, blocking=True,
                                          return_response=True, context=contexte)


async def test_reglage_par_appareil(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    # Rien réglé : pas de bloc, l'appareil garde son choix (liste affichée par défaut).
    for qui in (ENFANT, ENFANT2):
        assert (await _releve(client, qui))["accueil_options"] is None
    rep = await _regler(hass, liste_applis=False)
    assert rep["reglages"] == {"liste_applis": False}
    bloc = (await _releve(client, ENFANT))["accueil_options"]
    assert bloc["liste_applis"] is False and bloc["depuis"] > 0
    # Un autre appareil n'est pas touché.
    assert (await _releve(client, ENFANT2))["accueil_options"] is None
    # Réafficher : un réglage plus récent (« depuis » plus grand), que l'appareil appliquera.
    await _regler(hass, liste_applis=True)
    bloc2 = (await _releve(client, ENFANT))["accueil_options"]
    assert bloc2["liste_applis"] is True and bloc2["depuis"] > bloc["depuis"]


async def test_le_reglage_est_obligatoire(hass, hass_storage):
    await _monter(hass, hass_storage)
    with pytest.raises(vol.Invalid):
        await _regler(hass)


async def test_reserve_aux_administrateurs(hass, hass_storage, hass_read_only_user):
    await _monter(hass, hass_storage)
    with pytest.raises(Unauthorized):
        await _regler(hass, Context(user_id=hass_read_only_user.id), liste_applis=False)
