"""2.2.12 (veille, lot) : les cases typées (docs/veille-contrat.md §4.2) et la porte de leurs images (§4.3).

- chaque carte (weather-forecast, calendar, picture-elements, picture-entity, iframe, carte Frigate) devient la
  bonne case, la même pour le PC et la télé, avec un texte de repli pour les anciennes veilles ;
- prévisions et agenda gardés quelques minutes (un seul appel pour plusieurs relevés) ;
- une image ne sort que par sa clé, pour l'appareil qui l'a reçue ; jamais une adresse arbitraire.

    python -m pytest essai/veille_typees_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import datetime
import os

from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import SupportsResponse
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.util import dt as dt_util
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)


def _typee(carte):
    from custom_components.pc_parental import veille_cases
    return veille_cases.carte_typee(carte, None)


def test_cartes_vers_cases():
    assert _typee({"type": "weather-forecast", "entity": "weather.maison"})[0]["genre"] == "meteo"
    agenda = _typee({"type": "calendar", "entities": ["calendar.a", {"entity": "calendar.b"}, "sensor.x"]})[0]
    assert agenda["genre"] == "agenda" and agenda["entites"] == ["calendar.a", "calendar.b"]
    plan = _typee({"type": "picture-elements", "image": "/local/plan.png", "elements": [
        {"type": "state-label", "entity": "sensor.salon", "style": {"left": "40%", "top": "60%"}, "prefix": "Salon "},
        {"type": "state-icon", "entity": "binary_sensor.porte", "style": {"left": "10%", "top": "20%"}},
        {"type": "state-label", "entity": "sensor.sans_position"}]})[0]
    assert plan["genre"] == "image" and plan["fond"] == "/local/plan.png" and len(plan["elements"]) == 2
    radar = _typee({"type": "picture-elements", "image": "/local/radar-fond.jpg", "elements": [
        {"type": "image", "camera_image": "camera.radar", "style": {"left": "50%", "top": "50%", "width": "100%"}}]})[0]
    assert radar["genre"] == "radar" and radar["entite"] == "camera.radar"
    # Fond externe ou interne arbitraire : jamais repris.
    assert _typee({"type": "picture-elements", "image": "http://192.0.2.1/x.jpg", "elements": []}) == []
    assert _typee({"type": "picture-elements", "image": "/local/../secrets.yaml", "elements": []}) == []
    assert _typee({"type": "picture-entity", "entity": "camera.jardin"})[0]["genre"] == "camera"
    assert _typee({"type": "picture-glance", "camera_image": "camera.jardin", "entities": []})[0]["entite"] == "camera.jardin"
    assert _typee({"type": "iframe", "url": "https://exemple.org/trafic", "title": "Trafic"})[0]["url"] == "https://exemple.org/trafic"
    assert _typee({"type": "iframe", "url": "/api/hassio_ingress/x"}) == []
    clips = _typee({"type": "custom:advanced-camera-card", "cameras": [{"camera_entity": "camera.jardin"}]})[0]
    assert clips["genre"] == "clips" and clips["cameras"] == ["camera.jardin"]
    assert _typee({"type": "tile", "entity": "sensor.x"}) is None


async def test_meteo_et_agenda_en_cache(hass):
    from custom_components.pc_parental import veille_cases
    veille_cases._cache_meteo.clear()
    veille_cases._cache_agenda.clear()
    await hass.config.async_update(language="fr")
    appels = {"meteo": 0, "agenda": 0}

    async def previsions(call):
        appels["meteo"] += 1
        return {e: {"forecast": [{"datetime": f"2026-10-{12 + i}T00:00:00+00:00", "condition": "rainy",
                                  "temperature": 17, "templow": 9, "precipitation_probability": 60} for i in range(9)]}
                for e in ["weather.maison"]}

    async def evenements(call):
        appels["agenda"] += 1
        debut = dt_util.now().replace(microsecond=0)
        return {"calendar.famille": {"events": [
            {"start": (debut + datetime.timedelta(hours=2)).isoformat(), "end": (debut + datetime.timedelta(hours=3)).isoformat(), "summary": "Piscine"},
            {"start": debut.date().isoformat(), "end": debut.date().isoformat(), "summary": "Férié"}]}}

    hass.services.async_register("weather", "get_forecasts", previsions, supports_response=SupportsResponse.ONLY)
    hass.services.async_register("calendar", "get_events", evenements, supports_response=SupportsResponse.ONLY)
    hass.states.async_set("weather.maison", "sunny", {"friendly_name": "Forecast Maison", "temperature": 18.4, "temperature_unit": "°C"})
    hass.states.async_set("calendar.famille", "off", {"friendly_name": "Famille"})
    m = await veille_cases.resoudre(hass, {"genre": "meteo", "entite": "weather.maison"}, "t1")
    m2 = await veille_cases.resoudre(hass, {"genre": "meteo", "entite": "weather.maison"}, "t1")
    assert appels["meteo"] == 1 and m == m2
    assert m["genre"] == "meteo" and m["etat"] == "sunny" and m["etat_texte"] == "Ensoleillé" and m["temperature"] == 18.4
    assert len(m["previsions"]) == 5 and m["previsions"][0]["max"] == 17 and m["previsions"][0]["pluie"] == 60
    assert m["rendu"] == "texte" and m["texte"] == "Ensoleillé · 18°C" and m["nom"] == "Maison"
    a = await veille_cases.resoudre(hass, {"genre": "agenda", "entites": ["calendar.famille"]}, "t1")
    await veille_cases.resoudre(hass, {"genre": "agenda", "entites": ["calendar.famille"]}, "t1")
    assert appels["agenda"] == 1
    assert [e["titre"] for e in a["evenements"]] == ["Férié", "Piscine"] and a["evenements"][0]["journee"]
    assert a["evenements"][1]["debut"].endswith(dt_util.now().isoformat()[-6:]) and a["nom"] == "Famille"
    # En anglais : pas de français.
    veille_cases._cache_meteo.clear()
    await hass.config.async_update(language="en")
    assert (await veille_cases.resoudre(hass, {"genre": "meteo", "entite": "weather.maison"}, "t1"))["etat_texte"] == "Sunny"


async def test_image_camera_iframe(hass):
    from custom_components.pc_parental import veille_cases
    await hass.config.async_update(language="fr")
    hass.states.async_set("sensor.salon", "21.5", {"friendly_name": "Salon", "unit_of_measurement": "°C"})
    hass.states.async_set("binary_sensor.porte", "on", {"friendly_name": "Porte", "device_class": "door"})
    hass.states.async_set("input_number.consigne", "20", {"friendly_name": "Consigne"})
    hass.states.async_set("camera.jardin", "idle", {"friendly_name": "Jardin"})
    hass.states.async_set("camera.jardin_sub", "idle", {"friendly_name": "Jardin (léger)"})
    hass.states.async_set("camera.entree", "idle", {"friendly_name": "Entrée"})
    plan = _typee({"type": "picture-elements", "image": "/local/plan.png", "title": "Plan", "elements": [
        {"type": "state-label", "entity": "sensor.salon", "style": {"left": "40%", "top": "60%"}, "prefix": "Salon "},
        {"type": "state-icon", "entity": "binary_sensor.porte", "style": {"left": "10%", "top": "20%"}},
        {"type": "state-label", "entity": "input_number.consigne", "style": {"left": "1%", "top": "1%"}},
        {"type": "image", "camera_image": "camera.entree", "style": {"left": "80%", "top": "80%", "width": "20%"}}]})[0]
    r = await veille_cases.resoudre(hass, plan, "t1")
    assert r["genre"] == "image" and r["url"].startswith("/api/pc_parental/veille/media/")
    assert r["elements"] == [{"x": 40.0, "y": 60.0, "texte": "Salon 21,5 °C"}, {"x": 10.0, "y": 20.0, "texte": "ouverte"}]
    assert r["calques"][0]["entite"] == "camera.entree" and r["calques"][0]["largeur"] == 20.0
    assert "plan.png" not in str(r)  # jamais l'adresse d'origine
    cam = await veille_cases.resoudre(hass, _typee({"type": "picture-entity", "entity": "camera.jardin"})[0], "t1")
    assert cam["direct"] == "camera.jardin_sub" and cam["nom"] == "Jardin" and cam["url"].startswith("/api/pc_parental/veille/media/")
    assert (await veille_cases.resoudre(hass, _typee({"type": "picture-entity", "entity": "camera.entree"})[0], "t1"))["direct"] == ""
    assert await veille_cases.resoudre(hass, {"genre": "camera", "entite": "camera.disparue"}, "t1") is None
    f = await veille_cases.resoudre(hass, _typee({"type": "iframe", "url": "https://exemple.org/", "title": "Trafic"})[0], "t1")
    assert f == {"genre": "iframe", "rendu": "texte", "entite": "", "url": "https://exemple.org/", "nom": "Trafic", "texte": "Trafic"}
    # La clé ne vaut que pour l'appareil qui l'a reçue.
    cle = cam["url"].rsplit("/", 1)[1]
    assert veille_cases.source_permise(cle, "t1") == "camera:camera.jardin"
    assert veille_cases.source_permise(cle, "autre") is None and veille_cases.source_permise("inconnue", "t1") is None


async def test_derniers_passages_frigate(hass):
    from custom_components.pc_parental import veille_cases
    await hass.config.async_update(language="fr")
    carte = _typee({"type": "custom:frigate-card", "cameras": [{"camera_entity": "camera.jardin"}]})[0]
    assert await veille_cases.resoudre(hass, carte, "t1") is None  # sans Frigate : rien
    frigate = MockConfigEntry(domain="frigate", data={}, state=ConfigEntryState.LOADED)
    frigate.add_to_hass(hass)
    appareil = dr.async_get(hass).async_get_or_create(config_entry_id=frigate.entry_id, identifiers={("frigate", "jardin")})
    reg = er.async_get(hass)
    reg.async_get_or_create("camera", "frigate", f"{frigate.entry_id}:camera:jardin", suggested_object_id="jardin",
                            device_id=appareil.id, config_entry=frigate)
    hass.states.async_set("camera.jardin", "recording", {"friendly_name": "Jardin", "camera_name": "jardin"})
    for objet, quand in (("person", "2026-10-11T05:24:39.009205"), ("car", "2026-10-10T18:00:00.000000"), ("dog", "unknown")):
        e = reg.async_get_or_create("image", "frigate", f"{frigate.entry_id}:image_best_snapshot:jardin_{objet}",
                                    suggested_object_id=f"jardin_{objet}", device_id=appareil.id, config_entry=frigate,
                                    original_name=objet.capitalize())
        hass.states.async_set(e.entity_id, quand, {"friendly_name": f"Jardin {objet}"})
    r = await veille_cases.resoudre(hass, carte, "t1")
    assert r["genre"] == "clips" and [x["objet"] for x in r["liste"]] == ["person", "car"]
    assert r["liste"][0]["objet_nom"] == "Personne" and r["liste"][0]["camera"] == "Jardin"
    assert r["liste"][0]["quand"].startswith("2026-10-11T05:24:39") and r["liste"][0]["vignette"].startswith("/api/pc_parental/veille/media/")
    assert veille_cases.source_permise(r["liste"][0]["vignette"].rsplit("/", 1)[1], "t1") == "image:image.jardin_person"


async def test_releve_et_porte_des_images(hass, hass_storage, hass_client_no_auth):
    """Bout en bout : le relevé de la télé porte les cases typées ; l'image du fond sort par sa clé, pour cette
    télé seulement ; le PC la lit avec son ticket ; la caméra d'une case typée devient une caméra permise."""
    from custom_components.pc_parental import veille, veille_dash

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "PC-SALON", ENFANT["secret"], platform="windows"),
            "e2": _fiche("e2", "TELE", ENFANT2["secret"], platform="android", type="tv"),
        }}}
    www = hass.config.path("www")
    os.makedirs(www, exist_ok=True)
    with open(os.path.join(www, "plan-essai.png"), "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\nessai")
    hass.states.async_set("sensor.salon", "21.5", {"friendly_name": "Salon", "unit_of_measurement": "°C"})
    hass.states.async_set("camera.jardin", "idle", {"friendly_name": "Jardin"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    coord.store.modules = {**(coord.store.modules or {}), "veille": True, "veille_tableaux": True}
    vue = {"path": "maison", "title": "Maison", "sections": [{"type": "grid", "cards": [
        {"type": "picture-elements", "image": "/local/plan-essai.png", "elements": [
            {"type": "state-label", "entity": "sensor.salon", "style": {"left": "40%", "top": "60%"}}]},
        {"type": "picture-entity", "entity": "camera.jardin"},
        {"type": "entities", "entities": ["sensor.salon"]}]}]}
    fige = {"le": 1, "nom": "Maison", "vues": [{"cle": "maison", "titre": "Maison", "colonnes": 3, "sections": [
        {"titre": s["titre"], "cartes": [{"cle": f["cle"], "cases": f["cases"]} for f in s["cartes"]]} for s in veille_dash._sections(vue)]}]}
    coord.store.veille_dash = {"maison": {"actif": True, "fige": fige}}
    # Relecture sécurité : la caméra d'une case typée n'entre jamais dans la liste commune de la veille.
    assert "camera.jardin" not in veille.cameras_permises(coord)
    client = await hass_client_no_auth()
    d = await (await client.post("/api/pc_parental/veille", json={**ENFANT2, "ecran": "tele"})).json()
    cases = [c for t in d["tableaux"]["liste"] if t["code"] == "dash" for s in t["sections"] for c in s["cases"]]
    assert [c.get("genre") for c in cases] == ["image", "camera", None]
    image = cases[0]
    assert image["elements"][0]["texte"].startswith("21") and image["rendu"] == "texte"
    r = await client.post(image["url"], json={**ENFANT2})
    assert r.status == 200 and r.content_type == "image/png" and (await r.read()).endswith(b"essai")
    # Un autre appareil (même inscrit) n'a pas reçu cette clé : refusé, comme une clé inventée ou un faux secret.
    assert (await client.post(image["url"], json={**ENFANT})).status == 404
    assert (await client.post("/api/pc_parental/veille/media/0123456789abcdef01234567", json={**ENFANT2})).status == 404
    assert (await client.post(image["url"], json={"id": "e2", "secret": "z" * 32})).status == 401
    # Le flux de la caméra : permis à l'appareil qui a reçu la case, refusé à un autre appareil.
    flux_autre = await client.post("/api/pc_parental/veille/image", json={**ENFANT, "entite": "camera.jardin"})
    assert flux_autre.status == 404
    # La page de veille de l'autre appareil : son propre relevé, puis lecture par le ticket que /veille/acces lui remet
    # (POST seulement : jamais de ticket dans une adresse). La veille des PC (ancien « ticket_images ») n'est plus reprise.
    from custom_components.pc_parental import veille_passerelle
    p = await (await client.post("/api/pc_parental/veille", json={**ENFANT, "ecran": "tele"})).json()
    image_pc = next(c for t in p["tableaux"]["liste"] if t["code"] == "dash" for s in t["sections"] for c in s["cases"] if c.get("genre") == "image")
    jeton = veille_passerelle.ticket(coord.store.pcs[ENFANT["id"]])
    assert (await client.post(image_pc["url"], json={"ticket": jeton})).status == 200
    assert (await client.get(image_pc["url"], params={"ticket": jeton})).status == 405


def test_camera_d_une_case_permise_pour_cet_appareil_seulement():
    # Relecture sécurité : une caméra d'une case typée n'ouvre l'image ni le flux qu'à l'appareil pour lequel la case
    # a été résolue (plus de liste blanche commune) ; le flux léger « _sub » suit ; la permission expire.
    from custom_components.pc_parental import veille_cases
    veille_cases._cameras_pc.clear()
    veille_cases.media("camera:camera.salon", "decideur")
    assert veille_cases.camera_permise("decideur", "camera.salon")
    assert veille_cases.camera_permise("decideur", "camera.salon_sub")
    assert not veille_cases.camera_permise("demandeur", "camera.salon")
    assert not veille_cases.camera_permise("", "camera.salon")
    veille_cases._cameras_pc["decideur"]["camera.salon"] = 0
    assert not veille_cases.camera_permise("decideur", "camera.salon")


def test_cle_media_prolongee_a_l_usage_jamais_au_dela_de_24_h():
    # Une page de veille ouverte longtemps garde ses images tant que SON appareil s'en sert ; au plus 24 h sans
    # nouveau relevé ; un autre appareil, jamais.
    import time as _t

    from custom_components.pc_parental import veille_cases
    url = veille_cases.media("camera:camera.jardin", "tele")
    cle = url.rsplit("/", 1)[1]
    e = veille_cases._medias[cle]
    e["pcs"]["tele"] = _t.time() + 5                     # presque expirée…
    assert veille_cases.source_permise(cle, "tele") == "camera:camera.jardin"
    assert e["pcs"]["tele"] > _t.time() + 60             # …prolongée par l'usage
    assert veille_cases.source_permise(cle, "autre") is None
    e["debut"]["tele"] = _t.time() - 25 * 3600           # émise il y a plus de 24 h, sans relevé depuis
    e["pcs"]["tele"] = _t.time() + 5
    veille_cases.source_permise(cle, "tele")
    assert e["pcs"]["tele"] <= _t.time() + 5             # plus prolongée
    veille_cases.media("camera:camera.jardin", "tele")   # un relevé redonne le droit
    assert e["pcs"]["tele"] > _t.time() + 60
