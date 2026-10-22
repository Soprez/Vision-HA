"""Refonte UX, lot 5 : Réglages › « Réservé aux Décideurs » dans le tableau de bord (architecture cible § 2).

Trois réglages qui n'étaient que dans Configurer, à un seul endroit, sans flux qui se ferme à chaque formulaire :
- **Lieu et vocabulaire** : le nom du lieu, les mots des deux rôles (mêmes règles que Configurer : lieu.propre,
  vocabulaire.preparer) ;
- **Fonctions** : les modules rangés par objectif (assistant.GROUPES), un interrupteur par module, ses dépendances
  dites AVANT de basculer ; couper les protections quand des appareils sont protégés demande une confirmation ;
- **Rôles et droits** : un tableau rôles × fonctions, une case par droit (roles.modifier : il reste toujours un
  administrateur).

    GET  /api/pc_parental/reglages/admin                       → l'état (administrateurs seulement)
    POST /api/pc_parental/reglages/admin  {"op": "lieu", "lieu", "mots": {decideur…}}
                                          {"op": "module", "module", "actif", "confirmer"?}
                                          {"op": "droit", "role", "fonction", "actif"}
"""
from __future__ import annotations

import logging
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import assistant, langues, lieu, modules, roles, textes, vocabulaire
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)
URL = "/api/pc_parental/reglages/admin"


def _coord(hass: HomeAssistant):
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


def _module(store, m: str, lg: str) -> dict[str, Any]:
    _nom, _phrase, tous, un = modules.CATALOGUE[m]
    return {"id": m, "nom": modules.nom(m, lg), "phrase": modules.phrase(m, lg), "actif": modules.actif(store, m),
            "requiert": [modules.nom(d, lg) for d in tous], "requiert_un": [modules.nom(d, lg) for d in un]}


def etat(hass: HomeAssistant, coord) -> dict[str, Any]:
    store = coord.store
    lg = langues.langue_hass(hass)
    groupes = [{"id": g, "modules": [_module(store, m, lg) for m in assistant.membres(g)]} for g in assistant.GROUPES]
    ranges = {m for g in assistant.GROUPES for m in assistant.membres(g)}
    autres = [_module(store, m, lg) for m in modules.TOUS if m not in ranges]
    return {
        "lieu": {"nom": store.lieu, "defaut": lieu.defaut(lg), "max": lieu.LONGUEUR_MAX},
        "mots": {"actuels": vocabulaire.reglage(store, lg), "defauts": vocabulaire.defauts(lg), "max": vocabulaire.LONGUEUR_MAX},
        "fonctions": {"groupes": groupes, "autres": autres, "proteges": modules.demandeurs(store)},
        "roles": {
            "liste": [{"id": i, "nom": str(r.get("nom") or i), "fonctions": list(r.get("fonctions") or [])}
                      for i, r in store.roles.items()],
            "fonctions": [{"id": f, "nom": roles.libelle_fonction(f, lg), "sensible": s} for f, (_n, s) in roles.FONCTIONS.items()],
        },
        "son": _son_etat(store),
    }


def _son_etat(store) -> list[dict[str, Any]] | None:
    """2.5.12.10 : le niveau sonore de chaque appareil pour la carte (module allumé seulement)."""
    from . import son

    if not son.actif_module(store):
        return None
    sortie = []
    for pid, pc in sorted(store.pcs.items(), key=lambda x: str(x[1].get("name") or x[0]).lower()):
        r = son.reglages(pc)
        e = son.etat(pc)
        seuil, source = son.seuil_detail(store, pc)
        plancher = son.plancher_effectif_db(pc, e)
        mesure = son.plancher_mesure_db(e)
        sortie.append({
            "id": pid, "nom": str(pc.get("name") or pid), "actif": r["actif"], "reagir": r["reagir"],
            "mode": r["seuil_mode"], "general_dbfs": r["seuil_db"] - 100,
            "plages": son.seuils_plages_lisibles(pc),
            "moment_dbfs": seuil - 100, "source": source.get("nom") or "",
            "plancher_dbfs": None if plancher is None else round(plancher - 100, 1),
            "plancher_mesure_dbfs": None if mesure is None else round(mesure - 100, 1),
            "plancher_manuel_dbfs": r["plancher_manuel_dbfs"],
            "niveau_dbfs": son.dbfs(e.get("niveau")),
        })
    return sortie


async def regler(hass: HomeAssistant, coord, corps: dict[str, Any], par: str) -> str:
    """Applique un réglage ; rend la phrase « c'est fait ». textes.Erreur si refusé (rien n'a changé)."""
    store = coord.store
    lg = langues.langue_hass(hass)
    op = str(corps.get("op") or "")
    if op == "lieu":
        nom = lieu.propre(str(corps.get("lieu") or ""))
        if nom == lieu.defaut(lg):
            nom = ""  # le mot par défaut suit la langue : on ne l'enregistre pas
        try:
            mots = vocabulaire.preparer(dict(corps.get("mots") or {}), lg)
        except ValueError as err:
            raise textes.Erreur("reglages.mot_trop_long", n=vocabulaire.LONGUEUR_MAX) from err
        change_lieu = nom != store.lieu
        if change_lieu:
            store.lieu = nom
        change_mots = vocabulaire.poser(store, lg, mots)
        if change_lieu or change_mots:
            await store.async_save()
            _LOGGER.warning("%s : lieu « %s », vocabulaire %s (par %s).", PRODUIT, nom or "par défaut", mots or "par défaut", par)
            hass.config_entries.async_schedule_reload(coord.config_entry.entry_id)
        return textes.t("reglages.lieu_fait", lg)
    if op == "module":
        m = str(corps.get("module") or "")
        if m not in modules.TOUS:
            raise textes.Erreur("module.inconnu", liste=m)
        voulus = set(modules.actifs(store))
        if corps.get("actif"):
            voulus.add(m)
        else:
            voulus.discard(m)
        manque = modules.manques(voulus, lg)
        if manque:
            raise textes.Erreur("reglages.dependances", detail=" ".join(manque.values()))
        if m == "protections" and not corps.get("actif") and modules.demandeurs(store) and not corps.get("confirmer"):
            raise textes.Erreur("reglages.protections_confirmer", liste=", ".join(modules.demandeurs(store)))
        actives, desactives = modules.regler(hass, store, voulus, par)
        if actives or desactives:
            await store.async_save()
            hass.config_entries.async_schedule_reload(coord.config_entry.entry_id)
        return textes.t("reglages.module_fait", lg, module=modules.nom(m, lg))
    if op == "son":
        # 2.5.12.10 : niveau sonore d'un appareil (mesure, réaction, mode, seuil général, seuils par plage, plancher).
        from . import son

        if not son.actif_module(store):
            raise textes.Erreur("son.module_coupe")
        pc = store.pcs.get(str(corps.get("pc") or ""))
        if pc is None:
            raise textes.Erreur("commun.appareil_disparu")
        donnees: dict[str, Any] = {}
        for cle in ("actif", "reagir"):
            if cle in corps:
                donnees[cle] = bool(corps[cle])
        if corps.get("mode") in son.MODES_SEUIL:
            donnees["seuil_mode"] = corps["mode"]
        try:
            if corps.get("general_dbfs") is not None:
                donnees["seuil_dbfs"] = max(-70, min(0, int(round(float(corps["general_dbfs"])))))
            plages = corps.get("plages")
            if isinstance(plages, dict):
                donnees["seuils_plages"] = [{"plage": k, "seuil_dbfs": max(-70, min(0, int(round(float(v)))))}
                                            for k, v in plages.items() if v not in (None, "")]
                donnees["plage_effacer"] = [k for k, v in plages.items() if v in (None, "")]
            if "plancher_manuel_dbfs" in corps:
                v = corps["plancher_manuel_dbfs"]
                if v in (None, ""):
                    donnees["plancher_auto"] = True
                else:
                    donnees["plancher_dbfs"] = max(-120, min(-10, int(round(float(v)))))
            son.regler_service(pc, son.etat(pc), donnees)
        except (TypeError, ValueError) as err:
            raise textes.Erreur("son.plage_inconnue", plage=str(err)) from err
        await store.async_save()
        coord.async_update_listeners()
        _LOGGER.warning("%s : niveau sonore de « %s » réglé (%s, par %s).", PRODUIT, pc.get("name"),
                        {k: v for k, v in donnees.items()}, par)
        return textes.t("reglages.son_fait", lg, appareil=str(pc.get("name") or ""))
    if op == "droit":
        ident, fonction = str(corps.get("role") or ""), str(corps.get("fonction") or "")
        r = store.roles.get(ident)
        if r is None:
            raise textes.Erreur("roles.inconnu")
        if fonction not in roles.FONCTIONS:
            raise textes.Erreur("reglages.inconnu")
        fonctions = [f for f in (r.get("fonctions") or []) if f != fonction] + ([fonction] if corps.get("actif") else [])
        roles.modifier(hass, store, ident, str(r.get("nom") or ""), fonctions, int(r.get("pause_max") or 0), par)
        await store.async_save()
        await coord.async_ecrire()
        return textes.t("reglages.droit_fait", lg, role=str(r.get("nom") or ident))
    raise textes.Erreur("reglages.inconnu")


class PcParentalReglagesAdminView(HomeAssistantView):
    url = URL
    name = "api:pc_parental:reglages_admin"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _admin(self, request: web.Request) -> web.Response | None:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin", "retour": textes.t("commun.reserve_admins", textes.ha(self.hass))},
                             status_code=403)
        return None

    async def get(self, request: web.Request) -> web.Response:
        if refus := self._admin(request):
            return refus
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        return self.json(etat(self.hass, coord))

    async def post(self, request: web.Request) -> web.Response:
        if refus := self._admin(request):
            return refus
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        try:
            corps = await request.json()
        except ValueError:
            corps = None
        if not isinstance(corps, dict):
            return self.json({"ok": False, "error": "json"}, status_code=400)
        utilisateur = request.get("hass_user")
        par = str(getattr(utilisateur, "name", "") or "admin")
        try:
            fait = await regler(self.hass, coord, corps, par)
        except (textes.Erreur, ValueError) as err:
            retour = textes.erreur(err, textes.ha(self.hass)) if isinstance(err, textes.Erreur) else str(err)
            return self.json({"ok": False, "error": "refus", "retour": retour}, status_code=400)
        return self.json({**etat(self.hass, coord), "fait": fait})
