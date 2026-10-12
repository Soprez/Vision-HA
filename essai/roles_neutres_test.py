"""Rôles neutres (Vision 1.80.0, demande du 2026-10-08), dans un vrai Home Assistant.

- les modèles par défaut s'appellent « Décideur » et « Demandeur » (identifiants internes inchangés) ;
- migration une seule fois : « Parent »/« Enfant » renommés, un nom choisi par l'administrateur gardé ;
- chaque personne est rattachée au rôle que portaient ses appareils (avant : « Personnes : aucune »),
  sans qu'aucun appareil ne change de rôle effectif (une télé d'un décideur reste Demandeur) ;
- l'écran « Rôles » : une ligne par rôle (personnes, appareils), un rôle s'ouvre sur ses fonctions à
  cocher, la validation à plusieurs sur un écran à part, le rôle d'une personne en deux écrans.
"""
from __future__ import annotations

from homeassistant.data_entry_flow import FlowResultType

from roles_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ROBIN, _fiche, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)


def _options(r):
    """Les options du premier champ d'un formulaire (sélecteur)."""
    return next(iter(r["data_schema"].schema.values())).config["options"]


def _anciens(noms=("Parent", "Enfant")):
    from custom_components.pc_parental import roles

    r = {i: dict(m, fonctions=list(m["fonctions"])) for i, m in roles.MODELES.items()}
    r["parent"]["nom"], r["enfant"]["nom"] = noms
    return r


def _maison():
    """Comme dans une maison déjà installée : le rôle Décideur posé appareil par appareil, aucune personne rattachée."""
    return {
        "p1": _fiche("p1", "PIXEL-ROBIN", ROBIN["secret"], platform="android", person="person.robin", role="parent"),
        "p2": _fiche("p2", "PC-ROBIN", "b" * 32, person="person.robin", role="parent"),
        "t1": _fiche("t1", "Télé de Robin", "c" * 32, platform="android", type="tv", person="person.robin"),
        "e1": _fiche("e1", "TEL-LOU", "e" * 32, platform="android", person="person.lou"),
    }


async def test_migration_noms_et_personnes(hass, hass_storage):
    from custom_components.pc_parental import roles

    _e, coord = await _monter(hass, hass_storage, {"pcs": _maison(), "roles": _anciens(), "roles_personnes": {},
                                                   "roles_migres": True})
    s = coord.store
    assert s.roles["parent"]["nom"] == "Décideur" and s.roles["enfant"]["nom"] == "Demandeur"
    assert s.roles_personnes == {"person.robin": "parent"}
    # Les surcharges devenues inutiles disparaissent ; la télé garde son rôle effectif (Demandeur).
    assert s.pcs["p1"]["role"] == "" and s.pcs["p2"]["role"] == ""
    assert s.pcs["t1"]["role"] == "enfant"
    assert [roles.role_id(s, s.pcs[i]) for i in ("p1", "p2", "t1", "e1")] == ["parent", "parent", "enfant", "enfant"]
    assert s.vocabulaire_neutre is True


async def test_nom_choisi_par_l_admin_garde_et_une_seule_fois(hass, hass_storage):
    _e, coord = await _monter(hass, hass_storage, {"pcs": _maison(), "roles": _anciens(("Responsable", "Enfant")),
                                                   "roles_personnes": {}, "roles_migres": True,
                                                   "vocabulaire_neutre": False})
    s = coord.store
    assert s.roles["parent"]["nom"] == "Responsable" and s.roles["enfant"]["nom"] == "Demandeur"
    # L'administrateur rappelle un rôle « Enfant » : la migration ne repasse pas.
    s.roles["enfant"]["nom"] = "Enfant"
    await s.async_save()
    await hass.config_entries.async_reload(_e.entry_id)
    await hass.async_block_till_done()
    assert next(iter(hass.data[DOMAIN].values())).store.roles["enfant"]["nom"] == "Enfant"


async def test_installation_neuve(hass, hass_storage):
    _e, coord = await _monter(hass, hass_storage, {"pcs": {}})
    assert [r["nom"] for r in coord.store.roles.values()] == ["Décideur", "Demandeur"]


async def test_ecran_roles(hass, hass_storage):
    _e, coord = await _monter(hass, hass_storage, {"pcs": _maison(), "roles": _anciens(), "roles_personnes": {},
                                                   "roles_migres": True})
    r = await hass.config_entries.options.async_init(_e.entry_id)
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "roles"})
    resume = r["description_placeholders"]["resume"]
    assert "| **Décideur** | 1 | 2 |" in resume and "| **Demandeur** | 0 | 2 |" in resume
    assert "Lou" in resume  # sans rôle choisi : nommée, avec le rôle par défaut
    # Une ligne par rôle ; on l'ouvre : fonctions à cocher, qui l'a.
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "roles_modifier"})
    labels = [o["label"] for o in _options(r)]
    assert labels == ["Décideur · 1 personne · 2 appareils", "Demandeur · aucune personne · 2 appareils"]
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"role": "parent"})
    assert r["step_id"] == "roles_edition"
    assert r["description_placeholders"]["personnes"] == "Robin"
    assert set(r["description_placeholders"]["appareils"].split(", ")) == {"PIXEL-ROBIN", "PC-ROBIN"}
    fonctions = list(coord.store.roles["parent"]["fonctions"])
    r = await hass.config_entries.options.async_configure(r["flow_id"], {
        "nom": "Décideur", "fonctions": fonctions, "pause_max": 60, "validation": True, "supprimer": False})
    assert r["step_id"] == "roles_edition_validation"
    r = await hass.config_entries.options.async_configure(r["flow_id"], {
        "validation_mode": "n", "validation_n": 2, "validation_actions": [], "validation_au_dela_de": 0, "validation_repli": 0})
    assert r["type"] == FlowResultType.CREATE_ENTRY
    assert next(iter(hass.data[DOMAIN].values())).store.roles["parent"]["validation"]["mode"] == "n"


async def test_role_d_une_personne(hass, hass_storage):
    _e, coord = await _monter(hass, hass_storage, {"pcs": _maison(), "roles": _anciens(), "roles_personnes": {},
                                                   "roles_migres": True})
    r = await hass.config_entries.options.async_init(_e.entry_id)
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "roles"})
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "roles_personnes"})
    labels = {o["value"]: o["label"] for o in _options(r)}
    assert labels["person.robin"] == "Robin · Décideur" and labels["person.lou"] == "Lou · Demandeur, par défaut"
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"personne": "person.lou"})
    assert r["step_id"] == "roles_personne_role" and r["description_placeholders"]["appareils"] == "TEL-LOU"
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"role": "enfant"})
    assert r["type"] == FlowResultType.CREATE_ENTRY
    assert next(iter(hass.data[DOMAIN].values())).store.roles_personnes["person.lou"] == "enfant"
