"""1.94 · « Signaler un problème » (HA) : anonymisation sur le corpus commun, vue du journal d'une période.

    python -m pytest essai/diag_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import logging
import pathlib
import time

import pytest

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _monter, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

CORPUS = json.loads((pathlib.Path(__file__).with_name("corpus_anonymisation.json")).read_text(encoding="utf-8"))


@pytest.mark.parametrize("cas", CORPUS["cas"], ids=[c["nom"] for c in CORPUS["cas"]])
def test_corpus_commun(cas):
    from custom_components.pc_parental.diag import Anonymiseur

    a = Anonymiseur(CORPUS["personnes"], CORPUS["appareils"])
    assert a.ligne(cas["entree"]) == cas["sortie"]
    assert a.remplacements == cas["n"]


def test_table_stable_sur_plusieurs_lignes():
    from custom_components.pc_parental.diag import Anonymiseur

    a = Anonymiseur()
    assert a.ligne("a 10.1.1.5") == "a ip-1"
    assert a.ligne("b 10.1.1.6 puis 10.1.1.5") == "b ip-2 puis ip-1"


def test_nom_trop_court_non_masque():
    from custom_components.pc_parental.diag import Anonymiseur

    assert Anonymiseur(["Al"]).ligne("Al est la") == "Al est la"


async def _poser_code(hass, coord, personne="person.nolan", code="4821"):
    from custom_components.pc_parental import octroi

    await hass.async_add_executor_job(octroi.poser, coord.store, personne, code)


async def test_periode_refusee_sans_droit(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    await _poser_code(hass, coord)
    # Un appareil qui ne gère pas : refus, même avec le bon code.
    r = await client.post("/api/pc_parental/diag/periode", json={**ENFANT, "code_octroi": "4821"})
    assert r.status == 403 and (await r.json())["error"] == "parent"
    # Secret inconnu.
    r = await client.post("/api/pc_parental/diag/periode", json={"id": "p1", "secret": "faux", "code_octroi": "4821"})
    assert r.status == 401
    # Responsable sans code, ou avec un faux code.
    r = await client.post("/api/pc_parental/diag/periode", json={**PARENT})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"
    r = await client.post("/api/pc_parental/diag/periode", json={**PARENT, "code_octroi": "0000"})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi_faux"


async def test_periode_rend_le_journal_anonymise(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    await _poser_code(hass, coord)
    debut = time.time() - 1
    log = logging.getLogger("custom_components.pc_parental.essai")
    log.warning("Lou a demande du temps depuis 10.1.1.23 jeton=abc123 (TEL-LOU)")
    log.debug("trop bavard pour le tampon")
    hass.bus.async_fire("pc_parental_reponse_temps", {"name": "TEL-LOU", "minutes": 30, "secret": "zzz"})
    await hass.async_block_till_done()
    r = await client.post("/api/pc_parental/diag/periode",
                          json={**PARENT, "code_octroi": "4821", "depuis": debut, "jusqua": time.time() + 5})
    assert r.status == 200, await r.text()
    d = await r.json()
    texte = "\n".join(d["lignes"])
    assert "personne-" in texte and "ip-1" in texte and "[secret]" in texte
    for interdit in ("10.1.1.23", "abc123", "zzz", "trop bavard"):
        assert interdit not in texte
    assert "pc_parental_reponse_temps" in texte
    assert d["remplacements"] >= 3 and d["tronque"] is False
    assert d["versions"]["integration"]
    # Aucun secret d'appareil dans la réponse.
    for s in (ENFANT["secret"], PARENT["secret"]):
        assert s not in json.dumps(d)


async def test_periode_bornee_a_un_mega(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    await _poser_code(hass, coord)
    log = logging.getLogger("custom_components.pc_parental.essai")
    for i in range(1500):
        log.warning("ligne %d %s", i, "x" * 900)
    r = await client.post("/api/pc_parental/diag/periode",
                          json={**PARENT, "code_octroi": "4821", "depuis": time.time() - 60})
    d = await r.json()
    assert d["tronque"] is True
    assert sum(len(l.encode()) + 1 for l in d["lignes"]) <= 1_000_000
    assert "ligne 1499" in d["lignes"][-1]  # on garde la fin, le plus récent
