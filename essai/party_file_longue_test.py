"""2.5.12.2 (l'auteur, 19/10, TV du salon, appli 1.93.06 : « 928 titres, je clique sur Jump Around, c'est The Golden Path
qui s'affiche, ▶ ne donne rien ») puis 2.5.12.14.2 (revue de HACS, hacs/default#12284).

Jusqu'à la 2.5.12.14 Vision lisait la file complète par le client interne de Music Assistant (30 titres à venir, 10 passés,
lancer ou déplacer un titre précis). La revue de HACS a retiré ce client : la file ne se lit plus que par le service
`music_assistant.get_queue` (titre en cours, titre suivant, nombre de titres), et lancer, déplacer ou retirer un titre
précis est « à faire dans Music Assistant ». Les essais de la file longue sont donc réduits à ce qui reste vrai, sur une
file de 928 titres :
- seul le titre suivant est servi à l'écran (ni 30 titres à venir, ni titres passés) ; le total vient de `get_queue` ;
- lancer un titre de la file renvoie « À faire dans Music Assistant », sans rien lancer d'autre (jamais « suivant »).

    python -m pytest essai/party_file_longue_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, MA_CHAMBRE, MA_SALON, PARENT, TELE, _integrations, _monter, _post, expected_lingering_tasks,
    expected_lingering_timers,
)
from party_soir_test import _party

N = 928
COURANT = 500


def _longue_file(files, courant: int = COURANT):
    files.items = [{"queue_item_id": f"q{k}", "media_item": {"name": f"Titre {k}", "uri": f"library://track/{k}",
                                                             "artists": [{"name": f"Artiste {k}"}]}} for k in range(N)]
    files.courant = courant


async def test_longue_file_seul_le_titre_suivant_est_servi(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    _longue_file(files)
    s, r = await _post(client, "etat", TELE)
    assert s == 200, r
    # Le service get_queue ne donne que le titre qui suit celui en cours : un seul titre, pas de titres passés.
    assert [x["id"] for x in r["file"]] == [f"q{COURANT + 1}"] and r["file"][0]["titre"] == "Titre 501"
    assert r["file_passes"] == []
    assert r["file_total"] == N  # le champ « items » de get_queue
    assert files.supprimes == []  # aucun client interne appelé


async def test_longue_file_lancer_un_titre_est_a_faire_dans_music_assistant(hass, hass_storage, hass_client, monkeypatch):
    """Avant : un clic sur un titre (à venir, passé ou inconnu) le lançait lui-même (play_index). Plus de client : l'action
    dit « À faire dans Music Assistant » (400, comme toute erreur de commande), ne lance rien, jamais « suivant »."""
    _c, _a, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    _longue_file(files)
    suivants = []
    hass.services.async_register("media_player", "media_next_track", lambda call: suivants.append(call.data.get("entity_id")))
    for element in (f"q{COURANT + 25}", f"q{COURANT - 7}", f"q{COURANT}", "q-perime"):
        s, r = await _post(client, "commande", TELE, action="jouer_element", element=element)
        assert s == 400 and r["retour"].startswith("À faire dans Music Assistant"), (element, s, r)
    assert files.supprimes == [] and suivants == []
