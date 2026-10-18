"""Agent Windows 1.92.0 (refonte des autorisations, HA 2.5.0) : la fenêtre Vision du Décideur répond à une demande avec
15 min, 1 h, Exception, Toujours disponible, (Catégorie), Refuser — comme le tableau de Home Assistant.

La fenêtre (WPF) n'est pas lancée ici : on vérifie les choix écrits dans l'agent et leurs textes dans les six
langues, puis le même corps envoyé par un PC de Décideur à Home Assistant (/api/pc_parental/parent).

    python -m pytest essai/agent_autorisations_test.py -x -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

CLIENT = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental" / "client"
AGENT = CLIENT / "ha-parental-agent.ps1"


def test_la_fenetre_propose_les_cinq_reponses():
    agent = AGENT.read_text(encoding="utf-8-sig")
    debut = agent.index("function PageMaison()")
    page = agent[debut:agent.index("\nfunction ", debut + 10)]
    for attendu in ("(T 'maison.bouton_15'), 'temporaire', 15", "(T 'maison.bouton_1h'), 'temporaire', 60",
                    "(T 'maison.bouton_exception'), 'accorder', 0", "'exception')",
                    "(T 'maison.bouton_toujours_dispo'), 'accorder', 0", "'toujours')",
                    "(T 'maison.bouton_refuser'), 'non', 0"):
        assert attendu in page, attendu
    assert re.search(r"Agir @\{ action = 'acces';[^}]*mode = \[string\]\$t\.mode \}", page)


def test_textes_dans_les_six_langues():
    for lg in ("fr", "en", "de", "es", "it", "nl"):
        d = json.loads((CLIENT / "langues" / f"{lg}.json").read_text(encoding="utf-8-sig"))
        for cle in ("maison.bouton_15", "maison.bouton_1h", "maison.bouton_exception", "maison.bouton_toujours_dispo",
                    "maison.bouton_refuser", "maison.aide_choix"):
            assert d.get(cle), (lg, cle)


async def test_le_pc_du_decideur_accorde_en_exception(hass, hass_storage, hass_client):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "etiquettes": {"apps": {"roblox": ["Jeux"]}},
        "fermetures": {"person.lou": {"jeux|": {"etiquette": "Jeux", "raison": "Fermé.", "source": "", "depuis": 1}}},
        "pcs": {
            "w1": _fiche("w1", "PC-LOU", ENFANT["secret"], person="person.lou", platform="windows"),
            "p1": _fiche("p1", "PC-NOLAN", PARENT["secret"], person="person.nolan", platform="windows", role="parent"),
        }}}
    for p in ("lou", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    coord.store.autorisations_v2 = True
    demandes = next(m for n, m in sys.modules.items() if n.endswith("pc_parental.demandes"))
    d = demandes.deposer(hass, coord, coord.store.pcs["w1"], "apps", "roblox", "Roblox", duree="toujours")
    client = await hass_client()
    r = await client.post("/api/pc_parental/parent", json={**PARENT, "action": "acces", "pc": "w1", "demande": d["id"],
                                                           "decision": "accorder", "minutes": 0, "mode": "exception"})
    assert r.status == 200, await r.text()
    assert coord.store.mode_accorde(coord.store.pcs["w1"], "apps", "roblox") == "exception"
    assert not demandes.en_attente(coord.store.pcs["w1"])
