"""Accès au registre des appareils de Home Assistant, en un seul endroit.

Home Assistant 2027.8 supprime ``DeviceRegistry.async_get_device`` (recherche
par ``identifiers=``/``connections=``) : un identifiant n'est plus unique que
par entrée de configuration. La recherche passe donc par
``async_get_device_by_identifier(identifiant, config_entry_id)``, présent dès
la version minimale déclarée (2026.9.0), pour chaque entrée de Vision.
"""

from __future__ import annotations

from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr

from .const import DOMAIN


def identifiant_pc(pc_id: str) -> tuple[str, str]:
    """Identifiant d'appareil Home Assistant d'un PC."""
    return (DOMAIN, f"pc_{pc_id}")


def appareil_par_identifiant(
    hass: HomeAssistant, identifiant: tuple[str, str]
) -> dr.DeviceEntry | None:
    """Appareil de Vision portant cet identifiant, ou None s'il n'existe pas."""
    registre = dr.async_get(hass)
    for entree in hass.config_entries.async_entries(DOMAIN):
        appareil = registre.async_get_device_by_identifier(identifiant, entree.entry_id)
        if appareil is not None:
            return appareil
    return None


def appareil_du_pc(hass: HomeAssistant, pc_id: str) -> dr.DeviceEntry | None:
    """Appareil Home Assistant de ce PC, ou None s'il n'existe pas encore."""
    return appareil_par_identifiant(hass, identifiant_pc(pc_id))


def identifiant_hub(entry_id: str) -> tuple[str, str]:
    """Identifiant de l'appareil « Vision » (le hub) d'une entrée."""
    return (DOMAIN, entry_id)


def id_du_hub(hass: HomeAssistant, entry_id: str) -> str | None:
    """Id d'appareil du hub « Vision » de cette entrée, ou None s'il n'existe pas.

    Sert à rattacher chaque PC au hub par ``via_device_id`` : le tuple
    ``via_device`` est supprimé en HA 2027.8.
    """
    hub = dr.async_get(hass).async_get_device_by_identifier(
        identifiant_hub(entry_id), entry_id
    )
    return hub.id if hub is not None else None
