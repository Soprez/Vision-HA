"""2.2.11 : le journal de bord de Home Assistant décrit les commandes Musique de Vision, dans la langue de HA."""
from __future__ import annotations

from homeassistant.components.logbook import LOGBOOK_ENTRY_MESSAGE, LOGBOOK_ENTRY_NAME
from homeassistant.core import Event, HomeAssistant, callback

from . import textes
from .const import DOMAIN
from .journal_musique import EVENEMENT, phrase


@callback
def async_describe_events(hass: HomeAssistant, async_describe_event) -> None:
    @callback
    def decrire(event: Event) -> dict[str, str]:
        langue = textes.ha(hass)
        return {LOGBOOK_ENTRY_NAME: textes.t("mus.j.titre", langue),
                LOGBOOK_ENTRY_MESSAGE: phrase(dict(event.data), langue)}

    async_describe_event(DOMAIN, EVENEMENT, decrire)
