"""Refonte UX, lot 5 : 6 entités par appareil pour les nouvelles installations, l'existant intact (architecture §5).

    python -m pytest essai/entites_reduites_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

VISIBLES = {"etat", "temps_ecran", "acces", "ouvrir30", "suivant", "agent"}


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))


def _par_appareil(hass, nom):
    reg, dreg = er.async_get(hass), dr.async_get(hass)
    sortie = {}
    for e in reg.entities.values():
        dev = dreg.async_get(e.device_id) if e.device_id else None
        if dev is not None and dev.name == nom:
            sortie[e.unique_id[len(DOMAIN) + 1:].removesuffix("_e1")] = e
    return sortie


async def _ajouter_appareil(hass):
    """Un téléphone inscrit après l'installation (comme par l'assistant « Ajouter un appareil »), puis rechargement."""
    coord = _actuel(hass)
    coord.store.pcs["e1"] = _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone")
    await coord.store.async_save()
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    assert await hass.config_entries.async_reload(entree.entry_id)
    await hass.async_block_till_done()


async def test_nouvelle_installation_six_entites(hass, hass_storage):
    from custom_components.pc_parental import modules

    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    flux = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    for saisie in ({"lieu": "Club", "mots": {}}, {"decideurs": []}, {"objectifs": ["horaires", "contenus", "demandes", "ecrans"]},
                   {"appareil": "telephone"}, {}):
        flux = await hass.config_entries.flow.async_configure(flux["flow_id"], saisie)
    await hass.async_block_till_done()
    assert _actuel(hass).store.entites_reduites is True
    await _ajouter_appareil(hass)
    entites = _par_appareil(hass, "TEL-LOU")
    visibles = {k for k, e in entites.items() if e.disabled_by is None and e.hidden_by is None}
    assert visibles == VISIBLES  # exactement les 6
    # Le planning reste lisible (caché, pas éteint) : le tableau et la carte Planning s'en servent.
    assert entites["plages"].disabled_by is None and entites["plages"].hidden_by is er.RegistryEntryHider.INTEGRATION
    assert entites["verrouiller"].disabled_by is er.RegistryEntryDisabler.INTEGRATION
    # Les saisies du hub sont cachées ; « Tout fermer » reste en vue.
    reg = er.async_get(hass)
    hub = {e.unique_id: e for e in reg.entities.values() if e.platform == DOMAIN and e.unique_id.startswith(f"{DOMAIN}_")}
    assert hub[f"{DOMAIN}_pause_generale"].hidden_by is None
    assert hub[f"{DOMAIN}_creer_plage"].hidden_by is er.RegistryEntryHider.INTEGRATION
    # Un module coupé puis rallumé ne rallume pas ce qui a été créé éteint.
    store = _actuel(hass).store
    modules.regler(hass, store, modules.actifs(store) - {"messages"}, "essai")
    modules.appliquer_entites(hass, store)
    modules.regler(hass, store, modules.actifs(store) | {"messages"}, "essai")
    modules.appliquer_entites(hass, store)
    assert reg.async_get(entites["verrouiller"].entity_id).disabled_by is er.RegistryEntryDisabler.INTEGRATION


async def test_installation_existante_intacte(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "pcs": {"e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone")}}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    assert _actuel(hass).store.entites_reduites is False
    entites = _par_appareil(hass, "TEL-LOU")
    assert entites["verrouiller"].disabled_by is None and entites["plages"].hidden_by is None
    assert sum(1 for e in entites.values() if e.disabled_by is None) > 20
