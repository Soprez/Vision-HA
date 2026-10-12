"""2.4.0 (l'auteur, 2026-10-12) — arrêt automatique de la lecture après inactivité, et écran éteint signalé par l'appli.

    python -m pytest essai/musique_arret_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from homeassistant.core import ServiceCall

from party_musique_test import (  # noqa: F401  (fixtures du banc)
    MA_CHAMBRE, MA_SALON, PARENT, TELE2, _integrations, _monter, _post, expected_lingering_tasks, expected_lingering_timers,
)


def _services(hass):
    appels = []

    def faire(nom):
        async def f(call: ServiceCall):
            e = call.data.get("entity_id")
            appels.append((nom, e if isinstance(e, str) else e[0]))
        return f
    for s in ("media_pause", "media_stop"):
        hass.services.async_register("media_player", s, faire(s))
    return appels


def _jouer(hass, eid, nom):
    hass.states.async_set(eid, "playing", {"friendly_name": nom, "volume_level": 1.0, "active_queue": "qid"})


async def test_avertit_puis_arrete(hass, hass_storage, monkeypatch):
    coord, _a, _f = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique_arret
    musique_arret._ACTIVITE.clear(); musique_arret._AVERTIS.clear()
    appels = _services(hass)
    envois = []

    async def envoyer(_h, _c, donnees, expediteur):
        envois.append((donnees, expediteur))
        return {"envoi": "x", "messages": []}
    from custom_components.pc_parental import messages_v2
    monkeypatch.setattr(messages_v2, "envoyer", envoyer)
    assert musique_arret.delai_min(coord.store) == musique_arret.DEFAUT_MIN  # 2 h par défaut
    coord.store.musique["arret_auto_min"] = 30
    _jouer(hass, MA_SALON, "Télé des parents · Vision")
    t0 = time.time()
    await musique_arret.tour(hass, coord, t0)  # premier passage : le délai part de maintenant
    assert envois == [] and appels == []
    await musique_arret.tour(hass, coord, t0 + 30 * 60 - 50)  # 1 min avant : avertissement sur la Télé des parents
    assert len(envois) == 1
    donnees, exp = envois[0]
    assert donnees["destinataires"] == [TELE2["id"]] and exp["arret_auto"] == MA_SALON
    assert donnees["reponses"] == ["Continuer"] and "1 min" in donnees["texte"]
    await musique_arret.tour(hass, coord, t0 + 30 * 60 - 20)  # pas deux avertissements
    assert len(envois) == 1
    arretes = await musique_arret.tour(hass, coord, t0 + 30 * 60 + 1)
    assert arretes == [MA_SALON] and appels == [("media_pause", MA_SALON), ("media_stop", MA_SALON)]


async def test_continuer_et_activite_relancent(hass, hass_storage, monkeypatch):
    coord, _a, _f = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import messages_v2, musique_arret
    musique_arret._ACTIVITE.clear(); musique_arret._AVERTIS.clear()
    appels = _services(hass)

    async def envoyer(*_a, **_k):
        return {"envoi": "x", "messages": []}
    monkeypatch.setattr(messages_v2, "envoyer", envoyer)
    coord.store.musique["arret_auto_min"] = 30
    _jouer(hass, MA_SALON, "Télé des parents · Vision")
    t0 = time.time()
    await musique_arret.tour(hass, coord, t0)
    # « Continuer » sur l'avertissement : le délai repart.
    assert musique_arret.reponse({"expediteur": {"nom": "Vision", "arret_auto": MA_SALON}}) is True
    assert MA_SALON in musique_arret._ACTIVITE
    musique_arret.noter(MA_SALON, t0 + 25 * 60)  # (heure simulée de la réponse)
    assert musique_arret.reponse({"expediteur": {"nom": "Lou"}}) is False  # une réponse ordinaire : pas concernée
    assert await musique_arret.tour(hass, coord, t0 + 31 * 60) == []
    # Une interaction avec l'appareil (inactivité remontée par l'agent) compte aussi.
    pc = coord.store.pcs[TELE2["id"]]
    pc["last_seen"] = time.time() + 60 * 60
    pc["agent"] = {"idle_seconds": 10}
    assert await musique_arret.tour(hass, coord, time.time() + 60 * 60 + 5) == []
    # Désactivé : jamais d'arrêt.
    coord.store.musique["arret_auto_min"] = 0
    assert await musique_arret.tour(hass, coord, time.time() + 10 * 3600) == [] and appels == []


async def test_commande_party_est_une_activite(hass, hass_storage, hass_client, monkeypatch):
    coord, _a, _f = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique_arret
    musique_arret._ACTIVITE.clear()
    client = await hass_client()
    s, _r = await _post(client, "commande", PARENT, action="lancer", ecran="t1", lecteur=MA_SALON)
    assert s == 200 and MA_SALON in musique_arret._ACTIVITE


async def test_ecran_eteint_met_en_pause(hass, hass_storage, monkeypatch):
    coord, _a, _f = await _monter(hass, hass_storage, monkeypatch)
    from custom_components.pc_parental import musique_arret
    appels = _services(hass)
    _jouer(hass, MA_SALON, "Télé des parents · Vision")
    _jouer(hass, MA_CHAMBRE, "Chambre")
    pc = coord.store.pcs[TELE2["id"]]
    assert await musique_arret.ecran(hass, coord.store, pc, "allume") is False
    assert await musique_arret.ecran(hass, coord.store, pc, "eteint") is True
    assert appels == [("media_pause", MA_SALON)]  # seulement le lecteur « · Vision » de cet appareil
    assert await musique_arret.ecran(hass, coord.store, pc, "eteint") is False  # déjà éteint : rien de plus
