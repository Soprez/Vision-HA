"""Essai des primitives des automatisations (1.60.0) : fermer, rouvrir, lire, par source.

Sans Home Assistant : les méthodes sont sorties de store.py par leur nom, ainsi que le bloc
qui relit les fermetures enregistrées (pour vérifier la reprise de l'ancien format).

python3 essai/fermetures_test.py
"""
import ast, pathlib, textwrap, time

src = (pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental" / "store.py").read_text(encoding="utf-8")
arbre = ast.parse(src)
voulues = {"fermer_categorie", "ouvrir_categorie", "fermetures_de", "fermeture_de"}
corps, cle_fermeture = [], None
for n in arbre.body:
    if isinstance(n, ast.FunctionDef) and n.name == "_cle_fermeture":
        cle_fermeture = ast.get_source_segment(src, n)
    if isinstance(n, ast.ClassDef) and n.name == "PcStore":
        for m in n.body:
            if isinstance(m, ast.FunctionDef) and m.name in voulues:
                corps.append(textwrap.indent(textwrap.dedent(ast.get_source_segment(src, m)), "    "))
assert cle_fermeture and len(corps) == len(voulues)
espace = {"Any": object, "time": time}
exec(cle_fermeture, espace)
exec("class Magasin:\n" + "\n".join(corps), espace)

# Le bloc de chargement des fermetures, tel quel.
debut = src.index("        self.fermetures = {}\n")
fin = src.index("            self.fermetures[str(personne)] = rangees\n") + len("            self.fermetures[str(personne)] = rangees\n")
chargement = textwrap.dedent(src[debut:fin])

rates = 0
def verifier(quoi, condition):
    global rates
    rates += 0 if condition else 1
    print(("ok   " if condition else "RATE ") + quoi)


def charger(donnees):
    m = espace["Magasin"]()
    ns = {"data": {"fermetures": donnees}, "self": m, "Any": object, "_cle_fermeture": espace["_cle_fermeture"]}
    exec(chargement, ns)
    return m


LOU, PC = "person.lou", {"person": "person.lou"}

# 1. Reprise de l'ancien format : clés « jeux », source = identifiant d'une exécution (aléatoire).
ancien = {LOU: {c.lower(): {"etiquette": c, "raison": "Fermé tant que ta moyenne est sous 12 (elle est à 9,47). Ça rouvrira avec les notes.",
                              "source": "01K6TQ8Z4XJ3R0Y9ABCDEF1234", "depuis": 1791218059}
                  for c in ("Jeux", "Vidéos", "Réseaux sociaux", "TV")}}
m = charger(ancien)
verifier("reprise : les 4 fermetures de Lou sont là", len(m.fermetures_de(LOU)) == 4)
verifier("reprise : la source aléatoire devient la source vide", all(f["source"] == "" for f in m.fermetures_de(LOU)))
verifier("reprise : « depuis » et la raison sont gardés",
         all(f["depuis"] == 1791218059 and "9,47" in f["raison"] for f in m.fermetures_de(LOU)))

# 2. L'automatisation de la maison, telle quelle (sans source) : même raison → rien ne change ; elle rouvre bien.
raison = ancien[LOU]["jeux"]["raison"]
verifier("L'automatisation referme avec la même raison : rien ne change",
         not any(m.fermer_categorie(LOU, c, raison) for c in ("Jeux", "Vidéos", "Réseaux sociaux", "TV")))
verifier("fermé pendant ce temps", m.fermeture_de(PC, ["Vidéos"]) is not None)
verifier("L'automatisation rouvre (sans source) : les 4 se lèvent",
         sum(m.ouvrir_categorie(LOU, c) for c in ("Jeux", "Vidéos", "Réseaux sociaux", "TV")) == 4
         and m.fermeture_de(PC, ["Vidéos"]) is None)

# 3. Deux sources sur la même catégorie : chacune ne lève que la sienne.
m = charger({})
m.fermer_categorie(LOU, "Jeux", "Moyenne sous 12", "moyenne")
m.fermer_categorie(LOU, "Jeux", "Devoirs non rendus", "devoirs")
verifier("deux sources ferment Jeux : deux fermetures", len(m.fermetures_de(LOU)) == 2)
verifier("rouvrir « moyenne » laisse « devoirs »", m.ouvrir_categorie(LOU, "Jeux", "moyenne") == 1
         and (m.fermeture_de(PC, ["Jeux"]) or {}).get("source") == "devoirs")
verifier("rouvrir sans source ne touche pas aux sources nommées", m.ouvrir_categorie(LOU, "Jeux") == 0
         and m.fermeture_de(PC, ["Jeux"]) is not None)
verifier("rouvrir « devoirs » : Jeux est ouvert", m.ouvrir_categorie(LOU, "Jeux", "devoirs") == 1
         and m.fermeture_de(PC, ["Jeux"]) is None)

# 4. Rouvrir tout ce qu'une source a fermé, et la casse.
m.fermer_categorie(LOU, "Jeux", "a", "moyenne"); m.fermer_categorie(LOU, "TV", "a", "moyenne")
m.fermer_categorie(LOU, "Vidéos", "b", "devoirs")
verifier("rouvrir « moyenne » sans catégorie : ses 2 seulement", m.ouvrir_categorie(LOU, None, "moyenne") == 2
         and [f["etiquette"] for f in m.fermetures_de(LOU)] == ["Vidéos"])
verifier("la catégorie se compare sans la casse", m.fermeture_de(PC, ["vidéos"]) is not None)
verifier("une personne sans fermeture : rien", m.fermeture_de({"person": "person.noa"}, ["Jeux"]) is None)
verifier("une raison changée met à jour, « depuis » est gardé",
         (lambda d: m.fermer_categorie(LOU, "Vidéos", "c", "devoirs") and m.fermetures_de(LOU)[0]["depuis"] == d)(
             m.fermetures_de(LOU)[0]["depuis"]))
print(rates, "raté(s)")
