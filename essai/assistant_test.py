"""Refonte UX, lot 4 : l'assistant d'installation en 5 étapes et Configurer réduit (EX-01 à EX-04, EX-07, HA-09, HA-10).

- l'ajout : lieu et mots, personnes (qui décide), objectifs (dépendances dites en clair), premier appareil, résumé ;
  « Suivant » jusqu'au résumé ; les choix posés une fois au premier démarrage ;
- « Relancer l'assistant » sans surprise : un objectif décoché coupe ses modules, un objectif coché allume ses
  modules recommandés, un objectif inchangé garde les siens ; une personne décochée reprend le rôle par défaut ;
- Configurer : relancer l'assistant, ajouter un appareil, réparer ; tous les autres réglages sous « Autres réglages ».

    python -m pytest essai/assistant_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

RACINE = Path(__file__).resolve().parents[1] / "custom_components" / "pc_parental"
LANGUES = ("fr", "en", "de", "es", "it", "nl")


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))


def _personnes(hass):
    for p, nom in (("person.alex", "Alex"), ("person.lou", "Lou"), ("person.sam", "Sam")):
        hass.states.async_set(p, "home", {"friendly_name": nom})


async def _jusqu_au_resume(hass, lieu="École", mots=None, decideurs=("person.alex",), objectifs=("horaires", "demandes"),
                           appareil="tele"):
    flux = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    assert flux["step_id"] == "user" and flux["last_step"] is False
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"lieu": lieu, "mots": dict(mots or {})})
    assert flux["step_id"] == "personnes" and flux["last_step"] is False
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"decideurs": list(decideurs)})
    assert flux["step_id"] == "objectifs"
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"objectifs": list(objectifs)})
    assert flux["step_id"] == "appareil"
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"appareil": appareil})
    assert flux["step_id"] == "pret" and flux["last_step"] is True
    return flux


async def test_installation_en_cinq_etapes(hass, hass_storage):
    from custom_components.pc_parental import assistant, modules, roles

    _personnes(hass)
    flux = await _jusqu_au_resume(hass)
    resume = flux["description_placeholders"]["resume"]
    assert "École" in resume and "Alex" in resume and "/vision-maison" == flux["description_placeholders"]["tableau"]
    assert "télé" in flux["description_placeholders"]["suite"].lower()
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    assert flux["type"] == "create_entry"
    attendus = set(assistant.GROUPES["horaires"][0]) | set(assistant.GROUPES["demandes"][0])
    assert set(flux["result"].data["modules_initiaux"]) == attendus
    await hass.async_block_till_done()
    coord = _actuel(hass)
    # Posé une fois au premier démarrage : lieu, modules, Décideur ; l'assistant est marqué appliqué.
    assert coord.store.lieu == "École"
    assert modules.actifs(coord.store) == attendus
    assert coord.store.roles_personnes == {"person.alex": roles.PARENT}
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    assert entree.data["assistant_applique"] is True and entree.data["assistant"]["appareil"] == "tele"
    # Un second démarrage ne repose rien (le Décideur retiré entre-temps le reste).
    roles.attribuer_personne(hass, coord.store, "person.alex", "", "essai")
    await coord.store.async_save()
    assert await hass.config_entries.async_reload(entree.entry_id)
    await hass.async_block_till_done()
    assert _actuel(hass).store.roles_personnes == {}


async def test_une_seule_installation(hass, hass_storage):
    """HA-10 : une seule entrée (single_config_entry) ; Home Assistant n'offre plus « Ajouter un hub » ensuite."""
    _personnes(hass)
    flux = await _jusqu_au_resume(hass)
    await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    await hass.async_block_till_done()
    flux = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    assert flux["type"] == "abort" and flux["reason"] == "single_instance_allowed"
    manifeste = json.loads((RACINE / "manifest.json").read_text(encoding="utf-8"))
    assert manifeste["single_config_entry"] is True


async def test_objectifs_dependances_et_mots(hass, hass_storage):
    from custom_components.pc_parental import vocabulaire

    _personnes(hass)
    flux = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    # Un mot trop long : refusé en clair, la saisie gardée.
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"lieu": "Club", "mots": {"decideur": "x" * 31}})
    assert flux["step_id"] == "user" and flux["errors"] == {"base": "vocabulaire_trop_long"}
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"lieu": "Club", "mots": {"decideur": "responsable"}})
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"decideurs": []})
    # Les demandes seules : il manque de quoi demander, et la phrase le dit.
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"objectifs": ["demandes"]})
    assert flux["step_id"] == "objectifs" and flux["errors"] == {"base": "objectifs_dependances"}
    assert "Plages" in flux["description_placeholders"]["erreur"]
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"objectifs": ["contenus", "demandes"]})
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {"appareil": "plus_tard"})
    assert "personne pour l'instant" in flux["description_placeholders"]["resume"]
    flux = await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    await hass.async_block_till_done()
    store = _actuel(hass).store
    assert vocabulaire.reglage(store, "fr") == {"decideur": "responsable"}
    # Les rôles neufs prennent le mot choisi.
    assert store.roles["parent"]["nom"] == "Responsable"


async def _monter(hass, hass_storage, admin_par_appareil=True):
    from custom_components.pc_parental import modules

    actifs = set(modules.PRESETS["famille"]) | {"temps_gagne"}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "lieu": "École",
        "modules": {m: (m in actifs) for m in modules.TOUS},
        "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android"),
            "p1": _fiche("p1", "PIXEL-ALEX", PARENT["secret"], person="person.alex", platform="android", role="parent"),
        }}}
    _personnes(hass)
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    from custom_components.pc_parental import roles

    coord = _actuel(hass)
    roles.attribuer_personne(hass, coord.store, "person.alex", roles.PARENT, "essai")
    if admin_par_appareil:  # le téléphone d'Alex reste Décideur quoi qu'il arrive à sa personne
        coord.store.pcs["p1"]["role"] = roles.PARENT
    return entree


async def _relancer(hass, entree, lieu, decideurs, objectifs):
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "assistant"})
    assert flux["step_id"] == "assistant" and flux["last_step"] is False
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"lieu": lieu, "mots": {}})
    assert flux["step_id"] == "assistant_personnes"
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"decideurs": list(decideurs)})
    assert flux["step_id"] == "assistant_objectifs"
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"objectifs": list(objectifs)})
    assert flux["step_id"] == "assistant_pret" and flux["last_step"] is True
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {})
    assert flux["type"] == "create_entry", flux["description_placeholders"].get("erreur")
    await hass.async_block_till_done()
    return _actuel(hass).store


async def test_relancer_sans_surprise(hass, hass_storage):
    from custom_components.pc_parental import assistant, modules, roles

    entree = await _monter(hass, hass_storage)
    # Prérempli avec l'installation telle qu'elle est.
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "assistant"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"lieu": "École", "mots": {}})
    schema = {str(k): k for k in flux["data_schema"].schema}
    assert schema["decideurs"].default() == ["person.alex"]
    hass.config_entries.options.async_abort(flux["flow_id"])
    # Écrans partagés décochés, Sam devient Décideur, Alex reprend le rôle par défaut.
    store = await _relancer(hass, entree, "Club", ["person.sam"], ["horaires", "contenus", "demandes"])
    actifs = modules.actifs(store)
    assert not set(assistant.membres("ecrans")) & actifs
    assert "temps_gagne" in actifs  # groupe inchangé : ses modules restent tels quels
    assert store.lieu == "Club"
    assert store.roles_personnes == {"person.sam": roles.PARENT}
    # Écrans partagés recochés : leurs modules recommandés reviennent, pas les autres.
    store = await _relancer(hass, entree, "Club", ["person.sam"], ["horaires", "contenus", "demandes", "ecrans"])
    actifs = modules.actifs(store)
    assert {"veille", "accueil"} <= actifs and "widgets" not in actifs and "temps_gagne" in actifs


async def test_relancer_refuse_les_dependances(hass, hass_storage):
    entree = await _monter(hass, hass_storage, admin_par_appareil=False)
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "assistant"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"lieu": "École", "mots": {}})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"decideurs": ["person.alex"]})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"objectifs": ["demandes"]})
    assert flux["step_id"] == "assistant_objectifs" and flux["errors"] == {"base": "objectifs_dependances"}
    # Plus personne pour gérer (Alex décoché, seul Décideur) : refusé en clair au moment d'enregistrer, rien de changé.
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"objectifs": ["horaires", "demandes"]})
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "assistant"})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"lieu": "Club", "mots": {}})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"decideurs": []})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"objectifs": ["horaires", "demandes"]})
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {})
    assert flux["step_id"] == "assistant_pret" and flux["errors"] == {"base": "role_refuse"}
    assert "Gérer" in flux["description_placeholders"]["erreur"]
    assert _actuel(hass).store.lieu == "École"


async def test_configurer_reduit(hass, hass_storage):
    entree = await _monter(hass, hass_storage)
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    assert flux["menu_options"] == ["assistant", "ajouter", "appairage", "reparer", "autres", "soutien"]
    reparer = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "reparer"})
    assert reparer["menu_options"] == ["appareils_relier", "sauvegarde", "installer", "qr", "cle"]
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    autres = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "autres"})
    # Aucun réglage perdu : le reste de l'ancien menu est là. Lot 5 : modules et vocabulaire sont dans
    # Réglages › Réservé, temps gagné et règles selon le lieu sur la fiche de la personne.
    # Lot 6 : sans doublon — « Appareils existants » et « Avancé » répétaient Réparer et Personnes et rôles.
    assert set(autres["menu_options"]) == {"roles", "securite", "entites", "general", "accueil", "musique",
                                           "journal_musique", "appareils_essai"}


async def test_ajouter_propose_le_premier_appareil(hass, hass_storage):
    _personnes(hass)
    flux = await _jusqu_au_resume(hass, appareil="pc")
    await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    await hass.async_block_till_done()
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "ajouter"})
    schema = {str(k): k for k in flux["data_schema"].schema}
    assert schema["genre"].default() == "pc"


async def test_lieu_par_defaut_et_aucun_objectif(hass, hass_storage):
    """Le mot par défaut du lieu n'est pas enregistré (il suit la langue) ; tout décocher s'applique aussi."""
    from custom_components.pc_parental import assistant, lieu, modules

    _personnes(hass)
    flux = await _jusqu_au_resume(hass, lieu=lieu.defaut("fr"), objectifs=("horaires", "contenus"))
    await hass.config_entries.flow.async_configure(flux["flow_id"], {})
    await hass.async_block_till_done()
    store = _actuel(hass).store
    assert store.lieu == ""
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    store = await _relancer(hass, entree, lieu.defaut("fr"), ["person.alex"], [])
    assert store.lieu == ""
    groupes = {m for g in assistant.GROUPES for m in assistant.membres(g)}
    assert not modules.actifs(store) & groupes


@pytest.mark.parametrize("langue", LANGUES)
def test_textes_dans_les_six_langues(langue):
    tr = json.loads((RACINE / "translations" / f"{langue}.json").read_text(encoding="utf-8"))
    for etape in ("user", "personnes", "objectifs", "appareil", "pret"):
        assert tr["config"]["step"][etape]["title"] and tr["config"]["step"][etape]["description"]
    assert set(tr["selector"]["objectifs"]["options"]) == {"horaires", "contenus", "demandes", "ecrans"}
    assert set(tr["selector"]["premier_appareil"]["options"]) == {"telephone", "tele", "pc", "plus_tard"}
    for etape in ("assistant", "assistant_personnes", "assistant_objectifs", "assistant_pret", "reparer", "autres"):
        assert tr["options"]["step"][etape]["title"]
    assert "{resume}" in tr["config"]["step"]["pret"]["description"]
    cat = json.loads((RACINE / "i18n" / "config" / f"{langue}.json").read_text(encoding="utf-8"))
    for cle in ("assistant_resume_lieu", "assistant_suite_plus_tard", "objectif_ecrans", "premier_appareil_tele"):
        assert cat[cle]
    texte = json.dumps(tr["config"], ensure_ascii=False).lower()
    for mot in ("maison", "parent", "enfant", "famille"):
        assert mot not in texte
    if langue == "en":
        assert json.loads((RACINE / "strings.json").read_text(encoding="utf-8")) == tr
