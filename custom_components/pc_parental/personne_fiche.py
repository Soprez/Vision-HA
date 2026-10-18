"""Refonte UX, lot 3 : la fiche d'une personne (B2, HA-05, HA-20, EX-05).

Tout ce qui concerne une personne au même endroit : Résumé, Horaires, Contenus (catégories et règles selon le lieu),
Réveils, Récompenses (temps gagné), Sur ses écrans (widgets HA), Rôle. Les réglages qui n'existaient que dans
Configurer (temps gagné, règles selon le lieu, rôle de la personne) se font ici, avec les mêmes fonctions ; Configurer
garde son accès (secondaire) jusqu'au lot 4.

    GET  /api/pc_parental/personne/admin?personne=person.x   → la fiche (administrateurs seulement)
    POST /api/pc_parental/personne/admin   {"op": "role", "personne", "role"}            ("" : rôle par défaut)
                                           {"op": "temps_gagne", "personne", "liste", "minutes", "max"} (liste "" : retirer)
                                           {"op": "regle_lieu", "personne", "zone", "etiquettes"}      ([] : retirer)

`resume_appli()` donne à l'appli (réponse /parent, lecture seule) un résumé de la même fiche.
"""
from __future__ import annotations

from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import modules, regles_lieu, roles, temps_gagne, textes
from .const import DOMAIN

URL = "/api/pc_parental/personne/admin"

# Les onglets de la fiche et la fonction qui les fait exister (None : toujours là) ; voir _modules.
ONGLETS: tuple[tuple[str, str | None], ...] = (
    ("resume", None), ("horaires", "plages"), ("contenus", None), ("reveils", "reveil"),
    ("recompenses", "temps_gagne"), ("ecrans", "entites"), ("role", None),
)


def _coord(hass: HomeAssistant):
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


def _nom(hass: HomeAssistant, eid: str) -> str:
    e = hass.states.get(eid) if eid else None
    return str(e.name) if e is not None and e.name else (eid.split(".")[-1] if eid else "")


def personnes(hass: HomeAssistant, coord) -> list[dict[str, Any]]:
    """Les personnes qui ont une fiche, dans l'ordre des pages du tableau (passage ‹ › de l'une à l'autre)."""
    from .dashboard import PREFIXE_PERSONNE, _personnes_du_tableau

    return [{"entite": p["entite"], "cle": p["cle"], "prenom": p["prenom"], "chemin": f"{PREFIXE_PERSONNE}{p['cle']}"}
            for p in _personnes_du_tableau(hass, coord) if p["entite"]]


def onglets(store) -> list[str]:
    """Les onglets permis par les modules (un module coupé : son onglet n'existe pas, pas de refus après coup — EX-05)."""
    m = _modules(store)
    return [o for o, f in ONGLETS if f is None or m[f]]


def _modules(store) -> dict[str, bool]:
    return {
        "plages": not modules.coupe(store, "plages"),
        "reveil": not modules.coupe(store, "reveil"),
        # Le temps gagné vient en plus du temps d'écran : il faut les deux modules.
        "temps_gagne": not modules.coupe(store, "temps_gagne") and not modules.coupe(store, "temps"),
        "regles_lieu": not modules.coupe(store, "regles_lieu") and not modules.coupe(store, "blocage", "filtrage"),
        "entites": not modules.coupe(store, "entites"),
        "categories": not modules.coupe(store, "blocage", "filtrage"),
    }


def principal(store, personne: str) -> dict[str, Any] | None:
    """L'appareil qui porte les règles de la personne (le sien, jamais un partagé)."""
    propres = [a for a in store.appareils_de(personne) if a.get("enabled", True) and str(a.get("person") or "") == personne]
    return propres[0] if propres else None


def role_nom(store, personne: str) -> str:
    ident = str(store.roles_personnes.get(personne) or "")
    if ident and ident in store.roles:
        return str(store.roles[ident].get("nom") or ident)
    pc = principal(store, personne)
    return roles.nom(store, pc) if pc is not None else roles.nom_defaut(store)


def _recompense(hass: HomeAssistant, store, personne: str) -> dict[str, Any] | None:
    r = temps_gagne.reglages(store).get(personne)
    if not r:
        return None
    suivi = temps_gagne.suivi(store).get(personne) or {}
    return {"liste": r.get("liste"), "liste_nom": _nom(hass, str(r.get("liste") or "")), "minutes": int(r.get("minutes") or 0),
            "max": int(r.get("max") or 0), "accorde": int(suivi.get("accorde") or 0)}


def _cat(nom: str, langue: str | None) -> str:
    """Le libellé d'une catégorie dans la langue du lecteur (la donnée reste le nom écrit à l'origine)."""
    return nom if langue == "fr" else textes.categorie(nom, langue)


def _exiger(store, *mods: str) -> None:
    if modules.coupe(store, *mods):
        raise textes.Erreur("commun.module_coupe_long", module=lambda lg: modules.nom(mods[0], lg))


def _regles(hass: HomeAssistant, store, personne: str, langue: str | None) -> list[dict[str, Any]]:
    sortie = []
    for r in regles_lieu.regles(store):
        if r.get("personne") != personne:
            continue
        sortie.append({"zone": r["zone"], "zone_nom": regles_lieu.nom_zone(hass, r["zone"]),
                       "etiquettes": list(r.get("etiquettes") or []),
                       "categories": [_cat(e, langue) for e in r.get("etiquettes") or []]})
    sortie.sort(key=lambda x: x["zone_nom"].lower())
    return sortie


def _reveils(hass: HomeAssistant, store, personne: str) -> list[dict[str, Any]]:
    from . import reveil

    sortie = []
    for r in reveil.reveils(store).values():
        if r.get("demandeur") != personne:
            continue
        v = reveil.vue(hass, store, r)
        sortie.append({"id": v["id"], "nom": v["nom"] or "", "actif": v["actif"], "etat": v["etat"], "prochain": v["prochain"],
                       "sautees": v["sautees"], "appareils": v["appareils_noms"]})
    sortie.sort(key=lambda x: (x["prochain"] is None, x["prochain"] or "", x["nom"].lower()))
    return sortie


def resume_appli(hass: HomeAssistant, store, personne: str, langue: str | None = None) -> dict[str, Any]:
    """Le résumé de la fiche pour l'appli (lecture seule ; les réglages se font dans Home Assistant)."""
    m = _modules(store)
    return {
        "modules": m,
        "role_nom": role_nom(store, personne),
        "reveils": [{"nom": r["nom"], "actif": r["actif"], "prochain": r["prochain"]} for r in _reveils(hass, store, personne)]
        if m["reveil"] else [],
        "recompenses": _recompense(hass, store, personne) if m["temps_gagne"] else None,
        "regles_lieu": [{"zone": r["zone_nom"], "categories": r["categories"]} for r in _regles(hass, store, personne, langue)]
        if m["regles_lieu"] else [],
        "widgets": len(store.entites_personnes.get(personne) or []) if m["entites"] else 0,
    }


def etat(hass: HomeAssistant, coord, personne: str) -> dict[str, Any]:
    """La fiche complète, pour la carte (administrateur)."""
    store = coord.store
    langue = textes.ha(hass)
    liste = personnes(hass, coord)
    pc = principal(store, personne)
    plages = ""
    if pc is not None:
        from homeassistant.helpers import entity_registry as er
        plages = er.async_get(hass).async_get_entity_id("sensor", DOMAIN, f"{DOMAIN}_plages_{pc['id']}") or ""
    m = _modules(store)
    return {
        "ok": True,
        "personne": {"entite": personne, "cle": personne.split(".")[-1], "prenom": _nom(hass, personne),
                     "photo": str((hass.states.get(personne).attributes or {}).get("entity_picture") or "")
                     if hass.states.get(personne) is not None else ""},
        "personnes": liste,
        "onglets": onglets(store),
        "modules": m,
        "principal": pc["id"] if pc else "",
        "planning": plages,
        "role": {"actuel": str(store.roles_personnes.get(personne) or ""), "nom": role_nom(store, personne),
                 "defaut": roles.nom_defaut(store),
                 "options": [{"id": i, "nom": str(r.get("nom") or i)} for i, r in sorted(store.roles.items(), key=lambda x: str(x[1].get("nom") or x[0]).lower())]},
        "recompense": _recompense(hass, store, personne),
        "listes": sorted(({"id": s.entity_id, "nom": str(s.name)} for s in hass.states.async_all("todo")), key=lambda x: x["nom"].lower()),
        "regles": _regles(hass, store, personne, langue),
        "zones": sorted(({"id": s.entity_id, "nom": regles_lieu.nom_zone(hass, s.entity_id)} for s in hass.states.async_all("zone")),
                        key=lambda x: x["nom"].lower()),
        "categories": sorted(({"id": e, "nom": _cat(e, langue)} for e in store.toutes_etiquettes()), key=lambda x: x["nom"].lower())
        if m["categories"] else [],
        "reveils": _reveils(hass, store, personne) if m["reveil"] else [],
        "widgets": len(store.entites_personnes.get(personne) or []),
        "minutes_defaut": temps_gagne.MINUTES_DEFAUT, "max_defaut": temps_gagne.MAX_DEFAUT,
    }


async def regler(hass: HomeAssistant, coord, corps: dict[str, Any], par: str) -> str:
    """Applique un réglage de la fiche ; rend la phrase « fait » dans la langue de HA. textes.Erreur si refusé."""
    store = coord.store
    op = str(corps.get("op") or "")
    personne = str(corps.get("personne") or "")
    if not personne.startswith("person.") or hass.states.get(personne) is None:
        raise textes.Erreur("fiche.personne_inconnue")
    langue = textes.ha(hass)
    if op == "role":
        roles.attribuer_personne(hass, store, personne, str(corps.get("role") or ""), par)
        await coord.async_ecrire()
        return textes.t("fiche.role_fait", langue, prenom=_nom(hass, personne), role=role_nom(store, personne))
    if op == "temps_gagne":
        _exiger(store, "temps_gagne")
        _exiger(store, "temps")
        try:
            temps_gagne.regler(store, personne, str(corps.get("liste") or ""), int(corps.get("minutes") or 0), int(corps.get("max") or 0))
        except ValueError as err:
            raise textes.Erreur("fiche.recompense_" + (str(err) if str(err) in ("liste", "minutes") else "minutes")) from err
        await store.async_save()
        temps_gagne.async_brancher(hass, coord)
        return textes.t("fiche.recompense_faite" if corps.get("liste") else "fiche.recompense_retiree", langue, prenom=_nom(hass, personne))
    if op == "regle_lieu":
        _exiger(store, "regles_lieu")
        _exiger(store, "blocage", "filtrage")
        etiquettes = [str(x) for x in (corps.get("etiquettes") or []) if isinstance(x, str)]
        try:
            regles_lieu.poser(store, personne, str(corps.get("zone") or ""), etiquettes)
        except ValueError as err:
            raise textes.Erreur("fiche.regle_" + (str(err) if str(err) in ("zone", "trop") else "zone")) from err
        await store.async_save()
        return textes.t("fiche.regle_faite" if etiquettes else "fiche.regle_retiree", langue,
                        zone=regles_lieu.nom_zone(hass, str(corps.get("zone") or "")))
    raise textes.Erreur("fiche.reglage_inconnu")


class PcParentalPersonneAdminView(HomeAssistantView):
    url = URL
    name = "api:pc_parental:personne_admin"
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
        personne = str(request.query.get("personne") or "")
        if not personne.startswith("person.") or self.hass.states.get(personne) is None:
            return self.json({"ok": False, "error": "personne", "retour": textes.t("fiche.personne_inconnue", textes.ha(self.hass))},
                             status_code=404)
        return self.json(etat(self.hass, coord, personne))

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
        # FAILLE DE SÉCURITÉ (l'auteur, 16/10) : donner un rôle ou du temps exige le code de déverrouillage, à chaque geste.
        from . import octroi

        if (refus_code := await octroi.exiger(self.hass, coord, request, corps,
                                              str(corps.get("op") or "") in octroi.OPS_FICHE)) is not None:
            return self.json(refus_code, status_code=403)
        utilisateur = request.get("hass_user")
        par = str(getattr(utilisateur, "name", "") or "admin")
        try:
            fait = await regler(self.hass, coord, corps, par)
        except textes.Erreur as err:
            return self.json({"ok": False, "error": "refus", "retour": textes.erreur(err, textes.ha(self.hass))}, status_code=400)
        personne = str(corps.get("personne") or "")
        return self.json({**etat(self.hass, coord, personne), "fait": fait})
