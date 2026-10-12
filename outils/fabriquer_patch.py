"""Fabrique un patch pour Home Assistant à partir de deux états du dépôt.

Le patch ne publie que les lignes changées et un peu de contexte, pas les fichiers entiers.
Il porte l'empreinte du fichier de départ et celle du fichier d'arrivée : l'installeur
refuse tout fichier qui ne correspond pas, et n'écrit rien tant que tout n'est pas vérifié.

Usage : python3 outils/fabriquer_patch.py <commit de base> <sortie.json> chemin_depot=chemin_ha ...
"""
import difflib, hashlib, json, subprocess, sys

base, sortie, couples = sys.argv[1], sys.argv[2], sys.argv[3:]
sha = lambda b: hashlib.sha256(b).hexdigest()
fichiers = []
for couple in couples:
    depot, cible = couple.split("=", 1)
    neuf_b = open(depot, "rb").read()
    try:
        vieux_b = subprocess.run(["git", "show", f"{base}:{depot}"], capture_output=True, check=True).stdout
    except subprocess.CalledProcessError:
        vieux_b = None
    if vieux_b is None:
        fichiers.append({"cible": cible, "depart": None, "arrivee": sha(neuf_b), "contenu": neuf_b.decode("utf-8")})
        continue
    if vieux_b == neuf_b:
        continue
    vieux, neuf = vieux_b.decode("utf-8"), neuf_b.decode("utf-8")
    a, b = vieux.splitlines(keepends=True), neuf.splitlines(keepends=True)
    morceaux = []
    for groupe in difflib.SequenceMatcher(None, a, b, autojunk=False).get_grouped_opcodes(3):
        i1, i2, j1, j2 = groupe[0][1], groupe[-1][2], groupe[0][3], groupe[-1][4]
        # Le texte remplacé doit être unique dans le fichier : on élargit le contexte tant qu'il ne l'est pas.
        while vieux.count("".join(a[i1:i2])) != 1 and (i1 > 0 or i2 < len(a)):
            if i1 > 0: i1 -= 1; j1 -= 1
            if i2 < len(a): i2 += 1; j2 += 1
        morceaux.append(["".join(a[i1:i2]), "".join(b[j1:j2])])
    # Vérification : rejouer le patch redonne exactement le nouveau fichier.
    essai = vieux
    for ancien, nouveau in morceaux:
        assert essai.count(ancien) == 1, (depot, ancien[:80])
        essai = essai.replace(ancien, nouveau)
    assert essai.encode("utf-8") == neuf_b, depot
    fichiers.append({"cible": cible, "depart": sha(vieux_b), "arrivee": sha(neuf_b), "morceaux": morceaux})
json.dump({"fichiers": fichiers}, open(sortie, "w", encoding="utf-8"), ensure_ascii=False)
for f in fichiers:
    print(f["cible"], "nouveau" if f["depart"] is None else f"{len(f['morceaux'])} morceaux")
