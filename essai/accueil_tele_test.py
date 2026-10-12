"""Point 7 (1.97.6) : l'accueil des télés remonté et lisible dans HA.

Une télé dit si son « accueil forcé » tient (protections.accueil) ; une télé trop ancienne ne le dit pas : la carte
le signale (mettre Vision à jour) au lieu d'afficher « tout est en place ». La fiche porte le type et la version.

    python -m pytest essai/accueil_tele_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


async def test_fiche_tele_type_version_accueil(hass, hass_storage):
    from custom_components.pc_parental import parent

    salon = _fiche("e1", "TV-SALON", ENFANT["secret"], platform="android", role="enfant", type="tv")
    salon["agent"] = {"version": "1.78.0", "platform": "android",
                      "protections": {"accessibilite": True, "accueil": False}}
    parents_tv = _fiche("e2", "TV-PARENTS", ENFANT2["secret"], platform="android", role="enfant", type="tv")
    parents_tv["agent"] = {"version": "1.68.0", "platform": "android", "protections": {"accessibilite": True}}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": salon, "e2": parents_tv,
            "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], platform="android", role="parent"),
        }}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    fiches = {a["id"]: a for a in parent.etat_maison(hass, coord, "p1")["appareils"]}
    assert fiches["e1"]["protections"]["accueil"] is False and fiches["e1"]["version_appli"] == "1.78.0"
    assert "accueil" not in fiches["e2"]["protections"] and fiches["e2"]["version_appli"] == "1.68.0"
    assert fiches["e1"]["type"] == fiches["e2"]["type"]


def test_carte_signale_toute_appli_trop_ancienne():
    """1.97.9 : téléphone ou tablette d'enfant trop ancien (Pixel 8a 1.10.0, tablette de Lou 1.48.0) : « mettre
    Vision à jour », pas « tout est en place ». Le téléphone d'un parent et un appareil à jour ne sont pas signalés."""
    import json
    import pathlib
    import re
    import shutil
    import subprocess

    import pytest

    node = shutil.which("node")
    if not node:
        pytest.skip("Node manque.")
    source = (pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental" / "frontend" / "vision-maison-card.js").read_text(encoding="utf-8")
    fonction = re.search(r"const appliTropAncienne = \(a\) => \{.*?\n\};", source, re.S).group(0)
    cas = [
        {"android": True, "en_ligne": True, "type": "telephone", "version_appli": "1.10.0", "protections": {"vpn": True}},
        {"android": True, "en_ligne": True, "type": "tablette", "version_appli": "1.48.0", "protections": {}},
        {"android": True, "en_ligne": True, "type": "tv", "version_appli": "1.68.0", "protections": {"vpn": False}},
        {"android": True, "en_ligne": True, "type": "telephone", "parent": True, "protections": {}},
        {"android": True, "en_ligne": True, "type": "telephone", "protections": {"accueil": True}},
        {"android": True, "en_ligne": False, "type": "telephone", "protections": {}},
        {"android": False, "en_ligne": True, "type": "pc", "protections": {}},
    ]
    # La fonction lit les textes de la carte (tr) : son bloc i18n passe d'abord, en français. Le script passe par
    # l'entrée standard : en ligne de commande (node -e), il dépasserait la limite de Windows.
    i18n = source[source.index("// ---- i18n:debut"):source.index("// ---- i18n:fin")]
    script = i18n + "suivreLangue({ language: 'fr' });\n" + fonction + \
        f"\nconsole.log(JSON.stringify({json.dumps(cas)}.map(appliTropAncienne)));"
    sortie = json.loads(subprocess.run([node, "-"], input=script, capture_output=True, text=True, encoding="utf-8",
                                       check=True).stdout)
    assert sortie[0] == "mettre Vision à jour (1.10.0) : son accueil n'est pas encore vérifié"
    assert "1.48.0" in sortie[1] and "1.68.0" in sortie[2]
    assert sortie[3:] == ["", "", "", ""]
