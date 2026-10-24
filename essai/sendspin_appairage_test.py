"""2.5.12.14.2 (revue de HACS) : l'appairage Sendspin automatique n'existe plus (il passait par le client interne de
Music Assistant). La route reste, pour les appareils déjà installés : elle vérifie l'appareil puis répond toujours
« à faire dans Music Assistant ».

- seul un appareil inscrit (identifiant + secret) est entendu, et seulement un appareil Android (jamais un PC) ;
- la réponse est toujours {ok: false, resultat: "ma", retour: <phrase à faire dans Music Assistant>}.

    python -m pytest essai/sendspin_appairage_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from widgets_test import (  # noqa: F401  (banc des widgets et ses fixtures)
    DOMAIN, ENFANT, TELE, _monter, _post, expected_lingering_tasks, expected_lingering_timers, integrations,
)

URL = "/api/pc_parental/sendspin/appairage"


async def _appel(client, qui, **corps):
    r = await client.post(URL, json={**qui, **corps})
    return r.status, await r.json()


async def test_la_route_repond_a_faire_dans_ma(hass, integrations, hass_storage, hass_client):
    entree, coord = await _monter(hass, hass_storage)
    client = await hass_client()
    for corps in ({"etape": "demarrer", "lecteur": "cle-publique-tele"}, {"etape": "pin", "pin": "482913"}):
        st, d = await _appel(client, TELE, **corps)
        assert st == 200 and d["ok"] is False and d["resultat"] == "ma", d
        assert "Music Assistant" in d["retour"], d
    # Pas inscrit (secret faux) : refusé.
    st, d = await _appel(client, {"id": "t1", "secret": "faux" * 8}, etape="demarrer", lecteur="cle-publique-tele")
    assert st == 401
    # Un appareil qui n'est pas un Android (un PC) : refusé aussi.
    coord.store.pcs["e1"]["platform"] = "windows"
    st, d = await _appel(client, ENFANT, etape="demarrer", lecteur="cle-publique-tele")
    assert st == 403


async def test_prechauffage_des_pochettes(hass, integrations, hass_storage, monkeypatch):
    from custom_components.pc_parental import party, party_invites

    await _monter(hass, hass_storage)
    lues = []

    async def lire(_h, url):
        lues.append(url)
        party_invites._images[url] = (b"img", "image/jpeg")
        return party_invites._images[url]
    monkeypatch.setattr(party_invites, "telecharger_image", lire)
    party_invites._images.pop("http://ma/a.jpg", None); party_invites._images.pop("http://ma/b.jpg", None)
    n = party.prechauffer(hass, ["http://ma/a.jpg", "", "relais:x", "http://ma/a.jpg", "http://ma/b.jpg"])
    await hass.async_block_till_done()
    assert n == 2 and sorted(lues) == ["http://ma/a.jpg", "http://ma/b.jpg"]
    # Déjà gardées : rien de relancé.
    assert party.prechauffer(hass, ["http://ma/a.jpg", "http://ma/b.jpg"]) == 0
