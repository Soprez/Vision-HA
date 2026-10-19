"""2.5.12.4 (l'auteur, 19/10 : « l'appli doit découvrir SEULE quels sites appartiennent à quelle appli ») : sur un
téléphone, Snapchat, Instagram et WhatsApp autorisés, mais la catégorie fermée « Réseaux sociaux » bloquait leurs
CDN (sc-cdn.net, cdninstagram.com, whatsapp.net) : plus d'images. Une appli mobile autorisée garde désormais ses
sites, sur CET appareil seulement ; un domaine de publicité ou de traçage reste bloqué, même rangé sous l'appli.

    python -m pytest essai/applis_mobiles_cdn_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

SOCIAL = ("snapchat.com", "sc-cdn.net", "sc-static.net", "cdninstagram.com", "instagram.com", "whatsapp.com", "whatsapp.net")
TRACAGE = ("app-measurement.com", "appsflyer.com", "facebook.net")
APPLIS = ["com.snapchat.android", "com.instagram.android", "com.whatsapp"]


async def _installer(hass, hass_storage, extra=None):
    ferme = {"etiquette": "Réseaux sociaux", "raison": "Fermé.", "source": "", "depuis": 1}
    donnees = {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "etiquettes": {"apps": {a: ["Réseaux sociaux"] for a in APPLIS},
                       "sites": {**{s: ["Réseaux sociaux"] for s in SOCIAL}, **{s: ["Pub & traçage"] for s in TRACAGE}}},
        "fermetures": {"person.lou": {"reseaux|": ferme}, "person.noa": {"reseaux|": ferme}},
        "pcs": {
            # Lou : les trois applis autorisées sur son téléphone. Noa : rien d'autorisé.
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant",
                         autorises=list(APPLIS), bloques=["Pub & traçage"]),
            "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android", role="enfant",
                         bloques=["Pub & traçage"]),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent"),
        },
        **(extra or {}),
    }
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    for p in ("lou", "noa", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id].store


async def test_appli_autorisee_garde_ses_cdn_sur_cet_appareil_seulement(hass, hass_storage):
    store = await _installer(hass, hass_storage)
    lou = store.effectif(store.pcs["e1"])
    noa = store.effectif(store.pcs["e2"])
    # Lou : les applis passent, leurs sites aussi (sous la catégorie fermée).
    assert not set(APPLIS) & set(lou["apps"]), lou["apps"]
    assert not set(SOCIAL) & set(lou["sites"]), lou["sites"]
    assert {"sc-cdn.net", "cdninstagram.com", "whatsapp.net"} <= set(lou["exceptions"])
    # Noa : rien d'autorisé, la catégorie fermée bloque tout.
    assert set(APPLIS) <= set(noa["apps"])
    assert {"sc-cdn.net", "cdninstagram.com", "whatsapp.net"} <= set(noa["sites"])


async def test_le_tracage_reste_bloque_meme_range_sous_l_appli(hass, hass_storage):
    # Un Décideur (ou un lien ancien) a rangé un domaine de traçage sous Snapchat : il reste fermé.
    store = await _installer(hass, hass_storage, extra={"domaines_lies": {"com.snapchat.android": ["app-measurement.com"]}})
    lou = store.effectif(store.pcs["e1"])
    for s in TRACAGE:
        assert s not in lou["exceptions"], (s, lou["exceptions"])
    assert "app-measurement.com" in store.dependances("com.snapchat.android")
    assert "sc-cdn.net" in lou["exceptions"]


async def test_whatsapp_rattache_a_l_appli_et_plus_au_programme_pc(hass, hass_storage):
    lien = {"depuis": 1, "suit": True}
    store = await _installer(hass, hass_storage, extra={"domaines_auto": {
        "whatsapp.root": {"whatsapp.com": lien, "whatsapp.net": lien}, "discord": {"exemple-discord.com": lien}}})
    assert "whatsapp.root" not in store.domaines_auto
    assert store.domaines_auto["discord"] == {"exemple-discord.com": lien}  # les autres liens tout seuls restent
    proprio = store.logiciel_des_sites()
    assert proprio["whatsapp.com"] == "com.whatsapp" and proprio["whatsapp.net"] == "com.whatsapp"


def test_racine_d_un_nom_de_paquet():
    from custom_components.pc_parental.store import racine_app as r
    assert r("com.snapchat.android") == "snapchat"
    assert r("com.instagram.android") == "instagram"
    assert r("com.google.android.youtube") == "youtube"
    assert r("io.lonepalm.retro") == "lonepalm"
    assert r("roblox") == "roblox"
    assert r("com.android") == ""
