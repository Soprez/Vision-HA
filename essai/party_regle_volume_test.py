"""2.4.0 — règle du projet : « l'appli ne gère pas le volume ; le volume de l'appli est à 100 %, après c'est la télé qui
gère », et lecture figée (télé éteinte : « playing », position 0, mini-lecteur toujours « en cours »).

    python -m pytest essai/party_regle_volume_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import datetime

from homeassistant.core import ServiceCall

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    MA_CHAMBRE, MA_SALON, PARENT, TELE, _integrations, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
)


def _volumes(hass):
    appels = []

    async def volume_set(call: ServiceCall):
        appels.append((call.data["entity_id"], call.data["volume_level"]))
    hass.services.async_register("media_player", "volume_set", volume_set)
    return appels


def _ids(appels):
    return [(e if isinstance(e, str) else e[0], v) for e, v in appels]


async def test_volume_vision_tenu_et_jamais_regle(hass, hass_storage, hass_client, monkeypatch):
    await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique, party
    appels = _volumes(hass)
    hass.states.async_set(MA_SALON, "idle", {"friendly_name": "Télé du bureau · Vision", "volume_level": 1.0,
                                             "active_queue": "qid-salon"})
    hass.states.async_set(MA_CHAMBRE, "idle", {"friendly_name": "Chambre", "volume_level": 0.4, "active_queue": "qid-chambre"})
    await hass.async_block_till_done()
    assert musique.est_lecteur_vision(hass, MA_SALON) and not musique.est_lecteur_vision(hass, MA_CHAMBRE)

    # Music Assistant pousse 10 % (nouvel identifiant, réglage par défaut) : HA remet 100 % sans attendre.
    appels.clear()
    hass.states.async_set(MA_SALON, "idle", {"friendly_name": "Télé du bureau · Vision", "volume_level": 0.1,
                                             "active_queue": "qid-salon"})
    await hass.async_block_till_done()
    assert (MA_SALON, 1.0) in _ids(appels)
    # Une autre enceinte qui baisse : jamais touchée.
    appels.clear()
    hass.states.async_set(MA_CHAMBRE, "idle", {"friendly_name": "Chambre", "volume_level": 0.1, "active_queue": "qid-chambre"})
    await hass.async_block_till_done()
    assert appels == []

    # « maintenant » ne donne pas de volume pour un lecteur « · Vision » (l'appli n'affiche ni − / + ni pourcentage).
    assert party.maintenant(hass, MA_SALON)["volume"] is None
    assert party.maintenant(hass, MA_CHAMBRE)["volume"] == 0.1

    # La commande « volume » de la Party sur un lecteur « · Vision » : il reste à 100 %.
    client = await hass_client()
    s, _r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200
    appels.clear()
    s, _r = await _post(client, "commande", PARENT, action="volume", volume=0.3)
    assert s == 200 and all(v == 1.0 for _e, v in _ids(appels))


async def test_lecture_figee_exposee_arretee(hass, hass_storage, monkeypatch):
    await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import party
    vieux = datetime.datetime.now(datetime.UTC) - datetime.timedelta(seconds=party.FIGE_S + 30)
    frais = datetime.datetime.now(datetime.UTC) - datetime.timedelta(seconds=5)
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Télé du bureau · Vision", "media_title": "Around the World",
                                                "media_position": 0, "media_position_updated_at": vieux})
    assert party.maintenant(hass, MA_SALON)["etat"] == "idle"  # figée : position plus mise à jour depuis > 60 s
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Télé du bureau · Vision", "media_title": "Around the World",
                                                "media_position": 42, "media_position_updated_at": frais})
    assert party.maintenant(hass, MA_SALON)["etat"] == "playing"
    # Un lecteur qui n'est pas de Music Assistant (Cast ordinaire) : jamais jugé figé.
    hass.states.async_set("media_player.cast_seul", "playing", {"friendly_name": "Cast", "media_position": 10,
                                                                "media_position_updated_at": vieux})
    assert party.maintenant(hass, "media_player.cast_seul")["etat"] == "playing"
