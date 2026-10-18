"""Refonte UX, lot 3 : la fiche d'une personne (B2, HA-05, HA-20, EX-05).

L'API de la carte (réservée aux administrateurs) : la fiche, ses onglets selon les modules, le passage d'une personne
à l'autre ; les réglages qui n'existaient que dans Configurer (rôle, récompenses, règles selon le lieu) ; le résumé
donné à l'appli dans /parent ; la page personne du tableau devient la fiche.

    python -m pytest essai/personne_fiche_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from octroi_banc import CODE_OCTROI, poser_code_octroi
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

URL = "/api/pc_parental/personne/admin"


async def _installer(hass, hass_storage, modules=None):
    data = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "entites_personnes": {"person.lou": ["sensor.salon"]},
        "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone"),
            "e2": _fiche("e2", "TAB-NOA", ENFANT2["secret"], person="person.noa", platform="android", type="tablette"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        },
    }
    if modules is not None:
        data["modules"] = modules
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": data}
    for p, nom in (("person.lou", "Lou"), ("person.noa", "Noa"), ("person.nolan", "Nolan")):
        hass.states.async_set(p, "home", {"friendly_name": nom})
    hass.states.async_set("todo.taches_lou", "2", {"friendly_name": "Tâches de Lou"})
    hass.states.async_set("zone.college", "0", {"friendly_name": "Collège"})
    hass.states.async_set("sensor.salon", "21.5", {"friendly_name": "Température salon"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))


async def test_fiche_onglets_et_passage(hass, hass_storage, hass_client):
    await _installer(hass, hass_storage)
    poser_code_octroi(hass)
    client = await hass_client()
    r = await client.get(URL, params={"personne": "person.lou"})
    assert r.status == 200
    d = await r.json()
    assert d["personne"]["prenom"] == "Lou" and d["principal"] == "e1"
    # Les onglets de l'architecture cible, dans l'ordre (Réveil est un module inactif par défaut : pas d'onglet).
    assert d["onglets"] == ["resume", "horaires", "contenus", "recompenses", "ecrans", "role"]
    # ‹ › : les personnes qui ont une page, dans l'ordre du tableau, avec le chemin de leur page.
    assert [p["prenom"] for p in d["personnes"]] == ["Lou", "Noa", "Nolan"]
    assert d["personnes"][0]["chemin"] == "personne-lou"
    assert d["widgets"] == 1 and d["planning"].startswith("sensor.")
    assert {"id": "todo.taches_lou", "nom": "Tâches de Lou"} in d["listes"]
    assert any(z["id"] == "zone.college" for z in d["zones"])
    # Une personne inconnue : 404 en clair.
    r = await client.get(URL, params={"personne": "person.inconnue"})
    assert r.status == 404 and (await r.json())["retour"]


async def test_reglages_de_la_fiche(hass, hass_storage, hass_client):
    await _installer(hass, hass_storage)
    poser_code_octroi(hass)
    client = await hass_client()
    # Récompenses : une liste, 15 min par tâche, 60 au plus.
    r = await client.post(URL, json={"code_octroi": CODE_OCTROI, "op": "temps_gagne", "personne": "person.lou", "liste": "todo.taches_lou", "minutes": 15, "max": 60})
    assert r.status == 200
    d = await r.json()
    assert d["recompense"]["liste"] == "todo.taches_lou" and d["recompense"]["minutes"] == 15 and d["fait"]
    assert _actuel(hass).store.temps_gagne["person.lou"]["max"] == 60
    # Règle selon le lieu : au collège, une catégorie fermée ; puis retirée.
    cat = d["categories"][0]["id"] if d["categories"] else "Jeux"
    r = await client.post(URL, json={"op": "regle_lieu", "personne": "person.lou", "zone": "zone.college", "etiquettes": [cat]})
    d = await r.json()
    assert r.status == 200 and d["regles"][0]["zone_nom"] == "Collège" and d["regles"][0]["etiquettes"] == [cat]
    r = await client.post(URL, json={"op": "regle_lieu", "personne": "person.lou", "zone": "zone.college", "etiquettes": []})
    assert (await r.json())["regles"] == []
    # Rôle : un rôle existant, puis le rôle par défaut.
    roles = list(_actuel(hass).store.roles)
    r = await client.post(URL, json={"code_octroi": CODE_OCTROI, "op": "role", "personne": "person.lou", "role": roles[0]})
    d = await r.json()
    assert r.status == 200 and d["role"]["actuel"] == roles[0]
    r = await client.post(URL, json={"code_octroi": CODE_OCTROI, "op": "role", "personne": "person.lou", "role": ""})
    assert (await r.json())["role"]["actuel"] == ""
    # Demandes fausses : refusées en clair (400), rien de changé.
    r = await client.post(URL, json={"code_octroi": CODE_OCTROI, "op": "temps_gagne", "personne": "person.lou", "liste": "todo.taches_lou", "minutes": 0, "max": 60})
    assert r.status == 400 and (await r.json())["retour"]
    r = await client.post(URL, json={"op": "nimporte", "personne": "person.lou"})
    assert r.status == 400
    r = await client.post(URL, json={"code_octroi": CODE_OCTROI, "op": "role", "personne": "person.inconnue", "role": ""})
    assert r.status == 400


async def test_module_coupe_pas_d_onglet(hass, hass_storage, hass_client):
    from custom_components.pc_parental import modules as mods
    await _installer(hass, hass_storage)
    poser_code_octroi(hass)
    coord = _actuel(hass)
    coord.store.modules = {**dict(coord.store.modules or {}), "temps_gagne": False, "reveil": False}
    client = await hass_client()
    d = await (await client.get(URL, params={"personne": "person.lou"})).json()
    assert "recompenses" not in d["onglets"] and "reveils" not in d["onglets"]
    # Et le réglage est refusé (pas d'écran qui accepte puis refuse : EX-05).
    r = await client.post(URL, json={"code_octroi": CODE_OCTROI, "op": "temps_gagne", "personne": "person.lou", "liste": "todo.taches_lou", "minutes": 15, "max": 60})
    assert r.status == 400


async def test_reserve_aux_admins(hass, hass_storage, hass_client, hass_read_only_access_token):
    await _installer(hass, hass_storage)
    poser_code_octroi(hass)
    client = await hass_client(hass_read_only_access_token)
    assert (await client.get(URL, params={"personne": "person.lou"})).status == 403
    assert (await client.post(URL, json={"code_octroi": CODE_OCTROI, "op": "role", "personne": "person.lou", "role": ""})).status == 403


async def test_resume_pour_l_appli_et_page_du_tableau(hass, hass_storage, hass_client):
    await _installer(hass, hass_storage)
    poser_code_octroi(hass)
    coord = _actuel(hass)
    from custom_components.pc_parental import dashboard, parent
    e = parent.etat_maison(hass, coord, "p1")
    lou = next(p for p in e["personnes"] if p["entite"] == "person.lou")
    assert lou["fiche"]["widgets"] == 1 and lou["fiche"]["role_nom"] and lou["fiche"]["recompenses"] is None
    assert lou["fiche"]["modules"]["temps_gagne"] is True and "reveil" in lou["fiche"]["modules"]
    # La page de Lou dans le tableau est sa fiche.
    vues = dashboard.build(hass, coord)["views"]
    page = next(v for v in vues if v.get("path") == "personne-lou")
    carte = page["sections"][0]["cards"][0]
    assert carte["type"] == "custom:vision-personne-card" and carte["personne"] == "person.lou"
