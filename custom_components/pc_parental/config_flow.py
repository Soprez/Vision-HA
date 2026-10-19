"""Ajout de l'intégration depuis l'interface de Home Assistant."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

import voluptuous as vol
from homeassistant.config_entries import ConfigEntry, ConfigFlow, OptionsFlow
from homeassistant.core import callback
from homeassistant.helpers.selector import (
    EntitySelector,
    EntitySelectorConfig,
    SelectSelector,
    SelectSelectorConfig,
    SelectSelectorMode,
    TextSelector,
    TextSelectorConfig,
)

import logging

from . import langues, textes, vocabulaire
from .const import DOMAIN, PRODUIT_AFFICHE

_LOGGER = logging.getLogger(__name__)

# Les textes que l'assistant compose en Python (résumés, libellés d'options, résultats), dans la langue de
# Home Assistant ; les écrans eux-mêmes sont dans translations/<langue>.json. Les champs du vocabulaire
# ({decideur}, {demandeurs}, {lieu}…) y sont remplis d'office (vocabulaire.py).
TEXTES = vocabulaire.Catalogue("config")

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigFlowResult

GARDER, NOUVELLE, CHOISIR = "garder", "nouvelle", "choisir"


class _Assistant:
    """Ce que partagent l'assistant d'ajout (5 étapes) et « Relancer l'assistant » (refonte UX lot 4)."""

    hass: Any

    def _lg(self) -> str:
        return langues.langue_hass(self.hass)

    def _tx(self, cle: str, **champs: Any) -> str:
        magasin = getattr(self, "_magasin", None)
        return TEXTES.t(cle, self._lg(), magasin() if magasin else None, **champs)

    def _schema_lieu(self, nom_lieu: str, mots: dict[str, str]) -> vol.Schema:
        from homeassistant.data_entry_flow import section

        return vol.Schema({
            vol.Required("lieu", default=nom_lieu): str,
            vol.Required("mots"): section(vol.Schema({
                vol.Optional(m, description={"suggested_value": mots.get(m) or None}): str for m in vocabulaire.MOTS
            }), {"collapsed": not mots}),
        })

    def _lire_lieu(self, saisie: dict[str, Any]) -> tuple[str, dict[str, str]]:
        """(lieu, mots) ; ValueError(mot) si un mot est trop long."""
        from . import lieu

        return lieu.propre(saisie.get("lieu", "")), vocabulaire.preparer(dict(saisie.get("mots") or {}), self._lg())

    @staticmethod
    def _schema_personnes(decideurs: list[str]) -> vol.Schema:
        return vol.Schema({
            vol.Optional("decideurs", default=list(decideurs)): EntitySelector(EntitySelectorConfig(domain="person", multiple=True)),
        })

    @staticmethod
    def _schema_objectifs(groupes: set[str]) -> vol.Schema:
        from . import assistant

        return vol.Schema({
            vol.Optional("objectifs", default=[g for g in assistant.GROUPES if g in groupes]): SelectSelector(SelectSelectorConfig(
                options=list(assistant.GROUPES), multiple=True, translation_key="objectifs", mode=SelectSelectorMode.LIST)),
        })

    def _modules_objectifs(self, actifs: set[str], avant: set[str], saisie: dict[str, Any]) -> tuple[set[str], set[str], str]:
        """(groupes, modules voulus, phrase d'erreur des dépendances — vide si tout va)."""
        from . import assistant, modules

        groupes = {g for g in (saisie.get("objectifs") or []) if g in assistant.GROUPES}
        voulus = assistant.modules_voulus(actifs, avant, groupes)
        manque = modules.manques(voulus, self._lg())
        return groupes, voulus, (" ".join(manque.values()) + "\n\n") if manque else ""

    def _termes(self, mots: dict[str, str]) -> dict[str, str]:
        """Les mots des rôles (choisis, sinon par défaut), avec la majuscule des titres."""
        return {m: vocabulaire._majuscule(v) for m, v in {**vocabulaire.defauts(self._lg()), **mots}.items()}

    def _nom_de(self, personne: str) -> str:
        etat = self.hass.states.get(personne)
        return etat.name if etat is not None else personne

    def _resume_assistant(self, nom_lieu: str, mots: dict[str, str], decideurs: list[str], groupes: set[str],
                          genre: str = "") -> str:
        from . import assistant

        termes = self._termes(mots)
        lignes = [
            self._tx("assistant_resume_lieu", lieu=nom_lieu),
            self._tx("assistant_resume_mots", decideur=termes["decideur"], demandeur=termes["demandeur"]),
            self._tx("assistant_resume_decideurs", decideurs=termes["decideurs"], demandeur=termes["demandeur"],
                     liste=", ".join(sorted(self._nom_de(p) for p in decideurs)) or self._tx("assistant_aucun_decideur")),
            self._tx("assistant_resume_objectifs",
                     liste=", ".join(self._tx(f"objectif_{g}") for g in assistant.GROUPES if g in groupes)
                     or self._tx("assistant_aucun_objectif")),
        ]
        if genre:
            lignes.append(self._tx("assistant_resume_appareil", appareil=self._tx(f"premier_appareil_{genre}")))
        return "\n".join(f"- {ligne}" for ligne in lignes)


class PcParentalConfigFlow(_Assistant, ConfigFlow, domain=DOMAIN):
    """Une seule entrée suffit : elle pilote tous les appareils du lieu.

    Refonte UX lot 4 : l'ajout est un assistant de 5 étapes (assistant.py), « Suivant » jusqu'au résumé."""

    VERSION = 1

    def __init__(self) -> None:
        self._choix: dict[str, Any] = {}

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> "PcParentalOptionsFlow":
        return PcParentalOptionsFlow()

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        """1/5 : le lieu et les mots des rôles."""
        from . import lieu

        await self.async_set_unique_id(DOMAIN)
        self._abort_if_unique_id_configured()
        erreurs: dict[str, str] = {}
        lg = self._lg()
        mots = dict(self._choix.get("mots") or {})
        if user_input is not None:
            try:
                nom_lieu, mots = self._lire_lieu(user_input)
            except ValueError:
                erreurs["base"] = "vocabulaire_trop_long"
                mots = {m: vocabulaire.nettoyer((user_input.get("mots") or {}).get(m)) for m in vocabulaire.MOTS}
            else:
                self._choix.update(lieu="" if nom_lieu == lieu.defaut(lg) else nom_lieu, mots=mots)
                return await self.async_step_personnes()
        return self.async_show_form(
            step_id="user", data_schema=self._schema_lieu(self._choix.get("lieu") or lieu.defaut(lg), mots),
            errors=erreurs, last_step=False,
            description_placeholders={"defauts": ", ".join(vocabulaire.defauts(lg)[m] for m in vocabulaire.MOTS),
                                      "longueur": str(vocabulaire.LONGUEUR_MAX)},
        )

    async def async_step_personnes(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """2/5 : qui décide ? Les autres sont Demandeurs (le rôle par défaut)."""
        if user_input is not None:
            self._choix["decideurs"] = [str(p) for p in (user_input.get("decideurs") or []) if str(p).startswith("person.")]
            return await self.async_step_objectifs()
        termes = self._termes(dict(self._choix.get("mots") or {}))
        return self.async_show_form(
            step_id="personnes", data_schema=self._schema_personnes(self._choix.get("decideurs") or []), last_step=False,
            description_placeholders={"decideurs": termes["decideurs"], "demandeurs": termes["demandeurs"]},
        )

    async def async_step_objectifs(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """3/5 : les objectifs, quatre groupes de modules ; les dépendances dites en clair."""
        from . import assistant

        erreurs: dict[str, str] = {}
        place = {"erreur": ""}
        groupes = set(self._choix.get("groupes", assistant.GROUPES))
        if user_input is not None:
            groupes, voulus, place["erreur"] = self._modules_objectifs(set(), set(), user_input)
            if place["erreur"]:
                erreurs["base"] = "objectifs_dependances"
            else:
                self._choix.update(groupes=sorted(groupes), modules=sorted(voulus))
                return await self.async_step_appareil()
        return self.async_show_form(step_id="objectifs", data_schema=self._schema_objectifs(groupes), errors=erreurs,
                                    last_step=False, description_placeholders=place)

    async def async_step_appareil(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """4/5 : le premier appareil à relier (l'ajout se fait juste après, dans Configurer → Ajouter un appareil)."""
        from . import assistant

        if user_input is not None:
            genre = user_input.get("appareil")
            self._choix["appareil"] = genre if genre in assistant.GENRES else "plus_tard"
            return await self.async_step_pret()
        return self.async_show_form(step_id="appareil", data_schema=vol.Schema({
            vol.Required("appareil", default=self._choix.get("appareil") or "telephone"): SelectSelector(SelectSelectorConfig(
                options=list(assistant.GENRES), translation_key="premier_appareil", mode=SelectSelectorMode.LIST)),
        }), last_step=False)

    async def async_step_pret(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """5/5 : c'est prêt — le résumé ; l'entrée est créée, les choix posés au premier démarrage."""
        from . import dashboard, lieu

        c = self._choix
        if user_input is not None:
            return self.async_create_entry(title=PRODUIT_AFFICHE, data={
                "modules_initiaux": list(c.get("modules") or []),
                "assistant": {"lieu": c.get("lieu", ""), "mots": {"langue": self._lg(), "mots": dict(c.get("mots") or {})},
                              "decideurs": list(c.get("decideurs") or []), "appareil": c.get("appareil", "plus_tard")},
            })
        genre = c.get("appareil", "plus_tard")
        return self.async_show_form(step_id="pret", data_schema=vol.Schema({}), last_step=True, description_placeholders={
            "resume": self._resume_assistant(c.get("lieu") or lieu.defaut(self._lg()), dict(c.get("mots") or {}),
                                             list(c.get("decideurs") or []),
                                             set(c.get("groupes") or []), genre),
            "suite": self._tx(f"assistant_suite_{genre}"),
            "tableau": f"/{dashboard.URL_PATH}",
        })


class PcParentalOptionsFlow(_Assistant, OptionsFlow):
    """La clé d'inscription : lue et changée ici seulement.

    Les options d'une intégration ne s'ouvrent qu'à un administrateur. Avant la
    1.61.0, la clé était une entité, lisible et modifiable par tout compte
    Home Assistant, enfants compris.
    """

    def __init__(self) -> None:
        self._changee = False
        self._qr: dict[str, str] = {}

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        """Le menu (refonte UX lot 4) : relancer l'assistant, ajouter un appareil, réparer ; le reste sous « Autres
        réglages » en attendant le panneau Réglages (lot 5) — aucun réglage n'est perdu."""
        from . import appairage

        attente = sum(1 for d in appairage._demandes(self.hass).values() if d["etat"] == "en_attente")
        return self.async_show_menu(
            step_id="init", menu_options=["assistant", "ajouter", "appairage", "reparer", "autres", "soutien"],
            description_placeholders={"attente": str(attente)},
        )

    async def async_step_reparer(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return self.async_show_menu(step_id="reparer", menu_options=["appareils_relier", "sauvegarde", "installer", "qr", "cle"])

    async def async_step_autres(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Les réglages d'avant, tous, le temps que le panneau Réglages (lot 5) les accueille."""
        return self.async_show_menu(step_id="autres", menu_options=[
            "roles", "securite", "entites", "general", "accueil", "musique", "journal_musique", "appareils_essai"])

    # ------------------------------------------------ Relancer l'assistant (refonte UX lot 4)

    def _assistant_choix(self) -> dict[str, Any]:
        """Les réponses en cours, préremplies avec l'installation telle qu'elle est."""
        if not hasattr(self, "_relance"):
            from . import assistant, lieu, modules

            store = self._coord().store
            self._relance: dict[str, Any] = {
                "lieu": lieu.nom(store, self._lg()), "mots": vocabulaire.reglage(store, self._lg()),
                "decideurs": assistant.decideurs(store), "avant_decideurs": assistant.decideurs(store),
                "groupes": sorted(assistant.groupes_actifs(modules.actifs(store))),
                "avant_groupes": sorted(assistant.groupes_actifs(modules.actifs(store))),
            }
        return self._relance

    async def async_step_assistant(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """1/4 : le lieu et les mots des rôles (le premier appareil se relie par « Ajouter un appareil »)."""
        if self._coord() is None:
            return self.async_abort(reason="not_loaded")
        c = self._assistant_choix()
        erreurs: dict[str, str] = {}
        mots = dict(c["mots"])
        if user_input is not None:
            try:
                nom_lieu, mots = self._lire_lieu(user_input)
            except ValueError:
                erreurs["base"] = "vocabulaire_trop_long"
                mots = {m: vocabulaire.nettoyer((user_input.get("mots") or {}).get(m)) for m in vocabulaire.MOTS}
            else:
                c.update(lieu=nom_lieu or c["lieu"], mots=mots)
                return await self.async_step_assistant_personnes()
        lg = self._lg()
        return self.async_show_form(
            step_id="assistant", data_schema=self._schema_lieu(c["lieu"], mots), errors=erreurs, last_step=False,
            description_placeholders={"defauts": ", ".join(vocabulaire.defauts(lg)[m] for m in vocabulaire.MOTS),
                                      "longueur": str(vocabulaire.LONGUEUR_MAX)},
        )

    async def async_step_assistant_personnes(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """2/4 : qui décide ? Décoché alors qu'il décidait : le rôle par défaut ; les autres ne bougent pas."""
        if self._coord() is None:
            return self.async_abort(reason="not_loaded")
        c = self._assistant_choix()
        if user_input is not None:
            c["decideurs"] = [str(p) for p in (user_input.get("decideurs") or []) if str(p).startswith("person.")]
            return await self.async_step_assistant_objectifs()
        termes = self._termes(c["mots"])
        return self.async_show_form(
            step_id="assistant_personnes", data_schema=self._schema_personnes(c["decideurs"]), last_step=False,
            description_placeholders={"decideurs": termes["decideurs"], "demandeurs": termes["demandeurs"]},
        )

    async def async_step_assistant_objectifs(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """3/4 : les objectifs ; un groupe décoché coupe ses modules, un groupe coché allume ses modules recommandés."""
        from . import modules

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        c = self._assistant_choix()
        erreurs: dict[str, str] = {}
        place = {"erreur": ""}
        groupes = set(c["groupes"])
        if user_input is not None:
            groupes, voulus, place["erreur"] = self._modules_objectifs(
                modules.actifs(coord.store), set(c["avant_groupes"]), user_input)
            if place["erreur"]:
                erreurs["base"] = "objectifs_dependances"
            else:
                c.update(groupes=sorted(groupes), modules=sorted(voulus))
                return await self.async_step_assistant_pret()
        return self.async_show_form(step_id="assistant_objectifs", data_schema=self._schema_objectifs(groupes),
                                    errors=erreurs, last_step=False, description_placeholders=place)

    async def async_step_assistant_pret(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """4/4 : le résumé, puis tout est posé d'un coup (journalisé) ; les protections demandent leur confirmation."""
        from . import assistant, modules

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        c = self._assistant_choix()
        erreurs: dict[str, str] = {}
        place = {"erreur": ""}
        if user_input is not None:
            store, lg, qui = coord.store, self._lg(), await self._qui()
            try:
                roles_changes = assistant.regler_decideurs(self.hass, store, c["decideurs"], c["avant_decideurs"], qui)
            except ValueError as err:
                erreurs["base"] = "role_refuse"
                place["erreur"] = f"{err}\n\n"
            else:
                from . import lieu

                lieu_change = bool(c["lieu"]) and c["lieu"] != lieu.nom(store, lg)
                if lieu_change:
                    store.lieu = c["lieu"]
                mots_changes = vocabulaire.poser(store, lg, dict(c["mots"]))
                if roles_changes or lieu_change or mots_changes:
                    await store.async_save()
                    _LOGGER.warning("%s : assistant relancé — lieu, vocabulaire, Décideurs %s (par %s).",
                                    PRODUIT_AFFICHE, c["decideurs"] or "aucun", qui)
                if roles_changes:
                    await coord.async_ecrire()
                voulus = set(c["modules"]) if "modules" in c else modules.actifs(store)
                if voulus != modules.actifs(store):
                    self._voulus = voulus
                    if "protections" in modules.actifs(store) and "protections" not in voulus and modules.demandeurs(store):
                        return await self.async_step_modules_protections()
                    return await self._modules_enregistrer()
                if lieu_change:
                    self.hass.config_entries.async_schedule_reload(self.config_entry.entry_id)
                return self.async_create_entry(data=dict(self.config_entry.options))
        place["resume"] = self._resume_assistant(c["lieu"], dict(c["mots"]), list(c["decideurs"]), set(c["groupes"]))
        return self.async_show_form(step_id="assistant_pret", data_schema=vol.Schema({}), errors=erreurs, last_step=True,
                                    description_placeholders=place)

    async def async_step_securite(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return self.async_show_menu(step_id="securite", menu_options=["deverrouillage", "inscriptions", "code_gestion", "code_octroi"])

    async def async_step_code_octroi(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """FAILLE (l'auteur, 16/10) : le code de déverrouillage d'un Décideur — le même dans l'appli et le tableau (octroi.py).
        D'ordinaire, le Décideur le choisit dans l'appli ; ici, un administrateur le pose, le change ou le retire
        (code oublié, verrou après trop d'essais faux)."""
        from homeassistant.helpers.selector import SelectOptionDict, TextSelector, TextSelectorConfig, TextSelectorType

        from . import octroi

        coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
        if coord is None:
            return self.async_abort(reason="not_loaded")
        decideurs = sorted(octroi.decideurs(coord.store))
        if not decideurs:
            return self.async_abort(reason="aucun_decideur")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            personne = str(user_input.get("personne") or "")
            action = user_input.get("action", GARDER)
            if personne not in decideurs:
                erreurs["personne"] = "personne_inconnue"
            elif action == "retirer":
                octroi.poser(coord.store, personne, "")
                await coord.store.async_save()
                _LOGGER.warning("Vision : code de déverrouillage de %s retiré depuis les options.", personne)
                return self.async_create_entry(data=dict(self.config_entry.options))
            elif action == "changer":
                code = octroi.nettoyer(user_input.get("code"))
                if not octroi.valide(code):
                    erreurs["code"] = "code_court_deverrouillage"
                elif code != octroi.nettoyer(user_input.get("confirmation")):
                    erreurs["confirmation"] = "code_different"
                else:
                    await self.hass.async_add_executor_job(octroi.poser, coord.store, personne, code)
                    await coord.store.async_save()
                    _LOGGER.warning("Vision : code de déverrouillage de %s changé depuis les options.", personne)
                    return self.async_create_entry(data=dict(self.config_entry.options))
            else:
                return self.async_create_entry(data=dict(self.config_entry.options))
        mot_de_passe = TextSelector(TextSelectorConfig(type=TextSelectorType.PASSWORD))
        options = [SelectOptionDict(value=p, label=self._nom_personne(p)) for p in decideurs]
        return self.async_show_form(
            step_id="code_octroi",
            data_schema=vol.Schema({
                vol.Required("personne", default=decideurs[0]): SelectSelector(SelectSelectorConfig(
                    options=options, mode=SelectSelectorMode.DROPDOWN)),
                vol.Required("action", default=GARDER): SelectSelector(SelectSelectorConfig(
                    options=[GARDER, "changer", "retirer"], translation_key="code_octroi_action", mode=SelectSelectorMode.LIST)),
                vol.Optional("code", default=""): mot_de_passe,
                vol.Optional("confirmation", default=""): mot_de_passe,
            }),
            errors=erreurs,
            description_placeholders={
                "etat": ", ".join(f"{self._nom_personne(p)} : {'✓' if octroi.code_pose(coord.store, p) else '—'}" for p in decideurs),
                "essais": str(octroi.ESSAIS_FAUX_MAX), "minutes": str(octroi.FENETRE_S // 60), "total": str(octroi.ECHECS_LIEU_MAX)},
        )

    async def async_step_code_gestion(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Faille 2.2-1 : le code qui libère un message bloquant sur un PC se règle ici, et nulle part ailleurs."""
        from homeassistant.helpers.selector import TextSelector, TextSelectorConfig, TextSelectorType

        from . import code_gestion

        coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
        if coord is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            action = user_input.get("action", GARDER)
            if action == "retirer":
                code_gestion.poser(coord.store, "")
                await coord.store.async_save()
                _LOGGER.warning("Vision : code de gestion des PC retiré depuis les options.")
                return self.async_create_entry(data=dict(self.config_entry.options))
            if action == "changer":
                code = code_gestion.nettoyer(user_input.get("code"))
                if not code_gestion.valide(code):
                    erreurs["code"] = "code_court"
                elif code != code_gestion.nettoyer(user_input.get("confirmation")):
                    erreurs["confirmation"] = "code_different"
                else:
                    await self.hass.async_add_executor_job(code_gestion.poser, coord.store, code)
                    await coord.store.async_save()
                    _LOGGER.warning("Vision : code de gestion des PC changé depuis les options.")
                    return self.async_create_entry(data=dict(self.config_entry.options))
            else:
                return self.async_create_entry(data=dict(self.config_entry.options))
        mot_de_passe = TextSelector(TextSelectorConfig(type=TextSelectorType.PASSWORD))
        return self.async_show_form(
            step_id="code_gestion",
            data_schema=vol.Schema({
                vol.Required("action", default="changer" if not code_gestion.est_pose(coord.store) else GARDER):
                    SelectSelector(SelectSelectorConfig(options=[GARDER, "changer", "retirer"],
                                                        translation_key="code_gestion_action",
                                                        mode=SelectSelectorMode.LIST)),
                vol.Optional("code", default=""): mot_de_passe,
                vol.Optional("confirmation", default=""): mot_de_passe,
            }),
            errors=erreurs,
            description_placeholders={
                "etat": code_gestion.etat(coord.store),
                "essais": str(code_gestion.ESSAIS_FAUX_MAX),
                "minutes": str(code_gestion.FENETRE_S // 60),
                "total": str(code_gestion.ECHECS_MAISON_MAX),
            },
        )

    async def async_step_modules_protections(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Avertissement explicite avant de couper les protections anti-contournement."""
        from . import modules

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            if user_input.get("je_comprends"):
                return await self._modules_enregistrer()
            erreurs["base"] = "modules_confirmer"
        appareils = modules.demandeurs(coord.store)
        return self.async_show_form(step_id="modules_protections", data_schema=vol.Schema({
            vol.Required("je_comprends", default=False): bool,
        }), errors=erreurs, description_placeholders={
            "nombre": str(len(appareils)), "concernes": self._n("protections_concernes", len(appareils)),
            "appareils": ", ".join(appareils)})

    async def _modules_enregistrer(self) -> "ConfigFlowResult":
        from . import modules

        coord = self._coord()
        modules.regler(self.hass, coord.store, set(self._voulus), await self._qui())
        await coord.store.async_save()
        # Entités des modules coupés : désactivées (historique gardé) ; réactivées au retour.
        self.hass.config_entries.async_schedule_reload(self.config_entry.entry_id)
        return self.async_create_entry(data=dict(self.config_entry.options))

    # ------------------------------------------------------------------ Entités Home Assistant (widgets de l'appli)

    async def async_step_entites(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Qui voit quelles entités, et ce qui se pilote (docs/widgets-ha.md). Lecture seule par défaut."""
        from . import modules, widgets

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        store = coord.store
        lignes = []
        for s_ in sorted(self.hass.states.async_all("person"), key=lambda x: x.name.lower()):
            lignes.append(self._n("entites_ligne_personne", len(store.entites_personnes.get(s_.entity_id) or []),
                                  nom=s_.name))
        for pc_id, exc in store.entites_appareils.items():
            nom = str(store.pcs.get(pc_id, {}).get("name") or pc_id)
            lignes.append(self._t("entites_ligne_appareil", nom=nom, plus=len(exc.get("plus") or []),
                                  moins=len(exc.get("moins") or [])))
        lignes.append(self._n("entites_ligne_actions", sum(1 for v in store.entites_actions.values() if v)))
        etat = "" if modules.actif(store, widgets.MODULE) else self._t("entites_module_coupe") + "\n\n"
        return self.async_show_menu(step_id="entites", menu_options=["entites_personnes", "entites_appareils", "entites_actions"],
                                    description_placeholders={"resume": etat + "\n".join(lignes)})

    async def async_step_entites_personnes(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None and user_input.get("personne"):
            self._personne_entites = user_input["personne"]
            return await self.async_step_entites_personne_liste()
        options = [{"value": s_.entity_id, "label": self._n(
            "entites_option_personne", len(coord.store.entites_personnes.get(s_.entity_id) or []), nom=s_.name)}
            for s_ in sorted(self.hass.states.async_all("person"), key=lambda x: x.name.lower())]
        guide = getattr(self, "_guide_entites", False)
        self._guide_entites = False
        return self.async_show_form(step_id="entites_personnes", data_schema=vol.Schema({
            vol.Required("personne"): SelectSelector(SelectSelectorConfig(options=options, mode=SelectSelectorMode.LIST)),
        }), errors={"base": "entites_rien_a_piloter"} if guide else {}, last_step=False)

    async def async_step_entites_personne_liste(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        from . import widgets

        coord = self._coord()
        personne = getattr(self, "_personne_entites", "")
        if coord is None or not personne:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            if widgets.regler_personne(self.hass, coord.store, personne, user_input.get("entites") or [], await self._qui()):
                await coord.store.async_save()
            return self.async_create_entry(data=dict(self.config_entry.options))
        return self.async_show_form(step_id="entites_personne_liste", data_schema=vol.Schema({
            vol.Optional("entites", default=list(coord.store.entites_personnes.get(personne) or [])): EntitySelector(
                EntitySelectorConfig(multiple=True)),
        }), description_placeholders={"personne": self._nom_personne(personne)})

    async def async_step_entites_appareils(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None and user_input.get("appareil") in coord.store.pcs:
            self._appareil_entites = user_input["appareil"]
            return await self.async_step_entites_appareil_liste()
        appareils = [{"value": i, "label": "{} · {}".format(pc.get("name") or i, self._nom_personne(str(pc.get("person") or "")))}
                     for i, pc in sorted(coord.store.pcs.items(), key=lambda x: str(x[1].get("name") or x[0]).lower())]
        return self.async_show_form(step_id="entites_appareils", data_schema=vol.Schema({
            vol.Required("appareil"): SelectSelector(SelectSelectorConfig(options=appareils, mode=SelectSelectorMode.LIST)),
        }), last_step=False)

    async def async_step_entites_appareil_liste(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        from . import widgets

        coord = self._coord()
        pc = coord.store.pcs.get(getattr(self, "_appareil_entites", "")) if coord is not None else None
        if pc is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            if widgets.regler_appareil(self.hass, coord.store, pc["id"], user_input.get("plus") or [],
                                       user_input.get("moins") or [], await self._qui()):
                await coord.store.async_save()
            return self.async_create_entry(data=dict(self.config_entry.options))
        exc = coord.store.entites_appareils.get(pc["id"]) or {}
        personne = str(pc.get("person") or "")
        siennes = (coord.store.entites_personnes.get(personne) or []) if personne else []
        return self.async_show_form(step_id="entites_appareil_liste", data_schema=vol.Schema({
            vol.Optional("plus", default=list(exc.get("plus") or [])): EntitySelector(EntitySelectorConfig(multiple=True)),
            vol.Optional("moins", default=list(exc.get("moins") or [])): EntitySelector(EntitySelectorConfig(multiple=True)),
        }), description_placeholders={
            "appareil": str(pc.get("name") or pc["id"]),
            "personne": self._nom_personne(personne) if personne else self._t("compte_personnes_zero"),
            "liste": ", ".join(siennes) or self._t("aucune")})

    async def async_step_entites_actions(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Pour chaque entité donnée à quelqu'un et pilotable : les services permis (rien coché = lecture seule).

        Serrures et alarmes ne sont jamais proposées (widgets.INTERDITS)."""
        from . import widgets

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        pilotables = [e for e in widgets.toutes_choisies(coord.store) if widgets.pilotable(e)]
        if not pilotables:
            # Lot 6 (HA-25) : au lieu d'un abandon, l'écran de l'étape qui manque, avec la raison en tête.
            self._guide_entites = True
            return await self.async_step_entites_personnes()
        if user_input is not None:
            voulues = {e: list(user_input.get(e) or []) for e in pilotables}
            if widgets.regler_actions(self.hass, coord.store, voulues, await self._qui()):
                await coord.store.async_save()
            return self.async_create_entry(data=dict(self.config_entry.options))
        schema = {}
        for e in pilotables:
            etat = self.hass.states.get(e)
            nom = str(etat.attributes.get("friendly_name") or e) if etat is not None else e
            schema[vol.Optional(e, default=list(coord.store.entites_actions.get(e) or []))] = SelectSelector(
                SelectSelectorConfig(options=[{"value": s_, "label": f"{nom} : {s_}"} for s_ in widgets.services_proposes(e)],
                                     multiple=True, mode=SelectSelectorMode.LIST))
        return self.async_show_form(step_id="entites_actions", data_schema=vol.Schema(schema),
                                    description_placeholders={"nombre": str(len(pilotables)),
                                                              "pilotables": self._n("entites_pilotables", len(pilotables))})

    async def async_step_journal_musique(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """2.2.11 (retour d'essai) : les dernières commandes Musique (qui, quoi, d'où), et combien de jours on les garde."""
        from . import journal_musique

        if user_input is not None:
            journal_musique.poser_jours(self.hass, int(user_input.get("jours") or journal_musique.JOURS_DEFAUT))
            return self.async_create_entry(data=dict(self.config_entry.options))
        from homeassistant.util import dt as dt_util
        lg = langues.langue_hass(self.hass)
        l = journal_musique.lignes(self.hass, 40)
        texte = "\n".join("- " + dt_util.as_local(dt_util.utc_from_timestamp(float(d["ts"]))).strftime("%d/%m %H:%M")
                           + " — " + journal_musique.phrase(d, lg) for d in l) or self._t("journal_musique_vide")
        from homeassistant.helpers import selector
        return self.async_show_form(step_id="journal_musique", data_schema=vol.Schema({
            vol.Required("jours", default=str(journal_musique.jours(self.hass))): selector.SelectSelector(selector.SelectSelectorConfig(
                options=[str(j) for j in journal_musique.JOURS_POSSIBLES], translation_key="jours_journal_musique",
                mode=selector.SelectSelectorMode.LIST)),
        }), description_placeholders={"journal": texte})

    async def async_step_musique(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """2.2.5 (retour d'essai) : les lecteurs Music Assistant proposés comme enceintes dans Musique (tous par défaut)."""
        from homeassistant.helpers import selector

        from . import musique, musique_arret

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        tous = sorted(musique.lecteurs_ma(self.hass))
        noms = {e: str((self.hass.states.get(e).attributes.get("friendly_name") if self.hass.states.get(e) else None) or e) for e in tous}
        m = coord.store.musique
        if user_input is not None:
            choisis = [e for e in user_input.get("enceintes", []) if e in tous]
            # Tout coché = « tous », y compris les lecteurs ajoutés plus tard à Music Assistant.
            m["enceintes"] = None if set(choisis) == set(tous) else choisis
            m["masquer_hors_ligne"] = bool(user_input.get("masquer_hors_ligne"))
            # 2.4.0 (retour d'essai) : arrêt automatique après inactivité ; « perso » = la durée saisie (minutes).
            choix = str(user_input.get("arret_auto") or str(musique_arret.DEFAUT_MIN))
            if choix == "perso":
                m["arret_auto_min"] = max(musique_arret.ARRET_PERSO_MIN, min(musique_arret.ARRET_PERSO_MAX,
                                                                              int(user_input.get("arret_auto_perso") or musique_arret.DEFAUT_MIN)))
            else:
                m["arret_auto_min"] = int(choix)
            _LOGGER.warning("%s : enceintes de Musique → %s (par %s).", PRODUIT_AFFICHE,
                            "toutes" if m["enceintes"] is None else ", ".join(m["enceintes"]) or "aucune", await self._qui())
            await coord.store.async_save()
            return self.async_create_entry(data=dict(self.config_entry.options))
        coches = tous if m.get("enceintes") is None else [e for e in m["enceintes"] if e in tous]
        actuel = musique_arret.delai_min(coord.store)
        choix_arret = [str(v) for v in musique_arret.CHOIX_MIN] + ["perso"]
        options = [selector.SelectOptionDict(value=e, label=noms[e]) for e in sorted(tous, key=lambda x: noms[x].lower())]
        return self.async_show_form(step_id="musique", data_schema=vol.Schema({
            vol.Optional("enceintes", default=coches): selector.SelectSelector(selector.SelectSelectorConfig(
                options=options, multiple=True, mode=selector.SelectSelectorMode.LIST)),
            vol.Optional("masquer_hors_ligne", default=bool(m.get("masquer_hors_ligne"))): bool,
            vol.Optional("arret_auto", default=str(actuel) if actuel in musique_arret.CHOIX_MIN else "perso"):
                selector.SelectSelector(selector.SelectSelectorConfig(
                    options=choix_arret, mode=selector.SelectSelectorMode.DROPDOWN, translation_key="arret_auto")),
            vol.Optional("arret_auto_perso", default=actuel or musique_arret.DEFAUT_MIN): selector.NumberSelector(
                selector.NumberSelectorConfig(min=musique_arret.ARRET_PERSO_MIN, max=musique_arret.ARRET_PERSO_MAX, step=5,
                                              unit_of_measurement="min", mode=selector.NumberSelectorMode.BOX)),
        }))

    async def async_step_general(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Réglages généraux : les personnes qui tiennent lieu d'écran commun, les téléphones notifiés. Le nom du lieu
        est dans l'assistant et dans Réglages › Réservé (lot 5) ; les appareils Android d'essai sous Avancé (EX-06)."""
        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            # 2.5.6 : le salon Discord du lieu (chaque Décideur le coche ou non dans Réglages › Mes notifications).
            discord = {"service": str(user_input.get("discord_service") or "").removeprefix("notify."),
                       "cible": str(user_input.get("discord_cible") or "").strip()}
            discord = {k: v for k, v in discord.items() if v}
            if discord != (coord.store.discord or {}):
                coord.store.discord = discord
                _LOGGER.warning("%s : salon Discord → %s (par %s).", PRODUIT_AFFICHE, discord.get("service") or "aucun",
                                await self._qui())
                await coord.store.async_save()
            affichage = sorted({str(p) for p in (user_input.get("personnes_affichage") or []) if p})
            if affichage != sorted(coord.store.personnes_affichage):
                coord.store.personnes_affichage = affichage
                _LOGGER.warning("%s : réglages généraux — écrans communs %s (par %s).",
                                PRODUIT_AFFICHE, affichage or "automatique", await self._qui())
                await coord.store.async_save()
            return self.async_create_entry(data=dict(self.config_entry.options))
        schema: dict[Any, Any] = {
            vol.Optional("personnes_affichage", default=list(coord.store.personnes_affichage)): EntitySelector(
                EntitySelectorConfig(domain="person", multiple=True)),
        }
        # 2.5.6 (l'auteur, 15/10) : les téléphones se choisissent par Décideur (Réglages › Mes notifications, ou l'appli
        # Vision) ; reste ici le salon Discord du lieu : un service notify (intégration Discord) et l'id du salon.
        from homeassistant.helpers import selector as _sel
        exclus = {"persistent_notification", "send_message", "notify"}
        services = sorted(s for s in (self.hass.services.async_services().get("notify") or {})
                          if not s.startswith("mobile_app_") and s not in exclus)
        actuel = str((coord.store.discord or {}).get("service") or "")
        options = sorted(set(services) | ({actuel} if actuel else set()))
        if options:
            schema[vol.Optional("discord_service", description={"suggested_value": actuel or None})] = _sel.SelectSelector(
                _sel.SelectSelectorConfig(options=options, mode=_sel.SelectSelectorMode.DROPDOWN, custom_value=False))
            schema[vol.Optional("discord_cible", description={"suggested_value": (coord.store.discord or {}).get("cible") or None})] = (
                _sel.TextSelector(_sel.TextSelectorConfig()))
        return self.async_show_form(step_id="general", data_schema=vol.Schema(schema))

    async def async_step_appareils_essai(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Avancé → Appareils Android d'essai (EX-06) : ils reçoivent l'APK d'essai avant les autres.

        Même réglage qu'avant (store.appareils_essai), sorti de Réglages généraux : c'est un outil de test."""
        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            essai = sorted({str(p) for p in (user_input.get("appareils_essai") or []) if p in coord.store.pcs})
            if essai != sorted(coord.store.appareils_essai):
                coord.store.appareils_essai = essai
                _LOGGER.warning("%s : appareils d'essai %s (par %s).", PRODUIT_AFFICHE, essai or "aucun", await self._qui())
                await coord.store.async_save()
            return self.async_create_entry(data=dict(self.config_entry.options))
        androids = [{"value": pid, "label": str(pc.get("name") or pid)}
                    for pid, pc in sorted(coord.store.pcs.items(), key=lambda x: str(x[1].get("name") or x[0]).lower())
                    if pc.get("platform") == "android"]
        if not androids:
            return self.async_abort(reason="essai_aucun_android")
        return self.async_show_form(step_id="appareils_essai", data_schema=vol.Schema({
            vol.Optional("appareils_essai", default=[p for p in coord.store.appareils_essai if p in coord.store.pcs]):
                SelectSelector(SelectSelectorConfig(options=androids, multiple=True, mode=SelectSelectorMode.LIST)),
        }))

    async def async_step_accueil(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Les messages d'accueil de l'appli (accueil.py) : messages, mode, météo, modèle Jinja pour les experts.

        L'aide liste les variables avec leur valeur du moment ; l'aperçu montre ce qui est enregistré."""
        from homeassistant.data_entry_flow import section
        from homeassistant.helpers.selector import TextSelector, TextSelectorConfig

        from . import accueil

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        place = {"erreur": ""}
        actuel = accueil.reglages(coord.store)
        if user_input is not None:
            avance = user_input.get("avance") or {}
            saisie = accueil.propre({**user_input, "modele": avance.get("modele", "")})
            if probleme := accueil.valider_modele(self.hass, saisie["modele"]):
                erreurs["base"] = "accueil_modele_invalide"
                place["erreur"] = f"`{probleme}`\n\n"
                actuel = saisie
            else:
                if saisie != actuel:
                    coord.store.accueil = saisie
                    await coord.store.async_save()
                    _LOGGER.warning("%s : messages d'accueil réglés — %s ligne(s), mode %s, météo %s, modèle %s (par %s).",
                                    PRODUIT_AFFICHE, len(accueil.lignes(saisie["messages"])), saisie["mode"],
                                    saisie["meteo"] or "automatique", "oui" if saisie["modele"] else "non",
                                    await self._qui())
                return self.async_abort(reason="accueil_ok", description_placeholders={"apercu": self._apercu_accueil(coord)})
        place.update({"aide": self._aide_accueil(coord), "apercu": self._apercu_accueil(coord),
                      "exemple": accueil.EXEMPLE_MODELE})
        return self.async_show_form(
            step_id="accueil",
            data_schema=vol.Schema({
                vol.Optional("messages", description={"suggested_value": actuel["messages"]}): TextSelector(
                    TextSelectorConfig(multiline=True)),
                vol.Required("mode", default=actuel["mode"]): SelectSelector(SelectSelectorConfig(
                    options=list(accueil.MODES), translation_key="accueil_mode", mode=SelectSelectorMode.LIST)),
                vol.Optional("meteo", description={"suggested_value": actuel["meteo"] or None}): EntitySelector(
                    EntitySelectorConfig(domain="weather")),
                vol.Required("avance"): section(vol.Schema({
                    vol.Optional("modele", description={"suggested_value": actuel["modele"]}): TextSelector(
                        TextSelectorConfig(multiline=True)),
                }), {"collapsed": not actuel["modele"]}),
            }),
            errors=erreurs,
            description_placeholders=place,
        )

    def _aide_accueil(self, coord) -> str:
        """Les variables, une ligne chacune : ce qu'elle donne, et sa valeur d'exemple en ce moment."""
        from homeassistant.util import dt as dt_util

        from . import accueil

        lg = langues.langue_hass(self.hass)
        valeurs = accueil.variables(self.hass, coord.store, lg, dt_util.now(), self._t("accueil_prenom_exemple"))
        return "\n".join(f"- `{{{nom}}}` : {self._t('accueil_var_' + nom)} — {valeurs[nom] or self._t('accueil_vide')}"
                         for nom in valeurs)

    def _apercu_accueil(self, coord) -> str:
        """Le rendu de ce qui est enregistré, dans la langue de Home Assistant : sans prénom, puis avec un prénom."""
        from . import accueil

        lg = langues.langue_hass(self.hass)
        exemple = self._t("accueil_prenom_exemple")
        return "\n".join((
            self._t("accueil_apercu_sans", message=accueil.rendre(self.hass, coord.store, lg, "apercu")),
            self._t("accueil_apercu_avec", prenom=exemple,
                    message=accueil.rendre(self.hass, coord.store, lg, "apercu", exemple)),
        ))

    async def async_step_soutien(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Soutenir le projet (docs/soutien.md) : un badge décoratif, rien ne se débloque."""
        from . import soutien

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if soutien.actif(coord.store):
            # Une description à part (traduite) quand le badge est déjà là.
            return self.async_show_menu(step_id="soutien_actif", menu_options=["soutien_code", "soutien_retirer"],
                                        description_placeholders={"lien": soutien.LIEN_DON})
        # Retours de l'auteur (15/10) : plus de « café sur l'honneur » (un code est maintenant envoyé à chaque café) : la page
        # de soutien demande directement le code reçu par e-mail.
        return await self._soutien_formulaire("soutien", user_input, {"lien": soutien.LIEN_DON})

    # HA refuse d'afficher une étape sans méthode du même nom (UnknownStep, erreur 500) : le menu
    # « badge déjà là » a donc la sienne, qui revient au même choix.
    async_step_soutien_actif = async_step_soutien

    async def async_step_soutien_code(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """« J'ai un code » (badge déjà là : un autre code) : vérifié ici, hors ligne, avec la clé publique embarquée."""
        if self._coord() is None:
            return self.async_abort(reason="not_loaded")
        return await self._soutien_formulaire("soutien_code", user_input)

    async def _soutien_formulaire(self, etape: str, user_input: dict[str, Any] | None,
                                  champs: dict[str, str] | None = None) -> "ConfigFlowResult":
        import time

        from . import soutien

        coord = self._coord()
        erreurs: dict[str, str] = {}
        if user_input is not None:
            r = soutien.activer_code(coord.store, str(user_input.get("code") or ""), time.time())
            if r.ok:
                await coord.store.async_save()
                _LOGGER.warning("%s : badge Soutien activé par un code (émis le %s) par %s.", PRODUIT_AFFICHE, r.emis_le, await self._qui())
                return self.async_abort(reason="soutien_merci")
            erreurs["code"] = "soutien_" + r.erreur
        return self.async_show_form(step_id=etape, data_schema=vol.Schema({
            vol.Required("code", default=""): TextSelector(TextSelectorConfig(multiline=True)),
        }), errors=erreurs, description_placeholders=champs)

    async def async_step_soutien_retirer(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        from . import soutien

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        soutien.retirer(coord.store)
        await coord.store.async_save()
        _LOGGER.warning("%s : badge Soutien retiré par %s.", PRODUIT_AFFICHE, await self._qui())
        return self.async_abort(reason="soutien_retire")

    # ------------------------------------------------ 1.87.0 : sauvegarde / restauration (point 14.3)

    async def _liste_sauvegardes(self) -> str:
        from . import sauvegarde

        liste = await sauvegarde.async_lister(self.hass)
        if not liste:
            return self._t("aucune_sauvegarde")
        lignes = [f"- {sauvegarde.libelle(e, langues.langue_hass(self.hass))}" for e in liste[:8]]
        if len(liste) > 8:
            lignes.append(self._n("sauvegardes_plus_anciennes", len(liste) - 8))
        return "\n".join(lignes)

    async def async_step_sauvegarde(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return self.async_show_menu(step_id="sauvegarde", menu_options=["sauvegarde_creer", "sauvegarde_restaurer"],
                                    description_placeholders={"liste": await self._liste_sauvegardes()})

    async def async_step_sauvegarde_creer(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        from . import sauvegarde

        coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is None:
            return self.async_show_form(step_id="sauvegarde_creer", data_schema=vol.Schema({}),
                                        description_placeholders={"liste": await self._liste_sauvegardes()})
        entree = await sauvegarde.async_sauver(self.hass, coord, "manuelle")
        return self.async_abort(reason="sauvegarde_faite", description_placeholders={
            "sauvegarde": sauvegarde.libelle(entree, langues.langue_hass(self.hass)), "dossier": sauvegarde.DOSSIER})

    async def async_step_sauvegarde_restaurer(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        from homeassistant.helpers.selector import BooleanSelector, SelectOptionDict

        from . import sauvegarde

        liste = await sauvegarde.async_lister(self.hass)
        if not liste:
            return self.async_abort(reason="aucune_sauvegarde")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            nom = str(user_input.get("sauvegarde") or "")
            if nom not in {e["nom"] for e in liste}:
                erreurs["sauvegarde"] = "sauvegarde_introuvable"
            elif not user_input.get("confirmer"):
                erreurs["confirmer"] = "confirmer_restauration"
            else:
                try:
                    avant = await sauvegarde.async_restaurer(self.hass, self.config_entry.entry_id, nom)
                except (ValueError, OSError):
                    erreurs["sauvegarde"] = "sauvegarde_illisible"
                else:
                    choisie = next(e for e in liste if e["nom"] == nom)
                    return self.async_abort(reason="sauvegarde_restauree", description_placeholders={
                        "sauvegarde": sauvegarde.libelle(choisie, langues.langue_hass(self.hass)),
                        "avant": sauvegarde.libelle(avant, langues.langue_hass(self.hass))})
        return self.async_show_form(
            step_id="sauvegarde_restaurer",
            data_schema=vol.Schema({
                vol.Required("sauvegarde", default=liste[0]["nom"]): SelectSelector(SelectSelectorConfig(
                    options=[SelectOptionDict(value=e["nom"], label=sauvegarde.libelle(e, langues.langue_hass(self.hass))) for e in liste],
                    mode=SelectSelectorMode.LIST)),
                vol.Required("confirmer", default=False): BooleanSelector(),
            }),
            errors=erreurs,
        )

    # ------------------------------------------------ Ajouter un appareil (assistant, une question par écran)

    def _ajout(self) -> dict[str, Any]:
        if not hasattr(self, "_aj"):
            self._aj: dict[str, Any] = {}
        return self._aj

    @staticmethod
    def _genre(pc: dict[str, Any]) -> str:
        """telephone | tele | pc : le genre de l'assistant pour une fiche existante."""
        if str(pc.get("platform") or "") != "android":
            return "pc"
        return "tele" if str(pc.get("type") or "") == "tv" else "telephone"

    def _nom_personne(self, personne: str) -> str:
        if not personne:
            return self._t("ecran_partage")
        etat = self.hass.states.get(personne)
        return etat.name if etat is not None else personne

    def _genre_propose(self) -> str:
        """Le premier appareil choisi dans l'assistant d'ajout (lot 4), tant qu'aucun appareil n'est relié."""
        choix = str(((self.config_entry.data or {}).get("assistant") or {}).get("appareil") or "")
        coord = self._coord()
        if choix in ("telephone", "tele", "pc") and coord is not None and not coord.store.pcs:
            return choix
        return "telephone"

    async def async_step_ajouter(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """1/4 : quel appareil ?"""
        if self._coord() is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            self._aj = {"genre": user_input["genre"]}
            return await self.async_step_ajouter_qui()
        return self.async_show_form(step_id="ajouter", data_schema=vol.Schema({
            vol.Required("genre", default=self._genre_propose()): SelectSelector(SelectSelectorConfig(
                options=["telephone", "tele", "pc"], translation_key="genre", mode=SelectSelectorMode.LIST)),
        }), last_step=False)

    async def async_step_ajouter_qui(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """2/4 : pour qui ? (le rôle suit la personne)"""
        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        aj = self._ajout()
        if user_input is not None:
            aj["personne"] = "" if user_input.get("personne") in (None, "", "partage") else user_input["personne"]
            # Reprise proposée d'office : un appareil du même genre, à la même personne, existe déjà.
            aj["candidats"] = [i for i, pc in coord.store.pcs.items()
                               if self._genre(pc) == aj["genre"] and str(pc.get("person") or "") == aj["personne"]]
            if aj["candidats"]:
                return await self.async_step_ajouter_reprise()
            aj["reprise"] = ""
            return await self.async_step_ajouter_installer()
        from . import roles

        # Le rôle effectif que l'appareil aura (celui de la personne, sinon le rôle par défaut).
        options = [{"value": s.entity_id, "label": self._t(
                        "option_personne_role", personne=s.name, role=roles.nom(coord.store, {"person": s.entity_id}))}
                   for s in sorted(self.hass.states.async_all("person"), key=lambda s: s.name.lower())]
        options.append({"value": "partage", "label": self._t("option_ecran_partage")})
        defaut = "partage" if aj.get("genre") == "tele" else (options[0]["value"] if options else "partage")
        return self.async_show_form(step_id="ajouter_qui", data_schema=vol.Schema({
            vol.Required("personne", default=defaut): SelectSelector(SelectSelectorConfig(options=options, mode=SelectSelectorMode.LIST)),
        }), last_step=False)

    async def async_step_ajouter_reprise(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Un appareil semblable existe : nouveau, ou le même réinstallé ?"""
        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        aj = self._ajout()
        if user_input is not None:
            choix = user_input.get("reprise") or "nouveau"
            aj["reprise"] = "" if choix == "nouveau" else choix
            return await self.async_step_ajouter_installer()
        options = [{"value": i, "label": self._t("reprise_meme", nom=coord.store.pcs[i].get("name") or i)}
                   for i in aj.get("candidats", []) if i in coord.store.pcs]
        options.insert(0, {"value": "nouveau", "label": self._t("reprise_nouveau")})
        return self.async_show_form(step_id="ajouter_reprise", data_schema=vol.Schema({
            vol.Required("reprise", default="nouveau"): SelectSelector(SelectSelectorConfig(options=options, mode=SelectSelectorMode.LIST)),
        }), description_placeholders={"personne": self._nom_personne(aj.get("personne", ""))}, last_step=False)

    async def async_step_appareils_relier(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Appareils existants → relier à nouveau (appli réinstallée, PC remis à neuf)."""
        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None and user_input.get("appareil") in coord.store.pcs:
            pc = coord.store.pcs[user_input["appareil"]]
            self._aj = {"genre": self._genre(pc), "personne": str(pc.get("person") or ""), "reprise": user_input["appareil"]}
            return await self.async_step_ajouter_installer()
        appareils = [{"value": i, "label": str(pc.get("name") or i)} for i, pc in
                     sorted(coord.store.pcs.items(), key=lambda x: str(x[1].get("name") or x[0]).lower())]
        return self.async_show_form(step_id="appareils_relier", data_schema=vol.Schema({
            vol.Required("appareil"): SelectSelector(SelectSelectorConfig(options=appareils)),
        }), last_step=False)

    def _inviter(self) -> None:
        """Un QR / jeton d'appairage pour cet ajout (personne, reprise) ; le même tant qu'il vit."""
        from homeassistant.helpers.http import current_request

        from . import appairage

        aj = self._ajout()
        if aj.get("invitation") and appairage.resultat(self.hass, aj["invitation"]) is not None:
            return
        inv = appairage.creer_invitation(self.hass, *appairage.adresses(self.hass, current_request.get()),
                                         aj.get("personne", ""), aj.get("reprise", ""))
        aj["invitation"], aj["jeton"] = inv["id"], inv["jeton"]

    def _qui_resume(self) -> str:
        from . import roles

        aj = self._ajout()
        coord = self._coord()
        personne = self._nom_personne(aj.get("personne", ""))
        if coord is None:
            return self._t("qui_pour", personne=personne)
        r = coord.store.roles.get(str(coord.store.roles_personnes.get(aj.get("personne", ""), "")))
        role = r["nom"] if r else roles.nom(coord.store, {})
        if aj.get("reprise") in coord.store.pcs:
            return self._t("qui_pour_role_reprise", personne=personne, role=role,
                           appareil=coord.store.pcs[aj["reprise"]].get("name"))
        return self._t("qui_pour_role", personne=personne, role=role)

    async def async_step_ajouter_installer(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """3/4 : installer l'appli (téléphone : QR de l'APK ; télé : adresse courte ; PC : une commande)."""
        from homeassistant.helpers.selector import TextSelector, TextSelectorConfig

        from . import installation

        if self._coord() is None:
            return self.async_abort(reason="not_loaded")
        aj = self._ajout()
        if user_input is not None:
            return await self.async_step_ajouter_relier()
        l = installation.liens(self.hass)
        place = {"qui": aj.pop("note", "") + self._qui_resume(), "lien": l["android"], "qr": l["qr"],
                 "court": l["court"], "version": l["android_version"], "minutes": "5"}
        schema: dict = {}
        if aj["genre"] == "pc":
            try:
                self._inviter()
            except ValueError as err:
                return self.async_abort(reason="ajout_impossible", description_placeholders={"raison": str(err)})
            # Un champ pour copier la commande d'un geste (appui long, ou Ctrl+A puis Ctrl+C) ; sa valeur est ignorée.
            schema[vol.Optional("commande", default=installation.commande_windows(self.hass, aj["jeton"]))] = \
                TextSelector(TextSelectorConfig(multiline=True))
        return self.async_show_form(step_id=f"ajouter_installer_{aj['genre']}", data_schema=vol.Schema(schema),
                                    errors=aj.pop("erreurs", None), description_placeholders=place, last_step=False)

    async def async_step_ajouter_installer_telephone(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return await self.async_step_ajouter_installer(user_input)

    async def async_step_ajouter_installer_tele(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return await self.async_step_ajouter_installer(user_input)

    async def async_step_ajouter_installer_pc(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return await self.async_step_ajouter_installer(user_input)

    async def async_step_ajouter_relier(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """4/4 : relier (téléphone : il scanne un QR ; télé : son code ; PC : déjà fait par la commande)."""
        from datetime import timedelta

        from homeassistant.components.http.auth import async_sign_path

        from . import appairage

        coord = self._coord()
        aj = self._ajout()
        erreurs: dict[str, str] = {}
        place = {"qui": self._qui_resume(), "image": "", "minutes": str(appairage.APPAIRAGE_MINUTES)}
        if aj["genre"] == "tele":
            if user_input is not None and user_input.get("code"):
                try:
                    r = await appairage.valider(self.hass, user_input["code"], aj.get("personne", ""))
                except ValueError:
                    erreurs["code"] = "code_inconnu"
                else:
                    aj["relie"] = r
                    return await self.async_step_ajouter_fini()
            return self.async_show_form(step_id="ajouter_relier_tele", data_schema=vol.Schema({vol.Required("code"): str}),
                                        errors=erreurs, description_placeholders=place, last_step=False)
        # Téléphone et PC : on suit le jeton.
        note = ""
        if user_input is not None and aj.get("invitation"):
            r = appairage.resultat(self.hass, aj["invitation"])
            if r and r["etat"] == "relie":
                aj["relie"] = r
                return await self.async_step_ajouter_fini()
            if r and r["etat"] == "deja_inscrit" and coord is not None and r["existant"] in coord.store.pcs:
                # Reprise proposée d'office : un nouveau QR (ou une nouvelle commande) qui reprend cette fiche.
                aj["reprise"], aj["invitation"] = r["existant"], None
                note = self._t("deja_inscrit_qr" if aj["genre"] == "telephone" else "deja_inscrit_lien",
                               nom=coord.store.pcs[r["existant"]].get("name")) + "\n\n"
                if aj["genre"] == "pc":
                    aj["note"] = note
                    return await self.async_step_ajouter_installer()
            elif r is None:
                aj["invitation"] = None
                if aj["genre"] == "pc":
                    aj["erreurs"] = {"base": "ajout_expire"}
                    return await self.async_step_ajouter_installer()
                erreurs["base"] = "ajout_expire"
            else:
                erreurs["base"] = "ajout_pas_encore"
        if aj["genre"] == "pc":
            return self.async_show_form(step_id="ajouter_relier_pc", data_schema=vol.Schema({}), errors=erreurs,
                                        description_placeholders=place, last_step=False)
        try:
            self._inviter()
        except ValueError as err:
            return self.async_abort(reason="ajout_impossible", description_placeholders={"raison": str(err)})
        place["image"] = async_sign_path(self.hass, appairage.chemin_qr(aj["invitation"]),
                                         timedelta(minutes=appairage.APPAIRAGE_MINUTES))
        place["qui"] = note + place["qui"]
        return self.async_show_form(step_id="ajouter_relier_telephone", data_schema=vol.Schema({}), errors=erreurs,
                                    description_placeholders=place, last_step=False)

    async def async_step_ajouter_relier_tele(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return await self.async_step_ajouter_relier(user_input)

    async def async_step_ajouter_relier_telephone(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return await self.async_step_ajouter_relier(user_input)

    async def async_step_ajouter_relier_pc(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        return await self.async_step_ajouter_relier(user_input)

    async def async_step_ajouter_fini(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """✅ {Nom} est relié à {Personne}, avec l'état en direct."""
        from . import roles

        if user_input is not None:
            return self.async_create_entry(data=dict(self.config_entry.options))
        aj = self._ajout()
        r = aj.get("relie") or {}
        coord = self._coord()
        pc = coord.store.pcs.get(r.get("id", "")) if coord is not None else None
        if pc is None:
            etat = self._t("fini_rechargement")
        elif coord.agent_online(r["id"]):
            etat = self._t("fini_en_ligne")
        else:
            etat = self._t("fini_relie")
        return self.async_show_form(step_id="ajouter_fini", data_schema=vol.Schema({}), description_placeholders={
            "nom": str((pc or {}).get("name") or r.get("name") or self._t("l_appareil")),
            "personne": self._nom_personne(str((pc or {}).get("person") or aj.get("personne", ""))),
            "role": roles.nom(coord.store, pc) if pc is not None else "?",
            "reprise": "" if r.get("created", True) else " " + self._t("fini_reprise"),
            "etat": etat,
        })

    async def async_step_deverrouillage(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        """Les demandes de déverrouillage des enfants : approuver (directe, code, QR) ou refuser."""
        from homeassistant.components.http.auth import async_sign_path
        from datetime import timedelta

        from . import deverrouillage, deverrouillage_ecran

        coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
        if coord is None or deverrouillage.cle(self.hass) is None:
            return self.async_abort(reason="not_loaded")
        en_cours = [deverrouillage.public(self.hass, coord, d) for d in deverrouillage._demandes(self.hass).values()
                    if d["etat"] == "en_attente"]
        erreurs: dict[str, str] = {}
        if user_input is not None and en_cours:
            par = self._t("par_administrateur")
            if self.context.get("user_id"):
                u = await self.hass.auth.async_get_user(self.context["user_id"])
                if u is not None:
                    par = u.name or par
            try:
                r = deverrouillage.decider(
                    self.hass, coord, user_input.get("demande"), user_input.get("decision", ""),
                    user_input.get("minutes"), user_input.get("remise", "directe"), par,
                    voix=deverrouillage.votant_admin(self.hass, self.context.get("user_id")),
                )
            except deverrouillage.Refus as refus:
                erreurs["base"] = f"deverrouillage_{refus.erreur}"
            else:
                await coord.async_refresh()
                if r.get("etat") == "refusee":
                    texte = self._t("resultat_refusee")
                elif r.get("etat") == "partielle":
                    texte = self._t("resultat_partielle", voix=r["voix"], requis=r["requis"],
                                    attente=", ".join(r["en_attente_de"]) or self._t("une_autre_personne"))
                elif r.get("remise") == "code":
                    c = str(r["code"])
                    texte = self._t("resultat_code", code=f"{c[:3]} {c[3:]}", minutes=r["minutes"])
                elif r.get("remise") == "qr":
                    chemin = deverrouillage_ecran.deposer_qr(self.hass, r["qr"], 90)
                    image = async_sign_path(self.hass, chemin, timedelta(seconds=90))
                    texte = self._t("resultat_qr", image=image, minutes=r["minutes"])
                else:
                    texte = self._t("resultat_directe", minutes=r["minutes"])
                self._resultat = texte
                return await self.async_step_deverrouillage_resultat()
        if not en_cours:
            return self.async_show_form(step_id="deverrouillage_resultat", data_schema=vol.Schema({}),
                                        description_placeholders={"resultat": self._t("aucune_demande_deverrouillage")})
        demandes = [{"value": d["demande"], "label": deverrouillage_ecran.libelle_demande(d, langues.langue_hass(self.hass))} for d in en_cours]
        return self.async_show_form(
            step_id="deverrouillage",
            data_schema=vol.Schema({
                vol.Required("demande", default=demandes[0]["value"]): SelectSelector(SelectSelectorConfig(options=demandes)),
                vol.Required("decision", default="approuver"): SelectSelector(SelectSelectorConfig(
                    options=["approuver", "refuser"], translation_key="decision_deverrouillage", mode=SelectSelectorMode.LIST)),
                vol.Optional("minutes"): vol.All(vol.Coerce(int), vol.Range(min=1, max=240)),
                vol.Required("remise", default="directe"): SelectSelector(SelectSelectorConfig(
                    options=["directe", "code", "qr"], translation_key="remise", mode=SelectSelectorMode.LIST)),
            }),
            errors=erreurs,
            # « nombre » reste fourni (lu par les essais et par qui s'en servait) ; l'écran affiche « demandes ».
            description_placeholders={"nombre": str(len(en_cours)), "demandes": self._n("demandes_en_attente", len(en_cours))},
        )

    # ------------------------------------------------------------------ Rôles (1.71.0)

    async def _qui(self) -> str:
        if self.context.get("user_id"):
            u = await self.hass.auth.async_get_user(self.context["user_id"])
            if u is not None and u.name:
                return u.name
        return self._t("qui_administrateur")

    def _coord(self):
        return self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)

    def _magasin(self):
        coord = self._coord()
        return getattr(coord, "store", None)

    def _t(self, cle: str, **champs: Any) -> str:
        return TEXTES.t(cle, langues.langue_hass(self.hass), self._magasin(), **champs)

    def _n(self, cle: str, nombre: int, **champs: Any) -> str:
        return TEXTES.n(cle, nombre, langues.langue_hass(self.hass), self._magasin(), **champs)

    def _categorie(self, nom: str) -> str:
        lg = langues.langue_hass(self.hass)
        return str(nom) if lg == langues.SOURCE else langues.categorie(str(nom), lg)

    def _compte(self, n: int, genre: str) -> str:
        """« aucune personne », « 1 personne », « 3 appareils » (genre : personnes | appareils)."""
        return self._n(f"compte_{genre}", n) if n else self._t(f"compte_{genre}_zero")

    def _resume_roles(self, coord) -> str:
        """Un rôle = une ligne : nom, personnes, appareils (refonte du 2026-10-08)."""
        from . import roles

        store = coord.store
        qui = roles.resume(store)
        lignes = [self._t("roles_entete"), "|---|---|---|"]
        for ident, r in store.roles.items():
            q = qui.get(ident, {"personnes": [], "appareils": []})
            lignes.append(f"| **{r['nom']}** | {len(q['personnes'])} | {len(q['appareils'])} |")
        sans = [p.entity_id for p in self.hass.states.async_all("person") if p.entity_id not in store.roles_personnes]
        pied = self._t("roles_pied", role=roles.nom_defaut(store))
        if sans:
            pied += " " + self._t("roles_pied_personnes", personnes=", ".join(self._nom_personne(p) for p in sans))
        return "\n".join(lignes) + "\n\n" + pied

    async def async_step_roles(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        return self.async_show_menu(step_id="roles", menu_options=["roles_modifier", "roles_personnes", "roles_creer", "roles_appareils",
                                                                       "roles_validation_appareil"],
                                    description_placeholders={"resume": self._resume_roles(coord)})

    async def async_step_roles_modifier(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """La liste des rôles, une ligne chacun ; on en ouvre un pour cocher ses fonctions."""
        from . import roles

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            self._role = user_input["role"]
            return await self.async_step_roles_edition()
        qui = roles.resume(coord.store)
        options = [{"value": i, "label": "{} · {} · {}".format(
            r["nom"], self._compte(len(qui[i]["personnes"]), "personnes"),
            self._compte(len(qui[i]["appareils"]), "appareils"))} for i, r in coord.store.roles.items()]
        return self.async_show_form(step_id="roles_modifier", data_schema=vol.Schema({
            vol.Required("role", default=options[0]["value"] if options else ""): SelectSelector(SelectSelectorConfig(
                options=options, mode=SelectSelectorMode.LIST))}), last_step=False)

    async def async_step_roles_edition(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Un rôle ouvert : son nom, ses fonctions à cocher, sa pause maximale."""
        from . import roles

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        r = coord.store.roles.get(getattr(self, "_role", ""))
        if r is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None and "nom" in user_input:
            try:
                if user_input.get("supprimer"):
                    roles.supprimer(self.hass, coord.store, self._role, await self._qui())
                else:
                    roles.modifier(self.hass, coord.store, self._role, user_input.get("nom", ""), user_input.get("fonctions") or [],
                                   int(user_input.get("pause_max") or 60), await self._qui())
            except ValueError as err:
                self._erreur_role = str(err)
                erreurs["base"] = "role_refuse"
            else:
                await coord.async_ecrire()
                if user_input.get("validation") and not user_input.get("supprimer"):
                    return await self.async_step_roles_edition_validation()
                return self.async_create_entry(data=dict(self.config_entry.options))
        q = roles.resume(coord.store).get(self._role, {"personnes": [], "appareils": []})
        personnes = ", ".join(self._nom_personne(p) for p in q["personnes"]) or self._t("aucune")
        appareils = ", ".join(str(coord.store.pcs[i].get("name") or i) for i in q["appareils"]) or self._t("aucun")
        options = [{"value": f, "label": roles.libelle_fonction(f, langues.langue_hass(self.hass)) + (" ★" if f in roles.SENSIBLES else "")}
                   for f in roles.FONCTIONS]
        return self.async_show_form(step_id="roles_edition", data_schema=vol.Schema({
            vol.Required("nom", default=r["nom"]): str,
            vol.Optional("fonctions", default=list(r.get("fonctions") or [])): SelectSelector(SelectSelectorConfig(
                options=options, multiple=True, mode=SelectSelectorMode.LIST)),
            vol.Required("pause_max", default=int(r.get("pause_max") or 60)): vol.All(vol.Coerce(int), vol.Range(min=5, max=960)),
            vol.Optional("validation", default=False): bool,
            vol.Optional("supprimer", default=False): bool,
        }), errors=erreurs, description_placeholders={
            "erreur": getattr(self, "_erreur_role", "") if erreurs else "",
            "personnes": personnes, "appareils": appareils,
            "deverrouillage": roles.resume_validation(roles._validation_propre(r.get("validation")), langues.langue_hass(self.hass)),
        })

    async def async_step_roles_edition_validation(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """La validation à plusieurs de ce rôle (déverrouillage), sur son propre écran."""
        from . import roles

        coord = self._coord()
        r = coord.store.roles.get(getattr(self, "_role", "")) if coord is not None else None
        if r is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            nouvelle = _validation_saisie(user_input)
            if nouvelle != roles._validation_propre(r.get("validation")):
                roles.regler_validation(self.hass, coord.store, self._role, nouvelle, await self._qui())
                await coord.async_ecrire()
            return self.async_create_entry(data=dict(self.config_entry.options))
        return self.async_show_form(step_id="roles_edition_validation", data_schema=vol.Schema(
            _schema_validation(roles._validation_propre(r.get("validation")), avec_role=False)),
            description_placeholders={"role": str(r["nom"])})

    async def async_step_roles_creer(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        from . import roles

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            try:
                self._role = roles.creer(self.hass, coord.store, user_input.get("nom", ""), user_input.get("modele", roles.ENFANT),
                                         await self._qui())
            except ValueError as err:
                self._erreur_role = str(err)
                erreurs["base"] = "role_refuse"
            else:
                await coord.store.async_save()
                return await self.async_step_roles_edition()
        options = [{"value": i, "label": str(r["nom"])} for i, r in coord.store.roles.items()]
        return self.async_show_form(step_id="roles_creer", data_schema=vol.Schema({
            vol.Required("nom"): str,
            vol.Required("modele", default=roles.ENFANT): SelectSelector(SelectSelectorConfig(options=options)),
        }), errors=erreurs, description_placeholders={"erreur": getattr(self, "_erreur_role", "") if erreurs else ""},
            last_step=False)  # HA-30 : la suite (les fonctions du rôle) vient après ; le bouton dit « Suivant »

    async def async_step_roles_personnes(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Le rôle de chaque personne : on choisit la personne (avec son rôle actuel), puis son rôle."""
        from . import roles

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None and user_input.get("personne"):
            self._personne = user_input["personne"]
            return await self.async_step_roles_personne_role()
        options = []
        for s_ in sorted(self.hass.states.async_all("person"), key=lambda x: x.name.lower()):
            ident = coord.store.roles_personnes.get(s_.entity_id, "")
            actuel = (coord.store.roles.get(ident, {}).get("nom") if ident
                      else self._t("role_par_defaut_court", role=roles.nom_defaut(coord.store)))
            options.append({"value": s_.entity_id, "label": f"{s_.name} · {actuel}"})
        return self.async_show_form(step_id="roles_personnes", data_schema=vol.Schema({
            vol.Required("personne"): SelectSelector(SelectSelectorConfig(options=options, mode=SelectSelectorMode.LIST)),
        }), last_step=False)

    async def async_step_roles_personne_role(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        from . import roles

        coord = self._coord()
        personne = getattr(self, "_personne", "")
        if coord is None or not personne:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            try:
                roles.attribuer_personne(self.hass, coord.store, personne, user_input.get("role") or "", await self._qui())
            except ValueError as err:
                self._erreur_role = str(err)
                erreurs["base"] = "role_refuse"
            else:
                await coord.async_ecrire()
                return self.async_create_entry(data=dict(self.config_entry.options))
        options = [{"value": "", "label": self._t("option_role_defaut", role=roles.nom_defaut(coord.store))}] + [
            {"value": i, "label": str(r["nom"])} for i, r in coord.store.roles.items()]
        siens = [str(pc.get("name") or i) for i, pc in coord.store.pcs.items() if pc.get("person") == personne]
        return self.async_show_form(step_id="roles_personne_role", data_schema=vol.Schema({
            vol.Required("role", default=coord.store.roles_personnes.get(personne, "")): SelectSelector(SelectSelectorConfig(
                options=options, mode=SelectSelectorMode.LIST)),
        }), errors=erreurs, description_placeholders={
            "personne": self._nom_personne(personne), "appareils": ", ".join(siens) or self._t("compte_appareils_zero"),
            "erreur": getattr(self, "_erreur_role", "") if erreurs else ""})

    async def async_step_roles_appareils(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        from . import roles

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            try:
                roles.attribuer_appareil(self.hass, coord.store, user_input["appareil"], user_input.get("role") or "", await self._qui())
            except ValueError as err:
                self._erreur_role = str(err)
                erreurs["base"] = "role_refuse"
            else:
                await coord.async_ecrire()
                return self.async_create_entry(data=dict(self.config_entry.options))
        appareils = [{"value": i, "label": str(pc.get("name") or i)} for i, pc in
                     sorted(coord.store.pcs.items(), key=lambda x: str(x[1].get("name") or x[0]).lower())]
        options = [{"value": "", "label": self._t("option_comme_personne")}] + [{"value": i, "label": str(r["nom"])} for i, r in coord.store.roles.items()]
        return self.async_show_form(step_id="roles_appareils", data_schema=vol.Schema({
            vol.Required("appareil"): SelectSelector(SelectSelectorConfig(options=appareils)),
            vol.Optional("role", default=""): SelectSelector(SelectSelectorConfig(options=options)),
        }), errors=erreurs, description_placeholders={"resume": self._resume_roles(coord),
                                                      "erreur": getattr(self, "_erreur_role", "") if erreurs else ""})

    async def async_step_roles_validation_appareil(self, user_input: dict[str, Any] | None = None) -> "ConfigFlowResult":
        """Surcharge par appareil de la validation à plusieurs (sinon : celle du rôle)."""
        from . import roles

        coord = self._coord()
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            valeurs = None if user_input.get("validation_mode") == "role" else _validation_saisie(user_input)
            roles.regler_validation(self.hass, coord.store, f"appareil:{user_input['appareil']}", valeurs, await self._qui())
            await coord.async_ecrire()
            return self.async_create_entry(data=dict(self.config_entry.options))
        appareils = [{"value": i, "label": str(pc.get("name") or i)} for i, pc in
                     sorted(coord.store.pcs.items(), key=lambda x: str(x[1].get("name") or x[0]).lower())]
        return self.async_show_form(step_id="roles_validation_appareil", data_schema=vol.Schema({
            vol.Required("appareil"): SelectSelector(SelectSelectorConfig(options=appareils)),
            **_schema_validation(dict(roles.VALIDATION_DEFAUT, mode="role"), avec_role=True),
        }), description_placeholders={"resume": self._resume_roles(coord)})

    async def async_step_deverrouillage_resultat(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        if user_input is not None:
            return self.async_create_entry(data=dict(self.config_entry.options))
        return self.async_show_form(step_id="deverrouillage_resultat", data_schema=vol.Schema({}),
                                    description_placeholders={"resultat": getattr(self, "_resultat", "")})

    async def async_step_inscriptions(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        """Inscription de nouveaux appareils : ouvrir, fermer, ou autoriser 5 minutes."""
        from . import inscriptions

        coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
        if coord is None:
            return self.async_abort(reason="not_loaded")
        if user_input is not None:
            mode = user_input.get("inscriptions", GARDER)
            if mode != GARDER:
                qui = ""
                if self.context.get("user_id"):
                    utilisateur = await self.hass.auth.async_get_user(self.context["user_id"])
                    qui = utilisateur.name if utilisateur else ""
                await inscriptions.regler(self.hass, coord, mode, par=qui)
            return self.async_create_entry(data=dict(self.config_entry.options))
        return self.async_show_form(
            step_id="inscriptions",
            data_schema=vol.Schema({
                vol.Required("inscriptions", default=inscriptions.TEMPORAIRE): SelectSelector(SelectSelectorConfig(
                    options=[inscriptions.TEMPORAIRE, inscriptions.OUVRIR, inscriptions.FERMER, GARDER],
                    translation_key="inscriptions", mode=SelectSelectorMode.LIST,
                )),
            }),
            description_placeholders={
                "etat": inscriptions.etat(coord, langues.langue_hass(self.hass)),
                "minutes": str(inscriptions.TEMPORAIRE_MINUTES),
            },
        )

    async def async_step_installer(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        """Les liens d'installation (dernière version Android, installateur Windows) et leur QR."""
        from . import installation

        if user_input is not None:
            return self.async_create_entry(data=dict(self.config_entry.options))
        return self.async_show_form(
            step_id="installer",
            data_schema=vol.Schema({}),
            description_placeholders={"installation": installation.markdown(self.hass, False, langues.langue_hass(self.hass))},
        )

    async def async_step_qr(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        """Générer un QR d'appairage, que l'appareil (avec caméra) scanne pour se relier seul."""
        from homeassistant.components.http.auth import async_sign_path
        from homeassistant.helpers.http import current_request

        from . import appairage

        coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
        if coord is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            try:
                inv = appairage.creer_invitation(
                    self.hass, *appairage.adresses(self.hass, current_request.get()),
                    user_input.get("personne") or "", user_input.get("appareil") or "",
                )
            except ValueError:
                erreurs["base"] = "qr_impossible"
            else:
                from datetime import timedelta

                self._qr = {
                    # Chemin signé : l'image ne se charge qu'avec la session de cet administrateur.
                    "image": async_sign_path(self.hass, appairage.chemin_qr(inv["id"]),
                                             timedelta(minutes=appairage.APPAIRAGE_MINUTES)),
                    "minutes": str(appairage.APPAIRAGE_MINUTES),
                }
                return await self.async_step_qr_affiche()
        personnes = [{"value": s.entity_id, "label": s.name} for s in self.hass.states.async_all("person")]
        appareils = sorted(
            ({"value": i, "label": str(pc.get("name") or i)} for i, pc in coord.store.pcs.items()),
            key=lambda a: a["label"].lower(),
        )
        return self.async_show_form(
            step_id="qr",
            data_schema=vol.Schema({
                vol.Optional("personne"): SelectSelector(SelectSelectorConfig(options=personnes)),
                vol.Optional("appareil"): SelectSelector(SelectSelectorConfig(options=appareils)),
            }),
            errors=erreurs,
            last_step=False,  # le QR s'affiche à l'écran suivant
        )

    async def async_step_qr_affiche(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        if user_input is not None:
            return self.async_create_entry(data=dict(self.config_entry.options))
        return self.async_show_form(
            step_id="qr_affiche", data_schema=vol.Schema({}), description_placeholders=self._qr,
        )

    async def async_step_appairage(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        """Valider (ou refuser) l'appareil qui affiche ce code : l'appairage par QR sans QR."""
        from . import appairage

        coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
        if coord is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            try:
                if user_input.get("decision") == "refuser":
                    appairage.refuser(self.hass, user_input.get("code", ""))
                else:
                    await appairage.valider(self.hass, user_input.get("code", ""), user_input.get("personne") or "")
            except ValueError:
                erreurs["code"] = "code_inconnu"
            else:
                return self.async_create_entry(data=dict(self.config_entry.options))
        en_cours = [appairage.public(coord, d) for d in appairage._demandes(self.hass).values() if d["etat"] == "en_attente"]
        lignes = "\n".join(
            self._t("appairage_ligne_existant" if d["existant"] else "appairage_ligne", code=d["code"], nom=d["nom"],
                    plateforme=d["plateforme"] or self._t("appareil"), existant=d["existant"])
            for d in en_cours
        ) or self._t("aucune_demande")
        personnes = [{"value": s.entity_id, "label": s.name} for s in self.hass.states.async_all("person")]
        return self.async_show_form(
            step_id="appairage",
            data_schema=vol.Schema({
                vol.Required("code"): str,
                vol.Optional("personne"): SelectSelector(SelectSelectorConfig(options=personnes)),
                vol.Required("decision", default="valider"): SelectSelector(SelectSelectorConfig(
                    options=["valider", "refuser"], translation_key="decision",
                    mode=SelectSelectorMode.LIST,
                )),
            }),
            errors=erreurs,
            description_placeholders={"demandes": lignes},
        )

    async def async_step_cle(
        self, user_input: dict[str, Any] | None = None
    ) -> "ConfigFlowResult":
        coord = self.hass.data.get(DOMAIN, {}).get(self.config_entry.entry_id)
        if coord is None:
            return self.async_abort(reason="not_loaded")
        erreurs: dict[str, str] = {}
        if user_input is not None:
            action = user_input.get("action", GARDER)
            if action == GARDER:
                return self.async_create_entry(data=dict(self.config_entry.options))
            try:
                coord.store.nouvelle_cle(user_input.get("cle", "") if action == CHOISIR else "")
            except ValueError:
                erreurs["cle"] = "cle_invalide"
            else:
                await coord.store.async_save()
                self._changee = True
        from . import inscriptions
        from .dashboard import installation

        infos = installation(self.hass, coord)
        return self.async_show_form(
            step_id="cle",
            data_schema=vol.Schema({
                vol.Required("action", default=GARDER): SelectSelector(SelectSelectorConfig(
                    options=[GARDER, NOUVELLE, CHOISIR], translation_key="action",
                    mode=SelectSelectorMode.LIST,
                )),
                vol.Optional("cle", default=""): str,
            }),
            errors=erreurs,
            description_placeholders={
                "cle": coord.store.enroll_key,
                "commande": infos["commande"],
                "android": infos["android"],
                "inscriptions": inscriptions.etat(coord, langues.langue_hass(self.hass)),
                "changee": self._t("cle_changee") + "\n\n" if self._changee else "",
            },
        )


def _schema_validation(v: dict[str, Any], avec_role: bool) -> dict:
    """Les champs « validation à plusieurs » (docs/deverrouillage-quorum.md)."""
    modes = (["role"] if avec_role else []) + ["une", "toutes", "n"]
    return {
        vol.Required("validation_mode", default=v["mode"]): SelectSelector(SelectSelectorConfig(
            options=modes, translation_key="validation_mode", mode=SelectSelectorMode.LIST)),
        vol.Required("validation_n", default=v["n"]): vol.All(vol.Coerce(int), vol.Range(min=2, max=10)),
        vol.Optional("validation_actions", default=list(v["actions"])): SelectSelector(SelectSelectorConfig(
            options=["parent", "reglages", "app"], translation_key="validation_actions", multiple=True,
            mode=SelectSelectorMode.LIST)),
        vol.Required("validation_au_dela_de", default=v["au_dela_de"]): vol.All(vol.Coerce(int), vol.Range(min=0, max=1440)),
        vol.Required("validation_repli", default=v["repli_minutes"]): vol.All(vol.Coerce(int), vol.Range(min=0, max=9)),
    }


def _validation_saisie(u: dict[str, Any]) -> dict[str, Any]:
    from . import roles

    return roles._validation_propre({
        "mode": u.get("validation_mode"), "n": u.get("validation_n"), "actions": u.get("validation_actions") or [],
        "au_dela_de": u.get("validation_au_dela_de"), "repli_minutes": u.get("validation_repli"),
    })
