"""Le nom du lieu (1.81.0) : un mot neutre par défaut (« Lieu », dans la langue de Home Assistant ; un nom
enregistré, « Maison » compris, reste tel quel), réglable dans Vision → Configurer → Réglages généraux.

Le relevé des agents et l'espace gestion renvoient « lieu » ; l'agent et la carte l'affichent là où il faut
nommer le lieu (onglet, en-tête, signature des notifications).

    python -m pytest essai/lieu_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import _actuel, _monter
from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _integrations, expected_lingering_tasks, expected_lingering_timers,
)



async def _lieux(client):
    releve = await client.post("/api/pc_parental/poll", json=ENFANT)
    assert releve.status == 200, await releve.text()
    gestion = await client.post("/api/pc_parental/parent", json=PARENT)
    assert gestion.status == 200, await gestion.text()
    return (await releve.json())["lieu"], (await gestion.json())["lieu"]


async def test_maison_par_defaut_puis_ecole(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    assert await _lieux(client) == ("Lieu", "Lieu")
    _actuel(hass).store.lieu = "Maison"  # un nom déjà enregistré ne change pas
    assert await _lieux(client) == ("Maison", "Maison")
    await hass.async_block_till_done()
    _actuel(hass).store.lieu = "École"
    assert await _lieux(client) == ("École", "École")


async def test_reglages_enregistrent_le_nom(hass, hass_storage, hass_client):
    """Lot 5 : le nom du lieu se règle dans Réglages › Réservé › Lieu et vocabulaire (et dans l'assistant)."""
    admin = await hass_client()
    await _monter(hass, hass_storage)
    r = await admin.post("/api/pc_parental/reglages/admin", json={"op": "lieu", "lieu": "  Bureau  ", "mots": {}})
    assert r.status == 200, await r.text()
    await hass.async_block_till_done()
    assert _actuel(hass).store.lieu == "Bureau"
    assert hass_storage[DOMAIN]["data"]["lieu"] == "Bureau"
    assert await _lieux(admin) == ("Bureau", "Bureau")
