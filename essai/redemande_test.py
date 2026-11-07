"""1.94 : l'attente avant de refaire une demande (règle du serveur, pas seulement de l'appli).

- une demande faite (logiciel, site, temps) ne peut pas être refaite avant la fin de l'attente (5 min par défaut) ;
- une autre demande (autre logiciel, autre site) n'est pas retenue par l'attente d'une autre ;
- l'attente est réglable (0 : aucune), propre à une personne si on la pose, et dite à l'appli au relevé ;
- elle vaut pour la personne sur tous ses appareils ; un module « Demandes » coupé n'en parle plus.

    python -m pytest essai/redemande_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import time

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


async def _installer(hass, hass_storage):
    nolan = _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent")
    nolan["agent"] = {"version": "1.94.0", "platform": "android"}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "PC-CAMILLE", ENFANT["secret"], person="person.camille"),
            "e2": _fiche("e2", "TEL-CAMILLE", "s" * 24, person="person.camille", platform="android"),
            "p1": nolan,
        }}}
    hass.states.async_set("person.camille", "home", {"friendly_name": "Camille"})
    hass.states.async_set("person.nolan", "home", {"friendly_name": "Nolan"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


def _demande(genre, nom, **plus):
    return {**ENFANT, "genre": genre, "nom": nom, "libelle": nom, **plus}


async def test_le_defaut_est_cinq_minutes(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    assert coord.store.delai_redemande == 5
    assert coord.store.delai_redemande_de(coord.store.pcs["e1"]) == 5


async def test_la_meme_demande_ne_peut_pas_etre_refaite_tout_de_suite(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    r = await client.post("/api/pc_parental/demande", json=_demande("apps", "roblox"))
    assert (await r.json())["ok"] is True
    # Tout de suite après : refusée par le serveur, avec l'heure et le texte à montrer.
    r = await client.post("/api/pc_parental/demande", json=_demande("apps", "roblox"))
    corps = await r.json()
    assert corps["ok"] is False and corps["error"] == "delai"
    assert corps["jusqua"] > time.time() and "5" in corps["retour"]
    # Pas de seconde demande chez les parents.
    assert len(coord.store.pcs["e1"]["demandes"]) == 1


async def test_une_autre_demande_n_est_pas_retenue(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    assert (await (await client.post("/api/pc_parental/demande", json=_demande("apps", "roblox"))).json())["ok"] is True
    # Un autre logiciel, un site : libres.
    assert (await (await client.post("/api/pc_parental/demande", json=_demande("apps", "minecraft"))).json())["ok"] is True
    assert (await (await client.post("/api/pc_parental/demande", json=_demande("sites", "exemple-devoirs.fr"))).json())["ok"] is True
    assert len(coord.store.pcs["e1"]["demandes"]) == 3


async def test_l_attente_finie_la_demande_repart(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    assert (await (await client.post("/api/pc_parental/demande", json=_demande("apps", "roblox"))).json())["ok"] is True
    for a in coord.store.pcs.values():
        for k in (a.get("redemande") or {}):
            a["redemande"][k] = time.time() - 1
    assert (await (await client.post("/api/pc_parental/demande", json=_demande("apps", "roblox"))).json())["ok"] is True


async def test_delai_zero_pas_d_attente(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    coord.store.delai_redemande = 0
    client = await hass_client()
    for _ in range(2):
        assert (await (await client.post("/api/pc_parental/demande", json=_demande("apps", "roblox"))).json())["ok"] is True
    assert coord.store.redemandes_en_cours(coord.store.pcs["e1"]) == []


async def test_l_attente_vaut_pour_la_personne_sur_tous_ses_appareils(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    assert (await (await client.post("/api/pc_parental/demande", json=_demande("apps", "roblox"))).json())["ok"] is True
    # Le téléphone de la même personne ne la refait pas non plus.
    assert coord.store.redemande_jusqua(coord.store.pcs["e2"], "apps", "roblox") > time.time()
    # Celle d'une autre personne n'est pas retenue.
    assert coord.store.redemande_jusqua(coord.store.pcs["p1"], "apps", "roblox") == 0


async def test_delai_de_la_personne_prend_le_pas_et_se_retire(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    s = coord.store; e1 = s.pcs["e1"]
    s.poser_delai_redemande_personne(e1, 20)
    assert s.delai_redemande_de(e1) == 20 and s.delai_redemande_de(s.pcs["e2"]) == 20
    assert s.delai_redemande_de(s.pcs["p1"]) == 5
    s.poser_delai_redemande_personne(e1, 0)
    assert s.delai_redemande_de(e1) == 0
    s.poser_delai_redemande_personne(e1, None)
    assert s.delai_redemande_de(e1) == 5


async def test_un_delai_raccourci_ne_fait_pas_attendre_plus(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    s = coord.store; e1 = s.pcs["e1"]
    s.delai_redemande = 60
    s.noter_demande(e1, "apps", "roblox")
    s.delai_redemande = 5
    assert 0 < s.redemande_jusqua(e1, "apps", "roblox") <= time.time() + 5 * 60 + 1


async def test_le_releve_dit_l_attente_a_l_appli(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    await client.post("/api/pc_parental/demande", json=_demande("apps", "roblox"))
    r = await client.post("/api/pc_parental/poll", json={**ENFANT, "running": []})
    corps = await r.json()
    assert corps["delai_redemande_min"] == 5
    assert [x["nom"] for x in corps["redemande"]] == ["roblox"] and corps["redemande"][0]["jusqua"] > time.time()


async def test_la_demande_de_temps_a_la_meme_attente(hass, hass_storage, hass_client):
    coord = await _installer(hass, hass_storage)
    client = await hass_client()
    await client.post("/api/pc_parental/poll", json={**ENFANT, "running": [], "demande_temps": 20})
    assert coord.store.redemande_jusqua(coord.store.pcs["e1"], *coord.store.TEMPS) > time.time()
    premiere = coord.store.pcs["e1"].get("demande")
    assert premiere and premiere["minutes"] == 20
    # Une seconde, tout de suite : ignorée (la première reste telle quelle).
    await client.post("/api/pc_parental/poll", json={**ENFANT, "running": [], "demande_temps": 60})
    assert coord.store.pcs["e1"]["demande"] == premiere
