"""Réveil (lot 1) : traductions fr / en complètes et vocabulaire neutre.

Chaque service reveil_* de services.yaml a son nom, sa description et chacun de ses champs en fr et en en ;
chaque clé d'erreur (ReveilInvalide) a son message ; les entités ont leur nom ; strings.json suit fr.json ;
aucun mot interdit (famille, maison, parent, enfant…) dans ce qu'ajoute le module.

    python -m pytest essai/reveil_traductions_test.py -q
"""
from __future__ import annotations

import json
import pathlib
import re

import yaml

RACINE = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental"
LANGUES = ("fr", "en")
INTERDITS = re.compile(r"famil|maison|parent|enfant|\bkids?\b|\bchild|\bhome\b(?! assistant)", re.I)


def _trad(langue: str) -> dict:
    return json.loads((RACINE / "translations" / f"{langue}.json").read_text(encoding="utf-8"))


def _services() -> dict:
    tout = yaml.safe_load((RACINE / "services.yaml").read_text(encoding="utf-8"))
    return {k: v for k, v in tout.items() if k.startswith("reveil_")}


def test_services_traduits():
    services = _services()
    assert len(services) == 13
    for langue in LANGUES:
        t = _trad(langue)["services"]
        for nom, s in services.items():
            assert t[nom]["name"] and t[nom]["description"], (langue, nom)
            assert set(t[nom].get("fields", {})) == set((s.get("fields") or {})), (langue, nom)
            for champ, v in t[nom].get("fields", {}).items():
                assert v["name"] and v["description"], (langue, nom, champ)
    assert _trad("en")["services"]["reveil_creer"]["name"] != _trad("fr")["services"]["reveil_creer"]["name"]


def test_services_declares_dans_le_code():
    code = (RACINE / "reveil_services.py").read_text(encoding="utf-8")
    declares = set(re.findall(r'@_service\("(reveil_\w+)"', code))
    assert declares == set(_services())


def test_erreurs_et_entites_traduites():
    code = "".join((RACINE / f).read_text(encoding="utf-8") for f in
                   ("reveil.py", "reveil_calcul.py", "reveil_services.py", "reveil_http.py"))
    cles = set(re.findall(r'ReveilInvalide\(\s*"(\w+)"', code))
    assert {"heure", "jours", "introuvable", "appareil", "demandeur"} <= cles
    for langue in LANGUES:
        t = _trad(langue)
        for cle in cles:
            assert t["exceptions"][f"reveil_{cle}"]["message"], (langue, cle)
        for cle in ("reveil_prochain", "reveil_etat", "reveil_source", "reveil_appareils"):
            assert t["entity"]["sensor"][cle]["name"], (langue, cle)
        assert set(t["entity"]["sensor"]["reveil_etat"]["state"]) == {
            "inactif", "arme", "sonne", "reporte", "arrete", "echec", "non_synchronise"}
        assert t["entity"]["binary_sensor"]["fenetre_reveil"]["name"]
        assert set(t["selector"]["reveil_type"]["options"]) == {"hebdo", "ponctuel", "entite", "modele", "manuel"}
        assert set(t["selector"]["reveil_source"]["options"]) == {"sonnerie", "url", "ma", "video"}


def test_strings_suit_en():
    # i18n : strings.json est la copie conforme de translations/en.json (langue de repli).
    s = json.loads((RACINE / "strings.json").read_text(encoding="utf-8"))
    fr = _trad("en")
    for partie in ("services", "exceptions", "entity", "selector"):
        for cle, valeur in fr[partie].items():
            if cle.startswith(("reveil_", "fenetre_reveil")) or partie == "entity":
                assert s[partie][cle] == valeur, (partie, cle)


def test_vocabulaire_neutre():
    textes = []
    for langue in LANGUES:
        t = _trad(langue)
        textes += [json.dumps(v, ensure_ascii=False) for k, v in t["services"].items() if k.startswith("reveil_")]
        textes += [json.dumps(v, ensure_ascii=False) for k, v in t["exceptions"].items() if k.startswith("reveil_")]
        textes += [json.dumps(t["entity"], ensure_ascii=False)]
    # Le domaine technique (pc_parental.reveil_*, filtre d'intégration) n'est pas un texte affiché.
    textes.append((RACINE / "blueprints" / "automation" / "reveil_jours_ecole.yaml").read_text(encoding="utf-8")
                  .replace("pc_parental", ""))
    for f in ("reveil.py", "reveil_calcul.py", "reveil_services.py", "reveil_http.py", "reveil_entites.py"):
        # Identifiants techniques existants (domaine, classes, Path.parent, store.personnes_maison) : pas des textes.
        textes.append(re.sub(r"pc_parental|PcParental|PARENT|ENFANT|\.parent\b|parents=True|personnes_maison", "",
                             (RACINE / f).read_text(encoding="utf-8")))
    for texte in textes:
        assert not INTERDITS.search(texte), INTERDITS.search(texte).group(0) + " : " + texte[:120]
