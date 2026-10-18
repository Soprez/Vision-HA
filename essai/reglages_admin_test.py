"""Refonte UX, lot 5 : Réglages › « Réservé aux Décideurs » (API reglages/admin, carte vision-reglages-card).

    python -m pytest essai/reglages_admin_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

URL = "/api/pc_parental/reglages/admin"


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone"),
            "p1": _fiche("p1", "PIXEL-ALEX", PARENT["secret"], person="person.alex", platform="android", role="parent"),
        }}}
    for p in ("lou", "alex"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))


async def test_etat_et_fonctions(hass, hass_storage, hass_client):
    from custom_components.pc_parental import assistant, modules

    await _monter(hass, hass_storage)
    admin = await hass_client()
    d = await (await admin.get(URL)).json()
    # Les fonctions rangées par objectif, avec ce dont chacune a besoin, dit avant de basculer.
    assert [g["id"] for g in d["fonctions"]["groupes"]] == list(assistant.GROUPES)
    demandes = next(m for g in d["fonctions"]["groupes"] for m in g["modules"] if m["id"] == "demandes")
    assert demandes["requiert_un"] and demandes["actif"] is True
    assert {m["id"] for m in d["fonctions"]["autres"]} == {"optimisations", "reveil", "son"}
    assert d["roles"]["liste"] and any(f["sensible"] for f in d["roles"]["fonctions"])
    # Couper une fonction : enregistré, et le tableau de bord suivra.
    r = await admin.post(URL, json={"op": "module", "module": "bilan", "actif": False})
    assert r.status == 200 and (await r.json())["fait"]
    await hass.async_block_till_done()
    assert "bilan" not in modules.actifs(_actuel(hass).store)
    # Une fonction dont une autre dépend : refusée en clair.
    r = await admin.post(URL, json={"op": "module", "module": "temps", "actif": False})
    assert r.status == 400 and "Temps" in (await r.json())["retour"]


async def test_droits(hass, hass_storage, hass_client):
    from custom_components.pc_parental import roles

    await _monter(hass, hass_storage)
    admin = await hass_client()
    r = await admin.post(URL, json={"op": "droit", "role": roles.ENFANT, "fonction": "messages", "actif": False})
    assert r.status == 200
    assert "messages" not in _actuel(hass).store.roles[roles.ENFANT]["fonctions"]
    r = await admin.post(URL, json={"op": "droit", "role": roles.ENFANT, "fonction": "messages", "actif": True})
    assert "messages" in _actuel(hass).store.roles[roles.ENFANT]["fonctions"]
    # Plus personne pour gérer : refusé, rien ne change.
    r = await admin.post(URL, json={"op": "droit", "role": roles.PARENT, "fonction": "gerer", "actif": False})
    assert r.status == 400 and "Gérer" in (await r.json())["retour"]
    assert "gerer" in _actuel(hass).store.roles[roles.PARENT]["fonctions"]
    r = await admin.post(URL, json={"op": "nimporte"})
    assert r.status == 400


async def test_reserve_aux_admins(hass, hass_storage, hass_client, hass_read_only_access_token):
    await _monter(hass, hass_storage)
    client = await hass_client(hass_read_only_access_token)
    assert (await client.get(URL)).status == 403
    assert (await client.post(URL, json={"op": "lieu", "lieu": "X"})).status == 403
