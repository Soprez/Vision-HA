"""Vision → Configurer → « Ajouter un appareil » (refonte du 2026-10-08), dans un vrai Home Assistant.

Le menu (l'ajout d'abord, la clé brute seulement dans Avancé), puis l'assistant en 4 étapes :
type → pour qui → installer → relier → « ✅ {Nom} est relié à {Personne} ».
- téléphone : QR d'appairage signé ; l'appareil présente le jeton ; l'assistant le voit relié ;
- télé : le code affiché sur la télé ;
- PC : une commande PowerShell qui porte un jeton à usage unique (l'installateur relie le PC seul) ;
- reprise proposée d'office : un appareil semblable existe pour la personne, ou l'appareil se
  présente déjà inscrit (un nouveau QR reprend alors sa fiche) ;
- l'adresse courte /vision renvoie au lien stable de l'APK.

    python -m pytest essai/ajout_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

from securite_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, _actuel, _demander, _fiche, _integrations, _monter, expected_lingering_tasks, expected_lingering_timers,
)

FLUX = "/api/config/config_entries/options/flow"


async def _ouvrir(admin, entree):
    flux = await (await admin.post(FLUX, json={"handler": entree.entry_id})).json()
    assert flux["type"] == "menu"
    return flux


async def _suite(admin, flux, corps):
    reponse = await admin.post(f"{FLUX}/{flux['flow_id']}", json=corps)
    assert reponse.status == 200, await reponse.text()
    return await reponse.json()


def _invitation(hass, flux_id=None):
    invitations = hass.data[f"{DOMAIN}_appairage_invitations"]
    assert len(invitations) == 1
    return next(iter(invitations.values()))


async def test_menu_dans_l_ordre(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, _ = await _monter(hass, hass_storage)
    await _demander(admin)
    flux = await _ouvrir(admin, entree)
    # Refonte UX lot 4 : Configurer réduit ; les autres réglages sous « Autres réglages » (assistant_test.py).
    assert flux["menu_options"] == ["assistant", "ajouter", "appairage", "reparer", "autres", "soutien"]
    assert flux["description_placeholders"]["attente"] == "1"
    reparer = await _suite(admin, flux, {"next_step_id": "reparer"})
    assert reparer["menu_options"] == ["appareils_relier", "sauvegarde", "installer", "qr", "cle"]


async def test_telephone_par_qr(hass, hass_storage, hass_client, hass_client_no_auth):
    admin = await hass_client()
    anonyme = await hass_client_no_auth()
    entree, _ = await _monter(hass, hass_storage)
    flux = await _suite(admin, await _ouvrir(admin, entree), {"next_step_id": "ajouter"})
    assert flux["step_id"] == "ajouter"
    flux = await _suite(admin, flux, {"genre": "telephone"})
    assert flux["step_id"] == "ajouter_qui"
    flux = await _suite(admin, flux, {"personne": "person.enfant"})
    assert flux["step_id"] == "ajouter_installer_telephone" and "Enfant" in flux["description_placeholders"]["qui"]
    flux = await _suite(admin, flux, {})
    assert flux["step_id"] == "ajouter_relier_telephone"
    image = flux["description_placeholders"]["image"]
    assert "authSig=" in image and (await anonyme.get(image)).status == 200
    # Pas encore scanné : on le dit, même QR.
    flux = await _suite(admin, flux, {})
    assert flux["errors"] == {"base": "ajout_pas_encore"}
    inv = _invitation(hass)
    r = await anonyme.post("/api/pc_parental/appairage/jeton",
                           json={"jeton": inv["jeton"], "host": "PIXEL-LOU", "nom": "Pixel de Lou", "uid": "u1",
                                 "platform": "android"})
    assert r.status == 200
    await hass.async_block_till_done()
    flux = await _suite(admin, flux, {})
    assert flux["step_id"] == "ajouter_fini"
    p = flux["description_placeholders"]
    assert p["nom"] == "Pixel de Lou" and p["personne"] == "Enfant"
    pc = next(pc for pc in _actuel(hass).store.pcs.values() if pc.get("uid") == "u1")
    assert pc["person"] == "person.enfant"
    flux = await _suite(admin, flux, {})
    assert flux["type"] == "create_entry"


async def test_tele_par_code(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, _ = await _monter(hass, hass_storage)
    flux = await _suite(admin, await _ouvrir(admin, entree), {"next_step_id": "ajouter"})
    flux = await _suite(admin, flux, {"genre": "tele"})
    assert flux["data_schema"][0]["default"] == "partage"  # une télé : écran partagé, d'office
    flux = await _suite(admin, flux, {"personne": "partage"})
    assert flux["step_id"] == "ajouter_installer_tele"
    flux = await _suite(admin, flux, {})
    assert flux["step_id"] == "ajouter_relier_tele"
    flux = await _suite(admin, flux, {"code": "ZZZZZZ"})
    assert flux["errors"] == {"code": "code_inconnu"}
    _, d = await _demander(admin)
    flux = await _suite(admin, flux, {"code": d["code"]})
    assert flux["step_id"] == "ajouter_fini"
    assert flux["description_placeholders"]["nom"] == "Télé du salon"
    assert flux["description_placeholders"]["personne"] == "l'écran partagé"


async def test_pc_par_commande(hass, hass_storage, hass_client, hass_client_no_auth):
    admin = await hass_client()
    anonyme = await hass_client_no_auth()
    entree, _ = await _monter(hass, hass_storage)
    flux = await _suite(admin, await _ouvrir(admin, entree), {"next_step_id": "ajouter"})
    flux = await _suite(admin, flux, {"genre": "pc"})
    flux = await _suite(admin, flux, {"personne": "person.enfant"})
    assert flux["step_id"] == "ajouter_installer_pc"
    commande = flux["data_schema"][0]["default"]
    inv = _invitation(hass)
    assert f"-Jeton '{inv['jeton']}'" in commande and "/api/pc_parental/client/install" in commande
    assert not inv["jeton"].startswith("-")
    flux = await _suite(admin, flux, {"commande": commande})
    assert flux["step_id"] == "ajouter_relier_pc"
    # Ce que fait install.ps1 -Jeton : il présente le jeton, sans clé.
    r = await anonyme.post("/api/pc_parental/appairage/jeton",
                           json={"jeton": inv["jeton"], "host": "PC-LOU", "nom": "PC-LOU", "user": "lou"})
    assert r.status == 200 and (await r.json())["secret"]
    await hass.async_block_till_done()
    flux = await _suite(admin, flux, {})
    assert flux["step_id"] == "ajouter_fini" and flux["description_placeholders"]["nom"] == "PC-LOU"
    # Le jeton ne sert qu'une fois.
    r = await anonyme.post("/api/pc_parental/appairage/jeton", json={"jeton": inv["jeton"], "host": "AUTRE"})
    assert r.status == 404


async def test_pc_commande_expiree_en_donne_une_neuve(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, _ = await _monter(hass, hass_storage)
    flux = await _suite(admin, await _ouvrir(admin, entree), {"next_step_id": "ajouter"})
    flux = await _suite(admin, flux, {"genre": "pc"})
    flux = await _suite(admin, flux, {"personne": "partage"})
    ancien = _invitation(hass)
    flux = await _suite(admin, flux, {})
    ancien["expire"] = 0  # le délai passe
    flux = await _suite(admin, flux, {})
    assert flux["step_id"] == "ajouter_installer_pc" and flux["errors"] == {"base": "ajout_expire"}
    neuf = _invitation(hass)
    assert neuf["jeton"] != ancien["jeton"] and neuf["jeton"] in flux["data_schema"][0]["default"]


async def test_reprise_proposee_pour_la_personne(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, _ = await _monter(hass, hass_storage, appareils={
        "t1": _fiche("t1", "Ancien téléphone", "a" * 32, platform="android", type="telephone", person="person.enfant"),
    })
    flux = await _suite(admin, await _ouvrir(admin, entree), {"next_step_id": "ajouter"})
    flux = await _suite(admin, flux, {"genre": "telephone"})
    flux = await _suite(admin, flux, {"personne": "person.enfant"})
    assert flux["step_id"] == "ajouter_reprise"
    flux = await _suite(admin, flux, {"reprise": "t1"})
    assert "reprise de « Ancien téléphone »" in flux["description_placeholders"]["qui"]
    await _suite(admin, flux, {})
    assert _invitation(hass)["appareil"] == "t1"


async def test_deja_inscrit_un_nouveau_qr_le_reprend(hass, hass_storage, hass_client, hass_client_no_auth):
    admin = await hass_client()
    anonyme = await hass_client_no_auth()
    entree, _ = await _monter(hass, hass_storage, appareils={
        "t1": _fiche("t1", "Téléphone du salon", "a" * 32, platform="android", type="telephone", uid="u-salon"),
    })
    flux = await _suite(admin, await _ouvrir(admin, entree), {"next_step_id": "ajouter"})
    flux = await _suite(admin, flux, {"genre": "telephone"})
    flux = await _suite(admin, flux, {"personne": "person.enfant"})  # pas de candidat pour Enfant
    assert flux["step_id"] == "ajouter_installer_telephone"
    flux = await _suite(admin, flux, {})
    inv = _invitation(hass)
    corps = {"host": "SALON", "nom": "Téléphone du salon", "uid": "u-salon", "platform": "android"}
    r = await anonyme.post("/api/pc_parental/appairage/jeton", json={"jeton": inv["jeton"], **corps})
    assert r.status == 409
    flux = await _suite(admin, flux, {})
    assert flux["step_id"] == "ajouter_relier_telephone"
    assert "déjà inscrit sous « Téléphone du salon »" in flux["description_placeholders"]["qui"]
    invitations = hass.data[f"{DOMAIN}_appairage_invitations"]
    neuf = next(i for i in invitations.values() if i["appareil"] == "t1")
    r = await anonyme.post("/api/pc_parental/appairage/jeton", json={"jeton": neuf["jeton"], **corps})
    assert r.status == 200 and (await r.json())["created"] is False
    await hass.async_block_till_done()
    flux = await _suite(admin, flux, {})
    assert flux["step_id"] == "ajouter_fini" and "reprise" in flux["description_placeholders"]["reprise"]
    assert _actuel(hass).store.pcs["t1"]["person"] == "person.enfant"


async def test_relier_a_nouveau_depuis_appareils_existants(hass, hass_storage, hass_client):
    admin = await hass_client()
    entree, _ = await _monter(hass, hass_storage, appareils={
        "p1": _fiche("p1", "PC-NOA", "a" * 32, person="person.enfant"),
    })
    flux = await _suite(admin, await _ouvrir(admin, entree), {"next_step_id": "reparer"})
    flux = await _suite(admin, flux, {"next_step_id": "appareils_relier"})
    flux = await _suite(admin, flux, {"appareil": "p1"})
    assert flux["step_id"] == "ajouter_installer_pc"
    assert _invitation(hass)["appareil"] == "p1" and _invitation(hass)["personne"] == "person.enfant"


async def test_adresse_courte(hass, hass_storage, hass_client_no_auth):
    from custom_components.pc_parental import android_maj

    anonyme = await hass_client_no_auth()
    await _monter(hass, hass_storage)
    android_maj._ETAT["installation"] = {"version": "1.65.0", "sha256": "x"}
    r = await anonyme.get("/vision", allow_redirects=False)
    assert r.status == 302 and r.headers["Location"] == android_maj.LIEN_STABLE
    android_maj._ETAT["installation"] = {}
    assert (await anonyme.get("/vision", allow_redirects=False)).status == 404
