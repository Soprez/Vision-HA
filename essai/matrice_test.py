"""Matrice réglages × type d'appareil (1.72.0) : les entités sans objet sont désactivées, pas supprimées.

    python -m pytest essai/matrice_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

PC = {"id": "w1", "secret": "w" * 32}


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
            "w1": _fiche("w1", "PC-LOU", PC["secret"], person="person.lou"),
        }}}
    for p in ("lou", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()


def _desactivee(hass, domaine, unique_id):
    reg = er.async_get(hass)
    entree = reg.async_get(reg.async_get_entity_id(domaine, DOMAIN, f"{DOMAIN}_{unique_id}"))
    assert entree is not None  # jamais supprimée : l'historique reste
    return entree.disabled_by


async def test_desactivees_selon_le_type(hass, hass_storage, hass_client, caplog):
    client = await hass_client()
    await _monter(hass, hass_storage)
    # Avant le premier relevé : on ne touche à rien.
    assert _desactivee(hass, "number", "pause_max_w1") is None
    assert _desactivee(hass, "binary_sensor", "compte_admin_e1") is None

    async def releve(ident, **corps):
        r = await client.post("/api/pc_parental/poll", json={**ident, "version": "1", "host": "x", **corps})
        assert r.status == 200
        await hass.async_block_till_done()

    await releve(PC)
    await releve(ENFANT, platform="android", type="telephone")
    integration = er.RegistryEntryDisabler.INTEGRATION
    assert _desactivee(hass, "number", "pause_max_w1") is integration
    assert _desactivee(hass, "number", "pause_max_e1") is None
    assert _desactivee(hass, "binary_sensor", "compte_admin_e1") is integration
    assert _desactivee(hass, "binary_sensor", "compte_admin_w1") is None
    assert _desactivee(hass, "sensor", "session_e1") is None
    assert any("historique gardé" in m for m in caplog.messages)
    # Le même appareil devient une télé : la session s'éteint, le reste ne bouge pas.
    await releve(ENFANT, platform="android", type="tv")
    assert _desactivee(hass, "sensor", "session_e1") is integration
    assert _desactivee(hass, "binary_sensor", "compte_admin_e1") is integration
    # Une ancienne appli (sans type) : « android », la session revient.
    await releve(ENFANT, platform="android")
    assert _desactivee(hass, "sensor", "session_e1") is None


async def test_un_admin_qui_desactive_lui_meme_n_est_pas_contredit(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    reg = er.async_get(hass)
    session = reg.async_get_entity_id("sensor", DOMAIN, f"{DOMAIN}_session_e1")
    reg.async_update_entity(session, disabled_by=er.RegistryEntryDisabler.USER)
    await client.post("/api/pc_parental/poll", json={**ENFANT, "platform": "android", "type": "telephone",
                                                     "version": "1", "host": "x"})
    await hass.async_block_till_done()
    assert reg.async_get(session).disabled_by is er.RegistryEntryDisabler.USER
