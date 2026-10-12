"""Point 8 (2.0.0) : notifications v2 — décisions du 2026-10-10.

Important ne bloque pas ; pause télé seulement si demandée ; types système modifiables, types de l'utilisateur
créés et supprimés ; envoi depuis la carte (administrateur) et depuis l'appli (décideur, pas un enfant) ; réponse
d'un appareil notée dans le suivi et renvoyée à l'expéditeur.

    python -m pytest essai/messages_v2_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

TELE = {"id": "t1", "secret": "c" * 32}


async def _monter(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant", type="telephone"),
            "e2": _fiche("e2", "PC-LOU", ENFANT2["secret"], person="person.lou", platform="windows", role="enfant"),
            "t1": _fiche("t1", "Téléviseur du salon", TELE["secret"], platform="android", role="enfant", type="tv"),
            "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent"),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set("person.robin", "home", {"friendly_name": "Robin"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


async def test_types_systeme_modifiables_et_types_utilisateur(hass, hass_storage):
    from custom_components.pc_parental import messages_v2 as mv

    coord = await _monter(hass, hass_storage)
    st = coord.store
    ids = [t["id"] for t in mv.types(st)]
    assert ids[:4] == ["message", "important", "temps", "alerte"]
    # Un type système se modifie…
    t = mv.regler_type(st, {"id": "temps", "nom": "Temps d'écran", "son": "Silencieux", "priorite": "normale", "bloquant": "non"})
    assert t["son"] == "Silencieux" and t["systeme"]
    # … mais ne se supprime pas.
    try:
        mv.supprimer_type(st, "temps")
        raise AssertionError("supprimé")
    except ValueError as e:
        assert "ne se supprime pas" in str(e)
    # Un type de l'utilisateur, bloquant au choix.
    u = mv.regler_type(st, {"nom": "Devoirs", "son": "Rappel", "priorite": "importante", "bloquant": "lu"})
    assert u["bloquant"] == "lu" and not u["systeme"]
    mv.supprimer_type(st, u["id"])
    assert u["id"] not in [x["id"] for x in mv.types(st)]


async def test_envoi_carte_important_ne_bloque_pas_et_reponse(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    client = await hass_client()
    r = await client.get("/api/pc_parental/messages")
    d = await r.json()
    assert r.status == 200 and {p["id"] for p in d["destinataires"]["personnes"]} == {"person.lou", "person.robin"}
    r = await client.post("/api/pc_parental/messages", json={
        "action": "envoyer", "destinataires": ["person.lou", "t1"], "type": "message", "texte": "À table dans 10 minutes !",
        "reponses": ["J'arrive", "Dans 5 min", "OK", "Trop"], "important": True})
    j = await r.json()
    assert r.status == 200 and j["ok"], j
    assert {m["pc"] for m in j["messages"]} == {"e1", "e2", "t1"}
    m = coord.store.pcs["t1"]["messages"][-1]
    # Important : mis en avant, jamais bloquant ni plein écran ; pas de pause sans la case.
    assert m["important"] and m["bloquant"] == "non" and not m["urgent"] and m["pause_tele"] is False
    assert m["reponses"] == ["J'arrive", "Dans 5 min", "OK"] and m["v"] == 2 and m["de"]
    # Le téléphone de Lou répond (texte libre) : suivi et notification Home Assistant de l'expéditeur.
    mid = coord.store.pcs["e1"]["messages"][-1]["id"]
    r = await client.post("/api/pc_parental/message", json={**ENFANT, "message": mid, "etat": "repondu", "detail": "J'arrive, je finis ma partie"})
    assert r.status == 200
    d = await (await client.get("/api/pc_parental/messages")).json()
    ligne = next(a for a in d["envois"][0]["appareils"] if a["pc"] == "e1")
    assert ligne["etat"] == "repondu" and ligne["reponse"] == "J'arrive, je finis ma partie"
    notes = [s for s in hass.states.async_all("persistent_notification")] if hass.states.async_all("persistent_notification") else []
    from homeassistant.components import persistent_notification as pn
    assert any("Réponse de Lou" in str(n.get("title")) for n in pn._async_get_or_create_notifications(hass).values()) or notes


async def test_pause_tele_seulement_si_demandee_et_appli_decideur(hass, hass_storage, hass_client):
    coord = await _monter(hass, hass_storage)
    client = await hass_client()
    r = await client.post("/api/pc_parental/messages/app", json={**ENFANT, "action": "lire"})
    assert r.status == 403
    r = await client.post("/api/pc_parental/messages/app", json={**PARENT, "action": "lire"})
    assert r.status == 200 and (await r.json())["types"]
    r = await client.post("/api/pc_parental/messages/app", json={**PARENT, "action": "envoyer", "destinataires": ["t1"],
                                                                 "texte": "Film fini ?", "pause_tele": True, "reponses": ["Oui", "Non"]})
    assert r.status == 200
    m = coord.store.pcs["t1"]["messages"][-1]
    assert m["pause_tele"] is True and m["de"] == "Robin"
    # La télé répond « Oui » : le téléphone de Robin reçoit la réponse dans sa file.
    avant = len(coord.store.pcs["p1"]["messages"])
    r = await client.post("/api/pc_parental/message", json={**TELE, "message": m["id"], "etat": "repondu", "detail": "Oui"})
    assert r.status == 200
    nouveau = coord.store.pcs["p1"]["messages"][avant:]
    assert nouveau and nouveau[-1]["title"].startswith("Réponse de") and "« Oui »" in nouveau[-1]["text"]
    # Une réponse vide est refusée.
    r = await client.post("/api/pc_parental/message", json={**TELE, "message": m["id"], "etat": "repondu", "detail": "  "})
    assert r.status == 400
    # Destinataire vide, message vide : refus en français.
    r = await client.post("/api/pc_parental/messages/app", json={**PARENT, "action": "envoyer", "destinataires": [], "texte": "x"})
    assert r.status == 400 and (await r.json())["retour"] == "Choisis au moins un destinataire."
