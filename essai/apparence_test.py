"""Apparence du tableau de bord (1.95.0, point 16.2).

    python -m pytest essai/apparence_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import os

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

URL = "/api/pc_parental/apparence"


def test_palettes_lisibles():
    from custom_components.pc_parental import apparence as a

    assert len(a.PALETTES) >= 25 and a.PALETTES[0][0] == "Vision"
    for nom, *_ in a.PALETTES:
        for sombre in (False, True):
            v = a.variables(a.variante(nom, sombre), sombre)
            assert a.contraste(v["primary-text-color"], v["card-background-color"]) >= 7, (nom, sombre)
            assert a.contraste(v["secondary-text-color"], v["card-background-color"]) >= 4.5, (nom, sombre)
            assert a.contraste(v["primary-color"], v["primary-background-color"]) >= 4.5, (nom, sombre)
            assert a.contraste(v["text-primary-color"], v["primary-color"]) >= 4.5, (nom, sombre)


def test_a7_contrastes_wcag_de_chaque_palette():
    """A7 : pour chaque palette (et avec un accent choisi), la bordure des cartes en sombre fait ≥ 3:1 sur la carte
    et sur le fond ; les couleurs d'alerte, de succès, d'erreur et d'info font ≥ 4,5:1 sur blanc en clair, et sur
    la carte et le fond dans les deux modes (elles servent de texte)."""
    from custom_components.pc_parental import apparence as a

    etats = ("success-color", "warning-color", "error-color", "info-color")
    rates = []
    for nom, *_ in a.PALETTES:
        for accent in ("", "#FFE600", "#2B2B2B"):
            for sombre in (False, True):
                v = a.variables(a.variante(nom, sombre, accent), sombre)
                fonds = [v["card-background-color"], v["primary-background-color"]]
                if sombre:
                    for f in fonds:
                        if a.contraste(v["ha-card-border-color"], f) < 3:
                            rates.append((nom, accent, "bordure", round(a.contraste(v["ha-card-border-color"], f), 2)))
                else:
                    fonds.append("#FFFFFF")
                for cle in etats:
                    for f in fonds:
                        if a.contraste(v[cle], f) < 4.5:
                            rates.append((nom, accent, sombre, cle, f, round(a.contraste(v[cle], f), 2)))
    assert not rates, rates


def test_modes_et_reglage():
    from custom_components.pc_parental import apparence as a

    t = a.theme({"palette": "Sauge", "mode": "sombre"})["Vision"]["modes"]
    assert t["light"] == t["dark"] and a.luminance(t["dark"]["primary-background-color"]) < 0.05
    t = a.theme({"palette": "Sauge", "mode": "auto"})["Vision"]["modes"]
    assert t["light"] != t["dark"]
    assert a.reglage_propre({"palette": "Inconnue", "mode": "x", "accent": "rouge"}) == {"palette": "Vision", "mode": "auto", "accent": ""}
    texte = a.yaml_theme({"palette": "Beige", "mode": "clair", "accent": "#0a84d6"})
    assert texte.startswith("# Thème « Vision »") and "accent #0A84D6" in texte and "Vision:\n  modes:\n    dark:" in texte


async def test_api_ecrit_le_theme(hass, hass_storage, hass_client, hass_read_only_access_token, tmp_path):
    hass.config.config_dir = str(tmp_path)
    os.makedirs(tmp_path / "themes")
    (tmp_path / "themes" / "vision.yaml").write_text("Vision:\n  primary-color: red\n", encoding="utf-8")
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {}}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    client = await hass_client()
    d = await (await client.get(URL)).json()
    assert d["reglage"] == {"palette": "Vision", "mode": "auto", "accent": ""} and len(d["palettes"]) >= 25
    d = await (await client.post(URL, json={"palette": "Bleu nuit", "mode": "clair", "accent": ""})).json()
    assert d["reglage"]["palette"] == "Bleu nuit" and d["reglage"]["mode"] == "clair"
    ecrit = (tmp_path / "themes" / "vision.yaml").read_text(encoding="utf-8")
    assert "palette « Bleu nuit », mode clair" in ecrit
    # L'ancien fichier est gardé une fois.
    assert (tmp_path / "themes" / "vision.yaml.avant-apparence").read_text(encoding="utf-8").startswith("Vision:\n  primary-color: red")
    assert hass.data[DOMAIN][entree.entry_id].store.apparence["palette"] == "Bleu nuit"
    autre = await hass_client(hass_read_only_access_token)
    assert (await autre.post(URL, json={"palette": "Beige"})).status == 403
