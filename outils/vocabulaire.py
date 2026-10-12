"""« PC » devient « appareil » dans tout ce que l'utilisateur lit : Vision gère des téléphones,
des télés et des ordinateurs. Les commentaires, les noms de variables et les clés des services
ne bougent pas ; ce qui ne concerne vraiment que Windows (installation par PowerShell, DNS,
programmes au démarrage) garde « PC »."""
import ast, glob, io, re, sys, tokenize

REGLES = [
    (r"l'appareil de ce PC", "cet appareil"),
    (r"\bTous les PC\b", "Tous les appareils"), (r"\btous les PC\b", "tous les appareils"),
    (r"\bnouveaux PC\b", "nouveaux appareils"), (r"\bNouveau PC\b", "Nouvel appareil"), (r"\bnouveau PC\b", "nouvel appareil"),
    (r"\bces PC\b", "ces appareils"), (r"\bles PC\b", "les appareils"), (r"\bdes PC\b", "des appareils"),
    (r"\bAucun PC\b", "Aucun appareil"), (r"\baucun PC\b", "aucun appareil"),
    (r"\bChaque PC\b", "Chaque appareil"), (r"\bchaque PC\b", "chaque appareil"),
    (r"\bCe PC\b", "Cet appareil"), (r"\bce PC\b", "cet appareil"),
    (r"\bUn PC\b", "Un appareil"), (r"\bun PC\b", "un appareil"),
    (r"\bLe PC\b", "L'appareil"), (r"\ble PC\b", "l'appareil"),
    (r"\bdu PC\b", "de l'appareil"), (r"\bau PC\b", "à l'appareil"),
    (r"\bPC connus\b", "Appareils connus"),
    (r"\*\*PC\*\*", "**Appareil**"),
    (r"\bPC\b", "Appareil"),
]
# Lignes laissées telles quelles : elles parlent bien d'un ordinateur sous Windows.
GARDE = {
    "dashboard.py": re.compile(r"PowerShell|Installer un PC|PC à équiper|PC sont équipés|PC équipés|traîner sur le PC|apparaît ici tout seul|resté en \*\*1\.0\.0|depuis un PC de la maison|cartes réseau|se met à jour tout seul|rien ne change sur un PC|fermé sur le PC d'un enfant|Le PC décide"),
    "text.py": re.compile(r"install\.ps1|Le PC \""),
    "select.py": re.compile(r"Filtre DNS|Base des listes"),
    "processus.py": re.compile(r"."),
}

def traduire(texte: str, delim: str) -> str:
    for motif, neuf in REGLES:
        if "'" in neuf and delim == "'":
            neuf = neuf.replace("'", "’")
        texte = re.sub(motif, neuf, texte)
    return texte

def python(chemin: str) -> int:
    src = open(chemin, encoding="utf-8").read()
    nom = chemin.split("/")[-1]
    garde = GARDE.get(nom)
    doc = set()
    for n in ast.walk(ast.parse(src)):
        if isinstance(n, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and n.body and isinstance(n.body[0], ast.Expr) and isinstance(getattr(n.body[0], "value", None), ast.Constant) and isinstance(n.body[0].value.value, str):
            doc.add(n.body[0].lineno)
    lignes = src.splitlines(keepends=True)
    faits = 0
    remplacements = []
    for t in tokenize.generate_tokens(io.StringIO(src).readline):
        if t.type not in (tokenize.STRING, getattr(tokenize, "FSTRING_MIDDLE", -1)):
            continue
        if t.start[0] in doc or not re.search(r"\bPCs?\b", t.string):
            continue
        if garde and garde.search(t.string):
            continue
        if t.start[0] != t.end[0]:
            continue  # chaîne sur plusieurs lignes : à la main
        delim = "'" if (t.type == tokenize.STRING and t.string.lstrip("rbfuRBFU")[:1] == "'") else '"'
        if t.type != tokenize.STRING:
            # morceau de f-string : on regarde le guillemet qui ouvre la f-string sur la ligne
            avant = lignes[t.start[0] - 1][: t.start[1]]
            delim = "'" if avant.rfind("f'") > avant.rfind('f"') else '"'
        remplacements.append((t.start[0], t.start[1], t.end[1], traduire(t.string, delim)))
    for ligne, debut, fin, neuf in sorted(remplacements, reverse=True):
        l = lignes[ligne - 1]
        if l[debut:fin] != neuf:
            lignes[ligne - 1] = l[:debut] + neuf + l[fin:]
            faits += 1
    if faits:
        open(chemin, "w", encoding="utf-8").write("".join(lignes))
    return faits

if __name__ == "__main__":
    total = 0
    for f in sorted(glob.glob(sys.argv[1] + "/*.py")):
        n = python(f)
        if n:
            print(f.split("/")[-1], n)
        total += n
    print("total", total)
