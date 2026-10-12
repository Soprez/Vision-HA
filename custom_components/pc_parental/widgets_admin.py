"""Onglet « Widgets HA » du tableau de bord Vision (1.94.0, point 16.1) : l'API de la carte, réservée aux admins.

Les mêmes réglages que Configurer → Entités Home Assistant (qui reste un accès secondaire), avec les mêmes
fonctions (widgets.regler_personne / regler_appareil / regler_actions) : liste par personne, exceptions par
appareil (en plus, en moins), et pour chaque entité pilotable les actions permises (rien = lecture seule).

    GET  /api/pc_parental/widgets/admin   → l'état complet (personnes, appareils, entités candidates)
    POST /api/pc_parental/widgets/admin   → {"op": "personne", "personne", "entites"}
                                             {"op": "appareil", "pc", "plus", "moins"}
                                             {"op": "actions", "entite", "services"}
                                             {"op": "tous", "entites"}                      (2.4.0)
                                             {"op": "reglage", "entite", "role"?, "qui"?, "confirmer"?,
                                              "nom"?, "icone"?, "affichage"?, "attributs"?,
                                              "toucher"?, "chemin"?}                         (2.4.0, 2.4.3)
"""

from __future__ import annotations

from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import modules, textes, widgets
from .const import DOMAIN

URL = "/api/pc_parental/widgets/admin"
# Les états courants en clair (jamais d'état brut à l'écran) : catalogue serveur, « etat.<état> », langue de HA.
ETATS = frozenset({"on", "off", "open", "closed", "opening", "closing", "unavailable", "unknown", "home", "not_home",
                   "playing", "paused", "idle", "standby", "heat", "cool", "auto"})
CANDIDATES_MAX = 1500


def _coord(hass: HomeAssistant):
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


def fiche(hass: HomeAssistant, store, eid: str) -> dict[str, Any]:
    """Une entité vue par l'administrateur : son nom, sa valeur, ce qu'on peut permettre d'y faire."""
    etat = hass.states.get(eid)
    nom = str(etat.attributes.get("friendly_name") or eid) if etat is not None else eid
    valeur = ""
    if etat is not None:
        unite = str(etat.attributes.get("unit_of_measurement") or "")
        lisible = textes.t(f"etat.{etat.state}", textes.ha(hass)) if etat.state in ETATS else etat.state
        valeur = f"{lisible} {unite}".strip()
    r = widgets.reglage(store, eid)
    attributs = sorted(k for k in (etat.attributes if etat is not None else {}) if k not in widgets.ATTRIBUTS_CACHES)
    return {
        "id": eid, "nom": nom, "valeur": valeur, "domaine": widgets.domaine(eid), "existe": etat is not None,
        "pilotable": widgets.pilotable(eid), "proposees": widgets.services_proposes(eid) if widgets.pilotable(eid) else [],
        "permises": list(store.entites_actions.get(eid) or []),
        # 2.4.0 : le réglage de l'entité et ce qu'on peut y choisir.
        "reglage": r, "roles": widgets.roles_possibles(eid), "attributs_dispo": attributs[:40],
        "liste": widgets.domaine(eid) == "todo",
    }


def etat(hass: HomeAssistant, coord) -> dict[str, Any]:
    store = coord.store
    personnes = []
    for st in sorted(hass.states.async_all("person"), key=lambda s: str(s.name).lower()):
        personnes.append({"entite": st.entity_id, "prenom": st.name,
                          "entites": [fiche(hass, store, e) for e in (store.entites_personnes.get(st.entity_id) or [])]})
    appareils = []
    for pc in sorted(store.pcs.values(), key=lambda p: str(p.get("name") or p["id"]).lower()):
        exc = store.entites_appareils.get(str(pc["id"])) or {}
        personne = str(pc.get("person") or "")
        p_etat = hass.states.get(personne) if personne else None
        appareils.append({
            "id": pc["id"], "nom": str(pc.get("name") or pc["id"]), "personne": personne,
            "prenom": p_etat.name if p_etat is not None else "",
            "plus": [fiche(hass, store, e) for e in (exc.get("plus") or [])],
            "moins": [fiche(hass, store, e) for e in (exc.get("moins") or [])],
        })
    candidates = [{"id": s.entity_id, "nom": str(s.name)} for s in hass.states.async_all()
                  if widgets.domaine(s.entity_id) not in widgets.INTERDITS and not s.entity_id.startswith(("person.", "zone."))]
    candidates.sort(key=lambda c: c["nom"].lower())
    return {
        "ok": True, "module": not modules.coupe(store, "entites"),
        "tous": [fiche(hass, store, e) for e in (store.entites_tous or [])],
        "personnes": personnes, "appareils": appareils, "candidates": candidates[:CANDIDATES_MAX],
    }


async def regler(hass: HomeAssistant, coord, corps: dict[str, Any], par: str) -> bool:
    """Applique un réglage ; rend vrai s'il a changé quelque chose. ValueError si la demande est fausse."""
    op = str(corps.get("op") or "")
    store = coord.store
    liste = lambda cle: [str(x) for x in (corps.get(cle) or []) if isinstance(x, str)]  # noqa: E731
    if op == "personne":
        change = widgets.regler_personne(hass, store, str(corps.get("personne") or ""), liste("entites"), par)
    elif op == "appareil":
        change = widgets.regler_appareil(hass, store, str(corps.get("pc") or ""), liste("plus"), liste("moins"), par)
    elif op == "actions":
        eid = str(corps.get("entite") or "")
        if not widgets.pilotable(eid):
            raise textes.Erreur("wida.non_pilotable")
        change = widgets.regler_actions(hass, store, {eid: liste("services")}, par)
    elif op == "tous":
        change = widgets.regler_tous(hass, store, liste("entites"), par)
    elif op == "reglage":
        voulu = {k: corps[k] for k in ("role", "qui", "confirmer", "nom", "icone", "affichage", "attributs", "toucher", "chemin")
                 if k in corps}
        change = widgets.regler_entite(hass, store, str(corps.get("entite") or ""), voulu, par)
    else:
        raise textes.Erreur("wida.reglage_inconnu")
    if change:
        await store.async_save()
    return change


class PcParentalWidgetsAdminView(HomeAssistantView):
    url = URL
    name = "api:pc_parental:widgets_admin"
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
        # 2.4.3 : les tableaux de bord et vues de Home Assistant, pour « Au toucher ».
        return self.json(etat(self.hass, coord) | {"vues": await widgets.vues_ha(self.hass)})

    async def post(self, request: web.Request) -> web.Response:
        if refus := self._admin(request):
            return refus
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        par = str(getattr(request.get("hass_user"), "name", "") or "un administrateur")
        try:
            await regler(self.hass, coord, corps if isinstance(corps, dict) else {}, par)
        except ValueError as err:
            return self.json({"ok": False, "error": "reglage", "retour": textes.erreur(err, textes.ha(self.hass))},
                             status_code=400)
        return self.json(etat(self.hass, coord) | {"vues": await widgets.vues_ha(self.hass)})
