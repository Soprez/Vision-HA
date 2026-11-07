"""1.94 · Entités pour les automatisations (spec §3 bis, lot E1) : ce qui manquait au guide des entités (§9).

Par personne : `sensor.<personne>_prochain_reveil`, `binary_sensor.<personne>_concentration`,
`sensor.<personne>_temps_restant`, `button.<personne>_reveil_reporter`, `button.<personne>_reveil_arreter`.
Par appareil : `sensor.<appareil>_temps_restant`, `binary_sensor.<tv>_ecran` (télé et tablette qui le disent).
Global : `sensor.vision_party` (état de la soirée, gardé au redémarrage), voir `party.py`.

Une entité par chose à surveiller, `unique_id` stable, catégorie `diagnostic` pour les capteurs d'état, noms traduits en six
langues (translations/), vocabulaire neutre. Les calculs sont ici, sans Home Assistant : les essais les couvrent.
"""
from __future__ import annotations

import math
import time
from datetime import datetime, timedelta
from typing import Any

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.components.button import ButtonEntity
from homeassistant.components.sensor import SensorDeviceClass, SensorEntity
from homeassistant.const import EntityCategory, UnitOfTime
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.util import dt as dt_util

from . import concentration, reveil
from . import reveil_calcul as calcul
from .const import DOMAIN
from .entity import HubEntity, PcEntity, nom_traduit

MINUTE = timedelta(minutes=1)


# ----------------------------------------------------------------------------- Calculs (purs)


def slug(personne: str) -> str:
    return personne.split(".")[-1]


def prenom(hass: HomeAssistant, personne: str) -> str:
    etat = hass.states.get(personne)
    return str(etat.name) if etat is not None and etat.name else slug(personne)


def reveils_armes(store, personne: str, quand: datetime | None = None) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    """Les réveils actifs de cette personne avec leur prochaine occurrence, du plus proche au plus lointain."""
    quand = quand or reveil.maintenant()
    liste = []
    for r in reveil.reveils(store).values():
        if r.get("demandeur") != personne or not r.get("actif") or not reveil.actif(store):
            continue
        prochaine = calcul.prochaine(r, quand)
        if prochaine:
            liste.append((r, prochaine))
    liste.sort(key=lambda x: x[1]["heure"])
    return liste


def reveil_actionnable(store, personne: str, quand: datetime | None = None) -> dict[str, Any] | None:
    """Le réveil que les boutons visent : celui qui sonne (ou est reporté), sinon le prochain."""
    quand = quand or reveil.maintenant()
    if not reveil.actif(store):
        return None
    for r in reveil.reveils(store).values():
        if r.get("demandeur") == personne and reveil.etat(store, r, quand) in ("sonne", "reporte"):
            return r
    armes = reveils_armes(store, personne, quand)
    return armes[0][0] if armes else None


def prochain_reveil(hass: HomeAssistant, store, personne: str, quand: datetime | None = None) -> dict[str, Any] | None:
    armes = reveils_armes(store, personne, quand)
    if not armes:
        return None
    r, prochaine = armes[0]
    noms = {str(pc.get("id")): str(pc.get("name") or pc.get("id")) for pc in store.pcs.values()}
    return {"heure": datetime.fromisoformat(prochaine["heure"]), "reveil": r.get("nom"), "id": r["id"],
            "source": reveil.libelle_source(r), "appareils": [noms.get(str(pc.get("id")), str(pc.get("id"))) for pc in reveil.cibles(store, r)]}


def concentration_de(store, personne: str, maintenant: float | None = None) -> dict[str, Any] | None:
    """La concentration en cours sur les appareils de cette personne : fin la plus tardive, qui l'a lancée, appareils."""
    maintenant = time.time() if maintenant is None else maintenant
    en_cours = [(pc, c) for pc in store.pcs.values() if str(pc.get("person") or "") == personne
                for c in [concentration.active(pc, maintenant)] if c]
    if not en_cours:
        return None
    fin = max(float(c["fin"]) for _, c in en_cours)
    par = next((str(c.get("par") or "") for _, c in en_cours if c.get("par")), "")
    return {"fin": fin, "par": par, "appareils": [str(pc.get("name") or pc.get("id")) for pc, _ in en_cours]}


def temps_restant(store, pc: dict[str, Any], maintenant: float | None = None) -> dict[str, Any] | None:
    """Les minutes avant la prochaine fermeture de cet appareil, et pourquoi : le plus proche de
    - la fin du planning ouvert (`planning`) ;
    - la fin d'un temps gagné (`temps_gagne`, un laissez-passer ouvert pour un temps) ;
    - le temps qui reste à une limite par logiciel (`limite_logiciel:<nom>`, en minutes d'usage) ;
    - la fermeture en cours (`pause`, minutes 0) : une pause, un planning fermé.
    None quand rien ne ferme. Le calcul est celui du magasin (`evaluate`, `limites`, `temps_du_jour`)."""
    maintenant = time.time() if maintenant is None else maintenant
    if not pc.get("enabled", True):
        return None
    ev = store.evaluate(pc)
    candidats: list[tuple[float, str, float | None]] = []   # (minutes, raison, fin horodatage)
    if ev["locked"]:
        raison = "pause" if ev.get("override") else "planning"
        return {"minutes": 0.0, "raison": raison, "fin": None}
    over = ev.get("override")
    if over and float(over.get("until") or 0) > maintenant:
        fin = float(over["until"])
        candidats.append(((fin - maintenant) / 60, "temps_gagne", fin))
    suivant = ev.get("next_change")
    if suivant is not None and not over:
        fin = dt_util.as_local(suivant.replace(tzinfo=dt_util.DEFAULT_TIME_ZONE)).timestamp() if suivant.tzinfo is None else suivant.timestamp()
        if fin > maintenant:
            candidats.append(((fin - maintenant) / 60, "planning", fin))
    for nom, minutes in store.limites(pc).items():
        reste = minutes * 60 - float(store.temps_du_jour(pc, nom))
        if reste > 0:
            candidats.append((reste / 60, f"limite_logiciel:{nom}", maintenant + reste))
    if not candidats:
        return None
    m, raison, fin = min(candidats, key=lambda c: c[0])
    return {"minutes": max(0.0, m), "raison": raison, "fin": fin}


def temps_restant_personne(store, personne: str, maintenant: float | None = None) -> dict[str, Any] | None:
    """Le plus proche des appareils de la personne ; l'appareil qui ferme en premier est nommé."""
    pistes = []
    for pc in store.pcs.values():
        if str(pc.get("person") or "") != personne:
            continue
        t = temps_restant(store, pc, maintenant)
        if t is not None:
            pistes.append({**t, "appareil": str(pc.get("name") or pc.get("id"))})
    return min(pistes, key=lambda t: t["minutes"]) if pistes else None


def est_android(pc: dict[str, Any]) -> bool:
    return str(pc.get("platform") or (pc.get("agent") or {}).get("platform") or "") == "android"


def ecran_tele(pc: dict[str, Any]) -> bool | None:
    """L'écran de l'appareil, tel que l'appli l'a dit au dernier relevé : True allumé, False éteint, None inconnu."""
    e = (pc.get("ecran_releve") or {}).get("etat")
    return True if e == "allume" else False if e == "eteint" else None


# ----------------------------------------------------------------------------- Entités


class _Minute:
    """Les entités dont la valeur dépend de l'heure se recalculent chaque minute, sans attendre un relevé."""

    async def async_added_to_hass(self) -> None:  # type: ignore[override]
        await super().async_added_to_hass()  # type: ignore[misc]
        self.async_on_remove(async_track_time_interval(self.hass, self._minute, MINUTE))  # type: ignore[attr-defined]

    @callback
    def _minute(self, _maintenant: datetime) -> None:
        self.async_write_ha_state()  # type: ignore[attr-defined]


class _Personne(HubEntity):
    def __init__(self, coordinator, personne: str, cle: str) -> None:
        super().__init__(coordinator)
        self.personne = personne
        self._cle = cle
        self._attr_unique_id = f"{DOMAIN}_{cle}_{slug(personne)}"

    @property
    def name(self) -> str:  # type: ignore[override]
        return nom_traduit(self.coordinator.hass, self.PLATEFORME, self._attr_translation_key or self._cle,
                           prenom=prenom(self.coordinator.hass, self.personne))

    PLATEFORME = "sensor"


class ProchainReveilSensor(_Minute, _Personne, SensorEntity):
    PLATEFORME = "sensor"
    _attr_translation_key = "prochain_reveil_personne"
    _attr_device_class = SensorDeviceClass.TIMESTAMP
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_icon = "mdi:alarm-check"

    def __init__(self, coordinator, personne: str) -> None:
        super().__init__(coordinator, personne, "prochain_reveil")

    def _p(self) -> dict[str, Any] | None:
        return prochain_reveil(self.coordinator.hass, self.coordinator.store, self.personne)

    @property
    def native_value(self) -> datetime | None:
        p = self._p()
        return p["heure"] if p else None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        p = self._p()
        return {"personne": self.personne, "reveil": p["reveil"] if p else None, "source": p["source"] if p else None,
                "appareils": p["appareils"] if p else []}


class ConcentrationBinarySensor(_Minute, _Personne, BinarySensorEntity):
    PLATEFORME = "binary_sensor"
    _attr_translation_key = "concentration_personne"
    _attr_icon = "mdi:book-open-variant"

    def __init__(self, coordinator, personne: str) -> None:
        super().__init__(coordinator, personne, "concentration")

    def _c(self) -> dict[str, Any] | None:
        return concentration_de(self.coordinator.store, self.personne)

    @property
    def is_on(self) -> bool:
        return self._c() is not None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        c = self._c()
        return {"personne": self.personne, "fin": dt_util.utc_from_timestamp(c["fin"]).isoformat() if c else None,
                "par": c["par"] if c else None, "appareils": c["appareils"] if c else []}


class TempsRestantPersonneSensor(_Minute, _Personne, SensorEntity):
    PLATEFORME = "sensor"
    _attr_translation_key = "temps_restant_personne"
    _attr_device_class = SensorDeviceClass.DURATION
    _attr_native_unit_of_measurement = UnitOfTime.MINUTES
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_icon = "mdi:timer-sand"

    def __init__(self, coordinator, personne: str) -> None:
        super().__init__(coordinator, personne, "temps_restant")

    def _t(self) -> dict[str, Any] | None:
        return temps_restant_personne(self.coordinator.store, self.personne)

    @property
    def native_value(self) -> int | None:
        t = self._t()
        return math.ceil(t["minutes"]) if t else None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        t = self._t()
        return {"personne": self.personne, "raison": t["raison"] if t else None, "appareil": t["appareil"] if t else None,
                "fin": dt_util.utc_from_timestamp(t["fin"]).isoformat() if t and t["fin"] else None}


class TempsRestantPcSensor(_Minute, PcEntity, SensorEntity):
    _attr_translation_key = "temps_restant"
    _attr_device_class = SensorDeviceClass.DURATION
    _attr_native_unit_of_measurement = UnitOfTime.MINUTES
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_icon = "mdi:timer-sand"

    def __init__(self, coordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_temps_restant_{pc_id}"

    def _t(self) -> dict[str, Any] | None:
        return temps_restant(self.coordinator.store, self.pc)

    @property
    def native_value(self) -> int | None:
        t = self._t()
        return math.ceil(t["minutes"]) if t else None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        t = self._t()
        return {"raison": t["raison"] if t else None, "fin": dt_util.utc_from_timestamp(t["fin"]).isoformat() if t and t["fin"] else None}


class EcranBinarySensor(PcEntity, BinarySensorEntity):
    """Allumé quand l'écran de l'appareil est allumé (l'appli le remonte déjà au relevé) ; indisponible quand elle ne le dit pas."""

    _attr_translation_key = "ecran"
    _attr_device_class = BinarySensorDeviceClass.POWER
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_icon = "mdi:television"

    def __init__(self, coordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_ecran_{pc_id}"

    @property
    def available(self) -> bool:
        return super().available and ecran_tele(self.pc) is not None

    @property
    def is_on(self) -> bool | None:
        return ecran_tele(self.pc)


class PartySensor(HubEntity, SensorEntity):
    """L'état de la soirée (Mode Party) : `active` ou `inactive`, gardé au redémarrage de Home Assistant.
    Attributs : écran, lecteur (et leurs noms), depuis quand, par qui, invités ouverts, titre et artiste en cours."""

    _attr_translation_key = "party"
    _attr_device_class = SensorDeviceClass.ENUM
    _attr_options = ["active", "inactive"]
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_icon = "mdi:party-popper"

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_party"

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        from . import party

        self.async_on_remove(async_dispatcher_connect(self.hass, party.SIGNAL_PARTY, self.async_write_ha_state))

    @property
    def native_value(self) -> str:
        from . import party

        return "active" if party.etat(self.coordinator.hass).get("actif") else "inactive"

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        from . import party

        hass = self.coordinator.hass
        st = party.etat(hass)
        noms = {e["id"]: e["nom"] for e in party.ecrans(self.coordinator.store)}
        lecteur = hass.states.get(st.get("lecteur") or "") if st.get("actif") else None
        a = lecteur.attributes if lecteur is not None else {}
        actif = bool(st.get("actif"))
        return {
            "ecran": st.get("ecran") or None, "ecran_nom": noms.get(st.get("ecran") or "") if actif else None,
            "lecteur": st.get("lecteur") or None, "lecteur_nom": a.get("friendly_name") if actif else None,
            "depuis": dt_util.utc_from_timestamp(st["depuis"]).isoformat() if st.get("depuis") else None,
            "par": st.get("par") or None, "invites_ouverts": bool(st.get("invites_ouverts", True)),
            "titre": a.get("media_title") if actif else None, "artiste": a.get("media_artist") if actif else None,
        }


class _BoutonReveil(_Personne, ButtonEntity):
    PLATEFORME = "button"
    SERVICE = ""
    _attr_icon = "mdi:alarm"

    @property
    def available(self) -> bool:
        return super().available and reveil_actionnable(self.coordinator.store, self.personne) is not None

    async def async_press(self) -> None:
        r = reveil_actionnable(self.coordinator.store, self.personne)
        if r is None:
            return
        donnees: dict[str, Any] = {"reveil": r["id"]} if self.SERVICE == "reveil_reporter" else {"reveil": r["id"]}
        await self.hass.services.async_call(DOMAIN, self.SERVICE, donnees, blocking=True, context=self._context)


class ReveilReporterButton(_BoutonReveil):
    SERVICE = "reveil_reporter"
    _attr_translation_key = "reveil_reporter_personne"

    def __init__(self, coordinator, personne: str) -> None:
        super().__init__(coordinator, personne, "reveil_reporter")


class ReveilArreterButton(_BoutonReveil):
    SERVICE = "reveil_arreter"
    _attr_translation_key = "reveil_arreter_personne"

    def __init__(self, coordinator, personne: str) -> None:
        super().__init__(coordinator, personne, "reveil_arreter")


# ----------------------------------------------------------------------------- Services de la Party


def _ecran_resolu(store, valeur: str) -> str:
    """L'identifiant d'un écran de Party, donné par son identifiant ou par son nom."""
    from . import party

    bas = valeur.strip().lower()
    for e in party.ecrans(store):
        if bas in (str(e["id"]).lower(), str(e["nom"]).lower()):
            return str(e["id"])
    return valeur


def enregistrer_services(hass: HomeAssistant, coordinateur) -> None:
    """`party_lancer` (écran, lecteur facultatif) et `party_arreter` : la soirée en automatisation (« dîner : arrêter la soirée »),
    sans passer par un appareil décideur. Réservés aux administrateurs et aux automatisations."""
    import voluptuous as vol
    from homeassistant.helpers import config_validation as cv

    from . import party
    from .entity import service_admin

    if hass.services.has_service(DOMAIN, "party_lancer"):
        return

    async def _lancer(call) -> None:
        coord = coordinateur()
        store = coord.store
        ecran = _ecran_resolu(store, str(call.data["ecran"]))
        lecteur = str(call.data.get("lecteur") or "") or party.lecteur_par_defaut(hass, store, None)
        await party.lancer(hass, store, ecran, lecteur, "automatisation", None)

    async def _arreter(call) -> None:
        await party.arreter(hass, "automatisation", None)

    service_admin(hass, "party_lancer", _lancer, schema=vol.Schema({
        vol.Required("ecran"): cv.string,
        vol.Optional("lecteur"): cv.entity_id,
    }))
    service_admin(hass, "party_arreter", _arreter, schema=vol.Schema({}))


def retirer_services(hass: HomeAssistant) -> None:
    for nom in ("party_lancer", "party_arreter"):
        hass.services.async_remove(DOMAIN, nom)


# ----------------------------------------------------------------------------- Branchement des plateformes


def uids_valides(coordinator, plateforme: str) -> set[str]:
    """Les `unique_id` à garder pour cette plateforme (sinon `prune_stale` les retire)."""
    store = coordinator.store
    personnes = store.personnes_suivies()
    if plateforme == "sensor":
        return ({f"{DOMAIN}_prochain_reveil_{slug(p)}" for p in personnes} | {f"{DOMAIN}_temps_restant_{slug(p)}" for p in personnes}
                | {f"{DOMAIN}_temps_restant_{pc}" for pc in coordinator.pcs} | {f"{DOMAIN}_party"})
    if plateforme == "binary_sensor":
        return {f"{DOMAIN}_concentration_{slug(p)}" for p in personnes} | {f"{DOMAIN}_ecran_{pc}" for pc, f in coordinator.store.pcs.items() if est_android(f)}
    if plateforme == "button":
        return {f"{DOMAIN}_reveil_reporter_{slug(p)}" for p in personnes} | {f"{DOMAIN}_reveil_arreter_{slug(p)}" for p in personnes}
    return set()


def entites(coordinator, plateforme: str) -> list[Any]:
    store = coordinator.store
    personnes = store.personnes_suivies()
    if plateforme == "sensor":
        return ([ProchainReveilSensor(coordinator, p) for p in personnes] + [TempsRestantPersonneSensor(coordinator, p) for p in personnes]
                + [TempsRestantPcSensor(coordinator, pc) for pc in coordinator.pcs] + [PartySensor(coordinator)])
    if plateforme == "binary_sensor":
        return [ConcentrationBinarySensor(coordinator, p) for p in personnes] + [EcranBinarySensor(coordinator, pc) for pc, f in store.pcs.items() if est_android(f)]
    if plateforme == "button":
        return [ReveilReporterButton(coordinator, p) for p in personnes] + [ReveilArreterButton(coordinator, p) for p in personnes]
    return []
