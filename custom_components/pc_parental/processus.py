"""Processus, démarrage, tâches et services : le côté Home Assistant.

Le catalogue lui-même vit dans `processus_catalogue` (sans Home Assistant,
donc testable seul). Ici : la persistance, les trois services que la tâche
planifiée appelle par le MCP, la vue qui nourrit la carte, et un capteur
qui dit combien de propositions attendent.

Contrat pour le classeur (la tâche planifiée) :

1. `pc_parental.processus_lister` (filtre « a_classer ») rend les éléments
   jamais examinés, avec ce qu'on sait d'eux : éditeur, signature,
   emplacement, ligne de commande, mémoire, PC où ils tournent ;
2. `pc_parental.processus_proposer` y dépose un verdict — garder, retirer
   du démarrage, nuisible — avec sa raison et sa confiance ;
3. un adulte valide dans la carte (ou `processus_decider`). Rien ne change
   sur un PC avant cette étape.
"""

from __future__ import annotations

import logging
import time
from typing import Any

import voluptuous as vol
from aiohttp import web

from homeassistant.components.http import HomeAssistantView
from homeassistant.core import (
    HomeAssistant,
    ServiceCall,
    ServiceResponse,
    SupportsResponse,
    callback,
)
from homeassistant.exceptions import HomeAssistantError

from . import textes
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store

from .const import DOMAIN
from .entity import service_admin
from .processus_catalogue import FILTRES, VERDICTS, VERDICTS_SANS_RETOUR, Catalogue

_LOGGER = logging.getLogger(__name__)

STORAGE_KEY = f"{DOMAIN}_processus"
STORAGE_VERSION = 1
CAPTEUR = "sensor.vision_processus_a_valider"
# Le nom d'avant, que les cartes d'une ancienne version lisent encore.
SIGNAL_MAJ = f"{DOMAIN}_processus_maj"  # 2.5.12.14.2 : le capteur (une vraie entité, sensor.py) se met à jour à ce signal
CAPTEUR_ANCIEN = "sensor.comvision_processus_a_valider"
URL_PROCESSUS = "/api/pc_parental/processus"

SERVICE_LISTER = "processus_lister"
SERVICE_PROPOSER = "processus_proposer"
SERVICE_DECIDER = "processus_decider"

SCHEMA_LISTER = vol.Schema(
    {
        vol.Optional("filtre", default="a_classer"): vol.In(FILTRES),
        vol.Optional("limite", default=60): vol.All(
            vol.Coerce(int), vol.Range(min=1, max=500)
        ),
        vol.Optional("pc"): cv.string,
    }
)

SCHEMA_PROPOSITION = vol.Schema(
    {
        vol.Required("cle"): cv.string,
        vol.Required("verdict"): vol.In(list(VERDICTS)),
        vol.Optional("raison", default=""): cv.string,
        vol.Optional("confiance", default=50): vol.All(
            vol.Coerce(int), vol.Range(min=0, max=100)
        ),
    },
    extra=vol.ALLOW_EXTRA,
)

SCHEMA_PROPOSER = vol.Schema(
    {
        vol.Required("propositions"): vol.All(
            cv.ensure_list, [SCHEMA_PROPOSITION], vol.Length(min=1, max=300)
        ),
        vol.Optional("par", default="classeur"): cv.string,
    }
)

SCHEMA_DECIDER = vol.Schema(
    {
        vol.Required("cles"): vol.All(cv.ensure_list, [cv.string], vol.Length(min=1)),
        vol.Required("decision"): vol.In(list(VERDICTS) + ["valider", "neutre"]),
        # Exigé pour ce qui ne se défait pas (désinstaller) : la carte ne
        # l'envoie qu'après la question « es-tu sûr ? ».
        vol.Optional("confirmation", default=False): cv.boolean,
    }
)


class GestionProcessus:
    """Tient le catalogue et le relie à Home Assistant."""

    def __init__(self, hass: HomeAssistant, coordinator) -> None:
        self.hass = hass
        self.coordinator = coordinator
        self._store: Store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
        self.catalogue = Catalogue()

    # ----------------------------------------------------------- Mise en route

    async def async_setup(self) -> None:
        donnees = await self._store.async_load() or {}
        self.catalogue = Catalogue(donnees.get("elements") or {})
        if self.catalogue.purger(time.time()):
            self._sauver()
        self._publier()
        # Une seule vue par démarrage de Home Assistant : un rechargement de
        # l'intégration ne doit pas la déclarer deux fois. Elle retrouve la
        # gestion en cours à chaque requête.
        drapeau = f"{DOMAIN}_vue_processus"
        if not self.hass.data.get(drapeau):
            self.hass.http.register_view(ProcessusView())
            self.hass.data[drapeau] = True
        self._enregistrer_services()

    @callback
    def _sauver(self) -> None:
        # Un inventaire toutes les dix minutes par PC : inutile d'écrire le
        # disque à chaque fois, on regroupe.
        self._store.async_delay_save(
            lambda: {"elements": self.catalogue.elements}, 60
        )

    def etat_capteur(self) -> tuple[int, dict[str, Any]]:
        """La valeur et les attributs du capteur « à valider » (une vraie entité depuis 2.5.12.14.2, revue de HACS :
        plus de `hass.states.async_set`)."""
        compte = self.catalogue.compter()
        return compte["propositions"], {
            "a_classer": compte["a_classer"],
            "decides": compte["decides"],
            # Bouge quand un agent rend compte : la carte se relit.
            "desinstallations": sum(
                1
                for fiche in self.catalogue.elements.values()
                for chez in (fiche.get("pcs") or {}).values()
                if isinstance(chez, dict) and chez.get("desinstall")
            ),
        }

    @callback
    def _publier(self) -> None:
        async_dispatcher_send(self.hass, SIGNAL_MAJ)

    def _noms_pc(self) -> dict[str, str]:
        return {
            pc_id: pc.get("name") or pc_id
            for pc_id, pc in self.coordinator.store.pcs.items()
        }

    def _pc_id(self, nom: str) -> str:
        pc_id = self.coordinator.store.by_name(nom)
        if pc_id is None and nom in self.coordinator.store.pcs:
            pc_id = nom
        if pc_id is None:
            raise HomeAssistantError(textes.t("proc.pc_introuvable", textes.ha(self.hass), nom=nom))
        return pc_id

    # --------------------------------------------------------- Agent (poll)

    @callback
    def recevoir(self, pc_id: str, corps: dict[str, Any]) -> None:
        """Ce que l'agent envoie : l'inventaire (toutes les 10 min) et ce
        qu'il a déjà appliqué (à chaque tour)."""
        bouge = False
        maintenant = time.time()
        if isinstance(corps.get("inventaire"), list) and corps["inventaire"]:
            if self.catalogue.noter(pc_id, corps["inventaire"], maintenant):
                bouge = True
        if "appliques" in corps:
            self.catalogue.noter_appliques(pc_id, corps.get("appliques"))
        if isinstance(corps.get("desinstallations"), list):
            if self.catalogue.noter_desinstallations(pc_id, corps["desinstallations"]):
                bouge = True
                _LOGGER.info("Désinstallation : compte rendu reçu de %s", pc_id)
        if bouge:
            self.catalogue.purger(maintenant)
            self._sauver()
            self._publier()

    @callback
    def consignes(self, pc_id: str) -> dict[str, Any]:
        return self.catalogue.consignes(pc_id, self.desinstallation_permise)

    @property
    def desinstallation_permise(self) -> bool:
        """L'interrupteur de la maison. Éteint : aucun agent ne désinstalle,
        même si une décision traîne dans le catalogue."""
        return bool(getattr(self.coordinator.store, "desinstallation", False))

    # --------------------------------------------------------------- Services

    def _enregistrer_services(self) -> None:
        if self.hass.services.has_service(DOMAIN, SERVICE_LISTER):
            # Déjà là (rechargement) : les services retrouvent la gestion
            # courante par `_gestion`, rien à refaire.
            return

        async def _lister(call: ServiceCall) -> ServiceResponse:
            g = _gestion(self.hass) or self
            pc = call.data.get("pc")
            return g.catalogue.lister(
                call.data["filtre"],
                call.data["limite"],
                g._noms_pc(),
                g._pc_id(pc) if pc else None,
            )

        async def _proposer(call: ServiceCall) -> ServiceResponse:
            g = _gestion(self.hass) or self
            maintenant = time.time()
            acceptees, refusees = [], []
            for prop in call.data["propositions"]:
                try:
                    g.catalogue.proposer(
                        prop["cle"],
                        prop["verdict"],
                        prop.get("raison", ""),
                        prop.get("confiance", 50),
                        call.data["par"],
                        maintenant,
                    )
                    acceptees.append(prop["cle"])
                except (KeyError, ValueError) as err:
                    refusees.append({"cle": prop["cle"], "erreur": str(err).strip("'\"")})
            if acceptees:
                g._sauver()
                g._publier()
            return {"acceptees": acceptees, "refusees": refusees}

        async def _decider(call: ServiceCall) -> ServiceResponse:
            g = _gestion(self.hass) or self
            maintenant = time.time()
            faites, refusees = [], []
            if call.data["decision"] in VERDICTS_SANS_RETOUR:
                # Ce qui ne se défait pas se décide un logiciel à la fois,
                # interrupteur allumé, et après confirmation.
                if not g.desinstallation_permise:
                    raise HomeAssistantError(textes.t("proc.desinstallation_eteinte", textes.ha(self.hass)))
                if len(call.data["cles"]) != 1:
                    raise HomeAssistantError(textes.t("proc.une_a_la_fois", textes.ha(self.hass)))
                if not call.data["confirmation"]:
                    raise HomeAssistantError(textes.t("commun.confirme_desinstallation", textes.ha(self.hass)))
            for cle in call.data["cles"]:
                try:
                    g.catalogue.decider(cle, call.data["decision"], maintenant)
                    faites.append(cle)
                except (KeyError, ValueError) as err:
                    refusees.append({"cle": cle, "erreur": str(err).strip("'\"")})
            if faites:
                g._sauver()
                g._publier()
            if refusees and not faites and not call.return_response:
                raise HomeAssistantError(refusees[0]["erreur"])
            return {"decidees": faites, "refusees": refusees}

        service_admin(
            self.hass, SERVICE_LISTER, _lister, schema=SCHEMA_LISTER,
            supports_response=SupportsResponse.ONLY,
        )
        service_admin(
            self.hass, SERVICE_PROPOSER, _proposer, schema=SCHEMA_PROPOSER,
            supports_response=SupportsResponse.OPTIONAL,
        )
        service_admin(
            self.hass, SERVICE_DECIDER, _decider, schema=SCHEMA_DECIDER,
            supports_response=SupportsResponse.OPTIONAL,
        )


def _gestion(hass: HomeAssistant) -> GestionProcessus | None:
    for coord in (hass.data.get(DOMAIN) or {}).values():
        gestion = getattr(coord, "processus", None)
        if gestion is not None:
            return gestion
    return None


class ProcessusView(HomeAssistantView):
    """Tout le catalogue, pour la carte de validation."""

    url = URL_PROCESSUS
    name = "api:pc_parental:processus"
    requires_auth = True

    async def get(self, request: web.Request) -> web.Response:
        hass = request.app["hass"]
        # 1.93.06 (sécurité, ETUDE-optimisation-pc faille 4) : l'inventaire des logiciels de tous les PC n'est lisible
        # que par un administrateur de Home Assistant, comme les services processus_* (service_admin). Avant, il
        # suffisait d'être connecté : le compte d'un enfant voyait les logiciels de toute la maison.
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        gestion = _gestion(hass)
        if gestion is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        donnees = gestion.catalogue.lister("tous", 5000, gestion._noms_pc())
        donnees["compte"] = gestion.catalogue.compter()
        donnees["verdicts"] = {v: textes.t(f"proc.verdict_{v}", textes.ha(hass)) for v in VERDICTS}
        donnees["desinstallation"] = gestion.desinstallation_permise
        return self.json(donnees)
