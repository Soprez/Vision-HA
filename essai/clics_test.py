"""Les gestes des cartes Vision dans un vrai navigateur (1.97.3, règle : un clic met l'affichage à jour tout de suite).

Les données des cartes sont celles que Vision sert vraiment (lues ici par l'API, sur un Home Assistant d'essai) ;
les cartes tournent dans Edge ou Chrome sans affichage (essai/clics/clics.mjs), avec un serveur simulé dont le test
tient la réponse : pendant l'attente, le bouton dit « En cours… » ; après, l'affichage suit la réponse ; en cas
d'échec, un message clair en français. Sauté si Node ou le navigateur manquent.

    python -m pytest essai/clics_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import asyncio
import json
import os
import pathlib
import shutil
import time

import pytest

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

ICI = pathlib.Path(__file__).parent
CARTES = ICI.parent / "custom_components" / "pc_parental" / "frontend"
NAVIGATEURS = (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    "/usr/bin/chromium", "/usr/bin/google-chrome",
)
LECTURES = {
    "maison": ["pc_parental/maison"],
    "widgets": ["pc_parental/widgets/admin"],
    "apparence": ["pc_parental/apparence"],
    "veille": ["pc_parental/veille/tableaux"],
    "dashboards": ["pc_parental/veille/dashboards"],
    "catalogue": ["pc_parental/catalogue"],
    "processus": ["pc_parental/processus"],
}


def _navigateur() -> str | None:
    return next((n for n in NAVIGATEURS if os.path.exists(n)), None)


async def test_chaque_geste_se_voit_tout_de_suite(hass, hass_storage, hass_client, tmp_path):
    nav, node = _navigateur(), shutil.which("node")
    if not nav or not node:
        pytest.skip("Node ou un navigateur Chromium manquent : banc des gestes sauté.")
    maintenant = time.time()
    lou = _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone")
    lou["demandes"] = [{"id": "d1", "genre": "apps", "cle": "com.roblox.client", "nom": "com.roblox.client", "libelle": "Roblox",
                          "lien": "", "motif": "Pour jouer avec Noa", "duree": "1h", "ts": maintenant - 60}]
    lou["demande"] = {"id": "t1", "minutes": 15, "ts": maintenant - 30}
    # Lot 1 (HA-02) : de quoi trancher dans l'onglet Autorisations (un logiciel au nom long, deux sites).
    lou["apps_vues"] = {"com.mojang.minecraftpe.un.nom.de.paquet.tres.long": {"premier": maintenant, "dernier": maintenant, "secondes": 60}}
    lou["sites_vus"] = {d: {"premier": maintenant, "dernier": maintenant, "n": 1} for d in ("wikipedia.org", "twitch.tv")}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "entites_personnes": {"person.lou": ["sensor.salon"]},
        "pcs": {"e1": lou, "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent")},
    }}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set("person.robin", "home", {"friendly_name": "Robin"})
    hass.states.async_set("sensor.salon", "21.5", {"friendly_name": "Température salon", "unit_of_measurement": "°C"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()

    # Les données, telles que Vision les sert.
    client = await hass_client()
    (tmp_path / "donnees").mkdir()
    for nom, chemins in LECTURES.items():
        lu = {}
        for chemin in chemins:
            r = await client.get("/api/" + chemin)
            assert r.status == 200, (chemin, r.status, await r.text())
            lu[chemin] = await r.json()
        (tmp_path / "donnees" / f"{nom}.json").write_text(json.dumps(lu, ensure_ascii=False), encoding="utf-8")
    # La carte Planning lit une entité « plages » : la vraie, avec le catalogue.
    plages = next(e for e in hass.states.async_all("sensor") if "plages" in e.attributes)
    planning = json.loads((tmp_path / "donnees" / "catalogue.json").read_text(encoding="utf-8"))
    planning["__entite"] = plages.entity_id
    planning["__etats"] = {plages.entity_id: {"entity_id": plages.entity_id, "state": plages.state, "attributes": json.loads(json.dumps(dict(plages.attributes), default=str))}}
    (tmp_path / "donnees" / "planning.json").write_text(json.dumps(planning, ensure_ascii=False), encoding="utf-8")
    m = json.loads((tmp_path / "donnees" / "maison.json").read_text(encoding="utf-8"))["pc_parental/maison"]
    assert {d["genre"] for d in m["demandes"]} == {"apps", "temps"}
    shutil.copy(ICI / "clics" / "banc.html", tmp_path / "banc.html")
    for f in CARTES.glob("vision-*.js"):
        shutil.copy(f, tmp_path / f.name)

    # Le script sert lui-même le dossier, puis pilote le navigateur.
    proc = await asyncio.create_subprocess_exec(node, str(ICI / "clics" / "clics.mjs"), str(tmp_path), nav,
                                                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    sortie, erreurs = await asyncio.wait_for(proc.communicate(), timeout=300)
    r = type("R", (), {"stdout": sortie.decode("utf-8", "replace"), "stderr": erreurs.decode("utf-8", "replace")})
    lignes = [json.loads(x) for x in r.stdout.splitlines() if x.startswith("{")]
    ratés = [x for x in lignes if not x.get("ok") and not x.get("fin")]
    fin = next((x for x in lignes if x.get("fin")), {"echecs": "?", "erreur": r.stderr[-500:]})
    assert len([x for x in lignes if "nom" in x]) >= 19, r.stdout + r.stderr
    assert not ratés and fin.get("echecs") == 0, json.dumps(ratés or fin, ensure_ascii=False, indent=1)

