"""Essai des cartes servies par l'intégration, dans un vrai Home Assistant (banc officiel).

Ce qu'on vérifie :
- Home Assistant sert les fichiers de l'intégration à /pc_parental/frontend, sans
  /config/www ni ressource déclarée à la main ;
- chaque carte est chargée par l'interface, avec sa version dans l'adresse ;
- le panneau de l'écran de veille prend son adresse au même endroit ;
Sans l'interface de Home Assistant (dépendance facultative), Vision démarre quand même : c'est
ce que montre essai/securite_test.py, dont le banc n'a pas d'interface.

Banc : le même que essai/securite_test.py.
    python -m pytest essai/cartes_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import pathlib
import shutil
import sys
import tempfile

import pytest
from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-cartes-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
CARTES = ("vision-etiquettes-card.js", "vision-planning-card.js", "vision-processus-card.js",
          "vision-maison-card.js", "vision-veille-card.js", "vision-dashboards-card.js", "vision-widgets-card.js", "vision-apparence-card.js",
          "vision-messages-card.js")


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


@pytest.fixture
def chargees(monkeypatch):
    """Ce que Vision demande à l'interface de charger (l'interface elle-même n'est pas au banc)."""
    from homeassistant.components import frontend

    vues: list[str] = []
    monkeypatch.setattr(frontend, "add_extra_js_url", lambda hass, url, es5=False: vues.append(url))
    return vues


async def _monter(hass: HomeAssistant):
    hass.states.async_set("person.parent", "home", {"friendly_name": "Parent"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree


async def test_fichiers_servis_par_l_integration(hass, hass_client_no_auth, chargees):
    await _monter(hass)
    client = await hass_client_no_auth()
    for nom in CARTES + ("vision-veille-panel.js", "vision-veille-coeur.js"):
        r = await client.get(f"/pc_parental/frontend/{nom}")
        assert r.status == 200, nom
        assert "customElements.define" in await r.text(), nom
    assert (await client.get("/pc_parental/frontend/../manifest.json")).status in (403, 404)


async def test_cartes_jamais_dans_la_page(hass, chargees):
    """1.97.3 : la page de Home Assistant est gardée par le navigateur ; une carte qu'elle chargerait serait
    l'ancienne après une mise à jour (et prendrait la place de la nouvelle). Les cartes passent par les
    ressources Lovelace seulement (test_ressources_lovelace_versionnees)."""
    await _monter(hass)
    assert not [u for u in chargees if "vision-" in u and "-card.js" in u]


async def test_panneau_au_meme_endroit(hass, chargees):
    await _monter(hass)
    module = sys.modules["custom_components.pc_parental.veille_page"]
    adresse = module._module(hass)
    assert adresse.startswith("/pc_parental/frontend/vision-veille-panel.js?v=") and not adresse.endswith("?v=0")



async def test_ressources_lovelace_versionnees(hass, chargees):
    """1.86.2 : une ressource Lovelace par carte, à l'adresse versionnée ; une ancienne adresse est mise à jour."""
    from custom_components.pc_parental import cartes

    class Ressources:
        loaded = True

        def __init__(self):
            self.items = [{"id": "x1", "res_type": "module", "url": "/pc_parental/frontend/vision-maison-card.js?v=ancienne"},
                          {"id": "x2", "res_type": "module", "url": "/hacsfiles/autre/autre.js"}]

        def async_items(self):
            return list(self.items)

        async def async_create_item(self, d):
            self.items.append({"id": f"n{len(self.items)}", **d})

        async def async_update_item(self, ident, d):
            next(i for i in self.items if i["id"] == ident).update(d)

        async def async_delete_item(self, ident):
            self.items = [i for i in self.items if i["id"] != ident]

    class Lovelace:
        resources = Ressources()

    await _monter(hass)
    hass.data["lovelace"] = Lovelace()
    n = await cartes.async_ressources(hass)
    urls = {i["url"].split("?")[0]: i["url"] for i in Lovelace.resources.items}
    for c in CARTES:
        # 2.2.13 : la version est l'empreinte du fichier (essai/cartes_version_test.py).
        assert urls[f"/pc_parental/frontend/{c}"] == cartes.adresse(hass, c)
        assert urls[f"/pc_parental/frontend/{c}"].startswith(f"/pc_parental/frontend/{c}?v=")
    assert urls["/hacsfiles/autre/autre.js"] == "/hacsfiles/autre/autre.js"  # rien d'autre n'est touché
    assert n == len(CARTES)
    assert await cartes.async_ressources(hass) == 0  # déjà à jour : rien ne change
