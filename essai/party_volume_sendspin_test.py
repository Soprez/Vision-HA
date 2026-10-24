"""Client Sendspin natif (règles du projet, 2026-10-12) : le volume dans la Party.

- lecteur Vision (« · Vision ») : volume toujours à 100 %, aucun contrôle (la télé gère le son) ;
- enceinte externe : réglable ;
- groupe : 2.5.12.14.2 (revue de HACS), plus de volume de groupe de Music Assistant (pas de service) : chaque enceinte du
  groupe est réglée par media_player.volume_set en gardant les écarts, jamais une enceinte « · Vision » ;
- lecteurs « · Vision » repérés par leur nom dans Home Assistant, volume tenu à 100 % (plus de codec, plus de renommage).

    python -m pytest essai/party_volume_sendspin_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from types import SimpleNamespace

from homeassistant.helpers import entity_registry as er

from party_test import PARENT, SALON, _monter, _post  # noqa: F401
from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401

CHAMBRE = "media_player.chambre"
TELE_V = "media_player.tele_du_bureau_vision"


def _ma(hass, monkeypatch):
    """Music Assistant simulé : une entrée chargée sans client, et ses lecteurs dans le registre (aucune commande interne)."""
    from custom_components.pc_parental import musique
    monkeypatch.setattr(musique, "entree", lambda _h: SimpleNamespace(entry_id="ma1", data={}))
    reg = er.async_get(hass)
    for uid, eid in (("salon_ma", SALON), ("chambre_ma", CHAMBRE), ("02:00:00:00:00:01", TELE_V), ("upa0000001", "media_player.essai_natif_vision")):
        # L'état existe déjà dans le banc : le registre prendrait un autre identifiant (« _2 ») ; on l'ôte un instant.
        avant = hass.states.get(eid)
        if avant is not None:
            hass.states.async_remove(eid)
        e = reg.async_get_or_create("media_player", "music_assistant", uid, suggested_object_id=eid.split(".", 1)[1])
        assert e.entity_id == eid
        if avant is not None:
            hass.states.async_set(eid, avant.state, dict(avant.attributes))


async def test_groupe_vision_et_enceinte(hass, hass_storage, hass_client, monkeypatch):
    _coord, appels = await _monter(hass, hass_storage)
    _ma(hass, monkeypatch)
    from custom_components.pc_parental import party, volume_party
    hass.states.async_set(CHAMBRE, "playing", {"friendly_name": "Chambre", "volume_level": 0.2})
    hass.states.async_set(TELE_V, "playing", {"friendly_name": "Télé du bureau · Vision"})
    # Une enceinte seule : son volume.
    assert volume_party.etat(hass, SALON) == {"volume": 0.3, "groupe": False, "membres": []}
    # Le lecteur Vision seul : aucun volume.
    assert volume_party.etat(hass, TELE_V)["volume"] is None
    # Un groupe (salon + chambre + télé) : le volume du groupe est la moyenne des enceintes réglables, sans la télé.
    hass.states.async_set(SALON, "playing", {"friendly_name": "Salon", "volume_level": 0.3, "group_members": [SALON, CHAMBRE, TELE_V]})
    e = volume_party.etat(hass, SALON)
    assert e["groupe"] is True and e["volume"] == 0.25
    assert [(m["nom"], m["volume"]) for m in e["membres"]] == [("Salon", 0.3), ("Chambre", 0.2), ("Télé du bureau · Vision", None)]
    m = party.maintenant(hass, SALON)
    assert m["volume"] == 0.25 and m["groupe"] is True and len(m["membres"]) == 3

    client = await hass_client()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    # Le bouton de la Party sur un groupe : chaque enceinte non-Vision par volume_set, en gardant les écarts
    # (volume × demandé / moyenne : 0,3 → 0,66 et 0,2 → 0,44), jamais la télé « · Vision ».
    appels.clear()
    s, _ = await _post(client, "commande", PARENT, action="volume", volume=0.55)
    assert s == 200
    reglages = {d["entity_id"]: d["volume_level"] for _dom, svc, d in appels if svc == "volume_set"}
    assert reglages == {SALON: 0.66, CHAMBRE: 0.44}, reglages
    # Le détail : une enceinte du groupe, oui ; la télé « · Vision » ou une enceinte hors groupe, non.
    s, _ = await _post(client, "commande", PARENT, action="volume_membre", membre=CHAMBRE, volume=0.35)
    assert s == 200 and ("media_player", "volume_set", {"entity_id": CHAMBRE, "volume_level": 0.35}) in appels
    appels.clear()
    s, _ = await _post(client, "commande", PARENT, action="volume_membre", membre=TELE_V, volume=0.1)
    assert s == 400 and not appels
    s, _ = await _post(client, "commande", PARENT, action="volume_membre", membre="media_player.ailleurs", volume=0.1)
    assert s == 400 and not appels


async def test_lecteur_vision_seul_sans_volume(hass, hass_storage, hass_client, monkeypatch):
    _coord, appels = await _monter(hass, hass_storage)
    _ma(hass, monkeypatch)
    # La Party sur la télé elle-même : le bouton de volume ne fait rien (la télécommande règle le son).
    hass.states.async_set(SALON, "playing", {"friendly_name": "Salon · Vision", "volume_level": 1.0})
    from custom_components.pc_parental import volume_party
    assert await volume_party.regler(hass, SALON, 0.2) is True
    client = await hass_client()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    appels.clear()
    s, _ = await _post(client, "commande", PARENT, action="volume", volume=0.2)
    assert s == 200 and not [a for a in appels if a[1] == "volume_set"]


async def test_est_squeezelite_seulement_pour_une_adresse_mac(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental.volume_party import est_squeezelite
    assert est_squeezelite("02:00:00:00:00:01") and not est_squeezelite("upa0000001") and not est_squeezelite(None)


async def test_lecteurs_vision_reperes_par_leur_nom_et_volume_a_100(hass, hass_storage, monkeypatch):
    """2.5.12.14.2 (revue de HACS) : plus de codec, de renommage ni d'ajout automatique à régler. Les lecteurs « · Vision »
    sont repérés par leur nom dans Home Assistant et leur volume est tenu à 100 % par media_player.volume_set ; une autre
    enceinte n'est jamais touchée."""
    from homeassistant.core import ServiceCall
    await _monter(hass, hass_storage)
    _ma(hass, monkeypatch)
    from custom_components.pc_parental import musique
    hass.states.async_set(TELE_V, "idle", {"friendly_name": "Télé du bureau · Vision", "volume_level": 0.1})
    hass.states.async_set("media_player.essai_natif_vision", "idle", {"friendly_name": "Essai Sendspin natif · Vision", "volume_level": 1.0})
    hass.states.async_set(CHAMBRE, "idle", {"friendly_name": "Chambre", "volume_level": 0.1})
    appels = []

    async def volume_set(call: ServiceCall):
        cibles = call.data["entity_id"]
        appels.append((cibles if isinstance(cibles, str) else cibles[0], call.data["volume_level"]))
    hass.services.async_register("media_player", "volume_set", volume_set)
    faits = await musique.regler_lecteurs_vision(hass)
    assert set(faits) == {TELE_V, "media_player.essai_natif_vision"}
    assert (TELE_V, 1.0) in appels and not [a for a in appels if a[0] == CHAMBRE]
    assert not [a for a in appels if a[0] == "media_player.essai_natif_vision"]  # déjà à 100 % : rien à faire
