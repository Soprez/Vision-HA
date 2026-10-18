"""Réveil (lot 1) : la porte de l'appli (téléphone, TV) — POST /api/pc_parental/reveils.

Même modèle et mêmes droits que les services (reveil.peut), avec les fonctions de rôle de l'APPAREIL qui appelle
(et sa personne principale comme « soi »). Authentification : id + secret de l'appareil, comme le relevé.
Contrat détaillé : docs/reveil-contrat-appareils.md (§ Appli).

Corps : {"id", "secret", "action", ...}. Actions : liste · creer {reveil} · modifier {reveil_id, reveil} ·
activer · desactiver · sauter {date?} · reprendre {date?} · supprimer · sonner · reporter {minutes?} · arreter
(toutes avec reveil_id). Réponses : 200 {"ok": true, ...} ; 400 {"error": "invalide", "cle", "retour"} ;
401 auth ; 403 {"error": "droits"} ou {"error": "module"} ; 404 {"error": "introuvable"}.
"""

from __future__ import annotations

import logging
from datetime import timedelta
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import modules, reveil
from . import reveil_calcul as calcul
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/reveils"
ACTIONS_APPLI = ("liste", "creer", "modifier", "activer", "desactiver", "sauter", "reprendre", "supprimer",
                 "sonner", "reporter", "arreter")


def _vue_appli(hass, store, r, droits, personne) -> dict[str, Any]:
    v = reveil.vue(hass, store, r)
    v["peut"] = {a: reveil.peut(droits, personne, a, r) for a in reveil.ACTIONS if a != "creer"}
    v["automatisation"] = r.get("origine") == "automatisation"
    return v


def _demandeurs(store, droits, personne) -> list[dict[str, Any]]:
    """Pour qui cet appareil peut créer un réveil, avec les appareils proposés."""
    personnes = store.personnes_maison() if "reveil_gerer" in droits else ([personne] if personne else [])
    sortie = []
    for p in personnes:
        if not reveil.peut(droits, personne, "creer", None, p):
            continue
        sortie.append({"personne": p, "appareils": [{"id": str(pc.get("id")), "nom": str(pc.get("name") or pc.get("id"))}
                                                     for pc in store.appareils_de(p)]})
    return sortie


def _noms(hass, personnes: set[str]) -> dict[str, str]:
    """Le nom affiché de chaque personne citée (l'appli ne montre jamais « person.… »)."""
    sortie = {}
    for p in sorted(x for x in personnes if x):
        etat = hass.states.get(p)
        sortie[p] = str(etat.name) if etat is not None and etat.name else p.split(".", 1)[-1]
    return sortie


class PcParentalReveilsView(HomeAssistantView):
    url = URL
    name = "api:pc_parental:reveils"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _erreur(self, statut: int, erreur: str, **plus: Any) -> web.Response:
        return self.json({"ok": False, "error": erreur, **plus}, status_code=statut)

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps = await request.json()
        except ValueError:
            return self._erreur(400, "json")
        if not isinstance(corps, dict):
            return self._erreur(400, "json")
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord = entrees[0] if entrees else None
        if coord is None:
            return self._erreur(503, "loading")
        store = coord.store
        pc = store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self._erreur(401, "auth")
        if refus := modules.refus(self, store, reveil.MODULE):
            return refus
        action = str(corps.get("action") or "liste")
        if action not in ACTIONS_APPLI:
            return self._erreur(400, "action")
        droits = reveil.fonctions_appareil(store, pc)
        personne = str(pc.get("person") or "")
        par = str(pc.get("name") or pc.get("id"))
        try:
            sortie = await self._agir(coord, pc, action, corps, droits, personne, par)
        except reveil.Refus:
            return self._erreur(403, "droits")
        except calcul.ReveilInvalide as err:
            statut = 404 if err.cle == "introuvable" else 400
            return self._erreur(statut, "introuvable" if statut == 404 else "invalide", cle=err.cle, retour=str(err))
        return self.json({"ok": True, **sortie})

    async def _agir(self, coord, pc, action, corps, droits, personne, par) -> dict[str, Any]:
        hass, store, moteur = self.hass, coord.store, coord.reveil
        if action == "liste":
            visibles = [r for r in reveil.reveils(store).values() if reveil.peut(droits, personne, "voir", r)]
            visibles.sort(key=lambda x: (str(x.get("demandeur")), str(x.get("nom"))))
            demandeurs = _demandeurs(store, droits, personne)
            return {"reveils": [_vue_appli(hass, store, r, droits, personne) for r in visibles],
                    "droits": {"gerer": "reveil_gerer" in droits, "propres": "reveil_propres" in droits},
                    "demandeurs": demandeurs, "moi": personne,
                    "noms": _noms(hass, {str(r.get("demandeur") or "") for r in visibles}
                                  | {d["personne"] for d in demandeurs} | {personne})}
        if action == "creer":
            brut = dict(corps.get("reveil") or {})
            brut.setdefault("demandeur", personne)
            reveil.exiger(droits, personne, "creer", None, str(brut.get("demandeur") or ""), brut)
            r = reveil.creer(store, brut, par, "appli")
            moteur.suivre(r)
            moteur.evaluer(r)
            await moteur.async_publier(nouveaux=True)
            return {"reveil": _vue_appli(hass, store, r, droits, personne)}
        r = reveil.trouver(hass, store, str(corps.get("reveil_id") or ""))
        reveil.exiger(droits, personne, action, r)
        if action == "modifier":
            brut = dict(corps.get("reveil") or {})
            reveil.exiger(droits, personne, "modifier", r, brut=brut)
            if "demandeur" in brut:
                reveil.exiger(droits, personne, "modifier", None, str(brut["demandeur"]), brut)
            nom_avant = r.get("nom")
            reveil.modifier(store, r, brut, par)
            moteur.suivre(r)
            moteur.evaluer(r)
            if r.get("nom") != nom_avant:
                from .reveil_entites import renommer

                renommer(hass, r)
        elif action in ("activer", "desactiver"):
            if bool(r.get("actif")) != (action == "activer"):
                r["actif"] = action == "activer"
                reveil._horodater(r, par)
        elif action == "sauter":
            jour = corps.get("date") or calcul.prochaine_date(r, reveil.maintenant())
            if jour:
                reveil.sauter(r, jour, par)
        elif action == "reprendre":
            jour = corps.get("date") or next(iter(r.get("sauter") or []), "")
            if jour:
                reveil.reprendre(r, jour, par)
        elif action == "supprimer":
            reveil.supprimer(store, r, par)
            moteur.oublier(r["id"])
            from .reveil_entites import retirer

            retirer(hass, r["id"])
            await moteur.async_publier()
            return {"supprime": r["id"]}
        elif action in ("reporter", "arreter", "sonner"):
            occurrence = str((r.get("vivant") or {}).get("occurrence") or "")
            if action == "reporter":
                minutes = calcul._borne(corps.get("minutes") or (r.get("repetition") or {}).get("minutes") or 9, 1, 60, "minutes")
                jusqu_a = reveil.maintenant() + timedelta(minutes=minutes)
                ordre = {"type": "reporter", "reveil": r["id"], "occurrence": occurrence, "minutes": minutes,
                         "jusqu_a": jusqu_a.isoformat(), "par": "appli"}
                reveil._vivant(r, "reporte", jusqu_a + timedelta(minutes=1), occurrence=occurrence, par="appli")
            elif action == "arreter":
                ordre = {"type": "arreter", "reveil": r["id"], "occurrence": occurrence, "par": "appli"}
                reveil._vivant(r, "arrete", reveil.maintenant() + timedelta(hours=1), occurrence=occurrence, par="appli")
            else:
                import secrets

                ordre = {"type": "sonner", "reveil": r["id"], "occurrence": "ordre:" + secrets.token_hex(4),
                         "nom": r.get("nom"), "source": {k: v for k, v in (r.get("source") or {}).items() if v},
                         "repli": "sonnerie", "volume": r.get("volume"), "repetition": r.get("repetition"),
                         "arret_auto_min": int(reveil.reglages(store)["arret_auto_min"]),
                         "allumer_tv": bool(r.get("allumer_tv")), "par": "appli"}
            reveil.ordonner(store, reveil.cibles(store, r), ordre)
        await moteur.async_publier()
        _LOGGER.info("%s : réveil « %s » : %s depuis « %s ».", PRODUIT, r.get("nom"), action, par)
        return {"reveil": _vue_appli(hass, store, r, droits, personne)}


def enregistrer_vues(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalReveilsView(hass))
