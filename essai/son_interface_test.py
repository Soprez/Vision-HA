"""Niveau sonore 2.5.12.11 : ce que montre l'interface, et seulement quand le module ET la mesure sont allumés.

Principe : le niveau sonore est un module à part entière. Module coupé (ou mesure éteinte sur l'appareil) : aucune
trace dans les fiches d'appareils, le capteur des plages, les Réglages, aucune entité du son, aucun calcul.
Module et mesure allumés : seuil de chaque plage dans le capteur des plages (lu par le détail de plage), bloc
« Niveau sonore » dans la fiche de l'appareil (résumé de la personne, détail de l'appareil), avec la coupure en cours.

    python -m pytest essai/son_interface_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pathlib
import time

from son_pc_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, NUIT, _integrations, _monter, _releve, expected_lingering_tasks, expected_lingering_timers,
)

CARTES = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental" / "frontend"


def _fiche(hass, coord, pc_id="pc1"):
    from custom_components.pc_parental import parent

    return next(a for a in parent.etat_maison(hass, coord, "p1")["appareils"] if a["id"] == pc_id)


def _entites_son(hass):
    """Toutes les entités du niveau sonore, quel que soit leur domaine."""
    mots = ("niveau_sonore", "micros_actifs", "seuil_sonore", "reagir_au_bruit", "bruit_", "mode_du_seuil")
    return sorted(s.entity_id for s in hass.states.async_all() if any(m in s.entity_id for m in mots))


async def test_module_coupe_aucune_trace(hass, hass_storage, hass_client, monkeypatch):
    coord = await _monter(hass, hass_storage, module=False)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    # Même avec des réglages enregistrés (module coupé après usage) : rien ne remonte, rien ne se calcule.
    pc["son"] = {"reglages": {"actif": True, "seuil_db": 75, "seuil_nuit_db": 72}}
    calculs = []
    monkeypatch.setattr(son, "seuil_detail", lambda *a, **k: calculs.append(1) or (75, {"type": "general"}))
    coord.async_update_listeners()
    await hass.async_block_till_done()
    assert _entites_son(hass) == []
    assert "son" not in _fiche(hass, coord)
    plages = hass.states.get("sensor.pc_pl_plages").attributes
    assert "son" not in plages and all("seuil_sonore_dbfs" not in p for p in plages["plages"])
    admin = await hass_client()
    r = await admin.get("/api/pc_parental/reglages/admin")
    assert (await r.json())["son"] is None
    assert calculs == []
    # Le relevé dit seulement « éteint » à l'appareil.
    assert (await _releve(await hass_client(), son={"niveau": 50, "depassements": []}))["son"] == {"actif": False}


async def test_module_allume_mesure_eteinte_aucune_trace_pour_cet_appareil(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    assert "son" not in _fiche(hass, coord)
    plages = hass.states.get("sensor.pc_pl_plages").attributes
    assert "son" not in plages and all("seuil_sonore_dbfs" not in p for p in plages["plages"])


async def test_module_et_mesure_allumes_tout_apparait(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    pc = coord.store.pcs["pc1"]
    son.regler(pc, {"actif": True, "seuil_mode": "plages", "seuil_db": 75, "seuils_plages": {"7": 72},
                    "alertes_avant_coupure": 3, "coupure_min": 60})
    client = await hass_client()
    await _releve(client, son={"niveau": 52.5, "micros": 1, "micros_actifs": 1, "micros_liste": ["m"], "depassements": []})
    coord.async_update_listeners()
    await hass.async_block_till_done()
    # Détail de plage : le seuil de chaque plage (vide = général) et le seuil général, lus par la carte planning.
    plages = hass.states.get("sensor.pc_pl_plages").attributes
    assert plages["son"] == {"mode": "plages", "general_dbfs": -25}
    assert {p["nom"]: p["seuil_sonore_dbfs"] for p in plages["plages"]} == {"Nuit": -28}
    # Fiche de l'appareil (résumé de la personne, détail de l'appareil).
    f = _fiche(hass, coord)
    assert f["son"]["niveau_dbfs"] == -47.5 and f["son"]["seuil_dbfs"] in (-25, -28) and f["son"]["aujourd_hui"] == 0
    assert f["son"]["coupure"] is None and f["son"]["reagir"] is True
    assert f["appareil_ha"] and f["agent_version"].startswith("1.93.")
    # Une coupure pour bruit en cours apparaît, avec son heure de fin.
    t0 = time.time()
    for k in range(3):
        son.recevoir(hass, coord.store, pc, {"depassements": [{"ts": (t0 - 600 + 200 * k) * 1000, "db": 85}]},
                     maintenant=t0 - 595 + 200 * k)
    await hass.async_block_till_done()
    f = _fiche(hass, coord)
    assert f["son"]["coupure"] and f["son"]["coupure"]["fin"] > t0 + 50 * 60 and f["son"]["aujourd_hui"] == 3
    # Un autre appareil (mesure éteinte) n'a toujours rien.
    assert "son" not in _fiche(hass, coord, "p1")


def test_cartes_sans_texte_son_hors_condition():
    """Les cartes ne dessinent le niveau sonore que si le serveur l'envoie (a.son, attributes.son)."""
    maison = (CARTES / "vision-maison-card.js").read_text(encoding="utf-8")
    planning = (CARTES / "vision-planning-card.js").read_text(encoding="utf-8")
    assert "const n = a.son;\n    if (!n) return \"\";" in maison
    assert "p.appareils.filter((a) => a.son)" in maison
    assert "!neuf && this._son() ?" in planning and "const champSon = $(\"ed-son\");\n        if (champSon) {" in planning
    assert "data-fiche=" in maison and "/config/devices/device/" in maison


async def test_capteur_son_pour_l_historique_et_les_statistiques(hass, hass_storage, hass_client):
    """Un vrai capteur : enregistré par le recorder (pas d'exclusion), state_class measurement, classe pression
    acoustique, unité dB (statistiques longues natives) ; seuls les attributs lourds ne sont pas enregistrés."""
    from homeassistant.components.sensor import SensorDeviceClass, SensorStateClass

    coord = await _monter(hass, hass_storage)
    from custom_components.pc_parental import son
    from custom_components.pc_parental.sensor import NiveauSonoreSensor

    son.regler(coord.store.pcs["pc1"], {"actif": True})
    await _releve(await hass_client(), son={"niveau": 55, "micros": 1, "micros_actifs": 1, "micros_liste": ["m"], "depassements": []})
    await hass.async_block_till_done()
    s = hass.states.get("sensor.pc_pl_niveau_sonore")
    assert s.attributes["state_class"] == SensorStateClass.MEASUREMENT
    assert s.attributes["device_class"] == SensorDeviceClass.SOUND_PRESSURE and s.attributes["unit_of_measurement"] == "dB"
    assert s.attributes["echelle"] == "dBFS"
    non = NiveauSonoreSensor._unrecorded_attributes
    assert {"journal", "par_micro", "seuils_plages"} <= non and "seuil" not in non and "pic_10s" not in non

