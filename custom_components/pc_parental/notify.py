"""Envoi de messages sur l'écran d'un PC.

Chaque PC expose une entité de notification : une automatisation peut donc
lui écrire comme à un téléphone — « sors la poubelle », « extinction dans
dix minutes ». Le message s'affiche par-dessus ce que fait l'enfant, puis
disparaît de lui-même.
"""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN
from .coordinator import PcParentalCoordinator
from .entity import PcEntity, appliquer, prune_stale


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]

    valides = {f"{DOMAIN}_notify_{pc}" for pc in coordinator.pcs}
    prune_stale(hass, coordinator, "notify", f"{DOMAIN}_notify_", valides)

    async_add_entities(
        [PcNotify(coordinator, pc_id) for pc_id in coordinator.pcs]
    )


class PcNotify(PcEntity, NotifyEntity):
    """Affiche un message sur l'écran de ce PC."""

    _attr_translation_key = "message_a_l_ecran"
    _attr_icon = "mdi:message-alert-outline"
    _attr_supported_features = NotifyEntityFeature.TITLE

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_notify_{pc_id}"

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        return {
            "agent_en_ligne": self.coordinator.agent_online(self.pc_id),
            "en_attente": len(self.pc.get("messages") or []),
        }

    async def async_send_message(
        self, message: str, title: str | None = None
    ) -> None:
        from . import modules

        modules.exiger(self.coordinator.store, "messages")
        # On n'échoue pas si le PC est éteint : le message attend, et se
        # périme tout seul s'il n'a plus de sens à l'allumage suivant.
        def _faire() -> None:
            self.coordinator.store.add_message(
                self.pc_id, message, title or "Home Assistant"
            )

        await appliquer(self.coordinator, _faire)
