"""Validation à plusieurs (1.72.0, docs/deverrouillage-quorum.md), dans un vrai Home Assistant.

Attaques couvertes : même personne via deux appareils ; voix d'un appareil non habilité ; approbation
après un refus ; quorum atteint puis rejeu de la décision ; repli avant l'heure ; durée allongée par
la seconde voix ; « toutes » quand une personne habilitée est ajoutée après la demande ; voix d'un
administrateur HA lié à une personne qui a déjà voté.

    python -m pytest essai/quorum_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from homeassistant.core import Context
from pytest_homeassistant_custom_component.common import MockConfigEntry

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, Appareil, _fiche, _integrations, expected_lingering_tasks,
    expected_lingering_timers,
)

MIA = {"id": "p2", "secret": "b" * 32}
PAPA2 = {"id": "p3", "secret": "c" * 32}  # la tablette de Robin : même personne, autre appareil


async def _monter(hass, hass_storage, validation=None, avec_mia=True):
    pcs = {
        "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android"),
        "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android"),
        "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent"),
        "p3": _fiche("p3", "TABLETTE-ROBIN", PAPA2["secret"], person="person.robin", platform="android", role="parent"),
    }
    if avec_mia:
        pcs["p2"] = _fiche("p2", "PIXEL-MIA", MIA["secret"], person="person.mia", platform="android", role="parent")
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "pcs": pcs, "roles_migres": True}}
    for p in ("lou", "noa", "robin", "mia"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = next(iter(hass.data[DOMAIN].values()))
    if validation is not None:
        from custom_components.pc_parental import roles
        roles.regler_validation(hass, coord.store, "enfant", validation, "essai")
    return coord


async def _decide(client, qui, demande, decision="approuver", **corps):
    r = await client.post("/api/pc_parental/deverrouillage/decision",
                          json={**qui, "demande": demande, "decision": decision, **corps})
    return r.status, await r.json()


def _demande(hass, ident):
    return hass.data["pc_parental_deverrouillage"]["demandes"][ident]


async def test_deux_personnes_distinctes(hass, hass_storage, hass_client, caplog):
    client = await hass_client()
    await _monter(hass, hass_storage, {"mode": "n", "n": 2})
    lou = Appareil(client, ENFANT)
    assert (await lou.demander(minutes=30))[0] == 200

    statut, r = await _decide(client, PARENT, lou.demande, minutes=20, remise="code")
    assert statut == 200 and r == {"ok": True, "etat": "partielle", "voix": 1, "requis": 2, "en_attente_de": ["Mia"]}
    assert "code" not in r and _demande(hass, lou.demande)["approbation"] is None  # rien n'est signé
    # L'enfant voit « 1/2, en attente de Mia ».
    e = (await lou.appel("etat", demande=lou.demande))[1]
    assert e["etat"] == "partielle" and e["voix"] == 1 and e["requis"] == 2 and e["en_attente_de"] == ["Mia"]
    assert "approbation" not in e
    # Robin revote depuis sa tablette : une personne = une voix.
    assert (await _decide(client, PAPA2, lou.demande))[:1] == (409,)
    assert (await _decide(client, PAPA2, lou.demande))[1]["error"] == "deja_vote"
    # Mia voit qu'il ne manque qu'elle ; Robin voit qu'il a déjà voté.
    liste = (await Appareil(client, MIA).appel("liste"))[1]["demandes"][0]
    assert liste["voix"] == 1 and liste["approuve_par"] == ["Robin"] and liste["deja_vote"] is False
    assert (await Appareil(client, PAPA2).appel("liste"))[1]["demandes"][0]["deja_vote"] is True
    # Mia propose plus long et une remise directe : la durée la plus courte gagne.
    statut, r = await _decide(client, MIA, lou.demande, minutes=30, remise="directe")
    assert statut == 200 and r["etat"] == "approuvee" and r["minutes"] == 20 and r["remise"] == "directe"
    a = (await lou.appel("etat", demande=lou.demande))[1]["approbation"]
    assert lou.appliquer(a) == "ok" and a["par"] == "Robin + Mia"
    assert any("voix de Robin" in m and "(1/2)" in m for m in caplog.messages)
    # Rejeu de la décision une fois le quorum atteint.
    assert (await _decide(client, PAPA2, lou.demande))[0] == 404


async def test_un_refus_refuse_et_appareil_non_habilite(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, {"mode": "toutes"})
    lou = Appareil(client, ENFANT)
    await lou.demander()
    # Un appareil enfant ne vote pas.
    assert (await _decide(client, ENFANT2, lou.demande))[0] == 403
    assert (await _decide(client, PARENT, lou.demande))[1]["etat"] == "partielle"
    assert (await _decide(client, MIA, lou.demande, "refuser"))[1]["etat"] == "refusee"
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "refusee"
    assert (await _decide(client, MIA, lou.demande))[0] == 404  # approuver après un refus


async def test_seuils_actions_et_duree(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, {"mode": "n", "n": 2, "actions": ["app"], "au_dela_de": 15})
    lou = Appareil(client, ENFANT)
    await lou.demander(minutes=10)  # sous le seuil : une voix suffit
    assert (await _decide(client, PARENT, lou.demande))[1]["etat"] == "approuvee"
    await lou.demander(action="reglages", minutes=30)  # action non concernée
    assert (await _decide(client, PARENT, lou.demande))[1]["etat"] == "approuvee"
    await lou.demander(minutes=30)
    assert (await _decide(client, PARENT, lou.demande))[1]["etat"] == "partielle"


async def test_repli_une_seule_suffit(hass, hass_storage, hass_client, caplog):
    client = await hass_client()
    await _monter(hass, hass_storage, {"mode": "n", "n": 2, "repli_minutes": 3})
    lou = Appareil(client, ENFANT)
    await lou.demander(minutes=30)
    # Pas de repli sans voix, ni avant l'heure.
    d = _demande(hass, lou.demande)
    assert d["repli"] and d["repli"] > time.time() + 170
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "en_attente"
    # Remise directe : le repli ne remplace pas un code ou un QR choisi (essai/quorum_attaques_test.py).
    await _decide(client, PARENT, lou.demande, minutes=15, remise="directe")
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "partielle"
    d["repli"] = time.time() - 1
    e = (await lou.appel("etat", demande=lou.demande))[1]
    assert e["etat"] == "approuvee" and e["approbation"]["minutes"] == 15 and e["approbation"]["remise"] == "directe"
    assert lou.appliquer(e["approbation"]) == "ok"
    assert any("repli" in m for m in caplog.messages)


async def test_toutes_fige_a_la_creation(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage, {"mode": "toutes"}, avec_mia=False)
    lou = Appareil(client, ENFANT)
    await lou.demander()
    assert _demande(hass, lou.demande)["requis"] == 1  # seul Robin approuve : une règle impossible retombe
    # Un nouveau parent arrive après la demande : il ne change rien à celle-ci.
    coord.store.pcs["e2"]["role"] = "parent"
    await lou.demander()
    assert _demande(hass, lou.demande)["requis"] == 2


async def test_voix_d_un_administrateur_lie_a_une_personne(hass, hass_storage, hass_client, hass_admin_user):
    client = await hass_client()
    await _monter(hass, hass_storage, {"mode": "n", "n": 2})
    hass.states.async_set("person.robin", "home", {"friendly_name": "Robin", "user_id": hass_admin_user.id})
    lou = Appareil(client, ENFANT)
    await lou.demander()
    assert (await _decide(client, PARENT, lou.demande))[1]["etat"] == "partielle"
    # L'administrateur est Robin : sa voix depuis Home Assistant ne compte pas une seconde fois.
    import pytest
    from homeassistant.exceptions import HomeAssistantError
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call(DOMAIN, "approuver_deverrouillage", {"demande": lou.demande},
                                       blocking=True, context=Context(user_id=hass_admin_user.id), return_response=True)
    assert (await _decide(client, MIA, lou.demande))[1]["etat"] == "approuvee"
