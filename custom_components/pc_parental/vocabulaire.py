"""Le vocabulaire de Vision : Décideur, Demandeur, Lieu — jamais « maison », « parent », « enfant ».

Ces mots sont personnalisables (Configurer → Vocabulaire, lot Accueil) : les textes ne les écrivent donc jamais en dur,
ils portent des champs remplis ici. Champs disponibles (docs/i18n/vocabulaire.md) :

    {decideur} {decideurs} {demandeur} {demandeurs} {lieu}
    {Decideur} {Decideurs} {Demandeur} {Demandeurs} {Lieu}   (majuscule initiale, début de phrase)

{lieu} est le nom du lieu configuré (lieu.py : « École », « Bureau »…), tel qu'il est écrit, dans toutes les
langues ; rien de choisi : « Lieu », « Place »… (lieu.DEFAUTS). Le réglage « Vocabulaire » surcharge les autres mots par
langue dans `store.mots_vocabulaire` (store.vocabulaire est la liste des étiquettes, autre chose) :
{"fr": {"decideur": "responsable", ...}, "en": {...}} ; absent ou vide = les mots ci-dessous. Le lieu se règle dans
« Réglages généraux » (store.lieu), pas ici. Le relevé des appareils porte `vocabulaire` = termes(langue de l'appareil).
"""
from __future__ import annotations

from typing import Any

from . import langues

# Les mots par défaut (glossaire), en minuscules sauf là où la langue met toujours une majuscule (noms allemands).
DEFAUTS: dict[str, dict[str, str]] = {
    "fr": {"decideur": "décideur", "decideurs": "décideurs", "demandeur": "demandeur", "demandeurs": "demandeurs"},
    "en": {"decideur": "decider", "decideurs": "deciders", "demandeur": "requester", "demandeurs": "requesters"},
    "de": {"decideur": "Entscheider", "decideurs": "Entscheider", "demandeur": "Anfragender",
           "demandeurs": "Anfragende"},
    "es": {"decideur": "responsable", "decideurs": "responsables", "demandeur": "solicitante",
           "demandeurs": "solicitantes"},
    "it": {"decideur": "decisore", "decideurs": "decisori", "demandeur": "richiedente", "demandeurs": "richiedenti"},
    "nl": {"decideur": "beslisser", "decideurs": "beslissers", "demandeur": "aanvrager", "demandeurs": "aanvragers"},
}
CHAMPS = ("decideur", "decideurs", "demandeur", "demandeurs", "lieu")
# Le lieu tant que rien n'est choisi : un mot neutre par langue (lieu.DEFAUTS), jamais « Maison ».
from .lieu import DEFAUTS as LIEU_DEFAUTS  # noqa: E402


# Les mots que l'administrateur peut changer (le lieu a son propre réglage), et leur longueur maximale.
MOTS = ("decideur", "decideurs", "demandeur", "demandeurs")
LONGUEUR_MAX = 30


def nettoyer(mot: Any) -> str:
    """Un mot saisi, propre : espaces regroupées, caractères de contrôle et accolades retirés ; "" = le défaut."""
    texte = "".join(c for c in str(mot or "") if c.isprintable() and c not in "{}")
    return " ".join(texte.split())


def defauts(langue: str | None) -> dict[str, str]:
    """Les mots par défaut d'une langue (les quatre réglables)."""
    lg = langues.normaliser(langue) or langues.REPLI
    base = DEFAUTS.get(lg) or DEFAUTS[langues.REPLI]
    return {m: base[m] for m in MOTS}


def reglage(store: Any, langue: str | None) -> dict[str, str]:
    """Les mots choisis pour une langue (seulement ceux qui diffèrent du défaut)."""
    lg = langues.normaliser(langue) or langues.REPLI
    perso = getattr(store, "mots_vocabulaire", None)
    choisis = dict((perso or {}).get(lg) or {}) if isinstance(perso, dict) else {}
    return {m: choisis[m] for m in MOTS if isinstance(choisis.get(m), str) and nettoyer(choisis[m])}


def preparer(saisie: dict[str, Any], langue: str | None) -> dict[str, str]:
    """La saisie de l'écran → les mots à garder pour la langue : vide ou égal au défaut = retiré (repli).

    ValueError(mot) si un mot dépasse LONGUEUR_MAX caractères."""
    base = defauts(langue)
    sortie: dict[str, str] = {}
    for m in MOTS:
        mot = nettoyer(saisie.get(m))
        if len(mot) > LONGUEUR_MAX:
            raise ValueError(m)
        if mot and mot != base[m]:
            sortie[m] = mot
    return sortie


def poser(store: Any, langue: str | None, mots: dict[str, str]) -> bool:
    """Enregistre les mots d'une langue dans le magasin (sans sauvegarder) ; vrai si quelque chose change."""
    lg = langues.normaliser(langue) or langues.REPLI
    tous = {k: dict(v) for k, v in dict(getattr(store, "mots_vocabulaire", None) or {}).items()}
    if dict(tous.get(lg) or {}) == mots:
        return False
    if mots:
        tous[lg] = dict(mots)
    else:
        tous.pop(lg, None)
    store.mots_vocabulaire = tous
    return True


def _majuscule(mot: str) -> str:
    return mot[:1].upper() + mot[1:] if mot else mot


def termes(langue: str | None, store: Any = None) -> dict[str, str]:
    """Les valeurs de tous les champs pour une langue (minuscules et majuscule initiale)."""
    lg = langues.normaliser(langue) or langues.REPLI
    mots = dict(DEFAUTS.get(lg) or DEFAUTS[langues.REPLI])
    perso = getattr(store, "mots_vocabulaire", None) if store is not None else None
    if isinstance(perso, dict):
        for cle, valeur in dict(perso.get(lg) or {}).items():
            if cle in MOTS and isinstance(valeur, str) and nettoyer(valeur):
                mots[cle] = nettoyer(valeur)[:LONGUEUR_MAX]
    if "lieu" not in mots:
        nom = ""
        if store is not None:
            try:
                from . import lieu

                nom = lieu.nom(store, lg)
            except Exception:  # noqa: BLE001
                nom = str(getattr(store, "lieu", "") or "")
        if not nom:
            from . import lieu

            nom = lieu.defaut(lg)
        mots["lieu"] = nom
    return {**mots, **{_majuscule(c): _majuscule(v) for c, v in mots.items()}}


# Nom employé par textes.py et roles.py.
valeurs = termes


class _Champs(dict):
    """Un champ inconnu reste tel quel (« {x} ») au lieu de faire échouer le texte entier."""

    def __missing__(self, cle: str) -> str:
        return "{" + cle + "}"


def remplir(texte: str, langue: str | None, /, store: Any = None, **champs: Any) -> str:
    """Remplit les champs du vocabulaire, puis ceux de l'appelant (qui priment)."""
    try:
        return texte.format_map(_Champs({**termes(langue, store), **champs}))
    except (ValueError, IndexError):
        return texte


class Catalogue(langues.Catalogue):
    """Un catalogue dont les textes reçoivent le vocabulaire automatiquement : passer `store=` pour {lieu}
    et les mots personnalisés ; sans store, les mots par défaut et « Maison »."""

    def t(self, cle: str, langue: str | None = None, /, store: Any = None, **champs: Any) -> str:  # type: ignore[override]
        return remplir(self.brut(cle, langue), langue, store, **champs)

    def n(self, cle: str, nombre: int, langue: str | None = None, /, store: Any = None, **champs: Any) -> str:  # type: ignore[override]
        lg = langues.normaliser(langue) or langues.REPLI
        un = nombre in (0, 1) if lg == "fr" else nombre == 1
        return self.t(f"{cle}_{'one' if un else 'other'}", lg, store, n=nombre, **champs)
