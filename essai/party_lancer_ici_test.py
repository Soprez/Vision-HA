"""2.5.11.1 (l'auteur, 18/10 : « remplacer la file » puis ▶ sur la TV du salon : la file change, rien ne joue).

Journal musique de HA : à 01:23:17 puis à 10:35:24, « lancer_ici » depuis le Téléviseur du salon a choisi l'enceinte
« Télé du bureau · Vision ». Historique de HA : à ces deux instants, le lecteur « Téléviseur du salon · Vision »
était « unavailable » (l'appli venait de se reconnecter à Music Assistant : « Duplicate connection detected ») et
il est revenu 0 à 6 s plus tard. La soirée est partie sur une autre télé, dont le flux ne démarre pas, en silence :
tout ce que l'auteur a fait ensuite (remplacer, ▶, suivant) visait cette enceinte-là.

Règles vérifiées ici :
- une télé qui a son lecteur « · Vision » attend son retour (quelques secondes) avant de choisir une enceinte ;
- son lecteur passe avant la dernière enceinte de la soirée, sauf si celle-ci joue encore (2.2.3) ;
- quand une autre enceinte est tout de même choisie, le message le dit.

    python -m pytest essai/party_lancer_ici_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import asyncio

from homeassistant.helpers import entity_registry as er

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, MA_CHAMBRE, MA_SALON, PARENT, TELE, TELE2, _integrations, _monter, _post, expected_lingering_tasks,
    expected_lingering_timers,
)

SALON_VISION = "media_player.televiseur_du_salon_vision"
PARENTS_VISION = "media_player.tele_du_bureau_vision_2"


async def _maison(hass, hass_storage, hass_client, monkeypatch, salon_etat: str):
    """Les deux lecteurs « · Vision » de Music Assistant (comme en essai le 18/10) ; la dernière soirée était sur la
    Télé du bureau (lancée puis arrêtée) ; l'attente du lecteur propre est raccourcie pour l'essai."""
    from custom_components.pc_parental import party, widgets
    widgets._recentes.clear()
    coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    reg = er.async_get(hass)
    reg.async_get_or_create("media_player", "music_assistant", "16:c5:f4:17:73:61", suggested_object_id="televiseur_du_salon_vision")
    reg.async_get_or_create("media_player", "music_assistant", "up0d4adac9", suggested_object_id="tele_du_bureau_vision_2")
    hass.states.async_set(SALON_VISION, salon_etat, {"friendly_name": "Téléviseur du salon · Vision", "active_queue": "16:c5:f4:17:73:61"})
    hass.states.async_set(PARENTS_VISION, "idle", {"friendly_name": "Télé du bureau · Vision", "active_queue": "up0d4adac9"})
    client = await hass_client()
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t2", lecteur=PARENTS_VISION)
    assert s == 200, r
    s, r = await _post(client, "commande", PARENT, action="arreter")
    assert s == 200 and party.etat(hass)["lecteur"] == PARENTS_VISION
    monkeypatch.setattr(party, "ATTENTE_LECTEUR_PROPRE_S", 1.5, raising=False)
    return coord, appels, files, client


async def test_le_lecteur_de_la_tele_revient_pendant_le_lancement(hass, hass_storage, hass_client, monkeypatch):
    """Le bug du 18/10 : le lecteur du salon est hors ligne à l'instant du lancer_ici et revient 0,4 s plus tard.
    Sur la 2.5.11, la soirée partait sur la Télé du bureau."""
    from custom_components.pc_parental import party
    _c, _a, _f, client = await _maison(hass, hass_storage, hass_client, monkeypatch, "unavailable")

    async def retour():
        await asyncio.sleep(0.4)
        hass.states.async_set(SALON_VISION, "off", {"friendly_name": "Téléviseur du salon · Vision", "active_queue": "16:c5:f4:17:73:61"})
    tache = hass.async_create_task(retour())
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    await tache
    assert s == 200, r
    assert party.etat(hass)["lecteur"] == SALON_VISION
    assert r["retour"] == "Musique lancée."
    s, r = await _post(client, "etat", TELE)
    assert s == 200 and r["lecteur"] == SALON_VISION and r["lecteur_nom"] == "Téléviseur du salon · Vision"


async def test_le_lecteur_de_la_tele_ne_revient_pas_le_message_dit_l_enceinte(hass, hass_storage, hass_client, monkeypatch):
    """Le lecteur du salon reste hors ligne : on ne bloque pas la soirée, mais la télé dit sur quelle enceinte on joue
    (le 18/10, rien ne disait que la Télé du bureau avait été choisie)."""
    from custom_components.pc_parental import party
    _c, _a, _f, client = await _maison(hass, hass_storage, hass_client, monkeypatch, "unavailable")
    monkeypatch.setattr(party, "ATTENTE_LECTEUR_PROPRE_S", 0.3, raising=False)
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    assert s == 200, r
    assert party.etat(hass)["lecteur"] == PARENTS_VISION
    assert r["retour"] == "Musique lancée sur Télé du bureau · Vision."


async def test_le_lecteur_de_la_tele_passe_avant_la_derniere_enceinte(hass, hass_storage, hass_client, monkeypatch):
    """Le lecteur du salon est prêt : il l'emporte sur la dernière enceinte de la soirée, qui ne joue pas."""
    from custom_components.pc_parental import party
    coord, _a, _f, client = await _maison(hass, hass_storage, hass_client, monkeypatch, "off")
    tele = coord.store.pcs["t1"]
    assert party.lecteur_par_defaut(hass, coord.store, tele) == SALON_VISION
    # L'écran Party, hors soirée, montre déjà cette enceinte (« vu »).
    s, r = await _post(client, "etat", TELE)
    assert s == 200 and r["lecteur"] == SALON_VISION
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    assert s == 200 and party.etat(hass)["lecteur"] == SALON_VISION and r["retour"] == "Musique lancée."


async def test_une_enceinte_qui_joue_encore_garde_la_main(hass, hass_storage, hass_client, monkeypatch):
    """2.2.3 (l'auteur : « quand je reviens, la musique doit continuer ») : la dernière enceinte joue toujours, elle reste."""
    from custom_components.pc_parental import party
    _c, _a, _f, client = await _maison(hass, hass_storage, hass_client, monkeypatch, "off")
    hass.states.async_set(PARENTS_VISION, "playing", {"friendly_name": "Télé du bureau · Vision", "active_queue": "up0d4adac9", "media_title": "X"})
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    assert s == 200 and party.etat(hass)["lecteur"] == PARENTS_VISION
    assert r["retour"] == "Musique lancée sur Télé du bureau · Vision."


async def test_une_tele_sans_lecteur_propre_comme_avant(hass, hass_storage, hass_client, monkeypatch):
    """Une télé sans lecteur « · Vision » : l'enceinte de la même pièce, tout de suite (pas d'attente), et le message
    nomme l'enceinte."""
    from custom_components.pc_parental import party, widgets
    widgets._recentes.clear()
    coord, _a, _f = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client()
    tele = coord.store.pcs["t1"]
    assert party.lecteur_par_defaut(hass, coord.store, tele) == MA_SALON
    loop = hass.loop
    t0 = loop.time()
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    assert s == 200 and party.etat(hass)["lecteur"] == MA_SALON and loop.time() - t0 < 1.0
    assert r["retour"] == "Musique lancée sur Salon."
