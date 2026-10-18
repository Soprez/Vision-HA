"""Les modules activables (Vision 1.82.0, validés le 2026-10-08).

L'administrateur choisit ce dont il a besoin ; tout le reste disparaît partout (Home Assistant, appli,
télé, agent PC). Le socle reste toujours actif : appareils, personnes et rôles, gestion à distance,
ré-authentification, mises à jour.

- `store.modules` : {identifiant: bool} ; un module absent vaut « actif » (une installation existante
  garde tout tant que l'administrateur ne choisit rien) — sauf les modules de INACTIFS_PAR_DEFAUT (le
  Réveil) : absents, ils sont coupés, pour qu'une installation existante ne voie rien changer ;
- dépendances : `requiert` (tous) et `requiert_un` (au moins un) ; activer un module sans ses
  dépendances est refusé, désactiver un module dont un autre actif dépend aussi (on dit lequel) ;
- désactiver coupe l'action au prochain relevé de chaque appareil (le relevé porte la liste `modules`),
  désactive les entités du module en gardant leur historique, et garde ses réglages pour une
  réactivation sans perte ; réservé aux administrateurs (Vision → Configurer), journalisé.
"""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import HomeAssistant

from . import textes
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

EVENEMENT = f"{DOMAIN}_modules"

# identifiant → (requiert (tous), requiert_un (au moins un)) ; le nom et la phrase de chaque module sont dans le
# catalogue serveur (« module.<id>.nom », « module.<id>.phrase »), en six langues.
_DEPENDANCES: dict[str, tuple[tuple[str, ...], tuple[str, ...]]] = {
    "plages": ((), ()),
    "temps": ((), ()),
    "blocage": ((), ()),
    "filtrage": ((), ()),
    "demandes": ((), ("plages", "temps", "blocage")),
    "deverrouillage": ((), ("plages", "temps", "blocage")),
    "double_validation": (("deverrouillage",), ()),
    "pause": ((), ("plages", "temps", "blocage")),
    "messages": ((), ()),
    "preavis": ((), ("plages", "temps")),
    "veille": ((), ()),
    "veille_tableaux": (("veille", "entites"), ()),
    "entites": ((), ()),
    "accueil": ((), ()),
    "widgets": (("accueil",), ()),
    "habillage": ((), ()),
    "optimisations": ((), ()),
    "protections": ((), ()),
    "pause_generale": ((), ()),
    "concentration": (("blocage",), ()),
    "temps_gagne": (("temps",), ()),
    "regles_lieu": ((), ("blocage", "filtrage")),
    "bilan": (("temps",), ()),
    # Réveil (coupé par défaut, INACTIFS_PAR_DEFAUT).
    "reveil": ((), ()),
    # Niveau sonore (coupé par défaut, INACTIFS_PAR_DEFAUT) : le micro de l'appareil, seul un nombre remonte.
    "son": ((), ()),
}


def nom(module: str, langue: str | None = None) -> str:
    """Le nom d'un module dans une langue (sans langue : celle de Home Assistant)."""
    return textes.t(f"module.{module}.nom", langue)


def phrase(module: str, langue: str | None = None) -> str:
    """La phrase qui décrit un module dans une langue (sans langue : celle de Home Assistant)."""
    return textes.t(f"module.{module}.phrase", langue)


# identifiant → (nom, phrase, requiert (tous), requiert_un (au moins un)), en français : les journaux, et les
# écrans qui ne passent pas encore par `nom` / `phrase`.
CATALOGUE: dict[str, tuple[str, str, tuple[str, ...], tuple[str, ...]]] = {
    m: (nom(m, "fr"), phrase(m, "fr"), tous, un) for m, (tous, un) in _DEPENDANCES.items()
}
TOUS = tuple(CATALOGUE)
# Coupés tant que l'administrateur ne les a pas activés (absents des préréglages).
INACTIFS_PAR_DEFAUT = frozenset({"reveil", "son"})

PRESETS: dict[str, tuple[str, ...]] = {
    "simple": ("plages", "temps", "demandes", "messages", "preavis"),
    "famille": ("plages", "temps", "demandes", "messages", "preavis", "blocage", "filtrage", "deverrouillage", "pause",
                "veille", "accueil", "protections", "pause_generale"),
    "tout": tuple(m for m in TOUS if m not in INACTIFS_PAR_DEFAUT),
}


def actifs(store) -> set[str]:
    """Les modules actifs (absent = actif, sauf INACTIFS_PAR_DEFAUT)."""
    choix = getattr(store, "modules", None) or {}
    return {m for m in TOUS if choix.get(m, m not in INACTIFS_PAR_DEFAUT)}


def actif(store, module: str) -> bool:
    return module in actifs(store)


def manques(voulus: set[str], langue: str | None = None) -> dict[str, str]:
    """Pour chaque module voulu dont les dépendances manquent : la phrase qui le dit (sans langue : celle de HA)."""
    sortie: dict[str, str] = {}

    def cite(d: str) -> str:
        return textes.t("format.guillemets", langue, x=nom(d, langue))

    for m in sorted(voulus):
        _nom, _phrase, tous, un = CATALOGUE[m]
        absents = [d for d in tous if d not in voulus]
        if absents:
            sortie[m] = textes.t("module.besoin", langue, module=nom(m, langue), liste=", ".join(cite(a) for a in absents))
        elif un and not any(d in voulus for d in un):
            sortie[m] = textes.t("module.besoin_un", langue, module=nom(m, langue), liste=", ".join(cite(d) for d in un))
    return sortie


def regler(hass: HomeAssistant, store, voulus: set[str], par: str) -> tuple[set[str], set[str]]:
    """Enregistre le choix (déjà validé). Rend (activés, désactivés). Journalisé."""
    inconnus = set(voulus) - set(TOUS)
    if inconnus:
        raise textes.Erreur("module.inconnu", liste=", ".join(sorted(inconnus)))
    erreurs = manques(set(voulus))
    if erreurs:
        raise ValueError(" ".join(erreurs.values()))
    avant = actifs(store)
    store.modules = {m: (m in voulus) for m in TOUS}
    apres = actifs(store)
    actives, desactives = apres - avant, avant - apres
    if actives or desactives:
        noms = lambda ens: ", ".join(CATALOGUE[m][0] for m in sorted(ens)) or "aucun"  # noqa: E731
        _LOGGER.warning("%s : modules — activés : %s ; désactivés : %s (par %s).", PRODUIT, noms(actives), noms(desactives), par)
        hass.bus.async_fire(EVENEMENT, {"actives": sorted(actives), "desactives": sorted(desactives), "par": par})
    return actives, desactives


def public(store) -> list[str]:
    """Ce que le relevé envoie aux applis et à l'agent : la liste des modules actifs."""
    return sorted(actifs(store))


def resume(store, langue: str | None = None) -> str:
    a = actifs(store)
    return "\n".join(f"- {'✅' if m in a else '⬜'} **{nom(m, langue)}** — {phrase(m, langue)}" for m in CATALOGUE)


def demandeurs(store) -> list[str]:
    """Les appareils protégés (rôle avec « protege ») : l'avertissement des protections les nomme."""
    from . import roles

    return [str(pc.get("name") or i) for i, pc in store.pcs.items() if roles.a(store, pc, "protege")]


def info(store, langue: str | None = None) -> dict[str, Any]:
    return {"actifs": public(store), "catalogue": {m: {"nom": nom(m, langue), "phrase": phrase(m, langue)} for m in CATALOGUE}}


# ----------------------------------------------------------------------------- Ce que coupe un module (serveur)
#
# Couper n'efface jamais rien : les réglages d'un module coupé restent dans le magasin, ils sont simplement
# ignorés (relevé, espace gestion, tableau de bord) jusqu'à la réactivation, qui les retrouve tels quels.
# Le détail, module par module : docs (livrable modules-serveur.md).


def coupe(store, *mods: str) -> bool:
    """Vrai si AUCUN de ces modules n'est actif (une fonction partagée reste tant qu'un de ses modules vit)."""
    a = actifs(store)
    return not any(m in a for m in mods)


def refus(vue, store, *mods: str, langue: str | None = None):
    """La réponse d'un point d'accès dont le module est coupé : 403 {"error": "module"} ; None s'il est actif.

    Le texte est dans `langue` (celle de l'appareil qui appelle), sinon dans celle de Home Assistant."""
    if store is None or not coupe(store, *mods):
        return None
    langue = langue or textes.ha(getattr(vue, "hass", None))
    return vue.json({"ok": False, "error": "module", "module": mods[0],
                     "retour": textes.t("commun.module_coupe", langue, module=nom(mods[0], langue))}, status_code=403)


def exiger(store, *mods: str) -> None:
    """Pour un service ou une entité : HomeAssistantError si aucun de ces modules n'est actif."""
    if store is not None and coupe(store, *mods):
        from homeassistant.exceptions import HomeAssistantError

        raise HomeAssistantError(textes.t("commun.module_coupe_long", None, module=nom(mods[0])))


def store_de(hass: HomeAssistant):
    """Le magasin de l'entrée chargée (None pendant le chargement)."""
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return getattr(entrees[0], "store", None) if entrees else None


# Les fonctions de rôle sans objet quand leur module est coupé : le relevé ne les annonce plus (le rôle,
# lui, n'est pas modifié : elles reviennent avec le module).
FONCTIONS_MODULES: dict[str, tuple[str, ...]] = {
    "approuver": ("deverrouillage",),
    "demander_deverrouillage": ("deverrouillage",),
    "demander_acces": ("demandes",),
    "pause": ("pause",),
    "temps": ("temps",),
    "messages": ("messages",),
    "accueil": ("accueil",),
    "entites_voir": ("entites",),
    "entites_piloter": ("entites",),
    "veille": ("veille",),
    "reveil_gerer": ("reveil",),
    "reveil_propres": ("reveil",),
}


def fonctions_effectives(store, fonctions: list[str]) -> list[str]:
    return [f for f in fonctions if not (f in FONCTIONS_MODULES and coupe(store, *FONCTIONS_MODULES[f]))]


# Gestes de l'espace gestion (parent.agir) → modules (au moins un actif). Absents : le socle (ouvrir,
# fermer, annuler, « + N min », partage, attribuer).
GESTES: dict[str, tuple[str, ...]] = {
    "plage": ("plages",),
    "limite": ("temps",),
    "etiquette": ("blocage", "filtrage"),
    "acces": ("demandes",),
    "pause_max": ("pause",),
    "message": ("messages",),
    "logiciel": ("optimisations",),
    "pause_generale": ("pause_generale",),
    "fin_pause_generale": ("pause_generale",),
    "concentration": ("concentration",),
}


def geste_coupe(store, action: str, corps: dict[str, Any] | None = None) -> str | None:
    """Le module (coupé) qui interdit ce geste de l'espace gestion, ou None."""
    corps = corps or {}
    if action in ("accorder", "retirer_autorisation"):
        mods = ("blocage",) if str(corps.get("genre") or "") == "apps" or str(corps.get("nom") or "").lower().startswith(
            "app:") else ("filtrage",)
        return mods[0] if coupe(store, *mods) else None
    if action == "autoriser":
        decision = str(corps.get("decision") or "temporaire")
        if decision in ("disponible", "indisponible"):
            mods: tuple[str, ...] = ("plages", "blocage")
        else:
            mods = ("blocage",) if str(corps.get("genre") or "") == "apps" else ("filtrage",)
    else:
        mods = GESTES.get(action, ())
    return mods[0] if mods and coupe(store, *mods) else None


# ----------------------------------------------------------------------------- Les entités des modules
#
# plateforme → {début de l'unique_id sans « pc_parental_ » : modules}. L'entité est désactivée par
# l'intégration quand AUCUN de ces modules n'est actif (historique gardé, jamais supprimée), réactivée quand
# l'un revient — seulement si c'est l'intégration qui l'avait désactivée, et si la matrice (type d'appareil)
# ne la veut pas éteinte. Le début le plus long l'emporte.
_B, _F, _BF = ("blocage",), ("filtrage",), ("blocage", "filtrage")
ENTITES: dict[str, dict[str, tuple[str, ...]]] = {
    "button": {
        "creer_plage": ("plages",), "supprimer_plage": ("plages",),
        "envoyer": ("messages",),
        "bloquer_logiciel": _B, "autoriser_logiciel": _B, "sans_regle_logiciel": _B, "declasser_logiciel": _B,
        "inscrire_logiciel": _B, "retirer_logiciel": _B,
        "bloquer_site": _F, "autoriser_site": _F, "sans_regle_site": _F, "declasser_site": _F,
        "inscrire_site": _F, "retirer_site": _F, "bloquer_origine": _F, "epargner_adresse": _F,
        "retirer_adresse": _F, "laisser_passer": _F, "fermer_laissez_passer": _F,
        "rafraichir_liste_noire": _F, "rafraichir_notoriete": _F,
        "page_precedente": _BF, "page_suivante": _BF,
        "pause_generale": ("pause_generale",),
    },
    "notify": {"notify_": ("messages",)},
    "number": {
        "avertissement": ("plages",), "delai_refus": ("demandes",), "duree_laissez_passer": _F,
        "rythme_liste_noire": _F, "pause_max_": ("pause",),
    },
    "select": {
        "jours": ("plages",), "portee_plage": ("plages",), "plage_a_supprimer": ("plages",),
        "ruleportee_": ("plages",), "rulejours_": ("plages",),
        "destinataire": ("messages",), "son": ("messages",), "msg_position_": ("messages",),
        "msg_taille_": ("messages",),
        "logiciel_": _B, "site_": _F, "origine_cible": _F, "adresse_constatee": _F, "dns_filtrant": _F,
        "taille_page": _BF, "veille_": ("veille",),
    },
    "sensor": {
        "suivant_": ("plages",), "plages_": ("plages",), "temps_ecran_": ("temps",),
        "a_classer": _BF, "exceptions": _BF, "bloques": _BF, "fermetures_": _BF,
        "liste_noire_etat": _F, "alertes_": _F, "recherche_noire": _F, "blocages_": _F,
        "reveil_": ("reveil",),
    },
    "switch": {
        "rule_": ("plages",), "etq_": _BF, "veille_rendu": ("veille_tableaux",),
        "desinstallation": ("optimisations",), "virustotal": ("optimisations",),
        "notoriete": _F, "liste_noire": _F, "reveil_": ("reveil",),
    },
    "text": {
        "saisie_nom_plage": ("plages",), "saisie_etiquettes_plage": ("plages",), "ruletags_": ("plages",),
        "saisie_message": ("messages",), "saisie_adresse": _F, "saisie_recherche": _F, "saisie_ajout": _BF,
        "cle_virustotal": ("optimisations",), "liste_bloques_": _BF, "liste_autorises_": _BF,
        "veille_": ("veille",),
    },
    "time": {"new_rule_": ("plages",), "ruletime_": ("plages",)},
    "binary_sensor": {"compte_admin_": ("protections",), "pause_generale": ("pause_generale",),
                      "fenetre_reveil": ("reveil",)},
}


def modules_entite(domaine: str, unique_id: str) -> tuple[str, ...]:
    """Les modules dont dépend cette entité (vide : le socle, jamais désactivée par un module)."""
    tete = f"{DOMAIN}_"
    if not str(unique_id).startswith(tete):
        return ()
    reste = str(unique_id)[len(tete):]
    meilleur = ""
    for prefixe in ENTITES.get(domaine, {}):
        if reste.startswith(prefixe) and len(prefixe) > len(meilleur):
            meilleur = prefixe
    return ENTITES[domaine][meilleur] if meilleur else ()


def entite_coupee(store, domaine: str, unique_id: str) -> bool:
    mods = modules_entite(domaine, unique_id)
    return bool(mods) and coupe(store, *mods)


def appliquer_entites(hass: HomeAssistant, store) -> int:
    """Désactive les entités des modules coupés, réactive celles des modules revenus. Rend le nombre de changements."""
    from homeassistant.helpers import entity_registry as er

    from . import entites_reduites, matrice

    registre = er.async_get(hass)
    changements = 0
    for entree in list(registre.entities.values()):
        if entree.platform != DOMAIN:
            continue
        mods = modules_entite(entree.domain, entree.unique_id)
        if not mods:
            continue
        if coupe(store, *mods):
            if entree.disabled_by is None:
                registre.async_update_entity(entree.entity_id, disabled_by=er.RegistryEntryDisabler.INTEGRATION)
                changements += 1
                _LOGGER.warning("%s : %s désactivée (module « %s » coupé ; historique et réglages gardés).",
                                PRODUIT, entree.entity_id, CATALOGUE[mods[0]][0])
        elif entree.disabled_by is er.RegistryEntryDisabler.INTEGRATION and not matrice.sans_objet(
                store, entree.domain, entree.unique_id) and not entites_reduites.desactivee_par_uid(store, entree.unique_id):
            registre.async_update_entity(entree.entity_id, disabled_by=None)
            changements += 1
            _LOGGER.warning("%s : %s réactivée (module « %s » actif).", PRODUIT, entree.entity_id,
                            CATALOGUE[[m for m in mods if m in actifs(store)][0]][0])
    return changements
