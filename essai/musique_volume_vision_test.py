"""2.3.8 (l'auteur, 0 h 15 : « pas de son ») — un lecteur « · Vision » de Music Assistant recréé arrivait à 10 % de volume
logiciel. HA tient le volume logiciel des lecteurs « · Vision » à 100 % (c'est la télécommande de la télé qui règle
le son) ; les autres enceintes gardent leur volume.

    python -m pytest essai/musique_volume_vision_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.core import ServiceCall

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    MA_CHAMBRE, MA_SALON, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)


async def test_volume_des_lecteurs_vision_tenu_a_100(hass, hass_storage, monkeypatch):
    await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique

    appels = []

    async def volume_set(call: ServiceCall):
        appels.append((call.data["entity_id"], call.data["volume_level"]))
    hass.services.async_register("media_player", "volume_set", volume_set)
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Télé du bureau · Vision", "volume_level": 0.1})
    hass.states.async_set(MA_CHAMBRE, "playing", {"friendly_name": "Chambre", "volume_level": 0.1})
    await musique.regler_lecteurs_vision(hass)
    ids = {e for e, _ in appels}
    assert (MA_SALON, 1.0) in [(e if isinstance(e, str) else e[0], v) for e, v in appels]
    assert MA_CHAMBRE not in ids and [MA_CHAMBRE] not in [e for e, _ in appels]  # une autre enceinte : jamais touchée
    # Déjà à 100 % : rien à faire.
    appels.clear()
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Télé du bureau · Vision", "volume_level": 1.0})
    await musique.regler_lecteurs_vision(hass)
    assert appels == []
