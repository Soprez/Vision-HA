"""Niveau sonore 2.5.12.10 : le seuil autorisé, général ou par plage Vision, et le plancher de la pièce.

- mode général : un seul seuil toute la journée ;
- mode par plage : la plus stricte des plages actives qui ont un seuil, sinon le général ;
- migration : un appareil réglé jour/nuit garde exactement sa règle (général = jour, plages de session = nuit) ;
- plancher mesuré sur 24 h (bas des moyennes sur 1 min), saisie manuelle prioritaire, seuils en marge du plancher ;
- service son_regler, entités (mode, seuil général), API des Réglages.

    python -m pytest essai/son_seuils_plages_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from datetime import datetime

import pytest

from son_pc_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, NUIT, PARIS, _integrations, _monter, _releve, expected_lingering_tasks, expected_lingering_timers,
)

# Une plage « Devoirs » de 17:00 à 23:00 (portée étiquettes : elle ne verrouille pas la session).
DEVOIRS = {"id": 8, "name": "Devoirs", "start": 17 * 3600, "end": 23 * 3600, "weekdays": [True] * 7,
           "portee": "etiquettes", "etiquettes": ["jeux"], "enabled": True}
JOUR = datetime(2026, 10, 19, 15, 0, tzinfo=PARIS)
SOIR = datetime(2026, 10, 19, 18, 0, tzinfo=PARIS)        # Devoirs seulement
TARD = datetime(2026, 10, 19, 22, 30, tzinfo=PARIS)       # Devoirs et Nuit
NUIT_PROFONDE = datetime(2026, 10, 20, 3, 0, tzinfo=PARIS)  # Nuit seulement


async def test_migration_regle_jour_nuit_gardee(hass, hass_storage, hass_client):
    """La règle du 19/10 (jour −25, nuit −28 sur la plage de session « Nuit ») devient le mode par plage, à l'identique."""
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    pc["son"] = {"reglages": {"actif": True, "seuil_db": 75, "seuil_nuit_db": 72, "nuit": "plages", "reagir": True}}
    r = son.reglages(pc)
    assert r["seuil_mode"] == "plages" and r["seuils_plages"] == {"7": 72} and r["seuil_db"] == 75
    assert son.seuil_effectif(coord.store, pc, JOUR) == 75 and son.seuil_effectif(coord.store, pc, NUIT_PROFONDE) == 72
    assert son.migrer(coord.store) == 1 and pc["son"]["reglages"]["seuil_mode"] == "plages"
    assert son.migrer(coord.store) == 0   # une seule fois
    # Sans plage de session (ou nuit = jour) : mode général, seuil du jour.
    pc2 = {"id": "x", "name": "X", "rules": [], "son": {"reglages": {"seuil_db": 70, "seuil_nuit_db": 60}}}
    assert son.reglages(pc2)["seuil_mode"] == "general" and son.reglages(pc2)["seuil_db"] == 70
    pc3 = {"id": "y", "name": "Y", "rules": [NUIT], "son": {"reglages": {"seuil_db": 70, "seuil_nuit_db": 70}}}
    assert son.reglages(pc3)["seuil_mode"] == "general"
    # Un appareil jamais réglé : mode général, −25 dBFS.
    assert son.reglages({"id": "z", "rules": [NUIT]})["seuil_mode"] == "general"
    assert son.reglages({"id": "z", "rules": [NUIT]})["seuil_db"] == 75


async def test_general_un_seul_seuil_toute_la_journee(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True, "seuil_mode": "general", "seuil_db": 74, "seuils_plages": {"7": 60}})
    for moment in (JOUR, SOIR, TARD, NUIT_PROFONDE):
        assert son.seuil_detail(coord.store, pc, moment) == (74, {"type": "general"})


async def test_par_plage_la_plus_stricte_sinon_le_general(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    pc["rules"] = [NUIT, DEVOIRS]
    son.regler(pc, {"actif": True, "seuil_mode": "plages", "seuil_db": 75, "seuils_plages": {"7": 72, "8": 70}})
    assert son.seuil_detail(coord.store, pc, JOUR) == (75, {"type": "general"})
    assert son.seuil_detail(coord.store, pc, SOIR)[0] == 70
    s, source = son.seuil_detail(coord.store, pc, TARD)            # Devoirs (70) et Nuit (72) : la plus stricte
    assert s == 70 and source["nom"] == "Devoirs"
    assert son.seuil_detail(coord.store, pc, NUIT_PROFONDE) == (72, {"type": "plage", "id": "7", "nom": "Nuit"})
    # Une plage sans seuil suit le général ; une plage désactivée ne compte pas ; une plage supprimée est oubliée.
    son.regler(pc, {"seuils_plages": {"7": 72}})
    assert son.seuil_effectif(coord.store, pc, SOIR) == 75
    pc["rules"] = [dict(NUIT, enabled=False), DEVOIRS]
    assert son.seuil_effectif(coord.store, pc, NUIT_PROFONDE) == 75
    pc["rules"] = [DEVOIRS]
    son.regler(pc, {"seuils_plages": {"7": 72, "8": 69}})
    assert son.migrer(coord.store) == 1 and pc["son"]["reglages"]["seuils_plages"] == {"8": 69}
    # Le relevé envoie le seuil du moment à l'agent, rien d'autre ne change pour lui.
    client = await hass_client()
    d = await _releve(client, son={"depassements": []})
    assert d["son"]["seuil_db"] == son.seuil_effectif(coord.store, pc)


async def test_passer_en_mode_par_plage_propose_moins_28_aux_plages_de_session(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    pc["rules"] = [NUIT, DEVOIRS]
    son.regler(pc, {"actif": True})
    r = son.regler(pc, {"seuil_mode": "plages"})
    assert r["seuils_plages"] == {"7": 72}   # Nuit (session) : −28 ; Devoirs : vide, donc le général


async def test_plancher_mesure_et_manuel(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True})
    e = son.etat(pc)
    t0 = 1_792_400_000.0
    for k in range(11):
        son.noter_plancher(e, 55.0 + (k % 3), t0 + k * 300)
    assert son.plancher_mesure_db(e) is None                 # moins d'une heure
    son.noter_plancher(e, 55.0, t0 + 11 * 300 + 10)
    son.noter_plancher(e, 90.0, t0 + 11 * 300 + 20)          # moins de 5 min après : pas gardé
    assert len(e["plancher_ech"]) == 12 and son.plancher_mesure_db(e) == 55.0
    # Un cri isolé ne change pas le plancher (le bas de la distribution).
    son.noter_plancher(e, 95.0, t0 + 13 * 300)
    assert son.plancher_mesure_db(e) == 55.0
    # Les échantillons de plus de 24 h tombent.
    son.noter_plancher(e, 40.0, t0 + 24 * 3600 + 13 * 300 + 1)
    assert all(float(x[0]) > t0 + 13 * 300 - 24 * 3600 for x in e["plancher_ech"])
    # La saisie manuelle passe avant ; le seuil en marge du plancher est enregistré en absolu.
    son.regler_service(pc, e, {"plancher_dbfs": -48})
    assert son.plancher_effectif_db(pc, e) == 52.0
    r = son.regler_service(pc, e, {"marge_plancher_db": 20})
    assert r["seuil_db"] == 72
    son.regler_service(pc, e, {"plancher_auto": True})
    assert son.reglages(pc)["plancher_manuel_dbfs"] is None


async def test_service_son_regler(hass, hass_storage, hass_client):
    from homeassistant.exceptions import HomeAssistantError

    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    pc["rules"] = [NUIT, DEVOIRS]
    rep = await hass.services.async_call(DOMAIN, "son_regler", {
        "pc": "PC-PL", "actif": True, "seuil_mode": "plages", "seuil_dbfs": -25,
        "seuils_plages": [{"plage": "nuit", "seuil_dbfs": -28}, {"plage": "8", "seuil_dbfs": -30}],
    }, blocking=True, return_response=True)
    r = rep["reglages"]
    assert r["seuil_mode"] == "plages" and r["seuil_db"] == 75 and r["seuils_plages"] == {"7": 72, "8": 70}
    rep = await hass.services.async_call(DOMAIN, "son_regler", {"pc": "PC-PL", "plage_effacer": ["Devoirs"]},
                                         blocking=True, return_response=True)
    assert rep["reglages"]["seuils_plages"] == {"7": 72}
    # Compatibilité : seuil_nuit_db remplit les plages de session.
    rep = await hass.services.async_call(DOMAIN, "son_regler", {"pc": "PC-PL", "seuil_nuit_db": 68},
                                         blocking=True, return_response=True)
    assert rep["reglages"]["seuils_plages"]["7"] == 68
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call(DOMAIN, "son_regler", {"pc": "PC-PL", "seuils_plages": [{"plage": "Inconnue", "seuil_dbfs": -30}]},
                                       blocking=True)
    with pytest.raises(HomeAssistantError):  # pas encore de plancher mesuré ni saisi
        await hass.services.async_call(DOMAIN, "son_regler", {"pc": "PC-PL", "marge_plancher_db": 20}, blocking=True)
    assert son.reglages(pc)["seuils_plages"]["7"] == 68   # rien n'a changé


async def test_entites_et_capteur(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    assert hass.states.get("number.pc_pl_seuil_sonore_la_nuit") is None   # remplacé par les seuils par plage
    mode = hass.states.get("select.pc_pl_mode_du_seuil_sonore")
    assert mode is not None and mode.state == "general"
    await hass.services.async_call("select", "select_option", {"entity_id": mode.entity_id, "option": "plages"}, blocking=True)
    assert son.reglages(pc)["seuil_mode"] == "plages" and son.reglages(pc)["seuils_plages"] == {"7": 72}
    general = [s for s in hass.states.async_all("number") if s.entity_id.startswith("number.pc_pl_seuil_sonore")]
    assert len(general) == 1 and float(general[0].state) == -25.0
    son.regler(pc, {"actif": True})
    client = await hass_client()
    await _releve(client, son={"niveau": 55, "micros": 1, "micros_actifs": 1, "micros_liste": ["m"], "depassements": []})
    await hass.async_block_till_done()
    a = hass.states.get("sensor.pc_pl_niveau_sonore").attributes
    assert a["seuil_mode"] == "plages" and a["seuil_general"] == -25
    assert {p["nom"]: p["seuil_dbfs"] for p in a["seuils_plages"]} == {"Nuit": -28}
    assert a["seuil_source"] in ("general", "Nuit") and a["plancher_mesure_dbfs"] is None and a["plancher_manuel_dbfs"] is None


async def test_reglages_vision_section_son(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    pc["rules"] = [NUIT, DEVOIRS]
    admin = await hass_client()
    r = await admin.get("/api/pc_parental/reglages/admin")
    assert r.status == 200
    a = {x["id"]: x for x in (await r.json())["son"]}["pc1"]
    assert a["mode"] == "general" and a["general_dbfs"] == -25 and {p["nom"] for p in a["plages"]} == {"Nuit", "Devoirs"}
    r = await admin.post("/api/pc_parental/reglages/admin", json={"op": "son", "pc": "pc1", "actif": True, "mode": "plages"})
    assert r.status == 200, await r.text()
    r = await admin.post("/api/pc_parental/reglages/admin", json={
        "op": "son", "pc": "pc1", "general_dbfs": -24, "plages": {"7": -29, "8": None}, "plancher_manuel_dbfs": -46})
    assert r.status == 200, await r.text()
    rg = son.reglages(pc)
    assert rg["actif"] is True and rg["seuil_mode"] == "plages" and rg["seuil_db"] == 76
    assert rg["seuils_plages"] == {"7": 71} and rg["plancher_manuel_dbfs"] == -46
    a = {x["id"]: x for x in (await r.json())["son"]}["pc1"]
    assert a["plancher_dbfs"] == -46 and {p["id"]: p["seuil_dbfs"] for p in a["plages"]} == {"7": -29, "8": None}
    # Module coupé : pas de section.
    from custom_components.pc_parental import modules
    modules.regler(hass, coord.store, set(modules.actifs(coord.store)) - {"son"}, "essai")
    r = await admin.get("/api/pc_parental/reglages/admin")
    assert (await r.json())["son"] is None
