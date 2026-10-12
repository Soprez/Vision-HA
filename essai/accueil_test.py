"""Messages d'accueil personnalisables (Vision → Configurer → Messages d'accueil, accueil.py).

Défaut sans configuration (« {salutation} ! », jamais « la famille »), variables dans la langue de l'appareil,
mode « moment » avec préfixes, mode aléatoire stable par tranche de 30 minutes, modèle Jinja avec repli,
flux d'options de bout en bout et champ `moi.accueil` dans la réponse de /api/pc_parental/veille.

    python -m pytest essai/accueil_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta

from homeassistant.data_entry_flow import FlowResultType
from homeassistant.util import dt as dt_util
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

TELE = {"id": "t1", "secret": "c" * 32}
NB = " "


async def _monter(hass, hass_storage, langue: str = "fr", accueil: dict | None = None):
    hass.config.language = langue
    donnees = {"enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
        "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant", type="telephone"),
        "t1": _fiche("t1", "Téléviseur du salon", TELE["secret"], platform="android", role="enfant", type="tv"),
        "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent"),
    }}
    if accueil is not None:
        donnees["accueil"] = accueil
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set("person.robin", "home", {"friendly_name": "Robin"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id], entree


def _a(heure: int, minute: int = 0, jour: int = 10) -> datetime:
    """Samedi 10 octobre 2026, à l'heure locale de Home Assistant."""
    return datetime(2026, 10, jour, heure, minute, tzinfo=dt_util.get_default_time_zone())


def _accueil():
    from custom_components.pc_parental import accueil

    return accueil


# ----------------------------------------------------------------------------- défaut


async def test_defaut_sans_configuration(hass, hass_storage):
    accueil = _accueil()
    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    attendus = {
        "fr": (f"Bonjour{NB}!", f"Bonsoir{NB}!"),
        "en": ("Hello!", "Good evening!"),
        "es": ("¡Hola!", "¡Buenas noches!"),
        "de": ("Hallo!", "Guten Abend!"),
        "it": ("Ciao!", "Buonasera!"),
        "nl": ("Hallo!", "Goedenavond!"),
    }
    for lg, (jour, soir) in attendus.items():
        assert accueil.rendre(hass, store, lg, "e1", "Lou", _a(10)) == jour, lg
        assert accueil.rendre(hass, store, lg, "e1", "", _a(17, 59)) == jour, lg
        assert accueil.rendre(hass, store, lg, "e1", "", _a(18)) == soir, lg
        assert accueil.rendre(hass, store, lg, "e1", "", _a(2)) == soir, lg
    # Jamais « la famille », dans aucune langue.
    for lg in attendus:
        assert not re.search(r"famil|gezin", accueil.rendre(hass, store, lg, "e1", "", _a(10)), re.I)


# ----------------------------------------------------------------------------- variables


async def test_variables(hass, hass_storage):
    accueil = _accueil()
    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    store.lieu = "École"
    store.accueil = {"messages": "{salutation} {prenom} ! {lieu} · {jour} {date} · {heure} · {meteo} {temperature} · {inconnu}"}
    # Sans entité météo : les deux champs sont vides, la phrase reste propre.
    assert accueil.rendre(hass, store, "fr", "e1", "Lou", _a(9, 5)) == \
        "Bonjour Lou ! École · samedi 10 octobre · 09h05 · · {inconnu}"
    hass.states.async_set("weather.dehors", "sunny", {"temperature": 18.4, "temperature_unit": "°C"})
    assert accueil.rendre(hass, store, "fr", "e1", "Lou", _a(9, 5)) == \
        f"Bonjour Lou ! École · samedi 10 octobre · 09h05 · Ensoleillé 18,4{NB}°C · {{inconnu}}"
    assert accueil.rendre(hass, store, "en", "e1", "Lou", _a(20, 30)) == \
        f"Good evening Lou! École · Saturday October 10 · 20:30 · Sunny 18.4{NB}°C · {{inconnu}}"
    assert accueil.rendre(hass, store, "de", "e1", "", _a(20, 30)) == \
        f"Guten Abend! École · Samstag 10. Oktober · 20:30 · Sonnig 18,4{NB}°C · {{inconnu}}"
    # L'entité choisie l'emporte sur la première trouvée ; une température ronde n'a pas de décimale.
    hass.states.async_set("weather.autre", "rainy", {"temperature": 12.0, "temperature_unit": "°F"})
    store.accueil = {"messages": "{meteo}, {temperature}", "meteo": "weather.autre"}
    assert accueil.rendre(hass, store, "es", "e1", "", _a(9)) == f"Lluvia, 12{NB}°F"
    # Le lieu par défaut (rien de choisi) : le mot neutre de la langue.
    store.lieu = ""
    store.accueil = {"messages": "{lieu}"}
    assert accueil.rendre(hass, store, "it", "e1", "", _a(9)) == "Luogo"


def test_phrase_propre_malgre_un_champ_vide():
    accueil = _accueil()
    assert accueil.nettoyer("Bonjour  !", "fr") == "Bonjour !"
    assert accueil.nettoyer(f"Bonjour {NB}!", "fr") == f"Bonjour{NB}!"
    assert accueil.nettoyer("Bonjour , ça va ?", "fr") == "Bonjour, ça va ?"
    assert accueil.nettoyer("Bonjour, !", "fr") == "Bonjour !"
    assert accueil.nettoyer("Hello, !", "en") == "Hello!"
    assert accueil.nettoyer("¡Hola, !", "es") == "¡Hola!"
    assert accueil.nettoyer("Hallo ,", "de") == "Hallo"
    valeurs = {"salutation": "Bonjour", "prenom": ""}
    assert accueil.nettoyer(accueil.remplir("{salutation} {prenom} !", valeurs), "fr") == "Bonjour !"
    assert accueil.remplir("{salutation} {x} {", valeurs) == "Bonjour {x} {"


async def test_prenom_et_reponse_veille(hass, hass_storage, hass_client_no_auth):
    coord, _ = await _monter(hass, hass_storage, accueil={"messages": "{salutation} {prenom} !"})
    accueil = _accueil()
    store = coord.store
    assert accueil.prenom(hass, coord, store.pcs["e1"]) == "Lou"
    assert accueil.prenom(hass, coord, store.pcs["t1"]) == ""  # écran commun : pas de prénom
    client = await hass_client_no_auth()
    d = await (await client.post("/api/pc_parental/veille", json={**ENFANT, "ecran": "telephone"})).json()
    assert re.fullmatch(r"Bon(jour|soir) Lou !", d["moi"]["accueil"]), d["moi"]
    # Le reste du bloc « moi » ne change pas.
    assert d["moi"]["prenom"] == "Lou" and d["moi"]["nom"] == "TEL-LOU"
    d = await (await client.post("/api/pc_parental/veille", json={**TELE, "ecran": "tele"})).json()
    assert re.fullmatch(r"Bon(jour|soir) !", d["moi"]["accueil"]), d["moi"]
    # La langue de l'appareil.
    store.pcs["e1"]["langue"] = "en"
    d = await (await client.post("/api/pc_parental/veille", json={**ENFANT, "ecran": "telephone"})).json()
    assert re.fullmatch(r"(Hello|Good evening) Lou !", d["moi"]["accueil"]) or \
        re.fullmatch(r"(Hello|Good evening) Lou!", d["moi"]["accueil"]), d["moi"]


# ----------------------------------------------------------------------------- choix du message


async def test_mode_moment_et_prefixes(hass, hass_storage):
    accueil = _accueil()
    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    store.accueil = {"mode": "moment", "messages": "\n".join((
        "matin: Bon matin {prenom}",
        "Après-midi : Bon après-midi",
        "evening: Bonne soirée",
        "Nacht: Bonne nuit",
    ))}
    attendus = {7: "Bon matin Lou", 11: "Bon matin Lou", 12: "Bon après-midi", 17: "Bon après-midi",
                18: "Bonne soirée", 21: "Bonne soirée", 22: "Bonne nuit", 3: "Bonne nuit", 4: "Bonne nuit", 5: "Bon matin Lou"}
    for heure, texte in attendus.items():
        assert accueil.rendre(hass, store, "fr", "e1", "Lou", _a(heure, 10)) == texte, heure
    # Une ligne sans préfixe vaut à tout moment ; un mot qui n'est pas un moment n'est pas un préfixe.
    store.accueil["messages"] += "\nNote: {salutation}"
    vus = {accueil.rendre(hass, store, "fr", "e1", "Lou", _a(9) + timedelta(minutes=30 * i)) for i in range(6)}
    assert vus == {"Bon matin Lou", "Note: Bonjour"}, vus
    # Sans ligne pour ce moment : le défaut.
    store.accueil = {"mode": "moment", "messages": "matin: Bon matin"}
    assert accueil.rendre(hass, store, "fr", "e1", "", _a(15)) == f"Bonjour{NB}!"
    # En mode aléatoire, les préfixes sont ignorés : toutes les lignes tournent.
    store.accueil = {"mode": "aleatoire", "messages": "matin: A\nnuit: B"}
    vus = {accueil.rendre(hass, store, "fr", "e1", "", _a(0) + timedelta(minutes=30 * i)) for i in range(48)}
    assert vus == {"A", "B"}


async def test_aleatoire_stable_par_tranche(hass, hass_storage):
    accueil = _accueil()
    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    store.accueil = {"messages": "\n".join(f"Message {i}" for i in range(8))}
    debut = _a(10, 7)
    tranche = int(debut.timestamp() // 1800) * 1800
    base = datetime.fromtimestamp(tranche, tz=debut.tzinfo)
    premier = accueil.rendre(hass, store, "fr", "e1", "", base)
    for secondes in (0, 20, 40, 300, 900, 1799):
        assert accueil.rendre(hass, store, "fr", "e1", "", base + timedelta(seconds=secondes)) == premier
    jour = [accueil.rendre(hass, store, "fr", "e1", "", base + timedelta(minutes=30 * i)) for i in range(48)]
    assert len(set(jour)) >= 4, jour
    assert any(jour[i] != jour[i + 1] for i in range(47))
    # Chaque appareil a son propre tirage.
    autre = [accueil.rendre(hass, store, "fr", "p1", "", base + timedelta(minutes=30 * i)) for i in range(48)]
    assert autre != jour


# ----------------------------------------------------------------------------- modèle Jinja


async def test_modele_jinja_et_repli(hass, hass_storage, caplog):
    accueil = _accueil()
    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    hass.states.async_set("sensor.score", "42")
    store.accueil = {"messages": "Repli {prenom}",
                     "modele": "{{ salutation }} {{ prenom }} ({{ states('sensor.score') }}) {{ jour }}"}
    assert accueil.rendre(hass, store, "fr", "e1", "Lou", _a(9)) == "Bonjour Lou (42) samedi"
    assert accueil.rendre(hass, store, "fr", "e1", "", _a(9)) == "Bonjour (42) samedi"
    # Validation à l'enregistrement.
    assert accueil.valider_modele(hass, "{{ salutation }}") is None
    assert accueil.valider_modele(hass, "") is None
    assert accueil.valider_modele(hass, "{{ salutation ") is not None
    assert accueil.valider_modele(hass, "{% if %}") is not None
    # Erreur à l'exécution, vide, trop long : le message normal, et une ligne au journal.
    caplog.set_level(logging.WARNING)
    for modele in ("{{ inconnue() }}", "{% if false %}x{% endif %}", "{{ 'x' * 300 }}"):
        caplog.clear()
        store.accueil = {"messages": "Repli {prenom}", "modele": modele}
        assert accueil.rendre(hass, store, "fr", "e1", "Lou", _a(9)) == "Repli Lou", modele
        assert "Accueil" in caplog.text, modele
    store.accueil = {"modele": "{{ inconnue() }}"}
    assert accueil.rendre(hass, store, "en", "e1", "", _a(9)) == "Hello!"


# ----------------------------------------------------------------------------- flux d'options


async def test_flux_options_de_bout_en_bout(hass, hass_storage, hass_client_no_auth):
    coord, entree = await _monter(hass, hass_storage)
    store = coord.store
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    assert flux["type"] == FlowResultType.MENU and "accueil" in flux["menu_options"]
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "accueil"})
    assert flux["type"] == FlowResultType.FORM and flux["step_id"] == "accueil"
    place = flux["description_placeholders"]
    # L'aide : chaque variable avec un exemple ; l'aperçu : le défaut, sans puis avec un prénom d'exemple.
    for nom in ("salutation", "prenom", "lieu", "jour", "date", "heure", "meteo", "temperature"):
        assert f"`{{{nom}}}`" in place["aide"], nom
    assert "Alex" in place["aide"] and "{{ salutation }}" in place["exemple"]
    assert re.search(f"Bon(jour|soir){NB}!", place["apercu"]) and "Alex" in place["apercu"], place["apercu"]
    assert not re.search(r"famil", place["apercu"], re.I)

    # Un modèle invalide : erreur traduite, rien d'enregistré.
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {
        "messages": "{salutation} {prenom} !", "mode": "aleatoire", "avance": {"modele": "{{ salutation "}})
    assert flux["type"] == FlowResultType.FORM and flux["errors"] == {"base": "accueil_modele_invalide"}
    assert flux["description_placeholders"]["erreur"]
    assert store.accueil == {}

    # Enregistré : l'écran de fin montre l'aperçu à jour.
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {
        "messages": "{salutation} {prenom} !\n\nnuit: Dors bien {prenom}", "mode": "moment",
        "meteo": "weather.dehors", "avance": {"modele": ""}})
    assert flux["type"] == FlowResultType.ABORT and flux["reason"] == "accueil_ok"
    assert re.search(r"Bon(jour|soir) Alex !|Dors bien Alex", flux["description_placeholders"]["apercu"]), flux
    await hass.async_block_till_done()
    attendu = {"messages": "{salutation} {prenom} !\nnuit: Dors bien {prenom}", "mode": "moment",
               "meteo": "weather.dehors", "modele": ""}
    assert store.accueil == attendu
    assert hass_storage[DOMAIN]["data"]["accueil"] == attendu

    # Rouvert : les champs reprennent ce qui est enregistré.
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "accueil"})
    schema = {str(k): k for k in flux["data_schema"].schema}
    assert schema["messages"].description["suggested_value"] == attendu["messages"]
    assert schema["mode"].default() == "moment"

    # L'appli reçoit le message dans la réponse /veille.
    client = await hass_client_no_auth()
    d = await (await client.post("/api/pc_parental/veille", json={**ENFANT, "ecran": "telephone"})).json()
    assert re.fullmatch(r"Bon(jour|soir) Lou !|Dors bien Lou", d["moi"]["accueil"]), d["moi"]

    # Tout vider : retour au défaut.
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {
        "mode": "aleatoire", "avance": {}})
    assert flux["type"] == FlowResultType.ABORT
    d = await (await client.post("/api/pc_parental/veille", json={**ENFANT, "ecran": "telephone"})).json()
    assert re.fullmatch(f"Bon(jour|soir){NB}!", d["moi"]["accueil"]), d["moi"]
