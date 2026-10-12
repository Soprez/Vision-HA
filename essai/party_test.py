"""Point 14 bis (1.98.0) : le Mode Party et le widget Lecteur.

Droits : une télé affiche, un décideur pilote, un enfant ne fait rien. Le Mode Fête (option validée) s'allume
au lancement et revient comme avant à l'arrêt (déjà allumé : il reste allumé). Le volume est borné ; la pochette
ne sort que pour le lecteur de la Party ou une entité permise, sans jamais l'adresse de l'image (jeton).

    python -m pytest essai/party_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.core import ServiceCall
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

TELE = {"id": "t1", "secret": "c" * 32}
SALON = "media_player.salon"


async def _monter(hass, hass_storage, fete="off"):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant", type="telephone"),
            "t1": _fiche("t1", "Téléviseur du salon", TELE["secret"], platform="android", role="enfant", type="tv"),
            "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent"),
        },
        "entites_personnes": {"person.lou": [SALON]},
        "entites_actions": {SALON: ["media_play_pause", "volume_set"]},
    }}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set(SALON, "playing", {"friendly_name": "Salon", "media_title": "Titre", "media_artist": "Artiste",
                                             "volume_level": 0.3, "entity_picture": "/api/media_player_proxy/media_player.salon?token=SECRET"})
    hass.states.async_set("input_boolean.mode_fete", fete, {"friendly_name": "Mode fête"})
    appels = []

    async def noter(call: ServiceCall):
        appels.append((call.domain, call.service, dict(call.data)))
        if call.domain == "input_boolean":
            hass.states.async_set("input_boolean.mode_fete", "on" if call.service == "turn_on" else "off")
    for s in ("media_play_pause", "media_next_track", "media_previous_track", "volume_set"):
        hass.services.async_register("media_player", s, noter)
    for s in ("turn_on", "turn_off"):
        hass.services.async_register("input_boolean", s, noter)
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id], appels


async def _post(client, chemin, qui, **corps):
    r = await client.post("/api/pc_parental/party/" + chemin, json={**qui, **corps})
    try:
        return r.status, await r.json()
    except Exception:  # noqa: BLE001 - une image ou une réponse vide
        return r.status, None


async def test_droits_et_releve(hass, hass_storage, hass_client):
    from custom_components.pc_parental import party

    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    assert party.pour_releve(hass, store, store.pcs["t1"])["tuile"] is True
    assert party.pour_releve(hass, store, store.pcs["p1"])["tuile"] is True
    assert party.pour_releve(hass, store, store.pcs["e1"])["tuile"] is False
    client = await hass_client()
    # 2.1.0 : la Party est ouverte à tous, mais sans Party en cours un enfant ne pilote rien.
    s, r = await _post(client, "etat", ENFANT)
    assert s == 200 and r["participer"] is False and r["piloter"] is False and r["qr"] == "" and "ecrans" not in r
    s, r = await _post(client, "commande", ENFANT, action="arreter")
    assert s == 403 and r["retour"] == "Réservé aux décideurs."
    s, r = await _post(client, "etat", TELE)
    assert s == 200 and r["piloter"] is False and "ecrans" not in r
    s, r = await _post(client, "etat", PARENT)
    assert s == 200 and r["piloter"] is True
    assert [e["id"] for e in r["ecrans"]] == ["t1"] and SALON in [x["entity_id"] for x in r["lecteurs"]]
    assert "mode_fete_dispo" not in r  # 2.2.0 : plus de Mode Fête dans Vision
    # Le relevé porte « party » ; sans enceinte, pas de tuile.
    rr = await client.post("/api/pc_parental/poll", json={**TELE, "platform": "android", "type": "tv"})
    assert (await rr.json())["party"]["tuile"] is True
    hass.states.async_remove(SALON)
    assert party.pour_releve(hass, store, store.pcs["t1"])["tuile"] is False


async def test_lancer_arreter_emet_les_evenements_sans_toucher_au_mode_fete(hass, hass_storage, hass_client):
    """2.2.0 : Vision ne touche plus à input_boolean.mode_fete ; la Party émet
    vision_party_started / vision_party_stopped, avec l'appareil et la personne."""
    coord, appels = await _monter(hass, hass_storage, fete="off")
    vus = []
    hass.bus.async_listen("vision_party_started", lambda e: vus.append(("debut", dict(e.data))))
    hass.bus.async_listen("vision_party_stopped", lambda e: vus.append(("fin", dict(e.data))))
    client = await hass_client()
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON, mode_fete=True)
    assert s == 200 and r["ok"], r
    s, r = await _post(client, "etat", TELE)
    assert r["actif"] and r["ici"] and "mode_fete" not in r and r["maintenant"]["titre"] == "Titre"
    assert "token" not in str(r) and r["maintenant"]["pochette"]
    rr = await (await client.post("/api/pc_parental/poll", json={**TELE, "platform": "android", "type": "tv"})).json()
    assert rr["party"]["ecran"] is True
    # Relancer sur le même écran n'émet pas un 2e début.
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    s, r = await _post(client, "commande", PARENT, action="arreter")
    assert s == 200
    await hass.async_block_till_done()
    assert [v[0] for v in vus] == ["debut", "fin"]
    assert vus[0][1] == {"ecran": "t1", "ecran_nom": "Téléviseur du salon", "lecteur": SALON, "appareil": "PIXEL-ROBIN",
                         "appareil_id": "p1", "personne": "person.robin"}
    assert vus[1][1]["appareil"] == "PIXEL-ROBIN" and vus[1][1]["ecran"] == "t1"
    assert hass.states.get("input_boolean.mode_fete").state == "off" and not [a for a in appels if a[0] == "input_boolean"]
    rr = await (await client.post("/api/pc_parental/poll", json={**TELE, "platform": "android", "type": "tv"})).json()
    assert rr["party"]["ecran"] is False


async def test_commandes_lecture_et_volume(hass, hass_storage, hass_client):
    _coord, appels = await _monter(hass, hass_storage)
    client = await hass_client()
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="zz", lecteur=SALON)
    assert s == 400 and r["retour"] == "Cet écran n'est plus dans Vision."
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    s, _ = await _post(client, "commande", PARENT, action="suivant")
    assert s == 200 and ("media_player", "media_next_track", {"entity_id": SALON}) in appels
    s, r = await _post(client, "commande", PARENT, action="volume", volume=1.5)
    assert s == 400 and r["retour"] == "Volume invalide."
    s, _ = await _post(client, "commande", PARENT, action="volume", volume=0.45)
    assert s == 200 and ("media_player", "volume_set", {"entity_id": SALON, "volume_level": 0.45}) in appels
    s, r = await _post(client, "commande", PARENT, action="nimporte")
    assert s == 400 and r["retour"] == "Commande inconnue."


async def test_pochette_seulement_si_permise(hass, hass_storage, hass_client):
    _coord, _ = await _monter(hass, hass_storage)
    client = await hass_client()
    # Une entité hors liste et hors Party : refusée.
    s, _r = await _post(client, "pochette", TELE, entite=SALON)
    assert s == 403
    s, _r = await _post(client, "pochette", ENFANT2, entite=SALON)
    assert s in (401, 403)
    # L'entité d'un widget permis (Lou) : lue (sans lecteur réel, aucune image : 204).
    s, _r = await _post(client, "pochette", ENFANT, entite=SALON)
    assert s == 204
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    s, _r = await _post(client, "pochette", TELE, entite=SALON)
    assert s == 204
    s, _r = await _post(client, "pochette", TELE, entite="light.salon")
    assert s == 403


async def test_widget_lecteur(hass, hass_storage, hass_client):
    from custom_components.pc_parental import widgets

    _coord, appels = await _monter(hass, hass_storage)
    etat = hass.states.get(SALON)
    assert widgets.styles(etat, SALON)[0] == "lecteur"
    a = widgets.attributs_utiles(etat)
    assert a["media_title"] == "Titre" and a["pochette"] and "entity_picture" not in a and "SECRET" not in str(a)
    client = await hass_client()
    r = await client.post("/api/pc_parental/widgets/action", json={**ENFANT, "entite": SALON, "service": "volume_set", "data": {"volume_level": 2}})
    assert r.status in (400, 403)
    r = await client.post("/api/pc_parental/widgets/entites", json={**ENFANT})
    if r.status == 200:
        fiche = next(x for x in (await r.json())["entites"] if x["entity_id"] == SALON)
        assert fiche["styles"][0] == "lecteur"
    # Pas de suivant dans la liste blanche : refusé.
    r = await client.post("/api/pc_parental/widgets/action", json={**ENFANT, "entite": SALON, "service": "media_next_track"})
    assert r.status == 403


async def test_musique_joue_sur_la_tele_bloque_la_veille(hass, hass_storage, hass_client):
    """2.2.12 (l'auteur : « l'écran de veille essaie de se lancer » pendant la musique) : le relevé d'une télé dit si un de
    SES lecteurs (même nom, ou « · Vision ») joue, quel que soit l'appareil qui a lancé la lecture ; jamais pour un
    autre appareil, ni pour une enceinte d'une autre pièce."""
    from custom_components.pc_parental import party

    coord, _ = await _monter(hass, hass_storage)
    store = coord.store
    assert party.pour_releve(hass, store, store.pcs["t1"])["musique_joue"] is False  # « Salon » n'est pas cette télé
    hass.states.async_set("media_player.tele_salon", "playing", {"friendly_name": "Téléviseur du salon"})
    assert party.pour_releve(hass, store, store.pcs["t1"])["musique_joue"] is True
    assert party.pour_releve(hass, store, store.pcs["p1"])["musique_joue"] is False  # un téléphone : jamais
    hass.states.async_set("media_player.tele_salon", "paused", {"friendly_name": "Téléviseur du salon"})
    assert party.pour_releve(hass, store, store.pcs["t1"])["musique_joue"] is False
    hass.states.async_set("media_player.tele_salon_vision", "playing", {"friendly_name": "Téléviseur du salon · Vision"})
    assert party.pour_releve(hass, store, store.pcs["t1"])["musique_joue"] is True
    hass.states.async_set("media_player.tele_salon_vision", "idle", {"friendly_name": "Téléviseur du salon · Vision"})
    # La Party sur cette télé, avec l'enceinte « Salon » qui joue : pas de veille non plus.
    party.etat(hass).update({"actif": True, "ecran": "t1", "lecteur": SALON})
    assert party.pour_releve(hass, store, store.pcs["t1"])["musique_joue"] is True
    client = await hass_client()
    rr = await client.post("/api/pc_parental/poll", json={**TELE, "platform": "android", "type": "tv"})
    assert (await rr.json())["party"]["musique_joue"] is True
    party.etat(hass).update({"actif": False, "ecran": ""})
