"""Essai de la règle googlevideo.com (1.60.0) : les serveurs que YouTube partage avec YouTube Music ne
sont coupés que sur un Android sans accessibilité active.

python3 essai/serveurs_partages_test.py
"""
import ast, pathlib, textwrap

racine = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental"
src = (racine / "store.py").read_text(encoding="utf-8")
const = (racine / "const.py").read_text(encoding="utf-8")
espace = {"Any": object}
for n in ast.parse(const).body:
    if isinstance(n, ast.Assign) and getattr(n.targets[0], "id", "") in ("SERVEURS_PARTAGES", "FAMILLES_SITES"):
        exec(ast.get_source_segment(const, n), espace)
classe = next(n for n in ast.parse(src).body if isinstance(n, ast.ClassDef) and n.name == "PcStore")
methode = next(m for m in classe.body if isinstance(m, ast.FunctionDef) and m.name == "serveurs_partages_requis")
exec("class M:\n" + textwrap.indent(textwrap.dedent(ast.get_source_segment(src, methode)), "    "), espace)
requis = espace["M"].serveurs_partages_requis
calc = next(m for m in classe.body if isinstance(m, ast.FunctionDef) and m.name == "_effectif_calc")
corps = ast.get_source_segment(src, calc)

rates = 0
def verifier(quoi, condition):
    global rates
    rates += 0 if condition else 1
    print(("ok   " if condition else "RATE ") + quoi)


android = lambda acc: {"platform": "android", "agent": {"platform": "android", "protections": {"accessibilite": acc}}}
verifier("famille : googlevideo.com et ytimg.com sont dans celle de youtube.com",
         espace["FAMILLES_SITES"]["googlevideo.com"] == "youtube.com" and espace["FAMILLES_SITES"]["ytimg.com"] == "youtube.com")
verifier("Android avec accessibilité : serveurs partagés non coupés", requis(android(True)) is False)
verifier("Android sans accessibilité : serveurs partagés coupés", requis(android(False)) is True)
verifier("Android qui ne dit rien de ses protections : coupés (prudence)", requis({"platform": "android", "agent": {}}) is True)
verifier("PC Windows : non coupés (youtube.com suffit au navigateur)", requis({"agent": {"platform": ""}}) is False)
verifier("le calcul de la liste applique la règle", "serveurs_partages_requis(pc)" in corps and "SERVEURS_PARTAGES" in corps)
verifier("un nom bloqué exprès reste bloqué", "m == nom or m not in SERVEURS_PARTAGES" in corps)
print(rates, "raté(s)")
