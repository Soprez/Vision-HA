"""Widget « Demandes » de l'accueil (2.5.3, l'auteur : « Aucune demande en attente » alors qu'une demande attendait).

/api/pc_parental/veille envoie, pour l'accueil :
- les demandes une à une (même source que « À décider ») et leur nombre total (la liste reste coupée à quatre) ;
- pour un Décideur seulement, ce qui se traite par lots (appareil à valider, à classer, logiciels) ;
- une erreur dans les demandes ne fait jamais tomber le reste de la réponse.

    python -m pytest essai/accueil_demandes_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from accueil_test import _monter
from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

LOT = {"genre": "appairage", "nombre": 1, "libelle": "1 appareil à valider", "ou": "/vision-maison/appareils"}


def _six(*_a, **_k):
    return [{"id": f"d{i}", "genre": "temps", "minutes": 15, "libelle": "15 min", "ts": 1000 - i, "prenom": "Lou", "pc": "e1"}
            for i in range(6)]


async def test_total_et_lots_pour_un_decideur(hass, hass_storage, hass_client_no_auth, monkeypatch):
    await _monter(hass, hass_storage)
    from custom_components.pc_parental import a_decider, parent

    monkeypatch.setattr(parent, "demandes_attente", _six)
    monkeypatch.setattr(a_decider, "lots", lambda *_a, **_k: [LOT])
    client = await hass_client_no_auth()
    d = await (await client.post("/api/pc_parental/veille", json={**PARENT, "ecran": "telephone"})).json()
    assert len(d["demandes"]) == 4 and d["demandes_total"] == 6, d["demandes_total"]
    assert d["a_decider_lots"] == [LOT]
    # Un Demandeur ne reçoit pas ce qui se décide par lots.
    d = await (await client.post("/api/pc_parental/veille", json={**ENFANT, "ecran": "telephone"})).json()
    assert d["a_decider_lots"] == []


async def test_une_erreur_ne_coupe_pas_l_accueil(hass, hass_storage, hass_client_no_auth, monkeypatch):
    await _monter(hass, hass_storage)
    from custom_components.pc_parental import parent

    def casse(*_a, **_k):
        raise KeyError("demande mal formée")

    monkeypatch.setattr(parent, "demandes_attente", casse)
    client = await hass_client_no_auth()
    r = await client.post("/api/pc_parental/veille", json={**PARENT, "ecran": "telephone"})
    assert r.status == 200
    d = await r.json()
    assert d["demandes"] == [] and d["demandes_total"] == 0
    assert "meteo" in d and "moi" in d and "widgets_disponibles" in d
