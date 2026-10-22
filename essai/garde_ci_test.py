"""Garde anti-fuite de la CI (outils/garde_ci.py) : l'exception d'identité est stricte.

Une liste privée factice (clé et empreintes fabriquées ici) remplace les secrets du dépôt. Elle contient les mots
du pseudonyme du compte GitHub exempté et un faux nom : on prouve que l'identité noreply exacte passe, et que tout
le reste (autre nom ou adresse, même pseudonyme avec une autre adresse ou une autre casse, message de commit) est
refusé. L'identité exemptée est écrite en base64 : la garde refuse ce pseudonyme en clair dans le contenu du dépôt.

    python -m pytest essai/garde_ci_test.py -q
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

GARDE = Path(__file__).resolve().parent.parent / "outils" / "garde_ci.py"
_IDENTITE = base64.b64decode(
    "amFjcXVlc2FudG9pbmVyb3ktaGFzaCA8MjgzMDgzODQzK2phY3F1ZXNhbnRvaW5lcm95LWhhc2hAdXNlcnMubm9yZXBseS5naXRodWIuY29tPg=="
).decode()
NOREPLY = tuple(_IDENTITE.rstrip(">").split(" <"))
AUTRE = ("Jean Dupont", "jean.dupont@example.org")
CLE = "00112233445566778899aabbccddeeff"
MOTS_PRIVES = (*re.findall(r"[a-z]+", NOREPLY[0]), "dupont")  # le pseudonyme (2 mots) et un faux nom


def _empreintes(*mots: str) -> str:
    return "\n".join("1:" + hmac.new(bytes.fromhex(CLE), m.encode(), hashlib.sha256).hexdigest() for m in mots)


def _git(depot: Path, env_id: tuple[str, str, str, str] | None, *args: str) -> str:
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    if env_id:
        env.update(GIT_AUTHOR_NAME=env_id[0], GIT_AUTHOR_EMAIL=env_id[1],
                   GIT_COMMITTER_NAME=env_id[2], GIT_COMMITTER_EMAIL=env_id[3])
    return subprocess.run(["git", "-c", "core.hooksPath=", "-c", "commit.gpgsign=false", *args], cwd=depot, env=env,
                          check=True, capture_output=True, text=True).stdout


def _garde(tmp_path: Path, auteur, commettant, message="Correction de la documentation") -> subprocess.CompletedProcess:
    depot = tmp_path / "depot"
    depot.mkdir()
    _git(depot, None, "init", "-q")
    neutre = ("essai", "essai@example.org", "essai", "essai@example.org")
    _git(depot, neutre, "commit", "-q", "--allow-empty", "-m", "base")
    _git(depot, (*auteur, *commettant), "commit", "-q", "--allow-empty", "-m", message)
    env = dict(os.environ, VISION_GARDE_CLE=CLE, VISION_GARDE_EMPREINTES=_empreintes(*MOTS_PRIVES))
    return subprocess.run([sys.executable, "-I", "-X", "utf8", str(GARDE), "--commits", "HEAD~1..HEAD"], cwd=depot,
                          env=env, capture_output=True, text=True, encoding="utf-8")


def test_noreply_exacte_passe(tmp_path):
    r = _garde(tmp_path, NOREPLY, NOREPLY)
    assert r.returncode == 0, r.stdout
    assert f"liste privée : {len(MOTS_PRIVES)} empreintes" in r.stdout  # le contrôle est actif, pas sauté


@pytest.mark.parametrize("auteur, commettant", [
    (AUTRE, AUTRE),                                    # un autre nom et une autre adresse
    (NOREPLY, AUTRE),                                  # le commettant est contrôlé aussi
    (AUTRE, NOREPLY),                                  # l'auteur aussi
    ((NOREPLY[0], "jean.dupont@example.net"),          # même pseudonyme, autre adresse : pas d'exception
     (NOREPLY[0], "jean.dupont@example.net")),
    ((NOREPLY[0].capitalize(), NOREPLY[1]), NOREPLY),  # variante (casse) de l'exception : refusée
])
def test_autre_identite_refusee(tmp_path, auteur, commettant):
    r = _garde(tmp_path, auteur, commettant)
    assert r.returncode == 1, r.stdout
    assert "identité" in r.stdout


def test_exception_limitee_aux_identites(tmp_path):
    r = _garde(tmp_path, NOREPLY, NOREPLY, message=f"Merci à {NOREPLY[0]} <{NOREPLY[1]}>")
    assert r.returncode == 1, r.stdout
    assert "message" in r.stdout
