"""Essai du catalogue : le verdict « désinstaller », sans Home Assistant.

python3 essai/desinstall_catalogue_test.py
"""
import importlib
import pathlib
import sys
import types

# Le module lit ses textes dans textes.py (i18n) : on le charge dans un paquet de carton, sans Home Assistant.
_paquet = types.ModuleType("vision_seul")
_paquet.__path__ = [str(pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental")]
sys.modules["vision_seul"] = _paquet
m = importlib.import_module("vision_seul.processus_catalogue")

rates = 0


def verifier(quoi, obtenu, attendu):
    global rates
    if obtenu == attendu:
        print("ok   ", quoi)
    else:
        rates += 1
        print("RATE ", quoi, ": obtenu", repr(obtenu), "attendu", repr(attendu))


def leve(f):
    try:
        f()
    except (ValueError, KeyError) as e:
        return str(e)
    return ""


c = m.Catalogue()
releve = [
    {"g": "processus", "id": "nortonui", "nom": "NortonUI", "exe": "nortonui", "chemin": r"C:\Program Files\Norton\Suite\NortonUI.exe", "signataire": "Gen Digital Inc.", "editeur": "Gen Digital Inc."},
    {"g": "processus", "id": "vgc", "nom": "vgc", "exe": "vgc", "chemin": r"C:\Program Files\Riot Vanguard\vgc.exe", "signataire": "Riot Games, Inc.", "editeur": "Riot Games"},
    {"g": "processus", "id": "msedge", "nom": "msedge", "exe": "msedge", "chemin": r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", "signataire": "Microsoft Corporation", "editeur": "Microsoft Corporation"},
    {"g": "service", "id": "nvcontainer", "nom": "NVIDIA Container", "exe": "nvcontainer", "chemin": r"C:\Program Files\NVIDIA Corporation\NvContainer\nvcontainer.exe", "signataire": "NVIDIA Corporation", "editeur": "NVIDIA Corporation"},
    {"g": "processus", "id": "agent", "nom": "agent", "exe": "powershell_x", "chemin": r"C:\ProgramData\HAParental\x.exe", "signataire": "", "editeur": ""},
    {"g": "processus", "id": "sanschemin", "nom": "sanschemin", "exe": "sanschemin", "chemin": "", "signataire": "", "editeur": ""},
]
print("retenus :", c.noter("pc1", releve, 1000.0))
c.noter("pc2", releve[:1], 1000.0)
cles = {f["id"]: k for k, f in c.elements.items()}
print("champs :", sorted(c.elements[cles["nortonui"]].keys()))

verifier("Norton : désinstallable", m.raison_non_desinstallable(c.elements[cles["nortonui"]]), "")
for ident in ("vgc", "msedge", "nvcontainer", "agent", "sanschemin"):
    verifier(f"{ident} : refusé", bool(m.raison_non_desinstallable(c.elements[cles[ident]])), True)
    verifier(f"{ident} : decider lève", bool(leve(lambda i=ident: c.decider(cles[i], "desinstaller", 1001.0))), True)
verifier("proposer : refusé", bool(leve(lambda: c.proposer(cles["nortonui"], "desinstaller", "x", 90, "classeur", 1001.0))), True)

f = c.decider(cles["nortonui"], "desinstaller", 1002.0)
jeton = f["decision"]["jeton"]
verifier("jeton posé", len(jeton), 12)
verifier("interrupteur éteint : rien", "desinstaller" in c.consignes("pc1", False), False)
k = c.consignes("pc1", True)
verifier("interrupteur allumé : une consigne", [d["cle"] for d in k.get("desinstaller", [])], [cles["nortonui"]])
verifier("pas dans les nuisibles", "nortonui" in k["arreter"], False)
verifier("pc sans le logiciel : rien", "desinstaller" in c.consignes("pc3", True), False)
verifier("mauvais jeton ignoré", c.noter_desinstallations("pc1", [{"cle": cles["nortonui"], "jeton": "faux", "etat": "fait"}]), False)
verifier("compte rendu noté", c.noter_desinstallations("pc1", [{"cle": cles["nortonui"], "jeton": jeton, "etat": "echec", "detail": "x", "produit": "Norton 360", "date": 1003}]), True)
verifier("même compte rendu : rien de neuf", c.noter_desinstallations("pc1", [{"cle": cles["nortonui"], "jeton": jeton, "etat": "echec", "detail": "x", "produit": "Norton 360", "date": 1003}]), False)
verifier("plus de consigne pour pc1", "desinstaller" in c.consignes("pc1", True), False)
c.noter("pc1", releve, 2000.0)
verifier("un nouvel inventaire ne relance pas", "desinstaller" in c.consignes("pc1", True), False)
verifier("toujours pour pc2", len(c.consignes("pc2", True).get("desinstaller", [])), 1)
vue = c.lister("tous", 100, {"pc1": "PC 1", "pc2": "PC 2"})
ligne = [e for e in vue["elements"] if e["cle"] == cles["nortonui"]][0]
verifier("lister : compte rendu par appareil", [p.get("desinstall", {}) and p["desinstall"]["etat"] for p in ligne["pcs"] if p["pc"] == "PC 1"], ["echec"])
verifier("lister : raison de refus", bool([e for e in vue["elements"] if e["cle"] == cles["vgc"]][0]["non_desinstallable"]), True)
# Redécider (nouvel essai) : nouveau jeton, la consigne repart.
f = c.decider(cles["nortonui"], "desinstaller", 1004.0)
verifier("redécider : nouveau jeton", f["decision"]["jeton"] != jeton, True)
verifier("redécider : consigne repart", len(c.consignes("pc1", True).get("desinstaller", [])), 1)
c.decider(cles["nortonui"], "neutre", 1005.0)
verifier("défaire : plus de consigne", "desinstaller" in c.consignes("pc1", True), False)
# --- Logiciels installés, et désinstallation sur un seul appareil
log = [
    {"g": "logiciel", "id": "norton 360", "nom": "Norton 360", "editeur": "Gen Digital Inc.", "chemin": r"C:\Program Files\Norton\Suite", "exes": "nortonui,nortonsvc", "version": "25.1"},
    {"g": "logiciel", "id": "microsoft edge", "nom": "Microsoft Edge", "editeur": "Microsoft Corporation"},
    {"g": "logiciel", "id": "riot vanguard", "nom": "Riot Vanguard", "editeur": "Riot Games, Inc."},
    {"g": "logiciel", "id": "jeu sans chemin", "nom": "Jeu sans chemin", "editeur": "Petit Studio"},
]
c.noter("pc1", log, 3000.0); c.noter("pc2", log[:1], 3000.0)
kn = "logiciel|norton 360"
verifier("logiciel : pas dans la file à classer", c.etat(c.elements[kn]), "logiciels")
verifier("logiciel : compté à part", c.compter()["logiciels"], 4)
verifier("logiciel sans chemin : désinstallable", m.raison_non_desinstallable(c.elements["logiciel|jeu sans chemin"]), "")
verifier("logiciel Microsoft : refusé", bool(m.raison_non_desinstallable(c.elements["logiciel|microsoft edge"])), True)
verifier("logiciel anti-triche : refusé", bool(m.raison_non_desinstallable(c.elements["logiciel|riot vanguard"])), True)
verifier("logiciel : « nuisible » n'a pas de sens", bool(leve(lambda: c.decider(kn, "nuisible", 3001.0))), True)
vue = c.logiciels_de("pc1")
verifier("logiciels de pc1", [x["nom"] for x in vue], ["Jeu sans chemin", "Microsoft Edge", "Norton 360", "Riot Vanguard"])
verifier("logiciels : programmes connus", [x["exes"] for x in vue if x["nom"] == "Norton 360"], [["nortonui", "nortonsvc"]])
c.decider(kn, "desinstaller", 3002.0, pcs=["pc1"])
verifier("un seul appareil : pc1 visé", len(c.consignes("pc1", True).get("desinstaller", [])), 1)
verifier("un seul appareil : pc2 épargné", "desinstaller" in c.consignes("pc2", True), False)
j1 = c.jeton_pour(c.elements[kn], "pc1")
verifier("compte rendu de pc1", c.noter_desinstallations("pc1", [{"cle": kn, "jeton": j1, "etat": "fait"}]), True)
verifier("compte rendu de pc2 refusé", c.noter_desinstallations("pc2", [{"cle": kn, "jeton": j1, "etat": "fait"}]), False)
c.decider(kn, "desinstaller", 3003.0, pcs=["pc2"])
verifier("puis pc2 : pc1 n'est pas relancé", "desinstaller" in c.consignes("pc1", True), False)
verifier("puis pc2 : pc2 visé", len(c.consignes("pc2", True).get("desinstaller", [])), 1)
verifier("appareil qui ne l'a pas : refusé", bool(leve(lambda: c.decider(kn, "desinstaller", 3004.0, pcs=["pc9"]))), True)
c.noter("pc1", log[1:], 9000.0)
verifier("désinstallé : reste visible avec son compte rendu", [x["retour"]["etat"] for x in c.logiciels_de("pc1") if x["nom"] == "Norton 360"], ["fait"])
c.decider(kn, "garder", 9001.0)
verifier("disparu et sans demande : plus montré", [x["nom"] for x in c.logiciels_de("pc1") if x["nom"] == "Norton 360"], [])
print(rates, "raté(s)")
sys.exit(1 if rates else 0)
