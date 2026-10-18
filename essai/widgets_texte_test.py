"""Widgets : la ligne sous la valeur (17-10, l'auteur : « widget chauffage lisible »).

Un réglage de widget peut s'accompagner d'une entité « texte » (choisie dans la carte Widgets HA) : son état s'écrit
sous la valeur (« Réduit jusqu'à 12:00, puis Confort 20 °C »). Il part avec l'entité (catalogue, états, flux) et le
widget est redessiné quand seul le texte change. Aucun nom d'entité dans le code.

    python -m pytest essai/widgets_texte_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json

import pytest

from widgets_test import (  # noqa: F401  (banc des widgets et ses fixtures)
    DOMAIN, ENFANT, PARENT, W, _lignes, _monter, _post, _w, expected_lingering_tasks, expected_lingering_timers,
    integrations,
)

ADMIN = "/api/pc_parental/widgets/admin"


async def _admin(client, **corps):
    r = await client.post(ADMIN, json=corps)
    return r.status, await r.json()


@pytest.mark.parametrize("texte", ["pas une entité", "sensor.x;rm", "climate.salon", "a" * 300])
async def test_texte_refuse(hass, integrations, hass_storage, hass_client, texte):
    _, coord = await _monter(hass, hass_storage)
    client = await hass_client()
    st, d = await _admin(client, op="reglage", entite="climate.salon", texte=texte)
    assert st == 400, (texte, d)
    assert not (coord.store.entites_reglages.get("climate.salon") or {}).get("texte")


async def test_ligne_sous_la_valeur(hass, integrations, hass_storage, hass_client, monkeypatch):
    _, coord = await _monter(hass, hass_storage)
    client = await hass_client()
    hass.states.async_set("sensor.mode_essai", "Réduit jusqu'à 12:00, puis Confort 20 °C")
    # Sans entité liée : pas de clé « texte » (les anciennes versions de l'appli ne voient rien changer).
    st, d = await _post(client, "/entites", PARENT)
    assert "texte" not in next(e for e in d["entites"] if e["entity_id"] == "climate.salon")
    st, d = await _admin(client, op="reglage", entite="climate.salon", texte="sensor.mode_essai")
    assert st == 200, d
    st, d = await _post(client, "/entites", PARENT)
    assert next(e for e in d["entites"] if e["entity_id"] == "climate.salon")["texte"] == "Réduit jusqu'à 12:00, puis Confort 20 °C"
    st, d = await _post(client, "/etats", PARENT, entites=["climate.salon"])
    assert d["etats"]["climate.salon"]["texte"] == "Réduit jusqu'à 12:00, puis Confort 20 °C"
    # Le texte change seul : le widget de la consigne est poussé avec le nouveau texte ; l'entité texte, elle, n'est
    # jamais envoyée pour elle-même (elle n'est pas dans la liste de l'appareil).
    monkeypatch.setattr(_w().PcParentalWidgetsFluxView, "battement", 0.3)
    r = await client.get(W + "/flux", params=PARENT)
    await _lignes(r, lambda l: l.startswith("retry"))
    hass.states.async_set("sensor.mode_essai", "Confort jusqu'à 13:30")
    vues = await _lignes(r, lambda l: "Confort jusqu'à 13:30" in l)
    donnees = json.loads(vues[-1][len("data: "):])
    assert donnees["entity_id"] == "climate.salon" and donnees["texte"] == "Confort jusqu'à 13:30"
    assert not any('"entity_id": "sensor.mode_essai"' in l for l in vues)
    r.close()
    # Entité texte indisponible : une ligne vide (le widget ne montre rien), jamais « unavailable ».
    hass.states.async_set("sensor.mode_essai", "unavailable")
    st, d = await _post(client, "/etats", PARENT, entites=["climate.salon"])
    assert d["etats"]["climate.salon"]["texte"] == ""
    # Retirée dans la carte : plus de clé.
    st, d = await _admin(client, op="reglage", entite="climate.salon", texte="")
    st, d = await _post(client, "/etats", PARENT, entites=["climate.salon"])
    assert "texte" not in d["etats"]["climate.salon"]
