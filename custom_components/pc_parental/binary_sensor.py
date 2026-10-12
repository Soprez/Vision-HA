"""Ce que Vision surveille sur chaque PC : agent joignable, compte à risque."""

from __future__ import annotations

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from .const import DOMAIN
from .coordinator import PcParentalCoordinator
from .entity import EntityIdFige, HubEntity, PcEntity, prune_stale


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]

    valides = {
        f"{DOMAIN}_{cle}_{pc}"
        for pc in coordinator.pcs
        for cle in ("agent", "compte_admin")
    }
    valides.add(f"{DOMAIN}_pause_generale")
    valides.add(f"{DOMAIN}_fenetre_reveil")
    prune_stale(hass, coordinator, "binary_sensor", f"{DOMAIN}_", valides)

    from .reveil_entites import FenetreReveilBinarySensor

    entites: list[BinarySensorEntity] = [PauseGeneraleEnCours(coordinator), FenetreReveilBinarySensor(coordinator)]
    for pc_id in coordinator.pcs:
        entites.append(AgentOnline(coordinator, pc_id))
        entites.append(CompteAdministrateur(coordinator, pc_id))
    async_add_entities(entites)


class AgentOnline(PcEntity, BinarySensorEntity):
    """Allumé tant que l'agent donne signe de vie."""

    _attr_translation_key = "agent"
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_agent_{pc_id}"

    @property
    def is_on(self) -> bool:
        return self.coordinator.agent_online(self.pc_id)


class CompteAdministrateur(PcEntity, BinarySensorEntity):
    """Allumé si la session ouverte est administrateur de la machine.

    C'est le point faible de tout l'édifice : un compte administrateur peut
    arrêter la tâche planifiée, vider le fichier hosts et se libérer en deux
    minutes. Vision ne peut pas l'empêcher — il le dit.

    Tant que l'agent n'a pas réussi à le déterminer, l'entité reste
    indisponible plutôt que d'annoncer à tort que tout va bien.
    """

    _attr_translation_key = "compte_administrateur"
    _attr_device_class = BinarySensorDeviceClass.PROBLEM
    _attr_icon = "mdi:shield-alert-outline"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_compte_admin_{pc_id}"

    @property
    def available(self) -> bool:
        return (self.pc.get("agent") or {}).get("admin") is not None

    @property
    def is_on(self) -> bool:
        return bool((self.pc.get("agent") or {}).get("admin"))


class PauseGeneraleEnCours(EntityIdFige, HubEntity, BinarySensorEntity):
    """Allumé pendant « Tout fermer » (pause générale, point 14.2) ; l'attribut « fin » dit jusqu'à quand."""

    _attr_translation_key = "pause_generale_en_cours"
    _plateforme_entity_id = "binary_sensor"
    _attr_icon = "mdi:pause-octagon-outline"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_pause_generale"

    @property
    def is_on(self) -> bool:
        from . import pause_generale

        return pause_generale.public(self.coordinator.store) is not None

    @property
    def extra_state_attributes(self) -> dict:
        from . import pause_generale

        etat = pause_generale.public(self.coordinator.store)
        if etat is None:
            return {"fin": None, "fin_heure": None, "appareils": []}
        brut = self.coordinator.store.pause_generale or {}
        return {"fin": dt_util.utc_from_timestamp(etat["fin"]).isoformat(), "fin_heure": pause_generale.heure(etat["fin"]),
                "appareils": sorted(brut.get("appareils") or {})}
