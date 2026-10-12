"""Mise à jour de l'agent installé sur chaque PC.

Home Assistant héberge le client ; chaque PC apparaît ici avec sa version
installée face à la version publiée, et un bouton pour l'installer.
"""

from __future__ import annotations

from typing import Any

from homeassistant.components.update import UpdateEntity, UpdateEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CLIENT_VERSION, DOMAIN, PRODUIT
from . import android_maj
from .coordinator import PcParentalCoordinator
from .entity import PcEntity, appliquer, prune_stale, texte


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]

    valides = {f"{DOMAIN}_maj_{pc}" for pc in coordinator.pcs}
    prune_stale(hass, coordinator, "update", f"{DOMAIN}_maj_", valides)

    async_add_entities(
        [AgentUpdate(coordinator, pc_id) for pc_id in coordinator.pcs]
    )


class AgentUpdate(PcEntity, UpdateEntity):
    """Version de l'agent sur ce PC, face à celle publiée par Home Assistant."""

    _attr_translation_key = "agent"
    _attr_supported_features = UpdateEntityFeature.INSTALL

    @property
    def title(self) -> str:
        return texte(self.coordinator.hass, "titre_maj_agent", produit=PRODUIT)

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_maj_{pc_id}"

    @property
    def installed_version(self) -> str | None:
        return (self.pc.get("agent") or {}).get("version") or None

    @property
    def latest_version(self) -> str:
        if (self.pc.get("platform") or "") == "android":
            # La version de son appli (ancienne ou nouvelle), pas celle de l'autre.
            _v = android_maj.infos_pour(
                self.coordinator.hass, str(self.pc.get("name") or ""), self.installed_version or "",
                str(self.pc.get("id") or ""), self.coordinator.store.appareils_essai,
            ).get("version")
            return _v or self.installed_version or CLIENT_VERSION
        return CLIENT_VERSION

    @property
    def auto_update(self) -> bool:
        return self.coordinator.store.auto_update

    @property
    def release_summary(self) -> str | None:
        if not self.coordinator.agent_online(self.pc_id):
            return texte(self.coordinator.hass, "maj_agent_injoignable")
        if self.pc.get("update_asked"):
            return texte(self.coordinator.hass, "maj_demandee")
        return None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "agent_en_ligne": self.coordinator.agent_online(self.pc_id),
            "machine": self.pc.get("host") or None,
        }

    async def async_install(
        self, version: str | None, backup: bool, **kwargs: Any
    ) -> None:
        """Marque la mise à jour : elle part à la prochaine interrogation."""

        def _faire() -> None:
            self.pc["update_asked"] = True

        await appliquer(self.coordinator, _faire)
