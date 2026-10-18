"""2.5.6 (l'auteur, 15/10) : chaque Décideur choisit comment il reçoit demandes, alertes et résumé (canaux.py).

- défaut sûr : UN seul canal, jamais de doublon — l'appli Vision s'il en a une qui gère, sinon l'appli Compagnon ;
- les cases cochées sont respectées : Vision seule, Compagnon seule, les deux, Discord, ou rien ;
- réglage depuis le panneau (compte HA : chacun le sien, un administrateur ceux de tous) et depuis l'appli Vision ;
- le résumé de la semaine et les alertes passent par les mêmes choix ;
- carte « Mes notifications » dans Réglages ; Discord se règle dans Configurer.

    python -m pytest essai/canaux_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from demandes_partout_test import _demander
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN,
    ENFANT,
    PARENT,
    _integrations,
    _monter,
    expected_lingering_tasks,
    expected_lingering_timers,
)
from homeassistant.core import ServiceCall
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry

TELEPHONE = "mobile_app_pixel_decideur"


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))


def _compagnon(hass, personne="person.nolan", user_id=None):
    """Le téléphone de ce Décideur dans l'appli Compagnon (son inscription s'appelle « Pixel Decideur »)."""
    entree = MockConfigEntry(domain="mobile_app", data={"device_name": "Pixel Decideur"})
    entree.add_to_hass(hass)
    appareil = dr.async_get(hass).async_get_or_create(config_entry_id=entree.entry_id, identifiers={("mobile_app", "x")},
                                                       name="Pixel Decideur")
    # Renommé dans Home Assistant : le service reste celui de l'inscription.
    dr.async_get(hass).async_update_device(appareil.id, name_by_user="Le téléphone de Nolan")
    traceur = er.async_get(hass).async_get_or_create("device_tracker", "mobile_app", "x", config_entry=entree,
                                                     device_id=appareil.id)
    attrs = {"friendly_name": "Nolan", "device_trackers": [traceur.entity_id]}
    if user_id:
        attrs["user_id"] = user_id
    hass.states.async_set(personne, "home", attrs)
    recus = []

    async def notifier(call: ServiceCall):
        recus.append(dict(call.data))
    hass.services.async_register("notify", TELEPHONE, notifier)
    return recus


def _discord(hass, store):
    recus = []

    async def notifier(call: ServiceCall):
        recus.append(dict(call.data))
    hass.services.async_register("notify", "discord", notifier)
    store.discord = {"service": "discord", "cible": "123"}
    return recus


def _envois(recus):
    return [r for r in recus if r.get("message") != "clear_notification"]


def _messages_acces(hass, pid="p1"):
    return [m for m in _actuel(hass).store.pcs[pid].get("messages") or [] if m.get("acces")]


async def test_defaut_un_seul_canal_l_appli_vision(hass, hass_storage, hass_client):
    """PIXEL-NOLAN est un appareil Vision qui gère : la demande y arrive, pas en double sur l'appli Compagnon."""
    from custom_components.pc_parental import canaux

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    recus = _compagnon(hass)
    assert canaux.choix(hass, coord.store, "person.nolan", "demandes") == ["vision"]
    await _demander(client, ENFANT, "sites")
    await _demander(client, ENFANT, "temps")
    await hass.async_block_till_done()
    assert _envois(recus) == []
    assert _messages_acces(hass)


async def test_defaut_sans_appli_vision_l_appli_compagnon(hass, hass_storage, hass_client):
    from custom_components.pc_parental import canaux

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    coord.store.pcs["p1"]["person"] = ""  # l'appareil Vision n'est plus au nom de Nolan
    coord.store.roles_personnes["person.nolan"] = "parent"
    recus = _compagnon(hass)
    assert canaux.choix(hass, coord.store, "person.nolan", "demandes") == ["compagnon"]
    await _demander(client, ENFANT, "sites")
    await hass.async_block_till_done()
    envois = _envois(recus)
    assert len(envois) == 1 and envois[0]["data"]["channel"] == "Vision", recus


async def test_choix_respectes(hass, hass_storage, hass_client):
    from custom_components.pc_parental import canaux

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    recus = _compagnon(hass)
    sur_discord = _discord(hass, coord.store)
    canaux.poser(coord.store, "person.nolan", "demandes", ["compagnon", "discord"])  # plus l'appli Vision
    await _demander(client, ENFANT, "sites")
    await hass.async_block_till_done()
    assert len(_envois(recus)) == 1 and len(sur_discord) == 1 and sur_discord[0]["target"] == ["123"]
    assert not _messages_acces(hass)
    # Rien de coché : rien ne part, la demande reste dans Vision (Aujourd'hui).
    canaux.poser(_actuel(hass).store, "person.nolan", "demandes", [])
    recus.clear()
    sur_discord.clear()
    await _demander(client, ENFANT, "apps")
    await hass.async_block_till_done()
    assert _envois(recus) == [] and sur_discord == [] and not _messages_acces(hass)


async def test_resume_par_les_canaux(hass, hass_storage):
    from custom_components.pc_parental import bilan, canaux

    coord = await _monter(hass, hass_storage)
    recus = _compagnon(hass)
    canaux.poser(coord.store, "person.nolan", "resume", ["compagnon"])
    coord.store.bilan_compteurs = {"person.lou": {"demande": 2}}
    await bilan.async_envoyer(hass, coord, remise_a_zero=False)
    await hass.async_block_till_done()
    store = _actuel(hass).store
    assert bilan.preparer(hass, store)
    assert [r for r in _envois(recus) if r["data"].get("tag") == "vision_bilan"]
    titres = [m.get("title") for m in store.pcs["p1"].get("messages") or []]
    assert not any("bilan" in str(t).lower() for t in titres), titres


async def test_api_panneau_chacun_le_sien(hass, hass_storage, hass_client, hass_admin_user):
    client = await hass_client()  # l'administrateur
    await _monter(hass, hass_storage)
    _compagnon(hass, user_id=hass_admin_user.id)
    d = await (await client.get("/api/pc_parental/canaux")).json()
    assert d["admin"] is True and d["moi"] == "person.nolan"
    assert {p["personne"] for p in d["decideurs"]} >= {"person.nolan", "person.mia"}
    assert d["etats"]["person.nolan"]["choix"]["demandes"] == ["vision"]
    assert d["etats"]["person.nolan"]["possibles"] == {"vision": True, "compagnon": True, "discord": False}
    r = await client.post("/api/pc_parental/canaux", json={"personne": "person.mia", "evenement": "alertes",
                                                           "canaux": ["vision", "inconnu"]})
    assert r.status == 200 and (await r.json())["choix"]["alertes"] == ["vision"]
    r = await client.post("/api/pc_parental/canaux", json={"personne": "person.lou", "evenement": "demandes", "canaux": []})
    assert r.status == 403  # pas un Décideur
    await hass.async_block_till_done()
    assert hass_storage[DOMAIN]["data"]["canaux_notif"]["person.mia"]["alertes"] == ["vision"]


async def test_depuis_l_appli_vision(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lire = await client.post("/api/pc_parental/parent", json={**PARENT, "action": "canaux"})
    d = await lire.json()
    assert lire.status == 200 and d["personne"] == "person.nolan" and d["choix"]["demandes"] == ["vision"]
    assert d["evenements"] == ["demandes", "alertes", "resume"] and d["canaux"] == ["vision", "compagnon", "discord"]
    r = await client.post("/api/pc_parental/parent", json={**PARENT, "action": "canaux", "evenement": "resume",
                                                           "canaux": []})
    assert r.status == 200 and (await r.json())["choix"]["resume"] == []
    r = await client.post("/api/pc_parental/parent", json={**ENFANT, "action": "canaux"})
    assert r.status == 403


async def test_carte_dans_reglages(hass, hass_storage):
    import json

    from custom_components.pc_parental import cartes, dashboard

    coord = await _monter(hass, hass_storage)
    assert "vision-canaux-card.js" in cartes.CARTES
    vues = dashboard.build(hass, coord)["views"]
    reglages = next(v for v in vues if v.get("path") == "reglages")
    assert json.dumps(reglages).count("custom:vision-canaux-card") == 1


async def test_discord_dans_configurer(hass, hass_storage):
    coord = await _monter(hass, hass_storage)
    hass.services.async_register("notify", "discord", lambda call: None)
    om = hass.config_entries.options
    flux = await om.async_init(coord.config_entry.entry_id)
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "autres"})
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "general"})
    champs = {str(k) for k in flux["data_schema"].schema}
    assert {"discord_service", "discord_cible"} <= champs and "notif_decideurs" not in champs
    flux = await om.async_configure(flux["flow_id"], {"discord_service": "discord", "discord_cible": " 123 ",
                                                     "personnes_affichage": []})
    assert flux["type"] == "create_entry"
    assert _actuel(hass).store.discord == {"service": "discord", "cible": "123"}
