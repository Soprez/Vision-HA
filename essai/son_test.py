"""Niveau sonore (backlog n° 31) : module coupé par défaut, réglages par appareil, compteur, coupure, événements.

- module absent des préréglages et coupé par défaut ; une fois allumé, la mesure reste éteinte par appareil ;
- le relevé ne garde que des nombres (niveau, maximum, horodatage et niveau des dépassements) : aucun autre champ ;
- chaque dépassement reçu est compté une fois (relevé répété), dans les plages actives seulement ;
- à la N-ième alerte : coupure de l'appareil (même dérogation que le service lock) et événement ; compteur remis à zéro ;
- remise à zéro après X minutes calmes ; capteur « Niveau sonore » par appareil.

    essai/banc/lancer.sh son_test.py
"""
from __future__ import annotations

import time

import pytest
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import label_registry as lr
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)


async def _monter(hass, hass_storage, module=True):
    await hass.config.async_set_time_zone("Europe/Paris")
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-A", ENFANT["secret"], person="person.a", platform="android", type="telephone"),
            "e2": _fiche("e2", "TEL-B", ENFANT2["secret"], person="person.b", platform="android", type="telephone"),
        },
    }
    if module is not None:
        donnees["modules"] = {"son": module}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    for p in ("a", "b"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.upper()})
    lr.async_get(hass).async_create("Toujours autorisé")
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def _releve(client, qui, **corps):
    r = await client.post("/api/pc_parental/poll", json={**qui, "platform": "android", "type": "telephone", **corps})
    assert r.status == 200
    return await r.json()


async def _regler(hass, **donnees):
    return await hass.services.async_call(DOMAIN, "son_regler", {"pc": "TEL-A", **donnees}, blocking=True, return_response=True)


def _ecouter(hass):
    vus = []
    for nom in ("vision_son_seuil_depasse", "vision_son_coupure"):
        hass.bus.async_listen(nom, lambda e: vus.append((e.event_type.replace("vision_son_", ""), dict(e.data))))
    return vus


async def test_module_coupe_par_defaut(hass, hass_storage, hass_client):
    from custom_components.pc_parental import modules

    coord = await _monter(hass, hass_storage, module=None)
    assert "son" not in modules.actifs(coord.store) and "son" not in modules.PRESETS["tout"] and "son" in modules.CATALOGUE
    client = await hass_client()
    assert (await _releve(client, ENFANT))["son"] == {"actif": False}
    with pytest.raises(HomeAssistantError, match="Niveau sonore"):
        await _regler(hass, actif=True)
    assert not [e for e in hass.states.async_entity_ids("sensor") if "son" in e or "niveau_sonore" in e]


async def test_reglages_par_appareil_et_bornes(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    assert (await _releve(client, ENFANT))["son"] == {"actif": False}  # module allumé, mesure éteinte par appareil
    r = await _regler(hass, actif=True, seuil_db=80, seuil_nuit_db=80, sensibilite_s=4, plages=[{"debut": "08:00", "fin": "20:00"}, {"debut": "x", "fin": "y"}])
    assert r["reglages"]["seuil_db"] == 80 and r["reglages"]["plages"] == [{"debut": "08:00", "fin": "20:00"}]
    bloc = (await _releve(client, ENFANT))["son"]
    assert isinstance(bloc.pop("nuit"), bool)  # 2.5.12.3 : le relevé dit si c'est la nuit (seuil du moment)
    assert bloc == {"actif": True, "seuil_db": 80, "sensibilite_s": 4, "plages": [{"debut": "08:00", "fin": "20:00"}],
                    "calibration_db": 0, "micro_fige_s": 10, "micro_plancher_dbfs": -90, "alertes": 0, "alertes_avant_coupure": 3}
    assert (await _releve(client, ENFANT2))["son"] == {"actif": False}  # l'autre appareil n'est pas concerné


async def test_alertes_coupure_et_remise_a_zero(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import son

    coord = await _monter(hass, hass_storage)
    vus = _ecouter(hass)
    client = await hass_client()
    await _regler(hass, actif=True, coupure_min=20, calme_min=10)
    t0 = time.time()

    def dep(i):
        return {"ts": int((t0 + i) * 1000), "db": 82.5}

    await _releve(client, ENFANT, son={"niveau": 61.2, "maximum": 83.0, "depassements": [dep(1)]})
    await hass.async_block_till_done()
    assert [v[0] for v in vus] == ["seuil_depasse"] and vus[0][1]["alerte"] == 1 and vus[0][1]["niveau"] == 82.5
    # Le même relevé répété (réseau) ne compte pas deux fois.
    await _releve(client, ENFANT, son={"niveau": 60, "depassements": [dep(1)]})
    await hass.async_block_till_done()
    assert len(vus) == 1
    assert (await _releve(client, ENFANT))["son"]["alertes"] == 1
    # Capteur : le niveau, le maximum et les alertes.
    etat = hass.states.get(next(e for e in hass.states.async_entity_ids("sensor") if e.endswith("niveau_sonore")))
    assert etat is not None and float(etat.state) == -40.0 and etat.attributes["alertes"] == 1  # 2.5.12.3 : en dBFS (60 relatifs)
    # Deux autres dépassements : la troisième alerte coupe l'appareil, puis le compteur repart de zéro.
    await _releve(client, ENFANT, son={"niveau": 70, "depassements": [dep(2), dep(3)]})
    await hass.async_block_till_done()
    assert [v[0] for v in vus] == ["seuil_depasse", "seuil_depasse", "seuil_depasse", "coupure"]
    assert vus[-1][1]["minutes"] == 20
    pc = coord.store.get("e1")
    assert pc.get("override") and pc["override"]["mode"] == "locked" and pc["override"]["until"] > time.time() + 19 * 60
    assert son.etat(pc)["alertes"] == 0
    # Une alerte, puis dix minutes calmes : remise à zéro au relevé suivant.
    await _releve(client, ENFANT, son={"niveau": 50, "depassements": [dep(4)]})
    assert son.etat(pc)["alertes"] == 1
    monkeypatch.setattr(son.time, "time", lambda: t0 + 11 * 60)
    bloc = (await _releve(client, ENFANT, son={"niveau": 40}))["son"]
    assert bloc["alertes"] == 0


async def test_que_des_nombres_et_plages(hass, hass_storage, hass_client):
    from custom_components.pc_parental import son

    coord = await _monter(hass, hass_storage)
    vus = _ecouter(hass)
    client = await hass_client()
    # Une plage qui ne contient jamais l'heure du dépassement : rien n'est compté.
    maintenant = son.dt_util.now()
    debut = (maintenant.hour + 2) % 24
    await _regler(hass, actif=True, plages=[{"debut": f"{debut:02d}:00", "fin": f"{(debut + 1) % 24:02d}:00"}])
    await _releve(client, ENFANT, son={"niveau": "55", "audio": "AAAA", "echantillons": [1, 2, 3],
                                      "depassements": [{"ts": int(time.time() * 1000), "db": 90, "extrait": "x"}, "n'importe quoi"]})
    await hass.async_block_till_done()
    assert vus == []
    e = son.etat(coord.store.get("e1"))
    assert set(e) == {"alertes", "dernier", "vu_jusqua", "niveau", "maximum", "maj", "pic", "instant", "micros", "jour", "aujourd_hui",
                      "niveau_alerte", "micros_actifs", "micros_liste", "micros_detail"} and e["niveau"] == 55.0
    # Rien d'audio n'est gardé dans la fiche de l'appareil.
    assert "audio" not in str(coord.store.get("e1").get("son")) and "extrait" not in str(coord.store.get("e1").get("son"))


def test_dans_plages():
    from datetime import datetime

    from custom_components.pc_parental import son

    nuit = [{"debut": "22:00", "fin": "07:00"}]
    assert son.dans_plages(nuit, datetime(2026, 10, 14, 23, 30)) and son.dans_plages(nuit, datetime(2026, 10, 14, 6, 59))
    assert not son.dans_plages(nuit, datetime(2026, 10, 14, 12, 0))
    assert son.dans_plages([], datetime(2026, 10, 14, 12, 0))
