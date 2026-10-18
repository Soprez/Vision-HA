"""2.5.7 — 1.93.0 en production en essai (16/10) : « je choisis l'écran Télé du bureau, ça reste vide ».

État réel relevé (lecture) :
- deux lecteurs Music Assistant « Télé du bureau · Vision » : l'ancien Classique (hors ligne) et le Sendspin (prêt),
  le second vu AVANT le premier par Home Assistant ;
- options Vision → Musique : enceintes cochées « televiseur_du_salon_4 » (n'existe plus) et « tele_du_bureau_2 »
  (le Cast de la télé, remplacé par son lecteur « · Vision »), hors ligne masqués.
Avant : le Cast était remplacé par le jumeau hors ligne, aussitôt masqué ; l'autre case ne menait à rien → aucune enceinte.

    bash lancer.sh party_lecteurs_jumeaux_test.py
"""
from __future__ import annotations

from homeassistant.helpers import entity_registry as er

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    MA_CHAMBRE, MA_SALON, PARENT, _integrations, _monter, _post, expected_lingering_tasks,
    expected_lingering_timers,
)

CAST_TDP = "media_player.tele_du_bureau_2"
VISION_PRET = "media_player.tele_du_bureau_vision_2"
VISION_FANTOME = "media_player.tele_du_bureau_vision"


def _maison(hass, coord, ordre_reel: bool = True):
    reg = er.async_get(hass)
    for uid, obj in (("cast-tdp", "tele_du_bureau_2"), ("up0d4adac9", "tele_du_bureau_vision_2"),
                     ("02:00:00:00:00:01", "tele_du_bureau_vision")):
        reg.async_get_or_create("media_player", "music_assistant", uid, suggested_object_id=obj)
    hass.states.async_set(CAST_TDP, "idle", {"friendly_name": "Télé du bureau"})
    vision = [(VISION_PRET, "idle"), (VISION_FANTOME, "unavailable")]
    for eid, etat in (vision if ordre_reel else vision[::-1]):
        hass.states.async_set(eid, etat, {"friendly_name": "Télé du bureau · Vision"})
    coord.store.musique = {"enceintes": ["media_player.televiseur_du_salon_4", CAST_TDP], "masquer_hors_ligne": True}


async def test_le_jumeau_qui_marche_est_propose(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import party, widgets
    widgets._recentes.clear()
    coord, _appels, _files = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client()
    for ordre_reel in (True, False):
        _maison(hass, coord, ordre_reel)
        # Le téléphone du Décideur (la liste qu'il voit) et la télé du bureau elle-même.
        s, r = await _post(client, "etat", PARENT)
        listes = {"p1": [x["entity_id"] for x in r["lecteurs"]],
                  "t2": [x["entity_id"] for x in party.lecteurs_pour(hass, coord.store, coord.store.pcs["t2"])]}
        for qui, ids in listes.items():
            assert s == 200 and VISION_PRET in ids, (ordre_reel, qui, ids)
            assert VISION_FANTOME not in ids and CAST_TDP not in ids, (qui, ids)
        assert party.lecteur_par_defaut(hass, coord.store, coord.store.pcs["t2"]) == VISION_PRET


async def test_cases_perimees_jamais_de_liste_vide(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import widgets
    widgets._recentes.clear()
    coord, _appels, _files = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client()
    # Seule case cochée : une entité qui n'existe plus. Les lecteurs disponibles restent proposés.
    coord.store.musique = {"enceintes": ["media_player.televiseur_du_salon_4"], "masquer_hors_ligne": True}
    s, r = await _post(client, "etat", PARENT)
    ids = [x["entity_id"] for x in r["lecteurs"]]
    assert s == 200 and {MA_SALON, MA_CHAMBRE} <= set(ids), ids
    # Une case valide : le choix de l'auteur est respecté (rien d'autre n'est ajouté).
    coord.store.musique = {"enceintes": [MA_CHAMBRE], "masquer_hors_ligne": True}
    s, r = await _post(client, "etat", PARENT)
    assert [x["entity_id"] for x in r["lecteurs"]] == [MA_CHAMBRE]
