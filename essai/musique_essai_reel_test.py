"""2.5.8 — essai réel (16/10), partie Musique.

- Sendspin choisi sur la télé : l'ancien lecteur « · Vision » (Classique) passe hors ligne ; la soirée passe sur son
  jumeau prêt et la file le suit (avant : Music Assistant jouait dans le vide, « Lancement… » sans fin) ;
- le jumeau « · Vision » d'un Cast coché se choisit comme enceinte (avant : « pas proposée ») ;
- invités : mode « jouer après » ; « Vos titres » dit où en est chaque titre ; la page prend le thème de l'appareil
  qui affiche le code (couleurs dans l'adresse, vérifiées), sinon celui de Home Assistant.

    bash lancer.sh musique_essai_reel_test.py
"""
from __future__ import annotations

from homeassistant.core import ServiceCall
from homeassistant.helpers import entity_registry as er

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    MA_SALON, PARENT, _integrations, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
)

CLASSIQUE = "media_player.televiseur_du_salon_vision"
SENDSPIN = "media_player.televiseur_du_salon_vision_2"
NOM = "Téléviseur du salon · Vision"


async def _preparer(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import widgets
    widgets._recentes.clear()
    coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    async def transfert(call: ServiceCall):
        cibles = call.data.get("entity_id", [])
        for cible in ([cibles] if isinstance(cibles, str) else cibles):
            files.supprimes.append(("transfert", call.data.get("source_player"), cible))
    hass.services.async_register("music_assistant", "transfer_queue", transfert)
    reg = er.async_get(hass)
    for uid, obj in (("02:00:00:00:00:0a", "televiseur_du_salon_vision"), ("up1234", "televiseur_du_salon_vision_2")):
        reg.async_get_or_create("media_player", "music_assistant", uid, suggested_object_id=obj)
    hass.states.async_set(CLASSIQUE, "idle", {"friendly_name": NOM, "active_queue": "q-classique"})
    hass.states.async_set(SENDSPIN, "unavailable", {"friendly_name": NOM})
    return coord, appels, files, await hass_client()


async def test_la_soiree_suit_le_moteur_de_la_tele(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import party
    _coord, appels, files, client = await _preparer(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=CLASSIQUE)
    assert s == 200, r
    # La télé passe en Sendspin : le Classique se déconnecte, le Sendspin arrive.
    hass.states.async_set(CLASSIQUE, "unavailable", {"friendly_name": NOM})
    hass.states.async_set(SENDSPIN, "idle", {"friendly_name": NOM, "active_queue": "q-sendspin"})
    s, r = await _post(client, "commande", PARENT, action="jouer", uri="library://playlist/17", type="playlist", comment="remplacer")
    assert s == 200, r
    assert party.etat(hass)["lecteur"] == SENDSPIN
    jeu = [a[1] for a in appels if a[0] == "play_media"][-1]
    assert jeu["entity_id"] in (SENDSPIN, [SENDSPIN]), jeu
    assert ("transfert", CLASSIQUE, SENDSPIN) in files.supprimes  # service transfer_queue : de l'ancien lecteur vers le jumeau
    # Le relevé d'état montre la même enceinte.
    s, r = await _post(client, "etat", PARENT)
    assert r["lecteur"] == SENDSPIN


async def test_rien_ne_change_sans_jumeau_pret(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import party
    _coord, _appels, files, client = await _preparer(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=CLASSIQUE)
    assert s == 200, r
    hass.states.async_set(CLASSIQUE, "unavailable", {"friendly_name": NOM})  # le Sendspin reste hors ligne aussi
    await _post(client, "etat", PARENT)
    assert party.etat(hass)["lecteur"] == CLASSIQUE and not [x for x in files.supprimes if x[0] == "transfert"]


async def test_le_jumeau_d_un_cast_coche_se_choisit(hass, hass_storage, hass_client, monkeypatch):
    coord, _appels, _files, client = await _preparer(hass, hass_storage, hass_client, monkeypatch)
    reg = er.async_get(hass)
    reg.async_get_or_create("media_player", "music_assistant", "cast-salon-tv", suggested_object_id="televiseur_du_salon_cast")
    hass.states.async_set("media_player.televiseur_du_salon_cast", "idle", {"friendly_name": "Téléviseur du salon"})
    hass.states.async_set(SENDSPIN, "idle", {"friendly_name": NOM, "active_queue": "q-sendspin"})
    coord.store.musique = {"enceintes": ["media_player.televiseur_du_salon_cast", MA_SALON], "masquer_hors_ligne": True}
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200, r
    s, r = await _post(client, "commande", PARENT, action="enceinte", lecteur=CLASSIQUE)
    assert s == 200, r


async def test_invites_jouer_apres_et_vos_titres(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import party
    _coord, appels, files, client = await _preparer(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200, r
    s, r = await _post(client, "commande", PARENT, action="invites", mode="ensuite")
    assert s == 200 and "juste après" in r["retour"], r
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon", "media_title": "En cours"})
    msg = await party.proposer(hass, {"uri": "library://track/145", "titre": "Bam Bam", "artiste": "Sister Nancy"}, "fr", "inv1")
    assert "juste après" in msg
    assert [a[1] for a in appels if a[0] == "play_media"][-1]["enqueue"] == "next"
    # La file de Music Assistant la montre juste après le titre en cours : l'invité le lit.
    files.items.insert(1, {"queue_item_id": "qb", "media_item": {"name": "Bam Bam", "uri": "library://track/145", "artists": []}})
    mes = await party.mes_titres(hass, "inv1", "fr")
    assert mes == [{"titre": "Bam Bam", "artiste": "Sister Nancy", "etat": "Passe juste après le titre en cours", "attente": False}]
    assert await party.mes_titres(hass, "un_autre", "fr") == []  # rien des autres invités
    # Valider avant : « en attente de validation », puis refusé.
    s, r = await _post(client, "commande", PARENT, action="invites", mode="validation")
    await party.proposer(hass, {"uri": "library://track/9", "titre": "Bad Times", "artiste": "X"}, "fr", "inv1")
    mes = await party.mes_titres(hass, "inv1", "fr")
    assert mes[0]["titre"] == "Bad Times" and mes[0]["attente"] and "en attente de validation" in mes[0]["etat"]
    pid = party.etat(hass)["propositions"][-1]["id"]
    s, r = await _post(client, "commande", PARENT, action="refuser", proposition=pid)
    assert s == 200
    assert (await party.mes_titres(hass, "inv1", "fr"))[0]["etat"] == "Pas retenu par les hôtes"
    s, r = await _post(client, "commande", PARENT, action="invites", mode="autre")
    assert s >= 400


async def test_page_invites_au_theme_de_l_appareil(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import party, party_invites
    _coord, _appels, _files, client = await _preparer(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200, r
    jeton = party.etat(hass)["jeton"]
    th = "1a1420,f4ede1,bdb1c4,251c2d,3a2d45,e2b24a,1a1420,e2b24a"
    page = await (await client.get(f"{party_invites.URL}/{jeton}?th={th}")).text()
    assert "--fond:#1A1420" in page and "--accent:#E2B24A" in page and 'content="dark"' in page
    # Rien d'exact (8 couleurs hexadécimales) : le thème de Home Assistant, sans erreur ni injection.
    for mauvais in ("1a1420", "zzzzzz,f4ede1,bdb1c4,251c2d,3a2d45,e2b24a,1a1420,e2b24a", "1a1420;}body{x:y" + ",a" * 7):
        page = await (await client.get(f"{party_invites.URL}/{jeton}", params={"th": mauvais})).text()
        assert "--fond:#EFE6D6" in page and "x:y" not in page
    assert party_invites.theme_de_l_appareil(th)[10] is True


async def test_point16_le_mode_des_invites_survit_au_redemarrage(hass, hass_storage, hass_client, monkeypatch):
    """2.5.9 (l'auteur, point 16) : le mode choisi (ici « valider avant ») était en mémoire seulement ; le redémarrage de HA de
    09:35 le remettait sur « en fin de file ». Il est gardé dans les réglages et repris au lancement suivant."""
    from custom_components.pc_parental import party
    coord, _appels, _files, client = await _preparer(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200, r
    s, r = await _post(client, "commande", PARENT, action="invites", mode="validation")
    assert s == 200, r
    assert coord.store.musique["invites_mode"] == "validation"
    # Redémarrage de HA : l'état de la Party (en mémoire) repart de zéro.
    hass.data.pop(party._CLE, None)
    assert party.etat(hass)["invites_mode"] == "direct"
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200, r
    assert party.etat(hass)["invites_mode"] == "validation"
    # Et relu depuis le stockage, comme après un vrai redémarrage.
    coord.store.musique = {"enceintes": None, "masquer_hors_ligne": False}
    await coord.store.async_load()
    assert party.mode_invites_garde(coord.store) == "validation"
