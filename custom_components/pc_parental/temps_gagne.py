"""Temps gagné (1.89.0, point 14.6, module « Temps gagné », dépend de « Temps d'écran »).

Une tâche cochée dans une liste de tâches Home Assistant (entité `todo.*`) donne du temps en plus sur les
appareils de la personne : tant de minutes par tâche, au plus tant par jour.

- Réglage par personne (Configurer → Temps gagné) : la liste, les minutes par tâche, le plafond du jour.
- Les tâches déjà cochées quand la liste est choisie ne comptent pas (pas de bonus rétroactif).
- Une tâche décochée puis recochée ne compte qu'une fois (son identifiant est retenu).
- Plafond atteint : la tâche est notée, la personne est prévenue, pas de temps en plus.

Stockage : `store.temps_gagne[personne] = {"liste", "minutes", "max"}` (réglages) et
`store.temps_gagne_suivi[personne] = {"date", "accorde", "vus"}` (le compte du jour, les tâches déjà vues).
"""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers.event import async_track_state_change_event
from homeassistant.util import dt as dt_util

from . import textes

from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

MINUTES_DEFAUT = 15
MAX_DEFAUT = 60
VUS_MAX = 500
_CLE_ECOUTE = f"{DOMAIN}_temps_gagne_ecoute"


def reglages(store) -> dict[str, dict[str, Any]]:
    r = getattr(store, "temps_gagne", None)
    if r is None:
        store.temps_gagne = r = {}
    return r


def suivi(store) -> dict[str, dict[str, Any]]:
    s = getattr(store, "temps_gagne_suivi", None)
    if s is None:
        store.temps_gagne_suivi = s = {}
    return s


def regler(store, personne: str, liste: str, minutes: int, plafond: int) -> None:
    """Pose (ou retire, liste vide) le réglage d'une personne ; le suivi repart de zéro."""
    personne = str(personne or "").strip()
    if not personne.startswith("person."):
        raise ValueError("personne")
    liste = str(liste or "").strip()
    r = reglages(store)
    suivi(store).pop(personne, None)
    if not liste:
        r.pop(personne, None)
        return
    if not liste.startswith("todo."):
        raise ValueError("liste")
    minutes, plafond = int(minutes), int(plafond)
    if not 1 <= minutes <= 240 or not 1 <= plafond <= 720 or plafond < minutes:
        raise ValueError("minutes")
    r[personne] = {"liste": liste, "minutes": minutes, "max": plafond}


async def _faites(hass: HomeAssistant, liste: str) -> dict[str, str] | None:
    """Les tâches cochées d'une liste : {uid: intitulé}, ou None si la liste ne répond pas."""
    try:
        reponse = await hass.services.async_call(
            "todo", "get_items", {"entity_id": liste, "status": ["completed"]}, blocking=True, return_response=True)
    except Exception as err:  # noqa: BLE001 - une liste absente ou en panne ne doit rien casser
        _LOGGER.warning("Vision (temps gagné) : la liste %s ne répond pas (%s).", liste, err)
        return None
    items = ((reponse or {}).get(liste) or {}).get("items") or []
    une_tache = textes.t("tg.une_tache", textes.ha(hass))
    return {str(i.get("uid") or i.get("summary")): str(i.get("summary") or une_tache) for i in items if i.get("uid") or i.get("summary")}


def compter(store, personne: str, faites: dict[str, str], aujourd_hui: str) -> list[tuple[str, int]]:
    """Logique pure : les tâches nouvellement cochées et les minutes qu'elles donnent (0 au-delà du plafond)."""
    regle = reglages(store).get(personne)
    if not regle:
        return []
    s = suivi(store)
    etat = s.get(personne)
    if etat is None:
        # Première lecture : ce qui est déjà coché ne compte pas.
        s[personne] = {"date": aujourd_hui, "accorde": 0, "vus": sorted(faites)[:VUS_MAX]}
        return []
    if etat.get("date") != aujourd_hui:
        etat["date"], etat["accorde"] = aujourd_hui, 0
    vus = set(etat.get("vus") or [])
    sortie: list[tuple[str, int]] = []
    for uid, intitule in faites.items():
        if uid in vus:
            continue
        vus.add(uid)
        donne = min(int(regle["minutes"]), max(0, int(regle["max"]) - int(etat.get("accorde") or 0)))
        etat["accorde"] = int(etat.get("accorde") or 0) + donne
        sortie.append((intitule, donne))
    etat["vus"] = sorted(vus)[-VUS_MAX:]
    return sortie


async def async_verifier(hass: HomeAssistant, coord, personne: str) -> list[tuple[str, int]]:
    from . import modules

    if modules.coupe(coord.store, "temps_gagne") or modules.coupe(coord.store, "temps"):
        return []
    regle = reglages(coord.store).get(personne)
    if not regle:
        return []
    faites = await _faites(hass, regle["liste"])
    if faites is None:
        return []
    gains = compter(coord.store, personne, faites, dt_util.now().strftime("%Y-%m-%d"))
    if not gains:
        await coord.store.async_save()
        return []
    appareils = [pc for pc in coord.store.pcs.values() if str(pc.get("person") or "") == personne]
    avis = not modules.coupe(coord.store, "messages")
    for intitule, minutes in gains:
        fin = 0.0
        if minutes > 0:
            for pc in appareils:
                fin = max(fin, coord.store.ajouter_temps(pc, minutes))
            texte = textes.Phrase("tg.fait", tache=intitule, minutes=minutes,
                                  heure=lambda lg, fin=fin: textes.heure(fin, lg)) if fin else None
        else:
            texte = textes.Phrase("tg.plafond", tache=intitule, max=reglages(coord.store)[personne]["max"])
        _LOGGER.info("Vision (temps gagné) : %s, « %s » → %s min.", personne, intitule, minutes)
        if avis and texte:
            for pc in appareils:
                try:
                    coord.store.add_message(pc["id"], texte.en(textes.pour(hass, pc)), titre="Vision")
                except ValueError:
                    pass
    await coord.async_ecrire(structurel=False)
    return gains


@callback
def async_brancher(hass: HomeAssistant, coord) -> None:
    """(Re)branche l'écoute des listes réglées ; à rappeler après chaque changement de réglage."""
    ancien = hass.data.pop(_CLE_ECOUTE, None)
    if ancien:
        ancien()
    par_liste: dict[str, list[str]] = {}
    for personne, regle in reglages(coord.store).items():
        par_liste.setdefault(regle["liste"], []).append(personne)
    if not par_liste:
        return

    @callback
    def _change(event: Event) -> None:
        for personne in par_liste.get(event.data.get("entity_id"), []):
            hass.async_create_task(async_verifier(hass, coord, personne))

    hass.data[_CLE_ECOUTE] = async_track_state_change_event(hass, list(par_liste), _change)
    # Première lecture : ce qui est déjà coché est noté comme vu.
    for personne in list(reglages(coord.store)):
        if personne not in suivi(coord.store):
            hass.async_create_task(async_verifier(hass, coord, personne))


@callback
def async_debrancher(hass: HomeAssistant) -> None:
    ancien = hass.data.pop(_CLE_ECOUTE, None)
    if ancien:
        ancien()
