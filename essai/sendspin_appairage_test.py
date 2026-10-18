"""2.4.5 (retour d'essai) : appairage Sendspin automatique — HA lance le setup flow de MA et soumet le PIN envoyé par l'appareil.

- seul un appareil inscrit (identifiant + secret) demande, et seulement pour SON lecteur (nom « <appareil> · Vision ») ;
- le PIN passe par le canal Vision et va au flow lancé pour cet appareil ; rien sans flow en cours ;
- droits manquants dans MA : réponse « droits » (l'appairage reste manuel) ; PIN refusé : le flow est fermé ;
- jamais pour un PC ; une demande par minute.

    python -m pytest essai/sendspin_appairage_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pytest

from widgets_test import (  # noqa: F401  (banc des widgets et ses fixtures)
    DOMAIN, ENFANT, TELE, _monter, _post, expected_lingering_tasks, expected_lingering_timers, integrations,
)

URL = "/api/pc_parental/sendspin/appairage"


class _FauxMA:
    def __init__(self, nom="TELE-SALON · Vision", droits=True, bon_pin="482913"):
        self.nom, self.droits, self.bon_pin = nom, droits, bon_pin
        self.appels: list[tuple[str, dict]] = []
        self.gets = 0

    async def send_command(self, commande, **k):
        self.appels.append((commande, k))
        if commande == "players/get":
            if k["player_id"] != "cle-publique-tele":
                raise RuntimeError("player not found")
            return {"player_id": k["player_id"], "name": self.nom}
        if commande == "config/players/setup":
            if not self.droits:
                raise RuntimeError("Insufficient scope: config.players.write")
            return {"flow_id": "flux-1", "step_id": "pair", "type": "progress"}
        if commande == "config/flows/get":
            self.gets += 1
            if self.gets == 1:
                return {"flow_id": "flux-1", "step_id": "pair", "type": "progress"}
            return {"flow_id": "flux-1", "step_id": "pin", "type": "form",
                    "entries": [{"key": "pairing_code", "type": "pairing_code"}]}
        if commande == "config/flows/submit":
            if k["values"].get("pairing_code") == self.bon_pin:
                return {"flow_id": "flux-1", "step_id": "fini", "type": "finish", "result": {"player_id": "x"}}
            return {"flow_id": "flux-1", "step_id": "pin", "type": "form", "errors": {"pairing_code": "pin_mismatch"},
                    "entries": [{"key": "pairing_code", "type": "pairing_code"}]}
        if commande == "config/flows/abort":
            return None
        raise AssertionError(commande)


@pytest.fixture
def ma(monkeypatch):
    from custom_components.pc_parental import musique, sendspin_appairage

    faux = _FauxMA()
    monkeypatch.setattr(musique, "_client", lambda _h: faux)
    monkeypatch.setattr(sendspin_appairage.asyncio, "sleep", _sans_attente)
    sendspin_appairage._FLOWS.clear(); sendspin_appairage._DERNIER_DEMARRER.clear()
    return faux


async def _sans_attente(_s):
    return None


async def _appel(client, qui, **corps):
    r = await client.post(URL, json={**qui, **corps})
    return r.status, await r.json()


async def test_appairage_automatique(hass, integrations, hass_storage, hass_client, ma):
    await _monter(hass, hass_storage)
    client = await hass_client()
    # Sans flow : un PIN n'est rien.
    st, d = await _appel(client, TELE, etape="pin", pin="482913")
    assert (st, d["resultat"]) == (200, "aucun")
    st, d = await _appel(client, TELE, etape="demarrer", lecteur="cle-publique-tele")
    assert d["ok"] and d["resultat"] == "ok", d
    assert ("config/players/setup", {"player_id": "cle-publique-tele"}) in ma.appels
    st, d = await _appel(client, TELE, etape="pin", pin="482913")
    assert d["ok"] and d["resultat"] == "ok" and d["retour"], d
    assert ("config/flows/submit", {"flow_id": "flux-1", "values": {"pairing_code": "482913"}}) in ma.appels
    # Le flow est fermé : un second PIN n'est plus rien.
    st, d = await _appel(client, TELE, etape="pin", pin="482913")
    assert d["resultat"] == "aucun"


async def test_refus_et_securite(hass, integrations, hass_storage, hass_client, ma):
    from custom_components.pc_parental import sendspin_appairage

    await _monter(hass, hass_storage)
    client = await hass_client()
    # Pas inscrit : refusé.
    st, d = await _appel(client, {"id": "t1", "secret": "faux" * 8}, etape="demarrer", lecteur="cle-publique-tele")
    assert st == 401
    # Le lecteur d'un autre appareil (nom différent) : jamais.
    ma.nom = "Chambre · Vision"
    st, d = await _appel(client, TELE, etape="demarrer", lecteur="cle-publique-tele")
    assert d["resultat"] == "lecteur" and not any(c == "config/players/setup" for c, _ in ma.appels)
    # Un lecteur inconnu de MA : non plus.
    st, d = await _appel(client, TELE, etape="demarrer", lecteur="autre-cle")
    assert d["resultat"] == "lecteur"
    # Le bon lecteur, mauvais PIN : refus, flow fermé (abort envoyé).
    ma.nom = "tele-salon · vision"  # casse indifférente
    st, d = await _appel(client, TELE, etape="demarrer", lecteur="cle-publique-tele")
    assert d["resultat"] == "ok"
    st, d = await _appel(client, TELE, etape="pin", pin="000000")
    assert d["resultat"] == "refus" and ("config/flows/abort", {"flow_id": "flux-1"}) in ma.appels
    # PIN mal formé : refusé sans appel à MA.
    sendspin_appairage._FLOWS["t1"] = {"flow": "flux-1", "debut": __import__("time").monotonic(), "joueur": "x"}
    st, d = await _appel(client, TELE, etape="pin", pin="12ab")
    assert d["resultat"] == "pin"
    # Une demande par minute.
    st, d = await _appel(client, TELE, etape="demarrer", lecteur="cle-publique-tele")
    assert d["resultat"] == "trop_vite"


async def test_droits_manquants_et_pc(hass, integrations, hass_storage, hass_client, ma):
    entree, coord = await _monter(hass, hass_storage)
    client = await hass_client()
    ma.droits = False
    st, d = await _appel(client, TELE, etape="demarrer", lecteur="cle-publique-tele")
    assert d["resultat"] == "droits" and "Music Assistant" in d["retour"]
    # Un PC n'a pas de lecteur Sendspin.
    coord.store.pcs["e1"]["platform"] = "windows"
    st, d = await _appel(client, ENFANT, etape="demarrer", lecteur="cle-publique-tele")
    assert st == 403


async def test_lecteur_enregistre_un_instant_apres(hass, integrations, hass_storage, hass_client, ma):
    """Essai réel du 12/10 : l'appareil demande dès sa connexion, MA enregistre le lecteur 0,1 s après."""
    await _monter(hass, hass_storage)
    client = await hass_client()
    appels_get = {"n": 0}
    origine = ma.send_command

    async def tardif(commande, **k):
        if commande == "players/get":
            appels_get["n"] += 1
            if appels_get["n"] < 3:
                raise RuntimeError("player not found")
        return await origine(commande, **k)
    ma.send_command = tardif
    st, d = await _appel(client, TELE, etape="demarrer", lecteur="cle-publique-tele")
    assert d["resultat"] == "ok" and appels_get["n"] == 3, d


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
