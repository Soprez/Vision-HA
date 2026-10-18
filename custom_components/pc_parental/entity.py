"""Classes de base des entités."""

from __future__ import annotations

import functools
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError, Unauthorized
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from . import langues, vocabulaire
from .const import DOMAIN, PRODUIT_AFFICHE, VERSION
from .coordinator import PcParentalCoordinator
from .registre import appareil_du_pc, id_du_hub, identifiant_hub, identifiant_pc

# Les textes que les entités fabriquent (attributs, tableaux, unités) : dans la langue de Home Assistant.
# Les champs du vocabulaire ({decideur}, {demandeurs}, {lieu}…) y sont remplis d'office (vocabulaire.py).
TEXTES = vocabulaire.Catalogue("entites")


def magasin(hass: Any) -> Any:
    """Le magasin de Vision (pour {lieu} et le vocabulaire personnalisé), s'il est chargé."""
    for valeur in ((getattr(hass, "data", None) or {}).get(DOMAIN) or {}).values():
        if getattr(valeur, "store", None) is not None:
            return valeur.store
    return None


def _noms_entites() -> dict[str, dict[str, Any]]:
    """La section « entity » des fichiers translations/<langue>.json, lue une fois à l'import."""
    import json
    import os

    dossier = os.path.join(os.path.dirname(__file__), "translations")
    sortie: dict[str, dict[str, Any]] = {}
    for lg in langues.LANGUES:
        try:
            with open(os.path.join(dossier, f"{lg}.json"), encoding="utf-8") as f:
                sortie[lg] = json.load(f).get("entity") or {}
        except (OSError, ValueError):
            sortie[lg] = {}
    return sortie


_NOMS = _noms_entites()


def _anciens_noms() -> dict[str, Any]:
    """i18n/entity_id.json : les noms d'avant un renommage (lot 1 UX), qui fabriquent encore l'entity_id."""
    import json
    import os

    try:
        with open(os.path.join(os.path.dirname(__file__), "i18n", "entity_id.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


_ANCIENS_NOMS = _anciens_noms()


def ancien_nom(hass: Any, plateforme: str, cle: str | None) -> str | None:
    """Le nom d'avant le renommage d'une entité, dans la langue des entity_id de Home Assistant.

    Home Assistant fabrique l'entity_id d'une entité neuve depuis son nom traduit (`suggested_object_id`).
    Renommer le libellé (« Pause générale » → « Tout fermer ») changerait donc l'entity_id des installations
    neuves ; les entités qui reprennent ce nom gardent l'entity_id d'avant. Une entité déjà enregistrée garde
    le sien de toute façon (registre)."""
    noms = ((_ANCIENS_NOMS.get(plateforme) or {}).get(cle or "")) if isinstance(_ANCIENS_NOMS, dict) else None
    if not isinstance(noms, dict):
        return None
    brute = str(getattr(getattr(hass, "config", None), "language", "") or "")
    try:
        from homeassistant.generated.languages import NATIVE_ENTITY_IDS
    except ImportError:  # pragma: no cover - Home Assistant plus ancien
        NATIVE_ENTITY_IDS = set(langues.LANGUES)
    lg = langues.normaliser(brute if brute in NATIVE_ENTITY_IDS else "en") or langues.REPLI
    return noms.get(lg) or noms.get(langues.REPLI)


class EntityIdFige:
    """À placer avant la classe d'entité : l'entity_id d'une installation neuve suit le nom d'avant un renommage."""

    _plateforme_entity_id: str = ""

    @property
    def suggested_object_id(self) -> str | None:
        ancien = ancien_nom(getattr(self, "hass", None), self._plateforme_entity_id,
                            getattr(self, "_attr_translation_key", None))
        return ancien if ancien else super().suggested_object_id  # type: ignore[misc]


def langue(hass: Any) -> str:
    """La langue de Home Assistant, normalisée (« fr », « en »…)."""
    return langues.langue_hass(hass)


def texte(hass: Any, cle: str, **champs: Any) -> str:
    """Un texte du catalogue « entites », dans la langue de Home Assistant."""
    return TEXTES.t(cle, langue(hass), magasin(hass), **champs)


def pluriel(hass: Any, cle: str, nombre: int, **champs: Any) -> str:
    return TEXTES.n(cle, nombre, langue(hass), magasin(hass), **champs)


def categorie(hass: Any, nom: str) -> str:
    """Une catégorie (étiquette de la maison) affichée dans la langue de Home Assistant.

    En français, le nom tel qu'il a été écrit : c'est déjà la langue des données.
    """
    lg = langue(hass)
    return str(nom) if lg == langues.SOURCE else langues.categorie(str(nom), lg)


def nom_traduit(hass: Any, plateforme: str, cle: str, **champs: Any) -> str:
    """Le nom d'une entité (section « entity » des traductions), pour les noms qui changent en cours de route.

    Home Assistant fige le nom traduit à la création de l'entité ; le nom d'une plage ou d'une catégorie,
    lui, peut changer à tout moment. Ces entités gardent donc une propriété `name`, qui relit ici le modèle
    traduit (même fichier que Home Assistant) et y place les parties variables.
    """
    for lg in (langue(hass), langues.REPLI, langues.SOURCE):
        modele = ((_NOMS.get(lg) or {}).get(plateforme) or {}).get(cle, {}).get("name")
        if modele:
            break
    else:
        return cle
    return vocabulaire.remplir(modele, langue(hass), magasin(hass), **champs)


def erreur(cle: str, **champs: Any) -> HomeAssistantError:
    """Une erreur montrée à l'utilisateur, traduite par Home Assistant (section « exceptions »)."""
    return HomeAssistantError(
        translation_domain=DOMAIN,
        translation_key=cle,
        translation_placeholders={k: str(v) for k, v in champs.items()} or None,
    )

# Tout ce qui, dans une entité, change quelque chose. Home Assistant laisse
# n'importe quel utilisateur commander n'importe quelle entité : un enfant
# qui a un compte pourrait rouvrir son PC ou lire puis changer un réglage.
ECRITURES = (
    "async_turn_on", "async_turn_off", "async_toggle", "async_set_value",
    "async_set_native_value", "async_select_option", "async_press",
    "async_install", "async_send_message",
)


async def exiger_admin(entite: Any) -> None:
    """Refuse le geste d'un utilisateur qui n'est pas administrateur.

    Sans utilisateur (automatisation, script, Vision lui-même), on laisse
    passer : c'est l'administrateur qui les a écrits.
    """
    contexte = getattr(entite, "_context", None)
    user_id = getattr(contexte, "user_id", None)
    if not user_id:
        return
    utilisateur = await entite.hass.auth.async_get_user(user_id)
    if utilisateur is None or not utilisateur.is_admin:
        raise Unauthorized(
            context=contexte, user_id=user_id,
            entity_id=getattr(entite, "entity_id", None), permission="control",
        )


def service_admin(hass: HomeAssistant, nom: str, fonction, **options: Any) -> None:
    """Enregistre un service de Vision, réservé aux administrateurs.

    Même règle que pour les entités : un appel sans utilisateur (automatisation)
    passe, celui d'un utilisateur qui n'est pas administrateur est refusé.
    """

    @functools.wraps(fonction)
    async def garde(call):
        if call.context.user_id:
            utilisateur = await hass.auth.async_get_user(call.context.user_id)
            if utilisateur is None or not utilisateur.is_admin:
                raise Unauthorized(context=call.context, user_id=call.context.user_id, permission="control")
        return await fonction(call)

    hass.services.async_register(DOMAIN, nom, garde, **options)


def _garder(methode):
    @functools.wraps(methode)
    async def garde(self, *args: Any, **kwargs: Any) -> Any:
        await exiger_admin(self)
        return await methode(self, *args, **kwargs)

    garde._vision_garde = True  # type: ignore[attr-defined]
    return garde


class _ReserveAuxAdministrateurs:
    """Chaque écriture d'une entité de Vision passe d'abord par exiger_admin."""

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for nom in ECRITURES:
            methode = cls.__dict__.get(nom)
            if methode is not None and not getattr(methode, "_vision_garde", False):
                setattr(cls, nom, _garder(methode))


def infos_hub(coordinator: PcParentalCoordinator) -> DeviceInfo:
    """Description de l'appareil « Vision » (le hub) de l'entrée."""
    return DeviceInfo(
        identifiers={identifiant_hub(coordinator.config_entry.entry_id)},
        name=PRODUIT_AFFICHE,
        # Le fabricant est Vision (HA-11 : le nom du lieu n'est pas un fabricant) ; il n'entre pas dans l'entity_id.
        manufacturer=PRODUIT_AFFICHE,
        model=texte(coordinator.hass, "modele_vision"),
        sw_version=VERSION,
    )


def assurer_hub(hass: HomeAssistant, coordinator: PcParentalCoordinator) -> str:
    """Crée (ou met à jour) l'appareil hub avant les plateformes ; rend son id."""
    entry_id = coordinator.config_entry.entry_id
    return dr.async_get(hass).async_get_or_create(
        config_entry_id=entry_id, **infos_hub(coordinator)
    ).id


class HubEntity(_ReserveAuxAdministrateurs, CoordinatorEntity[PcParentalCoordinator]):
    """Entité de gestion, rattachée à l'appareil « Vision »."""

    _attr_has_entity_name = True

    def __init__(self, coordinator: PcParentalCoordinator) -> None:
        super().__init__(coordinator)
        self._attr_device_info = infos_hub(coordinator)

    # Refonte lot 5 : une nouvelle installation crée ses entités de hub cachées (entites_reduites.py).
    @property
    def entity_registry_visible_default(self) -> bool:
        from . import entites_reduites

        if entites_reduites.cachee(self.coordinator.store, self.unique_id, appareil=False):
            return False
        return super().entity_registry_visible_default


class PcEntity(_ReserveAuxAdministrateurs, CoordinatorEntity[PcParentalCoordinator]):
    """Entité rattachée à un PC (un appareil Home Assistant par PC)."""

    _attr_has_entity_name = True

    # Refonte lot 5 : une nouvelle installation ne montre que 6 entités par appareil (entites_reduites.py).
    @property
    def entity_registry_enabled_default(self) -> bool:
        from . import entites_reduites

        if entites_reduites.desactivee(self.coordinator.store, self.unique_id, appareil=True):
            return False
        return super().entity_registry_enabled_default

    @property
    def entity_registry_visible_default(self) -> bool:
        from . import entites_reduites

        if entites_reduites.cachee(self.coordinator.store, self.unique_id, appareil=True):
            return False
        return super().entity_registry_visible_default

    def __init__(self, coordinator: PcParentalCoordinator, pc_id: str) -> None:
        super().__init__(coordinator)
        self.pc_id = pc_id
        infos = DeviceInfo(
            identifiers={identifiant_pc(pc_id)},
            name=coordinator.pc_name(pc_id),
            manufacturer=PRODUIT_AFFICHE,
            model=texte(coordinator.hass, "modele_appareil"),
        )
        # Rattachement au hub par son id d'appareil (le tuple via_device est
        # supprimé en HA 2027.8). Le hub est créé avant les plateformes.
        hub = id_du_hub(coordinator.hass, coordinator.config_entry.entry_id)
        if hub is not None:
            infos["via_device_id"] = hub
        self._attr_device_info = infos

    @property
    def pc(self) -> dict[str, Any]:
        return self.coordinator.store.get(self.pc_id) or {}

    @property
    def etat(self) -> dict[str, Any]:
        return self.coordinator.etat(self.pc_id)

    @property
    def available(self) -> bool:
        return super().available and bool(self.pc)


async def appliquer(
    coordinator: PcParentalCoordinator, action, structurel: bool = False
) -> None:
    """Exécute une modification puis enregistre, en remontant les erreurs."""
    try:
        action()
    except (ValueError, KeyError) as err:
        raise HomeAssistantError(str(err)) from err
    await coordinator.async_ecrire(structurel=structurel)


def oublier_appareil(hass: HomeAssistant, pc_id: str) -> None:
    """Retire du registre l'appareil d'un PC supprimé.

    Sans cela, Home Assistant garde un appareil vide et sans entité.
    """
    appareil = appareil_du_pc(hass, pc_id)
    if appareil is not None:
        dr.async_get(hass).async_remove_device(appareil.id)


_DERNIERES_PURGES: dict[tuple[str, str, str], frozenset[str]] = {}


def prune_stale(
    hass: HomeAssistant,
    coordinator: PcParentalCoordinator,
    platform: str,
    prefix: str,
    valides: set[str],
) -> None:
    """Retire du registre les entités dont l'objet n'existe plus.

    Le registre n'est parcouru que si la liste a réellement bougé.
    """
    cle = (coordinator.config_entry.entry_id, platform, prefix)
    fige = frozenset(valides)
    if _DERNIERES_PURGES.get(cle) == fige:
        return
    _DERNIERES_PURGES[cle] = fige

    registry = er.async_get(hass)
    entry_id = coordinator.config_entry.entry_id
    for entity in list(registry.entities.values()):
        if (
            entity.config_entry_id == entry_id
            and entity.domain == platform
            and entity.unique_id.startswith(prefix)
            and entity.unique_id not in valides
        ):
            registry.async_remove(entity.entity_id)
