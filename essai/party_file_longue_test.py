"""2.5.12.2 (l'auteur, 19/10, TV du salon, appli 1.93.06 : « 928 titres, je clique sur Jump Around, c'est The Golden Path
qui s'affiche, ▶ ne donne rien »).

Ce qui s'est passé (journal musique de HA, historique, journaux de Music Assistant) : la soirée était partie sur une
AUTRE enceinte (le lecteur de la télé était hors ligne depuis sa veille ; voir party_lancer_ici_test et, côté appli,
LecteurSlimReconnexionTest). Mais la commande « jouer_element » elle-même était fragile : un titre à venir était
déplacé juste après le titre en cours (move_next) puis lancé par « suivant ». Entre les deux, si la file bouge (titre
qui se termine, lecteur à l'arrêt ou qui se reconnecte, index courant périmé), « suivant » part sur un autre titre
que celui choisi. Depuis la 2.5.12.2, dans tous les cas, Music Assistant lance CE titre (play_index sur son identifiant).

Règles vérifiées ici, sur une longue file (928 titres) dont l'écran ne montre qu'une partie (10 passés, le titre en
cours, 30 à venir) :
- un titre loin dans la file (index 500 et au-delà) : la liste envoyée à l'écran porte bien ses identifiants ;
- un clic sur un titre à venir le lance LUI (play_index), même si la file avance entre le déplacement et le lancement ;
- un clic sur un titre déjà passé le relance lui aussi ; jamais « suivant » ;
- un identifiant inconnu : refusé proprement, rien n'est lancé.

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


async def test_la_liste_affichee_porte_les_bons_identifiants_loin_dans_la_file(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    _longue_file(files)
    s, r = await _post(client, "etat", TELE)
    assert s == 200, r
    # Les 30 à suivre, à partir du titre qui suit celui en cours, avec le vrai total ; les 10 derniers passés.
    assert [x["id"] for x in r["file"]] == [f"q{k}" for k in range(COURANT + 1, COURANT + 31)]
    assert r["file_total"] == N - COURANT - 1
    assert [x["id"] for x in r["file_passes"]] == [f"q{k}" for k in range(COURANT - 10, COURANT)]
    assert r["maintenant"]["titre"] == "" or r["file"][0]["titre"] == "Titre 501"


async def test_un_titre_a_venir_est_lance_lui_meme_meme_si_la_file_avance(hass, hass_storage, hass_client, monkeypatch):
    """Le bug du 19/10 : la file bouge entre le déplacement et le lancement. Avant, « suivant » partait sur le titre
    d'après (The Golden Path au lieu de Jump Around)."""
    _c, _a, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    _longue_file(files)
    suivants = []
    hass.services.async_register("media_player", "media_next_track", lambda call: suivants.append(call.data.get("entity_id")))

    vrai_move_next = files.move_next

    async def move_next_puis_la_file_avance(qid, item):
        await vrai_move_next(qid, item)
        files.courant += 1  # un titre se termine juste à ce moment-là
    monkeypatch.setattr(files, "move_next", move_next_puis_la_file_avance)

    choisi = f"q{COURANT + 25}"  # au-delà des premiers titres, dans la partie affichée
    s, r = await _post(client, "commande", TELE, action="jouer_element", element=choisi)
    assert s == 200, r
    assert r["retour"] == "C'est parti."
    assert ("ensuite", "qid-salon", choisi) in files.supprimes
    assert files.supprimes[-1] == ("jouer", "qid-salon", choisi), files.supprimes[-3:]
    assert suivants == [], "jamais « suivant » : c'est CE titre que Music Assistant lance"


async def test_un_titre_passe_loin_dans_la_file_est_relance_lui_meme(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    _longue_file(files)
    suivants = []
    hass.services.async_register("media_player", "media_next_track", lambda call: suivants.append(call.data.get("entity_id")))
    passe = f"q{COURANT - 7}"
    s, r = await _post(client, "commande", TELE, action="jouer_element", element=passe)
    assert s == 200, r
    # Music Assistant refuse de déplacer un titre passé : il est relancé tel quel, rien d'autre.
    assert ("ensuite", "qid-salon", passe) not in files.supprimes
    assert files.supprimes[-1] == ("jouer", "qid-salon", passe) and suivants == []
    # Le titre en cours lui-même : relancé du début.
    s, r = await _post(client, "commande", TELE, action="jouer_element", element=f"q{COURANT}")
    assert s == 200 and files.supprimes[-1] == ("jouer", "qid-salon", f"q{COURANT}") and suivants == []


async def test_un_identifiant_inconnu_est_refuse_sans_rien_lancer(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    _longue_file(files)

    async def play_index_strict(qid, item):
        if item not in {x["queue_item_id"] for x in files.items}:
            raise KeyError(item)
        files.supprimes.append(("jouer", qid, item))
    monkeypatch.setattr(files, "play_index", play_index_strict)
    avant = list(files.supprimes)
    s, r = await _post(client, "commande", TELE, action="jouer_element", element="q-perime")
    assert s != 200, r
    assert [x for x in files.supprimes if x not in avant and x[0] == "jouer"] == []
    s, r = await _post(client, "commande", TELE, action="jouer_element", element="")
    assert s != 200
