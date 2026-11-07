"""1.94 · Découverte appli → sites : « tel logiciel fait une requête vers tel site, donc c'est lié » (décision de l'auteur).

Le lien vient de l'OBSERVATION d'une requête (appareil + appli + domaine), jamais d'une liste ni d'un nom. L'appareil
remonte, par domaine, quelle appli l'a demandé et combien de fois ; Home Assistant garde ces comptes par jour (sept jours)
et décide : un domaine est lié à une appli quand, sur les sept derniers jours, elle l'a demandé au moins
`SEUIL_N` fois sur au moins `SEUIL_JOURS` jours et pèse au moins `SEUIL_PART` de ses demandes. Un domaine demandé par
`APPS_PARTAGE` applis ou plus est partagé (CDN, traceurs) : jamais lié. Les demandes sans appli connue (« incertain » :
Android avant 10, UID perdu) sont gardées pour l'affichage mais ne comptent jamais.

Deux formats de relevé, même résultat :
- appareil Android : `{domaine: {paquet: {"n": demandes, "incertain": demandes sans UID, "premier": ts, "dernier": ts,
  "bloque": demandes bloquées}}}` (200 domaines au plus) ;
- agent PC : `{adresse: [programme, …]}` (un instantané : chaque présence compte pour une demande).
Jamais d'URL ni de chemin : seulement des domaines, des comptes et des dates.
"""
from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Any, Callable

JOURS = 7                 # fenêtre d'observation
SEUIL_N = 5               # demandes d'une appli sur un domaine
SEUIL_JOURS = 2           # jours différents
SEUIL_PART = 0.70         # part de l'appli parmi toutes les demandes du domaine
APPS_PARTAGE = 3          # un domaine demandé par 3 applis ou plus n'est jamais lié
MAX_RELEVE = 200          # domaines pris dans un relevé
MAX_DOMAINES = 400        # domaines gardés par appareil
MAX_APPS = 12             # applis gardées par domaine
MAX_N = 100_000


def _jour(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%d")


def _entier(v: Any) -> int:
    try:
        return max(0, min(MAX_N, int(v)))
    except (TypeError, ValueError):
        return 0


def mesures(pc: dict[str, Any]) -> dict[str, dict[str, dict[str, Any]]]:
    m = pc.get("mesures")
    if not isinstance(m, dict):
        m = pc["mesures"] = {}
    return m


def noter(pc: dict[str, Any], origines: Any, nom_domaine: Callable[[str], str], nom_app: Callable[[str], str],
          maintenant: float | None = None) -> int:
    """Range un relevé d'origines dans les mesures de l'appareil ; rend le nombre de domaines pris en compte.
    `nom_domaine` ramène une adresse à son domaine principal (« rien d'autre qu'un domaine »), `nom_app` un
    paquet ou un programme à son nom."""
    if not isinstance(origines, dict):
        return 0
    maintenant = maintenant or time.time()
    jour = _jour(maintenant)
    m = mesures(pc)
    pris = 0
    for brut, qui in list(origines.items())[:MAX_RELEVE]:
        domaine = nom_domaine(str(brut))
        if not domaine or "/" in domaine or " " in domaine:
            continue
        if isinstance(qui, dict):
            lignes = {str(p): v for p, v in list(qui.items())[:MAX_APPS] if isinstance(v, dict)}
        elif isinstance(qui, (list, tuple)):
            lignes = {str(p): {"n": 1} for p in list(qui)[:MAX_APPS]}
        else:
            continue
        for paquet, v in lignes.items():
            app = nom_app(paquet)
            if not app:
                continue
            fiche = m.setdefault(domaine, {})
            if app not in fiche and len(fiche) >= MAX_APPS:
                continue
            e = fiche.setdefault(app, {"jours": {}, "n": 0, "incertain": 0, "bloque": 0, "dernier": 0})
            n, inc, bloque = _entier(v.get("n")), _entier(v.get("incertain")), _entier(v.get("bloque"))
            if n:
                e["jours"][jour] = _entier(e["jours"].get(jour)) + n
                e["n"] = _entier(e.get("n")) + n
            e["incertain"] = _entier(e.get("incertain")) + inc
            e["bloque"] = _entier(e.get("bloque")) + bloque
            e["dernier"] = max(float(e.get("dernier") or 0), min(maintenant, float(v.get("dernier") or maintenant)))
        pris += 1
    purger(pc, maintenant)
    return pris


def purger(pc: dict[str, Any], maintenant: float | None = None) -> None:
    """Oublie ce qui a plus de sept jours, puis les domaines qui n'ont plus personne, puis garde les plus vus."""
    maintenant = maintenant or time.time()
    limite = _jour(maintenant - (JOURS + 1) * 86400)
    m = mesures(pc)
    for domaine in list(m):
        for app in list(m[domaine]):
            e = m[domaine][app]
            e["jours"] = {j: n for j, n in (e.get("jours") or {}).items() if j >= limite}
            if not e["jours"] and float(e.get("dernier") or 0) < maintenant - (JOURS + 1) * 86400:
                del m[domaine][app]
        if not m[domaine]:
            del m[domaine]
    if len(m) > MAX_DOMAINES:
        poids = {d: sum(sum(e["jours"].values()) for e in apps.values()) for d, apps in m.items()}
        for d in sorted(m, key=lambda d: poids[d])[: len(m) - MAX_DOMAINES]:
            del m[d]


def _fenetre(e: dict[str, Any], maintenant: float) -> tuple[int, int]:
    """(demandes, jours) de cette appli sur ce domaine pendant les sept derniers jours."""
    debut = _jour(maintenant - (JOURS - 1) * 86400)
    jours = {j: n for j, n in (e.get("jours") or {}).items() if j >= debut and n > 0}
    return sum(jours.values()), len(jours)


def observation(pc: dict[str, Any], domaine: str, maintenant: float | None = None) -> dict[str, tuple[int, int]]:
    """Qui a demandé ce domaine pendant les sept derniers jours sur cet appareil : {appli: (demandes, jours)}."""
    maintenant = maintenant or time.time()
    sortie: dict[str, tuple[int, int]] = {}
    for app, e in (mesures(pc).get(domaine) or {}).items():
        n, j = _fenetre(e, maintenant)
        if n:
            sortie[app] = (n, j)
    return sortie


def evaluer(pc: dict[str, Any], maintenant: float | None = None) -> list[tuple[str, str, dict[str, Any]]]:
    """Les liens que la mesure justifie : [(appli, domaine, preuve)], preuve = {n, jours, part, source: "mesure"}.
    Applique seuls les seuils et la règle des applis multiples ; le reste du filtre (domaines communs, nom d'une
    autre appli, garde-fous) est dans `Store.lier_auto`."""
    maintenant = maintenant or time.time()
    liens: list[tuple[str, str, dict[str, Any]]] = []
    for domaine in list(mesures(pc)):
        qui = observation(pc, domaine, maintenant)
        total = sum(n for n, _ in qui.values())
        if not qui or len(qui) >= APPS_PARTAGE:
            continue
        for app, (n, jours) in qui.items():
            part = n / total
            if n >= SEUIL_N and jours >= SEUIL_JOURS and part >= SEUIL_PART:
                liens.append((app, domaine, {"n": n, "jours": jours, "part": round(part, 2), "source": "mesure"}))
    return liens


def nb_applis(pc: dict[str, Any], domaine: str, maintenant: float | None = None) -> int:
    return len(observation(pc, domaine, maintenant))
