"""Garde anti-fuite de la CI (job « Garde » de Validation) : la deuxième barrière, après les hooks locaux.

Contrôles, sur tous les fichiers suivis et sur les messages et identités des commits examinés :
  1. vocabulaire neutre dans les textes affichés (jamais « famille », « maison », « parent », « enfant »…) ;
  2. adresses du réseau local réel : 192.168.x.y interdit (les exemples du code sont en 10.1.1.x) ;
  3. liste privée de motifs, connue seulement sous forme d'empreintes HMAC-SHA-256 :
     - secrets GitHub VISION_GARDE_CLE (clé) et VISION_GARDE_EMPREINTES (une empreinte « n:hex » par ligne) ;
     - chaque suite de 1 à 6 mots (lettres et chiffres, en minuscules) est hachée avec la clé puis comparée ;
     - le journal ne montre que « fichier:ligne, motif privé » : ni le mot, ni la liste.
Sans les deux secrets (PR venue d'un fork), le contrôle 3 est sauté et le dit ; les hooks locaux le font quand même.

    python outils/garde_ci.py [--commits <plage git>]
Sortie 0 = rien trouvé ; 1 = au moins un problème.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import unicodedata

BINAIRES = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".ttf", ".otf", ".woff", ".woff2",
            ".jar", ".apk", ".zip", ".gz", ".tgz", ".mp3", ".ogg", ".wav", ".pdf", ".keystore", ".jks"}

# ------------------------------------------------------------------ 1. vocabulaire neutre (textes affichés)
def _mots(liste: str):
    return re.compile(r"(?<![\w/.-])(" + liste + r")(?![\w-])", re.IGNORECASE)


MOTS = _mots(r"famill?e?s?|familial(?:e|es|aux)?|maisons?|parents?|parental(?:e|es|aux)?|enfants?"
             r"|family|families|household|children|child|kids?")
MOTS_LANGUE = {
    "de": _mots(r"familie|familien|eltern|elternteil|kinder|kind|zuhause"),
    "nl": _mots(r"gezin|familie|ouders?|kinderen|kind|thuis"),
    "es": _mots(r"familia|hogar|padres?|niños?|hijos?"),
    "it": _mots(r"famiglia|casa|genitori?|bambin[io]|figli?"),
}
LANGUE_DU_FICHIER = re.compile(r"(?:^|[/._-])(de|nl|es|it)(?:[/._-]|$)|LIESMICH|LEESMIJ|LEEME|LEGGIMI")
_FICHIER_LANGUE = {"LIESMICH": "de", "LEESMIJ": "nl", "LEEME": "es", "LEGGIMI": "it"}
# Identifiants techniques gardés (domaine HA, fichiers servis, protocole, marqueurs de l'agent déjà installé).
IDENTIFIANTS = re.compile(
    r"pc_parental\w*|ha-parental-agent\w*|HAParental\w*|vision-maison[\w-]*|PcParental\w*"
    r"|parent\.py|est_parent|code_parent|libere_parent|\"parent\"|'parent'|\"enfant\"|'enfant'"
    r"|\"maison\"|'maison'|Home Assistant|home screen|parentNode|parentElement|MATCH_PARENT"
    r"|HA Controle Parental|Controle parental Home Assistant|options: \[[^\]]*\]"
    r"|(?m:^\s*- parent\s*$)"
)
# Pas du texte affiché : commentaires XML/HTML, citations « … », code entre accents graves, la règle elle-même.
NON_AFFICHE = re.compile(r"<!--.*?-->|«[^»\n]*»|`[^`\n]*(?:\n[^`\n]*)?`"
                         r"|\(ni leurs équivalents dans les autres langues :[^)]*\)|\(ni home/house[^)]*\)", re.S)
AFFICHES = ["README*", "*.md", "custom_components/pc_parental/strings.json",
            "custom_components/pc_parental/translations/*.json", "custom_components/pc_parental/i18n/*",
            "custom_components/pc_parental/client/langues/*.json", "custom_components/pc_parental/client/*.txt",
            "android/src/app/src/main/res/values*/strings.xml", "custom_components/pc_parental/services.yaml"]
# Textes affichés qui citent d'anciens noms exprès (traduction des données existantes, non migrées).
VOCABULAIRE_EXCEPTIONS = ["custom_components/pc_parental/i18n/categories.json"]


def _valeurs_json(donnees):
    if isinstance(donnees, dict):
        for cle, valeur in donnees.items():
            if not str(cle).startswith("_"):
                yield from _valeurs_json(valeur)
    elif isinstance(donnees, list):
        for valeur in donnees:
            yield from _valeurs_json(valeur)
    elif isinstance(donnees, str):
        yield donnees


def vocabulaire(rel: str, texte: str) -> list[str]:
    if not any(fnmatch.fnmatch(rel, m) for m in AFFICHES) or any(fnmatch.fnmatch(rel, m) for m in VOCABULAIRE_EXCEPTIONS):
        return []
    if rel.endswith(".json"):
        try:
            texte = "\n".join(_valeurs_json(json.loads(texte)))
        except json.JSONDecodeError:
            pass
    texte = IDENTIFIANTS.sub(" ", NON_AFFICHE.sub(" ", texte))
    trouves = MOTS.findall(texte)
    m = LANGUE_DU_FICHIER.search(rel)
    if m:
        langue = m.group(1) or next(v for k, v in _FICHIER_LANGUE.items() if k in m.group(0))
        trouves += MOTS_LANGUE[langue].findall(texte)
    return [f"[vocabulaire neutre] {rel} : {', '.join(sorted(set(t.lower() for t in trouves)))}"] if trouves else []


# ------------------------------------------------------------------ 2. réseau local réel
IP_LOCALE = re.compile(r"(?<![\d.])192\.168\.(\d{1,3}|x)\.(\d{1,3}|x)(?![\d])")
IP_PERMISES = {"192.168.0.0"}  # le réseau 192.168.0.0/16 cité par le code (plages privées)


def reseau(rel: str, texte: str) -> list[str]:
    fautes = []
    for no, ligne in enumerate(texte.splitlines(), 1):
        for m in IP_LOCALE.finditer(ligne):
            if m.group(0) not in IP_PERMISES:
                fautes.append(f"[adresse du réseau local] {rel}:{no} (exemples : 10.1.1.x)")
    return fautes


# ------------------------------------------------------------------ 3. liste privée (empreintes HMAC)
ATOME = re.compile(r"[^\W_]+")  # suites de lettres et de chiffres ; « _ », « . », « - », « : », « @ » séparent


def atomes(texte: str) -> list[str]:
    return ATOME.findall(unicodedata.normalize("NFC", texte).casefold())


class Empreintes:
    def __init__(self, cle: str, liste: str) -> None:
        self.cle = bytes.fromhex(cle.strip())
        self.connues: dict[int, set[str]] = {}
        for ligne in liste.split():
            n, _, h = ligne.partition(":")
            self.connues.setdefault(int(n), set()).add(h.lower())

    def __call__(self, rel: str, texte: str) -> list[str]:
        fautes = []
        for no, ligne in enumerate(texte.splitlines(), 1):
            a = atomes(ligne)
            for n, connues in self.connues.items():
                if any(hmac.new(self.cle, "\x1f".join(a[i:i + n]).encode(), hashlib.sha256).hexdigest() in connues
                       for i in range(len(a) - n + 1)):
                    fautes.append(f"[motif privé] {rel}:{no}")
                    break
        return fautes


# Seule identité de commit exemptée de la liste privée : le compte GitHub de l'auteur de la PR HACS, sous sa
# forme « noreply ». Comparaison exacte, champ par champ (auteur, puis commettant) : le vrai nom, l'adresse
# personnelle, ou même ce pseudonyme avec une autre adresse restent contrôlés. Rien d'autre n'est exempté.
# Connue par son empreinte SHA-256 seulement : le pseudonyme ne figure pas en clair dans le dépôt (la garde le
# refuserait dans le contenu).
IDENTITE_AUTORISEE_SHA256 = "9dc62c3ecbd7507c0ce7c7185f9e801992e8f6bffdd81a7db4ad4a675e7727d5"


def identite_autorisee(identite: str) -> bool:
    return hashlib.sha256(identite.encode("utf-8")).hexdigest() == IDENTITE_AUTORISEE_SHA256


def git(*args: str) -> bytes:
    return subprocess.run(["git", *args], check=True, capture_output=True).stdout


def decoder(brut: bytes) -> str | None:
    if b"\0" in brut[:8000]:
        return None
    if brut.startswith(b"\xef\xbb\xbf"):
        brut = brut[3:]
    try:
        return brut.decode("utf-8")
    except UnicodeDecodeError:
        return brut.decode("latin-1")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--commits", help="plage git dont on contrôle les messages et identités (ex. A..B)")
    args = p.parse_args()

    cle, liste = os.environ.get("VISION_GARDE_CLE", ""), os.environ.get("VISION_GARDE_EMPREINTES", "")
    prive = Empreintes(cle, liste) if cle.strip() and liste.strip() else None
    print("liste privée : " + (f"{sum(len(v) for v in prive.connues.values())} empreintes" if prive
                               else "secrets absents (PR d'un fork ?) : contrôle sauté, les hooks locaux le font"))

    fautes: list[str] = []
    fichiers = [f for f in git("ls-files", "-z").decode("utf-8").split("\0") if f]
    for rel in fichiers:
        if os.path.splitext(rel)[1].lower() in BINAIRES or not os.path.isfile(rel):
            continue
        texte = decoder(open(rel, "rb").read())
        if texte is None:
            continue
        fautes += vocabulaire(rel, texte) + reseau(rel, texte)
        if prive:
            fautes += prive(rel, texte) + prive("(nom de fichier)", rel)

    if args.commits:
        journal = git("log", "--format=%h%x1f%an <%ae>%x1f%cn <%ce>%x1f%B%x1e", args.commits).decode("utf-8", "replace")
        n = 0
        for bloc in filter(str.strip, journal.split("\x1e")):
            court, auteur, commettant, message = (bloc.strip("\n").split("\x1f") + ["", "", ""])[:4]
            n += 1
            identite = "\n".join(i for i in (auteur, commettant) if not identite_autorisee(i))
            for quoi, texte in (("message", message), ("identité", identite)):
                fautes += [f.replace(f"{quoi}:", f"{court} {quoi}:", 1) for f in reseau(quoi, texte)]
                if prive:
                    fautes += [f.replace(f"{quoi}:", f"{court} {quoi}:", 1) for f in prive(quoi, texte)]
        print(f"commits examinés : {n} ({args.commits})")

    print(f"fichiers examinés : {len(fichiers)}")
    if fautes:
        print(f"REFUS : {len(fautes)} problème(s)")
        for f in fautes[:100]:
            print("  " + f)
        return 1
    print("rien trouvé")
    return 0


if __name__ == "__main__":
    sys.exit(main())
