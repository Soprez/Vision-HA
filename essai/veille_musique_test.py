"""2.4.8 — la musique de la veille (retour d'essai) : une playlist favorite de Music Assistant pendant l'écran de veille.

Règles : désactivée par défaut (côté appareil) ; la musique lancée par la veille s'arrête TOUJOURS en sortie ; si le
lecteur de l'appareil joue déjà, rien n'est lancé ; autoplay coupé, volume à 100 % ; jamais le lecteur d'un autre.

    python -m pytest essai/veille_musique_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from homeassistant.core import ServiceCall

from deverrouillage_test import _banc  # noqa: F401  (le banc de l'intégration)
from party_test import (  # noqa: F401  (fixtures du banc)
    PARENT, TELE, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)

PROPRE = "media_player.tele_salon_vision"
AUTRE = "media_player.cuisine_vision"
FAVORIS = [
    {"uri": "library://playlist/7", "nom": "Disney classique", "type": "playlist", "image": "", "auto": False},
    {"uri": "library://track/3", "nom": "Un titre", "type": "track"},
]
BIBLIOTHEQUE = [
    {"uri": "library://playlist/4", "nom": "500 Random tracks", "type": "playlist", "image": "", "auto": True},
    {"uri": "library://playlist/7", "nom": "Disney classique", "type": "playlist", "image": "", "auto": False},
    {"uri": "library://playlist/15", "nom": "Groovy Beats", "type": "playlist", "image": "", "auto": False},
    {"uri": "library://playlist/14", "nom": "Histoire du soir", "type": "playlist", "image": "", "auto": False},
]


async def _veille(hass, hass_storage, monkeypatch, etat_propre="idle"):
    from custom_components.pc_parental import musique

    coord, appels = await _monter(hass, hass_storage)
    hass.states.async_set(PROPRE, etat_propre, {"friendly_name": "Téléviseur du salon · Vision", "repeat": "off"})
    hass.states.async_set(AUTRE, "idle", {"friendly_name": "Cuisine · Vision"})
    monkeypatch.setattr(musique, "lecteurs_ma", lambda _h: {PROPRE, AUTRE, "media_player.salon"})

    async def biblio(_h, quoi):
        assert quoi == "favoris"
        return [dict(x) for x in FAVORIS]
    monkeypatch.setattr(musique, "bibliotheque", biblio)

    async def toutes(_h):
        return [dict(x) for x in BIBLIOTHEQUE]
    monkeypatch.setattr(musique, "playlists_bibliotheque", toutes)

    async def jouer(_h, lecteur, uri, type_, comment):
        appels.append(("ma", "jouer", {"lecteur": lecteur, "uri": uri, "type": type_, "comment": comment}))
    monkeypatch.setattr(musique, "jouer", jouer)

    async def sans_auto(_h, lecteur, similaires=False):
        appels.append(("ma", "sans_ajout_auto", {"lecteur": lecteur, "similaires": similaires}))
    monkeypatch.setattr(musique, "sans_ajout_auto", sans_auto)

    async def volume(_h, lecteur):
        appels.append(("ma", "tenir_volume", {"lecteur": lecteur}))
        return True
    monkeypatch.setattr(musique, "tenir_volume", volume)

    async def noter(call: ServiceCall):
        appels.append((call.domain, call.service, dict(call.data)))
    for s in ("media_stop", "clear_playlist", "repeat_set", "media_pause"):
        hass.services.async_register("media_player", s, noter)
    return coord, appels


async def _post(client, qui, **corps):
    r = await client.post("/api/pc_parental/veille/musique", json={**qui, **corps})
    return r.status, await r.json()


def _de(appels, service):
    return [d for (_dom, s, d) in appels if s == service]


async def test_playlists_favorites_d_abord_puis_toutes(hass, hass_storage, hass_client, monkeypatch):
    """l'auteur (15/10) : sans aucune favorite, la veille propose quand même les playlists de la bibliothèque."""
    await _veille(hass, hass_storage, monkeypatch)
    client = await hass_client()
    s, r = await _post(client, TELE, action="playlists")
    assert s == 200 and r["lecteur"] is True
    # La favorite d'abord (le titre favori n'est pas une playlist), puis les autres sans doublon, les automatiques en dernier.
    assert [x["nom"] for x in r["items"]] == ["Disney classique", "Groovy Beats", "Histoire du soir", "500 Random tracks"]
    assert [x["favori"] for x in r["items"]] == [True, False, False, False]
    # Une playlist non favorite se lance aussi.
    s, r = await _post(client, TELE, action="debut", uri="library://playlist/15")
    assert s == 200 and r["lance"] is True, r


async def test_aucune_favorite(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import musique

    await _veille(hass, hass_storage, monkeypatch)

    async def vide(_h, quoi):
        return []
    monkeypatch.setattr(musique, "bibliotheque", vide)
    client = await hass_client()
    s, r = await _post(client, TELE, action="playlists")
    assert s == 200 and [x["nom"] for x in r["items"]] == ["Disney classique", "Groovy Beats", "Histoire du soir", "500 Random tracks"]
    assert not any(x["favori"] for x in r["items"])


async def test_debut_sur_son_lecteur_autoplay_coupe_volume_100_en_boucle(hass, hass_storage, hass_client, monkeypatch):
    _c, appels = await _veille(hass, hass_storage, monkeypatch)
    client = await hass_client()
    s, r = await _post(client, TELE, action="debut", uri="library://playlist/7")
    assert s == 200 and r["lance"] is True, r
    assert _de(appels, "jouer") == [{"lecteur": PROPRE, "uri": "library://playlist/7", "type": "playlist", "comment": "replace"}]
    assert _de(appels, "sans_ajout_auto")[-1] == {"lecteur": PROPRE, "similaires": False}
    assert _de(appels, "tenir_volume") == [{"lecteur": PROPRE}]
    assert _de(appels, "repeat_set")[-1] == {"entity_id": PROPRE, "repeat": "all"}
    # Jamais le lecteur d'un autre appareil.
    assert all(d.get("lecteur", d.get("entity_id")) == PROPRE for d in _de(appels, "jouer") + _de(appels, "repeat_set"))


async def test_fin_arrete_toujours_et_remet_la_repetition(hass, hass_storage, hass_client, monkeypatch):
    _c, appels = await _veille(hass, hass_storage, monkeypatch)
    client = await hass_client()
    await _post(client, TELE, action="debut", uri="library://playlist/7")
    hass.states.async_set(PROPRE, "playing", {"friendly_name": "Téléviseur du salon · Vision", "repeat": "all"})
    s, r = await _post(client, TELE, action="fin")
    assert s == 200 and r["arrete"] is True
    assert _de(appels, "media_stop") == [{"entity_id": PROPRE}]
    assert _de(appels, "clear_playlist") == [{"entity_id": PROPRE}]
    assert _de(appels, "repeat_set")[-1] == {"entity_id": PROPRE, "repeat": "off"}
    # Une deuxième fin ne touche plus à rien.
    s, r = await _post(client, TELE, action="fin")
    assert r["arrete"] is False and len(_de(appels, "media_stop")) == 1


async def test_le_lecteur_joue_deja_rien_n_est_lance_ni_arrete(hass, hass_storage, hass_client, monkeypatch):
    _c, appels = await _veille(hass, hass_storage, monkeypatch, etat_propre="playing")
    client = await hass_client()
    s, r = await _post(client, TELE, action="debut", uri="library://playlist/7")
    assert s == 200 and r == {"ok": True, "lance": False, "raison": "joue_deja"}
    assert not _de(appels, "jouer")
    # Sortie de veille : la musique d'avant continue.
    s, r = await _post(client, TELE, action="fin")
    assert r["arrete"] is False and not _de(appels, "media_stop")


async def test_playlist_qui_n_est_plus_favorite_et_sans_lecteur(hass, hass_storage, hass_client, monkeypatch):
    _c, appels = await _veille(hass, hass_storage, monkeypatch)
    client = await hass_client()
    s, r = await _post(client, TELE, action="debut", uri="library://playlist/99")
    assert r["lance"] is False and r["raison"] == "introuvable"
    # Un appareil sans lecteur « · Vision » (le téléphone du Décideur) : rien.
    s, r = await _post(client, PARENT, action="debut", uri="library://playlist/7")
    assert r["lance"] is False and r["raison"] == "lecteur"
    assert not _de(appels, "jouer")


async def test_sans_nouvelles_la_musique_de_veille_s_arrete(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import veille_musique

    _c, appels = await _veille(hass, hass_storage, monkeypatch)
    client = await hass_client()
    await _post(client, TELE, action="debut", uri="library://playlist/7")
    v = veille_musique._EN_COURS["t1"]
    assert await veille_musique.tour(hass, v["vu"] + 60) == []
    s, r = await _post(client, TELE, action="maintenir")
    assert r["en_cours"] is True
    assert await veille_musique.tour(hass, v["vu"] + veille_musique.EXPIRE_S + 1) == [PROPRE]
    assert _de(appels, "media_stop") == [{"entity_id": PROPRE}]


async def test_pas_d_arret_apres_inactivite_pendant_la_veille_et_ecran_eteint(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import musique_arret, veille_musique

    coord, appels = await _veille(hass, hass_storage, monkeypatch)
    client = await hass_client()
    await _post(client, TELE, action="debut", uri="library://playlist/7")
    hass.states.async_set(PROPRE, "playing", {"friendly_name": "Téléviseur du salon · Vision"})
    monkeypatch.setattr(musique_arret, "delai_min", lambda _s: 5)
    assert PROPRE not in await musique_arret.tour(hass, coord, 10 ** 10)
    # L'écran s'éteint : la veille est finie, sa musique s'arrête (pas une simple pause).
    assert await musique_arret.ecran(hass, coord.store, coord.store.pcs["t1"], "eteint") is True
    assert _de(appels, "media_stop") == [{"entity_id": PROPRE}]
    assert "t1" not in veille_musique._EN_COURS


class _Mass:
    """Le client Music Assistant de l'intégration officielle, simulé : il note les commandes de favoris."""

    def __init__(self):
        self.appels = []
        mass = self

        class _Music:
            async def add_item_to_favorites(self, item):
                mass.appels.append(("ajout", item))

            async def remove_item_from_favorites(self, media_type, library_item_id):
                mass.appels.append(("retrait", media_type, library_item_id))

            async def get_item_by_uri(self, uri):
                class _I:
                    media_type = "playlist"; item_id = "21"; provider = "library"
                return _I()
        self.music = _Music()


def _brancher_mass(monkeypatch):
    from custom_components.pc_parental import musique

    mass = _Mass()
    entree = type("E", (), {"runtime_data": type("R", (), {"mass": mass})()})()
    monkeypatch.setattr(musique, "entree", lambda _h: entree)
    return mass


async def test_coeur_favori_par_le_client_music_assistant(hass, hass_storage, hass_client, monkeypatch):
    """l'auteur (15/10) : le cœur ajoute ou retire le favori dans Music Assistant, sans ouvrir son interface."""
    from deverrouillage_test import ENFANT

    await _veille(hass, hass_storage, monkeypatch)
    mass = _brancher_mass(monkeypatch)
    client = await hass_client()
    s, r = await _post(client, TELE, action="favori", uri="ytmusic://playlist/PLabc", favori=True)
    assert s == 200 and r["favori"] is True
    s, r = await _post(client, TELE, action="favori", uri="library://playlist/15", favori=False)
    assert s == 200 and r["favori"] is False
    s, r = await _post(client, TELE, action="favori", uri="ytmusic://playlist/PLabc", favori=False)
    assert s == 200
    assert mass.appels == [("ajout", "ytmusic://playlist/PLabc"), ("retrait", "playlist", "15"), ("retrait", "playlist", "21")]
    # Le téléphone d'un Demandeur ne change pas les favoris de tout le monde.
    s, r = await _post(client, ENFANT, action="favori", uri="library://playlist/15", favori=True)
    assert s == 403 and len(mass.appels) == 3
    # Une uri invalide : refusée proprement.
    s, r = await _post(client, TELE, action="favori", uri="pas-une-uri", favori=True)
    assert s == 503 and len(mass.appels) == 3


async def test_recherche_de_playlists_tous_fournisseurs(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import musique

    await _veille(hass, hass_storage, monkeypatch)
    vus = []

    async def chercher(_h, texte, types):
        vus.append((texte, types))
        return {"playlist": [{"uri": "ytmusic://playlist/PLx", "nom": "Groove du soir", "type": "playlist", "favori": False, "source": "YouTube Music"}]}
    monkeypatch.setattr(musique, "chercher", chercher)
    client = await hass_client()
    s, r = await _post(client, TELE, action="recherche", texte="groove")
    assert s == 200 and vus == [("groove", ("playlist",))]
    assert r["items"] == [{"uri": "ytmusic://playlist/PLx", "nom": "Groove du soir", "image": "", "auto": False, "favori": False, "source": "YouTube Music"}]
    # Une playlist trouvée chez un fournisseur se lance aussi pour la veille.
    s, r = await _post(client, TELE, action="debut", uri="ytmusic://playlist/PLx")
    assert s == 200 and r["lance"] is True, r


async def test_meme_coeur_dans_l_appli_musique(hass, hass_storage, hass_client, monkeypatch):
    """Le même appel (musique.basculer_favori) depuis l'appli Musique de Vision : /party/musique, quoi = favori."""
    await _veille(hass, hass_storage, monkeypatch)
    mass = _brancher_mass(monkeypatch)
    client = await hass_client()
    r = await client.post("/api/pc_parental/party/musique", json={**TELE, "quoi": "favori", "uri": "library://playlist/7", "favori": True})
    assert r.status == 200 and (await r.json())["favori"] is True
    assert mass.appels == [("ajout", "library://playlist/7")]


async def test_suite_demande_le_titre_suivant_sur_son_propre_lecteur(hass, hass_storage, hass_client, monkeypatch):
    """2.5.9 (l'auteur, point 25) : le lecteur Classique a fini un titre sans recevoir le suivant (Music Assistant n'a pas
    réessayé) ; HA demande le titre suivant, sur le lecteur de l'appareil seulement, même hors veille."""
    _c, appels = await _veille(hass, hass_storage, monkeypatch, etat_propre="playing")

    async def noter(call: ServiceCall):
        appels.append((call.domain, call.service, dict(call.data)))
    hass.services.async_register("media_player", "media_next_track", noter)
    client = await hass_client()
    s, r = await _post(client, TELE, action="suite")
    assert s == 200 and r["relance"] is True, r
    assert _de(appels, "media_next_track") == [{"entity_id": PROPRE}]


async def test_suite_sans_lecteur_ou_refusee_ne_fait_rien(hass, hass_storage, hass_client, monkeypatch):
    from homeassistant.exceptions import HomeAssistantError

    from custom_components.pc_parental import musique

    _c, appels = await _veille(hass, hass_storage, monkeypatch, etat_propre="playing")

    async def refus(call: ServiceCall):
        raise HomeAssistantError("plus de titre")
    hass.services.async_register("media_player", "media_next_track", refus)
    client = await hass_client()
    # Fin de file : Music Assistant refuse, l'appareil s'arrêtera comme avant.
    s, r = await _post(client, TELE, action="suite")
    assert s == 200 and r["relance"] is False
    # Pas de lecteur « · Vision » à lui : jamais celui d'un autre.
    monkeypatch.setattr(musique, "lecteurs_ma", lambda _h: {AUTRE, "media_player.salon"})
    s, r = await _post(client, TELE, action="suite")
    assert s == 200 and r["relance"] is False


async def test_reprise_a_la_main_jamais_arretee_ni_videe(hass, hass_storage, hass_client, monkeypatch):
    """Incidents du salon (18/10, 10:43 et 19:46) : une veille joue, l'appli est relancée sans dire « fin »
    (réinstallation), puis on écoute la même file à la main (« suivant »). 6 min plus tard, l'expiration arrêtait le
    lecteur et VIDAIT sa file. Désormais la musique reprise à la main est lâchée par la veille : rien n'est arrêté."""
    from homeassistant.core import Context
    from custom_components.pc_parental import veille_musique

    _c, appels = await _veille(hass, hass_storage, monkeypatch)

    async def noter(call):
        appels.append((call.domain, call.service, dict(call.data)))
    for s in ("media_next_track", "volume_set", "media_play"):
        hass.services.async_register("media_player", s, noter)
    client = await hass_client()
    await _post(client, TELE, action="debut", uri="library://playlist/7")
    v = dict(veille_musique._EN_COURS["t1"])
    # L'appli est relancée (le lecteur disparaît puis revient), sans « fin » ni « maintenir ».
    hass.states.async_set(PROPRE, "unavailable", {"friendly_name": "Téléviseur du salon · Vision"})
    hass.states.async_set(PROPRE, "playing", {"friendly_name": "Téléviseur du salon · Vision", "repeat": "all"})
    # Le volume ne compte pas, ni une commande sur un autre lecteur : la veille tient encore.
    await hass.services.async_call("media_player", "volume_set", {"entity_id": PROPRE, "volume_level": 0.3}, blocking=True)
    await hass.services.async_call("media_player", "media_next_track", {"entity_id": AUTRE}, blocking=True)
    assert "t1" in veille_musique._EN_COURS
    # « Suivant » à la main sur ce lecteur (le lecteur de l'appli, la carte, une automatisation) : reprise.
    await hass.services.async_call("media_player", "media_next_track", {"entity_id": PROPRE}, blocking=True,
                                   context=Context(user_id="ja"))
    await hass.async_block_till_done()
    assert "t1" not in veille_musique._EN_COURS
    # 6 min plus tard : rien n'est arrêté, la file n'est pas vidée.
    assert await veille_musique.tour(hass, v["vu"] + veille_musique.EXPIRE_S + 1) == []
    assert not _de(appels, "media_stop") and not _de(appels, "clear_playlist")
    # La sortie de veille n'arrête plus cette musique non plus.
    s, r = await _post(client, TELE, action="fin")
    assert r["arrete"] is False and not _de(appels, "media_stop")


async def test_le_titre_suivant_de_vision_n_est_pas_une_reprise(hass, hass_storage, hass_client, monkeypatch):
    """Le « suivant » demandé par Vision elle-même (lecteur Classique, fin de titre) garde la veille : sans nouvelles,
    elle s'arrête toujours (le filet de sûreté reste)."""
    from custom_components.pc_parental import veille_musique

    _c, appels = await _veille(hass, hass_storage, monkeypatch)

    async def noter(call):
        appels.append((call.domain, call.service, dict(call.data)))
    hass.services.async_register("media_player", "media_next_track", noter)
    client = await hass_client()
    await _post(client, TELE, action="debut", uri="library://playlist/7")
    v = dict(veille_musique._EN_COURS["t1"])
    s, r = await _post(client, TELE, action="suite")
    assert s == 200 and _de(appels, "media_next_track") == [{"entity_id": PROPRE}]
    await hass.async_block_till_done()
    assert "t1" in veille_musique._EN_COURS
    assert await veille_musique.tour(hass, v["vu"] + veille_musique.EXPIRE_S + 1) == [PROPRE]
    assert _de(appels, "media_stop") == [{"entity_id": PROPRE}]
