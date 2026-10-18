"""Profils (1.73.0) : le temps du jour cumulé par personne, pour la gestion et pour la personne elle-même.

    python -m pytest essai/profils_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.util import dt as dt_util
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

PC_LOU = {"id": "w1", "secret": "w" * 32}
TELE = {"id": "t1", "secret": "t" * 32}


async def _monter(hass, hass_storage):
    jour = dt_util.now().strftime("%Y-%m-%d")

    def usage(minutes):
        return {"usage": {jour: {"actif": minutes * 60, "ouvert": minutes * 90}}}

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", **usage(40)),
            "w1": _fiche("w1", "PC-LOU", PC_LOU["secret"], person="person.lou", **usage(75)),
            "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android", **usage(10)),
            "t1": _fiche("t1", "TÉLÉ", TELE["secret"], person="person.lou", platform="android",
                         partage=["*"], **usage(120)),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android",
                         role="parent", **usage(5)),
        }}}
    for p in ("lou", "noa", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()


async def test_temps_cumule_par_personne(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    r = await (await client.post("/api/pc_parental/parent", json=PARENT)).json()
    personnes = {p["cle"]: p for p in r["personnes"]}
    # Lou : téléphone + PC ; la télé partagée ne compte pas (on ne sait pas qui regardait).
    assert personnes["lou"]["temps"]["actif"] == 115
    assert [a["nom"] for a in personnes["lou"]["temps"]["appareils"]] == ["PC-LOU", "TEL-LOU"]
    assert personnes["noa"]["temps"]["actif"] == 10
    assert personnes["lou"]["role"] == "Demandeur"


async def test_la_personne_voit_son_total(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    releve = await (await client.post("/api/pc_parental/poll", json={**ENFANT, "platform": "android", "type": "telephone"})).json()
    assert releve["temps_personne"]["actif"] == 115
    # Noa ne voit que le sien.
    releve = await (await client.post("/api/pc_parental/poll", json={**ENFANT2, "platform": "android", "type": "telephone"})).json()
    assert releve["temps_personne"]["actif"] == 10 and len(releve["temps_personne"]["appareils"]) == 1
    # Sans la fonction « temps » dans son rôle : rien.
    coord = next(iter(hass.data[DOMAIN].values()))
    coord.store.roles["enfant"]["fonctions"].remove("temps")
    releve = await (await client.post("/api/pc_parental/poll", json={**ENFANT2, "platform": "android", "type": "telephone"})).json()
    assert "temps_personne" not in releve
