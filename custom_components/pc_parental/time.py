"""Heures de début et de fin des plages, modifiables depuis Home Assistant."""

from __future__ import annotations

from datetime import time as dt_time

from homeassistant.components.time import TimeEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from .const import DOMAIN
from .coordinator import PcParentalCoordinator
from .entity import HubEntity, PcEntity, appliquer, erreur, nom_traduit, prune_stale


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]

    entites: list[TimeEntity] = [
        NewRuleTime(coordinator, "debut", "debut_nouvelle_plage", 21, 0),
        NewRuleTime(coordinator, "fin", "fin_nouvelle_plage", 7, 0),
    ]
    for pc_id in coordinator.pcs:
        for rule in coordinator.rules(pc_id):
            for bord in ("start", "end"):
                entites.append(RuleTime(coordinator, pc_id, rule["id"], bord))
    valides = {
        f"{DOMAIN}_ruletime_{pc}_{r['id']}_{bord}"
        for pc in coordinator.pcs
        for r in coordinator.rules(pc)
        for bord in ("start", "end")
    }
    prune_stale(hass, coordinator, "time", f"{DOMAIN}_ruletime_", valides)

    async_add_entities(entites)


class NewRuleTime(HubEntity, TimeEntity, RestoreEntity):
    """Heure utilisée par le bouton « Créer la plage »."""

    _attr_icon = "mdi:clock-plus-outline"

    def __init__(
        self,
        coordinator: PcParentalCoordinator,
        bord: str,
        cle: str,
        heure: int,
        minute: int,
    ) -> None:
        super().__init__(coordinator)
        self._attr_translation_key = cle
        self._attr_unique_id = f"{DOMAIN}_new_rule_{bord}"
        self._valeur = dt_time(hour=heure, minute=minute)

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        dernier = await self.async_get_last_state()
        if dernier and ":" in (dernier.state or ""):
            try:
                h, m = dernier.state.split(":")[:2]
                self._valeur = dt_time(hour=int(h), minute=int(m))
            except ValueError:
                pass

    @property
    def native_value(self) -> dt_time:
        return self._valeur

    async def async_set_value(self, value: dt_time) -> None:
        self._valeur = value
        self.async_write_ha_state()


class RuleTime(PcEntity, TimeEntity):
    """Heure de début ou de fin d'une plage existante."""

    _attr_icon = "mdi:clock-edit-outline"

    def __init__(
        self,
        coordinator: PcParentalCoordinator,
        pc_id: str,
        rule_id: int,
        bord: str,
    ) -> None:
        super().__init__(coordinator, pc_id)
        self.rule_id = rule_id
        self.bord = bord
        self._champ = "start" if bord == "start" else "end"
        self._attr_unique_id = f"{DOMAIN}_ruletime_{pc_id}_{rule_id}_{bord}"
        self._attr_translation_key = "plage_debut" if bord == "start" else "plage_fin"
        self._nom = (self._rule() or {}).get("name", str(rule_id))

    def _rule(self):
        return self.coordinator.store.rule(self.pc_id, self.rule_id)

    @property
    def name(self) -> str:
        if rule := self._rule():
            self._nom = rule["name"]
        return nom_traduit(self.coordinator.hass, "time", self._attr_translation_key, nom=self._nom)

    @property
    def available(self) -> bool:
        return super().available and self._rule() is not None

    @property
    def native_value(self) -> dt_time | None:
        rule = self._rule()
        if not rule:
            return None
        secondes = int(rule[self._champ]) % 86400
        return dt_time(
            hour=secondes // 3600, minute=(secondes % 3600) // 60
        )

    async def async_set_value(self, value: dt_time) -> None:
        rule = self._rule()
        if not rule:
            raise erreur("plage_disparue")
        secondes = value.hour * 3600 + value.minute * 60
        autre = rule["end"] if self._champ == "start" else rule["start"]
        if secondes == autre:
            raise erreur("plage_debut_egal_fin")

        def _faire() -> None:
            rule[self._champ] = secondes

        await appliquer(self.coordinator, _faire)
