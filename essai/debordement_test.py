"""Recette visuelle (règle permanente de l'auteur, 2026-10-11) : rien de rogné, tronqué ou collé aux bords, sur toutes les cartes.

Chaque carte Vision (Personnes en modes maison et personne, Étiquettes, Messages, Planning, Logiciels, Apparence,
Tableaux de veille, Widgets HA, Écran de veille) et le panneau d'appairage sont dessinés dans Edge ou Chrome sans
affichage (essai/clics/debordement.mjs), avec les données que Vision sert vraiment — ici volontairement LONGUES (noms
de personnes et d'appareils à rallonge, paquet Android sans espace, motifs bavards) et dans les six langues :
- tailles : téléphone 360 et 412, tablette 800×1280 et 1280×800, TV 1280×720 et 1920×1080 ;
- police 100 % et 130 % (le zoom de texte de la WebView Android : voir l'en-tête de debordement.mjs) ;
- thème clair et sombre ; matrice complète en allemand, les cinq autres langues au cas le plus serré (360, 130 %).

Échec si une ellipse est appliquée, si du texte est coupé, si un élément sort de la carte, de son parent ou de la
fenêtre (la page ne défile jamais à l'horizontale), si un texte ou un contrôle est à moins de 8 px d'un bord de carte.
Le rapport (carte, taille, police, thème, langue, sélecteur, défaut) est écrit dans etude-ux/captures/
recette-lot1-ha-rapport.txt, avec une capture PNG par cas (recette-lot1-ha-<carte>-<largeur>-<police>-<theme>.png).

Sauté proprement si Node ou le navigateur manquent, ou s'il reste moins de 1,5 Go de mémoire libre (le PC d'essai :
VISION_DEBORDEMENT_FORCER=1 pour passer outre). Réglages : VISION_DEBORDEMENT_CARTES=maison,messages (quelques cartes),
VISION_DEBORDEMENT_RAPIDE=1 (360 et 1920×1080, 130 %, clair), VISION_CAPTURES=<dossier>,
VISION_DEBORDEMENT_GARDER=<dossier> (garde le banc préparé : node clics/debordement.mjs <dossier> <navigateur> <captures>).

    python -m pytest essai/debordement_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import asyncio
import ctypes
import json
import os
import pathlib
import shutil
import sys
import time

import pytest

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

ICI = pathlib.Path(__file__).parent
RACINE = ICI.parent / "custom_components" / "pc_parental"
CAPTURES_DEFAUT = pathlib.Path("captures")
NAVIGATEURS = (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    "/usr/bin/chromium", "/usr/bin/google-chrome",
)
LANGUES = ("de", "fr", "en", "es", "it", "nl")
LECTURES = {
    "maison": "pc_parental/maison",
    "widgets": "pc_parental/widgets/admin",
    "apparence": "pc_parental/apparence",
    "veille": "pc_parental/veille/tableaux",
    "dashboards": "pc_parental/veille/dashboards",
    "catalogue": "pc_parental/catalogue",
    "processus": "pc_parental/processus",
    "messages": "pc_parental/messages",
    "appairage": "pc_parental/appairage",
    "reglages": "pc_parental/reglages/admin",  # refonte lot 5
    "canaux": "pc_parental/canaux",  # 2.5.6 : Réglages › Mes notifications
}
# Des noms réalistes mais longs (l'allemand et les noms composés sont les plus longs).
LOU = "Maximilian-Alexander von Hohenzollern-Sigmaringen"
NOLAN = "Bartholomäus Friedrich-Wilhelm Langenscheidt"
ANNE = "Anne-Charlotte de Montmorency-Laval"
TEL = "TELEFON-MAXIMILIAN-KINDERZIMMER-OBERGESCHOSS"
PC = "ARBEITSPLATZRECHNER-WOHNZIMMER-ERDGESCHOSS"
TELE = "Fernsehgerät im Wohnzimmer (Erdgeschoss, Südseite)"
PAQUET = "com.mojang.minecraftpe.bedrock.edition.ein.sehr.langer.paketname"
LIEU = "Gemeinschaftsschule Nordrhein-Westfalen"  # 39 caractères (40 au plus)
BOUTONS = {"de": ("Weiter", "Absenden"), "fr": ("Suivant", "Valider"), "en": ("Next", "Submit"), "es": ("Siguiente", "Enviar"),
           "it": ("Avanti", "Invia"), "nl": ("Volgende", "Verzenden")}


class _Champs(dict):
    def __missing__(self, cle):
        return "{" + cle + "}"


def _flux(hass, langue: str) -> dict:
    """Refonte lot 4 : les écrans de l'assistant (ajout et relance) et de Configurer réduit, avec les textes de
    translations/<langue>.json et les valeurs que Vision met dans chaque étape — pour la maquette du banc."""
    from custom_components.pc_parental import assistant, config_flow, modules, vocabulaire

    tr = json.loads((RACINE / "translations" / f"{langue}.json").read_text(encoding="utf-8"))
    cfg, opt, sel = tr["config"], tr["options"]["step"], tr["selector"]
    flux = config_flow.PcParentalConfigFlow()
    flux.hass = hass
    termes = flux._termes({})
    suivant, valider = BOUTONS[langue]
    defauts = {"defauts": ", ".join(vocabulaire.defauts(langue)[m] for m in vocabulaire.MOTS),
               "longueur": str(vocabulaire.LONGUEUR_MAX), "decideurs": termes["decideurs"], "demandeurs": termes["demandeurs"],
               "erreur": "", "tableau": "/vision-maison", "attente": "2"}
    groupes = set(assistant.GROUPES)
    manque = " ".join(modules.manques(set(assistant.GROUPES["demandes"][0]), langue).values()) + "\n\n"
    defauts["resume"] = flux._resume_assistant(LIEU, {}, ["person.nolan", "person.anne"], groupes, "tele")
    defauts["suite"] = flux._tx("assistant_suite_tele")

    def texte(t, **plus):
        return str(t or "").format_map(_Champs({**defauts, **plus}))

    def lieu(etape):
        sec = etape["sections"]["mots"]
        return [{"type": "texte", "label": etape["data"]["lieu"], "aide": etape["data_description"]["lieu"], "valeur": LIEU},
                {"type": "section", "label": sec["name"], "description": texte(sec["description"]),
                 "champs": [{"type": "texte", "label": sec["data"][m]} for m in vocabulaire.MOTS]}]

    def ecran(etape, champs=(), bouton=suivant, erreur="", **plus):
        return {"titre": texte(etape.get("title")), "description": texte(etape.get("description"), **plus),
                "champs": list(champs), "bouton": bouton, "erreur": erreur}

    def menu(etape):
        return ecran(etape, [{"type": "menu", "options": [texte(v) for v in etape["menu_options"].values()]}], bouton="")

    personnes = [{"type": "personnes", "label": cfg["step"]["personnes"]["data"]["decideurs"], "valeurs": [NOLAN, ANNE]}]
    objectifs = [{"type": "cases", "label": cfg["step"]["objectifs"]["data"]["objectifs"],
                  "options": list(sel["objectifs"]["options"].values())}]
    erreur_obj = cfg["error"]["objectifs_dependances"]
    etapes = {
        "user": ecran(cfg["step"]["user"], lieu(cfg["step"]["user"])),
        "personnes": ecran(cfg["step"]["personnes"], personnes),
        "objectifs": ecran(cfg["step"]["objectifs"], objectifs),
        "objectifs_erreur": ecran(cfg["step"]["objectifs"], objectifs, erreur=erreur_obj) | {
            "description": texte(cfg["step"]["objectifs"]["description"], erreur=manque)},
        "appareil": ecran(cfg["step"]["appareil"], [{"type": "choix", "label": cfg["step"]["appareil"]["data"]["appareil"],
                                                     "options": list(sel["premier_appareil"]["options"].values())}]),
        "pret": ecran(cfg["step"]["pret"], bouton=valider),
        "menu": menu(opt["init"]),
        "reparer": menu(opt["reparer"]),
        "autres": menu(opt["autres"]),
        "r_user": ecran(opt["assistant"], lieu(opt["assistant"])),
        "r_personnes": ecran(opt["assistant_personnes"], personnes),
        "r_objectifs": ecran(opt["assistant_objectifs"], objectifs, erreur=erreur_obj) | {
            "description": texte(opt["assistant_objectifs"]["description"], erreur=manque)},
        "r_pret": ecran(opt["assistant_pret"], bouton=valider),
    }
    return {"flux": {"etapes": etapes}}


def _navigateur() -> str | None:
    return next((n for n in NAVIGATEURS if os.path.exists(n)), None)


def _memoire_libre_go() -> float | None:
    """La mémoire physique libre (Windows) ; None ailleurs (pas de garde)."""
    if sys.platform != "win32":
        return None

    class Etat(ctypes.Structure):
        _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong), ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong), ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong), ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong), ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

    e = Etat()
    e.dwLength = ctypes.sizeof(Etat)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(e)):
        return None
    return e.ullAvailPhys / 1024 ** 3


def _rapport(lignes: list[dict], fin: dict) -> str:
    """Le rapport lisible : un bloc par cas en défaut, puis les avertissements, puis le bilan."""
    out = ["Recette visuelle HA — test anti-débordement (essai/debordement_test.py)", time.strftime("%Y-%m-%d %H:%M"), ""]
    cas = [x for x in lignes if "carte" in x]
    for x in cas:
        if not x["defauts"]:
            continue
        out.append(f"■ {x['carte']} · {x['taille']} · police {x['police']} % · {x['theme']} · {x['langue']}"
                   + (f"  [{x['capture']}]" if x.get("capture") else ""))
        for d in x["defauts"]:
            texte = f" — « {d['texte']} »" if d.get("texte") else ""
            out.append(f"    [{d.get('etat', '-')}] {d['selecteur']} : {d['defaut']}{texte}")
    avert = [(x, d) for x in cas for d in x["avertissements"]]
    if avert:
        out += ["", "Avertissements (défilement horizontal voulu, indication de champ coupée) :"]
        vus = set()
        for x, d in avert:
            cle = (x["carte"], d["selecteur"], d["defaut"].split("(")[0])
            if cle in vus:
                continue
            vus.add(cle)
            out.append(f"    {x['carte']} · {x['taille']} · {x['police']} % : {d['selecteur']} : {d['defaut']}")
    par_carte: dict[str, int] = {}
    for x in cas:
        par_carte[x["carte"]] = par_carte.get(x["carte"], 0) + len(x["defauts"])
    out += ["", "Bilan par carte : " + ", ".join(f"{k} {v}" for k, v in par_carte.items()),
            f"Cas : {fin.get('cas')} · défauts : {fin.get('defauts')} · captures : {fin.get('captures_mo')} Mo"
            + (f" ({fin.get('captures_sautees')} sautées, budget atteint)" if fin.get("captures_sautees") else "")]
    if fin.get("erreur"):
        out.append("Erreur du banc : " + str(fin["erreur"]))
    return "\n".join(out) + "\n"


async def test_aucune_carte_ne_deborde(hass, hass_storage, hass_client, tmp_path):
    nav, node = _navigateur(), shutil.which("node")
    if not nav or not node:
        pytest.skip("Node ou un navigateur Chromium (Edge, Chrome) manquent : test anti-débordement sauté.")
    libre = _memoire_libre_go()
    if libre is not None and libre < 1.5 and os.environ.get("VISION_DEBORDEMENT_FORCER") != "1":
        pytest.skip(f"{libre:.1f} Go de mémoire libre (< 1,5 Go) : test anti-débordement sauté (VISION_DEBORDEMENT_FORCER=1).")

    maintenant = time.time()
    lou = _fiche("e1", TEL, ENFANT["secret"], person="person.lou", platform="android", type="telephone")
    lou["demandes"] = [
        {"id": "d1", "genre": "apps", "cle": PAQUET, "nom": PAQUET, "libelle": "Minecraft: Bedrock Edition (Bildungsausgabe)",
         "lien": "", "motif": "Ich möchte mit meinen Klassenkameraden an unserem gemeinsamen Schulprojekt weiterbauen, bitte!",
         "duree": "1h", "ts": maintenant - 60},
        {"id": "d2", "genre": "sites", "cle": "unterrichtsmaterialien-grundschule-nordrhein-westfalen.de",
         "nom": "unterrichtsmaterialien-grundschule-nordrhein-westfalen.de", "libelle": "unterrichtsmaterialien-grundschule-nordrhein-westfalen.de",
         "lien": "https://unterrichtsmaterialien-grundschule-nordrhein-westfalen.de/arbeitsblaetter", "motif": "Hausaufgaben",
         "duree": "1h", "ts": maintenant - 120},
    ]
    # 2.5.1 : une demande groupée (un logiciel et ses sites bloqués aux noms longs).
    lou["demandes"].append({"id": "d3", "genre": "apps", "cle": PAQUET, "nom": PAQUET, "libelle": "Minecraft: Bedrock Edition (Bildungsausgabe)",
                            "lien": "", "motif": "", "duree": "", "ts": maintenant - 30,
                            "sites": ["minecraft-marktplatz-inhalte-der-gemeinschaft.net", "minecraftservices.com"]})
    lou["demande"] = {"id": "t1", "minutes": 15, "ts": maintenant - 30}
    # Refonte lot 6 (HA-21) : des plages aux noms longs, pour la liste par jour du planning sur téléphone.
    lou["rules"] = [
        {"id": 1, "name": "Hausaufgabenzeit nach der Schule (ohne Spiele und soziale Netzwerke)", "start": 54000,
         "end": 64800, "weekdays": [True, True, True, True, True, False, False], "enabled": True},
        {"id": 2, "name": "Nachtruhe", "start": 75600, "end": 25200, "weekdays": [True] * 7, "enabled": False},
    ]
    lou["apps_vues"] = {PAQUET: {"premier": maintenant, "dernier": maintenant, "secondes": 3600},
                        "com.google.android.apps.youtube.kids": {"premier": maintenant, "dernier": maintenant, "secondes": 60}}
    lou["sites_vus"] = {d: {"premier": maintenant, "dernier": maintenant, "n": 3} for d in (
        "wikipedia.org", "unterrichtsmaterialien-grundschule-nordrhein-westfalen.de", "twitch.tv")}
    pc = _fiche("e2", PC, "f" * 32, person="person.lou", platform="windows", type="pc")
    # 2.5.1 : des blocages récents aux noms longs, dont un pendant que le logiciel tournait.
    pc["alertes"] = [{"domaine": "minecraft-marktplatz-inhalte-der-gemeinschaft.net", "source": "regle", "quand": maintenant - 90,
                      "fois": 17, "app": PAQUET},
                     {"domaine": "unterrichtsmaterialien-grundschule-nordrhein-westfalen.de", "source": "regle", "quand": maintenant - 400, "fois": 2}]
    tele = _fiche("t1", TELE, "c" * 32, platform="android", type="tv")
    nolan = _fiche("p1", "PIXEL-9-PRO-BARTHOLOMAEUS-LANGENSCHEIDT", PARENT["secret"], person="person.nolan", platform="android", role="parent")
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "entites_personnes": {"person.lou": ["sensor.salon"]},
        # Refonte lot 3 : le module Réveil (inactif par défaut) pour l'onglet Réveils de la fiche.
        "modules": {"reveil": True},
        # 2.4.0 : une entité « pour tout le monde », réglée (rôle, tout le monde, confirmation, nom long, détail).
        "entites_tous": ["switch.salle_de_jeux_bloquer_reseaux_sociaux"],
        "entites_reglages": {"switch.salle_de_jeux_bloquer_reseaux_sociaux": {
            "role": "interrupteur", "qui": "tous", "confirmer": True, "affichage": "valeur_detail",
            "nom": "Réseaux sociaux de la chambre — bloquer pendant les devoirs",
            # 2.4.3 : « Au toucher » › vue, avec un chemin long (liste des vues et chemin dans le panneau).
            "toucher": "vue", "chemin": "/tableau-de-la-maison-principale/energie-et-chauffage-du-sejour"},
            "sensor.salon": {"toucher": "vue", "chemin": "/tableau-de-la-maison-principale/energie-et-chauffage-du-sejour"}},
        "entites_actions": {"switch.salle_de_jeux_bloquer_reseaux_sociaux": ["toggle", "turn_on", "turn_off"]},
        "pcs": {"e1": lou, "e2": pc, "t1": tele, "p1": nolan},
        # 2.5.1 : une catégorie fermée pour Lou, un logiciel et ses sites (l'écran par élément).
        "etiquettes": {"apps": {PAQUET: ["Jeux"]}, "sites": {d: ["Jeux"] for d in (
            "minecraft-marktplatz-inhalte-der-gemeinschaft.net", "twitch.tv", "unterrichtsmaterialien-grundschule-nordrhein-westfalen.de")}},
        "domaines_auto": {PAQUET: {"minecraft-marktplatz-inhalte-der-gemeinschaft.net": {"depuis": 1, "suit": False}}},
    }}
    hass.states.async_set("person.lou", "home", {"friendly_name": LOU})
    hass.states.async_set("person.nolan", "not_home", {"friendly_name": NOLAN})
    hass.states.async_set("person.anne", "home", {"friendly_name": ANNE})
    # 2.3.8 : des entités aux noms longs pour la liste des suggestions de la carte Widgets HA (capture de l'auteur).
    for eid, nom, etat in (
        ("input_number.chauffage_cible_confort", "Chauffage — température cible confort du séjour et de la salle à manger", "20.0"),
        ("select.controle_parental_freebox_appareil_a_associer", "Contrôle parental Freebox : appareil à associer au profil", "aucun"),
        ("binary_sensor.piscine_bachee_ouvert", "Piscine bâchée ouverte (capteur de la bâche automatique)", "off"),
        ("switch.salle_de_jeux_bloquer_reseaux_sociaux", "Salle de jeux — bloquer les réseaux sociaux", "off"),
    ):
        hass.states.async_set(eid, etat, {"friendly_name": nom})
    hass.states.async_set("sensor.salon", "21.5", {"friendly_name": "Raumtemperatur im Wohnzimmer (Erdgeschoss, Südseite)",
                                                   "unit_of_measurement": "°C"})
    # 2.4.3 : des tableaux de bord aux titres longs, pour la liste « Au toucher » de la carte Widgets HA.
    class _Tableau:
        def __init__(self, titre, vues):
            self.config = {"title": titre}
            self._vues = vues

        async def async_load(self, _force):
            return {"views": self._vues}
    hass.data["lovelace"] = {"dashboards": {
        None: _Tableau("Übersicht", [{"title": "Startseite", "path": "start"}]),
        "tableau-de-la-maison-principale": _Tableau("Tableau de la maison principale et des dépendances",
                                                    [{"title": "Énergie et chauffage du séjour", "path": "energie-et-chauffage-du-sejour"}]),
    }}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    client = await hass_client()
    # FAILLE (16/10) : la fiche n'accorde qu'avec le code de déverrouillage d'un Décideur ; le banc en pose un d'essai.
    from custom_components.pc_parental import octroi as _octroi
    _octroi.poser(hass.data[DOMAIN][entree.entry_id].store, "person.nolan", "482913")

    # Un message long, avec des réponses longues ; une demande d'appairage au nom long.
    r = await client.post("/api/pc_parental/messages", json={
        "action": "envoyer", "destinataires": ["person.lou", "t1"], "type": "message",
        "texte": "Das Abendessen steht in zehn Minuten auf dem Tisch – bitte jetzt das Spiel speichern und die Hände waschen!",
        "reponses": ["Ich komme sofort", "Noch fünf Minuten bitte", "Einverstanden", "Ich habe keinen Hunger"], "important": True})
    assert r.status == 200, await r.text()
    r = await client.post("/api/pc_parental/appairage/demande", json={
        "host": "GALAXY-TAB-S9-ULTRA-KINDERZIMMER", "nom": "Samsung Galaxy Tab S9 Ultra (Kinderzimmer, Obergeschoss)", "platform": "android"})
    assert r.status in (200, 201), await r.text()
    # Refonte lot 3 (fiche d'une personne) : récompenses, règle selon le lieu et réveils aux noms longs.
    hass.states.async_set("todo.aufgaben", "3", {"friendly_name": "Aufgaben von Maximilian-Alexander (Hausaufgaben und Haushalt)"})
    hass.states.async_set("zone.gymnasium", "0", {"friendly_name": "Gymnasium am Stadtpark (Schulgelände, Sporthalle)"})
    r = await client.post("/api/pc_parental/personne/admin", json={"op": "temps_gagne", "code_octroi": "482913", "personne": "person.lou",
                                                                   "liste": "todo.aufgaben", "minutes": 15, "max": 60})
    assert r.status == 200, await r.text()
    r = await client.post("/api/pc_parental/personne/admin", json={"op": "regle_lieu", "code_octroi": "482913", "personne": "person.lou", "zone": "zone.gymnasium",
                                                                   "etiquettes": ["Jeux vidéo et divertissement en ligne", "Réseaux sociaux"]})
    assert r.status == 200, await r.text()
    from custom_components.pc_parental import reveil as _reveil
    _coord = hass.data[DOMAIN][entree.entry_id]
    for nom_reveil in ("Schule — Montag bis Freitag (früh, mit Radio und langsam lauter werdend)", "Wochenende"):
        _reveil.creer(_coord.store, {"nom": nom_reveil, "demandeur": "person.lou", "actif": True}, "essai", "home_assistant")
    await _coord.store.async_save()
    # Lot 2 (« À décider ») : des logiciels à valider, pour dessiner aussi les lots sous les demandes.
    from custom_components.pc_parental.processus import CAPTEUR
    hass.states.async_set(CAPTEUR, "12")
    await hass.async_block_till_done()

    # Les données, telles que Vision les sert, dans chaque langue (les textes du serveur suivent la langue de HA).
    plages = next(e for e in hass.states.async_all("sensor") if "plages" in e.attributes)
    for langue in LANGUES:
        hass.config.language = langue
        dossier = tmp_path / "donnees" / langue
        dossier.mkdir(parents=True)
        for nom, chemin in LECTURES.items():
            r = await client.get("/api/" + chemin)
            assert r.status == 200, (chemin, r.status, await r.text())
            (dossier / f"{nom}.json").write_text(json.dumps({chemin: await r.json()}, ensure_ascii=False), encoding="utf-8")
        # 2.5.1 : les lectures de l'écran par élément, servies au banc comme Vision les sert.
        maison = json.loads((dossier / "maison.json").read_text(encoding="utf-8"))
        for corps in ({"action": "categorie", "etiquette": "Jeux"}, {"action": "recherche", "texte": "minecraft"}, {"action": "recents"}):
            r = await client.post("/api/pc_parental/maison", json={"pc": "e1", **corps})
            assert r.status == 200, (corps, await r.text())
            maison["__lecture_" + corps["action"]] = await r.json()
        assert maison["__lecture_categorie"]["logiciels"] and maison["__lecture_recents"]["recents"], maison["__lecture_recents"]
        (dossier / "maison.json").write_text(json.dumps(maison, ensure_ascii=False), encoding="utf-8")
        planning = json.loads((dossier / "catalogue.json").read_text(encoding="utf-8"))
        planning["__entite"] = plages.entity_id
        planning["__etats"] = {plages.entity_id: {"entity_id": plages.entity_id, "state": plages.state,
                                                  "attributes": json.loads(json.dumps(dict(plages.attributes), default=str))}}
        (dossier / "planning.json").write_text(json.dumps(planning, ensure_ascii=False), encoding="utf-8")
        # Refonte lot 3 : la fiche, avec les données de ses cartes intégrées (personne, planning, widgets).
        r = await client.get("/api/pc_parental/personne/admin", params={"personne": "person.lou"})
        assert r.status == 200, await r.text()
        fiche = dict(planning)
        for nom in ("maison", "widgets"):
            fiche.update(json.loads((dossier / f"{nom}.json").read_text(encoding="utf-8")))
        fiche["pc_parental/personne/admin"] = await r.json()
        fiche["pc_parental/personne/admin"]["planning"] = plages.entity_id
        (dossier / "fiche.json").write_text(json.dumps(fiche, ensure_ascii=False), encoding="utf-8")
        # Refonte lot 4 : les écrans de l'assistant et de Configurer réduit (maquette du dialogue).
        (dossier / "flux.json").write_text(json.dumps(_flux(hass, langue), ensure_ascii=False), encoding="utf-8")
    hass.config.language = "fr"
    m = json.loads((tmp_path / "donnees" / "de" / "maison.json").read_text(encoding="utf-8"))["pc_parental/maison"]
    assert m["demandes"], "les demandes d'essai n'apparaissent pas"
    assert len(m.get("a_decider_lots") or []) >= 2, "les lots « À décider » d'essai n'apparaissent pas"

    shutil.copy(ICI / "clics" / "debordement.html", tmp_path / "debordement.html")
    for f in (RACINE / "frontend").glob("vision-*.js"):
        shutil.copy(f, tmp_path / f.name)
    shutil.copy(RACINE / "panneau" / "vision-appairage-panel.js", tmp_path / "vision-appairage-panel.js")
    shutil.copy(ICI / "clics" / "vision-flux-maquette.js", tmp_path / "vision-flux-maquette.js")

    if os.environ.get("VISION_DEBORDEMENT_GARDER"):  # le banc préparé, pour relancer debordement.mjs seul
        shutil.copytree(tmp_path, os.environ["VISION_DEBORDEMENT_GARDER"], dirs_exist_ok=True)
    captures = pathlib.Path(os.environ.get("VISION_CAPTURES") or (CAPTURES_DEFAUT if CAPTURES_DEFAUT.parent.exists() else tmp_path / "captures"))
    options: dict = {}
    if os.environ.get("VISION_DEBORDEMENT_CARTES"):
        options["cartes"] = [c.strip() for c in os.environ["VISION_DEBORDEMENT_CARTES"].split(",") if c.strip()]
    if os.environ.get("VISION_DEBORDEMENT_RAPIDE") == "1":
        options["rapide"] = True
    proc = await asyncio.create_subprocess_exec(node, str(ICI / "clics" / "debordement.mjs"), str(tmp_path), nav, str(captures),
                                                json.dumps(options), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    sortie, erreurs = await asyncio.wait_for(proc.communicate(), timeout=3600)
    texte = sortie.decode("utf-8", "replace")
    lignes = [json.loads(x) for x in texte.splitlines() if x.startswith("{")]
    fin = next((x for x in lignes if x.get("fin")), {"defauts": "?", "erreur": erreurs.decode("utf-8", "replace")[-500:]})
    rapport = _rapport(lignes, fin)
    captures.mkdir(parents=True, exist_ok=True)
    (captures / "recette-lot1-ha-rapport.txt").write_text(rapport, encoding="utf-8")
    (captures / "recette-lot1-ha-rapport.json").write_text(json.dumps(lignes, ensure_ascii=False, indent=1), encoding="utf-8")
    assert len([x for x in lignes if "carte" in x]) >= (2 if options else 11), rapport
    assert fin.get("defauts") == 0, rapport
