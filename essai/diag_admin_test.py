"""Point 10 (1.97.7 / agent 1.88.0) : diagnostic des administrateurs Windows, en lecture seule.

L'agent dit qui est dans le groupe Administrateurs (et l'état de l'UAC) ; Home Assistant le garde, borné.

    python -m pytest essai/diag_admin_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, _monter, _integrations, expected_lingering_tasks, expected_lingering_timers,
)


async def test_diagnostic_garde_et_borne(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    membres = [{"nom": "Administrateur", "type": "Utilisateur", "source": "Local", "actif": False},
               {"nom": "User", "type": "Utilisateur", "source": "Local", "actif": True}] + \
              [{"nom": f"x{i}" * 50, "type": "Groupe", "source": "Local", "actif": None} for i in range(40)]
    r = await client.post("/api/pc_parental/poll", json={**ENFANT, "diag_admin": {"membres": membres, "uac": 1, "invite_admin": 5}})
    assert r.status == 200
    await hass.async_block_till_done()
    coord = next(iter(hass.data[DOMAIN].values()))
    d = coord.store.pcs["e1"]["diag_admin"]
    assert d["uac"] == 1 and d["invite_admin"] == 5 and len(d["membres"]) == 20
    assert d["membres"][1] == {"nom": "User", "type": "Utilisateur", "source": "Local", "actif": True}
    assert all(len(m["nom"]) <= 64 for m in d["membres"])
    # Un relevé sans diagnostic ne l'efface pas (l'agent ne l'envoie que toutes les 6 heures).
    await client.post("/api/pc_parental/poll", json={**ENFANT})
    assert coord.store.pcs["e1"]["diag_admin"]["uac"] == 1
