"""1.93.2 (perte d'images par seconde chez un Demandeur), côté HA et côté agent.

- HA : `hosts_version` (http.py) est stable d'un relevé à l'autre et change avec chaque champ que l'agent applique ;
  la charge déclarée par l'agent est bornée.
- Agent : lance essai/hosts_version_essai.ps1 (les vraies fonctions de l'agent) sous Windows PowerShell : blocage,
  sous-domaine, exception, liste noire, reconstruction seulement quand il le faut, temps. Sauté hors de Windows.

    python -m pytest essai/hosts_version_test.py -q
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile

import pytest

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-hosts-version-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))


def _http():
    from custom_components.pc_parental import http

    return http


def hosts_version(reponse):
    return _http().hosts_version(reponse)


def _charge_agent(brut):
    return _http()._charge_agent(brut)

ESSAI = pathlib.Path(__file__).with_name("hosts_version_essai.ps1")

BASE = {
    "sites": ["roblox.com", "youtube.com"],
    "exceptions": ["m.youtube.com"],
    "liste_noire": "2026-10-17T04:00",
    "safesearch": True,
    "apps": ["steam"],
    "message": "",
}


def test_meme_reponse_meme_version():
    assert hosts_version(dict(BASE)) == hosts_version(dict(BASE))
    assert len(hosts_version(dict(BASE))) == 16


@pytest.mark.parametrize(
    "champ, valeur",
    [
        ("sites", ["roblox.com"]),
        ("sites", ["roblox.com", "youtube.com", "tiktok.com"]),
        ("exceptions", []),
        ("liste_noire", "2026-10-18T04:00"),
        ("liste_noire", ""),
        ("safesearch", False),
    ],
)
def test_chaque_champ_applique_change_la_version(champ, valeur):
    autre = dict(BASE)
    autre[champ] = valeur
    assert hosts_version(autre) != hosts_version(dict(BASE))


def test_les_autres_champs_ne_changent_rien():
    autre = dict(BASE, apps=["minecraft"], message="bonjour", warn=3, poll=20)
    assert hosts_version(autre) == hosts_version(dict(BASE))


def test_charge_agent_bornee():
    assert _charge_agent(None) is None
    assert _charge_agent("x") is None
    assert _charge_agent({}) is None
    assert _charge_agent({"cpu_pct": 3.14159, "mem_mo": 98.76, "hosts_ms": 12}) == {
        "cpu_pct": 3.1, "mem_mo": 98.8, "hosts_ms": 12.0}
    assert _charge_agent({"cpu_pct": 250, "mem_mo": -1, "hosts_ms": "abc"}) is None
    assert _charge_agent({"cpu_pct": None, "mem_mo": 50}) == {"cpu_pct": None, "mem_mo": 50.0, "hosts_ms": None}


@pytest.mark.skipif(shutil.which("powershell.exe") is None, reason="Windows PowerShell absent")
def test_agent_blocage_et_reconstruction_a_la_demande():
    r = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ESSAI)],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300, check=False)
    assert r.returncode == 0, r.stdout[-4000:] + r.stderr[-2000:]
