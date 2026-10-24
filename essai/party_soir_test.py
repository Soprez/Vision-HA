"""2.2.1 — test réel de la Party par l'auteur (2026-10-10, fin d'après-midi).

- « Play » sur son téléphone a coupé la musique : pendant une Party, on joue toujours sur l'enceinte de la Party, et un
  ajout ne devient jamais une lecture immédiate tant qu'un titre est en cours ou en file ;
- la télé de la Party pilote la file (retirer, jouer maintenant, jouer ensuite), le volume et l'enceinte ;
- changer d'enceinte transfère la file ; la télé n'est jamais l'enceinte de sa propre Party.

    python -m pytest essai/party_soir_test.py -q -o asyncio_mode=auto

2.5.12.14.2 (revue de HACS) : Vision n'utilise plus le client interne de Music Assistant, seulement ses services Home Assistant
(get_queue, transfer_queue, play_media). Ce qui n'avait pas de service (retirer, déplacer, vider la suite, enchaînement, ajout
automatique, titres similaires, nombre de titres) répond « À faire dans Music Assistant » (erreur 400 habituelle).
"""
from __future__ import annotations

import time

from homeassistant.core import ServiceCall, SupportsResponse
from homeassistant.helpers import entity_registry as er

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    ENFANT, MA_CHAMBRE, MA_SALON, PARENT, TELE, _integrations, _monter, _play, _post, expected_lingering_tasks,
    expected_lingering_timers,
)


async def _party(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import widgets
    widgets._recentes.clear()  # la limite d'actions par minute ne passe pas d'un test à l'autre
    coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    er.async_get(hass).async_get_or_create("media_player", "music_assistant", "tele_salon", suggested_object_id="televiseur_du_salon_4")
    hass.states.async_set("media_player.televiseur_du_salon_4", "idle", {"friendly_name": "Téléviseur du salon", "active_queue": "q-tv"})
    client = await hass_client()
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200, r
    return coord, appels, files, client


A_FAIRE = "À faire dans Music Assistant"


def _file_ma(hass, suivant="Dancing Queen", total=2, image=None):
    """Simule le service `music_assistant.get_queue` (réponse {entity_id: {current_item, next_item, items}}) ; rend les
    paramètres modifiables du test."""
    q = {"suivant": suivant, "total": total, "image": image}

    async def get_queue(call: ServiceCall):
        eids = call.data.get("entity_id") or []
        eids = [eids] if isinstance(eids, str) else eids
        courant = {"queue_item_id": "q0", "media_item": {"name": "En cours", "uri": "library://track/1", "artists": [],
                                                         **({"image": q["image"]} if q["image"] else {})}}
        reponse = {"current_item": courant, "items": q["total"]}
        reponse["next_item"] = ({"queue_item_id": "q1", "media_item": {"name": q["suivant"], "uri": "library://track/145",
                                                                       "artists": [{"name": "ABBA"}]}} if q["suivant"] else None)
        return {e: reponse for e in eids}
    hass.services.async_register("music_assistant", "get_queue", get_queue, supports_response=SupportsResponse.ONLY)
    return q


def _transferts(hass):
    """Enregistre les appels à `music_assistant.transfer_queue` : (source, cible, auto_play)."""
    vus = []

    async def transfer_queue(call: ServiceCall):
        cible = call.data.get("entity_id")
        vus.append((call.data.get("source_player"), cible[0] if isinstance(cible, list) else cible, call.data.get("auto_play")))
    hass.services.async_register("music_assistant", "transfer_queue", transfer_queue)
    return vus


async def test_jamais_de_coupure(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, _f, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    # Le lecteur passe un instant en « idle » (Cast qui recharge) alors qu'un titre est en cours : on AJOUTE quand même.
    hass.states.async_set(MA_SALON, "idle", {"friendly_name": "Salon", "active_queue": "qid-salon", "media_title": "Tidal Wave"})
    s, r = await _post(client, "commande", PARENT, action="jouer", uri="library://track/145", type="track", comment="ajouter")
    assert s == 200 and _play(appels)[-1]["enqueue"] == "add"
    # Un décideur qui vise une autre enceinte pendant la Party : on reste sur celle de la Party.
    s, r = await _post(client, "commande", PARENT, action="jouer", lecteur=MA_CHAMBRE, uri="library://track/145", type="track")
    assert s == 200 and _play(appels)[-1]["entity_id"] in (MA_SALON, [MA_SALON]) and _play(appels)[-1]["enqueue"] == "add"
    assert not [c for c in _play(appels) if c["enqueue"] in ("replace", "play")]


async def test_la_tele_pilote_la_file_et_l_enceinte(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "etat", TELE)
    assert s == 200 and r["ici"] and MA_CHAMBRE in [x["entity_id"] for x in r["lecteurs"]]
    # 2.2.5 (retour d'essai) : la télé est dans la liste, sans texte faux sur Music Assistant.
    tv = [x for x in r["lecteurs"] if x["entity_id"] == "media_player.televiseur_du_salon_4"]
    assert tv and tv[0]["dispo"] and not tv[0]["raison"]
    suivants = []
    hass.services.async_register("media_player", "media_next_track", lambda call: suivants.append(call.data.get("entity_id")))
    # 2.5.12.14.2 : jouer maintenant / jouer ensuite / retirer un élément de la file n'ont pas de service : « à faire dans
    # Music Assistant » (erreur 400 habituelle), rien n'est envoyé au lecteur et aucun client interne n'est appelé.
    for action in ("jouer_element", "ensuite_element", "retirer"):
        s, r = await _post(client, "commande", TELE, action=action, element="q1")
        assert s == 400 and A_FAIRE in r["retour"], (action, r)
    assert suivants == [] and files.supprimes == []
    transferts = _transferts(hass)
    s, r = await _post(client, "commande", TELE, action="enceinte", lecteur="media_player.televiseur_du_salon_4")
    assert s == 200 and transferts == [(MA_SALON, "media_player.televiseur_du_salon_4", True)] and "Music Assistant" not in r["retour"]
    s, r = await _post(client, "commande", TELE, action="enceinte", lecteur=MA_CHAMBRE)
    assert s == 200 and transferts[-1] == ("media_player.televiseur_du_salon_4", MA_CHAMBRE, True) and "continue" in r["retour"]
    assert files.supprimes == []  # le transfert passe par le service, pas par le client
    s, r = await _post(client, "etat", TELE)
    assert r["lecteur"] == MA_CHAMBRE


async def test_un_enfant_ne_coupe_pas_la_musique(hass, hass_storage, hass_client, monkeypatch):
    _c, appels, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "commande", ENFANT, action="jouer_element", element="q1")
    assert s == 403  # jouer tout de suite : décideurs et télé de la Party
    # « Jouer ensuite » reste permis à un enfant, mais n'a plus de service : « à faire dans Music Assistant ».
    s, r = await _post(client, "commande", ENFANT, action="ensuite_element", element="q1")
    assert s == 400 and A_FAIRE in r["retour"]


async def test_file_du_titre_suivant(hass, hass_storage, hass_client, monkeypatch):
    """l'auteur : « une file infinie ». Depuis la 2.5.12.14.2, la file ne montre que le titre suivant (champ next_item du
    service get_queue) et le total vient du champ items ; plus de titres déjà joués ; « similaires » est à faire dans MA."""
    _c, appels, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    _file_ma(hass, suivant="Titre 201", total=372)
    s, r = await _post(client, "etat", PARENT)
    assert s == 200 and [x["titre"] for x in r["file"]] == ["Titre 201"] and r["file"][0]["id"] == "q1" and r["file_total"] == 372
    assert r["file_passes"] == [] and r["enchainement"] is None and r["ajout_auto"] is None and not r["similaires"]
    s, r = await _post(client, "commande", TELE, action="similaires", actif=True)
    assert s == 400 and A_FAIRE in r["retour"]
    transferts = _transferts(hass)
    s, r = await _post(client, "commande", PARENT, action="enceinte", lecteur=MA_CHAMBRE)
    assert s == 200 and transferts == [(MA_SALON, MA_CHAMBRE, True)]
    assert files.supprimes == []  # aucun réglage de la file envoyé à Music Assistant


async def test_ce_qui_est_a_faire_dans_music_assistant(hass, hass_storage, hass_client, monkeypatch):
    """2.5.12.14.2 : retirer, déplacer, mettre à la fin, vider la suite, régler l'enchaînement n'ont pas de service Home
    Assistant : un décideur ou la télé reçoit « À faire dans Music Assistant » (400), un enfant reste refusé (403), et
    Vision n'appelle rien (ni client interne, ni lecture)."""
    _c, appels, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    _file_ma(hass)
    avant = len(appels)
    for qui, action, extra in ((TELE, "retirer", {"element": "q1"}), (PARENT, "retirer", {"element": "q1"}),
                               (TELE, "deplacer", {"element": "q1", "decalage": -1}), (PARENT, "a_la_fin", {"element": "q1"}),
                               (TELE, "vider", {}), (PARENT, "enchainement", {"mode": "fondu", "duree": 6}),
                               (TELE, "enchainement", {"mode": "sans_blanc"})):
        s, r = await _post(client, "commande", qui, action=action, **extra)
        assert s == 400 and not r["ok"] and A_FAIRE in r["retour"], (action, r)
    for action, extra in (("deplacer", {"element": "q1", "decalage": 1}), ("vider", {}), ("enchainement", {"mode": "fondu", "duree": 4})):
        s, r = await _post(client, "commande", ENFANT, action=action, **extra)
        assert s == 403, (action, r)
    assert files.supprimes == [] and len(appels) == avant
    assert not [c for c in _play(appels) if c["enqueue"] in ("replace", "play")]


async def test_clin_d_oeil_quand_la_musique_s_arrete(hass, hass_storage, hass_client, monkeypatch):
    """l'auteur : file vide et musique arrêtée → un titre de chanson + émoji (traductions), le même partout, puis l'action.

    i18n : les phrases sont dans le catalogue serveur (i18n/serveur, « mus.fin_<n> », « mus.fin_action »), six langues."""
    import json
    from pathlib import Path

    from custom_components.pc_parental import clins, party
    for langue in ("fr", "en", "de", "es", "it", "nl"):
        t = json.loads((Path(clins.__file__).parent / "i18n" / "serveur" / f"{langue}.json").read_text(encoding="utf-8"))
        assert all(t.get(f"mus.fin_{k}") for k in range(1, clins.NB_CLINS + 1)) and t["mus.fin_action"]
        assert all(len(t[f"mus.fin_{k}"]) <= 45 for k in range(1, clins.NB_CLINS + 1))  # des titres courts, pas de paroles
    _c, _a, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    ma = _file_ma(hass)
    s, r = await _post(client, "etat", TELE)
    assert r["fin_clin"] == -1  # il reste un titre dans la file
    ma["suivant"] = None  # plus rien après le titre en cours, et le lecteur s'arrête
    hass.states.async_set(MA_SALON, "idle", {"friendly_name": "Salon", "active_queue": "qid-salon"})
    s, r = await _post(client, "etat", TELE)
    n = r["fin_clin"]
    assert 0 <= n < clins.NB_CLINS
    s, r2 = await _post(client, "etat", PARENT)
    assert r2["fin_clin"] == n  # le même clin d'œil sur chaque écran, sans changer à chaque relecture
    st = party.etat(hass)
    page = await (await client.get(f"/api/pc_parental/party/invite/{st['jeton']}")).text()
    clin, action = clins.phrase(hass, n)
    import html
    assert html.escape(clin) in page and html.escape(action) in page
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon", "media_title": "X"})
    s, r = await _post(client, "etat", TELE)
    assert r["fin_clin"] == -1


async def test_toutes_les_enceintes_et_televiseurs(hass, hass_storage, hass_client, monkeypatch):
    """2.2.2 (l'auteur : « je n'ai pas toutes les télés ») : rien n'est caché ; indisponible ou hors Music Assistant = grisé
    avec la raison ; une aide dit comment en ajouter."""
    from homeassistant.helpers import entity_registry as er
    _c, _a, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    er.async_get(hass).async_get_or_create("media_player", "music_assistant", "cle_mi", suggested_object_id="cle_tv_mi_2")
    hass.states.async_set("media_player.cle_tv_mi_2", "unavailable", {"friendly_name": "Clé TV Mi"})
    hass.states.async_set("media_player.tele_du_bureau", "off", {"friendly_name": "Télé du bureau"})  # Cast seul
    s, r = await _post(client, "etat", PARENT)
    par_id = {x["entity_id"]: x for x in r["lecteurs"]}
    assert par_id["media_player.cle_tv_mi_2"]["dispo"] is False and "Hors ligne" in par_id["media_player.cle_tv_mi_2"]["raison"]
    assert "media_player.tele_du_bureau" not in par_id  # 2.2.5 : seulement les lecteurs Music Assistant
    assert par_id["media_player.cle_tv_mi_2"]["raison"] == "Hors ligne"
    assert par_id[MA_CHAMBRE]["dispo"] and par_id["media_player.televiseur_du_salon_4"]["dispo"]
    assert "Options → Musique" in r["aide_lecteurs"]
    dispos = [x["dispo"] for x in r["lecteurs"]]
    assert dispos == sorted(dispos, reverse=True)  # d'abord ce qui marche
    s, r = await _post(client, "commande", PARENT, action="enceinte", lecteur="media_player.cle_tv_mi_2")
    assert s == 400 and "hors ligne" in r["retour"]
    s, r = await _post(client, "commande", PARENT, action="enceinte", lecteur="media_player.tele_du_bureau")
    assert s == 400 and "pas reliée à Music Assistant" in r["retour"]
    # Les options (Vision → Musique) : seulement la télé du salon proposée, hors ligne masqués.
    coord = _c
    coord.store.musique = {"enceintes": ["media_player.televiseur_du_salon_4"], "masquer_hors_ligne": True}
    s, r = await _post(client, "etat", PARENT)
    assert [x["entity_id"] for x in r["lecteurs"]] == ["media_player.televiseur_du_salon_4"]
    s, r = await _post(client, "commande", PARENT, action="enceinte", lecteur=MA_CHAMBRE)
    assert s == 400 and "pas proposée" in r["retour"]


async def test_la_musique_continue_apres_un_redemarrage(hass, hass_storage, hass_client, monkeypatch):
    """2.2.3 (retour d'essai) : soirée oubliée (redémarrage de HA) mais Music Assistant joue encore : l'état montre ce qui joue, et
    la télé relance la soirée sur CE lecteur ; quitter ou arrêter ne coupe jamais le son."""
    from homeassistant.helpers import entity_registry as er

    from custom_components.pc_parental import party
    coord, appels, files = await _monter(hass, hass_storage, monkeypatch)
    er.async_get(hass).async_get_or_create("media_player", "music_assistant", "tele_salon", suggested_object_id="televiseur_du_salon_4")
    hass.states.async_set("media_player.televiseur_du_salon_4", "playing", {"friendly_name": "Téléviseur du salon", "active_queue": "q-tv", "media_title": "Tidal Wave"})
    coupes = []
    for svc in ("media_pause", "media_stop", "clear_playlist", "turn_off"):
        hass.services.async_register("media_player", svc, lambda call, svc=svc: coupes.append(svc))
    client = await hass_client()
    s, r = await _post(client, "etat", TELE)
    assert s == 200 and not r["actif"] and r["lecteur"] == "media_player.televiseur_du_salon_4" and r["maintenant"]["titre"] == "Tidal Wave"
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    assert s == 200 and party.etat(hass)["lecteur"] == "media_player.televiseur_du_salon_4"
    s, r = await _post(client, "commande", PARENT, action="arreter")
    assert s == 200
    # Ni pause, ni arrêt, ni vidage de la file : la musique continue.
    await hass.async_block_till_done()
    assert coupes == [] and not _play(appels)


async def test_options_musique_enceintes(hass, hass_storage, hass_client, monkeypatch):
    """2.2.5 (retour d'essai) : Vision > Options > Musique — tous cochés par défaut ; n'en garder qu'un ; tout recocher = « tous »."""
    coord, _a, _f = await _monter(hass, hass_storage, monkeypatch)
    entree = hass.config_entries.async_entries("pc_parental")[0]
    r = await hass.config_entries.options.async_init(entree.entry_id)
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "autres"})
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "musique"})
    assert r["step_id"] == "musique"
    # (arret_auto_perso donné : son défaut 0 est sous le minimum de 5, voir le rapport)
    defaut = r["data_schema"]({"arret_auto_perso": 5})
    assert set(defaut["enceintes"]) == {MA_SALON, MA_CHAMBRE} and defaut["masquer_hors_ligne"] is False
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"enceintes": [MA_SALON], "masquer_hors_ligne": True, "arret_auto": "0", "arret_auto_perso": 5})
    assert r["type"] == "create_entry"
    assert coord.store.musique == {"enceintes": [MA_SALON], "masquer_hors_ligne": True, "arret_auto_min": 0}  # 2.5.12.14.2 : arrêt automatique désactivé par défaut
    r = await hass.config_entries.options.async_init(entree.entry_id)
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "autres"})
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "musique"})
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"enceintes": [MA_SALON, MA_CHAMBRE], "masquer_hors_ligne": False,
                                                           "arret_auto": "0", "arret_auto_perso": 5})
    assert coord.store.musique["enceintes"] is None


async def test_lecture_pause_voulue_et_flux(hass, hass_storage, hass_client, monkeypatch):
    """2.2.5 (l'auteur : « play / pause en retard, va-et-vient ») : l'appli dit l'état voulu (jamais une bascule à l'aveugle) ;
    l'état porte son horodatage ; un flux prévient l'appli dès que le lecteur change."""
    import asyncio
    import json as _json

    _c, appels, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    vus = []
    for svc in ("media_pause", "media_play", "media_play_pause"):
        hass.services.async_register("media_player", svc, lambda call, svc=svc: vus.append(svc))
    s, r = await _post(client, "commande", TELE, action="lecture", voulu="pause")
    s2, r2 = await _post(client, "commande", TELE, action="lecture", voulu="lecture")
    await hass.async_block_till_done()
    assert s == 200 and s2 == 200 and vus == ["media_pause", "media_play"]
    s, r = await _post(client, "etat", TELE)
    assert isinstance(r["maintenant"]["maj"], float)
    # Le flux : un changement d'état du lecteur de la soirée arrive tout de suite.
    rep = await client.post("/api/pc_parental/party/flux", json=TELE)
    assert rep.status == 200 and rep.headers["Content-Type"].startswith("text/event-stream")
    assert (await rep.content.readline()).startswith(b"retry:")
    await rep.content.readline()
    hass.states.async_set(MA_SALON, "paused", {"friendly_name": "Salon", "active_queue": "qid-salon"})
    ligne = await asyncio.wait_for(rep.content.readline(), 5)
    assert ligne.startswith(b"data: ") and _json.loads(ligne[6:])["etat"] == "paused"
    rep.close()


async def test_reprise_apres_coupure_du_lecteur_vision(hass, hass_storage, hass_client, monkeypatch):
    """Banc Musique (2026-10-12) : le lecteur « · Vision » de la Party coupé en pleine lecture (playing → unavailable)
    puis revenu « off » est relancé (allumé puis lecture) ; jamais une pause, jamais au-delà de 2 min, jamais hors Party."""
    from custom_components.pc_parental import party
    _c, appels, _files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    v = "media_player.salon_vision"
    hass.states.async_set(v, "playing", {"friendly_name": "Salon · Vision", "active_queue": "qv"})
    party.etat(hass).update({"actif": True, "lecteur": v})
    assert not party.reprise_apres_coupure(hass, v, "playing", "unavailable", 1000.0)
    assert party.reprise_apres_coupure(hass, v, "unavailable", "off", 1030.0)
    # Une pause avant la coupure : rien.
    assert not party.reprise_apres_coupure(hass, v, "paused", "unavailable", 2000.0)
    assert not party.reprise_apres_coupure(hass, v, "unavailable", "off", 2010.0)
    # Trop tard (plus de 2 min) : rien.
    party.reprise_apres_coupure(hass, v, "playing", "unavailable", 3000.0)
    assert not party.reprise_apres_coupure(hass, v, "unavailable", "off", 3000.0 + party.REPRISE_MAX_S + 1)
    # Un lecteur qui n'est pas « · Vision », ou hors Party : rien.
    assert not party.reprise_apres_coupure(hass, "media_player.salon_ma", "playing", "unavailable", 4000.0)
    party.etat(hass)["actif"] = False
    party.reprise_apres_coupure(hass, v, "playing", "unavailable", 5000.0)
    assert not party.reprise_apres_coupure(hass, v, "unavailable", "off", 5010.0)
    # La relance : allumer puis lire (le lecteur est revenu « off »).
    party.etat(hass)["actif"] = True
    monkeypatch.setattr(party, "REPRISE_DELAI_S", 0)
    hass.states.async_set(v, "off", {"friendly_name": "Salon · Vision", "active_queue": "qv"})
    vus = []

    async def service(_hass, domaine, nom, eid, donnees=None):
        vus.append((nom, eid))
    monkeypatch.setattr(party, "_service", service)
    await party.reprendre_apres_coupure(hass, v)
    assert vus == [("turn_on", v), ("media_play", v)]


async def test_reprise_apres_coupure_par_l_ecouteur(hass, hass_storage, hass_client, monkeypatch):
    """Le même chemin que en essai : les changements d'état passent par l'écouteur de l'intégration."""
    from custom_components.pc_parental import party
    _c, _appels, _files, _client = await _party(hass, hass_storage, hass_client, monkeypatch)
    monkeypatch.setattr(party, "REPRISE_DELAI_S", 0)
    vus = []

    async def service(call):
        vus.append((call.service, call.data.get("entity_id")))
    for nom in ("turn_on", "media_play"):
        hass.services.async_register("media_player", nom, service)
    v = "media_player.salon_vision"
    hass.states.async_set(v, "playing", {"friendly_name": "Salon · Vision", "active_queue": "qv"})
    party.etat(hass).update({"actif": True, "lecteur": v})
    hass.states.async_set(v, "unavailable", {"friendly_name": "Salon · Vision"})
    hass.states.async_set(v, "off", {"friendly_name": "Salon · Vision", "active_queue": "qv"})
    await hass.async_block_till_done()
    assert ("turn_on", [v]) in vus or ("turn_on", v) in vus
    assert any(n == "media_play" for n, _e in vus)


async def test_lecteur_vision_remplace_le_cast_de_la_tele(hass, hass_storage, hass_client, monkeypatch):
    """2.2.9 (retour d'essai) : le lecteur « Téléviseur du salon · Vision » (Vision joue le son) remplace le lecteur Cast de la même
    télé dans la liste (masqué), hérite de sa case dans les options, et devient l'enceinte par défaut de cette télé."""
    from homeassistant.helpers import entity_registry as er

    from custom_components.pc_parental import party
    coord, _a, files = await _monter(hass, hass_storage, monkeypatch)
    reg = er.async_get(hass)
    reg.async_get_or_create("media_player", "music_assistant", "tele_salon", suggested_object_id="televiseur_du_salon_4")
    reg.async_get_or_create("media_player", "music_assistant", "vision_salon", suggested_object_id="televiseur_du_salon_vision")
    hass.states.async_set("media_player.televiseur_du_salon_4", "idle", {"friendly_name": "Téléviseur du salon", "active_queue": "q-tv"})
    hass.states.async_set("media_player.televiseur_du_salon_vision", "idle", {"friendly_name": "Téléviseur du salon · Vision", "active_queue": "q-vision"})
    coord.store.musique = {"enceintes": ["media_player.televiseur_du_salon_4"], "masquer_hors_ligne": False}  # comme en essai
    client = await hass_client()
    s, r = await _post(client, "etat", PARENT)
    ids = [x["entity_id"] for x in r["lecteurs"]]
    assert ids == ["media_player.televiseur_du_salon_vision"]  # le Cast de la télé n'apparaît plus
    tele = coord.store.pcs["t1"]
    assert party.lecteur_par_defaut(hass, coord.store, tele) == "media_player.televiseur_du_salon_vision"


async def test_playlist_sans_confirmation_et_journal(hass, hass_storage, hass_client, monkeypatch):
    """2.2.11 (l'auteur : « crée un système de log ») : chaque commande va au journal Musique (événement, capteur, options) ;
    une perte de l'écran sur la télé y est notée avec l'appli qui l'a pris. 2.5.12.14.2 : le nombre de titres d'une playlist
    n'a plus de service (inconnu), donc plus de confirmation « 500 titres » : l'ajout part directement."""
    from custom_components.pc_parental import journal_musique, musique
    _c, appels, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    vus = []
    hass.bus.async_listen(journal_musique.EVENEMENT, lambda e: vus.append(dict(e.data)))
    assert await musique.nb_titres(hass, "builtin://playlist/random", "playlist") is None
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon", "media_title": "X"})
    s, r = await _post(client, "commande", PARENT, action="jouer", uri="builtin://playlist/random", type="playlist", comment="ajouter")
    assert s == 200 and not r.get("confirmer") and _play(appels)[-1]["enqueue"] == "add"
    s, r = await _post(client, "commande", PARENT, action="jouer", uri="library://playlist/10", type="playlist", comment="ajouter")
    assert s == 200
    await hass.async_block_till_done()
    assert [v["resultat"] for v in vus[:2]] == ["ok", "ok"]
    # 2.2.13 + i18n : le résultat est un code (journal_musique.RESULTATS), dit dans la langue du lecteur du journal.
    assert vus[0]["mode"] == "ajouter" and vus[0]["uri"] == "builtin://playlist/random" and vus[0]["appareil"] == "PIXEL-NOLAN"
    # La télé note la perte de l'écran (sans droit Musique particulier).
    s, r = await _post(client, "commande", TELE, action="premier_plan_perdu", paquet="com.google.android.apps.mediashell")
    assert s == 200
    await hass.async_block_till_done()
    assert vus[-1]["action"] == "premier_plan_perdu" and "mediashell" in vus[-1]["paquet"]
    assert "écran pris par com.google.android.apps.mediashell" in journal_musique.phrase(vus[-1])
    etat_capteur = hass.states.get("sensor.vision_musique_derniere_commande")
    assert etat_capteur is not None and "Vision a perdu l'écran" in etat_capteur.state
    # L'écran « Journal Musique » des options.
    entree = hass.config_entries.async_entries("pc_parental")[0]
    f = await hass.config_entries.options.async_init(entree.entry_id)
    f = await hass.config_entries.options.async_configure(f["flow_id"], {"next_step_id": "autres"})
    f = await hass.config_entries.options.async_configure(f["flow_id"], {"next_step_id": "journal_musique"})
    assert f["step_id"] == "journal_musique" and "mediashell" in f["description_placeholders"]["journal"]
    f = await hass.config_entries.options.async_configure(f["flow_id"], {"jours": "1"})
    assert journal_musique.jours(hass) == 1


async def test_playlists_avec_etiquette_sans_nombre(hass, hass_storage, hass_client, monkeypatch):
    """2.2.11 : celles que fabrique Music Assistant ont une étiquette. 2.5.12.14.2 : le nombre de titres n'a plus de service
    (inconnu) : la liste n'en annonce aucun."""
    _c, appels, files, client = await _party(hass, hass_storage, hass_client, monkeypatch)

    async def library(call: ServiceCall):
        return {"items": [{"media_type": "playlist", "uri": "library://playlist/3", "name": "500 Random tracks",
                           "provider_mappings": [{"provider_domain": "builtin", "item_id": "random_tracks"}]},
                          {"media_type": "playlist", "uri": "library://playlist/17", "name": "Disney classique", "provider_mappings": []}]}
    hass.services.async_register("music_assistant", "get_library", library, supports_response=SupportsResponse.ONLY)
    s, r = await _post(client, "musique", PARENT, quoi="playlist")
    par_nom = {x["nom"]: x for x in r["items"]}
    assert par_nom["500 Random tracks"]["auto"] and not par_nom["Disney classique"]["auto"]
    assert not par_nom["500 Random tracks"].get("nb") and not par_nom["Disney classique"].get("nb")


async def test_tele_garde_son_propre_lecteur_vision(hass, hass_storage, hass_client, monkeypatch):
    """2.2.13 (l'auteur : « je clique sur Disney classique, il ne se passe rien », télé du bureau) : une télé peut toujours
    jouer sur SON lecteur « · Vision » même s'il n'est pas coché dans les options ; les autres appareils ne le voient pas."""
    from homeassistant.helpers import entity_registry as er

    from custom_components.pc_parental import party
    coord, _a, files = await _monter(hass, hass_storage, monkeypatch)
    reg = er.async_get(hass)
    reg.async_get_or_create("media_player", "music_assistant", "vision_salon", suggested_object_id="televiseur_du_salon_vision")
    hass.states.async_set("media_player.televiseur_du_salon_vision", "idle", {"friendly_name": "Téléviseur du salon · Vision", "active_queue": "q-vision"})
    coord.store.musique = {"enceintes": [MA_CHAMBRE], "masquer_hors_ligne": False}  # la télé n'est pas cochée
    tele = coord.store.pcs["t1"]
    ids_tele = [x["entity_id"] for x in party.lecteurs_pour(hass, coord.store, tele)]
    assert "media_player.televiseur_du_salon_vision" in ids_tele
    assert "media_player.televiseur_du_salon_vision" not in [x["entity_id"] for x in party.lecteurs_pour(hass, coord.store, coord.store.pcs["p1"])]
    party.etat(hass).update({"lecteur": ""})
    assert party.lecteur_par_defaut(hass, coord.store, tele) == "media_player.televiseur_du_salon_vision"
    # Sans aucun lecteur pour la télé : un message clair (où cocher une enceinte).
    hass.states.async_set("media_player.televiseur_du_salon_vision", "unavailable", {"friendly_name": "Téléviseur du salon · Vision"})
    coord.store.musique = {"enceintes": [], "masquer_hors_ligne": False}
    client = await hass_client()
    s, r = await _post(client, "commande", TELE, action="lancer_ici")
    assert s != 200 and "Vision → Options → Musique" in r["retour"]


async def test_lecteurs_vision_tenus_a_100_pour_cent(hass, hass_storage, hass_client, monkeypatch):
    """2.5.12.14.2 : plus de codec ni de renommage ni de client. Les lecteurs « · Vision » sont repérés par leur nom dans Home
    Assistant et leur volume est tenu à 100 % (media_player.volume_set) ; les autres lecteurs ne sont jamais touchés."""
    from custom_components.pc_parental import musique
    coord, _a, files = await _monter(hass, hass_storage, monkeypatch)
    musique._LECTEURS_VISION.clear()
    reg = er.async_get(hass)
    reg.async_get_or_create("media_player", "music_assistant", "02:00:00:00:00:01", suggested_object_id="tele_du_bureau_vision")
    hass.states.async_set("media_player.tele_du_bureau_vision", "idle", {"friendly_name": "Télé du bureau · Vision", "volume_level": 0.4})
    volumes = []
    hass.services.async_register("media_player", "volume_set", lambda call: volumes.append((call.data["entity_id"], call.data["volume_level"])))
    faits = await musique.regler_lecteurs_vision(hass)
    assert faits == ["media_player.tele_du_bureau_vision"]  # pas « Salon »
    assert volumes == [("media_player.tele_du_bureau_vision", 1.0)]
    assert musique.est_lecteur_vision(hass, "media_player.tele_du_bureau_vision") and not musique.est_lecteur_vision(hass, MA_SALON)
    assert files.supprimes == []  # ni codec, ni renommage, ni client interne
    # Déjà à 100 % : rien à remettre.
    hass.states.async_set("media_player.tele_du_bureau_vision", "idle", {"friendly_name": "Télé du bureau · Vision", "volume_level": 1.0})
    await musique.regler_lecteurs_vision(hass)
    assert len(volumes) == 1


async def test_titre_en_cours_a_toujours_sa_pochette(hass, hass_storage, hass_client, monkeypatch, aioclient_mock):
    """2.2.13 (l'auteur : « pochette vide » pour le titre en cours, mini-lecteur et file de la télé) : si le lecteur n'a pas
    d'image (lecteur « · Vision »), celle du titre en cours (current_item du service get_queue) ; lue par Home Assistant.
    Échoue si le titre en cours reste sans image alors que la file (ou le lecteur) en a une."""
    from custom_components.pc_parental import musique, party, party_invites
    coord, _a, files = await _monter(hass, hass_storage, monkeypatch)
    party_invites._images.clear()
    musique._IMAGES_EN_COURS.clear()
    ma = _file_ma(hass, image="https://img.example/en-cours.jpg")
    aioclient_mock.get("https://img.example/en-cours.jpg", content=b"JPEG-EN-COURS", headers={"Content-Type": "image/jpeg"})
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon", "media_title": "En cours"})
    assert party.maintenant(hass, MA_SALON)["pochette"] == ""  # la file n'a pas encore été lue
    await musique.file(hass, MA_SALON)
    m = party.maintenant(hass, MA_SALON)
    assert m["pochette"], "le titre en cours n'a pas d'empreinte de pochette alors que la file a une image"
    image = await party.pochette(hass, MA_SALON)
    assert image is not None and image[0] == b"JPEG-EN-COURS" and image[1] == "image/jpeg"
    # Le lecteur a sa propre image : elle passe d'abord (empreinte de son adresse).
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon", "media_title": "En cours",
                                                "entity_picture": "/api/media_player_proxy/media_player.salon_2?token=X"})
    assert party.maintenant(hass, MA_SALON)["pochette"] != m["pochette"]
    # Ni lecteur ni file : pas d'empreinte (l'écran garde son cadre), sans erreur.
    ma["image"] = None
    await musique.file(hass, MA_SALON)
    hass.states.async_set(MA_SALON, "playing", {"friendly_name": "Salon", "active_queue": "qid-salon"})
    assert party.maintenant(hass, MA_SALON)["pochette"] == "" and await party.pochette(hass, MA_SALON) is None


async def test_mode_soiree_ouvrir_fermer(hass, hass_storage, hass_client, monkeypatch):
    """17-10 (l'auteur, point 8) : le mode soirée (invités) s'ouvre et se ferme depuis le lecteur, sur la télé et le téléphone.
    Fermé : plus de QR code, et l'adresse des invités ne donne plus accès à rien (page « fini », 404, propositions
    refusées). Rouvert : un NOUVEAU lien (l'ancien reste mort). Le choix vaut pour la Party suivante. Un appareil sans
    droit n'y touche pas."""
    from custom_components.pc_parental import party
    _c, _a, _f, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    s, r = await _post(client, "etat", TELE)
    assert r["qr"] and r["invites_ouverts"] is True
    ancien = party.etat(hass)["jeton"]
    assert (await client.get(f"/api/pc_parental/party/invite/{ancien}")).status == 200
    s, r = await _post(client, "commande", ENFANT, action="invites_ouverts", actif=False)
    assert s == 403, r
    s, r = await _post(client, "commande", TELE, action="invites_ouverts", actif=False)
    assert s == 200 and r["ok"], r
    s, r = await _post(client, "etat", TELE)
    assert r["qr"] == "" and r["invites_ouverts"] is False
    assert (await client.get(f"/api/pc_parental/party/invite/{ancien}")).status == 404
    p = await client.post(f"/api/pc_parental/party/invite/{ancien}/proposer", json={"ref": "x"})
    assert p.status in (403, 404)
    # Le téléphone d'un décideur rouvre : un nouveau lien, l'ancien reste mort.
    s, r = await _post(client, "commande", PARENT, action="invites_ouverts", actif=True)
    assert s == 200 and r["ok"], r
    s, r = await _post(client, "etat", PARENT)
    nouveau = party.etat(hass)["jeton"]
    assert r["qr"] and nouveau and nouveau != ancien and r["invites_ouverts"] is True
    assert (await client.get(f"/api/pc_parental/party/invite/{ancien}")).status == 404
    # Fermé puis Party relancée : toujours fermé.
    await _post(client, "commande", PARENT, action="invites_ouverts", actif=False)
    await _post(client, "commande", PARENT, action="arreter")
    s, r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200, r
    s, r = await _post(client, "etat", TELE)
    assert r["qr"] == "" and party.etat(hass)["jeton"] is None


async def test_pas_de_relance_si_autre_chose_joue_sur_la_tele(hass, hass_storage, hass_client, monkeypatch):
    """Incident du salon (2026-10-18) : une appli de replay jouait sur la télé de la soirée quand le lecteur « · Vision » s'est
    reconnecté (réinstallation) ; la soirée est repartie par-dessus. Désormais : la télé dit au relevé qu'autre chose
    joue chez elle (« autre_son ») et HA ne relance rien ; sans ce signal (ou s'il est périmé), la relance a lieu."""
    from custom_components.pc_parental import party
    _c, _appels, _files, client = await _party(hass, hass_storage, hass_client, monkeypatch)
    monkeypatch.setattr(party, "REPRISE_DELAI_S", 0)
    v = "media_player.salon_vision"
    party.etat(hass).update({"actif": True, "lecteur": v, "ecran": "t1"})
    vus = []

    async def service(_hass, domaine, nom, eid, donnees=None):
        vus.append((nom, eid))
    monkeypatch.setattr(party, "_service", service)
    r = await client.post("/api/pc_parental/poll", json={**TELE, "platform": "android", "type": "tv", "autre_son": True,
                                                         "focus": "fr.tf1.mytf1"})
    assert r.status == 200
    assert party.autre_son_recent("t1", time.time())
    hass.states.async_set(v, "off", {"friendly_name": "Salon · Vision", "active_queue": "qv"})
    await party.reprendre_apres_coupure(hass, v)
    assert vus == []
    # La télé ne joue plus rien : la relance est permise.
    r = await client.post("/api/pc_parental/poll", json={**TELE, "platform": "android", "type": "tv", "autre_son": False})
    assert r.status == 200
    await party.reprendre_apres_coupure(hass, v)
    assert vus == [("turn_on", v), ("media_play", v)]
    # Un signal trop ancien ne bloque plus.
    party.noter_autre_son("t1", True, time.time() - party.AUTRE_SON_MAX_S - 5)
    assert not party.autre_son_recent("t1", time.time())
