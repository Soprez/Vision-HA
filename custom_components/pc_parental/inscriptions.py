"""Inscription de nouveaux appareils : ouverte, fermée, ou ouverte quelques minutes.

« Autoriser 5 min » ouvre les inscriptions puis les referme toute seule. L'échéance
est enregistrée (store.enroll_jusqua) : c'est elle qui fait foi à chaque inscription,
le minuteur ne sert qu'à refermer l'interrupteur et à le journaliser à l'heure dite.
Il est replanifié à chaque chargement de l'intégration, donc après un rechargement
ou un redémarrage ; une échéance passée pendant l'arrêt est refermée au démarrage.

Même idée que « Autoriser une réinscription » (une fenêtre par appareil). L'appairage
par QR, lui, ne dépend pas de ce réglage : chaque appareil y est validé par un
administrateur.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime, timezone
from typing import TYPE_CHECKING

from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.event import async_track_point_in_utc_time

from .const import DOMAIN

if TYPE_CHECKING:
    from .coordinator import PcParentalCoordinator

_LOGGER = logging.getLogger(__name__)

TEMPORAIRE_MINUTES = 5
OUVRIR, FERMER, TEMPORAIRE = "ouvrir", "fermer", "temporaire"
_CLE = f"{DOMAIN}_inscriptions_minuteur"


def reste(coord: PcParentalCoordinator) -> int:
    """Secondes avant la refermeture automatique, 0 s'il n'y en a pas."""
    if coord.store.enroll_open:
        return 0
    return max(0, int(float(coord.store.enroll_jusqua or 0) - time.time() + 0.999))


def etat(coord: PcParentalCoordinator, langue: str | None = None) -> str:
    """En clair, pour l'écran des options (dans `langue`, sinon celle de Home Assistant)."""
    from . import textes

    langue = langue or textes.ha(getattr(coord, "hass", None))
    if coord.store.enroll_open:
        return textes.t("insc.ouvertes", langue)
    secondes = reste(coord)
    if secondes:
        fin = datetime.fromtimestamp(coord.store.enroll_jusqua).astimezone()
        return textes.t("insc.temporaire", langue, min=secondes // 60, s="{:02d}".format(secondes % 60),
                        heure=fin.strftime("%H:%M:%S"))
    return textes.t("insc.fermees", langue)


def _annuler(hass: HomeAssistant) -> None:
    if annuler := hass.data.pop(_CLE, None):
        annuler()


async def _refermer(hass: HomeAssistant, coord: PcParentalCoordinator, au_demarrage: bool = False) -> None:
    store = coord.store
    if not store.enroll_jusqua or store.enroll_open:
        return
    store.enroll_jusqua = 0.0
    # En avertissement : le journal de Home Assistant ne garde que ceux-là par défaut.
    _LOGGER.warning(
        "Inscriptions de nouveaux appareils refermées automatiquement%s.",
        " (échéance passée pendant l'arrêt)" if au_demarrage else f" ({TEMPORAIRE_MINUTES} minutes écoulées)",
    )
    await coord.async_ecrire()
    coord.async_update_listeners()


@callback
def planifier(hass: HomeAssistant, coord: PcParentalCoordinator) -> None:
    """(Re)planifie la refermeture d'après l'échéance enregistrée."""
    _annuler(hass)
    store = coord.store
    if store.enroll_open or not store.enroll_jusqua:
        return
    if store.enroll_jusqua <= time.time():
        hass.async_create_task(_refermer(hass, coord, au_demarrage=True))
        return

    async def _echeance(_maintenant: datetime) -> None:
        hass.data.pop(_CLE, None)
        await _refermer(hass, coord)

    hass.data[_CLE] = async_track_point_in_utc_time(
        hass, _echeance, datetime.fromtimestamp(store.enroll_jusqua, tz=timezone.utc)
    )


@callback
def arreter(hass: HomeAssistant) -> None:
    """Au déchargement : le minuteur s'arrête, l'échéance enregistrée reste."""
    _annuler(hass)


async def regler(hass: HomeAssistant, coord: PcParentalCoordinator, mode: str, par: str = "") -> None:
    """Ouvre, ferme, ou ouvre pour TEMPORAIRE_MINUTES minutes."""
    store = coord.store
    if mode == OUVRIR:
        store.enroll_open, store.enroll_jusqua = True, 0.0
    elif mode == FERMER:
        store.enroll_open, store.enroll_jusqua = False, 0.0
    elif mode == TEMPORAIRE:
        store.enroll_open, store.enroll_jusqua = False, time.time() + TEMPORAIRE_MINUTES * 60
    else:
        from . import textes

        raise textes.Erreur("insc.mode_inconnu", mode=mode)
    # En avertissement : le journal de Home Assistant ne garde que ceux-là par défaut.
    _LOGGER.warning(
        "Inscriptions de nouveaux appareils %s%s.",
        {OUVRIR: "ouvertes", FERMER: "fermées", TEMPORAIRE: f"ouvertes pour {TEMPORAIRE_MINUTES} minutes"}[mode],
        f" par {par}" if par else "",
    )
    planifier(hass, coord)
    await coord.async_ecrire()
    coord.async_update_listeners()
