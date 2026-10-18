"""Une demande de site montre toujours son domaine (1.97.5, bug signalé : « site : » sans nom).

Notification, message du décideur, liste « Demandes en attente » (carte Maison, appli), accueil (widget) et
journal : le domaine partout. Une demande de temps n'est jamais présentée comme un site ; une appli d'avant la
1.77.0 (qui l'affichait en « Site : » vide) ne la reçoit pas dans la liste.

    python -m pytest essai/demande_site_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


async def _installer(hass, hass_storage, version_appli="1.77.0"):
    nolan = _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent")
    nolan["agent"] = {"version": version_appli, "platform": "android"}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "PC-CAMILLE", ENFANT["secret"], person="person.camille"),
            "p1": nolan,
        }}}
    hass.states.async_set("person.camille", "home", {"friendly_name": "Camille"})
    hass.states.async_set("person.nolan", "home", {"friendly_name": "Nolan"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def test_le_domaine_partout(hass, hass_storage, hass_client):
    from homeassistant.components import persistent_notification

    from custom_components.pc_parental import parent, veille_plus

    coord = await _installer(hass, hass_storage)
    evenements = []
    hass.bus.async_listen(f"{DOMAIN}_demande_acces", lambda e: evenements.append(e.data))
    client = await hass_client()
    # Comme l'agent PC : un lien complet, sans libellé.
    r = await client.post("/api/pc_parental/demande", json={**ENFANT, "genre": "sites", "nom": "https://www.exemple-devoirs.fr/cours/maths",
                                                            "libelle": "", "motif": "Pour les maths"})
    assert (await r.json())["ok"] is True
    await hass.async_block_till_done()
    d = coord.store.pcs["e1"]["demandes"][0]
    assert d["cle"] == "exemple-devoirs.fr" and d["libelle"]
    domaine = d["cle"]
    # Notification Home Assistant, message de l'appareil du décideur, journal.
    notes = persistent_notification._async_get_or_create_notifications(hass) if hasattr(persistent_notification, "_async_get_or_create_notifications") else {}
    textes = [n["message"] for n in notes.values()] if notes else []
    msg = coord.store.pcs["p1"]["messages"][-1]
    assert domaine in msg["text"] and msg["acces"]["libelle"] and domaine in (msg["acces"]["nom"] + msg["acces"]["libelle"])
    assert not textes or any(domaine in t for t in textes)
    assert evenements and evenements[0]["cle"] == domaine
    # Liste des demandes (carte Maison, appli) et accueil du téléphone.
    liste = parent.etat_maison(hass, coord, "p1")["demandes"]
    site = next(x for x in liste if x["genre"] == "sites")
    assert site["cle"] == domaine and site["libelle"]
    accueil = veille_plus.demandes(hass, coord)
    assert any(x["genre"] == "sites" and domaine in (x["cle"] + x["libelle"]) for x in accueil)
    # Aucune demande de site sans domaine, nulle part.
    for x in liste + accueil:
        if x["genre"] == "sites":
            assert (x.get("cle") or x.get("libelle") or "").strip(), x


async def test_temps_jamais_presente_comme_site(hass, hass_storage, hass_client):
    from custom_components.pc_parental import parent

    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    await client.post("/api/pc_parental/poll", json={**ENFANT, "demande_temps": 15})
    liste = parent.etat_maison(hass, coord, "p1")["demandes"]
    t = next(x for x in liste if x["genre"] == "temps")
    assert t["minutes"] == 15 and "cle" not in t  # l'appli le présente comme du temps, pas comme un site
    # Appli 1.77.0 : la demande de temps est dans la liste.
    r = await client.post("/api/pc_parental/parent", json={**PARENT})
    assert any(x["genre"] == "temps" for x in (await r.json())["demandes"])


async def test_ancienne_appli_sans_demande_de_temps_dans_la_liste(hass, hass_storage, hass_client):
    await _installer(hass, hass_storage, version_appli="1.76.0")
    client = await hass_client()
    await client.post("/api/pc_parental/poll", json={**ENFANT, "demande_temps": 15})
    await client.post("/api/pc_parental/demande", json={**ENFANT, "genre": "sites", "nom": "exemple.fr", "libelle": "exemple.fr"})
    d = await (await client.post("/api/pc_parental/parent", json={**PARENT})).json()
    genres = {x["genre"] for x in d["demandes"]}
    assert genres == {"sites"}  # la 1.76.0 l'aurait affichée en « Site : » vide
