"""i18n, lot A2 : les textes que Home Assistant fabrique lui-même (catalogue pc_parental/i18n/serveur).

Ce qu'on vérifie :
- les six langues ont exactement les mêmes clés, aucune vide, les mêmes champs {…} d'une langue à l'autre, et
  chaque pluriel a ses deux formes (_one / _other) ;
- aucun texte français en dur dans les modules du lot A2 (hors journaux, commentaires, docstrings, identifiants) :
  détecteur heuristique, avec une courte liste d'exceptions justifiées ;
- le vocabulaire neutre : jamais « maison », « parent », « enfant » (ni leurs traductions) dans le catalogue ;
  Décideur, Demandeur et le lieu passent par des champs ({decideur}, {lieu}…) remplis par vocabulaire.py ;
- de bout en bout : un appareil qui annonce « de-DE » reçoit ses raisons de blocage et ses messages en allemand,
  un appareil qui n'annonce rien les reçoit dans la langue de Home Assistant (le français, sur ce banc).

    python -m pytest essai/i18n_serveur_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import ast
import json
import pathlib
import re

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
SOURCE = racine / "custom_components" / "pc_parental"
CATALOGUE = SOURCE / "i18n" / "serveur"
LANGUES = ("fr", "en", "de", "es", "it", "nl")
CHAMP = re.compile(r"\{(\w*)[^{}]*\}")


def _catalogue() -> dict[str, dict[str, str]]:
    return {lg: json.loads((CATALOGUE / f"{lg}.json").read_text(encoding="utf-8")) for lg in LANGUES}


# ----------------------------------------------------------------------------- Le catalogue


def test_six_langues_completes_et_memes_champs():
    cat = _catalogue()
    cles = set(cat["fr"])
    assert len(cles) > 500
    for lg in LANGUES:
        assert set(cat[lg]) == cles, (lg, sorted(set(cat[lg]) ^ cles)[:10])
        vides = [k for k, v in cat[lg].items() if not isinstance(v, str) or not v.strip()]
        assert not vides, (lg, vides)
    for cle in cles:
        champs = {lg: sorted(set(CHAMP.findall(cat[lg][cle]))) for lg in LANGUES}
        assert len({tuple(c) for c in champs.values()}) == 1, (cle, champs)
        if cle.endswith("_one"):
            assert cle[:-4] + "_other" in cles, cle
        if cle.endswith("_other"):
            assert cle[:-6] + "_one" in cles, cle


# Le vocabulaire neutre (docs/i18n/vocabulaire.md) : ces mots ne s'écrivent jamais dans un texte du catalogue.
INTERDITS = re.compile(
    r"\bfamil|\bgezin|\bmaisons?\b|\bparents?\b|\benfants?\b|\bhomes?\b(?!\s*screen)|\bhouse|\bchild|\bkids?\b|\beltern|\bkind(er)?\b"
    r"|\bpadres\b|\bniños?\b|\bgenitori\b|\bbambin|\bouders\b|\bkinderen\b|\bcasa\b|\bzuhause\b|\bthuis\b"
    # Décideur et Demandeur passent par {decideur}/{demandeur} : jamais écrits en dur dans une phrase.
    r"|\bdécideurs?\b|\bdemandeurs?\b|\bdeciders?\b|\brequesters?\b|\bentscheider\b|\banfragende|\bdecisori\b"
    r"|\bbeslissers?\b|\baanvragers?\b",
    re.IGNORECASE,
)


def test_vocabulaire_neutre():
    cat = _catalogue()
    for lg in LANGUES:
        for cle, texte in cat[lg].items():
            if cle.startswith("vocab."):
                continue  # les termes par défaut eux-mêmes
            # « Home Assistant » (nom propre) ; « schermata Home » : l'accueil en italien (glossaire).
            propre = re.sub(r"(?i)schermata home", "", texte.replace("Home Assistant", "").replace("Home-Assistant", ""))
            assert not INTERDITS.search(propre), (lg, cle, texte)


# ----------------------------------------------------------------------------- Pas de français en dur

# Les modules des autres lots (et le socle commun) ne sont pas vérifiés ici.
AUTRES_LOTS = {
    "dashboard.py",  # lot B
    "config_flow.py", "entity.py", "sensor.py", "binary_sensor.py", "switch.py", "select.py", "text.py", "time.py",
    "number.py", "button.py", "update.py", "notify.py",  # lot A1
    "langues.py", "vocabulaire.py",  # socle commun (vocabulaire.py : termes par défaut du glossaire)
    "party_invites.py",  # page des invités de la Party : en refonte dans une autre session
    # Lot Réveil : ses erreurs (ReveilInvalide) portent une clé, traduite par Home Assistant (exceptions.reveil_<clé>,
    # translations/*.json, essai/reveil_traductions_test.py) et rendue telle quelle à l'appli ; le texte n'est qu'un repli.
    "reveil.py", "reveil_calcul.py", "reveil_entites.py", "reveil_services.py",
}
# Fichiers entiers d'identifiants : on ne les traduit pas, ils sont comparés ou enregistrés tels quels.
FICHIERS_IDENTIFIANTS = {
    # Valeurs des listes déroulantes et états enregistrés (lot A1 les affiche), sources des listes, mots de passage
    # des plages : changer un de ces textes casserait des états enregistrés et des automatisations.
    "const.py",
    # Motifs de lecture de pages web (HTML), noms de championnats et champs en anglais des API ; « sport »
    # (« Formule 1 »…) est un identifiant que l'appli compare.
    "esports.py",
}
EXCEPTIONS: dict[str, set[str]] = {
    # Noms propres et identifiants.
    "__init__.py": {"Tous les jours", "Home Assistant"},  # clé de DAY_PRESETS ; auteur d'une action
    "messages_v2.py": {"Home Assistant"},
    "store.py": {"Tous les PC", "Home Assistant", "Program Files", "pub & traçage"},  # ancien destinataire accepté
    "http.py": {"pub & traçage", "système", "Toujours autorisé"},  # noms d'étiquettes (données, en minuscules)
    "parent.py": {"pub & traçage", "Toujours autorisé"},
    "labels.py": {"toujours autorisé"},
    "musique.py": {"YouTube Music", "Apple Music", "Radio Browser", "Music Assistant",
                   "Déplacer ", "À la fin "},  # libellés d'une action, pour le journal (_LOGGER) seulement
    "nouveautes.py": {"version ou points manquants"},  # erreur interne, rattrapée aussitôt et écrite au journal
    "processus_catalogue.py": {"advanced micro devices", "killer networking", "rivet networks", "microsoft windows",
                               "easy anti-cheat", "ha parental"},  # éditeurs et noms comparés aux données
    "veille_page.py": {"Vision affichage"},  # nom de l'utilisateur Home Assistant créé (identifiant)
    "veille_passerelle.py": {"Vision affichage (lecture)"},
    "apparence.py": {"Bleu nuit", "Rose poudré", "Écrin", "Rétro 85", "Féerie", "Étincelle"},  # palettes (= appli)
    "party_invites.py": {"Bleu nuit", "Rose poudré"},  # palettes de la page invité (clés = store.veille_theme)
    # Données lues telles quelles chez la maison (Météo-France, Pronote, états de capteurs écrits en français).
    "veille.py": {"Vent violent", "allumé"},
    "veille_plus.py": {
        "SomaFM Fluid · lofi, hip-hop instrumental", "SomaFM Groove Salad · ambient, downtempo",
        "SomaFM Lush · électro douce, voix", "SomaFM Beat Blender · deep house", "SomaFM Drone Zone · ambiance",
        "SomaFM Secret Agent · lounge", "Radio Paradise Mellow", "Radio Paradise", "FIP Jazz", "FIP Électro",
        "FIP Groove", "FIP Pop",  # noms des radios
        "Personnalisée",  # option de la liste déroulante « Musique » (lot A1)
        "Bleu nuit", "Rose poudré",  # thèmes = palettes de l'appli
        "Français", "Histoire-géo", "allemand lv2", "anglais lv1", "espagnol lv2", "education musicale",
        "sciences vie & terre", "Arts plastiques", "arts plastiques", "education physique et sportive",  # Pronote
        "pluie faible", "pluie forte", "pluie modérée", "temps sec",  # Météo-France
        "allumé", "en charge",  # états écrits en français par des capteurs
    },
    # Le journal (_journal, avertissements, événement) : en français, comme tous les journaux.
    "roles.py": {
        "validation de « {} » : selon le rôle", "validation de « {} » : {}", "validation du rôle « {} » : {}",
        "rôle « {} » créé d'après « {} »", "renommé « {} » → « {} »", "pause max {} min", "rôle « {} » : {}",
        "rôle « {} » supprimé", "aucun ({} par défaut)", "{} : rôle « {} »", "« {} » : rôle « {} »",
        "« {} » : suit le rôle de sa personne", "annulé (un rôle effectif aurait changé)",
    },
    "widgets.py": {"ordre changé", "« {} » : plus {} ; moins {}", "inchangé", "lecture seule", "actions permises — "},
    "widgets_admin.py": {"un administrateur"},
    "apparence.py#journal": {"un administrateur"},
    "pauses.py": {"code de gestion", "empreinte ou visage", "déverrouillage à distance"},
    "notoriete.py": {"Aucun CSV dans l'archive.", "Classement vide.", "archive plus grosse que prévu"},
    # Une ancienne docstring restée après un « return » (code mort) : un commentaire.
    "veille_tableaux.py": {"Les tableaux composés sur l'appareil lui-même : on résout leurs cases, sans rien enregistrer."},
    "planner.py": {"tous les jours", "chaque jour", "en semaine", "jours d'école", "le week-end"},  # mots relus
    # Un fichier YAML de thème écrit pour Home Assistant : ses commentaires restent en français, comme ceux du code.
    "apparence.py#yaml": {
        "# Thème « Vision », écrit par Vision (Tableau → Appareils → Apparence) : palette « {} », mode {}{}.",
        ", accent {}", "# Choisir l'apparence depuis le tableau de bord : ce fichier est réécrit à chaque changement.",
    },
    "installation.py": set(),  # commandes PowerShell (reconnues comme du code ci-dessous)
}

MOT = re.compile(r"[A-Za-zÀ-ÿ]{2,}")


def _suspect(texte: str) -> bool:
    if not MOT.search(texte):
        return False
    if re.search(r"<[a-z]|\\[bdsw(]|\birm\b|\$env:", texte):
        return False  # HTML, expression régulière, commande PowerShell
    if re.search(r"[À-ÿ]", texte):
        return True
    t = re.sub(r"\{[^{}]*\}", "X", texte)
    return bool(re.search(r"[A-Za-zÀ-ÿ]{2,}[ «»]+[A-Za-zÀ-ÿX]{2,}|[A-Za-zÀ-ÿ]{2,} X|X [A-Za-zÀ-ÿ]{2,}", t))


def _chaines(arbre: ast.AST):
    """Les chaînes d'un module, hors docstrings et appels de journal ; une f-string devient « … {} … »."""
    ignorees: set[int] = set()
    for n in ast.walk(arbre):
        if isinstance(n, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            if n.body and isinstance(n.body[0], ast.Expr) and isinstance(n.body[0].value, ast.Constant):
                ignorees.add(id(n.body[0].value))
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute):
            cible = ast.unparse(n.func.value)
            if "LOGGER" in cible or cible.startswith("logging.getLogger"):
                for sous in ast.walk(n):
                    ignorees.add(id(sous))
    for n in ast.walk(arbre):
        if id(n) in ignorees:
            continue
        if isinstance(n, ast.JoinedStr):
            for v in n.values:
                ignorees.add(id(v))
            yield n.lineno, "".join(v.value if isinstance(v, ast.Constant) else "{}" for v in n.values)
        elif isinstance(n, ast.Constant) and isinstance(n.value, str):
            yield n.lineno, n.value


def test_pas_de_francais_en_dur():
    trouves = []
    for chemin in sorted(SOURCE.glob("*.py")):
        if chemin.name in AUTRES_LOTS or chemin.name in FICHIERS_IDENTIFIANTS:
            continue
        permis = set()
        for nom, chaines in EXCEPTIONS.items():
            if nom.split("#")[0] == chemin.name:
                permis |= chaines
        for ligne, texte in _chaines(ast.parse(chemin.read_text(encoding="utf-8"))):
            if _suspect(texte) and texte not in permis:
                trouves.append(f"{chemin.name}:{ligne}: {texte[:90]!r}")
    assert not trouves, "Texte en dur (à mettre dans le catalogue serveur) :\n" + "\n".join(trouves)


# ----------------------------------------------------------------------------- De bout en bout


async def _monter(hass, hass_storage, langue_ha: str = "fr"):
    hass.config.language = langue_ha
    pcs = {
        "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", bloques=["exemple.com"]),
        "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android", bloques=["exemple.com"]),
        "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent"),
    }
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": pcs}}
    for p in ("lou", "noa", "robin"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


def _textes_motifs(reponse: dict) -> list[str]:
    return [str(m).split("|", 1)[1] for m in reponse.get("motifs") or []]


async def test_raison_et_messages_dans_la_langue_de_l_appareil(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    client = await hass_client()
    # Lou annonce l'allemand ; Noa n'annonce rien : la langue de Home Assistant (le français).
    de = await (await client.post("/api/pc_parental/poll", json={**ENFANT, "langue": "de-DE"})).json()
    fr = await (await client.post("/api/pc_parental/poll", json={**ENFANT2})).json()
    await hass.async_block_till_done()
    coord = list(hass.data[DOMAIN].values())[0]
    assert coord.store.pcs["e1"]["langue"] == "de"
    assert "langue" not in coord.store.pcs["e2"]
    assert "Auf deinen Geräten gesperrt." in _textes_motifs(de), de.get("motifs")
    assert "Coupé sur tes appareils." in _textes_motifs(fr), fr.get("motifs")

    # Un décideur ferme les deux appareils : chacun reçoit le mot dans sa langue, le décideur le sien.
    r = await client.post("/api/pc_parental/parent", json={**PARENT, "langue": "en-GB", "action": "fermer", "pc": "e1"})
    assert (await r.json())["retour"] == "Robin blocked access."
    await client.post("/api/pc_parental/parent", json={**PARENT, "action": "fermer", "pc": "e2"})
    await hass.async_block_till_done()
    coord = list(hass.data[DOMAIN].values())[0]  # l'entrée a pu se recharger (étiquette créée au premier relevé)
    assert coord.store.pcs["e1"]["messages"][-1]["text"] == "Robin hat den Zugang gesperrt.", coord.store.pcs["e1"]
    assert coord.store.pcs["e2"]["messages"][-1]["text"] == "Robin a fermé l'accès."
    de = await (await client.post("/api/pc_parental/poll", json={**ENFANT, "langue": "de-DE"})).json()
    fr = await (await client.post("/api/pc_parental/poll", json={**ENFANT2})).json()
    assert de["message"] == "Gesperrt" and fr["message"] == "Accès fermé"

    # Une langue que Vision ne parle pas : l'appareil revient à la langue de Home Assistant.
    await client.post("/api/pc_parental/poll", json={**ENFANT, "langue": "ja-JP"})
    coord = list(hass.data[DOMAIN].values())[0]
    assert "langue" not in coord.store.pcs["e1"]


async def test_maison_en_anglais_appareil_sans_langue(hass, hass_storage, hass_client):
    """Home Assistant en anglais : l'appareil qui n'annonce rien parle anglais ; le vocabulaire suit."""
    await _monter(hass, hass_storage, langue_ha="en")
    client = await hass_client()
    r = await (await client.post("/api/pc_parental/poll", json={**ENFANT2})).json()
    assert "Blocked on your devices." in _textes_motifs(r)
    from custom_components.pc_parental import textes

    assert textes.t("commun.reserve_decideurs", "en") == "Only deciders can do this."
    assert textes.t("commun.reserve_decideurs", "fr") == "Réservé aux décideurs."
    assert textes.t("tab.maison.nom", "de") == "Ort"  # {lieu} : rien de choisi, le mot neutre de la langue (lieu.DEFAUTS)
