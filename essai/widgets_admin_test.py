"""Onglet « Widgets HA » (1.94.0, point 16.1) : l'API de la carte, réservée aux administrateurs.

    python -m pytest essai/widgets_admin_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

URL = "/api/pc_parental/widgets/admin"


async def _installer(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone"),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set("sensor.salon", "21.5", {"friendly_name": "Température salon", "unit_of_measurement": "°C"})
    hass.states.async_set("light.salon", "on", {"friendly_name": "Lampe du salon"})
    hass.states.async_set("lock.porte", "locked", {"friendly_name": "Serrure"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def test_lecture_et_reglages(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    r = await client.get(URL)
    assert r.status == 200
    d = await r.json()
    assert [p["prenom"] for p in d["personnes"]] == ["Lou"] and d["module"] is True
    ids = {c["id"] for c in d["candidates"]}
    assert {"sensor.salon", "light.salon"} <= ids and "lock.porte" not in ids and "person.lou" not in ids
    # Ajout à la personne, puis la lampe devient pilotable.
    r = await client.post(URL, json={"op": "personne", "personne": "person.lou", "entites": ["sensor.salon", "light.salon"]})
    d = await r.json()
    lou = d["personnes"][0]["entites"]
    assert [f["id"] for f in lou] == ["sensor.salon", "light.salon"]
    assert lou[0]["valeur"] == "21.5 °C" and d["personnes"][0]["entites"][1]["valeur"] == "allumé" and lou[1]["pilotable"] is True and lou[1]["permises"] == []
    r = await client.post(URL, json={"op": "actions", "entite": "light.salon", "services": lou[1]["proposees"]})
    d = await r.json()
    assert d["personnes"][0]["entites"][1]["permises"] == lou[1]["proposees"]
    assert coord.store.entites_actions["light.salon"] == lou[1]["proposees"]
    # Exception d'appareil : la température en moins sur le téléphone de Lou.
    r = await client.post(URL, json={"op": "appareil", "pc": "e1", "plus": [], "moins": ["sensor.salon"]})
    d = await r.json()
    assert d["appareils"][0]["moins"][0]["id"] == "sensor.salon"
    assert coord.store.entites_appareils["e1"] == {"plus": [], "moins": ["sensor.salon"]}
    # Une serrure ne se pilote jamais ; une demande fausse est refusée en clair.
    r = await client.post(URL, json={"op": "actions", "entite": "lock.porte", "services": ["unlock"]})
    assert r.status == 400 and "pilote pas" in (await r.json())["retour"]


async def test_reserve_aux_admins(hass, hass_storage, hass_client, hass_read_only_access_token):
    await _installer(hass, hass_storage)
    client = await hass_client(hass_read_only_access_token)
    r = await client.get(URL)
    assert r.status == 403
    r = await client.post(URL, json={"op": "personne", "personne": "person.lou", "entites": ["sensor.salon"]})
    assert r.status == 403


async def test_onglet_au_tableau(hass, hass_storage):
    from custom_components.pc_parental import dashboard, modules

    coord = await _installer(hass, hass_storage)
    vues = dashboard.build(hass, coord)["views"]
    widgets = next(v for v in vues if v.get("path") == "widgets")
    assert widgets["title"] == "Widgets HA" and widgets["sections"][0]["cards"][0]["type"] == "custom:vision-widgets-card"
    modules.regler(hass, coord.store, set(modules.TOUS) - {"entites", "veille_tableaux"}, "essai")
    assert all(v.get("path") != "widgets" for v in dashboard.build(hass, coord)["views"])
