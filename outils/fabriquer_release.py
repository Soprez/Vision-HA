"""Fabrique vision.zip (le fichier que HACS installe) : l'intégration + l'APK signé de l'application Android.

Usage : python -I outils/fabriquer_release.py [--depot <racine du dépôt>] --apk <vision-X.Y.Z.apk signé> [--sortie <dossier>]

- Le contenu du zip est celui de custom_components/pc_parental/ (à la racine du zip, comme HACS l'attend avec
  "zip_release": true), plus android/installation.apk et android/installation.json ({"version", "sha256"}).
- L'APK doit être signé par la clé de Vision : l'empreinte du certificat est vérifiée avec apksigner.
- Rien n'est publié : le zip et l'APK sont à joindre à la GitHub Release du tag.
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

EMPREINTE = "a77cd3e3a2a91eda5e8ed3b458d990d04737cba7a58469d3968ebc3b10fec1b7"
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr"))


def verifier_apk(apk: Path) -> str:
    apksigner = sorted(glob.glob(str(SDK / "build-tools/*/apksigner.bat")) + glob.glob(str(SDK / "build-tools/*/apksigner")))[-1]
    env = {**os.environ, "JAVA_HOME": str(JAVA), "PATH": str(JAVA / "bin") + os.pathsep + os.environ["PATH"]}
    r = subprocess.run([apksigner, "verify", "--print-certs", str(apk)], capture_output=True, text=True, env=env)
    m = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", r.stdout)
    if r.returncode or not m or m.group(1) != EMPREINTE:
        sys.exit(f"APK refusé : signature absente ou autre clé ({m.group(1)[:12] if m else r.stderr.strip()[-200:]})")
    aapt = sorted(glob.glob(str(SDK / "build-tools/*/aapt2*")))[-1]
    badging = subprocess.run([aapt, "dump", "badging", str(apk)], capture_output=True, text=True).stdout
    v = re.search(r"versionName='([^']+)'", badging)
    p = re.search(r"package: name='([^']+)'", badging)
    if not v or not p or p.group(1) != "org.soprez.vision":
        sys.exit("APK refusé : paquet inattendu")
    return v.group(1)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    a = argparse.ArgumentParser()
    a.add_argument("--depot", default=".", help="racine du dépôt (par défaut : le dossier courant)")
    a.add_argument("--apk", required=True)
    a.add_argument("--sortie", default="release", help="dossier de sortie (ignoré par git)")
    args = a.parse_args()
    depot, apk, sortie = Path(args.depot), Path(args.apk), Path(args.sortie)
    integration = depot / "custom_components/pc_parental"
    version = json.loads((integration / "manifest.json").read_text("utf-8"))["version"]
    version_apk = verifier_apk(apk)
    octets = apk.read_bytes()
    sortie.mkdir(parents=True, exist_ok=True)
    zip_ = sortie / "vision.zip"
    with zipfile.ZipFile(zip_, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(integration.rglob("*")):
            rel = f.relative_to(integration).as_posix()
            if f.is_file() and "__pycache__" not in rel and not rel.endswith(".pyc"):
                z.write(f, rel)
        z.writestr("android/installation.json", json.dumps({"version": version_apk, "sha256": hashlib.sha256(octets).hexdigest()}))
        z.write(apk, "android/installation.apk", compress_type=zipfile.ZIP_STORED)
    copie_apk = sortie / f"vision-{version_apk}.apk"
    copie_apk.write_bytes(octets)
    print(f"Intégration {version}, application {version_apk} (signature vérifiée)")
    print(f"  {zip_}  sha256 {hashlib.sha256(zip_.read_bytes()).hexdigest()}")
    print(f"  {copie_apk}  sha256 {hashlib.sha256(octets).hexdigest()}")
    print(f"Release à créer : tag v{version}, fichiers vision.zip et {copie_apk.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
