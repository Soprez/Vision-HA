"""Le tableau de bord généré ne contient jamais de carte nulle ni sans type (1.85.1).

Une carte `null` (ligne `None` restée dans le générateur depuis la 1.52, vue « Écran de veille ») s'affichait
en « Erreur de configuration » dans l'appli compagnon. On construit le tableau pour une maison complète, avec
chaque préréglage de modules, et on parcourt toutes les vues.

    python -m pytest essai/tableau_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pytest

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, ENFANT, ENFANT2, PARENT, _fiche, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from pytest_homeassistant_custom_component.common import MockConfigEntry


def _defauts(noeud, chemin, sortie):
    """Les cartes nulles ou sans type, à toute profondeur."""
    if isinstance(noeud, dict):
        for cle in ("views", "sections", "cards", "badges"):
            v = noeud.get(cle)
            if isinstance(v, list):
                for i, x in enumerate(v):
                    ici = f"{chemin}/{cle}[{i}]"
                    if not isinstance(x, dict):
                        sortie.append(f"{ici} = {x!r}")
                    elif cle == "cards" and not x.get("type"):
                        sortie.append(f"{ici} sans type")
                    else:
                        _defauts(x, ici, sortie)
        if "card" in noeud:
            c = noeud["card"]
            if not isinstance(c, dict) or not c.get("type"):
                sortie.append(f"{chemin}/card = {c!r}")
            else:
                _defauts(c, chemin + "/card", sortie)
    return sortie


@pytest.mark.parametrize("prereglage", ["tout", "famille", "simple"])
async def test_aucune_carte_nulle(hass, hass_storage, prereglage):
    from custom_components.pc_parental import dashboard, modules

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True,
        "modules": {m: (m in modules.PRESETS[prereglage]) for m in modules.TOUS}, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant",
                         type="telephone", rules=[{"id": 1, "name": "Soir", "start": 72000, "end": 25200,
                                                   "weekdays": [True] * 7, "enabled": True}]),
            "e2": _fiche("e2", "PC-NOA", ENFANT2["secret"], person="person.noa"),
            "t1": _fiche("t1", "Télé du salon", "t" * 32, platform="android", type="tv"),
            "p1": _fiche("p1", "PIXEL-ROBIN", PARENT["secret"], person="person.robin", platform="android", role="parent"),
        }}}
    for p in ("lou", "noa", "robin"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = next(iter(hass.data[DOMAIN].values()))
    config = dashboard.build(hass, coord)
    assert config["views"], "aucune vue"
    assert _defauts(config, "", []) == []


def test_nettoyer_retire_les_cartes_nulles():
    from custom_components.pc_parental import dashboard

    sale = {"views": [{"sections": [{"type": "grid", "cards": [{"type": "heading"}, None, {"x": 1},
                                                               {"type": "vertical-stack", "cards": [None]}]}]}]}
    propre = dashboard.nettoyer(sale)
    assert propre["views"][0]["sections"][0]["cards"] == [{"type": "heading"}, {"type": "vertical-stack", "cards": []}]


async def test_refait_au_demarrage_si_la_structure_change(hass, hass_storage):
    """1.86.1 : au démarrage, le tableau est refait si son empreinte a changé, et seulement alors."""
    from unittest.mock import patch

    from custom_components.pc_parental import dashboard

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "signature_tableau": "ancienne", "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant"),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    appels = []

    async def faux_build(h, c):
        appels.append(1)
        return True

    with patch.object(dashboard, "async_build", faux_build):
        entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
        entree.add_to_hass(hass)
        assert await hass.config_entries.async_setup(entree.entry_id)
        await hass.async_block_till_done()
        coord = next(iter(hass.data[DOMAIN].values()))
        assert appels == [1] and coord.store.signature_tableau == dashboard.signature(coord)
        # Rechargement sans changement : pas de nouvelle construction.
        assert await hass.config_entries.async_reload(entree.entry_id)
        await hass.async_block_till_done()
        assert appels == [1]


async def test_pages_des_personnes(hass, hass_storage):
    """1.86.2 : une page pour qui a un appareil, un appareil partagé ou un rôle ; pas pour une personne sans rien."""
    from custom_components.pc_parental import dashboard

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "roles_personnes": {"person.robin": "parent"},
        "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant"),
            "t1": _fiche("t1", "Télé", "t" * 32, platform="android", type="tv", partage=["person.mia"]),
        }}}
    for p in ("lou", "mia", "robin", "affichage"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = next(iter(hass.data[DOMAIN].values()))
    chemins = [v.get("path") for v in dashboard.build(hass, coord)["views"]]
    assert {"personne-lou", "personne-mia"} <= set(chemins)
    assert "personne-affichage" not in chemins


async def test_type_dans_la_fiche_maison(hass, hass_storage):
    """1.86.6 : la fiche d'un appareil porte son type ; la carte Maison montre une télé en télé, pas en téléphone."""
    from custom_components.pc_parental import parent

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", type="telephone"),
            "e2": _fiche("e2", "PC-NOA", ENFANT2["secret"], person="person.noa"),
            "t1": _fiche("t1", "Télé du salon", "t" * 32, platform="android", type="tv"),
        }}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    coord = next(iter(hass.data[DOMAIN].values()))
    types = {i: parent._fiche(hass, coord, coord.store.pcs[i], [])["type"] for i in ("e1", "e2", "t1")}
    assert types == {"e1": "telephone", "e2": "pc", "t1": "tv"}
    carte = (__import__("pathlib").Path(parent.__file__).parent / "frontend" / "vision-maison-card.js").read_text(encoding="utf-8")
    assert 'tv: "mdi:television"' in carte and 'a.android ? "mdi:cellphone" : "mdi:desktop-classic"}' not in carte


def test_premiere_phrase():
    """1.96.0 (point 16.3) : l'aide en une phrase, le reste sous « En savoir plus »."""
    from custom_components.pc_parental.dashboard import premiere_phrase

    assert premiere_phrase("Une vraie notification, sur l'appareil choisi. Elle reste à l'écran jusqu'à ce qu'on la lise.") == (
        "Une vraie notification, sur l'appareil choisi.", "Elle reste à l'écran jusqu'à ce qu'on la lise.")
    assert premiere_phrase("| a | b |\n|---|---|") == ("", "| a | b |\n|---|---|")
    assert premiere_phrase("**Téléphone, tablette** : rien à faire. La suite.")[0] == "**Téléphone, tablette** : rien à faire."


async def test_simplification(hass, hass_storage):
    """1.96.0 (point 16.3) : pas de formulaire de plage sur les pages des personnes, des tuiles par appareil en
    tête de Protection, plus de titre « Comment ça marche », l'aide visible en une phrase."""
    import json

    from custom_components.pc_parental import dashboard

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant",
                         rules=[{"id": 1, "name": "Soir", "start": 72000, "end": 25200, "weekdays": [True] * 7, "enabled": True}]),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    vues = dashboard.build(hass, next(iter(hass.data[DOMAIN].values())))["views"]
    page = next(v for v in vues if v.get("path") == "personne-lou")
    assert "Ajouter une plage" not in json.dumps(page, ensure_ascii=False)
    protection = next(v for v in vues if v.get("path") == "protection")
    assert protection["sections"][0]["cards"][1] == {"type": "custom:vision-maison-card", "mode": "protections"}
    tout = json.dumps(vues, ensure_ascii=False)
    assert '"heading": "Comment ça marche"' not in tout and "<summary>Comment ça marche</summary>" not in tout


async def test_activite_en_barres_et_tuiles(hass, hass_storage):
    """1.97.0 (point 16.3) : Activité commence par le temps d'écran en barres ; ses listes deviennent des tuiles."""
    import json

    from custom_components.pc_parental import dashboard

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant"),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    vues = dashboard.build(hass, next(iter(hass.data[DOMAIN].values())))["views"]
    act = next(v for v in vues if v.get("path") == "statistiques")
    graphe = act["sections"][0]["cards"][1]
    assert graphe["type"] == "statistics-graph" and graphe["chart_type"] == "bar" and graphe["days_to_show"] == 7
    assert graphe["entities"] and all(e.startswith("sensor.") for e in graphe["entities"])
    texte = json.dumps(act, ensure_ascii=False)
    assert '"type": "entities"' not in texte and '"type": "tile"' in texte


async def test_onglets_vision_jamais_empiles():
    """1.97.1 : l'onglet Widgets HA s'empilait à chaque régénération (pris pour un onglet fait à la main)."""
    from custom_components.pc_parental import dashboard

    class Store:
        async def async_load(self, _force):
            return {"views": [
                {"path": "widgets", "title": "Widgets HA"}, {"path": "widgets", "title": "Widgets HA"},
                {"path": "accueil", "title": "Accueil"}, {"path": "accueil", "title": "Accueil"},
                {"path": "nouveau", "title": "Nouvel onglet Vision"}, {"path": "maison"},
                {"path": "personne-lou"}, {"title": "Sans chemin"}, {"title": "Sans chemin 2"},
            ]}

    gardees = await dashboard._a_la_main(Store(), {"nouveau"})
    assert [v.get("path") for v in gardees] == ["accueil", None, None]


# Les cartes natives de Home Assistant que le tableau Vision a le droit d'employer : une faute de frappe, un type
# disparu ou une carte personnalisée non chargée donnent « Erreur de configuration » à l'écran.
TYPES_NATIFS = {
    "entities", "tile", "grid", "heading", "markdown", "button", "vertical-stack", "horizontal-stack", "history-graph",
    "statistics-graph", "conditional", "glance", "gauge", "sensor", "entity", "todo-list", "calendar", "map", "iframe",
    "logbook", "picture", "picture-entity", "media-control", "thermostat", "light", "area", "weather-forecast",
}


async def test_chaque_vue_sans_carte_en_erreur(hass, hass_storage):
    """1.97.1 : toutes les vues générées, modules tous actifs : types connus, cartes Vision chargées comme ressources,
    chemins uniques, une icône parlante et distincte par onglet de la barre."""
    from custom_components.pc_parental import cartes, dashboard

    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android", role="enfant"),
            "p1": _fiche("p1", "PC-PARENT", PARENT["secret"], person="person.robin", role="parent"),
        }}}
    hass.states.async_set("person.lou", "home", {"friendly_name": "Lou"})
    hass.states.async_set("person.robin", "home", {"friendly_name": "Robin"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    vues = dashboard.build(hass, next(iter(hass.data[DOMAIN].values())))["views"]

    chargees = {c.removesuffix(".js") for c in cartes.CARTES}
    fautes: list[str] = []

    def parcourir(noeud, ou):
        if isinstance(noeud, list):
            for i, n in enumerate(noeud):
                parcourir(n, f"{ou}[{i}]")
        elif isinstance(noeud, dict):
            t = noeud.get("type")
            if isinstance(t, str) and ("cards" in noeud or "entity" in noeud or "entities" in noeud or "content" in noeud
                                       or t.startswith("custom:") or t in TYPES_NATIFS or ou.endswith("]")):
                if t.startswith("custom:"):
                    if t.removeprefix("custom:") not in chargees:
                        fautes.append(f"{ou} : carte {t} non chargée")
                elif t not in TYPES_NATIFS and t not in ("sections", "panel", "masonry", "sidebar", "grid"):
                    fautes.append(f"{ou} : type inconnu {t}")
            for c in ("cards", "card", "sections"):
                if c in noeud:
                    parcourir(noeud[c], f"{ou}.{c}")

    for v in vues:
        parcourir(v.get("sections") or v.get("cards") or [], v.get("path"))
    assert not fautes, fautes
    chemins = [v.get("path") for v in vues]
    assert len(chemins) == len(set(chemins)), chemins
    barre = [v for v in vues if not v.get("subview")]
    # Lot 1 (HA-04) : des onglets écrits. Home Assistant montre l'icône d'une vue à la place de son titre : les vues
    # de Vision n'en portent donc plus, et chacune a un titre à elle.
    titres = [v.get("title") for v in barre]
    assert not any(v.get("icon") for v in barre), [v.get("icon") for v in barre]
    assert all(titres) and len(titres) == len(set(titres)), titres
