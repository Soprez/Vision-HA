"""i18n, lot B : les cartes Lovelace et le tableau de bord généré, en six langues (fr, en, de, es, it, nl).

- Chaque carte (pc_parental/frontend/*.js, pc_parental/panneau/*.js) porte ses textes dans un dictionnaire JSON
  (`/*i18n-json*/ … /*i18n-json:fin*/`) : mêmes clés et mêmes champs `{…}` dans les six langues, aucun texte vide.
- La table des catégories embarquée dans une carte est celle de pc_parental/i18n/categories.json.
- Le bloc de fonctions (tr, trn, trCat, langueDe…) est le même dans toutes les cartes.
- Plus de texte français en dur dans les cartes ni dans dashboard.py (détecteur heuristique, exceptions commentées).
- Le catalogue « tableau » (pc_parental/i18n/tableau/<langue>.json) : mêmes clés et mêmes champs partout.
- Le tableau généré en français est identique à celui d'avant l'i18n (photo : i18n_cartes_tableau_fr.json) ;
  un Home Assistant en allemand donne un tableau allemand ; la langue fait partie de l'empreinte du tableau.
- Avec Node : tr(), trn() (Intl.PluralRules), le repli (langue → anglais → français → clé), trCat().

    python -m pytest essai/i18n_cartes_test.py -q -o asyncio_mode=auto
    (VISION_PHOTO_TABLEAU=1 réécrit la photo du tableau français : seulement quand un texte français change exprès.)
"""
from __future__ import annotations

import ast
import json
import os
import pathlib
import re
import shutil
import subprocess

import pytest

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

ICI = pathlib.Path(__file__).parent
RACINE = ICI.parent / "custom_components" / "pc_parental"
LANGUES = ("fr", "en", "de", "es", "it", "nl")
CARTES = sorted([*(RACINE / "frontend").glob("*.js"), *(RACINE / "panneau").glob("*.js")])
PHOTO = ICI / "i18n_cartes_tableau_fr.json"
CHAMP = re.compile(r"\{(\w+)\}")


# ------------------------------------------------------------------ lecture des cartes

def _bloc(src: str, marque: str):
    debut = src.find(f"/*{marque}*/")
    if debut < 0:
        return None
    fin = src.index(f"/*{marque}:fin*/", debut)
    return json.loads(src[debut + len(marque) + 4:fin])


def _sans_blocs(src: str) -> str:
    """Le source sans ses dictionnaires (les textes traduits y sont à leur place) ; les numéros de ligne restent."""
    for marque in ("i18n-json", "i18n-categories", "i18n-vocabulaire"):
        while (d := src.find(f"/*{marque}*/")) >= 0:
            f = src.index(f"/*{marque}:fin*/", d) + len(marque) + 8
            src = src[:d] + "{}" + "\n" * src.count("\n", d, f) + src[f:]
    return src


def _code_commun(src: str) -> str:
    d = src.index("// ---- i18n:code")
    f = src.index("// ---- i18n:fin", d)
    return src[d:f]


# ------------------------------------------------------------------ lexeur JS (chaînes et gabarits)

def _morceaux(src: str):
    """(ligne, texte) pour chaque chaîne et chaque gabarit (les ${…} remplacés par \\x00), commentaires sautés."""
    n = len(src)
    sortie = []

    def ligne(p):
        return src.count("\n", 0, p) + 1

    def gabarit(i):
        debut, buf = i, []
        while i < n:
            c = src[i]
            if c == "\\":
                buf.append(src[i:i + 2]); i += 2; continue
            if c == "`":
                sortie.append((ligne(debut), "".join(buf))); return i + 1
            if c == "$" and i + 1 < n and src[i + 1] == "{":
                i = code(i + 2, True); buf.append("\x00"); continue
            buf.append(c); i += 1
        return i

    def code(i, dans_expr=False):
        prof, precedent = 0, ""
        while i < n:
            c = src[i]
            if c in " \t\r\n":
                i += 1; continue
            if src.startswith("//", i):
                j = src.find("\n", i); i = n if j < 0 else j; continue
            if src.startswith("/*", i):
                j = src.find("*/", i + 2); i = n if j < 0 else j + 2; continue
            if c in "\"'":
                j = i + 1
                while j < n and src[j] != c:
                    j += 2 if src[j] == "\\" else 1
                sortie.append((ligne(i), src[i + 1:j])); i = j + 1; precedent = "x"; continue
            if c == "`":
                i = gabarit(i + 1); precedent = "x"; continue
            if c == "/" and precedent in ("", "(", ",", "=", ":", "[", "!", "&", "|", "?", "{", "}", ";", "op"):
                j, classe = i + 1, False
                while j < n:
                    if src[j] == "\\":
                        j += 2; continue
                    if src[j] == "[":
                        classe = True
                    elif src[j] == "]":
                        classe = False
                    elif (src[j] == "/" and not classe) or src[j] == "\n":
                        break
                    j += 1
                i = j + 1
                while i < n and src[i].isalpha():
                    i += 1
                precedent = "x"; continue
            if c == "{":
                prof += 1
            elif c == "}":
                if dans_expr and prof == 0:
                    return i + 1
                prof -= 1
            if c.isalnum() or c in "_$":
                m = re.match(r"[\w$]+", src[i:])
                mot = m.group(0)
                precedent = "op" if mot in ("return", "typeof", "case", "in", "of", "else", "do") else "x"
                i += len(mot); continue
            if c in ")]":
                precedent = "x"
            elif c in "+-*%<>=&|^~!?":
                precedent = "op"
            elif c in "([{};,:":
                precedent = c
            i += 1
        return i

    code(0)
    return sortie


MOT = re.compile(r"[A-Za-zÀ-ÿ]{2,}")
DEUX_MOTS = re.compile(r"[A-Za-zÀ-ÿ]{2,}[ '’][A-Za-zÀ-ÿ]{2,}")


def _visibles(s: str):
    """Le texte d'un gabarit HTML : sans <style>, sans balises ; plus les attributs lus (title, placeholder…)."""
    s = re.sub(r"<style[\s\S]*?</style>", " ", s)
    lus = re.findall(r'(?:title|placeholder|aria-label|alt|label)="([^"]*)"', s)
    s = re.sub(r"<[^>]*>", "\x01", s)
    s = re.sub(r"&[a-z]+;|&#\d+;", " ", s)
    return s.split("\x01") + lus


def _css(s: str) -> bool:
    return bool(re.search(r"[{;]\s*$|^\s*[.#:@a-z-]+[^{}]*\{|:\s*(var\(|\d+px|#[0-9a-f]{3})|^\s*[a-z-]+\s*:\s*[^ ]+;",
                          s, re.I | re.M)) and not re.search(r"[éèàù]", s)


def _francais(s: str) -> bool:
    s = s.replace("\x00", " ").strip()
    if not MOT.search(s):
        return False
    if re.fullmatch(r"[A-ZÉÀÂ][a-zà-ÿ]{2,}[.…]?", s):
        return True
    if re.fullmatch(r"[\w.\-/:%#@$\[\]=,|()*+?^\\ ]+", s) and not DEUX_MOTS.search(s):
        return False
    if _css(s) or re.match(r"^(mdi:|https?:|/api/|pc_parental|var\(|M[ \d])", s):
        return False
    # Sélecteurs CSS (« .actions button ») et listes de polices.
    if re.fullmatch(r"[.#][\w-]+(\s+[.#]?[\w-]+)*", s) or re.search(r"\b(sans-serif|monospace|serif)\b", s):
        return False
    return bool(re.search(r"[À-ÿ«»’…]", s) or DEUX_MOTS.search(s))


# Les chaînes qui restent écrites dans le code, et pourquoi.
EXCEPTIONS_JS = {
    "Vision",            # nom propre (glossaire), jamais traduit
    "Home Assistant",    # nom propre
    "Music Assistant",   # nom propre
    "Party",             # nom propre (le mode musique partagé)
    "Android",           # nom propre
    "Roboto",            # nom de police
    "Helvetica Neue",    # nom de police
    "Segoe UI",          # nom de police
    "Arial",             # nom de police
    "use strict",        # directive JavaScript
    "Escape",            # nom de touche du clavier
    "Enter",             # nom de touche du clavier
}


def _texte_en_dur(src: str):
    """Le texte français resté dans le code. Une chaîne dont la ligne porte le commentaire « i18n-ignorer » (avec sa
    raison : valeur de données, diagnostic pour le mainteneur…) est permise."""
    trouves = []
    lignes = src.split("\n")
    for ligne, txt in _morceaux(_sans_blocs(src)):
        if "i18n-ignorer" in lignes[ligne - 1]:
            continue
        for bout in _visibles(txt):
            propre = bout.replace("\x00", "…").strip()
            if _francais(bout) and propre not in EXCEPTIONS_JS:
                trouves.append(f"{ligne}: {propre[:100]!r}")
    return trouves


# ------------------------------------------------------------------ les cartes

def _verifier_dictionnaire(textes: dict, ou: str) -> list[str]:
    fautes = []
    if set(textes) != set(LANGUES):
        fautes.append(f"{ou} : langues {sorted(textes)}")
        return fautes
    reference = textes["fr"]
    for lg in LANGUES:
        cles = set(textes[lg])
        if cles != set(reference):
            fautes.append(f"{ou} [{lg}] : manque {sorted(set(reference) - cles)[:8]}, en trop {sorted(cles - set(reference))[:8]}")
        for cle, texte in textes[lg].items():
            if not isinstance(texte, str) or not texte.strip():
                fautes.append(f"{ou} [{lg}] {cle} : texte vide")
                continue
            if cle in reference and set(CHAMP.findall(texte)) != set(CHAMP.findall(reference[cle])):
                fautes.append(f"{ou} [{lg}] {cle} : champs {sorted(set(CHAMP.findall(texte)))} au lieu de "
                              f"{sorted(set(CHAMP.findall(reference[cle])))}")
        for cle in cles:
            if cle.endswith("_one") and cle[:-4] + "_other" not in cles:
                fautes.append(f"{ou} [{lg}] {cle} : pluriel sans « _other »")
    return fautes


@pytest.mark.parametrize("carte", CARTES, ids=[c.name for c in CARTES])
def test_carte_six_langues(carte):
    src = carte.read_text(encoding="utf-8")
    textes = _bloc(src, "i18n-json")
    if carte.name == "vision-veille-panel.js":  # la porte du panneau n'affiche aucun texte
        assert textes is None and not _texte_en_dur(src)
        return
    assert textes is not None, f"{carte.name} : pas de dictionnaire /*i18n-json*/"
    assert not _verifier_dictionnaire(textes, carte.name), _verifier_dictionnaire(textes, carte.name)


@pytest.mark.parametrize("carte", CARTES, ids=[c.name for c in CARTES])
def test_carte_sans_francais_en_dur(carte):
    trouves = _texte_en_dur(carte.read_text(encoding="utf-8"))
    assert not trouves, f"{carte.name} : texte en dur\n" + "\n".join(trouves)


def test_categories_et_code_commun():
    table = json.loads((RACINE / "i18n" / "categories.json").read_text(encoding="utf-8"))["categories"]
    codes = {}
    for carte in CARTES:
        src = carte.read_text(encoding="utf-8")
        if carte.name == "vision-veille-panel.js":
            continue
        cats = _bloc(src, "i18n-categories")
        assert cats is not None, f"{carte.name} : pas de table /*i18n-categories*/ (même vide)"
        if cats:
            assert cats == table, f"{carte.name} : table des catégories différente de i18n/categories.json"
        codes[carte.name] = _code_commun(src)
    modele = codes["vision-apparence-card.js"]
    differents = [nom for nom, code in codes.items() if code != modele]
    assert not differents, f"code i18n différent de celui de vision-apparence-card.js : {differents}"


# ------------------------------------------------------------------ tr(), trn(), repli : avec Node

def _node(script: str):
    node = shutil.which("node")
    if not node:
        pytest.skip("Node manque.")
    # Par l'entrée standard : un bloc i18n complet dépasse la longueur d'une ligne de commande Windows.
    return json.loads(subprocess.run([node, "-"], input=script, capture_output=True, text=True, encoding="utf-8",
                                     check=True).stdout)


def _bloc_js(carte: str) -> str:
    """Le dictionnaire, la table des catégories et le code commun d'une carte, exécutables seuls."""
    src = (RACINE / "frontend" / carte).read_text(encoding="utf-8")
    d = src.index("// ---- i18n:debut")
    f = src.index("// ---- i18n:fin", d)
    return src[d:f]


def test_tr_trn_repli_avec_node():
    bloc = _bloc_js("vision-apparence-card.js")
    textes = _bloc((RACINE / "frontend" / "vision-apparence-card.js").read_text(encoding="utf-8"), "i18n-json")
    une_cle = next(iter(textes["fr"]))
    script = bloc + """
const r = {};
const essai = (hass) => { LANGUE = langueDe(hass); LOCALE = localeDe(hass); return LANGUE; };
r.de = essai({ locale: { language: "de-CH" }, language: "fr" });
r.en_inconnue = essai({ language: "ja" });
r.fr = essai({ language: "fr" });
r.sans = essai(null);
essai({ language: "de" });
r.cle_inconnue = tr("cle_qui_n_existe_pas");
TEXTES.de["__seulement_fr"] = undefined; TEXTES.fr["__seulement_fr"] = "Seulement {x}";
r.repli_fr = tr("__seulement_fr", { x: "ici" });
TEXTES.en["__repli_en"] = "In English"; TEXTES.fr["__repli_en"] = "En français";
r.repli_en = tr("__repli_en");
TEXTES.fr.essai_one = "{n} appareil"; TEXTES.fr.essai_other = "{n} appareils";
TEXTES.en.essai_one = "{n} device"; TEXTES.en.essai_other = "{n} devices";
essai({ language: "fr" }); r.fr0 = trn("essai", 0); r.fr1 = trn("essai", 1); r.fr2 = trn("essai", 2);
essai({ language: "en" }); r.en0 = trn("essai", 0); r.en1 = trn("essai", 1);
r.cat_de = (essai({ language: "de" }), trCat("Réseaux sociaux"));
r.cat_inconnue = trCat("Chauffage");
r.cle_cat = cleCategorie("Pub & traçage");
essai({ language: "fr" }); r.cle_fr = tr(CLE);
console.log(JSON.stringify(r));
""".replace("CLE", json.dumps(une_cle))
    r = _node(script)
    assert r["de"] == "de" and r["en_inconnue"] == "en" and r["fr"] == "fr"
    assert r["cle_inconnue"] == "cle_qui_n_existe_pas"
    assert r["repli_fr"] == "Seulement ici" and r["repli_en"] == "In English"
    assert (r["fr0"], r["fr1"], r["fr2"]) == ("0 appareil", "1 appareil", "2 appareils")
    assert (r["en0"], r["en1"]) == ("0 devices", "1 device")
    assert r["cle_cat"] == "pub_tracage" and r["cat_inconnue"] == "Chauffage"
    assert r["cle_fr"] == textes["fr"][une_cle]


def test_categorie_carte_avec_node():
    """La carte des étiquettes traduit une catégorie connue, laisse une inconnue telle quelle."""
    bloc = _bloc_js("vision-etiquettes-card.js")
    r = _node(bloc + """
LANGUE = "it"; const a = trCat("Réseaux sociaux"); const b = trCat("jeux"); const c = trCat("Mon étiquette");
LANGUE = "fr"; const d = trCat("Reseaux Sociaux");
console.log(JSON.stringify([a, b, c, d]));""")
    assert r == ["Social network", "Giochi", "Mon étiquette", "Reseaux Sociaux"]  # en français : le nom tel quel


# ------------------------------------------------------------------ vocabulaire : Décideur, Demandeur, Lieu

def _vocabulaire_python():
    """DEFAUTS de pc_parental/vocabulaire.py et le lieu par défaut (lieu.DEFAUTS), lus sans importer Home Assistant."""
    valeurs = {}
    for fichier, nom, cle in (("vocabulaire.py", "DEFAUTS", "DEFAUTS"), ("lieu.py", "DEFAUTS", "LIEU_DEFAUT")):
        for n in ast.parse((RACINE / fichier).read_text(encoding="utf-8")).body:
            if isinstance(n, (ast.Assign, ast.AnnAssign)):
                cible = n.targets[0] if isinstance(n, ast.Assign) else n.target
                if isinstance(cible, ast.Name) and cible.id == nom:
                    valeurs[cle] = ast.literal_eval(n.value)
    return valeurs


def test_vocabulaire_des_cartes_comme_vocabulaire_py():
    py = _vocabulaire_python()
    src = (RACINE / "frontend" / "vision-apparence-card.js").read_text(encoding="utf-8")
    assert _bloc(src, "i18n-vocabulaire") == py["DEFAUTS"]
    assert f"const LIEU_DEFAUT = {json.dumps(py['LIEU_DEFAUT'], ensure_ascii=False)};" in src


def test_vocabulaire_rempli_avec_node():
    r = _node(_bloc_js("vision-apparence-card.js") + """
TEXTES.fr.v = "Un {decideur}, des {demandeurs}, {Lieu} — {Decideurs}";
TEXTES.de = TEXTES.de || {}; TEXTES.de.v = "Ein {decideur} in {lieu}";
const r = {};
LANGUE = "fr"; r.fr = tr("v");
LANGUE = "de"; r.de = tr("v");
poserVocabulaire({ lieu: "École", mots: { de: { decideur: "Lehrer" } } });
r.de_perso = tr("v");
LANGUE = "fr"; r.fr_lieu = tr("v");
r.champ_appelant = tr("v", { decideur: "X" });
console.log(JSON.stringify(r));""")
    assert r["fr"] == "Un décideur, des demandeurs, Lieu — Décideurs"
    assert r["de"] == "Ein Entscheider in Ort"
    assert r["de_perso"] == "Ein Lehrer in École"
    assert r["fr_lieu"] == "Un décideur, des demandeurs, École — Décideurs"
    assert r["champ_appelant"].startswith("Un X,")


# Les mots non neutres, par langue (« maison », « parent », « enfant » et leurs équivalents).
NON_NEUTRES = {
    "fr": r"\b(maisons?|parents?|parentale?s?|enfants?|adultes?|famille|chez nous)\b",
    "en": r"\b(home|house|households?|parents?|parental|child|children|kids?|adults?|family)\b",
    "de": r"\b(Eltern\w*|Kind|Kinder\w*|Zuhause|Haus|Erwachsene\w*|Familie)\b",
    "es": r"\b(casa|hogar|padres?|hijos?|hijas?|niños?|niñas?|adultos?|familia)\b",
    "it": r"\b(casa|genitori|genitore|figli|figlio|figlia|bambini|bambino|adult[oi]|famiglia)\b",
    "nl": r"\b(huis|thuis|ouders?|kind|kinderen|volwassenen?|gezin)\b",
}
# Ce qui reste permis, et pourquoi.
EXCEPTIONS_NEUTRES = (
    "Home Assistant",      # nom propre
    "home screen",         # « accueil » (écran d'accueil d'Android) : glossaire
    "sites pour adultes", "adult sites", "Seiten für Erwachsene", "sitios para adultos", "siti per adulti",
    "sites voor volwassenen",  # contenu pour adultes (classement des sites), pas un rôle
)


def test_vocabulaire_neutre():
    dicos = {c.name: _bloc(c.read_text(encoding="utf-8"), "i18n-json") for c in CARTES}
    dicos["i18n/tableau"] = {lg: json.loads((RACINE / "i18n" / "tableau" / f"{lg}.json").read_text(encoding="utf-8"))
                             for lg in LANGUES}
    fautes = []
    for nom, textes in dicos.items():
        for lg, valeurs in (textes or {}).items():
            for cle, texte in valeurs.items():
                propre = texte
                for e in EXCEPTIONS_NEUTRES:
                    propre = propre.replace(e, "")
                for m in re.finditer(NON_NEUTRES[lg], propre, 0 if lg == "de" else re.I):
                    fautes.append(f"{nom} [{lg}] {cle} : « {m.group(0)} »")
    assert not fautes, "mots non neutres (utiliser {decideur}, {demandeur}, {lieu}…) :\n" + "\n".join(fautes)


# ------------------------------------------------------------------ catalogue du tableau

def test_catalogue_tableau_six_langues():
    dossier = RACINE / "i18n" / "tableau"
    textes = {lg: json.loads((dossier / f"{lg}.json").read_text(encoding="utf-8")) for lg in LANGUES}
    fautes = _verifier_dictionnaire(textes, "i18n/tableau")
    assert not fautes, fautes


# dashboard.py garde, à l'identique de la branche principale (moins de conflits), des fonctions que plus rien
# n'appelle : leurs textes ne s'affichent jamais, le détecteur les saute.
FONCTIONS_MORTES = {"_presence_markdown", "_etat_markdown", "_section_pc", "_section_gestion", "_pagination",
                    "_table_etat", "_vue_plages", "_async_reclamer"}


def _chaines_python(chemin: pathlib.Path):
    arbre = ast.parse(chemin.read_text(encoding="utf-8"))
    ignores = set()
    for n in arbre.body:
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name in FONCTIONS_MORTES:
            for sous in ast.walk(n):
                ignores.add(id(sous))
    for n in ast.walk(arbre):
        if isinstance(n, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            if n.body and isinstance(n.body[0], ast.Expr) and isinstance(n.body[0].value, ast.Constant):
                ignores.add(id(n.body[0].value))
        if isinstance(n, ast.Call):
            cible = ast.unparse(n.func)
            if "LOGGER" in cible:
                for sous in ast.walk(n):
                    ignores.add(id(sous))
    for n in ast.walk(arbre):
        if id(n) in ignores:
            continue
        if isinstance(n, ast.JoinedStr):
            for v in n.values:
                ignores.add(id(v))
            yield n.lineno, "".join(v.value if isinstance(v, ast.Constant) else "{…}" for v in n.values)
        elif isinstance(n, ast.Constant) and isinstance(n.value, str):
            yield n.lineno, n.value


# dashboard.py : ce qui reste écrit dans le code, et pourquoi.
EXCEPTIONS_TABLEAU = {
    "Vision",           # nom du tableau dans le menu (TITRE) : nom propre
    "Vision",           # thème « Vision » (nom propre)
}


def test_tableau_sans_francais_en_dur():
    trouves = []
    for ligne, s in _chaines_python(RACINE / "dashboard.py"):
        if s in EXCEPTIONS_TABLEAU:
            continue
        # Les gabarits Jinja, formats et identifiants ne sont pas du texte lu.
        sans_jinja = re.sub(r"\{[{%#][\s\S]*?[}%#]\}", " ", s)
        # Les morceaux de gabarit coupés par une entité insérée (« {% if is_state(' » … « ', 'on') %} »).
        sans_jinja = re.sub(r"\{[{%#][^}]*$", " ", re.sub(r"^[^{]*?[}%]\}", " ", sans_jinja))
        sans_jinja = re.sub(r"\{…\}", " ", sans_jinja)
        if _francais(sans_jinja):
            trouves.append(f"{ligne}: {s[:100]!r}")
    assert not trouves, "dashboard.py : texte en dur\n" + "\n".join(trouves)


# ------------------------------------------------------------------ le tableau généré

async def _tableau(hass, hass_storage, langue: str):
    from custom_components.pc_parental import dashboard, modules

    hass.config.language = langue
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "roles_personnes": {"person.nolan": "parent"},
        "modules": {m: True for m in modules.TOUS}, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant",
                         type="telephone", rules=[{"id": 1, "name": "Soir", "start": 72000, "end": 25200,
                                                   "weekdays": [True] * 7, "enabled": True}]),
            "e2": _fiche("e2", "PC-NOA", ENFANT2["secret"], person="person.noa"),
            "t1": _fiche("t1", "Télé du salon", "t" * 32, platform="android", type="tv", partage=["person.mia"]),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        }}}
    for p in ("lou", "noa", "nolan", "mia"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = next(iter(hass.data[DOMAIN].values()))
    return dashboard, coord, dashboard.build(hass, coord)


# Les entity_id du banc dépendent des noms d'entités (traduits par un autre lot) : on compare les textes, pas eux.
ENTITE = re.compile(r"\b(binary_sensor|button|sensor|switch|select|number|text|update|notify|time|todo)\.[a-z0-9_]+")


async def test_tableau_francais_inchange(hass, hass_storage):
    _, _, config = await _tableau(hass, hass_storage, "fr")
    texte = ENTITE.sub(r"\1.x", json.dumps(config, ensure_ascii=False, indent=1, sort_keys=True))
    if os.environ.get("VISION_PHOTO_TABLEAU") or not PHOTO.exists():
        PHOTO.write_text(texte, encoding="utf-8")
        pytest.skip("photo du tableau français écrite")
    attendu = PHOTO.read_text(encoding="utf-8")
    if texte != attendu:
        import difflib

        ecarts = list(difflib.unified_diff(attendu.splitlines(), texte.splitlines(), "avant", "apres", lineterm="", n=1))
        pytest.fail("Le tableau français a changé :\n" + "\n".join(ecarts[:80]))


async def test_tableau_allemand(hass, hass_storage):
    from custom_components.pc_parental import langues

    dashboard, coord, config = await _tableau(hass, hass_storage, "de")
    tableau = langues.Catalogue("tableau")
    texte = json.dumps(config, ensure_ascii=False)
    # Des titres d'onglets allemands, et pas les français.
    titres = {v.get("path"): v.get("title") for v in config["views"]}
    assert titres["appareils"] == tableau.t("onglet_appareils", "de") != tableau.t("onglet_appareils", "fr")
    assert titres["protection"] == tableau.t("onglet_protection", "de")
    assert "Sur les appareils" not in texte and tableau.t("protection_sur_appareils", "de") in texte
    # La langue fait partie de l'empreinte : changer la langue de Home Assistant refait le tableau.
    avant = dashboard.signature(coord)
    hass.config.language = "fr"
    assert dashboard.signature(coord) != avant
