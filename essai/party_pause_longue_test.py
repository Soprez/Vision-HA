"""2.5.12.2 (l'auteur, 19/10, TV du salon) : « une pause de plus de 30 s fait reprendre le titre au début ».

Vu en direct à 12:14 : pause à 1:11, Music Assistant arrête le lecteur Classique 30 s plus tard (« Clearing active output
protocol », état `idle`), et ▶ fait repartir le titre à 0:00. Depuis la 2.5.12.2, Home Assistant retient la position au
moment de la pause (changement d'état → `paused`) ; au ▶ suivant sur un lecteur à l'arrêt, dès que la lecture est
repartie, il saute à cette position (même titre seulement), et l'appli recale son lecteur (`saut`).

Règles vérifiées ici :
- pause à 71 s puis arrêt par MA → ▶ : `media_play`, puis `media_seek` à 71 s dès que le lecteur joue, et `saut` publié ;
- lecteur encore en pause → ▶ : `media_play` seulement (MA reprend sur place) ;
- un autre titre est maintenant chargé → pas de saut ; pause à moins de 10 s → pas de saut ;
- la lecture ne repart pas → pas de saut, rien de cassé.

    python -m pytest essai/party_pause_longue_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import asyncio

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, MA_CHAMBRE, MA_SALON, PARENT, TELE, _integrations, _monter, _post, expected_lingering_tasks,
    expected_lingering_timers,
)
from party_soir_test import _party

TITRE = "library://track/145"


def _etat(hass, etat: str, position: float | None = 71.0, titre: str = TITRE):
    attrs = {"friendly_name": "Salon", "active_queue": "qid-salon", "media_title": "Dancing Queen", "media_content_id": titre}
    if position is not None:
        attrs["media_position"] = position
    hass.states.async_set(MA_SALON, etat, attrs)


def _services(hass):
    appels = []
    for s in ("media_play", "media_pause", "media_seek"):
        hass.services.async_register("media_player", s, lambda call, s=s: appels.append((s, dict(call.data))))
    return appels


async def test_apres_un_arret_de_ma_le_titre_reprend_a_la_position_de_la_pause(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import party
    _c, _a, _f, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    appels = _services(hass)
    _etat(hass, "playing", 60.0)
    await hass.async_block_till_done()
    _etat(hass, "paused", 71.0)   # pause à 1:11
    await hass.async_block_till_done()
    _etat(hass, "idle", 0.0)      # 30 s plus tard : Music Assistant arrête le lecteur
    await hass.async_block_till_done()
    s, r = await _post(client, "commande", TELE, action="lecture", voulu="lecture")
    assert s == 200, r
    assert [n for n, _ in appels] == ["media_play"]
    _etat(hass, "playing", 0.0)   # la lecture repart (du début, côté MA)
    await asyncio.sleep(0.6)
    await hass.async_block_till_done()
    assert appels[-1] == ("media_seek", {"seek_position": 71.0, "entity_id": MA_SALON}), appels
    assert party.etat(hass)["saut"]["position"] == 71.0 and party.etat(hass)["saut"]["lecteur"] == MA_SALON
    # Une seconde fois : rien à reprendre (la pause est consommée).
    s, r = await _post(client, "commande", TELE, action="lecture", voulu="lecture")
    await asyncio.sleep(0.3)
    assert [n for n, _ in appels].count("media_seek") == 1


async def test_lecteur_encore_en_pause_music_assistant_reprend_sur_place(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, _f, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    appels = _services(hass)
    _etat(hass, "paused", 71.0)
    await hass.async_block_till_done()
    s, r = await _post(client, "commande", TELE, action="lecture", voulu="lecture")
    assert s == 200, r
    _etat(hass, "playing", 71.0)
    await asyncio.sleep(0.4)
    assert [n for n, _ in appels] == ["media_play"], appels


async def test_autre_titre_ou_pause_courte_pas_de_saut(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import party
    _c, _a, _f, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    appels = _services(hass)
    # Pause à 71 s, arrêt, mais un autre titre est maintenant chargé : il part du début, sans saut.
    _etat(hass, "paused", 71.0)
    await hass.async_block_till_done()
    _etat(hass, "idle", 0.0, titre="library://track/999")
    await hass.async_block_till_done()
    s, r = await _post(client, "commande", TELE, action="lecture", voulu="lecture")
    assert s == 200, r
    _etat(hass, "playing", 0.0, titre="library://track/999")
    await asyncio.sleep(0.4)
    assert [n for n, _ in appels] == ["media_play"], appels
    # Pause à 5 s : trop tôt pour un saut (règle pure).
    assert party.position_de_reprise(MA_SALON, "idle", {"media_content_id": TITRE}, 0.0) is None
    party.noter_pause(MA_SALON, "paused", {"media_position": 5.0, "media_content_id": TITRE}, 1000.0)
    assert party.position_de_reprise(MA_SALON, "idle", {"media_content_id": TITRE}, 1010.0) is None
    party.noter_pause(MA_SALON, "paused", {"media_position": 71.0, "media_content_id": TITRE}, 1000.0)
    assert party.position_de_reprise(MA_SALON, "idle", {"media_content_id": TITRE}, 1010.0) == 71.0
    # Trop ancien (plus de 4 h), ou lecteur en pause (MA reprend sur place), ou lecture en cours : rien.
    assert party.position_de_reprise(MA_SALON, "idle", {"media_content_id": TITRE}, 1000.0 + 5 * 3600) is None
    assert party.position_de_reprise(MA_SALON, "paused", {"media_content_id": TITRE}, 1010.0) is None
    party.noter_pause(MA_SALON, "playing", {}, 1020.0)
    assert party.position_de_reprise(MA_SALON, "idle", {"media_content_id": TITRE}, 1030.0) is None


async def test_la_lecture_ne_repart_pas_rien_de_casse(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import party
    _c, _a, _f, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    appels = _services(hass)
    monkeypatch.setattr(party, "PAUSE_REPRISE_ATTENTE_S", 0.6, raising=False)
    _etat(hass, "paused", 71.0)
    await hass.async_block_till_done()
    _etat(hass, "idle", 0.0)
    await hass.async_block_till_done()
    s, r = await _post(client, "commande", TELE, action="lecture", voulu="lecture")
    assert s == 200, r
    await asyncio.sleep(1.2)
    await hass.async_block_till_done()
    assert [n for n, _ in appels] == ["media_play"], appels
    assert not party.etat(hass).get("saut")
