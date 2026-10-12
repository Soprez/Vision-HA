"""Essai de la clé d'inscription (2.2.12) : plus aucune valeur en dur, dans un vrai Home Assistant.

Ce qu'on vérifie :
- installation neuve : une clé aléatoire (16 caractères hexadécimaux), différente à chaque fois, rien à signaler ;
- une clé connue (exemple publié des anciennes aides) ou faible est remplacée UNE fois au chargement,
  signalée à l'administrateur, et un appareil déjà inscrit continue de faire son relevé ;
- sursis de l'ancienne clé : elle ne vaut que pour un appareil déjà connu (réinscription, toujours
  soumise à l'accord d'un administrateur), jamais pour en créer un, et plus du tout après le délai ;
- une clé forte choisie par l'administrateur est conservée ;
- nouvelle_cle() refuse les exemples publiés et les clés faibles ;
- aucun exemple de clé dans le source (Python, PowerShell, cartes, traductions, docs).

Banc : le même que essai/securite_test.py.
    python -m pytest essai/cle_inscription_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import pathlib
import re
import shutil
import sys
import tempfile
import time

import pytest
from homeassistant.components import persistent_notification
from homeassistant.core import Context, HomeAssistant
from homeassistant.helpers import entity_registry as er
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"
_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-cle-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

DOMAIN = "pc_parental"
NOTE = f"{DOMAIN}_cle_inscription"
# Les exemples publiés par les anciennes aides (install.ps1) : connus de tous.
EXEMPLES = ("maison-2026", "vision-2026", "ma-cle-maison")
HEX16 = re.compile(r"^[0-9a-f]{16}$")


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


def _fiche(pc_id: str, nom: str, secret: str, **plus) -> dict:
    return {"id": pc_id, "name": nom, "host": nom, "secret": secret, "enabled": True, "rules": [],
            "bloques": [], "autorises": [], "override": None, "last_seen": 0, "agent": {},
            "person": "", "update_asked": False, "tags": [], "messages": [], "next_message_id": 1, **plus}


async def _monter(hass: HomeAssistant, hass_storage, donnees: dict | None = None):
    if donnees is not None:
        hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    hass.states.async_set("person.parent", "home", {"friendly_name": "Parent"})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree, hass.data[DOMAIN][entree.entry_id]


def _notes(hass: HomeAssistant) -> dict:
    return persistent_notification._async_get_or_create_notifications(hass)


# ------------------------------------------------------------- Installation neuve


async def test_installation_neuve_cle_aleatoire(hass, hass_storage):
    from custom_components.pc_parental.store import PcStore

    cles = set()
    for _ in range(5):
        hass_storage.pop(DOMAIN, None)
        store = PcStore(hass)
        await store.async_load()
        assert HEX16.match(store.enroll_key), store.enroll_key
        assert not store.cle_changee, "rien à signaler sur une installation neuve"
        cles.add(store.enroll_key)
    assert len(cles) == 5, "chaque installation tire sa propre clé"

    # Intégration complète : clé aléatoire enregistrée, génération à jour, aucune notification.
    hass_storage.pop(DOMAIN, None)
    _, coord = await _monter(hass, hass_storage)
    assert HEX16.match(coord.store.enroll_key)
    assert coord.store.enroll_key not in EXEMPLES
    assert hass_storage[DOMAIN]["data"]["enroll_key"] == coord.store.enroll_key
    assert hass_storage[DOMAIN]["data"]["enroll_key_v"] == 3
    assert NOTE not in _notes(hass)


# ------------------------------------------------------- Clé connue : migration douce


@pytest.mark.parametrize("vieille", [*EXEMPLES, "MAISON-2026", "court", "x" * 16])
async def test_cle_connue_ou_faible_regeneree_une_fois(hass, hass_storage, hass_client_no_auth, vieille):
    secret = "a" * 32
    _, coord = await _monter(hass, hass_storage, {
        "pcs": {"a1": _fiche("a1", "PC-SALON", secret, uid="uid-a1")},
        "enroll_key": vieille, "enroll_key_v": 2, "roles_migres": True, "enroll_open": True,
    })
    neuve = coord.store.enroll_key
    assert neuve != vieille and HEX16.match(neuve)
    donnees = hass_storage[DOMAIN]["data"]
    assert donnees["enroll_key"] == neuve and donnees["enroll_key_v"] == 3
    # Signalée à l'administrateur, avec un texte neutre.
    note = _notes(hass).get(NOTE)
    assert note is not None, "le remplacement doit être signalé"
    for mot in ("famille", "maison", "parent", "enfant"):
        assert mot not in (note["title"] + note["message"]).lower()
    assert neuve not in note["message"], "la clé ne s'affiche pas dans une notification"

    # L'appareil déjà inscrit continue de faire son relevé avec son propre secret.
    client = await hass_client_no_auth()
    r = await client.post("/api/pc_parental/poll", json={"id": "a1", "secret": secret})
    assert r.status == 200
    assert coord.store.pcs["a1"]["secret"] == secret

    # La nouvelle clé inscrit un nouvel appareil ; l'ancienne, jamais.
    r = await client.post("/api/pc_parental/enroll", json={"key": vieille, "host": "PC-NEUF", "uid": "uid-neuf"})
    assert r.status == 403
    assert len(coord.store.pcs) == 1

    # Une seule fois : au chargement suivant, la clé neuve est gardée et rien n'est signalé.
    persistent_notification.async_dismiss(hass, NOTE)
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    assert await hass.config_entries.async_reload(entree.entry_id)
    await hass.async_block_till_done()
    assert hass.data[DOMAIN][entree.entry_id].store.enroll_key == neuve
    assert NOTE not in _notes(hass)


async def test_sursis_de_l_ancienne_cle_pour_un_appareil_connu(hass, hass_storage, hass_client_no_auth):
    vieille = EXEMPLES[0]
    _, coord = await _monter(hass, hass_storage, {
        "pcs": {"a1": _fiche("a1", "TEL-A", "b" * 32, uid="uid-a1")},
        "enroll_key": vieille, "enroll_key_v": 2, "roles_migres": True, "enroll_open": True,
    })
    client = await hass_client_no_auth()
    corps = {"key": vieille, "host": "TEL-A", "uid": "uid-a1", "platform": "android"}

    # Appareil connu, ancienne clé, sans accord : le chemin ordinaire (409 + notification), pas 403.
    r = await client.post("/api/pc_parental/enroll", json=corps)
    assert r.status == 409
    assert coord.store.pcs["a1"]["secret"] == "b" * 32

    # Un administrateur permet la réinscription : l'ancienne clé suffit encore pour cet appareil-là.
    admin = await hass.auth.async_create_user("Admin", group_ids=["system-admin"])
    bouton = er.async_get(hass).async_get_entity_id("button", DOMAIN, f"{DOMAIN}_reinscription_a1")
    await hass.services.async_call("button", "press", {"entity_id": bouton}, blocking=True,
                                   context=Context(user_id=admin.id))
    r = await client.post("/api/pc_parental/enroll", json=corps)
    assert r.status == 200
    rep = await r.json()
    assert rep["id"] == "a1" and rep["created"] is False and rep["secret"] != "b" * 32
    assert len(coord.store.pcs) == 1

    # Après le sursis : plus rien.
    coord.store.ouvrir_reinscription("a1")
    coord.store.enroll_key_ancienne_jusqua = time.time() - 1
    r = await client.post("/api/pc_parental/enroll", json=corps)
    assert r.status == 403


async def test_admin_qui_change_la_cle_annule_le_sursis(hass, hass_storage):
    _, coord = await _monter(hass, hass_storage, {
        "pcs": {"a1": _fiche("a1", "PC-A", "c" * 32)},
        "enroll_key": EXEMPLES[1], "enroll_key_v": 2, "roles_migres": True,
    })
    assert coord.store.check_ancienne_cle(EXEMPLES[1], "PC-A")
    coord.store.nouvelle_cle("")
    assert not coord.store.check_ancienne_cle(EXEMPLES[1], "PC-A")


# ------------------------------------------------------------- Clé forte conservée


async def test_cle_forte_choisie_conservee(hass, hass_storage):
    forte = "Tq7-wr2Z-pk94-Lm"
    _, coord = await _monter(hass, hass_storage, {
        "pcs": {}, "enroll_key": forte, "enroll_key_v": 2, "roles_migres": True,
    })
    assert coord.store.enroll_key == forte
    assert hass_storage[DOMAIN]["data"]["enroll_key"] == forte
    assert hass_storage[DOMAIN]["data"]["enroll_key_v"] == 3
    assert NOTE not in _notes(hass)
    assert not coord.store.enroll_key_ancienne


# ------------------------------------------------------------------- nouvelle_cle()


async def test_nouvelle_cle_refuse_les_exemples(hass, hass_storage):
    _, coord = await _monter(hass, hass_storage)
    avant = coord.store.enroll_key
    for mauvaise in (*EXEMPLES, "Maison-2026", " vision-2026 ", "abc", "abcdefghijk", "x" * 20, "ab" * 10,
                     "avec espace dedans", "y" * 65):
        with pytest.raises(ValueError):
            coord.store.nouvelle_cle(mauvaise)
        assert coord.store.enroll_key == avant
    assert coord.store.nouvelle_cle("Tq7-wr2Z-pk94-Lm") == "Tq7-wr2Z-pk94-Lm"
    aleatoire = coord.store.nouvelle_cle("")
    assert HEX16.match(aleatoire) and aleatoire != "Tq7-wr2Z-pk94-Lm"


# ------------------------------------------------------------- Source sans exemple


def test_aucun_exemple_de_cle_dans_le_source():
    suffixes = {".py", ".ps1", ".js", ".json", ".yaml", ".yml", ".md", ".html", ".txt", ".kt", ".xml"}
    trouves = []
    for dossier in (racine / "custom_components" / "pc_parental", racine / "docs", racine / "public"):
        if not dossier.exists():
            continue
        for chemin in dossier.rglob("*"):
            if not chemin.is_file() or chemin.suffix.lower() not in suffixes:
                continue
            texte = chemin.read_text(encoding="utf-8", errors="ignore").lower()
            for exemple in EXEMPLES:
                if exemple in texte:
                    trouves.append(f"{chemin.relative_to(racine)} : {exemple}")
    assert not trouves, trouves


def test_l_installateur_n_a_pas_de_cle_par_defaut():
    texte = (racine / "custom_components" / "pc_parental" / "client" / "install.ps1").read_text(encoding="utf-8-sig")
    ligne = next(l for l in texte.splitlines() if "$Cle = Read-Obligatoire" in l)
    # L'exemple ne montre que le format ; Read-Obligatoire refuse une réponse vide.
    # i18n : l'exemple (format seulement) est dans le dictionnaire de l'installeur, en six langues.
    assert "(T 'install.exemple_cle')" in ligne
    for lg in ("fr", "en", "de", "es", "it", "nl"):
        d = json.loads((racine / "custom_components" / "pc_parental" / "client" / "langues" / f"{lg}.json").read_text(encoding="utf-8"))
        assert "0123456789abcdef" in d["install.exemple_cle"], lg
    assert "if ($reponse -and $reponse.Trim()) { return $reponse.Trim() }" in texte
