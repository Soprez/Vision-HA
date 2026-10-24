"""Coordinateur : recalcule l'état de chaque PC et réveille les entités."""

from __future__ import annotations

import logging
import time
from datetime import datetime
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator
from homeassistant.util import dt as dt_util

from .const import (
    AGENT_TIMEOUT,
    DOMAIN,
    ENREGISTREMENT_MIN,
    UPDATE_INTERVAL,
)
from .listenoire import ListeNoire
from .notoriete import Notoriete
from .virustotal import VirusTotal
from .planner import cibles_label, days_label, hhmm
from .store import PcStore

_LOGGER = logging.getLogger(__name__)


class PcParentalCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Tient à jour l'état calculé de tous les PC."""

    def __init__(
        self,
        hass: HomeAssistant,
        entry: ConfigEntry,
        store: PcStore,
        noire: ListeNoire,
        notoriete: Notoriete,
        virustotal: VirusTotal,
    ) -> None:
        super().__init__(
            hass,
            _LOGGER,
            name=DOMAIN,
            update_interval=UPDATE_INTERVAL,
            config_entry=entry,
        )
        self.store = store
        self.noire = noire
        self.notoriete = notoriete
        self.virustotal = virustotal
        self._a_sauver = False
        # Les étiquettes que Vision propose, au dernier enregistrement (1.60.0).
        self._proposees: tuple[str, ...] | None = None
        # Les relevés d'observation arrivent toutes les 20 s pour chaque PC.
        # Les écrire aussi souvent userait le disque pour rien : on les
        # regroupe et on n'enregistre qu'à intervalle raisonnable.
        self._observation_sale = False
        self._dernier_enregistrement = time.time()
        # PC sur lequel portent les entités de gestion (création de plage,
        # code d'appairage, suppression). Choisi par le select « PC ciblé ».
        self.cible: str | None = None
        # Plage sélectionnée pour suppression.
        self.plage_cible: str | None = None
        # Page affichée des listes. Elle revient à la première dès qu'on
        # change de filtre : rester page 7 d'une liste qui vient d'en
        # perdre six n'aurait aucun sens.
        self.page: int = 1
        self._signature: str | None = None

    async def _async_update_data(self) -> dict[str, Any]:
        etats: dict[str, Any] = {}
        for pc_id, pc in self.store.pcs.items():
            avant = pc.get("override")
            etat = self.store.evaluate(pc)
            if avant is not None and pc.get("override") is None:
                self._a_sauver = True
            # Un laissez-passer qui arrive à son terme doit se refermer même
            # si le PC est éteint : sinon il attendrait le prochain relevé.
            if self.store.purger_laissez_passer(pc):
                self._a_sauver = True
            etats[pc_id] = etat

        # La liste noire se recharge d'elle-même : une protection qu'il
        # faut penser à rafraîchir n'en est pas une.
        if self.noire.active and self.noire.perimee():
            self.hass.async_create_task(self._async_recharger_noire())

        # Le classement de fréquentation vieillit de la même façon, et se
        # recharge seul. Il ne bloque rien s'il manque : une colonne vide
        # vaut mieux qu'une colonne fausse.
        if self.notoriete.active and self.notoriete.perimee():
            self.hass.async_create_task(self.notoriete.async_rafraichir())

        # VirusTotal avance d'un cran par tour, jamais plus : quatre
        # requêtes par minute, c'est ce que donne le palier gratuit, et se
        # faire couper l'accès pour avoir été pressé perdrait l'information
        # pour de bon.
        if self.virustotal.peut_demander():
            self.hass.async_create_task(self._async_un_verdict())

        maintenant = time.time()
        assez_vieux = (maintenant - self._dernier_enregistrement) >= ENREGISTREMENT_MIN
        if self._a_sauver or (self._observation_sale and assez_vieux):
            self._a_sauver = False
            self._observation_sale = False
            self._dernier_enregistrement = maintenant
            await self.store.async_save()
        # 2.5.12.14.2 : les calculs lourds (ce que chaque agent doit appliquer) se refont ici, par tranches, à l'abri de
        # la boucle ; les capteurs qui les lisent ensuite trouvent le résultat prêt.
        for _pc in list(self.store.pcs.values()):
            try:
                await self.store.async_effectif(_pc)
            except Exception:  # noqa: BLE001 - le calcul se refera à la demande
                pass

        return etats

    async def _async_un_verdict(self) -> None:
        """Soumet un programme, celui qui en a le plus besoin."""
        for nom, empreinte in self.store.a_interroger():
            if self.virustotal.a_revoir(empreinte):
                if await self.virustotal.async_demander(empreinte, nom):
                    await self.async_refresh()
                return

    async def _async_recharger_noire(self) -> None:
        """Recharge la liste noire, puis en purge les listes de la maison.

        Un domaine qui vient d'être signalé dangereux n'a plus rien à faire
        dans le catalogue : il n'est plus une décision à prendre.
        """
        await self.noire.async_rafraichir()
        if self.store.poser_liste_noire(self.noire.domaines):
            self._a_sauver = True

    def marquer_observation(self) -> None:
        """Un relevé est arrivé : à enregistrer, mais sans se presser."""
        self._observation_sale = True

    # ------------------------------------------------------------ Confort

    @property
    def pcs(self) -> dict[str, dict[str, Any]]:
        return self.store.pcs

    def etat(self, pc_id: str) -> dict[str, Any]:
        return (self.data or {}).get(pc_id) or {}

    def pc_name(self, pc_id: str) -> str:
        pc = self.store.get(pc_id)
        return pc["name"] if pc else pc_id

    def rules(self, pc_id: str) -> list[dict[str, Any]]:
        pc = self.store.get(pc_id)
        return list(pc["rules"]) if pc else []

    def agent_online(self, pc_id: str) -> bool:
        pc = self.store.get(pc_id)
        if not pc:
            return False
        vu = float(pc.get("last_seen") or 0)
        return bool(vu) and (time.time() - vu) < AGENT_TIMEOUT.total_seconds()

    def rule_label(self, rule: dict[str, Any]) -> str:
        """Le libellé porte ce qui distingue une plage d'une autre.

        Il sert aussi de clé pour la supprimer : deux plages qui se
        liraient pareil seraient deux plages qu'on ne saurait pas viser. La
        portée en fait donc partie — « Jeux, Vidéo » n'est pas « session ».
        """
        cible = cibles_label(rule)
        return (
            f"{rule['name']} — {cible} — "
            f"{hhmm(rule['start'])}→{hhmm(rule['end'])} "
            f"({days_label(rule['weekdays'])})"
        )

    def rule_by_label(self, pc_id: str, label: str) -> dict[str, Any] | None:
        for rule in self.rules(pc_id):
            if self.rule_label(rule) == label:
                return rule
        return None

    def libelles_plages(self, pc_id: str = "") -> list[str]:
        """Les plages proposées à la suppression.

        Sans PC désigné, on montre celles de **toutes** les machines, sans
        doublon : une plage posée partout n'a qu'un libellé, et se retire
        d'un geste comme elle s'est posée.
        """
        vus: list[str] = []
        cibles = [pc_id] if pc_id else list(self.pcs)
        for machine in cibles:
            for regle in self.rules(machine):
                libelle = self.rule_label(regle)
                if libelle not in vus:
                    vus.append(libelle)
        return sorted(vus, key=str.lower)

    def plages_par_libelle(self, label: str) -> list[tuple[str, int]]:
        """Où se trouve cette plage : (pc_id, id de la règle) pour chacune."""
        trouves: list[tuple[str, int]] = []
        for pc_id in self.pcs:
            for regle in self.rules(pc_id):
                if self.rule_label(regle) == label:
                    trouves.append((pc_id, regle["id"]))
        return trouves

    @staticmethod
    def local_now() -> datetime:
        return dt_util.now().replace(tzinfo=None)

    async def async_ecrire(self, structurel: bool = False) -> None:
        """Enregistre et recalcule.

        « structurel » signifie que la liste des PC ou des plages a changé :
        les entités correspondantes doivent apparaître ou disparaître. Plutôt
        que de les créer à la volée, on recharge l'intégration : c'est
        instantané et l'on est certain que les entités reflètent les données.
        """
        await self.store.async_save()
        # Une étiquette qui devient proposable (rangée sur un premier site,
        # ajoutée à la main) ou qui cesse de l'être change les interrupteurs
        # par appareil : on recharge, comme pour un PC ajouté.
        from . import labels

        proposees = tuple(labels.proposees(self.hass, self.store))
        if self._proposees is not None and proposees != self._proposees:
            structurel = True
        self._proposees = proposees
        if structurel:
            self.hass.config_entries.async_schedule_reload(
                self.config_entry.entry_id
            )
            return
        await self.async_refresh()

    async def async_maj_tableau(self, force: bool = False) -> bool:
        """Reconstruit le tableau de bord à partir des PC et plages actuels.

        2.5.10 (l'auteur, retour 28) : appelé seulement à la demande (bouton « Régénérer le tableau de bord », service
        build_dashboard) ; l'ancien tableau est alors sauvegardé avant d'être remplacé.
        2.5.12 (étude des menus, C1) : sans `force`, plus rien n'est reconstruit (ni attribution, ni type d'appareil,
        ni vocabulaire) : le tableau appartient à l'utilisateur et les cartes lisent l'API."""
        from .dashboard import async_build, signature

        if not force:
            return False
        sig = signature(self)
        ok = await async_build(self.hass, self, sauvegarder=force)
        if ok:
            self._signature = sig
            # 1.86.1 : gardée aussi dans le magasin (le démarrage suivant ne refait pas un tableau à jour).
            if self.store.signature_tableau != sig:
                self.store.signature_tableau = sig
                await self.store.async_save()
        return ok
