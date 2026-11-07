"""1.94 (point 6) : « Créer le tableau de bord Vision ? » à l'installation, et dans Configurer.

- l'assistant pose la question juste après le lieu : Oui par défaut, Non (« je fais le mien ») gardé dans l'entrée ;
- Non : Vision ne remplit ni ne régénère aucun tableau ; les liens « Où décider » mènent au panneau, pas à un tableau absent ;
- installation d'avant la 1.94 : avec un tableau Vision déjà là, la réponse est Oui ; sans tableau, la question est posée une
  fois dans Configurer (jamais un tableau créé sans l'avoir demandé) ;
- Configurer › Réparer : « Créer le tableau maintenant » et « Ne plus gérer le tableau » (le tableau existant est laissé tel quel).

    python -m pytest essai/tableau_optionnel_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from assistant_test import _actuel, _jusqu_au_resume, _personnes  # noqa: F401  (banc de l'assistant)
from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

RACINE = Path(__file__).resolve().parents[1] / "custom_components" / "pc_parental"
LANGUES = ("fr", "en", "de", "es", "it", "nl")


@pytest.fixture(autouse=True)
def _tableau_par_defaut():
    """L'état « il y a un tableau » est global au processus (une seule installation) : on le rend à chaque essai."""
    yield
    from custom_components.pc_parental import textes

    textes.poser_tableau(True)


def _mods():
    from custom_components.pc_parental import dashboard, textes

    return dashboard, textes


async def test_la_question_est_posee_et_oui_est_la_reponse_par_defaut(hass, hass_storage):
    _personnes(hass)
    flux = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"lieu": "École", "mots": {}})
    assert flux["step_id"] == "tableau" and flux["last_step"] is False
    assert flux["data_schema"].schema and next(iter(flux["data_schema"].schema)).default() is True
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"tableau": True})
    assert flux["step_id"] == "personnes"


async def test_oui_cree_l_entree_avec_le_tableau_voulu(hass, hass_storage):
    _personnes(hass)
    flux = await _jusqu_au_resume(hass, tableau=True)
    assert "/vision-maison" in flux["description_placeholders"]["ligne_tableau"]
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    assert flux["result"].data["tableau_voulu"] is True


async def test_non_est_garde_et_le_resume_le_dit(hass, hass_storage):
    _personnes(hass)
    flux = await _jusqu_au_resume(hass, tableau=False)
    ligne = flux["description_placeholders"]["ligne_tableau"]
    assert "/vision-maison" not in ligne and "panneau" in ligne.lower()
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    assert flux["result"].data["tableau_voulu"] is False
    await hass.async_block_till_done()
    dashboard, _ = _mods()
    assert dashboard.tableau_voulu(hass) is False


async def test_non_aucun_tableau_rempli_ni_regenere(hass, hass_storage):
    _personnes(hass)
    flux = await _jusqu_au_resume(hass, tableau=False)
    await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    await hass.async_block_till_done()
    dashboard, _ = _mods()
    coord = _actuel(hass)
    assert await dashboard.async_remplir_si_vide(hass, coord) is False
    assert await dashboard.async_build(hass, coord, sauvegarder=True) is False
    assert await coord.async_maj_tableau(force=True) is False


async def test_non_les_liens_menent_au_panneau(hass, hass_storage):
    _, textes = _mods()
    try:
        textes.poser_tableau(True)
        avec = textes.ou_decider("fr")
        assert "/vision-maison/" in avec and "tableau de bord" in avec
        textes.poser_tableau(False)
        sans = textes.ou_decider("fr")
        assert "/vision-appairage" in sans and "/vision-maison" not in sans and "panneau" in sans
        assert textes.chemin_tableau() == "/vision-appairage"
        for lg in LANGUES:
            assert "vision-maison" not in textes.ou_decider(lg)
    finally:
        textes.poser_tableau(True)


async def test_installation_d_avant_avec_tableau_existant_vaut_oui(hass, hass_storage):
    # Une entrée d'avant la 1.94 : aucune clé « tableau_voulu » ; un tableau Vision existe déjà.
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    dashboard, _ = _mods()
    assert dashboard.tableau_voulu(hass, entree) is None            # ni choix ni tableau : rien n'est créé tant qu'on ne l'a pas demandé
    hass.data["lovelace"] = type("L", (), {"dashboards": {dashboard.URL_PATH: object()}})()
    assert dashboard.tableau_voulu(hass, entree) is True            # un tableau Vision est déjà là : il lui appartient
    hass.config_entries.async_update_entry(entree, data={"tableau_voulu": False})
    assert dashboard.tableau_voulu(hass, entree) is False           # le choix gardé l'emporte


async def test_configurer_pose_la_question_une_fois_puis_menu(hass, hass_storage):
    _personnes(hass)
    flux = await _jusqu_au_resume(hass, tableau=True)
    await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    await hass.async_block_till_done()
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    # Une installation d'avant : le choix n'est pas gardé.
    donnees = {k: v for k, v in entree.data.items() if k != "tableau_voulu"}
    hass.config_entries.async_update_entry(entree, data=donnees)
    dashboard, _ = _mods()
    assert dashboard.tableau_voulu(hass, entree) is None
    options = await hass.config_entries.options.async_init(entree.entry_id)
    assert options["step_id"] == "tableau_question"
    options = await hass.config_entries.options.async_configure(options["flow_id"], {"tableau": False})
    assert options["type"] == "menu" and options["step_id"] == "init"           # le menu, une fois la réponse donnée
    assert hass.config_entries.async_entries(DOMAIN)[0].data["tableau_voulu"] is False
    # La question ne revient pas.
    options = await hass.config_entries.options.async_init(entree.entry_id)
    assert options["type"] == "menu" and options["step_id"] == "init"


async def test_configurer_lacher_puis_creer(hass, hass_storage):
    _, textes = _mods()
    _personnes(hass)
    try:
        flux = await _jusqu_au_resume(hass, tableau=True)
        await hass.config_entries.flow.async_configure(flux["flow_id"], {})
        await hass.async_block_till_done()
        entree = hass.config_entries.async_entries(DOMAIN)[0]
        options = await hass.config_entries.options.async_init(entree.entry_id)
        assert options["type"] == "menu"
        options = await hass.config_entries.options.async_configure(options["flow_id"], {"next_step_id": "reparer"})
        assert {"tableau_creer", "tableau_lacher"} <= set(options["menu_options"])
        # Ne plus gérer : la réponse devient « non », les liens passent au panneau.
        options = await hass.config_entries.options.async_configure(options["flow_id"], {"next_step_id": "tableau_lacher"})
        assert options["step_id"] == "tableau_lacher"
        options = await hass.config_entries.options.async_configure(options["flow_id"], {})
        assert options["type"] == "abort" and options["reason"] == "tableau_lache"
        assert hass.config_entries.async_entries(DOMAIN)[0].data["tableau_voulu"] is False
        assert textes.chemin_tableau() == "/vision-appairage"
        # Créer maintenant : « oui » ; sans tableau Lovelace à remplir, on dit comment le créer.
        options = await hass.config_entries.options.async_init(entree.entry_id)
        options = await hass.config_entries.options.async_configure(options["flow_id"], {"next_step_id": "reparer"})
        options = await hass.config_entries.options.async_configure(options["flow_id"], {"next_step_id": "tableau_creer"})
        options = await hass.config_entries.options.async_configure(options["flow_id"], {})
        assert options["type"] == "abort" and options["reason"] in ("tableau_a_creer", "tableau_cree")
        assert hass.config_entries.async_entries(DOMAIN)[0].data["tableau_voulu"] is True
    finally:
        textes.poser_tableau(True)


def test_textes_du_choix_en_six_langues():
    for lg in LANGUES:
        d = json.loads((RACINE / "translations" / f"{lg}.json").read_text(encoding="utf-8"))
        assert d["config"]["step"]["tableau"]["title"] and d["config"]["step"]["tableau"]["description"]
        assert d["config"]["step"]["tableau"]["data"]["tableau"]
        assert "{ligne_tableau}" in d["config"]["step"]["pret"]["description"] and "{tableau}" not in d["config"]["step"]["pret"]["description"]
        o = d["options"]
        for cle in ("tableau_question", "tableau_creer", "tableau_lacher"):
            assert o["step"][cle]["title"] and o["step"][cle]["description"], (lg, cle)
        assert {"tableau_creer", "tableau_lacher"} <= set(o["step"]["reparer"]["menu_options"])
        assert {"tableau_cree", "tableau_lache", "tableau_a_creer"} <= set(o["abort"])
        assert "{titre}" in o["abort"]["tableau_a_creer"] and "{url}" in o["abort"]["tableau_a_creer"]
        cfg = json.loads((RACINE / "i18n" / "config" / f"{lg}.json").read_text(encoding="utf-8"))
        assert "{tableau}" in cfg["assistant_tableau_oui"] and cfg["assistant_tableau_non"]
        srv = json.loads((RACINE / "i18n" / "serveur" / f"{lg}.json").read_text(encoding="utf-8"))
        assert "{produit}" in srv["notif.lien_panneau"]
