"""Widgets interactifs (2.4.0, proposition validée en essai le 2026-10-12), côté serveur, dans un vrai Home Assistant.

- rôles Lecture / Interrupteur / Bouton / Réglage : proposés selon le domaine, ils fixent la liste blanche ; une
  entité sans réglage garde le comportement d'avant ; serrures et alarmes : jamais ;
- « Tout le monde » (qui peut agir) : un Demandeur sans la fonction « piloter » agit sur CETTE entité seulement ;
- confirmation vérifiée par le serveur : 409 « confirmer » (question dans la langue de l'appareil) sans
  {"confirme": true}, puis exécutée ;
- réglage d'une valeur (input_number : bornes de l'entité) et d'une liste (select : options de l'entité) ;
- nom et icône affichés ; liste « pour tout le monde » vue par tous les appareils ;
- affichage Valeur / Détail : éléments d'une liste todo (todo.get_items) ou attributs choisis, 20 au plus ;
- carte d'administration : section « tous », op « tous » et « reglage », refus en clair ; journal.

    python -m pytest essai/widgets_roles_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.core import ServiceCall, SupportsResponse

from widgets_test import (  # noqa: F401  (banc des widgets et ses fixtures)
    DOMAIN, ENFANT, ENFANT2, PARENT, TELE, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
    integrations,
)

ADMIN = "/api/pc_parental/widgets/admin"


def _w():
    from custom_components.pc_parental import widgets

    return widgets


def _services(hass):
    appels: list[ServiceCall] = []

    async def noter(call: ServiceCall) -> None:
        appels.append(call)

    for dom, servs in (("light", ("toggle", "turn_on", "turn_off")), ("script", ("turn_on",)),
                       ("input_number", ("set_value",)), ("select", ("select_option",)), ("lock", ("unlock",))):
        for s in servs:
            hass.services.async_register(dom, s, noter)
    return appels


def _todo(hass, items):
    async def get_items(call: ServiceCall):
        ids = call.data.get("entity_id")
        ids = ids if isinstance(ids, list) else [ids]
        return {e: {"items": items} for e in ids}

    hass.services.async_register("todo", "get_items", get_items, supports_response=SupportsResponse.ONLY)


async def _etats_de_plus(hass):
    hass.states.async_set("script.nuit", "off", {"friendly_name": "Nuit"})
    hass.states.async_set("input_number.consigne", "20", {"friendly_name": "Consigne confort", "min": 15, "max": 25,
                                                           "step": 0.5, "unit_of_measurement": "°C"})
    hass.states.async_set("select.mode", "auto", {"friendly_name": "Mode", "options": ["auto", "eco", "confort"]})
    hass.states.async_set("todo.devoirs", "3", {"friendly_name": "Devoirs"})


async def _admin(client, **corps):
    r = await client.post(ADMIN, json=corps)
    return r.status, await r.json()


async def test_roles_proposes_et_compatibilite(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    await _etats_de_plus(hass)
    w = _w()
    assert w.roles_possibles("light.salon") == ["lecture", "interrupteur"]
    assert w.roles_possibles("script.nuit") == ["lecture", "bouton"]
    assert w.roles_possibles("input_number.consigne") == ["lecture", "reglage"]
    assert w.roles_possibles("sensor.temp") == ["lecture"]
    assert w.roles_possibles("lock.porte") == ["lecture"]
    # Sans réglage : le rôle se déduit de la liste blanche d'avant (rien ne change pour un lieu existant).
    st = coord.store
    assert w.reglage(st, "light.salon")["role"] == "interrupteur"
    assert w.reglage(st, "light.cuisine")["role"] == "lecture"
    assert w.reglage(st, "light.salon")["confirmer"] is False and w.reglage(st, "light.salon")["qui"] == "decideurs"


async def test_reglage_par_l_administrateur(hass, integrations, hass_storage, hass_client, caplog):
    _, coord = await _monter(hass, hass_storage)
    await _etats_de_plus(hass)
    client = await hass_client()
    st, d = await _admin(client, op="reglage", entite="script.nuit", role="bouton")
    assert st == 200, d
    r = coord.store.entites_reglages["script.nuit"]
    assert r["role"] == "bouton" and r["confirmer"] is True  # un script : confirmation cochée d'office
    assert coord.store.entites_actions["script.nuit"] == ["turn_on"]
    # Retour en lecture : la liste blanche se vide.
    st, d = await _admin(client, op="reglage", entite="script.nuit", role="lecture")
    assert "script.nuit" not in coord.store.entites_actions
    # Refus en clair : rôle impossible, serrure, icône sans « mdi: ».
    st, d = await _admin(client, op="reglage", entite="sensor.temp", role="interrupteur")
    assert st == 400 and "pas possible" in d["retour"]
    st, d = await _admin(client, op="reglage", entite="lock.porte", role="interrupteur")
    assert st == 400
    st, d = await _admin(client, op="reglage", entite="light.salon", icone="lampe")
    assert st == 400 and "mdi:" in d["retour"]
    # Nom et icône, affichage : relus par la carte.
    st, d = await _admin(client, op="reglage", entite="light.salon", nom="Lampe du canapé", icone="mdi:sofa-outline",
                         affichage="valeur_detail", attributs=["brightness"])
    assert st == 200
    lou = next(p for p in d["personnes"] if p["entite"] == "person.lou")
    f = next(x for x in lou["entites"] if x["id"] == "light.salon")
    assert f["reglage"]["nom"] == "Lampe du canapé" and f["reglage"]["affichage"] == "valeur_detail"
    assert f["roles"] == ["lecture", "interrupteur"] and "brightness" in f["attributs_dispo"]
    assert "friendly_name" not in f["attributs_dispo"]
    assert "light.salon" in caplog.text


async def test_tout_le_monde_et_confirmation(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    await _etats_de_plus(hass)
    appels = _services(hass)
    client = await hass_client()
    # « Pour tout le monde » : le script est vu par Lou (Demandeur) et par la télé, sans être dans leur liste.
    st, d = await _admin(client, op="tous", entites=["script.nuit", "lock.porte"])
    assert coord.store.entites_tous == ["script.nuit"]  # jamais une serrure
    assert [f["id"] for f in d["tous"]] == ["script.nuit"]
    st, d = await _post(client, "/entites", ENFANT)
    assert "script.nuit" in [e["entity_id"] for e in d["entites"]]
    # En lecture : personne n'agit.
    st, rep = await _post(client, "/action", PARENT, entite="script.nuit", service="turn_on")
    assert (st, rep["error"]) == (403, "action")
    # Bouton réservé aux Décideurs : Lou (sans « piloter ») est refusée.
    await _admin(client, op="reglage", entite="script.nuit", role="bouton", confirmer=False)
    st, rep = await _post(client, "/action", ENFANT, entite="script.nuit", service="turn_on")
    assert (st, rep["error"]) == (403, "piloter")
    # « Tout le monde » : Lou peut appuyer, sur cette entité seulement ; la confirmation est alors cochée d'office.
    await _admin(client, op="reglage", entite="script.nuit", qui="tous")
    assert coord.store.entites_reglages["script.nuit"]["confirmer"] is True
    st, d = await _post(client, "/entites", ENFANT)
    nuit = next(e for e in d["entites"] if e["entity_id"] == "script.nuit")
    assert nuit["actions"] == ["turn_on"] and nuit["role"] == "bouton" and nuit["confirmer"] is True
    st, rep = await _post(client, "/action", ENFANT, entite="light.salon", service="toggle")
    assert (st, rep["error"]) == (403, "piloter")  # la lampe reste aux Décideurs
    # Sans confirmation : 409, la question dans la langue de l'appareil ; rien n'est fait.
    st, rep = await _post(client, "/action", ENFANT, entite="script.nuit", service="turn_on")
    assert st == 409 and rep["error"] == "confirmer" and rep["confirmer"] is True and "Nuit" in rep["retour"]
    st, rep = await _post(client, "/action", ENFANT, entite="script.nuit", service="turn_on", confirme="oui")
    assert st == 409  # seulement le vrai booléen
    await hass.async_block_till_done()
    assert appels == []
    st, rep = await _post(client, "/action", ENFANT, entite="script.nuit", service="turn_on", confirme=True)
    assert (st, rep) == (200, {"ok": True})
    await hass.async_block_till_done()
    assert [(c.domain, c.service) for c in appels] == [("script", "turn_on")] and appels[0].context.user_id is None
    # Une serrure inscrite à la main dans le magasin reste interdite, même « tout le monde ».
    coord.store.entites_reglages["lock.porte"] = {"role": "interrupteur", "qui": "tous"}
    st, rep = await _post(client, "/action", PARENT, entite="lock.porte", service="unlock", confirme=True)
    assert (st, rep["error"]) == (403, "action")


async def test_reglage_valeur_et_liste(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    await _etats_de_plus(hass)
    appels = _services(hass)
    client = await hass_client()
    await _admin(client, op="personne", personne="person.robin", entites=["input_number.consigne", "select.mode"])
    await _admin(client, op="reglage", entite="input_number.consigne", role="reglage")
    await _admin(client, op="reglage", entite="select.mode", role="reglage")
    for data in ({"value": 30}, {"value": "x"}, {"value": True}, {}, {"value": 1e12}):
        st, rep = await _post(client, "/action", PARENT, entite="input_number.consigne", service="set_value", data=data)
        assert (st, rep["error"]) == (400, "data"), data
    st, rep = await _post(client, "/action", PARENT, entite="select.mode", service="select_option", data={"option": "turbo"})
    assert (st, rep["error"]) == (400, "data")
    st, rep = await _post(client, "/action", PARENT, entite="input_number.consigne", service="set_value", data={"value": 21.5})
    assert st == 200, rep
    st, rep = await _post(client, "/action", PARENT, entite="select.mode", service="select_option", data={"option": "eco"})
    assert st == 200, rep
    await hass.async_block_till_done()
    assert [(c.service, c.data.get("value", c.data.get("option"))) for c in appels] == [("set_value", 21.5), ("select_option", "eco")]


async def test_nom_icone_et_detail(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    await _etats_de_plus(hass)
    _todo(hass, [{"summary": f"Exercice {i}", "status": "needs_action", "due": "2026-10-13"} for i in range(25)]
          + [{"summary": "", "status": "needs_action"}])
    client = await hass_client()
    await _admin(client, op="personne", personne="person.lou",
                 entites=list(coord.store.entites_personnes["person.lou"]) + ["todo.devoirs"])
    await _admin(client, op="reglage", entite="todo.devoirs", affichage="valeur_detail", nom="Mes devoirs", icone="mdi:school")
    await _admin(client, op="reglage", entite="climate.salon", affichage="detail", attributs=["current_temperature", "absent"])
    st, d = await _post(client, "/entites", ENFANT)
    par_id = {e["entity_id"]: e for e in d["entites"]}
    devoirs = par_id["todo.devoirs"]
    assert devoirs["nom"] == "Mes devoirs" and devoirs["nom_ha"] == "Devoirs" and devoirs["icone"] == "mdi:school"
    assert devoirs["etat"] == "3" and devoirs["affichage"] == "valeur_detail"
    assert devoirs["detail_total"] == 25 and len(devoirs["detail"]) == 20
    assert devoirs["detail"][0] == {"texte": "Exercice 0", "valeur": "2026-10-13"}
    clim = par_id["climate.salon"]
    assert clim["detail"] == [{"texte": "Current temperature", "valeur": "19.5"}] and clim["detail_total"] == 1
    assert "detail" not in par_id["sensor.temp"] if "sensor.temp" in par_id else True
    # /etats rend aussi le détail (l'appli le relit quand le flux signale un changement).
    st, d = await _post(client, "/etats", ENFANT, entites=["todo.devoirs"])
    assert d["etats"]["todo.devoirs"]["detail_total"] == 25


async def test_version_suit_le_reglage(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    w = _w()
    pc = coord.store.pcs["p1"]
    avant = w.version(coord.store, pc)
    w.regler_entite(hass, coord.store, "light.salon", {"nom": "Lampe"}, "essai")
    assert w.version(coord.store, pc) != avant


async def test_style_reglage_options_et_detail_demande(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    await _etats_de_plus(hass)
    client = await hass_client()
    await _admin(client, op="personne", personne="person.robin", entites=["select.mode", "input_number.consigne", "sensor.temp"])
    st, d = await _post(client, "/entites", PARENT)
    par_id = {e["entity_id"]: e for e in d["entites"]}
    assert par_id["select.mode"]["styles"][0] == "reglage" and par_id["input_number.consigne"]["styles"][0] == "reglage"
    assert par_id["select.mode"]["attributs_utiles"]["options"] == ["auto", "eco", "confort"]
    assert "detail" not in par_id["sensor.temp"]
    # Un widget de l'appli demande le détail d'une entité réglée « valeur » dans HA : /etats le donne.
    st, d = await _post(client, "/etats", PARENT, entites=["climate.salon", "sensor.temp", "select.mode"],
                        details=["climate.salon", "sensor.temp"])
    clim = d["etats"]["climate.salon"]["detail"]
    assert {"texte": "Current temperature", "valeur": "19.5"} in clim and d["etats"]["climate.salon"]["detail_total"] == len(clim)
    assert d["etats"]["sensor.temp"]["detail"] == []  # rien que des attributs techniques (cachés)
    assert "detail" not in d["etats"]["select.mode"]
