"""Espace parents de Vision : l'application, et le tableau de bord.

Un appareil portant l'étiquette « Parents » voit, depuis l'application,
l'état de tous les appareils de la maison et agit dessus : ouvrir un moment,
fermer, lever une dérogation, envoyer un mot. Même règle d'accès que la
réponse aux demandes de temps : le secret de l'appareil, et l'étiquette.

Le tableau de bord Home Assistant lit et agit par la même porte, mais avec
le compte Home Assistant d'un administrateur : ce que le téléphone d'un
parent montre, le tableau le montre aussi, et avec les mêmes gestes.
"""
from __future__ import annotations

import logging
import time
from typing import Any

from aiohttp import web
from homeassistant.components import persistent_notification
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import demandes, labels, lieu, matrice, modules, pause_generale
from . import roles, textes
from .const import DOMAIN, MODE_LOCKED, MODE_OPEN
from .coordinator import PcParentalCoordinator

_LOGGER = logging.getLogger(__name__)
from .planner import next_lock

URL_PARENT = "/api/pc_parental/parent"
URL_MAISON = "/api/pc_parental/maison"
EN_LIGNE_S = 120


def _hm(secondes: Any) -> str:
    s = int(secondes or 0)
    s = s - 86400 * (s // 86400)
    h, reste = divmod(s, 3600)
    return "{:02d}:{:02d}".format(h, reste // 60)


def _prenom(hass: HomeAssistant, pc: dict[str, Any]) -> str:
    etat = hass.states.get(str(pc.get("person") or ""))
    if etat is not None and etat.name:
        return str(etat.name)
    return ""


def _libelles(pc: dict[str, Any], genre: str, noms: list[str]) -> list[dict[str, str]]:
    """Noms lisibles pour ce que l'agent bloque : « Roblox » plutôt qu'un paquet."""
    vus = pc.get("apps_vues" if genre == "apps" else "sites_vus") or {}
    sortie = []
    deja = set()
    for nom in noms:
        fiche = vus.get(nom) if isinstance(vus, dict) else None
        lib = str((fiche or {}).get("libelle") or "") if isinstance(fiche, dict) else ""
        # Les membres d'une famille (cdn, sous-domaines) n'apportent rien au parent.
        if genre == "sites" and nom.count(".") > 1 and not lib:
            continue
        cle = (lib or nom).lower()
        if cle in deja:
            continue
        deja.add(cle)
        sortie.append({"nom": nom, "libelle": lib or nom})
    sortie.sort(key=lambda x: x["libelle"].lower())
    return sortie


def _plages(pc: dict[str, Any], maintenant_s: int, jour: int) -> list[dict[str, Any]]:
    sortie = []
    for regle in pc.get("rules") or []:
        if not isinstance(regle, dict):
            continue
        jours = list(regle.get("weekdays") or [True] * 7)
        debut = int(regle.get("start") or 0)
        fin = int(regle.get("end") or 0)
        aujourd_hui = bool(jours[jour]) if jour < len(jours) else True
        if debut <= fin:
            actif = aujourd_hui and debut <= maintenant_s < fin
        else:
            # Plage de nuit : commence la veille au soir, finit ce matin.
            veille = jours[(jour - 1) if jour else 6] if len(jours) == 7 else True
            actif = (aujourd_hui and maintenant_s >= debut) or (bool(veille) and maintenant_s < fin)
        sortie.append({
            "id": regle.get("id"),
            "epargne": str(pc.get("id") or "") in (regle.get("sauf") or []),
            "nom": str(regle.get("name") or ""),
            "debut": _hm(debut),
            "fin": _hm(fin),
            "jours": [bool(j) for j in jours],
            "aujourdhui": aujourd_hui,
            "actif": bool(regle.get("enabled", True)) and actif and str(pc.get("id") or "") not in (regle.get("sauf") or []),
            "active": bool(regle.get("enabled", True)),
            "portee": str(regle.get("portee") or "session"),
            "etiquettes": list(regle.get("etiquettes") or []),
        })
    return sortie


ROLES = ("parents", "enfants", "affichage")


def _categories(hass: HomeAssistant, coord: PcParentalCoordinator, langue: str | None = None) -> list[dict[str, Any]]:
    """Les étiquettes qui classent des applis et des sites, avec leur contenu. « nom » reste la donnée (en
    français chez qui l'a écrite ainsi) ; « libelle » est ce qu'on affiche, dans la langue du lecteur."""
    store = coord.store
    contenu: dict[str, dict[str, list]] = {}
    libelles: dict[str, str] = {}
    for pc in store.pcs.values():
        for nom, fiche in (pc.get("apps_vues") or {}).items():
            if isinstance(fiche, dict) and fiche.get("libelle"):
                libelles.setdefault(nom, str(fiche["libelle"]))
    for genre in ("apps", "sites"):
        for nom, ets in (store.etiquettes.get(genre) or {}).items():
            for e in ets or []:
                bloc = contenu.setdefault(str(e), {"apps": [], "sites": []})
                bloc[genre].append(nom)
    noms = set(contenu)
    noms.update(e for e in labels.proposees(hass, store) if e)
    noms.update(e for e in store.bannis if store._est_etiquette(e))
    sortie = []
    for nom in sorted(noms, key=lambda s: s.lower()):
        if nom.lower() in ROLES:
            continue
        bloc = contenu.get(nom, {"apps": [], "sites": []})
        apps = sorted(({"nom": a, "libelle": libelles.get(a, a)} for a in bloc["apps"]), key=lambda x: x["libelle"].lower())
        sites = sorted(s for s in bloc["sites"] if s.count(".") == 1) or sorted(bloc["sites"])
        sortie.append({
            "nom": nom,
            "libelle": nom if langue == "fr" else textes.categorie(nom, langue),
            "maison": store.est_banni(nom),
            "securite": nom.lower() in ("dangereux", "contournement", "pub & traçage", "pub & tracage"),
            "age": nom.lower() in ("pegi 12", "pegi 16", "pegi 18", "adulte"),
            "apps": apps,
            "sites": sites,
        })
    return sortie


def _etat_categorie(store, pc: dict[str, Any], c: dict[str, Any], langue: str | None = None) -> dict[str, Any]:
    """Ce que vaut une catégorie pour cet appareil : le choix du parent, et
    ce qui est réellement appliqué (la maison, le planning ou une
    automatisation peuvent la couper par-dessus)."""
    nom = c["nom"]
    bas = nom.lower()
    bloques = {str(b).lower() for b in (pc.get("bloques") or [])}
    autorises = {str(b).lower() for b in (pc.get("autorises") or [])}
    choix = "bloquer" if bas in bloques else ("autoriser" if bas in autorises else "neutre")
    code, texte = "", ""
    if c.get("maison"):
        code = "securite" if c.get("securite") else "maison"
        texte = textes.t("commun.interdit_tous", langue)
    else:
        try:
            fermeture = store.fermeture_de(pc, [nom])
            if fermeture is not None:
                code = "automatisation"
                texte = str(fermeture.get("raison") or "") or textes.t("parent.ferme_automatisation", langue)
            else:
                regle = store.plage_ferme(pc, nom)
                if regle:
                    code, texte = "plage", textes.t("parent.coupe_planning", langue,
                                                    plage=regle.get("name") or textes.t("commun.plage", langue))
        except Exception:  # noqa: BLE001
            pass
    if not code and choix == "bloquer":
        code = "age" if c.get("age") else "regle"
        texte = textes.t("parent.ferme_vision", langue)
    return {"choix": choix, "coupe": bool(code), "code": code, "raison": texte, "verrou": code in ("securite", "maison", "plage", "automatisation")}


def _etats_etiquettes(pc: dict[str, Any], categories: list[dict[str, Any]], store=None,
                      langue: str | None = None) -> dict[str, Any]:
    etats = {}
    for c in categories:
        if store is None:
            bloques = {str(b).lower() for b in (pc.get("bloques") or [])}
            etats[c["nom"]] = "bloquer" if c["nom"].lower() in bloques else "neutre"
        else:
            etats[c["nom"]] = _etat_categorie(store, pc, c, langue)
    return etats


def _appareils_personne(store, pc: dict[str, Any]) -> list[dict[str, Any]]:
    """Cet appareil et ceux de la même personne."""
    personne = str(pc.get("person") or "")
    return [a for a in store.pcs.values() if a is pc or (personne and str(a.get("person") or "") == personne)]


def _toujours_personne(store, pc: dict[str, Any]) -> list[str]:
    """Les applis que cette personne garde ouvertes même appareil fermé (tous ses appareils confondus)."""
    vus: dict[str, str] = {}
    for a in _appareils_personne(store, pc):
        for nom in a.get("toujours") or []:
            nom = str(nom).strip()
            if nom:
                vus.setdefault(nom.lower(), nom)
    return list(vus.values())


def _exceptions(store, pc: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
    """Ce qui est décidé nom par nom pour cette personne : toujours ouvert, toujours fermé.

    Les étiquettes (catégories) ont leur propre écran ; ici ne restent que les applis et les sites.
    """
    # Une appli se reconnaît sous son nom brut ou raccourci (« com.snapchat.android », « snapchat.android ») ;
    # son libellé (« Snapchat ») vient de n'importe quel appareil qui l'a déjà vue.
    applis: dict[str, str] = {}
    for n in (store.etiquettes.get("apps") or {}):
        for cle_app in (str(n).lower(), str(demandes._nom_app(str(n)) or "").lower()):
            if cle_app:
                applis.setdefault(cle_app, "")
    for autre in [pc, *store.pcs.values()]:
        for n, f in (autre.get("apps_vues") or {}).items():
            lib_app = str(f.get("libelle") or "") if isinstance(f, dict) else ""
            for cle_app in (str(n).lower(), str(demandes._nom_app(str(n)) or "").lower()):
                if cle_app and not applis.get(cle_app):
                    applis[cle_app] = lib_app
    sites = {str(n).lower() for n in (store.etiquettes.get("sites") or {})}
    sortie: dict[str, list[dict[str, Any]]] = {"autorises": [], "bloques": [], "toujours": []}
    # Toujours disponibles, même appareil fermé : celles de toute la maison (non modifiables ici), puis celles de la personne.
    deja: set[str] = set()
    try:
        maison_tj = list(store.noms_par_etiquette("apps", "Toujours autorisé"))
    except Exception:  # noqa: BLE001
        maison_tj = []
    for nom_tj in maison_tj:
        if str(nom_tj).lower() not in deja:
            deja.add(str(nom_tj).lower())
            sortie["toujours"].append({"nom": str(nom_tj), "libelle": applis.get(str(nom_tj).lower()) or str(nom_tj), "genre": "apps", "maison": True})
    for nom_tj in _toujours_personne(store, pc):
        if nom_tj.lower() not in deja:
            deja.add(nom_tj.lower())
            sortie["toujours"].append({"nom": nom_tj, "libelle": applis.get(nom_tj.lower()) or nom_tj, "genre": "apps", "maison": False})
    sortie["toujours"].sort(key=lambda x: x["libelle"].lower())
    for champ in ("autorises", "bloques"):
        for valeur in pc.get(champ) or []:
            valeur = str(valeur).strip()
            if not valeur or store._est_etiquette(valeur):
                continue
            bas = valeur.lower()
            if bas in applis:
                genre = "apps"
            elif bas in sites:
                genre = "sites"
            else:
                genre = "sites" if "." in bas and " " not in bas and not bas.endswith(".exe") and bas.count(".") <= 2 else "apps"
            libelle = applis.get(bas, "") if genre == "apps" else ""
            sortie[champ].append({"nom": valeur, "libelle": libelle or valeur, "genre": genre, "maison": bool(store.est_banni(valeur))})
        sortie[champ].sort(key=lambda x: x["libelle"].lower())
    return sortie


def _temps(pc: dict[str, Any]) -> dict[str, Any]:
    """Le temps passé : sept derniers jours (par heure et par appli), et le cumul par appli depuis le début."""
    vues = pc.get("apps_vues") or {}

    def libelle(nom: str) -> str:
        fiche = vues.get(nom)
        return (str(fiche.get("libelle") or "") if isinstance(fiche, dict) else "") or nom

    usage = pc.get("usage") or {}
    jours = []
    for jour in sorted(usage)[-7:]:
        j = usage.get(jour) or {}
        heures = j.get("heures") or {}
        applis = sorted(((str(n), int(s or 0)) for n, s in (j.get("apps") or {}).items()), key=lambda x: -x[1])[:12]
        jours.append({
            "jour": jour,
            "actif": int(j.get("actif", 0)),
            "ouvert": int(j.get("ouvert", 0)),
            "heures": [int(heures.get("%02d" % h, 0)) for h in range(24)],
            "apps": [{"nom": n, "libelle": libelle(n), "secondes": s} for n, s in applis if s >= 30],
        })
    total = sorted(
        ((str(n), int(f.get("secondes") or 0), int(float(f.get("premier") or 0))) for n, f in vues.items() if isinstance(f, dict)),
        key=lambda x: -x[1],
    )[:15]
    return {"jours": jours, "total": [{"nom": n, "libelle": libelle(n), "secondes": s, "depuis": p} for n, s, p in total if s >= 60]}


def _fiche(hass: HomeAssistant, coord: PcParentalCoordinator, pc: dict[str, Any], parents: list[str]) -> dict[str, Any]:
    etat = coord.store.evaluate(pc)
    maintenant = coord.local_now()
    local = maintenant.replace(tzinfo=None)
    agent = pc.get("agent") or {}
    bloque = coord.store.effectif(pc)
    vu = float(pc.get("last_seen") or 0)
    suivant = etat.get("next_change")
    prochain_verrou = None if etat["locked"] else next_lock(coord.store.regles_pour(pc), local)
    override = etat.get("override") or None
    usage = coord.store.usage_du_jour(pc)
    focus = str(agent.get("focus") or "")
    vues = pc.get("apps_vues") or {}
    fiche_focus = vues.get(focus) if focus else None
    if focus and fiche_focus is None:
        # L'agent dit « robloxplayerbeta », le catalogue connaît « RobloxPlayerBeta ».
        bas = focus.lower().removesuffix(".exe")
        fiche_focus = next((f for n, f in vues.items() if str(n).lower().removesuffix(".exe") == bas), None)
    etiquettes = [e for e in labels.noms_du_pc(hass, pc["id"]) if e.lower() != "parents"]
    fiche = {
        "id": pc["id"],
        "nom": str(pc.get("name") or pc["id"]),
        "prenom": _prenom(hass, pc),
        "personne": str(pc.get("person") or ""),
        "proprietaires": coord.store.proprietaires(pc),
        "partage_tous": coord.store.partage_tous(pc),
        "android": pc.get("platform") == "android",
        # 1.86.6 : pc, telephone, tablette, tv ou android (type inconnu) ; la carte Maison en tire l'icône.
        "type": matrice.type_appareil(pc),
        # 1.97.6 (point 7) : la version de l'appli, pour dire quand un appareil est trop ancien pour remonter son accueil.
        "version_appli": str(agent.get("version") or "") if pc.get("platform") == "android" else "",
        # 1.92.0 (point 7) : les protections que l'appli Android dit en place (accessibilité, accueil…).
        "protections": {str(k): bool(v) for k, v in dict(agent.get("protections") or {}).items()} if pc.get("platform") == "android" else {},
        "parent": pc["id"] in parents,
        "en_ligne": vu > 0 and time.time() - vu < EN_LIGNE_S,
        "vu": int(vu),
        "verrouille": bool(etat["locked"]),
        "source": str(etat.get("source") or ""),
        "planning_ferme": etat.get("planning") == MODE_LOCKED,
        "derogation": {
            "mode": override.get("mode"),
            "fin": int(float(override.get("until") or 0)),
        } if override else None,
        "prochain": suivant.isoformat() if suivant else "",
        "prochain_verrou": prochain_verrou.isoformat() if prochain_verrou else "",
        "focus": focus,
        "focus_libelle": str((fiche_focus or {}).get("libelle") or "") if isinstance(fiche_focus, dict) else "",
        "inactif_s": int(agent.get("idle_seconds") or 0),
        "usage": {"actif": int(usage.get("actif", 0)) // 60, "ouvert": int(usage.get("ouvert", 0)) // 60},
        "temps": _temps(pc),
        "etiquettes": etiquettes,
        "apps": _libelles(pc, "apps", list(bloque.get("apps") or [])),
        "sites": _libelles(pc, "sites", list(bloque.get("sites") or [])),
        "motifs": list(bloque.get("motifs") or []),
        "raisons": dict(bloque.get("raisons") or {}),
        "plages": _plages(pc, sum([local.hour * 3600, local.minute * 60]), local.weekday()),
        "demande": pc.get("demande") or None,
        "demandes": demandes.en_attente(pc),
        "refus": coord.store.refus_en_cours(pc),
        "limites": _limites(coord.store, pc),
    }
    # 1.82.0 : ce qui relève d'un module coupé n'est plus montré (et reste enregistré pour son retour).
    store = coord.store
    if modules.coupe(store, "plages"):
        fiche.update(plages=[], planning_ferme=False, prochain_verrou="")
    if modules.coupe(store, "temps"):
        fiche.update(limites=[], usage={"actif": 0, "ouvert": 0}, temps={"jours": [], "total": []})
    if modules.coupe(store, "demandes"):
        fiche.update(demande=None, demandes=[], refus=[])
    # 1.88.0 : le mode concentration ; ce qu'il ferme ne s'ajoute pas à la liste des applis fermées une par une.
    if modules.actif(store, "concentration") and not modules.coupe(store, "blocage"):
        from . import concentration

        fiche["concentration"] = concentration.fiche(pc, store)
        conc_idx = next((i for i, m in enumerate(fiche["motifs"]) if str(m).startswith(concentration.CODE + "|")), None)
        if conc_idx is not None:
            seules = {n for n, i in fiche["raisons"].items() if i == conc_idx}
            fiche["apps"] = [a for a in fiche["apps"] if str(a.get("nom") if isinstance(a, dict) else a) not in seules]
    return fiche


def _limites(store, pc: dict[str, Any]) -> list[dict[str, Any]]:
    """Les limites de temps par logiciel, avec ce qui est déjà passé aujourd'hui."""
    vues = pc.get("apps_vues") or {}
    sortie = []
    for nom, minutes in sorted(store.limites(pc).items()):
        fiche = vues.get(nom)
        sortie.append({
            "nom": nom,
            "libelle": (str(fiche.get("libelle") or "") if isinstance(fiche, dict) else "") or nom,
            "minutes": int(minutes),
            "passe": int(store.temps_du_jour(pc, nom)),
        })
    return sortie


def _secondes(texte: Any) -> int:
    """« 18:30 », « 18h30 » ou « 18 » → secondes depuis minuit."""
    brut = str(texte or "").strip().lower().replace("h", ":").replace(".", ":")
    if not brut:
        raise textes.Erreur("parent.heure_manquante")
    h, _, m = brut.partition(":")
    try:
        heures, minutes = int(h), int(m or 0)
    except ValueError as err:
        raise textes.Erreur("parent.heure_illisible") from err
    if not (0 <= heures <= 24 and 0 <= minutes < 60) or (heures == 24 and minutes):
        raise textes.Erreur("parent.heure_illisible")
    return (heures % 24) * 3600 + minutes * 60


def _num_regle(corps: dict[str, Any]) -> int:
    """Le numéro de la plage visée : « regle » (1.74.0). Jamais « id », qui est l'identifiant de l'appareil appelant."""
    try:
        return int(corps.get("regle"))
    except (TypeError, ValueError):
        return 0


def _plage(hass: HomeAssistant, coord: PcParentalCoordinator, cible: dict[str, Any], corps: dict[str, Any], qui: str,
           langue: str | None = None) -> str:
    """Créer, modifier ou supprimer une plage du planning, depuis l'application ou l'agent."""
    op = str(corps.get("op") or "")
    magasin = coord.store
    if op == "supprimer":
        regle = magasin.rule(cible["id"], _num_regle(corps))
        if regle is None:
            raise textes.Erreur("commun.plage_disparue")
        magasin.remove_rule(cible["id"], regle["id"])
        return textes.t("parent.plage_supprimee", langue, qui=qui, plage=regle.get("name") or "")
    champs: dict[str, Any] = {}
    if "nom" in corps:
        champs["name"] = str(corps.get("nom") or "").strip()[:60]
    if "debut" in corps:
        champs["start"] = _secondes(corps.get("debut"))
    if "fin" in corps:
        champs["end"] = _secondes(corps.get("fin"))
    if isinstance(corps.get("jours"), list):
        jours = [bool(j) for j in corps["jours"]][:7]
        jours += [False] * (7 - len(jours))
        if not any(jours):
            raise textes.Erreur("parent.un_jour")
        champs["weekdays"] = jours
    if "active" in corps:
        champs["enabled"] = bool(corps.get("active"))
    if "portee" in corps:
        portee = str(corps.get("portee") or "session")
        if portee not in ("session", "etiquettes"):
            raise textes.Erreur("parent.portee_inconnue")
        champs["portee"] = portee
    if isinstance(corps.get("etiquettes"), list):
        # Plus de tri silencieux : le magasin vérifie, et dit où cocher ce qu'il refuse.
        champs["etiquettes"] = [str(e).strip() for e in corps["etiquettes"] if str(e).strip()]
    if isinstance(corps.get("sauf"), list):
        siens = {a["id"] for a in magasin._siens(cible)}
        champs["sauf"] = [str(i) for i in corps["sauf"] if str(i) in siens]
    if op == "creer":
        for requis in ("name", "start", "end"):
            if requis not in champs:
                raise textes.Erreur("parent.plage_incomplete")
        nouvelle = magasin.add_rule(
            cible["id"], champs["name"], champs["start"], champs["end"],
            champs.get("weekdays") or [True] * 7, champs.get("enabled", True),
            portee=champs.get("portee", "session"), etiquettes=champs.get("etiquettes"),
        )
        if champs.get("sauf"):
            magasin.update_rule(cible["id"], nouvelle["id"], sauf=champs["sauf"])
        return textes.t("parent.plage_ajoutee", langue, qui=qui, plage=nouvelle.get("name") or "")
    if op == "modifier":
        if not champs:
            raise textes.Erreur("parent.rien_a_changer")
        regle = magasin.update_rule(cible["id"], _num_regle(corps), **champs)
        return textes.t("parent.plage_modifiee", langue, qui=qui, plage=regle.get("name") or "")
    raise textes.Erreur("parent.geste_planning")


def temps_personne(store, personne: str) -> dict[str, Any]:
    """Le temps d'écran du jour, cumulé sur les appareils propres à la personne (profils, 1.73.0).

    Les appareils partagés n'y comptent pas : on ne sait pas qui était devant.
    """
    appareils = []
    for pc in store.pcs.values():
        if str(pc.get("person") or "") != personne or not pc.get("enabled", True) or store.partage_tous(pc):
            continue
        appareils.append({"id": pc["id"], "nom": str(pc.get("name") or pc["id"]),
                          "actif": int(store.usage_du_jour(pc).get("actif", 0)) // 60})
    appareils.sort(key=lambda a: -a["actif"])
    return {"actif": sum(a["actif"] for a in appareils), "appareils": appareils}


def _personnes(hass: HomeAssistant, coord: PcParentalCoordinator, parents: list[str] | None = None) -> list[dict[str, Any]]:
    """Les actifs de la maison : toute personne à qui un appareil appartient, seule ou en partage.

    « appareils » donne ses appareils (les siens d'abord, puis les partagés) ;
    « principal » celui qui porte ses règles, pour les gestes qui valent pour la personne.
    """
    store = coord.store
    parents = parents or []
    sortie = []
    vues = set()
    for pc in store.pcs.values():
        for personne in store.proprietaires(pc):
            if personne in vues:
                continue
            vues.add(personne)
            cle = personne.split(".")[-1]
            etat = hass.states.get(personne)
            siens = [a for a in store.appareils_de(personne) if a.get("enabled", True)]
            propres = [a for a in siens if str(a.get("person") or "") == personne]
            sortie.append({
                "entite": personne,
                "cle": cle,
                "prenom": str(etat.name) if etat is not None and etat.name else cle,
                "photo": str((etat.attributes or {}).get("entity_picture") or "") if etat is not None else "",
                "appareils": [a["id"] for a in siens],
                "principal": propres[0]["id"] if propres else "",
                "parent": any(a["id"] in parents for a in propres),
                "temps": temps_personne(store, personne) if not modules.coupe(store, "temps")
                else {"actif": 0, "appareils": []},
                # Accordéons de l'appli (1.75.0) : le rôle de la personne (celui de son appareil principal).
                "role": roles.nom(store, propres[0]) if propres else "",
            })
    # Les enfants d'abord : c'est pour eux qu'on ouvre la page.
    sortie.sort(key=lambda p: (p["parent"], p["prenom"].lower()))
    return sortie


def etat_maison(hass: HomeAssistant, coord: PcParentalCoordinator, moi_id: str | None = None,
                langue: str | None = None) -> dict[str, Any]:
    """Tout ce qu'un parent voit : appareils, demandes, catégories, personnes.

    Les textes sont dans `langue` ; sans elle, celle de l'appareil `moi_id`, sinon celle de Home Assistant."""
    if langue is None:
        langue = textes.pour(hass, coord.store.pcs.get(str(moi_id or ""))) if moi_id else textes.ha(hass)
    parents = roles.avec(coord.store, "gerer")
    # 1.82.0 : sans blocage ni filtrage, plus de catégories à régler ; sans optimisations, plus de logiciels.
    categories = [] if modules.coupe(coord.store, "blocage", "filtrage") else _categories(hass, coord, langue)
    gestion = None if modules.coupe(coord.store, "optimisations") else getattr(coord, "processus", None)
    appareils = []
    for pc in coord.store.pcs.values():
        if not pc.get("enabled", True) and pc["id"] != moi_id:
            continue
        try:
            fiche = _fiche(hass, coord, pc, parents)
            fiche["etiquettes_etat"] = _etats_etiquettes(pc, categories, coord.store, langue)
            try:
                fiche["exceptions"] = _exceptions(coord.store, pc)
                if modules.coupe(coord.store, "blocage", "filtrage"):
                    fiche["exceptions"] = {"autorises": [], "bloques": [], "toujours": fiche["exceptions"]["toujours"]}
            except Exception:  # noqa: BLE001 - la fiche s'affiche même sans ses exceptions
                fiche["exceptions"] = {"autorises": [], "bloques": []}
            if gestion is not None and pc.get("platform") != "android":
                try:
                    fiche["logiciels"] = gestion.catalogue.logiciels_de(pc["id"])
                except Exception:  # noqa: BLE001 - la fiche s'affiche même sans ses logiciels
                    fiche["logiciels"] = []
            appareils.append(fiche)
        except Exception:  # noqa: BLE001 - une fiche cassée ne cache pas les autres
            continue
    appareils.sort(key=lambda a: (a["parent"], not a["en_ligne"], (a["prenom"] or a["nom"]).lower()))
    # 1.97.8 : une seule source pour toutes les demandes (accès, temps, déverrouillage).
    attente = demandes_attente(hass, coord, langue)
    return {
        "ok": True,
        "lieu": lieu.nom(coord.store),
        # 2.4.2 : un PC Windows (maison.json de l'agent) ne reçoit jamais la veille (retirée des PC).
        "modules": __import__(__package__ + ".veille_pc", fromlist=["x"]).modules_pour(
            coord.store.pcs.get(str(moi_id or "")) or {"platform": "android"}, modules.public(coord.store)),
        # Pause générale (point 14.2) : {"fin": horodatage} pendant une pause, sinon null.
        "pause_generale": pause_generale.public(coord.store),
        "appareils": appareils,
        "demandes": attente,
        "categories": categories,
        "personnes": _personnes(hass, coord, parents),
        "desinstallation": bool(gestion is not None and gestion.desinstallation_permise),
        "maison": [
            {"entite": p, "prenom": str(e.name) if (e := hass.states.get(p)) is not None and e.name else p.split(".")[-1]}
            for p in coord.store.personnes_maison()
        ],
    }


class _Refus(Exception):
    """Une action refusée, avec le code HTTP et le message à renvoyer."""

    def __init__(self, status: int, erreur: str, retour: str = "") -> None:
        super().__init__(erreur)
        self.status = status
        self.erreur = erreur
        self.retour = retour


async def agir(
    hass: HomeAssistant,
    coord: PcParentalCoordinator,
    cible: dict[str, Any],
    corps: dict[str, Any],
    qui: str,
    moi_id: str | None = None,
    langue: str | None = None,
) -> str:
    """Un geste de parent sur un appareil. Renvoie la phrase à afficher.

    Même code pour l'application et pour le tableau de bord : un parent qui
    ouvre trente minutes depuis son téléphone ou depuis Home Assistant fait
    exactement la même chose, et l'enfant reçoit le même mot.

    La phrase rendue est dans `langue` (celle de qui agit ; sans elle, celle de Home Assistant) ; le mot laissé
    à l'appareil visé est dans la sienne.
    """
    langue = langue or textes.ha(hass)
    lc = textes.pour(hass, cible)
    action = str(corps.get("action") or "")
    # 1.82.0 : un geste dont le module est coupé est refusé (403 « module ») ; rien ne change.
    if (coupe := modules.geste_coupe(coord.store, action, corps)) is not None:
        raise _Refus(403, "module", textes.t("commun.module_coupe", langue, module=modules.nom(coupe, langue)))
    # Les avis « Vision » (ouvert, fermé, temps ajouté…) sont des messages : pas sans le module « Messages ».
    avis = not modules.coupe(coord.store, "messages")
    try:
        minutes = max(0, min(720, int(corps.get("minutes") or 0)))
    except (TypeError, ValueError):
        minutes = 0
    # La phrase du geste, dite à qui agit (retour) et, selon le geste, à l'appareil visé (dans sa langue).
    dit = textes.Phrase()

    def heure_de(ts: float):
        return lambda lg: textes.heure(ts, lg)

    if action == "ouvrir":
        coord.store.set_override(cible, MODE_OPEN, minutes)
        if minutes:
            try:
                from . import bilan as _bilan

                _bilan.compter(coord.store, str(cible.get("person") or ""), "minutes", minutes)
            except Exception:  # noqa: BLE001
                pass
        dit("parent.ouvert_pour" if minutes else "parent.ouvert", qui=qui, minutes=minutes)
    elif action == "fermer":
        coord.store.set_override(cible, MODE_LOCKED, minutes)
        dit("parent.ferme_pour" if minutes else "parent.ferme", qui=qui, minutes=minutes)
    elif action == "annuler":
        coord.store.clear_override(cible)
        dit("parent.retour_planning")
    elif action == "acces" and (d_dev := _deverrouillage_de(hass, cible, str(corps.get("demande") or ""))) is not None:
        # 1.97.8 : une demande de déverrouillage réglée depuis la liste (carte Maison, appli, widget de l'accueil).
        from . import deverrouillage

        refus_dev = str(corps.get("decision") or "non") == "non"
        votant = coord.store.pcs.get(moi_id) if moi_id else None
        try:
            r = deverrouillage.decider(hass, coord, d_dev["id"], "refuser" if refus_dev else "approuver", None, "directe", qui,
                                       voix=roles.votant(votant) if votant else None)
        except deverrouillage.Refus:
            return textes.t("parent.deja_traitee", langue)
        nom_d = str(cible.get("name") or cible["id"])
        quoi_d = lambda lg: deverrouillage.libelle(d_dev["action"], lg)  # noqa: E731
        if refus_dev:
            dit("parent.dev_refuse", qui=qui, quoi=quoi_d, nom=nom_d)
        elif r.get("etat") == "partielle":
            dit("parent.dev_partiel", qui=qui, _n=max(1, int(r.get("requis", 2)) - int(r.get("voix", 1))))
        else:
            dit("parent.dev_accorde", qui=qui, quoi=quoi_d, nom=nom_d, minutes=int(d_dev["minutes"]))
    elif action == "acces" and (t_dem := temps_en_attente(cible)) and t_dem["id"] == str(corps.get("demande") or ""):
        # 1.97.2 : réponse à une demande de temps depuis la liste des demandes (carte Maison, appli).
        cible.pop("demande", None)
        nom_t = str(cible.get("name") or cible["id"])
        if str(corps.get("decision") or "non") == "non":
            dit("parent.temps_refuse_sur", qui=qui, minutes=t_dem["minutes"], nom=nom_t)
            texte_t = textes.Phrase("parent.temps_refuse_toi", qui=qui, minutes=t_dem["minutes"])
            compte_t = "refusee"
        else:
            m_t = int(t_dem["minutes"])
            fin_t = coord.store.ajouter_temps(cible, m_t)
            dit("parent.temps_ajoute_sur", qui=qui, minutes=m_t, nom=nom_t, heure=heure_de(fin_t))
            texte_t = textes.Phrase("parent.temps_ajoute_toi", qui=qui, minutes=m_t, heure=heure_de(fin_t))
            compte_t = "accordee"
        try:
            from . import bilan as _bilan

            _bilan.compter(coord.store, str(cible.get("person") or ""), compte_t)
        except Exception:  # noqa: BLE001 - un compteur de bilan ne bloque jamais un geste
            pass
        if cible["id"] != moi_id and avis:
            try:
                coord.store.add_message(cible["id"], texte_t.en(lc), titre="Vision")
            except ValueError:
                pass
    elif action == "acces":
        d = demandes.retirer(cible, str(corps.get("demande") or ""))
        if d is None:
            # 1.97.3 : déjà réglée ailleurs (autre décideur, autre écran) ou expirée : on le dit, l'affichage suit.
            return textes.t("parent.deja_traitee", langue)
        decision = str(corps.get("decision") or "non")
        # 1.90.0 : le bilan de la semaine compte les accès accordés et refusés.
        try:
            from . import bilan as _bilan

            _bilan.compter(coord.store, str(cible.get("person") or ""), "refusee" if decision == "non" else "accordee")
        except Exception:  # noqa: BLE001 - un compteur de bilan ne bloque jamais un geste
            pass
        joli = d.get("libelle") or d.get("cle")
        if decision == "toujours":
            if coord.store.est_banni(d["cle"]):
                dit("parent.impossible_banni", nom=joli)
            else:
                coord.store.basculer(cible, d["cle"], False)
                dit("parent.autorise", qui=qui, nom=joli)
        elif decision == "exception":
            # Exception : autorisé, et disponible même pendant les plages de coupure (une appli seulement).
            if coord.store.est_banni(d["cle"]):
                dit("parent.impossible_banni", nom=joli)
            else:
                coord.store.basculer(cible, d["cle"], False)
                if d.get("genre") == "apps":
                    nom_tj = str(d.get("nom") or d["cle"])
                    if nom_tj.lower() not in {n.lower() for n in _toujours_personne(coord.store, cible)}:
                        cible.setdefault("toujours", []).append(nom_tj)
                    dit("parent.autorise_coupures", qui=qui, nom=joli)
                else:
                    dit("parent.autorise_site_coupures", qui=qui, nom=joli)
        elif decision == "categorie":
            # Toute la catégorie : les étiquettes qui classent ce nom passent en « autorisé » pour l'enfant.
            ets = [e for e in (d.get("etiquettes") or coord.store.etiquettes_de(d.get("genre") or "sites", d["cle"])) if not coord.store.est_banni(e)]
            if not ets:
                if coord.store.est_banni(d["cle"]):
                    dit("parent.impossible_banni", nom=joli)
                else:
                    coord.store.basculer(cible, d["cle"], False)
                    dit("parent.autorise_sans_categorie", qui=qui, nom=joli)
            else:
                for e in ets:
                    coord.store.basculer(cible, e, False)
                dit("parent.autorise_categorie", qui=qui, nom=joli,
                    categories=lambda lg: ", ".join(textes.categorie(e, lg) for e in ets))
        elif decision == "temporaire":
            minutes = minutes or 60
            if d.get("genre") == "apps":
                coord.store.laisser_passer_app(cible, d["cle"], minutes)
            else:
                coord.store.laisser_passer(cible, d["cle"], minutes)
            dit("parent.ouvert_nom_pour", qui=qui, nom=joli, minutes=minutes)
        else:
            dit("parent.pas_autorise", qui=qui, nom=joli)
            # Refusé : la même demande attend avant de pouvoir revenir.
            jusqua = coord.store.noter_refus(cible, str(d.get("genre") or "sites"), d["cle"])
            if jusqua:
                dit("parent.redemande_a", heure=heure_de(jusqua))
        if decision != "non" and decision in ("toujours", "exception", "categorie", "temporaire"):
            coord.store.lever_refus(cible, str(d.get("genre") or "sites"), d["cle"])
        persistent_notification.async_dismiss(
            hass, "{}_acces_{}_{}".format(DOMAIN, cible["id"], d["id"])
        )
        try:
            coord.store.add_message(cible["id"], dit.en(lc), titre=textes.t("commun.reponse", lc))
        except ValueError:
            pass
    elif action == "autoriser":
        nom = str(corps.get("nom") or "").strip()
        # 2.3.4 : « app:minecraft » est un logiciel, et un appel sans genre ne fait plus d'un logiciel un site :
        # le catalogue tranche (comme pour les règles), au lieu de « sites » par défaut.
        if nom.lower().startswith("app:"):
            genre, nom = "apps", nom[4:].strip()
        elif str(corps.get("genre") or "") in ("apps", "sites"):
            genre = str(corps.get("genre"))
        else:
            genre = coord.store._genre_de(nom) if nom else "sites"
        cle = demandes.cle_de(genre, nom)
        if not cle:
            raise _Refus(400, "nom")
        decision = str(corps.get("decision") or "temporaire")
        if decision not in ("bloquer", "indisponible", "oublier"):
            # Un parent ouvre de lui-même : le refus d'avant ne tient plus.
            coord.store.lever_refus(cible, genre, cle)
        if decision == "toujours":
            if coord.store.est_banni(cle):
                raise _Refus(409, "banni", textes.t("parent.banni_lever", langue))
            coord.store.basculer(cible, cle, False)
            dit("parent.autorise", qui=qui, nom=nom)
        elif decision == "bloquer":
            coord.store.basculer(cible, cle, True)
            dit("parent.bloque", qui=qui, nom=nom)
        elif decision == "disponible":
            # Reste ouverte même quand le planning ferme l'appareil. Une appli seulement : un site n'a pas d'icône à proposer.
            if genre != "apps":
                raise _Refus(400, "genre", textes.t("parent.seule_appli_dispo", langue))
            if coord.store.est_banni(cle):
                raise _Refus(409, "banni", textes.t("parent.banni_lever", langue))
            coord.store.basculer(cible, cle, False)
            if nom.lower() not in {n.lower() for n in _toujours_personne(coord.store, cible)}:
                cible.setdefault("toujours", []).append(nom)
            dit("parent.reste_dispo", qui=qui, nom=nom)
        elif decision == "indisponible":
            retire = False
            for a in _appareils_personne(coord.store, cible):
                avant_tj = list(a.get("toujours") or [])
                apres_tj = [n for n in avant_tj if str(n).strip().lower() != nom.lower()]
                if len(apres_tj) != len(avant_tj):
                    a["toujours"] = apres_tj
                    retire = True
            if not retire:
                raise _Refus(404, "inconnu", textes.t("parent.appli_plus_liste", langue))
            dit("parent.suit_planning", qui=qui, nom=nom)
        elif decision == "oublier":
            # L'exception est retirée : ce nom suit de nouveau ses catégories, l'âge et le planning.
            retire = coord.store.oublier_regle(cible, cle)
            retire = coord.store.oublier_regle(cible, nom) or retire
            if not retire:
                raise _Refus(404, "inconnu", textes.t("parent.exception_disparue", langue))
            dit("parent.exception_retiree", qui=qui, nom=nom)
        else:
            minutes = minutes or 60
            if genre == "apps":
                coord.store.laisser_passer_app(cible, cle, minutes)
            else:
                coord.store.laisser_passer(cible, cle, minutes)
            dit("parent.ouvert_nom_pour", qui=qui, nom=nom, minutes=minutes)
        if cible["id"] != moi_id and avis:
            try:
                coord.store.add_message(cible["id"], dit.en(lc), titre="Vision")
            except ValueError:
                pass
    elif action == "etiquette":
        nom = str(corps.get("etiquette") or "").strip()
        etat = str(corps.get("etat") or "neutre")
        if not nom or not coord.store._est_etiquette(nom):
            raise _Refus(400, "etiquette")
        if coord.store.est_banni(nom):
            raise _Refus(409, "banni", textes.t("parent.banni_lever", langue))
        categorie_lg = lambda lg: textes.categorie(nom, lg)  # noqa: E731
        if etat == "bloquer":
            coord.store.basculer(cible, nom, True)
            dit("parent.etiquette_coupee", qui=qui, categorie=categorie_lg)
        elif etat == "autoriser":
            coord.store.basculer(cible, nom, False)
            dit("parent.etiquette_autorisee", qui=qui, categorie=categorie_lg)
        else:
            for champ in ("bloques", "autorises"):
                cible[champ] = [v for v in (cible.get(champ) or []) if str(v).strip().lower() != nom.lower()]
            dit("parent.etiquette_commune", qui=qui, categorie=categorie_lg)
    elif action == "temps":
        # Du temps en plus, sur les appareils choisis : celui visé, ou la liste « appareils ».
        if minutes < 1:
            raise _Refus(400, "minutes", textes.t("parent.duree_manquante", langue))
        voulus = corps.get("appareils")
        ids = [str(i) for i in voulus] if isinstance(voulus, list) and voulus else [cible["id"]]
        cibles = [coord.store.pcs[i] for i in dict.fromkeys(ids) if i in coord.store.pcs]
        if not cibles:
            raise _Refus(404, "pc", textes.t("parent.aucun_appareil", langue))
        fin = 0.0
        for a in cibles:
            fin = max(fin, coord.store.ajouter_temps(a, minutes))
            a.pop("demande", None)  # 1.97.2 : du temps donné répond à la demande en attente
        try:
            from . import bilan as _bilan

            for p_b in {str(a.get("person") or "") for a in cibles}:
                _bilan.compter(coord.store, p_b, "minutes", minutes)
        except Exception:  # noqa: BLE001
            pass
        noms = ", ".join(str(a.get("name") or a["id"]) for a in cibles)
        dit("parent.temps_ajoute_sur", qui=qui, minutes=minutes, nom=noms, heure=heure_de(fin))
        for a in cibles:
            if a["id"] != moi_id and avis:
                la = textes.pour(hass, a)
                try:
                    coord.store.add_message(a["id"], textes.t("parent.temps_ajoute_toi", la, qui=qui, minutes=minutes,
                                                              heure=textes.heure(fin, la)), titre="Vision")
                except ValueError:
                    pass
    elif action == "concentration":
        # 1.88.0 : seules les applis choisies restent ouvertes pendant « minutes » ; « fin » l'arrête.
        from . import concentration

        nom_c = str(cible.get("name") or cible["id"])
        if corps.get("fin"):
            if not concentration.arreter(cible):
                return textes.t("parent.pas_concentration", langue, nom=nom_c)
            dit("parent.concentration_arretee_sur", qui=qui, nom=nom_c)
            if cible["id"] != moi_id and avis:
                try:
                    coord.store.add_message(cible["id"], textes.t("parent.concentration_arretee_toi", lc, qui=qui), titre="Vision")
                except ValueError:
                    pass
        else:
            apps_c = corps.get("apps") if isinstance(corps.get("apps"), list) else None
            try:
                c = concentration.demarrer(coord.store, cible, minutes, apps_c, qui)
            except ValueError as err:
                raise _Refus(400, "minutes", textes.t("parent.duree_concentration", langue,
                                                      heures=concentration.MINUTES_MAX // 60)) from err
            dit("parent.concentration_lancee", qui=qui, nom=nom_c, heure=heure_de(float(c["fin"])), _n=len(c["apps"]))
            if cible["id"] != moi_id and avis:
                try:
                    coord.store.add_message(cible["id"], textes.t("conc.jusqua", lc, heure=textes.heure(float(c["fin"]), lc)),
                                            titre="Vision")
                except ValueError:
                    pass
    elif action == "partage":
        # À qui est cet appareil, en plus de sa personne principale.
        tous = bool(corps.get("tous"))
        personnes = corps.get("personnes") if isinstance(corps.get("personnes"), list) else []
        coord.store.definir_partage(cible, [str(p) for p in personnes], tous)
        avec = coord.store.proprietaires(cible)
        if tous:
            dit("parent.partage_tous", nom=cible.get("name") or cible["id"])
        elif len(avec) > 1:
            dit("parent.partage_entre", nom=cible.get("name") or cible["id"], n=len(avec))
        else:
            dit("parent.plus_partage", nom=cible.get("name") or cible["id"])
    elif action == "attribuer":
        # Depuis la fiche d'une personne : cet appareil devient le sien, elle le partage, ou il n'est plus à elle.
        mode = str(corps.get("mode") or "")
        personne = str(corps.get("personne") or "")
        try:
            coord.store.attribuer(cible, personne, mode)
        except ValueError as err:
            raise _Refus(400, "attribuer", textes.erreur(err, langue)) from err
        etat_p = hass.states.get(personne)
        prenom_p = str(etat_p.name) if etat_p is not None and etat_p.name else personne.split(".")[-1]
        nom_a = str(cible.get("name") or cible["id"])
        dit({
            "principal": "parent.attribue_principal",
            "partage": "parent.attribue_partage",
            "retirer": "parent.attribue_retire",
        }[mode], nom=nom_a, prenom=prenom_p)
    elif action == "limite":
        # Tant de minutes par jour pour un logiciel, sur tous les appareils de la personne. 0 retire la limite.
        nom = str(corps.get("nom") or "").strip()
        try:
            change = coord.store.poser_limite(cible, nom, minutes)
        except ValueError as err:
            raise _Refus(400, "limite", textes.erreur(err, langue)) from err
        if minutes:
            dit("parent.limite", qui=qui, nom=nom, minutes=minutes)
        elif change:
            dit("parent.limite_retiree", qui=qui, nom=nom)
        else:
            dit("parent.pas_de_limite", nom=nom)
    elif action == "plage":
        try:
            dit("parent.brut", texte=_plage(hass, coord, cible, corps, qui, langue))
        except (ValueError, KeyError) as err:
            raise _Refus(400, "plage", textes.erreur(err, langue).strip("'\"")) from err
    elif action == "logiciel":
        # Depuis la fiche d'un appareil : désinstaller un logiciel de CET appareil, ou annuler la demande.
        gestion = getattr(coord, "processus", None)
        if gestion is None:
            raise _Refus(503, "logiciel", textes.t("parent.logiciels_pas_prets", langue))
        cle = str(corps.get("cle") or "")
        geste = str(corps.get("geste") or "")
        try:
            if geste == "desinstaller":
                if not gestion.desinstallation_permise:
                    raise _Refus(403, "logiciel", textes.t("parent.desinstallation_eteinte", langue))
                if corps.get("confirmation") is not True:
                    raise _Refus(400, "logiciel", textes.t("commun.confirme_desinstallation", langue))
                fiche = gestion.catalogue.decider(cle, "desinstaller", time.time(), pcs=[cible["id"]])
                dit("parent.desinstallation_demandee", qui=qui, nom=fiche.get("nom") or cle,
                    appareil=cible.get("name") or cible["id"])
            elif geste == "annuler":
                fiche = gestion.catalogue.decider(cle, "neutre", time.time())
                dit("parent.desinstallation_annulee", qui=qui, nom=fiche.get("nom") or cle)
            else:
                raise _Refus(400, "logiciel", textes.t("commun.geste_inconnu", langue))
        except (ValueError, KeyError) as err:
            raise _Refus(400, "logiciel", textes.erreur(err, langue).strip("'\"")) from err
        gestion._sauver()
        gestion._publier()
    elif action == "pause_max":
        # La durée max d'une pause (code parent ou empreinte) sur cet appareil (1.70.0).
        from .pauses import PAUSE_MAX_MAX, PAUSE_MAX_MIN

        try:
            valeur = int(corps.get("minutes") or 0)
        except (TypeError, ValueError):
            valeur = 0
        if not PAUSE_MAX_MIN <= valeur <= PAUSE_MAX_MAX:
            raise _Refus(400, "minutes", textes.t("parent.pause_entre", langue, min=PAUSE_MAX_MIN, max=PAUSE_MAX_MAX))
        cible["pause_max"] = valeur
        _LOGGER.warning("Vision : durée max de pause sur « %s » réglée à %d min (par %s).", cible.get("name"), valeur, qui)
        dit("parent.pause_max", minutes=valeur,
            nom=cible.get("name") or (lambda lg: textes.t("parent.cet_appareil", lg)))
    elif action == "message":
        texte = str(corps.get("texte") or "").strip()[:200]
        if not texte:
            raise _Refus(400, "texte")
        try:
            coord.store.add_message(cible["id"], texte, titre=qui)
        except ValueError:
            pass
        dit("commun.message_envoye")
    else:
        raise _Refus(400, "action")
    if action in ("ouvrir", "fermer", "annuler") and cible["id"] != moi_id and avis:
        try:
            coord.store.add_message(cible["id"], dit.en(lc), titre="Vision")
        except ValueError:
            pass
    retour = dit.en(langue)
    await coord.store.async_save()
    coord.async_set_updated_data(
        {p: coord.store.evaluate(x) for p, x in coord.store.pcs.items()}
    )
    if action == "plage" and (str(corps.get("op") or "") in ("creer", "supprimer") or "nom" in corps):
        # Une plage a son interrupteur dans Home Assistant : il apparaît ou disparaît avec elle.
        try:
            await coord.async_ecrire(structurel=True)
        except Exception:  # noqa: BLE001 - le geste est fait, les entités suivront au prochain rechargement
            pass
    if action == "attribuer":
        # Les pages des personnes suivent : le tableau de bord se refait s'il a changé.
        try:
            await coord.async_maj_tableau()
        except Exception:  # noqa: BLE001 - le geste est fait, le tableau suivra
            pass
    hass.bus.async_fire(
        "{}_parent_action".format(DOMAIN),
        {"action": action, "pc": cible["id"], "minutes": minutes, "par": qui},
    )
    return retour


# Les gestes qui visent toute la maison, pas un appareil : pas de « pc » à fournir.
GESTES_MAISON = frozenset({"pause_generale", "fin_pause_generale"})


def demandes_attente(hass: HomeAssistant, coord: PcParentalCoordinator, langue: str | None = None) -> list[dict[str, Any]]:
    """1.97.8 : LA source des demandes en attente d'un décideur — accès (site, appli), temps, déverrouillage.

    La carte « Demandes en attente », l'appli (gestion) et le widget de l'accueil la lisent toutes ; la
    notification part de la même création. Avant, les demandes de déverrouillage n'y étaient pas.
    """
    from . import deverrouillage

    sortie: list[dict[str, Any]] = []
    for pc in coord.store.pcs.values():
        qui = demandes.prenom(hass, pc) or str(pc.get("name") or "")
        for d in demandes.en_attente(pc):
            sortie.append(dict(d, prenom=qui, pc=pc["id"]))
        if t := temps_en_attente(pc):
            sortie.append({"id": t["id"], "genre": "temps", "minutes": t["minutes"],
                           "libelle": textes.t("parent.libelle_minutes", langue, minutes=t["minutes"]),
                           "ts": t["ts"], "prenom": qui, "pc": pc["id"]})
    if not modules.coupe(coord.store, "deverrouillage"):
        try:
            for d in deverrouillage._demandes(hass).values():
                if d.get("etat") != "en_attente":
                    continue
                pc = coord.store.pcs.get(d["pc"]) or {}
                sortie.append({
                    "id": d["id"], "genre": "deverrouillage", "minutes": int(d["minutes"]), "ts": float(d["cree"]),
                    "libelle": textes.t("parent.libelle_dev", langue, quoi=deverrouillage.libelle(d["action"], langue),
                                        minutes=int(d["minutes"])),
                    "motif": str(d.get("motif") or ""), "action": d["action"],
                    "prenom": demandes.prenom(hass, pc) or str(pc.get("name") or d["pc"]), "pc": d["pc"],
                })
        except Exception:  # noqa: BLE001 - une liste de déverrouillages illisible ne cache pas les autres demandes
            _LOGGER.exception("Demandes de déverrouillage")
    sortie.sort(key=lambda d: d.get("ts") or 0, reverse=True)
    return sortie


def version_au_moins(version: str, voulue: tuple[int, ...]) -> bool:
    """« 1.76.0 » >= (1, 77, 0) ? Une version illisible compte comme ancienne."""
    try:
        return tuple(int(x) for x in version.split(".")[:3]) >= voulue
    except ValueError:
        return False


def _deverrouillage_de(hass: HomeAssistant, pc: dict[str, Any], ident: str) -> dict[str, Any] | None:
    """La demande de déverrouillage en attente de cet appareil qui porte cet identifiant, sinon None."""
    from . import deverrouillage

    try:
        d = deverrouillage._demandes(hass).get(ident)
    except Exception:  # noqa: BLE001
        return None
    return d if d is not None and d.get("pc") == pc.get("id") and d.get("etat") == "en_attente" else None


def temps_en_attente(pc: dict[str, Any] | None) -> dict[str, Any] | None:
    """1.97.2 : la demande de temps encore ouverte d'un appareil (24 h au plus, comme les demandes d'accès)."""
    t = (pc or {}).get("demande")
    if not isinstance(t, dict) or not t.get("id"):
        return None
    try:
        if float(t.get("ts") or 0) < time.time() - 24 * 3600:
            return None
        return {"id": str(t["id"]), "minutes": max(1, int(t.get("minutes") or 0)), "ts": float(t.get("ts") or 0)}
    except (TypeError, ValueError):
        return None


async def agir_maison(hass: HomeAssistant, coord: PcParentalCoordinator, corps: dict[str, Any], qui: str,
                      langue: str | None = None) -> str:
    """Pause générale : poser (minutes, 1 à 240) ou reprendre. Mêmes refus que les autres gestes."""
    langue = langue or textes.ha(hass)
    action = str(corps.get("action") or "")
    if (coupe := modules.geste_coupe(coord.store, action, corps)) is not None:
        raise _Refus(403, "module", textes.t("commun.module_coupe", langue, module=modules.nom(coupe, langue)))
    minutes = 0
    if action == "pause_generale":
        try:
            minutes = int(corps.get("minutes") or 0)
        except (TypeError, ValueError):
            minutes = 0
        if not pause_generale.MINUTES_MIN <= minutes <= pause_generale.MINUTES_MAX:
            raise _Refus(400, "minutes", textes.t("parent.pause_generale_duree", langue,
                                                  min=pause_generale.MINUTES_MIN, max=pause_generale.MINUTES_MAX))
        try:
            retour = await pause_generale.async_poser(hass, coord, minutes, qui, langue)
        except ValueError as err:
            raise _Refus(409, "pause_generale", textes.erreur(err, langue)) from err
    else:
        retour = await pause_generale.async_reprendre(hass, coord, qui, langue)
    hass.bus.async_fire("{}_parent_action".format(DOMAIN), {"action": action, "pc": "", "minutes": minutes, "par": qui})
    return retour


def _cible(coord: PcParentalCoordinator, corps: dict[str, Any]) -> dict[str, Any] | None:
    """L'appareil visé : « pc », ou le premier de la liste « appareils »."""
    cible = coord.store.pcs.get(str(corps.get("pc") or ""))
    if cible is None and isinstance(corps.get("appareils"), list):
        for i in corps["appareils"]:
            cible = coord.store.pcs.get(str(i))
            if cible is not None:
                break
    return cible


def _coordinateur(hass: HomeAssistant) -> PcParentalCoordinator | None:
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


# Les gestes qui changent les règles : il faut la fonction « regles » du rôle, en plus de « gerer ».
GESTES_REGLES = frozenset({"plage", "limite", "etiquette", "autoriser", "attribuer", "partage", "logiciel", "pause_max"})


class PcParentalParentView(HomeAssistantView):
    """L'application d'un parent : secret de l'appareil et étiquette « Parents »."""

    url = URL_PARENT
    name = "api:pc_parental:parent"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        moi = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if moi is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        if textes.noter_langue(moi, corps):
            self.hass.async_create_task(coord.store.async_save())
        langue = textes.pour(self.hass, moi)
        parents = roles.avec(coord.store, "gerer")
        if moi["id"] not in parents:
            return self.json({"ok": False, "error": "parent"}, status_code=403)

        retour = ""
        if str(corps.get("action") or "") in GESTES_MAISON:
            # Pause générale : toute la maison, pas d'appareil visé ; « Gérer » suffit (vérifié plus haut).
            qui = _prenom(self.hass, moi) or str(moi.get("name") or textes.t("commun.un_gestionnaire", langue))
            try:
                retour = await agir_maison(self.hass, coord, corps, qui, langue)
            except _Refus as refus:
                reponse_m: dict[str, Any] = {"ok": False, "error": refus.erreur}
                if refus.retour:
                    reponse_m["retour"] = refus.retour
                return self.json(reponse_m, status_code=refus.status)
        elif str(corps.get("action") or ""):
            cible = _cible(coord, corps)
            if cible is None:
                return self.json({"ok": False, "error": "pc"}, status_code=404)
            qui = _prenom(self.hass, moi) or str(moi.get("name") or textes.t("commun.un_gestionnaire", langue))
            # ★ « Modifier règles, plages, limites et étiquettes » : à part de « Gérer » (1.74.0).
            if str(corps.get("action")) in GESTES_REGLES and not roles.a(coord.store, moi, "regles"):
                _LOGGER.warning("Vision : « %s » a tenté « %s » sans la fonction « Modifier les règles ».",
                                moi.get("name"), corps.get("action"))
                return self.json({"ok": False, "error": "regles",
                                  "retour": textes.t("parent.role_regles", langue)}, status_code=403)
            try:
                retour = await agir(self.hass, coord, cible, corps, qui, moi["id"], langue)
            except _Refus as refus:
                reponse: dict[str, Any] = {"ok": False, "error": refus.erreur}
                if refus.retour:
                    reponse["retour"] = refus.retour
                return self.json(reponse, status_code=refus.status)
            if corps.get("action") == "acces" and not retour:
                return self.json({"ok": True, "deja": True, "appareils": []})
        etat = etat_maison(self.hass, coord, moi["id"], langue)
        # 1.97.5 : une appli d'avant la 1.77.0 affichait une demande de temps comme un « Site : » vide ; elle les
        # reçoit seulement en message (comme avant la 1.97.2).
        if "android" in (moi.get("platform"), (moi.get("agent") or {}).get("platform")) and not version_au_moins(str((moi.get("agent") or {}).get("version") or ""), (1, 77, 0)):
            etat["demandes"] = [d for d in etat.get("demandes") or [] if d.get("genre") != "temps"]
        # 1.97.8 : avant la 1.80.0, l'appli ne sait pas présenter une demande de déverrouillage (elle la recevait déjà
        # en message) : on ne la lui met pas dans la liste.
        if "android" in (moi.get("platform"), (moi.get("agent") or {}).get("platform")) and not version_au_moins(str((moi.get("agent") or {}).get("version") or ""), (1, 80, 0)):
            etat["demandes"] = [d for d in etat.get("demandes") or [] if d.get("genre") != "deverrouillage"]
        etat.update({"moi": moi["id"], "retour": retour})
        return self.json(etat)


class PcParentalMaisonView(HomeAssistantView):
    """Le tableau de bord : un administrateur Home Assistant, rien d'autre."""

    url = URL_MAISON
    name = "api:pc_parental:maison"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _admin(self, request: web.Request) -> web.Response | None:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        return None

    async def get(self, request: web.Request) -> web.Response:
        if refus := self._admin(request):
            return refus
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        return self.json(etat_maison(self.hass, coord))

    async def post(self, request: web.Request) -> web.Response:
        if refus := self._admin(request):
            return refus
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        langue = textes.ha(self.hass)
        qui = str(getattr(request.get("hass_user"), "name", "") or textes.t("commun.un_gestionnaire", langue))
        cible = None
        if str(corps.get("action") or "") not in GESTES_MAISON:
            cible = _cible(coord, corps)
            if cible is None:
                return self.json({"ok": False, "error": "pc"}, status_code=404)
        try:
            retour = (await agir_maison(self.hass, coord, corps, qui, langue) if cible is None
                      else await agir(self.hass, coord, cible, corps, qui, langue=langue))
        except _Refus as refus:
            reponse: dict[str, Any] = {"ok": False, "error": refus.erreur}
            if refus.retour:
                reponse["retour"] = refus.retour
            return self.json(reponse, status_code=refus.status)
        etat = etat_maison(self.hass, coord)
        etat["retour"] = retour
        return self.json(etat)
