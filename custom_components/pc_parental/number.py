"""Réglages chiffrés de la maison."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from .const import (
    DOMAIN,
    LISTE_NOIRE_HEURES_MAX,
    LISTE_NOIRE_HEURES_MIN,
    LAISSEZ_PASSER_DEFAUT,
    LAISSEZ_PASSER_MAX,
    LAISSEZ_PASSER_MIN,
)
from .coordinator import PcParentalCoordinator
from .entity import prune_stale, EntityIdFige, HubEntity, PcEntity, appliquer


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(
        [
            WarnMinutes(coordinator),
            IdleMinutes(coordinator),
            DelaiRefus(coordinator),
            DureeLaissezPasser(coordinator),
            RythmeListeNoire(coordinator),
        ]
        + [PauseMaxNumber(coordinator, pc_id) for pc_id in coordinator.pcs]
        + _son_numbers(coordinator)
    )
    prune_stale(hass, coordinator, "number", f"{DOMAIN}_pause_max_", {f"{DOMAIN}_pause_max_{pc}" for pc in coordinator.pcs})
    # La règle de moyenne a quitté l'intégration (1.60.0) : ses seuils quittent le registre.
    prune_stale(hass, coordinator, "number", f"{DOMAIN}_moyenne_seuil_", set())


class _Reglage(HubEntity, NumberEntity):
    """Un entier réglable, enregistré côté maison."""

    _attr_entity_category = EntityCategory.CONFIG
    _attr_mode = NumberMode.BOX
    _attr_native_step = 1
    _attr_native_unit_of_measurement = "min"
    _champ = ""

    @property
    def native_value(self) -> float:
        return float(getattr(self.coordinator.store, self._champ))

    async def async_set_native_value(self, value: float) -> None:
        entier = int(value)

        def _faire() -> None:
            setattr(self.coordinator.store, self._champ, entier)

        await appliquer(self.coordinator, _faire)


class WarnMinutes(_Reglage):
    """Combien de temps avant une fermeture le PC prévient-il ?"""

    _attr_translation_key = "avertir_avant_fermeture"
    _attr_icon = "mdi:bell-alert-outline"
    _attr_native_min_value = 0
    _attr_native_max_value = 60
    _champ = "warn_minutes"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_avertissement"


class IdleMinutes(_Reglage):
    """Au bout de combien de temps sans rien toucher le PC est-il inactif ?"""

    _attr_translation_key = "inactif_apres"
    _attr_icon = "mdi:timer-sand"
    _attr_native_min_value = 1
    _attr_native_max_value = 120
    _champ = "idle_minutes"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_inactivite"


class DelaiRefus(_Reglage):
    """Après un refus, combien de temps avant de pouvoir redemander la même chose ?"""

    _attr_translation_key = "attente_apres_un_refus"
    _attr_icon = "mdi:timer-lock-outline"
    _attr_native_min_value = 0
    _attr_native_max_value = 1440
    _champ = "delai_refus"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_delai_refus"


class DureeLaissezPasser(HubEntity, NumberEntity, RestoreEntity):
    """Combien de temps dure le laissez-passer qu'on s'apprête à donner.

    Ce n'est pas un réglage de la maison mais une intention du moment : il
    ne part donc pas dans le stockage, il se souvient simplement de la
    dernière durée choisie.
    """

    _attr_translation_key = "duree_du_laissez_passer"
    _attr_icon = "mdi:timer-outline"
    _attr_mode = NumberMode.BOX
    _attr_native_step = 5
    _attr_native_unit_of_measurement = "min"
    _attr_native_min_value = LAISSEZ_PASSER_MIN
    _attr_native_max_value = LAISSEZ_PASSER_MAX

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_duree_laissez_passer"
        self._minutes = LAISSEZ_PASSER_DEFAUT

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        dernier = await self.async_get_last_state()
        try:
            valeur = int(float(dernier.state)) if dernier else 0
        except (TypeError, ValueError):
            valeur = 0
        if LAISSEZ_PASSER_MIN <= valeur <= LAISSEZ_PASSER_MAX:
            self._minutes = valeur

    @property
    def native_value(self) -> float:
        return float(self._minutes)

    async def async_set_native_value(self, value: float) -> None:
        self._minutes = int(value)
        self.async_write_ha_state()


class RythmeListeNoire(HubEntity, NumberEntity):
    """Toutes les combien d'heures Vision retourne voir les sources.

    « Automatique » ne veut rien dire si l'on ne voit ni le rythme, ni la
    dernière fois que ça a marché. Les deux sont donc à l'écran, à côté l'un
    de l'autre.
    """

    _attr_translation_key = "rafraichir_la_liste_noire_toutes_les"
    _attr_icon = "mdi:timer-sync-outline"
    _attr_mode = NumberMode.BOX
    _attr_native_step = 1
    _attr_native_unit_of_measurement = "h"
    _attr_native_min_value = LISTE_NOIRE_HEURES_MIN
    _attr_native_max_value = LISTE_NOIRE_HEURES_MAX

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_rythme_liste_noire"

    @property
    def native_value(self) -> float:
        return float(self.coordinator.noire.heures)

    async def async_set_native_value(self, value: float) -> None:
        noire = self.coordinator.noire
        noire.heures = int(value)
        await noire.async_save()
        self.async_write_ha_state()
        if noire.active and noire.perimee():
            await noire.async_rafraichir(force=True)
        await self.coordinator.async_refresh()


class PauseMaxNumber(EntityIdFige, PcEntity, NumberEntity):
    """« Durée max de suspension » de cet appareil (1.70.0) : plafond des suspensions de la protection par code
    ou empreinte. Administrateurs."""

    _attr_translation_key = "duree_max_de_pause"
    _plateforme_entity_id = "number"
    _attr_icon = "mdi:timer-pause-outline"
    _attr_entity_category = EntityCategory.CONFIG
    _attr_mode = NumberMode.BOX
    _attr_native_step = 5
    _attr_native_unit_of_measurement = "min"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        from .pauses import PAUSE_MAX_MAX, PAUSE_MAX_MIN

        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_pause_max_{pc_id}"
        self._attr_native_min_value = PAUSE_MAX_MIN
        self._attr_native_max_value = PAUSE_MAX_MAX

    @property
    def native_value(self) -> float:
        from .roles import pause_max

        return float(pause_max(self.coordinator.store, self.pc))

    async def async_set_native_value(self, value: float) -> None:
        from .pauses import PAUSE_MAX_MAX, PAUSE_MAX_MIN

        entier = max(PAUSE_MAX_MIN, min(PAUSE_MAX_MAX, int(value)))

        def _faire() -> None:
            self.pc["pause_max"] = entier

        await appliquer(self.coordinator, _faire)


# --------------------------------------------------------------------------- Niveau sonore (2.5.12.3)


def _son_numbers(coordinator: PcParentalCoordinator) -> list:
    """Les réglages chiffrés du niveau sonore, par appareil, seulement quand le module est allumé (son.py)."""
    from . import son as _son

    if not _son.actif_module(coordinator.store):
        return []
    sortie: list = []
    for pc_id in coordinator.pcs:
        sortie += [SonSeuilNumber(coordinator, pc_id, nuit=False), SonSeuilNumber(coordinator, pc_id, nuit=True),
                   SonDureeNumber(coordinator, pc_id), SonReposNumber(coordinator, pc_id)]
    return sortie


class _SonNumber(EntityIdFige, PcEntity, NumberEntity):
    """Un réglage du niveau sonore de cet appareil (son.reglages), écrit par son.regler. Administrateurs."""

    _plateforme_entity_id = "number"
    _attr_entity_category = EntityCategory.CONFIG
    _attr_mode = NumberMode.BOX
    _cle = ""        # la clé de son.REGLAGES_DEFAUT
    _decalage = 0    # dBFS affiché = relatif + décalage (les seuils) ; 0 pour les durées

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str, suffixe: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_son_{suffixe}_{pc_id}"

    @property
    def native_value(self) -> float | None:
        from . import son as _son

        return float(_son.reglages(self.pc)[self._cle] + self._decalage) if self.pc else None

    async def async_set_native_value(self, value: float) -> None:
        from . import son as _son

        def _faire() -> None:
            _son.regler(self.pc, {self._cle: int(round(value)) - self._decalage})

        await appliquer(self.coordinator, _faire)


class SonSeuilNumber(_SonNumber):
    """Seuil de jour ou de nuit, en dBFS (0 = pleine échelle du micro ; l'appareil le reçoit en dB relatifs + 100)."""

    _attr_icon = "mdi:volume-high"
    _attr_native_unit_of_measurement = "dB"
    _attr_native_step = 1
    _decalage = -100

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str, nuit: bool) -> None:
        from . import son as _son

        self._cle = "seuil_nuit_db" if nuit else "seuil_db"
        self._attr_translation_key = "seuil_sonore_nuit" if nuit else "seuil_sonore_jour"
        if nuit:
            self._attr_icon = "mdi:weather-night"
        super().__init__(coordinator, pc_id, "seuil_nuit" if nuit else "seuil_jour")
        bas, haut = _son.BORNES[self._cle]
        self._attr_native_min_value, self._attr_native_max_value = bas - 100, haut - 100


class SonDureeNumber(_SonNumber):
    """Secondes au-dessus du seuil avant une alerte (« sensibilité »)."""

    _attr_translation_key = "bruit_duree"
    _attr_icon = "mdi:timer-sand"
    _attr_native_unit_of_measurement = "s"
    _attr_native_step = 1
    _cle = "sensibilite_s"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        from . import son as _son

        super().__init__(coordinator, pc_id, "duree")
        self._attr_native_min_value, self._attr_native_max_value = _son.BORNES["sensibilite_s"]


class SonReposNumber(_SonNumber):
    """Minutes au moins entre deux alertes comptées."""

    _attr_translation_key = "bruit_repos"
    _attr_icon = "mdi:timer-outline"
    _attr_native_unit_of_measurement = "min"
    _attr_native_step = 1
    _cle = "repos_min"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        from . import son as _son

        super().__init__(coordinator, pc_id, "repos")
        self._attr_native_min_value, self._attr_native_max_value = _son.BORNES["repos_min"]
