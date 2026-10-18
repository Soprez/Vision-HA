"""Client Sendspin natif (règles du projet, 2026-10-12) : le volume dans la Party.

- lecteur Vision (« · Vision ») : volume toujours à 100 %, aucun contrôle (la télé gère le son) ;
- enceinte externe : réglable ;
- groupe : volume du groupe (« group volume » de Music Assistant), détail par enceinte en option, jamais une
  enceinte « · Vision » ;
- le réglage MP3 de Music Assistant ne touche que l'ancien lecteur Squeezelite (identifiant MAC), jamais un lecteur
  Sendspin.

    python -m pytest essai/party_volume_sendspin_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from types import SimpleNamespace

from homeassistant.helpers import entity_registry as er

from party_test import PARENT, SALON, _monter, _post  # noqa: F401
from deverrouillage_test import _integrations, expected_lingering_tasks, expected_lingering_timers  # noqa: F401

CHAMBRE = "media_player.chambre"
TELE_V = "media_player.tele_du_bureau_vision"


class FauxMass:
    def __init__(self, group_volume=40):
        self.envoyes = []
        self.gv = group_volume
        self.players = SimpleNamespace(get=lambda pid: {"player_id": pid, "group_volume": self.gv})

    async def send_command(self, cmd, **kw):
        self.envoyes.append((cmd, kw))
        if cmd == "config/players/get_value":
            return "flac"


def _ma(hass, monkeypatch, mass):
    from custom_components.pc_parental import musique
    monkeypatch.setattr(musique, "entree", lambda _h: SimpleNamespace(runtime_data=SimpleNamespace(mass=mass)))
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
    mass = FauxMass(group_volume=40)
    _ma(hass, monkeypatch, mass)
    from custom_components.pc_parental import party, volume_party
    hass.states.async_set(CHAMBRE, "playing", {"friendly_name": "Chambre", "volume_level": 0.2})
    hass.states.async_set(TELE_V, "playing", {"friendly_name": "Télé du bureau · Vision"})
    # Une enceinte seule : son volume.
    assert volume_party.etat(hass, SALON) == {"volume": 0.3, "groupe": False, "membres": []}
    # Le lecteur Vision seul : aucun volume.
    assert volume_party.etat(hass, TELE_V)["volume"] is None
    # Un groupe (salon + chambre + télé) : le volume du groupe tenu par MA, le détail sans réglage pour la télé.
    hass.states.async_set(SALON, "playing", {"friendly_name": "Salon", "volume_level": 0.3, "group_members": [SALON, CHAMBRE, TELE_V]})
    e = volume_party.etat(hass, SALON)
    assert e["groupe"] is True and e["volume"] == 0.4
    assert [(m["nom"], m["volume"]) for m in e["membres"]] == [("Salon", 0.3), ("Chambre", 0.2), ("Télé du bureau · Vision", None)]
    m = party.maintenant(hass, SALON)
    assert m["volume"] == 0.4 and m["groupe"] is True and len(m["membres"]) == 3

    client = await hass_client()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    # Le bouton de la Party sur un groupe : volume du groupe, aucun volume_set d'enceinte.
    appels.clear()
    s, _ = await _post(client, "commande", PARENT, action="volume", volume=0.55)
    assert s == 200
    assert ("players/cmd/group_volume", {"player_id": "salon_ma", "volume_level": 55}) in mass.envoyes
    assert not [a for a in appels if a[1] == "volume_set"]
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
    _ma(hass, monkeypatch, FauxMass())
    # La Party sur la télé elle-même : le bouton de volume ne fait rien (la télécommande règle le son).
    hass.states.async_set(SALON, "playing", {"friendly_name": "Salon · Vision", "volume_level": 1.0})
    from custom_components.pc_parental import volume_party
    assert await volume_party.regler(hass, SALON, 0.2) is True
    client = await hass_client()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=SALON)
    appels.clear()
    s, _ = await _post(client, "commande", PARENT, action="volume", volume=0.2)
    assert s == 200 and not [a for a in appels if a[1] == "volume_set"]


async def test_mp3_seulement_pour_squeezelite(hass, hass_storage, monkeypatch):
    await _monter(hass, hass_storage)
    mass = FauxMass()
    _ma(hass, monkeypatch, mass)
    from custom_components.pc_parental import musique
    hass.states.async_set(TELE_V, "idle", {"friendly_name": "Télé du bureau · Vision"})
    hass.states.async_set("media_player.essai_natif_vision", "idle", {"friendly_name": "Essai Sendspin natif · Vision"})
    musique._CODEC_FAIT.clear()
    await musique.regler_lecteurs_vision(hass)
    sauves = [kw["player_id"] for c, kw in mass.envoyes if c == "config/players/save"]
    assert sauves == ["02:00:00:00:00:01"]  # l'ancien lecteur Squeezelite ; jamais le lecteur Sendspin « upa0000001 »
    from custom_components.pc_parental.volume_party import est_squeezelite
    assert est_squeezelite("02:00:00:00:00:01") and not est_squeezelite("upa0000001") and not est_squeezelite(None)


async def test_ajout_auto_coupe_sur_tout_lecteur_vision(hass, hass_storage, monkeypatch):
    """2.4.6 (l'auteur : « la file a fini, Play a ajouté 28 titres ») : l'Autoplay de Music Assistant est coupé sur tout
    lecteur « · Vision », au démarrage et dès qu'un nouveau lecteur apparaît ; jamais sur une autre enceinte ; sauf la
    Party en cours sur ce lecteur avec « titres similaires »."""
    await _monter(hass, hass_storage)
    files = {"q_tele": {"autoplay_enabled": True}, "q_neuf": {"autoplay_enabled": True}, "q_ch": {"autoplay_enabled": True}}
    mass = FauxMass()
    mass.player_queues = SimpleNamespace(get=lambda qid: files.get(qid))
    _ma(hass, monkeypatch, mass)
    from custom_components.pc_parental import musique, party
    musique._AUTOPLAY_COUPE.clear()
    hass.states.async_set(TELE_V, "idle", {"friendly_name": "Télé du bureau · Vision", "active_queue": "q_tele", "volume_level": 1.0})
    hass.states.async_set(CHAMBRE, "idle", {"friendly_name": "Chambre", "active_queue": "q_ch", "volume_level": 1.0})
    await hass.async_block_till_done()
    mass.envoyes.clear()

    def coupes():
        return [(kw["queue_id"], kw["autoplay_enabled"]) for c, kw in mass.envoyes if c == "player_queues/autoplay"]

    await musique.regler_lecteurs_vision(hass)
    assert ("q_tele", False) in coupes() and not [q for q, _ in coupes() if q == "q_ch"]
    # Déjà coupé : plus rien n'est envoyé.
    files["q_tele"]["autoplay_enabled"] = False
    mass.envoyes.clear()
    await musique.regler_lecteurs_vision(hass)
    assert coupes() == []
    # Un nouveau lecteur « · Vision » (Sendspin) : coupé dès sa création, sans attendre la minute.
    hass.states.async_set("media_player.salon_sendspin_vision", "idle",
                          {"friendly_name": "Salon · Vision", "active_queue": "q_neuf", "volume_level": 1.0})
    await hass.async_block_till_done()
    assert ("q_neuf", False) in coupes()
    # Party sur ce lecteur avec « titres similaires » : on n'y touche pas.
    files["q_tele"]["autoplay_enabled"] = True
    party.etat(hass).update({"actif": True, "lecteur": TELE_V, "similaires": True})
    mass.envoyes.clear()
    assert await musique.couper_ajout_auto(hass, TELE_V) is False
    assert coupes() == []
    party.etat(hass).update({"actif": False, "similaires": False})


async def test_lecteur_classique_sans_nom_renomme_et_regle(hass, hass_storage, monkeypatch):
    """2.4.7 (télé d'essai) : un lecteur Squeezelite enregistré par MA sous « squeezeplay: 5a:7a:… », que MA connaît
    ensuite comme « Salle De jeux · Vision » : HA renomme l'appareil (pas un nom choisi à la main), coupe l'ajout
    automatique et tient le volume à 100 %."""
    from homeassistant.core import ServiceCall
    from homeassistant.helpers import device_registry as dr
    await _monter(hass, hass_storage)
    mass = FauxMass()
    noms = {"02:00:00:00:00:02": "Salle De jeux · Vision", "11:22:33:44:55:66": "Enceinte du bureau"}
    mass.players = SimpleNamespace(get=lambda pid: {"player_id": pid, "name": noms.get(pid, "")})
    mass.player_queues = SimpleNamespace(get=lambda qid: {"autoplay_enabled": True})
    _ma(hass, monkeypatch, mass)
    from custom_components.pc_parental import musique
    musique._AUTOPLAY_COUPE.clear()
    reg, dreg = er.async_get(hass), dr.async_get(hass)
    entree = hass.config_entries.async_entries("pc_parental")[0]
    eids = {}
    for mac in noms:
        dev = dreg.async_get_or_create(config_entry_id=entree.entry_id, identifiers={("music_assistant", mac)}, name=f"squeezeplay: {mac}")
        e = reg.async_get_or_create("media_player", "music_assistant", mac, device_id=dev.id)
        eids[mac] = (e.entity_id, dev.id)
        hass.states.async_set(e.entity_id, "idle", {"friendly_name": f"squeezeplay: {mac}", "active_queue": mac, "volume_level": 0.1})
    appels = []

    async def volume_set(call: ServiceCall):
        appels.append(call.data["entity_id"])
    hass.services.async_register("media_player", "volume_set", volume_set)
    await musique.regler_lecteurs_vision(hass)
    alex, autre = eids["02:00:00:00:00:02"], eids["11:22:33:44:55:66"]
    assert dreg.async_get(alex[1]).name_by_user == "Salle De jeux · Vision"
    assert dreg.async_get(autre[1]).name_by_user is None  # une autre enceinte : jamais touchée
    assert ("02:00:00:00:00:02", False) in [(kw["queue_id"], kw["autoplay_enabled"]) for c, kw in mass.envoyes if c == "player_queues/autoplay"]
    assert any(alex[0] in (a if isinstance(a, list) else [a]) for a in appels)
    assert not any(autre[0] in (a if isinstance(a, list) else [a]) for a in appels)
    # Un nom choisi à la main n'est jamais écrasé.
    dreg.async_update_device(alex[1], name_by_user="Télé de la chambre")
    await musique.regler_lecteurs_vision(hass)
    assert dreg.async_get(alex[1]).name_by_user == "Télé de la chambre"
