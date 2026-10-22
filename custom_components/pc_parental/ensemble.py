"""La vue d'ensemble du tableau : tous les appareils et toutes les personnes dans une seule liste, avec des filtres.

2.5.12.14 (retour de l'auteur : « je vois seulement le PC de chacun, je dois tout voir et filtrer, idem pour les
limites »). Deux vues, lues par la carte `vision-ensemble-card` (POST /api/pc_parental/maison, action « ensemble ») :

- « logiciels » : une ligne par logiciel ou site et par appareil, avec son état (bloqué, limité, ouvert), ce qui le
  ferme, ses catégories, la personne et l'appareil ;
- « limites » : une ligne par limite de temps par logiciel, avec le temps passé aujourd'hui, pour chaque personne.

Filtres (tous facultatifs, « Tous » par défaut) : personne, appareil, catégorie, état, texte. Le tri et le filtrage
se font ici : la carte ne reçoit qu'une tranche (150 lignes par défaut), jamais les quelque 2 000 lignes d'un coup.
Le jeu complet est calculé une fois et gardé 10 s (oublié à chaque écriture, comme le catalogue de 2.5.12.13).
"""
from __future__ import annotations

from typing import Any

from homeassistant.core import HomeAssistant

PAS = 150
PAS_MAX = 400
ETATS = ("bloque", "limite", "ouvert")
_ORDRE = {e: i for i, e in enumerate(ETATS)}


def _jeu(hass: HomeAssistant, coord) -> dict[str, Any]:
    """Toutes les lignes, triées (les fermées d'abord), et les listes de choix des filtres."""
    from . import parent
    from .store import _nom_app

    store = coord.store
    appareils: list[dict[str, str]] = []
    personnes: dict[str, str] = {}
    categories: set[str] = set()
    logiciels: list[dict[str, Any]] = []
    sources: dict[str, dict[str, Any]] = {}   # personne → son premier appareil (les limites valent pour tous les siens)
    siens: dict[str, list[str]] = {}
    for pc in store.pcs.values():
        if not pc.get("enabled", True):
            continue
        personne = str(pc.get("person") or "")
        nom_pc = str(pc.get("name") or pc["id"])
        prenom = parent._prenom(hass, pc) or nom_pc
        appareils.append({"id": pc["id"], "nom": nom_pc, "personne": personne})
        personnes.setdefault(personne or "-" + pc["id"], prenom)
        cle_personne = personne or "-" + pc["id"]
        sources.setdefault(cle_personne, pc)
        siens.setdefault(cle_personne, []).append(pc["id"])
        bloque = store.effectif(pc)
        motifs = list(bloque.get("motifs") or [])
        raisons = dict(bloque.get("raisons") or {})
        limites = store.limites(pc)
        for genre in ("apps", "sites"):
            vus = pc.get("apps_vues" if genre == "apps" else "sites_vus") or {}
            fermes = set(bloque.get(genre) or [])
            for nom in sorted(set(vus) | fermes):
                fiche = vus.get(nom) if isinstance(vus, dict) else None
                libelle = str(fiche.get("libelle") or "") if isinstance(fiche, dict) else ""
                if genre == "sites" and nom not in vus and nom.count(".") > 1 and not libelle:
                    continue  # les membres d'une famille de sites n'apportent rien (même règle que la fiche d'appareil)
                if nom in fermes:
                    etat = "bloque"
                elif genre == "apps" and _nom_app(nom) in limites:
                    etat = "limite"
                else:
                    etat = "ouvert"
                posees = store.etiquettes_de(genre, nom)
                categories.update(posees)
                i = raisons.get(nom)
                motif = str(motifs[i]).split("|").pop() if etat == "bloque" and i is not None and i < len(motifs) else ""
                ligne = {
                    "pc": pc["id"], "appareil": nom_pc, "personne": cle_personne, "prenom": prenom, "genre": genre,
                    "nom": nom, "libelle": libelle or nom, "etat": etat, "categories": list(posees),
                }
                if motif:
                    ligne["motif"] = motif
                if etat == "limite":
                    ligne["minutes"] = limites[_nom_app(nom)]
                ligne["_q"] = " ".join([ligne["libelle"], nom, nom_pc, prenom, *posees]).lower()
                logiciels.append(ligne)
    logiciels.sort(key=lambda r: (_ORDRE[r["etat"]], r["prenom"].lower(), r["appareil"].lower(), r["libelle"].lower()))

    limites_lignes: list[dict[str, Any]] = []
    for cle_personne, pc in sources.items():
        for lim in parent._limites(store, pc):
            posees = store.etiquettes_de("apps", lim["nom"])
            categories.update(posees)
            atteinte = lim["passe"] >= lim["minutes"] * 60
            limites_lignes.append({
                "pc": pc["id"], "personne": cle_personne, "prenom": personnes[cle_personne], "genre": "apps",
                "nom": lim["nom"], "libelle": lim["libelle"], "minutes": lim["minutes"], "passe": lim["passe"],
                "etat": "bloque" if atteinte else "limite", "categories": list(posees), "appareils": siens[cle_personne],
                "_q": " ".join([lim["libelle"], lim["nom"], personnes[cle_personne], *posees]).lower(),
            })
    limites_lignes.sort(key=lambda r: (_ORDRE[r["etat"]], r["prenom"].lower(), r["libelle"].lower()))
    return {
        "logiciels": logiciels,
        "limites": limites_lignes,
        "facettes": {
            "personnes": sorted(({"id": k, "nom": v} for k, v in personnes.items()), key=lambda p: p["nom"].lower()),
            "appareils": sorted(appareils, key=lambda a: a["nom"].lower()),
            "categories": sorted(categories, key=str.lower),
        },
    }


def _garde(ligne: dict[str, Any], f: dict[str, str], ignorer_etat: bool = False) -> bool:
    if f["personne"] and ligne["personne"] != f["personne"]:
        return False
    if f["appareil"] and ligne.get("pc") != f["appareil"] and f["appareil"] not in ligne.get("appareils", ()):
        return False
    if f["categorie"] and f["categorie"] not in ligne["categories"]:
        return False
    if not ignorer_etat and f["etat"] and ligne["etat"] != f["etat"]:
        return False
    return not f["q"] or f["q"] in ligne["_q"]


def _entier(valeur: Any, defaut: int) -> int:
    try:
        return int(valeur)
    except (TypeError, ValueError):
        return defaut


def lire(hass: HomeAssistant, coord, corps: dict[str, Any]) -> dict[str, Any]:
    """Une tranche de la vue demandée, avec le total, le nombre de lignes par état et les choix des filtres."""
    jeu = coord.store._memo(("ensemble",), 10.0, lambda: _jeu(hass, coord))
    vue = "limites" if corps.get("vue") == "limites" else "logiciels"
    brut = corps.get("filtres") if isinstance(corps.get("filtres"), dict) else {}
    f = {k: str(brut.get(k) or "").strip() for k in ("personne", "appareil", "categorie", "etat", "q")}
    f["q"] = f["q"].lower()
    if f["etat"] not in ETATS:
        f["etat"] = ""
    lignes = jeu[vue]
    visibles = [r for r in lignes if _garde(r, f)]
    compte = {e: 0 for e in ETATS}
    for r in lignes:
        if _garde(r, f, ignorer_etat=True):
            compte[r["etat"]] += 1
    debut = max(0, _entier(corps.get("debut"), 0))
    n = max(1, min(PAS_MAX, _entier(corps.get("n"), PAS)))
    tranche = [{k: v for k, v in r.items() if k != "_q"} for r in visibles[debut:debut + n]]
    return {"ok": True, "vue": vue, "total": len(visibles), "debut": debut, "lignes": tranche, "compte": compte,
            "facettes": jeu["facettes"]}
