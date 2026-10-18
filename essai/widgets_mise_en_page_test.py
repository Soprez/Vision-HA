"""Widgets : la mise en page (2.5.3, l'auteur, version suivante).

- par entité : alignement horizontal et vertical du titre et de la valeur, taille de la valeur (« remplir » ou
  S / M / L / XL), titre et icône montrés ou non ; défaut : valeur grande (« remplir ») et centrée, titre et icône ;
- la même configuration depuis l'onglet Widgets HA (administrateur) et depuis le téléphone d'un Décideur
  (/widgets/mise_en_page, appareil qui pilote, entité qu'il voit) ; un Demandeur ne la change pas ;
- les appareils la reçoivent avec l'entité ; une valeur inconnue est refusée.

    python -m pytest essai/widgets_mise_en_page_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from widgets_test import (  # noqa: F401  (banc des widgets et ses fixtures)
    DOMAIN, ENFANT, PARENT, _monter, _post, expected_lingering_tasks, expected_lingering_timers, integrations,
)

ADMIN = "/api/pc_parental/widgets/admin"
DEFAUT = {"align_h": "centre", "align_v": "centre", "taille": "remplir", "titre": True, "icone": True}


def _salon(d):
    return next(e for e in d["entites"] if e["entity_id"] == "light.salon")


async def test_defaut_grande_et_centree(hass, integrations, hass_storage, hass_client):
    await _monter(hass, hass_storage)
    client = await hass_client()
    st, d = await _post(client, "/entites", PARENT)
    assert st == 200 and _salon(d)["mise_en_page"] == DEFAUT


async def test_depuis_ha_puis_depuis_le_telephone(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    client = await hass_client()
    # Onglet Widgets HA (administrateur) : une partie des clés, le reste garde sa valeur.
    r = await client.post(ADMIN, json={"op": "reglage", "entite": "light.salon", "mise_en_page": {"align_h": "gauche", "taille": "xl"}})
    assert r.status == 200, await r.text()
    assert coord.store.entites_reglages["light.salon"]["mise_en_page"] == {**DEFAUT, "align_h": "gauche", "taille": "xl"}
    # Le téléphone d'un Décideur lit la même configuration…
    st, d = await _post(client, "/entites", PARENT)
    assert _salon(d)["mise_en_page"] == {**DEFAUT, "align_h": "gauche", "taille": "xl"}
    version = d["version"]
    # … et la change : la même pour tous (et la version annoncée change, les appareils rechargent).
    st, d = await _post(client, "/mise_en_page", PARENT, entite="light.salon", mise_en_page={"titre": False, "align_v": "bas"})
    assert st == 200 and d["ok"], d
    attendu = {**DEFAUT, "align_h": "gauche", "taille": "xl", "titre": False, "align_v": "bas"}
    assert d["mise_en_page"] == attendu and d["version"] != version
    st, d = await _post(client, "/entites", ENFANT)
    salon = [e for e in d["entites"] if e["entity_id"] == "light.salon"]
    assert not salon or salon[0]["mise_en_page"] == attendu


async def test_un_demandeur_ne_change_rien(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    client = await hass_client()
    st, d = await _post(client, "/mise_en_page", ENFANT, entite="light.salon", mise_en_page={"taille": "s"})
    assert st == 403
    assert "mise_en_page" not in (coord.store.entites_reglages.get("light.salon") or {})
    # Sans appareil reconnu : refusé.
    st, _ = await _post(client, "/mise_en_page", {"id": "inconnu", "secret": "x"}, entite="light.salon", mise_en_page={"taille": "s"})
    assert st == 401


async def test_valeurs_refusees(hass, integrations, hass_storage, hass_client):
    _, coord = await _monter(hass, hass_storage)
    client = await hass_client()
    for faux in ({"taille": "xxl"}, {"align_h": "milieu"}, {"titre": "oui"}, {"couleur": "rouge"}, "remplir"):
        st, d = await _post(client, "/mise_en_page", PARENT, entite="light.salon", mise_en_page=faux)
        assert st == 400 and d["error"] == "reglage", (faux, d)
    assert "mise_en_page" not in (coord.store.entites_reglages.get("light.salon") or {})
    # Une entité que l'appareil ne voit pas : refusée.
    st, _ = await _post(client, "/mise_en_page", PARENT, entite="lock.porte_inexistante", mise_en_page={"taille": "s"})
    assert st == 403
