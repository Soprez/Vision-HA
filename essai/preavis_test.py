"""Préavis avant coupure (1.85.0, point 14.1) : le relevé dit quand l'appareil va se fermer, et pourquoi.

Lot Accueil : le délai X est le réglage « Avertir avant fermeture » (warn_minutes, défaut 5) ; seuils [X] puis 5 et 1
s'ils sont plus petits ; fenêtre X min + 30 s ; X = 0 : aucun préavis.

- une plage qui commence dans 4 min, réglage par défaut : `preavis` (fin, motif, seuils 5/1) et l'ancien `warn` ;
- X = 10, plage dans 7 min : seuils 10/5/1 ; X = 1 : [1] ; X = 0 : rien ; plage dans 7 min avec X = 5 : rien ;
- rien d'imminent (plage dans 2 h) : pas de `preavis` ;
- un temps accordé qui finit dans 4 min sur un agenda fermé : motif « Fin du temps accordé » ;
- la limite du jour d'une appli qui approche : `preavis_apps` ;
- module « Préavis » coupé : rien.

    python -m pytest essai/preavis_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from homeassistant.util import dt as dt_util

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


def _secondes_dans(minutes: float) -> int:
    m = dt_util.now() + __import__("datetime").timedelta(minutes=minutes)
    return (m.hour * 3600 + m.minute * 60 + m.second) % 86400


def _plage(debut_dans: float, duree_min: float = 60) -> dict:
    debut = _secondes_dans(debut_dans)
    return {"id": 1, "name": "Coupure", "start": debut, "end": (debut + int(duree_min * 60)) % 86400,
            "weekdays": [True] * 7, "enabled": True}


async def _monter(hass, hass_storage, regles, **plus):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, **plus, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant",
                         rules=regles),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return next(iter(hass.data[DOMAIN].values()))


async def _apres_rechargement(hass):
    """Le premier relevé déclare l'étiquette « Toujours autorisé » : Vision se recharge ; le coordinateur change."""
    await hass.async_block_till_done()
    return next(iter(hass.data[DOMAIN].values()))


async def _releve(client):
    r = await client.post("/api/pc_parental/poll", json=ENFANT)
    assert r.status == 200
    return await r.json()


def test_seuils_selon_le_delai():
    from custom_components.pc_parental import preavis

    assert preavis.seuils(10) == [10, 5, 1]
    assert preavis.seuils(5) == [5, 1]
    assert preavis.seuils(3) == [3, 1]
    assert preavis.seuils(1) == [1]
    assert preavis.seuils(0) == []
    assert preavis.seuils(60) == [60, 5, 1]
    assert preavis.fenetre_s(10) == 630 and preavis.fenetre_s(0) == 0

    class _S:
        warn_minutes = 999

    assert preavis.delai(_S()) == 60
    _S.warn_minutes = -3
    assert preavis.delai(_S()) == 0


async def test_plage_imminente(hass, hass_storage, hass_client):
    """Réglage par défaut (5 min) : seuils 5 et 1 ; l'ancien `warn` arrive aussi (l'appli le tait alors)."""
    client = await hass_client()
    await _monter(hass, hass_storage, [_plage(4)])
    r = await _releve(client)
    p = r["preavis"]
    assert p["seuils"] == [5, 1] and p["motif"] == "Début d'une plage fermée"
    assert 3 * 60 < p["fin"] - time.time() <= 5 * 60
    assert r["warn"] > 0  # même fenêtre ou plus étroite que le décompte : jamais l'ancien seul quand le décompte existe


async def test_delai_dix_minutes(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, [_plage(7)], warn_minutes=10)
    r = await _releve(client)
    assert r["preavis"]["seuils"] == [10, 5, 1]
    assert 5 * 60 < r["preavis"]["fin"] - time.time() <= 8 * 60


async def test_hors_fenetre_du_delai(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, [_plage(7)])  # défaut 5 min : 7 min, c'est trop tôt
    r = await _releve(client)
    assert "preavis" not in r and not r["warn"]


async def test_delai_court_puis_zero(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, [_plage(2.5)], warn_minutes=3)
    r = await _releve(client)
    assert r["preavis"]["seuils"] == [3, 1] and r["warn"]
    coord = await _apres_rechargement(hass)
    coord.store.warn_minutes = 0
    r = await _releve(client)
    assert "preavis" not in r and "preavis_apps" not in r and not r["warn"]


async def test_rien_d_imminent(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, [_plage(120)])
    assert "preavis" not in await _releve(client)


async def test_fin_du_temps_accorde(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage, [_plage(-30, 120)])  # agenda fermé depuis 30 min, encore 1 h 30
    coord.store.pcs["e1"]["override"] = {"mode": "unlocked", "until": time.time() + 4 * 60}
    r = await _releve(client)
    assert r["preavis"]["motif"] == "Fin du temps accordé"
    assert 3 * 60 < r["preavis"]["fin"] - time.time() <= 4 * 60 + 5


async def test_limite_d_une_appli(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage, [])
    pc = coord.store.pcs["e1"]
    pc["limites"] = {"com.roblox.client": 60}
    jour = dt_util.now().strftime("%Y-%m-%d")
    pc["usage"] = {jour: {"apps": {"com.roblox.client": 55 * 60}}}
    r = await _releve(client)
    assert r["preavis_apps"][0]["nom"] == "com.roblox.client" and 0 < r["preavis_apps"][0]["reste_s"] <= 5 * 60
    assert r["preavis_apps"][0]["seuils"] == [5, 1]
    # Délai 3 min : 5 min restantes, c'est trop tôt pour l'appli.
    coord = await _apres_rechargement(hass)
    coord.store.warn_minutes = 3
    assert "preavis_apps" not in await _releve(client)


async def test_module_coupe(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, [_plage(7)], modules={"preavis": False})
    r = await _releve(client)
    assert "preavis" not in r and "preavis_apps" not in r
