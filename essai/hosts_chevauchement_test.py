"""Double autorisation (2.4.4), côté agent : lance essai/hosts_chevauchement_essai.ps1 (la vraie Sync-Hosts de l'agent)
sous Windows PowerShell. Sauté hors de Windows.

    python -m pytest essai/hosts_chevauchement_test.py -q
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess

import pytest

ESSAI = pathlib.Path(__file__).with_name("hosts_chevauchement_essai.ps1")


@pytest.mark.skipif(shutil.which("powershell.exe") is None, reason="Windows PowerShell absent")
def test_sync_hosts_accords_qui_se_chevauchent():
    r = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ESSAI)],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    assert r.returncode == 0, r.stdout[-3000:] + r.stderr[-2000:]
