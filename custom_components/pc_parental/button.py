"""Boutons d'action, par PC et pour la gestion."""

from __future__ import annotations

import time

from homeassistant.components import persistent_notification
from homeassistant.components.button import ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import (
    AUCUNE_ADRESSE,
    AUCUNE_ORIGINE,
    AUCUN_CONSTAT,
    DAY_PRESETS,
    DEFAULT_DAYS,
    DEFAULT_SOUND,
    DOMAIN,
    MODE_LOCKED,
    MODE_OPEN,
    PORTEE_ETIQUETTES,
    PRODUIT,
)
from .coordinator import PcParentalCoordinator
from .entity import (
    EntityIdFige,
    HubEntity,
    PcEntity,
    appliquer,
    erreur,
    oublier_appareil,
    prune_stale,
)
from . import textes


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]

    entites: list[ButtonEntity] = [
        CreatePcButton(coordinator),
        DeletePcButton(coordinator),
        CreateRuleButton(coordinator),
        DeleteRuleButton(coordinator),
        RebuildDashboardButton(coordinator),
        SendMessageButton(coordinator),
        BloquerLogicielButton(coordinator),
        AutoriserLogicielButton(coordinator),
        SansRegleLogicielButton(coordinator),
        BloquerSiteButton(coordinator),
        AutoriserSiteButton(coordinator),
        SansRegleSiteButton(coordinator),
        BloquerLogicielAutoriseButton(coordinator),
        DeclasserLogicielAutoriseButton(coordinator),
        AutoriserLogicielBloqueButton(coordinator),
        DeclasserLogicielBloqueButton(coordinator),
        BloquerSiteAutoriseButton(coordinator),
        DeclasserSiteAutoriseButton(coordinator),
        AutoriserSiteBloqueButton(coordinator),
        DeclasserSiteBloqueButton(coordinator),
        BloquerOrigineButton(coordinator),
        EpargnerAdresseButton(coordinator),
        RetirerAdresseButton(coordinator),
        LaisserPasserButton(coordinator),
        FermerLaissezPasserButton(coordinator),
        InscrireSiteButton(coordinator),
        InscrireLogicielButton(coordinator),
        RetirerSiteButton(coordinator),
        RetirerLogicielButton(coordinator),
        RafraichirListeNoireButton(coordinator),
        RafraichirNotorieteButton(coordinator),
        PagePrecedenteButton(coordinator),
        PageSuivanteButton(coordinator),
        PauseGenerale30Button(coordinator),
        FinPauseGeneraleButton(coordinator),
    ]
    for pc_id in coordinator.pcs:
        entites.append(LockNowButton(coordinator, pc_id))
        entites.append(Open30Button(coordinator, pc_id))
        entites.append(BackToPlanningButton(coordinator, pc_id))
        entites.append(DeleteThisPcButton(coordinator, pc_id))
        entites.append(ReinscriptionButton(coordinator, pc_id))
    valides = {
        f"{DOMAIN}_{cle}_{pc}"
        for pc in coordinator.pcs
        for cle in ("verrouiller", "ouvrir30", "planning", "oublier", "reinscription")
    } | {
        f"{DOMAIN}_creer_pc",
        f"{DOMAIN}_supprimer_pc",
        f"{DOMAIN}_creer_plage",
        f"{DOMAIN}_supprimer_plage",
        f"{DOMAIN}_tableau",
        f"{DOMAIN}_envoyer",
        f"{DOMAIN}_bloquer_logiciel",
        f"{DOMAIN}_autoriser_logiciel",
        f"{DOMAIN}_bloquer_site",
        f"{DOMAIN}_autoriser_site",
        f"{DOMAIN}_sans_regle_logiciel",
        f"{DOMAIN}_sans_regle_site",
        f"{DOMAIN}_bloquer_logiciel_autorise",
        f"{DOMAIN}_declasser_logiciel_autorise",
        f"{DOMAIN}_autoriser_logiciel_bloque",
        f"{DOMAIN}_declasser_logiciel_bloque",
        f"{DOMAIN}_bloquer_site_autorise",
        f"{DOMAIN}_declasser_site_autorise",
        f"{DOMAIN}_autoriser_site_bloque",
        f"{DOMAIN}_declasser_site_bloque",
        f"{DOMAIN}_rafraichir_notoriete",
        f"{DOMAIN}_bloquer_origine",
        f"{DOMAIN}_epargner_adresse",
        f"{DOMAIN}_retirer_adresse",
        f"{DOMAIN}_laisser_passer",
        f"{DOMAIN}_fermer_laissez_passer",
        f"{DOMAIN}_inscrire_site",
        f"{DOMAIN}_inscrire_logiciel",
        f"{DOMAIN}_retirer_site",
        f"{DOMAIN}_retirer_logiciel",
        f"{DOMAIN}_rafraichir_liste_noire",
        f"{DOMAIN}_page_precedente",
        f"{DOMAIN}_page_suivante",
        f"{DOMAIN}_pause_generale_30",
        f"{DOMAIN}_pause_generale_fin",
    }
    prune_stale(hass, coordinator, "button", f"{DOMAIN}_", valides)

    async_add_entities(entites)


# --------------------------------------------------------------- Par PC


class LockNowButton(PcEntity, ButtonEntity):
    _attr_translation_key = "verrouiller_maintenant"
    _attr_icon = "mdi:lock"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_verrouiller_{pc_id}"

    async def async_press(self) -> None:
        await appliquer(
            self.coordinator,
            lambda: self.coordinator.store.set_override(self.pc, MODE_LOCKED),
        )


class ReinscriptionButton(PcEntity, ButtonEntity):
    """Permet à cet appareil, déjà inscrit, de se réinscrire (appli réinstallée).

    Pendant quelques minutes, il reçoit un secret **neuf** à l'inscription ;
    l'ancien cesse de valoir. Sans ce geste d'un administrateur, une
    inscription sous le nom d'un appareil connu est refusée : elle ne rend
    jamais le secret d'un autre.
    """

    _attr_translation_key = "autoriser_une_reinscription"
    _attr_icon = "mdi:cellphone-key"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_reinscription_{pc_id}"

    async def async_press(self) -> None:
        await appliquer(
            self.coordinator,
            lambda: self.coordinator.store.ouvrir_reinscription(self.pc_id),
        )


class Open30Button(PcEntity, ButtonEntity):
    _attr_translation_key = "ouvrir_30_min"
    _attr_icon = "mdi:lock-clock"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_ouvrir30_{pc_id}"

    async def async_press(self) -> None:
        await appliquer(
            self.coordinator,
            lambda: self.coordinator.store.set_override(self.pc, MODE_OPEN, 30),
        )


class BackToPlanningButton(PcEntity, ButtonEntity):
    _attr_translation_key = "revenir_au_planning"
    _attr_icon = "mdi:calendar-sync"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_planning_{pc_id}"

    async def async_press(self) -> None:
        await appliquer(
            self.coordinator,
            lambda: self.coordinator.store.clear_override(self.pc),
        )


# ------------------------------------------------------------- Gestion


def _etat(coordinator: PcParentalCoordinator, platform: str, unique_id: str):
    """Retrouve l'état d'une entité de gestion par son identifiant unique."""
    registry = er.async_get(coordinator.hass)
    entity_id = registry.async_get_entity_id(platform, DOMAIN, unique_id)
    if not entity_id:
        return None
    etat = coordinator.hass.states.get(entity_id)
    if etat is None or etat.state in ("unknown", "unavailable"):
        return None
    return etat


def _saisie(coordinator: PcParentalCoordinator, cle: str) -> str:
    """Valeur d'un champ texte de gestion."""
    etat = _etat(coordinator, "text", f"{DOMAIN}_saisie_{cle}")
    return etat.state if etat else ""


def _heure(coordinator: PcParentalCoordinator, bord: str) -> int:
    etat = _etat(coordinator, "time", f"{DOMAIN}_new_rule_{bord}")
    if not etat or ":" not in (etat.state or ""):
        raise erreur("heure_debut_manquante" if bord == "debut" else "heure_fin_manquante")
    h, m = etat.state.split(":")[:2]
    return int(h) * 3600 + int(m) * 60


def _jours(coordinator: PcParentalCoordinator) -> list[bool]:
    etat = _etat(coordinator, "select", f"{DOMAIN}_jours")
    choix = etat.state if etat else DEFAULT_DAYS
    return list(DAY_PRESETS.get(choix, DAY_PRESETS[DEFAULT_DAYS]))


def _portee(coordinator: PcParentalCoordinator) -> str:
    """« session » ou « etiquettes », lu depuis le sélecteur de portée."""
    etat = _etat(coordinator, "select", f"{DOMAIN}_portee_plage")
    choix = etat.state if etat else ""
    return "etiquettes" if choix == PORTEE_ETIQUETTES else "session"


def _etiquettes_plage(coordinator: PcParentalCoordinator) -> list[str]:
    """Les étiquettes saisies, séparées par des virgules.

    On accepte la saisie libre plutôt qu'une liste déroulante : il faut
    pouvoir en nommer plusieurs. Une étiquette inconnue ou non cochée comme
    catégorie est refusée par le magasin, qui dit où la cocher (1.60.0).
    """
    brut = _saisie(coordinator, "etiquettes_plage")
    return [m.strip() for m in brut.replace(";", ",").split(",") if m.strip()]


def _cible(coordinator: PcParentalCoordinator) -> str:
    pc_id = coordinator.cible
    if not pc_id or pc_id not in coordinator.pcs:
        raise erreur("appareil_cible_manquant")
    return pc_id


class SendMessageButton(HubEntity, ButtonEntity):
    """Envoie le message au destinataire choisi, puis vide le champ."""

    _attr_translation_key = "envoyer_le_message"
    _attr_icon = "mdi:send"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_envoyer"

    async def async_press(self) -> None:
        coord = self.coordinator
        from . import modules

        modules.exiger(coord.store, "messages")
        champ = _etat(coord, "text", f"{DOMAIN}_saisie_message")
        texte = (champ.state if champ else "").strip()
        if not texte:
            raise erreur("message_vide")

        dest = _etat(coord, "select", f"{DOMAIN}_destinataire")
        cible = dest.state if dest else None
        son_etat = _etat(coord, "select", f"{DOMAIN}_son")
        son = son_etat.state if son_etat else DEFAULT_SOUND

        try:
            vises = coord.store.destinataires(cible)
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err
        if not vises:
            raise erreur("destinataire_vide")

        absents = [
            coord.pc_name(p) for p in vises if not coord.agent_online(p)
        ]

        def _faire() -> None:
            for pc_id in vises:
                coord.store.add_message(
                    pc_id, texte, PRODUIT, False, son
                )

        await appliquer(coord, _faire)

        if champ:
            await coord.hass.services.async_call(
                "text",
                "set_value",
                {"entity_id": champ.entity_id, "value": ""},
                blocking=True,
            )

        if absents:
            raise erreur("message_en_attente", appareils=", ".join(absents))


# HA-01 : un retrait d'appareil ne se fait jamais en un appui. Le premier appui arme le bouton et le dit
# (notification persistante) ; seul un second appui dans ce délai retire l'appareil.
DELAI_CONFIRMATION_RETRAIT = 15  # secondes
_horloge = time.monotonic  # remplaçable par les essais


class _RetraitEnDeuxAppuis:
    """Premier appui : avertir ; second appui, sur le même appareil, dans les 15 s : retirer."""

    _arme: tuple[str, float] | None = None

    def _notification_id(self, pc_id: str) -> str:
        return f"{DOMAIN}_retrait_{pc_id}"

    async def _retirer_en_deux_appuis(self, pc_id: str) -> None:
        coord = self.coordinator  # type: ignore[attr-defined]
        hass = coord.hass
        maintenant = _horloge()
        arme, self._arme = self._arme, None
        if arme is None or arme[0] != pc_id or maintenant - arme[1] > DELAI_CONFIRMATION_RETRAIT:
            self._arme = (pc_id, maintenant)
            lg = textes.ha(hass)
            nom = coord.pc_name(pc_id) or pc_id
            persistent_notification.async_create(
                hass, textes.t("retrait.confirmer", lg, appareil=nom),
                title=textes.t("retrait.titre", lg, appareil=nom), notification_id=self._notification_id(pc_id),
            )
            return
        persistent_notification.async_dismiss(hass, self._notification_id(pc_id))

        def _faire() -> None:
            coord.store.delete_pc(pc_id)
            oublier_appareil(hass, pc_id)

        await appliquer(coord, _faire, structurel=True)


class DeleteThisPcButton(_RetraitEnDeuxAppuis, PcEntity, ButtonEntity):
    """Retire ce PC, ses plages et son appareil Home Assistant (en deux appuis, HA-01).

    L'agent installé sur la machine n'est pas désinstallé : il se
    réinscrira au prochain démarrage tant que les inscriptions sont
    ouvertes. Pour l'enlever pour de bon, lancer uninstall.ps1 sur le PC.

    Désactivé par défaut sur une installation neuve : le retrait se fait par « Retirer… » dans la carte de la
    personne (nom à taper). Une entité déjà enregistrée garde son état (le registre fait foi).
    """

    _attr_translation_key = "supprimer_cet_appareil"
    _attr_icon = "mdi:delete-forever"
    _attr_entity_category = EntityCategory.CONFIG
    _attr_entity_registry_enabled_default = False

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_oublier_{pc_id}"

    async def async_press(self) -> None:
        await self._retirer_en_deux_appuis(self.pc_id)


class CreatePcButton(HubEntity, ButtonEntity):
    """Créer un PC à la main, avant même d'installer son agent."""

    _attr_translation_key = "creer_l_appareil"
    _attr_icon = "mdi:desktop-tower"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_creer_pc"

    async def async_press(self) -> None:
        nom = _saisie(self.coordinator, "nom_pc").strip()
        if not nom:
            raise erreur("nom_appareil_manquant")
        await appliquer(
            self.coordinator,
            lambda: self.coordinator.store.create_pc(nom),
            structurel=True,
        )


class DeletePcButton(_RetraitEnDeuxAppuis, HubEntity, ButtonEntity):
    """Retire l'appareil choisi dans « Appareil ciblé », en deux appuis (HA-01).

    Entité gardée (automatisations, tableaux existants) ; elle n'est plus sur le tableau généré.
    """

    _attr_translation_key = "supprimer_l_appareil_cible"
    _attr_icon = "mdi:desktop-tower-monitor"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_supprimer_pc"

    async def async_press(self) -> None:
        await self._retirer_en_deux_appuis(_cible(self.coordinator))


class CreateRuleButton(HubEntity, ButtonEntity):
    _attr_translation_key = "creer_la_plage"
    _attr_icon = "mdi:calendar-plus"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_creer_plage"

    async def async_press(self) -> None:
        coord = self.coordinator
        nom = _saisie(coord, "nom_plage").strip()
        if not nom:
            raise erreur("nom_plage_manquant")
        debut = _heure(coord, "debut")
        fin = _heure(coord, "fin")
        jours = _jours(coord)
        portee = _portee(coord)
        etiquettes = _etiquettes_plage(coord) if portee == "etiquettes" else []
        if portee == "etiquettes" and not etiquettes:
            raise erreur("plage_etiquettes_manquantes")

        # Sans PC choisi, la plage est posée sur **toutes** les machines.
        # C'est le cas courant : l'heure du coucher ne se négocie pas
        # machine par machine, et la poser quatre fois serait quatre
        # occasions de se tromper d'une demi-heure.
        cibles = [coord.cible] if coord.cible else list(coord.pcs)
        if not cibles:
            raise erreur("aucun_appareil")

        def _faire() -> None:
            for pc_id in cibles:
                coord.store.add_rule(
                    pc_id, nom, debut, fin, jours, True, portee, etiquettes
                )

        await appliquer(coord, _faire, structurel=True)


class DeleteRuleButton(HubEntity, ButtonEntity):
    _attr_translation_key = "supprimer_la_plage"
    _attr_icon = "mdi:calendar-remove"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_supprimer_plage"

    async def async_press(self) -> None:
        coord = self.coordinator
        libelle = coord.plage_cible
        if not libelle:
            raise erreur("plage_a_supprimer_manquante")
        # Symétrique de la création : sans PC choisi, la plage se retire de
        # **toutes** les machines où elle a été posée. Exiger une machine
        # pour supprimer ce qu'on a posé partout, c'était laisser une plage
        # impossible à retirer sans comprendre pourquoi.
        if coord.cible:
            rule = coord.rule_by_label(coord.cible, libelle)
            cibles = [(coord.cible, rule["id"])] if rule else []
        else:
            cibles = coord.plages_par_libelle(libelle)
        if not cibles:
            raise erreur("plage_disparue")

        def _faire() -> None:
            for pc_id, rule_id in cibles:
                coord.store.remove_rule(pc_id, rule_id)

        await appliquer(coord, _faire, structurel=True)


class BloquerOrigineButton(HubEntity, ButtonEntity):
    """Bloque le programme qui a ouvert ce site, pour toute la maison.

    Couper le domaine arrête la fuite ; couper le programme arrête la
    source. Quand un logiciel qu'on ne connaît pas parle à un domaine
    qu'on ne connaît pas, c'est lui qu'on veut voir s'arrêter.
    """

    _attr_translation_key = "bloquer_ce_programme"
    _attr_icon = "mdi:application-cog-outline"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_bloquer_origine"

    async def async_press(self) -> None:
        coord = self.coordinator
        etat = _etat(coord, "select", f"{DOMAIN}_origine_cible")
        programme = etat.state if etat else ""
        if not programme or programme == AUCUNE_ORIGINE:
            raise erreur("programme_manquant")
        if coord.store.est_intouchable(programme):
            raise erreur("programme_intouchable", programme=programme)

        def _faire() -> None:
            coord.store.ajouter_au_catalogue("apps", programme)
            coord.store.decider_maison(programme, True)

        await appliquer(coord, _faire)


async def _auteur(entite) -> str:
    """Le nom de qui appuie (compte Home Assistant), sinon « Home Assistant » (automatisation)."""
    user_id = getattr(getattr(entite, "_context", None), "user_id", None)
    if user_id:
        utilisateur = await entite.hass.auth.async_get_user(user_id)
        if utilisateur is not None and utilisateur.name:
            return str(utilisateur.name)
    return "Home Assistant"


class PauseGenerale30Button(EntityIdFige, HubEntity, ButtonEntity):
    """« Tout fermer » (pause générale, point 14.2) : ferme tous les appareils protégés pour 30 minutes."""

    _attr_translation_key = "pause_generale_30_min"
    _plateforme_entity_id = "button"
    _attr_icon = "mdi:silverware-fork-knife"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_pause_generale_30"

    async def async_press(self) -> None:
        from . import modules, pause_generale

        modules.exiger(self.coordinator.store, pause_generale.MODULE)
        try:
            await pause_generale.async_poser(self.hass, self.coordinator, 30, await _auteur(self))
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err


class FinPauseGeneraleButton(EntityIdFige, HubEntity, ButtonEntity):
    """« Rouvrir » (fin de la pause générale) : les appareils qu'elle tenait fermés reviennent à leur planning."""

    _attr_translation_key = "fin_de_la_pause_generale"
    _plateforme_entity_id = "button"
    _attr_icon = "mdi:play-circle-outline"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_pause_generale_fin"

    async def async_press(self) -> None:
        from . import modules, pause_generale

        modules.exiger(self.coordinator.store, pause_generale.MODULE)
        await pause_generale.async_reprendre(self.hass, self.coordinator, await _auteur(self))


class RebuildDashboardButton(HubEntity, ButtonEntity):
    """Reconstruit le tableau de bord à la demande."""

    _attr_translation_key = "regenerer_le_tableau_de_bord"
    _attr_icon = "mdi:view-dashboard-edit"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_tableau"

    async def async_press(self) -> None:
        if not await self.coordinator.async_maj_tableau(force=True):
            raise erreur("tableau_echec")


class _RegleButton(HubEntity, ButtonEntity):
    """Pose ou retire une règle sur le PC ciblé.

    Une règle porte soit sur un nom précis (un logiciel, un domaine), soit
    sur une étiquette Vision — auquel cas elle vaut pour tout ce qui la
    porte. Le nom l'emporte toujours sur l'étiquette : « bloque les jeux,
    sauf minecraft » s'écrit ainsi en deux gestes.

    Ce qui n'est ni bloqué ni autorisé reste **autorisé** : on n'enferme pas
    par défaut.
    """

    _selecteur: str = ""
    _vide: str = ""
    _action: str = "bloquer"  # bloquer | autoriser | oublier

    async def async_press(self) -> None:
        coord = self.coordinator
        etat = _etat(coord, "select", self._selecteur)
        valeur = (etat.state if etat else "").strip()
        if not valeur or valeur == self._vide:
            raise erreur("rien_selectionne")

        action = self._action

        # Les listes tranchent pour **toute la maison**. On parle de ce qui
        # est permis chez soi : le décider quatre fois serait quatre
        # occasions d'en oublier une, et la machine installée l'an prochain
        # serait ouverte sans que personne n'y pense.
        #
        # Une machine peut toujours faire exception — cela se pose sur sa
        # carte, dans « Règles de ce PC », et cela l'emporte : le plus
        # précis gagne.
        # Redire ce qui est déjà dit ne change rien, et ne doit donc rien
        # signaler. La version d'avant levait une erreur — « acer.com est
        # déjà autorisé pour la maison » — et comme la liste ne faisait pas
        # sortir les éléments autorisés, appuyer deux fois arrivait tout le
        # temps. Quatre bandeaux rouges pour une action sans conséquence :
        # l'utilisateur croyait avoir cassé quelque chose.
        #
        # Les listes tranchent pour **toute la maison**. Une machine peut
        # toujours faire exception — cela se pose sur sa carte, et cela
        # l'emporte : le plus précis gagne.
        def _faire() -> None:
            if action == "oublier":
                coord.store.oublier_maison(valeur)
                return
            coord.store.decider_maison(valeur, action == "bloquer")

        await appliquer(coord, _faire)

# Chaque liste a ses propres boutons, et ils ne peuvent rien faire d'autre
# que ce que leur liste permet. Un bouton unique agissant sur « la
# sélection » obligeait à deviner de quelle liste elle venait ; ici la
# question ne se pose pas.
class _BoutonListe(_RegleButton):
    """Un bouton attaché à une liste précise, pour une action précise."""

    _uid: str = ""

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_{self._uid}"


class BloquerLogicielAutoriseButton(_BoutonListe):
    _attr_translation_key = "bloquer_ce_logiciel"
    _attr_icon = "mdi:application-cog"
    _selecteur = f"{DOMAIN}_logiciel_autorise"
    _vide = AUCUN_CONSTAT
    _action = "bloquer"
    _uid = "bloquer_logiciel_autorise"


class DeclasserLogicielAutoriseButton(_BoutonListe):
    _attr_translation_key = "remettre_a_trier"
    _attr_icon = "mdi:tray-full"
    _selecteur = f"{DOMAIN}_logiciel_autorise"
    _vide = AUCUN_CONSTAT
    _action = "oublier"
    _uid = "declasser_logiciel_autorise"


class AutoriserLogicielBloqueButton(_BoutonListe):
    _attr_translation_key = "autoriser_ce_logiciel"
    _attr_icon = "mdi:application-outline"
    _selecteur = f"{DOMAIN}_logiciel_bloque"
    _vide = AUCUN_CONSTAT
    _action = "autoriser"
    _uid = "autoriser_logiciel_bloque"


class DeclasserLogicielBloqueButton(_BoutonListe):
    _attr_translation_key = "remettre_a_trier"
    _attr_icon = "mdi:tray-full"
    _selecteur = f"{DOMAIN}_logiciel_bloque"
    _vide = AUCUN_CONSTAT
    _action = "oublier"
    _uid = "declasser_logiciel_bloque"


class BloquerSiteAutoriseButton(_BoutonListe):
    _attr_translation_key = "bloquer_ce_site"
    _attr_icon = "mdi:web-cancel"
    _selecteur = f"{DOMAIN}_site_autorise"
    _vide = AUCUN_CONSTAT
    _action = "bloquer"
    _uid = "bloquer_site_autorise"


class DeclasserSiteAutoriseButton(_BoutonListe):
    _attr_translation_key = "remettre_a_trier"
    _attr_icon = "mdi:tray-full"
    _selecteur = f"{DOMAIN}_site_autorise"
    _vide = AUCUN_CONSTAT
    _action = "oublier"
    _uid = "declasser_site_autorise"


class AutoriserSiteBloqueButton(_BoutonListe):
    _attr_translation_key = "autoriser_ce_site"
    _attr_icon = "mdi:web-check"
    _selecteur = f"{DOMAIN}_site_bloque"
    _vide = AUCUN_CONSTAT
    _action = "autoriser"
    _uid = "autoriser_site_bloque"


class DeclasserSiteBloqueButton(_BoutonListe):
    _attr_translation_key = "remettre_a_trier"
    _attr_icon = "mdi:tray-full"
    _selecteur = f"{DOMAIN}_site_bloque"
    _vide = AUCUN_CONSTAT
    _action = "oublier"
    _uid = "declasser_site_bloque"


class _RegleLogicielButton(_RegleButton):
    _selecteur = f"{DOMAIN}_logiciel_constate"
    _vide = AUCUN_CONSTAT


class BloquerLogicielButton(_RegleLogicielButton):
    _attr_translation_key = "bloquer_ce_logiciel"
    _attr_icon = "mdi:application-cog"
    _action = "bloquer"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_bloquer_logiciel"


class AutoriserLogicielButton(_RegleLogicielButton):
    _attr_translation_key = "autoriser_ce_logiciel"
    _attr_icon = "mdi:application-outline"
    _action = "autoriser"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_autoriser_logiciel"


class SansRegleLogicielButton(_RegleLogicielButton):
    _attr_translation_key = "sans_regle_ce_logiciel"
    _attr_icon = "mdi:application-off"
    _action = "oublier"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_sans_regle_logiciel"


class _RegleSiteButton(_RegleButton):
    _selecteur = f"{DOMAIN}_site_constate"
    _vide = AUCUN_CONSTAT


class BloquerSiteButton(_RegleSiteButton):
    _attr_translation_key = "bloquer_ce_site"
    _attr_icon = "mdi:web-cancel"
    _action = "bloquer"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_bloquer_site"


class AutoriserSiteButton(_RegleSiteButton):
    _attr_translation_key = "autoriser_ce_site"
    _attr_icon = "mdi:web-check"
    _action = "autoriser"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_autoriser_site"


class SansRegleSiteButton(_RegleSiteButton):
    _attr_translation_key = "sans_regle_ce_site"
    _attr_icon = "mdi:web-off"
    _action = "oublier"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_sans_regle_site"


# ------------------------------------------------- Étiquettes du catalogue


# ------------------------------------------------------------ Exceptions


def _adresse(coordinator: PcParentalCoordinator) -> str:
    """L'adresse visée : celle qu'on a écrite, sinon celle qu'on a choisie.

    Le champ libre l'emporte, parce qu'on ne l'a pas rempli par hasard : il
    sert justement aux adresses que Vision n'a pas encore vues passer.
    """
    ecrite = _saisie(coordinator, "adresse").strip()
    if ecrite:
        return ecrite
    etat = _etat(coordinator, "select", f"{DOMAIN}_adresse_constatee")
    choisie = (etat.state if etat else "").strip()
    if not choisie or choisie == AUCUNE_ADRESSE:
        raise erreur("adresse_manquante")
    return choisie


class EpargnerAdresseButton(HubEntity, ButtonEntity):
    """Laisse passer une adresse précise, même si son site est bloqué.

    Une ressource pédagogique vit souvent sur une adresse à elle, à
    l'intérieur d'un site qu'on ne veut pas ouvrir en entier. C'est
    exactement la porte que ce bouton laisse dans le mur — et elle reste
    ouverte, contrairement au laissez-passer, qui se referme.
    """

    _attr_translation_key = "epargner_cette_adresse"
    _attr_icon = "mdi:door-open"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_epargner_adresse"

    async def async_press(self) -> None:
        coord = self.coordinator
        pc_id = _cible(coord)
        pc = coord.store.pcs[pc_id]
        adresse = _adresse(coord)

        def _faire() -> None:
            if not coord.store.ajouter_exception(pc, adresse):
                raise erreur("adresse_deja_epargnee", adresse=adresse)

        await appliquer(coord, _faire)


class RetirerAdresseButton(HubEntity, ButtonEntity):
    """Referme la porte laissée ouverte."""

    _attr_translation_key = "ne_plus_epargner_cette_adresse"
    _attr_icon = "mdi:door-closed"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_retirer_adresse"

    async def async_press(self) -> None:
        coord = self.coordinator
        pc_id = _cible(coord)
        pc = coord.store.pcs[pc_id]
        adresse = _adresse(coord)

        def _faire() -> None:
            if not coord.store.retirer_exception(pc, adresse):
                raise erreur("adresse_pas_epargnee", adresse=adresse)

        await appliquer(coord, _faire)


# --------------------------------------------------------- Laissez-passer


class LaisserPasserButton(HubEntity, ButtonEntity):
    """Rouvre le site choisi pour la durée indiquée, puis le referme.

    C'est la réponse au « j'ai besoin de cette vidéo, ce soir, pour l'école » :
    on ouvre, on regarde, cela se referme tout seul. La règle, elle, n'a pas
    bougé — personne n'a à se souvenir de la remettre.
    """

    _attr_translation_key = "laisser_passer_ce_site"
    _attr_icon = "mdi:timer-play"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_laisser_passer"

    async def async_press(self) -> None:
        coord = self.coordinator
        pc_id = _cible(coord)
        pc = coord.store.pcs[pc_id]
        etat = _etat(coord, "select", f"{DOMAIN}_site_constate")
        site = (etat.state if etat else "").strip()
        if not site or site == AUCUN_CONSTAT:
            raise erreur("site_manquant")
        duree = _etat(coord, "number", f"{DOMAIN}_duree_laissez_passer")
        try:
            minutes = int(float(duree.state)) if duree else 0
        except (TypeError, ValueError):
            minutes = 0

        def _faire() -> None:
            coord.store.laisser_passer(pc, site, minutes)

        await appliquer(coord, _faire)


class FermerLaissezPasserButton(HubEntity, ButtonEntity):
    """Referme tout de suite, sans attendre l'heure."""

    _attr_translation_key = "refermer_ce_site"
    _attr_icon = "mdi:timer-off"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_fermer_laissez_passer"

    async def async_press(self) -> None:
        coord = self.coordinator
        pc_id = _cible(coord)
        pc = coord.store.pcs[pc_id]
        etat = _etat(coord, "select", f"{DOMAIN}_site_constate")
        site = (etat.state if etat else "").strip()
        if not site or site == AUCUN_CONSTAT:
            raise erreur("site_manquant")

        def _faire() -> None:
            if not coord.store.fin_laissez_passer(pc, site):
                raise erreur("aucun_laissez_passer", site=site)

        await appliquer(coord, _faire)


# ------------------------------------------------- Inscrire soi-même


class _InscrireButton(HubEntity, ButtonEntity):
    """Fait entrer dans la liste un nom qu'on n'a pas encore vu passer.

    Attendre qu'un enfant ouvre un site pour avoir le droit d'en décider,
    c'est arriver après. Le nom inscrit se comporte ensuite comme tout le
    reste : il s'étiquette, il se tranche, il compte dans la file.
    """

    _genre: str = ""

    async def async_press(self) -> None:
        coord = self.coordinator
        nom = _saisie(coord, "ajout").strip()
        if not nom:
            raise erreur("ajout_vide")
        genre = self._genre

        def _faire() -> None:
            coord.store.ajouter_au_catalogue(genre, nom)

        await appliquer(coord, _faire)
        champ = _etat(coord, "text", f"{DOMAIN}_saisie_ajout")
        if champ:
            await coord.hass.services.async_call(
                "text",
                "set_value",
                {"entity_id": champ.entity_id, "value": ""},
                blocking=True,
            )


class InscrireSiteButton(_InscrireButton):
    _attr_translation_key = "inscrire_comme_site"
    _attr_icon = "mdi:web-plus"
    _genre = "sites"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_inscrire_site"


class InscrireLogicielButton(_InscrireButton):
    _attr_translation_key = "inscrire_comme_logiciel"
    _attr_icon = "mdi:application-import"
    _genre = "apps"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_inscrire_logiciel"


class _RetirerCatalogueButton(HubEntity, ButtonEntity):
    """Retire une inscription faite à la main, et ce qui s'y rattachait.

    Ce qui a été **constaté** ne s'efface pas : la machine l'a vu, il
    reviendrait au prochain relevé. Seule une inscription se retire —
    typiquement une faute de frappe.
    """

    _genre: str = ""
    _selecteur: str = ""

    async def async_press(self) -> None:
        coord = self.coordinator
        etat = _etat(coord, "select", self._selecteur)
        nom = (etat.state if etat else "").strip()
        if not nom or nom == AUCUN_CONSTAT:
            raise erreur("element_manquant")
        genre = self._genre

        def _faire() -> None:
            if not coord.store.retirer_du_catalogue(genre, nom):
                raise erreur("element_constate", nom=nom)

        await appliquer(coord, _faire)


class RetirerSiteButton(_RetirerCatalogueButton):
    _attr_translation_key = "retirer_ce_site_de_la_liste"
    _attr_icon = "mdi:web-minus"
    _genre = "sites"
    _selecteur = f"{DOMAIN}_site_constate"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_retirer_site"


class RetirerLogicielButton(_RetirerCatalogueButton):
    _attr_translation_key = "retirer_ce_logiciel_de_la_liste"
    _attr_icon = "mdi:application-export"
    _genre = "apps"
    _selecteur = f"{DOMAIN}_logiciel_constate"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_retirer_logiciel"


class RafraichirListeNoireButton(HubEntity, ButtonEntity):
    """Recharge les sources tout de suite, sans attendre le prochain tour."""

    _attr_translation_key = "rafraichir_la_liste_noire"
    _attr_icon = "mdi:shield-refresh"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_rafraichir_liste_noire"

    async def async_press(self) -> None:
        noire = self.coordinator.noire
        await noire.async_rafraichir(force=True)
        self.coordinator.store.poser_liste_noire(noire.domaines)
        if noire.erreur and not noire.domaines:
            raise HomeAssistantError(noire.erreur)
        await self.coordinator.async_refresh()


class RafraichirNotorieteButton(HubEntity, ButtonEntity):
    """Recharge le classement tout de suite.

    Dix méga-octets, une fois par jour normalement : ce bouton sert quand
    on ne veut pas attendre le prochain tour.
    """

    _attr_translation_key = "rafraichir_la_frequentation"
    _attr_icon = "mdi:chart-timeline-variant"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_rafraichir_notoriete"

    async def async_press(self) -> None:
        notoriete = self.coordinator.notoriete
        await notoriete.async_rafraichir(force=True)
        if notoriete.erreur and not notoriete.rangs:
            raise HomeAssistantError(notoriete.erreur)
        await self.coordinator.async_refresh()


class _PageButton(HubEntity, ButtonEntity):
    """Feuillette la liste affichée.

    La page vit dans le coordinateur, pas dans le tableau : les deux listes
    et leurs tableaux la lisent au même endroit, donc ils ne se
    désynchronisent pas.
    """

    _pas = 1

    def _pages(self) -> int:
        """Le nombre de pages de la liste la plus longue.

        La page est commune aux deux listes — il n'y en a qu'une à
        l'écran à la fois, et la borner sur la plus courte empêcherait
        d'atteindre la fin de l'autre.
        """
        registre = er.async_get(self.coordinator.hass)
        pages = 1
        for uid in (f"{DOMAIN}_site_constate", f"{DOMAIN}_logiciel_constate"):
            eid = registre.async_get_entity_id("select", DOMAIN, uid)
            etat = self.coordinator.hass.states.get(eid) if eid else None
            if etat is not None:
                pages = max(pages, int(etat.attributes.get("pages") or 1))
        return pages

    async def async_press(self) -> None:
        coord = self.coordinator
        pages = self._pages()
        voulu = min(max(1, coord.page + self._pas), pages)
        if voulu == coord.page:
            raise erreur("premiere_page" if self._pas < 0 else "derniere_page")
        coord.page = voulu
        coord.async_update_listeners()


class PagePrecedenteButton(_PageButton):
    _attr_translation_key = "page_precedente"
    _attr_icon = "mdi:chevron-left"
    _pas = -1

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_page_precedente"


class PageSuivanteButton(_PageButton):
    _attr_translation_key = "page_suivante"
    _attr_icon = "mdi:chevron-right"
    _pas = 1

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_page_suivante"
