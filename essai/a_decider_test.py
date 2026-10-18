"""Refonte UX, lot 2 — « À décider » unique : notifications avec boutons (appli Compagnon), réponses, lots.

    python -m pytest essai/a_decider_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pytest
from homeassistant.core import Context, ServiceCall

from demandes_partout_test import _demander
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _monter, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

TELEPHONE = "mobile_app_pixel_decideur"


def _actuel(hass):
    """Le coordinateur en service (le banc recharge l'entrée après le premier relevé d'un appareil)."""
    return next(iter(hass.data[DOMAIN].values()))


def _telephone(hass):
    recus = []

    async def notifier(call: ServiceCall):
        recus.append(dict(call.data))
    hass.services.async_register("notify", TELEPHONE, notifier)
    return recus


# HA 2.5 : les demandes d'accès (sites, applis) passent par notifs_mobiles (cinq choix, essais à part) ; « À décider »
# notifie le temps et le déverrouillage. Voir test_acces_par_notifs_mobiles_seulement.
@pytest.mark.parametrize("genre", ["temps", "deverrouillage"])
async def test_notification_avec_boutons_puis_reponse(hass, hass_storage, hass_client, hass_admin_user, genre):
    from custom_components.pc_parental import a_decider, parent

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    coord.store.notif_decideurs = [TELEPHONE]
    recus = _telephone(hass)
    await _demander(client, ENFANT, genre)
    await hass.async_block_till_done()
    coord = _actuel(hass)
    envoi = [r for r in recus if r.get("message") != "clear_notification"]
    assert len(envoi) == 1, recus
    attendu = {"sites": "acces", "temps": "temps", "deverrouillage": "deverrouillage"}[genre]
    actions = [b["action"] for b in envoi[0]["data"]["actions"]]
    assert all(a.startswith(f"VISION|{attendu}|e1|") for a in actions), actions
    # Faille du 16/10 : accorder exige l'empreinte ou le code ; la notification ne garde que « Refuser ».
    assert len(actions) == 1 and actions[-1].endswith("|non") and envoi[0]["data"]["tag"].startswith(f"vision_{attendu}_")
    # Réponse « Refuser » depuis le téléphone (un administrateur de HA) : réglée partout, notification retirée.
    recus.clear()
    hass.bus.async_fire(a_decider.EVENEMENT_ACTION, {"action": actions[-1]}, context=Context(user_id=hass_admin_user.id))
    await hass.async_block_till_done()
    assert not [d for d in parent.etat_maison(hass, coord, "p1")["demandes"] if d["pc"] == "e1"]
    assert any(r.get("message") == "clear_notification" for r in recus), recus


async def test_acces_par_notifs_mobiles_seulement(hass, hass_storage, hass_client):
    """Une demande d'accès n'est jamais notifiée deux fois : « À décider » la laisse à notifs_mobiles (HA 2.5)."""
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    coord.store.notif_decideurs = [TELEPHONE]
    recus = _telephone(hass)
    await _demander(client, ENFANT, "sites")
    await hass.async_block_till_done()
    assert not [r for r in recus if any(str(b.get("action", "")).startswith("VISION|acces|") for b in (r.get("data") or {}).get("actions", []))], recus


async def test_reponse_refusee_sans_droit(hass, hass_storage, hass_client, hass_read_only_user):
    from custom_components.pc_parental import a_decider, parent

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    coord.store.notif_decideurs = [TELEPHONE]
    recus = _telephone(hass)
    await _demander(client, ENFANT, "temps")
    await hass.async_block_till_done()
    coord = _actuel(hass)
    action = recus[0]["data"]["actions"][0]["action"]
    hass.bus.async_fire(a_decider.EVENEMENT_ACTION, {"action": action}, context=Context(user_id=hass_read_only_user.id))
    hass.bus.async_fire(a_decider.EVENEMENT_ACTION, {"action": action})  # sans utilisateur
    await hass.async_block_till_done()
    assert [d["genre"] for d in parent.etat_maison(hass, coord, "p1")["demandes"] if d["pc"] == "e1"] == ["temps"]


async def test_sans_telephone_choisi_rien_ne_part(hass, hass_storage, hass_client):
    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    assert coord.store.notif_decideurs == []
    recus = _telephone(hass)
    await _demander(client, ENFANT, "sites")
    await hass.async_block_till_done()
    assert recus == []


def test_decodage_et_corps():
    from custom_components.pc_parental import a_decider

    assert a_decider.decodage("autre|chose") is None
    assert a_decider.decodage("VISION|inconnu|e1|x|non") is None
    r = a_decider.decodage("VISION|acces|e1|abc|30")
    assert r == {"genre": "acces", "pc": "e1", "id": "abc", "choix": "30"}
    assert a_decider.corps_agir(r) == {"action": "acces", "pc": "e1", "demande": "abc", "decision": "temporaire", "minutes": 30}
    assert a_decider.corps_agir({**r, "choix": "toujours"})["decision"] == "toujours"
    assert a_decider.corps_agir({**r, "choix": "non"})["decision"] == "non"


async def test_service_ajouter_temps_repond_a_la_demande(hass, hass_storage, hass_client):
    from custom_components.pc_parental import parent

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    await _demander(client, ENFANT, "temps")
    await hass.async_block_till_done()
    coord = _actuel(hass)
    assert coord.store.pcs["e1"].get("demande")
    await hass.services.async_call(DOMAIN, "ajouter_temps", {"personne": "person.lou", "minutes": 20}, blocking=True)
    assert not coord.store.pcs["e1"].get("demande")
    assert not [d for d in parent.etat_maison(hass, coord, "p1")["demandes"] if d["pc"] == "e1"]


async def test_lots_dans_etat_maison(hass, hass_storage):
    from custom_components.pc_parental import parent
    from custom_components.pc_parental.processus import CAPTEUR

    coord = await _monter(hass, hass_storage)
    hass.states.async_set(CAPTEUR, "3")
    lots = parent.etat_maison(hass, coord, "p1")["a_decider_lots"]
    logiciels = [x for x in lots if x["genre"] == "logiciels"]
    assert logiciels and logiciels[0]["nombre"] == 3 and "3" in logiciels[0]["libelle"]
    assert logiciels[0]["ou"].startswith("/")
