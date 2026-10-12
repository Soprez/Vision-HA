"""Listes déroulantes des entités de gestion."""

from __future__ import annotations

from homeassistant.const import MATCH_ALL, EntityCategory
from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from .const import (
    AUCUNE_ADRESSE,
    AUCUNE_ORIGINE,
    TAILLES_PAGE,
    TAILLE_PAGE_DEFAUT,
    DNS_AUCUN,
    DNS_FILTRANTS,
    AUCUN_CONSTAT,
    FILTRE_A_CLASSER,
    FILTRE_AUTORISES,
    FILTRE_BLOQUES,
    DAY_PRESETS,
    DEFAULT_DAYS,
    DEFAULT_SOUND,
    DOMAIN,
    NO_PC,
    JOURS_CHOIX,
    NO_RULE,
    PORTEES,
    PORTEE_ETIQUETTES,
    PORTEE_SESSION,
    NO_USER,
    PREFIXE_TAG,
    SOUNDS,
    TOUS_LES_PC,
)
from .coordinator import PcParentalCoordinator
from .planner import days_label, jours_depuis_texte
from .entity import HubEntity, PcEntity, appliquer, categorie, erreur, langue, nom_traduit, prune_stale, texte


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator: PcParentalCoordinator = hass.data[DOMAIN][entry.entry_id]
    valides = {f"{DOMAIN}_personne_{pc}" for pc in coordinator.pcs}
    prune_stale(hass, coordinator, "select", f"{DOMAIN}_personne_", valides)
    for prefixe in ("msg_position_", "msg_taille_", "role_"):
        prune_stale(hass, coordinator, "select", f"{DOMAIN}_{prefixe}",
                    {f"{DOMAIN}_{prefixe}{pc}" for pc in coordinator.pcs})

    portees = {
        f"{DOMAIN}_ruleportee_{pc}_{r['id']}"
        for pc in coordinator.pcs
        for r in coordinator.rules(pc)
    }
    prune_stale(hass, coordinator, "select", f"{DOMAIN}_ruleportee_", portees)
    jours = {
        f"{DOMAIN}_rulejours_{pc}_{r['id']}"
        for pc in coordinator.pcs
        for r in coordinator.rules(pc)
    }
    prune_stale(hass, coordinator, "select", f"{DOMAIN}_rulejours_", jours)
    # La liste « Étiquette Vision » n'existe plus : la carte d'étiquetage
    # a remplacé le trio liste + boutons. On la retire du registre, sinon
    # elle resterait indisponible sur les tableaux de bord.
    prune_stale(hass, coordinator, "select", f"{DOMAIN}_etiquette_cible", set())

    entites = [
        TargetPcSelect(coordinator),
        DaysSelect(coordinator),
        PorteePlageSelect(coordinator),
        LogicielAutoriseSelect(coordinator),
        LogicielBloqueSelect(coordinator),
        SiteAutoriseSelect(coordinator),
        SiteBloqueSelect(coordinator),
        OrigineSelect(coordinator),
        RuleToDeleteSelect(coordinator),
        DestinataireSelect(coordinator),
        SonSelect(coordinator),
        LogicielConstateSelect(coordinator),
        SiteConstateSelect(coordinator),
        AdresseSelect(coordinator),
        TaillePageSelect(coordinator),
        DnsFiltrantSelect(coordinator),
        VeilleThemeSelect(coordinator),
        VeilleRadioSelect(coordinator),
    ]
    entites += [PersonSelect(coordinator, pc_id) for pc_id in coordinator.pcs]
    entites += [RoleSelect(coordinator, pc_id) for pc_id in coordinator.pcs]
    entites += [MessagePositionSelect(coordinator, pc_id) for pc_id in coordinator.pcs]
    entites += [MessageTailleSelect(coordinator, pc_id) for pc_id in coordinator.pcs]
    entites += [
        RulePorteeSelect(coordinator, pc_id, regle["id"])
        for pc_id in coordinator.pcs
        for regle in coordinator.rules(pc_id)
    ]
    entites += [
        RuleJoursSelect(coordinator, pc_id, regle["id"])
        for pc_id in coordinator.pcs
        for regle in coordinator.rules(pc_id)
    ]
    async_add_entities(entites)


class TargetPcSelect(HubEntity, SelectEntity):
    """PC sur lequel portent les actions de gestion.

    HA-01 : vaut « — choisir — » au démarrage et ne reprend **pas** sa dernière valeur. Une cible restée
    sur un appareil depuis la veille, c'était un retrait (ou un réglage) appliqué au mauvais appareil sans
    l'avoir choisi. « — choisir — » agit comme « Tous les appareils » pour ce qui accepte « tous » (une
    plage) ; une suppression exige, elle, un vrai choix. Les valeurs d'option existantes ne changent pas.
    """

    _attr_translation_key = "appareil_cible"
    _attr_icon = "mdi:desktop-classic"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_pc_cible"
        self._choix: str | None = None  # None : « — choisir — » (dans la langue de Home Assistant)

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self._choix = None
        self._appliquer()

    def _a_choisir(self) -> str:
        return texte(self.hass, "choisir_appareil")

    def _appliquer(self) -> None:
        self.coordinator.cible = self.coordinator.store.by_name(self._choix) if self._choix else None

    @property
    def options(self) -> list[str]:
        return [self._a_choisir(), NO_PC] + self.coordinator.store.names()

    @property
    def current_option(self) -> str:
        if self._choix is not None and self._choix not in self.coordinator.store.names() and self._choix != NO_PC:
            self._choix = None
            self._appliquer()
        return self._choix if self._choix is not None else self._a_choisir()

    async def async_select_option(self, option: str) -> None:
        self._choix = None if option == self._a_choisir() else option
        self._appliquer()
        self.coordinator.page = 1
        self.async_write_ha_state()
        self.coordinator.async_update_listeners()


class DaysSelect(HubEntity, SelectEntity, RestoreEntity):
    """Jours de la plage à créer."""

    _attr_translation_key = "jours_de_la_plage"
    _attr_icon = "mdi:calendar-week"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_jours"
        self._attr_options = list(DAY_PRESETS)
        self._choix = DEFAULT_DAYS

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        dernier = await self.async_get_last_state()
        if dernier and dernier.state in self._attr_options:
            self._choix = dernier.state

    @property
    def current_option(self) -> str:
        return self._choix

    @property
    def weekdays(self) -> list[bool]:
        return list(DAY_PRESETS.get(self._choix, DAY_PRESETS[DEFAULT_DAYS]))

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.async_write_ha_state()


class PorteePlageSelect(HubEntity, SelectEntity, RestoreEntity):
    """Ce que la prochaine plage viendra fermer.

    Une seule liste de plages, deux façons de s'en servir. « Verrouiller la
    session » est le comportement historique et reste le défaut : personne
    ne doit découvrir que ses plages ont changé de sens.
    """

    _attr_translation_key = "cette_plage_ferme"
    _attr_icon = "mdi:target"
    _attr_options = PORTEES

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_portee_plage"
        self._choix = PORTEE_SESSION

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        dernier = await self.async_get_last_state()
        if dernier and dernier.state in PORTEES:
            self._choix = dernier.state

    @property
    def current_option(self) -> str:
        return self._choix

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.async_write_ha_state()


class RuleToDeleteSelect(HubEntity, SelectEntity):
    """Plage à supprimer, sur le PC ciblé ou sur toutes les machines.

    La création pose une plage sur toutes les machines d'un coup quand
    « PC ciblé » reste sur « Tous les PC ». La suppression, elle, exigeait
    de désigner une machine — et la liste restait donc vide tant qu'on ne
    l'avait pas fait. On pouvait créer une plage et ne plus jamais la
    retirer sans comprendre pourquoi. Les deux gestes sont maintenant
    symétriques : ce qui se pose partout se retire partout.
    """

    _attr_translation_key = "plage_a_supprimer"
    _attr_icon = "mdi:calendar-remove"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_plage_a_supprimer"
        self._choix = NO_RULE

    @property
    def options(self) -> list[str]:
        # Filtrée par « PC ciblé » quand une machine est désignée : proposer
        # les plages des autres, c'était promettre une suppression qui
        # échouait ensuite avec « cette plage n'existe plus ».
        return [NO_RULE] + self.coordinator.libelles_plages(
            self.coordinator.cible or ""
        )

    @property
    def current_option(self) -> str:
        if self._choix not in self.options:
            self._choix = NO_RULE
            self.coordinator.plage_cible = None
        return self._choix

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.coordinator.plage_cible = None if option == NO_RULE else option
        self.async_write_ha_state()


class RulePorteeSelect(PcEntity, SelectEntity):
    """Ce que cette plage-ci ferme, modifiable à tout moment.

    Une plage se pose une fois et se relit longtemps. Devoir la supprimer
    et la recréer pour passer de « verrouiller la session » à « fermer des
    étiquettes », c'était perdre son nom, ses heures et ses jours pour
    changer un seul mot.
    """

    _attr_icon = "mdi:target"
    _attr_options = PORTEES
    _attr_translation_key = "plage_ferme"

    def __init__(
        self, coordinator: PcParentalCoordinator, pc_id: str, rule_id: int
    ) -> None:
        super().__init__(coordinator, pc_id)
        self.rule_id = rule_id
        self._attr_unique_id = f"{DOMAIN}_ruleportee_{pc_id}_{rule_id}"
        self._nom = (self._rule() or {}).get("name", str(rule_id))

    def _rule(self) -> dict | None:
        return self.coordinator.store.rule(self.pc_id, self.rule_id)

    @property
    def name(self) -> str:
        if rule := self._rule():
            self._nom = rule["name"]
        return nom_traduit(self.coordinator.hass, "select", "plage_ferme", nom=self._nom)

    @property
    def available(self) -> bool:
        return super().available and self._rule() is not None

    @property
    def current_option(self) -> str:
        rule = self._rule()
        if rule and rule.get("portee") == "etiquettes":
            return PORTEE_ETIQUETTES
        return PORTEE_SESSION

    async def async_select_option(self, option: str) -> None:
        rule = self._rule()
        if rule is None:
            raise erreur("plage_disparue")
        portee = "etiquettes" if option == PORTEE_ETIQUETTES else "session"
        if portee == "etiquettes" and not (rule.get("etiquettes") or []):
            raise erreur("plage_sans_etiquette")

        def _faire() -> None:
            rule["portee"] = portee

        await appliquer(self.coordinator, _faire)


class RuleJoursSelect(PcEntity, SelectEntity):
    """Les jours où cette plage s'applique, choisis dans une liste.

    Les combinaisons proposées sont écrites comme le tableau les affiche,
    et relues par le même lecteur : choisir une ligne ne peut donc pas
    changer le sens de ce qu'on lit ailleurs.

    Une plage dont la combinaison n'est dans aucune de ces lignes — écrite
    à la main du temps où ce champ était libre — garde la sienne : elle
    s'ajoute en tête de liste plutôt que d'être écrasée en silence.
    """

    _attr_icon = "mdi:calendar-week"
    _attr_translation_key = "plage_jours"

    def __init__(
        self, coordinator: PcParentalCoordinator, pc_id: str, rule_id: int
    ) -> None:
        super().__init__(coordinator, pc_id)
        self.rule_id = rule_id
        self._attr_unique_id = f"{DOMAIN}_rulejours_{pc_id}_{rule_id}"
        self._nom = (self._rule() or {}).get("name", str(rule_id))

    def _rule(self) -> dict | None:
        return self.coordinator.store.rule(self.pc_id, self.rule_id)

    @property
    def name(self) -> str:
        if rule := self._rule():
            self._nom = rule["name"]
        return nom_traduit(self.coordinator.hass, "select", "plage_jours", nom=self._nom)

    @property
    def available(self) -> bool:
        return super().available and self._rule() is not None

    def _actuel(self) -> str:
        rule = self._rule()
        return days_label((rule or {}).get("weekdays") or [])

    @property
    def options(self) -> list[str]:
        actuel = self._actuel()
        if actuel and actuel not in JOURS_CHOIX:
            return [actuel] + list(JOURS_CHOIX)
        return list(JOURS_CHOIX)

    @property
    def current_option(self) -> str:
        return self._actuel()

    async def async_select_option(self, option: str) -> None:
        rule = self._rule()
        if rule is None:
            raise erreur("plage_disparue")
        try:
            jours = jours_depuis_texte(option)
        except ValueError as err:
            raise erreur("jours_illisibles", detail=str(err)) from err
        if not any(jours):
            raise erreur("plage_sans_jour")

        def _faire() -> None:
            rule["weekdays"] = jours

        await appliquer(self.coordinator, _faire)


class PersonSelect(PcEntity, SelectEntity):
    """Utilisateur Home Assistant à qui appartient ce PC."""

    _attr_translation_key = "utilisateur"
    _attr_icon = "mdi:account-box"

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_personne_{pc_id}"

    def _personnes(self) -> dict[str, str]:
        """Nom affiché → entity_id, pour toutes les personnes de la maison."""
        sortie: dict[str, str] = {}
        for etat in self.coordinator.hass.states.async_all("person"):
            nom = etat.attributes.get("friendly_name") or etat.entity_id
            sortie[nom] = etat.entity_id
        return sortie

    @property
    def options(self) -> list[str]:
        return [NO_USER] + sorted(self._personnes())

    @property
    def current_option(self) -> str:
        cible = self.pc.get("person") or ""
        for nom, entity_id in self._personnes().items():
            if entity_id == cible:
                return nom
        return NO_USER

    @property
    def extra_state_attributes(self) -> dict[str, str | None]:
        return {
            "entite_personne": self.pc.get("person") or None,
            # Ce que l'appareil a proposé (choix dans l'appli, compte Windows) :
            # seul un administrateur fixe la personne, ici.
            "personne_proposee": self.pc.get("personne_proposee") or None,
        }

    async def async_select_option(self, option: str) -> None:
        entity_id = "" if option == NO_USER else self._personnes().get(option, "")

        def _faire() -> None:
            self.pc["person"] = entity_id
            self.pc.pop("personne_proposee", None)

        await appliquer(self.coordinator, _faire)


class DestinataireSelect(HubEntity, SelectEntity, RestoreEntity):
    """À qui envoyer le message : tous, une étiquette, ou un PC précis."""

    _attr_translation_key = "destinataire_du_message"
    _attr_icon = "mdi:account-multiple"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_destinataire"
        self._choix = TOUS_LES_PC

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        dernier = await self.async_get_last_state()
        if dernier and dernier.state in self.options:
            self._choix = dernier.state

    @property
    def options(self) -> list[str]:
        store = self.coordinator.store
        return (
            [TOUS_LES_PC]
            + [f"{PREFIXE_TAG}{t}" for t in store.tags()]
            + store.names()
        )

    @property
    def current_option(self) -> str:
        if self._choix not in self.options:
            self._choix = TOUS_LES_PC
        return self._choix

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        try:
            vises = self.coordinator.store.destinataires(self._choix)
        except ValueError:
            vises = []
        return {
            "pc_vises": [self.coordinator.pc_name(p) for p in vises],
            "etiquettes_connues": self.coordinator.store.tags(),
        }

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.async_write_ha_state()


class SonSelect(HubEntity, SelectEntity, RestoreEntity):
    """Son joué par Windows à l'arrivée du message."""

    _attr_translation_key = "son_de_la_notification"
    _attr_icon = "mdi:music-note"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_son"
        self._attr_options = list(SOUNDS)
        self._choix = DEFAULT_SOUND

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        dernier = await self.async_get_last_state()
        if dernier and dernier.state in self._attr_options:
            self._choix = dernier.state

    @property
    def current_option(self) -> str:
        return self._choix

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.async_write_ha_state()


def _duree(hass, secondes: float) -> str:
    """Durée lisible : « 2 h 15 » plutôt que « 8100 »."""
    minutes = int(round(float(secondes) / 60))
    if minutes < 60:
        return texte(hass, "duree_minutes", minutes=minutes)
    return texte(hass, "duree_heures", heures=minutes // 60, minutes=f"{minutes % 60:02d}")


_PAR_ETAT: dict = {}


class _ConstateSelect(HubEntity, SelectEntity):
    # Les tableaux markdown de ces listes pèsent des dizaines de Ko et changent à chaque relevé :
    # le recorder ne garde que l'état, pas les attributs.
    _unrecorded_attributes = frozenset({MATCH_ALL})

    """Base des listes alimentées par ce que Vision a constaté.

    Elles portent sur le **PC ciblé** : un seul jeu d'entités quel que soit
    le nombre de machines. L'attribut `details` sert au tableau de bord, qui
    l'affiche tel quel — ainsi le tableau reste juste sans être reconstruit.
    """

    _genre: str = ""

    def _pc(self) -> dict | None:
        """Toujours la maison.

        On parle de ce qui est permis chez soi, pas machine par machine :
        une exception se pose sur la carte du PC concerné, et elle l'emporte.
        """
        return None

    def _noms(self) -> list[str]:
        raise NotImplementedError

    def _ligne(self, nom: str) -> str:
        raise NotImplementedError

    def _par_etat(self, etat: str) -> list[str]:
        """Les noms dans cet état — à classer, autorisés, ou bloqués.

        Un onglet par type, une section par état : le filtre déroulant
        d'avant demandait de se souvenir de ce qu'on regardait. Ici c'est
        écrit au-dessus de chaque tableau.
        """
        pc = self._pc()
        magasin = self.coordinator.store
        # Un seul passage sur le catalogue toutes les 5 s pour les trois états : les trois
        # listes et leurs attributs s'y servent, au lieu de reclasser des milliers de noms
        # à chaque relevé d'un appareil (ce qui bloquait la boucle d'événements).
        import time as _t
        cle = (self._genre, (pc or {}).get("id") or "", getattr(magasin, "version_cache", 0))
        cache = _PAR_ETAT.get(cle)
        if cache is None or _t.monotonic() - cache[0] > 5:
            seaux: dict[str, list[str]] = {}
            for n in self._noms():
                seaux.setdefault(magasin.classe(pc, self._genre, n), []).append(n)
            cache = (_t.monotonic(), seaux)
            _PAR_ETAT[cle] = cache
        return list(cache[1].get(etat, []))

    # L'état que cette liste montre. Trois listes plutôt qu'une seule qui
    # mélangeait tout : une liste unique obligeait à lire l'état de chaque
    # ligne pour savoir ce qu'on regardait, et les boutons agissaient sur
    # n'importe quoi. Ici chaque liste ne contient que ce qui lui
    # appartient, et ses boutons ne peuvent rien faire d'autre.
    _etat: str = FILTRE_A_CLASSER

    def _retenus(self) -> list[str]:
        return self._par_etat(self._etat)

    def _file(self) -> list[str]:
        """Ce sur quoi personne ne s'est encore prononcé."""
        return self._par_etat(FILTRE_A_CLASSER)

    @property
    def current_option(self) -> str:
        choix = getattr(self, "_choix", None)
        options = self.options
        if choix not in options:
            choix = options[0]
            self._choix = choix
        return choix

    _colonnes: tuple[str, ...] = ()

    def _taille(self) -> int:
        """Combien de lignes par page, tel que choisi à l'écran."""
        registre = er.async_get(self.coordinator.hass)
        eid = registre.async_get_entity_id(
            "select", DOMAIN, f"{DOMAIN}_taille_page"
        )
        etat = self.coordinator.hass.states.get(eid) if eid else None
        valeur = etat.state if etat else TAILLE_PAGE_DEFAUT
        if valeur not in TAILLES_PAGE:
            valeur = TAILLE_PAGE_DEFAUT
        return int(valeur)

    def _pagine(self) -> tuple[list[str], int, int, int]:
        """La page à montrer, et de quoi dire où l'on en est.

        La page demandée est ramenée dans les bornes plutôt que refusée :
        une liste qui rétrécit sous nos pieds ne doit pas afficher du vide.
        """
        tous = self._retenus()
        taille = self._taille()
        pages = max(1, -(-len(tous) // taille))
        # Le numéro de page est commun : il sert la file, là où l'on
        # travaille. Les deux autres listes s'en tiennent à leur première
        # page — on y va chercher un élément précis, pas en parcourir cent.
        page = min(max(1, self.coordinator.page), pages)
        if self._etat != FILTRE_A_CLASSER:
            page = 1
        debut = (page - 1) * taille
        return tous[debut : debut + taille], page, pages, len(tous)

    @property
    def options(self) -> list[str]:
        """Seulement la page affichée : les boutons agissent sur ce qu'on voit."""
        return self._pagine()[0] or [AUCUN_CONSTAT]

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        # La colonne montre l'état de la **maison** : elle l'annonçait avec
        # le nom du PC ciblé, ce qui promettait une information machine par
        # machine que ce tableau n'a jamais donnée.
        hass = self.coordinator.hass
        colonnes = [texte(hass, f"colonne_{c}") for c in self._colonnes] + [texte(hass, "colonne_etat_maison")]
        entete = (
            "| " + " | ".join(colonnes) + " |\n"
            "|" + "|".join(["---"] * len(colonnes)) + "|"
        )
        noms, page, pages, total = self._pagine()
        taille = self._taille()
        autorises = self._par_etat(FILTRE_AUTORISES)
        bloques = self._par_etat(FILTRE_BLOQUES)
        return {
            "pc": texte(hass, "tous_les_appareils"),
            "entete": entete,
            # La file, paginée : c'est là qu'on travaille.
            "lignes": [self._ligne(nom) for nom in noms],
            "total": total,
            "file": len(self._file()),
            "page": page,
            "pages": pages,
            "montres": len(noms),
            # Les deux autres états, pour relire ce qu'on a décidé sans
            # changer d'écran. Bornés eux aussi : un attribut d'entité ne
            # doit jamais enfler sans limite.
            "lignes_autorises": [
                self._ligne(nom) for nom in autorises[:taille]
            ],
            "total_autorises": len(autorises),
            "lignes_bloques": [self._ligne(nom) for nom in bloques[:taille]],
            "total_bloques": len(bloques),
            "a_classer": self.coordinator.store.a_classer(self._pc()),
            # Les étiquettes de l'élément choisi, pour les **lire** sans
            # changer d'onglet. On les pose ailleurs — dans la carte —
            # mais ne pas les voir ici, c'est décider à l'aveugle.
            "etiquettes_choisi": self.coordinator.store.etiquettes_de(
                self._genre, self.current_option or ""
            ),
        }

    def _marque(self, nom: str) -> str:
        """L'état de cette ligne, en toutes lettres, et d'où il vient.

        Une colonne de pastilles oblige à se souvenir de ce qu'elles
        veulent dire. Ici on écrit ce qui s'applique **et** pourquoi :
        c'est la seule information qu'on cherche en parcourant la liste.

        La question se pose au niveau de la **maison** — le catalogue lui
        appartient. La version d'avant portait une branche « par PC » que
        rien n'atteignait jamais, et surtout elle ne savait pas dire
        « autorisé » : un site autorisé pour la maison s'affichait
        « à classer », y compris dans le tableau des autorisés.
        """
        magasin = self.coordinator.store
        hass = self.coordinator.hass
        etiquettes = magasin.etiquettes_de(self._genre, nom)

        if magasin.est_banni(nom):
            return texte(hass, "marque_bloque_nom")
        for etiquette in etiquettes:
            if magasin.est_banni(etiquette):
                return texte(hass, "marque_bloque_categorie", categorie=categorie(hass, etiquette))
        if magasin.est_autorise_maison(nom):
            return texte(hass, "marque_autorise_nom")
        for etiquette in etiquettes:
            if magasin.est_autorise_maison(etiquette):
                return texte(hass, "marque_autorise_categorie", categorie=categorie(hass, etiquette))
        return texte(hass, "marque_a_classer")

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.async_write_ha_state()


class LogicielConstateSelect(_ConstateSelect):
    """Logiciels utilisés dans la maison, les plus longs d'abord.

    Le catalogue est commun à tous les PC : un logiciel étiqueté une fois
    l'est partout. Les marques, elles, dépendent du PC ciblé.
    """

    _attr_translation_key = "logiciel_constate"
    _attr_icon = "mdi:application-outline"
    _genre = "apps"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_logiciel_constate"
        self._choix = AUCUN_CONSTAT

    def _noms(self) -> list[str]:
        return self.coordinator.store.catalogue_maison("apps")

    _colonnes = ("logiciel", "etiquettes", "signe_par", "temps")

    def _confiance(self, nom: str) -> str:
        """La signature, et ce qu'en disent les antivirus quand on le sait.

        Les deux côte à côte parce qu'ils ne disent pas la même chose : un
        certificat volé signe un logiciel malveillant tout aussi bien, et
        un programme honnête peut n'être signé par personne.
        """
        magasin = self.coordinator.store
        signature = magasin.confiance_logiciel(nom)
        avis = self.coordinator.virustotal.libelle(magasin.empreinte_de(nom), langue(self.coordinator.hass))
        return f"{signature} · {avis}" if avis else signature

    def _ligne(self, nom: str) -> str:
        magasin = self.coordinator.store
        hass = self.coordinator.hass
        etiquettes = [categorie(hass, e) for e in magasin.etiquettes_de("apps", nom)]
        mesure = (
            texte(hass, "mention_ajout")
            if magasin.est_ajout("apps", nom)
            else _duree(hass, magasin.temps_maison(nom))
        )
        return (
            f"| **{nom}** | {', '.join(etiquettes) or '—'} "
            f"| {self._confiance(nom)} "
            f"| {mesure} | {self._marque(nom)} |"
        )


class LogicielAutoriseSelect(LogicielConstateSelect):
    """Les logiciels autorisés pour la maison."""

    _attr_translation_key = "logiciel_autorise"
    _attr_icon = "mdi:check-decagram"
    _etat = FILTRE_AUTORISES

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_logiciel_autorise"


class LogicielBloqueSelect(LogicielConstateSelect):
    """Les logiciels bloqués pour la maison."""

    _attr_translation_key = "logiciel_bloque"
    _attr_icon = "mdi:cancel"
    _etat = FILTRE_BLOQUES

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_logiciel_bloque"


class SiteConstateSelect(_ConstateSelect):
    """Domaines résolus dans la maison, les plus revus d'abord."""

    _attr_translation_key = "site_constate"
    _attr_icon = "mdi:web"
    _genre = "sites"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_site_constate"
        self._choix = AUCUN_CONSTAT

    def _noms(self) -> list[str]:
        return self.coordinator.store.catalogue_maison("sites")

    _colonnes = ("site", "etiquettes", "ouvert_par", "frequentation", "releves")

    def _ligne(self, nom: str) -> str:
        magasin = self.coordinator.store
        hass = self.coordinator.hass
        etiquettes = [categorie(hass, e) for e in magasin.etiquettes_de("sites", nom)]
        mesure = (
            texte(hass, "mention_ajout")
            if magasin.est_ajout("sites", nom)
            else str(magasin.releves_maison(nom))
        )
        # Ce que l'agent a vu parler à ce domaine. Souvent le navigateur, et
        # alors sans intérêt ; parfois un programme qu'on ne connaît pas, et
        # c'est précisément ce qu'on cherchait.
        origines = magasin.origines_de(nom)
        return (
            f"| **{nom}** | {', '.join(etiquettes) or '—'} "
            f"| {', '.join(origines[:3]) or '—'} "
            f"| {self.coordinator.notoriete.libelle(nom, langue(hass))} "
            f"| {mesure} | {self._marque(nom)} |"
        )


class SiteAutoriseSelect(SiteConstateSelect):
    """Les sites autorisés pour la maison."""

    _attr_translation_key = "site_autorise"
    _attr_icon = "mdi:check-decagram"
    _etat = FILTRE_AUTORISES

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_site_autorise"


class SiteBloqueSelect(SiteConstateSelect):
    """Les sites bloqués pour la maison.

    Les domaines de la liste noire n'y sont pas, et c'est voulu : ils ne
    sont pas une décision de la maison, ils ne se discutent pas, et ils se
    comptent par centaines de milliers. Pour en chercher un, il y a
    « Chercher dans la liste », plus bas.
    """

    _attr_translation_key = "site_bloque"
    _attr_icon = "mdi:cancel"
    _etat = FILTRE_BLOQUES

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_site_bloque"


class OrigineSelect(HubEntity, SelectEntity):
    """Les programmes vus parler au site choisi.

    Bloquer le domaine coupe la fuite ; bloquer le programme coupe la
    source. Encore faut-il savoir lequel c'est — c'est ce que cette liste
    répond, quand elle le sait.
    """

    _attr_translation_key = "ouvert_par"
    _attr_icon = "mdi:application-export"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_origine_cible"
        self._choix = AUCUNE_ORIGINE

    def _site(self) -> str:
        registre = er.async_get(self.coordinator.hass)
        eid = registre.async_get_entity_id(
            "select", DOMAIN, f"{DOMAIN}_site_constate"
        )
        etat = self.coordinator.hass.states.get(eid) if eid else None
        return etat.state if etat else ""

    @property
    def options(self) -> list[str]:
        site = self._site()
        if not site or site == AUCUN_CONSTAT:
            return [AUCUNE_ORIGINE]
        return [AUCUNE_ORIGINE] + self.coordinator.store.origines_de(site)

    @property
    def current_option(self) -> str:
        if self._choix not in self.options:
            self._choix = AUCUNE_ORIGINE
        return self._choix

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        return {"site": self._site()}

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.async_write_ha_state()

    @property
    def programme(self) -> str:
        return "" if self._choix == AUCUNE_ORIGINE else self._choix


class AdresseSelect(HubEntity, SelectEntity):
    """Adresses précises constatées sous le site choisi.

    C'est le seul endroit où les sous-domaines reparaissent, et pour une
    raison précise : désigner celui qu'on veut épargner. Ailleurs, un site
    est un domaine — « m.youtube.com » et « youtube.com » sont le même
    endroit, et personne ne veut trancher deux fois.
    """

    _attr_translation_key = "adresse_du_site"
    _attr_icon = "mdi:link-variant"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_adresse_constatee"
        self._choix = AUCUNE_ADRESSE

    def _site(self) -> str:
        registre = er.async_get(self.coordinator.hass)
        eid = registre.async_get_entity_id(
            "select", DOMAIN, f"{DOMAIN}_site_constate"
        )
        if not eid:
            return ""
        etat = self.coordinator.hass.states.get(eid)
        if etat is None or etat.state in ("unknown", "unavailable", AUCUN_CONSTAT):
            return ""
        return etat.state

    @property
    def options(self) -> list[str]:
        site = self._site()
        if not site:
            return [AUCUNE_ADRESSE]
        return self.coordinator.store.adresses_maison(site) or [AUCUNE_ADRESSE]

    @property
    def current_option(self) -> str:
        if self._choix not in self.options:
            self._choix = self.options[0]
        return self._choix

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        return {"site": self._site() or None}

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.async_write_ha_state()


class DnsFiltrantSelect(HubEntity, SelectEntity):
    """Résolveur DNS posé sur les cartes réseau de chaque PC.

    C'est lui qui encaisse la masse : des millions de domaines dangereux,
    aucun entretien, et le fichier hosts reste court — le service DNS de
    Windows le relit sans arrêt, cent mille lignes le mettraient à genoux.

    Cela ne marche que parce que Vision désactive déjà le DNS chiffré des
    navigateurs : sans quoi Chrome résoudrait lui-même et ne verrait jamais
    ce réglage. Revenir sur « Aucun » rend la main au DHCP de la box.
    """

    _attr_translation_key = "filtre_dns_des_pc"
    _attr_icon = "mdi:dns"
    _attr_options = list(DNS_FILTRANTS)

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_dns_filtrant"

    @property
    def current_option(self) -> str:
        return self.coordinator.store.dns_filtrant

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        return {
            "serveurs": DNS_FILTRANTS.get(
                self.coordinator.store.dns_filtrant, []
            )
        }

    async def async_select_option(self, option: str) -> None:
        if option not in DNS_FILTRANTS:
            option = DNS_AUCUN
        coord = self.coordinator

        def _faire() -> None:
            coord.store.dns_filtrant = option

        await appliquer(coord, _faire)


class TaillePageSelect(HubEntity, SelectEntity, RestoreEntity):
    """Combien de lignes affichées à la fois.

    Une liste qui déborde ne se lit pas ; une liste paginée se parcourt sans
    jamais perdre sa place. Changer la taille ramène à la première page :
    rester page sept d'une liste qui vient d'en perdre six n'aurait aucun
    sens.
    """

    _attr_translation_key = "resultats_par_page"
    _attr_icon = "mdi:format-list-numbered"
    _attr_options = list(TAILLES_PAGE)

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{DOMAIN}_taille_page"
        self._choix = TAILLE_PAGE_DEFAUT

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        dernier = await self.async_get_last_state()
        if dernier and dernier.state in TAILLES_PAGE:
            self._choix = dernier.state

    @property
    def current_option(self) -> str:
        return self._choix

    async def async_select_option(self, option: str) -> None:
        self._choix = option
        self.coordinator.page = 1
        self.async_write_ha_state()
        self.coordinator.async_update_listeners()


class VeilleThemeSelect(HubEntity, SelectEntity):
    """Les couleurs de l'écran de veille de la télé."""

    _attr_translation_key = "ecran_de_veille_theme"
    _attr_icon = "mdi:palette"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        from .veille_plus import THEMES

        self._attr_options = list(THEMES)
        self._attr_unique_id = f"{DOMAIN}_veille_theme"

    @property
    def current_option(self) -> str:
        return self.coordinator.store.veille_theme if self.coordinator.store.veille_theme in self._attr_options else self._attr_options[0]

    async def async_select_option(self, option: str) -> None:
        coord = self.coordinator

        def _faire() -> None:
            coord.store.veille_theme = option

        await appliquer(coord, _faire)


class VeilleRadioSelect(HubEntity, SelectEntity):
    """La radio de l'écran de veille, parmi des stations sans publicité.

    « Personnalisée » apparaît quand l'adresse (Écran de veille : musique) n'est pas dans la liste.
    """

    _attr_translation_key = "ecran_de_veille_radio"
    _attr_icon = "mdi:radio"

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        from .veille_plus import RADIOS, RADIO_PERSONNALISEE

        self._radios = dict(RADIOS)
        self._attr_options = [n for n, _ in RADIOS] + [RADIO_PERSONNALISEE]
        self._attr_unique_id = f"{DOMAIN}_veille_radio"

    @property
    def current_option(self) -> str:
        from .veille_plus import radio_nom

        return radio_nom(self.coordinator.store.veille_musique)

    async def async_select_option(self, option: str) -> None:
        if option not in self._radios:
            return  # « Personnalisée » : l'adresse se tape dans le champ texte
        coord = self.coordinator
        url = self._radios[option]

        def _faire() -> None:
            coord.store.veille_musique = url

        await appliquer(coord, _faire)


class _MessageReglageSelect(PcEntity, SelectEntity):
    """Où et en quelle taille cet appareil montre un message, quand l'envoi ne le précise pas."""

    _attr_entity_category = EntityCategory.CONFIG
    _cle = ""
    _valeurs: tuple[str, ...] = ()
    _defaut = ""

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_{self._cle}_{pc_id}"

    def _libelle(self, valeur: str) -> str:
        return texte(self.coordinator.hass, f"{self._cle}_{valeur}")

    @property
    def options(self) -> list[str]:
        return [self._libelle(v) for v in self._valeurs]

    @property
    def current_option(self) -> str:
        v = str(self.pc.get(self._cle) or "")
        return self._libelle(v if v in self._valeurs else self._defaut)

    async def async_select_option(self, option: str) -> None:
        valeur = next((v for v in self._valeurs if self._libelle(v) == option), self._defaut)

        def _faire() -> None:
            self.pc[self._cle] = valeur

        await appliquer(self.coordinator, _faire)


class MessagePositionSelect(_MessageReglageSelect):
    _attr_translation_key = "position_des_messages"
    _attr_icon = "mdi:dock-window"
    _cle = "msg_position"
    _defaut = "centre"
    _valeurs = ("plein_ecran", "centre", "haut", "bas", "gauche", "droite",
                "haut_gauche", "haut_droite", "bas_gauche", "bas_droite")


class MessageTailleSelect(_MessageReglageSelect):
    _attr_translation_key = "taille_des_messages"
    _attr_icon = "mdi:resize"
    _cle = "msg_taille"
    _defaut = "moyenne"
    _valeurs = ("petite", "moyenne", "grande")


class RoleSelect(PcEntity, SelectEntity):
    """Le rôle de cet appareil (1.71.0) : celui de sa personne, ou un autre rôle en surcharge. Administrateurs ; journalisé."""

    _attr_translation_key = "role_de_l_appareil"
    _attr_icon = "mdi:account-key"
    _attr_entity_category = EntityCategory.CONFIG

    @property
    def SUIVRE(self) -> str:  # noqa: N802 (nom d'origine, gardé pour qui s'en sert)
        return texte(self.coordinator.hass, "role_selon_personne")

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator, pc_id)
        self._attr_unique_id = f"{DOMAIN}_role_{pc_id}"

    @property
    def options(self) -> list[str]:
        return [self.SUIVRE] + [str(r["nom"]) for r in self.coordinator.store.roles.values()]

    @property
    def current_option(self) -> str:
        ident = str(self.pc.get("role") or "")
        r = self.coordinator.store.roles.get(ident)
        return str(r["nom"]) if r else self.SUIVRE

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        from . import roles

        return {"role_effectif": roles.nom(self.coordinator.store, self.pc),
                "fonctions": roles.fonctions(self.coordinator.store, self.pc)}

    async def async_select_option(self, option: str) -> None:
        from . import roles

        store = self.coordinator.store
        ident = "" if option == self.SUIVRE else next((i for i, r in store.roles.items() if r["nom"] == option), None)
        if ident is None:
            raise erreur("role_inconnu", role=option)
        qui = texte(self.coordinator.hass, "qui_administrateur")
        if self._context is not None and self._context.user_id:
            u = await self.hass.auth.async_get_user(self._context.user_id)
            qui = (u.name if u else None) or qui
        elif self._context is not None:
            qui = texte(self.coordinator.hass, "qui_automatisation")
        try:
            roles.attribuer_appareil(self.hass, store, self.pc_id, ident, qui)
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err  # i18n : message du module rôles (lot A2)
        await self.coordinator.async_ecrire()
