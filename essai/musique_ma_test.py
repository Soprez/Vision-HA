"""1.94 : « Music Assistant » à l'accueil.

- invisible si Music Assistant n'est pas installé dans Home Assistant ; le bloc `musique_ma` de /veille n'existe pas, pas de widget « ma » ;
- installé : le bloc (port, panneau d'ingress, jamais d'adresse interne) et le widget, pour un Décideur seulement ;
- le droit « Ouvrir Music Assistant » : un Demandeur ne l'a pas d'office, un administrateur peut le lui donner ; module Accueil coupé : rien ;
- les installations d'avant : le rôle Décideur déjà enregistré le reçoit une fois ;
- les réponses « à faire dans Music Assistant » portent `ouvrir_ma` (l'appli les rend cliquables).

    python -m pytest essai/musique_ma_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from giphy_test import _installer  # noqa: F401  (une installation d'essai : un Demandeur e1, un Décideur p1)
from party_soir_test import A_FAIRE, _party  # noqa: F401
from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, PARENT, TELE, _integrations, _post, expected_lingering_tasks, expected_lingering_timers,
)

DOMAINE = "pc_parental"
RACINE = Path(__file__).resolve().parents[1] / "custom_components" / "pc_parental"
LANGUES = ("fr", "en", "de", "es", "it", "nl")


def _ma_installe(monkeypatch, url="http://d5369777-music-assistant:8095"):
    """Music Assistant « chargé » sans en monter l'intégration (une vraie entrée serait déchargée à la fin de l'essai)."""
    import importlib

    musique = importlib.import_module("custom_components.pc_parental.musique")
    entree = SimpleNamespace(data={"url": url}, state=SimpleNamespace(value="loaded"), domain="music_assistant")
    monkeypatch.setattr(musique, "entree", lambda hass: entree)


async def _etat(hass, coord, pc_id):
    from custom_components.pc_parental import veille

    return await veille.etat_veille(hass, coord, coord.store.pcs[pc_id])


async def test_invisible_sans_music_assistant(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    e = await _etat(hass, coord, "p1")
    assert e["musique_ma"] is None and "ma" not in e["widgets_disponibles"]


async def test_installe_le_decideur_a_le_bloc_et_le_widget(hass, hass_storage, monkeypatch):
    coord = await _installer(hass, hass_storage)
    _ma_installe(monkeypatch)
    hass.data["frontend_panels"] = {"d5369777_music_assistant": SimpleNamespace(sidebar_title="Music Assistant"),
                                    "map": SimpleNamespace(sidebar_title="Map")}
    e = await _etat(hass, coord, "p1")
    assert e["musique_ma"] == {"installe": True, "port": 8095, "ingress": "/d5369777_music_assistant"}
    assert "ma" in e["widgets_disponibles"]
    # Jamais l'adresse interne de l'extension (non joignable d'un appareil), ni le nom d'hôte.
    assert "d5369777-music-assistant" not in json.dumps(e["musique_ma"])


async def test_sans_panneau_ni_port_dans_l_entree(hass, hass_storage, monkeypatch):
    coord = await _installer(hass, hass_storage)
    _ma_installe(monkeypatch, url="")
    hass.data["frontend_panels"] = {}
    e = await _etat(hass, coord, "p1")
    assert e["musique_ma"]["port"] == 8095 and e["musique_ma"]["ingress"] == ""


async def test_le_port_vient_de_l_entree(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental import musique_ma

    await _installer(hass, hass_storage)
    _ma_installe(monkeypatch, url="http://10.1.1.20:8097/")
    assert musique_ma.port(hass) == 8097


async def test_un_demandeur_n_a_ni_bloc_ni_widget(hass, hass_storage, monkeypatch):
    coord = await _installer(hass, hass_storage)
    _ma_installe(monkeypatch)
    e = await _etat(hass, coord, "e1")
    assert e["musique_ma"] is None and "ma" not in e["widgets_disponibles"]


async def test_l_administrateur_peut_donner_le_droit_a_un_demandeur(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental import roles

    coord = await _installer(hass, hass_storage)
    _ma_installe(monkeypatch)
    assert roles.a(coord.store, coord.store.pcs["e1"], "musique_ma") is False
    coord.store.roles[roles.ENFANT]["fonctions"] = list(coord.store.roles[roles.ENFANT]["fonctions"]) + ["musique_ma"]
    e = await _etat(hass, coord, "e1")
    assert e["musique_ma"] and "ma" in e["widgets_disponibles"]


async def test_module_accueil_coupe_rien(hass, hass_storage, monkeypatch):
    coord = await _installer(hass, hass_storage)
    _ma_installe(monkeypatch)
    coord.store.modules = {**dict(coord.store.modules or {}), "accueil": False}
    e = await _etat(hass, coord, "p1")
    assert e["musique_ma"] is None


async def test_le_droit_est_sensible_et_traduit(hass, hass_storage):
    from custom_components.pc_parental import roles

    assert "musique_ma" in roles.FONCTIONS and "musique_ma" in roles.SENSIBLES
    assert "musique_ma" in roles.MODELES[roles.PARENT]["fonctions"] and "musique_ma" not in roles.MODELES[roles.ENFANT]["fonctions"]
    for lg in LANGUES:
        assert "Music Assistant" in roles.libelle_fonction("musique_ma", lg)


async def test_installation_d_avant_le_decideur_recoit_le_droit_une_fois(hass, hass_storage):
    from custom_components.pc_parental import roles

    coord = await _installer(hass, hass_storage)
    s = coord.store
    s.roles[roles.PARENT]["fonctions"] = [f for f in s.roles[roles.PARENT]["fonctions"] if f != "musique_ma"]
    s.roles_ma = False
    assert roles.fonctions_ma(s) is True
    assert "musique_ma" in s.roles[roles.PARENT]["fonctions"] and "musique_ma" not in s.roles[roles.ENFANT]["fonctions"]
    # Une seule fois : un administrateur qui le retire ensuite n'est pas contredit au démarrage suivant.
    s.roles[roles.PARENT]["fonctions"].remove("musique_ma")
    assert roles.fonctions_ma(s) is False and "musique_ma" not in s.roles[roles.PARENT]["fonctions"]


async def test_les_reponses_a_faire_dans_ma_sont_cliquables(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import musique, musique_ma, textes

    r = musique_ma.reponse_erreur(musique.a_faire_dans_ma(), "fr")
    assert r["ouvrir_ma"] is True and A_FAIRE in r["retour"]
    autre = musique_ma.reponse_erreur(textes.Erreur("commun.message_vide"), "fr")
    assert "ouvrir_ma" not in autre and autre["retour"]
    # De bout en bout : la commande « titres similaires » de la télé.
    _c, _appels, _files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", TELE, action="similaires", actif=True)
    assert s == 400 and A_FAIRE in r["retour"] and r["ouvrir_ma"] is True


def test_textes_serveur_six_langues():
    for lg in LANGUES:
        srv = json.loads((RACINE / "i18n" / "serveur" / f"{lg}.json").read_text(encoding="utf-8"))
        assert "Music Assistant" in srv["roles.f_musique_ma"] and "Music Assistant" in srv["mus.a_faire_dans_ma"]
