"""Refonte UX, lot 1 — zone cartes et tableau : ce que le tableau généré promet désormais.

- A7 : thème Vision contrasté, encre noire ou blanche sur les couleurs, point d'état doublé d'un mot, cibles.
- HA-23 : recherche de Protection rappelée et effaçable.
- HA-08 : « Tout fermer » (ex-pause générale) avec confirmation ; « Rouvrir ».
- A6 / HA-11 : textes du tableau neutres dans les six langues ; pastille « Règle du {lieu} ».
- HA-06 : un seul formulaire dans Messages (l'aide aux automatisations reste, repliée, exemple neutre).
- HA-04 : onglets écrits (sans icône), première vue « Utilisateur » (2.5.10 ; avant « Aujourd'hui ») au chemin « maison » inchangé.
- HA-02 : décisions en mots et lignes empilées sous 600 px (rendu vérifié par clics_test.py).
- HA-01 : plus aucune tuile qui supprime un appareil en un appui (ni le bouton, ni une cible préremplie à côté) ;
  le retrait passe par « Retirer… » dans la carte de la personne (le geste lui-même est vérifié dans un vrai
  navigateur par clics_test.py : aucun appel tant que le nom n'est pas tapé).

    python -m pytest essai/refonte_l1_cartes_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import pathlib
import re

import pytest
from homeassistant.helpers import entity_registry as er

from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from i18n_cartes_test import _tableau

ICI = pathlib.Path(__file__).parent
CARTES = ICI.parent / "custom_components" / "pc_parental" / "frontend"


def _vue(config, chemin):
    return next(v for v in config["views"] if v.get("path") == chemin)


async def test_ha01_aucune_suppression_en_un_appui(hass, hass_storage):
    _, _, config = await _tableau(hass, hass_storage, "fr")
    registre = er.async_get(hass)
    bouton = registre.async_get_entity_id("button", DOMAIN, f"{DOMAIN}_supprimer_pc")
    cible = registre.async_get_entity_id("select", DOMAIN, f"{DOMAIN}_pc_cible")
    assert bouton and cible  # les entités existent toujours (rien de renommé)
    texte = json.dumps(config, ensure_ascii=False)
    assert bouton not in texte, "le bouton qui supprime en un appui est encore dans le tableau"
    # La cible préremplie ne se montre plus à côté d'un retrait : l'onglet Appareils n'en parle plus.
    assert cible not in json.dumps(_vue(config, "appareils"), ensure_ascii=False)
    assert "Retirer…" in json.dumps(_vue(config, "appareils"), ensure_ascii=False)


def test_ha01_carte_retrait_confirme_par_le_nom():
    src = (CARTES / "vision-maison-card.js").read_text(encoding="utf-8")
    # Le service de toujours, appelé d'un seul endroit : le dialogue, après le nom vérifié.
    assert src.count('callService("pc_parental", "delete_pc"') == 1
    corps = src.split("async _retirer()", 1)[1].split("\n  }\n", 1)[0]
    assert "_nomConfirme(a, this._retrait.saisie)" in corps.split("callService", 1)[0]
    # Rien de prérempli : le dialogue s'ouvre avec une saisie vide.
    assert 'this._retrait = { id: b.dataset.retirer, saisie: "" }' in src


def test_ha02_decisions_en_mots():
    """HA-02 : les décisions de l'onglet Autorisations sont des mots, et la carte s'empile sous 600 px (le rendu à
    320 px, sans débordement et avec des boutons de 44 px, est vérifié dans un vrai navigateur par clics_test.py)."""
    src = (CARTES / "vision-etiquettes-card.js").read_text(encoding="utf-8")
    assert '"dec_fermer": "Fermer"' in src and '"dec_autoriser": "Autoriser"' in src and '"dec_plus_tard": "Plus tard"' in src
    assert "⛔" not in src.split("/*i18n-json:fin*/", 1)[1]
    assert "@container (max-width: 600px)" in src and "container-type: inline-size" in src


async def test_ha04_onglets_ecrits_et_aujourdhui(hass, hass_storage):
    """HA-04 : la barre d'onglets montre des mots (aucune icône sur les vues de Vision), la première s'appelle
    « Aujourd'hui » et garde son chemin « maison » ; les pages des personnes y reviennent toujours."""
    _, _, config = await _tableau(hass, hass_storage, "fr")
    barre = [v for v in config["views"] if not v.get("subview")]
    assert barre[0]["path"] == "maison" and barre[0]["title"] == "Utilisateur"  # 2.5.10 (l'auteur, retour 28)
    assert not any("icon" in v for v in barre)
    # Retours de l'auteur (15/10) : Autorisations et Protection rejoignent le menu du haut ; les fiches des personnes
    # reviennent à la page d'où l'on vient (pas de back_path imposé).
    # Décision de l'auteur (15/10) : plus d'onglets Personnes ni Appareils (Aujourd'hui les montre déjà).
    assert [v["title"] for v in barre] == ["Utilisateur", "Autorisations", "Protection", "Activité", "Réglages"]
    personnes = [v for v in config["views"] if v.get("subview") and v["path"].startswith("personne-")]
    assert personnes and not any("back_path" in v for v in personnes)


async def test_ha04_aujourdhui_en_allemand(hass, hass_storage):
    _, _, config = await _tableau(hass, hass_storage, "de")
    assert config["views"][0]["path"] == "maison" and config["views"][0]["title"] == "Benutzer"


async def test_ha06_un_seul_formulaire_de_message(hass, hass_storage):
    """HA-06 : l'onglet Messages n'a plus que la carte Vision ; l'ancien formulaire (Destinataire, Message, Son,
    Envoyer) n'y est plus, ses entités existent toujours ; l'aide aux automatisations reste, repliée, sans étiquette
    du lieu en exemple."""
    _, _, config = await _tableau(hass, hass_storage, "fr")
    vue = _vue(config, "message")
    texte = json.dumps(vue, ensure_ascii=False)
    assert texte.count("custom:vision-messages-card") == 1
    registre = er.async_get(hass)
    for plateforme, uid in (("select", "destinataire"), ("text", "saisie_message"), ("select", "son"), ("button", "envoyer")):
        eid = registre.async_get_entity_id(plateforme, DOMAIN, f"{DOMAIN}_{uid}")
        assert eid and eid not in texte, uid
    assert '"entities"' not in texte and "Envoyer un message depuis une automatisation" in texte
    assert "tag: salon" in texte


def _textes_lus(noeud):
    """Les textes qu'on lit dans le tableau : titres, en-têtes, noms et contenus (pas les chemins ni les types)."""
    if isinstance(noeud, list):
        for n in noeud:
            yield from _textes_lus(n)
    elif isinstance(noeud, dict):
        for cle, valeur in noeud.items():
            if cle in ("title", "heading", "name", "content") and isinstance(valeur, str):
                yield valeur
            else:
                yield from _textes_lus(valeur)


@pytest.mark.parametrize("langue", ["fr", "en", "de", "es", "it", "nl"])
async def test_a6_tableau_neutre(hass, hass_storage, langue):
    """A6 / HA-11 : aucun mot non neutre (maison, famille, parent, enfant, chez nous… et leurs équivalents) dans les
    textes du tableau généré, dans chacune des six langues."""
    from i18n_cartes_test import EXCEPTIONS_NEUTRES, NON_NEUTRES

    _, _, config = await _tableau(hass, hass_storage, langue)
    fautes = []
    for texte in _textes_lus(config["views"]):
        propre = texte
        for e in EXCEPTIONS_NEUTRES:
            propre = propre.replace(e, "")
        fautes += [m.group(0) for m in re.finditer(NON_NEUTRES[langue], propre, 0 if langue == "de" else re.I)]
    assert not fautes, fautes


def test_a6_pastille_regle_du_lieu():
    """A6 / HA-11 : la pastille de raison d'une catégorie fermée par une règle commune dit « Règle du {lieu} »."""
    src = (CARTES / "vision-maison-card.js").read_text(encoding="utf-8")
    assert '"code_lieu": "Règle du {lieu}"' in src
    assert 'maison: { libelle: "code_lieu"' in src


async def test_ha08_tout_fermer_demande_confirmation(hass, hass_storage):
    """HA-08 : « Tout fermer » (ex-pause générale) demande une confirmation avant d'appuyer ; « Rouvrir » non."""
    _, _, config = await _tableau(hass, hass_storage, "fr")
    registre = er.async_get(hass)
    fermer = registre.async_get_entity_id("button", DOMAIN, f"{DOMAIN}_pause_generale_30")
    rouvrir = registre.async_get_entity_id("button", DOMAIN, f"{DOMAIN}_pause_generale_fin")
    boutons = {}

    def parcourir(n):
        if isinstance(n, list):
            for x in n:
                parcourir(x)
        elif isinstance(n, dict):
            if n.get("type") == "button" and n.get("entity") in (fermer, rouvrir):
                boutons[n["entity"]] = n
            for v in n.values():
                parcourir(v)

    parcourir(_vue(config, "maison"))
    # Lot 2 (A8) : plus de bouton « Tout fermer 30 min » (l'entité reste, pour les automatisations) ; une durée au
    # choix, par le service, chacune confirmée en la répétant.
    # Faille du 16/10 : plus le bouton standard « Rouvrir » (button.press, sans code) : la carte Vision, code d'octroi.
    assert fermer not in boutons and rouvrir not in boutons
    assert '"mode": "rouvrir"' in json.dumps(_vue(config, "maison"))
    durees = []

    def chercher(n):
        if isinstance(n, list):
            for x in n:
                chercher(x)
        elif isinstance(n, dict):
            if n.get("type") == "button" and (n.get("tap_action") or {}).get("perform_action") == f"{DOMAIN}.pause_generale":
                durees.append(n)
            for v in n.values():
                chercher(v)

    chercher(_vue(config, "maison"))
    assert [b["tap_action"]["data"]["minutes"] for b in durees] == [15, 30, 60, 120]
    assert all("Tout fermer" in b["tap_action"]["confirmation"]["text"] for b in durees)
    assert "30 min" in durees[1]["tap_action"]["confirmation"]["text"]
    texte = json.dumps(_vue(config, "maison"), ensure_ascii=False)
    assert "Pause générale" not in texte and "repas" not in texte


async def test_ha23_recherche_jamais_reprise_et_oubliee_apres_10_min(hass, hass_storage):
    """HA-23 : une recherche d'hier ne revient pas au redémarrage ; sans saisie pendant 10 min, le champ se vide."""
    from datetime import timedelta

    from homeassistant.core import State
    from homeassistant.util import dt as dt_util
    from pytest_homeassistant_custom_component.common import async_fire_time_changed, mock_restore_cache

    mock_restore_cache(hass, [State("text.vision_chercher_dans_la_liste_noire", "exemple"),
                              State("text.vision_nom_du_nouvel_appareil", "garde")])
    await _tableau(hass, hass_storage, "fr")
    saisie = er.async_get(hass).async_get_entity_id("text", DOMAIN, f"{DOMAIN}_saisie_recherche")
    assert saisie == "text.vision_chercher_dans_la_liste_noire"
    assert hass.states.get(saisie).state == ""
    assert hass.states.get("text.vision_nom_du_nouvel_appareil").state == "garde"  # les autres saisies, si
    await hass.services.async_call("text", "set_value", {"entity_id": saisie, "value": "jeu"}, blocking=True)
    async_fire_time_changed(hass, dt_util.utcnow() + timedelta(minutes=9))
    await hass.async_block_till_done()
    assert hass.states.get(saisie).state == "jeu"
    await hass.services.async_call("text", "set_value", {"entity_id": saisie, "value": "jeux"}, blocking=True)
    async_fire_time_changed(hass, dt_util.utcnow() + timedelta(minutes=9))  # la saisie a relancé le délai
    await hass.async_block_till_done()
    assert hass.states.get(saisie).state == "jeux"
    async_fire_time_changed(hass, dt_util.utcnow() + timedelta(minutes=11))
    await hass.async_block_till_done()
    assert hass.states.get(saisie).state == ""


async def test_ha23_recherche_perimee_visible_et_effacable(hass, hass_storage):
    """HA-23 : la recherche de Protection rappelle le terme cherché et offre « Effacer la recherche » (qui vide le champ
    par text.set_value) quand le champ n'est pas vide."""
    _, _, config = await _tableau(hass, hass_storage, "fr")
    saisie = er.async_get(hass).async_get_entity_id("text", DOMAIN, f"{DOMAIN}_saisie_recherche")
    vue = _vue(config, "protection")
    conditionnelles = []

    def parcourir(n):
        if isinstance(n, list):
            for x in n:
                parcourir(x)
        elif isinstance(n, dict):
            if n.get("type") == "conditional":
                conditionnelles.append(n)
            for v in n.values():
                parcourir(v)

    parcourir(vue)
    effacer = next(c for c in conditionnelles if c["card"].get("name") == "Effacer la recherche")
    assert effacer["conditions"][0]["entity"] == saisie and "" in effacer["conditions"][0]["state_not"]
    action = effacer["card"]["tap_action"]
    assert action["perform_action"] == "text.set_value" and action["target"] == {"entity_id": saisie}
    assert action["data"] == {"value": ""}
    assert "Recherche en cours : « {{ q }} »" in json.dumps(vue, ensure_ascii=False)


def _lum(h):
    h = h.lstrip("#")
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4  # noqa: E731
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)


def _contraste(a, b):
    x, y = sorted((_lum(a), _lum(b)), reverse=True)
    return (x + 0.05) / (y + 0.05)


def _theme():
    modes, mode = {}, None
    for ligne in (ICI.parent / "custom_components" / "pc_parental" / "themes" / "vision.yaml").read_text(encoding="utf-8").splitlines():
        if m := re.match(r"^    (dark|light):\s*$", ligne):
            mode = modes.setdefault(m.group(1), {})
        elif (m := re.match(r'^      ([a-z0-9-]+):\s*"(#[0-9A-Fa-f]{6})"', ligne)) and mode is not None:
            mode[m.group(1)] = m.group(2)
    return modes


def test_a7_theme_vision_contrastes():
    """A7 : dans le thème Vision, alerte, succès, erreur et info se lisent en texte (≥ 4,5:1) sur la carte et sur le fond
    clair ; la bordure des cartes en sombre se voit (≥ 3:1 sur la carte et sur le fond)."""
    clair, sombre = _theme()["light"], _theme()["dark"]
    for cle in ("warning-color", "success-color", "error-color", "info-color", "primary-text-color", "secondary-text-color"):
        for fond in ("card-background-color", "primary-background-color"):
            assert _contraste(clair[cle], clair[fond]) >= 4.5, (cle, fond, _contraste(clair[cle], clair[fond]))
            assert _contraste(sombre[cle], sombre[fond]) >= 4.5, (cle, fond)
    for fond in ("card-background-color", "primary-background-color"):
        assert _contraste(sombre["ha-card-border-color"], sombre[fond]) >= 3, fond


def test_a7_planning_et_puces_encre_lisible():
    """A7 : le texte d'une plage (et d'une puce d'étiquette) est noir ou blanc selon sa couleur, toujours ≥ 4,5:1 sur
    la palette du planning et le nuancier des étiquettes ; les textes du planning font au moins 12 px."""
    planning = (CARTES / "vision-planning-card.js").read_text(encoding="utf-8")
    palette = re.findall(r'"(#[0-9a-f]{6})"', planning.split("const PALETTE = [", 1)[1].split("];", 1)[0])
    assert len(palette) == 10
    encre = lambda c: "#000000" if (_lum(c) + 0.05) / 0.05 >= 1.05 / (_lum(c) + 0.05) else "#FFFFFF"  # noqa: E731
    for c in palette:
        assert _contraste(encre(c), c) >= 4.5, c
    assert "el.style.color = encreSur(s.couleur)" in planning
    assert not re.search(r"font-size:\s*(10|11)px", planning)
    etiquettes = (CARTES / "vision-etiquettes-card.js").read_text(encoding="utf-8")
    nuancier = dict(re.findall(r'(\w+): "(#[0-9a-f]{6})"', etiquettes.split("const COULEURS = {", 1)[1].split("};", 1)[0]))
    assert encre(nuancier["yellow"]) == "#000000" and encre(nuancier["amber"]) == "#000000"  # puces jaunes : texte foncé
    for c in nuancier.values():
        assert _contraste(encre(c), c) >= 4.5, c
    assert "--encre:${this._encre(nom)}" in etiquettes


def test_a7_point_d_etat_double_d_un_mot_et_cibles():
    """A7 : le point d'état d'un appareil est doublé d'un mot (Ouvert, Fermé, Hors ligne) ; un appareil hors ligne
    n'est plus estompé (opacité) ; interrupteurs et cases d'au moins 24 et 20 px."""
    src = (CARTES / "vision-maison-card.js").read_text(encoding="utf-8")
    for cle, texte in (("etat_ouvert", "Ouvert"), ("etat_ferme", "Fermé"), ("etat_hors_ligne", "Hors ligne")):
        assert f'"{cle}": "{texte}"' in src
    assert 'return { texte: tr("etat_hors_ligne"), classe: "off" }' in src
    assert ".off { opacity" not in src
    assert ".inter { position:relative; width:44px; height:24px;" in src
    assert ".coche input { width:20px; height:20px;" in src
