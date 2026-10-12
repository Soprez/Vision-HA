"""Vision — supervision des PC de la maison, pour Home Assistant."""

from __future__ import annotations

import logging
from datetime import timedelta

import voluptuous as vol
from homeassistant.util import dt as dt_util
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EVENT_STATE_CHANGED, Platform
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import label_registry as lr
from homeassistant.helpers.event import async_track_time_interval

from .const import (
    DAY_PRESETS,
    DEFAULT_SOUND,
    DOMAIN,
    MODE_LOCKED,
    MODE_OPEN,
    PRODUIT,
    SERVICE_ADD_SCHEDULE,
    SERVICE_BUILD_DASHBOARD,
    SERVICE_CREATE_PC,
    SERVICE_DELETE_PC,
    SERVICE_LOCK,
    SERVICE_NOTIFY,
    SERVICE_CREATE_TAG,
    SERVICE_DELETE_TAG,
    SERVICE_REMOVE_SCHEDULE,
    SERVICE_RENAME_PC,
    SERVICE_RESET,
    SERVICE_SET_APPS,
    SERVICE_SET_HOUSE_TAG,
    SERVICE_SET_PC_LABELS,
    SERVICE_SET_PC_TAG,
    SERVICE_SET_SITES,
    SERVICE_SET_TAGS,
    SERVICE_DECIDER_ELEMENTS,
    SERVICE_TAG_ITEMS,
    SERVICE_UNLOCK,
    SERVICE_UPDATE_SCHEDULE,
    SOUNDS,
    SERVICE_UPDATE_AGENT,
    WEEKDAYS,
)
from . import android_maj, client_files, labels, textes
from .coordinator import PcParentalCoordinator
from .entity import oublier_appareil, service_admin
from .http import async_register as async_register_views
from .listenoire import ListeNoire
from .notoriete import Notoriete
from .virustotal import VirusTotal
from .store import PcStore

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.NOTIFY,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SWITCH,
    Platform.TEXT,
    Platform.TIME,
    Platform.UPDATE,
]

# Ces services font apparaître ou disparaître des entités : ils demandent
# un rechargement de l'intégration.
STRUCTURELS = {
    SERVICE_CREATE_PC,
    SERVICE_DELETE_PC,
    SERVICE_ADD_SCHEDULE,
    SERVICE_REMOVE_SCHEDULE,
    SERVICE_UPDATE_SCHEDULE,
    SERVICE_RENAME_PC,
    # Créer ou supprimer une étiquette fait aussi apparaître ou disparaître
    # un interrupteur par PC — mais ce rechargement-là est déclenché par
    # l'écoute du registre d'étiquettes, plus bas, qui voit aussi bien les
    # étiquettes créées depuis Paramètres. Le demander ici en plus, c'était
    # recharger deux fois pour un seul geste.
}

PC = vol.Schema({vol.Required("pc"): cv.string})
MINUTES = {vol.Optional("minutes", default=0): vol.Coerce(float)}

SCHEMA_LOCK = PC.extend(MINUTES)
SCHEMA_UNLOCK = PC.extend(MINUTES)
SCHEMA_RESET = PC
SCHEMA_CREATE_PC = vol.Schema({vol.Required("nom"): cv.string})
SCHEMA_DELETE_PC = PC
SCHEMA_ADD = PC.extend(
    {
        vol.Required("nom"): cv.string,
        vol.Required("debut"): cv.time,
        vol.Required("fin"): cv.time,
        vol.Optional("jours", default=list(WEEKDAYS)): vol.All(
            cv.ensure_list, [cv.string]
        ),
        vol.Optional("active", default=True): cv.boolean,
        vol.Optional("portee", default="session"): vol.In(
            ["session", "etiquettes"]
        ),
        vol.Optional("etiquettes"): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("elements"): vol.All(cv.ensure_list, [dict]),
        vol.Optional("sauf"): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("couleur"): vol.Any("", vol.Match(r"^#[0-9a-fA-F]{6}$")),
    }
)
SCHEMA_REMOVE = PC.extend(
    {vol.Optional("nom"): cv.string, vol.Optional("id"): vol.Coerce(int)}
)
SCHEMA_UPDATE = PC.extend(
    {
        vol.Required("id"): vol.Coerce(int),
        vol.Optional("nom"): cv.string,
        vol.Optional("debut"): cv.time,
        vol.Optional("fin"): cv.time,
        vol.Optional("jours"): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("active"): cv.boolean,
        vol.Optional("portee"): vol.In(["session", "etiquettes"]),
        vol.Optional("etiquettes"): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("elements"): vol.All(cv.ensure_list, [dict]),
        vol.Optional("sauf"): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("couleur"): vol.Any("", vol.Match(r"^#[0-9a-fA-F]{6}$")),
    }
)
SCHEMA_LISTE = PC.extend(
    {vol.Required("liste"): vol.All(cv.ensure_list, [cv.string])}
)
SCHEMA_NOTIFY = vol.Schema(
    {
        vol.Optional("pc"): cv.string,
        vol.Optional("tag"): cv.string,
        vol.Required("message"): cv.string,
        vol.Optional("titre", default=PRODUIT): cv.string,
        vol.Optional("son", default=DEFAULT_SOUND): vol.In(list(SOUNDS)),
        vol.Optional("urgent", default=False): cv.boolean,
    }
)
SCHEMA_RENAME = PC.extend({vol.Required("nom"): cv.string})

# ------------------------------------------------------------- Étiquetage
#
# Les étiquettes voyagent en `label_id` quand elles viennent du sélecteur
# natif, en clair quand elles viennent d'une automatisation écrite à la
# main. Les schémas acceptent donc du texte, et c'est `labels.noms()` qui
# tranche — un seul endroit où la question se pose.
GENRE = vol.In(["apps", "sites"])
SCHEMA_SET_TAGS = vol.Schema(
    {
        vol.Required("genre"): GENRE,
        vol.Required("nom"): cv.string,
        vol.Required("etiquettes"): vol.All(cv.ensure_list, [cv.string]),
    }
)
SCHEMA_TAG_ITEMS = vol.Schema(
    {
        vol.Required("elements"): vol.All(
            cv.ensure_list,
            [
                vol.Schema(
                    {
                        vol.Required("genre"): GENRE,
                        vol.Required("nom"): cv.string,
                    }
                )
            ],
        ),
        vol.Required("etiquettes"): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("action", default="ajouter"): vol.In(
            ["ajouter", "retirer"]
        ),
    }
)
SCHEMA_TAG = vol.Schema({vol.Required("etiquette"): cv.string})
SCHEMA_SET_PC_TAG = PC.extend(
    {
        vol.Required("etiquette"): cv.string,
        vol.Required("bloquer"): cv.boolean,
    }
)
# La maison, elle, connaît trois états : une étiquette peut être bannie
# partout, autorisée partout, ou n'avoir encore été tranchée par personne —
# et ce troisième état n'est pas le deuxième : c'est lui qui alimente la
# file de ce qui reste à classer.
# Les étiquettes **du PC lui-même** : celles qui le groupent avec les
# autres machines (Parents, Enfants) pour les notifications. Ce sont les
# étiquettes de l'appareil Home Assistant, pas celles qui classent les sites.
SCHEMA_SET_PC_LABELS = PC.extend(
    {vol.Required("etiquettes"): vol.All(cv.ensure_list, [cv.string])}
)
SCHEMA_SET_HOUSE_TAG = vol.Schema(
    {
        vol.Required("etiquette"): cv.string,
        vol.Required("decision"): vol.In(["bloquer", "autoriser", "neutre"]),
    }
)
# Le même geste que pour une étiquette, mais sur un site ou un logiciel
# nommé. La carte l'appelle sur une ligne, ou sur les douze qu'on vient
# de cocher : c'est ce qui remplace la liste déroulante et ses boutons.
SCHEMA_DECIDER_ELEMENTS = vol.Schema(
    {
        vol.Required("elements"): vol.All(
            cv.ensure_list,
            [
                vol.Schema(
                    {
                        vol.Required("genre"): GENRE,
                        vol.Required("nom"): cv.string,
                    }
                )
            ],
            vol.Length(min=1),
        ),
        vol.Required("decision"): vol.In(
            ["bloquer", "autoriser", "neutre", "retirer"]
        ),
    }
)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Met en route l’intégration."""
    # Les erreurs lues sans langue (services, entités) parlent celle de Home Assistant.
    textes.brancher(hass)
    await client_files.async_precharger(hass)

    store = PcStore(hass)
    await store.async_load()
    # 1.82.0 : le préréglage choisi à l'ajout de l'intégration, appliqué une fois.
    initiaux = (entry.data or {}).get("modules_initiaux")
    if initiaux and not store.modules:
        from . import modules as _modules

        if initiaux in _modules.PRESETS:
            store.modules = {m: (m in _modules.PRESETS[initiaux]) for m in _modules.TOUS}
            await store.async_save()
    if store.cle_changee:
        # 1.61.0 : la clé d'inscription d'avant était lisible par tout compte.
        # Elle est enregistrée juste en dessous, avec le reste du magasin.
        # 2.2.12 : aussi quand la clé était un exemple publié ou trop faible ; on le
        # signale alors à l'administrateur, pas seulement dans le journal.
        store.cle_changee = False
        logging.getLogger(__name__).warning(
            "%s : nouvelle clé d'inscription (l'ancienne ne vaut plus). Elle se lit dans "
            "Paramètres → Appareils et services → %s → Configurer.", PRODUIT, PRODUIT
        )
        from homeassistant.components import persistent_notification

        from . import textes as _textes

        persistent_notification.async_create(
            hass,
            _textes.t("cle.notif_texte", None, produit=PRODUIT),
            title=_textes.t("cle.notif_titre", None, produit=PRODUIT),
            notification_id=f"{DOMAIN}_cle_inscription",
        )
    # 1.61.0 : plus aucun jeton Home Assistant n'est remis aux appareils.
    from .veille_page import revoquer_jeton

    await revoquer_jeton(hass)
    await store.async_save()

    noire = ListeNoire(hass)
    await noire.async_load()
    # Le magasin doit savoir ce qui est déjà écarté d'office : ces
    # domaines-là n'ont rien à faire dans les listes de la maison.
    if store.poser_liste_noire(noire.domaines):
        await store.async_save()

    notoriete = Notoriete(hass)
    await notoriete.async_load()

    virustotal = VirusTotal(hass)
    await virustotal.async_load()

    coordinator = PcParentalCoordinator(
        hass, entry, store, noire, notoriete, virustotal
    )
    # L'APK publié est lu hors de la boucle, puis relu toutes les 60 s.
    await android_maj.async_rafraichir(hass)

    async def _relire_android(_maintenant) -> None:
        await android_maj.async_rafraichir(hass)

    entry.async_on_unload(
        async_track_time_interval(
            hass, _relire_android, timedelta(seconds=android_maj.INTERVALLE_S)
        )
    )
    await coordinator.async_config_entry_first_refresh()

    # 2.2.13 : les lecteurs « · Vision » de Music Assistant en MP3 (le FLAC par défaut coupait après 5 s).
    from . import musique as _mus

    async def _codec_vision(_maintenant=None) -> None:
        await _mus.regler_lecteurs_vision(hass)

    entry.async_on_unload(async_track_time_interval(hass, _codec_vision, timedelta(minutes=1)))
    # 2.4.0 : dès le démarrage, et à chaque changement d'un lecteur « · Vision » (nouvel identifiant, volume poussé par
    # Music Assistant), son volume revient à 100 % sans attendre la minute suivante.
    hass.async_create_task(_codec_vision())

    @callback
    def _volume_vision(event) -> None:
        nouveau = event.data.get("new_state")
        if nouveau is None or not str(event.data.get("entity_id") or "").startswith("media_player."):
            return
        vol = nouveau.attributes.get("volume_level")
        if isinstance(vol, (int, float)) and vol < _mus.VOLUME_VISION - 0.005 and \
                str(nouveau.attributes.get("friendly_name") or "").endswith(_mus.SUFFIXE_VISION):
            hass.async_create_task(_mus.tenir_volume(hass, nouveau.entity_id))

    entry.async_on_unload(hass.bus.async_listen(EVENT_STATE_CHANGED, _volume_vision))

    # 2.4.0 (retour d'essai) : arrêt automatique de la lecture après inactivité (Configurer › Musique).
    from . import musique_arret as _arret

    async def _arret_auto(_maintenant=None) -> None:
        try:
            await _arret.tour(hass, coordinator)
        except Exception:  # noqa: BLE001 - la musique ne doit jamais faire tomber Vision
            _LOGGER.exception("Musique : arrêt automatique")

    entry.async_on_unload(async_track_time_interval(hass, _arret_auto, timedelta(seconds=30)))

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = coordinator
    # Module Réveil — le moteur (évaluation, contrôle de synchronisation) existe avant les entités.
    from . import reveil as _reveil

    coordinator.reveil = _reveil.Moteur(hass, coordinator)
    entry.async_on_unload(coordinator.reveil.arreter)

    # « Autoriser 5 min » : la refermeture est replanifiée à chaque chargement.
    from . import inscriptions

    inscriptions.planifier(hass, coordinator)
    entry.async_on_unload(lambda: inscriptions.arreter(hass))

    # Processus, demarrage, taches, services. Le module est neuf et vit a
    # cote : s'il tombe, Vision continue sans lui.
    try:
        from .processus import GestionProcessus

        coordinator.processus = GestionProcessus(hass, coordinator)
        await coordinator.processus.async_setup()
    except Exception:  # noqa: BLE001
        logging.getLogger(__name__).exception(
            "Processus : module non charge, le reste de Vision continue."
        )
        coordinator.processus = None
    # Les cartes et le panneau voyagent avec l'intégration : servis avant que le panneau
    # de l'écran de veille ne soit déclaré (il en prend l'adresse).
    from . import cartes

    await cartes.async_installer(hass)
    async_register_views(hass)
    # Appairage par QR (1.62.0) : l'appareil demande, un administrateur valide.
    from . import appairage

    await appairage.async_installer(hass)
    # Déverrouillage à distance (1.66.0) : la clé de signature, les vues, les services.
    from . import deverrouillage

    await deverrouillage.async_installer(hass)
    # 1.69.0 : l'étiquette « Parents » et l'interrupteur global de biométrie deviennent des réglages par appareil.
    from . import roles

    _cle = deverrouillage.cle(hass)
    if roles.migrer(hass, store, bool(_cle and _cle.biometrie)):
        await store.async_save()
        if _cle is not None and _cle.biometrie:
            _cle.biometrie = False
            await _cle.async_save()
    from . import deverrouillage_ecran

    deverrouillage_ecran.enregistrer(hass)
    # Le hub « Vision » existe avant les plateformes : chaque PC s'y rattache
    # par via_device_id (le tuple via_device est supprimé en HA 2027.8).
    from .entity import assurer_hub

    assurer_hub(hass, coordinator)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    from . import matrice

    matrice.appliquer(hass, store)  # entités sans objet pour le type de l'appareil (1.72.0)
    from . import modules as _mods

    # 1.82.0 : entités des modules coupés désactivées (historique gardé), réactivées au retour du module.
    _mods.appliquer_entites(hass, store)
    await coordinator.reveil.async_demarrer()
    # Les interrupteurs par étiquette viennent d'être créés pour cette liste :
    # c'est la référence à laquelle comparer les écritures suivantes.
    coordinator._proposees = tuple(labels.proposees(hass, store))

    # Les appareils existent maintenant : les anciennes étiquettes maison
    # peuvent devenir de vraies étiquettes Home Assistant.
    if labels.migrer(hass, store.pcs):
        await store.async_save()

    # Le registre de Home Assistant fait foi pour les étiquettes. Renommée
    # dans Paramètres sans être suivie ici, une étiquette laisserait
    # derrière elle des règles qui ne visent plus rien — et qui se
    # rallumeraient le jour où quelqu'un recréerait l'ancien nom. On garde
    # donc une photo des noms, et on la compare à chaque changement.
    connues: dict[str, str] = {
        e.label_id: e.name for e in lr.async_get(hass).async_list_labels()
    }

    async def _etiquettes_changees(_event) -> None:
        maintenant = {
            e.label_id: e.name for e in lr.async_get(hass).async_list_labels()
        }
        avant, bouge, structurel = dict(connues), False, False
        connues.clear()
        connues.update(maintenant)
        for label_id, nom in avant.items():
            if label_id not in maintenant:
                structurel = True
                if store.oublier_etiquette(nom):
                    bouge = True
            elif maintenant[label_id] != nom:
                if store.renommer_etiquette(nom, maintenant[label_id]):
                    bouge = True
        # Une étiquette de plus, c'est un interrupteur de plus par PC.
        structurel = structurel or set(maintenant) - set(avant) != set()
        if bouge or structurel:
            await coordinator.async_ecrire(structurel=structurel)

    entry.async_on_unload(
        hass.bus.async_listen(
            lr.EVENT_LABEL_REGISTRY_UPDATED, _etiquettes_changees
        )
    )

    _async_register_services(hass)
    if not hass.services.has_service(DOMAIN, "valider_appairage"):
        appairage.enregistrer_services(hass)

    # Le tableau de bord ne se reconstruit **plus tout seul**. Il était
    # réécrit à chaque démarrage de Home Assistant et à chaque
    # rechargement de l'intégration : toute retouche faite à la main dans
    # l'interface disparaissait sans prévenir, et l'on ne savait jamais
    # qui avait écrasé quoi. Il se reconstruit désormais sur demande, par
    # le bouton « Régénérer le tableau de bord » ou le service
    # `pc_parental.build_dashboard` — c'est-à-dire quand on le veut.
    # 1.86.1 : exception, au démarrage — si la structure ou les textes que Vision génère ont changé depuis
    # la dernière construction (empreinte gardée dans le magasin), le tableau est refait une fois. Les
    # onglets faits à la main restent devant ; une retouche dans un onglet de Vision n'est perdue que
    # lorsqu'une nouvelle version change ce qu'elle génère.
    from homeassistant.helpers.start import async_at_started

    async def _tableau_au_demarrage(_hass: HomeAssistant) -> None:
        from .dashboard import signature

        c = hass.data.get(DOMAIN, {}).get(entry.entry_id)
        if c is None or signature(c) == c.store.signature_tableau:
            return
        if await c.async_maj_tableau(force=True):
            c.store.signature_tableau = signature(c)
            await c.store.async_save()

    entry.async_on_unload(async_at_started(hass, _tableau_au_demarrage))

    # 1.87.0 (point 14.3) : chaque nuit à 03h30, une sauvegarde automatique de la configuration (7 gardées).
    from homeassistant.helpers.event import async_track_time_change

    async def _sauvegarde_de_nuit(_maintenant) -> None:
        from . import sauvegarde

        c = hass.data.get(DOMAIN, {}).get(entry.entry_id)
        if c is None:
            return
        try:
            await sauvegarde.async_sauver(hass, c, "automatique")
        except Exception:  # noqa: BLE001 - une sauvegarde ratée ne doit rien casser ; on le dit dans le journal
            _LOGGER.exception("Vision : la sauvegarde automatique de la nuit a échoué.")

    entry.async_on_unload(async_track_time_change(hass, _sauvegarde_de_nuit, hour=3, minute=30, second=0))

    # 1.89.0 (point 14.6) : temps gagné, l'écoute des listes de tâches réglées.
    from . import temps_gagne

    async def _temps_gagne_au_demarrage(_hass: HomeAssistant) -> None:
        c = hass.data.get(DOMAIN, {}).get(entry.entry_id)
        if c is not None:
            temps_gagne.async_brancher(hass, c)

    entry.async_on_unload(async_at_started(hass, _temps_gagne_au_demarrage))
    entry.async_on_unload(lambda: temps_gagne.async_debrancher(hass))

    # 1.90.0 (point 14.7) : le bilan de la semaine, chaque dimanche à 19h00.
    from . import bilan

    async def _bilan_du_dimanche(maintenant) -> None:
        if dt_util.as_local(maintenant).weekday() != bilan.JOUR_ENVOI:
            return
        c = hass.data.get(DOMAIN, {}).get(entry.entry_id)
        if c is None:
            return
        try:
            await bilan.async_envoyer(hass, c)
        except Exception:  # noqa: BLE001 - un bilan raté ne doit rien casser ; on le dit dans le journal
            _LOGGER.exception("Vision : le bilan de la semaine n'a pas pu être envoyé.")

    entry.async_on_unload(async_track_time_change(hass, _bilan_du_dimanche, hour=bilan.HEURE_ENVOI, minute=0, second=0))
    return True



async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    decharge = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if decharge:
        hass.data[DOMAIN].pop(entry.entry_id, None)
        if not hass.data[DOMAIN]:
            for service in (
                SERVICE_LOCK,
                SERVICE_UNLOCK,
                SERVICE_RESET,
                SERVICE_CREATE_PC,
                SERVICE_DELETE_PC,
                SERVICE_ADD_SCHEDULE,
                SERVICE_REMOVE_SCHEDULE,
                SERVICE_SET_APPS,
                SERVICE_SET_SITES,
                SERVICE_BUILD_DASHBOARD,
                SERVICE_UPDATE_AGENT,
                SERVICE_NOTIFY,
                SERVICE_RENAME_PC,
                "envoyer",
                "liberer_message",
                "pause_generale",
                "fin_pause_generale",
                "musique_vider_suite",
            ):
                hass.services.async_remove(DOMAIN, service)
            from . import reveil_services

            reveil_services.retirer(hass)
    return decharge


async def async_remove_config_entry_device(
    hass: HomeAssistant, entry: ConfigEntry, device
) -> bool:
    """Autorise la suppression d'un appareil PC depuis l'interface."""
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]
    for domaine, identifiant in device.identifiers:
        if domaine == DOMAIN and identifiant.startswith("pc_"):
            coordinator.store.delete_pc(identifiant[3:])
            await coordinator.async_ecrire(structurel=True)
            return True
        if domaine == DOMAIN and identifiant.startswith("reveil_"):
            # Module Réveil : supprimer l'appareil d'un réveil dans l'interface supprime le réveil (administrateurs).
            from . import reveil

            r = reveil.reveils(coordinator.store).get(identifiant[len("reveil_"):])
            if r is not None:
                reveil.supprimer(coordinator.store, r, "interface")
                coordinator.reveil.oublier(r["id"])
                await coordinator.reveil.async_publier()
            return True
    return False


def _async_register_services(hass: HomeAssistant) -> None:
    if hass.services.has_service(DOMAIN, SERVICE_LOCK):
        return

    def _coordinator() -> PcParentalCoordinator:
        entrees = list(hass.data.get(DOMAIN, {}).values())
        if not entrees:
            raise HomeAssistantError(textes.t("commun.pas_charge", textes.ha(hass), produit=PRODUIT))
        return entrees[0]

    def _epargnes(coord: PcParentalCoordinator, pc: dict, bruts: list) -> list[str]:
        """Les appareils qu'une plage épargne, par identifiant ou par nom ; seuls ceux de la personne comptent."""
        siens = {a["id"]: a for a in coord.store._siens(pc)}
        garde: list[str] = []
        for brut in bruts or []:
            brut = str(brut).strip()
            ident = brut if brut in siens else coord.store.by_name(brut)
            if ident in siens and ident not in garde:
                garde.append(ident)
        return garde

    def _pc(coord: PcParentalCoordinator, nom: str) -> dict:
        pc_id = coord.store.by_name(nom)
        if pc_id is None:
            lg = textes.ha(hass)
            connus = ", ".join(coord.store.names()) or textes.t("init.aucun", lg)
            raise HomeAssistantError(textes.t("init.appareil_introuvable", lg, nom=nom, connus=connus))
        return coord.store.get(pc_id)

    async def _run(call: ServiceCall) -> None:
        coord = _coordinator()
        service = call.service
        data = call.data
        try:
            if service == SERVICE_LOCK:
                coord.store.set_override(
                    _pc(coord, data["pc"]), MODE_LOCKED, data.get("minutes", 0)
                )
            elif service == SERVICE_UNLOCK:
                coord.store.set_override(
                    _pc(coord, data["pc"]), MODE_OPEN, data.get("minutes", 0)
                )
            elif service == SERVICE_RESET:
                coord.store.clear_override(_pc(coord, data["pc"]))
            elif service == SERVICE_CREATE_PC:
                coord.store.create_pc(data["nom"])
            elif service == SERVICE_DELETE_PC:
                pc_id = _pc(coord, data["pc"])["id"]
                coord.store.delete_pc(pc_id)
                oublier_appareil(hass, pc_id)
            elif service == SERVICE_ADD_SCHEDULE:
                pc = _pc(coord, data["pc"])
                voulus = {j.lower()[:3] for j in data["jours"]}
                jours = [j in voulus for j in WEEKDAYS]
                if not any(jours):
                    jours = list(DAY_PRESETS["Tous les jours"])
                nouvelle = coord.store.add_rule(
                    pc["id"],
                    data["nom"],
                    data["debut"].hour * 3600 + data["debut"].minute * 60,
                    data["fin"].hour * 3600 + data["fin"].minute * 60,
                    jours,
                    bool(data.get("active", True)),
                    portee=data.get("portee", "session"),
                    # Le sélecteur natif transmet des identifiants ; on
                    # range en noms, seule forme que le reste du code lit.
                    etiquettes=labels.noms(hass, data.get("etiquettes")),
                    elements=data.get("elements"),
                )
                if data.get("couleur"):
                    coord.store.set_couleur_plage(
                        pc, nouvelle["id"], data["couleur"]
                    )
                if data.get("sauf"):
                    coord.store.update_rule(pc["id"], nouvelle["id"], sauf=_epargnes(coord, pc, data["sauf"]))
            elif service == SERVICE_REMOVE_SCHEDULE:
                pc = _pc(coord, data["pc"])
                if data.get("id") is not None:
                    rule = coord.store.rule(pc["id"], int(data["id"]))
                else:
                    if not data.get("nom"):
                        raise HomeAssistantError(textes.t("init.plage_nom_ou_id", textes.ha(hass)))
                    rule = coord.store.rule_by_name(pc["id"], data["nom"])
                if rule is None:
                    raise HomeAssistantError(textes.t("init.plage_introuvable", textes.ha(hass)))
                coord.store.remove_rule(pc["id"], rule["id"])
            elif service == SERVICE_UPDATE_SCHEDULE:
                pc = _pc(coord, data["pc"])
                champs: dict = {}
                if "nom" in data:
                    champs["name"] = data["nom"]
                if "debut" in data:
                    champs["start"] = (
                        data["debut"].hour * 3600 + data["debut"].minute * 60
                    )
                if "fin" in data:
                    champs["end"] = (
                        data["fin"].hour * 3600 + data["fin"].minute * 60
                    )
                if "jours" in data:
                    voulus = {j.lower()[:3] for j in data["jours"]}
                    champs["weekdays"] = [j in voulus for j in WEEKDAYS]
                if "active" in data:
                    champs["enabled"] = bool(data["active"])
                if "portee" in data:
                    champs["portee"] = data["portee"]
                if "etiquettes" in data:
                    champs["etiquettes"] = labels.noms(hass, data["etiquettes"])
                if "elements" in data:
                    champs["elements"] = data["elements"]
                if "sauf" in data:
                    champs["sauf"] = _epargnes(coord, pc, data["sauf"])
                if champs:
                    coord.store.update_rule(pc["id"], int(data["id"]), **champs)
                if "couleur" in data:
                    if coord.store.rule(pc["id"], int(data["id"])) is None:
                        raise HomeAssistantError(textes.t("init.plage_introuvable", textes.ha(hass)))
                    coord.store.set_couleur_plage(
                        pc, int(data["id"]), data["couleur"]
                    )
            elif service == SERVICE_SET_APPS:
                coord.store.definir_bloques(
                    _pc(coord, data["pc"]), "apps", data["liste"]
                )
            elif service == SERVICE_SET_SITES:
                coord.store.definir_bloques(
                    _pc(coord, data["pc"]), "sites", data["liste"]
                )
            elif service == SERVICE_NOTIFY:
                from . import modules as _mods_n

                _mods_n.exiger(coord.store, "messages")
                # Sans pc ni tag, le message part à tous les PC.
                cible = data.get("pc") or data.get("tag")
                for pc_id in coord.store.destinataires(cible):
                    coord.store.add_message(
                        pc_id,
                        data["message"],
                        data.get("titre", PRODUIT),
                        bool(data.get("urgent")),
                        data.get("son", DEFAULT_SOUND),
                    )
            elif service == SERVICE_SET_TAGS:
                coord.store.poser_etiquettes(
                    data["genre"], data["nom"], data["etiquettes"]
                )
            elif service == SERVICE_TAG_ITEMS:
                # L'étiquetage en lot : c'est lui qui vide la file. Poser
                # « Jeux » sur douze sites d'un coup, c'est douze décisions
                # prises en un geste — et le geste qu'on refaisait douze
                # fois était la vraie raison de ne pas étiqueter du tout.
                voulues = labels.noms(hass, data["etiquettes"])
                retirer = data.get("action") == "retirer"
                for element in data["elements"]:
                    genre, nom = element["genre"], element["nom"]
                    for etiquette in voulues:
                        if retirer:
                            coord.store.retirer_etiquette(genre, nom, etiquette)
                        else:
                            coord.store.ajouter_etiquette(genre, nom, etiquette)
            elif service == SERVICE_CREATE_TAG:
                coord.store.declarer_etiquette(data["etiquette"])
                # Créée exprès dans Vision : elle est cochée comme catégorie, pour
                # pouvoir se choisir au planning tout de suite.
                nom = labels.resoudre(hass, data["etiquette"]) or str(data["etiquette"]).strip()
                coord.store.choisir_categories({nom: True})
            elif service == SERVICE_DELETE_TAG:
                nom = labels.resoudre(hass, data["etiquette"])
                coord.store.oublier_etiquette(nom or data["etiquette"])
            elif service == SERVICE_SET_PC_TAG:
                nom = labels.resoudre(hass, data["etiquette"])
                if nom is None:
                    raise HomeAssistantError(textes.t("init.etiquette_inconnue", textes.ha(hass), nom=data['etiquette']))
                coord.store.basculer_etiquette(
                    _pc(coord, data["pc"]), nom, bool(data["bloquer"])
                )
            elif service == SERVICE_SET_PC_LABELS:
                coord.store.set_tags(
                    _pc(coord, data["pc"])["id"],
                    labels.noms(hass, data["etiquettes"]),
                )
            elif service == SERVICE_SET_HOUSE_TAG:
                nom = labels.resoudre(hass, data["etiquette"])
                if nom is None:
                    raise HomeAssistantError(textes.t("init.etiquette_inconnue", textes.ha(hass), nom=data['etiquette']))
                if data["decision"] == "neutre":
                    coord.store.oublier_maison(nom)
                else:
                    coord.store.decider_maison(
                        nom, data["decision"] == "bloquer"
                    )
            elif service == SERVICE_DECIDER_ELEMENTS:
                # Un nom banni pour la maison l'est pour de bon : c'est
                # l'unique décision sans appel du système. « retirer » ne
                # touche qu'aux inscriptions faites à la main — ce qu'une
                # machine a constaté reviendra au prochain relevé.
                decision = data["decision"]
                for element in data["elements"]:
                    nom = element["nom"]
                    if decision == "retirer":
                        if not coord.store.retirer_du_catalogue(
                            element["genre"], nom
                        ):
                            raise textes.Erreur("init.pas_inscrit", nom=nom)
                    elif decision == "neutre":
                        coord.store.oublier_maison(nom)
                    else:
                        coord.store.decider_maison(nom, decision == "bloquer")
            elif service == SERVICE_RENAME_PC:
                coord.store.rename_pc(_pc(coord, data["pc"])["id"], data["nom"])
            elif service == SERVICE_UPDATE_AGENT:
                nom = data.get("pc")
                cibles = (
                    [_pc(coord, nom)] if nom else list(coord.store.pcs.values())
                )
                for cible in cibles:
                    cible["update_asked"] = True
            elif service == SERVICE_BUILD_DASHBOARD:
                await coord.async_maj_tableau(force=True)
                return
        except (ValueError, KeyError) as err:
            raise HomeAssistantError(str(err)) from err
        await coord.async_ecrire(structurel=service in STRUCTURELS)

    def _personne(valeur: str) -> str:
        """« person.lou », ou un prénom : rend l'entité de la personne."""
        valeur = str(valeur or "").strip()
        if valeur.startswith("person."):
            if hass.states.get(valeur) is None:
                raise HomeAssistantError(textes.t("init.personne_introuvable", textes.ha(hass), nom=valeur))
            return valeur
        for etat in hass.states.async_all("person"):
            if str(etat.name or "").strip().lower() == valeur.lower() or etat.entity_id.split(".", 1)[1] == valeur.lower():
                return etat.entity_id
        raise HomeAssistantError(textes.t("init.personne_introuvable", textes.ha(hass), nom=valeur))

    async def _categorie(call: ServiceCall) -> None:
        coord = _coordinator()
        personne = _personne(call.data["personne"])
        categories = []
        for brut in call.data.get("categorie") or []:
            nom = labels.resoudre(hass, brut)
            if nom is None:
                raise HomeAssistantError(textes.t("init.categorie_inconnue", textes.ha(hass), nom=brut))
            categories.append(nom)
        # La source : le nom que l'automatisation se donne (« moyenne »). Une catégorie reste
        # fermée tant qu'une source la ferme ; rouvrir ne lève que la sienne. Sans source,
        # tout se range sous la source vide, comme avant la 1.60.0.
        source = str(call.data.get("source") or "").strip()
        change = False
        if call.service == "fermer_categorie":
            for c in categories:
                change = coord.store.fermer_categorie(personne, c, call.data.get("raison", ""), source) or change
        elif categories:
            for c in categories:
                change = bool(coord.store.ouvrir_categorie(personne, c, source)) or change
        else:
            change = bool(coord.store.ouvrir_categorie(personne, None, source))
        if change:
            await coord.async_ecrire(structurel=False)

    service_admin(hass, "fermer_categorie", _categorie, schema=vol.Schema({
        vol.Required("personne"): cv.string,
        vol.Required("categorie"): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("raison", default=""): cv.string,
        vol.Optional("source", default=""): cv.string,
    }))
    service_admin(hass, "ouvrir_categorie", _categorie, schema=vol.Schema({
        vol.Required("personne"): cv.string,
        vol.Optional("categorie", default=list): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("source", default=""): cv.string,
    }))

    def _appareils(coord: PcParentalCoordinator, personne: str, noms: list[str]) -> list[dict]:
        """Les appareils nommés, ou tous ceux de la personne quand rien n'est précisé."""
        siens = coord.store.appareils_de(personne)
        if not noms:
            return siens
        cibles = []
        for nom in noms:
            pc = coord.store.pcs.get(nom) or _pc(coord, nom)
            if pc not in cibles:
                cibles.append(pc)
        return cibles

    async def _temps(call: ServiceCall) -> None:
        coord = _coordinator()
        personne = _personne(call.data["personne"])
        noms = [str(n).strip() for n in (call.data.get("appareils") or []) if str(n).strip()]
        cibles = _appareils(coord, personne, noms)
        if not cibles:
            raise HomeAssistantError(textes.t("init.aucun_appareil", textes.ha(hass)))
        minutes = int(call.data["minutes"])
        for pc in cibles:
            fin = coord.store.ajouter_temps(pc, minutes)
            from . import modules as _mods_t

            if _mods_t.coupe(coord.store, "messages"):
                continue  # 1.82.0 : l'avis « N min en plus » est un message
            lg = textes.pour(hass, pc)
            try:
                coord.store.add_message(
                    pc["id"],
                    textes.t("init.temps_plus", lg, minutes=minutes, heure=textes.heure(fin, lg)),
                    titre=PRODUIT,
                )
            except ValueError:
                pass
        await coord.async_ecrire(structurel=False)

    service_admin(hass, "ajouter_temps", _temps, schema=vol.Schema({
        vol.Required("personne"): cv.string,
        vol.Required("minutes"): vol.All(vol.Coerce(int), vol.Range(min=1, max=720)),
        vol.Optional("appareils", default=list): vol.All(cv.ensure_list, [cv.string]),
    }))

    async def _limite(call: ServiceCall) -> None:
        coord = _coordinator()
        personne = _personne(call.data["personne"])
        siens = [pc for pc in coord.store.pcs.values() if str(pc.get("person") or "") == personne]
        if not siens:
            raise HomeAssistantError(textes.t("init.pas_appareil_propre", textes.ha(hass)))
        try:
            change = coord.store.poser_limite(siens[0], call.data["logiciel"], int(call.data["minutes"]))
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err
        if change:
            await coord.async_ecrire(structurel=False)

    service_admin(hass, "limiter_temps", _limite, schema=vol.Schema({
        vol.Required("personne"): cv.string,
        vol.Required("logiciel"): cv.string,
        vol.Required("minutes"): vol.All(vol.Coerce(int), vol.Range(min=0, max=1440)),
    }))

    # 1.88.0 (point 14.5) : le mode concentration, pour les automatisations (« devoirs de 17h à 18h »).
    async def _concentration(call: ServiceCall) -> None:
        from . import concentration
        from . import modules as _mods_c

        coord = _coordinator()
        if _mods_c.coupe(coord.store, "concentration") or _mods_c.coupe(coord.store, "blocage"):
            raise HomeAssistantError(textes.t("init.concentration_coupee", textes.ha(hass)))
        personne = _personne(call.data["personne"])
        noms = [str(n).strip() for n in (call.data.get("appareils") or []) if str(n).strip()]
        cibles = _appareils(coord, personne, noms)
        if not cibles:
            raise HomeAssistantError(textes.t("init.aucun_appareil", textes.ha(hass)))
        applis = [str(a).strip() for a in (call.data.get("applis") or []) if str(a).strip()] or None
        avis = not _mods_c.coupe(coord.store, "messages")
        for pc in cibles:
            lg = textes.pour(hass, pc)
            if call.data.get("arreter"):
                if concentration.arreter(pc) and avis:
                    texte = textes.t("conc.fin", lg)
                else:
                    continue
            else:
                c = concentration.demarrer(coord.store, pc, int(call.data["minutes"]), applis, "Home Assistant")
                texte = textes.t("conc.jusqua", lg, heure=concentration.heure(c["fin"], lg))
            if avis:
                try:
                    coord.store.add_message(pc["id"], texte, titre=PRODUIT)
                except ValueError:
                    pass
        await coord.async_ecrire(structurel=False)

    async def _bilan_maintenant(call: ServiceCall) -> None:
        from . import bilan

        coord = _coordinator()
        envoye = await bilan.async_envoyer(hass, coord, remise_a_zero=bool(call.data.get("remettre_a_zero")))
        if not envoye:
            raise HomeAssistantError(textes.t("init.pas_de_bilan", textes.ha(hass)))

    service_admin(hass, "bilan_hebdo", _bilan_maintenant, schema=vol.Schema({
        vol.Optional("remettre_a_zero", default=False): cv.boolean,
    }))

    service_admin(hass, "concentration", _concentration, schema=vol.Schema({
        vol.Required("personne"): cv.string,
        vol.Optional("minutes", default=60): vol.All(vol.Coerce(int), vol.Range(min=1, max=360)),
        vol.Optional("applis", default=list): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("appareils", default=list): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("arreter", default=False): cv.boolean,
    }))

    async def _partage(call: ServiceCall) -> None:
        coord = _coordinator()
        pc = _pc(coord, call.data["pc"])
        personnes = [_personne(p) for p in (call.data.get("personnes") or []) if str(p).strip()]
        coord.store.definir_partage(pc, personnes, bool(call.data.get("tout_le_monde")))
        await coord.async_ecrire(structurel=False)

    service_admin(hass, "partager_appareil", _partage, schema=vol.Schema({
        vol.Required("pc"): cv.string,
        vol.Optional("personnes", default=list): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional("tout_le_monde", default=False): cv.boolean,
    }))

    for service, schema in (
        (SERVICE_LOCK, SCHEMA_LOCK),
        (SERVICE_UNLOCK, SCHEMA_UNLOCK),
        (SERVICE_RESET, SCHEMA_RESET),
        (SERVICE_CREATE_PC, SCHEMA_CREATE_PC),
        (SERVICE_DELETE_PC, SCHEMA_DELETE_PC),
        (SERVICE_ADD_SCHEDULE, SCHEMA_ADD),
        (SERVICE_REMOVE_SCHEDULE, SCHEMA_REMOVE),
        (SERVICE_UPDATE_SCHEDULE, SCHEMA_UPDATE),
        (SERVICE_SET_APPS, SCHEMA_LISTE),
        (SERVICE_SET_SITES, SCHEMA_LISTE),
        (SERVICE_BUILD_DASHBOARD, vol.Schema({})),
        (SERVICE_UPDATE_AGENT, vol.Schema({vol.Optional("pc"): cv.string})),
        (SERVICE_NOTIFY, SCHEMA_NOTIFY),
        (SERVICE_RENAME_PC, SCHEMA_RENAME),
        (SERVICE_SET_TAGS, SCHEMA_SET_TAGS),
        (SERVICE_TAG_ITEMS, SCHEMA_TAG_ITEMS),
        (SERVICE_CREATE_TAG, SCHEMA_TAG),
        (SERVICE_DELETE_TAG, SCHEMA_TAG),
        (SERVICE_SET_PC_TAG, SCHEMA_SET_PC_TAG),
        (SERVICE_SET_HOUSE_TAG, SCHEMA_SET_HOUSE_TAG),
        (SERVICE_SET_PC_LABELS, SCHEMA_SET_PC_LABELS),
        (SERVICE_DECIDER_ELEMENTS, SCHEMA_DECIDER_ELEMENTS),
    ):
        service_admin(hass, service, _run, schema=schema)

    # Messages riches (1.67.0, docs/messages.md).
    from . import messages

    async def _envoyer(call: ServiceCall) -> ServiceResponse:
        coord = _coordinator()
        from . import modules as _mods_e

        _mods_e.exiger(coord.store, "messages")
        try:
            return await messages.async_envoyer(hass, coord, dict(call.data))
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err

    async def _liberer(call: ServiceCall) -> None:
        coord = _coordinator()
        pc = _pc(coord, call.data["pc"])
        try:
            messages.liberer(coord.store, pc["id"], call.data.get("id"))
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err
        logging.getLogger(__name__).warning(
            "Message bloquant sur « %s » : libération demandée depuis Home Assistant.", pc.get("name"))
        await coord.async_ecrire()

    service_admin(hass, messages.SERVICE_ENVOYER, _envoyer, schema=messages.SCHEMA_ENVOYER,
                  supports_response=SupportsResponse.OPTIONAL)
    service_admin(hass, messages.SERVICE_LIBERER, _liberer, schema=messages.SCHEMA_LIBERER)

    # Pause générale (point 14.2) : ferme d'un coup tous les appareils protégés, puis les rouvre.
    from . import modules as _mods_pg
    from . import pause_generale as _pause_g

    async def _qui(call: ServiceCall) -> str:
        if call.context.user_id:
            utilisateur = await hass.auth.async_get_user(call.context.user_id)
            if utilisateur is not None and utilisateur.name:
                return str(utilisateur.name)
        return "Home Assistant"

    async def _pause_generale(call: ServiceCall) -> None:
        coord = _coordinator()
        _mods_pg.exiger(coord.store, _pause_g.MODULE)
        try:
            await _pause_g.async_poser(hass, coord, int(call.data["minutes"]), await _qui(call))
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err

    async def _fin_pause_generale(call: ServiceCall) -> None:
        coord = _coordinator()
        _mods_pg.exiger(coord.store, _pause_g.MODULE)
        await _pause_g.async_reprendre(hass, coord, await _qui(call))

    service_admin(hass, "pause_generale", _pause_generale, schema=vol.Schema({
        vol.Required("minutes"): vol.All(vol.Coerce(int), vol.Range(min=_pause_g.MINUTES_MIN, max=_pause_g.MINUTES_MAX)),
    }))
    service_admin(hass, "fin_pause_generale", _fin_pause_generale, schema=vol.Schema({}))

    async def _musique_vider_suite(call: ServiceCall) -> None:
        """2.2.10 (retour d'essai) : vider la suite d'une file Music Assistant (le titre en cours continue)."""
        from . import musique as _musique
        try:
            for eid in call.data["entity_id"]:
                await _musique.vider_suite(hass, eid)
        except _musique.MusiqueIndisponible as err:
            raise HomeAssistantError(str(err)) from err

    service_admin(hass, "musique_vider_suite", _musique_vider_suite, schema=vol.Schema({
        vol.Required("entity_id"): cv.entity_ids,
    }))

    # Module Réveil — services pc_parental.reveil_* (droits du Réveil, pas « administrateurs seulement »).
    from . import reveil_services

    reveil_services.enregistrer(hass, _coordinator)
