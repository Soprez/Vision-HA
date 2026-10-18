"""2.4.7 (décision du projet, 2026-10-12) — liaison d'une télé SANS code à l'écran.

La demande part sur les appareils des Décideurs (rôle qui peut « Gérer ») ; l'un d'eux accepte (en choisissant le
Demandeur, ou l'écran partagé du Lieu) ou refuse. Ni le jeton d'attente ni le code ne sortent vers eux. Rafales
bornées par adresse ; une nouvelle demande du même appareil remplace l'ancienne ; expiration.

    python -m pytest essai/liaison_sans_code_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import time

from homeassistant.helpers import entity_registry as er

from securite_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, _actuel, _attendre, _demander, _fiche, _integrations, _monter, expected_lingering_tasks,
    expected_lingering_timers,
)

SP = "s" * 32
SE = "e" * 32


async def _avec_decideur(hass, hass_storage):
    appareils = {"p1": _fiche("p1", "Téléphone de A", SP), "e1": _fiche("e1", "Tablette", SE, person="person.enfant")}
    _, coord = await _monter(hass, hass_storage, appareils)
    role = er.async_get(hass).async_get_entity_id("select", DOMAIN, f"{DOMAIN}_role_p1")
    await hass.services.async_call("select", "select_option", {"entity_id": role, "option": "Décideur"}, blocking=True)
    await hass.async_block_till_done()
    return _actuel(hass)


def _messages(coord, pc_id):
    return [m for m in coord.store.pcs[pc_id].get("messages") or [] if m.get("nouvel_appareil")]


async def _decider(client, pc, secret, demande, action, personne=""):
    r = await client.post("/api/pc_parental/appairage/decideur/decision",
                          json={"id": pc, "secret": secret, "demande": demande, "action": action, "personne": personne})
    return r.status, await r.json()


async def test_accepte_par_le_decideur_sans_code(hass, hass_storage, hass_client):
    client = await hass_client()
    await _avec_decideur(hass, hass_storage)
    statut, d = await _demander(client, host="CHROMECAST", nom="Salle De jeux", platform="tv")
    assert statut == 200 and d["decideurs"] == 1 and d["nom"] == "Salle De jeux"
    coord = _actuel(hass)
    # Le Décideur a reçu « Nouvel appareil », sans jeton ni code ; le Demandeur, rien.
    msgs = _messages(coord, "p1")
    assert len(msgs) == 1 and _messages(coord, "e1") == []
    na = msgs[0]["nouvel_appareil"]
    assert na["demande"] == d["demande"] and na["nom"] == "Salle De jeux" and na["lieu"]
    assert {"id": "person.enfant", "nom": "Enfant"} in na["personnes"]
    assert d["attente"] not in json.dumps(msgs) and d["code"] not in json.dumps(msgs)
    assert msgs[0]["expire"] > time.time()
    # Liste côté Décideur ; refusée à un Demandeur.
    r = await client.post("/api/pc_parental/appairage/decideur/liste", json={"id": "p1", "secret": SP})
    assert [x["demande"] for x in (await r.json())["demandes"]] == [d["demande"]]
    r = await client.post("/api/pc_parental/appairage/decideur/liste", json={"id": "e1", "secret": SE})
    assert r.status == 403
    assert (await _decider(client, "e1", SE, d["demande"], "accepter"))[0] == 403
    assert (await _decider(client, "p1", "x" * 32, d["demande"], "accepter"))[0] == 401
    # Acceptée pour l'écran partagé du Lieu : l'appareil reçoit son accès, une fois.
    statut, r = await _decider(client, "p1", SP, d["demande"], "accepter", "partage")
    assert statut == 200 and r["etat"] == "validee" and r["nom"] == "Salle De jeux"
    _, a = await _attendre(client, d)
    await hass.async_block_till_done()
    assert a["etat"] == "validee" and len(a["secret"]) == 32
    assert _actuel(hass).store.pcs[a["id"]]["person"] == ""
    # Une deuxième décision ne change rien.
    assert (await _decider(client, "p1", SP, d["demande"], "refuser"))[1]["ok"] is False


async def test_accepte_pour_un_demandeur_puis_refus(hass, hass_storage, hass_client):
    client = await hass_client()
    await _avec_decideur(hass, hass_storage)
    _, d = await _demander(client, host="TV-A", nom="Télé A")
    statut, r = await _decider(client, "p1", SP, d["demande"], "accepter", "person.enfant")
    assert statut == 200
    _, a = await _attendre(client, d)
    await hass.async_block_till_done()
    assert _actuel(hass).store.pcs[a["id"]]["person"] == "person.enfant"
    # Une personne inconnue : refusée, la demande reste en attente.
    _, d2 = await _demander(client, host="TV-B", nom="Télé B")
    assert (await _decider(client, "p1", SP, d2["demande"], "accepter", "person.inconnue"))[0] == 400
    assert (await _attendre(client, d2))[1]["etat"] == "en_attente"
    # Refus : l'appareil le lit une fois, rien n'est créé.
    assert (await _decider(client, "p1", SP, d2["demande"], "refuser"))[1]["etat"] == "refusee"
    assert (await _attendre(client, d2))[1]["etat"] == "refusee"
    assert _actuel(hass).store.by_host("TV-B") is None


async def test_rafales_remplacement_et_expiration(hass, hass_storage, hass_client):
    client = await hass_client()
    await _avec_decideur(hass, hass_storage)
    from custom_components.pc_parental import appairage, appairage_decideur as dec

    # Le même appareil redemande : l'ancienne demande est remplacée (elle ne peut plus être acceptée).
    _, d1 = await _demander(client, host="TV-R", uid="u-1")
    _, d2 = await _demander(client, host="TV-R", uid="u-1")
    assert (await _attendre(client, d1))[1]["etat"] == "remplacee"
    assert (await _decider(client, "p1", SP, d1["demande"], "accepter"))[1]["ok"] is False
    # Rafale : au plus 3 demandes par adresse en 10 min, même en changeant de nom.
    _, d3 = await _demander(client, host="TV-S", uid="u-2")
    statut, _ = await _demander(client, host="TV-T", uid="u-3")
    assert statut == 429
    # Après 10 min, de nouveau permis.
    for h in dec._historique(hass).values():
        h[:] = [t - dec.RAFALE_S - 1 for t in h]
    # (les 2 demandes en cours de cette adresse comptent encore : on en laisse expirer une)
    appairage._demandes(hass)[d3["demande"]]["expire"] = time.time() - 1
    assert (await _attendre(client, d3))[1]["etat"] == "expiree"
    statut, _ = await _demander(client, host="TV-T", uid="u-3")
    assert statut == 200
    assert (await _attendre(client, d2))[1]["etat"] == "en_attente"


async def test_aucun_decideur(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    statut, d = await _demander(client)
    assert statut == 200 and d["decideurs"] == 0 and d["code"]  # le code reste en secours (« Autre méthode »)
