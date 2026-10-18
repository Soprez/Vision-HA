"""2.5.0 — première version utilisable de la refonte des autorisations (lots 1, 2 et 3), côté Home Assistant.

- la fiche de la personne reçoit ses autorisations (modes, appareil et heure de fin d'un « pour un temps »,
  sites liés d'une appli et propositions) ;
- une demande se règle avec 15 min, 1 h, Exception, Toujours disponible ou Refuser ; un blocage de sécurité ou
  d'âge refuse le geste et la demande reste à régler ;
- la reprise se lance depuis le tableau (simuler, appliquer, annuler) ;
- lot 3 : un Décideur valide un site lié proposé, qui s'ouvre alors avec l'appli ;
- les téléphones des Décideurs (appli compagnon) reçoivent la demande avec les boutons ; la réponse revient par
  l'événement de l'appli compagnon, seulement d'un utilisateur habilité.

    python -m pytest essai/autorisations_25_test.py -x -q -o asyncio_mode=auto
"""
from __future__ import annotations

import sys

import pytest
from octroi_banc import CODE_OCTROI, poser_code_octroi
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from homeassistant.core import Context
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry

ROBLOX_SITES = ("roblox.com", "rbx.com", "rbxinfra.com", "rbxcdn.com", "robloxlabs.com")


def _module(fin):
    return next(m for n, m in sys.modules.items() if n.endswith("pc_parental." + fin))


async def _installer(hass, hass_storage, v2=True, nolan_user_id=None):
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "etiquettes": {"apps": {"roblox": ["Jeux"], "minecraft": ["Jeux"]},
                       "sites": {**{s: ["Jeux"] for s in ROBLOX_SITES}, "exemple-adulte.org": ["Adulte"]}},
        "fermetures": {"person.lou": {"jeux|": {"etiquette": "Jeux", "raison": "Fermé par une règle.", "source": "",
                                                   "depuis": 1}}},
        "pcs": {
            "w1": _fiche("w1", "PC-LOU", ENFANT["secret"], person="person.lou", platform="windows", bloques=["Adulte"],
                         sites_vus={"robloxdev.com": {"premier": 1}, "exemple.org": {"premier": 1}}),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set("person.nolan", "home", {"friendly_name": "Nolan", "user_id": nolan_user_id,
                                                  "device_trackers": ["device_tracker.telephone_nolan"]})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    coord.store.autorisations_v2 = v2
    for pc in coord.store.pcs.values():
        pc.setdefault("apps_vues", {}).update({"roblox": {"premier": 1}, "minecraft": {"premier": 1}})
    return coord


def _ouvert(coord, pc, nom, genre="apps"):
    coord.store._memos = {}
    return coord.store.statut(coord.store.pcs[pc], genre, nom) == "autorise"


def _demande(hass, coord, nom="roblox", genre="apps"):
    d = _module("demandes").deposer(hass, coord, coord.store.pcs["w1"], genre, nom, nom.capitalize(), duree="1h")
    assert d and "id" in d, d
    return d


async def _repondre(hass, coord, d, decision, mode="", minutes=0):
    return await _module("parent").agir(hass, coord, coord.store.pcs["w1"],
                                        {"action": "acces", "demande": d["id"], "decision": decision, "mode": mode,
                                         "minutes": minutes}, "Nolan", "p1")


# ------------------------------------------------------------------ fiche de la personne


async def test_la_fiche_porte_les_autorisations_et_les_sites_lies(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    parent = _module("parent")
    await parent.agir(hass, coord, coord.store.pcs["w1"], {"action": "accorder", "nom": "roblox", "genre": "apps",
                                                           "mode": "toujours"}, "Nolan", "p1")
    await parent.agir(hass, coord, coord.store.pcs["w1"], {"action": "accorder", "nom": "minecraft", "genre": "apps",
                                                           "mode": "temps", "minutes": 15}, "Nolan", "p1")
    etat = parent.etat_maison(hass, coord)
    assert etat["autorisations_v2"] is True
    lou = next(p for p in etat["personnes"] if p["entite"] == "person.lou")
    par_nom = {f["nom"]: f for f in lou["autorisations"]}
    assert par_nom["roblox"]["mode"] == "toujours" and "rbxcdn.com" in par_nom["roblox"]["lies"]
    assert "robloxdev.com" in par_nom["roblox"]["proposes"]
    assert par_nom["minecraft"]["mode"] == "temps" and par_nom["minecraft"]["appareil"] == "PC-LOU"


# ------------------------------------------------------------------ réponses à une demande


@pytest.mark.parametrize(("decision", "mode", "minutes", "ailleurs"), [
    ("temporaire", "", 15, False), ("temporaire", "", 60, False),
    ("accorder", "exception", 0, True), ("accorder", "toujours", 0, True),
])
async def test_les_reponses_a_une_demande(hass, hass_storage, decision, mode, minutes, ailleurs):
    coord = await _installer(hass, hass_storage)
    d = _demande(hass, coord)
    await _repondre(hass, coord, d, decision, mode, minutes)
    assert _ouvert(coord, "w1", "roblox")
    assert coord.store.mode_accorde(coord.store.pcs["w1"], "apps", "roblox") == (mode or None)
    assert not _module("demandes").en_attente(coord.store.pcs["w1"])


async def test_refuser(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    d = _demande(hass, coord)
    await _repondre(hass, coord, d, "non")
    assert not _ouvert(coord, "w1", "roblox") and not _module("demandes").en_attente(coord.store.pcs["w1"])


@pytest.mark.parametrize(("decision", "mode", "minutes"), [("temporaire", "", 15), ("accorder", "toujours", 0)])
async def test_securite_refuse_et_la_demande_reste(hass, hass_storage, decision, mode, minutes):
    coord = await _installer(hass, hass_storage)
    d = _demande(hass, coord, "exemple-adulte.org", "sites")
    with pytest.raises(_module("parent")._Refus) as err:
        await _repondre(hass, coord, d, decision, mode, minutes)
    assert err.value.status == 409
    assert [x["id"] for x in _module("demandes").en_attente(coord.store.pcs["w1"])] == [d["id"]]
    assert not _ouvert(coord, "w1", "exemple-adulte.org", "sites")


# ------------------------------------------------------------------ reprise depuis le tableau


async def test_reprise_depuis_le_tableau(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage, v2=False)
    client = await hass_client()
    poser_code_octroi(hass)
    r = await client.post("/api/pc_parental/maison", json={"code_octroi": CODE_OCTROI, "action": "reprise_autorisations", "etape": "simuler"})
    assert r.status == 200
    corps = await r.json()
    assert corps["autorisations_v2"] is False and corps["retour"]
    r = await client.post("/api/pc_parental/maison", json={"code_octroi": CODE_OCTROI, "action": "reprise_autorisations", "etape": "appliquer"})
    assert r.status == 200 and (await r.json())["autorisations_v2"] is True
    r = await client.post("/api/pc_parental/maison", json={"code_octroi": CODE_OCTROI, "action": "reprise_autorisations", "etape": "appliquer"})
    assert r.status == 409
    r = await client.post("/api/pc_parental/maison", json={"code_octroi": CODE_OCTROI, "action": "reprise_autorisations", "etape": "annuler"})
    assert r.status == 200 and (await r.json())["autorisations_v2"] is False
    assert coord.store.reprise_autorisations["annulee_par"]


# ------------------------------------------------------------------ lot 3 : sites liés validés


async def test_site_lie_propose_puis_valide(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    parent = _module("parent")
    pc = coord.store.pcs["w1"]
    coord.store.etiquettes["sites"]["robloxdev.com"] = ["Jeux"]
    pc["sites_vus"]["robloxdev.com"] = {"premier": 1}
    await parent.agir(hass, coord, pc, {"action": "accorder", "nom": "roblox", "genre": "apps", "mode": "exception"},
                      "Nolan", "p1")
    coord.store._memos = {}
    assert "robloxdev.com" in coord.store.effectif(pc)["sites"]          # proposé, pas encore lié
    await parent.agir(hass, coord, pc, {"action": "lier_domaine", "nom": "roblox", "domaine": "robloxdev.com",
                                        "lier": True}, "Nolan", "p1")
    coord.store._memos = {}
    eff = coord.store.effectif(pc)
    assert "robloxdev.com" not in eff["sites"] and "robloxdev.com" in eff["exceptions"]
    await parent.agir(hass, coord, pc, {"action": "lier_domaine", "nom": "roblox", "domaine": "robloxdev.com",
                                        "lier": False}, "Nolan", "p1")
    coord.store._memos = {}
    assert "robloxdev.com" in coord.store.effectif(pc)["sites"]


# ------------------------------------------------------------------ notifications à boutons


async def _telephone(hass):
    """Le téléphone de Nolan dans l'appli compagnon : un traceur mobile_app et son service notify."""
    entree = MockConfigEntry(domain="mobile_app", data={}, title="Téléphone Nolan")
    entree.add_to_hass(hass)
    appareil = dr.async_get(hass).async_get_or_create(config_entry_id=entree.entry_id, identifiers={("mobile_app", "tel-nolan")},
                                                     name="Téléphone Nolan")
    er.async_get(hass).async_get_or_create("device_tracker", "mobile_app", "tel-nolan-traceur", suggested_object_id="telephone_nolan",
                                           device_id=appareil.id, config_entry=entree)
    appels = []

    async def _notifier(call):
        appels.append(dict(call.data))

    hass.services.async_register("notify", "mobile_app_telephone_nolan", _notifier)
    return appels


async def test_notification_a_boutons_puis_reponse_du_telephone(hass, hass_storage, hass_admin_user):
    appels = await _telephone(hass)
    coord = await _installer(hass, hass_storage, nolan_user_id=hass_admin_user.id)
    # 2.5.6 : Nolan a l'appli Vision (défaut : elle seule) ; il choisit aussi l'appli Compagnon pour les demandes.
    _module("canaux").poser(coord.store, "person.nolan", "demandes", ["vision", "compagnon"])
    d = _demande(hass, coord)
    await hass.async_block_till_done()
    assert appels, "la demande n'est pas partie sur le téléphone"
    actions = appels[0]["data"]["actions"]
    # Faille du 16/10 : accorder exige l'empreinte ou le code ; la notification ne garde que « Refuser ».
    assert [a["title"] for a in actions] == ["Refuser"]
    # Le bouton « Exception » d'une notification d'avant la correction n'accorde rien.
    exception = actions[0]["action"].rsplit("|", 1)[0] + "|exception"
    hass.bus.async_fire("mobile_app_notification_action", {"action": exception}, context=Context(user_id=hass_admin_user.id))
    await hass.async_block_till_done()
    assert coord.store.mode_accorde(coord.store.pcs["w1"], "apps", "roblox") != "exception"
    assert [x["id"] for x in _module("demandes").en_attente(coord.store.pcs["w1"])] == [d["id"]]
    # « Refuser » depuis la notification marche toujours.
    hass.bus.async_fire("mobile_app_notification_action", {"action": actions[0]["action"]}, context=Context(user_id=hass_admin_user.id))
    await hass.async_block_till_done()
    assert not _module("demandes").en_attente(coord.store.pcs["w1"])


async def test_reponse_du_telephone_refusee_sans_habilitation(hass, hass_storage, hass_read_only_user):
    appels = await _telephone(hass)
    coord = await _installer(hass, hass_storage)
    _module("canaux").poser(coord.store, "person.nolan", "demandes", ["vision", "compagnon"])
    d = _demande(hass, coord)
    await hass.async_block_till_done()
    non = appels[0]["data"]["actions"][0]["action"]
    assert non.endswith("|non")
    # Même « Refuser » demande un utilisateur habilité.
    hass.bus.async_fire("mobile_app_notification_action", {"action": non}, context=Context(user_id=hass_read_only_user.id))
    hass.bus.async_fire("mobile_app_notification_action", {"action": non})  # sans utilisateur
    await hass.async_block_till_done()
    assert not _ouvert(coord, "w1", "roblox")
    assert [x["id"] for x in _module("demandes").en_attente(coord.store.pcs["w1"])] == [d["id"]]
