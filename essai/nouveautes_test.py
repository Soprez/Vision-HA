"""« Quoi de neuf » côté Home Assistant : notes de version par appareil, une seule fois par version.

Ce qu'on vérifie :
- les notes sortent dans la langue demandée, sinon celle de l'appareil, sinon celle de Home Assistant ;
  un point sans la traduction voulue se replie en anglais, puis en français ;
- chaque appareil ne reçoit que les points de sa cible (téléphone, télé, PC) et, pour les points
  « decideurs », seulement s'il est Décideur ;
- « vu jusqu'à X » (vue dédiée ou champ du relevé) : plus rien ; une nouvelle version : elle seule réapparaît ;
- un appareil qui n'a encore rien vu ne reçoit que la dernière version ;
- un appareil non authentifié est refusé ;
- tous les fichiers de notes ont les 6 langues, 3 à 5 points, des cibles connues, et aucun mot interdit.

Banc : Python 3.14 et pytest-homeassistant-custom-component==0.13.367 (Home Assistant 2026.9.4).
    python -m pytest essai/nouveautes_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import pathlib
import re

import pytest

from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

URL = "/api/pc_parental/nouveautes"
TEL = {"id": "t1", "secret": "1" * 32}       # téléphone d'un Demandeur
TEL_D = {"id": "d1", "secret": "2" * 32}     # téléphone d'un Décideur
TELE = {"id": "v1", "secret": "3" * 32}      # télé
PC = {"id": "w1", "secret": "4" * 32}        # PC Windows
NOTES = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental" / "notes_version"
LANGUES = ("fr", "en", "de", "es", "it", "nl")


@pytest.fixture(autouse=True)
def _notes_figees(request, monkeypatch, tmp_path):
    """Les essais de service partent des notes 2.2.8 à 2.2.12 (figées) : une version publiée ensuite ne change pas
    leurs attentes. Les essais de fichiers (complets, mots interdits) lisent, eux, le vrai dossier."""
    if request.node.name in ("test_fichiers_complets", "test_aucun_mot_interdit"):
        return
    import shutil

    from custom_components.pc_parental import nouveautes
    for f in nouveautes.DOSSIER.glob("*.json"):
        v = tuple(int(x) for x in f.stem.split("."))
        if v <= (2, 2, 12):
            shutil.copy(f, tmp_path / f.name)
    monkeypatch.setattr(nouveautes, "DOSSIER", tmp_path)


async def _monter(hass, hass_storage, **vus):
    """Quatre appareils ; ``vus`` donne la dernière version vue de certains (``t1="2.2.10"``)."""
    pcs = {
        "t1": _fiche("t1", "TEL-A", TEL["secret"], platform="android", role="enfant", type="telephone"),
        "d1": _fiche("d1", "TEL-B", TEL_D["secret"], platform="android", role="parent", type="telephone"),
        "v1": _fiche("v1", "Téléviseur", TELE["secret"], platform="android", role="enfant", type="tv"),
        "w1": _fiche("w1", "PC-A", PC["secret"], platform="windows", role="enfant"),
    }
    for pc_id, v in vus.items():
        pcs[pc_id]["nouveautes_vu"] = v
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": pcs}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


def _nv():
    from custom_components.pc_parental import nouveautes

    return nouveautes


async def _lire(client, appareil, **plus):
    r = await client.post(URL, json={**appareil, **plus})
    assert r.status == 200, await r.text()
    return await r.json()


def _note(version: str, cible: str, textes: dict) -> dict:
    return {"version": version, "date": "2099-01-01", "points": [{"cibles": [cible], "texte": textes}]}


# ------------------------------------------------------------------ Langue


async def test_langue_demandee_appareil_puis_home_assistant(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    hass.config.language = "it"
    d = await _lire(client, TEL)  # ni demandée ni connue de l'appareil : celle de Home Assistant
    assert d["langue"] == "it" and d["version"] == _nv().COURANTE
    assert d["nouveautes"][0]["points"][0] == "Sul telefono, la pagina ospiti mostra le copertine."
    d = await _lire(client, TEL, langue="de-CH")  # demandée (et retenue pour cet appareil)
    assert d["langue"] == "de" and d["nouveautes"][0]["points"][0].startswith("Auf dem Handy")
    d = await _lire(client, TEL)
    assert d["langue"] == "de"
    d = await _lire(client, TEL, langue="pt")  # inconnue : on garde celle de l'appareil
    assert d["langue"] == "de"
    # Le relevé peut aussi donner la langue de l'appareil.
    r = await client.post("/api/pc_parental/poll", json={**TELE, "platform": "android", "type": "tv", "langue": "nl"})
    assert r.status == 200
    await hass.async_block_till_done()  # le premier relevé peut recharger Vision (matrice des types)
    # La langue notée pendant ce premier relevé peut se perdre dans ce rechargement (selon l'ordre des tâches : vu
    # sur le banc Windows) ; l'appareil la redonne à chaque relevé, comme ici, et c'est le suivant qui la garde.
    r = await client.post("/api/pc_parental/poll", json={**TELE, "platform": "android", "type": "tv", "langue": "nl"})
    assert r.status == 200
    await hass.async_block_till_done()
    d = await _lire(client, TELE)
    assert d["langue"] == "nl" and d["nouveautes"][0]["points"] == ["De tv gaat niet meer in slaapstand terwijl er muziek speelt."]


async def test_repli_en_anglais_puis_en_francais(hass, hass_storage, hass_client, monkeypatch):
    await _monter(hass, hass_storage, t1="2.2.12")
    client = await hass_client()
    nv = _nv()
    await nv.charger(hass)
    monkeypatch.setattr(nv, "COURANTE", "2.2.13")
    hass.data[nv._CACHE]["2.2.13"] = {"version": "2.2.13", "date": "", "points": [
        {"cibles": ["telephone"], "texte": {"fr": "Point A", "en": "Item A"}},
        {"cibles": ["telephone"], "texte": {"fr": "Point B seulement"}},
        {"cibles": ["telephone"], "texte": {"es": "Punto C", "fr": "Point C"}},
    ]}
    d = await _lire(client, TEL, langue="de")
    assert d["nouveautes"] == [{"version": "2.2.13", "date": "", "points": ["Item A", "Point B seulement", "Point C"]}]
    d = await _lire(client, TEL, langue="es")
    assert d["nouveautes"][0]["points"] == ["Item A", "Point B seulement", "Punto C"]


# ------------------------------------------------------------------ Cible et public


async def test_filtrage_par_cible_et_public(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage, t1="2.2.10", d1="2.2.10", v1="2.2.10", w1="2.2.10")
    client = await hass_client()
    hass.config.language = "fr"
    tel = await _lire(client, TEL)
    assert tel["cible"] == "telephone" and [n["version"] for n in tel["nouveautes"]] == ["2.2.12", "2.2.11"]
    assert len(tel["nouveautes"][0]["points"]) == 3
    # Le Demandeur ne voit pas le point réservé aux Décideurs (écran Journal Musique)…
    assert not any("Journal Musique" in p for p in tel["nouveautes"][1]["points"])
    # … le Décideur, si ; et aucun des deux ne voit les points du seul tableau Home Assistant.
    dec = await _lire(client, TEL_D)
    assert any("Journal Musique" in p for p in dec["nouveautes"][1]["points"])
    assert not any("journal de Home Assistant" in p for n in dec["nouveautes"] for p in n["points"])
    tele = await _lire(client, TELE)
    assert tele["cible"] == "tele"
    assert tele["nouveautes"][0] == {"version": "2.2.12", "date": "2026-10-10",
                                     "points": ["La télé ne se met plus en veille pendant que la musique joue."]}
    assert not any("page invité" in p for n in tele["nouveautes"] for p in n["points"])
    # Rien pour le PC dans ces versions : aucune fenêtre ne doit s'ouvrir.
    pc = await _lire(client, PC)
    assert pc["cible"] == "pc" and pc["nouveautes"] == []
    # Une cible demandée explicitement l'emporte sur le type.
    d = await _lire(client, TEL, cible="tele")
    assert d["cible"] == "tele" and d["nouveautes"][0]["points"] == tele["nouveautes"][0]["points"]


async def test_point_de_module_coupe_pas_servi(hass, hass_storage, hass_client):
    """2.5.12.14 : un point marqué « module » (le niveau sonore, éteint par défaut) ne sort que module allumé."""
    await _monter(hass, hass_storage, t1="2.2.10", d1="2.2.10", v1="2.2.10", w1="2.2.10")
    client = await hass_client()
    hass.config.language = "fr"
    coord = next(iter(hass.data[DOMAIN].values()))
    nv = _nv()
    await nv.charger(hass)
    hass.data[nv._CACHE]["2.2.12"]["points"].append(
        {"cibles": ["telephone"], "module": "son", "texte": {"fr": "Point du module sonore", "en": "Sound module item"}})
    sans = await _lire(client, TEL)
    assert not any("module sonore" in p for n in sans["nouveautes"] for p in n["points"])
    from custom_components.pc_parental import modules
    coord.store.modules = {"son": True}
    assert modules.actif(coord.store, "son")
    avec = await _lire(client, TEL)
    assert any("module sonore" in p for n in avec["nouveautes"] for p in n["points"])


# ------------------------------------------------------------------ Vu, nouvelle version, appareil neuf


async def test_vu_puis_nouvelle_version(hass, hass_storage, hass_client, monkeypatch):
    await _monter(hass, hass_storage)
    client = await hass_client()
    releve = {**TEL, "platform": "android", "type": "telephone"}
    r = await (await client.post("/api/pc_parental/poll", json=releve)).json()
    assert r["nouveautes"] == "2.2.12"
    d = await _lire(client, TEL, vu="2.2.12")
    assert d["vu"] == "2.2.12" and d["nouveautes"] == []
    r = await (await client.post("/api/pc_parental/poll", json=releve)).json()
    assert "nouveautes" not in r
    # Gardé dans le store (persistant).
    await hass.async_block_till_done()
    assert hass_storage[DOMAIN]["data"]["pcs"]["t1"]["nouveautes_vu"] == "2.2.12"
    # Le « vu » ne recule pas.
    d = await _lire(client, TEL, vu="2.2.8")
    assert d["vu"] == "2.2.12" and d["nouveautes"] == []
    # Une version de plus : elle seule réapparaît (pas la 2.2.12 déjà vue).
    nv = _nv()
    monkeypatch.setattr(nv, "COURANTE", "2.2.13")
    hass.data[nv._CACHE]["2.2.13"] = _note("2.2.13", "telephone", {"fr": "Nouveau point", "en": "New item"})
    r = await (await client.post("/api/pc_parental/poll", json=releve)).json()
    assert r["nouveautes"] == "2.2.13"
    d = await _lire(client, TEL)
    assert [n["version"] for n in d["nouveautes"]] == ["2.2.13"]
    # « Vu » par le champ du relevé, cette fois.
    r = await (await client.post("/api/pc_parental/poll", json={**releve, "nouveautes_vu": "2.2.13"})).json()
    # (Un relevé peut recharger Vision : on reprend le coordinateur du moment.)
    coord = next(iter(hass.data[DOMAIN].values()))
    assert "nouveautes" not in r and coord.store.pcs["t1"]["nouveautes_vu"] == "2.2.13"
    # Une version au-delà de celle installée est ramenée à celle-ci.
    assert nv.marquer_vu(coord.store.pcs["w1"], "9.9.9") and coord.store.pcs["w1"]["nouveautes_vu"] == "2.2.13"


async def test_notes_preparees_d_avance_pas_servies(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage, t1="2.2.12")
    client = await hass_client()
    nv = _nv()
    await nv.charger(hass)
    hass.data[nv._CACHE]["99.0.0"] = _note("99.0.0", "telephone", {"fr": "Pas encore", "en": "Not yet"})
    assert (await _lire(client, TEL))["nouveautes"] == []


async def test_appareil_neuf_seulement_la_derniere_version(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage, d1="2.2.8")
    client = await hass_client()
    d = await _lire(client, TEL)  # rien de vu : seulement la dernière version
    assert [n["version"] for n in d["nouveautes"]] == ["2.2.12"] and d["vu"] == ""
    # Resté éteint longtemps : trois versions au plus, de la plus récente à la plus ancienne.
    d = await _lire(client, TEL_D)
    assert [n["version"] for n in d["nouveautes"]] == ["2.2.12", "2.2.11", "2.2.10"]


# ------------------------------------------------------------------ Authentification


async def test_appareil_non_authentifie_refuse(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    client = await hass_client()
    for corps in ({"id": "t1", "secret": "faux"}, {"id": "inconnu", "secret": TEL["secret"]}, {}):
        r = await client.post(URL, json={**corps, "vu": "2.2.12"})
        assert r.status == 401
    assert "nouveautes_vu" not in coord.store.pcs["t1"]
    r = await client.post(URL, data="pas du json")
    assert r.status == 400
    # Le « vu » par le relevé exige lui aussi le secret de l'appareil.
    r = await client.post("/api/pc_parental/poll", json={"id": "t1", "secret": "faux", "nouveautes_vu": "2.2.12"})
    coord = next(iter(hass.data[DOMAIN].values()))
    assert r.status == 401 and "nouveautes_vu" not in coord.store.pcs["t1"]


# ------------------------------------------------------------------ Fichiers de notes


def _fichiers():
    fichiers = sorted(NOTES.glob("*.json"))
    assert fichiers, "aucun fichier de notes"
    return fichiers


def test_fichiers_complets():
    cibles = {"ha", "telephone", "tele", "pc"}
    for f in _fichiers():
        d = json.loads(f.read_text(encoding="utf-8"))
        assert d["version"] == f.stem, f.name
        assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", d.get("date") or ""), f.name
        assert 3 <= len(d["points"]) <= 5, f"{f.name} : {len(d['points'])} points"
        for p in d["points"]:
            assert p["cibles"] and set(p["cibles"]) <= cibles, (f.name, p["cibles"])
            assert p.get("public", "tous") in ("tous", "decideurs"), f.name
            for lg in LANGUES:
                t = str(p["texte"].get(lg) or "").strip()
                assert t, f"{f.name} : point sans « {lg} »"
                assert len(t) <= 120, f"{f.name} ({lg}) : point trop long"


# Vocabulaire neutre : jamais ces mots (mots entiers, casse ignorée), dans aucune des 6 langues.
# « Home Assistant » est permis : on le retire avant de chercher « home ».
INTERDITS = (
    "famille", "familles", "familial", "familiale", "maison", "maisons", "foyer", "parent", "parents",
    "parental", "parentale", "enfant", "enfants",
    "family", "families", "home", "homes", "house", "household", "parenting", "child", "children", "kid", "kids",
    "familie", "familien", "eltern", "elternteil", "kind", "kinder", "haus", "zuhause", "heim",
    "gezin", "gezinnen", "ouder", "ouders", "kinderen", "huis", "thuis",
    "familia", "familias", "hogar", "casa", "padre", "padres", "madre", "niño", "niños", "niña", "hijo", "hijos",
    "famiglia", "famiglie", "genitore", "genitori", "bambino", "bambini", "bambina", "figlio", "figli",
)


def test_aucun_mot_interdit():
    motif = re.compile(r"\b(?:" + "|".join(map(re.escape, INTERDITS)) + r")\b", re.IGNORECASE | re.UNICODE)
    for f in _fichiers():
        brut = f.read_text(encoding="utf-8")
        trouves = motif.findall(re.sub(r"Home Assistant", "", brut, flags=re.IGNORECASE))
        assert not trouves, f"{f.name} : {sorted(set(trouves))}"
