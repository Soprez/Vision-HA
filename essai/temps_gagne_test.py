"""Temps gagné (1.89.0, point 14.6) : une tâche cochée donne du temps en plus.

    python -m pytest essai/temps_gagne_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from types import SimpleNamespace

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from homeassistant.core import SupportsResponse
from pytest_homeassistant_custom_component.common import MockConfigEntry


def test_compter():
    from custom_components.pc_parental import temps_gagne as tg

    store = SimpleNamespace()
    tg.regler(store, "person.lou", "todo.lou", 15, 40)
    # Première lecture : ce qui est déjà coché ne compte pas.
    assert tg.compter(store, "person.lou", {"a": "Lit"}, "2026-10-09") == []
    assert tg.compter(store, "person.lou", {"a": "Lit", "b": "Vaisselle"}, "2026-10-09") == [("Vaisselle", 15)]
    # Recochée : rien. Deux nouvelles : la seconde est rognée au plafond (40).
    assert tg.compter(store, "person.lou", {"a": "Lit", "b": "Vaisselle", "c": "Devoirs", "d": "Linge"}, "2026-10-09") == [("Devoirs", 15), ("Linge", 10)]
    assert tg.compter(store, "person.lou", {"e": "Poubelles"}, "2026-10-09") == [("Poubelles", 0)]
    # Le lendemain, le compte repart.
    assert tg.compter(store, "person.lou", {"f": "Chambre"}, "2026-10-10") == [("Chambre", 15)]
    # Réglages refusés.
    for mauvais in (("lou", "todo.x", 15, 60), ("person.lou", "light.x", 15, 60), ("person.lou", "todo.x", 30, 20)):
        try:
            tg.regler(store, *mauvais)
        except ValueError:
            continue
        raise AssertionError(mauvais)
    tg.regler(store, "person.lou", "", 0, 0)
    assert "person.lou" not in store.temps_gagne


async def test_tache_cochee_donne_du_temps(hass, hass_storage):
    from custom_components.pc_parental import temps_gagne as tg

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone"),
            "e2": _fiche("e2", "PC-NOA", ENFANT2["secret"], person="person.noa"),
        }}}
    for p in ("lou", "noa"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    taches = {"a": ("Lit", "completed")}

    async def get_items(call):
        return {"todo.lou": {"items": [{"uid": u, "summary": s, "status": st} for u, (s, st) in taches.items()
                                          if st in (call.data.get("status") or ["completed"])]}}

    hass.services.async_register("todo", "get_items", get_items, supports_response=SupportsResponse.ONLY)
    hass.states.async_set("todo.lou", "1", {"friendly_name": "Tâches de Lou"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    tg.regler(coord.store, "person.lou", "todo.lou", 15, 60)
    tg.async_brancher(hass, coord)
    await hass.async_block_till_done()
    assert coord.store.temps_gagne_suivi["person.lou"]["vus"] == ["a"]
    # Lou coche « Vaisselle » : la liste change d'état, 15 min sur son téléphone, un message.
    taches["b"] = ("Vaisselle", "completed")
    hass.states.async_set("todo.lou", "0", {"friendly_name": "Tâches de Lou"})
    await hass.async_block_till_done()
    lou = coord.store.pcs["e1"]
    assert any("Tâche « Vaisselle » faite : 15 min en plus" in str(m) for m in lou["messages"])
    assert not coord.store.pcs["e2"]["messages"]
    assert coord.store.temps_gagne_suivi["person.lou"]["accorde"] == 15
    tg.async_debrancher(hass)


async def test_parcours_configurer(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone"),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set("todo.lou", "0", {"friendly_name": "Tâches de Lou"})
    hass.services.async_register("todo", "get_items", lambda call: {"todo.lou": {"items": []}}, supports_response=SupportsResponse.ONLY)
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    om = hass.config_entries.options
    r = await om.async_init(entree.entry_id)
    assert "temps_gagne" in r["menu_options"]
    r = await om.async_configure(r["flow_id"], {"next_step_id": "temps_gagne"})
    assert r["type"] == "form" and "Aucune personne" in r["description_placeholders"]["resume"]
    r = await om.async_configure(r["flow_id"], {"personne": "person.lou", "liste": "todo.lou", "minutes": 30, "max": 20})
    assert r["errors"] == {"base": "temps_gagne_minutes"}
    r = await om.async_configure(r["flow_id"], {"personne": "person.lou", "liste": "todo.lou", "minutes": 20, "max": 60})
    assert r["type"] == "abort" and r["reason"] == "temps_gagne_ok"
    assert "Lou : Tâches de Lou, 20 min par tâche, au plus 60 min par jour" in r["description_placeholders"]["resume"]
    await hass.async_block_till_done()
    from custom_components.pc_parental import temps_gagne as tg

    tg.async_debrancher(hass)
