"""Les rôles (Vision 1.71.0) : des modèles composés de fonctions, attribués aux personnes.

Un rôle = un nom + un ensemble de fonctions prises dans un catalogue défini par Vision (ci-dessous).
« Décideur » et « Demandeur » sont fournis par défaut (1.80.0 ; « Parent » et « Enfant » avant) ; un
administrateur de Home Assistant renomme, duplique, crée (« Ado », « Enseignant », « Invité »…) et coche
les fonctions — il ne crée pas de code. Les identifiants internes restent "parent" et "enfant".

- Attribution : par personne (store.roles_personnes), avec une surcharge possible par appareil
  (pc["role"]). Un appareil sans personne ni surcharge prend le rôle par défaut (« Demandeur »).
- Fonctions ★ (sensibles) : seul un administrateur les attribue — tous les changements de rôle
  passent par Vision → Configurer ou les entités, réservés aux administrateurs.
- Toujours un rôle d'administration effectif : on ne peut pas retirer « Gérer » + « Approuver » du
  dernier rôle qui les a sur un appareil, ni lui retirer son dernier appareil.
- Tout changement est journalisé (avertissement + événement `pc_parental_role`).

Migration : 1.68 et avant, étiquette « Parents » → Parent ; 1.69/1.70, pc["role"] parent/enfant et
pc["biometrie"] → surcharge par appareil, et la biométrie devient une fonction du modèle Parent.
1.80.0 : noms neutres (Décideur / Demandeur) et rôles rattachés aux personnes (voir `neutraliser`).
"""

from __future__ import annotations

import copy
import logging
import secrets
from typing import Any

from homeassistant.core import HomeAssistant

from . import textes, vocabulaire
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

EVENEMENT = f"{DOMAIN}_role"
PARENT, ENFANT = "parent", "enfant"
PAUSE_MAX_DEFAUT = 60

# Les fonctions : identifiant → sensible ★. L'ordre est celui de l'écran. Le libellé de chacune est dans le
# catalogue serveur (« roles.f_<id> ») : `libelle_fonction(f, langue)`.
_SENSIBLES: dict[str, bool] = {
    "gerer": True, "approuver": True, "regles": True, "biometrie": True, "pause": True,
    "code": False, "protege": False, "demander_acces": False, "demander_deverrouillage": False, "temps": False,
    "messages": False, "accueil": False, "entites_voir": False, "entites_piloter": False, "veille": False,
    # Module Réveil : gérer les réveils de tout Demandeur (★), ou seulement les siens.
    "reveil_gerer": True, "reveil_propres": False,
}


def libelle_fonction(fonction: str, langue: str | None = None) -> str:
    """Le libellé d'une fonction de rôle dans une langue (sans langue : celle de Home Assistant)."""
    return textes.t(f"roles.f_{fonction}", langue)


# Le catalogue : identifiant → (libellé en français, sensible ★), pour les écrans qui ne passent pas encore par
# `libelle_fonction`.
FONCTIONS: dict[str, tuple[str, bool]] = {f: (libelle_fonction(f, "fr"), s) for f, s in _SENSIBLES.items()}
SENSIBLES = {f for f, (_, s) in FONCTIONS.items() if s}
ADMINISTRATION = {"gerer", "approuver"}

# Les noms par défaut (« Décideur », « Demandeur ») : en français ici ; une installation neuve les reçoit dans la
# langue de Home Assistant (`migrer`). Un nom enregistré ne change jamais.
MODELES: dict[str, dict[str, Any]] = {
    PARENT: {"nom": vocabulaire.termes("fr")["Decideur"], "fonctions": ["gerer", "approuver", "regles", "pause", "code", "protege", "temps",
                                            "messages", "accueil", "entites_voir", "entites_piloter", "veille",
                                            "reveil_gerer"],
             "pause_max": PAUSE_MAX_DEFAUT},
    ENFANT: {"nom": vocabulaire.termes("fr")["Demandeur"], "fonctions": ["protege", "demander_acces", "demander_deverrouillage", "temps",
                                            "messages", "entites_voir"],
             "pause_max": PAUSE_MAX_DEFAUT},
}


# ----------------------------------------------------------------------------- Lire


def tous(store) -> dict[str, dict[str, Any]]:
    return store.roles


def role_id(store, pc: dict[str, Any] | None) -> str:
    """Le rôle effectif d'un appareil : sa surcharge, sinon celui de sa personne, sinon le rôle par défaut."""
    pc = pc or {}
    r = str(pc.get("role") or "")
    if r in store.roles:
        return r
    r = str(store.roles_personnes.get(str(pc.get("person") or ""), ""))
    if r in store.roles:
        return r
    return ENFANT if ENFANT in store.roles else next(iter(store.roles), ENFANT)


def role(store, pc: dict[str, Any] | None) -> dict[str, Any]:
    return store.roles.get(role_id(store, pc)) or {"nom": "?", "fonctions": []}


def nom(store, pc: dict[str, Any] | None) -> str:
    return str(role(store, pc).get("nom") or "?")


def a(store, pc: dict[str, Any] | None, fonction: str) -> bool:
    return fonction in (role(store, pc).get("fonctions") or [])


def fonctions(store, pc: dict[str, Any] | None) -> list[str]:
    return [f for f in FONCTIONS if a(store, pc, f)]


def avec(store, fonction: str) -> list[str]:
    """Les appareils dont le rôle effectif a cette fonction."""
    return [i for i, pc in store.pcs.items() if a(store, pc, fonction)]


def pause_max(store, pc: dict[str, Any] | None) -> int:
    """Surcharge de l'appareil, sinon la durée max du rôle."""
    try:
        v = int((pc or {}).get("pause_max") or role(store, pc).get("pause_max") or PAUSE_MAX_DEFAUT)
    except (TypeError, ValueError):
        v = PAUSE_MAX_DEFAUT
    return v


# ----------------------------------------------------------------------------- Validation à plusieurs (1.72.0)

VALIDATION_MODES = ("une", "toutes", "n")
VALIDATION_ACTIONS = ("parent", "reglages", "app")
VALIDATION_DEFAUT: dict[str, Any] = {"mode": "une", "n": 2, "actions": [], "au_dela_de": 0, "repli_minutes": 0}


def _validation_propre(v: Any) -> dict[str, Any]:
    v = v if isinstance(v, dict) else {}
    sortie = dict(VALIDATION_DEFAUT)
    if v.get("mode") in VALIDATION_MODES:
        sortie["mode"] = v["mode"]
    for cle_, bas, haut in (("n", 2, 10), ("au_dela_de", 0, 24 * 60), ("repli_minutes", 0, 9)):
        try:
            sortie[cle_] = max(bas, min(haut, int(v.get(cle_, sortie[cle_]))))
        except (TypeError, ValueError):
            pass
    sortie["actions"] = [a_ for a_ in VALIDATION_ACTIONS if a_ in set(v.get("actions") or [])]
    return sortie


def validation(store, pc: dict[str, Any] | None) -> dict[str, Any]:
    """La règle du demandeur (docs/deverrouillage-quorum.md) : surcharge de l'appareil, sinon son rôle."""
    propre = (pc or {}).get("validation")
    return _validation_propre(propre if propre else role(store, pc).get("validation"))


def votant(pc: dict[str, Any]) -> str:
    """Une voix par personne : la personne liée à l'appareil, sinon l'appareil lui-même."""
    return str(pc.get("person") or "") or f"appareil:{pc['id']}"


def habilites(store) -> list[str]:
    """Les personnes dont un appareil peut « Approuver ». Un appareil sans personne n'en est pas une :
    quand il faut plusieurs voix, il ne vote pas (le même adulte compterait deux fois)."""
    vus: list[str] = []
    for pc_id in avec(store, "approuver"):
        v = votant(store.pcs[pc_id])
        if v.startswith("person.") and v not in vus:
            vus.append(v)
    return vus


def requis(store, pc: dict[str, Any], action: str, minutes: int) -> tuple[int, list[str]]:
    """Combien de voix distinctes il faut pour cette demande, et qui peut voter (figé à la création).
    Jamais plus que le nombre de votants possibles : une règle impossible retombe à « toutes »."""
    v = validation(store, pc)
    qui = habilites(store)
    from . import modules

    if modules.coupe(store, "double_validation"):
        # 1.82.0 : module « Double validation » coupé : une seule voix (la règle reste enregistrée).
        return 1, qui
    if v["mode"] == "une" or (v["actions"] and action.split(":", 1)[0] not in v["actions"]) \
            or minutes <= v["au_dela_de"]:
        return 1, qui
    voulu = len(qui) if v["mode"] == "toutes" else v["n"]
    return max(1, min(voulu, len(qui))), qui


def regler_validation(hass: HomeAssistant, store, cible: str, valeurs: dict[str, Any] | None, par: str) -> None:
    """cible = id de rôle, ou « appareil:<id> » pour une surcharge (valeurs None = suivre le rôle)."""
    if cible.startswith("appareil:"):
        pc = store.pcs.get(cible.split(":", 1)[1])
        if pc is None:
            raise textes.Erreur("commun.appareil_inconnu")
        if valeurs is None:
            pc.pop("validation", None)
            texte = f"validation de « {pc.get('name')} » : selon le rôle"
        else:
            pc["validation"] = _validation_propre(valeurs)
            texte = f"validation de « {pc.get('name')} » : {resume_validation(pc['validation'], 'fr')}"
    else:
        r = store.roles.get(cible)
        if r is None:
            raise textes.Erreur("roles.inconnu")
        r["validation"] = _validation_propre(valeurs)
        texte = f"validation du rôle « {r['nom']} » : {resume_validation(r['validation'], 'fr')}"
    _journal(hass, texte, par, cible=cible)


def resume_validation(v: dict[str, Any], langue: str | None = None) -> str:
    """La règle de validation en une phrase, dans `langue` (sans langue : celle de Home Assistant)."""
    if v["mode"] == "une":
        return textes.t("roles.v_une", langue)
    t = textes.t("roles.v_toutes", langue) if v["mode"] == "toutes" else textes.t("roles.v_n", langue, n=v['n'])
    if v["actions"]:
        t += textes.t("roles.v_pour", langue, actions=", ".join(v["actions"]))
    if v["au_dela_de"]:
        t += textes.t("roles.v_au_dela", langue, minutes=v['au_dela_de'])
    t += (textes.t("roles.v_repli", langue, minutes=v['repli_minutes']) if v["repli_minutes"]
          else textes.t("roles.v_expire", langue))
    return t


# ----------------------------------------------------------------------------- Garde-fou


def _admin_effectif(roles: dict[str, Any], personnes: dict[str, str], pcs: dict[str, Any]) -> bool:
    """Au moins un appareil a un rôle effectif avec « Gérer » et « Approuver »."""
    class _S:  # un store de passage pour simuler un changement
        pass

    s = _S(); s.roles, s.roles_personnes, s.pcs = roles, personnes, pcs  # type: ignore[attr-defined]
    return any(ADMINISTRATION <= set(role(s, pc).get("fonctions") or []) for pc in pcs.values())


def _verifier(store, roles, personnes, pcs) -> None:
    # On ne bloque que si l'état actuel a un administrateur effectif (une maison neuve n'en a pas encore).
    if _admin_effectif(store.roles, store.roles_personnes, store.pcs) and not _admin_effectif(roles, personnes, pcs):
        raise textes.Erreur("roles.admin_requis")


def _journal(hass: HomeAssistant, texte: str, par: str, **donnees: Any) -> None:
    _LOGGER.warning("%s : %s (par %s).", PRODUIT, texte, par or "un administrateur")
    hass.bus.async_fire(EVENEMENT, {"texte": texte, "par": par, **donnees})


# ----------------------------------------------------------------------------- Modifier (administrateurs)


def creer(hass: HomeAssistant, store, nom_role: str, modele: str, par: str) -> str:
    nom_role = (nom_role or "").strip()[:40]
    if not nom_role:
        raise textes.Erreur("roles.nom_role")
    if any(str(r.get("nom")).lower() == nom_role.lower() for r in store.roles.values()):
        raise textes.Erreur("roles.nom_pris")
    base = store.roles.get(modele) or MODELES[ENFANT]
    ident = "r" + secrets.token_hex(4)
    store.roles[ident] = {"nom": nom_role, "fonctions": list(base.get("fonctions") or []),
                          "pause_max": int(base.get("pause_max") or PAUSE_MAX_DEFAUT),
                          "validation": _validation_propre(base.get("validation"))}
    _journal(hass, f"rôle « {nom_role} » créé d'après « {base.get('nom')} »", par, role=ident)
    return ident


def modifier(hass: HomeAssistant, store, ident: str, nom_role: str, fonctions_voulues: list[str], pause: int, par: str) -> None:
    r = store.roles.get(ident)
    if r is None:
        raise textes.Erreur("roles.inconnu")
    nom_role = (nom_role or "").strip()[:40] or r["nom"]
    if any(i != ident and str(x.get("nom")).lower() == nom_role.lower() for i, x in store.roles.items()):
        raise textes.Erreur("roles.nom_pris")
    voulues = [f for f in FONCTIONS if f in set(fonctions_voulues or [])]
    pause = max(5, min(16 * 60, int(pause or PAUSE_MAX_DEFAUT)))
    roles = copy.deepcopy(store.roles)
    roles[ident] = {**r, "nom": nom_role, "fonctions": voulues, "pause_max": pause}
    _verifier(store, roles, store.roles_personnes, store.pcs)
    avant = set(r.get("fonctions") or [])
    store.roles[ident] = roles[ident]
    changements = [f"+{f}" for f in voulues if f not in avant] + [f"-{f}" for f in avant if f not in voulues]
    if r["nom"] != nom_role:
        changements.insert(0, f"renommé « {r['nom']} » → « {nom_role} »")
    if int(r.get("pause_max") or 0) != pause:
        changements.append(f"pause max {pause} min")
    if changements:
        _journal(hass, f"rôle « {nom_role} » : {', '.join(changements)}", par, role=ident, changements=changements)


def supprimer(hass: HomeAssistant, store, ident: str, par: str) -> None:
    if ident == ENFANT:
        raise textes.Erreur("roles.defaut_insupprimable", role=nom_defaut(store))
    r = store.roles.get(ident)
    if r is None:
        raise textes.Erreur("roles.inconnu")
    if ident in store.roles_personnes.values() or any(pc.get("role") == ident for pc in store.pcs.values()):
        raise textes.Erreur("roles.encore_attribue")
    del store.roles[ident]
    _journal(hass, f"rôle « {r['nom']} » supprimé", par, role=ident)


def attribuer_personne(hass: HomeAssistant, store, personne: str, ident: str, par: str) -> None:
    if ident and ident not in store.roles:
        raise textes.Erreur("roles.inconnu")
    personnes = dict(store.roles_personnes)
    if ident:
        personnes[personne] = ident
    else:
        personnes.pop(personne, None)
    _verifier(store, store.roles, personnes, store.pcs)
    if store.roles_personnes.get(personne) == personnes.get(personne):
        return
    store.roles_personnes = personnes
    nom_role = store.roles[ident]["nom"] if ident else "aucun ({} par défaut)".format(nom_defaut(store))
    _journal(hass, f"{personne} : rôle « {nom_role} »", par, personne=personne, role=ident)


def attribuer_appareil(hass: HomeAssistant, store, pc_id: str, ident: str, par: str) -> None:
    """ident vide : l'appareil suit le rôle de sa personne."""
    pc = store.pcs.get(pc_id)
    if pc is None:
        raise textes.Erreur("commun.appareil_inconnu")
    if ident and ident not in store.roles:
        raise textes.Erreur("roles.inconnu")
    if str(pc.get("role") or "") == ident:
        return
    pcs = copy.deepcopy(store.pcs)
    pcs[pc_id]["role"] = ident
    _verifier(store, store.roles, store.roles_personnes, pcs)
    pc["role"] = ident
    texte = f"« {pc.get('name') or pc_id} » : rôle « {store.roles[ident]['nom']} »" if ident else \
        f"« {pc.get('name') or pc_id} » : suit le rôle de sa personne"
    _journal(hass, texte, par, pc=pc_id, role=ident)


# ----------------------------------------------------------------------------- Migration


def nom_defaut(store) -> str:
    """Le nom du rôle que prend un appareil sans personne ni surcharge."""
    return str(store.roles.get(ENFANT, {}).get("nom") or MODELES[ENFANT]["nom"])


def resume(store) -> dict[str, dict[str, Any]]:
    """Par rôle : ses personnes et ses appareils (rôle effectif), pour l'écran « Rôles »."""
    sortie: dict[str, dict[str, Any]] = {i: {"personnes": [], "appareils": []} for i in store.roles}
    for personne, ident in store.roles_personnes.items():
        if ident in sortie:
            sortie[ident]["personnes"].append(personne)
    for pc_id, pc in store.pcs.items():
        sortie.setdefault(role_id(store, pc), {"personnes": [], "appareils": []})["appareils"].append(pc_id)
    return sortie


# Les anciens noms par défaut : renommés une fois ; un nom choisi par l'administrateur ne change pas.
ANCIENS_NOMS = {PARENT: ("Parent", "Parents"), ENFANT: ("Enfant", "Enfants")}


def neutraliser(hass: HomeAssistant, store) -> bool:
    """1.80.0, une fois : noms neutres, et chaque personne rattachée au rôle que portent ses appareils.

    Avant, la migration de 1.71.0 avait laissé le rôle « Parent » en surcharge sur chaque appareil, et
    aucune personne n'avait de rôle (l'écran disait « Personnes : aucune »). On donne à chaque personne
    le rôle le plus fréquent parmi les surcharges de ses appareils, puis on retire les surcharges
    devenues inutiles ; un appareil qui avait un autre rôle effectif le garde en surcharge explicite.
    Aucun appareil ne change de rôle effectif.
    """
    if store.vocabulaire_neutre:
        return False
    renommes = []
    for ident, anciens in ANCIENS_NOMS.items():
        r = store.roles.get(ident)
        if r is not None and str(r.get("nom") or "") in anciens:
            r["nom"] = MODELES[ident]["nom"]
            renommes.append(f"{anciens[0]} → {r['nom']}")
    avant = {pc_id: role_id(store, pc) for pc_id, pc in store.pcs.items()}
    sauve = ({i: pc.get("role") for i, pc in store.pcs.items()}, dict(store.roles_personnes))
    rattaches = []
    par_personne: dict[str, list[str]] = {}
    for pc_id, pc in store.pcs.items():
        if pc.get("person"):
            par_personne.setdefault(str(pc["person"]), []).append(pc_id)
    for personne, ids in par_personne.items():
        if personne in store.roles_personnes:
            continue
        explicites = [str(store.pcs[i].get("role") or "") for i in ids if str(store.pcs[i].get("role") or "") in store.roles]
        if not explicites:
            continue
        choisi = max(sorted(set(explicites)), key=explicites.count)
        store.roles_personnes[personne] = choisi
        rattaches.append(f"{personne} → {store.roles[choisi]['nom']}")
        for i in ids:
            pc = store.pcs[i]
            if pc.get("role") == choisi:
                pc["role"] = ""  # suit maintenant sa personne
            elif not pc.get("role") and avant[i] != choisi:
                pc["role"] = avant[i]  # gardait un autre rôle : on l'écrit sur l'appareil
    if any(role_id(store, pc) != avant[i] for i, pc in store.pcs.items()):
        # Filet : on ne change jamais le rôle effectif d'un appareil ; dans le doute, rien n'est rattaché.
        for i, r in sauve[0].items():
            store.pcs[i]["role"] = r
        store.roles_personnes.clear()
        store.roles_personnes.update(sauve[1])
        rattaches = ["annulé (un rôle effectif aurait changé)"]
    store.vocabulaire_neutre = True
    _LOGGER.warning("%s : rôles neutres (%s) ; personnes rattachées : %s.", PRODUIT,
                    ", ".join(renommes) or "noms déjà choisis par l'administrateur", ", ".join(rattaches) or "aucune")
    return True


def fonctions_reveil(store) -> bool:
    """Module Réveil, une fois : le rôle Décideur déjà enregistré reçoit « Gérer les réveils » (comme un rôle neuf), s'il a
    toujours « Gérer ». Les autres rôles ne changent pas ; « Créer et modifier ses propres réveils » n'est accordé
    à personne d'office. True si fait (à enregistrer)."""
    if getattr(store, "roles_reveil", False):
        return False
    r = store.roles.get(PARENT)
    if r is not None and "gerer" in (r.get("fonctions") or []) and "reveil_gerer" not in r["fonctions"]:
        r["fonctions"] = [f for f in FONCTIONS if f in set(r["fonctions"]) | {"reveil_gerer"}]
        _LOGGER.warning("%s : rôle « %s » : + Gérer les réveils (module Réveil).", PRODUIT, r.get("nom"))
    store.roles_reveil = True
    return True


def migrer(hass: HomeAssistant, store, biometrie_globale: bool) -> bool:
    """Une fois : modèles par défaut ; anciens réglages (étiquette, 1.69/1.70) → surcharges. True si fait.

    Ensuite (1.80.0), une fois aussi : `neutraliser`. True si l'un ou l'autre a changé quelque chose.
    """
    if store.roles:
        neutre = neutraliser(hass, store)
        return fonctions_reveil(store) or neutre
    # Avant la 1.69 : l'étiquette « Parents » faisait le parent, l'interrupteur global la biométrie.
    if not store.roles_migres:
        from . import labels

        etiquetes = set(labels.pcs_avec(hass, store.pcs, "Parents"))
        for pc_id, pc in store.pcs.items():
            pc["role"] = PARENT if pc_id in etiquetes else ENFANT
            pc["biometrie"] = bool(biometrie_globale and pc_id in etiquetes)
        store.roles_migres = True
    store.roles = copy.deepcopy(MODELES)
    # Installation neuve : les noms par défaut dans la langue de Home Assistant (en français chez une maison
    # française, comme avant). Ils sont ensuite enregistrés et ne changent plus.
    langue = textes.ha(hass)
    from . import vocabulaire

    termes = vocabulaire.termes(langue, store)
    store.roles[PARENT]["nom"] = termes["Decideur"]
    store.roles[ENFANT]["nom"] = termes["Demandeur"]
    store.roles_personnes = {}
    biometrie = False
    parents = 0
    for pc in store.pcs.values():
        biometrie = bool(pc.pop("biometrie", False)) or biometrie
        if pc.get("role") == PARENT:
            parents += 1  # surcharge par appareil : rien ne change pour lui
        else:
            pc["role"] = ""  # suit sa personne (aucune : Enfant), comme avant
    if biometrie:
        store.roles[PARENT]["fonctions"].insert(3, "biometrie")
    _LOGGER.warning("%s : rôles en modèles — Décideur et Demandeur créés, %d appareil(s) Décideur gardés, biométrie %s.",
                    PRODUIT, parents, "cochée" if biometrie else "non cochée")
    neutraliser(hass, store)
    store.roles_reveil = True  # les modèles neufs ont déjà « Gérer les réveils »
    return True
