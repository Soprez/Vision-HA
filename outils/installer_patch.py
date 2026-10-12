"""Applique un patch fabriqué par fabriquer_patch.py, dans Home Assistant.

Tout ou rien : chaque fichier est vérifié au départ et à l'arrivée (sha256) avant la première
écriture. Les fichiers remplacés sont gardés dans <sauvegardes>/<nom du patch>/.

Usage : python3 installer_patch.py <patch.json> [racine de la configuration]
"""
import hashlib, json, os, shutil, sys

patch = sys.argv[1]
racine = sys.argv[2] if len(sys.argv) > 2 else "/homeassistant"
sha = lambda b: hashlib.sha256(b).hexdigest()
plan = []
deja = 0
for f in json.load(open(patch, encoding="utf-8"))["fichiers"]:
    chemin = os.path.join(racine, f["cible"])
    actuel = open(chemin, "rb").read() if os.path.exists(chemin) else None
    if actuel is not None and sha(actuel) == f["arrivee"]:
        deja += 1
        continue
    if f["depart"] is None:
        neuf = f["contenu"].encode("utf-8")
    else:
        if actuel is None or sha(actuel) != f["depart"]:
            sys.exit("REFUS : {} n'est pas dans l'état attendu, rien n'a été écrit.".format(f["cible"]))
        texte = actuel.decode("utf-8")
        for ancien, nouveau in f["morceaux"]:
            if texte.count(ancien) != 1:
                sys.exit("REFUS : morceau introuvable dans {}, rien n'a été écrit.".format(f["cible"]))
            texte = texte.replace(ancien, nouveau)
        neuf = texte.encode("utf-8")
    if sha(neuf) != f["arrivee"]:
        sys.exit("REFUS : {} n'arrive pas à l'empreinte prévue, rien n'a été écrit.".format(f["cible"]))
    plan.append((f["cible"], chemin, actuel, neuf))
garde = os.path.join(os.path.dirname(os.path.abspath(patch)), "sauvegardes", os.path.splitext(os.path.basename(patch))[0])
for cible, chemin, actuel, neuf in plan:
    if actuel is not None:
        copie = os.path.join(garde, cible)
        os.makedirs(os.path.dirname(copie), exist_ok=True)
        if not os.path.exists(copie):
            with open(copie, "wb") as s:
                s.write(actuel)
    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    tmp = chemin + ".tmp-patch"
    with open(tmp, "wb") as s:
        s.write(neuf)
    os.replace(tmp, chemin)
print("OK : {} fichier(s) écrit(s), {} déjà à jour. Sauvegardes : {}".format(len(plan), deja, garde))
