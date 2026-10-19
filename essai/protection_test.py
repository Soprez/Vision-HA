"""2.5.12 (point de sécurité, l'auteur, 18/10) : « Protection désactivée sur <appareil> ».

Sur la TCL du salon, l'accessibilité de Vision était coupée (un force-stop suffit) sans que personne le sache. Le
relevé de l'appareil porte `protections` ; HA montre l'état (capteur « Protection désactivée », classe problème) et
prévient les responsables quand l'accessibilité reste coupée sur l'appareil d'un membre, puis quand elle revient.

    python -m pytest essai/protection_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.helpers import entity_registry as er

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)


async def _releve(client, qui, accessibilite):
    r = await client.post("/api/pc_parental/poll", json={**qui, "platform": "android", "type": "tv",
                                                         "protections": {"accessibilite": accessibilite, "usage": True, "vpn": True}})
    assert r.status == 200


async def test_protection_desactivee_puis_retablie(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import protection

    client = await hass_client()
    await _monter(hass, hass_storage)
    coord = next(iter(hass.data[DOMAIN].values()))
    vus = []
    hass.bus.async_listen(f"{DOMAIN}_protection_desactivee", lambda e: vus.append(("coupee", e.data)))
    hass.bus.async_listen(f"{DOMAIN}_protection_retablie", lambda e: vus.append(("retablie", e.data)))

    def messages():
        return [m["text"] for m in coord.store.pcs["p1"].get("messages") or []]

    notifs = {}
    monkeypatch.setattr(protection.persistent_notification, "async_create",
                        lambda _h, texte, title=None, notification_id=None: notifs.__setitem__(notification_id, title))
    monkeypatch.setattr(protection.persistent_notification, "async_dismiss", lambda _h, ident: notifs.pop(ident, None))

    eid = er.async_get(hass).async_get_entity_id("binary_sensor", DOMAIN, f"{DOMAIN}_protection_e1")
    assert eid, "le capteur « Protection désactivée » existe"
    await _releve(client, ENFANT, True)
    await coord.async_refresh(); await hass.async_block_till_done()
    assert hass.states.get(eid).state == "off"

    # Coupée : pas d'alerte tout de suite (mise à jour, redémarrage de l'appli) ; l'état, si.
    await _releve(client, ENFANT, False)
    await coord.async_refresh(); await hass.async_block_till_done()
    assert hass.states.get(eid).state == "on"
    assert hass.states.get(eid).attributes["manquantes"] == ["accessibilite"]
    assert not vus and not any("Protection désactivée" in t for t in messages())
    # Toujours coupée après le délai : une alerte, une seule.
    monkeypatch.setattr(protection, "DELAI_S", 0)
    await _releve(client, ENFANT, False)
    await hass.async_block_till_done()
    await _releve(client, ENFANT, False)
    await hass.async_block_till_done()
    assert [k for k, _ in vus] == ["coupee"] and vus[0][1]["nom"] == "TEL-LOU"
    assert sum("Protection désactivée sur « TEL-LOU »" in t for t in messages()) == 1
    assert notifs == {"pc_parental_protection_e1": "Protection désactivée"}

    # Rétablie : un message, l'état revient.
    await _releve(client, ENFANT, True)
    await coord.async_refresh(); await hass.async_block_till_done()
    assert [k for k, _ in vus] == ["coupee", "retablie"]
    assert any("Protection rétablie sur « TEL-LOU »" in t for t in messages())
    assert hass.states.get(eid).state == "off"
    assert notifs == {}  # la notification persistante est retirée


async def test_pas_d_alerte_pour_l_appareil_d_un_responsable(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import protection

    client = await hass_client()
    await _monter(hass, hass_storage)
    monkeypatch.setattr(protection, "DELAI_S", 0)
    vus = []
    hass.bus.async_listen(f"{DOMAIN}_protection_desactivee", lambda e: vus.append(e.data))
    await _releve(client, PARENT, False)
    await _releve(client, PARENT, False)
    await hass.async_block_till_done()
    assert vus == []
