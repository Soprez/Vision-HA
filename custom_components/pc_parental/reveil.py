"""Réveil (lot 1, côté Home Assistant) : un module Vision activable, piloté par automatisation ou par l'appli.

Principe (étude etude-reveil, révision 2) : HA calcule les occurrences et les **transmet à l'avance** aux
appareils dans le relevé (bloc `reveils`, 48 h) ; chaque appareil arme sa propre alarme et sonne seul, même
si HA ou le réseau sont tombés. HA ne fait donc jamais sonner « à l'heure » : il synchronise, il transmet des
ordres (sonner maintenant, reporter, arrêter) au relevé suivant, et il reçoit les événements des appareils.

Le module ne connaît ni l'école, ni un agenda, ni les vacances : il sonne à une heure (fixe, ponctuelle, lue
dans une entité ou rendue par un modèle), saute une occurrence, reçoit des ordres par services et prévient
par événements. Tout le reste se branche par automatisation (blueprints fournis dans `blueprints/`).

- Magasin : `store.reveils` {id: réveil} et `store.reveil_reglages` (voir REGLAGES_DEFAUT).
- Calcul pur : `reveil_calcul.py`. Entités : `reveil_entites.py`. Services : `reveil_services.py`.
- Contrat avec les appareils : docs/reveil-contrat-appareils.md.
- Droits : `reveil_gerer` (★, tout Demandeur) et `reveil_propres` (ses réveils) ; voir `peut`.
"""

from __future__ import annotations

import copy
import logging
from datetime import datetime, timedelta
from typing import Any

from homeassistant.core import CoreState, HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.util import dt as dt_util

from . import modules, reveil_calcul as calcul
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

MODULE = "reveil"

# Les événements (noms demandés en essai). Utilisables dans n'importe quelle automatisation.
EV_SONNE = "vision_reveil_sonne"
EV_REPORTE = "vision_reveil_reporte"
EV_ARRETE = "vision_reveil_arrete"
EV_ECHEC = "vision_reveil_echec"
EVENEMENTS = {"sonne": EV_SONNE, "reporte": EV_REPORTE, "arrete": EV_ARRETE, "echec": EV_ECHEC}

# Le signal qui fait apparaître les entités d'un réveil neuf (sans recharger l'intégration).
SIGNAL_NOUVEAUX = f"{DOMAIN}_reveils_nouveaux"

REGLAGES_DEFAUT: dict[str, Any] = {
    "heure_evaluation": "20:00",   # réévaluation quotidienne des réveils calculés (pour le lendemain)
    "arret_auto_min": 30,          # l'appareil s'arrête seul au bout de ce délai (événement arrete, par auto)
    "fenetre_avant_min": 60,       # binary_sensor.vision_fenetre_reveil : avant la première occurrence…
    "fenetre_apres_min": 30,       # … et après la dernière
    "synchro_avant_min": 10,       # contrôle « appareil synchronisé ? » et relevé forcé par l'appareil
    "ordre_ttl_min": 15,           # un ordre « sonner » plus vieux n'est plus remis
    "horizon_h": calcul.HORIZON_H,
}

ACTIONS = ("voir", "creer", "modifier", "activer", "desactiver", "sauter", "reprendre", "supprimer",
           "sonner", "reporter", "arreter")
ACTIONS_DU_DEMANDEUR = ("voir", "reporter", "arreter")  # toujours permises au Demandeur visé
TYPES_PAR_DEFAUT = ("telephone", "tablette", "android")  # appareils visés quand la liste est vide

ORIGINES = ("automatisation", "home_assistant", "appli")


class Refus(Exception):
    """Action refusée par les droits (service : Unauthorized ; appli : 403)."""


# ----------------------------------------------------------------------------- Magasin


def reveils(store) -> dict[str, dict[str, Any]]:
    if not isinstance(getattr(store, "reveils", None), dict):
        store.reveils = {}
    return store.reveils


def reglages(store) -> dict[str, Any]:
    brut = getattr(store, "reveil_reglages", None) or {}
    return {**REGLAGES_DEFAUT, **{k: v for k, v in dict(brut).items() if k in REGLAGES_DEFAUT}}


def regler(store, valeurs: dict[str, Any]) -> dict[str, Any]:
    """Change les réglages du module (validés) et rend les réglages complets."""
    actuels = reglages(store)
    nouveaux = dict(actuels)
    for cle_, valeur in (valeurs or {}).items():
        if cle_ not in REGLAGES_DEFAUT:
            raise calcul.ReveilInvalide("reglage", f"Réglage inconnu : {cle_}.", valeur=cle_)
        if cle_ == "heure_evaluation":
            nouveaux[cle_] = calcul.lire_heure(valeur, cle_)
        else:
            bornes = {"arret_auto_min": (1, 120), "fenetre_avant_min": (0, 240), "fenetre_apres_min": (0, 240),
                      "synchro_avant_min": (1, 60), "ordre_ttl_min": (1, 120), "horizon_h": (24, 96)}[cle_]
            nouveaux[cle_] = calcul._borne(valeur, *bornes, cle_)
    store.reveil_reglages = {k: v for k, v in nouveaux.items() if v != REGLAGES_DEFAUT[k]}
    return nouveaux


def actif(store) -> bool:
    return modules.actif(store, MODULE)


def maintenant() -> datetime:
    return dt_util.now()


def cibles(store, r: dict[str, Any]) -> list[dict[str, Any]]:
    """Les appareils qui sonnent pour ce réveil : sa liste, ou à défaut les téléphones et tablettes du Demandeur."""
    from . import matrice

    if r.get("appareils"):
        return [store.pcs[i] for i in r["appareils"] if i in store.pcs]
    demandeur = str(r.get("demandeur") or "")
    return [pc for pc in store.pcs.values()
            if str(pc.get("person") or "") == demandeur and matrice.type_appareil(pc) in TYPES_PAR_DEFAUT]


def de_l_appareil(store, pc_id: str) -> list[dict[str, Any]]:
    return [r for r in reveils(store).values() if any(str(c.get("id")) == pc_id for c in cibles(store, r))]


def trouver(hass: HomeAssistant, store, ref: str) -> dict[str, Any]:
    """Un réveil par son identifiant, une de ses entités (switch.vision_reveil_…) ou son nom exact (unique)."""
    ref = str(ref or "").strip()
    tous = reveils(store)
    if ref in tous:
        return tous[ref]
    if "." in ref:
        from homeassistant.helpers import entity_registry as er

        entree = er.async_get(hass).async_get(ref)
        if entree is not None and entree.platform == DOMAIN and entree.unique_id.startswith(f"{DOMAIN}_reveil_"):
            ident = entree.unique_id[len(f"{DOMAIN}_reveil_"):].split("_", 1)[0]
            if ident in tous:
                return tous[ident]
    memes = [r for r in tous.values() if str(r.get("nom") or "").lower() == ref.lower()]
    if len(memes) == 1:
        return memes[0]
    raise calcul.ReveilInvalide("introuvable", f"Réveil « {ref} » introuvable.", valeur=ref)


# ----------------------------------------------------------------------------- Droits


def fonctions_personne(store, personne: str) -> set[str]:
    """Les fonctions de rôle d'une personne : celles de son rôle, et celles des rôles de ses propres appareils."""
    from . import roles

    trouvees: set[str] = set()
    ident = str(store.roles_personnes.get(personne, "") or "")
    if ident in store.roles:
        trouvees |= set(store.roles[ident].get("fonctions") or [])
    for pc in store.pcs.values():
        if str(pc.get("person") or "") == personne:
            trouvees |= set(roles.fonctions(store, pc))
    return set(modules.fonctions_effectives(store, sorted(trouvees)))


def fonctions_appareil(store, pc: dict[str, Any]) -> set[str]:
    from . import roles

    return set(modules.fonctions_effectives(store, roles.fonctions(store, pc)))


def peut(droits: set[str] | None, personne: str, action: str, r: dict[str, Any] | None = None,
         demandeur: str = "", brut: dict[str, Any] | None = None) -> bool:
    """La règle commune à l'appli et aux services (spécification §5).

    droits None = tous les droits (automatisation sans utilisateur, administrateur de HA).
    - `reveil_gerer` : tout, sur tous les Demandeurs (y compris `impose`) ;
    - le Demandeur visé peut toujours voir, reporter et arrêter ses réveils ;
    - `reveil_propres` : créer, modifier, activer, sauter, supprimer, faire sonner SES réveils non imposés,
      sans pouvoir poser `impose`.
    """
    if droits is None:
        return True
    if action not in ACTIONS:
        return False
    if "reveil_gerer" in droits:
        return True
    vise = str((r or {}).get("demandeur") or demandeur or "")
    if not personne or personne != vise:
        return False
    if action in ACTIONS_DU_DEMANDEUR:
        return True
    if "reveil_propres" not in droits:
        return False
    if (r or {}).get("impose") or (brut or {}).get("impose"):
        return False
    return True


def exiger(droits: set[str] | None, personne: str, action: str, r: dict[str, Any] | None = None,
           demandeur: str = "", brut: dict[str, Any] | None = None) -> None:
    if not peut(droits, personne, action, r, demandeur, brut):
        raise Refus(action)


def personne_de(hass: HomeAssistant, user_id: str) -> str:
    """La personne HA liée à un utilisateur (attribut user_id de person.*), ou ""."""
    for etat in hass.states.async_all("person"):
        if str(etat.attributes.get("user_id") or "") == str(user_id):
            return etat.entity_id
    return ""


async def droits_utilisateur(hass: HomeAssistant, store, user_id: str | None) -> tuple[set[str] | None, str]:
    """(droits, personne) pour un appel de service : sans utilisateur ou administrateur → tous les droits."""
    if not user_id:
        return None, ""
    utilisateur = await hass.auth.async_get_user(user_id)
    if utilisateur is not None and utilisateur.is_admin:
        return None, personne_de(hass, user_id)
    personne = personne_de(hass, user_id) if utilisateur is not None else ""
    if not personne:
        return set(), ""
    return fonctions_personne(store, personne), personne


# ----------------------------------------------------------------------------- Opérations (magasin seul)


def _resoudre_appareils(store, demandeur: str, valeurs: list[str]) -> list[str]:
    siens = {str(pc.get("id")): pc for pc in store.appareils_de(demandeur)}
    sortie: list[str] = []
    for brut in valeurs or []:
        brut = str(brut).strip()
        ident = brut if brut in store.pcs else (store.by_name(brut) or "")
        if ident not in siens:
            raise calcul.ReveilInvalide("appareil", f"« {brut} » n'est pas un appareil de {demandeur}.",
                                        valeur=brut, demandeur=demandeur)
        if ident not in sortie:
            sortie.append(ident)
    return sortie


def _horodater(r: dict[str, Any], par: str) -> None:
    r["version"] = int(r.get("version") or 0) + 1
    r["modifie"] = maintenant().isoformat()
    r["modifie_par"] = par


def creer(store, brut: dict[str, Any], par: str, origine: str) -> dict[str, Any]:
    brut = dict(brut)
    if "appareils" in brut:
        brut["appareils"] = _resoudre_appareils(store, str(brut.get("demandeur") or ""), brut["appareils"])
    r = calcul.normaliser(brut)
    r["id"] = calcul.nouvel_id()
    while r["id"] in reveils(store):
        r["id"] = calcul.nouvel_id()
    r["origine"] = origine if origine in ORIGINES else "home_assistant"
    r["cree"] = maintenant().isoformat()
    r["cree_par"] = par
    r.update({"calcul": {}, "vivant": {}, "vus": {}, "signales": [], "version": 0})
    _horodater(r, par)
    reveils(store)[r["id"]] = r
    _LOGGER.warning("%s : réveil « %s » créé pour %s (par %s).", PRODUIT, r["nom"], r["demandeur"], par)
    return r


def modifier(store, r: dict[str, Any], brut: dict[str, Any], par: str) -> dict[str, Any]:
    brut = {k: v for k, v in dict(brut).items() if k not in ("id", "origine", "cree", "cree_par", "version")}
    demandeur = str(brut.get("demandeur") or r.get("demandeur") or "")
    if "appareils" in brut:
        brut["appareils"] = _resoudre_appareils(store, demandeur, brut["appareils"])
    elif "demandeur" in brut and brut["demandeur"] != r.get("demandeur"):
        brut["appareils"] = []  # les appareils de l'ancien Demandeur ne le suivent pas
    neuf = calcul.normaliser(brut, r)
    if neuf["declenchement"] != r.get("declenchement"):
        neuf["calcul"] = {}
    r.clear()
    r.update(neuf)
    _horodater(r, par)
    return r


def supprimer(store, r: dict[str, Any], par: str) -> None:
    reveils(store).pop(r["id"], None)
    for pc in store.pcs.values():
        if isinstance(pc.get("reveil_recu"), dict):
            pc["reveil_recu"].pop(r["id"], None)
    _LOGGER.warning("%s : réveil « %s » supprimé (par %s).", PRODUIT, r.get("nom"), par)


def sauter(r: dict[str, Any], jour: str, par: str) -> None:
    jours = set(r.get("sauter") or [])
    jours.add(calcul.lire_date(jour))
    aujourd_hui = maintenant().date().isoformat()
    r["sauter"] = sorted(j for j in jours if j >= aujourd_hui)  # le passé ne sert plus
    _horodater(r, par)


def reprendre(r: dict[str, Any], jour: str, par: str) -> None:
    jour = calcul.lire_date(jour)
    r["sauter"] = [j for j in (r.get("sauter") or []) if j != jour]
    _horodater(r, par)


def ordonner(store, pcs: list[dict[str, Any]], ordre: dict[str, Any]) -> list[str]:
    """Met un ordre (sonner, reporter, arreter) dans la file de chaque appareil ; rend leurs identifiants."""
    import secrets

    emis = maintenant().isoformat()
    vises = []
    for pc in pcs:
        file = pc.setdefault("reveil_ordres", [])
        file.append({"id": "o" + secrets.token_hex(4), "emis": emis, **ordre})
        del file[:-20]
        vises.append(str(pc.get("id")))
    return vises


# ----------------------------------------------------------------------------- Ce que voient entités et appli


def etat(store, r: dict[str, Any], quand: datetime | None = None) -> str:
    """inactif · arme · sonne · reporte · arrete · echec · non_synchronise."""
    quand = quand or maintenant()
    if not actif(store) or not r.get("actif"):
        return "inactif"
    vivant = r.get("vivant") or {}
    if vivant.get("etat") in calcul.ETATS and vivant.get("jusqu_a"):
        try:
            if datetime.fromisoformat(vivant["jusqu_a"]) > quand:
                return str(vivant["etat"])
        except ValueError:
            pass
    return "arme" if calcul.prochaine(r, quand) else "inactif"


def signature(store, r: dict[str, Any], quand: datetime | None = None) -> str:
    """L'empreinte de la CONFIGURATION de ce réveil (champs envoyés, calcul figé, dates sautées), sans la fenêtre
    d'occurrences : le temps qui passe ne la change pas, une modification si. L'appareil la renvoie (`recu`)."""
    element = element_releve(store, r, quand or maintenant(), avec_signature=False)
    element.pop("occurrences", None)
    return calcul.signature({**element, "calcul": r.get("calcul") or {}, "sauter": r.get("sauter") or [],
                             "declenchement": r.get("declenchement")})


def a_jour(store, pc: dict[str, Any], r: dict[str, Any], quand: datetime | None = None) -> bool:
    """L'appareil a armé la dernière configuration de ce réveil."""
    return (pc.get("reveil_recu") or {}).get(r["id"]) == signature(store, r, quand)


def synchronises(store, r: dict[str, Any], quand: datetime | None = None) -> list[str]:
    return [str(pc.get("id")) for pc in cibles(store, r) if a_jour(store, pc, r, quand)]


def vue(hass: HomeAssistant, store, r: dict[str, Any], quand: datetime | None = None) -> dict[str, Any]:
    """Le réveil tel que le montrent les entités, l'appli et le service reveil_lister."""
    quand = quand or maintenant()
    prochaine = calcul.prochaine(r, quand)
    toutes = calcul.occurrences(r, quand, 24 * 8, inclure_sautees=True)
    suivantes = [o for o in toutes if not o["sautee"]]
    sautees = [o["date"] for o in toutes if o["sautee"]]
    noms = {str(pc.get("id")): str(pc.get("name") or pc.get("id")) for pc in store.pcs.values()}
    ids = [str(pc.get("id")) for pc in cibles(store, r)]
    raison = prochaine["raison"] if prochaine else ((r.get("calcul") or {}).get("raison") or "")
    return {
        "id": r["id"], "nom": r.get("nom"), "demandeur": r.get("demandeur"), "actif": bool(r.get("actif")),
        "etat": etat(store, r, quand), "prochain": prochaine["heure"] if prochaine else None,
        "raison": raison, "occurrence_suivante": suivantes[1]["heure"] if len(suivantes) > 1 else None,
        "sautees": sautees, "declenchement": copy.deepcopy(r.get("declenchement")), "sauter": list(r.get("sauter") or []),
        "source": copy.deepcopy(r.get("source")), "volume": dict(r.get("volume") or {}),
        "repetition": dict(r.get("repetition") or {}), "impose": bool(r.get("impose")),
        "allumer_tv": bool(r.get("allumer_tv")), "appareils": ids, "appareils_noms": [noms.get(i, i) for i in ids],
        "appareils_choisis": list(r.get("appareils") or []), "synchronises": synchronises(store, r, quand),
        "origine": r.get("origine"), "cree_par": r.get("cree_par"), "version": int(r.get("version") or 0),
        "calcul": copy.deepcopy(r.get("calcul") or {}),
    }


def libelle_source(r: dict[str, Any]) -> str:
    s = r.get("source") or {}
    if s.get("libelle"):
        return str(s["libelle"])
    if s.get("type") == "sonnerie":
        return "sonnerie"
    return str(s.get("uri") or s.get("type") or "")


# ----------------------------------------------------------------------------- Relevé : HA → appareil


def element_releve(store, r: dict[str, Any], quand: datetime, avec_signature: bool = True) -> dict[str, Any]:
    reg = reglages(store)
    occs = calcul.occurrences(r, quand, int(reg["horizon_h"]))
    element = {
        "id": r["id"], "nom": r.get("nom"), "demandeur": r.get("demandeur"),
        "occurrences": [{"id": o["id"], "heure": o["heure"], "heure_locale": o["heure_locale"], "date": o["date"],
                         "raison": o["raison"]} for o in occs],
        "source": {k: v for k, v in (r.get("source") or {}).items() if v},
        "repli": "sonnerie",
        "volume": dict(r.get("volume") or {}),
        "repetition": dict(r.get("repetition") or {}),
        "arret_auto_min": int(reg["arret_auto_min"]),
        "allumer_tv": bool(r.get("allumer_tv")),
        "impose": bool(r.get("impose")),
    }
    if avec_signature:
        element["signature"] = signature(store, r, quand)
    return element


def pour_releve(hass: HomeAssistant, store, pc: dict[str, Any], quand: datetime | None = None) -> tuple[dict[str, Any], bool]:
    """Le bloc `reveils` du relevé de cet appareil, et vrai s'il a pris des ordres (à enregistrer tout de suite).

    Module coupé : `actif` faux et listes vides → l'appareil désarme tout.
    """
    quand = quand or maintenant()
    reg = reglages(store)
    if not actif(store):
        pris = bool(pc.pop("reveil_ordres", None))
        return {"actif": False, "version": "", "liste": [], "ordres": []}, pris
    pc_id = str(pc.get("id"))
    liste = []
    for r in sorted(de_l_appareil(store, pc_id), key=lambda x: x["id"]):
        if not r.get("actif") or _demandeur_disparu(hass, r):
            continue
        liste.append(element_releve(store, r, quand))
    ordres = []
    limite = quand - timedelta(minutes=int(reg["ordre_ttl_min"]))
    for o in pc.pop("reveil_ordres", None) or []:
        try:
            if datetime.fromisoformat(o["emis"]) < limite and o.get("type") == "sonner":
                continue  # trop vieux pour sonner « maintenant »
        except (KeyError, ValueError):
            continue
        ordres.append(o)
    # La version change dès que l'appareil doit réarmer : une configuration, ou la fenêtre (nouvelle occurrence).
    version = calcul.signature([(e["id"], e["signature"], [o["id"] for o in e["occurrences"]]) for e in liste])
    bloc = {"actif": True, "version": version, "genere": quand.isoformat(),
            "horizon_h": int(reg["horizon_h"]), "synchro_avant_min": int(reg["synchro_avant_min"]),
            "liste": liste, "ordres": ordres}
    return bloc, bool(ordres)


def _demandeur_disparu(hass: HomeAssistant, r: dict[str, Any]) -> bool:
    """Personne supprimée : désarmée. Jamais pendant le démarrage (les personnes ne sont peut-être pas encore là)."""
    return hass.state is CoreState.running and hass.states.get(str(r.get("demandeur") or "")) is None


# ----------------------------------------------------------------------------- Relevé : appareil → HA


def _donnees(store, r: dict[str, Any] | None, pc: dict[str, Any] | None, **plus: Any) -> dict[str, Any]:
    r = r or {}
    return {"reveil": r.get("id", ""), "nom": r.get("nom", ""), "demandeur": r.get("demandeur", ""),
            "appareil": str((pc or {}).get("name") or ""), "appareil_id": str((pc or {}).get("id") or ""),
            "source": str((r.get("source") or {}).get("type") or ""), **plus}


def _vivant(r: dict[str, Any], etat_: str, jusqu_a: datetime, **plus: Any) -> None:
    r["vivant"] = {"etat": etat_, "jusqu_a": jusqu_a.isoformat(), **plus}


def _deja_vu(r: dict[str, Any], occurrence: str, cle_: str) -> bool:
    vus = r.setdefault("vus", {})
    marques = vus.setdefault(occurrence or "-", [])
    if cle_ in marques:
        return True
    marques.append(cle_)
    for vieux in list(vus)[:-8]:  # on garde les dernières occurrences seulement
        vus.pop(vieux, None)
    return False


def recevoir(hass: HomeAssistant, store, pc: dict[str, Any], corps: Any, quand: datetime | None = None) -> bool:
    """Ce que l'appareil remonte dans son relevé (`reveils`) : ce qu'il a armé et ses événements.

    Rend vrai si le magasin a changé. Un événement n'est émis qu'une fois par occurrence (le premier appareil
    qui sonne, qui arrête ; un report par numéro), et un même `eid` renvoyé n'est jamais rejoué.
    L'arrêt et le report sur un appareil sont transmis aux autres appareils du réveil (arrêt partagé).
    """
    if not isinstance(corps, dict):
        return False
    quand = quand or maintenant()
    reg = reglages(store)
    change = False
    recu = corps.get("recu")
    if isinstance(recu, dict):
        propre = {str(k)[:16]: str(v)[:16] for k, v in list(recu.items())[:50]}
        if pc.get("reveil_recu") != propre:
            pc["reveil_recu"] = propre
            change = True
    armees = corps.get("armees")
    if isinstance(armees, list):
        propre_a = sorted({str(x)[:40] for x in armees[:100]})
        if pc.get("reveil_armees") != propre_a:
            pc["reveil_armees"] = propre_a
            change = True
    capacites = corps.get("capacites")
    if isinstance(capacites, dict):
        propre_c = {str(k)[:32]: (v if isinstance(v, (bool, int)) else [str(x)[:16] for x in v][:10] if isinstance(v, list) else str(v)[:32])
                    for k, v in list(capacites.items())[:20]}
        avant = pc.get("reveil_capacites") or {}
        if propre_c != avant:
            pc["reveil_capacites"] = propre_c
            change = True
            if avant.get("alarme_exacte", True) and propre_c.get("alarme_exacte") is False:
                for r in de_l_appareil(store, str(pc.get("id"))):
                    hass.bus.async_fire(EV_ECHEC, _donnees(store, r, pc, raison="permission", detail="alarme_exacte",
                                                          repli_joue=False))
    vus_eid = pc.setdefault("reveil_eids", [])
    for ev in list(corps.get("evenements") or [])[:30]:
        if not isinstance(ev, dict):
            continue
        eid = str(ev.get("eid") or "")[:40]
        if eid and eid in vus_eid:
            continue
        if eid:
            vus_eid.append(eid)
            del vus_eid[:-60]
            change = True
        type_ = str(ev.get("type") or "")
        if type_ not in EVENEMENTS:
            continue
        r = reveils(store).get(str(ev.get("reveil") or ""))
        occurrence = str(ev.get("occurrence") or "")[:40]
        plus: dict[str, Any] = {"occurrence": occurrence}
        for cle_ in ("heure_prevue", "heure_reelle", "horodatage_appareil", "jusqu_a", "par", "raison", "detail"):
            if ev.get(cle_) not in (None, ""):
                plus[cle_] = str(ev[cle_])[:120]
        if type_ == "reporte":
            plus["nombre"] = int(ev.get("nombre") or 1) if str(ev.get("nombre") or "1").isdigit() else 1
        if type_ == "echec":
            plus["repli_joue"] = bool(ev.get("repli_joue"))
        if type_ == "arrete" and "par" not in plus:
            plus["par"] = "appareil"
        donnees = _donnees(store, r, pc, **plus)
        if r is None:
            if type_ in ("sonne", "arrete", "reporte", "echec") and occurrence.startswith("ordre:"):
                hass.bus.async_fire(EVENEMENTS[type_], donnees)  # sonnerie immédiate sans réveil enregistré
            continue
        cle_dedup = type_ if type_ in ("sonne", "arrete") else (
            f"reporte:{plus.get('nombre')}" if type_ == "reporte" else f"echec:{plus.get('raison')}:{pc.get('id')}")
        if not _deja_vu(r, occurrence, cle_dedup):
            hass.bus.async_fire(EVENEMENTS[type_], donnees)
            # Lot 10 (V1) : une source Music Assistant joue sur son enceinte, pilotée par HA (l'appareil garde sa sonnerie).
            if type_ in ("sonne", "reporte", "arrete"):
                hass.async_create_task(async_musique(hass, r, {"sonne": "jouer", "reporte": "pause", "arrete": "arreter"}[type_]))
        change = True
        if type_ == "sonne":
            fin = quand + timedelta(minutes=int(reg["arret_auto_min"]))
            _vivant(r, "sonne", fin, occurrence=occurrence, appareil=str(pc.get("id")))
        elif type_ == "reporte":
            try:
                jusqu_a = datetime.fromisoformat(plus["jusqu_a"]) if "jusqu_a" in plus else None
            except ValueError:
                jusqu_a = None
            jusqu_a = jusqu_a or quand + timedelta(minutes=int((r.get("repetition") or {}).get("minutes") or 9))
            _vivant(r, "reporte", jusqu_a + timedelta(minutes=1), occurrence=occurrence, nombre=plus.get("nombre", 1))
            if plus.get("par") != "partage":
                autres = [c for c in cibles(store, r) if c is not pc]
                ordonner(store, autres, {"type": "reporter", "reveil": r["id"], "occurrence": occurrence,
                                         "jusqu_a": jusqu_a.isoformat(), "par": "partage"})
        elif type_ == "arrete":
            _vivant(r, "arrete", quand + timedelta(hours=1), occurrence=occurrence, par=plus.get("par", ""))
            if plus.get("par") != "partage":
                autres = [c for c in cibles(store, r) if c is not pc]
                ordonner(store, autres, {"type": "arreter", "reveil": r["id"], "occurrence": occurrence, "par": "partage"})
        elif type_ == "echec":
            _vivant(r, "echec", quand + timedelta(hours=12), occurrence=occurrence, raison=plus.get("raison", ""))
    return change


# ----------------------------------------------------------------------------- Music Assistant (lot 10, V1)

TYPES_MA = ("playlist", "radio", "album", "artist", "track", "podcast", "podcast_episode", "audiobook")


def type_media(uri: str) -> str:
    """« library://playlist/10 » → playlist ; type inconnu → track."""
    try:
        segment = str(uri).split("://", 1)[1].split("/", 1)[0]
    except IndexError:
        return "track"
    return segment if segment in TYPES_MA else "track"


async def async_musique(hass: HomeAssistant, r: dict[str, Any], action: str) -> None:
    """Source « ma » avec une enceinte : jouer à la sonnerie, mettre en pause au report, arrêter à l'arrêt.
    Sans enceinte, ou si Music Assistant échoue, rien de plus : la sonnerie de l'appareil reste le filet."""
    from homeassistant.core import Context

    s = r.get("source") or {}
    lecteur = str(s.get("lecteur") or "")
    if s.get("type") != "ma" or not lecteur.startswith("media_player.") or not s.get("uri"):
        return
    try:
        if action == "jouer":
            await hass.services.async_call("music_assistant", "play_media",
                                           {"media_id": s["uri"], "media_type": type_media(s["uri"]), "enqueue": "replace"},
                                           target={"entity_id": lecteur}, blocking=True, context=Context())
        else:
            await hass.services.async_call("media_player", "media_pause" if action == "pause" else "media_stop", {},
                                           target={"entity_id": lecteur}, blocking=True, context=Context())
    except Exception as err:  # noqa: BLE001 - la sonnerie de l'appareil continue de toute façon
        _LOGGER.warning("%s : réveil « %s », Music Assistant (%s sur %s) : %s", PRODUIT, r.get("nom"), action, lecteur, err)
        if action == "jouer":
            hass.bus.async_fire(EV_ECHEC, _donnees(None, r, None, raison="source", detail="music_assistant", repli_joue=True))


# ----------------------------------------------------------------------------- Le moteur (Home Assistant)


class Moteur:
    """Évalue les réveils calculés, contrôle la synchronisation avant l'heure, fait vivre les entités."""

    def __init__(self, hass: HomeAssistant, coordinator) -> None:
        self.hass = hass
        self.coord = coordinator
        self._suivis: dict[str, Any] = {}  # id → désabonnement du suivi de sa source (entité ou modèle)
        self._arret = None
        self._derniere_eval = ""

    @property
    def store(self):
        return self.coord.store

    async def async_demarrer(self) -> None:
        from homeassistant.helpers.event import async_track_time_interval

        self._arret = async_track_time_interval(self.hass, self._async_tic, timedelta(seconds=60))
        if actif(self.store):
            for r in list(reveils(self.store).values()):
                self.suivre(r)
            if self.evaluer_tout():
                await self.store.async_save()

    @callback
    def arreter(self) -> None:
        if self._arret is not None:
            self._arret()
            self._arret = None
        for desabonner in self._suivis.values():
            desabonner()
        self._suivis.clear()

    # -- Évaluation des réveils calculés

    def valeur_source(self, r: dict[str, Any], quand: datetime) -> datetime | None:
        d = r.get("declenchement") or {}
        if d.get("type") == "entite":
            etat_ = self.hass.states.get(d.get("entite") or "")
            if etat_ is None:
                return None
            if d.get("attribut"):
                brut = etat_.attributes.get(d["attribut"])
            elif etat_.domain == "calendar":
                brut = etat_.attributes.get("start_time")
            else:
                brut = etat_.state
            return calcul.lire_horodatage(brut, quand)
        if d.get("type") == "modele":
            from homeassistant.exceptions import TemplateError
            from homeassistant.helpers.template import Template

            try:
                rendu = Template(d.get("modele") or "", self.hass).async_render(parse_result=False)
            except TemplateError as err:
                _LOGGER.warning("%s : réveil « %s », modèle en erreur : %s", PRODUIT, r.get("nom"), err)
                return None
            return calcul.lire_horodatage(rendu, quand)
        return None

    def evaluer(self, r: dict[str, Any], quand: datetime | None = None) -> bool:
        """Recalcule l'heure d'un réveil entite / modele. Vrai si elle a changé. Échec signalé une fois."""
        d = r.get("declenchement") or {}
        if d.get("type") not in ("entite", "modele"):
            if r.get("calcul"):
                r["calcul"] = {}
                return True
            return False
        quand = quand or maintenant()
        resultat = calcul.appliquer_calcul(d, self.valeur_source(r, quand), quand)
        avant = {k: v for k, v in (r.get("calcul") or {}).items() if k != "evalue"}
        nouveau = dict(resultat)
        if nouveau == avant:
            return False
        r["calcul"] = {**nouveau, "evalue": quand.isoformat()}
        if nouveau["raison"] == "invalide" and r.get("actif") and actif(self.store):
            cle_ = f"invalide:{nouveau['valeur']}:{quand.date().isoformat()}"
            if cle_ not in (r.get("signales") or []):
                r.setdefault("signales", []).append(cle_)
                del r["signales"][:-10]
                self.hass.bus.async_fire(EV_ECHEC, _donnees(self.store, r, None, raison="heure_invalide",
                                                            detail=nouveau["valeur"] or "aucune valeur", repli_joue=False))
        return True

    def evaluer_tout(self) -> bool:
        change = False
        for r in list(reveils(self.store).values()):
            change = self.evaluer(r) or change
        return change

    @callback
    def suivre(self, r: dict[str, Any]) -> None:
        """Suit la source d'un réveil calculé : chaque changement réévalue l'heure."""
        from homeassistant.helpers.event import (TrackTemplate, async_track_state_change_event,
                                                 async_track_template_result)
        from homeassistant.helpers.template import Template

        ancien = self._suivis.pop(r["id"], None)
        if ancien is not None:
            ancien()
        d = r.get("declenchement") or {}
        ident = r["id"]

        @callback
        def _changement(*_args: Any) -> None:
            rv = reveils(self.store).get(ident)
            if rv is not None and actif(self.store) and self.evaluer(rv):
                self.hass.async_create_task(self.async_publier())

        if d.get("type") == "entite" and d.get("entite"):
            self._suivis[ident] = async_track_state_change_event(self.hass, [d["entite"]], _changement)
        elif d.get("type") == "modele" and d.get("modele"):
            suivi = async_track_template_result(self.hass, [TrackTemplate(Template(d["modele"], self.hass), None)],
                                                _changement)
            self._suivis[ident] = suivi.async_remove

    @callback
    def oublier(self, ident: str) -> None:
        ancien = self._suivis.pop(ident, None)
        if ancien is not None:
            ancien()

    async def async_publier(self, nouveaux: bool = False) -> None:
        """Enregistre, fait apparaître les entités neuves et rafraîchit les autres."""
        await self.store.async_save()
        if nouveaux:
            async_dispatcher_send(self.hass, SIGNAL_NOUVEAUX)
        self.coord.async_update_listeners()

    # -- Le tic de chaque minute

    async def _async_tic(self, _instant=None) -> None:
        if not actif(self.store):
            return
        quand = maintenant()
        reg = reglages(self.store)
        change = False
        hhmm = f"{quand.hour:02d}:{quand.minute:02d}"
        if hhmm == reg["heure_evaluation"] and self._derniere_eval != quand.date().isoformat():
            self._derniere_eval = quand.date().isoformat()
            change = self.evaluer_tout() or change
        change = self.controler_synchro(quand) or change
        if change:
            await self.store.async_save()
        self.coord.async_update_listeners()

    def controler_synchro(self, quand: datetime) -> bool:
        """À H − synchro_avant_min : un appareil visé qui n'a pas armé la dernière version → état
        non_synchronise et vision_reveil_echec (raison non_synchronise). Il sonnera sur l'ancienne version."""
        reg = reglages(self.store)
        delai = timedelta(minutes=int(reg["synchro_avant_min"]))
        change = False
        for r in reveils(self.store).values():
            if not r.get("actif"):
                continue
            o = calcul.prochaine(r, quand, 24)
            if o is None:
                continue
            heure = datetime.fromisoformat(o["heure"])
            if not quand < heure <= quand + delai:
                continue
            cle_ = f"synchro:{o['id']}"
            if cle_ in (r.get("signales") or []):
                continue
            # En retard : configuration pas armée, ou cette occurrence absente de ce que l'appareil a armé.
            en_retard = [pc for pc in cibles(self.store, r)
                         if not a_jour(self.store, pc, r, quand) or o["id"] not in (pc.get("reveil_armees") or [])]
            r.setdefault("signales", []).append(cle_)
            del r["signales"][:-10]
            change = True
            if en_retard:
                noms = ", ".join(str(pc.get("name") or pc.get("id")) for pc in en_retard)
                _vivant(r, "non_synchronise", heure, occurrence=o["id"])
                self.hass.bus.async_fire(EV_ECHEC, _donnees(self.store, r, en_retard[0], raison="non_synchronise",
                                                            detail=noms, occurrence=o["id"], heure_prevue=o["heure"],
                                                            repli_joue=False))
        return change


async def async_installer_modeles(hass: HomeAssistant) -> list[str]:
    """Dépose les blueprints fournis dans <config>/blueprints/automation/pc_parental/ s'ils n'y sont pas déjà.

    Une copie existante n'est jamais écrasée (l'utilisateur a pu la modifier). Rend les fichiers déposés.
    """
    import pathlib
    import shutil

    source = pathlib.Path(__file__).parent / "blueprints" / "automation"
    cible = pathlib.Path(hass.config.path("blueprints", "automation", DOMAIN))

    def _copier() -> list[str]:
        deposes = []
        if not source.is_dir():
            return deposes
        for fichier in sorted(source.glob("*.yaml")):
            dest = cible / fichier.name
            if not dest.exists():
                cible.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(fichier, dest)
                deposes.append(fichier.name)
        return deposes

    try:
        deposes = await hass.async_add_executor_job(_copier)
    except OSError as err:
        _LOGGER.warning("%s : blueprints du Réveil non déposés (%s).", PRODUIT, err)
        return []
    if deposes:
        _LOGGER.warning("%s : blueprints du Réveil déposés dans %s : %s.", PRODUIT, cible, ", ".join(deposes))
    return deposes


def moteur(hass: HomeAssistant) -> Moteur | None:
    for coord in hass.data.get(DOMAIN, {}).values():
        return getattr(coord, "reveil", None)
    return None

