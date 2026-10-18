"""Relecture sécurité du quorum (docs/deverrouillage-quorum.md) : contournements, dans un vrai Home Assistant.

Une voix = une **personne** habilitée au moment de la demande. On vérifie qu'aucune de ces voies ne
compte deux fois le même adulte, ni une voix qui n'était pas prévue :
- un administrateur HA dont le compte n'est lié à aucune personne, qui vote en plus depuis son téléphone ;
- un appareil « Parent » sans personne (tablette du salon), en plus du téléphone d'un parent ;
- en mode « toutes », une personne devenue approbatrice après la demande, à la place d'une habilitée ;
- le demandeur lui-même, via un autre de ses appareils ;
- le repli « une seule validation suffit » quand le parent avait choisi un code ou un QR : il ne doit
  pas devenir une remise directe que ce parent n'a pas voulue.

    python -m pytest essai/quorum_attaques_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

import pytest
from homeassistant.core import Context
from homeassistant.exceptions import HomeAssistantError

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, Appareil, _fiche, _integrations, expected_lingering_tasks,
    expected_lingering_timers,
)
from quorum_test import MIA, _decide, _demande, _monter

TABLETTE = {"id": "p4", "secret": "d" * 32}


async def test_admin_sans_personne_ne_double_pas_un_parent(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, {"mode": "n", "n": 2})
    admin = await hass.auth.async_create_user("Compte de Nolan", group_ids=["system-admin"])  # lié à aucune personne
    lou = Appareil(client, ENFANT)
    await lou.demander()
    assert (await _decide(client, PARENT, lou.demande))[1]["etat"] == "partielle"
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call(DOMAIN, "approuver_deverrouillage", {"demande": lou.demande},
                                       blocking=True, context=Context(user_id=admin.id), return_response=True)
    assert _demande(hass, lou.demande)["approbation"] is None
    # Lié à une personne habilitée (Mia), il compte, une fois.
    hass.states.async_set("person.mia", "home", {"friendly_name": "Mia", "user_id": admin.id})
    r = await hass.services.async_call(DOMAIN, "approuver_deverrouillage", {"demande": lou.demande},
                                       blocking=True, context=Context(user_id=admin.id), return_response=True)
    assert r["etat"] == "approuvee"


async def test_appareil_parent_sans_personne_ne_compte_pas(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage, {"mode": "n", "n": 2})
    coord.store.pcs["p4"] = _fiche("p4", "TABLETTE-SALON", TABLETTE["secret"], platform="android", role="parent")
    coord.store._completer(coord.store.pcs["p4"])
    lou = Appareil(client, ENFANT)
    await lou.demander()
    assert "appareil:p4" not in _demande(hass, lou.demande)["habilites"]
    assert (await _decide(client, PARENT, lou.demande))[1]["etat"] == "partielle"
    statut, r = await _decide(client, TABLETTE, lou.demande)
    assert (statut, r["error"]) == (403, "personne")
    assert _demande(hass, lou.demande)["approbation"] is None
    assert (await _decide(client, MIA, lou.demande))[1]["etat"] == "approuvee"


async def test_toutes_une_voix_non_prevue_ne_remplace_pas(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage, {"mode": "toutes"})
    lou = Appareil(client, ENFANT)
    await lou.demander()
    assert sorted(_demande(hass, lou.demande)["habilites"]) == ["person.mia", "person.nolan"]
    coord.store.pcs["e2"]["role"] = "parent"  # Noa devient approbateur après la demande
    assert (await _decide(client, PARENT, lou.demande))[1]["etat"] == "partielle"
    statut, r = await _decide(client, ENFANT2, lou.demande)
    assert (statut, r["error"]) == (403, "non_habilite")
    assert _demande(hass, lou.demande)["approbation"] is None
    assert (await _decide(client, MIA, lou.demande))[1]["etat"] == "approuvee"


async def test_le_demandeur_ne_vote_pas_pour_lui_meme(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage, {"mode": "n", "n": 2})
    # La tablette de Lou a (par erreur) le rôle Parent : Lou ne valide pas sa propre demande.
    coord.store.pcs["p4"] = _fiche("p4", "TABLETTE-LOU", TABLETTE["secret"], person="person.lou",
                                   platform="android", role="parent")
    coord.store._completer(coord.store.pcs["p4"])
    lou = Appareil(client, ENFANT)
    await lou.demander()
    statut, r = await _decide(client, TABLETTE, lou.demande)
    assert (statut, r["error"]) == (403, "parent")


@pytest.mark.parametrize("remise", ["code", "qr"])
async def test_repli_ne_change_pas_la_remise_choisie(hass, hass_storage, hass_client, caplog, remise):
    client = await hass_client()
    await _monter(hass, hass_storage, {"mode": "n", "n": 2, "repli_minutes": 3})
    lou = Appareil(client, ENFANT)
    await lou.demander()
    assert (await _decide(client, PARENT, lou.demande, remise=remise))[1]["etat"] == "partielle"
    _demande(hass, lou.demande)["repli"] = time.time() - 1
    e = (await lou.appel("etat", demande=lou.demande))[1]
    # Le parent voulait remettre un code (ou un QR) en personne : pas d'approbation directe à sa place.
    assert "approbation" not in e and e["etat"] == "partielle"
    assert "deverrouillage" not in await (await client.post("/api/pc_parental/poll", json=ENFANT)).json()
    # Le second parent peut toujours compléter le quorum.
    assert (await _decide(client, MIA, lou.demande))[1]["etat"] == "approuvee"


async def test_repli_directe_toujours_possible(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, {"mode": "n", "n": 2, "repli_minutes": 3})
    lou = Appareil(client, ENFANT)
    await lou.demander()
    await _decide(client, PARENT, lou.demande, minutes=12, remise="directe")
    _demande(hass, lou.demande)["repli"] = time.time() - 1
    a = (await lou.appel("etat", demande=lou.demande))[1]["approbation"]
    assert a["minutes"] == 12 and a["par"] == "Nolan" and lou.appliquer(a) == "ok"
