"""Veille (1.76.0) : en attendant le vrai rendu des cartes, jamais d'état brut ni d'identifiant technique.

    python -m pytest essai/veille_cases_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401


async def test_cartes_personnalisees_masquees():
    from custom_components.pc_parental import veille_dash as vd

    planning = vd._feuille({"type": "custom:pronote-planning-card", "entities": [{"entity": "sensor.pronote_noa_period_s_timetable"}]}, None)
    assert planning["cases"] == []  # « 48 » leçons : rien plutôt qu'un nombre incompréhensible
    devoirs = vd._feuille({"type": "custom:pronote-homework-card", "entity": "sensor.pronote_noa_homework"}, None)
    assert devoirs["cases"] == []
    # Une carte mushroom montre bien l'état de son entité : gardée, avec son titre.
    moyenne = vd._feuille({"type": "custom:mushroom-template-card", "entity": "sensor.moyenne", "primary": "Moyenne générale"}, None)
    assert moyenne["cases"][0]["libelle"] == "Moyenne générale"
    assert vd._feuille({"type": "custom:mushroom-template-card", "entity": "sensor.m", "primary": "{{ x }}"}, None)["cases"][0]["libelle"] == ""
    # picture-elements : rien d'affichable en cases (déjà le cas), masquée.
    assert vd._feuille({"type": "picture-elements", "elements": [{"type": "state-icon", "entity": "lock.x"}]}, None)["cases"] == []


async def test_meteo_en_francais_et_jamais_d_identifiant(hass):
    from custom_components.pc_parental import veille_tableaux as vt

    hass.states.async_set("weather.forecast_maison", "sunny", {"friendly_name": "Forecast Maison", "temperature": 19.4, "temperature_unit": "°C"})
    r = await vt._case(hass, {"entite": "weather.forecast_maison"})
    assert r["nom"] == "Météo Maison" and r["texte"] == "Ensoleillé · 19°C" and r["rendu"] == "texte" and not r.get("masquer")
    hass.states.async_set("weather.x", "storming", {})
    assert (await vt._case(hass, {"entite": "weather.x"})).get("masquer")
    # Sans nom convivial ni libellé : la case ne montre pas « sensor.abc_123 », elle est masquée.
    hass.states.async_set("sensor.abc_123", "48", {})
    assert (await vt._case(hass, {"entite": "sensor.abc_123"})).get("masquer")
    assert not (await vt._case(hass, {"entite": "sensor.abc_123", "libelle": "Leçons"})).get("masquer")
