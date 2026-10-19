"""Interrupteurs : accès du PC et activation de chaque plage."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from homeassistant.const import MATCH_ALL
from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from . import labels
from .const import DOMAIN, MODE_LOCKED, MODE_OPEN
from .coordinator import PcParentalCoordinator
from .entity import HubEntity, PcEntity, appliquer, categorie, erreur, langue, nom_traduit, prune_stale, texte
from .planner import days_label, hhmm


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]

    entites: list[SwitchEntity] = [
        EnrollOpenSwitch(coordinator),
        AutoUpdateSwitch(coordinator),
        VeilleRenduSwitch(coordinator),
        DesinstallationSwitch(coordinator),
        ListeNoireSwitch(coordinator),
        NotorieteSwitch(coordinator),
        VirusTotalSwitch(coordinator),
    ]
    # Une étiquette par PC, en interrupteur : allumé, tout ce qui la porte
    # est coupé sur cette machine. C'est le geste qu'on fait vraiment —
    # « ce soir, plus de jeux sur le PC de Lou » — et il se voit d'un
    # coup d'œil sur la carte du PC, sans ouvrir de liste.
    # Les étiquettes cochées comme catégories, plus celles encore posées : un
    # interrupteur « Radiateur » sur chaque appareil ne fermait rien.
    etiquettes = labels.details_proposes(hass, coordinator.store)
    from . import son as _son

    son_actif = _son.actif_module(coordinator.store)
    for pc_id in coordinator.pcs:
        entites.append(PcAccessSwitch(coordinator, pc_id))
        if son_actif:
            entites.append(SonMesureSwitch(coordinator, pc_id))  # 2.5.12.3 : la mesure du bruit, par appareil
            entites.append(SonReagirSwitch(coordinator, pc_id))  # 2.5.12.7 : réagir (messages, coupure) ou mesurer seulement
        for rule in coordinator.rules(pc_id):
            entites.append(RuleSwitch(coordinator, pc_id, rule["id"]))
        for etiquette in etiquettes:
            entites.append(
                EtiquettePcSwitch(coordinator, pc_id, etiquette["id"])
            )
    valides = (
        {f"{DOMAIN}_acces_{pc}" for pc in coordinator.pcs}
        | ({f"{DOMAIN}_son_{cle}_{pc}" for pc in coordinator.pcs for cle in ("mesure", "reagir")} if son_actif else set())
        | {
            f"{DOMAIN}_rule_{pc}_{r['id']}"
            for pc in coordinator.pcs
            for r in coordinator.rules(pc)
        }
        | {
            f"{DOMAIN}_etq_{pc}_{e['id']}"
            for pc in coordinator.pcs
            for e in etiquettes
        }
        # 2.4.2 (retour d'essai) : l'écran de veille des PC est retiré ; ses interrupteurs (pc_parental_veille_pc, et
        # pc_parental_veille_pc_<pc>) ne sont plus valides et sortent du registre (prune_stale ci-dessous).
        | {f"{DOMAIN}_inscriptions",
            f"{DOMAIN}_veille_rendu",
            f"{DOMAIN}_maj_auto",
            f"{DOMAIN}_desinstallation",
            f"{DOMAIN}_liste_noire",
            f"{DOMAIN}_notoriete",
            f"{DOMAIN}_virustotal",
        }
    )
    # Réveil (lot 1) : un interrupteur par réveil, ajouté sans recharger (reveil_entites.brancher).
    from . import reveil_entites

    valides |= reveil_entites.valides(coordinator.store, "switch")
    prune_stale(hass, coordinator, "switch", f"{DOMAIN}_", valides)

    async_add_entities(entites)
    reveil_entites.brancher(hass, entry, coordinator, "switch", async_add_entities)


class PcAccessSwitch(PcEntity, SwitchEntity):
    """Allumé = le PC est utilisable, éteint = il est verrouillé."""

    _attr_icon = "mdi:monitor-lock"
    _attr_translation_key = "acces_a_l_appareil"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_acces_{pc_id}"

    @property
    def is_on(self) -> bool:
        return not self.etat.get("locked", False)

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        etat = self.etat
        pc = self.pc
        override = etat.get("override") or {}
        fin = float(override.get("until") or 0)
        suivant = etat.get("next_change")
        agent = pc.get("agent") or {}
        return {
            "source": etat.get("source"),
            "planning": (
                "fermé" if etat.get("planning") == MODE_LOCKED else "ouvert"  # i18n-ok : valeur lue par le tableau de bord
            ),
            "fin_derogation": (
                dt_util.utc_from_timestamp(fin).isoformat() if fin else None
            ),
            "prochain_changement": (
                suivant.replace(
                    tzinfo=dt_util.DEFAULT_TIME_ZONE
                ).isoformat()
                if suivant
                else None
            ),
            "agent_en_ligne": self.coordinator.agent_online(self.pc_id),
            "session": agent.get("user") or None,
            "utilisateur": pc.get("person") or None,
            "machine": pc.get("host") or None,
            "plages": len(pc.get("rules") or []),
        }

    async def async_turn_on(self, **kwargs: Any) -> None:
        await appliquer(
            self.coordinator,
            lambda: self.coordinator.store.set_override(self.pc, MODE_OPEN),
        )

    async def async_turn_off(self, **kwargs: Any) -> None:
        await appliquer(
            self.coordinator,
            lambda: self.coordinator.store.set_override(self.pc, MODE_LOCKED),
        )


class RuleSwitch(PcEntity, SwitchEntity):
    """Active ou désactive une plage sans la supprimer."""

    _attr_icon = "mdi:calendar-clock"
    _attr_translation_key = "plage"

    def __init__(
        self, coordinator: PcParentalCoordinator, pc_id: str, rule_id: int
    ) -> None:
        super().__init__(coordinator, pc_id)
        self.rule_id = rule_id
        self._attr_unique_id = f"{DOMAIN}_rule_{pc_id}_{rule_id}"
        self._nom = (self._rule() or {}).get("name", str(rule_id))

    def _rule(self) -> dict[str, Any] | None:
        return self.coordinator.store.rule(self.pc_id, self.rule_id)

    @property
    def name(self) -> str:
        # Le libellé complet, recalculé à chaque lecture : changer l'heure
        # ou la portée d'une plage doit se voir tout de suite, sans attendre
        # que le tableau de bord soit reconstruit.
        if rule := self._rule():
            self._nom = self.coordinator.rule_label(rule)
        return nom_traduit(self.coordinator.hass, "switch", "plage", libelle=self._nom)

    @property
    def available(self) -> bool:
        return super().available and self._rule() is not None

    @property
    def is_on(self) -> bool:
        rule = self._rule()
        return bool(rule and rule["enabled"])

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        rule = self._rule()
        if not rule:
            return {}
        return {
            "ferme": ", ".join(categorie(self.coordinator.hass, e) for e in rule.get("etiquettes") or [])
            or texte(self.coordinator.hass, "plage_ferme_session"),
            "debut": hhmm(rule["start"]),
            "fin": hhmm(rule["end"]),
            "jours": days_label(rule["weekdays"], langue(self.coordinator.hass)),
            "id_plage": rule["id"],
        }

    async def _basculer(self, actif: bool) -> None:
        def _faire() -> None:
            rule = self._rule()
            if rule is None:
                raise erreur("plage_disparue")
            rule["enabled"] = actif

        await appliquer(self.coordinator, _faire)

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._basculer(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._basculer(False)


class EtiquettePcSwitch(PcEntity, SwitchEntity):
    """Allumé = cette étiquette est coupée sur ce PC.

    On garde l'**identifiant** de l'étiquette, jamais son nom : renommée
    dans Paramètres, elle reste le même interrupteur, avec le même
    historique. Le nom, lui, est relu à chaque affichage.
    """
    # 2.5.12 (étude des menus, L.3) : attributs hors de l'historique (la base ne grossit plus à chaque relevé).
    _unrecorded_attributes = frozenset({MATCH_ALL})

    _attr_icon = "mdi:tag-off-outline"
    _attr_entity_category = EntityCategory.CONFIG
    _attr_translation_key = "bloquer_categorie"

    def __init__(
        self, coordinator: PcParentalCoordinator, pc_id: str, label_id: str
    ) -> None:
        super().__init__(coordinator, pc_id)
        self.label_id = label_id
        self._attr_unique_id = f"{DOMAIN}_etq_{pc_id}_{label_id}"

    def _etiquette(self) -> str | None:
        # Le coordinateur, pas `self.hass` : le nom est lu dès la
        # construction de l'entité, avant que la plateforme ne l'ait posée.
        return labels.resoudre(self.coordinator.hass, self.label_id)

    @property
    def name(self) -> str:
        nom = self._etiquette()
        return nom_traduit(
            self.coordinator.hass, "switch", "bloquer_categorie",
            categorie=categorie(self.coordinator.hass, nom) if nom else self.label_id,
        )

    @property
    def available(self) -> bool:
        return super().available and self._etiquette() is not None

    @property
    def is_on(self) -> bool:
        nom = self._etiquette()
        return bool(nom) and self.coordinator.store.etiquette_bloquee(
            self.pc, nom
        )

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        nom = self._etiquette()
        if not nom:
            return {}
        magasin = self.coordinator.store
        # 2.5.12 : des nombres, plus les listes (une copie par appareil et par catégorie, jusqu'à des centaines de
        # noms chacune). La liste d'une catégorie se lit dans l'onglet Autorisations.
        return {
            "etiquette": nom,
            "nb_logiciels": len(magasin.noms_par_etiquette("apps", nom)),
            "nb_sites": len(magasin.noms_par_etiquette("sites", nom)),
        }

    async def _basculer(self, actif: bool) -> None:
        def _faire() -> None:
            nom = self._etiquette()
            if nom is None:
                raise erreur("etiquette_disparue")
            self.coordinator.store.basculer_etiquette(self.pc, nom, actif)

        await appliquer(self.coordinator, _faire)

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._basculer(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._basculer(False)


class EnrollOpenSwitch(HubEntity, SwitchEntity):
    """Ouvre ou ferme l'inscription de nouveaux PC."""

    _attr_translation_key = "inscription_de_nouveaux_appareils"
    _attr_icon = "mdi:laptop-account"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_inscriptions"

    @property
    def is_on(self) -> bool:
        return self.coordinator.store.inscriptions_ouvertes()

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        """Pendant « Autoriser 5 min » : l'heure de refermeture."""
        from . import inscriptions

        if not inscriptions.reste(self.coordinator):
            return {}
        return {
            "refermeture_automatique": datetime.fromtimestamp(
                self.coordinator.store.enroll_jusqua, tz=timezone.utc
            ).isoformat(),
        }

    async def async_turn_on(self, **kwargs: Any) -> None:
        from . import inscriptions

        await inscriptions.regler(self.hass, self.coordinator, inscriptions.OUVRIR)

    async def async_turn_off(self, **kwargs: Any) -> None:
        from . import inscriptions

        await inscriptions.regler(self.hass, self.coordinator, inscriptions.FERMER)


class AutoUpdateSwitch(HubEntity, SwitchEntity):
    """Les agents se mettent à jour seuls dès qu'une version est publiée."""

    _attr_translation_key = "mise_a_jour_automatique_des_agents"
    _attr_icon = "mdi:auto-download"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_maj_auto"

    @property
    def is_on(self) -> bool:
        return self.coordinator.store.auto_update

    async def _regler(self, actif: bool) -> None:
        def _faire() -> None:
            self.coordinator.store.auto_update = actif

        await appliquer(self.coordinator, _faire)

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._regler(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._regler(False)


class VeilleRenduSwitch(HubEntity, SwitchEntity):
    """La veille des appareils montre les vraies cartes de Home Assistant (passerelle, sans jeton global)."""

    _attr_translation_key = "veille_vrai_rendu_des_cartes"
    _attr_icon = "mdi:view-dashboard-variant"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_veille_rendu"

    @property
    def is_on(self) -> bool:
        return self.coordinator.store.veille_rendu

    async def _regler(self, actif: bool) -> None:
        def _faire() -> None:
            self.coordinator.store.veille_rendu = actif

        await appliquer(self.coordinator, _faire)

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._regler(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._regler(False)



class DesinstallationSwitch(HubEntity, SwitchEntity):
    """Autorise les agents à désinstaller ce qu'un adulte a désigné.

    Éteint par défaut : une désinstallation ne se défait pas. Éteint, aucun
    agent ne désinstalle rien, même si une décision est restée au catalogue.
    """

    _attr_translation_key = "desinstallation_par_l_agent"
    _attr_icon = "mdi:delete-forever-outline"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_desinstallation"

    @property
    def is_on(self) -> bool:
        return self.coordinator.store.desinstallation

    async def _regler(self, actif: bool) -> None:
        def _faire() -> None:
            self.coordinator.store.desinstallation = actif

        await appliquer(self.coordinator, _faire)

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._regler(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._regler(False)


class VirusTotalSwitch(HubEntity, SwitchEntity):
    """Demande à VirusTotal ce qu'il pense des programmes de la maison.

    Rien ne part sans que ce bouton soit allumé, et rien ne peut partir
    sans clé. Ce qui part est l'**empreinte** du fichier, jamais le fichier
    — mais une empreinte apprend quand même à un tiers quels programmes
    tournent ici, et c'est le prix à décider en connaissance de cause.
    """

    _attr_translation_key = "avis_virustotal"
    _attr_icon = "mdi:virus-outline"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_virustotal"

    @property
    def is_on(self) -> bool:
        return self.coordinator.virustotal.active

    @property
    def available(self) -> bool:
        return super().available and bool(self.coordinator.virustotal.cle)

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        vt = self.coordinator.virustotal
        return {
            "cle_posee": bool(vt.cle),
            "verdicts_connus": len(vt.verdicts),
            "requetes_restantes_aujourdhui": vt.reste_aujourdhui(),
            "erreur": vt.erreur or None,
            "ce_qui_part": texte(self.coordinator.hass, "virustotal_ce_qui_part"),
        }

    async def _regler(self, actif: bool) -> None:
        vt = self.coordinator.virustotal
        if actif and not vt.cle:
            raise erreur("virustotal_sans_cle")
        vt.active = actif
        await vt.async_save()
        self.async_write_ha_state()
        await self.coordinator.async_refresh()

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._regler(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._regler(False)


class NotorieteSwitch(HubEntity, SwitchEntity):
    """Affiche à quel point chaque site est fréquenté dans le monde.

    Ce n'est **pas** un jugement sur le contenu, et la confusion serait
    grave : les sites pour adultes sont parmi les plus visités au monde et
    ressortiront « très fréquentés ». Ce que ce classement apporte est
    l'inverse — un domaine que personne ne visite, apparu ce matin,
    contacté par un programme qu'on ne connaît pas, se voit enfin au
    milieu de deux cents autres lignes.

    Rien n'est bloqué à partir de là. C'est une colonne, pas une règle.
    """

    _attr_translation_key = "frequentation_des_sites"
    _attr_icon = "mdi:chart-bar"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_notoriete"

    @property
    def is_on(self) -> bool:
        return self.coordinator.notoriete.active

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        notoriete = self.coordinator.notoriete
        return {
            "domaines_classes": len(notoriete.rangs),
            "source": "Tranco",
            "derniere_maj": (
                dt_util.utc_from_timestamp(notoriete.maj).isoformat()
                if notoriete.maj
                else None
            ),
            "erreur": notoriete.erreur or None,
            "avertissement": texte(self.coordinator.hass, "frequentation_avertissement"),
        }

    async def _regler(self, actif: bool) -> None:
        notoriete = self.coordinator.notoriete
        notoriete.active = actif
        await notoriete.async_save()
        if actif and notoriete.perimee():
            await notoriete.async_rafraichir(force=True)
        self.async_write_ha_state()
        await self.coordinator.async_refresh()

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._regler(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._regler(False)


class ListeNoireSwitch(HubEntity, SwitchEntity):
    """Bloque les domaines signalés dangereux par des sources publiques.

    Ce n'est pas une règle de la maison : rien à trancher, rien à discuter.
    Un domaine qui héberge un logiciel malveillant ou une page d'hameçonnage
    est coupé sur tous les PC, et aucun laissez-passer ne l'ouvre.
    """

    _attr_translation_key = "liste_noire_des_sites_dangereux"
    _attr_icon = "mdi:shield-alert"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_liste_noire"

    @property
    def is_on(self) -> bool:
        return self.coordinator.noire.active

    async def _regler(self, actif: bool) -> None:
        noire = self.coordinator.noire
        noire.active = actif
        await noire.async_save()
        if actif and noire.perimee():
            await noire.async_rafraichir(force=True)
        self.async_write_ha_state()
        await self.coordinator.async_refresh()

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._regler(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._regler(False)


class SonMesureSwitch(PcEntity, SwitchEntity):
    """2.5.12.3 : la mesure du niveau sonore sur cet appareil (son.reglages « actif »). Éteinte par défaut ; le module
    « Niveau sonore » doit être allumé pour que l'interrupteur existe. Administrateurs."""

    _attr_translation_key = "niveau_sonore_mesure"
    _attr_icon = "mdi:microphone-outline"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_son_mesure_{pc_id}"

    @property
    def is_on(self) -> bool:
        from . import son as _son

        return bool(self.pc and _son.reglages(self.pc)["actif"])

    async def _poser(self, actif: bool) -> None:
        from . import son as _son

        await appliquer(self.coordinator, lambda: _son.regler(self.pc, {"actif": actif}))

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._poser(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._poser(False)


class SonReagirSwitch(PcEntity, SwitchEntity):
    """2.5.12.7 : réagir au bruit sur cet appareil (messages gradués, responsables, coupure) ; éteint, la mesure
    continue sans aucune réaction. D'un clic, la règle s'arrête. Administrateurs."""

    _attr_translation_key = "bruit_reagir"
    _attr_icon = "mdi:account-voice"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_son_reagir_{pc_id}"

    @property
    def is_on(self) -> bool:
        from . import son as _son

        return bool(self.pc and _son.reglages(self.pc)["reagir"])

    async def _poser(self, actif: bool) -> None:
        from . import son as _son

        await appliquer(self.coordinator, lambda: _son.regler(self.pc, {"reagir": actif}))

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._poser(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._poser(False)
