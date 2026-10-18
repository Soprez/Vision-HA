"""Passerelle de la veille (1.77.0, point 6) : le frontend de Home Assistant dessine les vraies cartes, mais
n'entend parler que des entités des cartes rendues disponibles pour CET appareil, sans jeton global.

Attaques couvertes : ticket forgé, expiré, d'un autre appareil, appareil supprimé ; lecture d'une entité
hors des cartes (états, abonnement, historique) ; modèle (template) qui lirait tout ; appel de service ;
abonnement aux entités sans liste (vaudrait « toutes ») ; événements d'une entité non autorisée.

    python -m pytest essai/passerelle_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

PAGE = {"ok": True, "horloge": {"actif": True, "duree": 15}, "tableaux": [{
    "id": "dash_x_0", "titre": "Noa", "duree": 30, "colonnes": 4, "sections": [{"titre": "", "span": 1, "cartes": [
        {"cle": "a", "cles": ["a"], "config": {"type": "picture-elements", "image": "/local/cles.png",
                                                 "elements": [{"type": "state-icon", "entity": "lock.cle_noa"}]}},
        {"cle": "b", "cles": ["b"], "config": {"type": "custom:pronote-planning-card",
                                                 "entities": [{"entity": "sensor.edt_noa"}]}},
    ]}]}]}


ECRANS_VUS: list[str] = []


async def _monter(hass, hass_storage, monkeypatch):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-NOA", ENFANT["secret"], person="person.noa", platform="android"),
            "e2": _fiche("e2", "TEL-LOU", ENFANT2["secret"], person="person.lou", platform="android"),
        }}}
    for eid, etat in (("lock.cle_noa", "locked"), ("sensor.edt_noa", "48"), ("weather.maison", "sunny"),
                      ("alarm_control_panel.maison", "armed_away"), ("person.nolan", "home")):
        hass.states.async_set(eid, etat, {"friendly_name": eid})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    from custom_components.pc_parental import veille_page

    async def page(_hass, _coord, pc, ecran, _essai="", typees=True):  # typees : cases de la page (veille-lot)
        ECRANS_VUS.append(ecran)
        return PAGE if pc and pc["id"] == "e1" else {**PAGE, "tableaux": []}

    monkeypatch.setattr(veille_page, "page", page)
    return next(iter(hass.data[DOMAIN].values()))


def test_ticket():
    from custom_components.pc_parental import veille_passerelle as vp

    class S:
        pcs = {"e1": {"id": "e1", "secret": "e" * 32}, "e2": {"id": "e2", "secret": "f" * 32}}

    t = vp.ticket(S.pcs["e1"])
    assert vp.verifier(S, t)["id"] == "e1"
    v, pc, fin, signe = t.split(".")
    assert vp.verifier(S, f"{v}.e2.{fin}.{signe}") is None             # même signature, autre appareil
    assert vp.verifier(S, f"{v}.{pc}.{int(fin) + 10}.{signe}") is None  # prolongé à la main
    assert vp.verifier(S, vp.ticket(S.pcs["e1"], time.time() - 2 * vp.DUREE_TICKET)) is None  # expiré
    assert vp.verifier(S, "n'importe quoi") is None
    del S.pcs["e1"]
    assert vp.verifier(S, t) is None  # appareil supprimé


async def test_passerelle(hass, hass_storage, hass_client, monkeypatch):
    coord = await _monter(hass, hass_storage, monkeypatch)
    coord.store.veille_rendu = True
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    # Un ticket forgé : refusé.
    ws = await client.ws_connect(vp.URL)
    assert (await ws.receive_json())["type"] == "auth_required"
    await ws.send_json({"type": "auth", "access_token": "vv1.e1.9999999999.0000"})
    assert (await ws.receive_json())["type"] == "auth_invalid"
    await ws.close()

    ws = await client.ws_connect(vp.URL)
    await ws.receive_json()
    await ws.send_json({"type": "auth", "access_token": vp.ticket(coord.store.pcs["e1"])})
    assert (await ws.receive_json())["type"] == "auth_ok"

    async def demander(msg):
        await ws.send_json(msg)
        while True:
            r = await ws.receive_json()
            if r.get("id") == msg["id"] and r.get("type") == "result":
                return r

    # Les états : seulement ceux des cartes de CET appareil. 1.93.05 (retour 35) : plus la météo de l'horloge.
    r = await demander({"id": 1, "type": "get_states"})
    assert r["success"] and {e["entity_id"] for e in r["result"]} == {"lock.cle_noa", "sensor.edt_noa"}
    # La page de l'appareil, par la passerelle.
    r = await demander({"id": 2, "type": "pc_parental/veille_page"})
    assert r["result"]["tableaux"][0]["titre"] == "Noa"
    # Un modèle lirait tout Home Assistant : refusé. Un appel de service : refusé.
    # (1.78.3 : un modèle n'est jamais transmis à HA ; celui-ci, qui lirait tout, est rendu vide ici.)
    assert (await demander({"id": 3, "type": "render_template", "template": "{{ states.alarm_control_panel }}"}))["success"]
    assert not (await demander({"id": 4, "type": "call_service", "domain": "lock", "service": "unlock",
                                "target": {"entity_id": "lock.cle_noa"}}))["success"]
    # L'historique d'une entité hors des cartes : refusé ; d'une entité des cartes : accepté.
    hist = {"type": "history/history_during_period", "start_time": "2026-01-01T00:00:00+00:00", "minimal_response": True}
    assert not (await demander({"id": 5, **hist, "entity_ids": ["alarm_control_panel.maison"]}))["success"]
    # 1.84.0 : une liste mêlée n'est plus refusée en bloc : seule l'entité autorisée est demandée à HA.
    hass.states.async_set("person.nolan", "home")
    r = await demander({"id": 6, **hist, "entity_ids": ["lock.cle_noa", "person.nolan"]})
    # (Sans recorder dans ce banc, HA répond « commande inconnue » : l'important est que la passerelle l'ait laissée passer.)
    assert r["success"] or (r.get("error") or {}).get("code") != "unauthorized"
    assert "person.nolan" not in (r.get("result") or {})
    # L'abonnement aux entités : borné aux autorisées, même demandé sans liste.
    await ws.send_json({"id": 7, "type": "subscribe_entities"})
    vues: set[str] = set()
    for _ in range(4):
        m = await ws.receive_json()
        if m.get("type") == "event" and m.get("id") == 7:
            vues |= set((m["event"].get("a") or {}).keys())
            break
    assert vues and vues <= {"lock.cle_noa", "sensor.edt_noa"}
    # Un changement d'une entité non autorisée ne sort pas.
    assert (await demander({"id": 8, "type": "subscribe_events", "event_type": "state_changed"}))["success"]
    hass.states.async_set("alarm_control_panel.maison", "disarmed")
    hass.states.async_set("lock.cle_noa", "unlocked")
    await hass.async_block_till_done()
    sortis = []
    for _ in range(6):
        try:
            m = await ws.receive_json(timeout=1)
        except Exception:  # noqa: BLE001
            break
        if m.get("id") == 8 and m.get("type") == "event":
            sortis.append(m["event"]["data"]["entity_id"])
    assert "alarm_control_panel.maison" not in sortis and "lock.cle_noa" in sortis
    assert not (await demander({"id": 9, "type": "subscribe_events", "event_type": "call_service"}))["success"]
    # 1.84.0 (en fin : les identifiants du websocket doivent croître) :
    # une liste d'entités toutes non autorisées reste refusée par la passerelle ;
    r = await demander({"id": 900, **hist, "entity_ids": ["person.nolan"]})
    assert (r.get("error") or {}).get("code") == "unauthorized"
    # l'état de démarrage des intégrations, que le frontend attend au chargement, passe la passerelle.
    # 1.86.4 : répondue par la passerelle elle-même (sa vraie réponse faisait recharger la page de veille).
    r = await demander({"id": 901, "type": "subscribe_bootstrap_integrations"})
    assert r["success"] is True
    await ws.close()


async def test_appareil_sans_carte_ne_voit_rien(hass, hass_storage, hass_client, monkeypatch):
    coord = await _monter(hass, hass_storage, monkeypatch)
    coord.store.veille_rendu = True
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    ws = await client.ws_connect(vp.URL)
    await ws.receive_json()
    await ws.send_json({"type": "auth", "access_token": vp.ticket(coord.store.pcs["e2"])})
    assert (await ws.receive_json())["type"] == "auth_ok"
    await ws.send_json({"id": 1, "type": "subscribe_entities"})
    for _ in range(4):
        m = await ws.receive_json()
        if m.get("type") == "event" and m.get("id") == 1:
            assert not (m["event"].get("a") or {})  # une liste vide ne vaut jamais « toutes »
            break
    await ws.send_json({"id": 2, "type": "get_states"})
    while True:
        m = await ws.receive_json()
        if m.get("id") == 2:
            assert m["result"] == []
            break
    await ws.close()


async def test_acces_remet_un_ticket(hass, hass_storage, hass_client, monkeypatch):
    coord = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    r = await (await client.post("/api/pc_parental/veille/acces", json={**ENFANT, "ecran": "telephone"})).json()
    assert r["jeton"] == ""  # l'essai est éteint par défaut : l'appareil garde sa veille à lui
    coord.store.veille_rendu = True
    r = await (await client.post("/api/pc_parental/veille/acces", json={**ENFANT, "ecran": "telephone"})).json()
    assert vp.verifier(coord.store, r["jeton"])["id"] == "e1"
    r = await (await client.post("/api/pc_parental/veille/acces", json={**ENFANT2, "ecran": "telephone"})).json()
    assert r["jeton"] == ""  # rien à montrer : pas de ticket


async def _authentifier(client, vp, jeton):
    ws = await client.ws_connect(vp.URL)
    await ws.receive_json()
    await ws.send_json({"type": "auth", "access_token": jeton})
    return ws, (await ws.receive_json())["type"]


async def test_interrupteur_eteint_ferme_la_passerelle(hass, hass_storage, hass_client, monkeypatch):
    """1.78.0 : un ticket remis pendant l'essai ne vaut plus une fois « vrai rendu » éteint."""
    coord = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    coord.store.veille_rendu = True
    jeton = vp.ticket(coord.store.pcs["e1"])
    ws, etat = await _authentifier(client, vp, jeton)
    assert etat == "auth_ok"
    await ws.close()
    coord.store.veille_rendu = False
    ws, etat = await _authentifier(client, vp, jeton)
    assert etat == "auth_invalid"
    await ws.close()


async def test_connexion_fermee_a_l_expiration(hass, hass_storage, hass_client, monkeypatch):
    coord = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    coord.store.veille_rendu = True
    # Un ticket qui expire dans 2 s : la connexion ne lui survit pas.
    jeton = vp.ticket(coord.store.pcs["e1"], time.time() - vp.DUREE_TICKET + 2)
    ws, etat = await _authentifier(client, vp, jeton)
    assert etat == "auth_ok"
    fermee = False
    for _ in range(10):
        m = await ws.receive(timeout=5)
        if m.type.name in ("CLOSE", "CLOSED", "CLOSING"):
            fermee = True
            break
    assert fermee


async def test_page_selon_le_type_d_appareil(hass, hass_storage, hass_client, monkeypatch):
    coord = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    coord.store.veille_rendu = True
    for genre, attendu in (("telephone", "telephone"), ("tv", "tele"), ("", "tele")):
        coord.store.pcs["e1"]["type"] = genre
        ECRANS_VUS.clear()
        ws, etat = await _authentifier(client, vp, vp.ticket(coord.store.pcs["e1"]))
        assert etat == "auth_ok" and ECRANS_VUS == [attendu], (genre, ECRANS_VUS)
        await ws.close()


async def test_ouverture_et_fermeture_journalisees(hass, hass_storage, hass_client, monkeypatch, caplog):
    """1.78.1 : on voit dans le journal qu'un appareil a ouvert sa veille, et ce qui a été refusé."""
    import logging

    coord = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    coord.store.veille_rendu = True
    caplog.set_level(logging.WARNING)
    ws, etat = await _authentifier(client, vp, vp.ticket(coord.store.pcs["e1"]))
    assert etat == "auth_ok"
    await ws.send_json({"id": 1, "type": "call_service", "domain": "lock", "service": "unlock"})
    while (await ws.receive_json()).get("id") != 1:
        pass
    await ws.close()
    await hass.async_block_till_done()
    for _ in range(20):
        if any("fermée" in m for m in caplog.messages):
            break
        import asyncio
        await asyncio.sleep(0.05)
    assert any("« TEL-NOA » ouverte par la passerelle" in m and "2 entités" in m for m in caplog.messages)
    assert any("« TEL-NOA » fermée" in m and "call_service ×1" in m for m in caplog.messages)


async def test_demarrage_du_frontend(hass, hass_storage, hass_client, monkeypatch):
    """1.78.2 : ce que le frontend de Home Assistant demande au démarrage (relevé sur un PC réel, où il
    restait sur « Chargement des données » puis tombait sur la page de connexion)."""
    coord = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    coord.store.veille_rendu = True
    ws, etat = await _authentifier(client, vp, vp.ticket(coord.store.pcs["e1"]))
    assert etat == "auth_ok"

    async def demander(msg):
        await ws.send_json(msg)
        while True:
            r = await ws.receive_json()
            if r.get("id") == msg["id"] and r.get("type") == "result":
                return r

    # La liste des services : abonnements acceptés (sans eux, le frontend ne finit pas de charger).
    assert (await demander({"id": 1, "type": "subscribe_events", "event_type": "service_registered"}))["success"]
    assert (await demander({"id": 2, "type": "subscribe_events", "event_type": "service_removed"}))["success"]
    # Réparations : une liste vide, un abonnement muet ; rien de la maison ne sort.
    r = await demander({"id": 3, "type": "repairs/list_issues"})
    assert r["success"] and r["result"] == {"issues": []}
    assert (await demander({"id": 4, "type": "subscribe_events", "event_type": "repairs_issue_registry_updated"}))["success"]
    # Le frontend qui journalise ses erreurs : succès factice, rien n'est écrit.
    ecrits = []
    hass.services.async_register("system_log", "write", lambda call: ecrits.append(call))
    r = await demander({"id": 5, "type": "call_service", "domain": "system_log", "service": "write",
                        "service_data": {"message": "x"}})
    assert r["success"] and ecrits == []
    # Tout autre appel de service reste refusé, même du même domaine.
    assert not (await demander({"id": 6, "type": "call_service", "domain": "system_log", "service": "clear"}))["success"]
    # Le jeton des logos : accepté (il n'ouvre que /api/brands).
    r = await demander({"id": 7, "type": "brands/access_token"})
    assert (r.get("error") or {}).get("code") != "unauthorized"
    await ws.close()


async def test_modeles_et_images_de_camera(hass, hass_storage, hass_client, monkeypatch):
    """1.78.3 : un modèle n'est jamais transmis à Home Assistant ; seuls les {{ states('x') }} d'entités de
    la veille sont rendus ici, le reste donne une chaîne vide (jamais le texte source à l'écran). L'image
    d'une caméra de la veille passe par l'adresse déjà publiée dans son état ; aucun autre chemin n'est signé."""
    coord = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import veille_passerelle as vp

    client = await hass_client()
    coord.store.veille_rendu = True
    ws, etat = await _authentifier(client, vp, vp.ticket(coord.store.pcs["e1"]))
    assert etat == "auth_ok"

    async def modele(ident, texte):
        await ws.send_json({"id": ident, "type": "render_template", "template": texte})
        resultat = evenement = None
        while evenement is None:
            r = await ws.receive_json()
            if r.get("id") == ident and r.get("type") == "result":
                resultat = r
            if r.get("id") == ident and r.get("type") == "event":
                evenement = r["event"]["result"]
        assert resultat["success"]
        return evenement

    assert await modele(1, "{{ states('lock.cle_noa') }}") == "Verrouillé"       # traduit
    assert await modele(2, "Clé : {{ states('sensor.edt_noa') }} !") == "Clé : 48 !"
    assert await modele(3, "{{ states('alarm_control_panel.maison') }}") == ""     # hors de la veille
    assert await modele(4, "{{ states.alarm_control_panel.maison.state }}") == ""  # forme libre : rien
    assert await modele(5, "{% for s in states %}{{ s.entity_id }}{% endfor %}") == ""
    # Le désabonnement d'un modèle servi ici : accepté par la passerelle elle-même (1.78.4).
    await ws.send_json({"id": 6, "type": "unsubscribe_events", "subscription": 1})
    while True:
        r = await ws.receive_json()
        if r.get("id") == 6:
            assert r["success"]
            break
    # Le jeton des tuiles de carte : accepté (fond de carte seulement).
    await ws.send_json({"id": 7, "type": "map_tiles/access_token"})
    while True:
        r = await ws.receive_json()
        if r.get("id") == 7:
            assert (r.get("error") or {}).get("code") != "unauthorized"
            break
    await ws.close()

    # Les images de caméra : seulement une caméra de la veille, et seulement son chemin de proxy.
    hass.states.async_set("camera.radar", "idle", {"entity_picture": "/api/camera_proxy/camera.radar?token=abc"})
    hass.states.async_set("camera.chambre", "idle", {"entity_picture": "/api/camera_proxy/camera.chambre?token=def"})
    ok, r = vp.reponse_locale({"type": "auth/sign_path", "path": "/api/camera_proxy/camera.radar"}, hass, {"camera.radar"})
    assert ok and r == {"path": "/api/camera_proxy/camera.radar?token=abc"}
    assert vp.reponse_locale({"type": "auth/sign_path", "path": "/api/camera_proxy/camera.chambre"}, hass, {"camera.radar"}) == (False, None)
    assert vp.reponse_locale({"type": "auth/sign_path", "path": "/api/states"}, hass, {"camera.radar"}) == (False, None)
    assert vp.Filtre({"camera.radar"}).entree({"id": 9, "type": "auth/sign_path", "path": "/api/states"})[0] is None


def test_entree_sans_service_worker():
    """1.86.5 : la page d'entrée empêche le service worker de HA de s'installer. En prenant la main, il recharge
    la page, qui perd alors la passerelle et tombe sur l'écran de connexion (veille noire sur le PC)."""
    from custom_components.pc_parental.veille_page import ENTREE

    assert "serviceWorker" in ENTREE and "S.register=function" in ENTREE and "unregister()" in ENTREE
    # La neutralisation précède le chargement du frontend.
    assert ENTREE.index("S.register=function") < ENTREE.index("fetch('/'")
