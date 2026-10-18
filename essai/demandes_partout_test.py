"""Une demande, partout, tout de suite (1.97.8 — Lou : notifications reçues, rien dans le widget ni la carte).

Pour chaque type (site, appli, temps, déverrouillage) et chaque enfant : la demande crée le message du décideur
(la notification sur son téléphone), apparaît dans « Demandes en attente » (carte Maison, appli) ET dans le widget
de l'accueil (même source), et déclenche l'événement qui fait recharger la carte sur-le-champ. Réglée depuis la
liste, elle en disparaît partout.

    python -m pytest essai/demandes_partout_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import base64
import secrets

import pytest

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _monter, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

EVENEMENTS = {"sites": "pc_parental_demande_acces", "apps": "pc_parental_demande_acces",
              "temps": "pc_parental_demande_temps", "deverrouillage": "pc_parental_deverrouillage_demande"}


async def _demander(client, ident, genre):
    if genre in ("sites", "apps"):
        r = await client.post("/api/pc_parental/demande", json={**ident, "genre": genre,
                                                              "nom": "exemple-devoirs.fr" if genre == "sites" else "com.roblox.client",
                                                              "libelle": "" if genre == "sites" else "Roblox"})
    elif genre == "temps":
        r = await client.post("/api/pc_parental/poll", json={**ident, "demande_temps": 20})
    else:
        defi = base64.urlsafe_b64encode(secrets.token_bytes(16)).rstrip(b"=").decode()
        r = await client.post("/api/pc_parental/deverrouillage/demande", json={**ident, "action": "app:com.roblox.client",
                                                                            "minutes": 20, "defi": defi})
    assert r.status == 200, (genre, r.status, await r.text())


@pytest.mark.parametrize("genre", ["sites", "apps", "temps", "deverrouillage"])
@pytest.mark.parametrize("enfant", ["e1", "e2"], ids=["lou", "noa"])
async def test_demande_partout(hass, hass_storage, hass_client, genre, enfant):
    from custom_components.pc_parental import parent, veille_plus

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    vus = []
    hass.bus.async_listen(EVENEMENTS[genre], lambda e: vus.append(e.data))
    coord.store.pcs["p1"]["messages"] = []
    await _demander(client, ENFANT if enfant == "e1" else ENFANT2, genre)
    await hass.async_block_till_done()
    prenom = "Lou" if enfant == "e1" else "Noa"

    # 1. La notification du décideur (message mis en file pour son téléphone).
    msgs = [m for m in coord.store.pcs["p1"]["messages"] if prenom in m.get("text", "") or prenom in m.get("title", "")]
    assert msgs, (genre, coord.store.pcs["p1"]["messages"])
    # 2. L'événement qui recharge la carte tout de suite.
    assert vus, genre
    # 3. La carte « Demandes en attente » et l'appli (même liste).
    liste = [d for d in parent.etat_maison(hass, coord, "p1")["demandes"] if d["pc"] == enfant]
    assert [d["genre"] for d in liste] == [genre], liste
    d = liste[0]
    assert d["prenom"] == prenom and d["id"] and (d.get("libelle") or d.get("cle"))
    if genre == "sites":
        assert d["cle"] == "exemple-devoirs.fr"
    # 4. Le widget de l'accueil (téléphone d'un décideur) : la même demande.
    accueil = [x for x in veille_plus.demandes(hass, coord) if x["pc"] == enfant]
    assert [(x["genre"], x["id"]) for x in accueil] == [(genre, d["id"])], accueil
    # 5. Réglée depuis la liste : elle disparaît de la carte et du widget.
    retour = await parent.agir(hass, coord, coord.store.pcs[enfant], {"action": "acces", "demande": d["id"], "decision": "non"}, "Nolan", "p1")
    assert retour and "déjà" not in retour, retour
    assert not [x for x in parent.etat_maison(hass, coord, "p1")["demandes"] if x["pc"] == enfant]
    assert not [x for x in veille_plus.demandes(hass, coord) if x["pc"] == enfant]


async def test_deverrouillage_accorde_depuis_la_liste(hass, hass_storage, hass_client):
    from custom_components.pc_parental import deverrouillage, parent

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    await _demander(client, ENFANT, "deverrouillage")
    d = next(x for x in parent.etat_maison(hass, coord, "p1")["demandes"] if x["genre"] == "deverrouillage")
    retour = await parent.agir(hass, coord, coord.store.pcs["e1"], {"action": "acces", "demande": d["id"], "decision": "temporaire"}, "Nolan", "p1")
    assert "20 min" in retour or "accord" in retour.lower(), retour
    etat = deverrouillage._demandes(hass)[d["id"]]["etat"]
    assert etat in ("approuvee", "en_attente"), etat  # quorum à plusieurs : une voix est notée


async def test_ancienne_appli_sans_deverrouillage_dans_la_liste(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    coord.store.pcs["p1"]["agent"] = {"version": "1.79.0", "platform": "android"}
    await _demander(client, ENFANT, "deverrouillage")
    await _demander(client, ENFANT2, "sites")
    r = await client.post("/api/pc_parental/parent", json={**PARENT})
    genres = {x["genre"] for x in (await r.json())["demandes"]}
    assert genres == {"sites"}, genres
