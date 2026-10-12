"""Refonte UX, lot 1, zone serveur (intégration Home Assistant et agent PC) : les libellés.

Seuls les textes affichés changent : aucun entity_id, unique_id, nom de service, clé de stockage ni valeur
d'option ne bouge (migration douce).

    python -m pytest essai/ux_l1_serveur_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import pathlib
import re

from homeassistant.helpers import entity_registry as er

from i18n_ha_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, _a_plat, _fiche, _integrations, _monter_langue, _releve, expected_lingering_tasks,
    expected_lingering_timers,
)

ICI = pathlib.Path(__file__).resolve().parent
RACINE = ICI.parent / "custom_components" / "pc_parental"
LANGUES = ("fr", "en", "de", "es", "it", "nl")


def _json(chemin: pathlib.Path) -> dict:
    return json.loads(chemin.read_text(encoding="utf-8"))


def _textes(langue: str) -> dict[str, str]:
    """Tout ce que le serveur et l'agent affichent dans une langue (hors tableau : autre zone)."""
    sortie: dict[str, str] = {}
    for nom, chemin in (("translations", RACINE / "translations" / f"{langue}.json"),
                        ("serveur", RACINE / "i18n" / "serveur" / f"{langue}.json"),
                        ("config", RACINE / "i18n" / "config" / f"{langue}.json"),
                        ("entites", RACINE / "i18n" / "entites" / f"{langue}.json"),
                        ("agent", RACINE / "client" / "langues" / f"{langue}.json")):
        for cle, texte in _a_plat(_json(chemin)).items():
            sortie[f"{nom}:{cle}"] = str(texte)
    return sortie


# ------------------------------------------------------------------ APP-09 / EX-11 : les deux « pauses »

# « Pause générale » devient « Tout fermer » (et « Rouvrir ») ; la pause de la protection devient « Suspendre la
# protection ». Les anciens libellés ne restent nulle part (sauf l'état « en pause » d'un lecteur multimédia).
ANCIENS = {
    "fr": r"pause g[ée]n[ée]rale|pause de gestion|pause de vision|vision en pause|dur[ée]e max(imale)? (de|d'une) pause|demande la pause",
    "en": r"general pause|management pause|vision pause|pause vision|pause length|length of a pause",
    "de": r"allgemeine pause|verwaltungspause|vision-pause|vision .*pausieren|pausendauer|dauer einer pause",
    "es": r"pausa general|pausa de gesti[óo]n|pausa de vision|pausar vision|duraci[óo]n m[áa]xima (de|de una) pausa",
    "it": r"pausa generale|pausa di gestione|pausa di vision|in pausa vision|durata massima (della|di una) pausa",
    "nl": r"algemene pauze|beheerpauze|vision-pauze|vision .*pauzeren|pauzeduur|duur van een pauze",
}
PERMIS_PAUSE = ("serveur:etat.paused", "serveur:lib.en_pause", "serveur:mus.j.action_lecture")


def test_plus_de_pause_generale_ni_pause_de_gestion():
    trouve = []
    for lg in LANGUES:
        motif = re.compile(ANCIENS[lg], re.I)
        for cle, texte in _textes(lg).items():
            if cle not in PERMIS_PAUSE and motif.search(texte):
                trouve.append((lg, cle, texte[:80]))
    assert not trouve, trouve


def test_nouveaux_libelles_en_francais():
    tr = _json(RACINE / "translations" / "fr.json")
    serveur = _json(RACINE / "i18n" / "serveur" / "fr.json")
    assert tr["services"]["pause_generale"]["name"] == "Tout fermer"
    assert tr["services"]["fin_pause_generale"]["name"] == "Rouvrir"
    assert tr["entity"]["button"]["pause_generale_30_min"]["name"] == "Tout fermer 30 min"
    assert tr["entity"]["button"]["fin_de_la_pause_generale"]["name"] == "Rouvrir"
    assert tr["entity"]["number"]["duree_max_de_pause"]["name"] == "Durée max de suspension"
    assert tr["selector"]["validation_actions"]["options"]["parent"] == "Suspendre la protection"
    assert serveur["module.pause_generale.nom"] == "Tout fermer"
    assert serveur["module.pause.nom"] == "Suspendre la protection"
    assert serveur["roles.f_pause"].startswith("Suspendre la protection")


def test_notification_de_deverrouillage_sans_pause():
    """La demande d'une suspension par code ne dit plus « demande une pause de Vision »."""
    from custom_components.pc_parental import textes

    for lg in LANGUES:
        quoi = textes.t("dev.libelle_parent", lg)
        texte = textes.t("dev.demande", lg, qui="Lou", quoi=quoi, minutes=30)
        assert not re.search(r"pau[sz]", texte, re.I), (lg, texte)


async def test_renommage_garde_les_entity_id_francais(hass, hass_storage):
    """Installation neuve : nouveaux noms affichés, entity_id d'avant (i18n/entity_id.json)."""
    await _monter_langue(hass, hass_storage, "fr")
    releve = _releve(hass)
    assert releve["pc_parental_pause_generale"] == ["binary_sensor.vision_pause_generale_en_cours", "Vision Tout fermé"]
    assert releve["pc_parental_pause_generale_30"] == ["button.vision_pause_generale_30_min", "Vision Tout fermer 30 min"]
    assert releve["pc_parental_pause_generale_fin"] == ["button.vision_fin_de_la_pause_generale", "Vision Rouvrir"]
    assert releve["pc_parental_pause_max_j1"] == ["number.pc_alex_duree_max_de_pause", "PC-ALEX Durée max de suspension"]


async def test_renommage_garde_les_entity_id_anglais(hass, hass_storage):
    await _monter_langue(hass, hass_storage, "en")
    releve = _releve(hass)
    assert releve["pc_parental_pause_generale"] == ["binary_sensor.vision_general_pause_on", "Vision Everything blocked"]
    assert releve["pc_parental_pause_generale_30"] == ["button.vision_general_pause_30_min", "Vision Block everything 30 min"]
    assert releve["pc_parental_pause_generale_fin"] == ["button.vision_end_the_general_pause", "Vision Allow again"]
    assert releve["pc_parental_pause_max_j1"] == ["number.pc_alex_maximum_pause_length", "PC-ALEX Maximum suspension length"]


# ------------------------------------------------------------------ HA-03 : « Appareils à valider (n) »


def test_menu_appareils_a_valider():
    """L'entrée du menu désigne l'appairage, pas les demandes d'accès ou de temps."""
    attendu = {"fr": "Appareils à valider ({attente})", "en": "Devices to approve ({attente})"}
    for lg in LANGUES:
        tr = _json(RACINE / "translations" / f"{lg}.json")
        libelle = tr["options"]["step"]["init"]["menu_options"]["appairage"]
        assert "{attente}" in libelle and not re.search(r"demande|request|anfrage|solicitud|richiest|verzoek", libelle, re.I)
        if lg in attendu:
            assert libelle == attendu[lg]
        assert tr["options"]["step"]["appairage"]["title"] == libelle.replace(" ({attente})", "")


async def test_ecran_appareils_a_valider_vide(hass, hass_storage):
    entree = await _monter_langue(hass, hass_storage, "fr")
    flux = await hass.config_entries.options.async_init(entree.entry_id)
    assert flux["description_placeholders"]["attente"] == "0"
    flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "appairage"})
    assert flux["step_id"] == "appairage"
    assert flux["description_placeholders"]["demandes"] == "Aucun appareil à valider."


# ------------------------------------------------------------------ HA-07 : « Où décider : » et un lien direct

TABLEAU = "Où décider : [tableau de bord Vision](/vision-maison/maison)"


def _capturer(monkeypatch) -> dict[str, str]:
    """Les notifications persistantes créées, par identifiant (titre + texte)."""
    from homeassistant.components import persistent_notification

    vues: dict[str, str] = {}

    def creer(hass, message, title=None, notification_id=None):
        vues[str(notification_id)] = f"{title}\n{message}"

    monkeypatch.setattr(persistent_notification, "async_create", creer)
    return vues


def test_ou_decider_six_langues():
    from custom_components.pc_parental import textes

    assert textes.chemin_tableau() == "/vision-maison/maison"
    assert textes.ou_decider("fr") == TABLEAU
    for lg in LANGUES:
        ligne = textes.ou_decider(lg)
        assert ligne.endswith("](/vision-maison/maison)") and "{" not in ligne, (lg, ligne)
        assert "/vision-appairage?code=AB" in textes.t("app.notif_demande", lg, nom="x", produit="Vision", code="AB",
                                                       chemin="/vision-appairage?code=AB", minutes=10)


async def test_notifications_disent_ou_decider(hass, hass_storage, hass_client, monkeypatch):
    from deverrouillage_test import ENFANT, Appareil, _monter as _monter_dev

    vues = _capturer(monkeypatch)
    client = await hass_client()
    await _monter_dev(hass, hass_storage)
    # Demande d'accès (site), comme l'agent.
    r = await client.post("/api/pc_parental/demande", json={**ENFANT, "genre": "sites", "nom": "exemple.org", "libelle": ""})
    assert (await r.json())["ok"] is True
    # Demande de temps, au relevé.
    await client.post("/api/pc_parental/poll", json={**ENFANT, "demande_temps": 15})
    # Demande de déverrouillage.
    statut, _ = await Appareil(client, ENFANT).demander()
    assert statut == 200
    await hass.async_block_till_done()
    acces = [t for i, t in vues.items() if i.startswith(f"{DOMAIN}_acces_")]
    temps = [t for i, t in vues.items() if i.startswith(f"{DOMAIN}_temps_")]
    dev = [t for i, t in vues.items() if i.startswith(f"{DOMAIN}_deverrouillage_")]
    assert acces and temps and dev, vues
    for texte in acces + temps:
        assert TABLEAU in texte, texte
    assert "Où décider : [tableau de bord Vision](/vision-maison/maison), ou sur un appareil" in dev[0]


async def test_reinscription_refusee_renvoie_vers_la_fiche(hass, hass_storage, hass_client_no_auth, monkeypatch):
    from custom_components.pc_parental.registre import appareil_du_pc
    from deverrouillage_test import _monter as _monter_dev

    vues = _capturer(monkeypatch)
    await _monter_dev(hass, hass_storage)
    client = await hass_client_no_auth()
    r = await client.post("/api/pc_parental/enroll", json={"key": "x" * 16, "host": "TEL-LOU", "platform": "android"})
    assert r.status == 409
    texte = vues[f"{DOMAIN}_reinscription_e1"]
    fiche = appareil_du_pc(hass, "e1")
    assert f"Où décider : [fiche de « TEL-LOU »](/config/devices/device/{fiche.id})" in texte, texte


# ------------------------------------------------------------------ A6 / EX-12 / HA-11 : derniers mots non neutres

RESTES = {
    "fr": r"gestionnaire|\b(un|l')\s?adulte\b|\brepas\b|par maison",
    "en": r"\bmanagers?\b|\b(an|the) adult\b|\bmeals\b",
    "de": r"verwalter\b|\bein(em)? erwachsene[rn]?\b|\bessen\b",
    "es": r"\bgestor(es)?\b|\b(un|al) adulto\b|\bcomidas\b",
    "it": r"\bgestor[ei]\b|\b(un|all')\s?adulto\b|\bpasti\b",
    # « beheerder » dit aussi « administrateur » (compte Home Assistant) : seul le reste est cherché.
    "nl": r"\bvolwassene\b|\beten\b",
}


def test_plus_de_gestionnaire_ni_adulte():
    """« gestionnaire » devient {decideur} ; « adulte » et « (repas, devoirs) » disparaissent.

    « sites pour adultes » (avertissement de fréquentation) parle des sites, pas d'une personne : il reste."""
    trouve = []
    for lg in LANGUES:
        motif = re.compile(RESTES[lg], re.I)
        for cle, texte in _textes(lg).items():
            if motif.search(texte):
                trouve.append((lg, cle, texte[:90]))
    assert not trouve, trouve


def test_gestionnaire_suit_le_vocabulaire():
    from custom_components.pc_parental import textes

    assert textes.t("commun.un_gestionnaire", "fr") == "Un décideur"
    assert textes.t("commun.un_gestionnaire", "en") == "A decider"
    assert textes.t("tab.public_parent", "fr") == "Décideurs"
    assert "décideur" in textes.t("proc.pas_proposer", "fr")


async def test_fabricant_vision_et_entity_id_inchanges(hass, hass_storage):
    """Le fabricant des appareils Home Assistant est « Vision », pas le nom du lieu ; les entity_id ne bougent pas."""
    from homeassistant.helpers import device_registry as dr

    await _monter_langue(hass, hass_storage, "fr")
    appareils = [d for d in dr.async_get(hass).devices.values() if any(i[0] == DOMAIN for i in d.identifiers)]
    assert len(appareils) >= 3
    assert {d.manufacturer for d in appareils} == {"Vision"}
    releve = _releve(hass)
    assert releve["pc_parental_acces_j1"][0] == "switch.pc_alex_acces_a_l_appareil"


# ------------------------------------------------------------------ A9 / EX-15 : plus de clé brute dans les listes


def test_options_de_selecteur_jamais_en_cle_brute():
    """« hors_demarrage », « plein_ecran », « moyenne »… : chaque option a un vrai libellé, dans chaque langue."""
    brutes = []
    for lg in LANGUES:
        for selecteur, contenu in _json(RACINE / "translations" / f"{lg}.json")["selector"].items():
            for cle, libelle in (contenu.get("options") or {}).items():
                if libelle == cle or re.fullmatch(r"[a-z0-9]+(_[a-z0-9]+)+", libelle):
                    brutes.append((lg, selecteur, cle))
    assert not brutes, brutes
    fr = _json(RACINE / "translations" / "fr.json")["selector"]
    assert fr["decision_processus"]["options"]["hors_demarrage"] == "Retirer du démarrage"
    assert fr["position_message"]["options"]["plein_ecran"] == "Plein écran"
    assert fr["taille_message"]["options"]["moyenne"] == "Moyenne"


# ------------------------------------------------------------------ A10 / HA-30 : « Suivant » sur les choix intermédiaires

# Home Assistant affiche « Suivant » (et non « Valider ») quand un formulaire annonce last_step=False.
INTERMEDIAIRES = (
    ("roles", "roles_modifier"), ("roles", "roles_personnes"), ("roles", "roles_creer"),
    ("entites", "entites_personnes"), ("entites", "entites_appareils"),
    ("appareils", "appareils_relier"), ("avance", "qr"), (None, "ajouter"),
)


async def test_choix_intermediaires_disent_suivant(hass, hass_storage):
    entree = await _monter_langue(hass, hass_storage, "fr")
    om = hass.config_entries.options
    for menu, etape in INTERMEDIAIRES:
        flux = await om.async_init(entree.entry_id)
        if menu:
            flux = await om.async_configure(flux["flow_id"], {"next_step_id": menu})
        flux = await om.async_configure(flux["flow_id"], {"next_step_id": etape})
        assert flux["type"] == "form" and flux["step_id"] == etape, (etape, flux)
        assert flux["last_step"] is False, etape
        om.async_abort(flux["flow_id"])
    # Ajouter un appareil : l'écran « Pour qui ? » aussi.
    flux = await om.async_init(entree.entry_id)
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "ajouter"})
    flux = await om.async_configure(flux["flow_id"], {"genre": "telephone"})
    assert flux["step_id"] == "ajouter_qui" and flux["last_step"] is False
    om.async_abort(flux["flow_id"])


# ------------------------------------------------------------------ EX-06 : « Appareils Android d'essai » sous Avancé


async def test_appareils_d_essai_sous_avance(hass, hass_storage):
    """L'outil de test quitte Réglages généraux ; sans appareil Android, l'écran le dit au lieu d'un formulaire vide."""
    entree = await _monter_langue(hass, hass_storage, "fr")
    om = hass.config_entries.options
    flux = await om.async_init(entree.entry_id)
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "general"})
    assert "appareils_essai" not in {str(k) for k in flux["data_schema"].schema}
    om.async_abort(flux["flow_id"])
    flux = await om.async_init(entree.entry_id)
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "avance"})
    assert flux["menu_options"][-1] == "appareils_essai"
    flux = await om.async_configure(flux["flow_id"], {"next_step_id": "appareils_essai"})
    assert flux["step_id"] == "appareils_essai"
    flux = await om.async_configure(flux["flow_id"], {"appareils_essai": ["g1"]})
    store = hass.data[DOMAIN][entree.entry_id].store
    assert store.appareils_essai == ["g1"]
    tr = _json(RACINE / "translations" / "fr.json")["options"]
    assert tr["step"]["avance"]["menu_options"]["appareils_essai"] == "Appareils Android d'essai"
    assert "appareils_essai" not in tr["step"]["general"]["data"]
    assert tr["abort"]["essai_aucun_android"]


# ------------------------------------------------------------------ HA-01 : retirer un appareil, jamais en un appui


def _eid(hass, plateforme: str, uid: str) -> str:
    return er.async_get(hass).async_get_entity_id(plateforme, DOMAIN, uid)


async def test_ha01_appareil_cible_a_choisir_et_non_restaure(hass, hass_storage):
    """« Appareil ciblé » vaut « — choisir — » au démarrage, même si un appareil était choisi avant."""
    from pytest_homeassistant_custom_component.common import mock_restore_cache
    from homeassistant.core import State

    mock_restore_cache(hass, [State("select.vision_appareil_cible", "PC-ALEX")])
    await _monter_langue(hass, hass_storage, "fr")
    cible = _eid(hass, "select", f"{DOMAIN}_pc_cible")
    etat = hass.states.get(cible)
    assert etat.state == "— choisir —"
    assert etat.attributes["options"][:2] == ["— choisir —", "Tous les appareils"]  # valeur d'avant gardée
    assert "PC-ALEX" in etat.attributes["options"]
    coord = next(iter(hass.data[DOMAIN].values()))
    assert coord.cible is None


async def test_ha01_supprimer_l_appareil_cible_en_deux_appuis(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental import button as boutons

    vues = _capturer(monkeypatch)
    rejets: list[str] = []
    from homeassistant.components import persistent_notification

    monkeypatch.setattr(persistent_notification, "async_dismiss", lambda hass, nid: rejets.append(nid))
    instant = [1000.0]
    monkeypatch.setattr(boutons, "_horloge", lambda: instant[0])
    entree = await _monter_langue(hass, hass_storage, "fr")
    coord = hass.data[DOMAIN][entree.entry_id]
    bouton = _eid(hass, "button", f"{DOMAIN}_supprimer_pc")
    await hass.services.async_call("select", "select_option", {
        "entity_id": _eid(hass, "select", f"{DOMAIN}_pc_cible"), "option": "PC-ALEX"}, blocking=True)

    async def appuyer():
        await hass.services.async_call("button", "press", {"entity_id": bouton}, blocking=True)
        await hass.async_block_till_done()

    await appuyer()  # premier appui : rien n'est retiré, on prévient
    assert "j1" in coord.pcs
    assert vues[f"{DOMAIN}_retrait_j1"] == ("Retirer PC-ALEX ?\nAppuie de nouveau dans les 15 s pour retirer "
                                           "PC-ALEX (plages, règles, historique perdus).")
    instant[0] += 16  # trop tard : le second appui réarme seulement
    await appuyer()
    assert "j1" in coord.pcs
    instant[0] += 10  # dans les 15 s : retiré
    await appuyer()
    assert "j1" not in coord.pcs
    assert f"{DOMAIN}_retrait_j1" in rejets


async def test_ha01_bouton_par_appareil_desactive_et_en_deux_appuis(hass, hass_storage, monkeypatch):
    """Installation neuve : « Supprimer cet appareil » est désactivé ; réactivé, il demande aussi deux appuis."""
    vues = _capturer(monkeypatch)
    entree = await _monter_langue(hass, hass_storage, "fr")
    registre = er.async_get(hass)
    uid = f"{DOMAIN}_oublier_j1"
    eid = _eid(hass, "button", uid)
    assert registre.async_get(eid).disabled_by is er.RegistryEntryDisabler.INTEGRATION
    registre.async_update_entity(eid, disabled_by=None)
    assert await hass.config_entries.async_reload(entree.entry_id)
    await hass.async_block_till_done()
    coord = hass.data[DOMAIN][entree.entry_id]
    await hass.services.async_call("button", "press", {"entity_id": eid}, blocking=True)
    await hass.async_block_till_done()
    assert "j1" in coord.pcs and f"{DOMAIN}_retrait_j1" in vues
    await hass.services.async_call("button", "press", {"entity_id": eid}, blocking=True)
    await hass.async_block_till_done()
    assert "j1" not in coord.pcs


# ------------------------------------------------------------------ Vocabulaire : plus de « Chez nous »


def test_theme_autres_dit_le_lieu():
    """Le thème fourre-tout de l'écran de veille ne dit plus « Chez nous » (ni « Bei uns »…) : le nom du {lieu}."""
    from custom_components.pc_parental import textes

    for lg in LANGUES:
        assert _json(RACINE / "i18n" / "serveur" / f"{lg}.json")["veille.theme_autres"] == "{Lieu}"
    assert textes.t("veille.theme_autres", "fr") == "Lieu"
    assert textes.t("veille.theme_autres", "de") == "Ort"
    motif = re.compile(r"chez nous|bei uns|around here|por aqu[íi]|da noi|bij ons", re.I)
    assert not [(lg, c) for lg in LANGUES for c, t in _textes(lg).items() if motif.search(t)]


# ------------------------------------------------------------------ Mêmes mots partout pour « Tout fermer »


def test_tout_fermer_rouvrir_memes_mots_partout():
    """« Tout fermer », « Rouvrir », « Tout fermé », « Suspendre la protection » : une seule forme par langue, du
    tableau aux entités et à l'agent PC (docs/i18n/glossaire.md)."""
    for lg in LANGUES:
        tab = _json(RACINE / "i18n" / "tableau" / f"{lg}.json")
        tr = _json(RACINE / "translations" / f"{lg}.json")
        srv = _json(RACINE / "i18n" / "serveur" / f"{lg}.json")
        agent = _json(RACINE / "client" / "langues" / f"{lg}.json")
        fermer = tr["services"]["pause_generale"]["name"]
        rouvrir = tr["services"]["fin_pause_generale"]["name"]
        assert tab["pause_generale"] == srv["module.pause_generale.nom"] == fermer, lg
        assert tab["pause_generale_fin"] == tr["entity"]["button"]["fin_de_la_pause_generale"]["name"] == rouvrir, lg
        assert tab["pause_generale_30"] == tr["entity"]["button"]["pause_generale_30_min"]["name"], lg
        assert tab["pause_generale_30"].startswith(fermer), lg
        assert fermer in tab["pause_generale_aucune"] and fermer in tab["pause_generale_confirmation"], lg
        assert rouvrir in tab["pause_generale_en_cours"], lg
        assert fermer in _a_plat(agent)["exceptions.aide_toujours"], lg
        assert srv["module.pause.nom"] == tr["selector"]["validation_actions"]["options"]["parent"], lg
