"""Capteurs : état du PC, prochain basculement, code d'appairage."""

from __future__ import annotations

import time
from typing import Any

from homeassistant.const import MATCH_ALL, UnitOfSoundPressure
from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorStateClass,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from . import langues, listes
from .const import (
    DOMAIN,
    ORIGINE_JAMAIS,
    MODE_LOCKED,
    PRESENCE_ICONES,
    PRESENCE_LIBELLES,
    PRESENCES,
    STATE_LOCKED,
    STATE_OPEN,
)
from .coordinator import PcParentalCoordinator
from .entity import HubEntity, PcEntity, categorie, nom_traduit, prune_stale, texte


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]

    entites: list[SensorEntity] = []
    for pc_id in coordinator.pcs:
        entites.append(PcStateSensor(coordinator, pc_id))
        entites.append(PcNextChangeSensor(coordinator, pc_id))
        entites.append(PcSessionSensor(coordinator, pc_id))
        entites.append(PcPresenceSensor(coordinator, pc_id))
        entites.append(PcTempsEcranSensor(coordinator, pc_id))
        entites.append(PcPlagesSensor(coordinator, pc_id))
    entites.append(AClasserSensor(coordinator))
    entites.append(DemandesEnAttenteSensor(coordinator))
    entites.append(ProcessusAValiderSensor(coordinator))
    entites.append(MusiqueDerniereCommandeSensor(coordinator))
    entites.append(ExceptionsSensor(coordinator))
    entites.append(ListeNoireSensor(coordinator))
    entites.append(BloquesSensor(coordinator))
    entites.append(RechercheNoireSensor(coordinator))
    for pc_id in coordinator.pcs:
        entites.append(AlertesSensor(coordinator, pc_id))
        entites.append(BlocagesSensor(coordinator, pc_id))
    for personne in coordinator.store.personnes_suivies():
        entites.append(FermeturesSensor(coordinator, personne))
    # Niveau sonore : un capteur par appareil, seulement quand le module est allumé (son.py).
    from . import son as _son

    son_actif = _son.actif_module(coordinator.store)
    if son_actif:
        entites.extend(NiveauSonoreSensor(coordinator, pc_id) for pc_id in coordinator.pcs)
        entites.extend(MicrosActifsSensor(coordinator, pc_id) for pc_id in coordinator.pcs)
    valides = {
        f"{DOMAIN}_{cle}_{pc}"
        for pc in coordinator.pcs
        for cle in (
            "etat",
            "suivant",
            "session",
            "presence",
            "temps_ecran",
            "plages",
            "alertes",
            "blocages",
        )
    } | ({f"{DOMAIN}_{cle}_{pc}" for pc in coordinator.pcs for cle in ("son", "son_micros")} if son_actif else set()) | {
        f"{DOMAIN}_fermetures_{p.split('.')[-1]}"
        for p in coordinator.store.personnes_suivies()
    } | {
        f"{DOMAIN}_a_classer",
        f"{DOMAIN}_demandes_en_attente",
        f"{DOMAIN}_processus_a_valider",
        f"{DOMAIN}_exceptions",
        f"{DOMAIN}_liste_noire_etat",
        f"{DOMAIN}_bloques",
        f"{DOMAIN}_recherche_noire",
    }
    # Réveil (lot 1) : quatre capteurs par réveil, ajoutés sans recharger (reveil_entites.brancher).
    from . import reveil_entites

    valides |= reveil_entites.valides(coordinator.store, "sensor")
    prune_stale(hass, coordinator, "sensor", f"{DOMAIN}_", valides)

    async_add_entities(entites)
    reveil_entites.brancher(hass, entry, coordinator, "sensor", async_add_entities)


class PcStateSensor(PcEntity, SensorEntity):
    """Ouvert ou fermé, et pourquoi."""
    # 2.5.12 (étude des menus, L.3) : attributs hors de l'historique (la base ne grossit plus à chaque relevé).
    _unrecorded_attributes = frozenset({MATCH_ALL})

    _attr_translation_key = "etat"
    _attr_icon = "mdi:monitor-shimmer"
    _attr_device_class = SensorDeviceClass.ENUM
    _attr_options = [STATE_OPEN, STATE_LOCKED]

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_etat_{pc_id}"

    @property
    def native_value(self) -> str:
        return STATE_LOCKED if self.etat.get("locked") else STATE_OPEN

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        etat = self.etat
        override = etat.get("override") or {}
        fin = float(override.get("until") or 0)
        return {
            "source": etat.get("source"),
            "planning": (
                "fermé" if etat.get("planning") == MODE_LOCKED else "ouvert"  # i18n-ok : valeur lue par le tableau de bord
            ),
            "fin_derogation": (
                dt_util.utc_from_timestamp(fin).isoformat() if fin else None
            ),
            "agent_en_ligne": self.coordinator.agent_online(self.pc_id),
            # Les catégories fermées en plus par une règle (planning, automatisation) :
            # l'interrupteur de l'étiquette est à « non », et pourtant c'est coupé.
            "categories_coupees": self._categories_coupees(),
            # Ce que l'agent applique vraiment, une fois les étiquettes et les exceptions résolues : des
            # nombres seulement. 2.5.12 : la liste se lit à la demande (listes.py), quand on ouvre la carte.
            **self._effectif(),
        }

    def _categories_coupees(self) -> list[str]:
        # 2.5.12 : gardé jusqu'à la minute suivante (et oublié à chaque écriture), comme `effectif` : la lecture des attributs revient
        # à chaque mise à jour du coordinateur, toutes les 15 s et à chaque relevé.
        cle = ("categories_coupees", self.pc_id, int(time.time() // 60))
        return list(self.coordinator.store._memo(cle, 60.0, self._categories_coupees_calc))

    def _categories_coupees_calc(self) -> list[str]:
        magasin = self.coordinator.store
        hass = self.coordinator.hass
        pc = self.pc
        noms: set[str] = set()
        for genre in ("apps", "sites"):
            for ets in (magasin.etiquettes.get(genre) or {}).values():
                noms.update(str(e) for e in (ets or []))
        # Une catégorie fermée par une automatisation, même si elle ne range encore rien.
        noms.update(str(f.get("etiquette") or "") for f in magasin.fermetures_de(str(pc.get("person") or "")))
        noms.discard("")
        sortie = []
        for nom in sorted(noms, key=str.lower):
            if nom.lower() in ("parents", "enfants"):
                continue
            try:
                fermeture = magasin.fermeture_de(pc, [nom])
                if fermeture is not None:
                    sortie.append(texte(
                        hass, "categorie_fermee_raison", categorie=categorie(hass, nom),
                        raison=fermeture.get("raison") or texte(hass, "fermee_par_automatisation"),
                    ))
                    continue
                regle = magasin.plage_ferme(pc, nom)
                if regle:
                    sortie.append(texte(
                        hass, "categorie_fermee_planning", categorie=categorie(hass, nom),
                        plage=regle.get("name") or texte(hass, "plage"),
                    ))
            except Exception:  # noqa: BLE001
                continue
        return sortie

    def _effectif(self) -> dict[str, Any]:
        """Ce qui est bloqué **sur cette machine seulement**, en nombres, et l'empreinte de la liste.

        2.5.12 : plus les listes (`bloques_sites`, `bloques_logiciels`, `exceptions`, `lignes_bloques`). Elles
        pesaient 30 Ko par appareil et leur calcul (le catalogue entier, nom par nom) prenait 0,5 à 0,7 s dans la
        boucle d'événements, à chaque mise à jour. La carte « Ce qui est réellement bloqué » les lit à la demande
        (`GET /api/pc_parental/listes?liste=bloques_appareil&pc=…`) et ne les relit que si `revision` change.
        """
        magasin = self.coordinator.store
        applique = magasin.effectif(self.pc)
        return {
            "nb_logiciels_bloques": len(applique.get("apps") or []),
            "nb_sites_bloques": len(applique.get("sites") or []),
            "nb_exceptions": len(applique.get("exceptions") or []),
            "revision": listes.revision_appareil(magasin, self.pc),
        }

    def _raison(self, genre: str, nom: str) -> str:
        """Pourquoi celui-là est bloqué. La règle vit dans le magasin."""
        magasin = self.coordinator.store
        raison = magasin.raison(self.pc, genre, nom)
        if raison.startswith("nommément") and magasin.origine(genre, nom) == ORIGINE_JAMAIS:  # i18n-ok : raison du magasin
            # Un nom bloqué que personne n'a jamais vu tourner est presque
            # toujours une faute de frappe — ou une étiquette écrite avant
            # d'exister. Le dire évite de chercher longtemps.
            return texte(self.coordinator.hass, "raison_jamais_constate", raison=raison)
        return raison

class PcNextChangeSensor(PcEntity, SensorEntity):
    """Heure du prochain basculement du planning."""

    _attr_translation_key = "prochain_changement"
    _attr_icon = "mdi:clock-alert-outline"
    _attr_device_class = SensorDeviceClass.TIMESTAMP

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_suivant_{pc_id}"

    @property
    def native_value(self):
        suivant = self.etat.get("next_change")
        if suivant is None:
            return None
        return suivant.replace(tzinfo=dt_util.DEFAULT_TIME_ZONE)


class PcSessionSensor(PcEntity, SensorEntity):
    """Compte Windows actuellement connecté, tel que rapporté par l'agent."""

    _attr_translation_key = "session_ouverte"
    _attr_icon = "mdi:account"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_session_{pc_id}"

    @property
    def native_value(self) -> str | None:
        if not self.coordinator.agent_online(self.pc_id):
            return None
        return (self.pc.get("agent") or {}).get("user") or None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        agent = self.pc.get("agent") or {}
        vu = float(self.pc.get("last_seen") or 0)
        return {
            "version_agent": agent.get("version") or None,
            "machine": agent.get("host") or None,
            "verrouille_sur_le_pc": agent.get("locked"),
            "derniere_nouvelle": (
                dt_util.utc_from_timestamp(vu).isoformat() if vu else None
            ),
            # 1.93.2 : ce que l'agent coûte à la machine (processeur sur toute la machine, mémoire, fichier hosts).
            "agent_processeur_pct": (agent.get("charge") or {}).get("cpu_pct"),
            "agent_memoire_mo": (agent.get("charge") or {}).get("mem_mo"),
            "agent_hosts_ms": (agent.get("charge") or {}).get("hosts_ms"),
        }


class PcPresenceSensor(PcEntity, SensorEntity):
    """Où en est la machine : utilisée, inactive, verrouillée, éteinte.

    À la façon d'un statut de messagerie : d'un coup d'œil on sait si
    quelqu'un est réellement devant l'écran.
    """

    _attr_translation_key = "presence"
    _attr_device_class = SensorDeviceClass.ENUM
    _attr_options = PRESENCES

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_presence_{pc_id}"

    def _presence(self) -> str:
        return self.coordinator.store.presence(
            self.pc, self.coordinator.agent_online(self.pc_id)
        )

    @property
    def native_value(self) -> str:
        return self._presence()

    @property
    def icon(self) -> str:
        return PRESENCE_ICONES.get(self._presence(), "mdi:monitor")

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        agent = self.pc.get("agent") or {}
        inactif = agent.get("idle_seconds")
        return {
            "explication": (
                texte(self.coordinator.hass, f"presence_{self._presence()}")
                if self._presence() in PRESENCE_LIBELLES else None
            ),
            "inactif_depuis_minutes": (
                round(float(inactif) / 60) if inactif is not None else None
            ),
            "session": agent.get("user") or None,
        }


def _minutes(secondes: float) -> int:
    return int(round(float(secondes) / 60))


class PcTempsEcranSensor(PcEntity, SensorEntity):
    """Temps passé devant ce PC aujourd'hui, en minutes.

    On compte le temps **actif** : session ouverte et quelqu'un qui se sert
    du clavier ou de la souris. Le temps simplement allumé est donné à part,
    en attribut — un PC laissé ouvert toute la nuit ne doit pas passer pour
    du temps d'écran.

    Le compteur repart de zéro chaque jour ; `TOTAL_INCREASING` dit à Home
    Assistant que cette remise à zéro est normale et non une panne.
    """

    _attr_translation_key = "temps_d_ecran"
    _attr_icon = "mdi:timer-outline"
    _attr_device_class = SensorDeviceClass.DURATION
    _attr_native_unit_of_measurement = "min"
    _attr_state_class = SensorStateClass.TOTAL_INCREASING

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_temps_ecran_{pc_id}"

    @property
    def native_value(self) -> int:
        return _minutes(self.coordinator.store.usage_du_jour(self.pc)["actif"])

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        magasin = self.coordinator.store
        aujourdhui = magasin.usage_du_jour(self.pc)
        hier = magasin.usage_du_jour(self.pc, 1)
        semaine = magasin.usage_semaine(self.pc)
        jours = {
            jour: _minutes((valeurs or {}).get("actif", 0))
            for jour, valeurs in sorted(self.pc.get("usage", {}).items())
        }
        return {
            "allume_aujourd_hui": _minutes(aujourdhui["ouvert"]),
            "actif_hier": _minutes(hier["actif"]),
            "actif_7_jours": _minutes(semaine["actif"]),
            "moyenne_7_jours": _minutes(semaine["actif"] / 7),
            "logiciel_au_premier_plan": (self.pc.get("agent") or {}).get("focus")
            or "",
            "par_jour": jours,
        }


class MusiqueDerniereCommandeSensor(HubEntity, SensorEntity):
    """2.2.11 (retour d'essai) : la dernière commande Musique (qui, quoi, d'où) ; tout le journal est dans les options."""

    _attr_translation_key = "musique_derniere_commande"
    _attr_icon = "mdi:playlist-music"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_musique_derniere_commande"

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        from homeassistant.helpers.dispatcher import async_dispatcher_connect

        from . import journal_musique
        self.async_on_remove(async_dispatcher_connect(self.hass, journal_musique.SIGNAL, self.async_write_ha_state))

    @property
    def native_value(self) -> str | None:
        from . import journal_musique
        d = journal_musique.derniere(self.hass)
        return journal_musique.phrase(d, langues.langue_hass(self.hass))[:250] if d else None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        from . import journal_musique
        d = journal_musique.derniere(self.hass) or {}
        return {k: v for k, v in d.items() if k in ("appareil", "personne", "profil", "action", "uri", "type", "mode", "nb", "paquet", "resultat")}


class AClasserSensor(HubEntity, SensorEntity):
    """Combien d'éléments attendent encore une décision sur le PC concerné.

    C'est la mesure du travail restant. Quand il tombe à zéro, tout ce que
    la maison a utilisé a été tranché — et le chiffre remonte tout seul dès
    qu'un nouveau logiciel ou un nouveau site apparaît.
    """
    # 2.5.12 (étude des menus, L.3) : attributs hors de l'historique (la base ne grossit plus à chaque relevé).
    _unrecorded_attributes = frozenset({MATCH_ALL})

    _attr_translation_key = "a_classer"
    _attr_icon = "mdi:tray-full"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_elements")

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_a_classer"

    @property
    def native_value(self) -> int:
        pc = self.coordinator.store.get(self.coordinator.cible or "")
        return self.coordinator.store.a_classer(pc)

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "pc": self.coordinator.pc_name(self.coordinator.cible or "") or "—"
        }


class ExceptionsSensor(HubEntity, SensorEntity):
    """Les portes ouvertes dans le mur du PC concerné.

    Deux sortes, et la différence compte : l'adresse épargnée reste ouverte
    jusqu'à ce qu'on la referme, le laissez-passer se referme tout seul.
    Les voir ensemble évite la dérive silencieuse — une exception posée un
    soir de devoirs et jamais rouverte au grand jour.
    """
    # 2.5.12 (étude des menus, L.3) : attributs hors de l'historique (la base ne grossit plus à chaque relevé).
    _unrecorded_attributes = frozenset({MATCH_ALL})

    _attr_translation_key = "exceptions"
    _attr_icon = "mdi:door-open"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_exceptions")

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_exceptions"

    def _pc(self) -> dict[str, Any] | None:
        return self.coordinator.store.get(self.coordinator.cible or "")

    @property
    def native_value(self) -> int:
        pc = self._pc()
        if pc is None:
            return 0
        magasin = self.coordinator.store
        return len(magasin.exceptions(pc)) + len(magasin.laissez_passer_actifs(pc))

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        pc = self._pc()
        hass = self.coordinator.hass
        nom_pc = self.coordinator.pc_name(self.coordinator.cible or "") or texte(hass, "appareil")
        lignes: list[str] = []
        if pc is not None:
            magasin = self.coordinator.store
            for adresse in magasin.exceptions(pc):
                lignes.append(texte(hass, "exception_adresse", adresse=adresse))
            for domaine, fin in magasin.laissez_passer_actifs(pc):
                heure = dt_util.as_local(
                    dt_util.utc_from_timestamp(fin)
                ).strftime("%H:%M")
                restant = max(0, int((fin - dt_util.utcnow().timestamp()) / 60))
                lignes.append(texte(
                    hass, "exception_laissez_passer", domaine=domaine, heure=heure, restant=restant
                ))
        return {
            "pc": nom_pc,
            "entete": texte(hass, "entete_exceptions"),
            "lignes": lignes,
        }


class ListeNoireSensor(HubEntity, SensorEntity):
    """Combien de domaines dangereux Vision connaît, et depuis quand.

    Deux nombres, jamais l'un pour l'autre : ce que la liste contient, et ce
    qui tient dans le fichier hosts. Le reste est bloqué par le résolveur
    filtrant — le dire évite de croire à une protection qui n'existe pas.
    """
    # 2.5.12 (étude des menus, L.3) : attributs hors de l'historique (la base ne grossit plus à chaque relevé).
    _unrecorded_attributes = frozenset({MATCH_ALL})

    _attr_translation_key = "liste_noire"
    _attr_icon = "mdi:shield-alert-outline"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_domaines")

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_liste_noire_etat"

    @property
    def native_value(self) -> int:
        return len(self.coordinator.noire.domaines)

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        from .const import LISTE_NOIRE_SOURCES, MAX_HOSTS_LISTE_NOIRE

        noire = self.coordinator.noire
        total = len(noire.domaines)
        lignes = []
        for cle, source in LISTE_NOIRE_SOURCES.items():
            lignes.append(
                f"| **{source['nom']}** | {source['quoi']} "
                f"| {noire.par_source.get(cle, 0)} |"
            )
        return {
            "active": noire.active,
            "dans_le_fichier_hosts": min(total, MAX_HOSTS_LISTE_NOIRE),
            "bloques_par_le_resolveur": max(0, total - MAX_HOSTS_LISTE_NOIRE),
            "mise_a_jour": (
                dt_util.utc_from_timestamp(noire.maj).isoformat()
                if noire.maj
                else None
            ),
            "probleme": noire.erreur or None,
            "entete": texte(self.coordinator.hass, "entete_liste_noire"),
            "lignes": lignes,
        }


class AlertesSensor(PcEntity, SensorEntity):
    """Tentatives vers un domaine dangereux, depuis cette machine.

    Une liste noire qui travaille en silence n'apprend rien à personne :
    savoir *qu'on a essayé*, et quand, vaut souvent plus que le blocage
    lui-même.
    """
    # 2.5.12 (étude des menus, L.3) : attributs hors de l'historique (la base ne grossit plus à chaque relevé).
    _unrecorded_attributes = frozenset({MATCH_ALL})

    _attr_translation_key = "sites_dangereux_bloques"
    _attr_icon = "mdi:shield-alert"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_tentatives")

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_alertes_{pc_id}"

    @property
    def native_value(self) -> int:
        return sum(
            int(a.get("fois") or 1)
            for a in self.coordinator.store.alertes(self.pc)
        )

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        hass = self.coordinator.hass
        lignes = []
        for alerte in self.coordinator.store.alertes(self.pc)[:20]:
            quand = float(alerte.get("quand") or 0)
            moment = (
                dt_util.as_local(dt_util.utc_from_timestamp(quand)).strftime(
                    texte(hass, "format_jour_heure")
                )
                if quand
                else "—"
            )
            fois = int(alerte.get("fois") or 1)
            repetition = f" ×{fois}" if fois > 1 else ""
            lignes.append(
                f"| **{alerte.get('domaine')}** | {alerte.get('source') or '—'} "
                f"| {moment}{repetition} |"
            )
        return {
            "entete": texte(hass, "entete_alertes"),
            "lignes": lignes,
            "domaines": [
                a.get("domaine") for a in self.coordinator.store.alertes(self.pc)
            ],
        }


class BloquesSensor(HubEntity, SensorEntity):
    """Ce qui est bloqué sur le PC concerné, et d'où ça vient.

    Les listes de travail ne montrent que ce qui reste à trancher — c'est
    leur raison d'être, et c'est ce qui les rend vidables. Mais relire ses
    propres décisions est un autre geste : il ne doit demander ni de changer
    un filtre, ni de chercher un nom.
    """
    # 2.5.12 (étude des menus, L.3) : attributs hors de l'historique (la base ne grossit plus à chaque relevé).
    _unrecorded_attributes = frozenset({MATCH_ALL})

    _attr_translation_key = "bloques_sur_cet_appareil"
    _attr_icon = "mdi:cancel"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_elements")

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_bloques"

    def _pc(self) -> dict[str, Any] | None:
        return self.coordinator.store.get(self.coordinator.cible or "")

    @property
    def native_value(self) -> int:
        pc = self._pc()
        return len(self.coordinator.store.bloques(pc)) if pc else 0

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        pc = self._pc()
        nom_pc = self.coordinator.pc_name(self.coordinator.cible or "") or texte(self.coordinator.hass, "appareil")
        lignes = []
        if pc is not None:
            for entree in self.coordinator.store.bloques(pc):
                lignes.append(
                    f"| {entree['genre']} | **{entree['nom']}** "
                    f"| {entree['origine']} | {entree['raison']} |"
                )
        return {
            "pc": nom_pc,
            "entete": texte(self.coordinator.hass, "entete_bloques"),
            "lignes": lignes,
        }


class RechercheNoireSensor(HubEntity, SensorEntity):
    """Ce que contient la liste noire, pour de vrai.

    Cinquante mille lignes ne se lisent pas : elles se demandent. Sans
    recherche on montre un extrait — de quoi voir que la liste existe et à
    quoi elle ressemble ; avec un morceau de nom, tout ce qui correspond.
    """
    # 2.5.12 (étude des menus, L.3) : attributs hors de l'historique (la base ne grossit plus à chaque relevé).
    _unrecorded_attributes = frozenset({MATCH_ALL})

    _attr_translation_key = "dans_la_liste_noire"
    _attr_icon = "mdi:shield-search"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_domaines")

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_recherche_noire"

    def _terme(self) -> str:
        from homeassistant.helpers import entity_registry as er

        registre = er.async_get(self.coordinator.hass)
        eid = registre.async_get_entity_id(
            "text", DOMAIN, f"{DOMAIN}_saisie_recherche"
        )
        if not eid:
            return ""
        etat = self.coordinator.hass.states.get(eid)
        if etat is None or etat.state in ("unknown", "unavailable"):
            return ""
        return etat.state.strip()

    @property
    def native_value(self) -> int:
        return self.coordinator.noire.chercher(self._terme())[1]

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        terme = self._terme()
        trouves, total = self.coordinator.noire.chercher(terme)
        montres = len(trouves)
        return {
            "recherche": terme or None,
            "montres": montres,
            "total": total,
            "tronque": total > montres,
            "entete": (
                texte(self.coordinator.hass, "entete_recherche", terme=terme)
                if terme else texte(self.coordinator.hass, "entete_extrait")
            ),
            "lignes": [f"| `{d}` |" for d in trouves],
        }


class BlocagesSensor(PcEntity, SensorEntity):
    """Combien de sites bloqués cette machine a essayé d'atteindre aujourd'hui.

    Une seule ligne par PC, un point par jour : c'est la forme qui parle.
    Trois tentatives lundi et trois vendredi ne racontent pas la même chose
    selon qu'on montre ou non les jours sans rien — la série comble donc les
    trous par des zéros.

    L'attribut `courbe` porte un mois entier, indépendant de l'historique de
    Home Assistant : celui-ci est purgé au bout de quelques jours, et une
    courbe qui s'efface toute seule ne sert à rien.
    """

    _attr_translation_key = "sites_bloques_aujourd_hui"
    _attr_icon = "mdi:chart-line"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_tentatives")
    _attr_state_class = SensorStateClass.MEASUREMENT

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_blocages_{pc_id}"

    @property
    def native_value(self) -> int:
        serie = self.coordinator.store.serie_blocages(self.pc)
        return serie[-1][1] if serie else 0

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        from .const import SPARK

        serie = self.coordinator.store.serie_blocages(self.pc)
        valeurs = [n for _, n in serie]
        haut = max(valeurs) if valeurs else 0
        if haut:
            barres = "".join(
                SPARK[min(len(SPARK) - 1, round(n * (len(SPARK) - 1) / haut))]
                for n in valeurs
            )
        else:
            barres = SPARK[0] * len(valeurs)
        return {
            "courbe": barres,
            "par_jour": {jour: n for jour, n in serie},
            "total_30_jours": sum(valeurs),
            "jour_le_plus_charge": haut,
        }


class PcPlagesSensor(PcEntity, SensorEntity):
    """Toutes les plages du PC, lues par la carte planning."""

    _attr_translation_key = "plages"
    _attr_icon = "mdi:calendar-clock"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_plages")

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_plages_{pc_id}"

    @property
    def native_value(self) -> int:
        return len(self.coordinator.rules(self.pc_id))

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        pc = self.pc
        plages = []
        for rule in self.coordinator.rules(self.pc_id):
            plages.append(
                {
                    "id": rule["id"],
                    "nom": rule["name"],
                    "debut": int(rule["start"]) % 86400,
                    "fin": int(rule["end"]) % 86400,
                    "jours": list(rule["weekdays"]),
                    "active": bool(rule["enabled"]),
                    "portee": rule["portee"],
                    "etiquettes": list(rule["etiquettes"]),
                    "elements": [dict(e) for e in rule.get("elements") or []],
                    "sauf": list(rule.get("sauf") or []),
                    "couleur": self.coordinator.store.couleur_plage(
                        pc, rule["id"]
                    ),
                    "libelle": self.coordinator.rule_label(rule),
                }
            )
        # Le vocabulaire voyage avec les plages ; le catalogue, non — il
        # se demande à `/api/pc_parental/catalogue`. Plusieurs centaines de
        # noms recopiés dans les attributs de quatre capteurs finiraient
        # écrits en base à chaque changement, pour rien.
        # 2.5.12.11 : le seuil sonore de chaque plage, seulement module allumé et mesure allumée sur cet appareil.
        from . import son as _son

        son_bloc = None
        if _son.visible(self.coordinator.store, pc):
            r = _son.reglages(pc)
            son_bloc = {"mode": r["seuil_mode"], "general_dbfs": r["seuil_db"] - 100}
            for p in plages:
                v = r["seuils_plages"].get(str(p["id"]))
                p["seuil_sonore_dbfs"] = None if v is None else v - 100
        attributs = {
            "pc": self.coordinator.pc_name(self.pc_id),
            # Les appareils de la personne : une plage peut en épargner certains.
            "appareils": [
                {"id": a["id"], "nom": str(a.get("name") or a["id"])}
                for a in self.coordinator.store._siens(pc)
            ],
            "vocabulaire": self.coordinator.store.toutes_etiquettes(),
            "plages": plages,
        }
        if son_bloc is not None:
            attributs["son"] = son_bloc
        return attributs



class FermeturesSensor(HubEntity, SensorEntity):
    """Ce que les automatisations ferment en ce moment pour une personne, et pourquoi.

    État : le nombre de catégories fermées. Attributs : chaque fermeture, avec sa
    catégorie, sa raison (ce que la personne lit), sa source et depuis quand. Une
    automatisation peut s'en servir comme condition ; un tableau, pour l'afficher.
    """

    _attr_icon = "mdi:lock-clock"
    _attr_translation_key = "fermetures_automatisation"

    @property
    def native_unit_of_measurement(self) -> str:
        return texte(self.coordinator.hass, "unite_categories")

    def __init__(self, coordinator: PcParentalCoordinator, personne: str) -> None:
        super().__init__(coordinator)
        self.personne = personne
        self._attr_unique_id = f"{DOMAIN}_fermetures_{personne.split('.')[-1]}"

    @property
    def name(self) -> str:
        etat = self.coordinator.hass.states.get(self.personne)
        prenom = etat.name if etat else self.personne.split(".")[-1]
        return nom_traduit(self.coordinator.hass, "sensor", "fermetures_automatisation", prenom=prenom)

    def _fermetures(self) -> list[dict[str, Any]]:
        return self.coordinator.store.fermetures_de(self.personne)

    @property
    def native_value(self) -> int:
        return len({str(f.get("etiquette") or "").lower() for f in self._fermetures()})

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "personne": self.personne,
            "categories": sorted({str(f.get("etiquette") or "") for f in self._fermetures()}, key=str.lower),
            "fermetures": [
                {
                    "categorie": f.get("etiquette"),
                    "raison": f.get("raison") or "",
                    "source": f.get("source") or "",
                    "depuis": dt_util.utc_from_timestamp(f["depuis"]).isoformat() if f.get("depuis") else None,
                }
                for f in self._fermetures()
            ],
        }


class NiveauSonoreSensor(PcEntity, SensorEntity):
    """Niveau sonore de la pièce en dBFS (0 = pleine échelle du micro), mesuré par le micro de l'appareil ; jamais d'audio.

    Indisponible quand la mesure est éteinte sur l'appareil ou sans relevé depuis cinq minutes. Attributs : le maximum
    de la dernière minute, le pic des 10 dernières secondes, l'instant, le seuil du moment (jour ou nuit), les alertes
    comptées, celles d'aujourd'hui et la gradation des messages (2.5.12.3).
    """

    _attr_translation_key = "niveau_sonore"
    _attr_icon = "mdi:volume-vibrate"
    # 2.5.12.11 : un vrai capteur pour l'historique et les statistiques longues natives de Home Assistant (moyenne,
    # minimum et maximum horaires, gardés au-delà de la purge du recorder). Classe « pression acoustique » et unité dB
    # (la seule que cette classe accepte, et celle des statistiques déjà enregistrées) ; la valeur reste en dBFS
    # (0 = pleine échelle du micro), comme le dit l'attribut « echelle ».
    _attr_device_class = SensorDeviceClass.SOUND_PRESSURE
    _attr_native_unit_of_measurement = UnitOfSoundPressure.DECIBEL
    _attr_state_class = SensorStateClass.MEASUREMENT
    # L'état est enregistré à chaque relevé ; les attributs lourds (listes) ne le sont pas, la base ne gonfle pas.
    _unrecorded_attributes = frozenset({"journal", "par_micro", "seuils_plages", "micros_pris_en_compte", "micros_exclus"})

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_son_{pc_id}"

    def _etat(self) -> dict[str, Any]:
        from . import son

        return son.etat(self.pc) if self.pc else {}

    @property
    def available(self) -> bool:
        import time

        from . import son

        e = self._etat()
        # 2.5.12.3 (retour d'essai) : aucun micro allumé = indisponible, jamais 0 (le capteur « Micros actifs » dit pourquoi).
        return bool(super().available and self.pc and son.mesure_active(self.coordinator.store, self.pc)
                    and time.time() - float(e.get("maj") or 0) < 300
                    and e.get("micros_actifs") != 0 and e.get("niveau") is not None)

    @property
    def native_value(self):
        # 2.5.12.3 (retour d'essai) : le capteur parle en dBFS (0 = pleine échelle du micro) ; l'appareil mesure en dB relatifs
        # (0 à 100 = dBFS + 100), gardés dans l'attribut « niveau_relatif ».
        from . import son

        return son.dbfs(self._etat().get("niveau"))

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        from . import son

        e = self._etat()
        pc = self.pc or {}
        r = son.reglages(pc)
        store = self.coordinator.store
        nuit = son.est_nuit(store, pc) if self.pc else False
        seuil, source = son.seuil_detail(store, pc) if self.pc else (r["seuil_db"], {"type": "general"})
        plancher = son.plancher_effectif_db(pc, e) if self.pc else None
        mesure = son.plancher_mesure_db(e)
        return {
            "echelle": "dBFS",
            "maximum": son.dbfs(e.get("maximum")), "pic_10s": son.dbfs(e.get("pic")), "instant": son.dbfs(e.get("instant")),
            "niveau_relatif": e.get("niveau"), "micros": e.get("micros"),
            "micros_actifs": e.get("micros_actifs"), "micros_pris_en_compte": list(e.get("micros_liste") or []),
            "par_micro": son.par_micro(e),
            "reagir": r["reagir"], "micros_exclus": r["micros_exclus"], "journal": son.journal_lisible(e),
            # 2.5.12.10 : seuil général ou par plage ; le seuil du moment et d'où il vient ; le plancher de la pièce.
            "seuil": seuil - 100, "seuil_mode": r["seuil_mode"], "seuil_general": r["seuil_db"] - 100,
            "seuil_source": source.get("nom") or source["type"], "seuils_plages": son.seuils_plages_lisibles(pc),
            "plancher_dbfs": None if plancher is None else round(plancher - 100, 1),
            "plancher_mesure_dbfs": None if mesure is None else round(mesure - 100, 1),
            "plancher_manuel_dbfs": r["plancher_manuel_dbfs"], "nuit": nuit,
            "alertes": e.get("alertes", 0), "alertes_avant_coupure": r["alertes_avant_coupure"],
            "aujourd_hui": e.get("aujourd_hui", 0), "gradation": e.get("niveau_alerte", 0),
        }


class MicrosActifsSensor(PcEntity, SensorEntity):
    """2.5.12.3 (retour d'essai) : les micros pris en compte dans le niveau sonore (allumés, ni muets, ni silencieux, ni figés).

    Reste disponible quand « Niveau sonore » ne l'est plus faute de micro : 0 et la liste vide disent pourquoi.
    Indisponible quand la mesure est éteinte, sans relevé depuis cinq minutes, ou si l'appareil ne le dit pas (l'appli).
    """

    _attr_translation_key = "micros_actifs"
    _attr_icon = "mdi:microphone"
    _attr_state_class = SensorStateClass.MEASUREMENT

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_son_micros_{pc_id}"

    def _etat(self) -> dict[str, Any]:
        from . import son

        return son.etat(self.pc) if self.pc else {}

    @property
    def available(self) -> bool:
        import time

        from . import son

        e = self._etat()
        return bool(super().available and self.pc and son.mesure_active(self.coordinator.store, self.pc)
                    and time.time() - float(e.get("maj") or 0) < 300 and e.get("micros_actifs") is not None)

    @property
    def native_value(self) -> int | None:
        return self._etat().get("micros_actifs")

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        from . import son

        e = self._etat()
        return {"liste": list(e.get("micros_liste") or []), "micros_ecoutes": e.get("micros"), "par_micro": son.par_micro(e)}


class ProcessusAValiderSensor(HubEntity, SensorEntity):
    """Les logiciels et processus à valider (carte Processus, « À décider ») : le nombre de propositions, avec les compteurs
    en attributs. 2.5.12.14.2 : une vraie entité (dans le registre, avec son appareil), plus un état posé à la main ;
    elle se met à jour au signal de la gestion des processus."""

    _unrecorded_attributes = frozenset({MATCH_ALL})
    _attr_translation_key = "processus_a_valider"
    _attr_icon = "mdi:cog-transfer-outline"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_processus_a_valider"
        # Le même entity_id dans toutes les langues : les cartes et « À décider » le lisent (processus.CAPTEUR).
        from .processus import CAPTEUR

        self.entity_id = CAPTEUR

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        from homeassistant.helpers.dispatcher import async_dispatcher_connect

        from .processus import SIGNAL_MAJ

        self.async_on_remove(async_dispatcher_connect(self.hass, SIGNAL_MAJ, self.async_write_ha_state))

    def _etat(self) -> tuple[int, dict[str, Any]]:
        gestion = getattr(self.coordinator, "processus", None)
        return gestion.etat_capteur() if gestion is not None else (0, {"a_classer": 0, "decides": 0, "desinstallations": 0})

    @property
    def native_value(self) -> int:
        return self._etat()[0]

    @property
    def native_unit_of_measurement(self) -> str:
        from . import textes

        return textes.t("proc.unite", textes.ha(self.hass))

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return self._etat()[1]


class DemandesEnAttenteSensor(HubEntity, SensorEntity):
    """Les demandes d'accès ouvertes (SPEC-1.94, §3 bis, point 5) : leur nombre, et la liste en attribut avec, pour
    chacune, les appareils Décideurs qui l'ont reçue et si le secours est parti (demandes_livraison)."""

    _unrecorded_attributes = frozenset({MATCH_ALL})
    _attr_translation_key = "demandes_en_attente"
    _attr_icon = "mdi:hand-back-right-outline"
    _attr_state_class = SensorStateClass.MEASUREMENT

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_demandes_en_attente"

    @property
    def native_value(self) -> int:
        from . import demandes_livraison

        return len(demandes_livraison.ouvertes(self.coordinator.store))

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        from . import demandes_livraison

        return {"demandes": demandes_livraison.pour_capteur(self.coordinator.hass, self.coordinator.store)}
