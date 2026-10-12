"""Version des adresses des cartes et des panneaux (2.2.13), dans un vrai Home Assistant (banc officiel).

Ce qu'on vérifie :
- la version d'un fichier est l'empreinte de son contenu : elle change quand le contenu change, et seulement
  alors (date de modification, dossier, version de Vision : sans effet) ;
- l'empreinte d'un fichier couvre ce qu'il importe : une dépendance modifiée change l'adresse de l'importeur ;
  la porte du panneau de l'écran de veille couvre son cœur, et lui passe sa version ;
- ressources Lovelace : une par carte, à jour, doublons ramenés à un, fichier disparu retiré, rien hors de
  /pc_parental/frontend ; en YAML, rien n'est touché ;
- rechargement de l'intégration sans redémarrage : versions relues, ressources et panneau à la nouvelle adresse ;
- lecture du source : aucune adresse de carte ou de panneau fabriquée sans version.

Banc : le même que essai/cartes_test.py.
    python -m pytest essai/cartes_version_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import logging
import os
import pathlib
import re
import shutil
import sys
import tempfile

import pytest
from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-cartes-version-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
PREFIXE = "/pc_parental/frontend/"


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


def _cartes():
    from custom_components.pc_parental import cartes

    return cartes


async def _monter(hass: HomeAssistant):
    hass.states.async_set("person.decideur", "home", {"friendly_name": "Décideur"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree


class Ressources:
    """Les ressources Lovelace en mode stockage (interface de la collection de Home Assistant)."""

    loaded = True

    def __init__(self, items):
        self.items = [dict(i) for i in items]
        self.n = 0

    def async_items(self):
        return [dict(i) for i in self.items]

    async def async_create_item(self, d):
        self.n += 1
        self.items.append({"id": f"n{self.n}", **d})

    async def async_update_item(self, ident, d):
        next(i for i in self.items if i["id"] == ident).update(d)

    async def async_delete_item(self, ident):
        self.items = [i for i in self.items if i["id"] != ident]


class RessourcesYaml:
    """En YAML : lecture seule (pas d'async_create_item)."""

    def __init__(self, items):
        self.items = items

    def async_items(self):
        return list(self.items)


class Lovelace:
    def __init__(self, ressources):
        self.resources = ressources


# --- Empreintes (logique pure, sur un dossier à part) ------------------------------------------------------


def _ecrire(dossier: pathlib.Path, nom: str, texte: str) -> None:
    (dossier / nom).write_text(texte, encoding="utf-8")


def test_empreinte_suit_le_contenu_et_lui_seul(tmp_path):
    cartes = _cartes()
    _ecrire(tmp_path, "a.js", "customElements.define('a-x', class extends HTMLElement {});\n")
    _ecrire(tmp_path, "b.js", "console.log('b');\n")
    v1 = cartes.versions(str(tmp_path))
    assert set(v1) == {"a.js", "b.js"} and all(re.fullmatch(r"[0-9a-f]{12}", v) for v in v1.values())
    # Même contenu, autre date de modification : même version.
    os.utime(tmp_path / "a.js", (1, 1))
    assert cartes.versions(str(tmp_path)) == v1
    # Même contenu recopié ailleurs (déploiement par copie) : même version.
    ailleurs = tmp_path / "copie"
    ailleurs.mkdir()
    shutil.copy(tmp_path / "a.js", ailleurs / "a.js")
    shutil.copy(tmp_path / "b.js", ailleurs / "b.js")
    assert cartes.versions(str(ailleurs)) == v1
    # Le contenu change : la version de ce fichier change, pas celle de l'autre.
    _ecrire(tmp_path, "a.js", "customElements.define('a-x', class extends HTMLElement { x() {} });\n")
    v2 = cartes.versions(str(tmp_path))
    assert v2["a.js"] != v1["a.js"] and v2["b.js"] == v1["b.js"]
    # Retour au contenu d'avant : la version d'avant.
    _ecrire(tmp_path, "a.js", "customElements.define('a-x', class extends HTMLElement {});\n")
    assert cartes.versions(str(tmp_path)) == v1


def test_dependance_modifiee_change_l_importeur(tmp_path):
    cartes = _cartes()
    _ecrire(tmp_path, "entree.js", 'import { f } from "./outil.js";\nf();\n')
    _ecrire(tmp_path, "dyn.js", "import('./coeur.js').then(() => 0);\n")
    _ecrire(tmp_path, "porte.js", "import(new URL(`coeur.js?v=${1}`, import.meta.url).href);\n")
    _ecrire(tmp_path, "outil.js", 'import "./profond.js";\nexport const f = () => 1;\n')
    _ecrire(tmp_path, "profond.js", "export const p = 1;\n")
    _ecrire(tmp_path, "coeur.js", "export const c = 1;\n")
    _ecrire(tmp_path, "seul.js", "// parle de outil.js dans un commentaire : pas un import\n")
    _ecrire(tmp_path, "boucle1.js", 'import "./boucle2.js";\n')
    _ecrire(tmp_path, "boucle2.js", 'import "./boucle1.js";\n')
    avant = cartes.versions(str(tmp_path))
    # Dépendance directe et de proche en proche.
    _ecrire(tmp_path, "profond.js", "export const p = 2;\n")
    apres = cartes.versions(str(tmp_path))
    assert apres["outil.js"] != avant["outil.js"]
    assert apres["entree.js"] != avant["entree.js"]
    assert apres["seul.js"] == avant["seul.js"] and apres["coeur.js"] == avant["coeur.js"]
    # Chargement dynamique et new URL(…, import.meta.url).
    _ecrire(tmp_path, "coeur.js", "export const c = 2;\n")
    encore = cartes.versions(str(tmp_path))
    assert encore["dyn.js"] != apres["dyn.js"] and encore["porte.js"] != apres["porte.js"]
    assert encore["entree.js"] == apres["entree.js"]
    # Une boucle d'imports ne bloque rien, et chacun suit l'autre.
    _ecrire(tmp_path, "boucle2.js", 'import "./boucle1.js";\n// change\n')
    b = cartes.versions(str(tmp_path))
    assert b["boucle1.js"] != encore["boucle1.js"] and b["boucle2.js"] != encore["boucle2.js"]


def test_porte_de_la_veille_couvre_son_coeur():
    cartes = _cartes()
    texte = (source / "frontend" / cartes.PANNEAU).read_text(encoding="utf-8")
    assert "vision-veille-coeur.js" in cartes.dependances(texte)
    # La porte passe sa propre version au cœur (sinon le cœur resterait en cache sous une adresse fixe).
    assert re.search(r"vision-veille-coeur\.js\?v=\$\{VERSION_PORTE\}", texte)
    assert 'searchParams.get("v")' in texte


# --- Dans Home Assistant ------------------------------------------------------------------------------------


async def test_adresses_versionnees_par_empreinte(hass):
    cartes = _cartes()
    from custom_components.pc_parental.const import VERSION

    await _monter(hass)
    lues = cartes.versions()
    for nom in cartes.CARTES + (cartes.PANNEAU,):
        a = cartes.adresse(hass, nom)
        assert a == f"{PREFIXE}{nom}?v={lues[nom]}"
        assert VERSION not in a.split("?v=")[1]  # plus la version de Vision : une carte inchangée garde son adresse
    module = sys.modules["custom_components.pc_parental.veille_page"]._module(hass)
    assert module == cartes.adresse(hass, cartes.PANNEAU)


async def test_ressources_doublons_et_nettoyage(hass, caplog):
    cartes = _cartes()
    await _monter(hass)
    maison = "vision-maison-card.js"
    voulue = cartes.adresse(hass, maison)
    items = [
        {"id": "d1", "res_type": "module", "url": f"{PREFIXE}{maison}?v=ancienne"},
        {"id": "d2", "res_type": "module", "url": f"{PREFIXE}{maison}"},
        {"id": "d3", "res_type": "module", "url": f"http://ha.local:8123{PREFIXE}{maison}?v=autre"},
        {"id": "w1", "res_type": "module", "url": f"{PREFIXE}vision-widgets-card.js?v=1"},
        {"id": "w2", "res_type": "module", "url": cartes.adresse(hass, "vision-widgets-card.js")},
        {"id": "x1", "res_type": "module", "url": f"{PREFIXE}vision-disparue-card.js?v=1"},
        {"id": "c1", "res_type": "module", "url": f"{PREFIXE}vision-veille-coeur.js"},
        {"id": "h1", "res_type": "module", "url": "/hacsfiles/autre/autre.js"},
        {"id": "h2", "res_type": "js", "url": "/local/pc_parental/frontend/a-moi.js"},
        {"id": "h3", "res_type": "module", "url": "https://exemple.org/pc_parental/frontendx/b.js"},
    ]
    r = Ressources(items)
    hass.data["lovelace"] = Lovelace(r)
    n = await cartes.async_ressources(hass)
    assert n > 0
    par_url: dict[str, list[dict]] = {}
    for i in r.items:
        par_url.setdefault(i["url"].split("?")[0].replace("http://ha.local:8123", ""), []).append(i)
    # Une seule ressource par carte, à la bonne adresse.
    for c in cartes.CARTES:
        siennes = par_url[f"{PREFIXE}{c}"]
        assert len(siennes) == 1, c
        assert siennes[0]["url"] == cartes.adresse(hass, c) and siennes[0]["res_type"] == "module"
    assert par_url[f"{PREFIXE}{maison}"][0]["url"] == voulue
    assert [i["id"] for i in par_url[f"{PREFIXE}vision-widgets-card.js"]] == ["w2"]  # celle déjà à jour est gardée
    # Fichier disparu : retiré. Autre fichier de Vision posé à la main : gardé, versionné.
    assert f"{PREFIXE}vision-disparue-card.js" not in par_url
    assert par_url[f"{PREFIXE}vision-veille-coeur.js"][0]["url"] == cartes.adresse(hass, "vision-veille-coeur.js")
    # Hors de /pc_parental/frontend : intact.
    for ident in ("h1", "h2", "h3"):
        assert next(i for i in r.items if i["id"] == ident) == next(i for i in items if i["id"] == ident)
    # Déjà à jour : rien ne change.
    assert await cartes.async_ressources(hass) == 0


async def test_ressources_yaml_intouchees(hass, caplog):
    cartes = _cartes()
    await _monter(hass)
    items = [{"id": "y1", "type": "module", "url": f"{PREFIXE}vision-maison-card.js?v=vieille"}]
    hass.data["lovelace"] = Lovelace(RessourcesYaml(items))
    with caplog.at_level(logging.WARNING):
        assert await cartes.async_ressources(hass) == 0
    assert items == [{"id": "y1", "type": "module", "url": f"{PREFIXE}vision-maison-card.js?v=vieille"}]
    assert cartes.adresse(hass, "vision-maison-card.js") in caplog.text  # on dit quoi mettre


async def test_rechargement_relit_les_versions(hass):
    """Une carte changée sur le disque, l'intégration rechargée sans redémarrer : nouvelles adresses partout."""
    cartes = _cartes()
    from homeassistant.components import frontend

    entree = await _monter(hass)
    r = Ressources([])
    hass.data["lovelace"] = Lovelace(r)
    assert await cartes.async_ressources(hass) == len(cartes.CARTES)
    widgets = "vision-widgets-card.js"
    avant = cartes.adresse(hass, widgets)
    panneau_avant = cartes.adresse(hass, cartes.PANNEAU)
    fichier = pathlib.Path(cartes.DOSSIER) / widgets
    coeur = pathlib.Path(cartes.DOSSIER) / "vision-veille-coeur.js"
    texte, texte_coeur = fichier.read_text(encoding="utf-8"), coeur.read_text(encoding="utf-8")
    try:
        fichier.write_text(texte + "\n// modifiée\n", encoding="utf-8")
        coeur.write_text(texte_coeur + "\n// modifié\n", encoding="utf-8")
        assert await hass.config_entries.async_reload(entree.entry_id)
        await hass.async_block_till_done()
        apres = cartes.adresse(hass, widgets)
        assert apres != avant
        # Ressource Lovelace suivie, toujours une seule.
        siennes = [i for i in r.items if i["url"].startswith(f"{PREFIXE}{widgets}")]
        assert [i["url"] for i in siennes] == [apres]
        # Le cœur a changé : la porte du panneau change d'adresse, et le panneau déclaré la prend.
        assert cartes.adresse(hass, cartes.PANNEAU) != panneau_avant
        panneau = hass.data.get(frontend.DATA_PANELS, {}).get("vision-veille")
        if panneau is not None:
            assert panneau.config["_panel_custom"]["module_url"] == cartes.adresse(hass, cartes.PANNEAU)
        # Les autres cartes n'ont pas bougé.
        assert all(i["url"] == cartes.adresse(hass, i["url"].split("?")[0].rsplit("/", 1)[1]) for i in r.items)
    finally:
        fichier.write_text(texte, encoding="utf-8")
        coeur.write_text(texte_coeur, encoding="utf-8")


async def test_panneau_appairage_versionne(hass):
    cartes = _cartes()
    from homeassistant.components import frontend

    await _monter(hass)
    panneau = hass.data.get(frontend.DATA_PANELS, {}).get("vision-appairage")
    assert panneau is not None
    v = cartes.versions(str(source / "panneau"))["vision-appairage-panel.js"]
    assert panneau.config["_panel_custom"]["module_url"] == f"/pc_parental_static/vision-appairage-panel.js?v={v}"


# --- Lecture du source ----------------------------------------------------------------------------------------


def test_aucune_adresse_de_carte_sans_version():
    """Côté Python : une adresse de fichier servi (cartes, panneaux) passe par cartes.adresse_versionnee.
    Côté JS : un fichier voisin importé porte une version (?v=)."""
    fautes = []
    for py in sorted(source.rglob("*.py")):
        texte = py.read_text(encoding="utf-8")
        for n, ligne in enumerate(texte.splitlines(), 1):
            if ligne.lstrip().startswith("#"):
                continue
            # Une adresse vers un .js servi par Vision, écrite à la main (avec ou sans ?v=).
            if re.search(r"""f?["'](\{URL\}|\{STATIQUE\}|/pc_parental/frontend|/pc_parental_static)/[^"']*\.js""", ligne):
                fautes.append(f"{py.name}:{n}: {ligne.strip()}")
            if "module_url" in ligne and "?v=" in ligne:
                fautes.append(f"{py.name}:{n}: {ligne.strip()}")
            if re.search(r"\?v=\{VERSION\}", ligne):
                fautes.append(f"{py.name}:{n}: {ligne.strip()}")
        if "add_extra_js_url(" in texte:
            fautes.append(f"{py.name}: add_extra_js_url (la page gardée en cache chargerait l'ancienne carte)")
    cartes = _cartes()
    for dossier in (source / "frontend", source / "panneau"):
        noms = {p.name for p in dossier.glob("*.js")}
        motif = re.compile(r"""(?:\bfrom\s*|\bimport\s*\(?\s*|\bnew\s+URL\s*\(\s*)[`'"](?:\./)?([\w.-]+\.js)([^`'"]*)""")
        for js in sorted(dossier.glob("*.js")):
            for nom, suite in motif.findall(js.read_text(encoding="utf-8")):
                if nom in noms and "?v=" not in suite:
                    fautes.append(f"{js.name}: importe {nom} sans version")
                if nom in noms:
                    assert nom in cartes.dependances(js.read_text(encoding="utf-8"))
    assert not fautes, "\n".join(fautes)
