"""1.94 · Autorisations refondues (spec §0 ter) : une lecture complète pour la carte, et les gestes qui la modifient.

`lire` rend, pour la personne ou l'appareil regardé (ou « tout le monde ») : les blocages « pour tous » avec leur raison,
les logiciels (badge vert / orange / rouge, portée écrite, catégories, sites découverts par l'observation avec leur preuve),
les sites sans logiciel, les catégories. L'état est celui que calcule le magasin (`statut`, `raison`, `ferme_par`) : la carte
ne recalcule rien.

`geste` exécute un geste signé du compte Home Assistant (ouvrir, bloquer, lever, laisser passer, rattacher, retirer un lien,
lier tout seul, marquer un compte partagé). Le code de déverrouillage n'est demandé que pour un compte marqué partagé (ou
qui ne peut pas être reconnu : accès d'API, tablette, télé) ; sinon le geste est consigné dans le journal « qui a fait quoi »
(`journal_gestes`).

Trois portées, toujours écrites de la même façon : « Pour tout le monde », « Pour <prénom> », « Sur <appareil> ».
"""
from __future__ import annotations

import time
from typing import Any

from homeassistant.core import HomeAssistant

from . import journal_gestes, octroi, textes
from .store import AUTORISE, _nom_app, _nom_site, domaine_principal

ACTIONS = ("ouvrir", "bloquer", "lever", "laisser_passer", "rattacher", "retirer_lien", "lier_auto", "compte_partage")
# Les gestes qui OUVRENT quelque chose (ou le rattachent) : ce sont ceux pour lesquels un compte partagé tape le code.
ACCORDENT = frozenset({"ouvrir", "lever", "laisser_passer", "rattacher"})
MAX_SITES_PAR_APPLI = 60
MAX_APPLIS = 300
MAX_SITES_SEULS = 300


class Refus(Exception):
    def __init__(self, statut: int, code: str, message: str = "", **extra: Any) -> None:
        super().__init__(message or code)
        self.statut, self.code, self.message, self.extra = statut, code, message, extra


# ----------------------------------------------------------------------------- Portées


def _prenom(hass: HomeAssistant, pc: dict[str, Any]) -> str:
    from . import parent

    return parent._prenom(hass, pc) or str(pc.get("name") or pc["id"])


def _portee_cible(coord, personne: str, appareil: str) -> tuple[list[dict[str, Any]], str, str]:
    """(appareils regardés, personne ou "", appareil ou "") ; sans choix : tous les appareils activés."""
    store = coord.store
    if appareil:
        pc = store.pcs.get(appareil)
        if pc is None:
            raise Refus(404, "appareil")
        return [pc], str(pc.get("person") or ""), appareil
    pcs = [pc for pc in store.pcs.values() if pc.get("enabled", True)]
    if personne:
        pcs = [pc for pc in pcs if str(pc.get("person") or "") == personne]
        if not pcs:
            raise Refus(404, "personne")
        return pcs, personne, ""
    return pcs, "", ""


def analyser_portee(coord, portee: str) -> tuple[str, list[dict[str, Any]]]:
    """« tous » | « personne:<person.x> » | « appareil:<id> » → (genre de portée, appareils concernés)."""
    portee = str(portee or "tous")
    if portee == "tous":
        return "tous", [pc for pc in coord.store.pcs.values() if pc.get("enabled", True)]
    genre, _, valeur = portee.partition(":")
    if genre == "personne" and valeur:
        pcs = [pc for pc in coord.store.pcs.values() if pc.get("enabled", True) and str(pc.get("person") or "") == valeur]
        if pcs:
            return "personne", pcs
        raise Refus(404, "personne")
    if genre == "appareil" and valeur and valeur in coord.store.pcs:
        return "appareil", [coord.store.pcs[valeur]]
    raise Refus(400, "portee")


def _libelle_portee(hass: HomeAssistant, coord, portee: str) -> str:
    lg = textes.ha(hass)
    genre, _, valeur = str(portee).partition(":")
    if genre == "personne":
        pcs = [pc for pc in coord.store.pcs.values() if str(pc.get("person") or "") == valeur]
        return textes.t("aut.portee_personne", lg, qui=_prenom(hass, pcs[0]) if pcs else valeur)
    if genre == "appareil":
        pc = coord.store.pcs.get(valeur)
        return textes.t("aut.portee_appareil", lg, appareil=str(pc.get("name") or valeur) if pc else valeur)
    return textes.t("aut.portee_tous", lg)


# ----------------------------------------------------------------------------- Lecture


def _etat_logiciel(hass, coord, pcs: list[dict[str, Any]], app: str, langue: str) -> dict[str, Any]:
    """Un logiciel vu sur les appareils regardés : badge agrégé (rouge si fermé partout, orange si fermé quelque part ou si
    un site lié reste fermé, vert sinon), raison, portée écrite, sites avec leur lien et leur preuve."""
    store = coord.store
    lignes = [(pc, store._ligne_logiciel(pc, app, langue)) for pc in pcs]
    ouverts = [(pc, l) for pc, l in lignes if l.get("ouvert")]
    fermes = [(pc, l) for pc, l in lignes if not l.get("ouvert")]
    # Les sites : ceux du premier appareil où le logiciel est ouvert (sinon du premier), fermés si fermés quelque part.
    modele_pc, modele = (ouverts or fermes)[0]
    sites: list[dict[str, Any]] = []
    for s in (modele.get("sites") or [])[:MAX_SITES_PAR_APPLI]:
        ferme_sur = [pc for pc in pcs if store.statut(pc, "sites", s["nom"]) != AUTORISE
                     and not _suit_logiciel_ouvert(store, pc, app, s["nom"])]
        sites.append(_ligne_site(hass, coord, modele_pc, s, ferme_sur, pcs, langue))
    if not ouverts:
        badge = "rouge"
    elif fermes or any(s["badge"] != "vert" for s in sites):
        badge = "orange"
    else:
        badge = "vert"
    ligne = {"nom": app, "libelle": modele.get("libelle") or app, "badge": badge, "sites": sites,
             "categories": store.etiquettes_de("apps", app), "ouvert_sur": [pc["id"] for pc, _ in ouverts],
             "ferme_sur": [pc["id"] for pc, _ in fermes]}
    ligne.update(_raison_portee(hass, coord, pcs, ouverts, fermes, modele, fermes[0][1] if fermes else None, langue))
    return ligne


def _suit_logiciel_ouvert(store, pc, app: str, site: str) -> bool:
    """Un site qui suit son logiciel ouvert est ouvert avec lui (règle des sites liés)."""
    return store.statut(pc, "apps", app) == AUTORISE and site in store.dependances(app) and store.bloque_explicite(pc, "sites", site) in (None, "nomme") and \
        "mode" not in store.etat_element(pc, "sites", site)


def _ligne_site(hass, coord, pc, s: dict[str, Any], ferme_sur: list[dict[str, Any]], pcs, langue: str) -> dict[str, Any]:
    nom = s["nom"]
    lg = textes.ha(hass)
    ouvert_ici = s.get("ouvert", True)
    ferme = bool(ferme_sur) or not ouvert_ici
    if s.get("preuve"):
        lien = "observe"
    elif s.get("secours"):
        lien = "incertain" if str(pc.get("attribution") or "") == "degrade" else "secours"
    elif s.get("auto"):
        lien = "humain"
    else:
        lien = "connu"
    ligne: dict[str, Any] = {"domaine": nom, "badge": "rouge" if ferme and len(ferme_sur) >= len(pcs) else ("orange" if ferme else "vert"),
                             "lien": lien}
    if s.get("preuve"):
        ligne["preuve"] = dict(s["preuve"])
    if s.get("a_verifier"):
        ligne["a_verifier"] = True
    if s.get("attente"):
        ligne["attente"] = True
    if ferme:
        ligne["raison"] = s.get("raison") or ""
        ligne["portee"] = textes.t("aut.ferme_sur", lg, appareil=str(ferme_sur[0].get("name") or ferme_sur[0]["id"])) if len(ferme_sur) == 1 \
            else textes.t("aut.etat_bloque_tous", lg)
    return ligne


def _raison_portee(hass, coord, pcs, ouverts, fermes, modele, ferme_ligne, langue: str) -> dict[str, Any]:
    """La raison (appli fermée) et la portée écrite : « Bloqué pour tout le monde », « Ouvert pour Léo, fermé sur sa tablette »…"""
    lg = textes.ha(hass)
    store = coord.store
    out: dict[str, Any] = {}
    personnes = {str(pc.get("person") or "") for pc in pcs}
    une_personne = len(personnes) == 1
    qui = _prenom(hass, pcs[0]) if une_personne else ""
    if not fermes:
        out["portee"] = textes.t("aut.etat_ouvert_personne", lg, qui=qui) if une_personne and qui else textes.t("aut.etat_ouvert_tous", lg)
        return out
    out["raison"] = (ferme_ligne or {}).get("raison") or ""
    lieu = (ferme_ligne or {}).get("ferme_par") == "lieu"
    if not ouverts:
        if lieu:
            out["portee"] = textes.t("aut.etat_bloque_tous", lg)
        elif len(pcs) == 1:
            out["portee"] = textes.t("aut.etat_bloque_appareil", lg, appareil=str(pcs[0].get("name") or pcs[0]["id"]))
        else:
            out["portee"] = textes.t("aut.etat_bloque_personne", lg, qui=qui) if une_personne and qui else textes.t("aut.etat_bloque_tous", lg)
    else:
        out["portee"] = textes.t("aut.etat_partiel", lg, qui=qui or textes.t("aut.tout_le_monde", lg),
                                 appareil=str(fermes[0][0].get("name") or fermes[0][0]["id"]))
    return out


def _blocages_pour_tous(hass, coord, pcs) -> list[dict[str, Any]]:
    """Ce qui est bloqué pour tout le Lieu, avec la raison : les choix d'un Décideur (liste des bannis, avec qui et quand si
    le journal le sait) et les sites signalés dangereux vus sur les appareils (liste noire de sécurité, non levable ici)."""
    store = coord.store
    lg = textes.ha(hass)
    sortie: list[dict[str, Any]] = []
    for valeur in sorted(store.bannis, key=str.lower):
        j = journal_gestes.dernier(hass, valeur, ("bloquer",))
        ligne: dict[str, Any] = {"site": valeur, "source": "lieu", "levable": True,
                                 "raison": textes.t("aut.raison_choix", lg), "etiquette": store._est_etiquette(valeur)}
        if j:
            ligne["par"] = j["qui"]
            ligne["quand"] = j["ts"]
        sortie.append(ligne)
    vus: set[str] = set()
    for pc in pcs:
        for site in (pc.get("sites_vus") or {}):
            if site not in vus and store.est_dangereux(site) and not store.est_banni(site):
                vus.add(site)
                sortie.append({"site": site, "source": "liste_noire", "levable": False,
                               "raison": textes.t("aut.raison_liste_noire", lg)})
        if len(vus) >= 50:
            break
    return sortie


PAS_EVALUATIONS = 2   # logiciels évalués entre deux reprises de la boucle (chacun : une cinquantaine de statuts au plus)


def _preparer(hass: HomeAssistant, coord, corps: dict[str, Any]):
    """(clé du relevé, fabrique du générateur de calcul) pour une lecture de l'écran."""
    personne = str(corps.get("personne") or "").strip()
    appareil = str(corps.get("appareil") or "").strip()
    langue = textes.ha(hass)
    pcs, personne, appareil = _portee_cible(coord, personne, appareil)
    store = coord.store

    def calcul():
        """Générateur : rend la main (`yield`) tous les PAS_EVALUATIONS logiciels et tous les 100 sites, et RETOURNE le relevé."""
        lg = langue
        vues = {n for pc in pcs for a in store._siens(pc) for n in (a.get("apps_vues") or {})}
        apps = sorted({_nom_app(n) for n in vues if _nom_app(n)}, key=str.lower)[:MAX_APPLIS]
        applis = []
        for i, a in enumerate(apps):
            applis.append(_etat_logiciel(hass, coord, pcs, a, lg))
            if i % PAS_EVALUATIONS == PAS_EVALUATIONS - 1:
                yield
        ordre = {"rouge": 0, "orange": 1, "vert": 2}
        applis.sort(key=lambda a: (ordre[a["badge"]], a["libelle"].lower()))
        rattaches = {s["domaine"] for a in applis for s in a["sites"]}
        ailleurs = store.logiciel_des_sites()
        seuls = []
        for pc in pcs:
            for n_site, site in enumerate(sorted(pc.get("sites_vus") or {})):
                if n_site % 100 == 99:
                    yield
                if site in rattaches or site in ailleurs or store.est_banni(site) or any(site == x["domaine"] for x in seuls):
                    continue
                if site.count(".") > 1:
                    continue
                etats = [store.statut(p, "sites", site) == AUTORISE for p in pcs]
                badge = "vert" if all(etats) else ("rouge" if not any(etats) else "orange")
                l: dict[str, Any] = {"domaine": site, "badge": badge, "categories": store.etiquettes_de("sites", site)}
                if badge != "vert":
                    l["raison"] = (store.raison_enfant(pc, "sites", site, lg) or {}).get("texte", "") if hasattr(store, "raison_enfant") else ""
                seuls.append(l)
        seuls.sort(key=lambda s: ({"rouge": 0, "orange": 1, "vert": 2}[s["badge"]], s["domaine"]))
        categories: dict[str, dict[str, int]] = {}
        for a in applis:
            for e in a["categories"]:
                categories.setdefault(e, {"applis": 0, "sites": 0})["applis"] += 1
        for s in seuls:
            for e in s["categories"]:
                categories.setdefault(e, {"applis": 0, "sites": 0})["sites"] += 1
        resume = {b: sum(1 for a in applis if a["badge"] == b) for b in ("rouge", "orange", "vert")}
        regarde = (textes.t("aut.portee_appareil", lg, appareil=str(pcs[0].get("name") or pcs[0]["id"])) if appareil
                   else textes.t("aut.portee_personne", lg, qui=_prenom(hass, pcs[0])) if personne
                   else textes.t("aut.portee_tous", lg))
        return {
            "ok": True, "regarde": regarde, "resume": resume,
            "bloques_pour_tous": _blocages_pour_tous(hass, coord, pcs),
            "applis": applis, "sites_seuls": seuls[:MAX_SITES_SEULS],
            "categories": [{"nom": k, **v} for k, v in sorted(categories.items(), key=lambda kv: kv[0].lower())],
            "facettes": {
                "personnes": sorted(({"id": p, "nom": _prenom(hass, next(x for x in store.pcs.values() if str(x.get("person") or "") == p))}
                                     for p in {str(x.get("person") or "") for x in store.pcs.values() if x.get("person")}),
                                    key=lambda p: p["nom"].lower()),
                "appareils": sorted(({"id": x["id"], "nom": str(x.get("name") or x["id"]), "personne": str(x.get("person") or "")}
                                     for x in store.pcs.values() if x.get("enabled", True)), key=lambda a: a["nom"].lower()),
            },
            "comptes_partages": list(getattr(store, "comptes_partages", []) or []),
            "liens_auto_coupes": list(getattr(store, "liens_auto_coupes", []) or []),
            "journal": journal_gestes.recents(hass, 20),
        }

    return ("autorisations", personne, appareil, langue), calcul


def _piloter(gen):
    """Mène un générateur de calcul jusqu'au bout, d'un seul tenant (essais et chemins hors de la boucle) → son résultat."""
    try:
        while True:
            next(gen)
    except StopIteration as fin:
        return fin.value


def lire(hass: HomeAssistant, coord, corps: dict[str, Any]) -> dict[str, Any]:
    """Le relevé d'un seul tenant : pour les essais et les appelants qui ne sont pas sur la boucle. Une vue HTTP appelle `async_lire`."""
    cle, calcul = _preparer(hass, coord, corps)
    return coord.store._memo(cle, 10.0, lambda: _piloter(calcul()))


async def async_lire(hass: HomeAssistant, coord, corps: dict[str, Any]) -> dict[str, Any]:
    """GET /autorisations?personne=&appareil=  (la carte relit toutes les 30 s : le calcul est gardé 10 s). 2.5.12.25 (revue
    de HACS) : par tranches, la boucle d'événements reprend la main entre deux séries d'évaluations (jusqu'à 300 logiciels)."""
    cle, calcul = _preparer(hass, coord, corps)
    return await coord.store.async_memo(cle, 10.0, calcul)


# ----------------------------------------------------------------------------- Gestes


def _qui(request) -> tuple[str, str]:
    u = request.get("hass_user")
    return (str(getattr(u, "name", "") or getattr(u, "id", "") or "?"), "compte")


def _statut_texte(coord, pcs, genre: str, nom: str) -> str:
    etats = [coord.store.statut(pc, genre, nom) == AUTORISE for pc in pcs]
    return "ouvert" if all(etats) else ("ferme" if not any(etats) else "partiel")


def _genre_cle(coord, cible: str) -> tuple[str, str]:
    nom = str(cible or "").strip()
    if not nom:
        raise Refus(400, "cible")
    if nom.lower().startswith("app:"):
        return "apps", _nom_app(nom[4:].strip())
    genre = coord.store._genre_de(nom)
    return genre, (_nom_app(nom) if genre == "apps" else nom)


def appliquer_geste(hass: HomeAssistant, coord, corps: dict[str, Any], qui: str, par: str = "compte") -> dict[str, Any]:
    """Exécute un geste (sans droit ni code : l'appelant les a vérifiés) ; rend {ok, avant, apres, portee}."""
    store = coord.store
    action = str(corps.get("action") or "")
    if action not in ACTIONS:
        raise Refus(400, "action")
    portee = str(corps.get("portee") or "tous")
    lg = textes.ha(hass)

    if action == "compte_partage":
        uid = str(corps.get("compte") or "").strip()
        if not uid:
            raise Refus(400, "compte")
        liste = store.__dict__.setdefault("comptes_partages", [])
        partage = bool(corps.get("partage", True))
        avant = "partage" if uid in liste else "normal"
        if partage and uid not in liste:
            liste.append(uid)
        elif not partage and uid in liste:
            liste.remove(uid)
        apres = "partage" if uid in liste else "normal"
        journal_gestes.noter(hass, qui, action, uid, "compte", avant, apres, par)
        return {"ok": True, "avant": avant, "apres": apres}

    if action in ("rattacher", "retirer_lien", "lier_auto"):
        app = _nom_app(str(corps.get("app") or corps.get("cible") or "").replace("app:", "")) if action == "lier_auto" else _nom_app(str(corps.get("app") or ""))
        if not app:
            raise Refus(400, "app")
        if action == "lier_auto":
            off = store.__dict__.setdefault("liens_auto_coupes", [])
            veut = bool(corps.get("oui", True))
            avant = "oui" if app not in off else "non"
            if veut and app in off:
                off.remove(app)
            elif not veut and app not in off:
                off.append(app)
            apres = "oui" if app not in off else "non"
            journal_gestes.noter(hass, qui, action, app, portee, avant, apres, par)
            return {"ok": True, "avant": avant, "apres": apres}
        site = domaine_principal(str(corps.get("cible") or corps.get("site") or ""))
        if not site:
            raise Refus(400, "cible")
        avant = "lie" if site in store.dependances(app) else "non_lie"
        try:
            store.lier_domaine(app, site, action == "rattacher")
        except textes.Erreur as err:
            raise Refus(400, "domaine", err.texte(lg)) from None
        apres = "lie" if site in store.dependances(app) else "non_lie"
        journal_gestes.noter(hass, qui, action, f"{site} ↔ {app}", "tous", avant, apres, par)
        return {"ok": True, "avant": avant, "apres": apres}

    genre, cle = _genre_cle(coord, corps.get("cible"))
    genre_portee, pcs = analyser_portee(coord, portee)
    if not pcs:
        raise Refus(404, "appareil")
    avant = _statut_texte(coord, pcs, genre, cle)

    if action == "lever":
        if not store.est_banni(cle):
            if store.est_dangereux(cle):
                raise Refus(409, "non_levable", textes.t("aut.non_levable", lg))
            raise Refus(404, "inconnu", textes.t("aut.pas_bloque_tous", lg))
        if not corps.get("confirmer"):
            raise Refus(409, "confirmer", textes.t("aut.confirmer_lever", lg), confirmer=True)
        store.lever_bannissement(cle)
        store.oublier_maison(cle)
    elif action == "bloquer":
        if genre_portee == "tous":
            try:
                store.decider_maison(cle, True)
            except textes.Erreur as err:
                raise Refus(400, "essentiel", err.texte(lg)) from None
        else:
            for pc in pcs:
                if store.est_intouchable(cle):
                    raise Refus(400, "essentiel", textes.t("store.essentiel", lg, nom=cle))
                store.basculer(pc, cle, True)
                store.retirer_autorisation(pc, genre, cle)
    elif action == "ouvrir":
        if genre_portee == "tous":
            store.lever_bannissement(cle)
            store.decider_maison(cle, False)
        else:
            for pc in pcs:
                if store.bloque_explicite(pc, genre, cle) in ("lieu", "securite", "liste_noire"):
                    raise Refus(409, "bloque_explicite", textes.t("store.bloque_explicite", lg, nom=cle))
                store.lever_refus(pc, genre, cle)
                store.basculer(pc, cle, False)
    elif action == "laisser_passer":
        try:
            minutes = max(1, min(720, int(corps.get("duree") or 60)))
        except (TypeError, ValueError):
            minutes = 60
        for pc in pcs:
            if store.bloque_explicite(pc, genre, cle) in ("lieu", "securite", "liste_noire"):
                raise Refus(409, "bloque_explicite", textes.t("store.bloque_explicite", lg, nom=cle))
            (store.laisser_passer_app if genre == "apps" else store.laisser_passer)(pc, cle, minutes)
    # L'état d'après se lit APRÈS l'écriture (une exception se propage aux appareils de la personne pendant l'enregistrement) :
    # `finaliser` le calcule, consigne le geste et dit l'effet réel.
    return {"ok": True, "avant": avant, "_ctx": {"action": action, "genre": genre, "cle": cle, "portee": portee,
                                                 "pcs": [pc["id"] for pc in pcs], "qui": qui, "par": par}}


def finaliser(hass: HomeAssistant, coord, resultat: dict[str, Any]) -> dict[str, Any]:
    """Après l'écriture : l'état réel, l'effet sur tous les appareils des personnes touchées, et le journal « qui a fait quoi »."""
    ctx = resultat.pop("_ctx", None)
    if ctx is None:
        return resultat
    store = coord.store
    store._memos = {}
    pcs = [store.pcs[i] for i in ctx["pcs"] if i in store.pcs]
    apres = _statut_texte(coord, pcs, ctx["genre"], ctx["cle"])
    libelle = _libelle_portee(hass, coord, ctx["portee"])
    journal_gestes.noter(hass, ctx["qui"], ctx["action"], ctx["cle"], libelle, resultat["avant"], apres, ctx["par"])
    # Les exceptions et les « toujours » valent pour la personne, pas pour un seul appareil : l'effet réel est dit pour tous
    # les appareils des personnes touchées (la carte l'écrit : « aussi ouvert sur … »).
    touches = {str(pc.get("person") or "") for pc in pcs} - {""}
    ids = set(ctx["pcs"])
    effet = {p["id"]: ("ouvert" if store.statut(p, ctx["genre"], ctx["cle"]) == AUTORISE else "ferme")
             for p in store.pcs.values() if p.get("enabled", True) and (p["id"] in ids or str(p.get("person") or "") in touches)}
    resultat.update(apres=apres, portee=libelle, effet=effet)
    return resultat


def compte_marque_partage(store, utilisateur) -> bool:
    return bool(utilisateur is not None and str(getattr(utilisateur, "id", "")) in (getattr(store, "comptes_partages", None) or []))


# ----------------------------------------------------------------------------- Vues HTTP

from aiohttp import web  # noqa: E402
from homeassistant.components.http import HomeAssistantView  # noqa: E402

from .const import DOMAIN  # noqa: E402
from .entity import appliquer  # noqa: E402

URL_AUT = "/api/pc_parental/autorisations"
URL_GESTE = "/api/pc_parental/autorisations/geste"


def _coordinateur(hass: HomeAssistant):
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


def _autorise(hass: HomeAssistant, coord, request) -> bool:
    """Un administrateur HA, ou le compte d'une personne Décideur : jamais un autre compte."""
    u = request.get("hass_user")
    if u is None or not getattr(u, "is_active", True):
        return False
    if getattr(u, "is_admin", False):
        return True
    return octroi.personne_du_compte(hass, getattr(u, "id", None)) in octroi.decideurs(coord.store)


class PcParentalAutorisationsView(HomeAssistantView):
    """La lecture de l'écran Autorisations pour la carte (personne ou appareil regardé)."""

    url = URL_AUT
    name = "api:pc_parental:autorisations"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.Response:
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        if not _autorise(self.hass, coord, request):
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        try:
            return self.json(await async_lire(self.hass, coord, {k: request.query.get(k, "") for k in ("personne", "appareil")}))
        except Refus as r:
            return self.json({"ok": False, "error": r.code}, status_code=r.statut)


class PcParentalAutorisationsGesteView(HomeAssistantView):
    """Un geste d'autorisation, signé du compte Home Assistant ; le code n'est demandé qu'à un compte marqué partagé."""

    url = URL_GESTE
    name = "api:pc_parental:autorisations_geste"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        if not _autorise(self.hass, coord, request):
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        action = str(corps.get("action") or "")
        if action not in ACTIONS:
            return self.json({"ok": False, "error": "action"}, status_code=400)
        if action == "compte_partage" and not getattr(request.get("hass_user"), "is_admin", False):
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        # Le code : seulement pour ce qui ouvre, et seulement pour un compte marqué partagé (ou non reconnaissable).
        accorde = action in ACCORDENT or (action == "compte_partage" and not corps.get("partage", True))
        refus = await octroi.exiger(self.hass, coord, request, corps, accorde)
        if refus is not None:
            return self.json(refus, status_code=403)
        qui, par = _qui(request)
        resultat: dict[str, Any] = {}
        try:
            await appliquer(coord, lambda: resultat.update(appliquer_geste(self.hass, coord, corps, qui, par)))
            finaliser(self.hass, coord, resultat)
        except Refus as r:
            return self.json({"ok": False, "error": r.code, "retour": r.message, **r.extra}, status_code=r.statut)
        except textes.Erreur as err:
            return self.json({"ok": False, "error": "refus", "retour": err.texte(textes.ha(self.hass))}, status_code=400)
        return self.json(resultat)


def async_register(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalAutorisationsView(hass))
    hass.http.register_view(PcParentalAutorisationsGesteView(hass))
