"""2.2.12 (veille, lot) : la veille ne montre que ce qui se lit, proprement, dans la langue de Home Assistant.

- une entité d'action ou de saisie (bouton, input_number…) est retirée des tableaux composés, des tableaux de bord
  et des tuiles ;
- une date devient « 9 oct., 08:00 » en français, « Oct 9, 08:00 » en anglais (jamais « 2026-10-09T08:00:46 ») ;
- les unités de temps sont dites dans la langue : « 176 j », « 1,9 L/j » en français, inchangées en anglais.

    python -m pytest essai/veille_valeurs_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.util import dt as dt_util

from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401


def test_unites_dans_la_langue():
    from custom_components.pc_parental.veille_tableaux import unite_locale
    assert unite_locale("d", "fr") == "j"
    assert unite_locale("L/d", "fr") == "L/j"
    assert unite_locale("w", "fr") == "sem."
    assert unite_locale("kWh", "fr") == "kWh" and unite_locale("°C", "fr") == "°C" and unite_locale("dB", "fr") == "dB"
    assert unite_locale("cd/m²", "fr") == "cd/m²" and unite_locale("W", "fr") == "W"
    assert unite_locale("L/d", "en") == "L/d" and unite_locale("d", "de") == "d"


def test_date_lisible():
    from custom_components.pc_parental.veille_tableaux import date_lisible
    annee = dt_util.now().year
    fr = date_lisible(f"{annee}-10-09T12:00:46+00:00", "fr")
    assert fr is not None and " oct., " in fr and "T" not in fr and len(fr) <= 14
    en = date_lisible(f"{annee}-10-09T12:00:46+00:00", "en")
    assert en is not None and en.startswith("Oct ") and "oct." not in en
    # 2.5 : six langues (catalogue « veille.date », « veille.mois.<n> ») au lieu de l'ISO hors fr/en.
    de = date_lisible(f"{annee}-10-09T12:00:46+00:00", "de")
    assert de is not None and de.startswith("9. Okt., ") and "T" not in de
    nl = date_lisible(f"{annee}-10-09T12:00:46+00:00", "nl")
    assert nl is not None and nl.startswith("9 okt., ")
    assert date_lisible("1234", "fr") is None and date_lisible("on", "fr") is None
    assert " 2020, " in date_lisible("2020-01-05T10:00:00+00:00", "fr")
    # Sans fuseau (image Frigate) : heure de la maison.
    assert date_lisible(f"{annee}-10-09T05:24:39.009205", "fr") is not None


async def test_cases_exclues_unites_dates(hass):
    from custom_components.pc_parental.veille_tableaux import _case
    await hass.config.async_update(language="fr")
    hass.states.async_set("input_button.recalibrer", "2026-10-09T08:00:46+00:00", {"friendly_name": "Recalibrer"})
    hass.states.async_set("input_number.niveau_lu", "0", {"friendly_name": "Niveau lu", "unit_of_measurement": "L"})
    hass.states.async_set("button.redemarrer", "unknown", {"friendly_name": "Redémarrer"})
    hass.states.async_set("script.arroser", "off", {"friendly_name": "Arroser"})
    hass.states.async_set("input_boolean.mode_test", "on", {"friendly_name": "Mode test"})
    hass.states.async_set("sensor.autonomie", "176", {"friendly_name": "Autonomie", "unit_of_measurement": "d"})
    hass.states.async_set("sensor.conso", "1.9", {"friendly_name": "Consommation", "unit_of_measurement": "L/d"})
    hass.states.async_set("sensor.livraison", "2026-10-09T08:00:46+00:00", {"friendly_name": "Dernière livraison", "device_class": "timestamp"})
    for e in ("input_button.recalibrer", "input_number.niveau_lu", "button.redemarrer", "script.arroser"):
        assert (await _case(hass, {"entite": e})).get("masquer"), e
    assert not (await _case(hass, {"entite": "input_boolean.mode_test"})).get("masquer")
    a = await _case(hass, {"entite": "sensor.autonomie"})
    assert a["unite"] == "j" and a["valeur"] == 176 and not a.get("masquer")
    assert (await _case(hass, {"entite": "sensor.conso"}))["unite"] == "L/j"
    d = await _case(hass, {"entite": "sensor.livraison"})
    assert d["rendu"] == "texte" and d["valeur"] is None and "oct." in d["texte"] and "T" not in d["texte"]
    # En anglais : pas de français.
    await hass.config.async_update(language="en")
    assert (await _case(hass, {"entite": "sensor.autonomie"}))["unite"] == "d"
    d = await _case(hass, {"entite": "sensor.livraison"})
    assert d["texte"].startswith("Oct ") and "oct." not in d["texte"]


async def test_tuiles_sans_action(hass):
    from custom_components.pc_parental import veille
    await hass.config.async_update(language="fr")
    hass.states.async_set("input_number.niveau_lu", "3", {"friendly_name": "Niveau lu"})
    hass.states.async_set("sensor.autonomie", "12", {"friendly_name": "Autonomie", "unit_of_measurement": "d"})
    hass.states.async_set("sensor.vu", "2026-10-09T08:00:46+00:00", {"friendly_name": "Vu"})
    assert veille._tuile(hass, "input_number.niveau_lu") is None
    assert veille._tuile(hass, "sensor.autonomie")["unite"] == "j"
    assert "oct." in veille._tuile(hass, "sensor.vu")["valeur"]


async def test_tableau_de_bord_sans_bouton(hass):
    """Une vue de tableau de bord : le bouton et le champ ne sont pas envoyés, le reste oui."""
    from types import SimpleNamespace

    from custom_components.pc_parental import veille_dash as vd
    await hass.config.async_update(language="fr")
    hass.states.async_set("sensor.cuve", "820", {"friendly_name": "Cuve", "unit_of_measurement": "L"})
    hass.states.async_set("input_button.recalibrer", "2026-10-09T08:00:46+00:00", {"friendly_name": "Recalibrer"})
    hass.states.async_set("input_number.niveau_lu", "0", {"friendly_name": "Niveau lu"})
    vue = {"path": "cuve", "title": "Cuve", "sections": [{"type": "grid", "cards": [
        {"type": "entities", "entities": ["sensor.cuve", "input_button.recalibrer", "input_number.niveau_lu"]}]}]}
    fige = {"le": 1, "nom": "Cuve", "vues": [{"cle": "cuve", "titre": "Cuve", "colonnes": 3, "sections": [
        {"titre": s["titre"], "cartes": [{"cle": f["cle"], "cases": f["cases"]} for f in s["cartes"]]} for s in vd._sections(vue)]}]}
    coord = SimpleNamespace(store=SimpleNamespace(veille_dash={"cuve": {"actif": True, "fige": fige}}))
    t = await vd.tableaux(hass, coord, "tele", {"public": "affichage", "personne": ""})
    entites = [c["entite"] for s in t[0]["sections"] for c in s["cases"]]
    assert entites == ["sensor.cuve"]


async def test_ouvertures_homogenes(hass):
    """l'auteur (15/10) : le bloc « Ouvertures » — capteurs texte « Ouvert / Fermé » et binary_sensor ont tous un voyant ;
    un capteur muet n'est jamais montré fermé ; le type d'ouverture sert à l'icône choisie sur l'appareil."""
    from custom_components.pc_parental.veille_tableaux import _case
    await hass.config.async_update(language="fr")
    hass.states.async_set("sensor.terrasse_portillon", "Ouvert", {"friendly_name": "Portillon", "icon": "mdi:tag-text"})
    hass.states.async_set("sensor.terrasse_store_terrasse_classification", "Fermé", {"friendly_name": "Store de la Terrasse"})
    hass.states.async_set("binary_sensor.portail_avant_ouvert", "off", {"friendly_name": "Portail Avant", "device_class": "opening"})
    hass.states.async_set("sensor.jardin_linge_etendu_classification", "Non", {"friendly_name": "Linge Etendu"})
    hass.states.async_set("binary_sensor.fenetre_salon", "unavailable", {"friendly_name": "Fenêtre salon", "device_class": "window"})
    hass.states.async_set("cover.garage", "open", {"friendly_name": "Garage", "device_class": "garage"})
    hass.states.async_set("lock.entree", "locked", {"friendly_name": "Serrure entrée"})
    hass.states.async_set("sensor.porte_cave", "Geschlossen", {"friendly_name": "Porte cave"})
    hass.states.async_set("sensor.temperature", "21.5", {"friendly_name": "Température", "unit_of_measurement": "°C"})
    vus = {e: await _case(hass, {"entite": e}) for e in (
        "sensor.terrasse_portillon", "sensor.terrasse_store_terrasse_classification", "binary_sensor.portail_avant_ouvert",
        "sensor.jardin_linge_etendu_classification", "binary_sensor.fenetre_salon", "cover.garage", "lock.entree",
        "sensor.porte_cave", "sensor.temperature")}
    # Toutes les ouvertures et le linge : rendu « état », donc un voyant.
    for e, c in vus.items():
        if e != "sensor.temperature":
            assert c["rendu"] == "etat", e
    assert vus["sensor.temperature"]["rendu"] == "valeur"
    assert [vus[e]["on"] for e in ("sensor.terrasse_portillon", "sensor.terrasse_store_terrasse_classification",
                                   "binary_sensor.portail_avant_ouvert", "sensor.jardin_linge_etendu_classification",
                                   "binary_sensor.fenetre_salon", "cover.garage", "lock.entree", "sensor.porte_cave")] == \
        [True, False, False, False, None, True, False, False]
    # Le texte du capteur reste le sien ; un capteur muet se dit « indisponible », jamais « fermé ».
    assert vus["sensor.terrasse_portillon"]["texte"] == "Ouvert"
    assert vus["binary_sensor.fenetre_salon"]["on"] is None
    # Ouvertures et leur type ; le linge n'en est pas une (« Oui / Non », nom sans mot d'ouverture).
    types = {e: (c["ouverture"], c["type_ouverture"]) for e, c in vus.items()}
    assert types["sensor.terrasse_portillon"] == (True, "portail")
    assert types["sensor.terrasse_store_terrasse_classification"] == (True, "store")
    assert types["binary_sensor.portail_avant_ouvert"] == (True, "portail")
    assert types["binary_sensor.fenetre_salon"] == (True, "fenetre")
    assert types["cover.garage"] == (True, "garage")
    assert types["lock.entree"] == (True, "cadenas")
    assert types["sensor.porte_cave"] == (True, "porte")
    assert types["sensor.jardin_linge_etendu_classification"] == (False, "")
    assert types["sensor.temperature"] == (False, "")
