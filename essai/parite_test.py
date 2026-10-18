"""Parité de l'espace gestion de l'appli (1.74.0) : plages, limites, à qui, temps sur plusieurs appareils,
et la fonction ★ « Modifier les règles » exigée en plus de « Gérer » pour ce qui change les règles.

    python -m pytest essai/parite_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

MIA = {"id": "p2", "secret": "b" * 32}


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android"),
            "e2": _fiche("e2", "TAB-LOU", ENFANT2["secret"], person="person.lou", platform="android"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
            "p2": _fiche("p2", "PIXEL-MIA", MIA["secret"], person="person.mia", platform="android", role="parent"),
        }}}
    for p in ("lou", "nolan", "mia"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()


async def _geste(client, qui, **corps):
    r = await client.post("/api/pc_parental/parent", json={**qui, **corps})
    reponse = r.status, await r.json()
    # Une plage créée ou supprimée recharge Vision : on attend la fin avant le geste suivant.
    await client.app["hass"].async_block_till_done(wait_background_tasks=True)
    return reponse


def _coord(hass):
    return next(iter(hass.data[DOMAIN].values()))


async def test_gestes_de_l_appli(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    statut, r = await _geste(client, PARENT, action="plage", pc="e1", op="creer", nom="Devoirs", debut="18:30", fin="20:00",
                             jours=[True] * 5 + [False] * 2)
    assert statut == 200 and "Devoirs" in r["retour"]
    regles = _coord(hass).store.pcs["e1"]["rules"]
    assert regles and regles[0]["start"] == 18 * 3600 + 1800 and regles[0]["weekdays"][5] is False
    # La plage vaut pour toute la personne : la tablette de Lou l'a aussi.
    assert _coord(hass).store.pcs["e2"]["rules"]
    ident = regles[0]["id"]
    statut, r = await _geste(client, PARENT, action="plage", pc="e1", op="modifier", regle=ident, active=False)
    assert statut == 200 and _coord(hass).store.pcs["e1"]["rules"][0]["enabled"] is False
    # Une heure illisible : la phrase revient à l'appli.
    statut, r = await _geste(client, PARENT, action="plage", pc="e1", op="creer", nom="X", debut="bientôt", fin="20:00")
    assert statut == 400 and "18:30" in r["retour"]
    statut, r = await _geste(client, PARENT, action="limite", pc="e1", nom="com.roblox.client", minutes=45)
    assert statut == 200 and "45 min" in r["retour"]
    # Du temps sur les deux appareils de Lou d'un coup.
    statut, r = await _geste(client, PARENT, action="temps", pc="e1", minutes=30, appareils=["e1", "e2"])
    assert statut == 200 and "TEL-LOU" in r["retour"] and "TAB-LOU" in r["retour"]
    # À qui : la tablette devient un écran de toute la maison.
    statut, r = await _geste(client, PARENT, action="partage", pc="e2", tous=True, personnes=[])
    assert statut == 200 and _coord(hass).store.partage_tous(_coord(hass).store.pcs["e2"])
    statut, r = await _geste(client, PARENT, action="plage", pc="e1", op="supprimer", regle=ident)
    assert statut == 200 and not _coord(hass).store.pcs["e1"]["rules"]


async def test_modifier_les_regles_est_a_part(hass, hass_storage, hass_client, caplog):
    await _monter(hass, hass_storage)
    client = await hass_client()
    from custom_components.pc_parental import roles

    coord = _coord(hass)
    ident = roles.creer(hass, coord.store, "Gestion légère", "parent", "essai")
    fonctions = [f for f in coord.store.roles[ident]["fonctions"] if f != "regles"]
    roles.modifier(hass, coord.store, ident, "Gestion légère", fonctions, 60, "essai")
    roles.attribuer_appareil(hass, coord.store, "p2", ident, "essai")
    for geste in ({"action": "plage", "op": "creer", "nom": "X", "debut": "18:00", "fin": "19:00"},
                  {"action": "limite", "nom": "x", "minutes": 10}, {"action": "partage", "tous": True},
                  {"action": "attribuer", "personne": "person.mia", "mode": "principal"}):
        statut, r = await _geste(client, MIA, pc="e1", **geste)
        assert statut == 403 and r["error"] == "regles" and r["retour"], geste
    assert not _coord(hass).store.pcs["e1"]["rules"]
    # Mais elle gère : ouvrir, du temps, un mot.
    assert (await _geste(client, MIA, action="ouvrir", pc="e1", minutes=30))[0] == 200
    assert (await _geste(client, MIA, action="temps", pc="e1", minutes=15))[0] == 200
    assert any("sans la fonction" in m for m in caplog.messages)
