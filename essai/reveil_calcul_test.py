"""Réveil (lot 1) : le calcul pur des occurrences (reveil_calcul.py), sans Home Assistant.

Hebdo, ponctuel, calculé (entité / modèle) avec décalage, bornes et repli, dates sautées, horizon, changement
d'heure (heure inexistante, heure double), fenêtre de silence, normalisation et refus.

    python -m pytest essai/reveil_calcul_test.py -q
"""
from __future__ import annotations

import importlib.util
import pathlib
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

_chemin = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental" / "reveil_calcul.py"
_spec = importlib.util.spec_from_file_location("reveil_calcul", _chemin)
c = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(c)

PARIS = ZoneInfo("Europe/Paris")


def _r(**plus):
    r = c.normaliser({"demandeur": "person.alex", **plus})
    r["id"] = "rv1"
    return r


def _t(texte: str) -> datetime:
    return datetime.fromisoformat(texte).replace(tzinfo=PARIS)


# ----------------------------------------------------------------------------- Lecture des champs


def test_lecteurs_de_champs():
    assert c.lire_heure("7:05") == "07:05" and c.lire_heure("07:05:00") == "07:05" and c.lire_heure("7h") == "07:00"
    assert c.lire_heure("6h45") == "06:45"
    for faux in ("25:00", "7:60", "", "midi", None):
        with pytest.raises(c.ReveilInvalide):
            c.lire_heure(faux)
    assert c.lire_jours(["lun", "mer", "Vendredi"]) == [0, 2, 4]
    assert c.lire_jours("mon,tue") == [0, 1] and c.lire_jours([6, 0, 6]) == [0, 6]
    assert c.lire_jours([True, False, True, False, False, False, False]) == [0, 2]
    for faux in ([], ["lundy"], [7], [True]):
        with pytest.raises(c.ReveilInvalide):
            c.lire_jours(faux)
    assert c.lire_date("2026-10-12T07:00") == "2026-10-12"
    with pytest.raises(c.ReveilInvalide) as err:
        c.lire_date("12/10/2026")
    assert err.value.cle == "date"


def test_normaliser_defaut_et_refus():
    r = c.normaliser({"demandeur": "person.alex"})
    assert r["declenchement"]["type"] == "hebdo" and r["declenchement"]["heure"] == "07:00"
    assert r["declenchement"]["jours"] == [0, 1, 2, 3, 4] and r["source"]["type"] == "sonnerie" and r["actif"]
    assert r["volume"] == {"debut": 10, "fin": 60, "montee_s": 180} and r["repetition"] == {"minutes": 9, "max": 3}
    assert r["declenchement"]["bornes"] == ["05:00", "10:00"] and r["declenchement"]["repli"] is None
    refus = [
        ({"demandeur": "alex"}, "demandeur"),
        ({"demandeur": "person.j", "declenchement": {"type": "tous_les_jours"}}, "type"),
        ({"demandeur": "person.j", "declenchement": {"type": "ponctuel"}}, "date"),
        ({"demandeur": "person.j", "declenchement": {"type": "entite"}}, "entite"),
        ({"demandeur": "person.j", "declenchement": {"type": "modele", "modele": "  "}}, "modele"),
        ({"demandeur": "person.j", "declenchement": {"bornes": ["09:00", "06:00"]}}, "bornes"),
        ({"demandeur": "person.j", "declenchement": {"decalage": 2000}}, "nombre"),
        ({"demandeur": "person.j", "source": {"type": "url", "uri": "ftp://x"}}, "source_uri"),
        ({"demandeur": "person.j", "source": {"type": "ma"}}, "source_uri"),
        ({"demandeur": "person.j", "source": {"type": "ma", "uri": "x", "lecteur": "light.x"}}, "source_lecteur"),
        ({"demandeur": "person.j", "source": "cloche"}, "source"),
        ({"demandeur": "person.j", "volume": {"fin": 120}}, "nombre"),
        ({"demandeur": "person.j", "repetition": {"minutes": 0}}, "nombre"),
    ]
    for brut, cle in refus:
        with pytest.raises(c.ReveilInvalide) as err:
            c.normaliser(brut)
        assert err.value.cle == cle, brut


def test_modification_partielle_garde_le_reste():
    r = _r(nom="Collège", declenchement={"heure": "06:50", "jours": ["lun", "mar"]}, source={"type": "url", "uri": "https://radio/x"})
    r["calcul"] = {"heure": "x"}
    m = c.normaliser({"volume": {"fin": 80}}, r)
    assert m["nom"] == "Collège" and m["declenchement"]["heure"] == "06:50" and m["declenchement"]["jours"] == [0, 1]
    assert m["source"]["uri"] == "https://radio/x" and m["volume"] == {"debut": 10, "fin": 80, "montee_s": 180}
    assert m["calcul"] == {"heure": "x"}  # champs internes gardés
    m = c.normaliser({"declenchement": {"repli": "07:30", "bornes": None}}, r)
    assert m["declenchement"]["repli"] == "07:30" and m["declenchement"]["bornes"] is None
    m = c.normaliser({"source": "sonnerie"}, r)
    assert m["source"] == {"type": "sonnerie", "uri": "", "lecteur": "", "libelle": ""}
    assert c.normaliser({"nom": ""}, r)["nom"] == c.NOM_DEFAUT


# ----------------------------------------------------------------------------- Occurrences


def test_hebdo_sur_48h_et_sauter():
    r = _r(declenchement={"heure": "07:00", "jours": ["lun", "mar", "mer", "jeu", "ven"]})
    # Dimanche 11/10/2026 à 21:00 : lundi 12 et mardi 13 dans les 48 h, pas mercredi.
    occ = c.occurrences(r, _t("2026-10-11T21:00"))
    assert [o["id"] for o in occ] == ["rv1@2026-10-12", "rv1@2026-10-13"]
    assert occ[0]["heure"] == "2026-10-12T07:00:00+02:00" and occ[0]["heure_locale"] == "07:00" and occ[0]["raison"] == "hebdo"
    # Juste avant / juste après l'heure.
    assert c.occurrences(r, _t("2026-10-12T06:59"))[0]["date"] == "2026-10-12"
    assert c.occurrences(r, _t("2026-10-12T07:00"))[0]["date"] == "2026-10-13"
    r["sauter"] = ["2026-10-12"]
    assert [o["date"] for o in c.occurrences(r, _t("2026-10-11T21:00"))] == ["2026-10-13"]
    toutes = c.occurrences(r, _t("2026-10-11T21:00"), inclure_sautees=True)
    assert toutes[0]["sautee"] and toutes[0]["raison"] == "sautee"
    assert c.prochaine(r, _t("2026-10-11T21:00"))["date"] == "2026-10-13"
    assert c.prochaine_date(r, _t("2026-10-11T21:00")) == "2026-10-13"
    # Vendredi soir : le prochain est lundi (hors 48 h pour le relevé, mais `prochaine` voit la semaine).
    assert c.occurrences(r, _t("2026-10-16T21:00")) == []
    assert c.prochaine(r, _t("2026-10-16T21:00"))["date"] == "2026-10-19"
    r["actif"] = False
    assert c.occurrences(r, _t("2026-10-11T21:00")) == [] and c.prochaine(r, _t("2026-10-11T21:00")) is None


def test_ponctuel_et_manuel():
    r = _r(declenchement={"type": "ponctuel", "date": "2026-10-13", "heure": "06:40"})
    assert [o["heure"] for o in c.occurrences(r, _t("2026-10-12T20:00"))] == ["2026-10-13T06:40:00+02:00"]
    assert c.occurrences(r, _t("2026-10-13T07:00")) == []
    assert c.occurrences(r, _t("2026-10-10T20:00")) == []  # au-delà de 48 h : pas encore envoyé
    assert c.prochaine(r, _t("2026-10-10T20:00"))["date"] == "2026-10-13"
    assert c.occurrences(_r(declenchement={"type": "manuel"}), _t("2026-10-12T20:00")) == []


def test_changement_d_heure():
    # Passage à l'heure d'été (29/03/2026, 02:00 → 03:00) : 02:30 n'existe pas → 03:30, heure d'été.
    dt = c.normaliser_local(datetime(2026, 3, 29).date(), "02:30", PARIS)
    assert dt.isoformat() == "2026-03-29T03:30:00+02:00"
    # Passage à l'heure d'hiver (25/10/2026, 03:00 → 02:00) : 02:30 existe deux fois → la première (+02:00).
    dt = c.normaliser_local(datetime(2026, 10, 25).date(), "02:30", PARIS)
    assert dt.isoformat() == "2026-10-25T02:30:00+02:00"
    # Un réveil 07:00 reste à 07:00 locale de part et d'autre du changement.
    r = _r(declenchement={"heure": "07:00", "jours": ["sam", "dim", "lun"]})
    occ = c.occurrences(r, _t("2026-10-24T08:00"))
    assert [o["heure"] for o in occ] == ["2026-10-25T07:00:00+01:00", "2026-10-26T07:00:00+01:00"]


def test_calcul_decalage_bornes_repli():
    d = _r(declenchement={"type": "entite", "entite": "sensor.x", "decalage": -10, "bornes": ["05:00", "10:00"]})["declenchement"]
    m = _t("2026-10-12T20:00")
    assert c.appliquer_calcul(d, _t("2026-10-13T07:05"), m) == {
        "heure": "2026-10-13T06:55:00+02:00", "raison": "calculee", "valeur": "2026-10-13T07:05:00+02:00"}
    # Hors bornes, sans repli : invalide.
    assert c.appliquer_calcul(d, _t("2026-10-13T11:00"), m)["raison"] == "invalide"
    assert c.appliquer_calcul(d, None, m) == {"heure": "", "raison": "invalide", "valeur": ""}
    # Déjà passée : rien, sans échec.
    assert c.appliquer_calcul(d, _t("2026-10-12T07:05"), m)["raison"] == "passee"
    # Avec repli : le jour de la valeur, sinon la prochaine fois.
    d["repli"] = "07:30"
    assert c.appliquer_calcul(d, _t("2026-10-13T11:00"), m) == {
        "heure": "2026-10-13T07:30:00+02:00", "raison": "repli", "valeur": "2026-10-13T11:00:00+02:00"}
    assert c.appliquer_calcul(d, None, m)["heure"] == "2026-10-13T07:30:00+02:00"
    assert c.appliquer_calcul(d, None, _t("2026-10-13T06:00"))["heure"] == "2026-10-13T07:30:00+02:00"
    # Sans bornes, une heure tardive est acceptée.
    d["bornes"] = None
    assert c.appliquer_calcul(d, _t("2026-10-13T11:00"), m)["raison"] == "calculee"


def test_occurrence_calculee_vient_du_calcul_fige():
    r = _r(declenchement={"type": "modele", "modele": "{{ x }}"})
    m = _t("2026-10-12T20:00")
    assert c.occurrences(r, m) == []
    r["calcul"] = {"heure": "2026-10-13T06:55:00+02:00", "raison": "calculee"}
    assert [(o["heure"], o["raison"]) for o in c.occurrences(r, m)] == [("2026-10-13T06:55:00+02:00", "calculee")]
    r["sauter"] = ["2026-10-13"]
    assert c.occurrences(r, m) == []


def test_lire_horodatage():
    m = _t("2026-10-12T20:00")
    assert c.lire_horodatage("2026-10-13T04:55:00+00:00", m).isoformat() == "2026-10-13T06:55:00+02:00"
    assert c.lire_horodatage("2026-10-13 06:55:00", m).isoformat() == "2026-10-13T06:55:00+02:00"
    assert c.lire_horodatage("06:55:00", m).isoformat() == "2026-10-13T06:55:00+02:00"  # heure seule : demain
    assert c.lire_horodatage("21:00", m).isoformat() == "2026-10-12T21:00:00+02:00"    # encore aujourd'hui
    for rien in ("unknown", "unavailable", "", None, "2026-10-13", "bientôt"):
        assert c.lire_horodatage(rien, m) is None


def test_fenetre_de_silence():
    r1 = _r(declenchement={"heure": "07:00", "jours": ["lun"]})
    r2 = c.normaliser({"demandeur": "person.sam", "declenchement": {"heure": "07:30", "jours": ["lun"]}})
    r2["id"] = "rv2"
    f = c.fenetre([r1, r2], _t("2026-10-12T05:00"))
    assert f == {"actif": False, "debut": "2026-10-12T06:00:00+02:00", "fin": "2026-10-12T08:00:00+02:00"}
    assert c.fenetre([r1, r2], _t("2026-10-12T06:10"))["actif"] is True
    assert c.fenetre([r1, r2], _t("2026-10-12T07:45"))["actif"] is True   # 30 min après la dernière
    assert c.fenetre([r1, r2], _t("2026-10-12T08:01"))["actif"] is False
    r2["actif"] = False
    assert c.fenetre([r1, r2], _t("2026-10-12T06:10"))["fin"] == "2026-10-12T07:30:00+02:00"
    assert c.fenetre([], _t("2026-10-12T06:10")) == {"actif": False, "debut": "", "fin": ""}


def test_signature_stable():
    assert c.signature({"a": 1, "b": [1, 2]}) == c.signature({"b": [1, 2], "a": 1})
    assert c.signature({"a": 1}) != c.signature({"a": 2}) and len(c.signature({})) == 12
    assert c.nouvel_id().startswith("rv") and c.nouvel_id() != c.nouvel_id()


def test_horizon():
    r = _r(declenchement={"heure": "07:00", "jours": [0, 1, 2, 3, 4, 5, 6]})
    m = _t("2026-10-12T06:00")
    assert len(c.occurrences(r, m)) == 2 and len(c.occurrences(r, m, 72)) == 3
    assert all(datetime.fromisoformat(o["heure"]) - m <= timedelta(hours=48) for o in c.occurrences(r, m))
