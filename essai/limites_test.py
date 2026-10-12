"""Essai des limites de temps par logiciel, sans Home Assistant : les méthodes sont
sorties de store.py par leur nom et posées sur un magasin de carton.

python3 essai/limites_test.py
"""
import ast, datetime, pathlib, sys, textwrap, types

src = (pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental" / "store.py").read_text(encoding="utf-8")
arbre = ast.parse(src)
voulues = {"limites", "poser_limite", "temps_du_jour", "limite_atteinte", "_siens"}
corps, fonctions = [], []
for n in arbre.body:
    if isinstance(n, ast.FunctionDef) and n.name == "_nom_app":
        fonctions.append(ast.get_source_segment(src, n))
    if isinstance(n, ast.ClassDef) and n.name == "PcStore":
        for m in n.body:
            if isinstance(m, ast.FunctionDef) and m.name in voulues:
                corps.append(textwrap.indent(textwrap.dedent(ast.get_source_segment(src, m)), "    "))
            if isinstance(m, ast.Assign) and getattr(m.targets[0], "id", "") == "LIMITE_MAX_MINUTES":
                corps.append("    " + ast.get_source_segment(src, m))
assert len(corps) == len(voulues) + 1, len(corps)
maintenant = [datetime.datetime(2026, 10, 5, 18, 0)]
espace = {"Any": object, "dt_util": types.SimpleNamespace(now=lambda: maintenant[0]),
          "FAMILLES": {}, "re": __import__("re"),
          # 1.82.0 : les modules (modules.py) ; ici, tous actifs.
          "_modules": types.SimpleNamespace(coupe=lambda magasin, *m: False)}
# Les textes de Vision (textes.py, i18n), chargés seuls dans un paquet de carton, sans Home Assistant.
import importlib  # noqa: E402
_paquet = types.ModuleType("vision_seul")
_paquet.__path__ = [str(pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental")]
sys.modules["vision_seul"] = _paquet
espace["textes"] = importlib.import_module("vision_seul.textes")
try:
    exec("\n".join(fonctions), espace)
except Exception as e:  # _nom_app dépend d'autres aides : un équivalent suffit à l'essai
    espace["_nom_app"] = lambda n: str(n or "").strip().lower().removesuffix(".exe")
try:
    espace["_nom_app"]("Roblox.exe")
except Exception:
    espace["_nom_app"] = lambda n: str(n or "").strip().lower().removesuffix(".exe")
exec("class Magasin:\n" + "\n".join(corps), espace)
m = espace["Magasin"]()
enfant_pc = {"id": "pc", "person": "person.alex", "usage": {"2026-10-05": {"apps": {"roblox": 1500}}}}
enfant_tel = {"id": "tel", "person": "person.alex", "usage": {"2026-10-05": {"apps": {"roblox": 900}}, "2026-10-04": {"apps": {"roblox": 9000}}}}
autre = {"id": "a", "person": "person.sam", "usage": {"2026-10-05": {"apps": {"roblox": 5000}}}}
m.pcs = {"pc": enfant_pc, "tel": enfant_tel, "a": autre}

rates = 0
def verifier(quoi, obtenu, attendu):
    global rates
    ok = obtenu == attendu
    rates += 0 if ok else 1
    print(("ok   " if ok else "RATE ") + quoi + ("" if ok else f" : obtenu {obtenu!r}, attendu {attendu!r}"))

verifier("sans limite : rien", m.limite_atteinte(enfant_pc, "apps", "roblox"), None)
verifier("temps du jour : les deux appareils, pas hier, pas un autre", m.temps_du_jour(enfant_pc, "roblox"), 2400)
verifier("poser 45 min", m.poser_limite(enfant_pc, "Roblox.exe", 45), True)
verifier("posée sur tous ses appareils", (enfant_pc["limites"], enfant_tel["limites"]), ({"roblox": 45}, {"roblox": 45}))
verifier("pas sur ceux de l’autre enfant", "limites" in autre, False)
verifier("40 min passées sur 45 : ouvert", m.limite_atteinte(enfant_tel, "apps", "roblox"), None)
enfant_tel["usage"]["2026-10-05"]["apps"]["roblox"] = 1200
verifier("45 min passées : fermé, sur le PC aussi", m.limite_atteinte(enfant_pc, "apps", "RobloxPlayer") or m.limite_atteinte(enfant_pc, "apps", "roblox"), (45, 2700))
verifier("un site n'a pas de limite", m.limite_atteinte(enfant_pc, "sites", "roblox"), None)
verifier("même valeur : rien ne change", m.poser_limite(enfant_tel, "roblox", 45), False)
verifier("relever à 60 : rouvre", (m.poser_limite(enfant_tel, "roblox", 60), m.limite_atteinte(enfant_pc, "apps", "roblox")), (True, None))
m.poser_limite(enfant_pc, "roblox", 45)
maintenant[0] = datetime.datetime(2026, 10, 6, 0, 5)
verifier("le lendemain : compteur à zéro", m.limite_atteinte(enfant_pc, "apps", "roblox"), None)
verifier("retirer", (m.poser_limite(enfant_pc, "roblox", 0), enfant_tel["limites"]), (True, {}))
verifier("retirer ce qui n'existe pas", m.poser_limite(enfant_pc, "roblox", 0), False)
try:
    m.poser_limite(enfant_pc, "", 30); verifier("nom vide refusé", False, True)
except ValueError:
    verifier("nom vide refusé", True, True)
verifier("plafonnée à 24 h", (m.poser_limite(enfant_pc, "x", 99999), enfant_pc["limites"]["x"]), (True, 1440))
enfant_pc["limites"] = {"roblox": "n'importe quoi", "ok": 30}
verifier("donnée abîmée ignorée", m.limites(enfant_pc), {"ok": 30})
print(rates, "raté(s)")
sys.exit(1 if rates else 0)
