"""Widgets Home Assistant dans l'appli (docs/widgets-ha.md), côté serveur, dans un vrai Home Assistant.

- droits : liste de la personne ∪ « plus » − « moins » ; un appareil sans personne : « plus » seulement ;
- lecture refusée sans la fonction de rôle `entites_voir` (403 voir), mauvais secret 401 ;
- action refusée sans `entites_piloter` (403 piloter), hors liste blanche (403 action), entité non permise
  (403 entite), serrure même inscrite à la main (403 action), données non sûres (400 data) ; action permise
  exécutée par un service factice, avec un contexte sans utilisateur ;
- états filtrés ; historique (enregistreur du banc) et sa réduction en logique pure ;
- flux SSE : une entité permise arrive, une non permise jamais ; battement ;
- module « entites » coupé : 403 {"error": "module"} partout ; version des droits dans le relevé ;
- écrans Configurer → « Entités Home Assistant », journalisés.

    python -m pytest essai/widgets_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import asyncio
import json
import logging

import pytest
from homeassistant.core import ServiceCall
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, expected_lingering_tasks, expected_lingering_timers,
)
from homeassistant.setup import async_setup_component


@pytest.fixture(autouse=True)
def _integrations():
    """Remplace celle du banc : l'enregistreur (test_historique) doit être prêt AVANT hass ; `integrations`
    monte http, api, websocket_api et config, et chaque essai le demande après hass."""
    yield


@pytest.fixture
async def integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    yield

FLUX = "/api/config/config_entries/options/flow"
TELE = {"id": "t1", "secret": "t" * 32}
W = "/api/pc_parental/widgets"

ETATS = {
    "light.salon": ("on", {"friendly_name": "Salon", "brightness": 120, "secret_maison": "chut", "icon": "mdi:lamp"}),
    "light.chambre": ("off", {"friendly_name": "Chambre"}),
    "light.cuisine": ("off", {"friendly_name": "Cuisine"}),
    "sensor.temp": ("21.5", {"friendly_name": "Température", "unit_of_measurement": "°C", "device_class": "temperature"}),
    "lock.porte": ("locked", {"friendly_name": "Serrure"}),
    "binary_sensor.porte": ("off", {"friendly_name": "Porte", "device_class": "door"}),
    "climate.salon": ("heat", {"friendly_name": "Thermostat", "temperature": 20, "current_temperature": 19.5,
                               "hvac_modes": ["off", "heat"], "min_temp": 7, "max_temp": 30}),
    "weather.maison": ("sunny", {"friendly_name": "Météo", "temperature": 18}),
    "switch.prise": ("off", {"friendly_name": "Prise"}),
}


def _w():
    from custom_components.pc_parental import widgets

    return widgets


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))


async def _monter(hass, hass_storage, sans_voir=False):
    roles = {
        "parent": {"nom": "Décideur", "fonctions": ["gerer", "approuver", "protege", "entites_voir", "entites_piloter"],
                   "pause_max": 60},
        "enfant": {"nom": "Demandeur", "fonctions": ["protege", "temps"] + ([] if sans_voir else ["entites_voir"]),
                   "pause_max": 60},
    }
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "vocabulaire_neutre": True,
        "roles": roles, "roles_personnes": {},
        "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant"),
            "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android", role="enfant"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
            "t1": _fiche("t1", "TELE-SALON", TELE["secret"], platform="android", role="enfant"),
        },
        "entites_personnes": {
            "person.lou": ["light.salon", "sensor.temp", "lock.porte", "binary_sensor.porte", "climate.salon"],
            "person.nolan": ["light.salon", "light.chambre", "sensor.temp"],
        },
        "entites_appareils": {
            "e1": {"plus": ["weather.maison"], "moins": ["sensor.temp"]},
            "t1": {"plus": ["sensor.temp"], "moins": []},
            "p1": {"plus": ["lock.porte", "climate.salon"], "moins": []},
        },
        # La serrure a été écrite à la main dans le magasin : elle ne doit JAMAIS se piloter.
        "entites_actions": {
            "light.salon": ["toggle", "turn_on", "turn_off"], "light.chambre": ["toggle"],
            "lock.porte": ["unlock", "lock"], "climate.salon": ["set_temperature", "set_hvac_mode"],
        },
    }}
    for p in ("lou", "noa", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    for eid, (etat, attrs) in ETATS.items():
        hass.states.async_set(eid, etat, attrs)
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree, _actuel(hass)


async def _post(client, chemin, qui, **corps):
    r = await client.post(W + chemin, json={**qui, **corps})
    try:
        return r.status, await r.json()
    except Exception:  # noqa: BLE001
        return r.status, await r.text()


def _services(hass):
    """Des services factices qui notent leurs appels (le banc n'a pas les intégrations light, lock…)."""
    appels: list[ServiceCall] = []

    async def noter(call: ServiceCall) -> None:
        appels.append(call)

    for dom, servs in (("light", ("toggle", "turn_on", "turn_off")), ("lock", ("lock", "unlock")),
                       ("climate", ("set_temperature", "set_hvac_mode"))):
        for s in servs:
            hass.services.async_register(dom, s, noter)
    return appels


# ----------------------------------------------------------------------------- Droits


async def test_droits_personne_et_exceptions(hass, integrations, hass_storage, hass_client):
    client = await hass_client()
    _e, coord = await _monter(hass, hass_storage)
    w, store = _w(), coord.store
    assert w.visibles(store, store.pcs["e1"]) == ["light.salon", "lock.porte", "binary_sensor.porte", "climate.salon",
                                                   "weather.maison"]  # − sensor.temp, + météo
    assert w.visibles(store, store.pcs["e2"]) == []  # Noa : pas de liste, pas d'exception
    assert w.visibles(store, store.pcs["t1"]) == ["sensor.temp"]  # sans personne : « plus » seulement
    assert "lock.porte" in w.visibles(store, store.pcs["p1"])
    # Serrure : jamais d'action, même inscrite à la main ; lecture seule pour qui n'a pas « piloter ».
    assert w.actions_permises(store, store.pcs["p1"], "lock.porte") == []
    assert w.actions_permises(store, store.pcs["p1"], "light.salon") == ["toggle", "turn_on", "turn_off"]
    assert w.actions_permises(store, store.pcs["e1"], "light.salon") == []

    st, corps = await _post(client, "/entites", ENFANT)
    assert st == 200, corps
    par = {e["entity_id"]: e for e in corps["entites"]}
    assert list(par) == w.visibles(store, store.pcs["e1"])
    assert "light.chambre" not in par and "sensor.temp" not in par
    assert all(e["actions"] == [] for e in corps["entites"])
    assert "interrupteur" in par["light.salon"]["styles"] and "ouverture" in par["binary_sensor.porte"]["styles"]
    assert "meteo" in par["weather.maison"]["styles"]
    assert par["light.salon"]["attributs_utiles"]["brightness"] == 120
    assert "secret_maison" not in json.dumps(corps)  # jamais tout l'état
    assert par["light.salon"]["nom"] == "Salon" and par["light.salon"]["icone"] == "mdi:lamp"

    st, corps = await _post(client, "/entites", PARENT)
    par = {e["entity_id"]: e for e in corps["entites"]}
    assert par["light.salon"]["actions"] == ["toggle", "turn_on", "turn_off"]
    assert par["lock.porte"]["actions"] == []
    assert {"jauge", "graphique"} <= set(par["sensor.temp"]["styles"]) and par["sensor.temp"]["unite"] == "°C"


async def test_lecture_refusee_sans_entites_voir(hass, integrations, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, sans_voir=True)
    for chemin, corps in (("/entites", {}), ("/etats", {"entites": ["light.salon"]}),
                          ("/historique", {"entite": "light.salon", "periode": "24h"}),
                          ("/action", {"entite": "light.salon", "service": "toggle"}), ("/flux", {})):
        st, rep = await _post(client, chemin, ENFANT, **corps)
        assert (st, rep["error"]) == (403, "voir"), (chemin, rep)
    r = await client.get(W + "/flux", params=ENFANT)
    assert r.status == 403
    # Mauvais secret : 401, rien ne sort.
    st, rep = await _post(client, "/entites", {"id": "e1", "secret": "z" * 32})
    assert (st, rep["error"]) == (401, "auth")
    r = await client.get(W + "/flux", params={"id": "e1", "secret": "z"})
    assert r.status == 401


# ----------------------------------------------------------------------------- Actions


async def test_actions(hass, integrations, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    appels = _services(hass)

    # Sans « piloter » (Lou) : 403 piloter, même pour une entité de sa liste.
    st, rep = await _post(client, "/action", ENFANT, entite="light.salon", service="toggle")
    assert (st, rep["error"]) == (403, "piloter")
    # Entité non permise (pas dans la liste de Nolan).
    st, rep = await _post(client, "/action", PARENT, entite="light.cuisine", service="toggle")
    assert (st, rep["error"]) == (403, "entite")
    # Hors liste blanche : la chambre n'a que « toggle » ; la température n'a rien.
    st, rep = await _post(client, "/action", PARENT, entite="light.chambre", service="turn_on")
    assert (st, rep["error"]) == (403, "action")
    st, rep = await _post(client, "/action", PARENT, entite="sensor.temp", service="turn_on")
    assert (st, rep["error"]) == (403, "action")
    # Serrure : jamais, même inscrite dans le magasin.
    for s in ("unlock", "lock"):
        st, rep = await _post(client, "/action", PARENT, entite="lock.porte", service=s)
        assert (st, rep["error"]) == (403, "action")
    # Données non sûres.
    for data in ({"entity_id": "light.cuisine"}, {"rgb_color": [255, 0, 0]}, {"brightness_pct": 150},
                 {"brightness_pct": True}, "brut", {"brightness_pct": "x"}):
        st, rep = await _post(client, "/action", PARENT, entite="light.salon", service="turn_on", data=data)
        assert (st, rep["error"]) == (400, "data"), data
    st, rep = await _post(client, "/action", PARENT, entite="light.salon", service="toggle", data={"brightness_pct": 10})
    assert (st, rep["error"]) == (400, "data")
    for data in ({"temperature": 80}, {"temperature": 5.5}, {}, {"temperature": 21, "hvac_mode": "heat"}):
        st, rep = await _post(client, "/action", PARENT, entite="climate.salon", service="set_temperature", data=data)
        assert (st, rep["error"]) == (400, "data"), data
    st, rep = await _post(client, "/action", PARENT, entite="climate.salon", service="set_hvac_mode", data={"hvac_mode": "cool"})
    assert (st, rep["error"]) == (400, "data")  # l'entité ne propose pas « cool »
    await hass.async_block_till_done()
    assert appels == []  # rien n'a été exécuté

    # Permis : exécuté, entité ciblée par le serveur, contexte sans utilisateur.
    st, rep = await _post(client, "/action", PARENT, entite="light.salon", service="turn_on", data={"brightness_pct": 40})
    assert (st, rep) == (200, {"ok": True})
    st, rep = await _post(client, "/action", PARENT, entite="climate.salon", service="set_temperature",
                          data={"temperature": 21.3})
    assert st == 200, rep
    st, rep = await _post(client, "/action", PARENT, entite="light.chambre", service="toggle")
    assert st == 200, rep
    assert [(c.domain, c.service) for c in appels] == [("light", "turn_on"), ("climate", "set_temperature"),
                                                         ("light", "toggle")]
    assert appels[0].data["brightness_pct"] == 40 and appels[0].data["entity_id"] in ("light.salon", ["light.salon"])
    assert appels[1].data["temperature"] == 21.5
    assert all(c.context.user_id is None for c in appels)


# ----------------------------------------------------------------------------- États, historique


async def test_etats_filtres(hass, integrations, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    st, rep = await _post(client, "/etats", ENFANT, entites=["light.salon", "light.chambre", "sensor.temp", "lock.porte"])
    assert st == 200
    assert set(rep["etats"]) == {"light.salon", "lock.porte"}
    assert rep["etats"]["light.salon"]["etat"] == "on" and rep["etats"]["light.salon"]["depuis"]
    assert "secret_maison" not in rep["etats"]["light.salon"]["attributs_utiles"]
    st, rep = await _post(client, "/etats", TELE)
    assert list(rep["etats"]) == ["sensor.temp"]


def test_reduction_pure():
    w = _w()
    # 10 000 mesures sur 24 h → au plus 200 points, moyennes justes.
    debut, fin = 0.0, 86400.0
    points = [(i * 8.64, float(i % 2)) for i in range(10000)]
    r = w.reduire_nombres(points, debut, fin)
    assert len(r) == 200 and all(abs(v - 0.5) < 0.05 for _t, v in r)
    assert w.reduire_nombres([(10.0, 3.0), (20.0, 5.0)], debut, fin) == [[216, 4.0]]
    assert w.reduire_nombres([], debut, fin) == []
    # Segments : fusion des états égaux, bornés à la période, 200 au plus (les plus récents).
    seg = w.reduire_etats([(-50.0, "off"), (100.0, "on"), (200.0, "on"), (300.0, "off")], debut, 1000.0)
    assert seg == [[0, 100, "off"], [100, 300, "on"], [300, 1000, "off"]]
    beaucoup = [(float(i), "on" if i % 2 else "off") for i in range(1000)]
    seg = w.reduire_etats(beaucoup, 0.0, 2000.0)
    assert len(seg) == 200 and seg[-1] == [999, 2000, "on"]


async def test_historique(recorder_mock, hass, integrations, hass_storage, hass_client):
    from homeassistant.components.recorder.util import get_instance

    client = await hass_client()
    await _monter(hass, hass_storage)
    for v in ("20", "21", "22"):
        hass.states.async_set("sensor.temp", v, ETATS["sensor.temp"][1])
        hass.states.async_set("light.salon", "off" if v == "21" else "on", ETATS["light.salon"][1])
        await hass.async_block_till_done()
    await get_instance(hass).async_block_till_done()
    st, rep = await _post(client, "/historique", PARENT, entite="sensor.temp", periode="24h")
    assert st == 200, rep
    assert rep["type"] == "nombre" and rep["unite"] == "°C" and 1 <= len(rep["points"]) <= 200
    assert 20 <= rep["points"][-1][1] <= 22
    st, rep = await _post(client, "/historique", PARENT, entite="light.salon", periode="7j")
    assert st == 200 and rep["type"] == "etats" and rep["segments"][-1][2] == "on"
    # Période inconnue, entité non permise.
    st, rep = await _post(client, "/historique", PARENT, entite="sensor.temp", periode="30j")
    assert (st, rep["error"]) == (400, "periode")
    st, rep = await _post(client, "/historique", ENFANT, entite="sensor.temp", periode="24h")
    assert (st, rep["error"]) == (403, "entite")


# ----------------------------------------------------------------------------- Flux SSE


async def _lignes(reponse, jusqua, delai=5.0):
    vues = []
    async with asyncio.timeout(delai):
        while True:
            ligne = (await reponse.content.readline()).decode()
            vues.append(ligne)
            if jusqua(ligne):
                return vues


async def test_flux_filtre(hass, integrations, hass_storage, hass_client, monkeypatch):
    client = await hass_client()
    await _monter(hass, hass_storage)
    monkeypatch.setattr(_w().PcParentalWidgetsFluxView, "battement", 0.3)
    r = await client.get(W + "/flux", params=ENFANT)
    assert r.status == 200 and r.headers["Content-Type"].startswith("text/event-stream")
    await _lignes(r, lambda l: l.startswith("retry"))
    hass.states.async_set("light.chambre", "on", {"friendly_name": "Chambre"})  # non permise à Lou
    hass.states.async_set("sensor.temp", "30", ETATS["sensor.temp"][1])  # retirée par « moins »
    hass.states.async_set("light.salon", "off", ETATS["light.salon"][1])  # permise
    vues = await _lignes(r, lambda l: "light.salon" in l)
    donnees = json.loads(vues[-1][len("data: "):])
    assert donnees["entity_id"] == "light.salon" and donnees["etat"] == "off"
    assert "secret_maison" not in vues[-1]
    vues += await _lignes(r, lambda l: l.startswith(": battement"))
    hass.states.async_set("light.chambre", "off", {"friendly_name": "Chambre"})
    vues += await _lignes(r, lambda l: l.startswith(": battement"))
    assert not any("light.chambre" in l or "sensor.temp" in l for l in vues)
    # Module coupé en cours de route : le flux se ferme proprement.
    store = _actuel(hass).store
    _modules().regler(hass, store, set(_modules().TOUS) - {"entites", "veille_tableaux"}, "essai")
    vues = await _lignes(r, lambda l: l.startswith("event: fin"))
    r.close()


def _modules():
    from custom_components.pc_parental import modules

    return modules


# ----------------------------------------------------------------------------- Module, relevé


async def test_module_coupe(hass, integrations, hass_storage, hass_client):
    client = await hass_client()
    _e, coord = await _monter(hass, hass_storage)
    _services(hass)
    _modules().regler(hass, coord.store, set(_modules().TOUS) - {"entites", "veille_tableaux"}, "essai")
    for qui in (ENFANT, PARENT):
        for chemin, corps in (("/entites", {}), ("/etats", {}), ("/historique", {"entite": "light.salon"}),
                              ("/action", {"entite": "light.salon", "service": "toggle"}), ("/flux", {})):
            st, rep = await _post(client, chemin, qui, **corps)
            assert (st, rep["error"]) == (403, "module"), (chemin, rep)
    r = await client.get(W + "/flux", params=PARENT)
    assert r.status == 403 and (await r.json())["error"] == "module"


async def test_version_dans_le_releve(hass, integrations, hass_storage, hass_client, caplog):
    client = await hass_client()
    await _monter(hass, hass_storage)

    async def releve(qui):
        r = await client.post("/api/pc_parental/poll", json={**qui, "version": "1", "host": "x", "platform": "android",
                                                              "type": "telephone"})
        assert r.status == 200
        return (await r.json())["widgets"]["version"]

    v1, v_nolan = await releve(ENFANT), await releve(PARENT)
    assert isinstance(v1, int) and v1 == await releve(ENFANT)  # stable tant que rien ne change
    caplog.set_level(logging.WARNING)
    _w().regler_personne(hass, _actuel(hass).store, "person.lou", ["light.salon"], "essai")
    assert "person.lou" in caplog.text and "-sensor.temp" in caplog.text
    v2 = await releve(ENFANT)
    assert v2 != v1 and await releve(PARENT) == v_nolan  # seuls les droits de Lou ont changé
    _w().regler_actions(hass, _actuel(hass).store, {"light.salon": ["toggle"]}, "essai")
    assert await releve(PARENT) != v_nolan


# ----------------------------------------------------------------------------- Écrans Configurer


async def _suite(admin, flux, corps):
    r = await admin.post(f"{FLUX}/{flux['flow_id']}", json=corps)
    assert r.status == 200, await r.text()
    return await r.json()


async def _menu(admin, entree):
    flux = await (await admin.post(FLUX, json={"handler": entree.entry_id})).json()
    flux = await _suite(admin, flux, {"next_step_id": "autres"})
    opts = flux["menu_options"]
    assert opts.index("entites") == opts.index("general") - 1  # lot 5 : Modules est dans Réglages › Réservé
    return await _suite(admin, flux, {"next_step_id": "entites"})


async def test_ecrans_configurer(hass, integrations, hass_storage, hass_client, caplog):
    admin = await hass_client()
    entree, _coord = await _monter(hass, hass_storage)
    caplog.set_level(logging.WARNING)
    w = _w()

    # Personne → sa liste.
    flux = await _menu(admin, entree)
    assert flux["type"] == "menu" and flux["menu_options"] == ["entites_personnes", "entites_appareils", "entites_actions"]
    assert "Lou" in flux["description_placeholders"]["resume"]
    flux = await _suite(admin, flux, {"next_step_id": "entites_personnes"})
    flux = await _suite(admin, flux, {"personne": "person.noa"})
    assert flux["step_id"] == "entites_personne_liste" and flux["description_placeholders"]["personne"] == "Noa"
    flux = await _suite(admin, flux, {"entites": ["switch.prise", "light.cuisine"]})
    assert flux["type"] == "create_entry"
    store = _actuel(hass).store
    assert store.entites_personnes["person.noa"] == ["switch.prise", "light.cuisine"]
    assert hass_storage[DOMAIN]["data"]["entites_personnes"]["person.noa"] == ["switch.prise", "light.cuisine"]
    assert "person.noa : +switch.prise, +light.cuisine" in caplog.text

    # Appareil → plus / moins.
    flux = await _menu(admin, entree)
    flux = await _suite(admin, flux, {"next_step_id": "entites_appareils"})
    flux = await _suite(admin, flux, {"appareil": "e2"})
    assert flux["step_id"] == "entites_appareil_liste"
    assert "switch.prise" in flux["description_placeholders"]["liste"]
    flux = await _suite(admin, flux, {"plus": ["weather.maison"], "moins": ["light.cuisine"]})
    assert flux["type"] == "create_entry"
    store = _actuel(hass).store
    assert w.visibles(store, store.pcs["e2"]) == ["switch.prise", "weather.maison"]
    assert "TEL-NOA" in caplog.text

    # Actions permises : seulement les entités pilotables données à quelqu'un ; serrure jamais proposée.
    flux = await _menu(admin, entree)
    flux = await _suite(admin, flux, {"next_step_id": "entites_actions"})
    assert flux["step_id"] == "entites_actions"
    champs = [c["name"] for c in flux["data_schema"]]
    assert "light.salon" in champs and "switch.prise" in champs and "climate.salon" in champs
    assert "lock.porte" not in champs and "sensor.temp" not in champs and "weather.maison" not in champs
    reponse = {c: [] for c in champs}
    reponse.update({"switch.prise": ["toggle"], "light.salon": ["turn_on", "turn_off"]})
    flux = await _suite(admin, flux, reponse)
    assert flux["type"] == "create_entry"
    store = _actuel(hass).store
    assert store.entites_actions["switch.prise"] == ["toggle"]
    assert store.entites_actions["light.salon"] == ["turn_on", "turn_off"]
    assert "climate.salon" not in store.entites_actions  # décoché : lecture seule
    assert "actions permises" in caplog.text
    # Même par le code, une serrure ou une alarme ne devient jamais pilotable.
    w.regler_actions(hass, store, {"lock.porte": ["unlock"], "alarm_control_panel.maison": ["alarm_disarm"]}, "essai")
    assert w.services_proposes("lock.porte") == [] and w.services_proposes("alarm_control_panel.maison") == []
