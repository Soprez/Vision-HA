"""2.1.0 — la Party ouverte à tous (décision du 2026-10-10), sauf l'interrupteur Mode Fête.

Pendant une Party, un enfant choisit la musique, met en pause, passe, règle le volume (retirer un titre : à faire dans Music Assistant). Il ne lance
pas, n'arrête pas, ne change pas d'enceinte, ne touche ni aux invités ni au Mode Fête. Un appareil fermé par ses
règles (la nuit) ne participe pas, et le dit.

    python -m pytest essai/party_tous_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, MA_CHAMBRE, MA_SALON, PARENT, TELE, _integrations, _monter, _play, _post, expected_lingering_tasks,
    expected_lingering_timers,
)


async def test_pendant_la_party_un_enfant_participe(hass, hass_storage, hass_client, monkeypatch):
    coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import party
    client = await hass_client()
    # Sans Party : rien.
    s, r = await _post(client, "commande", ENFANT, action="jouer", uri="library://track/145", type="track")
    assert s == 403
    s, r = await _post(client, "musique", ENFANT, quoi="chercher", texte="queen")
    assert s == 403
    assert party.pour_releve(hass, coord.store, coord.store.pcs["e1"])["tuile"] is False
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert party.pour_releve(hass, coord.store, coord.store.pcs["e1"])["tuile"] is True
    s, r = await _post(client, "etat", ENFANT)
    assert s == 200 and r["participer"] is True and r["choisir"] is True and r["qr"] == "" and "ecrans" not in r
    s, r = await _post(client, "musique", ENFANT, quoi="chercher", texte="queen")
    assert s == 200 and r["resultats"]["track"]
    # L'enfant joue sur l'enceinte de la Party, même s'il en demande une autre.
    s, r = await _post(client, "commande", ENFANT, action="jouer", lecteur=MA_CHAMBRE, uri="library://track/145", type="track", comment="ajouter")
    assert s == 200 and _play(appels)[-1]["entity_id"] in (MA_SALON, [MA_SALON])
    for action in ("lecture", "suivant"):
        s, r = await _post(client, "commande", ENFANT, action=action)
        assert s != 403, (action, r)  # le service de lecture lui-même n'existe pas dans le banc
    s, r = await _post(client, "commande", ENFANT, action="volume", volume=0.3)
    assert s != 403
    # Retirer un titre n'existe plus dans Vision (2.5.12.14.2) : permis à l'enfant, mais « à faire dans Music Assistant ».
    s, r = await _post(client, "commande", ENFANT, action="retirer", element="q1")
    assert s == 400 and r["retour"].startswith("À faire dans Music Assistant") and files.supprimes == []
    # Ce qui reste aux décideurs.
    for action, plus in (("arreter", {}), ("lancer", {"ecran": "t1", "lecteur": MA_SALON}),
                         ("enceinte", {"lecteur": MA_CHAMBRE}), ("invites", {"mode": "direct"}), ("nouveau_lien", {})):
        s, r = await _post(client, "commande", ENFANT, action=action, **plus)
        assert s == 403 and r["retour"] == "Réservé aux décideurs.", action
    assert party.etat(hass)["lecteur"] == MA_SALON


async def test_un_appareil_ferme_ne_participe_pas(hass, hass_storage, hass_client, monkeypatch):
    coord, appels, _f = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    coord.store.pcs["e1"]["override"] = {"mode": "locked", "until": time.time() + 3600}
    s, r = await _post(client, "etat", ENFANT)
    assert s == 200 and r["participer"] is False and "Fermé pour l'instant" in r["ferme"]
    n = len(_play(appels))
    s, r = await _post(client, "commande", ENFANT, action="jouer", uri="library://track/145", type="track")
    assert s == 403 and "Fermé pour l'instant" in r["retour"] and len(_play(appels)) == n
    s, r = await _post(client, "musique", ENFANT, quoi="chercher", texte="queen")
    assert s == 403


async def test_la_tele_n_est_jamais_sa_propre_enceinte(hass, hass_storage, hass_client, monkeypatch):
    """2.2.0 (retour d'essai) : jouer sur le lecteur Music Assistant de la télé de la Party ouvre l'appli Cast de Music
    Assistant par-dessus l'écran Party. L'enceinte par défaut est celle de la pièce ; la télé elle-même est refusée."""
    coord, appels, _f = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import party
    from homeassistant.helpers import entity_registry as er
    er.async_get(hass).async_get_or_create("media_player", "music_assistant", "tele_salon", suggested_object_id="televiseur_du_salon_4")
    hass.states.async_set("media_player.televiseur_du_salon_4", "idle", {"friendly_name": "Téléviseur du salon", "active_queue": "q-tv"})
    tele = coord.store.pcs["t1"]
    assert party.lecteur_par_defaut(hass, coord.store, tele) == MA_SALON  # « Salon », la même pièce
    client = await hass_client()
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur="media_player.televiseur_du_salon_4")
    assert s == 200 and party.etat(hass)["lecteur"] == "media_player.televiseur_du_salon_4"  # 2.2.2 : permis (retour d'essai)
    s, r = await _post(client, "commande", PARENT, action="arreter")
    assert s == 200
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    assert s == 200 and party.etat(hass)["lecteur"] == MA_SALON
