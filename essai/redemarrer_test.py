"""outils/redemarrer.py : jamais de redémarrage pendant la fenêtre des réveils (la plage fixe 6 h 30 – 8 h est supprimée, l'auteur 17/10).

    python -m pytest essai/redemarrer_test.py -q
"""
from __future__ import annotations

import importlib.util
import pathlib

_chemin = pathlib.Path(__file__).resolve().parent.parent / "outils" / "redemarrer.py"
_spec = importlib.util.spec_from_file_location("redemarrer", _chemin)
r = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(r)


def test_plus_de_plage_fixe_le_matin():
    # l'auteur, 17/10 : la plage 6 h 30 – 8 h est retirée ; le matin, seule la fenêtre des réveils refuse.
    for hhmm in ("06:29", "06:30", "07:00", "07:59", "08:00"):
        assert r.decider(hhmm, "off")[0] is True, hhmm
        assert r.decider(hhmm, None)[0] is True, hhmm
    assert r.decider("07:00", "on")[0] is False


def test_fenetre_des_reveils():
    assert r.decider("05:45", "on") == (False, "REFUS : fenêtre des réveils en cours (binary_sensor.vision_fenetre_reveil).")
    assert r.decider("14:00", "off")[0] is True
    assert r.decider("14:00", None)[0] is True          # module jamais activé : rien à protéger
    for illisible in ("unknown", "unavailable", ""):
        assert r.decider("14:00", illisible)[0] is False  # dans le doute, non


def test_plage_de_nuit_en_plus():
    nuit = [("23:00", "08:00")]
    assert r.decider("23:30", "off", nuit)[0] is False
    assert r.decider("02:00", "off", nuit)[0] is False
    assert r.decider("22:59", "off", nuit)[0] is True
    assert r.dans_plage("23:00", "23:00", "08:00") and not r.dans_plage("08:00", "23:00", "08:00")
