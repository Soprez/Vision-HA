"""Accorder du temps (1.80.0, demande du 2026-10-08) : durée libre et « jusqu'à la prochaine plage ».

- la demande de temps arrive chez qui gère avec l'heure du prochain changement de l'agenda ;
- répondre « jusqu'à la prochaine plage » ouvre jusqu'à ce changement (et le dit) ;
- une durée libre au-delà de 4 h passe (jusqu'à 12 h) ;
- un appareil sans « Gérer » ne répond toujours pas (gestion_attaques_test).

    python -m pytest essai/temps_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)


async def _demander(client, hass, minutes=30):
    r = await client.post("/api/pc_parental/poll", json={**ENFANT, "demande_temps": minutes})
    assert r.status == 200
    coord = next(iter(hass.data[DOMAIN].values()))
    return coord, coord.store.pcs["e1"]["demande"]["id"]


async def test_demande_porte_le_prochain_changement(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    coord, _ = await _demander(client, hass)
    msg = next(m for m in coord.store.pcs["p1"]["messages"] if m.get("demande"))
    assert msg["demande"]["prochain"]  # la plage « Soir » donne toujours un prochain changement


async def test_jusqu_a_la_prochaine_plage(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    coord, ident = await _demander(client, hass)
    r = await client.post("/api/pc_parental/reponse", json={**PARENT, "pc": "e1", "demande": ident, "minutes": 7, "jusqua_plage": True})
    corps = await r.json()
    assert r.status == 200 and "jusqu'à" in corps["retour"]
    o = coord.store.pcs["e1"]["override"]
    attendu = coord.store.evaluate(coord.store.pcs["e1"]).get("next_change")
    assert o["mode"] == "unlocked" and o["until"] > time.time() and attendu is not None
    assert corps["minutes"] >= 1


async def test_duree_libre_au_dela_de_4_h(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    coord, ident = await _demander(client, hass)
    r = await client.post("/api/pc_parental/reponse", json={**PARENT, "pc": "e1", "demande": ident, "minutes": 330})
    assert (await r.json())["minutes"] == 330
    assert abs(coord.store.pcs["e1"]["override"]["until"] - (time.time() + 330 * 60)) < 120


async def test_demande_de_temps_dans_la_liste_et_reponse(hass, hass_storage, hass_client):
    """1.97.2 (signalé par Camille) : une demande de temps attend dans « Demandes » (carte Maison, appli),
    se garde tout de suite, et se règle de là : accordée (le temps demandé) ou refusée, prévenu dans les deux cas."""
    from custom_components.pc_parental import parent

    client = await hass_client()
    await _monter(hass, hass_storage)
    coord, ident = await _demander(client, hass, minutes=15)
    await hass.async_block_till_done()
    assert hass_storage[DOMAIN]["data"]["pcs"]["e1"]["demande"]["id"] == ident  # gardée sans attendre
    liste = parent.etat_maison(hass, coord, "p1")["demandes"]
    t = next(d for d in liste if d["genre"] == "temps")
    assert t["id"] == ident and t["minutes"] == 15 and t["pc"] == "e1"
    enfant = coord.store.pcs["e1"]
    enfant["messages"] = []
    retour = await parent.agir(hass, coord, enfant, {"action": "acces", "demande": ident, "decision": "temporaire", "minutes": 60}, "Nolan", "p1")
    assert "15 min" in retour and enfant.get("demande") is None
    assert any("15 min" in m["text"] for m in enfant["messages"])
    assert not [d for d in parent.etat_maison(hass, coord, "p1")["demandes"] if d["genre"] == "temps"]
    # Refus
    _, ident2 = await _demander(client, hass, minutes=30)
    retour = await parent.agir(hass, coord, enfant, {"action": "acces", "demande": ident2, "decision": "non"}, "Nolan", "p1")
    assert "refusé" in retour and enfant.get("demande") is None
    # Trop vieille : elle ne s'affiche plus.
    _, _ident3 = await _demander(client, hass, minutes=30)
    enfant["demande"]["ts"] = time.time() - 25 * 3600
    assert not [d for d in parent.etat_maison(hass, coord, "p1")["demandes"] if d["genre"] == "temps"]
