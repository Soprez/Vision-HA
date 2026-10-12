"""Rôles en modèles (Vision 1.71.0), dans un vrai Home Assistant : migrations, garde-fous, écrans.

Ce qu'on vérifie :
- migration depuis la 1.68 (étiquette « Parents », interrupteur global de biométrie) et depuis la
  1.69/1.70 (pc["role"] parent/enfant, pc["biometrie"]) : modèles Parent et Enfant créés, appareils
  Parent gardés en surcharge, biométrie dans le modèle Parent si elle était cochée, ancien
  interrupteur global retiré ; une seule fois ;
- un rôle se crée d'après un modèle, se modifie, s'attribue à une personne (vaut sur ses appareils)
  ou à un appareil (surcharge) ; chaque changement est journalisé ;
- garde-fous : réservé aux administrateurs ; impossible de retirer le dernier rôle qui peut
  « Gérer » et « Approuver » ; un rôle attribué ne se supprime pas ; « Enfant » ne se supprime pas.
"""
from __future__ import annotations

import logging
import pathlib
import shutil
import sys
import tempfile

import pytest
from homeassistant.core import Context
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.exceptions import HomeAssistantError, Unauthorized
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import label_registry as lr
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_capture_events

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-roles-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
ROBIN = {"id": "p1", "secret": "p" * 32}
LOU = {"id": "e1", "secret": "e" * 32}


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    hass.states.async_set("person.robin", "home", {"friendly_name": "Robin"})
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


def _fiche(pc_id, nom, secret, **plus):
    return {"id": pc_id, "name": nom, "host": nom, "secret": secret, "enabled": True, "rules": [],
            "bloques": [], "autorises": [], "override": None, "last_seen": 0, "agent": {},
            "person": "", "update_asked": False, "tags": [], "messages": [], "next_message_id": 1, **plus}


async def _monter(hass, hass_storage, donnees, biometrie_globale=False, etiquette_sur=()):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {"enroll_key": "x" * 16, "enroll_key_v": 2, **donnees}}
    hass_storage[f"{DOMAIN}.deverrouillage"] = {"version": 1, "minor_version": 1, "key": f"{DOMAIN}.deverrouillage",
                                                "data": {"biometrie": biometrie_globale}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    etiquette = lr.async_get(hass).async_create("Parents")
    for pc_id, pc in donnees["pcs"].items():
        a = dr.async_get(hass).async_get_or_create(config_entry_id=entree.entry_id, identifiers={(DOMAIN, f"pc_{pc_id}")}, name=pc["name"])
        if pc_id in etiquette_sur:
            dr.async_get(hass).async_update_device(a.id, labels={etiquette.label_id})
    er.async_get(hass).async_get_or_create("switch", DOMAIN, f"{DOMAIN}_biometrie_parents", config_entry=entree)
    er.async_get(hass).async_get_or_create("switch", DOMAIN, f"{DOMAIN}_biometrie_p1", config_entry=entree)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree, hass.data[DOMAIN][entree.entry_id]


def _pcs():
    return {"p1": _fiche("p1", "PIXEL-ROBIN", ROBIN["secret"], platform="android", person="person.robin"),
            "e1": _fiche("e1", "TEL-LOU", LOU["secret"], platform="android", person="person.lou")}


async def _releve(client, ident):
    return await (await client.post("/api/pc_parental/poll", json=ident)).json()


async def test_migration_depuis_1_68(hass, hass_storage, hass_client, caplog):
    caplog.set_level(logging.WARNING)
    _e, coord = await _monter(hass, hass_storage, {"pcs": _pcs()}, biometrie_globale=True, etiquette_sur=("p1",))
    s = coord.store
    assert set(s.roles) == {"parent", "enfant"} and "biometrie" in s.roles["parent"]["fonctions"]
    # 1.80.0 : la surcharge « parent » de p1 est devenue le rôle de sa personne (roles.neutraliser).
    assert s.pcs["p1"]["role"] == "" and s.pcs["e1"]["role"] == "" and s.roles_personnes == {"person.robin": "parent"}
    assert all("biometrie" not in p for p in s.pcs.values())
    assert hass_storage[f"{DOMAIN}.deverrouillage"]["data"]["biometrie"] is False
    assert er.async_get(hass).async_get_entity_id("switch", DOMAIN, f"{DOMAIN}_biometrie_parents") is None
    assert er.async_get(hass).async_get_entity_id("switch", DOMAIN, f"{DOMAIN}_biometrie_p1") is None
    client = await hass_client()
    robin, lou = await _releve(client, ROBIN), await _releve(client, LOU)
    assert robin["parent"] is True and robin["biometrie"] is True and robin["role"] == "Décideur"
    assert lou["parent"] is False and lou["biometrie"] is False and "demander_deverrouillage" in lou["fonctions"]
    assert "rôles en modèles" in caplog.text


async def test_migration_depuis_1_70_et_une_seule_fois(hass, hass_storage, hass_client):
    pcs = _pcs()
    pcs["p1"].update(role="parent", biometrie=False)
    pcs["e1"].update(role="enfant", biometrie=False)
    entree, coord = await _monter(hass, hass_storage, {"pcs": pcs, "roles_migres": True})
    assert "biometrie" not in coord.store.roles["parent"]["fonctions"]
    assert coord.store.pcs["p1"]["role"] == "" and coord.store.pcs["e1"]["role"] == ""
    assert coord.store.roles_personnes == {"person.robin": "parent"}
    roles = coord.store.roles
    assert await hass.config_entries.async_reload(entree.entry_id)
    await hass.async_block_till_done()
    assert hass.data[DOMAIN][entree.entry_id].store.roles == roles


async def test_creer_attribuer_et_journal(hass, hass_storage, hass_client, caplog):
    caplog.set_level(logging.WARNING)
    _e, coord = await _monter(hass, hass_storage, {"pcs": _pcs()}, etiquette_sur=("p1",))
    from custom_components.pc_parental import roles

    evenements = async_capture_events(hass, f"{DOMAIN}_role")
    s = coord.store
    ado = roles.creer(hass, s, "Ado", "enfant", "Robin")
    roles.modifier(hass, s, ado, "Ado", s.roles[ado]["fonctions"] + ["accueil"], 30, "Robin")
    roles.attribuer_personne(hass, s, "person.lou", ado, "Robin")
    assert roles.nom(s, s.pcs["e1"]) == "Ado" and roles.a(s, s.pcs["e1"], "accueil")
    assert roles.pause_max(s, s.pcs["e1"]) == 30
    with pytest.raises(ValueError):
        roles.supprimer(hass, s, ado, "Robin")  # encore attribué
    with pytest.raises(ValueError):
        roles.supprimer(hass, s, "enfant", "Robin")
    with pytest.raises(ValueError):
        roles.creer(hass, s, "ado", "enfant", "Robin")  # même nom
    await hass.async_block_till_done()
    assert len(evenements) == 3
    assert "rôle « Ado » créé d'après « Demandeur » (par Robin)" in caplog.text
    assert "person.lou : rôle « Ado » (par Robin)" in caplog.text


async def test_dernier_role_d_administration(hass, hass_storage):
    _e, coord = await _monter(hass, hass_storage, {"pcs": _pcs()}, etiquette_sur=("p1",))
    from custom_components.pc_parental import roles

    s = coord.store
    with pytest.raises(ValueError):
        roles.modifier(hass, s, "parent", "Décideur", ["protege", "approuver"], 60, "Robin")  # retire « Gérer »
    with pytest.raises(ValueError):
        roles.attribuer_appareil(hass, s, "p1", "enfant", "Robin")
    # Avec Robin (la personne) en Parent, l'appareil peut perdre sa surcharge.
    roles.attribuer_personne(hass, s, "person.robin", "parent", "Robin")
    roles.attribuer_appareil(hass, s, "p1", "", "Robin")
    assert roles.nom(s, s.pcs["p1"]) == "Décideur"


async def test_reserve_aux_administrateurs_et_ecrans(hass, hass_storage, hass_client):
    entree, coord = await _monter(hass, hass_storage, {"pcs": _pcs()}, etiquette_sur=("p1",))
    role_lou = er.async_get(hass).async_get_entity_id("select", DOMAIN, f"{DOMAIN}_role_e1")
    enfant = await hass.auth.async_create_user("Lou", group_ids=["system-users"])
    with pytest.raises(Unauthorized):
        await hass.services.async_call("select", "select_option", {"entity_id": role_lou, "option": "Décideur"},
                                       blocking=True, context=Context(user_id=enfant.id))
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call("select", "select_option", {"entity_id": role_lou, "option": "Inexistant"}, blocking=True)
    # Vision → Configurer → Rôles : créer « Ado » d'après Enfant, puis cocher « accueil ».
    r = await hass.config_entries.options.async_init(entree.entry_id)
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "roles"})
    assert r["type"] == FlowResultType.MENU and "| **Décideur** | 1 | 1 |" in r["description_placeholders"]["resume"]
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "roles_creer"})
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"nom": "Ado", "modele": "enfant"})
    assert r["step_id"] == "roles_edition"
    fonctions = coord.store.roles[next(i for i, x in coord.store.roles.items() if x["nom"] == "Ado")]["fonctions"]
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"nom": "Ado", "fonctions": fonctions + ["accueil"],
                                                                         "pause_max": 20, "supprimer": False, "validation": True})
    assert r["step_id"] == "roles_edition_validation"
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"validation_mode": "n", "validation_n": 2,
                                                                         "validation_actions": ["app"], "validation_au_dela_de": 0,
                                                                         "validation_repli": 5})
    assert r["type"] == FlowResultType.CREATE_ENTRY
    coord = next(iter(hass.data[DOMAIN].values()))
    ado = next(x for x in coord.store.roles.values() if x["nom"] == "Ado")
    assert "accueil" in ado["fonctions"] and ado["pause_max"] == 20
    assert ado["validation"] == {"mode": "n", "n": 2, "actions": ["app"], "au_dela_de": 0, "repli_minutes": 5}
    # Surcharge par appareil, puis retour au rôle.
    for mode, attendu in (("toutes", "toutes"), ("role", None)):
        r = await hass.config_entries.options.async_init(entree.entry_id)
        r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "roles"})
        r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "roles_validation_appareil"})
        r = await hass.config_entries.options.async_configure(r["flow_id"], {
            "appareil": "e1", "validation_mode": mode, "validation_n": 2, "validation_actions": [],
            "validation_au_dela_de": 0, "validation_repli": 0})
        assert r["type"] == FlowResultType.CREATE_ENTRY
        coord = next(iter(hass.data[DOMAIN].values()))
        assert (coord.store.pcs["e1"].get("validation") or {}).get("mode") == attendu
