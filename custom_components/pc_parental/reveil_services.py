"""Réveil (lot 1) : les services pc_parental.reveil_*.

Droits (reveil.peut) : appelés par une automatisation (sans utilisateur) ou par un administrateur de HA, tous les
droits ; appelés par un autre utilisateur, contrôlés comme dans l'appli avec la personne qui lui est liée
(fonctions de rôle reveil_gerer / reveil_propres). Refus : Unauthorized, sans aucun changement.
Module coupé : erreur explicite (modules.exiger).

Les champs d'un réveil se donnent soit en objets (`declenchement`, `source`), soit à plat (`type`, `heure`,
`jours`, `uri`…) — plus simple dans l'éditeur d'automatisations. Les deux se fusionnent.
"""

from __future__ import annotations

import secrets
from datetime import timedelta
from typing import Any

import voluptuous as vol
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse
from homeassistant.exceptions import ServiceValidationError, Unauthorized
from homeassistant.helpers import config_validation as cv

from . import modules, reveil
from . import reveil_calcul as calcul
from .const import DOMAIN

SERVICES = ("reveil_creer", "reveil_modifier", "reveil_activer", "reveil_desactiver", "reveil_sauter",
            "reveil_reprendre", "reveil_sonner", "reveil_reporter", "reveil_arreter", "reveil_supprimer",
            "reveil_lister", "reveil_regler", "reveil_installer_modeles")

CHAMPS_DECLENCHEMENT = ("type", "heure", "jours", "date", "entite", "attribut", "modele", "decalage", "bornes", "repli")
CHAMPS_SOURCE = ("uri", "lecteur", "libelle")
CHAMPS_REVEIL = ("nom", "demandeur", "appareils", "actif", "impose", "allumer_tv", "volume", "repetition", "sauter")

_TEXTE = vol.Any(cv.string, None)
_CHAMPS: dict[Any, Any] = {
    vol.Optional("nom"): cv.string,
    vol.Optional("appareils"): vol.All(cv.ensure_list, [cv.string]),
    vol.Optional("actif"): cv.boolean,
    vol.Optional("impose"): cv.boolean,
    vol.Optional("allumer_tv"): cv.boolean,
    vol.Optional("declenchement"): dict,
    vol.Optional("type"): vol.In(calcul.TYPES),
    vol.Optional("heure"): cv.string,
    vol.Optional("jours"): vol.Any(cv.string, [vol.Any(int, cv.string, bool)]),
    vol.Optional("date"): cv.string,
    vol.Optional("entite"): cv.string,
    vol.Optional("attribut"): cv.string,
    vol.Optional("modele"): cv.string,
    vol.Optional("decalage"): vol.Coerce(int),
    vol.Optional("bornes"): vol.Any(None, [cv.string]),
    vol.Optional("repli"): _TEXTE,
    vol.Optional("source"): vol.Any(vol.In(calcul.SOURCES), dict),
    vol.Optional("uri"): cv.string,
    vol.Optional("lecteur"): cv.string,
    vol.Optional("libelle"): cv.string,
    vol.Optional("volume"): dict,
    vol.Optional("repetition"): dict,
    vol.Optional("sauter"): vol.All(cv.ensure_list, [cv.string]),
}
SCHEMA_CREER = vol.Schema({vol.Required("demandeur"): cv.string, **_CHAMPS})
SCHEMA_MODIFIER = vol.Schema({vol.Required("reveil"): cv.string, vol.Optional("demandeur"): cv.string, **_CHAMPS})
SCHEMA_REVEIL = vol.Schema({vol.Required("reveil"): cv.string})
SCHEMA_DATE = vol.Schema({vol.Required("reveil"): cv.string, vol.Optional("date"): cv.string})
SCHEMA_SONNER = vol.Schema({
    vol.Optional("reveil"): cv.string, vol.Optional("demandeur"): cv.string,
    vol.Optional("appareils"): vol.All(cv.ensure_list, [cv.string]),
    vol.Optional("source"): vol.Any(vol.In(calcul.SOURCES), dict),
    vol.Optional("uri"): cv.string, vol.Optional("lecteur"): cv.string, vol.Optional("libelle"): cv.string,
})
SCHEMA_REPORTER = vol.Schema({vol.Required("reveil"): cv.string,
                              vol.Optional("minutes"): vol.All(vol.Coerce(int), vol.Range(min=1, max=60))})
SCHEMA_ARRETER = vol.Schema({vol.Optional("reveil"): cv.string, vol.Optional("demandeur"): cv.string})
SCHEMA_LISTER = vol.Schema({vol.Optional("demandeur"): cv.string})
SCHEMA_REGLER = vol.Schema({vol.Optional(k): cv.string if k == "heure_evaluation" else vol.Coerce(int)
                            for k in reveil.REGLAGES_DEFAUT})


def brut_depuis(data: dict[str, Any]) -> dict[str, Any]:
    """Les champs d'un réveil depuis les données d'un service (objets et champs à plat fusionnés)."""
    brut: dict[str, Any] = {k: data[k] for k in CHAMPS_REVEIL if k in data}
    decl = dict(data.get("declenchement") or {})
    decl.update({k: data[k] for k in CHAMPS_DECLENCHEMENT if k in data})
    if decl:
        brut["declenchement"] = decl
    src = data.get("source")
    src = {"type": src} if isinstance(src, str) else dict(src or {})
    src.update({k: data[k] for k in CHAMPS_SOURCE if k in data})
    if src:
        brut["source"] = src
    return brut


def _personne(hass: HomeAssistant, valeur: str) -> str:
    valeur = str(valeur or "").strip()
    if valeur.startswith("person."):
        if hass.states.get(valeur) is None:
            raise calcul.ReveilInvalide("demandeur", f"Personne « {valeur} » introuvable.", valeur=valeur)
        return valeur
    for etat in hass.states.async_all("person"):
        if str(etat.name or "").strip().lower() == valeur.lower() or etat.entity_id.split(".", 1)[1] == valeur.lower():
            return etat.entity_id
    raise calcul.ReveilInvalide("demandeur", f"Personne « {valeur} » introuvable.", valeur=valeur)


def enregistrer(hass: HomeAssistant, coordinateur) -> None:
    """Enregistre les services (une fois). `coordinateur()` rend le coordinateur chargé."""
    if hass.services.has_service(DOMAIN, "reveil_creer"):
        return

    def _service(nom: str, schema: vol.Schema, reponse: SupportsResponse = SupportsResponse.NONE):
        def deco(fonction):
            async def gerer(call: ServiceCall) -> ServiceResponse:
                coord = coordinateur()
                modules.exiger(coord.store, reveil.MODULE)
                droits, personne = await reveil.droits_utilisateur(hass, coord.store, call.context.user_id)
                try:
                    return await fonction(call, coord, droits, personne)
                except reveil.Refus as err:
                    raise Unauthorized(context=call.context, user_id=call.context.user_id, permission="control") from err
                except calcul.ReveilInvalide as err:
                    raise ServiceValidationError(str(err), translation_domain=DOMAIN, translation_key=f"reveil_{err.cle}",
                                                 translation_placeholders=err.valeurs) from err

            hass.services.async_register(DOMAIN, nom, gerer, schema=schema, supports_response=reponse)
            return fonction

        return deco

    def _par(personne: str, droits) -> str:
        return personne or ("automatisation" if droits is None else "home_assistant")

    def _origine(call: ServiceCall) -> str:
        return "home_assistant" if call.context.user_id else "automatisation"

    @_service("reveil_creer", SCHEMA_CREER, SupportsResponse.OPTIONAL)
    async def _creer(call, coord, droits, personne):
        brut = brut_depuis(call.data)
        brut["demandeur"] = _personne(hass, call.data["demandeur"])
        reveil.exiger(droits, personne, "creer", None, brut["demandeur"], brut)
        r = reveil.creer(coord.store, brut, _par(personne, droits), _origine(call))
        coord.reveil.suivre(r)
        coord.reveil.evaluer(r)
        await coord.reveil.async_publier(nouveaux=True)
        return {"id": r["id"], "nom": r["nom"]}

    @_service("reveil_modifier", SCHEMA_MODIFIER, SupportsResponse.OPTIONAL)
    async def _modifier(call, coord, droits, personne):
        r = reveil.trouver(hass, coord.store, call.data["reveil"])
        brut = brut_depuis(call.data)
        if "demandeur" in call.data:
            brut["demandeur"] = _personne(hass, call.data["demandeur"])
        reveil.exiger(droits, personne, "modifier", r, brut=brut)
        if "demandeur" in brut:  # changer de Demandeur : il faut aussi les droits sur le nouveau
            reveil.exiger(droits, personne, "modifier", None, brut["demandeur"], brut)
        nom_avant = r.get("nom")
        reveil.modifier(coord.store, r, brut, _par(personne, droits))
        coord.reveil.suivre(r)
        coord.reveil.evaluer(r)
        if r.get("nom") != nom_avant:
            from .reveil_entites import renommer

            renommer(hass, r)
        await coord.reveil.async_publier()
        return {"id": r["id"], "nom": r["nom"], "version": r["version"]}

    async def _activer(call, coord, droits, personne, valeur: bool):
        r = reveil.trouver(hass, coord.store, call.data["reveil"])
        reveil.exiger(droits, personne, "activer" if valeur else "desactiver", r)
        if bool(r.get("actif")) != valeur:
            r["actif"] = valeur
            reveil._horodater(r, _par(personne, droits))
            await coord.reveil.async_publier()

    @_service("reveil_activer", SCHEMA_REVEIL)
    async def _on(call, coord, droits, personne):
        await _activer(call, coord, droits, personne, True)

    @_service("reveil_desactiver", SCHEMA_REVEIL)
    async def _off(call, coord, droits, personne):
        await _activer(call, coord, droits, personne, False)

    @_service("reveil_sauter", SCHEMA_DATE, SupportsResponse.OPTIONAL)
    async def _sauter(call, coord, droits, personne):
        r = reveil.trouver(hass, coord.store, call.data["reveil"])
        reveil.exiger(droits, personne, "sauter", r)
        jour = call.data.get("date") or calcul.prochaine_date(r, reveil.maintenant())
        if not jour:
            return {"id": r["id"], "date": None}
        reveil.sauter(r, jour, _par(personne, droits))
        await coord.reveil.async_publier()
        return {"id": r["id"], "date": calcul.lire_date(jour)}

    @_service("reveil_reprendre", SCHEMA_DATE, SupportsResponse.OPTIONAL)
    async def _reprendre(call, coord, droits, personne):
        r = reveil.trouver(hass, coord.store, call.data["reveil"])
        reveil.exiger(droits, personne, "reprendre", r)
        sautees = list(r.get("sauter") or [])
        jour = call.data.get("date") or (sautees[0] if sautees else "")
        if not jour:
            return {"id": r["id"], "date": None}
        reveil.reprendre(r, jour, _par(personne, droits))
        await coord.reveil.async_publier()
        return {"id": r["id"], "date": calcul.lire_date(jour)}

    def _appareils(coord, demandeur: str, noms: list[str], defaut: list[dict]) -> list[dict]:
        if not noms:
            return defaut
        return [coord.store.pcs[i] for i in reveil._resoudre_appareils(coord.store, demandeur, noms)]

    @_service("reveil_sonner", SCHEMA_SONNER, SupportsResponse.OPTIONAL)
    async def _sonner(call, coord, droits, personne):
        store = coord.store
        brut_source = brut_depuis({k: call.data[k] for k in ("source", *CHAMPS_SOURCE) if k in call.data}).get("source")
        if call.data.get("reveil"):
            r = reveil.trouver(hass, store, call.data["reveil"])
            demandeur = str(r["demandeur"])
            reveil.exiger(droits, personne, "sonner", r)
            base = r
        elif call.data.get("demandeur"):
            r = None
            demandeur = _personne(hass, call.data["demandeur"])
            reveil.exiger(droits, personne, "sonner", None, demandeur)
            base = calcul.normaliser({"demandeur": demandeur, "nom": calcul.NOM_DEFAUT})
        else:
            raise calcul.ReveilInvalide("cible", "Indique un réveil ou un Demandeur.")
        source = calcul._source(brut_source, base["source"]) if brut_source else base["source"]
        defaut = reveil.cibles(store, r) if r else reveil.cibles(store, {"demandeur": demandeur, "appareils": []})
        pcs = _appareils(coord, demandeur, list(call.data.get("appareils") or []), defaut)
        if not pcs:
            raise calcul.ReveilInvalide("aucun_appareil", f"Aucun appareil pour {demandeur}.", demandeur=demandeur)
        occurrence = "ordre:" + secrets.token_hex(4)
        ordre = {"type": "sonner", "reveil": r["id"] if r else "", "occurrence": occurrence, "nom": base.get("nom"),
                 "source": {k: v for k, v in source.items() if v}, "repli": "sonnerie", "volume": base["volume"],
                 "repetition": base["repetition"], "arret_auto_min": int(reveil.reglages(store)["arret_auto_min"]),
                 "allumer_tv": bool(base.get("allumer_tv")), "par": _par(personne, droits)}
        vises = reveil.ordonner(store, pcs, ordre)
        await coord.reveil.async_publier()
        return {"occurrence": occurrence, "appareils": vises}

    @_service("reveil_reporter", SCHEMA_REPORTER, SupportsResponse.OPTIONAL)
    async def _reporter(call, coord, droits, personne):
        r = reveil.trouver(hass, coord.store, call.data["reveil"])
        reveil.exiger(droits, personne, "reporter", r)
        minutes = int(call.data.get("minutes") or (r.get("repetition") or {}).get("minutes") or 9)
        jusqu_a = reveil.maintenant() + timedelta(minutes=minutes)
        occurrence = str((r.get("vivant") or {}).get("occurrence") or "")
        vises = reveil.ordonner(coord.store, reveil.cibles(coord.store, r),
                                {"type": "reporter", "reveil": r["id"], "occurrence": occurrence, "minutes": minutes,
                                 "jusqu_a": jusqu_a.isoformat(), "par": "service"})
        reveil._vivant(r, "reporte", jusqu_a + timedelta(minutes=1), occurrence=occurrence, par="service")
        await coord.reveil.async_publier()
        return {"id": r["id"], "jusqu_a": jusqu_a.isoformat(), "appareils": vises}

    @_service("reveil_arreter", SCHEMA_ARRETER, SupportsResponse.OPTIONAL)
    async def _arreter(call, coord, droits, personne):
        store = coord.store
        if call.data.get("reveil"):
            liste = [reveil.trouver(hass, store, call.data["reveil"])]
        elif call.data.get("demandeur"):
            demandeur = _personne(hass, call.data["demandeur"])
            liste = [r for r in reveil.reveils(store).values() if r.get("demandeur") == demandeur]
            if not liste:  # une sonnerie lancée par reveil_sonner sans réveil enregistré
                reveil.exiger(droits, personne, "arreter", None, demandeur)
                pcs = reveil.cibles(store, {"demandeur": demandeur, "appareils": []})
                vises = reveil.ordonner(store, pcs, {"type": "arreter", "reveil": "", "par": "service"})
                await coord.reveil.async_publier()
                return {"reveils": [], "appareils": vises}
        else:
            raise calcul.ReveilInvalide("cible", "Indique un réveil ou un Demandeur.")
        for r in liste:
            reveil.exiger(droits, personne, "arreter", r)
        vises: list[str] = []
        for r in liste:
            occurrence = str((r.get("vivant") or {}).get("occurrence") or "")
            vises += reveil.ordonner(store, reveil.cibles(store, r),
                                     {"type": "arreter", "reveil": r["id"], "occurrence": occurrence, "par": "service"})
            if reveil.etat(store, r) in ("sonne", "reporte"):
                reveil._vivant(r, "arrete", reveil.maintenant() + timedelta(hours=1), occurrence=occurrence, par="service")
        await coord.reveil.async_publier()
        return {"reveils": [r["id"] for r in liste], "appareils": sorted(set(vises))}

    @_service("reveil_supprimer", SCHEMA_REVEIL)
    async def _supprimer(call, coord, droits, personne):
        r = reveil.trouver(hass, coord.store, call.data["reveil"])
        reveil.exiger(droits, personne, "supprimer", r)
        reveil.supprimer(coord.store, r, _par(personne, droits))
        coord.reveil.oublier(r["id"])
        from .reveil_entites import retirer

        retirer(hass, r["id"])
        await coord.reveil.async_publier()

    @_service("reveil_lister", SCHEMA_LISTER, SupportsResponse.ONLY)
    async def _lister(call, coord, droits, personne):
        demandeur = _personne(hass, call.data["demandeur"]) if call.data.get("demandeur") else ""
        sortie = []
        for r in sorted(reveil.reveils(coord.store).values(), key=lambda x: (str(x.get("demandeur")), str(x.get("nom")))):
            if demandeur and r.get("demandeur") != demandeur:
                continue
            if reveil.peut(droits, personne, "voir", r):
                sortie.append(reveil.vue(hass, coord.store, r))
        return {"reveils": sortie, "reglages": reveil.reglages(coord.store)}

    @_service("reveil_regler", SCHEMA_REGLER, SupportsResponse.OPTIONAL)
    async def _regler(call, coord, droits, personne):
        if droits is not None:  # réglage du Lieu : administrateurs et automatisations seulement
            raise reveil.Refus("regler")
        reglages = reveil.regler(coord.store, dict(call.data))
        await coord.reveil.async_publier()
        return {"reglages": reglages}

    @_service("reveil_installer_modeles", vol.Schema({}), SupportsResponse.OPTIONAL)
    async def _installer(call, coord, droits, personne):
        """Dépose les blueprints fournis (jamais par-dessus une copie existante). Administrateurs seulement."""
        if droits is not None:
            raise reveil.Refus("installer")
        return {"deposes": await reveil.async_installer_modeles(hass)}


def retirer(hass: HomeAssistant) -> None:
    for nom in SERVICES:
        hass.services.async_remove(DOMAIN, nom)

