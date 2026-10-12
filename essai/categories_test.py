"""Essai des catégories cochées (1.60.0), sans Home Assistant.

Les méthodes sont sorties de store.py par leur nom et posées sur un magasin de carton, avec un
faux registre d'étiquettes. On vérifie : les cases cochées d'office, que décocher ne retire rien
de ce qui s'applique (et que c'est dit), les refus des plages et l'endroit où cocher, le stockage
des seuls écarts au défaut, le texte de la carte (rendu par Node), et qu'android_maj ne lit plus
aucun fichier dans la boucle d'événements.

python3 essai/categories_test.py   (demande node pour la partie carte)
"""
import ast, json, os, pathlib, shutil, subprocess, sys, tempfile, textwrap, types

racine = pathlib.Path(__file__).resolve().parent.parent
pc_parental = racine / "custom_components" / "pc_parental"


def sortir(fichier, noms, classe=None):
    src = (pc_parental / fichier).read_text(encoding="utf-8")
    arbre = ast.parse(src)
    noeuds = arbre.body if not classe else next(
        n for n in arbre.body if isinstance(n, ast.ClassDef) and n.name == classe).body
    trouves = {}
    for n in noeuds:
        nom = n.name if isinstance(n, ast.FunctionDef) else (
            getattr(n.targets[0], "id", "") if isinstance(n, ast.Assign) else "")
        if nom in noms:
            trouves[nom] = textwrap.dedent(ast.get_source_segment(src, n))
    assert set(trouves) == set(noms), set(noms) - set(trouves)
    return list(trouves.values()), src


# Un module « labels » de carton : le vrai pour ce qui est pur, un faux registre pour le reste.
REGISTRE, SUR_APPAREILS = [], {}
faux = types.ModuleType("labels")
corps, src_labels = sortir("labels.py", ["CATEGORIES_LUES_PAR_LE_CODE", "details_proposes", "proposees"])
faux.__dict__["HomeAssistant"] = object
exec("\n\n".join(corps), faux.__dict__)
faux.details = lambda hass: [dict(e) for e in REGISTRE]
faux.vocabulaire = lambda hass: [e["nom"] for e in REGISTRE]
faux.noms_du_pc = lambda hass, pc_id: list(SUR_APPAREILS.get(pc_id, []))

METHODES = ["_porteuses", "categorie_par_defaut", "categorie_cochee", "categories_cochees",
            "usages_etiquette", "etiquettes_visibles", "choisir_categories", "verifier_etiquettes_plage"]
corps, src_store = sortir("store.py", METHODES, "PcStore")
# Les textes de Vision (pc_parental/textes.py et langues.py), chargés seuls, sans le reste de l'intégration.
import importlib  # noqa: E402
_paquet = types.ModuleType("vision_textes")
_paquet.__path__ = [str(pc_parental)]
sys.modules["vision_textes"] = _paquet
espace = {"Any": object, "labels": faux, "textes": importlib.import_module("vision_textes.textes")}
exec("class Magasin:\n" + textwrap.indent("\n\n".join(corps), "    "), espace)


def registre(*noms):
    REGISTRE[:] = [{"id": n.lower().replace(" ", "_"), "nom": n, "icone": "", "couleur": ""} for n in noms]


def magasin():
    m = espace["Magasin"]()
    m.hass = None
    m.etiquettes = {"apps": {"steam": ["Jeux"], "com.google.android.youtube.tv": ["Vidéos"]},
                    "sites": {"youtube.com": ["Vidéos"]}}
    m.bannis, m.autorises_maison, m.categories_choix = [], [], {}
    m.fermetures = {"person.lou": {"tv|": {"etiquette": "TV", "source": ""}}}
    m.pcs = {"pc": {"id": "pc", "name": "PC-LOU", "rules": [], "bloques": [], "autorises": []}}
    return m


rates = 0
def verifier(quoi, condition):
    global rates
    rates += 0 if condition else 1
    print(("ok   " if condition else "RATE ") + quoi)


def refus(m, voulues, deja=()):
    try:
        m.verifier_etiquettes_plage(voulues, list(deja))
        return ""
    except ValueError as e:
        return str(e)


registre("Jeux", "Vidéos", "TV", "Chauffage", "Parents", "Enfants", "Toujours autorisé", "Radiateur")
m = magasin()
coch = m.categories_cochees()

# 1. Les cases cochées d'office.
verifier("cochées d'office : celles qui rangent des éléments", {"jeux", "vidéos"} <= coch)
verifier("cochées d'office : Parents, Enfants, Toujours autorisé", {"parents", "enfants", "toujours autorisé"} <= coch)
verifier("décochées : Chauffage, Radiateur, et TV qui ne range rien", not ({"chauffage", "radiateur", "tv"} & coch))

# 2. Décocher ne retire rien de ce qui s'applique, et c'est dit.
vis = m.etiquettes_visibles()
verifier("TV, décochée mais utilisée, reste visible (interrupteurs gardés)", "tv" in vis)
u = m.usages_etiquette("TV")
verifier("TV dit où elle s'applique encore", u == ["une automatisation la tient fermée pour Lou"])
verifier("Chauffage, libre, n'est plus visible", "chauffage" not in vis)
m.pcs["pc"]["rules"] = [{"name": "Ecole", "etiquettes": ["Jeux", "Chauffage"]}]
verifier("une étiquette encore dans une plage reste visible", "chauffage" in m.etiquettes_visibles())
verifier("et la plage est nommée", "la plage « Ecole » de PC-LOU la ferme" in m.usages_etiquette("Chauffage"))
m.pcs["pc"]["rules"] = []
SUR_APPAREILS["pc"] = ["Radiateur"]
verifier("une étiquette posée sur un appareil reste visible, et c'est dit",
         "radiateur" in m.etiquettes_visibles() and "elle est posée sur l'appareil PC-LOU" in m.usages_etiquette("Radiateur"))
SUR_APPAREILS.clear()
m.choisir_categories({"Jeux": False})
verifier("décocher Jeux, qui range des éléments : elle reste visible", "jeux" in m.etiquettes_visibles()
         and "jeux" not in m.categories_cochees())
m.choisir_categories({"Jeux": True})

# 3. Stockage : seuls les écarts au défaut, par identifiant.
m.choisir_categories({"Chauffage": True})
verifier("cocher Chauffage : un écart gardé, par identifiant", m.categories_choix == {"chauffage": True})
m.choisir_categories({"Chauffage": False})
verifier("la décocher : retour au défaut, plus rien de gardé", m.categories_choix == {})
m.choisir_categories({"Jeux": True})
verifier("cocher ce qui l'est d'office : rien de gardé", m.categories_choix == {})
try:
    m.choisir_categories({"Inconnue": True}); verifier("une étiquette inexistante est refusée", False)
except ValueError:
    verifier("une étiquette inexistante est refusée", True)

# 4. Les plages : refus, et où cocher.
verifier("ajouter Jeux : accepté, au nom exact", m.verifier_etiquettes_plage(["jeux"], []) == ["Jeux"])
r = refus(m, ["Chauffage"])
verifier("ajouter Chauffage : refusé, et le message dit où cocher",
         "n'est pas cochée" in r and "Autorisations → Étiquettes" in r and "Catégorie Vision" in r)
r = refus(m, ["Inexistante"])
verifier("ajouter une étiquette inexistante : refusé sans la créer, et où la créer",
         "n'existe pas" in r and "Créer" in r and "Inexistante" not in faux.vocabulaire(None))
verifier("une plage qui porte déjà Chauffage la garde", m.verifier_etiquettes_plage(["Jeux", "Chauffage"], ["Jeux", "Chauffage"]) == ["Jeux", "Chauffage"])
m.choisir_categories({"Chauffage": True})
verifier("une fois cochée, Chauffage est acceptée", m.verifier_etiquettes_plage(["Chauffage"], []) == ["Chauffage"])
m.choisir_categories({"Chauffage": False})

# 5. Le magasin vérifie à la création comme à la modification ; la carte ne trie plus en silence.
for fonction in ("add_rule", "update_rule"):
    corps = next(ast.get_source_segment(src_store, x) for x in ast.walk(ast.parse(src_store))
                 if isinstance(x, ast.FunctionDef) and x.name == fonction)
    verifier(f"{fonction} vérifie les étiquettes", "verifier_etiquettes_plage" in corps)
verifier("la carte des parents ne trie plus en silence",
         "connues.get(str(e).strip().lower())" not in (pc_parental / "parent.py").read_text(encoding="utf-8"))

# 6. Le texte de la carte : rendu par Node à partir de la vraie méthode.
carte = (racine / "custom_components" / "pc_parental" / "frontend" / "vision-etiquettes-card.js").read_text(encoding="utf-8")
debut = carte.index("  _etatCategorie(e) {")
fin = carte.index("\n  }\n", debut) + 4
methode = carte[debut:fin]
if shutil.which("node"):
    cas = {
        "jeux": {"categorie": True, "defaut": True, "apps": 1, "sites": 0, "usages": []},
        "code": {"categorie": True, "defaut": True, "lue_par_le_code": True, "apps": 0, "sites": 0, "usages": []},
        "tv": {"categorie": False, "defaut": False, "apps": 0, "sites": 0,
               "usages": ["une automatisation la tient fermée pour Lou"]},
        "chauffage": {"categorie": False, "defaut": False, "apps": 0, "sites": 0, "usages": []},
        "jeux_decochee": {"categorie": False, "defaut": True, "apps": 167, "sites": 0, "usages": []},
    }
    # La méthode lit les textes de la carte (tr) : son bloc i18n passe d'abord, en français ; le script passe par
    # l'entrée standard (node -e dépasserait la limite de la ligne de commande sous Windows).
    js = carte[carte.index("// ---- i18n:debut"):carte.index("// ---- i18n:fin")] \
        + "suivreLangue({ language: 'fr' });\n" + "const o={" + methode.replace("  _etatCategorie(e) {", "_etatCategorie(e) {", 1) + "};" \
         "const cas=" + json.dumps(cas, ensure_ascii=False) + ";" \
         "const r={};for(const k in cas){r[k]=o._etatCategorie(cas[k]);}" \
         "process.stdout.write(JSON.stringify(r));"
    sortie = json.loads(subprocess.run(["node", "-"], input=js, capture_output=True, text=True, encoding="utf-8", check=True).stdout)
    tv = sortie["tv"]
    print("     texte affiché pour TV :", tv["texte"])
    verifier("TV : affichée « décochée, mais elle compte encore », avec où",
             tv["classe"] == "cat-encore" and tv["texte"].startswith("Décochée, mais elle compte encore")
             and "une automatisation la tient fermée pour Lou" in tv["texte"] and "retire-la de ces endroits" in tv["texte"])
    verifier("Chauffage : « pas une catégorie », grisée", sortie["chauffage"]["classe"] == "cat-non")
    verifier("Jeux : cochée d'office, avec la raison", "cochée d'office" in sortie["jeux"]["texte"] and "range 1" in sortie["jeux"]["texte"])
    verifier("Parents/Enfants : « Vision s'en sert lui-même »", "s'en sert lui-même" in sortie["code"]["texte"])
    verifier("Jeux décochée : dit qu'elle range encore 167 éléments", sortie["jeux_decochee"]["classe"] == "cat-encore"
             and "167" in sortie["jeux_decochee"]["texte"])
else:
    verifier("node disponible pour vérifier le texte de la carte", False)

# 7. android_maj : aucune lecture de fichier dans la boucle (repris de la 1.60.0 abandonnée).
src_a = (pc_parental / "android_maj.py").read_text(encoding="utf-8")
arbre = ast.parse(src_a)
for fonction in ("infos", "infos_pour", "fichier"):
    f = next(x for x in arbre.body if isinstance(x, ast.FunctionDef) and x.name == fonction)
    appels = {ast.unparse(c.func) for c in ast.walk(f) if isinstance(c, ast.Call)}
    verifier(f"android_maj.{fonction} ne touche pas au disque", not ({"open", "os.path.isfile", "_lire", "json.load"} & appels))
module = types.ModuleType("android_maj")
module.__file__ = str(pc_parental / "android_maj.py")
sys.modules.setdefault("homeassistant", types.ModuleType("homeassistant"))
sys.modules["homeassistant.core"] = types.SimpleNamespace(HomeAssistant=object)
exec(compile(src_a, "android_maj.py", "exec"), module.__dict__)
with tempfile.TemporaryDirectory() as d:
    module.DOSSIER = d
    for nom, contenu in (("vision.apk", "x"), ("essai.apk", "y"),
                         ("vision.json", json.dumps({"version": "1.48.0", "sha256": "AB"})),
                         ("essai.json", json.dumps({"version": "1.49.0", "sha256": "cd", "appareils": ["Google Pixel 9"]}))):
        pathlib.Path(d, nom).write_text(contenu, encoding="utf-8")
    module._ETAT.update(module._lire_tout(os.path.join(d, "absent.json")))
    verifier("android : version générale lue", module.infos(None)["version"] == "1.48.0")
    verifier("android : l'appareil d'essai reçoit l'essai", module.infos_pour(None, "google pixel 9")["version"] == "1.49.0")
    verifier("android : les autres reçoivent la générale", module.infos_pour(None, "Pixel 8a")["version"] == "1.48.0")
    verifier("android : un nom hors liste n'est pas servi", module.fichier("../secret") is None)
print(rates, "raté(s)")
