"""La page d'entrée de la veille web (télés et téléphones) attend la désinscription du service worker (et recharge
une fois s'il tenait la page). Les essais de la veille des PC (garde, profils Edge) sont partis avec elle (1.91.0).

    python -m pytest essai/agent_veille_test.py -q
"""
from __future__ import annotations

import pathlib

AGENT = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental" / "client" / "ha-parental-agent.ps1"


def test_entree_attend_le_service_worker():
    texte = (AGENT.parent.parent / "veille_page.py").read_text(encoding="utf-8")
    e = texte[texte.index('ENTREE = """'):texte.index('</html>"""')]
    # Le frontend ne démarre qu'une fois les service workers désinscrits ; une page tenue est rechargée une fois.
    assert "Promise.all(l.map(function(r){return r.unregister();}))" in e
    assert "location.reload()" in e and "vision_sw_libre" in e
    assert e.index("liberer.then") < e.index("fetch('/'")
