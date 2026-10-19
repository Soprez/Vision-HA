"""1.93.06 / 2.5.12 (sujet 6, essais instables) : le premier relevé d'un appareil ne recharge plus Vision.

Cause des échecs instables de la suite complète (temps, biométrie, octroi, soutien, pauses…, 17/10) : au premier relevé,
Vision déclarait l'étiquette « Toujours autorisé ». La liste des étiquettes proposées changeait, et l'intégration se
rechargeait (`async_schedule_reload`). Pendant ce rechargement, `hass.data[DOMAIN]` est vide un instant : un essai qui
lisait le coordinateur juste après le relevé tombait dessus (« coroutine raised StopIteration »), plus souvent sous
charge (trois lots en parallèle). En vrai, c'était aussi un rechargement inutile à chaque nouvelle installation.

Maintenant l'étiquette est déclarée au démarrage, avant la création des entités.

    python -m pytest essai/stabilite_releve_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.helpers import label_registry as lr

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)


async def test_le_premier_releve_ne_recharge_pas_vision(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    # L'étiquette existe dès le démarrage, avant tout relevé.
    assert lr.async_get(hass).async_get_label_by_name("Toujours autorisé") is not None
    recharges = []
    vrai = hass.config_entries.async_schedule_reload
    hass.config_entries.async_schedule_reload = lambda entry_id: recharges.append(entry_id)
    try:
        r = await client.post("/api/pc_parental/poll", json=ENFANT)
        assert r.status == 200
        await hass.async_block_till_done()
        r = await client.post("/api/pc_parental/poll", json=ENFANT)
        assert r.status == 200
        await hass.async_block_till_done()
    finally:
        hass.config_entries.async_schedule_reload = vrai
    assert recharges == [], "le relevé ne doit pas recharger l'intégration"
    # Le même coordinateur est toujours là (rien n'a été démonté).
    assert next(iter(hass.data[DOMAIN].values())) is coord
