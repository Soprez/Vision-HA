"""2.3.3 : un accord « app:minecraft » rouvre Minecraft Bedrock (Minecraft.Windows.exe), qui est un logiciel.

Avant, l'exécutable « minecraft.windows » n'était pas de la famille « minecraft » : l'accord restait sans effet, et son
point le faisait passer pour un site sur un HA qui ne l'avait pas encore vu.
"""
from __future__ import annotations

import json
import pathlib
import shutil
import sys
import tempfile

import pytest
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-minecraft-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config", "persistent_notification"):
        assert await async_setup_component(hass, composant, {})
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


async def test_accord_minecraft_rouvre_bedrock(hass, hass_storage):
    pc = {"id": "pc1", "name": "PC-ESSAI", "host": "PC-ESSAI", "secret": "s", "enabled": True, "rules": [],
          "bloques": ["minecraft"], "autorises": [], "override": None, "last_seen": 0, "agent": {}, "person": "",
          "update_asked": False, "tags": [], "messages": [], "next_message_id": 1}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {"pcs": {"pc1": pc}}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    store = hass.data[DOMAIN][entree.entry_id]
    store = getattr(store, "store", store)
    pc = store.pcs["pc1"]
    from custom_components.pc_parental.store import _nom_app

    assert _nom_app("Minecraft.Windows.exe") == "minecraft"
    assert store._genre_de("minecraft.windows") == "apps"
    bloques = store._effectif_calc(pc)["apps"]
    assert {"minecraft", "minecraft.windows", "minecraftlauncher"} <= set(bloques)
    store.laisser_passer_app(pc, "minecraft", 30)
    store._memos = {}
    bloques = store._effectif_calc(pc)["apps"]
    assert not {"minecraft", "minecraft.windows", "minecraftlauncher"} & set(bloques), bloques


async def test_demande_app_prefixe_est_un_logiciel(hass, hass_storage, hass_client_no_auth):
    """2.3.4 : une demande « app:minecraft » (ou sans genre) n'est plus rangée parmi les sites."""
    for composant in ("http",):
        assert await async_setup_component(hass, composant, {})
    pc = {"id": "pc1", "name": "PC-ESSAI", "host": "PC-ESSAI", "secret": "s", "enabled": True, "rules": [],
          "bloques": ["minecraft"], "autorises": [], "override": None, "last_seen": 0, "agent": {}, "person": "",
          "update_asked": False, "tags": [], "messages": [], "next_message_id": 1}
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {"pcs": {"pc1": pc}}}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    client = await hass_client_no_auth()
    for corps in ({"nom": "app:minecraft"}, {"nom": "Minecraft.Windows.exe"}):
        for _ in range(10):
            r = await client.post("/api/pc_parental/demande", json={"id": "pc1", "secret": "s", **corps})
            if r.status != 503:
                break
            await hass.async_block_till_done()
        assert r.status == 200, await r.text()
    store = hass.data[DOMAIN][entree.entry_id]
    store = getattr(store, "store", store)
    genres = {(d["genre"], d["cle"]) for d in store.pcs["pc1"].get("demandes") or []}
    assert genres == {("apps", "minecraft")}, genres
