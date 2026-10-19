"""2.5.12 (Déverrouillage simplifié) : un Décideur déverrouille un appareil à son initiative, sans demande.

Parcours : l'appli d'un Décideur (Appareils › <appareil> › Déverrouiller 15/30/60 min), le service d'administrateur
et la carte. L'approbation est signée en version 2 (jamais confondue avec une approbation de demande, version 1),
remise par le relevé, à usage unique, journalisée à la consommation ; tous les Décideurs sont prévenus.

    python -m pytest essai/deverrouillage_initiative_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import base64
import logging
import time

import pytest
import voluptuous as vol
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from homeassistant.core import Context
from homeassistant.exceptions import Unauthorized

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)


def _message(appareil, a, version="2", defi="initiative") -> bytes:
    return "\n".join(["vision-deverrouillage", version, appareil, a["demande"], a["action"], str(int(a["minutes"])),
                      str(int(a["expire"])), defi]).encode("utf-8")


def _signee(cle, appareil, a, **plus) -> bool:
    """Ce que l'appli vérifie (Deverrouillage.verifierInitiative) : la signature en version 2, pour CET appareil."""
    publique = serialization.load_der_public_key(base64.b64decode(cle["pub"]))
    try:
        publique.verify(base64.urlsafe_b64decode(a["signature"] + "=" * (-len(a["signature"]) % 4)),
                        _message(appareil, a, **plus), ec.ECDSA(hashes.SHA256()))
        return True
    except InvalidSignature:
        return False


async def _initiative(client, appareil="e1", minutes=15, qui=PARENT, **plus):
    r = await client.post("/api/pc_parental/deverrouillage/initiative",
                          json={**qui, "appareil": appareil, "minutes": minutes, **plus})
    return r.status, await r.json()


async def _releve(client, qui=ENFANT):
    return await (await client.post("/api/pc_parental/poll", json=qui)).json()


async def test_parcours_depuis_l_appli_d_un_decideur(hass, hass_storage, hass_client, caplog):
    client = await hass_client()
    await _monter(hass, hass_storage)
    vus = []
    hass.bus.async_listen(f"{DOMAIN}_deverrouillage_initiative", lambda e: vus.append(e.data))
    caplog.set_level(logging.WARNING)
    statut, r = await _initiative(client, minutes=30)
    assert statut == 200 and r["demande"].startswith("init-") and r["minutes"] == 30
    await hass.async_block_till_done()
    assert vus and vus[0]["par"] == "Nolan" and vus[0]["nom"] == "TEL-LOU" and vus[0]["minutes"] == 30
    assert any("Nolan déverrouille « TEL-LOU »" in m for m in caplog.messages)
    # Le relevé de l'appareil porte l'approbation, en version 2, avec l'accès total (« parent »).
    releve = await _releve(client)
    a = releve["deverrouillage"]
    assert a["version"] == "2" and a["action"] == "parent" and a["minutes"] == 30 and a["remise"] == "directe"
    assert a["appareil"] == "e1" and a["expire"] <= time.time() + 10 * 60 + 2
    cle = releve["deverrouillage_cle"]
    assert _signee(cle, "e1", a)
    # Jamais confondue : ni comme approbation de demande (version 1), ni pour un autre appareil.
    assert not _signee(cle, "e1", a, version="1")
    assert not _signee(cle, "e2", a)
    # Rien pour les autres appareils.
    assert "deverrouillage" not in await _releve(client, ENFANT2)
    # Appliquée : consommée une fois, journalisée, puis plus rien dans le relevé.
    r = await client.post("/api/pc_parental/deverrouillage/consommer",
                          json={**ENFANT, "demande": a["demande"], "applique": int(time.time())})
    assert r.status == 200 and (await r.json())["fin"] >= time.time() + 29 * 60
    await hass.async_block_till_done()
    assert any("déverrouillage de « TEL-LOU »" in m and "approuvé par Nolan" in m for m in caplog.messages)
    r = await client.post("/api/pc_parental/deverrouillage/consommer", json={**ENFANT, "demande": a["demande"]})
    assert r.status == 409
    assert "deverrouillage" not in await _releve(client)


async def test_tous_les_decideurs_sont_prevenus(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage, parents=("p1", "e2"))  # e2 : un second appareil qui peut approuver
    coord = next(iter(hass.data[DOMAIN].values()))

    def messages(pid):
        return [m["text"] for m in coord.store.pcs[pid].get("messages") or []]
    assert (await _initiative(client, minutes=15))[0] == 200
    await hass.async_block_till_done()
    for pid in ("p1", "e2"):
        assert any("Nolan a déverrouillé « TEL-LOU » pour 15 min" in t for t in messages(pid)), (pid, messages(pid))
    assert not any("déverrouillé" in t for t in messages("e1"))  # l'appareil déverrouillé n'est pas un Décideur


async def test_refus(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    # Un appareil qui ne peut pas approuver ; soi-même ; une durée hors 15/30/60 ; un appareil inconnu.
    assert (await _initiative(client, appareil="e2", qui=ENFANT))[0] == 403
    assert (await _initiative(client, appareil="p1"))[0] == 403
    statut, r = await _initiative(client, minutes=20)
    assert statut == 400 and r["minutes_permises"] == [15, 30, 60]
    assert (await _initiative(client, minutes=90))[0] == 400
    assert (await _initiative(client, appareil="inconnu"))[0] == 404
    # Un PC : l'agent ne lirait jamais l'approbation.
    coord = next(iter(hass.data[DOMAIN].values()))
    coord.store.pcs["e2"]["platform"] = "windows"
    statut, r = await _initiative(client, appareil="e2")
    assert statut == 400 and r.get("raison") == "android"
    assert "deverrouillage" not in await _releve(client)


async def test_la_nouvelle_remplace_l_ancienne_et_annuler(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    premiere = (await _initiative(client, minutes=15))[1]["demande"]
    seconde = (await _initiative(client, minutes=60))[1]["demande"]
    a = (await _releve(client))["deverrouillage"]
    assert a["demande"] == seconde != premiere and a["minutes"] == 60
    r = await client.post("/api/pc_parental/deverrouillage/consommer", json={**ENFANT, "demande": premiere})
    assert r.status == 404  # l'ancienne, remplacée, n'ouvre plus rien
    statut, r = await _initiative(client, decision="annuler")
    assert statut == 200 and r["annulees"] == 1
    assert "deverrouillage" not in await _releve(client)


async def test_service_d_administrateur(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    admin = await hass.auth.async_create_user("Mia", group_ids=["system-admin"])
    r = await hass.services.async_call(DOMAIN, "deverrouiller_appareil", {"pc": "e1", "minutes": 60}, blocking=True,
                                       context=Context(user_id=admin.id), return_response=True)
    assert r["demande"].startswith("init-") and r["minutes"] == 60
    a = (await _releve(client))["deverrouillage"]
    assert a["par"] == "Mia" and a["minutes"] == 60
    # Une automatisation (sans utilisateur) ou un compte ordinaire : non.
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "deverrouiller_appareil", {"pc": "e1"}, blocking=True, return_response=True)
    lou = await hass.auth.async_create_user("Lou", group_ids=["system-users"])
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "deverrouiller_appareil", {"pc": "e1"}, blocking=True,
                                       context=Context(user_id=lou.id), return_response=True)
    with pytest.raises(vol.Invalid):
        await hass.services.async_call(DOMAIN, "deverrouiller_appareil", {"pc": "e1", "minutes": 45}, blocking=True,
                                       context=Context(user_id=admin.id), return_response=True)


async def test_carte_exige_le_code_du_decideur(hass, hass_storage, hass_client):
    # 2.5.12.5 : un administrateur qui se connecte n'a plus de code ; le code reste demandé à un compte à jetons seuls.
    from octroi_test import _client_code

    client = await _client_code(hass, hass_client)
    await _monter(hass, hass_storage)
    r = await client.post("/api/pc_parental/deverrouillage", json={"initiative": "e1", "minutes": 15})
    assert r.status == 403  # aucun code : le tableau n'accorde rien
    from custom_components.pc_parental import octroi

    coord = next(iter(hass.data[DOMAIN].values()))
    await hass.async_add_executor_job(octroi.poser, coord.store, "person.nolan", "4815")
    r = await client.post("/api/pc_parental/deverrouillage", json={"initiative": "e1", "minutes": 15, "code_octroi": "0000"})
    assert r.status == 403
    r = await client.post("/api/pc_parental/deverrouillage", json={"initiative": "e1", "minutes": 15, "code_octroi": "4815"})
    assert r.status == 200 and (await r.json())["minutes"] == 15
    assert (await _releve(client))["deverrouillage"]["minutes"] == 15
