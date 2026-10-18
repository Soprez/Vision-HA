"""L'assistant d'installation en 5 étapes (refonte UX, lot 4 : EX-01 à EX-04, EX-07, HA-09, HA-10).

1. Lieu et vocabulaire : le nom du lieu, les mots des deux rôles ;
2. Personnes : qui décide (les autres sont Demandeurs, par le rôle par défaut) ;
3. Objectifs : quatre groupes de modules au lieu de 24 cases ; les dépendances sont dites en clair ;
4. Premier appareil : quel genre d'appareil relier d'abord (ou plus tard) ;
5. C'est prêt : le résumé, et ce qui vient ensuite (le tableau de bord est créé).

Avant que l'entrée existe, il n'y a pas de magasin : l'assistant d'ajout range ses choix dans `entry.data`
(« assistant ») et `appliquer_installation` les pose une fois, au premier démarrage, comme `modules_initiaux`.
« Relancer l'assistant » (Configurer) refait les mêmes étapes sur l'installation existante, sans surprise :
un groupe décoché coupe ses modules, un groupe nouvellement coché allume ses modules recommandés, un groupe
inchangé garde ses modules tels quels.
"""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.core import HomeAssistant

from .const import PRODUIT

_LOGGER = logging.getLogger(__name__)

# groupe → (modules recommandés, allumés quand on coche le groupe ; autres modules du groupe, coupés avec lui).
# L'union des modules recommandés est le préréglage « Recommandé » (modules.PRESETS["famille"]). Le Réveil et les
# optimisations ne dépendent d'aucun groupe : l'assistant ne les touche jamais.
GROUPES: dict[str, tuple[tuple[str, ...], tuple[str, ...]]] = {
    "horaires": (("plages", "temps", "preavis"), ("temps_gagne", "bilan")),
    "contenus": (("blocage", "filtrage", "protections"), ("concentration", "regles_lieu")),
    "demandes": (("demandes", "deverrouillage", "pause", "messages", "pause_generale"), ("double_validation",)),
    "ecrans": (("veille", "accueil"), ("veille_tableaux", "entites", "widgets", "habillage")),
}
GENRES = ("telephone", "tele", "pc", "plus_tard")


def membres(groupe: str) -> tuple[str, ...]:
    recommandes, autres = GROUPES[groupe]
    return recommandes + autres


def groupes_actifs(actifs: set[str]) -> set[str]:
    """Un groupe est « coché » quand au moins un de ses modules est actif."""
    return {g for g in GROUPES if set(membres(g)) & set(actifs)}


def modules_voulus(actifs: set[str], avant: set[str], apres: set[str]) -> set[str]:
    """Les modules après l'assistant, sans surprise (voir l'en-tête)."""
    voulus = set(actifs)
    for g in avant - apres:
        voulus -= set(membres(g))
    for g in apres - avant:
        voulus |= set(GROUPES[g][0])
    return voulus


def decideurs(store) -> list[str]:
    """Les personnes qui ont le rôle Décideur (le modèle, quel que soit son nom)."""
    from . import roles

    return sorted(p for p, r in (store.roles_personnes or {}).items() if r == roles.PARENT)


def regler_decideurs(hass: HomeAssistant, store, voulus: list[str], avant: list[str], par: str) -> bool:
    """Coché : le rôle Décideur ; décoché alors qu'il l'avait : le rôle par défaut. Les autres ne bougent pas.

    textes.Erreur (roles.admin_requis) si plus personne ne pourrait gérer. Vrai si quelque chose change."""
    from . import roles

    change = False
    for p in sorted(set(voulus) - set(avant)):
        if store.roles_personnes.get(p) != roles.PARENT:
            roles.attribuer_personne(hass, store, p, roles.PARENT, par)
            change = True
    for p in sorted(set(avant) - set(voulus)):
        if store.roles_personnes.get(p) == roles.PARENT:
            roles.attribuer_personne(hass, store, p, "", par)
            change = True
    return change


def avant_roles(hass: HomeAssistant, entry, store) -> bool:
    """Premier démarrage, avant les rôles : le lieu et les mots (les noms des rôles neufs les reprennent).

    Vrai si le magasin a changé. Ne fait rien une fois l'assistant appliqué."""
    from . import lieu, vocabulaire

    choix = (entry.data or {}).get("assistant")
    if not isinstance(choix, dict) or (entry.data or {}).get("assistant_applique"):
        return False
    change = False
    # Lot 5 : une installation neuve (aucun appareil encore) n'expose que l'essentiel (entites_reduites.py).
    if not store.pcs and not store.entites_reduites:
        store.entites_reduites = True
        change = True
    nom = lieu.propre(choix.get("lieu") or "")
    if nom and not store.lieu:
        store.lieu = nom
        change = True
    mots = choix.get("mots")
    if isinstance(mots, dict) and mots.get("langue") and not vocabulaire.reglage(store, mots["langue"]):
        change = vocabulaire.poser(store, mots["langue"], dict(mots.get("mots") or {})) or change
    return change


def apres_roles(hass: HomeAssistant, entry, store) -> bool:
    """Premier démarrage, une fois les rôles créés : les Décideurs choisis ; puis l'assistant est marqué appliqué.

    Vrai si le magasin a changé."""
    from . import textes

    donnees = dict(entry.data or {})
    choix = donnees.get("assistant")
    if not isinstance(choix, dict) or donnees.get("assistant_applique"):
        return False
    change = False
    voulus = [str(p) for p in (choix.get("decideurs") or []) if str(p).startswith("person.")]
    try:
        change = regler_decideurs(hass, store, voulus, [], PRODUIT)
    except textes.Erreur:  # une installation neuve n'a pas d'administrateur effectif à protéger
        _LOGGER.exception("%s : assistant — rôles des Décideurs", PRODUIT)
    hass.config_entries.async_update_entry(entry, data={**donnees, "assistant_applique": True})
    _LOGGER.warning("%s : assistant d'installation appliqué (lieu, vocabulaire, %d Décideur(s), modules).",
                    PRODUIT, len(voulus))
    return change
