"""Évaluation du planning d'un PC.

Ce module ne dépend que de la bibliothèque standard : il est testable sans
Home Assistant (voir tests/test_planner.py).

Vocabulaire : une « plage » décrit un intervalle pendant lequel quelque
chose est **fermé**. Ce quelque chose est soit la session — le PC est
verrouillé — soit une ou plusieurs étiquettes : « Jeux » et « Vidéo » sont
coupés, le reste de la machine continue de servir.

Le module ne tranche pas entre les deux : il sait dire si une plage couvre
un instant, et l'appelant lui passe la liste qui l'intéresse.

Une plage qui enjambe minuit (``start`` > ``end``) appartient au jour où elle
commence : « Nuit 23h00 → 07h00 » cochée le vendredi ferme le PC du vendredi
23h00 au samedi 07h00.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Any, Iterable

DAY = 86400
# Horizon de recherche du prochain basculement.
HORIZON_DAYS = 8

WEEKDAY_NAMES = [
    "lundi",
    "mardi",
    "mercredi",
    "jeudi",
    "vendredi",
    "samedi",
    "dimanche",
]


PORTEE_SESSION = "session"
PORTEE_ETIQUETTES = "etiquettes"


def normalise_rule(rule: dict[str, Any]) -> dict[str, Any]:
    """Complète une plage avec des valeurs sûres.

    Une plage écrite avant la 1.33.0 n'a pas de portée : elle verrouille la
    session, c'est ce qu'elle a toujours fait. Le défaut est donc le seul
    qui ne change le comportement de personne.
    """
    jours = list(rule.get("weekdays") or [])
    jours = [bool(j) for j in jours][:7]
    jours += [False] * (7 - len(jours))
    portee = str(rule.get("portee") or PORTEE_SESSION)
    if portee != PORTEE_ETIQUETTES:
        portee = PORTEE_SESSION
    etiquettes: list[str] = []
    for valeur in rule.get("etiquettes") or []:
        valeur = str(valeur).strip()
        if valeur and valeur.lower() not in [e.lower() for e in etiquettes]:
            etiquettes.append(valeur)
    # Depuis la 1.45.0 une plage peut aussi viser **nommément** un site ou
    # un logiciel. Une catégorie ne dit pas tout : « plus de Discord après
    # 21h » n'a pas à devenir une étiquette qu'on inventerait pour un seul
    # nom. Les deux listes cohabitent, une plage peut porter les deux.
    elements: list[dict[str, str]] = []
    vus: set[tuple[str, str]] = set()
    for brut in rule.get("elements") or []:
        if not isinstance(brut, dict):
            continue
        genre = str(brut.get("genre") or "").strip().lower()
        nom = str(brut.get("nom") or "").strip()
        if genre not in ("apps", "sites") or not nom:
            continue
        cle = (genre, nom.lower())
        if cle in vus:
            continue
        vus.add(cle)
        elements.append({"genre": genre, "nom": nom})
    return {
        "id": int(rule.get("id", 0)),
        "name": str(rule.get("name") or "Plage"),
        "start": int(rule.get("start", 0)) % DAY,
        "end": int(rule.get("end", 0)) % DAY,
        "weekdays": jours,
        "enabled": bool(rule.get("enabled", True)),
        "portee": portee,
        "etiquettes": etiquettes,
        "elements": elements,
        # Le planning est celui de la personne ; une plage peut épargner certains de ses appareils.
        "sauf": list(dict.fromkeys(str(i) for i in (rule.get("sauf") or []) if str(i).strip())),
    }


def cibles_label(rule: dict[str, Any], langue: str | None = None) -> str:
    """Ce qu'une plage ferme, écrit pour être lu.

    Sert au libellé de la plage, à l'avertissement envoyé sur le PC, et à
    la carte. Un seul endroit : deux formulations pour la même plage, et
    on ne la reconnaît plus d'un écran à l'autre.

    Sans langue, les catégories gardent leur nom enregistré (le libellé sert d'identifiant à des entités) ;
    avec une langue, elles sont dites dans celle-ci (l'avertissement envoyé à un appareil).
    """
    r = normalise_rule(rule)
    if r["portee"] != PORTEE_ETIQUETTES:
        return "session"
    etiquettes = list(r["etiquettes"])
    if langue:
        from . import textes

        etiquettes = [textes.categorie(e, langue) for e in etiquettes]
    noms = etiquettes + [e["nom"] for e in r["elements"]]
    return ", ".join(noms) or "session"


def de_portee(
    rules: Iterable[dict[str, Any]], portee: str
) -> list[dict[str, Any]]:
    """Les plages d'une portée donnée, normalisées.

    Tout passe par ici : oublier ce filtre, c'est verrouiller le PC pour une
    plage qui ne devait fermer que « Jeux ».
    """
    return [r for r in (normalise_rule(x) for x in rules) if r["portee"] == portee]


def _actives(rules: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    sorties = []
    for rule in rules:
        r = normalise_rule(rule)
        # Une plage dont le début et la fin coïncident ne couvre rien —
        # pas plus qu'une plage d'étiquettes qui n'en nomme aucune.
        if not (r["enabled"] and r["start"] != r["end"] and any(r["weekdays"])):
            continue
        if (
            r["portee"] == PORTEE_ETIQUETTES
            and not r["etiquettes"]
            and not r["elements"]
        ):
            continue
        sorties.append(r)
    return sorties


def rule_covers(rule: dict[str, Any], moment: datetime) -> bool:
    """Indique si une plage couvre l'instant donné."""
    r = normalise_rule(rule)
    seconde = moment.hour * 3600 + moment.minute * 60 + moment.second
    jour = moment.weekday()
    veille = (jour - 1) % 7
    if r["start"] == r["end"]:
        return False
    if r["start"] < r["end"]:
        return r["weekdays"][jour] and r["start"] <= seconde < r["end"]
    # Plage à cheval sur minuit.
    if r["weekdays"][jour] and seconde >= r["start"]:
        return True
    return r["weekdays"][veille] and seconde < r["end"]


def is_locked(rules: Iterable[dict[str, Any]], moment: datetime) -> bool:
    """Le planning ferme-t-il le PC à cet instant ?

    Seules les plages de session comptent : une plage d'étiquettes ne
    verrouille rien.
    """
    return any(
        rule_covers(rule, moment)
        for rule in _actives(de_portee(rules, PORTEE_SESSION))
    )


def _boundaries(
    rules: Iterable[dict[str, Any]], depart: date
) -> list[datetime]:
    """Tous les instants où une plage commence ou se termine."""
    bornes: set[datetime] = set()
    for r in _actives(de_portee(rules, PORTEE_SESSION)):
        for delta in range(-1, HORIZON_DAYS + 1):
            jour = depart + timedelta(days=delta)
            if not r["weekdays"][jour.weekday()]:
                continue
            debut = datetime.combine(jour, datetime.min.time()) + timedelta(
                seconds=r["start"]
            )
            fin = datetime.combine(jour, datetime.min.time()) + timedelta(
                seconds=r["end"]
            )
            if r["start"] > r["end"]:
                fin += timedelta(days=1)
            bornes.add(debut)
            bornes.add(fin)
    return sorted(bornes)


def next_change(
    rules: Iterable[dict[str, Any]], moment: datetime
) -> datetime | None:
    """Prochain instant où l'état du planning change, ou None."""
    actuel = is_locked(rules, moment)
    for borne in _boundaries(rules, moment.date()):
        if borne <= moment:
            continue
        if is_locked(rules, borne) != actuel:
            return borne
    return None


def next_lock(
    rules: Iterable[dict[str, Any]], moment: datetime
) -> datetime | None:
    """Prochaine fermeture, si le PC est actuellement ouvert."""
    if is_locked(rules, moment):
        return None
    return next_change(rules, moment)


def prochaine_fermeture(
    rule: dict[str, Any], moment: datetime
) -> datetime | None:
    """Quand cette plage-ci va commencer à couvrir, si ce n'est pas déjà fait.

    `next_lock` ne répond que pour le verrouillage de la session ; ici on
    interroge une plage précise, quelle que soit sa portée. C'est ce qui
    permet de prévenir « Jeux : fermeture à 18h00 » sans rien verrouiller.
    """
    actives = _actives([rule])
    if not actives:
        return None
    r = actives[0]
    if rule_covers(r, moment):
        return None
    jour = moment.date()
    for delta in range(-1, HORIZON_DAYS + 1):
        depart = jour + timedelta(days=delta)
        if not r["weekdays"][depart.weekday()]:
            continue
        debut = datetime.combine(depart, datetime.min.time()) + timedelta(
            seconds=r["start"]
        )
        if debut > moment:
            return debut
    return None


def hhmm(secondes: int) -> str:
    """21600 → « 06h00 »."""
    secondes = int(secondes) % DAY
    return f"{secondes // 3600:02d}h{(secondes % 3600) // 60:02d}"


# Ce qu'on accepte de lire pour désigner un jour. Les formes courtes et les
# formes sans accent comprises : on écrit vite, et « decembre » vaut
# « décembre » partout ailleurs.
_JOURS_LUS: dict[str, int] = {}
for _i, _nom in enumerate(WEEKDAY_NAMES):
    _JOURS_LUS[_nom] = _i
    _JOURS_LUS[_nom[:3]] = _i
_JOURS_LUS["dim"] = 6
_JOURS_LUS["sam"] = 5

_RACCOURCIS: dict[str, list[bool]] = {
    "tous les jours": [True] * 7,
    "tous": [True] * 7,
    "chaque jour": [True] * 7,
    "semaine": [True] * 5 + [False, False],
    "en semaine": [True] * 5 + [False, False],
    "jours d'école": [True] * 5 + [False, False],
    "week-end": [False] * 5 + [True, True],
    "weekend": [False] * 5 + [True, True],
    "le week-end": [False] * 5 + [True, True],
    "jamais": [False] * 7,
}


def _sans_accent(texte: str) -> str:
    for avant, apres in (("é", "e"), ("è", "e"), ("ê", "e"), ("ë", "e"),
                         ("à", "a"), ("â", "a"), ("î", "i"), ("ï", "i"),
                         ("ô", "o"), ("û", "u"), ("ù", "u"), ("ç", "c")):
        texte = texte.replace(avant, apres)
    return texte


def jours_depuis_texte(texte: str) -> list[bool]:
    """Lit des jours écrits à la main, dans les formes qu'on écrit vraiment.

    « du lundi au vendredi », « semaine », « week-end », « lun, mer, ven »,
    « lundi mercredi et vendredi » — et ce que `days_label` produit, pour
    qu'on puisse relire puis réécrire sans que la phrase change de sens.

    Rend sept booléens. Une phrase qu'on ne comprend pas ne devient pas
    « aucun jour » en silence : elle lève une erreur, et l'appelant le dit.
    """
    from . import textes

    brut = str(texte or "").strip().lower()
    if not brut:
        raise textes.Erreur("planner.un_jour")
    normalise = _sans_accent(brut)

    for cle, jours in _RACCOURCIS.items():
        if normalise == _sans_accent(cle):
            return list(jours)

    # « du lundi au jeudi » : un intervalle, éventuellement à cheval sur
    # la fin de semaine — « du samedi au mardi » se lit comme on l'écrit.
    if normalise.startswith("du ") and " au " in normalise:
        depart, arrivee = normalise[3:].split(" au ", 1)
        d = _JOURS_LUS.get(_sans_accent(depart.strip()))
        f = _JOURS_LUS.get(_sans_accent(arrivee.strip()))
        if d is not None and f is not None:
            jours = [False] * 7
            i = d
            while True:
                jours[i] = True
                if i == f:
                    break
                i = (i + 1) % 7
            return jours

    jours = [False] * 7
    trouve = False
    morceaux = (
        normalise.replace(" et ", ",").replace(";", ",").replace(" ", ",")
    )
    # Les petits mots qu'on écrit sans y penser. Ils ne désignent aucun
    # jour, et les retirer par caractères mangeait le « l » de « lun ».
    liaisons = {"le", "les", "la", "l", "de", "des", "du", "au", "aux", "et"}
    for morceau in morceaux.split(","):
        morceau = morceau.strip()
        if not morceau or morceau in liaisons:
            continue
        indice = _JOURS_LUS.get(morceau)
        if indice is None:
            raise textes.Erreur("planner.jour_inconnu", jour=morceau)
        jours[indice] = True
        trouve = True
    if not trouve:
        raise textes.Erreur("planner.un_jour")
    return jours


def days_label(weekdays: Iterable[bool], langue: str | None = None) -> str:
    """Libellé lisible d'une sélection de jours.

    Sans langue : en français, tel que `jours_depuis_texte` le relit (des sélecteurs s'en servent comme valeur).
    Avec une langue : pour être lu seulement.
    """
    from . import textes

    lg = langue or "fr"
    jours = [bool(j) for j in list(weekdays)[:7]]
    jours += [False] * (7 - len(jours))
    coches = [i for i, j in enumerate(jours) if j]

    def nom(i: int) -> str:
        return textes.t(f"planner.jour_{i}", lg)

    if not coches:
        return textes.t("planner.jamais", lg)
    if len(coches) == 7:
        return textes.t("planner.tous_les_jours", lg)
    if coches == [0, 1, 2, 3, 4]:
        return textes.t("planner.semaine", lg)
    if coches == [5, 6]:
        return textes.t("planner.week_end", lg)
    if len(coches) == 1:
        return textes.t("planner.le_jour", lg, jour=nom(coches[0]))
    # Suite continue d'au moins trois jours : « du lundi au jeudi ».
    if len(coches) >= 3 and coches == list(range(coches[0], coches[-1] + 1)):
        return textes.t("planner.intervalle", lg, debut=nom(coches[0]), fin=nom(coches[-1]))
    noms = [nom(i) for i in coches]
    return textes.t("planner.liste", lg, debut=", ".join(noms[:-1]), fin=noms[-1])
