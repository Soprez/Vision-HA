"""Musique : arrêt automatique après inactivité, et écran éteint (2.4.0, demandes d'essai du 2026-10-12).

Arrêt automatique (option « Arrêt automatique de la lecture » dans Configurer › Musique) : un lecteur de Music Assistant
qui joue sans aucune activité depuis le délai choisi est arrêté. L'activité, c'est :
  - une action sur la musique : lecture, pause, suivant, ajout dans la file (y compris depuis la page invités) ;
  - une interaction avec l'appareil qui porte ce lecteur (l'inactivité que remonte son agent) ;
  - la réponse « Continuer » à l'avertissement.
Une minute avant, un avertissement s'affiche sur l'appareil (message Vision avec la réponse « Continuer ») ; à
l'échéance : pause puis arrêt, le lecteur n'est plus « en cours » et le mini-lecteur disparaît.

Écran éteint (relevé de l'appli : « ecran » = « eteint ») : la lecture du lecteur « · Vision » de cet appareil est
mise en pause. L'appli lit l'état de l'écran par DisplayManager (sans permission système) ; l'état réel du téléviseur
n'est pas connu sans CEC système.

Le futur lecteur Sendspin devra respecter la même option (même moteur, seule l'identification du lecteur change).
"""
from __future__ import annotations

import logging
import time
from typing import Any

from homeassistant.core import HomeAssistant

from . import musique, textes

_LOGGER = logging.getLogger(__name__)

# Valeurs proposées (minutes) ; 0 = désactivé. Défaut : 2 h. « Personnalisé » : ARRET_PERSO_MIN à ARRET_PERSO_MAX.
CHOIX_MIN = (0, 30, 60, 120, 240)
DEFAUT_MIN = 120
ARRET_PERSO_MIN, ARRET_PERSO_MAX = 5, 1440
AVANCE_S = 60
REPONSE_CONTINUER = "continuer"

_ACTIVITE: dict[str, float] = {}
_AVERTIS: dict[str, float] = {}


def delai_min(store) -> int:
    """Le délai réglé (minutes), 0 = désactivé."""
    v = (getattr(store, "musique", None) or {}).get("arret_auto_min", DEFAUT_MIN)
    try:
        v = int(v)
    except (TypeError, ValueError):
        return DEFAUT_MIN
    return 0 if v <= 0 else max(ARRET_PERSO_MIN, min(ARRET_PERSO_MAX, v))


def noter(lecteur: str, quand: float | None = None) -> None:
    """Une activité sur ce lecteur : le délai repart de zéro (et l'avertissement en cours est oublié)."""
    if lecteur:
        _ACTIVITE[lecteur] = quand if quand is not None else time.time()
        _AVERTIS.pop(lecteur, None)


def appareils_du_lecteur(hass: HomeAssistant, store, lecteur: str) -> list[str]:
    """Les appareils qui portent ce lecteur : celui dont le lecteur « · Vision » porte le nom, et l'écran de la Party
    qui l'affiche."""
    s = hass.states.get(lecteur)
    nom = str(s.attributes.get("friendly_name") or "") if s is not None else ""
    ids = []
    if nom.endswith(musique.SUFFIXE_VISION):
        appareil = nom[: -len(musique.SUFFIXE_VISION)].strip().lower()
        ids += [pid for pid, pc in store.pcs.items() if str(pc.get("name") or "").strip().lower() == appareil]
    from .party import etat as etat_party
    st = etat_party(hass)
    if st.get("actif") and st.get("lecteur") == lecteur and st.get("ecran") in store.pcs and st["ecran"] not in ids:
        ids.append(st["ecran"])
    return ids


def _derniere_activite(hass: HomeAssistant, store, lecteur: str, maintenant: float) -> float:
    vu = _ACTIVITE.get(lecteur)
    if vu is None:  # premier passage : on part de maintenant (jamais d'arrêt sur un démarrage de HA)
        vu = _ACTIVITE[lecteur] = maintenant
    for pid in appareils_du_lecteur(hass, store, lecteur):
        agent = (store.pcs.get(pid) or {}).get("agent") or {}
        inactif = agent.get("idle_seconds")
        vu_le = float((store.pcs.get(pid) or {}).get("last_seen") or 0)
        if isinstance(inactif, (int, float)) and vu_le:
            vu = max(vu, vu_le - float(inactif))
    return vu


async def _avertir(hass: HomeAssistant, coord, lecteur: str) -> None:
    from . import messages_v2
    for pid in appareils_du_lecteur(hass, coord.store, lecteur):
        lg = textes.pour(hass, coord.store.pcs.get(pid))
        try:
            r = await messages_v2.envoyer(hass, coord, {
                "destinataires": [pid], "texte": textes.t("musique.arret_bientot", lg),
                "titre": textes.t("musique.arret_titre", lg),
                "reponses": [textes.t("musique.arret_continuer", lg)],
            }, {"nom": "Vision", "arret_auto": lecteur})
            _LOGGER.info("Musique : arrêt automatique de %s dans 1 min, avertissement sur %s (%s)", lecteur, pid, r.get("envoi"))
        except Exception as err:  # noqa: BLE001 - l'arrêt se fera quand même
            _LOGGER.warning("Musique : avertissement d'arrêt non envoyé à %s : %s", pid, err)


async def _arreter(hass: HomeAssistant, lecteur: str) -> None:
    for service in ("media_pause", "media_stop"):
        try:
            await hass.services.async_call("media_player", service, {"entity_id": lecteur}, blocking=True)
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("Musique : %s de %s : %s", service, lecteur, err)
    _LOGGER.warning("Musique : %s arrêté après inactivité", lecteur)


async def tour(hass: HomeAssistant, coord, maintenant: float | None = None) -> list[str]:
    """Un passage (toutes les 30 s) : avertit, puis arrête les lecteurs inactifs. Rend les lecteurs arrêtés."""
    maintenant = maintenant if maintenant is not None else time.time()
    delai = delai_min(coord.store) * 60
    arretes = []
    for lecteur in sorted(musique.lecteurs_ma(hass)):
        s = hass.states.get(lecteur)
        if s is None or s.state != "playing":
            _ACTIVITE.pop(lecteur, None)
            _AVERTIS.pop(lecteur, None)
            continue
        if not delai:
            continue
        inactif = maintenant - _derniere_activite(hass, coord.store, lecteur, maintenant)
        if inactif >= delai:
            await _arreter(hass, lecteur)
            _ACTIVITE.pop(lecteur, None)
            _AVERTIS.pop(lecteur, None)
            arretes.append(lecteur)
        elif inactif >= delai - AVANCE_S and lecteur not in _AVERTIS:
            _AVERTIS[lecteur] = maintenant
            await _avertir(hass, coord, lecteur)
    return arretes


def reponse(suivi_msg: dict[str, Any]) -> bool:
    """La réponse « Continuer » à un avertissement d'arrêt : le délai repart. Vrai si c'était un avertissement."""
    lecteur = (suivi_msg.get("expediteur") or {}).get("arret_auto")
    if not lecteur:
        return False
    noter(str(lecteur))
    return True


async def ecran(hass: HomeAssistant, store, pc: dict[str, Any], etat_ecran: str) -> bool:
    """L'appli signale son écran : éteint → la lecture du lecteur « · Vision » de cet appareil est mise en pause."""
    avant = pc.get("ecran")
    pc["ecran"] = etat_ecran
    if etat_ecran != "eteint" or avant == "eteint":
        return False
    nom = str(pc.get("name") or "").strip().lower()
    for lecteur in musique.lecteurs_ma(hass):
        s = hass.states.get(lecteur)
        fn = str(s.attributes.get("friendly_name") or "") if s is not None else ""
        if s is not None and s.state == "playing" and fn.endswith(musique.SUFFIXE_VISION) \
                and fn[: -len(musique.SUFFIXE_VISION)].strip().lower() == nom:
            try:
                await hass.services.async_call("media_player", "media_pause", {"entity_id": lecteur}, blocking=True)
                _LOGGER.info("Musique : écran de %s éteint, %s en pause", pc.get("name"), lecteur)
                return True
            except Exception as err:  # noqa: BLE001
                _LOGGER.warning("Musique : pause de %s (écran éteint) impossible : %s", lecteur, err)
    return False
