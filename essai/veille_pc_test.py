"""2.4.2 / agent 1.91.0 (l'auteur, 2026-10-12) : l'écran de veille des PC Windows est retiré ; les télés et téléphones gardent
le leur.

- un PC Windows ne reçoit jamais « veille » ni « veille_tableaux » (les agents pas encore à jour la rouvriraient) ;
- un ancien enregistrement (« veille_pc ») se charge sans erreur, et les interrupteurs de la veille des PC sortent du
  registre ;
- plus de ticket d'images pour un PC, ni d'accès aux images par ticket ;
- l'agent n'a plus de code de veille, et nettoie ce qu'elle laissait sur le PC.

    python -m pytest essai/veille_pc_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import re

import pathlib

from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

AGENT = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental" / "client" / "ha-parental-agent.ps1"


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        # Un ancien enregistrement : la veille des PC allumée pour tous.
        "veille_pc": {"global": True, "pcs": {"e1": True}},
        "veille_cameras": ["camera.entree"],
        "pcs": {
            "e1": _fiche("e1", "PC-LOU", ENFANT["secret"], platform="windows", role="enfant"),
            "e2": _fiche("e2", "TELE", ENFANT2["secret"], platform="android", role="enfant", type="tv"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], platform="android", role="parent"),
        }}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    return entree


async def test_jamais_de_veille_sur_un_pc(hass, hass_storage, hass_client):
    entree = await _monter(hass, hass_storage)
    # Les interrupteurs de l'ancienne version, encore dans le registre.
    reg = er.async_get(hass)
    for uid in ("pc_parental_veille_pc", "pc_parental_veille_pc_e1"):
        reg.async_get_or_create("switch", DOMAIN, uid, config_entry=entree)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    from custom_components.pc_parental import modules as mods
    coord.store.modules = {**(coord.store.modules or {}), "veille": True, "veille_tableaux": True}
    assert "veille" in mods.actifs(coord.store)
    client = await hass_client()
    pc = await (await client.post("/api/pc_parental/poll", json={**ENFANT})).json()
    tele = await (await client.post("/api/pc_parental/poll", json={**ENFANT2, "platform": "android", "type": "tv"})).json()
    assert "veille" not in pc["modules"] and "veille_tableaux" not in pc["modules"]
    assert "veille" in tele["modules"]
    # Les interrupteurs de la veille des PC ont quitté le registre ; l'ancien champ n'est plus gardé.
    assert not [e.unique_id for e in reg.entities.values() if e.unique_id.startswith("pc_parental_veille_pc")]
    assert not hasattr(coord.store, "veille_pc")


async def test_plus_de_ticket_d_images_pour_un_pc(hass, hass_storage, hass_client_no_auth):
    from custom_components.pc_parental import veille_passerelle

    hass.states.async_set("camera.entree", "idle", {"friendly_name": "Entrée"})
    entree = await _monter(hass, hass_storage)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    client = await hass_client_no_auth()
    r = await client.post("/api/pc_parental/veille", json={**ENFANT, "ecran": "tele"})
    if r.status == 200:
        assert "ticket_images" not in await r.json()
    ticket = veille_passerelle.ticket(coord.store.pcs["e1"])
    r = await client.post("/api/pc_parental/veille/image", json={"ticket": ticket, "entite": "camera.entree"})
    assert r.status == 401


def test_agent_sans_veille_et_nettoyage():
    agent = AGENT.read_text(encoding="utf-8-sig")
    # La veille est partie en 1.91.0 : toute version d'après la garde partie.
    version = re.search(r"\$script:Version\s+= '(\d+)\.(\d+)\.(\d+)'", agent)
    assert version and tuple(int(x) for x in version.groups()) >= (1, 91, 0), version
    for absent in ("VisionGarde", "VisionCamera", "VisionVeilleNatif", "Install-EcranDeVeille", "Sync-Veille", "SvOuvrir",
                   "SvGuet", "/api/pc_parental/veille", "reglages.veille"):
        assert absent not in agent, absent
    # Nettoyage : Vision.scr et son marqueur (service), la clé de l'écran de veille de Windows (session) si elle
    # désigne Vision.scr, les profils Edge et images de l'ancienne veille, ses données dans la sonde.
    assert "function Remove-EcranDeVeille" in agent and "try { Remove-EcranDeVeille }" in agent
    assert "'SCRNSAVE.EXE'" in agent and "-ieq 'Vision.scr'" in agent
    assert "'veille-edge-*'" in agent and "'vision-camera*'" in agent and "@('veille.json', 'veille.log')" in agent
