"""2.5.12.3 (l'auteur, 19/10 : « un membre crie dans sa chambre, parfois le jour, parfois la nuit ») : le niveau sonore
sur un PC (agent Windows).

- le relevé d'un PC porte un bloc `son` (niveau, maximum, pic10, instant, micros, dépassements) : le capteur parle en
  dBFS, avec le pic des 10 s et le compteur du jour ;
- un seuil de jour et un seuil de nuit : la nuit est la plage « Verrouiller la session » du planning de l'appareil,
  sinon la plage de repli ; le relevé reçoit le seuil du moment ;
- réaction graduée : message doux à l'écran du PC, message ferme si ça recommence dans la fenêtre, puis les
  responsables sont prévenus (canaux « alertes ») ; délai minimal entre deux alertes ;
- un PC n'est jamais coupé par défaut (alertes seules) ; entités de réglage par appareil quand le module est allumé.

    essai/banc/lancer.sh son_pc_test.py
"""
from __future__ import annotations

import pathlib
import time
import shutil
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

PC = {"id": "pc1", "secret": "e" * 32}
PARIS = ZoneInfo("Europe/Paris")
# Une plage « Nuit » comme celle de l'auteur : verrouiller la session de 22:00 à 07:00, tous les jours.
NUIT = {"id": 7, "name": "Nuit", "start": 22 * 3600, "end": 7 * 3600, "weekdays": [True] * 7,
        "portee": "Verrouiller la session", "enabled": True}


async def _monter(hass, hass_storage, plages=True, module=True):
    await hass.config.async_set_time_zone("Europe/Paris")
    pc = _fiche("pc1", "PC-PL", PC["secret"], person="person.pl", platform="windows")
    if plages:
        pc["rules"] = [NUIT]
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "modules": {"son": module},
        "pcs": {
            "pc1": pc,
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        },
    }
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    hass.states.async_set("person.pl", "home", {"friendly_name": "PL"})
    hass.states.async_set("person.nolan", "home", {"friendly_name": "Nolan"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def _releve(client, **corps):
    r = await client.post("/api/pc_parental/poll", json={**PC, "host": "PC-PL", "user": "pl", "version": "1.93.5", **corps})
    assert r.status == 200, await r.text()
    return await r.json()


def _messages(coord, pc_id="pc1"):
    return [m["text"] for m in coord.store.pcs[pc_id].get("messages") or []]


def _ecouter(hass):
    vus = []
    hass.bus.async_listen("vision_son_seuil_depasse", lambda e: vus.append(dict(e.data)))
    return vus


async def test_le_pc_remonte_dbfs_pic_et_compteur(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    # Un PC : mesure éteinte par défaut, jamais de coupure, 3 s de sensibilité.
    r = son.reglages(pc)
    assert r["actif"] is False and r["alertes_avant_coupure"] == 0 and r["sensibilite_s"] == 3
    client = await hass_client()
    d = await _releve(client, son={"niveau": 40, "pic10": 70, "depassements": []})
    assert d["son"] == {"actif": False}  # rien n'est lu tant que la mesure est éteinte
    assert hass.states.get("sensor.pc_pl_niveau_sonore") is None or hass.states.get("sensor.pc_pl_niveau_sonore").state == "unavailable"
    son.regler(pc, {"actif": True})
    d = await _releve(client, son={"niveau": 42.5, "maximum": 61, "pic10": 58.2, "instant": 44, "micros": 2, "depassements": []})
    assert d["son"]["actif"] is True and d["son"]["sensibilite_s"] == 3 and d["son"]["alertes_avant_coupure"] == 0
    await hass.async_block_till_done()
    s = hass.states.get("sensor.pc_pl_niveau_sonore")
    assert s is not None and s.state == "-57.5"  # 42,5 dB relatifs = −57,5 dBFS
    assert s.attributes["pic_10s"] == -41.8 and s.attributes["instant"] == -56.0 and s.attributes["micros"] == 2
    assert s.attributes["echelle"] == "dBFS" and s.attributes["niveau_relatif"] == 42.5 and s.attributes["aujourd_hui"] == 0


async def test_seuil_de_jour_et_de_nuit_selon_la_plage_de_session(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    # 2.5.12.10 : « la nuit » = les plages de session ; seuil_nuit_db (service) les remplit, en mode par plage.
    son.regler_service(pc, son.etat(pc), {"actif": True, "seuil_db": 78, "seuil_nuit_db": 62})
    jour = datetime(2026, 10, 19, 15, 0, tzinfo=PARIS)
    nuit = datetime(2026, 10, 19, 23, 30, tzinfo=PARIS)
    petit_matin = datetime(2026, 10, 20, 6, 30, tzinfo=PARIS)
    assert son.est_nuit(coord.store, pc, jour) is False and son.est_nuit(coord.store, pc, nuit) is True
    assert son.est_nuit(coord.store, pc, petit_matin) is True
    assert son.seuil_effectif(coord.store, pc, jour) == 78 and son.seuil_effectif(coord.store, pc, nuit) == 62
    # Sans plage de session : la plage de repli (22:00 → 07:00 par défaut), réglable.
    pc["rules"] = []
    assert son.est_nuit(coord.store, pc, nuit) is True and son.est_nuit(coord.store, pc, jour) is False
    son.regler(pc, {"nuit_debut": "21:00", "nuit_fin": "08:00"})
    assert son.est_nuit(coord.store, pc, datetime(2026, 10, 19, 21, 15, tzinfo=PARIS)) is True
    # « heures » force la plage de repli même avec un planning.
    pc["rules"] = [NUIT]
    son.regler(pc, {"nuit": "heures", "nuit_debut": "20:00", "nuit_fin": "21:00"})
    assert son.est_nuit(coord.store, pc, nuit) is False and son.est_nuit(coord.store, pc, datetime(2026, 10, 19, 20, 30, tzinfo=PARIS)) is True
    # Le relevé reçoit le seuil du moment.
    son.regler(pc, {"nuit": "plages"})
    client = await hass_client()
    d = await _releve(client, son={"niveau": 40, "depassements": []})
    attendu = 62 if son.est_nuit(coord.store, pc) else 78
    assert d["son"]["seuil_db"] == attendu and d["son"]["nuit"] == son.est_nuit(coord.store, pc)


async def test_reaction_graduee_doux_ferme_puis_responsables(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True, "repos_min": 1, "ferme_apres_min": 5})
    vus = _ecouter(hass)
    t0 = 1_792_400_000.0  # une heure de l'après-midi, en secondes
    # 1er dépassement : message doux au PC, rien aux responsables.
    assert son.recevoir(hass, coord.store, pc, {"niveau": 70, "depassements": [{"ts": t0 * 1000, "db": 82}]}, maintenant=t0 + 5)
    await hass.async_block_till_done()
    assert _messages(coord) == ["Moins fort, s'il te plaît 🙂"]
    assert _messages(coord, "p1") == []
    assert vus[-1]["gradation"] == 1 and vus[-1]["aujourd_hui"] == 1 and vus[-1]["niveau_dbfs"] == -18.0
    # 20 s plus tard : sous le délai minimal (1 min), ignoré.
    assert son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 20) * 1000, "db": 84}]}, maintenant=t0 + 25)
    assert len(vus) == 1 and len(_messages(coord)) == 1
    # 2 min plus tard, dans la fenêtre de 5 min : message ferme.
    assert son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 120) * 1000, "db": 85}]}, maintenant=t0 + 125)
    await hass.async_block_till_done()
    assert _messages(coord)[-1].startswith("C'est encore trop fort") and vus[-1]["gradation"] == 2
    assert _messages(coord, "p1") == []
    # 4 min plus tard, encore dans la fenêtre : message ferme ET les responsables prévenus (appli Vision du décideur).
    assert son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 240) * 1000, "db": 88}]}, maintenant=t0 + 245)
    await hass.async_block_till_done()
    assert vus[-1]["gradation"] == 3 and vus[-1]["aujourd_hui"] == 3
    responsable = _messages(coord, "p1")
    assert len(responsable) == 1 and "PC-PL" in responsable[0] and "-12.0 dBFS" in responsable[0] and "3 fois" in responsable[0]
    # Jamais de coupure sur un PC par défaut.
    assert coord.store.pcs["pc1"].get("override") is None
    # Après la fenêtre de calme, la gradation repart du message doux.
    assert son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 900) * 1000, "db": 80}]}, maintenant=t0 + 905)
    await hass.async_block_till_done()
    assert vus[-1]["gradation"] == 1 and vus[-1]["aujourd_hui"] == 4 and _messages(coord)[-1] == "Moins fort, s'il te plaît 🙂"
    assert len(_messages(coord, "p1")) == 1
    # Le compteur du jour revient à zéro le lendemain.
    assert son.recevoir(hass, coord.store, pc, {"niveau": 30, "depassements": []}, maintenant=t0 + 86_400)
    assert son.etat(pc)["aujourd_hui"] == 0


async def test_responsables_pas_prevenus_si_on_le_demande(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True, "repos_min": 0, "ferme_apres_min": 10, "prevenir_responsable": False})
    t0 = 1_792_400_000.0
    for k in range(3):
        son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 60 * k) * 1000, "db": 85}]}, maintenant=t0 + 60 * k + 5)
    await hass.async_block_till_done()
    assert len(_messages(coord)) == 3 and _messages(coord, "p1") == []


async def test_entites_de_reglage_par_appareil(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    sw = hass.states.get("switch.pc_pl_mesurer_le_niveau_sonore")
    assert sw is not None and sw.state == "off"
    await hass.services.async_call("switch", "turn_on", {"entity_id": "switch.pc_pl_mesurer_le_niveau_sonore"}, blocking=True)
    assert son.reglages(pc)["actif"] is True
    # Les seuils s'affichent en dBFS : 75 relatif = −25 dBFS ; régler −30 dBFS écrit 70 relatif.
    # 2.5.12.10 : un seuil général ; les seuils par plage passent par Réglages Vision et son_regler.
    jour = hass.states.get("number.pc_pl_seuil_sonore_general")
    assert jour is not None and float(jour.state) == -25.0
    await hass.services.async_call("number", "set_value", {"entity_id": "number.pc_pl_seuil_sonore_general", "value": -30}, blocking=True)
    assert son.reglages(pc)["seuil_db"] == 70
    assert hass.states.get("number.pc_pl_seuil_sonore_la_nuit") is None
    await hass.services.async_call("number", "set_value", {"entity_id": "number.pc_pl_bruit_duree_avant_alerte", "value": 4}, blocking=True)
    await hass.services.async_call("number", "set_value", {"entity_id": "number.pc_pl_bruit_delai_entre_deux_alertes", "value": 3}, blocking=True)
    r = son.reglages(pc)
    assert r["sensibilite_s"] == 4 and r["repos_min"] == 3
    # Le service accepte les nouveaux réglages.
    rep = await hass.services.async_call(DOMAIN, "son_regler", {"pc": "PC-PL", "seuil_nuit_db": 55, "repos_min": 2, "prevenir_responsable": False,
                                                                 "nuit": "heures", "nuit_debut": "23:00", "nuit_fin": "06:00"},
                                         blocking=True, return_response=True)
    assert rep["reglages"]["seuil_nuit_db"] == 55 and rep["reglages"]["prevenir_responsable"] is False and rep["reglages"]["nuit"] == "heures"


async def test_module_coupe_aucune_entite_de_reglage(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage, module=False)
    assert hass.states.get("switch.pc_pl_mesurer_le_niveau_sonore") is None
    assert hass.states.get("number.pc_pl_seuil_sonore_le_jour") is None


async def test_micros_eteints_exclus_et_aucun_micro_actif_indisponible(hass, hass_storage, hass_client):
    """Précision de l'auteur : la moyenne ne porte que sur les micros vraiment allumés. Aucun micro actif : « Niveau
    sonore » indisponible (jamais 0) et « Micros actifs » à 0 avec la liste vide."""
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True})
    client = await hass_client()
    d = await _releve(client, son={"niveau": 50, "maximum": 60, "pic10": 58, "instant": 49, "micros": 2, "micros_actifs": 1,
                                   "micros_liste": ["Micro (Razer)"], "depassements": []})
    # Le réglage « micro figé » part vers l'agent avec les autres.
    assert d["son"]["micro_fige_s"] == 10
    await hass.async_block_till_done()
    s = hass.states.get("sensor.pc_pl_niveau_sonore")
    assert s.state == "-50.0" and s.attributes["micros_actifs"] == 1 and s.attributes["micros_pris_en_compte"] == ["Micro (Razer)"]
    m = hass.states.get("sensor.pc_pl_micros_actifs")
    assert m is not None and m.state == "1" and m.attributes["liste"] == ["Micro (Razer)"] and m.attributes["micros_ecoutes"] == 2
    # Les deux micros coupés (muet, débranché, silence numérique ou figé) : l'agent n'envoie plus de niveau.
    await _releve(client, son={"micros": 2, "micros_actifs": 0, "micros_liste": [], "depassements": []})
    await hass.async_block_till_done()
    assert hass.states.get("sensor.pc_pl_niveau_sonore").state == "unavailable"
    m = hass.states.get("sensor.pc_pl_micros_actifs")
    assert m.state == "0" and m.attributes["liste"] == [] and m.attributes["micros_ecoutes"] == 2
    assert son.etat(pc)["niveau"] is None and son.etat(pc)["pic"] is None
    # Un micro revient : le capteur aussi.
    await _releve(client, son={"niveau": 47, "micros": 2, "micros_actifs": 1, "micros_liste": ["Micro (Razer)"], "depassements": []})
    await hass.async_block_till_done()
    assert hass.states.get("sensor.pc_pl_niveau_sonore").state == "-53.0"
    # Un appareil qui ne dit rien des micros actifs (l'appli Android) garde l'ancien comportement.
    son.etat(pc)["micros_actifs"] = None
    await _releve(client, son={"niveau": 45, "depassements": []})
    await hass.async_block_till_done()
    assert hass.states.get("sensor.pc_pl_niveau_sonore").state == "-55.0"
    assert hass.states.get("sensor.pc_pl_micros_actifs").state == "unavailable"
    # Les noms sont bornés : 16 au plus, 80 caractères, chaînes seulement.
    son.recevoir(hass, coord.store, pc, {"micros_actifs": 3, "micros_liste": ["x" * 200, 42, *["m"] * 30]})
    assert len(son.etat(pc)["micros_liste"]) == 15 and len(son.etat(pc)["micros_liste"][0]) == 80


@pytest.mark.skipif(shutil.which("powershell.exe") is None, reason="Windows PowerShell absent")
def test_sonde_agent_exclut_micros_muets_silencieux_figes():
    """La vraie sonde de l'agent (Mesurer-Tranche, Micros-Pris) sur de faux micros : essai/son_micros_essai.ps1."""
    essai = pathlib.Path(__file__).with_name("son_micros_essai.ps1")
    r = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(essai)],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    assert r.returncode == 0, r.stdout[-3000:] + r.stderr[-2000:]


async def test_detail_par_micro(hass, hass_storage, hass_client):
    """Agent 1.93.5 : chaque micro remonte avec son état et son niveau ; HA les montre en dBFS, bornés."""
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True})
    client = await hass_client()
    detail = [{"nom": "Microphone (Cirrus Logic)", "etat": "actif", "niveau": 55.0},
              {"nom": "Micro casque (Corsair)", "etat": "silence"},
              {"nom": "Micro figé", "etat": "fige", "niveau": 30.0}]
    await _releve(client, son={"niveau": 55, "micros": 3, "micros_actifs": 1, "micros_liste": ["Microphone (Cirrus Logic)"],
                               "micros_detail": detail, "depassements": []})
    await hass.async_block_till_done()
    attendu = [{"nom": "Microphone (Cirrus Logic)", "etat": "actif", "niveau_dbfs": -45.0},
               {"nom": "Micro casque (Corsair)", "etat": "silence", "niveau_dbfs": None},
               {"nom": "Micro figé", "etat": "fige", "niveau_dbfs": -70.0}]
    assert hass.states.get("sensor.pc_pl_niveau_sonore").attributes["par_micro"] == attendu
    assert hass.states.get("sensor.pc_pl_micros_actifs").attributes["par_micro"] == attendu
    # Aucun micro actif : le niveau est indisponible, mais « Micros actifs » dit encore micro par micro pourquoi.
    await _releve(client, son={"micros": 1, "micros_actifs": 0, "micros_liste": [],
                               "micros_detail": [{"nom": "Micro casque (Corsair)", "etat": "muet"}], "depassements": []})
    await hass.async_block_till_done()
    assert hass.states.get("sensor.pc_pl_niveau_sonore").state == "unavailable"
    assert hass.states.get("sensor.pc_pl_micros_actifs").attributes["par_micro"] == [
        {"nom": "Micro casque (Corsair)", "etat": "muet", "niveau_dbfs": None}]
    # Bornes : 16 micros au plus, noms de 80 caractères, état inconnu = « sans_donnees », rien d'autre gardé.
    son.recevoir(hass, coord.store, pc, {"micros_actifs": 1, "micros_detail": [
        {"nom": "x" * 200, "etat": "pirate", "niveau": 50, "audio": "AAAA"}, "texte", {"etat": "actif"},
        *[{"nom": f"m{i}", "etat": "actif"} for i in range(30)]]})
    d = son.etat(pc)["micros_detail"]
    assert len(d) == 16 and d[0] == {"nom": "x" * 80, "etat": "sans_donnees", "niveau": 50.0}
    assert "audio" not in str(pc.get("son"))


async def test_plancher_micro_reglable(hass, hass_storage, hass_client):
    """l'auteur : un micro sous le plancher (-90 dBFS par défaut, réglable) ne capte rien et compte comme éteint ; l'agent
    reçoit le plancher, et l'état « sous_plancher » remonte dans le détail par micro."""
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True})
    client = await hass_client()
    d = await _releve(client, son={"micros": 2, "micros_actifs": 0, "micros_liste": [], "depassements": [],
                                   "micros_detail": [{"nom": "Réseau de micros (Realtek)", "etat": "sous_plancher", "niveau": 1.0},
                                                     {"nom": "Casque (Corsair)", "etat": "sous_plancher", "niveau": 0.5}]})
    assert d["son"]["micro_plancher_dbfs"] == -90
    await hass.async_block_till_done()
    assert hass.states.get("sensor.pc_pl_niveau_sonore").state == "unavailable"
    m = hass.states.get("sensor.pc_pl_micros_actifs")
    assert m.state == "0" and m.attributes["par_micro"] == [
        {"nom": "Réseau de micros (Realtek)", "etat": "sous_plancher", "niveau_dbfs": -99.0},
        {"nom": "Casque (Corsair)", "etat": "sous_plancher", "niveau_dbfs": -99.5}]
    # Réglable par appareil, borné entre -120 et -40 dBFS.
    rep = await hass.services.async_call(DOMAIN, "son_regler", {"pc": "PC-PL", "micro_plancher_dbfs": -100},
                                         blocking=True, return_response=True)
    assert rep["reglages"]["micro_plancher_dbfs"] == -100
    assert (await _releve(client, son={"depassements": []}))["son"]["micro_plancher_dbfs"] == -100
    assert son.regler(pc, {"micro_plancher_dbfs": -10})["micro_plancher_dbfs"] == -40
    assert son.regler(pc, {"micro_plancher_dbfs": -500})["micro_plancher_dbfs"] == -120


async def test_trois_avertissements_puis_coupure_une_heure(hass, hass_storage, hass_client):
    """2.5.12.7 : 1er dépassement message doux, 2e message ferme, 3e coupure de 60 min (fermeture comme le service
    lock : écran de blocage, réouverture à l'échéance), message à l'écran, responsables prévenus, tout journalisé ;
    puis la gradation repart de zéro."""
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True, "seuil_db": 75, "sensibilite_s": 3, "repos_min": 2, "ferme_apres_min": 15,
                    "calme_min": 15, "alertes_avant_coupure": 3, "coupure_min": 60})
    coupures = []
    hass.bus.async_listen("vision_son_coupure", lambda e: coupures.append(dict(e.data)))
    t0 = 1_792_400_000.0
    assert son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": t0 * 1000, "db": 80}]}, maintenant=t0 + 5)
    await hass.async_block_till_done()
    assert _messages(coord) == ["Moins fort, s'il te plaît 🙂"] and pc.get("override") is None
    son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 180) * 1000, "db": 82}]}, maintenant=t0 + 185)
    await hass.async_block_till_done()
    assert _messages(coord)[-1].startswith("C'est encore trop fort") and pc.get("override") is None
    assert _messages(coord, "p1") == []
    avant = time.time()
    son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 600) * 1000, "db": 85}]}, maintenant=t0 + 605)
    await hass.async_block_till_done()
    o = pc.get("override")
    assert o and o["mode"] == "locked" and 3590 <= o["until"] - avant <= 3610   # fermé une heure
    assert _messages(coord)[-1].startswith("Trop de bruit (3 avertissements) : l'appareil est fermé 60 min, jusqu'à")
    responsable = _messages(coord, "p1")
    assert len(responsable) == 1 and "PC-PL" in responsable[0] and "fermé 60 min" in responsable[0] and "-15.0 dBFS" in responsable[0]
    assert coupures == [{"appareil": "pc1", "nom": "PC-PL", "minutes": 60, "alertes": 3}]
    e = son.etat(pc)
    assert e["alertes"] == 0 and e["niveau_alerte"] == 0
    assert [(x["type"], x.get("rang"), x["niveau_dbfs"]) for x in e["journal"]] == [
        ("avertissement", 1, -20.0), ("avertissement", 2, -18.0), ("coupure", 3, -15.0)]
    s = hass.states.get("sensor.pc_pl_niveau_sonore")
    # Le journal est lisible dans le capteur (heure locale), même sans relevé récent pour l'état.
    assert e["journal"][-1]["minutes"] == 60
    # Après la coupure, la gradation repart du message doux.
    son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 4500) * 1000, "db": 80}]}, maintenant=t0 + 4505)
    await hass.async_block_till_done()
    assert _messages(coord)[-1] == "Moins fort, s'il te plaît 🙂"


async def test_calme_quinze_minutes_remet_a_zero(hass, hass_storage, hass_client):
    """Deux avertissements, puis 15 min de calme : le troisième dépassement redevient un premier avertissement."""
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True, "repos_min": 2, "ferme_apres_min": 15, "calme_min": 15,
                    "alertes_avant_coupure": 3, "coupure_min": 60})
    t0 = 1_792_400_000.0
    son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": t0 * 1000, "db": 80}]}, maintenant=t0 + 5)
    son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 300) * 1000, "db": 80}]}, maintenant=t0 + 305)
    son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 300 + 16 * 60) * 1000, "db": 80}]},
                 maintenant=t0 + 300 + 16 * 60 + 5)
    await hass.async_block_till_done()
    assert pc.get("override") is None and son.etat(pc)["alertes"] == 1
    assert _messages(coord)[-1] == "Moins fort, s'il te plaît 🙂"


async def test_reagir_eteint_mesure_seule(hass, hass_storage, hass_client):
    """« Réagir au bruit » éteint (d'un clic) : dépassements comptés et journalisés, sans message, sans responsables,
    sans coupure ; rallumé, la règle reprend."""
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True, "repos_min": 0, "alertes_avant_coupure": 1, "coupure_min": 60})
    sw = hass.states.get("switch.pc_pl_reagir_au_bruit")
    assert sw is not None and sw.state == "on"   # réagir : allumé par défaut (comportement d'avant)
    await hass.services.async_call("switch", "turn_off", {"entity_id": "switch.pc_pl_reagir_au_bruit"}, blocking=True)
    assert son.reglages(pc)["reagir"] is False
    t0 = 1_792_400_000.0
    for k in range(4):
        son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 60 * k) * 1000, "db": 90}]}, maintenant=t0 + 60 * k + 5)
    await hass.async_block_till_done()
    assert _messages(coord) == [] and _messages(coord, "p1") == [] and pc.get("override") is None
    e = son.etat(pc)
    assert e["aujourd_hui"] == 4 and e["alertes"] == 0 and [x["type"] for x in e["journal"]] == ["depassement"] * 4
    await hass.services.async_call("switch", "turn_on", {"entity_id": "switch.pc_pl_reagir_au_bruit"}, blocking=True)
    son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 + 600) * 1000, "db": 90}]}, maintenant=t0 + 605)
    await hass.async_block_till_done()
    assert pc.get("override") is not None   # une seule alerte avant coupure : fermé


async def test_entites_coupure_et_micros_exclus(hass, hass_storage, hass_client):
    """Réglages par appareil : avertissements avant coupure (0 = jamais), durée, micros ignorés envoyés à l'agent."""
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    av = hass.states.get("number.pc_pl_bruit_avertissements_avant_coupure")
    cp = hass.states.get("number.pc_pl_bruit_duree_de_la_coupure")
    assert av is not None and float(av.state) == 0 and cp is not None   # un PC : jamais de coupure par défaut
    await hass.services.async_call("number", "set_value", {"entity_id": av.entity_id, "value": 3}, blocking=True)
    await hass.services.async_call("number", "set_value", {"entity_id": cp.entity_id, "value": 60}, blocking=True)
    r = son.reglages(pc)
    assert r["alertes_avant_coupure"] == 3 and r["coupure_min"] == 60
    rep = await hass.services.async_call(DOMAIN, "son_regler", {"pc": "PC-PL", "actif": True,
                                                                 "micros_exclus": ["CORSAIR VOID", "  "]},
                                         blocking=True, return_response=True)
    assert rep["reglages"]["micros_exclus"] == ["CORSAIR VOID"]
    client = await hass_client()
    d = await _releve(client, son={"depassements": []})
    assert d["son"]["micros_exclus"] == ["CORSAIR VOID"]
    # Bornes : 8 au plus, 80 caractères, chaînes seulement.
    assert son.regler(pc, {"micros_exclus": ["x" * 200, 3, *[f"m{i}" for i in range(20)]]})["micros_exclus"][0] == "x" * 80
    assert len(son.reglages(pc)["micros_exclus"]) == 8
    # L'état « exclu » d'un micro remonte dans le détail.
    son.recevoir(hass, coord.store, pc, {"micros_actifs": 1, "micros_detail": [{"nom": "Casque (CORSAIR VOID)", "etat": "exclu", "niveau": 80}]})
    assert son.etat(pc)["micros_detail"][0]["etat"] == "exclu"

