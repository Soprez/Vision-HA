"""Modules activables (1.82.0) côté serveur, dans un vrai Home Assistant.

- les dépendances sont refusées (Configurer → Modules, et modules.manques) ; les préréglages ;
- l'avertissement avant de couper les protections quand des appareils protégés existent ;
- chaque module coupé : son effet au relevé suivant, ou 403 {"error": "module"} sur ses portes ;
- « Fermer » à distance reste possible sans les plages (le socle de la gestion à distance) ;
- la réactivation retrouve tout, réglages intacts ; les entités sont désactivées puis réactivées
  (seulement celles que l'intégration avait éteintes ; la matrice garde les siennes) ;
- le préréglage du premier lancement (entry.data["modules_initiaux"]).

    python -m pytest essai/modules_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import copy

import pytest
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, Appareil, _fiche, _integrations, expected_lingering_tasks,
    expected_lingering_timers,
)

FLUX = "/api/config/config_entries/options/flow"
MIA = {"id": "p2", "secret": "c" * 32}
PC = {"id": "w1", "secret": "w" * 32}
SOIR = {"id": 1, "name": "Soir", "start": 72000, "end": 25200, "weekdays": [True] * 7, "enabled": True}


def _modules():
    from custom_components.pc_parental import modules

    return modules


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))


async def _monter(hass, hass_storage, data=None, stocke=True):
    if stocke:
        hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
            "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
                "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android",
                             role="enfant", rules=[dict(SOIR)], limites={"minetest": 5},
                             bloques=["minetest", "jeu-exemple.com", "Adulte"],
                             sites_vus={"jeu-exemple.com": {}}),
                "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android",
                             role="enfant"),
                "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android",
                             role="parent"),
                "p2": _fiche("p2", "PIXEL-MIA", MIA["secret"], person="person.mia", platform="android",
                             role="parent"),
                "w1": _fiche("w1", "PC-LOU", PC["secret"], person="person.lou"),
            }}}
    for p in ("lou", "noa", "robin", "mia"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data=data or {}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    _actuel(hass).store.declarer_etiquette("Adulte")
    await hass.async_block_till_done()
    return entree, _actuel(hass)


async def _choisir(hass, voulus):
    """modules.regler sur le magasin du moment (une étiquette créée recharge Vision), sans passer par l'écran."""
    await hass.async_block_till_done()
    store = _actuel(hass).store
    _modules().regler(hass, store, set(voulus), "essai")
    store._memos = {}
    return store


async def _suite(admin, flux, corps):
    r = await admin.post(f"{FLUX}/{flux['flow_id']}", json=corps)
    assert r.status == 200, await r.text()
    return await r.json()


async def _ecran_modules(admin, entree):
    flux = await (await admin.post(FLUX, json={"handler": entree.entry_id})).json()
    assert flux["type"] == "menu" and "modules" in flux["menu_options"]
    flux = await _suite(admin, flux, {"next_step_id": "modules"})
    assert flux["step_id"] == "modules"
    return flux


async def _regler(hass, admin, entree, voulus=None, prereglage="garder", confirmer=True):
    """Configurer → Modules, jusqu'au bout (avec l'avertissement des protections s'il vient)."""
    flux = await _ecran_modules(admin, entree)
    flux = await _suite(admin, flux, {"prereglage": prereglage, "modules": sorted(voulus or [])})
    if flux.get("step_id") == "modules_protections":
        flux = await _suite(admin, flux, {"je_comprends": confirmer})
    assert flux["type"] == "create_entry", flux
    await hass.async_block_till_done()
    return _actuel(hass)


async def _releve(client, ident=ENFANT, **corps):
    appareil = {} if ident is PC else {"platform": "android", "type": "telephone"}
    r = await client.post("/api/pc_parental/poll", json={**ident, "version": "1", "host": "x", **appareil, **corps})
    assert r.status == 200, await r.text()
    return await r.json()


def _desactivee(hass, domaine, uid):
    reg = er.async_get(hass)
    eid = reg.async_get_entity_id(domaine, DOMAIN, f"{DOMAIN}_{uid}")
    assert eid is not None, uid  # jamais supprimée : l'historique reste
    return reg.async_get(eid).disabled_by


# ----------------------------------------------------------------------------- Dépendances et préréglages


async def test_dependances_refusees(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, coord = await _monter(hass, hass_storage)
    m = _modules()
    # modules.manques : « tous » et « au moins un ».
    assert set(m.manques({"veille_tableaux"})) == {"veille_tableaux"}
    assert "Écran de veille" in m.manques({"veille_tableaux"})["veille_tableaux"]
    assert "Entités" in m.manques({"veille_tableaux", "veille"})["veille_tableaux"]
    assert "au moins un" in m.manques({"demandes"})["demandes"]
    assert m.manques({"demandes", "temps"}) == {}
    assert set(m.manques({"double_validation", "widgets"})) == {"double_validation", "widgets"}
    with pytest.raises(ValueError):
        m.regler(hass, coord.store, {"pause"}, "essai")
    # Par l'écran : refusé, et on dit ce qui manque ; rien n'est enregistré.
    flux = await _ecran_modules(admin, entree)
    flux = await _suite(admin, flux, {"prereglage": "garder", "modules": ["demandes", "double_validation"]})
    assert flux["type"] == "form" and flux["errors"] == {"base": "modules_dependances"}
    assert "Déverrouillage" in flux["description_placeholders"]["erreur"]
    assert _actuel(hass).store.modules == {}
    # absent = actif, sauf les modules coupés par défaut (le Réveil)
    assert m.actifs(_actuel(hass).store) == set(m.TOUS) - m.INACTIFS_PAR_DEFAUT


@pytest.mark.parametrize("pre", ["simple", "famille", "tout"])
async def test_prereglages(hass, hass_storage, hass_client, pre):
    admin = await hass_client()
    entree, _ = await _monter(hass, hass_storage)
    coord = await _regler(hass, admin, entree, prereglage=pre)
    m = _modules()
    assert m.actifs(coord.store) == set(m.PRESETS[pre])
    assert hass_storage[DOMAIN]["data"]["modules"] == {x: (x in m.PRESETS[pre]) for x in m.TOUS}
    assert (await _releve(await hass_client()))["modules"] == sorted(m.PRESETS[pre])


async def test_avertissement_protections(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, coord = await _monter(hass, hass_storage)
    m = _modules()
    assert m.demandeurs(coord.store)  # Lou et Noa sont protégés
    sans = sorted(set(m.TOUS) - {"protections"})
    flux = await _ecran_modules(admin, entree)
    flux = await _suite(admin, flux, {"prereglage": "garder", "modules": sans})
    assert flux["step_id"] == "modules_protections"
    assert "TEL-LOU" in flux["description_placeholders"]["appareils"]
    # Sans cocher « je comprends » : refusé, rien ne change.
    flux = await _suite(admin, flux, {"je_comprends": False})
    assert flux["errors"] == {"base": "modules_confirmer"}
    assert "protections" in m.actifs(_actuel(hass).store)
    flux = await _suite(admin, flux, {"je_comprends": True})
    assert flux["type"] == "create_entry"
    await hass.async_block_till_done()
    assert "protections" not in m.actifs(_actuel(hass).store)
    assert _desactivee(hass, "binary_sensor", "compte_admin_w1") is er.RegistryEntryDisabler.INTEGRATION


async def test_premier_lancement(hass, hass_storage, hass_client):
    """Le préréglage choisi à l'ajout de l'intégration s'applique une fois, sur un magasin vierge."""
    await hass_client()
    _entree, coord = await _monter(hass, hass_storage, data={"modules_initiaux": "simple"}, stocke=False)
    m = _modules()
    assert m.actifs(coord.store) == set(m.PRESETS["simple"])
    # Un choix fait ensuite n'est plus écrasé par le préréglage initial.
    m.regler(hass, coord.store, set(m.PRESETS["simple"]) | {"veille"}, "essai")
    await coord.store.async_save()
    assert await hass.config_entries.async_reload(coord.config_entry.entry_id)
    await hass.async_block_till_done()
    assert "veille" in m.actifs(_actuel(hass).store)


# ----------------------------------------------------------------------------- Tout couper, tout retrouver


async def test_couper_puis_retrouver(hass, hass_storage, hass_client):
    admin = await hass_client()
    client = await hass_client()
    entree, coord = await _monter(hass, hass_storage)
    m = _modules()

    # --- Témoin, tout actif -------------------------------------------------------------------------
    await _releve(client, PC)  # le PC a un type : la matrice éteint sa « durée max de pause »
    await hass.async_block_till_done()
    avant_pcs = copy.deepcopy(_actuel(hass).store.pcs)
    r = await _releve(client)
    assert r["modules"] == sorted(set(m.TOUS) - m.INACTIFS_PAR_DEFAUT)
    assert "minetest" in r["apps"] and "jeu-exemple.com" in r["sites"]
    assert r["safesearch"] is True and r["pause_max"] > 0 and "deverrouillage_cle" in r
    assert "demander_acces" in r["fonctions"]
    assert _desactivee(hass, "number", "pause_max_w1") is er.RegistryEntryDisabler.INTEGRATION  # la matrice
    # Un administrateur éteint lui-même une entité de module : on ne la rallumera pas.
    reg = er.async_get(hass)
    reg.async_update_entity(reg.async_get_entity_id("select", DOMAIN, f"{DOMAIN}_son"),
                            disabled_by=er.RegistryEntryDisabler.USER)

    # --- Tout couper --------------------------------------------------------------------------------
    coord = await _regler(hass, admin, entree, voulus=[])
    assert m.actifs(coord.store) == set()
    store = coord.store
    # Rien n'est effacé.
    for pc_id, pc in avant_pcs.items():
        for champ in ("rules", "limites", "bloques", "autorises", "pause_max", "role"):
            assert store.pcs[pc_id].get(champ) == pc.get(champ), (pc_id, champ)

    r = await _releve(client)
    assert r["modules"] == []
    # plages : planning ignoré ; temps, blocage, filtrage : plus rien de fermé.
    assert r["action"] == "none" and "next_change" not in r
    etat = store.evaluate(store.pcs["e1"])
    assert etat["locked"] is False and etat["next_change"] is None
    assert r["apps"] == [] and r["sites"] == [] and r["exceptions"] == []
    assert r["liste_noire"] == "" and r["dns"] == [] and r["safesearch"] is False
    assert r["pause_max"] == 0 and "deverrouillage_cle" not in r and "deverrouillage" not in r
    assert r["warn"] == 0 and "avertissements" not in r and "temps_personne" not in r
    assert not ({"demander_acces", "demander_deverrouillage", "temps", "messages", "entites_voir"} & set(r["fonctions"]))
    assert "protege" in r["fonctions"]  # le socle
    rpc = await _releve(client, PC)
    if "processus" in rpc:
        # optimisations : des consignes VIDES, pour que l'agent restaure tout.
        assert rpc["processus"] == {"demarrage": [], "taches": [], "services": [], "arreter": []}

    # demandes : la demande de temps au relevé est ignorée, personne n'est prévenu.
    robin_avant = len(store.pcs["p1"].get("messages") or [])
    await _releve(client, demande_temps=30)
    assert not store.pcs["e1"].get("demande")
    assert len(store.pcs["p1"].get("messages") or []) == robin_avant

    # Les portes des modules coupés : 403 « module ».
    portes = [
        ("/api/pc_parental/demande", {**ENFANT, "genre": "sites", "nom": "jeu-exemple.com"}),
        ("/api/pc_parental/reponse", {**PARENT, "pc": "e1", "demande": "x", "minutes": 30}),
        ("/api/pc_parental/deverrouillage/demande", {**ENFANT, "action": "parent", "minutes": 10, "defi": "x"}),
        ("/api/pc_parental/deverrouillage/liste", PARENT),
        ("/api/pc_parental/deverrouillage/etat", {**ENFANT, "demande": "x"}),
        ("/api/pc_parental/veille", ENFANT),
        ("/api/pc_parental/veille/image", {**ENFANT, "entite": "camera.x"}),
        ("/api/pc_parental/veille/flux", {**ENFANT, "entite": "camera.x"}),
        ("/api/pc_parental/veille/entites", PARENT),
        ("/api/pc_parental/veille/photo", {**ENFANT, "nom": "x.jpg"}),
        ("/api/pc_parental/veille/reglages", PARENT),
        ("/api/pc_parental/veille/logo", {**ENFANT, "url": "https://x"}),
        ("/api/pc_parental/veille/acces", ENFANT),
    ]
    for chemin, corps in portes:
        rep = await client.post(chemin, json=corps)
        assert rep.status == 403, (chemin, rep.status, await rep.text())
        assert (await rep.json())["error"] == "module", chemin
    for chemin in ("/api/pc_parental/veille/dashboards", "/api/pc_parental/veille/tableaux",
                   "/api/pc_parental/deverrouillage", "/api/pc_parental/veille/page?id=e1"):
        rep = await admin.get(chemin)
        assert rep.status == 403 and (await rep.json())["error"] == "module", chemin
    # Les gestes de l'espace gestion qui relèvent d'un module coupé.
    for geste in ({"action": "plage", "op": "supprimer", "regle": 1}, {"action": "limite", "nom": "x", "minutes": 5},
                  {"action": "etiquette", "etiquette": "Adulte", "etat": "neutre"}, {"action": "acces", "demande": "x"},
                  {"action": "pause_max", "minutes": 30}, {"action": "message", "texte": "coucou"},
                  {"action": "logiciel", "cle": "x", "geste": "annuler"},
                  {"action": "autoriser", "genre": "apps", "nom": "minetest", "decision": "toujours"},
                  {"action": "autoriser", "genre": "sites", "nom": "jeu-exemple.com"}):
        rep = await client.post("/api/pc_parental/parent", json={**PARENT, "pc": "e1", **geste})
        assert rep.status == 403 and (await rep.json())["error"] == "module", geste
    assert store.pcs["e1"]["rules"] == avant_pcs["e1"]["rules"]
    # messages : plus d'envoi vers les appareils.
    for service, donnees in (("notify", {"message": "coucou"}), ("envoyer", {"message": "coucou"})):
        with pytest.raises(HomeAssistantError):
            await hass.services.async_call(DOMAIN, service, donnees, blocking=True)
    # L'espace gestion ne montre plus ce qui relève des modules coupés.
    etat_gestion = await (await client.post("/api/pc_parental/parent", json=PARENT)).json()
    lou = next(a for a in etat_gestion["appareils"] if a["id"] == "e1")
    assert lou["plages"] == [] and lou["limites"] == [] and lou["demandes"] == [] and lou["apps"] == []
    assert etat_gestion["categories"] == [] and etat_gestion["modules"] == []

    # Le socle : « Fermer » à distance marche toujours, sans durée il tient jusqu'à « Ouvrir ».
    rep = await client.post("/api/pc_parental/parent", json={**PARENT, "pc": "e1", "action": "fermer"})
    assert rep.status == 200, await rep.text()
    assert (await _releve(client))["action"] == "lock"
    assert store.pcs["e1"]["override"]["until"] == 0
    assert not [x for x in store.pcs["e1"].get("messages") or [] if x.get("title") == "Vision"]  # pas d'avis
    rep = await client.post("/api/pc_parental/parent", json={**PARENT, "pc": "e1", "action": "annuler"})
    assert (await _releve(client))["action"] == "none"

    # Entités : désactivées par l'intégration, jamais supprimées ; le socle reste.
    integration = er.RegistryEntryDisabler.INTEGRATION
    for domaine, uid in (("switch", "rule_e1_1"), ("sensor", "temps_ecran_e1"), ("number", "pause_max_e1"),
                         ("notify", "notify_e1"), ("select", "site_constate"), ("select", "logiciel_constate"),
                         ("sensor", "plages_e1"), ("switch", "liste_noire"), ("text", "veille_entites"),
                         ("switch", "veille_rendu"), ("switch", "desinstallation"), ("number", "delai_refus"),
                         ("binary_sensor", "compte_admin_w1")):
        assert _desactivee(hass, domaine, uid) is integration, (domaine, uid)
    for domaine, uid in (("sensor", "etat_e1"), ("switch", "acces_e1"), ("select", "role_e1")):
        assert _desactivee(hass, domaine, uid) is None, (domaine, uid)
    assert _desactivee(hass, "select", "son") is er.RegistryEntryDisabler.USER
    # Tableau de bord : plus les onglets des modules coupés.
    from custom_components.pc_parental import dashboard

    chemins = [v["path"] for v in dashboard.build(hass, _actuel(hass))["views"]]
    assert not ({"listes", "message", "veille", "protection"} & set(chemins)) and "appareils" in chemins

    # --- Tout retrouver -----------------------------------------------------------------------------
    coord = await _regler(hass, admin, entree, prereglage="tout")
    store = coord.store
    assert m.actifs(store) == set(m.PRESETS["tout"]) == set(m.TOUS) - m.INACTIFS_PAR_DEFAUT
    for pc_id, pc in avant_pcs.items():
        for champ in ("rules", "limites", "bloques", "autorises", "pause_max", "role"):
            assert store.pcs[pc_id].get(champ) == pc.get(champ), (pc_id, champ)
    r = await _releve(client)
    assert r["modules"] == sorted(m.PRESETS["tout"])
    assert "minetest" in r["apps"] and "jeu-exemple.com" in r["sites"] and r["safesearch"] is True
    assert r["pause_max"] > 0 and "deverrouillage_cle" in r and "demander_acces" in r["fonctions"]
    assert store.evaluate(store.pcs["e1"])["next_change"] is not None
    for domaine, uid in (("switch", "rule_e1_1"), ("sensor", "temps_ecran_e1"), ("number", "pause_max_e1"),
                         ("notify", "notify_e1"), ("select", "site_constate"), ("binary_sensor", "compte_admin_w1")):
        assert _desactivee(hass, domaine, uid) is None, (domaine, uid)
    # La matrice garde la sienne ; l'administrateur garde la sienne.
    assert _desactivee(hass, "number", "pause_max_w1") is integration
    assert _desactivee(hass, "select", "son") is er.RegistryEntryDisabler.USER
    chemins = [v["path"] for v in dashboard.build(hass, _actuel(hass))["views"]]
    assert {"listes", "message", "veille", "protection"} <= set(chemins)
    rep = await client.post("/api/pc_parental/veille", json=ENFANT)
    assert rep.status != 403


async def test_temps_seul_coupe(hass, hass_storage, hass_client):
    """Sans « Temps d'écran » : la limite atteinte ne ferme plus l'appli ; le blocage nommé, si."""
    client = await hass_client()
    await _monter(hass, hass_storage)
    m = _modules()
    await _releve(client)  # le premier relevé déclare « Toujours autorisé » : Vision se recharge
    await hass.async_block_till_done()
    store = _actuel(hass).store
    pc = store.pcs["e1"]
    pc["bloques"] = []
    jour = __import__("homeassistant.util.dt", fromlist=["now"]).now().strftime("%Y-%m-%d")
    pc["usage"] = {jour: {"apps": {"minetest": 3600}}}
    store.noter_app(pc, "minetest", 60)  # au catalogue : la limite ferme ce que le catalogue connaît
    store._memos = {}
    assert store.limite_atteinte(pc, "apps", "minetest") is not None
    assert "minetest" in (await _releve(client))["apps"]
    assert store is await _choisir(hass, set(m.TOUS) - {"temps", "temps_gagne", "bilan"})  # 1.89 / 1.90 : ils en dépendent
    r = await _releve(client)
    assert "minetest" not in r["apps"] and "temps_personne" not in r
    assert store.limites(store.pcs["e1"]) == {"minetest": 5}  # gardée


async def test_blocage_seul_coupe(hass, hass_storage, hass_client):
    """Sans « Blocage d'applis » : plus d'applis fermées ; les sites, eux, restent filtrés."""
    client = await hass_client()
    _entree, coord = await _monter(hass, hass_storage)
    m = _modules()
    # 1.88.0 : « Concentration » dépend de « Blocage d'applis » : elle se coupe avec lui.
    await _choisir(hass, set(m.TOUS) - {"blocage", "concentration"})
    r = await _releve(client)
    assert r["apps"] == [] and "jeu-exemple.com" in r["sites"] and r["safesearch"] is True
    await _choisir(hass, set(m.TOUS) - {"filtrage"})
    r = await _releve(client)
    assert "minetest" in r["apps"] and r["sites"] == [] and r["safesearch"] is False


async def test_double_validation_coupee(hass, hass_storage, hass_client):
    """Sans « Double validation » : une seule voix suffit ; la règle revient avec le module."""
    client = await hass_client()
    _entree, coord = await _monter(hass, hass_storage)
    m = _modules()
    from custom_components.pc_parental import roles

    roles.regler_validation(hass, _actuel(hass).store, "enfant", {"mode": "n", "n": 2}, "essai")
    lou = Appareil(client, ENFANT)
    assert (await lou.demander(minutes=30))[0] == 200
    r = await client.post("/api/pc_parental/deverrouillage/decision",
                          json={**PARENT, "demande": lou.demande, "decision": "approuver"})
    assert (await r.json())["etat"] == "partielle"
    # Coupée pendant la demande : la voix suivante suffit, et les nouvelles demandes n'en veulent qu'une.
    await _choisir(hass, set(m.TOUS) - {"double_validation"})
    r = await client.post("/api/pc_parental/deverrouillage/decision",
                          json={**MIA, "demande": lou.demande, "decision": "approuver"})
    assert (await r.json())["etat"] == "approuvee"
    assert (await lou.demander(minutes=30))[0] == 200
    r = await client.post("/api/pc_parental/deverrouillage/decision",
                          json={**PARENT, "demande": lou.demande, "decision": "approuver"})
    assert (await r.json())["etat"] == "approuvee"
    # Revenue : la règle à deux voix est intacte.
    store = await _choisir(hass, set(m.TOUS))
    assert roles.validation(store, store.pcs["e1"])["mode"] == "n"
    assert (await lou.demander(minutes=30))[0] == 200
    r = await client.post("/api/pc_parental/deverrouillage/decision",
                          json={**PARENT, "demande": lou.demande, "decision": "approuver"})
    assert (await r.json())["etat"] == "partielle"


async def test_pause_coupee_deverrouillage_garde(hass, hass_storage, hass_client):
    """Sans « Pause » (déverrouillage gardé) : « une pause de Vision » à distance est refusée, une appli non."""
    client = await hass_client()
    _entree, coord = await _monter(hass, hass_storage)
    m = _modules()
    await _choisir(hass, set(m.TOUS) - {"pause"})
    lou = Appareil(client, ENFANT)
    statut, r = await lou.demander(action="parent", minutes=10)
    assert statut == 403 and r["error"] == "module"
    assert (await lou.demander(minutes=10))[0] == 200
    assert (await _releve(client))["pause_max"] == 0
