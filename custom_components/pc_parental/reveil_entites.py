"""Réveil (lot 1) : les entités de chaque réveil, et la fenêtre de silence.

Par réveil, un appareil HA « <nom du réveil> » (rattaché à Vision) porte :
- switch.vision_reveil_<nom> : actif / inactif ;
- sensor.vision_reveil_<nom>_prochain : horodatage de la prochaine occurrence armée (raison, occurrence suivante) ;
- sensor.vision_reveil_<nom>_etat : inactif · arme · sonne · reporte · arrete · echec · non_synchronise ;
- sensor.vision_reveil_<nom>_source : libellé de la source (type, uri) ;
- sensor.vision_reveil_<nom>_appareils : appareils visés (ids, synchronises).
Plus binary_sensor.vision_fenetre_reveil (global) : allumé d'une heure avant la première occurrence armée à
30 min après la dernière ; les outils de livraison ne redémarrent pas HA pendant ce temps.

Les entités d'un réveil neuf apparaissent sans recharger l'intégration (signal reveil.SIGNAL_NOUVEAUX) ; celles
d'un réveil supprimé partent avec son appareil HA (`retirer`). Module coupé : désactivées par modules.py.
"""

from __future__ import annotations

import functools
from datetime import datetime
from typing import Any

from homeassistant.components.binary_sensor import BinarySensorEntity
from homeassistant.components.sensor import SensorDeviceClass, SensorEntity
from homeassistant.components.switch import SwitchEntity
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import Unauthorized
from homeassistant.helpers import device_registry as dr

from . import registre as registre_vision
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.update_coordinator import CoordinatorEntity
from homeassistant.util import slugify

from . import lieu, reveil
from . import reveil_calcul as calcul
from .const import DOMAIN

SUFFIXES = {"switch": ("actif",), "sensor": ("prochain", "etat", "source", "appareils")}


def unique_id(ident: str, suffixe: str) -> str:
    return f"{DOMAIN}_reveil_{ident}_{suffixe}"


def brancher(hass: HomeAssistant, entry, coordinator, domaine: str, async_add_entities) -> None:
    """À appeler dans async_setup_entry de chaque plateforme : crée les entités des réveils connus et suit les neufs."""
    connus: set[str] = set()

    @callback
    def _ajouter() -> None:
        neuves = []
        for ident, r in list(reveil.reveils(coordinator.store).items()):
            if ident in connus:
                continue
            connus.add(ident)
            for suffixe in SUFFIXES.get(domaine, ()):
                neuves.append(CLASSES[(domaine, suffixe)](coordinator, ident, suffixe))
        if neuves:
            async_add_entities(neuves)

    _ajouter()
    entry.async_on_unload(async_dispatcher_connect(hass, reveil.SIGNAL_NOUVEAUX, _ajouter))


@callback
def retirer(hass: HomeAssistant, ident: str) -> None:
    """Retire les entités et l'appareil HA d'un réveil supprimé."""
    registre = er.async_get(hass)
    for domaine, suffixes in SUFFIXES.items():
        for suffixe in suffixes:
            eid = registre.async_get_entity_id(domaine, DOMAIN, unique_id(ident, suffixe))
            if eid:
                registre.async_remove(eid)
    appareils = dr.async_get(hass)
    appareil = registre_vision.appareil_par_identifiant(hass, (DOMAIN, f"reveil_{ident}"))
    if appareil is not None:
        appareils.async_remove_device(appareil.id)


@callback
def renommer(hass: HomeAssistant, r: dict[str, Any]) -> None:
    """Le nom du réveil a changé : l'appareil HA suit (les entity_id, eux, restent stables)."""
    appareils = dr.async_get(hass)
    appareil = registre_vision.appareil_par_identifiant(hass, (DOMAIN, f"reveil_{r['id']}"))
    if appareil is not None and appareil.name != r.get("nom"):
        appareils.async_update_device(appareil.id, name=str(r.get("nom")))


class _EntiteReveil(CoordinatorEntity):
    _attr_has_entity_name = True

    def __init__(self, coordinator, ident: str, suffixe: str) -> None:
        super().__init__(coordinator)
        self.ident = ident
        r = reveil.reveils(coordinator.store).get(ident) or {}
        domaine = "switch" if suffixe == "actif" else "sensor"
        self._attr_unique_id = unique_id(ident, suffixe)
        base = f"vision_reveil_{slugify(str(r.get('nom') or ident)) or ident}"
        self.entity_id = f"{domaine}.{base}" if suffixe == "actif" else f"{domaine}.{base}_{suffixe}"
        self._attr_translation_key = f"reveil_{suffixe}"
        infos = DeviceInfo(
            identifiers={(DOMAIN, f"reveil_{ident}")},
            name=str(r.get("nom") or calcul.NOM_DEFAUT),
            manufacturer=lieu.nom(coordinator.store),
            model="Réveil",  # i18n-exception : modèle d'appareil (donnée), comme le nom du module
        )
        # Rattaché au hub par son id d'appareil (le tuple via_device disparaît en HA 2027.8).
        hub = registre_vision.id_du_hub(coordinator.hass, coordinator.config_entry.entry_id)
        if hub is not None:
            infos["via_device_id"] = hub
        self._attr_device_info = infos

    @property
    def r(self) -> dict[str, Any]:
        return reveil.reveils(self.coordinator.store).get(self.ident) or {}

    @property
    def available(self) -> bool:
        return super().available and bool(self.r) and reveil.actif(self.coordinator.store)

    def _vue(self) -> dict[str, Any]:
        return reveil.vue(self.hass, self.coordinator.store, self.r)


def _garde_reveil(action: str):
    """Les droits du Réveil (et non « administrateur seulement ») : sans utilisateur, tout passe ; un utilisateur
    est contrôlé comme dans l'appli, par la personne qui lui est liée (reveil.peut)."""

    def enveloppe(methode):
        @functools.wraps(methode)
        async def garde(self, *args: Any, **kwargs: Any) -> Any:
            contexte = getattr(self, "_context", None)
            user_id = getattr(contexte, "user_id", None)
            droits, personne = await reveil.droits_utilisateur(self.hass, self.coordinator.store, user_id)
            if not reveil.peut(droits, personne, action, self.r):
                raise Unauthorized(context=contexte, user_id=user_id, entity_id=self.entity_id, permission="control")
            return await methode(self, *args, **kwargs)

        garde._vision_garde = True  # type: ignore[attr-defined]
        return garde

    return enveloppe


class ReveilActifSwitch(_EntiteReveil, SwitchEntity):
    _attr_icon = "mdi:alarm"
    _attr_name = None  # le nom de l'appareil HA, c'est-à-dire du réveil

    @property
    def is_on(self) -> bool:
        return bool(self.r.get("actif"))

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        r = self.r
        return {"reveil": self.ident, "demandeur": r.get("demandeur"), "impose": bool(r.get("impose")),
                "origine": r.get("origine"), "type": (r.get("declenchement") or {}).get("type")}

    async def _regler(self, valeur: bool) -> None:
        r = self.r
        if not r or bool(r.get("actif")) == valeur:
            return
        r["actif"] = valeur
        reveil._horodater(r, "entité")
        await self.coordinator.reveil.async_publier()

    @_garde_reveil("activer")
    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._regler(True)

    @_garde_reveil("desactiver")
    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._regler(False)


class ReveilProchainSensor(_EntiteReveil, SensorEntity):
    _attr_icon = "mdi:alarm-check"
    _attr_device_class = SensorDeviceClass.TIMESTAMP

    @property
    def native_value(self) -> datetime | None:
        v = self._vue()
        return datetime.fromisoformat(v["prochain"]) if v["prochain"] else None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        v = self._vue()
        return {"raison": v["raison"], "occurrence_suivante": v["occurrence_suivante"], "sautees": v["sautees"],
                "valeur_lue": (v["calcul"] or {}).get("valeur") or None}


class ReveilEtatSensor(_EntiteReveil, SensorEntity):
    _attr_icon = "mdi:alarm-note"
    _attr_device_class = SensorDeviceClass.ENUM
    _attr_options = list(calcul.ETATS)

    @property
    def native_value(self) -> str:
        return reveil.etat(self.coordinator.store, self.r)

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        vivant = self.r.get("vivant") or {}
        return {k: vivant.get(k) for k in ("occurrence", "jusqu_a", "par", "raison", "nombre") if vivant.get(k) not in (None, "")}


class ReveilSourceSensor(_EntiteReveil, SensorEntity):
    _attr_icon = "mdi:music-note"

    @property
    def native_value(self) -> str:
        return reveil.libelle_source(self.r)[:255]

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        s = self.r.get("source") or {}
        return {"type": s.get("type"), "uri": s.get("uri") or None, "lecteur": s.get("lecteur") or None,
                "repli": "sonnerie"}


class ReveilAppareilsSensor(_EntiteReveil, SensorEntity):
    _attr_icon = "mdi:cellphone-sound"

    @property
    def native_value(self) -> str:
        return ", ".join(self._vue()["appareils_noms"])[:255]

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        v = self._vue()
        return {"ids": v["appareils"], "synchronises": v["synchronises"],
                "par_defaut": not v["appareils_choisis"]}


CLASSES = {
    ("switch", "actif"): ReveilActifSwitch,
    ("sensor", "prochain"): ReveilProchainSensor,
    ("sensor", "etat"): ReveilEtatSensor,
    ("sensor", "source"): ReveilSourceSensor,
    ("sensor", "appareils"): ReveilAppareilsSensor,
}


class FenetreReveilBinarySensor(CoordinatorEntity, BinarySensorEntity):
    """Allumé pendant la fenêtre des réveils du jour : pas de redémarrage de HA (outils de livraison, automatisations)."""

    _attr_has_entity_name = True
    _attr_translation_key = "fenetre_reveil"
    _attr_icon = "mdi:alarm-snooze"

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_fenetre_reveil"
        self.entity_id = "binary_sensor.vision_fenetre_reveil"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, coordinator.config_entry.entry_id)})

    def _fenetre(self) -> dict[str, Any]:
        store = self.coordinator.store
        if not reveil.actif(store):
            return {"actif": False, "debut": "", "fin": ""}
        reg = reveil.reglages(store)
        return calcul.fenetre(list(reveil.reveils(store).values()), reveil.maintenant(),
                              int(reg["fenetre_avant_min"]), int(reg["fenetre_apres_min"]))

    @property
    def is_on(self) -> bool:
        return bool(self._fenetre()["actif"])

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        f = self._fenetre()
        return {"debut": f["debut"] or None, "fin": f["fin"] or None}


def valides(store, domaine: str) -> set[str]:
    """Les unique_id des entités de réveil de cette plateforme (pour prune_stale)."""
    return {unique_id(ident, s) for ident in reveil.reveils(store) for s in SUFFIXES.get(domaine, ())}
