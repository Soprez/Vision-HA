"""Réveil (lot 1, côté Home Assistant), dans un vrai Home Assistant.

Module (coupé par défaut, activation, coupure : entités désactivées, relevé vide) ; services reveil_* (créer,
modifier, activer, sauter, reprendre, sonner, reporter, arrêter, supprimer, lister, régler) ; entités par réveil
(apparition sans rechargement, état, prochain, appareils synchronisés) ; relevé (liste 48 h, ordres remis une
fois, accusé de réception) ; événements (une fois par occurrence, arrêt partagé, eid rejoué) ; réveils calculés
(entité, modèle, repli, échec heure_invalide) ; contrôle non_synchronise ; droits (automatisation, administrateur,
Demandeur avec ou sans reveil_propres, réveil imposé, utilisateur sans personne) ; porte de l'appli ; migration
des rôles ; fenêtre de silence.

    python -m pytest essai/reveil_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pathlib
import tempfile
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from homeassistant.core import Context
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError, Unauthorized
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import label_registry as lr
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

PARIS = ZoneInfo("Europe/Paris")
TELE = {"id": "t1", "secret": "c" * 32}
LUNDI_6H = datetime(2026, 10, 12, 6, 0, tzinfo=PARIS)
DIMANCHE_21H = datetime(2026, 10, 11, 21, 0, tzinfo=PARIS)


def _horloge(monkeypatch, quand: datetime):
    from custom_components.pc_parental import reveil

    etat = {"t": quand}
    monkeypatch.setattr(reveil, "maintenant", lambda: etat["t"])
    return etat


async def _monter(hass, hass_storage, module=True, roles=None, **plus):
    await hass.config.async_set_time_zone("Europe/Paris")
    # Un dossier de configuration à soi : le module y dépose ses blueprints (jamais dans celui, partagé, du banc).
    hass.config.config_dir = tempfile.mkdtemp(prefix="vision-banc-config-")
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-ALEX", ENFANT["secret"], person="person.alex", platform="android", type="telephone"),
            "e2": _fiche("e2", "TEL-SAM", ENFANT2["secret"], person="person.sam", platform="android", type="telephone"),
            "t1": _fiche("t1", "TV-ALEX", TELE["secret"], person="person.alex", platform="android", type="tv"),
            "p1": _fiche("p1", "PIXEL-DECIDEUR", PARENT["secret"], person="person.nolan", platform="android",
                         type="telephone", role="parent"),
        },
        **plus,
    }
    if module is not None:
        donnees["modules"] = {"reveil": module}
    if roles is not None:
        donnees["roles"] = roles
        donnees["vocabulaire_neutre"] = True
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    for p in ("alex", "sam", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    # Le premier relevé crée l'étiquette « Toujours autorisé », ce qui recharge Vision (comportement existant) :
    # elle existe d'avance, pour garder le même coordinateur pendant tout l'essai.
    lr.async_get(hass).async_create("Toujours autorisé")
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def _service(hass, nom, donnees=None, contexte=None, reponse=True):
    return await hass.services.async_call(DOMAIN, nom, donnees or {}, blocking=True, context=contexte,
                                          return_response=reponse)


TYPES = {"e1": "telephone", "e2": "telephone", "t1": "tv", "p1": "telephone"}


async def _releve(client, qui, **corps):
    # Le type déclaré doit rester celui de la fiche : un changement de type recharge Vision.
    r = await client.post("/api/pc_parental/poll", json={**qui, "platform": "android", "type": TYPES[qui["id"]], **corps})
    assert r.status == 200
    return await r.json()


async def _appli(client, qui, action, **corps):
    r = await client.post("/api/pc_parental/reveils", json={**qui, "action": action, **corps})
    return r.status, await r.json()


async def _personne_utilisateur(hass, prenom: str):
    """Un utilisateur HA non administrateur, lié à person.<prenom>."""
    utilisateur = await hass.auth.async_create_user(prenom.capitalize(), group_ids=["system-users"])
    hass.states.async_set(f"person.{prenom}", "home", {"friendly_name": prenom.capitalize(), "user_id": utilisateur.id})
    return Context(user_id=utilisateur.id)


def _ecouter(hass):
    vus = []
    for nom in ("vision_reveil_sonne", "vision_reveil_reporte", "vision_reveil_arrete", "vision_reveil_echec"):
        hass.bus.async_listen(nom, lambda e: vus.append((e.event_type.replace("vision_reveil_", ""), dict(e.data))))
    return vus


# ----------------------------------------------------------------------------- Module


async def test_module_coupe_par_defaut(hass, hass_storage, hass_client):
    from custom_components.pc_parental import modules

    coord = await _monter(hass, hass_storage, module=None)
    assert "reveil" not in modules.actifs(coord.store)
    assert "reveil" not in modules.PRESETS["tout"] and "reveil" in modules.CATALOGUE
    with pytest.raises(HomeAssistantError, match="Réveil"):
        await _service(hass, "reveil_creer", {"demandeur": "person.alex"})
    client = await hass_client()
    bloc = (await _releve(client, ENFANT))["reveils"]
    assert bloc == {"actif": False, "version": "", "liste": [], "ordres": []}
    s, r = await _appli(client, ENFANT, "liste")
    assert s == 403 and r["error"] == "module"
    assert hass.states.get("binary_sensor.vision_fenetre_reveil") is None or \
        hass.states.get("binary_sensor.vision_fenetre_reveil").state in ("off", "unavailable")


async def test_creer_entites_releve_et_accuse(hass, hass_storage, hass_client, monkeypatch):
    horloge = _horloge(monkeypatch, DIMANCHE_21H)
    coord = await _monter(hass, hass_storage)
    rep = await _service(hass, "reveil_creer", {"demandeur": "Alex", "nom": "Collège", "heure": "07:00",
                                                "jours": ["lun", "mar", "mer", "jeu", "ven"],
                                                "source": "url", "uri": "https://radio.example/flux.mp3"})
    ident = rep["id"]
    await hass.async_block_till_done()
    r = coord.store.reveils[ident]
    assert r["origine"] == "automatisation" and r["demandeur"] == "person.alex" and r["appareils"] == []
    # Entités apparues sans recharger l'intégration.
    assert hass.states.get("switch.vision_reveil_college").state == "on"
    assert hass.states.get("sensor.vision_reveil_college_prochain").state == "2026-10-12T05:00:00+00:00"
    assert hass.states.get("sensor.vision_reveil_college_prochain").attributes["raison"] == "hebdo"
    assert hass.states.get("sensor.vision_reveil_college_etat").state == "arme"
    assert hass.states.get("sensor.vision_reveil_college_source").state == "https://radio.example/flux.mp3"
    appareils = hass.states.get("sensor.vision_reveil_college_appareils")
    assert appareils.state == "TEL-ALEX" and appareils.attributes["ids"] == ["e1"]  # par défaut : ses téléphones
    assert appareils.attributes["synchronises"] == []
    # Relevé : le téléphone de Alex reçoit le réveil (48 h), pas la télé, pas les autres.
    client = await hass_client()
    bloc = (await _releve(client, ENFANT))["reveils"]
    assert bloc["actif"] is True and bloc["synchro_avant_min"] == 10 and bloc["ordres"] == []
    (el,) = bloc["liste"]
    assert el["id"] == ident and el["repli"] == "sonnerie" and el["source"] == {"type": "url", "uri": "https://radio.example/flux.mp3"}
    assert [o["heure"] for o in el["occurrences"]] == ["2026-10-12T07:00:00+02:00", "2026-10-13T07:00:00+02:00"]
    assert el["arret_auto_min"] == 30 and el["volume"]["fin"] == 60 and el["signature"]
    assert (await _releve(client, TELE))["reveils"]["liste"] == []
    assert (await _releve(client, ENFANT2))["reveils"]["liste"] == []
    # Accusé de réception : l'appareil est synchronisé ; une modification le désynchronise.
    await _releve(client, ENFANT, reveils={"recu": {ident: el["signature"]}})
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_college_appareils").attributes["synchronises"] == ["e1"]
    # Le temps qui passe (nouvelle occurrence dans la fenêtre) ne désynchronise pas : seule la version du bloc change.
    version = bloc["version"]
    horloge["t"] = DIMANCHE_21H + timedelta(days=1, hours=10)
    bloc2 = (await _releve(client, ENFANT))["reveils"]
    assert bloc2["version"] != version and bloc2["liste"][0]["signature"] == el["signature"]
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_college_appareils").attributes["synchronises"] == ["e1"]
    horloge["t"] = DIMANCHE_21H
    await _service(hass, "reveil_modifier", {"reveil": "switch.vision_reveil_college", "heure": "06:45", "appareils": ["TEL-ALEX", "TV-ALEX"]})
    await hass.async_block_till_done()
    assert hass.states.get("sensor.vision_reveil_college_prochain").state == "2026-10-12T04:45:00+00:00"
    assert hass.states.get("sensor.vision_reveil_college_appareils").attributes["synchronises"] == []
    assert hass.states.get("sensor.vision_reveil_college_appareils").attributes["ids"] == ["e1", "t1"]
    assert len((await _releve(client, TELE))["reveils"]["liste"]) == 1
    # Un appareil d'un autre Demandeur est refusé.
    with pytest.raises(ServiceValidationError):
        await _service(hass, "reveil_modifier", {"reveil": ident, "appareils": ["TEL-SAM"]})
    # Lister.
    liste = (await _service(hass, "reveil_lister"))["reveils"]
    assert [x["nom"] for x in liste] == ["Collège"] and liste[0]["declenchement"]["heure"] == "06:45"


async def test_activer_sauter_reprendre_supprimer(hass, hass_storage, hass_client, monkeypatch):
    _horloge(monkeypatch, DIMANCHE_21H)
    coord = await _monter(hass, hass_storage)
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Matin"}))["id"]
    await hass.async_block_till_done()
    rep = await _service(hass, "reveil_sauter", {"reveil": ident})
    assert rep["date"] == "2026-10-12"
    await coord.async_refresh()
    etat = hass.states.get("sensor.vision_reveil_matin_prochain")
    assert etat.state == "2026-10-13T05:00:00+00:00" and etat.attributes["sautees"] == ["2026-10-12"]
    client = await hass_client()
    assert [o["date"] for o in (await _releve(client, ENFANT))["reveils"]["liste"][0]["occurrences"]] == ["2026-10-13"]
    assert (await _service(hass, "reveil_reprendre", {"reveil": ident}))["date"] == "2026-10-12"
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_matin_prochain").state == "2026-10-12T05:00:00+00:00"
    # L'interrupteur et les services activer / désactiver.
    await hass.services.async_call("switch", "turn_off", {"entity_id": "switch.vision_reveil_matin"}, blocking=True)
    assert coord.store.reveils[ident]["actif"] is False
    assert hass.states.get("sensor.vision_reveil_matin_etat").state == "inactif"
    assert (await _releve(client, ENFANT))["reveils"]["liste"] == []
    await _service(hass, "reveil_activer", {"reveil": "Matin"}, reponse=False)
    assert hass.states.get("switch.vision_reveil_matin").state == "on"
    # Supprimer : entités et appareil HA retirés.
    await _service(hass, "reveil_supprimer", {"reveil": ident}, reponse=False)
    await hass.async_block_till_done()
    assert ident not in coord.store.reveils
    assert er.async_get(hass).async_get_entity_id("switch", DOMAIN, f"{DOMAIN}_reveil_{ident}_actif") is None
    assert hass.states.get("switch.vision_reveil_matin") is None
    with pytest.raises(ServiceValidationError):
        await _service(hass, "reveil_activer", {"reveil": ident}, reponse=False)


async def test_evenements_une_fois_et_arret_partage(hass, hass_storage, hass_client, monkeypatch):
    horloge = _horloge(monkeypatch, LUNDI_6H)
    coord = await _monter(hass, hass_storage)
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Collège",
                                                   "appareils": ["e1", "t1"]}))["id"]
    await hass.async_block_till_done()
    vus = _ecouter(hass)
    client = await hass_client()
    occ = f"{ident}@2026-10-12"
    horloge["t"] = LUNDI_6H.replace(hour=7)
    sonne = {"eid": "a1", "type": "sonne", "reveil": ident, "occurrence": occ, "heure_prevue": "2026-10-12T07:00:00+02:00",
             "heure_reelle": "2026-10-12T07:00:02+02:00"}
    await _releve(client, ENFANT, reveils={"evenements": [sonne]})
    await _releve(client, ENFANT, reveils={"evenements": [sonne]})  # même eid renvoyé : ignoré
    await _releve(client, TELE, reveils={"evenements": [{**sonne, "eid": "b1"}]})  # 2e appareil : pas de 2e événement
    await hass.async_block_till_done()
    assert [v[0] for v in vus] == ["sonne"]
    assert vus[0][1] == {"reveil": ident, "nom": "Collège", "demandeur": "person.alex", "appareil": "TEL-ALEX",
                         "appareil_id": "e1", "source": "sonnerie", "occurrence": occ,
                         "heure_prevue": "2026-10-12T07:00:00+02:00", "heure_reelle": "2026-10-12T07:00:02+02:00"}
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_college_etat").state == "sonne"
    # Report sur le téléphone : un événement par numéro ; la télé reçoit l'ordre de reporter.
    rep = {"eid": "a2", "type": "reporte", "reveil": ident, "occurrence": occ, "nombre": 1,
           "jusqu_a": "2026-10-12T07:09:00+02:00", "par": "appareil"}
    await _releve(client, ENFANT, reveils={"evenements": [rep]})
    ordres = (await _releve(client, TELE))["reveils"]["ordres"]
    assert [(o["type"], o["par"], o["jusqu_a"]) for o in ordres] == [("reporter", "partage", "2026-10-12T07:09:00+02:00")]
    assert (await _releve(client, TELE))["reveils"]["ordres"] == []  # remis une seule fois
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_college_etat").state == "reporte"
    # Arrêt sur la télé : la télé l'annonce, le téléphone reçoit l'ordre d'arrêter.
    await _releve(client, TELE, reveils={"evenements": [{"eid": "b2", "type": "arrete", "reveil": ident, "occurrence": occ,
                                                         "par": "appareil", "horodatage_appareil": "2026-10-12T07:12:00+02:00"}]})
    ordres = (await _releve(client, ENFANT))["reveils"]["ordres"]
    assert [(o["type"], o["reveil"], o["occurrence"]) for o in ordres] == [("arreter", ident, occ)]
    await hass.async_block_till_done()
    assert [v[0] for v in vus] == ["sonne", "reporte", "arrete"]
    assert vus[1][1]["nombre"] == 1 and vus[2][1]["par"] == "appareil" and vus[2][1]["appareil"] == "TV-ALEX"
    assert vus[2][1]["horodatage_appareil"] == "2026-10-12T07:12:00+02:00"
    # Le téléphone confirme l'arrêt partagé : pas de second événement.
    await _releve(client, ENFANT, reveils={"evenements": [{"eid": "a3", "type": "arrete", "reveil": ident, "occurrence": occ, "par": "partage"}]})
    await hass.async_block_till_done()
    assert [v[0] for v in vus] == ["sonne", "reporte", "arrete"]
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_college_etat").state == "arrete"
    # Échec de source avec repli joué : émis.
    await _releve(client, ENFANT, reveils={"evenements": [{"eid": "a4", "type": "echec", "reveil": ident, "occurrence": occ,
                                                           "raison": "source", "detail": "flux muet 20 s", "repli_joue": True}]})
    await hass.async_block_till_done()
    assert vus[-1][0] == "echec" and vus[-1][1]["raison"] == "source" and vus[-1][1]["repli_joue"] is True
    # Permission d'alarme exacte retirée : échec « permission ».
    await _releve(client, ENFANT, reveils={"capacites": {"alarme_exacte": False, "plein_ecran": True}})
    await hass.async_block_till_done()
    assert vus[-1][1]["raison"] == "permission" and vus[-1][1]["appareil_id"] == "e1"


async def test_ordres_sonner_reporter_arreter(hass, hass_storage, hass_client, monkeypatch):
    horloge = _horloge(monkeypatch, LUNDI_6H)
    coord = await _monter(hass, hass_storage)
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Matin"}))["id"]
    client = await hass_client()
    rep = await _service(hass, "reveil_sonner", {"reveil": ident, "source": "url", "uri": "https://radio.example/a"})
    assert rep["appareils"] == ["e1"] and rep["occurrence"].startswith("ordre:")
    (o,) = (await _releve(client, ENFANT))["reveils"]["ordres"]
    assert o["type"] == "sonner" and o["source"] == {"type": "url", "uri": "https://radio.example/a"} and o["repli"] == "sonnerie"
    # Sonner sans réveil, pour un Demandeur, sur un appareil choisi.
    rep = await _service(hass, "reveil_sonner", {"demandeur": "person.alex", "appareils": ["TV-ALEX"]})
    assert rep["appareils"] == ["t1"]
    (o,) = (await _releve(client, TELE))["reveils"]["ordres"]
    assert o["reveil"] == "" and o["source"] == {"type": "sonnerie"}
    # Un ordre « sonner » trop vieux n'est plus remis ; « arrêter » l'est toujours.
    await _service(hass, "reveil_sonner", {"reveil": ident})
    await _service(hass, "reveil_arreter", {"demandeur": "Alex"})
    horloge["t"] = LUNDI_6H + timedelta(minutes=20)
    ordres = (await _releve(client, ENFANT))["reveils"]["ordres"]
    assert [x["type"] for x in ordres] == ["arreter"] and ordres[0]["par"] == "service"
    rep = await _service(hass, "reveil_reporter", {"reveil": ident, "minutes": 5})
    assert rep["jusqu_a"] == (LUNDI_6H + timedelta(minutes=25)).isoformat()
    (o,) = (await _releve(client, ENFANT))["reveils"]["ordres"]
    assert o["type"] == "reporter" and o["minutes"] == 5
    with pytest.raises(ServiceValidationError):
        await _service(hass, "reveil_sonner", {})


async def test_reveil_calcule_entite_et_modele(hass, hass_storage, monkeypatch):
    _horloge(monkeypatch, DIMANCHE_21H)
    coord = await _monter(hass, hass_storage)
    hass.states.async_set("sensor.premier_cours", "2026-10-12T06:00:00+00:00", {"device_class": "timestamp"})
    vus = _ecouter(hass)
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Pronote", "type": "entite",
                                                   "entite": "sensor.premier_cours", "decalage": -60}))["id"]
    await hass.async_block_till_done()
    assert coord.store.reveils[ident]["calcul"]["raison"] == "calculee"
    assert hass.states.get("sensor.vision_reveil_pronote_prochain").state == "2026-10-12T05:00:00+00:00"
    # La source change : réévaluation aussitôt.
    hass.states.async_set("sensor.premier_cours", "2026-10-12T07:00:00+00:00", {"device_class": "timestamp"})
    await hass.async_block_till_done()
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_pronote_prochain").state == "2026-10-12T06:00:00+00:00"
    # Hors bornes, sans repli : pas d'occurrence et un seul vision_reveil_echec (heure_invalide).
    hass.states.async_set("sensor.premier_cours", "2026-10-12T12:00:00+00:00", {"device_class": "timestamp"})
    await hass.async_block_till_done()
    hass.states.async_set("sensor.premier_cours", "2026-10-12T12:00:00+00:00", {"device_class": "timestamp", "x": 1})
    await hass.async_block_till_done()
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_pronote_prochain").state == "unknown"
    assert [(v[0], v[1]["raison"]) for v in vus] == [("echec", "heure_invalide")]
    # Avec repli : l'heure de repli.
    await _service(hass, "reveil_modifier", {"reveil": ident, "repli": "07:15"})
    await hass.async_block_till_done()
    assert coord.store.reveils[ident]["calcul"]["raison"] == "repli"
    assert hass.states.get("sensor.vision_reveil_pronote_prochain").attributes["raison"] == "repli"
    # Modèle Jinja.
    hass.states.async_set("input_datetime.reveil_fixe", "2026-10-12 06:30:00")
    ident2 = (await _service(hass, "reveil_creer", {"demandeur": "person.sam", "nom": "Modèle", "type": "modele",
                                                    "modele": "{{ states('input_datetime.reveil_fixe') }}"}))["id"]
    await hass.async_block_till_done()
    assert coord.store.reveils[ident2]["calcul"]["heure"] == "2026-10-12T06:30:00+02:00"
    hass.states.async_set("input_datetime.reveil_fixe", "2026-10-12 06:40:00")
    await hass.async_block_till_done()
    assert coord.store.reveils[ident2]["calcul"]["heure"] == "2026-10-12T06:40:00+02:00"


async def test_controle_non_synchronise(hass, hass_storage, hass_client, monkeypatch):
    horloge = _horloge(monkeypatch, LUNDI_6H)
    coord = await _monter(hass, hass_storage)
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Matin",
                                                   "appareils": ["e1", "t1"]}))["id"]
    client = await hass_client()
    el = (await _releve(client, ENFANT))["reveils"]["liste"][0]
    await _releve(client, ENFANT, reveils={"recu": {ident: el["signature"]}, "armees": [o["id"] for o in el["occurrences"]]})
    # La télé a la bonne configuration mais n'a pas armé l'occurrence du jour : en retard aussi.
    await _releve(client, TELE, reveils={"recu": {ident: el["signature"]}, "armees": []})
    vus = _ecouter(hass)
    horloge["t"] = LUNDI_6H.replace(hour=6, minute=40)
    assert coord.reveil.controler_synchro(horloge["t"]) is False  # trop tôt
    horloge["t"] = LUNDI_6H.replace(hour=6, minute=52)
    assert coord.reveil.controler_synchro(horloge["t"]) is True
    assert coord.reveil.controler_synchro(horloge["t"]) is False  # une seule fois par occurrence
    await hass.async_block_till_done()
    assert [(v[0], v[1]["raison"], v[1]["detail"]) for v in vus] == [("echec", "non_synchronise", "TV-ALEX")]
    await coord.async_refresh()
    assert hass.states.get("sensor.vision_reveil_matin_etat").state == "non_synchronise"
    assert hass.states.get("binary_sensor.vision_fenetre_reveil").state == "on"


# ----------------------------------------------------------------------------- Droits


async def test_droits_des_services(hass, hass_storage, hass_client, monkeypatch):
    await hass_client()  # crée le propriétaire : l'utilisateur de Alex n'est donc pas administrateur
    _horloge(monkeypatch, DIMANCHE_21H)
    coord = await _monter(hass, hass_storage)
    alex = await _personne_utilisateur(hass, "alex")
    # Demandeur sans « reveil_propres » : rien créer, mais arrêter et reporter les siens.
    with pytest.raises(Unauthorized):
        await _service(hass, "reveil_creer", {"demandeur": "person.alex"}, alex)
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Matin"}))["id"]
    autre = (await _service(hass, "reveil_creer", {"demandeur": "person.sam", "nom": "Sam"}))["id"]
    await _service(hass, "reveil_arreter", {"reveil": ident}, alex)
    await _service(hass, "reveil_reporter", {"reveil": ident}, alex)
    for nom, donnees in (("reveil_modifier", {"reveil": ident, "heure": "06:00"}), ("reveil_sauter", {"reveil": ident}),
                         ("reveil_sonner", {"reveil": ident}), ("reveil_supprimer", {"reveil": ident}),
                         ("reveil_arreter", {"reveil": autre}), ("reveil_regler", {"arret_auto_min": 10})):
        with pytest.raises(Unauthorized):
            await _service(hass, nom, donnees, alex, reponse=nom != "reveil_supprimer")
    with pytest.raises(Unauthorized):
        await hass.services.async_call("switch", "turn_off", {"entity_id": "switch.vision_reveil_matin"}, blocking=True, context=alex)
    assert [r["nom"] for r in (await _service(hass, "reveil_lister", {}, alex))["reveils"]] == ["Matin"]
    # Le Décideur accorde « reveil_propres » au rôle Demandeur : ses propres réveils, pas ceux des autres, jamais imposés.
    coord.store.roles["enfant"]["fonctions"].append("reveil_propres")
    rep = await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Le mien"}, alex)
    assert coord.store.reveils[rep["id"]]["origine"] == "home_assistant"
    await _service(hass, "reveil_modifier", {"reveil": ident, "heure": "06:30"}, alex)
    await hass.services.async_call("switch", "turn_off", {"entity_id": "switch.vision_reveil_matin"}, blocking=True, context=alex)
    for donnees in ({"demandeur": "person.sam"}, {"demandeur": "person.alex", "impose": True}):
        with pytest.raises(Unauthorized):
            await _service(hass, "reveil_creer", donnees, alex)
    with pytest.raises(Unauthorized):
        await _service(hass, "reveil_modifier", {"reveil": ident, "demandeur": "person.sam"}, alex)
    # Réveil imposé (par une automatisation) : reporter et arrêter seulement.
    await _service(hass, "reveil_modifier", {"reveil": ident, "impose": True})
    with pytest.raises(Unauthorized):
        await _service(hass, "reveil_desactiver", {"reveil": ident}, alex, reponse=False)
    await _service(hass, "reveil_arreter", {"reveil": ident}, alex)
    # Utilisateur sans personne liée : refusé ; administrateur : tout.
    sans = await hass.auth.async_create_user("Sans", group_ids=["system-users"])
    with pytest.raises(Unauthorized):
        await _service(hass, "reveil_arreter", {"reveil": ident}, Context(user_id=sans.id))
    admin = await hass.auth.async_create_user("Admin", group_ids=["system-admin"])
    await _service(hass, "reveil_desactiver", {"reveil": ident}, Context(user_id=admin.id), reponse=False)
    assert coord.store.reveils[ident]["actif"] is False
    assert (await _service(hass, "reveil_regler", {"arret_auto_min": 20}, Context(user_id=admin.id)))["reglages"]["arret_auto_min"] == 20


async def test_porte_de_l_appli(hass, hass_storage, hass_client, monkeypatch):
    _horloge(monkeypatch, DIMANCHE_21H)
    coord = await _monter(hass, hass_storage)
    client = await hass_client()
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Matin"}))["id"]
    await _service(hass, "reveil_creer", {"demandeur": "person.sam", "nom": "Sam"})
    # Le Décideur voit tout et peut tout.
    s, r = await _appli(client, PARENT, "liste")
    assert s == 200 and r["droits"] == {"gerer": True, "propres": False} and len(r["reveils"]) == 2
    # Les noms affichés des personnes citées (l'appli ne montre jamais « person.… »).
    assert r["noms"]["person.alex"] == "Alex" and r["noms"]["person.sam"] == "Sam" and r["noms"]["person.nolan"] == "Nolan"
    assert {d["personne"] for d in r["demandeurs"]} == {"person.alex", "person.sam", "person.nolan"}
    s, r = await _appli(client, PARENT, "creer", reveil={"demandeur": "person.sam", "nom": "Sam 2",
                                                         "declenchement": {"heure": "06:50", "jours": [0, 1]}})
    assert s == 200 and r["reveil"]["declenchement"]["heure"] == "06:50" and r["reveil"]["automatisation"] is False
    assert coord.store.reveils[r["reveil"]["id"]]["origine"] == "appli"
    # Alex voit les siens (marqués « automatisation »), peut arrêter, pas modifier.
    s, r = await _appli(client, ENFANT, "liste")
    assert s == 200 and [x["nom"] for x in r["reveils"]] == ["Matin"] and r["demandeurs"] == []
    assert r["reveils"][0]["automatisation"] is True
    assert r["reveils"][0]["peut"]["arreter"] and not r["reveils"][0]["peut"]["modifier"]
    s, r = await _appli(client, ENFANT, "modifier", reveil_id=ident, reveil={"nom": "Pirate"})
    assert s == 403 and r["error"] == "droits"
    s, r = await _appli(client, ENFANT, "creer", reveil={"nom": "Le mien"})
    assert s == 403
    s, r = await _appli(client, ENFANT, "arreter", reveil_id=ident)
    assert s == 200 and r["reveil"]["etat"] in ("arrete", "arme")
    s, r = await _appli(client, ENFANT2, "arreter", reveil_id=ident)
    assert s == 403
    # Avec « reveil_propres » : « Pas de réveil demain » et création des siens.
    coord.store.roles["enfant"]["fonctions"].append("reveil_propres")
    s, r = await _appli(client, ENFANT, "sauter", reveil_id=ident)
    assert s == 200 and r["reveil"]["sautees"] == ["2026-10-12"]
    s, r = await _appli(client, ENFANT, "creer", reveil={"nom": "Le mien", "appareils": ["t1"]})
    assert s == 200 and r["reveil"]["demandeur"] == "person.alex" and r["reveil"]["appareils"] == ["t1"]
    s, r = await _appli(client, ENFANT, "creer", reveil={"nom": "X", "declenchement": {"heure": "29:00"}})
    assert s == 400 and r["error"] == "invalide" and r["cle"] == "heure"
    s, r = await _appli(client, ENFANT, "modifier", reveil_id="rv_inconnu", reveil={})
    assert s == 404
    s, r = await _appli(client, {"id": "e1", "secret": "faux"}, "liste")
    assert s == 401


async def test_migration_des_roles_existants(hass, hass_storage):
    roles = {
        "parent": {"nom": "Décideur", "fonctions": ["gerer", "approuver", "regles", "protege"], "pause_max": 60},
        "enfant": {"nom": "Demandeur", "fonctions": ["protege", "temps"], "pause_max": 60},
        "rprof": {"nom": "Enseignant", "fonctions": ["gerer"], "pause_max": 60},
    }
    coord = await _monter(hass, hass_storage, roles=roles)
    assert "reveil_gerer" in coord.store.roles["parent"]["fonctions"]
    assert "reveil_gerer" not in coord.store.roles["enfant"]["fonctions"]
    assert "reveil_gerer" not in coord.store.roles["rprof"]["fonctions"]  # seul le rôle Décideur fourni
    assert hass_storage[DOMAIN]["data"]["roles_reveil"] is True
    # Retiré ensuite par l'administrateur : la migration ne repasse pas.
    from custom_components.pc_parental import roles as r

    coord.store.roles["parent"]["fonctions"].remove("reveil_gerer")
    assert r.fonctions_reveil(coord.store) is False and "reveil_gerer" not in coord.store.roles["parent"]["fonctions"]


async def test_coupure_du_module_desactive_les_entites(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import modules

    _horloge(monkeypatch, DIMANCHE_21H)
    coord = await _monter(hass, hass_storage)
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Matin"}))["id"]
    await hass.async_block_till_done()
    modules.regler(hass, coord.store, modules.actifs(coord.store) - {"reveil"}, "essai")
    assert modules.appliquer_entites(hass, coord.store) >= 6  # 5 entités du réveil + la fenêtre
    registre = er.async_get(hass)
    entree = registre.async_get(registre.async_get_entity_id("switch", DOMAIN, f"{DOMAIN}_reveil_{ident}_actif"))
    assert entree.disabled_by is er.RegistryEntryDisabler.INTEGRATION
    client = await hass_client()
    assert (await _releve(client, ENFANT))["reveils"]["actif"] is False
    assert ident in coord.store.reveils  # réglages gardés
    modules.regler(hass, coord.store, modules.actifs(coord.store) | {"reveil"}, "essai")
    modules.appliquer_entites(hass, coord.store)
    entree = registre.async_get(entree.entity_id)
    assert entree.disabled_by is None
    assert len((await _releve(client, ENFANT))["reveils"]["liste"]) == 1


# ----------------------------------------------------------------------------- Blueprint fourni


async def test_blueprint_jours_d_ecole(hass, hass_storage):
    """Le blueprint d'exemple, exécuté : jour d'école → ponctuel à premier cours − marge ; vacances → sauté ;
    mode « heure fixe » ; agenda vide sans repli → sauté. Déposé sur demande (reveil_installer_modeles), jamais écrasé."""
    from homeassistant.core import SupportsResponse
    from homeassistant.setup import async_setup_component
    from homeassistant.util import dt as dt_util

    coord = await _monter(hass, hass_storage)
    chemin = pathlib.Path(hass.config.path("blueprints", "automation", DOMAIN, "reveil_jours_ecole.yaml"))
    assert not chemin.exists()  # rien n'est déposé sans qu'on le demande
    assert (await _service(hass, "reveil_installer_modeles"))["deposes"] == ["reveil_jours_ecole.yaml"]
    chemin.write_text(chemin.read_text(encoding="utf-8") + "# retouche\n", encoding="utf-8")
    assert (await _service(hass, "reveil_installer_modeles"))["deposes"] == []  # jamais écrasé
    assert "# retouche" in chemin.read_text(encoding="utf-8")

    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "École"}))["id"]
    await hass.async_block_till_done()
    agendas = {"calendar.ouvres": True, "calendar.vacances": False, "calendar.cours": [("08:00", "Maths", ""), ("07:30", "Sport", "Cours annulé"),
                                                                                    ("07:00", "Annulé : SVT", "")]}

    async def get_events(call):
        debut = call.data["start_date_time"]
        debut = dt_util.parse_datetime(debut) if isinstance(debut, str) else debut
        jour = dt_util.as_local(debut).date()
        sortie = {}
        for eid in call.data["entity_id"]:
            v = agendas[eid]
            if isinstance(v, list):
                sortie[eid] = {"events": [{"start": f"{jour}T{h}:00+02:00", "end": f"{jour}T{h}:00+02:00", "summary": t,
                                           "description": d} for h, t, d in v]}
            else:
                sortie[eid] = {"events": [{"start": str(jour), "end": str(jour), "summary": "x"}] if v else []}
        return sortie

    hass.services.async_register("calendar", "get_events", get_events, supports_response=SupportsResponse.ONLY)
    entrees = {"reveil": "switch.vision_reveil_ecole", "jours_ouvres": "calendar.ouvres", "vacances": ["calendar.vacances"],
               "cours": ["calendar.cours"], "marge": 50}
    assert await async_setup_component(hass, "automation", {"automation": [
        {"id": "ecole", "alias": "École", "use_blueprint": {"path": f"{DOMAIN}/reveil_jours_ecole.yaml", "input": entrees}},
        {"id": "fixe", "alias": "Fixe", "use_blueprint": {"path": f"{DOMAIN}/reveil_jours_ecole.yaml",
                                                          "input": {**entrees, "mode_heure": "fixe", "heure_fixe": "06:40:00"}}},
    ]})
    await hass.async_block_till_done()
    demain = (dt_util.now() + timedelta(days=1)).date().isoformat()

    async def lancer(alias):
        await hass.services.async_call("automation", "trigger", {"entity_id": f"automation.{alias}"}, blocking=True)
        await hass.async_block_till_done()
        return coord.store.reveils[ident]

    r = await lancer("ecole")  # les cours « annulé » (titre ou description) ne comptent pas : 08:00 − 50 min
    assert r["declenchement"]["type"] == "ponctuel" and r["declenchement"]["date"] == demain
    assert r["declenchement"]["heure"] == "07:10" and demain not in r["sauter"]
    agendas["calendar.vacances"] = True
    r = await lancer("ecole")
    assert demain in r["sauter"]
    agendas["calendar.vacances"] = False
    r = await lancer("fixe")
    assert r["declenchement"]["heure"] == "06:40" and demain not in r["sauter"]
    agendas["calendar.cours"] = []  # agenda vide, sans repli : pas de réveil ce jour-là
    r = await lancer("ecole")
    assert demain in r["sauter"]
    agendas["calendar.ouvres"] = False  # jour non ouvré
    r = await lancer("fixe")
    assert demain in r["sauter"]


# ----------------------------------------------------------------------------- Music Assistant (lot 10, V1)


async def test_source_music_assistant_sur_son_enceinte(hass, hass_storage, hass_client, monkeypatch):
    """Source « ma » avec une enceinte : HA lance la lecture quand l'appareil sonne, met en pause au report, arrête à
    l'arrêt. Music Assistant en échec : vision_reveil_echec (la sonnerie de l'appareil, elle, continue)."""
    from homeassistant.core import ServiceCall, SupportsResponse

    from custom_components.pc_parental import reveil

    _horloge(monkeypatch, LUNDI_6H.replace(hour=7))
    await _monter(hass, hass_storage)
    appels = []

    async def noter(call: ServiceCall):
        appels.append((call.domain, call.service, dict(call.data)))
    hass.services.async_register("music_assistant", "play_media", noter, supports_response=SupportsResponse.NONE)
    for s in ("media_pause", "media_stop"):
        hass.services.async_register("media_player", s, noter)
    ident = (await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Playlist", "source": "ma",
                                                   "uri": "library://playlist/10", "lecteur": "media_player.chambre"}))["id"]
    client = await hass_client()
    occ = f"{ident}@2026-10-12"
    ev = {"reveil": ident, "occurrence": occ}
    await _releve(client, ENFANT, reveils={"evenements": [{**ev, "eid": "m1", "type": "sonne"}]})
    await _releve(client, ENFANT, reveils={"evenements": [{**ev, "eid": "m2", "type": "reporte", "nombre": 1}]})
    await _releve(client, ENFANT, reveils={"evenements": [{**ev, "eid": "m3", "type": "arrete", "par": "appareil"}]})
    await hass.async_block_till_done()
    assert [(d, s) for d, s, _ in appels] == [("music_assistant", "play_media"), ("media_player", "media_pause"),
                                              ("media_player", "media_stop")]
    assert appels[0][2]["media_id"] == "library://playlist/10" and appels[0][2]["media_type"] == "playlist"
    assert appels[0][2]["entity_id"] in ("media_player.chambre", ["media_player.chambre"])
    assert reveil.type_media("ytmusic://track/abc") == "track" and reveil.type_media("x://podcast/1") == "podcast"
    assert reveil.type_media("bizarre") == "track"
    # Une source « sonnerie » ne touche à rien ; Music Assistant en panne : échec signalé.
    vus = _ecouter(hass)
    hass.services.async_remove("music_assistant", "play_media")
    await _releve(client, ENFANT, reveils={"evenements": [{**ev, "eid": "m4", "type": "sonne", "occurrence": f"{ident}@2026-10-13"}]})
    await hass.async_block_till_done()
    assert ("echec", "music_assistant") in [(v[0], v[1].get("detail")) for v in vus]


async def test_onglet_reveils_du_tableau(hass, hass_storage, monkeypatch):
    """L'onglet « Réveils » du tableau de Vision : liste en direct (modèle rendu), interrupteurs, aide ; absent si le
    module est coupé ; l'empreinte du tableau suit les réveils."""
    from homeassistant.helpers.template import Template

    from custom_components.pc_parental import dashboard, modules

    _horloge(monkeypatch, DIMANCHE_21H)
    coord = await _monter(hass, hass_storage)
    vide = next(v for v in dashboard.build(hass, coord)["views"] if v["path"] == "reveils")
    rendu = Template(vide["sections"][0]["cards"][0]["content"], hass).async_render(parse_result=False)
    assert "Aucun réveil" in rendu or "No alarm" in rendu
    avant = dashboard.signature(coord)
    await _service(hass, "reveil_creer", {"demandeur": "person.alex", "nom": "Collège"})
    await hass.async_block_till_done()
    await coord.async_refresh()
    assert dashboard.signature(coord) != avant
    vue = next(v for v in dashboard.build(hass, coord)["views"] if v["path"] == "reveils")
    assert {"type": "tile", "entity": "switch.vision_reveil_college"} in vue["sections"][1]["cards"]
    rendu = Template(vue["sections"][0]["cards"][0]["content"], hass).async_render(parse_result=False)
    assert "| Collège | Alex |" in rendu and "12/10 07:00" in rendu, rendu
    assert "pc_parental.reveil_creer" in vue["sections"][2]["cards"][0]["content"]
    modules.regler(hass, coord.store, modules.actifs(coord.store) - {"reveil"}, "essai")
    assert "reveils" not in [v["path"] for v in dashboard.build(hass, coord)["views"]]
