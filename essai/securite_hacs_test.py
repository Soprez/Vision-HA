"""2.5.12.14.1 : retours de sécurité de la revue HACS (hacs/default#12284).

- Le lien joint à une demande n'est enregistré que s'il est http(s) (« javascript: », « data: »… refusés), que la demande
  vienne de l'agent PC, de l'appli ou d'une ancienne donnée ; la carte Maison, elle, ne le rend cliquable que s'il est http(s)
  (même une donnée déjà enregistrée) : l'essai en navigateur est dans clics_test.py (« Sécurité — … »).
- Le titre de la carte Planning (qui porte le nom de l'appareil, choisi par l'appareil) est échappé.

    python -m pytest essai/securite_hacs_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pathlib

import pytest

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry

CARTES = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental" / "frontend"


@pytest.mark.parametrize("brut, attendu", [
    ("https://www.exemple.fr/cours?a=1#b", "https://www.exemple.fr/cours?a=1#b"),
    ("http://exemple.fr", "http://exemple.fr"),
    ("  HTTPS://exemple.fr/x  ", "HTTPS://exemple.fr/x"),
    ("javascript:alert(1)", ""),
    ("JaVaScRiPt:alert(1)", ""),
    ("  javascript:alert(1)", ""),
    ("java\nscript:alert(1)", ""),
    ("data:text/html,<script>alert(1)</script>", ""),
    ("vbscript:msgbox(1)", ""),
    ("file:///etc/passwd", ""),
    ("ftp://exemple.fr/x", ""),
    ("//exemple.fr/x", ""),
    ("https:/exemple.fr", ""),
    ("https://", ""),
    ("https://exemple.fr/ un espace", ""),
    ("https://exemple.fr/\x00", ""),
    ("https://" + "a" * 400 + ".fr", ""),
    ("", ""),
    (None, ""),
])
def test_lien_sur(brut, attendu):
    from custom_components.pc_parental.demandes import lien_sur

    assert lien_sur(brut) == attendu


async def test_demande_sans_lien_dangereux(hass, hass_storage, hass_client):
    nolan = _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android", role="parent")
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "pcs": {"e1": _fiche("e1", "PC-CAMILLE", ENFANT["secret"], person="person.camille"), "p1": nolan}}}
    hass.states.async_set("person.camille", "home", {"friendly_name": "Camille"})
    hass.states.async_set("person.nolan", "home", {"friendly_name": "Nolan"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    client = await hass_client()
    from custom_components.pc_parental import parent

    for nom, lien, garde in (("exemple-un.fr", "javascript:alert(1)", ""), ("exemple-deux.fr", "data:text/html,x", ""),
                             ("exemple-trois.fr", "https://www.exemple.fr/cours", "https://www.exemple.fr/cours")):
        r = await client.post("/api/pc_parental/demande", json={**ENFANT, "genre": "sites", "nom": nom, "lien": lien, "motif": "Pour les cours"})
        assert (await r.json())["ok"] is True, nom
    await hass.async_block_till_done()
    stockees = {d["cle"]: d["lien"] for d in coord.store.pcs["e1"]["demandes"]}
    assert stockees == {"exemple-un.fr": "", "exemple-deux.fr": "", "exemple-trois.fr": "https://www.exemple.fr/cours"}
    # Ni le message du Décideur (appareil, notification) ne porte le lien dangereux.
    for msg in coord.store.pcs["p1"].get("messages", []):
        assert "javascript:" not in str(msg) and "data:text" not in str(msg)
    # Ce que la carte reçoit : le lien dangereux n'y est plus.
    etat = parent.etat_maison(hass, coord, "p1")
    assert {d["cle"]: d["lien"] for d in etat["demandes"] if d["pc"] == "e1"} == stockees


def test_titre_de_la_carte_planning_echappe():
    src = (CARTES / "vision-planning-card.js").read_text(encoding="utf-8")
    assert '<div class="titre">${titre}</div>' not in src
    assert '<div class="titre">${echap(titre)}</div>' in src


def test_lien_de_la_demande_http_seulement_dans_la_carte_maison():
    src = (CARTES / "vision-maison-card.js").read_text(encoding="utf-8")
    assert '<a href="${esc(d.lien)}"' not in src
    assert "/^https?:" in src and "lienSur" in src
