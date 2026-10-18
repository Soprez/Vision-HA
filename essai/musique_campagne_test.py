"""Campagne de tests Musique (l'auteur, 15-10) — côté Home Assistant, avec le faux Music Assistant du banc.

Chaque essai est un scénario de la campagne (file, navigation, source, enceinte, recherche, robustesse, droits,
volume). Rien ne joue nulle part : les services media_player et Music Assistant sont simulés et notés.

    python -m pytest essai/musique_campagne_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import asyncio
from types import SimpleNamespace

from homeassistant.core import ServiceCall, SupportsResponse
from homeassistant.helpers import entity_registry as er

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, MA_CHAMBRE, MA_SALON, PARENT, TELE, _integrations, _monter, _play, _post, expected_lingering_tasks,
    expected_lingering_timers,
)


async def _soiree(hass, hass_storage, hass_client, monkeypatch, *, jouer=True):
    """Une soirée lancée sur le salon (faux MA), les services media_player notés, la limite de débit remise à zéro."""
    from custom_components.pc_parental import musique, widgets
    widgets._recentes.clear()
    coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    er.async_get(hass).async_get_or_create("media_player", "music_assistant", "tele_salon", suggested_object_id="televiseur_du_salon_4")
    hass.states.async_set("media_player.televiseur_du_salon_4", "idle", {"friendly_name": "Téléviseur du salon", "active_queue": "q-tv"})
    media = []
    for svc in ("media_play", "media_pause", "media_play_pause", "media_next_track", "media_previous_track", "volume_set", "media_seek"):
        async def h(call: ServiceCall, svc=svc):
            media.append((svc, dict(call.data)))
        hass.services.async_register("media_player", svc, h)
    # mass.music pour le nombre de titres d'un album ou d'une playlist (la confirmation au-delà de 50).
    async def pistes(*_a, **_k):
        return [object()] * 3
    ma = musique.entree(hass)
    ma.runtime_data.mass.music = SimpleNamespace(get_album_tracks=pistes, get_playlist_tracks=pistes)
    client = await hass_client()
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200, r
    if jouer:
        hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon", "media_duration": 200,
                                                     "media_position": 50, "volume_level": 0.4})
    return coord, appels, files, client, media


# ------------------------------------------------------------------ file d'attente

async def test_ajouter_titre_album_playlist_radio_artiste(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    for uri, genre in (("library://track/145", "track"), ("library://album/3", "album"), ("library://playlist/17", "playlist"),
                       ("tunein://radio/fip", "radio"), ("library://artist/9", "artist")):
        s, r = await _post(client, "commande", PARENT, action="jouer", uri=uri, type=genre)
        assert s == 200, (genre, r)
        assert _play(appels)[-1]["media_type"] == genre and _play(appels)[-1]["enqueue"] == "add", genre


async def test_ensuite_ou_a_la_fin(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    await _post(client, "commande", PARENT, action="jouer", uri="library://track/145", type="track", comment="ensuite")
    assert _play(appels)[-1]["enqueue"] == "next"
    await _post(client, "commande", PARENT, action="jouer", uri="library://track/145", type="track", comment="ajouter")
    assert _play(appels)[-1]["enqueue"] == "add"


async def test_ajout_en_double(hass, hass_storage, hass_client, monkeypatch):
    """Le même titre ajouté deux fois : constat du comportement (aucun dédoublonnage aujourd'hui)."""
    _c, appels, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    for _ in range(2):
        s, _r = await _post(client, "commande", PARENT, action="jouer", uri="library://track/145", type="track")
        assert s == 200
    assert len([c for c in _play(appels) if c["media_id"] == "library://track/145"]) == 2


async def test_retirer_suivant_dernier_en_cours_inconnu(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, files, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    ids = [x["queue_item_id"] for x in files.items]
    # Le dernier (q1) : retiré.
    s, r = await _post(client, "commande", PARENT, action="retirer", element=ids[-1])
    assert s == 200, r
    # Un identifiant inconnu : une phrase, jamais une erreur 500.
    s, r = await _post(client, "commande", PARENT, action="retirer", element="inconnu")
    assert s in (200, 400) and "retour" in r, r
    # Le titre en cours : refusé ou sans effet, jamais une erreur 500.
    s, r = await _post(client, "commande", PARENT, action="retirer", element=ids[0])
    assert s in (200, 400) and "retour" in r, r
    # Sans élément : une phrase.
    s, r = await _post(client, "commande", PARENT, action="retirer")
    assert s == 400 and r.get("retour"), r


async def test_deplacer_zero_et_a_la_fin_inconnu(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", PARENT, action="deplacer", element="q1", decalage=0)
    assert s == 400 and r.get("retour"), r
    s, r = await _post(client, "commande", PARENT, action="a_la_fin", element="nexistepas")
    assert s == 400 and r.get("retour"), r


async def test_file_vide_suivant(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, files, client, media = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    files.items.clear()
    s, r = await _post(client, "commande", PARENT, action="suivant")
    assert s == 200, r
    assert media[-1][0] == "media_next_track"


# ------------------------------------------------------------------ navigation

async def test_lecture_pause_suivant_precedent(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, _f, client, media = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    for action, extra, attendu in (("lecture", {"voulu": "pause"}, "media_pause"), ("lecture", {"voulu": "lecture"}, "media_play"),
                                   ("lecture", {}, "media_play_pause"), ("suivant", {}, "media_next_track"),
                                   ("precedent", {}, "media_previous_track")):
        s, r = await _post(client, "commande", PARENT, action=action, **extra)
        assert s == 200, (action, r)
        assert media[-1][0] == attendu, (action, media[-1])


async def test_appuis_rapides_limite_de_debit(hass, hass_storage, hass_client, monkeypatch):
    """31 ordres dans la minute : le 31e est refusé par une phrase (429), jamais une erreur."""
    _c, _a, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    codes = []
    for _ in range(31):
        s, r = await _post(client, "commande", PARENT, action="suivant")
        codes.append(s)
    assert codes[:29].count(200) == 29 and codes[-1] == 429, codes
    assert r.get("retour")


async def test_saut_borne_et_radio(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, _f, client, media = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", PARENT, action="position", position=500)
    assert s == 200 and media[-1][0] == "media_seek" and media[-1][1]["seek_position"] == 199, media[-1]
    # Une radio (sans durée) : pas d'erreur 500.
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon"})
    s, r = await _post(client, "commande", PARENT, action="position", position=30)
    assert s in (200, 400), r


# ------------------------------------------------------------------ source et enceinte

async def test_changer_de_source_pendant_la_lecture(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    for uri, genre in (("library://playlist/17", "playlist"), ("tunein://radio/fip", "radio"), ("library://album/3", "album")):
        s, r = await _post(client, "commande", PARENT, action="jouer", uri=uri, type=genre, comment="remplacer")
        assert s == 200, (genre, r)
        assert _play(appels)[-1]["enqueue"] == "replace" and _play(appels)[-1]["media_type"] == genre


async def test_changer_d_enceinte_en_pleine_lecture(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, files, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", PARENT, action="enceinte", lecteur=MA_CHAMBRE)
    assert s == 200, r
    assert ("transfert", "qid-salon", "qid-chambre") in files.supprimes or any(x[0] == "transfert" for x in files.supprimes), files.supprimes
    s, r = await _post(client, "etat", PARENT)
    assert r["lecteur"] == MA_CHAMBRE


async def test_transfert_impossible(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, files, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)

    async def casse(*_a, **_k):
        raise RuntimeError("MA refuse")
    files.transfer = casse
    s, r = await _post(client, "commande", PARENT, action="enceinte", lecteur=MA_CHAMBRE)
    assert s in (200, 400) and r.get("retour"), r


# ------------------------------------------------------------------ recherche

async def test_recherche_une_lettre_et_aucun_resultat(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    n = len([a for a in appels if a[0] == "search"])
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="a")
    assert s == 200 and r["resultats"] == {} and len([a for a in appels if a[0] == "search"]) == n

    async def vide(call: ServiceCall):
        return {"tracks": [], "playlists": [], "albums": [], "artists": [], "radio": []}
    hass.services.async_register("music_assistant", "search", vide, supports_response=SupportsResponse.ONLY)
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="zzzz introuvable")
    assert s == 200 and r["resultats"] == {}, r


async def test_favoris_lus_dans_music_assistant(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "musique", PARENT, quoi="favoris")
    assert s == 200, r
    lib = [c for n, c in appels if n == "get_library"]
    assert lib and all(c.get("favorite") is True for c in lib), lib


# ------------------------------------------------------------------ robustesse

async def test_music_assistant_absent_ou_en_panne(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import musique
    _c, _a, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)

    async def panne(call: ServiceCall):
        raise RuntimeError("MA ne répond plus")
    hass.services.async_register("music_assistant", "play_media", panne)
    s, r = await _post(client, "commande", PARENT, action="jouer", uri="library://track/145", type="track")
    assert s == 400 and r.get("retour"), r
    hass.services.async_register("music_assistant", "search", panne, supports_response=SupportsResponse.ONLY)
    s, r = await _post(client, "musique", PARENT, quoi="chercher", texte="queen ma")
    assert s == 503 and r.get("retour"), r
    monkeypatch.setattr(musique, "entree", lambda _h: None)
    s, r = await _post(client, "commande", PARENT, action="jouer", uri="library://track/145", type="track")
    assert s == 400 and r.get("retour"), r


async def test_enceinte_muette_dit_une_phrase(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    hass.services.async_remove("media_player", "media_next_track")
    s, r = await _post(client, "commande", PARENT, action="suivant")
    assert s == 502 and r.get("retour"), r


async def test_deux_appareils_en_meme_temps(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, _f, client, media = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    rs = await asyncio.gather(_post(client, "commande", PARENT, action="lecture", voulu="pause"),
                              _post(client, "commande", TELE, action="lecture", voulu="lecture"),
                              _post(client, "commande", PARENT, action="suivant"),
                              _post(client, "commande", TELE, action="volume", volume=0.5))
    assert all(s == 200 for s, _r in rs), rs
    assert {m[0] for m in media} >= {"media_pause", "media_play", "media_next_track"}


# ------------------------------------------------------------------ droits

async def test_droits_participant_et_decideur(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, _f, client, _m = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    # Un participant (Demandeur) pendant la soirée : ajouter oui ; lancer, arrêter, enceinte non.
    s, r = await _post(client, "commande", ENFANT, action="jouer", uri="library://track/145", type="track")
    assert s == 200, r
    for action, extra in (("arreter", {}), ("enceinte", {"lecteur": MA_CHAMBRE}), ("similaires", {"actif": True}),
                          ("invites", {"mode": "direct"}), ("nouveau_lien", {})):
        s, r = await _post(client, "commande", ENFANT, action=action, **extra)
        assert s == 403, (action, s, r)
    # Constat à faire trancher par l'auteur : un participant peut remplacer toute la file de la soirée (aucun garde-fou).
    s, r = await _post(client, "commande", ENFANT, action="jouer", uri="library://playlist/17", type="playlist", comment="remplacer")
    assert s == 200 and _play(appels)[-1]["enqueue"] == "replace", r


async def test_invites_proposer_accepter_refuser(hass, hass_storage, hass_client_no_auth, monkeypatch):
    from custom_components.pc_parental import widgets
    widgets._recentes.clear()
    _c, appels, _f = await _monter(hass, hass_storage, monkeypatch)
    client = await hass_client_no_auth()
    await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    await _post(client, "commande", PARENT, action="invites", mode="validation")
    s, r = await _post(client, "etat", PARENT)
    chemin = r["qr"].split("exemple.org", 1)[1]
    j = await (await client.post(chemin + "/chercher", json={"texte": "queen"})).json()
    r = await client.post(chemin + "/proposer", json={"ref": j["titres"][0]["ref"], "appareil": "inv1"})
    assert r.status == 200, await r.text()
    s, r = await _post(client, "etat", PARENT)
    props = r.get("propositions") or []
    assert len(props) >= 1, r
    s, r = await _post(client, "commande", PARENT, action="refuser", proposition=props[0]["id"])
    assert s == 200, r
    s, r = await _post(client, "commande", PARENT, action="refuser", proposition=props[0]["id"])
    assert s == 400 and r.get("retour"), r  # déjà traitée
    assert len(_play(appels)) == 0  # refusée : rien n'a été ajouté


# ------------------------------------------------------------------ volume

async def test_volume_limites(hass, hass_storage, hass_client, monkeypatch):
    _c, _a, _f, client, media = await _soiree(hass, hass_storage, hass_client, monkeypatch)
    for v in (0, 1, 0.55):
        s, r = await _post(client, "commande", PARENT, action="volume", volume=v)
        assert s == 200, (v, r)
    assert [m[1]["volume_level"] for m in media if m[0] == "volume_set"][-3:] == [0, 1, 0.55]
    for v in (True, -0.1, 1.01, "fort", None):
        s, r = await _post(client, "commande", PARENT, action="volume", volume=v)
        assert s == 400 and r.get("retour"), (v, s, r)
