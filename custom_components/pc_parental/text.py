"""Champs de saisie : nom d'un PC, nom d'une plage, listes de blocage."""

from __future__ import annotations

from typing import Any

from homeassistant.components.text import TextEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.event import async_call_later
from homeassistant.helpers.restore_state import RestoreEntity

from .const import DOMAIN
from .coordinator import PcParentalCoordinator
from .entity import HubEntity, PcEntity, appliquer, erreur, nom_traduit, prune_stale


def decouper(valeur: str) -> list[str]:
    """« steam, fortnite ; roblox » → ['steam', 'fortnite', 'roblox']."""
    brut = (valeur or "").replace(";", ",").replace("\n", ",")
    return [m.strip() for m in brut.split(",") if m.strip()]


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]

    entites: list[TextEntity] = [
        # La clé de traduction du nom est celle de l'identifiant : saisie_<clé>.
        SaisieText(coordinator, "nom_pc", "mdi:desktop-tower"),
        SaisieText(coordinator, "nom_plage", "mdi:tag"),
        SaisieText(coordinator, "etiquettes_plage", "mdi:tag-multiple"),
        SaisieText(coordinator, "message", "mdi:message-draw"),
        SaisieText(coordinator, "adresse", "mdi:link-plus"),
        SaisieText(coordinator, "ajout", "mdi:playlist-plus"),
        RechercheText(coordinator, "recherche", "mdi:shield-search"),
        CleVirusTotalText(coordinator),
        VeilleText(coordinator),
        VeilleCourbeText(coordinator),
        VeilleCamerasText(coordinator),
        VeilleFluxText(coordinator),
        VeilleMusiqueText(coordinator),
        VeilleNuitText(coordinator),
    ]
    for pc_id in coordinator.pcs:
        entites.append(NomText(coordinator, pc_id))
        for champ, icone in (
            ("bloques", "mdi:cancel"),
            ("autorises", "mdi:check-decagram"),
        ):
            entites.append(ListText(coordinator, pc_id, champ, icone))
        for regle in coordinator.rules(pc_id):
            entites.append(RuleEtiquettesText(coordinator, pc_id, regle["id"]))
    valides = {
        f"{DOMAIN}_liste_{champ}_{pc}"
        for pc in coordinator.pcs
        for champ in ("bloques", "autorises")
    } | {
        f"{DOMAIN}_liste_{champ}_{pc}"
        for pc in coordinator.pcs
        for champ in ("nom",)
    }
    # Les étiquettes du catalogue ne sont pas rattachées à un PC : elles
    # échappent volontairement à cette purge, dont le préfixe est « _liste_ ».
    prune_stale(hass, coordinator, "text", f"{DOMAIN}_liste_", valides)
    # Les champs « Étiquettes du site / du logiciel » et « Nouvelle
    # étiquette » ont disparu avec l'ancien étiquetage : on les retire du
    # registre plutôt que de les y laisser indisponibles.
    prune_stale(hass, coordinator, "text", f"{DOMAIN}_etiquettes_", set())
    # La règle de moyenne a quitté l'intégration (1.60.0), et sa liste de divertissement avec elle.
    prune_stale(hass, coordinator, "text", f"{DOMAIN}_divertissement", set())
    # 1.61.0 : la clé d'inscription n'est plus une entité (lisible et modifiable
    # par tout compte). Elle vit dans les options de l'intégration.
    prune_stale(hass, coordinator, "text", f"{DOMAIN}_cle_inscription", set())
    prune_stale(
        hass,
        coordinator,
        "text",
        f"{DOMAIN}_saisie_etiquette",
        {f"{DOMAIN}_saisie_etiquettes_plage"},
    )
    tags = {
        f"{DOMAIN}_ruletags_{pc}_{r['id']}"
        for pc in coordinator.pcs
        for r in coordinator.rules(pc)
    }
    prune_stale(hass, coordinator, "text", f"{DOMAIN}_ruletags_", tags)
    # Les jours ont quitté le texte libre pour une liste déroulante : on
    # purge donc ce que la version d'avant avait posé.
    prune_stale(hass, coordinator, "text", f"{DOMAIN}_rulejours_", set())

    async_add_entities(entites)


class SaisieText(HubEntity, TextEntity, RestoreEntity):
    """Champ libre utilisé par les boutons de création."""

    _attr_native_max = 60
    _attr_mode = "text"

    def __init__(
        self,
        coordinator: PcParentalCoordinator,
        cle: str,
        icone: str,
    ) -> None:
        super().__init__(coordinator)
        self._attr_translation_key = f"saisie_{cle}"
        self._attr_icon = icone
        self._attr_unique_id = f"{DOMAIN}_saisie_{cle}"
        self._valeur = ""

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        dernier = await self.async_get_last_state()
        if dernier and dernier.state not in (None, "unknown", "unavailable"):
            self._valeur = dernier.state[: self._attr_native_max]

    @property
    def native_value(self) -> str:
        return self._valeur

    async def async_set_value(self, value: str) -> None:
        self._valeur = value[: self._attr_native_max]
        self.async_write_ha_state()


class RechercheText(SaisieText):
    """Champ de recherche de Protection (HA-23) : jamais repris au redémarrage, vidé après 10 min sans saisie.

    Une recherche périmée filtrait la liste sans qu'on s'en souvienne : on croyait un site absent. Même entité,
    même unique_id ; seule la durée de vie du texte change.
    """

    OUBLI_APRES = 600  # secondes sans nouvelle saisie

    _annuler_oubli = None

    async def async_added_to_hass(self) -> None:
        await super(SaisieText, self).async_added_to_hass()  # pas de reprise de l'ancienne recherche
        self._valeur = ""
        self.async_on_remove(self._arreter_oubli)

    def _arreter_oubli(self) -> None:
        if self._annuler_oubli is not None:
            self._annuler_oubli()
            self._annuler_oubli = None

    async def async_set_value(self, value: str) -> None:
        self._arreter_oubli()
        await super().async_set_value(value)
        if self._valeur:
            self._annuler_oubli = async_call_later(self.hass, self.OUBLI_APRES, self._oublier)

    @callback
    def _oublier(self, _maintenant=None) -> None:
        self._annuler_oubli = None
        self._valeur = ""
        self.async_write_ha_state()


class RuleEtiquettesText(PcEntity, TextEntity):
    """Les étiquettes que cette plage ferme, modifiables à tout moment.

    Saisie libre, séparée par des virgules : il en faut plusieurs, et une
    étiquette qu'on s'apprête à poser n'existe pas encore au moment où on
    écrit la plage.
    """

    _attr_icon = "mdi:tag-multiple"
    _attr_mode = "text"
    _attr_native_max = 255
    _attr_translation_key = "plage_etiquettes"

    def __init__(
        self, coordinator: PcParentalCoordinator, pc_id: str, rule_id: int
    ) -> None:
        super().__init__(coordinator, pc_id)
        self.rule_id = rule_id
        self._attr_unique_id = f"{DOMAIN}_ruletags_{pc_id}_{rule_id}"
        self._nom = (self._rule() or {}).get("name", str(rule_id))

    def _rule(self) -> dict[str, Any] | None:
        return self.coordinator.store.rule(self.pc_id, self.rule_id)

    @property
    def name(self) -> str:
        if rule := self._rule():
            self._nom = rule["name"]
        return nom_traduit(self.coordinator.hass, "text", "plage_etiquettes", nom=self._nom)

    @property
    def available(self) -> bool:
        return super().available and self._rule() is not None

    @property
    def native_value(self) -> str:
        rule = self._rule()
        return ", ".join((rule or {}).get("etiquettes") or [])

    async def async_set_value(self, value: str) -> None:
        rule = self._rule()
        if rule is None:
            raise erreur("plage_disparue")
        etiquettes: list[str] = []
        for morceau in str(value or "").replace(";", ",").split(","):
            morceau = morceau.strip()
            if morceau and morceau.lower() not in [e.lower() for e in etiquettes]:
                etiquettes.append(morceau)
        if not etiquettes and rule.get("portee") == "etiquettes":
            raise erreur("plage_etiquettes_vides")

        def _faire() -> None:
            rule["etiquettes"] = etiquettes

        await appliquer(self.coordinator, _faire)


class CleVirusTotalText(HubEntity, TextEntity):
    """La clé d'API VirusTotal, saisie ici et nulle part ailleurs.

    En mode mot de passe : elle ne s'affiche pas une fois posée, et elle
    ne traverse aucun journal. Elle vit dans le stockage de Home Assistant,
    au même titre que les autres secrets de la maison — c'est-à-dire en
    clair sur le disque du serveur, comme tout ce que HA garde. Qui a accès
    au serveur a accès à la clé ; ce n'est pas un coffre-fort, et il vaut
    mieux le savoir que le supposer.
    """

    _attr_translation_key = "cle_virustotal"
    _attr_icon = "mdi:key-variant"
    _attr_mode = "password"
    _attr_native_max = 128
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_cle_virustotal"

    @property
    def native_value(self) -> str:
        # Jamais la vraie valeur : la renvoyer la remettrait dans l'état de
        # l'entité, donc dans l'historique, donc dans la base de données.
        return "•" * 12 if self.coordinator.virustotal.cle else ""

    async def async_set_value(self, value: str) -> None:
        vt = self.coordinator.virustotal
        propre = str(value or "").strip()
        if propre and set(propre) == {"•"}:
            return
        vt.cle = propre
        vt.erreur = ""
        if not propre:
            vt.active = False
        await vt.async_save()
        self.async_write_ha_state()
        await self.coordinator.async_refresh()


class ListText(PcEntity, TextEntity):
    """Liste de logiciels ou de sites, séparés par des virgules."""

    _attr_native_max = 255
    _attr_mode = "text"

    def __init__(
        self,
        coordinator: PcParentalCoordinator,
        pc_id: str,
        champ: str,
        icone: str,
    ) -> None:
        super().__init__(coordinator, pc_id)
        self.champ = champ
        self._attr_translation_key = f"liste_{champ}"
        self._attr_icon = icone
        self._attr_unique_id = f"{DOMAIN}_liste_{champ}_{pc_id}"

    @property
    def native_value(self) -> str:
        return ", ".join(self.pc.get(self.champ) or [])[: self._attr_native_max]

    async def async_set_value(self, value: str) -> None:
        elements = decouper(value)

        def _faire() -> None:
            self.pc[self.champ] = elements

        await appliquer(self.coordinator, _faire)


class NomText(PcEntity, TextEntity):
    """Nom donné à ce PC dans Home Assistant.

    Indépendant du nom de la machine Windows : « PC de Lou » se lit mieux
    que « DESKTOP-4F2K9X ».
    """

    _attr_translation_key = "nom"
    _attr_icon = "mdi:rename-outline"
    _attr_native_min = 1
    _attr_native_max = 60
    _attr_mode = "text"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_liste_nom_{pc_id}"

    @property
    def native_value(self) -> str:
        return self.pc.get("name") or ""

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        return {"machine": self.pc.get("host") or None}

    async def async_set_value(self, value: str) -> None:
        pc_id = self.pc_id
        coord = self.coordinator

        def _faire() -> None:
            coord.store.rename_pc(pc_id, value)

        # Renommer change le nom de l'appareil : on recharge pour que
        # toutes les entités du PC suivent.
        await appliquer(coord, _faire, structurel=True)


class TagsText(PcEntity, TextEntity):
    """Étiquettes **Home Assistant** de ce PC, séparées par des virgules.

    Ce sont les vraies étiquettes de Home Assistant, celles de la page
    Paramètres → Zones et étiquettes : les mêmes que pour une prise ou une
    lampe. Écrire ici revient à les poser sur l'appareil ; une étiquette
    inconnue est créée. Elles restent visibles et modifiables depuis la fiche
    de l'appareil, et utilisables partout ailleurs dans Home Assistant.
    """

    _attr_translation_key = "etiquettes"
    _attr_icon = "mdi:tag-multiple"
    _attr_native_min = 0
    _attr_native_max = 255
    _attr_mode = "text"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_liste_tags_{pc_id}"

    @property
    def native_value(self) -> str:
        etiquettes = self.coordinator.store.tags_du_pc(self.pc_id)
        return ", ".join(etiquettes)[: self._attr_native_max]

    async def async_set_value(self, value: str) -> None:
        etiquettes = decouper(value)
        pc_id = self.pc_id
        coord = self.coordinator

        def _faire() -> None:
            coord.store.set_tags(pc_id, etiquettes)

        await appliquer(coord, _faire)




class VeilleText(HubEntity, TextEntity):
    """Les entités que la télé affiche en veille, séparées par des virgules.

    `sensor.exterieur|Dehors` renomme la tuile. Une entité inconnue est
    refusée tout de suite : mieux vaut une erreur ici qu'une tuile vide
    sur l'écran du salon.
    """

    _attr_translation_key = "ecran_de_veille_entites"
    _attr_icon = "mdi:television-ambient-light"
    _attr_native_max = 255
    _attr_mode = "text"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_veille_entites"

    @property
    def native_value(self) -> str:
        return ",".join(self.coordinator.store.veille_entites)

    async def async_set_value(self, value: str) -> None:
        choisies: list[str] = []
        for entree in decouper(value):
            eid = entree.split("|")[0].strip()
            if self.hass.states.get(eid) is None:
                raise erreur("entite_inconnue", entite=eid)
            if entree not in choisies:
                choisies.append(entree)

        def _faire() -> None:
            self.coordinator.store.veille_entites = choisies

        await appliquer(self.coordinator, _faire)


class VeilleCamerasText(HubEntity, TextEntity):
    """Les caméras que l'écran de veille a le droit de montrer, séparées par des virgules.

    Vide, il n'en montre aucune : un appareil inscrit ne voit jamais une
    caméra que personne n'a choisie pour lui.
    """

    _attr_translation_key = "ecran_de_veille_cameras"
    _attr_icon = "mdi:cctv"
    _attr_native_max = 255
    _attr_mode = "text"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_veille_cameras"

    @property
    def native_value(self) -> str:
        return ",".join(self.coordinator.store.veille_cameras)

    async def async_set_value(self, value: str) -> None:
        choisies: list[str] = []
        for eid in decouper(value):
            if not eid.startswith("camera.") or self.hass.states.get(eid) is None:
                raise erreur("camera_inconnue", camera=eid)
            if eid not in choisies:
                choisies.append(eid)

        def _faire() -> None:
            self.coordinator.store.veille_cameras = choisies

        await appliquer(self.coordinator, _faire)


class VeilleCourbeText(HubEntity, TextEntity):
    """Les deux températures de la courbe de l'écran de veille : dehors, dedans.

    Vide, la courbe prend les deux premières tuiles de température.
    """

    _attr_translation_key = "ecran_de_veille_courbe"
    _attr_icon = "mdi:chart-bell-curve-cumulative"
    _attr_native_max = 255
    _attr_mode = "text"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_veille_courbe"

    @property
    def native_value(self) -> str:
        return ",".join(self.coordinator.store.veille_courbe)

    async def async_set_value(self, value: str) -> None:
        choisies: list[str] = []
        for entree in decouper(value):
            eid = entree.split("|")[0].strip()
            if self.hass.states.get(eid) is None:
                raise erreur("entite_inconnue", entite=eid)
            choisies.append(entree)

        def _faire() -> None:
            self.coordinator.store.veille_courbe = choisies[:2]

        await appliquer(self.coordinator, _faire)


class VeilleFluxText(HubEntity, TextEntity):
    """Suffixe du flux secondaire des caméras pour l'écran de veille.

    Une télé ne décode pas plusieurs flux 4K : avec « _sub » (Frigate, go2rtc),
    l'écran de veille lit le flux léger de chaque caméra. Vide = flux normal.
    """

    _attr_translation_key = "ecran_de_veille_flux_secondaire"
    _attr_icon = "mdi:video-switch"
    _attr_native_max = 40
    _attr_mode = "text"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_veille_flux_suffixe"

    @property
    def native_value(self) -> str:
        return self.coordinator.store.veille_flux_suffixe

    async def async_set_value(self, value: str) -> None:
        suffixe = value.strip()

        def _faire() -> None:
            self.coordinator.store.veille_flux_suffixe = suffixe

        await appliquer(self.coordinator, _faire)


class VeilleMusiqueText(HubEntity, TextEntity):
    """La musique de l'écran de veille : l'adresse d'un flux audio (radio sans publicité).

    Vide, l'écran de veille reste silencieux. Le volume se règle à la télécommande.
    """

    _attr_translation_key = "ecran_de_veille_musique"
    _attr_icon = "mdi:music-note"
    _attr_native_max = 255
    _attr_mode = "text"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_veille_musique"

    @property
    def native_value(self) -> str:
        return self.coordinator.store.veille_musique

    async def async_set_value(self, value: str) -> None:
        adresse = value.strip()
        if adresse and not adresse.startswith(("http://", "https://")):
            raise erreur("adresse_http")

        def _faire() -> None:
            self.coordinator.store.veille_musique = adresse

        await appliquer(self.coordinator, _faire)


class VeilleNuitText(HubEntity, TextEntity):
    """La plage du mode nuit de l'écran de veille : fond sombre, musique coupée.

    Au format « 23:00-07:00 » ; vide = jamais.
    """

    _attr_translation_key = "ecran_de_veille_nuit"
    _attr_icon = "mdi:weather-night"
    _attr_native_max = 11
    _attr_mode = "text"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_veille_nuit"

    @property
    def native_value(self) -> str:
        return self.coordinator.store.veille_nuit

    async def async_set_value(self, value: str) -> None:
        import re as _re

        plage = value.strip()
        if plage and not _re.fullmatch(r"\d\d:\d\d-\d\d:\d\d", plage):
            raise erreur("format_nuit")

        def _faire() -> None:
            self.coordinator.store.veille_nuit = plage

        await appliquer(self.coordinator, _faire)
