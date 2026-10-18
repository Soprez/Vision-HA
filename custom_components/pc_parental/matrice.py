"""Matrice réglages × type d'appareil (1.72.0, décisions du 2026-10-06).

- Le type vient du relevé : l'agent Windows → « pc » ; l'appli Android envoie « telephone »,
  « tablette » ou « tv » (une ancienne appli qui ne l'envoie pas reste « android »).
- Une entité sans objet pour le type de l'appareil est DÉSACTIVÉE par l'intégration, jamais
  supprimée : son historique reste. Elle est réactivée si le type change.
- Côté appli : ce qui est sans objet est masqué, ce qui dépend d'un état changeant est grisé avec
  la raison (et la veille reste sur les tablettes, masquée seulement sur les téléphones).
"""
from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er

from . import entites_reduites, modules
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

TYPES_ANDROID = ("telephone", "tablette", "tv")
ANDROID = TYPES_ANDROID + ("android",)

# (domaine, début de l'unique_id) → types d'appareil pour lesquels l'entité est sans objet.
SANS_OBJET: dict[tuple[str, str], frozenset[str]] = {
    # Les pauses de la protection n'existent que dans l'appli Android.
    ("number", f"{DOMAIN}_pause_max_"): frozenset({"pc"}),
    # La session administrateur Windows : l'appli Android ne la remonte pas.
    ("binary_sensor", f"{DOMAIN}_compte_admin_"): frozenset(ANDROID),
    # Une télé n'a pas de session à elle : c'est un écran partagé.
    ("sensor", f"{DOMAIN}_session_"): frozenset({"tv"}),
}


def type_appareil(corps: dict[str, Any]) -> str:
    if str(corps.get("platform") or "") != "android":
        return "pc"
    t = str(corps.get("type") or "")
    return t if t in TYPES_ANDROID else "android"


def sans_objet(store, domaine: str, unique_id: str) -> bool:
    """Vrai si la matrice veut cette entité éteinte (type de l'appareil) : les modules ne la rallument pas."""
    for (dom, debut), types in SANS_OBJET.items():
        if dom == domaine and str(unique_id).startswith(debut):
            pc = store.pcs.get(str(unique_id)[len(debut):]) or {}
            return str(pc.get("type") or "") in types
    return False


def appliquer(hass: HomeAssistant, store) -> int:
    """Désactive (ou réactive) les entités selon le type de chaque appareil. Rend le nombre de changements."""
    registre = er.async_get(hass)
    changements = 0
    for pc_id, pc in store.pcs.items():
        t = str(pc.get("type") or "")
        if not t:
            continue  # pas encore de relevé depuis 1.72.0 : on ne touche à rien
        for (domaine, debut), types in SANS_OBJET.items():
            entity_id = registre.async_get_entity_id(domaine, DOMAIN, f"{debut}{pc_id}")
            entree = registre.async_get(entity_id) if entity_id else None
            if entree is None:
                continue
            if t in types and entree.disabled_by is None:
                registre.async_update_entity(entity_id, disabled_by=er.RegistryEntryDisabler.INTEGRATION)
                changements += 1
                _LOGGER.warning("%s : %s désactivée (sans objet sur un appareil de type « %s » ; historique gardé).",
                                PRODUIT, entity_id, t)
            elif (t not in types and entree.disabled_by is er.RegistryEntryDisabler.INTEGRATION
                  and not modules.entite_coupee(store, domaine, f"{debut}{pc_id}")
                  and not entites_reduites.desactivee_par_uid(store, f"{debut}{pc_id}")):
                # 1.82.0 : une entité d'un module coupé reste éteinte (modules.appliquer_entites).
                registre.async_update_entity(entity_id, disabled_by=None)
                changements += 1
                _LOGGER.warning("%s : %s réactivée (appareil de type « %s »).", PRODUIT, entity_id, t)
    return changements
