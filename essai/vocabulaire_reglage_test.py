"""Réglage « Vocabulaire » (lot Accueil, Configurer → Vocabulaire, vocabulaire.py) : l'administrateur choisit les mots
Décideur / Demandeur (singulier, pluriel) dans la langue de Home Assistant ; vide = le mot par défaut.

- sans réglage : les mots du glossaire, partout (catalogue serveur, relevé des appareils) ;
- personnalisés : ils apparaissent dans un texte du catalogue serveur et dans le champ `vocabulaire` du relevé ;
- repli : un champ vide, rempli d'espaces ou égal au défaut ne garde rien ; une autre langue n'est pas touchée ;
- flux d'options de bout en bout : défauts en suggestion, mot trop long refusé, enregistrement, réouverture.

    python -m pytest essai/vocabulaire_reglage_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.data_entry_flow import FlowResultType
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)


async def _monter(hass, hass_storage, langue: str = "fr", **plus):
    hass.config.language = langue
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, **plus, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant"),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id], entree


async def _releve(client) -> dict:
    r = await client.post("/api/pc_parental/poll", json=ENFANT)
    assert r.status == 200
    return await r.json()


def _serveur(cle: str, langue: str = "fr") -> str:
    from custom_components.pc_parental import textes

    return textes.t(cle, langue)


async def test_defauts(hass, hass_storage, hass_client):
    coord, _ = await _monter(hass, hass_storage)
    assert coord.store.mots_vocabulaire == {}
    assert _serveur("commun.reserve_decideurs") == "Réservé aux décideurs."
    voc = (await _releve(await hass_client()))["vocabulaire"]
    assert voc["decideur"] == "décideur" and voc["demandeurs"] == "demandeurs" and "Demandeurs" not in voc and voc["lieu"]


async def test_personnalisation_partout(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage, mots_vocabulaire={
        "fr": {"decideur": "responsable", "decideurs": "responsables"},
        "de": {"decideurs": "Betreuer"}})
    assert _serveur("commun.reserve_decideurs") == "Réservé aux responsables."
    assert "Betreuer" in _serveur("commun.reserve_decideurs", "de")
    voc = (await _releve(await hass_client()))["vocabulaire"]
    assert voc["decideur"] == "responsable" and voc["decideurs"] == "responsables" and "Decideurs" not in voc
    assert voc["demandeur"] == "demandeur"  # non choisi : le défaut


async def test_repli(hass, hass_storage):
    from custom_components.pc_parental import vocabulaire

    # Chargement : des mots vides ou d'un autre type ne sont pas gardés.
    coord, _ = await _monter(hass, hass_storage, mots_vocabulaire={"fr": {"decideur": "   ", "demandeur": 3},
                                                                  "en": "x"})
    store = coord.store
    assert store.mots_vocabulaire == {}
    assert vocabulaire.termes("fr", store)["decideur"] == "décideur"
    # Saisie : vide, espaces, égal au défaut = rien de gardé ; espaces regroupés, accolades retirées.
    assert vocabulaire.preparer({"decideur": "  ", "decideurs": "décideurs", "demandeur": ""}, "fr") == {}
    assert vocabulaire.preparer({"demandeur": "  petit   {x} ", "decideur": None}, "fr") == {"demandeur": "petit x"}
    try:
        vocabulaire.preparer({"decideurs": "x" * 31}, "fr")
        raise AssertionError("trop long accepté")
    except ValueError as err:
        assert str(err) == "decideurs"
    # Poser puis vider : la langue disparaît, les autres restent.
    store.mots_vocabulaire = {"de": {"decideur": "Betreuer"}}
    assert vocabulaire.poser(store, "fr", {"decideur": "chef"})
    assert not vocabulaire.poser(store, "fr", {"decideur": "chef"})
    assert vocabulaire.poser(store, "fr", {})
    assert store.mots_vocabulaire == {"de": {"decideur": "Betreuer"}}
    # Un mot trop long glissé dans le stockage est coupé à l'affichage, jamais refusé.
    store.mots_vocabulaire = {"fr": {"decideur": "y" * 50}}
    assert vocabulaire.termes("fr", store)["decideur"] == "y" * 30


async def test_flux_options(hass, hass_storage, hass_client, caplog):
    from custom_components.pc_parental import dashboard

    coord, entree = await _monter(hass, hass_storage)
    store = coord.store
    sig_avant = dashboard.signature(coord)
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    assert flux["type"] == FlowResultType.MENU and "vocabulaire" in flux["menu_options"]
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "vocabulaire"})
    assert flux["type"] == FlowResultType.FORM and flux["step_id"] == "vocabulaire"
    # Les défauts en suggestion et dans l'aide ; le lieu est rappelé, pas dupliqué.
    champs = {str(k): k.description["suggested_value"] for k in flux["data_schema"].schema}
    assert champs == {"decideur": "décideur", "decideurs": "décideurs", "demandeur": "demandeur",
                      "demandeurs": "demandeurs"}
    place = flux["description_placeholders"]
    assert "décideur" in place["defauts"] and place["nom_lieu"] and place["longueur"] == "30"
    assert "Décideur / décideurs" in place["apercu"]

    # Trop long : erreur sur le champ, rien d'enregistré.
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {
        "decideur": "responsable", "decideurs": "r" * 31, "demandeur": "", "demandeurs": ""})
    assert flux["type"] == FlowResultType.FORM and flux["errors"] == {"decideurs": "vocabulaire_trop_long"}
    assert store.mots_vocabulaire == {}

    # Enregistré : seuls les mots changés sont gardés ; journal, aperçu, tableau à refaire.
    caplog.clear()
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {
        "decideur": "Responsable ", "decideurs": "responsables", "demandeur": "demandeur", "demandeurs": ""})
    assert flux["type"] == FlowResultType.ABORT and flux["reason"] == "vocabulaire_ok"
    assert "Responsable / responsables" in flux["description_placeholders"]["apercu"]
    await hass.async_block_till_done()
    attendu = {"fr": {"decideur": "Responsable", "decideurs": "responsables"}}
    assert store.mots_vocabulaire == attendu
    assert hass_storage[DOMAIN]["data"]["mots_vocabulaire"] == attendu
    assert "vocabulaire (fr)" in caplog.text
    assert dashboard.signature(coord) != sig_avant and "Responsable" in dashboard.signature(coord)
    assert _serveur("commun.reserve_decideurs") == "Réservé aux responsables."
    assert (await _releve(await hass_client()))["vocabulaire"]["decideurs"] == "responsables"
    # Le premier relevé déclare l'étiquette « Toujours autorisé » : Vision se recharge, nouveau magasin.
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    store = coord.store
    assert store.mots_vocabulaire == attendu  # relu du stockage

    # Rouvert : les champs reprennent les mots choisis ; tout vider revient aux défauts.
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "vocabulaire"})
    champs = {str(k): k.description["suggested_value"] for k in flux["data_schema"].schema}
    assert champs["decideur"] == "Responsable" and champs["demandeur"] == "demandeur"
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {})
    assert flux["type"] == FlowResultType.ABORT and flux["reason"] == "vocabulaire_ok"
    await hass.async_block_till_done()
    assert store.mots_vocabulaire == {}
    assert "|mots:[]|" in dashboard.signature(coord)  # le reste a pu bouger avec le rechargement
