"""Chantier « Générique » : ce qui était propre à une maison devient un réglage ou se découvre.

Écrans communs choisis (ou la personne
« Affichage »), appareils d'essai par identifiant, réseaux privés au complet, adresses de ce Home Assistant
jamais bloquées par la liste noire, réglages généraux enregistrés.

    python -m pytest essai/generique_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import ipaddress

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from party_test import _monter


async def test_ecran_commun(hass, hass_storage):
    from custom_components.pc_parental import veille_tableaux

    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    hass.states.async_set("person.affichage", "home", {"friendly_name": "Affichage"})
    hass.states.async_set("person.salon", "home", {"friendly_name": "Télé du salon"})
    # Rien de choisi : la personne qui s'appelle « Affichage », comme avant.
    assert store.est_ecran_commun("person.affichage") and not store.est_ecran_commun("person.salon")
    assert not store.est_ecran_commun("person.lou") and not store.est_ecran_commun("")
    store.pcs["e1"]["person"] = "person.affichage"
    assert veille_tableaux.profil(hass, coord, store.pcs["e1"])["public"] == "affichage"
    # Choisies : elles seules.
    store.personnes_affichage = ["person.salon"]
    assert store.est_ecran_commun("person.salon") and not store.est_ecran_commun("person.affichage")
    assert veille_tableaux.profil(hass, coord, store.pcs["e1"])["public"] == "enfant"
    store.pcs["e1"]["person"] = "person.salon"
    assert veille_tableaux.profil(hass, coord, store.pcs["e1"])["public"] == "affichage"
    assert "person.salon" not in store.personnes_maison()


def test_reseaux_prives():
    from custom_components.pc_parental.http import adresse_locale

    for a in ("10.1.1.10", "10.1.2.3", "172.16.0.5", "172.31.255.254", "169.254.3.4",
              "fd12:3456::1", "fe80::1", "::ffff:10.1.1.10"):
        assert adresse_locale(ipaddress.ip_address(a)), a
    for a in ("172.32.0.1", "8.8.8.8", "100.64.0.1", "127.0.0.1", "::1", "2a01:e34::1", "::ffff:8.8.8.8"):
        assert not adresse_locale(ipaddress.ip_address(a)), a


async def test_liste_noire_epargne_ce_home_assistant(hass):
    from custom_components.pc_parental import listenoire

    await hass.config.async_update(external_url="https://maison.exemple.org:8123", internal_url="http://10.1.1.10:8123")
    hotes = listenoire.hotes_home_assistant(hass)
    assert hotes == {"maison.exemple.org"}
    epargnes = listenoire.JAMAIS | hotes
    assert listenoire._domaine("0.0.0.0 maison.exemple.org", epargnes) == ""
    assert listenoire._domaine("||www.maison.exemple.org^", epargnes) == ""
    # Le reste du domaine parent n'est plus épargné d'office (avant : tout exemple.org).
    assert listenoire._domaine("0.0.0.0 autre.exemple.org", epargnes) == "autre.exemple.org"
    assert listenoire._domaine("0.0.0.0 piege.exemple.org", epargnes) == "piege.exemple.org"
    assert listenoire._domaine("0.0.0.0 x.ui.nabu.casa", epargnes) == ""
    await hass.config.async_update(external_url=None, internal_url=None)
    assert listenoire.hotes_home_assistant(hass) == set()


def test_essai_par_identifiant():
    from custom_components.pc_parental import android_maj

    avant = android_maj._ETAT["essai"]
    try:
        android_maj._ETAT["essai"] = {"version": "1.90.0", "sha256": "0" * 64, "appareils": {"télé du bureau"}}
        assert android_maj.infos_pour(None, "Télé du bureau", "1.89.0")["version"] == "1.90.0"
        assert android_maj.infos_pour(None, "Pixel", "1.89.0", "a1b2", ["a1b2"])["version"] == "1.90.0"
        assert android_maj.infos_pour(None, "Pixel", "1.89.0", "a1b2", ["zz"]).get("version") != "1.90.0"
        assert android_maj.infos_pour(None, "Pixel", "1.89.0", "", [""]).get("version") != "1.90.0"
    finally:
        android_maj._ETAT["essai"] = avant


async def test_reglages_generaux(hass, hass_storage):
    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    hass.states.async_set("person.salon", "home", {"friendly_name": "Télé du salon"})
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "autres"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "general"})
    assert flux["step_id"] == "general"
    # EX-06 : les appareils Android d'essai ne sont plus dans Réglages généraux, mais sous Avancé.
    assert "appareils_essai" not in {str(k) for k in flux["data_schema"].schema}
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"personnes_affichage": ["person.salon"]})
    await hass.async_block_till_done()
    assert store.personnes_affichage == ["person.salon"]
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "autres"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "appareils_essai"})
    assert flux["step_id"] == "appareils_essai"
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"appareils_essai": ["t1"]})
    await hass.async_block_till_done()
    assert (store.personnes_affichage, store.appareils_essai) == (["person.salon"], ["t1"])
    assert hass_storage[DOMAIN]["data"]["appareils_essai"] == ["t1"]
    # Tout vider : retour à la découverte.
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "autres"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "general"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"personnes_affichage": []})
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "autres"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "appareils_essai"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"appareils_essai": []})
    await hass.async_block_till_done()
    assert (store.personnes_affichage, store.appareils_essai) == ([], [])
