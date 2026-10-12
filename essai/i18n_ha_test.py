"""Lot A1 de l'internationalisation : l'intégration Home Assistant (assistant, entités, services).

- les six fichiers translations/<langue>.json ont les mêmes clés et les mêmes champs `{…}` ; strings.json est
  la copie conforme de en.json ;
- chaque service et chaque champ de services.yaml a un nom et une description dans les six langues, chaque
  liste d'options à clé de traduction a toutes ses options ;
- les catalogues pc_parental/i18n/config et pc_parental/i18n/entites : mêmes clés, mêmes champs ;
- chaque clé employée dans le code (noms d'entités, erreurs, textes) existe ;
- plus de français en dur dans les fichiers du lot (hors journaux, commentaires, docstrings, exceptions listées) ;
- les entity_id : une installation neuve en français obtient exactement les entity_id et les noms affichés
  d'avant l'internationalisation (référence relevée sur le code d'avant, i18n_ha_reference_fr.json) ; en
  anglais, les noms sont anglais ; passer Home Assistant d'une langue à l'autre ne change aucun entity_id.

    python -m pytest essai/i18n_ha_test.py -q -o asyncio_mode=auto

`I18N_HA_REFERENCE=ecrire` réécrit la référence (à lancer sur le code d'avant, pas après).
"""
from __future__ import annotations

import ast
import json
import os
import pathlib
import re

import yaml
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import label_registry as lr
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

ICI = pathlib.Path(__file__).resolve().parent
RACINE = ICI.parent / "custom_components" / "pc_parental"
LANGUES = ("fr", "en", "de", "es", "it", "nl")
REFERENCE = ICI / "i18n_ha_reference_fr.json"
FICHIERS_LOT = ("config_flow", "entity", "sensor", "binary_sensor", "switch", "select", "text", "time",
                "number", "button", "update", "notify")
PLATEFORMES = ("sensor", "binary_sensor", "switch", "select", "text", "time", "number", "button", "update", "notify")
CHAMP = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _json(chemin: pathlib.Path) -> dict:
    return json.loads(chemin.read_text(encoding="utf-8"))


def _a_plat(d: dict, prefixe: str = "") -> dict[str, str]:
    sortie: dict[str, str] = {}
    for k, v in d.items():
        cle = f"{prefixe}{k}"
        if isinstance(v, dict):
            sortie.update(_a_plat(v, cle + "."))
        else:
            sortie[cle] = v
    return sortie


def _champs(texte: str) -> set[str]:
    return set(CHAMP.findall(str(texte)))


def _traductions() -> dict[str, dict]:
    return {lg: _json(RACINE / "translations" / f"{lg}.json") for lg in LANGUES}


# ------------------------------------------------------------------ parité des traductions Home Assistant


def test_traductions_six_langues_memes_cles_memes_champs():
    tr = {lg: _a_plat(d) for lg, d in _traductions().items()}
    ref = tr["fr"]
    for lg in LANGUES:
        manque = sorted(set(ref) - set(tr[lg]))
        trop = sorted(set(tr[lg]) - set(ref))
        assert not manque and not trop, (lg, manque[:20], trop[:20])
        for cle, texte in ref.items():
            assert str(tr[lg][cle]).strip(), (lg, cle, "vide")
            assert _champs(tr[lg][cle]) == _champs(texte), (lg, cle, tr[lg][cle])


def test_strings_json_copie_de_en():
    assert _json(RACINE / "strings.json") == _json(RACINE / "translations" / "en.json")


def test_en_est_vraiment_en_anglais():
    """en.json était en grande partie en français : plus aucun mot accentué typiquement français."""
    accents = re.compile(r"[éèêàùçôîû]")
    permis = {"Réseaux sociaux"}  # aucune pour l'instant ; la liste reste là pour une exception justifiée
    for cle, texte in _a_plat(_json(RACINE / "translations" / "en.json")).items():
        propre = texte
        for p in permis:
            propre = propre.replace(p, "")
        assert not accents.search(propre), (cle, texte)


def test_services_et_champs_traduits():
    services = yaml.safe_load((RACINE / "services.yaml").read_text(encoding="utf-8"))
    tr = _traductions()
    for lg in LANGUES:
        section = tr[lg]["services"]
        assert set(section) == set(services), (lg, set(services) ^ set(section))
        for svc, desc in services.items():
            assert section[svc].get("name") and section[svc].get("description"), (lg, svc)
            champs = (desc or {}).get("fields") or {}
            assert set(section[svc].get("fields") or {}) == set(champs), (lg, svc)
            for champ, fdesc in champs.items():
                t = section[svc]["fields"][champ]
                assert t.get("name") and t.get("description"), (lg, svc, champ)
                select = ((fdesc or {}).get("selector") or {}).get("select") or {}
                if select.get("translation_key"):
                    options = tr[lg]["selector"][select["translation_key"]]["options"]
                    assert set(options) == set(select["options"]), (lg, svc, champ)
            # Les textes sont dans les traductions, plus dans services.yaml.
            assert "name" not in (desc or {}) and "description" not in (desc or {}), svc


def test_selecteurs_de_l_assistant_traduits():
    source = (RACINE / "config_flow.py").read_text(encoding="utf-8")
    cles = set(re.findall(r'translation_key="([a-z_]+)"', source))
    assert cles
    for lg, d in _traductions().items():
        assert cles <= set(d["selector"]), (lg, cles - set(d["selector"]))


# ------------------------------------------------------------------ catalogues config et entites


def _catalogue(nom: str) -> dict[str, dict[str, str]]:
    return {lg: _json(RACINE / "i18n" / nom / f"{lg}.json") for lg in LANGUES}


def test_catalogues_config_et_entites():
    for nom in ("config", "entites"):
        cat = _catalogue(nom)
        ref = cat["fr"]
        assert ref, nom
        for lg in LANGUES:
            assert set(cat[lg]) == set(ref), (nom, lg, sorted(set(cat[lg]) ^ set(ref)))
            for cle, texte in ref.items():
                assert cat[lg][cle].strip(), (nom, lg, cle)
                assert _champs(cat[lg][cle]) == _champs(texte), (nom, lg, cle, cat[lg][cle])
            # Les pluriels vont par paires.
            for cle in cat[lg]:
                if cle.endswith("_one"):
                    assert cle[:-4] + "_other" in cat[lg], (nom, lg, cle)


def _source(nom: str) -> str:
    return (RACINE / f"{nom}.py").read_text(encoding="utf-8")


def test_cles_employees_existent():
    fr = _traductions()["fr"]
    entites = _catalogue("entites")["fr"]
    config = _catalogue("config")["fr"]
    # Noms d'entités : chaque translation_key d'une plateforme existe dans sa section.
    for plateforme in PLATEFORMES:
        src = _source(plateforme)
        cles = set(re.findall(r'_attr_translation_key = "([a-z0-9_]+)"', src))
        cles |= set(re.findall(r'nom_traduit\([^,]+, "' + plateforme + r'", "([a-z0-9_]+)"', src))
        for cle in cles:
            assert cle in fr["entity"].get(plateforme, {}), (plateforme, cle)
    # Clés construites : saisie_<clé>, liste_<champ>, plage_debut / plage_fin.
    for cle in re.findall(r'SaisieText\(coordinator, "([a-z_]+)"', _source("text")):
        assert f"saisie_{cle}" in fr["entity"]["text"], cle
    for champ in ("bloques", "autorises"):
        assert f"liste_{champ}" in fr["entity"]["text"]
    # Erreurs montrées à l'utilisateur.
    for nom in FICHIERS_LOT:
        for cle in re.findall(r'erreur\(\s*"([a-z_]+)"', _source(nom)) + re.findall(r'"([a-z_]+)" if [^)]*else "([a-z_]+)"\)', ""):
            assert cle in fr["exceptions"], (nom, cle)
    for cle in ("heure_debut_manquante", "heure_fin_manquante", "premiere_page", "derniere_page"):
        assert cle in fr["exceptions"], cle
    # Textes des entités.
    for nom in FICHIERS_LOT:
        for cle in re.findall(r'texte\(\s*(?:self\.coordinator\.hass|coordinator\.hass|hass),\s*"([a-z_]+)"', _source(nom)):
            assert cle in entites, (nom, cle)
    for c in ("logiciel", "etiquettes", "signe_par", "temps", "site", "ouvert_par", "frequentation", "releves"):
        assert f"colonne_{c}" in entites, c
    for etat in ("actif", "inactif", "verrouille", "hors_ligne"):
        assert f"presence_{etat}" in entites
    # Textes de l'assistant.
    src = _source("config_flow")
    for cle in re.findall(r'_t\(\s*"([a-z_]+)"', src):
        assert cle in config, cle
    for cle in re.findall(r'_n\(\s*"([a-z_]+)"', src):
        assert f"{cle}_one" in config and f"{cle}_other" in config, cle
    for genre in ("personnes", "appareils"):
        assert {f"compte_{genre}_zero", f"compte_{genre}_one", f"compte_{genre}_other"} <= set(config)
    # Les placeholders que l'assistant fournit existent dans les textes des écrans.
    etapes = fr["options"]["step"]
    assert "{demandes}" in etapes["deverrouillage"]["description"]
    assert "{concernes}" in etapes["modules_protections"]["description"]
    assert "{pilotables}" in etapes["entites_actions"]["description"]


# ------------------------------------------------------------------ vocabulaire neutre

# Vision ne dit jamais « famille », « maison », « parent », « enfant » (docs/i18n/vocabulaire.md) : il dit le rôle
# ({decideur}, {demandeur}…) et le nom du lieu ({lieu}), remplis par vocabulaire.py.
MOTS_INTERDITS = {
    "fr": r"\b(familles?|maisons?|parents?|parental|enfants?)\b",
    "en": r"\b(family|families|home|homes|house|household|parents?|parental|child|children|kids?)\b",
    "de": r"\b(famili\w*|haus|zuhause|eltern\w*|kind|kinder\w*|kindes)\b",
    "es": r"\b(familias?|casa|hogar|padres?|madres?|hij[oa]s?|niñ[oa]s?)\b",
    "it": r"\b(famiglie?|casa|genitor[ei]|figli[oa]?|bambin[ioa])\b",
    "nl": r"\b(gezin\w*|famil\w*|huis|thuis|ouders?|kind|kinderen)\b",
}
MOTS_PERMIS = (
    "Home Assistant",  # nom propre
    "home screen",  # glossaire : « accueil » (le lanceur Vision), sans rapport avec un foyer
    "pc_parental",  # identifiant technique (nom de l'intégration, événements, services)
)


def test_vocabulaire_neutre():
    trouve = []
    for lg in LANGUES:
        motif = re.compile(MOTS_INTERDITS[lg], re.I)
        sources = {"translations": _json(RACINE / "translations" / f"{lg}.json"),
                   "config": _json(RACINE / "i18n" / "config" / f"{lg}.json"),
                   "entites": _json(RACINE / "i18n" / "entites" / f"{lg}.json")}
        for nom, d in sources.items():
            for cle, texte in _a_plat(d).items():
                propre = str(texte)
                for permis in MOTS_PERMIS:
                    propre = re.sub(re.escape(permis), "", propre, flags=re.I)
                if m := motif.search(propre):
                    trouve.append((lg, nom, cle, m.group(0)))
    assert not trouve, trouve


def test_vocabulaire_rempli_dans_les_catalogues():
    """Un texte de catalogue qui porte un champ du vocabulaire le reçoit tout seul."""
    from custom_components.pc_parental import vocabulaire  # noqa: PLC0415  (la copie du banc)

    assert vocabulaire.remplir("{Decideurs} · {demandeur} · {lieu}", "fr") == "Décideurs · demandeur · Lieu"
    for nom in ("config", "entites"):
        for lg in LANGUES:
            for cle, texte in _catalogue(nom)[lg].items():
                champs = _champs(texte) & set(vocabulaire.CHAMPS) | {c for c in _champs(texte) if c[:1].isupper()}
                rendu = vocabulaire.remplir(texte, lg)
                for c in champs:
                    assert "{" + c + "}" not in rendu, (nom, lg, cle)


# ------------------------------------------------------------------ français en dur

# Chaînes permises, et pourquoi.
PERMIS = {
    "Home Assistant",  # nom propre (glossaire)
    "fermé", "ouvert",  # valeur de l'attribut « planning », lue par le tableau de bord (comparée à 'fermé')
    "sur cet appareil",  # marqueur posé par le magasin dans ses raisons (lot A2), pas un texte affiché
}
APPELS_JOURNAL = re.compile(r"^_?(LOGGER|_LOGGER|logging|log)$")


def _francais(texte: str) -> bool:
    """Heuristique : des mots en lettres, avec un accent ou une espace entre deux mots."""
    if not re.search(r"[A-Za-zÀ-ÿ]{2,}", texte):
        return False
    if texte.startswith(("mdi:", "http", "/", "%")) or re.fullmatch(r"[a-z0-9_.:\-{}/ ]*[a-z0-9_}]", texte) and " " not in texte:
        return False
    return bool(re.search(r"[À-ÿ]", texte) or re.search(r"[A-Za-zÀ-ÿ]{2,} +[A-Za-zÀ-ÿ']{2,}", texte))


def _chaines_suspectes(nom: str) -> list[tuple[int, str]]:
    src = _source(nom)
    arbre = ast.parse(src)
    lignes = src.splitlines()
    ignorees: set[int] = set()
    # Docstrings, et toute chaîne posée seule comme instruction (un commentaire, en somme).
    for noeud in ast.walk(arbre):
        if isinstance(noeud, ast.Expr) and isinstance(noeud.value, ast.Constant):
            ignorees.add(id(noeud.value))
    # Journaux : tout ce qui est sous un appel _LOGGER.xxx(...).
    for noeud in ast.walk(arbre):
        if isinstance(noeud, ast.Call) and isinstance(noeud.func, ast.Attribute):
            base = noeud.func.value
            if isinstance(base, ast.Name) and APPELS_JOURNAL.match(base.id):
                for sous in ast.walk(noeud):
                    ignorees.add(id(sous))
    sortie = []
    for noeud in ast.walk(arbre):
        if id(noeud) in ignorees or not isinstance(noeud, ast.Constant) or not isinstance(noeud.value, str):
            continue
        ligne = lignes[noeud.lineno - 1] if noeud.lineno else ""
        if "i18n-ok" in ligne:
            continue
        valeur = noeud.value.strip()
        if valeur in PERMIS or not _francais(valeur):
            continue
        sortie.append((noeud.lineno, valeur))
    return sortie


def test_pas_de_francais_en_dur():
    trouve = {nom: _chaines_suspectes(nom) for nom in FICHIERS_LOT}
    trouve = {k: v for k, v in trouve.items() if v}
    assert not trouve, trouve


def test_le_detecteur_detecte():
    assert _francais("Cette plage n'existe plus.")
    assert _francais("Écran de veille")
    assert not _francais("mdi:calendar-clock")
    assert not _francais("agent_en_ligne")
    assert not _francais("| **{nom}** |")


# ------------------------------------------------------------------ entity_id et noms affichés


async def _monter_langue(hass, hass_storage, langue: str) -> MockConfigEntry:
    hass.config.language = langue
    for nom in ("Jeux", "Réseaux sociaux"):
        lr.async_get(hass).async_create(nom)
    regle = {"id": 1, "name": "Matin", "start": 7 * 3600, "end": 8 * 3600, "weekdays": [True] * 5 + [False] * 2,
             "enabled": True, "portee": "etiquettes", "etiquettes": ["Jeux"]}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "j1": _fiche("j1", "PC-ALEX", "a" * 32, person="person.alex", rules=[regle]),
            "g1": _fiche("g1", "Google Pixel 8a", "b" * 32, person="person.alex", platform="android",
                         rules=[dict(regle, etiquettes=["Réseaux sociaux"])]),
        }}}
    hass.states.async_set("person.alex", "home", {"friendly_name": "Alex"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree


def _releve(hass) -> dict[str, list]:
    sortie = {}
    for e in er.async_get(hass).entities.values():
        if e.platform != DOMAIN:
            continue
        etat = hass.states.get(e.entity_id)
        sortie[e.unique_id] = [e.entity_id, etat.name if etat is not None else None]
    return dict(sorted(sortie.items()))


async def test_installation_neuve_en_francais_memes_entity_id_et_noms(hass, hass_storage):
    await _monter_langue(hass, hass_storage, "fr")
    releve = _releve(hass)
    if os.environ.get("I18N_HA_REFERENCE") == "ecrire":
        REFERENCE.write_text(json.dumps(releve, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        return
    reference = _json(REFERENCE)
    # Quelques repères lisibles, puis la liste complète.
    assert releve["pc_parental_rule_j1_1"][0].startswith("switch.pc_alex_plage_matin")
    assert releve["pc_parental_ruletime_j1_1_start"] == ["time.pc_alex_plage_matin_debut", "PC-ALEX Plage Matin début"]
    assert releve["pc_parental_blocages_g1"][1] == "Google Pixel 8a Sites bloqués aujourd'hui"
    assert releve["pc_parental_acces_j1"] == ["switch.pc_alex_acces_a_l_appareil", "PC-ALEX Accès à l'appareil"]
    manque = sorted(set(reference) - set(releve))
    trop = sorted(set(releve) - set(reference))
    assert not manque and not trop, (manque, trop)
    differents = {uid: (reference[uid], releve[uid]) for uid in reference if reference[uid] != releve[uid]}
    assert not differents, differents


async def test_installation_neuve_en_anglais(hass, hass_storage):
    await _monter_langue(hass, hass_storage, "en")
    releve = _releve(hass)
    assert releve["pc_parental_acces_j1"] == ["switch.pc_alex_device_access", "PC-ALEX Device access"]
    assert releve["pc_parental_ruletime_j1_1_start"][1] == "PC-ALEX Time slot Matin start"
    assert releve["pc_parental_blocages_g1"][1] == "Google Pixel 8a Blocked sites today"
    assert releve["pc_parental_verrouiller_j1"][1] == "PC-ALEX Lock now"
    etiquette = next(v for k, v in releve.items() if k.startswith("pc_parental_etq_j1_") and "Games" in str(v[1]))
    assert etiquette[1] == "PC-ALEX Block “Games”"
    # Les textes des entités suivent : unités, tableaux, libellés des listes.
    assert hass.states.get(releve["pc_parental_blocages_j1"][0]).attributes["unit_of_measurement"] == "attempts"
    taille = hass.states.get(releve["pc_parental_msg_taille_j1"][0])
    assert taille.state == "Medium" and "Large" in taille.attributes["options"]
    # Aucun nom resté en français.
    for uid, (_eid, nom) in releve.items():
        assert not re.search(r"[éèàù]", str(nom).replace("Réseaux sociaux", "")), (uid, nom)


async def test_changer_de_langue_garde_les_entity_id(hass, hass_storage):
    entree = await _monter_langue(hass, hass_storage, "fr")
    avant = _releve(hass)
    hass.config.language = "de"
    assert await hass.config_entries.async_reload(entree.entry_id)
    await hass.async_block_till_done()
    apres = _releve(hass)
    assert set(apres) == set(avant)
    assert {uid: v[0] for uid, v in apres.items()} == {uid: v[0] for uid, v in avant.items()}
    # Les noms affichés, eux, passent en allemand.
    assert apres["pc_parental_acces_j1"][1] != avant["pc_parental_acces_j1"][1]
