"""2.5.12.5 (l'auteur, 19/10) : au tableau de bord de Vision, plus de code de déverrouillage pour les gestes d'un
administrateur de Home Assistant (qui se connecte lui-même) ou d'un Décideur déclaré. Décidé côté serveur, d'après le
compte de la requête (octroi.dispense), jamais d'après la carte ; chaque geste accordé sans code est journalisé avec
son auteur. Les autres comptes gardent le code : un compte à jetons seuls (accès d'API, assistant), un compte partagé,
une tablette, une télé. Les services (automatisations, sans utilisateur) ne changent pas.

    python -m pytest essai/octroi_sans_code_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN, Credentials
from homeassistant.core import Context
from octroi_test import CODE, MAISON, _actuel, _poser_code

GESTE = {"action": "temps", "pc": "e1", "minutes": 15}


async def _compte(hass, hass_client, nom, groupe, connexion=True, personne=None):
    """Un compte HA et son client : « connexion » = un identifiant de connexion (une personne qui se connecte)."""
    u = await hass.auth.async_create_user(nom, group_ids=[groupe])
    if connexion:
        await hass.auth.async_link_user(u, Credentials(auth_provider_type="homeassistant", auth_provider_id=None,
                                                       data={"username": nom.lower()}, is_new=False))
    if personne:
        hass.states.async_set(personne, "home", {"friendly_name": nom, "user_id": u.id})
    jeton = await hass.auth.async_create_refresh_token(u, client_name="essai", token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN)
    return u, await hass_client(hass.auth.async_create_access_token(jeton))


def _journal(hass):
    vus = []
    hass.bus.async_listen("pc_parental_geste_sans_code", lambda e: vus.append(dict(e.data)))
    return vus


async def test_un_administrateur_qui_se_connecte_n_a_pas_de_code(hass, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    _poser_code(hass)
    vus = _journal(hass)
    _u, client = await _compte(hass, hass_client, "Gestion", "system-admin")
    r = await client.post(MAISON, json=GESTE)
    assert r.status == 200, await r.text()
    await hass.async_block_till_done()
    assert vus and vus[-1]["par"] == "Gestion" and vus[-1]["raison"] == "administrateur" and "temps" in vus[-1]["geste"]


async def test_un_decideur_declare_n_a_pas_de_code(hass, hass_storage, hass_client):
    """Le compte lié à Nolan (PIXEL-NOLAN gère) : Décideur déclaré, même s'il n'a pas d'identifiant de connexion ici."""
    await _monter(hass, hass_storage)
    _poser_code(hass, "1111", "person.nolan")
    vus = _journal(hass)
    _u, client = await _compte(hass, hass_client, "Nolan", "system-admin", connexion=False, personne="person.nolan")
    r = await client.post(MAISON, json=GESTE)
    assert r.status == 200, await r.text()
    await hass.async_block_till_done()
    assert vus and vus[-1]["raison"] == "decideur" and vus[-1]["personne"] == "person.nolan"


async def test_un_compte_a_jetons_seuls_garde_le_code(hass, hass_storage, hass_client):
    """Un administrateur sans identifiant de connexion (accès d'API, assistant) : le code reste demandé."""
    await _monter(hass, hass_storage)
    _poser_code(hass)
    vus = _journal(hass)
    _u, client = await _compte(hass, hass_client, "Assistant", "system-admin", connexion=False)
    r = await client.post(MAISON, json=GESTE)
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"
    r = await client.post(MAISON, json={**GESTE, "code_octroi": CODE})
    assert r.status == 200
    assert not vus


async def test_un_non_administrateur_sans_code_est_refuse(hass, hass_storage, hass_client):
    """Un compte partagé ou de tablette (utilisateur ordinaire) : refusé, avec ou sans code."""
    await _monter(hass, hass_storage)
    _poser_code(hass)
    vus = _journal(hass)
    _u, client = await _compte(hass, hass_client, "Affichage", "system-users")
    r = await client.post(MAISON, json=GESTE)
    assert r.status == 403
    assert not vus


async def test_une_automatisation_ne_change_pas(hass, hass_storage):
    """Un service appelé sans utilisateur (automatisation) : comme avant, sans code ni geste « sans code » noté."""
    await _monter(hass, hass_storage)
    _poser_code(hass)
    vus = _journal(hass)
    await hass.services.async_call(DOMAIN, "pause_generale", {"minutes": 30}, blocking=True, context=Context())
    await hass.async_block_till_done()
    from custom_components.pc_parental import pause_generale

    assert pause_generale.en_cours(_actuel(hass).store)
    assert not vus
