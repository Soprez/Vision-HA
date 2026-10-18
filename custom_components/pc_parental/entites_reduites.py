"""Moins d'entités pour les nouvelles installations (refonte UX, lot 5 ; architecture cible §5).

Une installation faite avec l'assistant (lot 4), sur un magasin sans appareil, porte `store.entites_reduites`.
Ses entités sont alors créées ainsi, à leur première inscription au registre (l'existant n'est jamais touché :
une installation d'avant garde toutes ses entités, et ce que l'administrateur a réglé dans le registre prime) :

- par appareil, 6 entités visibles : état, temps d'écran du jour, ouvert ou fermé, « Ouvrir 30 min »,
  prochain changement, agent en ligne (la protection en place) ;
- cachées mais actives, parce que le tableau de bord et les cartes les lisent : planning, plages et règles,
  listes, compteurs de sites, présence, mise à jour de l'agent ;
- tout le reste d'un appareil est créé désactivé (les services font le même travail pour les automatisations) ;
- les entités du hub (saisies du tableau de bord, réglages techniques) sont cachées, sauf « Tout fermer » en
  cours, les éléments à classer et l'ouverture des inscriptions.

Une entité créée désactivée ici ne se rallume pas seule avec un module (modules.appliquer_entites,
matrice.appliquer) : seul l'administrateur la rallume, dans Home Assistant.
"""
from __future__ import annotations

from .const import DOMAIN

ACTIVES_APPAREIL = ("etat_", "temps_ecran_", "acces_", "ouvrir30_", "suivant_", "agent_")
CACHEES_APPAREIL = ("plages_", "rule_", "ruletime_", "rulejours_", "ruleportee_", "ruletags_", "liste_", "etq_",
                    "blocages_", "alertes_", "presence_", "maj_")
VISIBLES_HUB = ("pause_generale", "a_classer", "inscriptions")


def _reste(unique_id: str | None) -> str:
    uid = str(unique_id or "")
    return uid[len(DOMAIN) + 1:] if uid.startswith(f"{DOMAIN}_") else ""


def _actif(store) -> bool:
    return bool(getattr(store, "entites_reduites", False))


def desactivee(store, unique_id: str | None, appareil: bool) -> bool:
    """Vrai si l'entité est créée désactivée dans une installation réduite."""
    if not _actif(store) or not appareil:
        return False
    reste = _reste(unique_id)
    return bool(reste) and not reste.startswith(ACTIVES_APPAREIL + CACHEES_APPAREIL)


def cachee(store, unique_id: str | None, appareil: bool) -> bool:
    """Vrai si l'entité est créée cachée (active, mais hors des listes de Home Assistant)."""
    if not _actif(store):
        return False
    reste = _reste(unique_id)
    if not reste:
        return False
    if appareil:
        return reste.startswith(CACHEES_APPAREIL)
    return not any(reste == v or reste.startswith(v + "_") for v in VISIBLES_HUB)


def desactivee_par_uid(store, unique_id: str | None) -> bool:
    """Pour les règles qui rallument des entités (modules, matrice) : sans la classe, d'après le préfixe."""
    if not _actif(store):
        return False
    reste = _reste(unique_id)
    appareil = any(reste.startswith(p) for p in _PREFIXES_APPAREIL)
    return desactivee(store, unique_id, appareil)


# Les préfixes des entités d'appareil désactivées en installation réduite (pour desactivee_par_uid).
_PREFIXES_APPAREIL = ("reinscription_", "planning_", "verrouiller_", "notify_", "pause_max_", "personne_", "role_",
                      "msg_", "session_", "compte_admin_", "oublier_", "veille_pc_")
