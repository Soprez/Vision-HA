"""FAILLE DE SÉCURITÉ (l'auteur, 16/10) : accorder exige une preuve à CHAQUE geste, sans délai de grâce (octroi.py).

l'auteur a déverrouillé Alex depuis son téléphone de Décideur en touchant la demande, sans empreinte ni code. Côté
Home Assistant, ces essais prouvent :
- notifications de l'appli Compagnon : plus aucun bouton d'accord ; un bouton d'accord d'une ancienne notification
  (accès, temps, déverrouillage) n'accorde rien ; « Refuser » marche toujours ;
- tableau de bord (/maison, fiche personne, appairage, QR) : tout geste qui accorde exige le code d'octroi, à chaque
  fois (pas de fenêtre de grâce) ; sans code posé, rien n'est accordé ; codes faux limités ; refuser, fermer, lire :
  sans code ;
- le code se pose dans Configurer › Sécurité ; Home Assistant n'en garde que l'empreinte.

    python -m pytest essai/octroi_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import sys

from demandes_partout_test import _demander
from gestion_attaques_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN,
    ENFANT,
    PARENT,
    _integrations,
    _monter,
    expected_lingering_tasks,
    expected_lingering_timers,
)
from homeassistant.core import Context, ServiceCall

CODE = "482913"
MAISON = "/api/pc_parental/maison"


def _module(fin):
    import importlib

    paquet = next(n for n in sys.modules if n.endswith("pc_parental") and n.count(".") <= 1)
    return importlib.import_module(paquet + "." + fin)


def _actuel(hass):
    return next(iter(hass.data[DOMAIN].values()))


def _poser_code(hass, code=CODE, personne="person.nolan"):
    """Le code de déverrouillage d'un Décideur (Nolan, PIXEL-NOLAN gère) : le même pour l'appli et le tableau."""
    _module("octroi").poser(_actuel(hass).store, personne, code)


def _demandes(hass):
    return [d for d in _module("parent").etat_maison(hass, _actuel(hass), "p1")["demandes"] if d["pc"] == "e1"]


def test_regle_des_gestes_qui_accordent():
    from custom_components.pc_parental import octroi

    for a in ("ouvrir", "annuler", "temps", "partage", "attribuer", "limite", "plage", "logiciel", "pause_max",
              "lier_domaine", "retirer_autorisation", "fin_pause_generale", "reprise_autorisations", "inconnue"):
        assert octroi.est_octroi({"action": a}), a
    for d in ("temporaire", "accorder", "toujours", "exception", ""):
        assert octroi.est_octroi({"action": "acces", "decision": d}), d
    assert octroi.est_octroi({"action": "etiquette", "etat": "neutre"})
    for corps in ({}, {"action": "categorie"}, {"action": "recherche"}, {"action": "recents"}, {"action": "fermer"},
                  {"action": "message"}, {"action": "concentration"}, {"action": "pause_generale"},
                  {"action": "acces", "decision": "non"}, {"action": "etiquette", "etat": "bloquer"},
                  {"action": "autoriser", "decision": "bloquer"}):
        assert not octroi.est_octroi(corps), corps


async def test_tableau_sans_code_pose_n_accorde_rien(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    r = await client.post(MAISON, json={"action": "temps", "pc": "e1", "minutes": 30})
    corps = await r.json()
    assert r.status == 403 and corps["error"] == "code_octroi_absent" and corps["retour"]
    # Fermer reste possible sans code.
    r = await client.post(MAISON, json={"action": "fermer", "pc": "e1"})
    assert r.status == 200


async def test_tableau_code_a_chaque_geste(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    _poser_code(hass)
    geste = {"action": "temps", "pc": "e1", "minutes": 30}
    r = await client.post(MAISON, json=geste)
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"
    r = await client.post(MAISON, json={**geste, "code_octroi": "000000"})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi_faux"
    r = await client.post(MAISON, json={**geste, "code_octroi": CODE})
    assert r.status == 200, await r.text()
    # Pas de délai de grâce : le geste suivant redemande le code.
    r = await client.post(MAISON, json=geste)
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"


async def test_tableau_accepter_une_demande_exige_le_code(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    _poser_code(hass)
    await _demander(client, ENFANT, "sites")
    await hass.async_block_till_done()
    d = _demandes(hass)[0]
    accepter = {"action": "acces", "pc": "e1", "demande": d["id"], "decision": "temporaire", "minutes": 30}
    r = await client.post(MAISON, json=accepter)
    assert r.status == 403 and _demandes(hass), "accordé sans code"
    r = await client.post(MAISON, json={**accepter, "code_octroi": CODE})
    assert r.status == 200 and not _demandes(hass)
    # Refuser ne demande rien.
    await _demander(client, ENFANT, "apps")
    await hass.async_block_till_done()
    d = _demandes(hass)[0]
    r = await client.post(MAISON, json={"action": "acces", "pc": "e1", "demande": d["id"], "decision": "non"})
    assert r.status == 200 and not _demandes(hass)


async def test_codes_faux_limites(hass, hass_storage, hass_client):
    from custom_components.pc_parental import octroi

    client = await hass_client()
    await _monter(hass, hass_storage)
    _poser_code(hass)
    geste = {"action": "ouvrir", "pc": "e1", "minutes": 30}
    for _ in range(octroi.ESSAIS_FAUX_MAX):
        r = await client.post(MAISON, json={**geste, "code_octroi": "999999"})
        assert (await r.json())["error"] == "code_octroi_faux"
    r = await client.post(MAISON, json={**geste, "code_octroi": CODE})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi_suspendu"
    assert len(_actuel(hass).store.codes_decideurs_echecs) == octroi.ESSAIS_FAUX_MAX


async def test_fiche_personne_role_et_temps(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    _poser_code(hass)
    r = await client.post("/api/pc_parental/personne/admin", json={"op": "role", "personne": "person.lou", "role": "parent"})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"
    assert _actuel(hass).store.roles_personnes.get("person.lou") != "parent"
    r = await client.post("/api/pc_parental/personne/admin",
                          json={"op": "role", "personne": "person.lou", "role": "parent", "code_octroi": CODE})
    assert r.status == 200


async def test_appairage_valider_et_qr(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    _poser_code(hass)
    r = await client.post("/api/pc_parental/appairage", json={"code": "ABCDEF", "action": "valider", "personne": "person.lou"})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"
    r = await client.post("/api/pc_parental/appairage/invitation", json={"personne": "person.lou"})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"
    # Refuser une demande d'appairage : sans code (rien n'est accordé).
    r = await client.post("/api/pc_parental/appairage", json={"code": "ABCDEF", "action": "refuser"})
    assert r.status != 403


def test_notifications_compagnon_sans_bouton_d_accord():
    from custom_components.pc_parental import a_decider

    for genre in ("acces", "temps", "deverrouillage"):
        boutons = a_decider.boutons(genre, "e1", "x", 20, "fr")
        assert [b["action"].split("|")[-1] for b in boutons] == ["non"], boutons


async def test_ancienne_notification_n_accorde_rien(hass, hass_storage, hass_client, hass_admin_user):
    """Un bouton d'accord d'une notification envoyée avant la correction : ignoré, la demande attend toujours."""
    client = await hass_client()
    await _monter(hass, hass_storage)
    await _demander(client, ENFANT, "temps")
    await _demander(client, ENFANT, "sites")
    await hass.async_block_till_done()
    avant = {d["id"] for d in _demandes(hass)}
    assert avant
    temps = next(d for d in _demandes(hass) if d["genre"] == "temps")
    acces = next(d for d in _demandes(hass) if d["genre"] != "temps")
    ctx = Context(user_id=hass_admin_user.id)
    hass.bus.async_fire("mobile_app_notification_action", {"action": f"VISION|temps|e1|{temps['id']}|20"}, context=ctx)
    hass.bus.async_fire("mobile_app_notification_action", {"action": f"VISION_ACCES|e1|{acces['id']}|t60"}, context=ctx)
    hass.bus.async_fire("mobile_app_notification_action", {"action": f"VISION_ACCES|e1|{acces['id']}|toujours"}, context=ctx)
    await hass.async_block_till_done()
    assert {d["id"] for d in _demandes(hass)} == avant, "accordé depuis une notification"
    # « Refuser » depuis la notification marche toujours.
    hass.bus.async_fire("mobile_app_notification_action", {"action": f"VISION_ACCES|e1|{acces['id']}|non"}, context=ctx)
    await hass.async_block_till_done()
    assert acces["id"] not in {d["id"] for d in _demandes(hass)}


async def test_notification_compagnon_seulement_refuser(hass, hass_storage, hass_client):
    """La notification d'une demande d'accès n'a plus que « Refuser »."""
    from custom_components.pc_parental import canaux

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    recus = []

    async def notifier(call: ServiceCall):
        recus.append(dict(call.data))
    hass.services.async_register("notify", "mobile_app_pixel_decideur", notifier)
    coord.store.notif_decideurs = ["mobile_app_pixel_decideur"]
    canaux.poser(coord.store, "person.nolan", "demandes", ["vision", "compagnon"])
    await _demander(client, ENFANT, "sites")
    await hass.async_block_till_done()
    envois = [r for r in recus if r.get("message") != "clear_notification"]
    assert envois and all([b["action"].split("|")[-1] for b in e["data"]["actions"]] == ["non"] for e in envois), envois


async def test_code_pose_dans_configurer(hass, hass_storage):
    coord = await _monter(hass, hass_storage)
    om = hass.config_entries.options
    flux = await om.async_init(coord.config_entry.entry_id)
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "autres"})
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "securite"})
    assert "code_octroi" in flux["menu_options"]
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "code_octroi"})
    flux = await om.async_configure(flux["flow_id"], {"personne": "person.mia", "action": "changer", "code": CODE,
                                                     "confirmation": CODE})
    assert flux["type"] == "create_entry"
    await hass.async_block_till_done()
    fiche = hass_storage[DOMAIN]["data"]["codes_decideurs"]["person.mia"]
    assert fiche["format"] == "pbkdf2" and fiche["empreinte"] and CODE not in str(fiche)


async def test_rouvrir_tout_fermer_par_la_carte_avec_le_code(hass, hass_storage, hass_client):
    """Proposition validée en essai : « Rouvrir » n'est plus le bouton standard de HA (button.press, sans code) ; c'est la
    carte Vision (mode « rouvrir »), qui passe par /maison et exige le code d'octroi."""
    import json

    from custom_components.pc_parental import dashboard

    client = await hass_client()
    coord = await _monter(hass, hass_storage)
    vues = dashboard.build(hass, _actuel(hass))["views"]
    texte = json.dumps(next(v for v in vues if v.get("path") == "maison"))
    assert "button.press" not in texte and "pause_generale_fin" not in texte
    assert '"mode": "rouvrir"' in texte
    _poser_code(hass)
    await hass.services.async_call(DOMAIN, "pause_generale", {"minutes": 30}, blocking=True)
    await hass.async_block_till_done()
    assert _module("pause_generale").en_cours(_actuel(hass).store)
    r = await client.post(MAISON, json={"action": "fin_pause_generale"})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi"
    assert _module("pause_generale").en_cours(_actuel(hass).store), "rouvert sans code"
    r = await client.post(MAISON, json={"action": "fin_pause_generale", "code_octroi": CODE})
    assert r.status == 200, await r.text()
    assert not _module("pause_generale").en_cours(_actuel(hass).store)
    del coord



# ----------------------------------------------------------------------------- Un seul code (décision du projet, 16/10)

PARENT_URL = "/api/pc_parental/parent"


async def _code(client, op, **corps):
    r = await client.post(PARENT_URL, json={**PARENT, "action": "code_deverrouillage", "op": op, **corps})
    return r.status, await r.json()


async def test_migration_du_code_de_l_appli_sans_rien_redemander(hass, hass_storage, hass_client):
    """L'appli de Nolan a déjà un code (son empreinte locale, SHA-256 « vision:<code> ») : il sert tel quel au tableau ;
    au premier code juste, Home Assistant le garde en PBKDF2."""
    import hashlib

    client = await hass_client()
    await _monter(hass, hass_storage)
    assert (await _code(client, "etat"))[1] == {"ok": True, "pose": False}
    locale = hashlib.sha256(b"vision:2580").hexdigest()
    statut, d = await _code(client, "migrer", empreinte=locale)
    assert statut == 200 and d["migre"] is True and d["pose"] is True
    assert _actuel(hass).store.codes_decideurs["person.nolan"]["format"] == "appli_sha256"
    # Une seconde migration ne remplace rien (le code de HA fait foi).
    assert (await _code(client, "migrer", empreinte=hashlib.sha256(b"vision:9999").hexdigest()))[1]["migre"] is False
    # Le tableau accepte ce même code : il devient PBKDF2.
    r = await client.post(MAISON, json={"action": "temps", "pc": "e1", "minutes": 15, "code_octroi": "2580"})
    assert r.status == 200, await r.text()
    fiche = _actuel(hass).store.codes_decideurs["person.nolan"]
    assert fiche["format"] == "pbkdf2" and locale not in str(fiche)
    # Et l'appli le vérifie au même endroit.
    assert (await _code(client, "verifier", code="2580"))[0] == 200
    assert (await _code(client, "verifier", code="0000"))[1]["error"] == "code_octroi_faux"


async def test_l_appli_choisit_puis_change_le_code(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    assert (await _code(client, "definir", code="12"))[1]["error"] == "code_court"
    assert (await _code(client, "definir", code="1357"))[0] == 200
    # Le changer exige l'ancien.
    statut, d = await _code(client, "definir", code="2468", ancien="0000")
    assert statut == 403 and d["error"] == "code_octroi_faux"
    assert (await _code(client, "definir", code="2468", ancien="1357"))[0] == 200
    assert (await _code(client, "verifier", code="2468"))[0] == 200
    # Le même code au tableau, pour un compte qui n'est lié à aucun Décideur : le code d'un Décideur suffit.
    r = await client.post(MAISON, json={"action": "temps", "pc": "e1", "minutes": 15, "code_octroi": "2468"})
    assert r.status == 200


async def test_un_demandeur_n_a_pas_de_code_de_deverrouillage(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    r = await client.post(PARENT_URL, json={**ENFANT, "action": "code_deverrouillage", "op": "definir", "code": "1234"})
    assert r.status == 403
    assert "person.lou" not in _actuel(hass).store.codes_decideurs


async def test_le_releve_dit_si_le_code_est_dans_ha(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    r = await (await client.post("/api/pc_parental/poll", json={**PARENT})).json()
    assert r["code_deverrouillage"] is False
    _poser_code(hass)
    r = await (await client.post("/api/pc_parental/poll", json={**PARENT})).json()
    assert r["code_deverrouillage"] is True
    r = await (await client.post("/api/pc_parental/poll", json={**ENFANT})).json()
    assert r.get("code_deverrouillage") is None


async def test_le_tableau_prend_le_code_de_la_personne_du_compte(hass, hass_storage, hass_client, hass_admin_user):
    """Un compte HA lié à Nolan : c'est le code de Nolan qui compte, pas celui de Mia."""
    client = await hass_client()
    await _monter(hass, hass_storage)
    hass.states.async_set("person.nolan", "home", {"friendly_name": "Nolan", "user_id": hass_admin_user.id})
    _poser_code(hass, "1111", "person.nolan")
    _poser_code(hass, "2222", "person.mia")
    r = await client.post(MAISON, json={"action": "temps", "pc": "e1", "minutes": 15, "code_octroi": "2222"})
    assert r.status == 403 and (await r.json())["error"] == "code_octroi_faux"
    r = await client.post(MAISON, json={"action": "temps", "pc": "e1", "minutes": 15, "code_octroi": "1111"})
    assert r.status == 200
