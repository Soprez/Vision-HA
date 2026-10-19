"""Livraison des demandes d'accès aux Décideurs (demandes_livraison.py) : une demande déposée n'est jamais perdue.

Constat du 19/10 : des demandes envoyées pendant des redémarrages de Home Assistant ne sont jamais arrivées, et le
message de l'appli Vision du Décideur ne vivait que 15 minutes, remis une seule fois.

- le message « acces » d'un Décideur vit tant que la demande est ouverte ; sa remise au relevé est notée ;
- une demande réglée ne sonne plus chez un Décideur qui revient en ligne ;
- secours sur l'appli Compagnon de tous les Décideurs si personne ne l'a reçue au bout de 5 min (une seule fois) ;
- notification Home Assistant recréée au démarrage ;
- capteur sensor.vision_demandes_en_attente.

    python -m pytest essai/demandes_livraison_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess

import pytest

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _monter, _integrations, expected_lingering_tasks, expected_lingering_timers,
)


async def _demander(client, nom="com.roblox.client", libelle="Roblox"):
    r = await client.post("/api/pc_parental/demande", json={**ENFANT, "genre": "apps", "nom": nom, "libelle": libelle})
    assert r.status == 200, await r.text()
    return (await r.json())["demande"]


def _acces(coord, pid="p1"):
    return [m for m in coord.store.pcs[pid].get("messages") or [] if m.get("acces")]


async def test_message_du_decideur_attend_et_accuse_reception(hass, hass_storage, hass_client):
    from custom_components.pc_parental import demandes_livraison

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    coord.store.pcs["p1"]["messages"] = []
    ident = await _demander(client)
    msgs = _acces(coord)
    assert len(msgs) == 1
    d = demandes_livraison.trouver(coord.store, "e1", ident)
    assert abs(msgs[0]["expire"] - (d["ts"] + demandes_livraison.DUREE_S)) < 1
    # Le téléphone du Décideur était hors ligne 40 min : avant, le message tombait au bout de 15 min.
    msgs[0]["ts"] -= 40 * 60
    r = await client.post("/api/pc_parental/poll", json={**PARENT})
    assert r.status == 200
    remis = [m for m in (await r.json()).get("notify") or [] if m.get("acces")]
    assert len(remis) == 1 and remis[0]["acces"]["id"] == ident
    # Accusé de réception : la remise est notée dans la demande.
    assert "p1" in (d.get("livree") or {})
    # Remis une fois : le relevé suivant ne le redonne pas.
    r = await client.post("/api/pc_parental/poll", json={**PARENT})
    assert not [m for m in (await r.json()).get("notify") or [] if m.get("acces")]


async def test_demande_reglee_ne_sonne_plus(hass, hass_storage, hass_client):
    from custom_components.pc_parental import demandes

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    coord.store.pcs["p1"]["messages"] = []
    ident = await _demander(client)
    # Réglée ailleurs (autre Décideur, Home Assistant) pendant que ce téléphone était hors ligne.
    assert demandes.retirer(coord.store.pcs["e1"], ident) is not None
    r = await client.post("/api/pc_parental/poll", json={**PARENT})
    assert not [m for m in (await r.json()).get("notify") or [] if m.get("acces")]


async def test_secours_si_personne_ne_l_a_recue(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import canaux, demandes_livraison, notifs_mobiles

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    envois = []

    async def _envoyer(hass_, store, pc, demande, texte, services=None):
        envois.append((pc["id"], demande["id"], list(services or []), texte))
        return len(services or [])

    monkeypatch.setattr(notifs_mobiles, "envoyer", _envoyer)
    monkeypatch.setattr(canaux, "compagnon_tous", lambda hass_, store: ["mobile_app_pixel_9"])
    ident = await _demander(client)
    d = demandes_livraison.trouver(coord.store, "e1", ident)
    await hass.async_block_till_done()
    envois.clear()  # l'envoi du dépôt (téléphones ayant choisi Compagnon) n'est pas un secours
    # Trop tôt : rien.
    assert await demandes_livraison.async_verifier(hass, coord) == 0 and envois == []
    # 6 min sans qu'aucun Décideur ne l'ait reçue : secours sur tous les téléphones, une seule fois.
    assert await demandes_livraison.async_verifier(hass, coord, maintenant=d["ts"] + 6 * 60) == 1
    assert envois == [("e1", ident, ["mobile_app_pixel_9"], envois[0][3])] and "Roblox" in envois[0][3]
    assert d.get("secours")
    assert await demandes_livraison.async_verifier(hass, coord, maintenant=d["ts"] + 20 * 60) == 0
    assert len(envois) == 1
    # Une demande déjà reçue par un Décideur ne part pas en secours.
    ident2 = await _demander(client, "com.discord", "Discord")
    d2 = demandes_livraison.trouver(coord.store, "e1", ident2)
    await hass.async_block_till_done()
    envois.clear()
    d2["livree"] = {"p1": d2["ts"] + 30}
    assert await demandes_livraison.async_verifier(hass, coord, maintenant=d2["ts"] + 10 * 60) == 0
    assert envois == []


async def test_notification_ha_recreee_au_demarrage(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import demandes_livraison

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    ident = await _demander(client)
    creees = []
    monkeypatch.setattr(demandes_livraison.persistent_notification, "async_create",
                        lambda hass_, message, title=None, notification_id=None: creees.append((notification_id, message)))
    arret = demandes_livraison.demarrer(hass, coord)
    arret()
    assert [c[0] for c in creees] == [f"{DOMAIN}_acces_e1_{ident}"] and "Roblox" in creees[0][1]


async def test_capteur_demandes_en_attente(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    await _demander(client)
    coord.async_update_listeners()
    await hass.async_block_till_done()
    s = hass.states.get("sensor.vision_demandes_en_attente")
    assert s is not None and s.state == "1"
    d = s.attributes["demandes"][0]
    assert d["type"] == "apps" and d["objet"] == "Roblox" and d["appareil"] and d["depuis"] and d["recue_par"] == []
    assert d["secours"] is False


@pytest.mark.skipif(shutil.which("powershell.exe") is None, reason="Windows PowerShell absent")
def test_agent_garde_la_demande_tant_que_ha_ne_l_a_pas_recue():
    """La vraie Send-Demandes de l'agent face à Home Assistant qui démarre (503, 404), refuse (400) ou répond :
    essai/demandes_agent_essai.ps1."""
    essai = pathlib.Path(__file__).with_name("demandes_agent_essai.ps1")
    r = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(essai)],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    assert r.returncode == 0, r.stdout[-3000:] + r.stderr[-2000:]
