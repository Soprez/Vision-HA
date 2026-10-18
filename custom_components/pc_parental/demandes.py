"""Demandes d'accès : un enfant demande un logiciel ou un site fermé.

Depuis l'écran de blocage (Android) ou l'agent du PC, la demande part chez
les parents, qui répondent d'un geste depuis leur téléphone : pour de bon,
pour un moment, ou non. La réponse revient à l'enfant comme un message.
Rien n'est automatique : sans réponse, rien ne s'ouvre.
"""
from __future__ import annotations

import secrets
import time
from typing import Any

from aiohttp import web
from homeassistant.components import persistent_notification
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import labels
from . import modules
from . import roles
from . import textes
from .const import AUTORISE, DOMAIN
from .coordinator import PcParentalCoordinator
from .store import _nom_app, _nom_site, domaine_principal

URL_DEMANDE = "/api/pc_parental/demande"
MAX_EN_ATTENTE = 12
# Une même demande n'est renvoyée aux parents qu'une fois par période.
REPOS_S = 15 * 60
# Ce que l'enfant souhaite : une heure, une exception permanente (quand son écran est ouvert), ou toute la catégorie.
# Valeur : la clé du texte dans le catalogue serveur.
DUREES = {"1h": "dem.duree_1h", "toujours": "dem.duree_toujours", "categorie": "dem.duree_categorie"}


def prenom(hass: HomeAssistant, pc: dict[str, Any]) -> str:
    etat = hass.states.get(str(pc.get("person") or ""))
    if etat is not None and etat.name:
        return str(etat.name)
    return str(pc.get("name") or "")


def cle_de(genre: str, nom: str) -> str:
    return _nom_app(nom) if genre == "apps" else _nom_site(nom)


def en_attente(pc: dict[str, Any]) -> list[dict[str, Any]]:
    """Les demandes encore ouvertes de cet appareil (les vieilles tombent)."""
    limite = time.time() - 24 * 3600
    liste = [d for d in (pc.get("demandes") or []) if float(d.get("ts") or 0) > limite]
    pc["demandes"] = liste
    return liste


def retirer(pc: dict[str, Any], demande_id: str) -> dict[str, Any] | None:
    liste = en_attente(pc)
    for d in liste:
        if d.get("id") == demande_id:
            liste.remove(d)
            return d
    return None


def deposer(
    hass: HomeAssistant, coord: PcParentalCoordinator, pc: dict[str, Any],
    genre: str, nom: str, libelle: str, lien: str = "", motif: str = "", duree: str = "",
    sites: list[str] | None = None,
) -> dict[str, Any] | None:
    """Enregistre la demande et prévient les parents. None si déjà en attente.

    2.5.1 (ajout 5 de l'auteur) : une demande pour un logiciel peut emporter ses sites bloqués (`sites`) : une seule
    demande, que le Décideur accepte en bloc ou élément par élément. Seuls restent les sites rangés sous ce
    logiciel (ou bloqués pendant qu'il tournait), fermés, et qu'une autorisation peut ouvrir."""
    cle = cle_de(genre, nom)
    if not cle:
        return None
    sites = sites_du_groupe(coord, pc, cle, sites) if genre == "apps" and sites else []
    # Refusée il y a peu : elle ne repart pas chez les parents avant la fin de l'attente.
    jusqua = coord.store.refus_jusqua(pc, genre, cle)
    if jusqua:
        return {"refus": jusqua, "cle": cle, "libelle": libelle.strip() or cle}
    liste = en_attente(pc)
    for d in liste:
        if d.get("genre") == genre and d.get("cle") == cle:
            # Déjà demandé : on rafraîchit l'heure, sans re-sonner chez les parents.
            if time.time() - float(d.get("ts") or 0) < REPOS_S:
                d["sites"] = list(dict.fromkeys(list(d.get("sites") or []) + sites))
                return d
            liste.remove(d)
            break
    if len(liste) >= MAX_EN_ATTENTE:
        liste.pop(0)
    qui = prenom(hass, pc)
    joli = libelle.strip() or cle
    try:
        _r = coord.store.raison_enfant(pc, genre, cle)
    except Exception:  # noqa: BLE001
        _r = None
    # La raison gardée avec la demande (tableau de bord, liste des demandes) : dans la langue de Home Assistant.
    langue_ha = textes.ha(hass)
    try:
        _r_ha = coord.store.raison_enfant(pc, genre, cle, langue_ha) if _r else None
    except Exception:  # noqa: BLE001
        _r_ha = None
    raison = (_r_ha or _r)["texte"] if _r else ""
    code = _r["code"] if _r else ""
    duree = duree if duree in DUREES else ""
    try:
        etiquettes = [e for e in coord.store.etiquettes_de(genre, cle) if not coord.store.est_banni(e)]
    except Exception:  # noqa: BLE001
        etiquettes = []
    try:
        from . import bilan as _bilan

        _bilan.compter(coord.store, str(pc.get("person") or ""), "demande")
    except Exception:  # noqa: BLE001 - un compteur de bilan ne bloque jamais un geste
        pass
    d = {
        "id": secrets.token_hex(4), "genre": genre, "nom": nom, "cle": cle,
        "libelle": joli, "ts": time.time(), "pc": pc["id"], "prenom": qui,
        "lien": lien[:300], "motif": motif[:120], "raison": raison, "code": code,
        "duree": duree, "etiquettes": etiquettes[:6],
    }
    if sites:
        d["sites"] = sites
    liste.append(d)

    def raison_en(langue: str) -> str:
        if not _r:
            return ""
        if langue == langue_ha:
            return raison
        try:
            return (coord.store.raison_enfant(pc, genre, cle, langue) or _r)["texte"]
        except Exception:  # noqa: BLE001
            return raison

    def texte_en(langue: str) -> str:
        texte = textes.t("dem.acces", langue, qui=qui or pc.get("name"), nom=joli)
        if lien and lien.strip().lower() != cle:
            texte = "{} {}".format(texte, lien[:120])
        if duree:
            texte = "{} {}".format(texte.rstrip("."), textes.t(DUREES[duree], langue)) + "."
            if duree == "categorie" and etiquettes:
                texte = "{} {}".format(texte, textes.t("dem.categorie", langue, liste=", ".join(
                    textes.categorie(e, langue) for e in etiquettes[:6])))
        if sites:
            texte = "{} {}".format(texte, textes.n("dem.sites_bloques", len(sites), langue))
        if motif:
            texte = "{} ({})".format(texte, motif[:120])
        if _r:
            texte = "{} {}".format(texte, textes.t("dem.pourquoi", langue, raison=raison_en(langue)))
        return texte

    # La notification de Home Assistant dit où décider, avec un lien (HA-07) ; les appareils ont leurs boutons.
    persistent_notification.async_create(
        hass, texte_en(langue_ha) + "\n\n" + textes.ou_decider(langue_ha), title=textes.t("dem.titre_ha", langue_ha),
        notification_id="{}_acces_{}_{}".format(DOMAIN, pc["id"], d["id"]),
    )
    hass.bus.async_fire("{}_demande_acces".format(DOMAIN), dict(d))
    # 2.5.0 : la même demande sur le téléphone des Décideurs (appli compagnon), avec les boutons de réponse.
    try:
        from . import notifs_mobiles

        hass.async_create_task(notifs_mobiles.envoyer(hass, coord.store, pc, d, texte_en(langue_ha)))
    except Exception:  # noqa: BLE001 - une notification ratée ne bloque jamais la demande
        pass
    # 2.5.6 (l'auteur, 15/10) : sur Discord si un Décideur l'a choisi ; dans l'appli Vision de ceux qui l'ont choisie.
    from . import canaux

    hass.async_create_task(canaux.discord(hass, coord.store, "demandes", textes.t("dem.titre_ha", langue_ha),
                                          texte_en(langue_ha)))
    for pid in roles.avec(coord.store, "gerer"):
        if pid == pc["id"] or not canaux.vision_pour(hass, coord.store, pid, "demandes"):
            continue
        langue_p = textes.pour(hass, coord.store.pcs.get(pid))
        try:
            msg = coord.store.add_message(pid, texte_en(langue_p), titre=textes.t("dem.titre", langue_p))
        except ValueError:
            continue
        msg["acces"] = {
            "id": d["id"], "pc": pc["id"], "prenom": qui, "genre": genre,
            "nom": nom, "libelle": joli, "lien": lien[:300], "motif": motif[:120],
            "raison": raison_en(langue_p), "code": code, "duree": duree, "etiquettes": etiquettes[:6],
            **({"sites": sites} if sites else {}),
        }
    return d


def sites_du_groupe(coord: PcParentalCoordinator, pc: dict[str, Any], app: str, voulus: list[str]) -> list[str]:
    """Les sites d'une demande groupée qu'on garde : rangés sous ce logiciel ou bloqués pendant qu'il tournait,
    fermés sur cet appareil, et ni Lieu, ni sécurité ou âge, ni liste noire (aucune autorisation ne les passe)."""
    store = coord.store
    permis = {f["nom"] for f in store.sites_du_logiciel(app)}
    permis |= {b["nom"] for b in store.blocages_recents(pc, pour_demandeur=True, maximum=60) if b.get("logiciel") == app}
    gardes: list[str] = []
    for brut in list(voulus or [])[:40]:
        site = domaine_principal(str(brut))
        if not site or site in gardes or site not in permis:
            continue
        if store.statut(pc, "sites", site) == AUTORISE:
            continue
        if store.bloque_explicite(pc, "sites", site) in ("lieu", "securite", "liste_noire"):
            continue
        gardes.append(site)
    return gardes


class PcParentalDemandeView(HomeAssistantView):
    url = URL_DEMANDE
    name = "api:pc_parental:demande"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord: PcParentalCoordinator | None = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.noter_langue(pc, corps)
        langue = textes.pour(self.hass, pc)
        # 1.82.0 : module « Demandes » coupé : refusé proprement, personne n'est prévenu.
        if refus := modules.refus(self, coord.store, "demandes", langue=langue):
            return refus
        nom = str(corps.get("nom") or "").strip()[:120]
        # 2.3.4 : « app:… » est un logiciel ; sans genre, le catalogue tranche au lieu de « sites » par défaut.
        if nom.lower().startswith("app:"):
            genre, nom = "apps", nom[4:].strip()
        elif str(corps.get("genre") or "") in ("apps", "sites"):
            genre = str(corps.get("genre"))
        else:
            genre = coord.store._genre_de(nom) if nom else "sites"
        libelle = str(corps.get("libelle") or "").strip()[:60]
        lien = str(corps.get("lien") or "").strip()[:300]
        motif = str(corps.get("motif") or "").strip()[:120]
        duree = str(corps.get("duree") or "").strip()
        if not nom:
            return self.json({"ok": False, "error": "nom"}, status_code=400)
        sites = [str(x) for x in corps.get("sites") or [] if isinstance(x, str)] if isinstance(corps.get("sites"), list) else []
        d = deposer(self.hass, coord, pc, genre, nom, libelle, lien, motif, duree, sites)
        if d is None:
            return self.json({"ok": False, "error": "nom"}, status_code=400)
        if d.get("refus"):
            return self.json({
                "ok": False, "error": "refus", "jusqua": int(d["refus"]),
                "retour": textes.t("dem.refusee", langue, nom=d["libelle"], heure=textes.heure(float(d["refus"]), langue)),
            })
        await coord.store.async_save()
        return self.json({"ok": True, "demande": d["id"], "attente": len(en_attente(pc))})
