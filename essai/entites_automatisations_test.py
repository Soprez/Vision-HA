"""1.94 · Entités pour les automatisations (lot E1) : réveil (boutons et prochain réveil par personne), concentration,
temps restant, écran des télés, état de la Party (gardé au redémarrage) et ses services, événement des widgets, seuil de
nuit du niveau sonore en dBFS.

    python -m pytest essai/entites_automatisations_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time
from datetime import timedelta

from homeassistant.helpers import entity_registry as er
from homeassistant.util import dt as dt_util
from pytest_homeassistant_custom_component.common import async_fire_time_changed

from reveil_test import (  # noqa: F401  (fixtures du banc)
    DIMANCHE_21H, DOMAIN, ENFANT, PARENT, TELE, _horloge, _integrations, _monter, _releve, _service,
    expected_lingering_tasks, expected_lingering_timers,
)


def _eid(hass, plateforme, uid):
    eid = er.async_get(hass).async_get_entity_id(plateforme, DOMAIN, f"{DOMAIN}_{uid}")
    assert eid, f"entité {plateforme} {uid} absente"
    return eid


async def _reveil(hass, **plus):
    donnees = {"demandeur": "person.alex", "nom": "Collège", "heure": "07:00", "jours": ["lun", "mar", "mer", "jeu", "ven"],
               "source": "url", "uri": "https://radio.example/flux.mp3", **plus}
    return (await _service(hass, "reveil_creer", donnees))["id"]


# ------------------------------------------------------------------ réveil


async def test_prochain_reveil_par_personne(hass, hass_storage, monkeypatch):
    _horloge(monkeypatch, DIMANCHE_21H)
    await _monter(hass, hass_storage)
    await _reveil(hass)
    await _reveil(hass, nom="Sport", heure="06:30", jours=["lun"])
    await hass.async_block_till_done()
    etat = hass.states.get(_eid(hass, "sensor", "prochain_reveil_alex"))
    assert etat.state == "2026-10-12T04:30:00+00:00"          # le plus proche des deux (lundi 6 h 30, heure de Paris)
    assert etat.attributes["reveil"] == "Sport" and etat.attributes["personne"] == "person.alex"
    assert etat.attributes["appareils"] and etat.attributes["source"]
    assert hass.states.get(_eid(hass, "sensor", "prochain_reveil_sam")).state == "unknown"   # aucun réveil armé
    assert etat.name.endswith("Alex") or "Alex" in etat.attributes.get("friendly_name", "")


async def test_boutons_reporter_et_arreter_visent_le_reveil_de_la_personne(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental import reveil

    horloge = _horloge(monkeypatch, DIMANCHE_21H)
    coord = await _monter(hass, hass_storage)
    ident = await _reveil(hass)
    await hass.async_block_till_done()
    arreter = _eid(hass, "button", "reveil_arreter_alex")
    reporter = _eid(hass, "button", "reveil_reporter_alex")
    assert hass.states.get(arreter).state != "unavailable" and hass.states.get(reporter).state != "unavailable"
    # Personne sans réveil armé : boutons indisponibles.
    assert hass.states.get(_eid(hass, "button", "reveil_arreter_sam")).state == "unavailable"
    # Le réveil sonne : « arrêter » l'arrête (service existant, mêmes droits qu'une automatisation).
    r = coord.store.reveils[ident]
    reveil._vivant(r, "sonne", horloge["t"] + timedelta(minutes=5), occurrence="2026-10-12T07:00", par="essai")
    assert reveil.etat(coord.store, r) == "sonne"
    await hass.services.async_call("button", "press", {"entity_id": arreter}, blocking=True)
    assert reveil.etat(coord.store, r) == "arrete"
    # « reporter » sur un réveil qui sonne : reporté.
    reveil._vivant(r, "sonne", horloge["t"] + timedelta(minutes=5), occurrence="2026-10-12T07:00", par="essai")
    await hass.services.async_call("button", "press", {"entity_id": reporter}, blocking=True)
    assert reveil.etat(coord.store, r) == "reporte"


# ------------------------------------------------------------------ concentration, temps restant


async def test_concentration_par_personne(hass, hass_storage):
    from custom_components.pc_parental import concentration

    coord = await _monter(hass, hass_storage)
    eid = _eid(hass, "binary_sensor", "concentration_alex")
    assert hass.states.get(eid).state == "off"
    concentration.demarrer(coord.store, coord.store.pcs["e1"], 45, None, "Nolan")
    await coord.async_ecrire()
    await hass.async_block_till_done()
    etat = hass.states.get(eid)
    assert etat.state == "on" and etat.attributes["par"] == "Nolan" and etat.attributes["appareils"] == ["TEL-ALEX"]
    assert etat.attributes["fin"] and hass.states.get(_eid(hass, "binary_sensor", "concentration_sam")).state == "off"
    concentration.arreter(coord.store.pcs["e1"])
    await coord.async_ecrire()
    await hass.async_block_till_done()
    assert hass.states.get(eid).state == "off"


async def test_temps_restant_planning_temps_gagne_limite_et_fermeture(hass, hass_storage):
    from custom_components.pc_parental import entites_auto
    from custom_components.pc_parental.const import MODE_LOCKED, MODE_OPEN

    coord = await _monter(hass, hass_storage)
    store = coord.store
    pc = store.pcs["e1"]
    maintenant = time.time()
    assert entites_auto.temps_restant(store, pc, maintenant) is None                 # rien ne ferme
    # Un temps gagné de 30 minutes.
    store.set_override(pc, MODE_OPEN, 30)
    t = entites_auto.temps_restant(store, pc, maintenant)
    assert t["raison"] == "temps_gagne" and 29 <= t["minutes"] <= 30.01
    # Une limite par logiciel plus proche l'emporte (minutes d'usage restantes).
    store.clear_override(pc)
    pc["limites"] = {"roblox": 30}
    store.temps_du_jour = lambda p, nom: 600          # 10 min passées sur 30
    t = entites_auto.temps_restant(store, pc, maintenant)
    assert t["raison"] == "limite_logiciel:roblox" and round(t["minutes"]) == 20
    # Fermé en ce moment (pause) : zéro minute.
    store.set_override(pc, MODE_LOCKED, 60)
    t = entites_auto.temps_restant(store, pc, maintenant)
    assert t == {"minutes": 0.0, "raison": "pause", "fin": None}
    # Les entités : par appareil et par personne (le plus proche de ses appareils).
    store.clear_override(pc)
    await coord.async_ecrire()
    await hass.async_block_till_done()
    appareil = hass.states.get(_eid(hass, "sensor", "temps_restant_e1"))
    assert appareil.state == "20" and appareil.attributes["raison"] == "limite_logiciel:roblox"
    # Une limite vaut pour la personne : sa télé a le même temps restant que son téléphone.
    assert hass.states.get(_eid(hass, "sensor", "temps_restant_t1")).state == "20"
    personne = hass.states.get(_eid(hass, "sensor", "temps_restant_alex"))
    assert personne.state == "20" and personne.attributes["appareil"] == "TEL-ALEX"
    assert hass.states.get(_eid(hass, "sensor", "temps_restant_sam")).state == "unknown"


# ------------------------------------------------------------------ écran de la télé


async def test_ecran_de_la_tele_depuis_le_releve(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    eid = _eid(hass, "binary_sensor", "ecran_t1")
    assert hass.states.get(eid).state == "unavailable"                       # l'appli ne l'a pas encore dit
    await _releve(client, TELE, ecran="allume")
    await hass.async_block_till_done()
    assert hass.states.get(eid).state == "on"
    await _releve(client, TELE, ecran="eteint")
    await hass.async_block_till_done()
    assert hass.states.get(eid).state == "off"
    # Un relevé sans l'information (ancienne appli) ne change rien ; le dernier état reste.
    await _releve(client, TELE)
    await hass.async_block_till_done()
    assert hass.states.get(eid).state == "off"
    assert coord.store.pcs["t1"]["ecran_releve"]["etat"] == "eteint"


# ------------------------------------------------------------------ Party


async def test_etat_de_la_party_gardé_au_redemarrage_et_services(hass, hass_storage):
    from custom_components.pc_parental import party

    coord = await _monter(hass, hass_storage)
    eid = _eid(hass, "sensor", "party")
    assert hass.states.get(eid).state == "inactive"
    hass.states.async_set("media_player.salon_vision", "playing",
                          {"friendly_name": "Salon · Vision", "media_title": "Titre d'essai", "media_artist": "Artiste d'essai"})
    st = party.etat(hass)
    st.update({"actif": True, "ecran": "t1", "lecteur": "media_player.salon_vision", "par": "Nolan", "depuis": int(time.time())})
    party.sauver(hass)
    await hass.async_block_till_done()
    etat = hass.states.get(eid)
    assert etat.state == "active" and etat.attributes["lecteur_nom"] == "Salon · Vision" and etat.attributes["titre"] == "Titre d'essai"
    assert etat.attributes["ecran_nom"] == "TV-ALEX" and etat.attributes["par"] == "Nolan" and etat.attributes["invites_ouverts"] is True
    # Gardée sur disque…
    async_fire_time_changed(hass, dt_util.utcnow() + timedelta(seconds=5))
    await hass.async_block_till_done()
    garde = hass_storage["pc_parental_party"]["data"]
    assert garde["actif"] is True and garde["lecteur"] == "media_player.salon_vision" and garde["par"] == "Nolan"
    # … et relue après un redémarrage : la soirée est toujours là.
    party.etat(hass).update({"actif": False, "ecran": "", "lecteur": "", "par": "", "depuis": 0})
    assert party.etat(hass)["actif"] is False
    await party.charger(hass)
    assert party.etat(hass)["actif"] is True and party.etat(hass)["ecran"] == "t1"
    # Le service d'arrêt (automatisation : « dîner : arrêter la soirée »).
    vus = []
    hass.bus.async_listen(party.EVENEMENT_FIN, lambda e: vus.append(dict(e.data)))
    await hass.services.async_call(DOMAIN, "party_arreter", {}, blocking=True)
    await hass.async_block_till_done()
    assert hass.states.get(eid).state == "inactive" and vus and party.etat(hass)["actif"] is False
    # Le service de lancement : écran par son nom, lecteur donné.
    await hass.services.async_call(DOMAIN, "party_lancer", {"ecran": "TV-ALEX", "lecteur": "media_player.salon_vision"}, blocking=True)
    await hass.async_block_till_done()
    assert hass.states.get(eid).state == "active" and party.etat(hass)["par"] == "automatisation"


async def test_party_services_reserves_aux_administrateurs(hass, hass_storage):
    from homeassistant.exceptions import Unauthorized
    from homeassistant.core import Context
    import pytest

    await _monter(hass, hass_storage)
    await hass.auth.async_create_user("Proprietaire", group_ids=["system-admin"])   # le premier compte créé est le propriétaire
    simple = await hass.auth.async_create_user("Simple", group_ids=["system-users"])
    assert not simple.is_admin
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "party_arreter", {}, blocking=True, context=Context(user_id=simple.id))


# ------------------------------------------------------------------ gestes sur les widgets


async def test_evenement_entite_pilotee_avec_l_auteur(hass, hass_storage, hass_client):
    from widgets_test import W, _post, _services, _monter as monter_widgets

    client = await hass_client()
    await monter_widgets(hass, hass_storage)
    _services(hass)
    vus = []
    hass.bus.async_listen("pc_parental_entite_pilotee", lambda e: vus.append(dict(e.data)))
    st, rep = await _post(client, "/action", PARENT, entite="light.salon", service="toggle", widget="w-salon")
    assert st == 200, rep
    await hass.async_block_till_done()
    assert len(vus) == 1
    e = vus[0]
    assert e["entite"] == "light.salon" and e["service"] == "toggle" and e["appareil_id"] == "p1" and e["widget"] == "w-salon"
    assert e["personne"] and e["appareil"] and "donnees" in e
    # Un refus n'émet rien : l'événement dit ce qui a été fait, pas ce qui a été tenté.
    vus.clear()
    st, rep = await _post(client, "/action", ENFANT, entite="light.salon", service="toggle")
    assert st == 403 and not vus


# ------------------------------------------------------------------ niveau sonore : dBFS partout


async def test_seuil_de_nuit_en_dbfs(hass, hass_storage):
    from custom_components.pc_parental import son

    coord = await _monter(hass, hass_storage)
    pc = coord.store.pcs["e1"]
    pc["rules"] = [{"id": 1, "name": "Soir", "start": 72000, "end": 25200, "weekdays": [True] * 7, "enabled": True}]
    en_dbfs = son.regler_service(pc, {}, {"seuil_nuit_dbfs": -35})
    en_relatif = son.regler_service(pc, {}, {"seuil_nuit_db": 65})
    assert en_dbfs["seuil_mode"] == "plages" and en_dbfs["seuils_plages"] == en_relatif["seuils_plages"] == {"1": 65}
    jour = son.regler_service(pc, {}, {"seuil_dbfs": -30})
    assert jour["seuil_db"] == 70
    # L'unité reste lisible : l'état est en dBFS et le dit.
    from custom_components.pc_parental.sensor import NiveauSonoreSensor  # noqa: F401
