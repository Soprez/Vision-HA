"""Règles de hassfest qui ont déjà fait échouer la validation du dépôt public (Vision 2.4.3) : gardées ici pour
qu'elles ne reviennent pas sans attendre l'intégration continue du dépôt public.

1. strings.json et translations/*.json : seules les sections connues de Home Assistant ; pas d'accolades qui ne
   sont pas des variables valides (« {minutes, max} » est prise pour une variable).
2. manifest.json : une exigence que Home Assistant fournit déjà s'écrit avec un minimum, jamais épinglée (==).
3. Un composant de Home Assistant importé par le code figure dans dependencies ou after_dependencies.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

INTEGRATION = Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental"
if not INTEGRATION.exists():  # dépôt public : custom_components/pc_parental
    INTEGRATION = Path(__file__).resolve().parent.parent / "custom_components" / "custom_components" / "pc_parental"

# Sections de strings.json acceptées par hassfest (script/hassfest/translations.py, HA 2026.10).
SECTIONS = {"title", "config", "options", "selector", "services", "entity", "exceptions", "issues",
            "device_automation", "system_health", "application_credentials", "conversation", "common",
            "device", "entity_component", "state", "config_subentries", "preview_features", "triggers",
            "conditions"}
VARIABLE = re.compile(r"\{([^{}]*)\}")
IDENTIFIANT = re.compile(r"[a-zA-Z_][a-zA-Z0-9_]*")


def _textes(donnees, chemin=""):
    if isinstance(donnees, dict):
        for cle, valeur in donnees.items():
            yield from _textes(valeur, f"{chemin}.{cle}" if chemin else cle)
    elif isinstance(donnees, str):
        yield chemin, donnees


def test_sections_et_variables_des_traductions():
    fichiers = [INTEGRATION / "strings.json", *sorted((INTEGRATION / "translations").glob("*.json"))]
    fautes = []
    for f in fichiers:
        donnees = json.loads(f.read_text(encoding="utf-8"))
        fautes += [f"{f.name}: section « {s} »" for s in set(donnees) - SECTIONS]
        for chemin, texte in _textes(donnees):
            for variable in VARIABLE.findall(texte):
                if not IDENTIFIANT.fullmatch(variable):
                    fautes.append(f"{f.name}: {chemin} : {{{variable}}}")
    assert not fautes, fautes


def test_exigences_jamais_epinglees():
    manifeste = json.loads((INTEGRATION / "manifest.json").read_text(encoding="utf-8"))
    assert manifeste["requirements"], "pyqrcode attendu"
    for exigence in manifeste["requirements"]:
        assert "==" not in exigence, exigence
        assert exigence == exigence.lower(), f"nom en minuscules, comme Home Assistant : {exigence}"


def test_composants_importes_declares():
    manifeste = json.loads((INTEGRATION / "manifest.json").read_text(encoding="utf-8"))
    declares = set(manifeste.get("dependencies", [])) | set(manifeste.get("after_dependencies", []))
    importes = set()
    for f in INTEGRATION.rglob("*.py"):
        texte = f.read_text(encoding="utf-8")
        trouves = set(re.findall(r"homeassistant\.components\.(\w+)", texte))
        trouves |= {m for ligne in re.findall(r"from homeassistant\.components import ([\w, ]+)", texte)
                    for m in re.split(r"\s*,\s*", ligne.strip())}
        importes |= trouves - {f.stem}  # plate-forme (logbook.py) : importer son composant est permis
    # Ce que hassfest ne demande pas de déclarer (ALLOWED_USED_COMPONENTS de script/hassfest/dependencies.py) :
    # composants du cœur, plates-formes d'entités et composants internes.
    from homeassistant.bootstrap import CORE_INTEGRATIONS
    from homeassistant.const import Platform

    permis = {*CORE_INTEGRATIONS, *(p.value for p in Platform),
              "alert", "automation", "conversation", "default_config", "device_automation", "frontend", "group",
              "input_boolean", "input_button", "input_datetime", "input_number", "input_select", "input_text",
              "labs", "media_source", "onboarding", "panel_custom", "person", "script", "shopping_list", "sun",
              "system_health", "system_log", "timer", "webhook", "websocket_api", "zone", "mjpeg", "stream"}
    manquants = sorted(importes - declares - permis - {manifeste["domain"]})
    assert "recorder" in declares
    assert not manquants, manquants
