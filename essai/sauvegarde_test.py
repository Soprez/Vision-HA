"""Sauvegarde et restauration de la configuration Vision (1.87.0, point 14.3).

    python -m pytest essai/sauvegarde_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import os

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


async def _installer(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "lieu": "Maison des essais",
        "roles_personnes": {"person.nolan": "parent"}, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone"),
            "e2": _fiche("e2", "PC-NOA", ENFANT2["secret"], person="person.noa"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}}
    for p in ("lou", "noa", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree


async def test_sauver_lister_elaguer(hass, hass_storage, tmp_path):
    from custom_components.pc_parental import sauvegarde

    hass.config.config_dir = str(tmp_path)
    entree = await _installer(hass, hass_storage)
    coord = hass.data[DOMAIN][entree.entry_id]
    e = await sauvegarde.async_sauver(hass, coord, "manuelle")
    assert e["resume"] == {"lieu": "Maison des essais", "appareils": 3, "personnes": 3, "roles": e["resume"]["roles"]}
    chemin = os.path.join(sauvegarde.dossier(hass), e["nom"])
    contenu = json.loads(open(chemin, encoding="utf-8").read())
    assert contenu["format"] == "vision-sauvegarde" and set(contenu["donnees"]["pcs"]) == {"e1", "e2", "p1"}
    assert "3 appareils, 3 personnes" in sauvegarde.libelle(e) and "manuelle" in sauvegarde.libelle(e)
    # Les automatiques : 7 gardées, les plus récentes ; les manuelles ne sont pas touchées.
    rep = sauvegarde.dossier(hass)
    for jour in range(1, 11):
        nom = f"vision-202610{jour:02d}-033000-automatique.json"
        with open(os.path.join(rep, nom), "w", encoding="utf-8") as f:
            json.dump({"format": "vision-sauvegarde", "date": f"2026-10-{jour:02d}T03:30:00+02:00", "resume": {}}, f)
    await hass.async_add_executor_job(sauvegarde._elaguer, rep)
    liste = await sauvegarde.async_lister(hass)
    autos = [x["nom"] for x in liste if x["motif"] == "automatique"]
    assert len(autos) == 7 and autos[0].startswith("vision-20261010") and autos[-1].startswith("vision-20261004")
    assert any(x["nom"] == e["nom"] for x in liste)


async def test_restaurer_et_revenir(hass, hass_storage, tmp_path):
    from custom_components.pc_parental import sauvegarde

    hass.config.config_dir = str(tmp_path)
    entree = await _installer(hass, hass_storage)
    coord = hass.data[DOMAIN][entree.entry_id]
    e = await sauvegarde.async_sauver(hass, coord, "manuelle")
    # Changements après la sauvegarde : un appareil retiré, le lieu renommé.
    coord.store.pcs.pop("e2")
    coord.store.lieu = "Chalet"
    await coord.store.async_save()
    avant = await sauvegarde.async_restaurer(hass, entree.entry_id, e["nom"])
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    assert set(coord.store.pcs) == {"e1", "e2", "p1"} and coord.store.lieu == "Maison des essais"
    # L'état d'avant est gardé, et on peut y revenir.
    assert avant["motif"] == "avant" and avant["resume"]["appareils"] == 2 and avant["resume"]["lieu"] == "Chalet"
    await sauvegarde.async_restaurer(hass, entree.entry_id, avant["nom"])
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    assert set(coord.store.pcs) == {"e1", "p1"} and coord.store.lieu == "Chalet"


async def test_refus_fichier_douteux(hass, hass_storage, tmp_path):
    from custom_components.pc_parental import sauvegarde

    hass.config.config_dir = str(tmp_path)
    entree = await _installer(hass, hass_storage)
    rep = sauvegarde.dossier(hass)
    os.makedirs(rep, exist_ok=True)
    nom = "vision-20261009-100000-manuelle.json"
    with open(os.path.join(rep, nom), "w", encoding="utf-8") as f:
        json.dump({"format": "vision-sauvegarde", "format_version": 99, "donnees": {"pcs": {}}}, f)
    for mauvais in (nom, "../../.storage/pc_parental", "vision-x.json"):
        try:
            await sauvegarde.async_restaurer(hass, entree.entry_id, mauvais)
        except ValueError:
            pass
        else:
            raise AssertionError(f"{mauvais} accepté")
    assert set(hass.data[DOMAIN][entree.entry_id].store.pcs) == {"e1", "e2", "p1"}


async def test_parcours_configurer(hass, hass_storage, tmp_path):
    """Configurer → Avancé → Sauvegarde : sauvegarder, puis restaurer (la case est exigée)."""
    hass.config.config_dir = str(tmp_path)
    entree = await _installer(hass, hass_storage)
    om = hass.config_entries.options
    r = await om.async_init(entree.entry_id)
    r = await om.async_configure(r["flow_id"], {"next_step_id": "reparer"})
    r = await om.async_configure(r["flow_id"], {"next_step_id": "sauvegarde"})
    assert r["type"] == "menu" and "Aucune sauvegarde" in r["description_placeholders"]["liste"]
    r = await om.async_configure(r["flow_id"], {"next_step_id": "sauvegarde_creer"})
    r = await om.async_configure(r["flow_id"], {})
    assert r["type"] == "abort" and r["reason"] == "sauvegarde_faite"

    r = await om.async_init(entree.entry_id)
    r = await om.async_configure(r["flow_id"], {"next_step_id": "reparer"})
    r = await om.async_configure(r["flow_id"], {"next_step_id": "sauvegarde"})
    r = await om.async_configure(r["flow_id"], {"next_step_id": "sauvegarde_restaurer"})
    assert r["type"] == "form"
    nom = r["data_schema"].schema
    choix = next(k for k in nom if str(k) == "sauvegarde").default()
    r = await om.async_configure(r["flow_id"], {"sauvegarde": choix, "confirmer": False})
    assert r["type"] == "form" and r["errors"] == {"confirmer": "confirmer_restauration"}
    r = await om.async_configure(r["flow_id"], {"sauvegarde": choix, "confirmer": True})
    assert r["type"] == "abort" and r["reason"] == "sauvegarde_restauree"
    await hass.async_block_till_done()
    assert set(hass.data[DOMAIN][entree.entry_id].store.pcs) == {"e1", "e2", "p1"}
