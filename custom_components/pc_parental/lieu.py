"""Le nom du lieu (Vision 1.81.0, décision du 2026-10-08).

Vision sert à la maison, à l'école, au bureau, dans une association : là où les textes disaient
« maison », ils disent le nom choisi par l'administrateur (Vision → Configurer → Réglages généraux),
un mot neutre par défaut (« Lieu », dans la langue de Home Assistant : jamais « Maison », règle de vocabulaire).
Un nom déjà enregistré (« Maison » compris) reste tel quel. Le nom s'emploie seul, comme un titre (« Maison », « École », « Bureau ») :
les phrases qui en auraient besoin avec un article sont tournées autrement (« tous les appareils »,
« pour tout le monde »), pour rester justes quel que soit le nom.
"""

from __future__ import annotations

# Le nom par défaut, tant que rien n'est choisi : neutre, dans la langue demandée (sinon celle de Home Assistant).
DEFAUTS = {"fr": "Lieu", "en": "Place", "de": "Ort", "es": "Lugar", "it": "Luogo", "nl": "Plek"}
DEFAUT = DEFAUTS["fr"]
LONGUEUR_MAX = 40


def propre(texte: str) -> str:
    """Le nom tel qu'on l'enregistre : sans espaces autour, sans retour à la ligne, borné."""
    return " ".join(str(texte or "").split())[:LONGUEUR_MAX]


def defaut(langue: str | None = None) -> str:
    from . import langues

    return DEFAUTS.get(langues.normaliser(langue) or langues.REPLI, DEFAUTS[langues.REPLI])


def nom(store, langue: str | None = None) -> str:
    """Le nom du lieu de cette installation ; tant que rien n'est choisi, le mot par défaut dans `langue`
    (sans langue : celle de Home Assistant)."""
    choisi = propre(getattr(store, "lieu", "") or "")
    if choisi:
        return choisi
    if langue is None:
        from . import langues

        langue = langues.langue_hass(getattr(store, "hass", None))
    return defaut(langue)
