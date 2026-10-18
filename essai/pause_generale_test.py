"""Pause générale (point 14.2) : un geste ferme d'un coup tous les appareils protégés, « Reprendre » les rouvre.

- pose (espace gestion, service, bouton) : les Demandeurs fermés, les Décideurs non ; l'écran de blocage dit
  « Tout est fermé jusqu'à 19h45 » (lot 1 UX : « Tout fermer ») ; l'état est dans la réponse de l'espace gestion et le capteur binaire ;
- reprise : rouverts (retour au planning) ; une fermeture manuelle d'avant n'est pas rouverte à tort ; un
  appareil rouvert ou refermé à la main pendant la pause n'est pas touché ;
- refus : rôle sans « Gérer » (403 parent), module coupé (403 module, service et bouton refusés, entités
  désactivées), compte non administrateur (service), durée hors de 1–240 ;
- tableau de bord : la section « Tout fermer » (ex-pause générale) sur l'accueil, seulement avec le module.

    python -m pytest essai/pause_generale_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

import pytest
import voluptuous as vol
from homeassistant.core import Context
from homeassistant.exceptions import HomeAssistantError, Unauthorized
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import async_capture_events

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)

URL = "/api/pc_parental/parent"
DEMANDEURS = ("e1", "e2")
DECIDEURS = ("p1", "p2")


def _coord(hass):
    return next(iter(hass.data[DOMAIN].values()))


def _eid(hass, domaine, uid):
    eid = er.async_get(hass).async_get_entity_id(domaine, DOMAIN, f"{DOMAIN}_{uid}")
    assert eid is not None, uid
    return eid


def _ferme_par_la_pause(store, pc_id):
    o = store.pcs[pc_id].get("override") or {}
    return o.get("mode") == "locked" and o.get("pause_generale") is True


async def _geste(client, qui=PARENT, **corps):
    r = await client.post(URL, json={**qui, **corps})
    return r.status, await r.json()


async def test_pose_puis_reprise_par_l_espace_gestion(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    evenements = async_capture_events(hass, f"{DOMAIN}_pause_generale")
    statut, corps = await _geste(client, action="pause_generale", minutes=30)
    assert statut == 200, corps
    store = _coord(hass).store
    for pc_id in DEMANDEURS:
        assert store.evaluate(store.pcs[pc_id])["locked"] is True, pc_id
        assert _ferme_par_la_pause(store, pc_id), pc_id
    for pc_id in DECIDEURS:
        assert store.pcs[pc_id].get("override") is None, pc_id
    # 1.93.0 (point 6 bis) : chaque appareil fermé reçoit une notification native ; pas les décideurs.
    for pc_id in DEMANDEURS:
        assert any("Tout est fermé jusqu'à" in m["text"] for m in store.pcs[pc_id]["messages"]), pc_id
    for pc_id in DECIDEURS:
        assert not any("Tout est fermé" in m["text"] for m in store.pcs[pc_id].get("messages") or []), pc_id
    fin = corps["pause_generale"]["fin"]
    assert abs(fin - (time.time() + 30 * 60)) < 120
    assert "Tout est fermé jusqu'à" in corps["retour"]
    # Persistant : écrit dans le magasin.
    assert hass_storage[DOMAIN]["data"]["pause_generale"]["fin"] == pytest.approx(fin, abs=1)
    await hass.async_block_till_done()
    assert evenements and evenements[-1].data["etat"] == "pose" and evenements[-1].data["appareils"] == ["e1", "e2"]
    # L'écran de blocage le dit (module « Messages » actif).
    r = await (await client.post("/api/pc_parental/poll", json={**ENFANT, "version": "1", "host": "x",
                                                               "platform": "android", "type": "telephone"})).json()
    assert r["action"] == "lock" and r["message"].startswith("Tout est fermé jusqu'à ")
    # Le capteur binaire (le premier relevé d'un appareil peut recharger Vision : on attend la fin du rechargement).
    await hass.async_block_till_done()
    etat = hass.states.get(_eid(hass, "binary_sensor", "pause_generale"))
    assert etat.state == "on" and etat.attributes["fin_heure"] in r["message"]
    # La lecture seule de l'espace gestion porte aussi l'état.
    assert (await _geste(client))[1]["pause_generale"]["fin"] == fin

    await hass.async_block_till_done()
    store = _coord(hass).store  # le premier relevé d'un appareil peut recharger Vision : le magasin du moment
    assert _ferme_par_la_pause(store, "e1") and _ferme_par_la_pause(store, "e2")  # la pause survit au rechargement
    statut, corps = await _geste(client, action="fin_pause_generale")
    assert statut == 200, corps
    assert corps["pause_generale"] is None
    for pc_id in DEMANDEURS:
        assert store.pcs[pc_id].get("override") is None, pc_id
    assert store.pause_generale is None
    await hass.async_block_till_done()
    assert evenements[-1].data["etat"] == "fin" and evenements[-1].data["appareils"] == ["e1", "e2"]
    assert hass.states.get(_eid(hass, "binary_sensor", "pause_generale")).state == "off"


async def test_fermeture_manuelle_d_avant_pas_rouverte(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    store = _coord(hass).store
    # e2 fermé à la main sans fin (jusqu'à « Ouvrir ») ; e1 fermé à la main pour 5 min seulement.
    assert (await _geste(client, action="fermer", pc="e2"))[0] == 200
    assert (await _geste(client, action="fermer", pc="e1", minutes=5))[0] == 200
    avant_e1 = dict(store.pcs["e1"]["override"])
    avant_e2 = dict(store.pcs["e2"]["override"])
    statut, corps = await _geste(client, action="pause_generale", minutes=60)
    assert statut == 200, corps
    assert store.pcs["e2"]["override"] == avant_e2  # déjà fermé plus longtemps : pas touché
    assert _ferme_par_la_pause(store, "e1")  # la pause le tient plus longtemps que ses 5 min
    assert list(store.pause_generale["appareils"]) == ["e1"]
    statut, corps = await _geste(client, action="fin_pause_generale")
    assert statut == 200, corps
    # Rien n'est rouvert à tort : e2 garde sa fermeture, e1 retrouve la sienne (encore valable).
    assert store.pcs["e2"]["override"] == avant_e2
    assert store.pcs["e1"]["override"] == avant_e1
    for pc_id in DEMANDEURS:
        assert store.evaluate(store.pcs[pc_id])["locked"] is True, pc_id


async def test_geste_a_la_main_pendant_la_pause_respecte(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    store = _coord(hass).store
    assert (await _geste(client, action="pause_generale", minutes=30))[0] == 200
    # Pendant la pause : e1 rouvert 10 min à la main, e2 refermé sans fin à la main.
    assert (await _geste(client, action="ouvrir", pc="e1", minutes=10))[0] == 200
    assert (await _geste(client, action="fermer", pc="e2"))[0] == 200
    e1, e2 = dict(store.pcs["e1"]["override"]), dict(store.pcs["e2"]["override"])
    assert not _ferme_par_la_pause(store, "e1") and not _ferme_par_la_pause(store, "e2")
    assert (await _geste(client, action="fin_pause_generale"))[0] == 200
    assert store.pcs["e1"]["override"] == e1 and store.pcs["e2"]["override"] == e2


async def test_echeance_rien_a_faire(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    store = _coord(hass).store
    assert (await _geste(client, action="pause_generale", minutes=1))[0] == 200
    # On avance l'échéance : chaque dérogation expire seule, l'état est oublié à la lecture.
    passe = time.time() - 1
    store.pause_generale["fin"] = passe
    for pc_id in DEMANDEURS:
        store.pcs[pc_id]["override"]["until"] = passe
    _statut, corps = await _geste(client)
    assert corps["pause_generale"] is None and store.pause_generale is None
    for pc_id in DEMANDEURS:
        etat = store.evaluate(store.pcs[pc_id])
        assert etat["override"] is None and etat["source"] != "override", pc_id  # retour au planning
    # « Reprendre » sans pause en cours : rien à faire, pas d'erreur.
    assert (await _geste(client, action="fin_pause_generale"))[0] == 200


@pytest.mark.parametrize("minutes", [0, 241, "x"])
async def test_duree_hors_bornes(hass, hass_storage, hass_client, minutes):
    client = await hass_client()
    await _monter(hass, hass_storage)
    statut, corps = await _geste(client, action="pause_generale", minutes=minutes)
    assert statut == 400 and corps["error"] == "minutes"
    assert all(pc.get("override") is None for pc in _coord(hass).store.pcs.values())


async def test_refuse_sans_gerer(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    for action in ("pause_generale", "fin_pause_generale"):
        statut, corps = await _geste(client, ENFANT, action=action, minutes=30)
        assert statut == 403 and corps["error"] == "parent", (action, corps)
    from custom_components.pc_parental import roles

    # Un rôle qui approuve sans gérer : refusé aussi.
    ident = roles.creer(hass, coord.store, "Approbateur", "parent", "essai")
    roles.modifier(hass, coord.store, ident, "Approbateur", ["protege", "approuver"], 60, "essai")
    roles.attribuer_appareil(hass, coord.store, "p1", ident, "essai")
    statut, corps = await _geste(client, action="pause_generale", minutes=30)
    assert statut == 403 and corps["error"] == "parent"
    assert all(pc.get("override") is None for pc in coord.store.pcs.values())
    assert coord.store.pause_generale is None


async def test_module_coupe(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    from custom_components.pc_parental import dashboard, modules

    store = _coord(hass).store
    # Témoin : module actif, la section est sur l'accueil.
    accueil = dashboard.build(hass, _coord(hass))["views"][0]
    assert any(c.get("heading") == "Tout fermer" for s in accueil["sections"] for c in s.get("cards", []))
    # Lot 2 (A8) : la durée se choisit (15 min à 2 h), chacune avec sa confirmation qui la répète ; « Rouvrir » sans.
    section = next(s for s in accueil["sections"]
                   if any(c.get("heading") == "Tout fermer" for c in s.get("cards", [])))
    durees = next(c for c in section["cards"] if c.get("type") == "grid")["cards"]
    assert [(b["name"], b["icon"]) for b in durees] == [
        ("15 min", "mdi:lock-clock"), ("30 min", "mdi:lock-clock"), ("1 h", "mdi:lock-clock"), ("2 h", "mdi:lock-clock")]
    assert all(b["tap_action"]["confirmation"]["text"].startswith("Tout fermer") for b in durees)
    assert [b["tap_action"]["data"]["minutes"] for b in durees] == [15, 30, 60, 120]
    # Faille du 16/10 : « Rouvrir » est la carte Vision (code d'octroi), plus le bouton standard de HA.
    rouvrir = [c for c in section["cards"] if c.get("type") == "custom:vision-maison-card"]
    assert len(rouvrir) == 1 and rouvrir[0]["mode"] == "rouvrir" and rouvrir[0]["entity"].startswith("binary_sensor.")
    assert not any(c.get("type") == "horizontal-stack" for c in section["cards"])
    modules.regler(hass, store, set(modules.TOUS) - {"pause_generale"}, "essai")
    modules.appliquer_entites(hass, store)
    await hass.async_block_till_done()
    for action in ("pause_generale", "fin_pause_generale"):
        statut, corps = await _geste(client, action=action, minutes=30)
        assert statut == 403 and corps["error"] == "module", (action, corps)
    for service, donnees in (("pause_generale", {"minutes": 30}), ("fin_pause_generale", {})):
        with pytest.raises(HomeAssistantError):
            await hass.services.async_call(DOMAIN, service, donnees, blocking=True)
    integration = er.RegistryEntryDisabler.INTEGRATION
    reg = er.async_get(hass)
    for domaine, uid in (("button", "pause_generale_30"), ("button", "pause_generale_fin"),
                         ("binary_sensor", "pause_generale")):
        assert reg.async_get(_eid(hass, domaine, uid)).disabled_by is integration, uid
    assert all(pc.get("override") is None for pc in store.pcs.values())
    assert (await _geste(client))[1]["pause_generale"] is None
    accueil = dashboard.build(hass, _coord(hass))["views"][0]
    assert not any(c.get("heading") == "Tout fermer" for s in accueil["sections"] for c in s.get("cards", []))
    # Hors préréglage « simple », dans « famille » et « tout ».
    assert "pause_generale" in modules.PRESETS["famille"] and "pause_generale" in modules.PRESETS["tout"]
    assert "pause_generale" not in modules.PRESETS["simple"]
    assert modules.manques({"pause_generale"}) == {}


async def test_service_et_boutons(hass, hass_storage, hass_client, hass_read_only_user):
    await hass_client()
    await _monter(hass, hass_storage)
    store = _coord(hass).store
    capteur = _eid(hass, "binary_sensor", "pause_generale")
    await hass.services.async_call(DOMAIN, "pause_generale", {"minutes": 45}, blocking=True)
    await hass.async_block_till_done()
    assert all(_ferme_par_la_pause(store, p) for p in DEMANDEURS)
    assert not any(store.pcs[p].get("override") for p in DECIDEURS)
    assert hass.states.get(capteur).state == "on"
    assert abs(store.pause_generale["fin"] - (time.time() + 45 * 60)) < 120
    await hass.services.async_call(DOMAIN, "fin_pause_generale", {}, blocking=True)
    await hass.async_block_till_done()
    assert not any(store.pcs[p].get("override") for p in DEMANDEURS)
    assert hass.states.get(capteur).state == "off"
    # Durée hors bornes.
    for minutes in (0, 241):
        with pytest.raises(vol.Invalid):
            await hass.services.async_call(DOMAIN, "pause_generale", {"minutes": minutes}, blocking=True)
    # Réservé aux administrateurs.
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "pause_generale", {"minutes": 30}, blocking=True,
                                       context=Context(user_id=hass_read_only_user.id))
    assert not any(store.pcs[p].get("override") for p in DEMANDEURS)
    # Les boutons de l'appareil « Vision ».
    await hass.services.async_call("button", "press", {"entity_id": _eid(hass, "button", "pause_generale_30")},
                                   blocking=True)
    await hass.async_block_till_done()
    assert all(_ferme_par_la_pause(store, p) for p in DEMANDEURS)
    assert abs(store.pause_generale["fin"] - (time.time() + 30 * 60)) < 120
    assert hass.states.get(capteur).state == "on"
    with pytest.raises(Unauthorized):
        await hass.services.async_call("button", "press", {"entity_id": _eid(hass, "button", "pause_generale_fin")},
                                       blocking=True, context=Context(user_id=hass_read_only_user.id))
    assert all(_ferme_par_la_pause(store, p) for p in DEMANDEURS)
    await hass.services.async_call("button", "press", {"entity_id": _eid(hass, "button", "pause_generale_fin")},
                                   blocking=True)
    await hass.async_block_till_done()
    assert not any(store.pcs[p].get("override") for p in DEMANDEURS)
    assert hass.states.get(capteur).state == "off"


async def test_messages_coupes_texte_par_defaut(hass, hass_storage, hass_client):
    """Sans le module « Messages », l'écran de blocage garde son texte habituel."""
    client = await hass_client()
    await _monter(hass, hass_storage)
    from custom_components.pc_parental import modules

    store = _coord(hass).store
    assert (await _geste(client, action="pause_generale", minutes=30))[0] == 200
    modules.regler(hass, store, set(modules.TOUS) - {"messages"}, "essai")
    r = await (await client.post("/api/pc_parental/poll", json={**ENFANT2, "version": "1", "host": "x",
                                                               "platform": "android", "type": "telephone"})).json()
    assert r["action"] == "lock" and "Tout est fermé" not in r["message"]
