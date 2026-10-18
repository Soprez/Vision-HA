"""2.4.6 — choisir le moment de la musique (l'auteur : « avancer et reculer avec un petit bouton qu'on fait glisser »).

L'appli envoie « position » (secondes) : Home Assistant demande `media_player.media_seek` au lecteur de la Party,
borné au titre. Le saut est gardé 30 s dans l'état de la Party (« saut » : position et âge), pour que la télé qui
joue elle-même (lecteur « · Vision », sans position publiée par Music Assistant) recale son horloge.

    python -m pytest essai/party_saut_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from homeassistant.core import ServiceCall

from party_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, PARENT, SALON, TELE, _integrations, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
)


async def _avec_seek(hass, hass_storage):
    coord, appels = await _monter(hass, hass_storage)

    async def noter(call: ServiceCall):
        appels.append((call.domain, call.service, dict(call.data)))
    hass.services.async_register("media_player", "media_seek", noter)
    a = dict(hass.states.get(SALON).attributes)
    hass.states.async_set(SALON, "playing", a | {"media_duration": 240, "media_position": 12})
    return coord, appels


def _seeks(appels):
    return [d for (dom, srv, d) in appels if dom == "media_player" and srv == "media_seek"]


async def test_saut_appelle_media_seek_et_reste_dans_l_etat(hass, hass_storage, hass_client):
    _coord, appels = await _avec_seek(hass, hass_storage)
    client = await hass_client()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    s, r = await _post(client, "commande", PARENT, action="position", position=95.26)
    assert s == 200, r
    assert _seeks(appels)[-1] == {"entity_id": SALON, "seek_position": 95.3}
    # L'état de la Party donne le saut à la télé (position et âge, sans horloge commune).
    s, r = await _post(client, "etat", TELE)
    assert s == 200 and r["saut"]["position"] == 95.3 and 0 <= r["saut"]["il_y_a"] < 5


async def test_saut_borne_au_titre_et_refus_d_une_position_absurde(hass, hass_storage, hass_client):
    _coord, appels = await _avec_seek(hass, hass_storage)
    client = await hass_client()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    s, _ = await _post(client, "commande", PARENT, action="position", position=10_000)
    assert s == 200 and _seeks(appels)[-1]["seek_position"] == 239.0  # jamais au-delà de la fin
    n = len(_seeks(appels))
    for mauvaise in (-3, "abc", None, float("nan")):
        s, r = await _post(client, "commande", PARENT, action="position", position=mauvaise if mauvaise == mauvaise else "nan")
        assert s == 400 and r["retour"] == "Position invalide.", mauvaise
    assert len(_seeks(appels)) == n


async def test_la_tele_et_chacun_pendant_la_party_peuvent_sauter(hass, hass_storage, hass_client):
    _coord, appels = await _avec_seek(hass, hass_storage)
    client = await hass_client()
    # Hors Party, un enfant ne pilote rien.
    s, _ = await _post(client, "commande", ENFANT, action="position", position=30)
    assert s == 403 and not _seeks(appels)
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    s, r = await _post(client, "commande", TELE, action="position", position=30)
    assert s == 200, r
    s, r = await _post(client, "commande", ENFANT, action="position", position=40)
    assert s == 200, r
    assert [x["seek_position"] for x in _seeks(appels)] == [30.0, 40.0]


async def test_un_vieux_saut_ou_un_autre_lecteur_ne_compte_plus(hass, hass_storage, hass_client):
    _coord, _appels = await _avec_seek(hass, hass_storage)
    from custom_components.pc_parental import party
    client = await hass_client()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    await _post(client, "commande", PARENT, action="position", position=60)
    assert party.saut_recent(hass, SALON)["position"] == 60.0
    assert party.saut_recent(hass, "media_player.cuisine") is None
    assert party.saut_recent(hass, SALON, time.time() + party.SAUT_S + 1) is None
    s, r = await _post(client, "etat", PARENT)
    assert s == 200 and r["saut"]["position"] == 60.0
