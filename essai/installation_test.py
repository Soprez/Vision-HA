"""Essai de « Installer Vision sur un appareil », dans un vrai Home Assistant (banc officiel).

Ce qu'on vérifie :
- la dernière version Android publiée est servie à une adresse versionnée, un ancien
  lien mène à la dernière version, la première appli (autre paquet) n'est plus servie ;
- le QR encode ce lien, et rien d'autre ;
- la section (tableau de bord et Vision → Configurer) ne contient jamais la clé ;
- chaque appareil se voit proposer la version publiée, ou l'essai s'il en fait partie (jamais un essai périmé).

Banc : le même que essai/securite_test.py.
    python -m pytest essai/installation_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import hashlib
import io
import json
import pathlib
import shutil
import sys
import tempfile

import pytest
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-installation-"))
_dest = (_banc / "custom_components").joinpath(source.name)
shutil.copytree(source, _dest, ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")

# L'appli publiée (1.50.0) et un essai (1.51.0) pour deux appareils. Les fichiers de la première appli
# (vision.apk / vision.json, autre identifiant de paquet) ne sont plus servis depuis la 2.5.8.
_android = _dest / "android"
_android.mkdir()
NOUVELLE = b"APK org.soprez.vision 1.50.0"
ESSAI = b"APK org.soprez.vision 1.51.0"
ANCIENNE = b"APK de la premiere appli 1.48.0"
(_android / "installation.apk").write_bytes(NOUVELLE)
(_android / "installation.json").write_text(json.dumps(
    {"version": "1.50.0", "sha256": hashlib.sha256(NOUVELLE).hexdigest()}))
(_android / "essai.apk").write_bytes(ESSAI)
(_android / "essai.json").write_text(json.dumps(
    {"version": "1.51.0", "sha256": hashlib.sha256(ESSAI).hexdigest(), "appareils": ["Google Pixel 9", "Télé"]}))
(_android / "vision.apk").write_bytes(ANCIENNE)
(_android / "vision.json").write_text(json.dumps(
    {"version": "1.48.0", "sha256": hashlib.sha256(ANCIENNE).hexdigest()}))

sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
BASE = "https://maison.exemple.org"


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    await hass.config.async_update(external_url=BASE)
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


async def _monter(hass: HomeAssistant):
    hass.states.async_set("person.parent", "home", {"friendly_name": "Parent"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree, hass.data[DOMAIN][entree.entry_id]


def _module(nom):
    return sys.modules[f"custom_components.pc_parental.{nom}"]


async def test_lien_versionne_et_ancien_lien(hass, hass_client_no_auth):
    await _monter(hass)
    client = await hass_client_no_auth()
    r = await client.get("/api/pc_parental/android/vision-1.50.0.apk")
    assert r.status == 200 and await r.read() == NOUVELLE
    assert r.headers["Content-Type"] == "application/vnd.android.package-archive"
    assert "vision-1.50.0.apk" in r.headers["Content-Disposition"]
    # Un lien d'une version précédente (QR imprimé, message) mène à la dernière.
    r = await client.get("/api/pc_parental/android/vision-1.49.1.apk", allow_redirects=False)
    assert r.status == 302 and r.headers["Location"] == "/api/pc_parental/android/vision-1.50.0.apk"
    # Le lien stable : la dernière version, sans redirection.
    r = await client.get("/api/pc_parental/android/vision.apk", allow_redirects=False)
    assert r.status == 200 and await r.read() == NOUVELLE
    assert "vision-1.50.0.apk" in r.headers["Content-Disposition"]
    # La première appli n'a plus de canal : ses fichiers, même restés sur le disque, ne sont pas servis.
    assert _module("android_maj").infos(hass)["path"] == "/api/pc_parental/android/vision-1.50.0.apk"
    # Rien d'autre n'est servi.
    for nom in ("ancienne.apk", "vision.json", "vision-..%2Fmanifest.json.apk", "installation.json.apk",
                "vision-1.50.0.apk.json"):
        assert (await client.get(f"/api/pc_parental/android/{nom}", allow_redirects=False)).status in (302, 400, 404)


async def test_qr_du_lien(hass, hass_client_no_auth):
    import pyqrcode

    await _monter(hass)
    lien = _module("installation").liens(hass)
    assert lien["android"] == f"{BASE}/api/pc_parental/android/vision.apk"
    r = await (await hass_client_no_auth()).get("/api/pc_parental/installation/qr.svg?cible=autre-chose")
    assert r.status == 200 and r.headers["Content-Type"].startswith("image/svg+xml")
    attendu = io.BytesIO()
    pyqrcode.create(lien["android"], error="M").svg(
        attendu, scale=6, quiet_zone=2, background="#ffffff", module_color="#000000",
        xmldecl=False, svgns=True,
    )
    assert await r.read() == attendu.getvalue()


async def test_section_sans_cle(hass):
    _entree, coord = await _monter(hass)
    cle = coord.store.enroll_key
    assert cle
    texte = _module("installation").markdown(hass)
    assert "Installer Vision sur un appareil" in texte
    assert f"{BASE}/api/pc_parental/android/vision.apk\n" in texte and "vision-1.50.0.apk" not in texte
    assert f"{BASE}/api/pc_parental/installation/qr.svg?v=1.50.0" in texte
    assert f"irm {BASE}/api/pc_parental/client/install " in texte
    assert cle not in texte and "-Cle" not in texte
    section = json.dumps(_module("dashboard")._section_installation(hass, coord), ensure_ascii=False)
    assert "/api/pc_parental/android/vision.apk" in section and cle not in section and "/local/vision.apk" not in section


async def test_configurer_installer(hass):
    entree, coord = await _monter(hass)
    r = await hass.config_entries.options.async_init(entree.entry_id)
    assert r["type"] == FlowResultType.MENU and "autres" in r["menu_options"]
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "reparer"})
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "installer"})
    assert r["type"] == FlowResultType.FORM and r["step_id"] == "installer"
    texte = r["description_placeholders"]["installation"]
    assert "/api/pc_parental/android/vision.apk" in texte and coord.store.enroll_key not in texte
    r = await hass.config_entries.options.async_configure(r["flow_id"], {})
    assert r["type"] == FlowResultType.CREATE_ENTRY


async def test_la_mise_a_jour_de_chaque_appareil(hass):
    await _monter(hass)
    maj = _module("android_maj")
    assert maj.infos_pour(hass, "Pixel", "1.49.1")["version"] == "1.50.0"
    assert maj.infos_pour(hass, "Pixel", "1.50.0")["path"].endswith("vision-1.50.0.apk")
    assert maj.infos_pour(hass, "Inconnu", "")["version"] == "1.50.0"
    # Les appareils d'essai reçoivent l'essai.
    assert maj.infos_pour(hass, "Google Pixel 9", "1.50.0")["version"] == "1.51.0"
    assert maj.infos_pour(hass, "Télé", "1.49.0")["path"] == "/api/pc_parental/android/essai.apk?v=1.51.0"


async def test_essai_perime_ignore(hass):
    """Un essai plus ancien que la version publiée (l'essai 1.47.0 de la première appli, resté sur l'installation
    de référence) n'est jamais proposé : il installerait l'ancienne appli à côté de l'actuelle."""
    await _monter(hass)
    maj = _module("android_maj")
    sauve = maj._ETAT["essai"]
    maj._ETAT["essai"] = {"version": "1.47.0", "sha256": "ab", "appareils": {"google pixel 9", "télé"}}
    try:
        assert maj.infos_pour(hass, "Google Pixel 9", "1.50.0")["version"] == "1.50.0"
        assert maj.infos_pour(hass, "Télé", "1.49.0", "t1", ["t1"])["version"] == "1.50.0"
    finally:
        maj._ETAT["essai"] = sauve


async def test_lien_stable_sans_appli_publiee(hass, hass_client_no_auth):
    """Rien de publié : le lien stable ne sert rien (plus de repli sur la première appli)."""
    await _monter(hass)
    maj = _module("android_maj")
    sauve = dict(maj._ETAT)
    maj._ETAT["installation"] = {}
    try:
        r = await (await hass_client_no_auth()).get("/api/pc_parental/android/vision.apk")
        assert r.status == 404
        assert _module("installation").liens(hass)["android"] == ""
    finally:
        maj._ETAT.update(sauve)
