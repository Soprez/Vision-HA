"""2.3.3 : la version écrite dans l'agent est celle que Home Assistant annonce.

En 2.3.1, HA annonçait 1.90.0 alors que le script servi se disait 1.89.2 : chaque PC téléchargeait la mise à jour,
redémarrait en 1.89.2, se la voyait reproposer, et ainsi de suite.
"""
import pathlib
import re

RACINE = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental"
AGENT = RACINE / "client" / "ha-parental-agent.ps1"
CLIENT_VERSION = re.search(r'^CLIENT_VERSION = "([^"]+)"', (RACINE / "const.py").read_text(encoding="utf-8"), re.MULTILINE).group(1)


def test_agent_et_ha_annoncent_la_meme_version():
    texte = AGENT.read_text(encoding="utf-8")
    m = re.search(r"^\$script:Version\s*=\s*'([^']+)'", texte, re.MULTILINE)
    assert m, "ligne $script:Version introuvable dans l'agent"
    assert m.group(1) == CLIENT_VERSION, f"agent {m.group(1)} ≠ CLIENT_VERSION {CLIENT_VERSION} : boucle de mise à jour"
