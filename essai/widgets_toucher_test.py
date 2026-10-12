"""Widgets : « Au toucher » (2.4.3, l'auteur : « accéder directement à la carte ou au dashboard »).

- par entité : action du rôle (défaut), détails de l'entité, tableau de bord / vue (chemin), rien ;
- le chemin est toujours une page de Home Assistant (« / » en tête, jamais une autre adresse ni « // ») ;
- droits : ouvrir les détails ou une vue demande le droit d'agir sur l'entité (Décideur, ou « tout le monde ») ;
  sinon le toucher retombe sur l'action du rôle, sans chemin ;
- la carte d'administration et l'éditeur d'un Décideur reçoivent la liste des tableaux de bord et des vues.

    python -m pytest essai/widgets_toucher_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pytest

from widgets_test import (  # noqa: F401  (banc des widgets et ses fixtures)
    DOMAIN, ENFANT, PARENT, _monter, _post, expected_lingering_tasks, expected_lingering_timers, integrations,
)

ADMIN = "/api/pc_parental/widgets/admin"


class _Tableau:
    def __init__(self, titre, vues):
        self.config = {"title": titre}
        self._vues = vues

    async def async_load(self, _force):
        return {"views": self._vues}


async def _admin(client, **corps):
    r = await client.post(ADMIN, json=corps)
    return r.status, await r.json()


def _lovelace(hass):
    hass.data["lovelace"] = {"dashboards": {
        None: _Tableau("Aperçu", [{"title": "Maison", "path": "maison"}, {"title": "Énergie"}]),
        "tableau-jardin": _Tableau("Jardin", [{"title": "Arrosage", "path": "arrosage"}]),
    }}


@pytest.mark.parametrize("chemin", ["https://exemple.invalid/x", "//exemple.invalid", "lovelace/0", "/a b", "/x\\y",
                                    "javascript:alert(1)", "/" + "a" * 250])
async def test_chemin_refuse(hass, integrations, hass_storage, hass_client, chemin):
    _, coord = await _monter(hass, hass_storage)
    client = await hass_client()
    st, d = await _admin(client, op="reglage", entite="light.salon", toucher="vue", chemin=chemin)
    assert st == 400 and d["error"] == "reglage", (chemin, d)
    assert "toucher" not in (coord.store.entites_reglages.get("light.salon") or {})


async def test_toucher_selon_les_droits(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    _lovelace(hass)
    client = await hass_client()
    # Sans réglage : l'action du rôle, comme avant.
    st, d = await _post(client, "/entites", PARENT)
    salon = next(e for e in d["entites"] if e["entity_id"] == "light.salon")
    assert (salon["toucher"], salon["chemin"], salon["ouvrir"]) == ("role", "", True)
    # Une vue sans chemin : refusée ; avec un chemin de la liste : gardée.
    st, d = await _admin(client, op="reglage", entite="light.salon", toucher="vue")
    assert st == 400
    st, d = await _admin(client, op="reglage", entite="light.salon", toucher="vue", chemin="/lovelace/maison")
    assert st == 200 and coord.store.entites_reglages["light.salon"]["toucher"] == "vue"
    assert {"chemin": "/tableau-jardin/arrosage", "titre": "Jardin › Arrosage"} in d["vues"]
    # Le Décideur ouvre la vue ; Lou (Demandeur, entité réservée aux Décideurs) garde l'action du rôle, sans chemin.
    st, d = await _post(client, "/entites", PARENT)
    salon = next(e for e in d["entites"] if e["entity_id"] == "light.salon")
    assert (salon["toucher"], salon["chemin"]) == ("vue", "/lovelace/maison")
    assert any(v["chemin"] == "/lovelace/0" or v["chemin"] == "/lovelace/1" for v in d["vues_ha"])
    st, d = await _post(client, "/entites", ENFANT)
    salon = next(e for e in d["entites"] if e["entity_id"] == "light.salon")
    assert (salon["toucher"], salon["chemin"], salon["ouvrir"]) == ("role", "", False)
    assert "vues_ha" not in d  # la liste des vues n'est donnée qu'à un Décideur
    # « Tout le monde » : Lou peut alors ouvrir la vue de cette entité.
    await _admin(client, op="reglage", entite="light.salon", role="interrupteur", qui="tous")
    st, d = await _post(client, "/entites", ENFANT)
    salon = next(e for e in d["entites"] if e["entity_id"] == "light.salon")
    assert (salon["toucher"], salon["chemin"]) == ("vue", "/lovelace/maison")
    # Détails, puis rien.
    await _admin(client, op="reglage", entite="light.salon", toucher="details")
    st, d = await _post(client, "/entites", ENFANT)
    assert next(e for e in d["entites"] if e["entity_id"] == "light.salon")["toucher"] == "details"
    await _admin(client, op="reglage", entite="light.salon", toucher="rien")
    st, d = await _post(client, "/entites", PARENT)
    assert next(e for e in d["entites"] if e["entity_id"] == "light.salon")["toucher"] == "rien"
    # Valeur inconnue : refusée.
    st, d = await _admin(client, op="reglage", entite="light.salon", toucher="ailleurs")
    assert st == 400


async def test_vues_de_la_carte_d_administration(hass, integrations, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    _lovelace(hass)
    client = await hass_client()
    d = await (await client.get(ADMIN)).json()
    chemins = [v["chemin"] for v in d["vues"]]
    assert "/lovelace" in chemins and "/lovelace/maison" in chemins and "/lovelace/1" in chemins
    assert "/tableau-jardin" in chemins and "/tableau-jardin/arrosage" in chemins
