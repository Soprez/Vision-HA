"""Pause générale (point 14.2) : un geste ferme d'un coup tous les appareils protégés, pour un moment.

Le dîner, les devoirs : « Pause générale 30 min » ferme tous les appareils des Demandeurs (rôle avec la
fonction « protege », sans « gerer » : un Décideur ne se ferme pas lui-même), puis « Reprendre » les rouvre
(retour au planning). Ce n'est PAS la pause de Vision (module « pause »), qui lève la protection.

- poser : une dérogation « fermé » de N minutes sur chaque appareil protégé (store.set_override), marquée
  `pause_generale` ; l'état {fin, debut, par, appareils} est gardé dans le magasin (store.pause_generale) ;
  pour chaque appareil, on retient la fermeture manuelle qui le tenait avant (s'il y en avait une) ;
- un appareil déjà fermé à la main plus longtemps que la pause (ou sans fin) n'est pas touché ;
- reprendre : seuls les appareils encore sous CETTE fermeture sont rouverts ; une fermeture manuelle d'avant,
  encore valable, est rendue (l'appareil reste fermé) ; un appareil refermé ou rouvert à la main entre-temps
  n'est pas touché ;
- à l'échéance, rien à faire : chaque dérogation expire seule ; l'état est nettoyé à la lecture suivante ;
- message à l'écran de blocage (module « Messages ») : « Pause générale jusqu'à 19h45 ».
Journal (WARNING) et événement `pc_parental_pause_generale` à chaque geste.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from homeassistant.core import HomeAssistant

from . import modules, roles, textes
from .const import DOMAIN, MODE_LOCKED, PRODUIT

_LOGGER = logging.getLogger(__name__)

MODULE = "pause_generale"
EVENEMENT = f"{DOMAIN}_pause_generale"
MINUTES_MIN = 1
MINUTES_MAX = 240
MARQUE = "pause_generale"


def protege(store, pc: dict[str, Any]) -> bool:
    """Un appareil que la pause générale ferme : suivi, protégé, et pas celui d'un Décideur."""
    return (bool(pc.get("enabled", True)) and roles.a(store, pc, "protege")
            and not roles.a(store, pc, "gerer"))


def heure(fin: float, langue: str | None = None) -> str:
    """L'heure de fin, lisible (« 19h45 ») ; sans langue : celle de Home Assistant."""
    return textes.heure(float(fin), langue)


def en_cours(store, maintenant: float | None = None) -> dict[str, Any] | None:
    """L'état de la pause en cours, ou None (une pause échue est oubliée ici)."""
    etat = getattr(store, "pause_generale", None)
    if not isinstance(etat, dict):
        return None
    maintenant = time.time() if maintenant is None else maintenant
    if float(etat.get("fin") or 0) <= maintenant:
        store.pause_generale = None
        return None
    return etat


def _sous_cette_pause(pc: dict[str, Any], fin: float) -> bool:
    o = pc.get("override") or None
    return bool(o) and o.get("mode") == MODE_LOCKED and o.get(MARQUE) is True and float(o.get("until") or 0) == float(fin)


def sous_pause(store, pc: dict[str, Any]) -> bool:
    """Cet appareil est-il fermé par la pause générale en cours ?"""
    etat = en_cours(store)
    return etat is not None and str(pc.get("id")) in (etat.get("appareils") or {}) and _sous_cette_pause(pc, etat["fin"])


def message(store, pc: dict[str, Any]) -> str | None:
    """Le texte de l'écran de blocage, si l'appareil est sous la pause et que les messages sont actifs."""
    if modules.coupe(store, MODULE) or modules.coupe(store, "messages") or not sous_pause(store, pc):
        return None
    langue = textes.pour(getattr(store, "hass", None), pc)
    return textes.t("pg.ecran", langue, heure=heure(store.pause_generale["fin"], langue))


def public(store) -> dict[str, Any] | None:
    """Ce que l'espace gestion reçoit : {"fin": horodatage} ou None."""
    if modules.coupe(store, MODULE):
        return None
    etat = en_cours(store)
    return {"fin": int(float(etat["fin"]))} if etat else None


def _encore_valable(o: Any, maintenant: float) -> bool:
    return isinstance(o, dict) and o.get("mode") == MODE_LOCKED and (
        not float(o.get("until") or 0) or float(o.get("until") or 0) > maintenant)


def poser(hass: HomeAssistant, store, minutes: int, par: str) -> dict[str, Any]:
    """Ferme tous les appareils protégés pour `minutes`. Rend l'état. ValueError si rien à faire."""
    try:
        minutes = int(minutes)
    except (TypeError, ValueError) as err:
        raise textes.Erreur("pg.duree", min=MINUTES_MIN, max=MINUTES_MAX) from err
    if not MINUTES_MIN <= minutes <= MINUTES_MAX:
        raise textes.Erreur("pg.duree", min=MINUTES_MIN, max=MINUTES_MAX)
    maintenant = time.time()
    precedente = en_cours(store, maintenant)
    fin = maintenant + minutes * 60
    appareils: dict[str, Any] = {}
    cibles = [pc for pc in store.pcs.values() if protege(store, pc)]
    if not cibles:
        raise textes.Erreur("pg.aucun_appareil")
    for pc in cibles:
        pc_id = str(pc["id"])
        avant = pc.get("override") or None
        if precedente is not None and pc_id in (precedente.get("appareils") or {}) and _sous_cette_pause(pc, precedente["fin"]):
            # Une pause qui en remplace une autre garde la fermeture manuelle d'avant la première.
            avant = precedente["appareils"][pc_id]
        elif _encore_valable(avant, maintenant) and (not float(avant.get("until") or 0) or float(avant["until"]) >= fin):
            continue  # déjà fermé à la main au moins aussi longtemps : on n'y touche pas
        store.set_override(pc, MODE_LOCKED, minutes)
        pc["override"]["until"] = fin
        pc["override"][MARQUE] = True
        appareils[pc_id] = dict(avant) if _encore_valable(avant, maintenant) else None
    store.pause_generale = {"fin": fin, "debut": maintenant, "par": par, "minutes": minutes, "appareils": appareils}
    # 1.93.0 (point 6 bis) : une notification native sur chaque appareil fermé, quel que soit son lanceur.
    _prevenir(store, appareils, textes.Phrase("pg.notif_pose", heure=lambda lg: heure(fin, lg)))
    noms = ", ".join(str(store.pcs[i].get("name") or i) for i in appareils)
    _LOGGER.warning("%s : pause générale de %d min, jusqu'à %s (par %s) — appareils fermés : %s.",
                    PRODUIT, minutes, heure(fin, "fr"), par, noms or "aucun (déjà fermés)")
    hass.bus.async_fire(EVENEMENT, {"etat": "pose", "minutes": minutes, "fin": int(fin), "par": par,
                                    "appareils": sorted(appareils)})
    return store.pause_generale


def _prevenir(store, ids, texte: textes.Phrase) -> None:
    """Un message Vision (notification Android native) sur ces appareils, si le module « Messages » vit.

    Chaque appareil le reçoit dans sa langue."""
    if modules.coupe(store, "messages"):
        return
    for pc_id in ids:
        langue = textes.pour(getattr(store, "hass", None), store.pcs.get(str(pc_id)))
        try:
            store.add_message(str(pc_id), texte.en(langue), titre="Vision")
        except ValueError:
            pass


def reprendre(hass: HomeAssistant, store, par: str) -> list[str]:
    """Rouvre les appareils encore sous la pause en cours. Rend leurs identifiants ([] si aucune pause)."""
    maintenant = time.time()
    etat = en_cours(store, maintenant)
    if etat is None:
        store.pause_generale = None
        return []
    rouverts: list[str] = []
    for pc_id, avant in (etat.get("appareils") or {}).items():
        pc = store.pcs.get(pc_id)
        if pc is None or not _sous_cette_pause(pc, etat["fin"]):
            continue  # rouvert ou refermé à la main entre-temps : ce n'est plus la pause qui le tient
        if _encore_valable(avant, maintenant):
            pc["override"] = dict(avant)  # la fermeture manuelle d'avant reprend sa place
        else:
            store.clear_override(pc)
            rouverts.append(pc_id)
    store.pause_generale = None
    _prevenir(store, rouverts, textes.Phrase("pg.notif_fin"))
    noms = ", ".join(str(store.pcs[i].get("name") or i) for i in rouverts)
    _LOGGER.warning("%s : fin de la pause générale (par %s) — appareils rouverts : %s.", PRODUIT, par, noms or "aucun")
    hass.bus.async_fire(EVENEMENT, {"etat": "fin", "par": par, "appareils": sorted(rouverts)})
    return rouverts


async def async_poser(hass: HomeAssistant, coord, minutes: int, par: str, langue: str | None = None) -> str:
    """Pose la pause ; rend la phrase pour qui l'a posée (dans `langue`, sinon celle de Home Assistant)."""
    etat = poser(hass, coord.store, minutes, par)
    await coord.async_ecrire(structurel=False)
    langue = langue or textes.ha(hass)
    return textes.n("pg.pose", len(etat["appareils"]), langue, heure=heure(etat["fin"], langue))


async def async_reprendre(hass: HomeAssistant, coord, par: str, langue: str | None = None) -> str:
    rouverts = reprendre(hass, coord.store, par)
    await coord.async_ecrire(structurel=False)
    return textes.t("pg.fin_planning" if rouverts else "pg.fin", langue or textes.ha(hass))
