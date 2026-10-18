"""Appairage par QR : l'appareil demande, un administrateur valide, Home Assistant répond.

Plus de clé à recopier sur l'appareil d'un enfant. L'appareil à inscrire (télé, PC,
téléphone) demande un appairage et affiche un QR et un code court ; un parent le scanne,
l'appli Home Assistant s'ouvre sur `/vision-appairage` (panneau réservé aux
administrateurs) et il valide. L'appareil, qui interrogeait Home Assistant en attendant,
reçoit alors son identifiant et son secret — une seule fois.

- `POST /api/pc_parental/appairage/demande` (sans authentification) :
  {host, nom, uid, platform, user} → {demande, attente, code, qr, lien, expire_dans, intervalle}.
- `POST /api/pc_parental/appairage/attente` (sans authentification) : {demande, attente}
  → {etat: en_attente | validee | refusee | expiree}, et sur « validee » : {id, secret, name, poll, created}.
- `GET /api/pc_parental/appairage` (administrateur) : les demandes en cours.
- `POST /api/pc_parental/appairage` (administrateur) : {code, action: valider | refuser, personne}.
- Services `pc_parental.valider_appairage` et `pc_parental.refuser_appairage` (administrateurs).

Dans l'autre sens (1.65.0), pour un appareil qui a une caméra : un administrateur génère un
QR d'appairage (Vision → Configurer, ou le panneau) ; l'appareil le scanne et se relie seul.
- `POST /api/pc_parental/appairage/invitation` (administrateur) : {personne, appareil}
  → {id, qr, svg, expire_dans}. Le QR : `vision://appairage?v=1&adresse=…&locale=…&jeton=…`.
- `GET /api/pc_parental/appairage/invitation/{id}/qr.svg` (administrateur, chemin signé).
- `POST /api/pc_parental/appairage/jeton` (sans authentification) : {jeton, host, nom, uid,
  platform, user} → {id, secret, name, poll, created}. Le jeton ne sert qu'une fois.

Rien n'est écrit sur le disque tant qu'un administrateur n'a pas validé : une demande vit
quelques minutes en mémoire, puis disparaît. Le QR ne porte que le code de la demande.
"""

from __future__ import annotations

import logging
import os
import io
import secrets
import time
from typing import Any
from urllib.parse import quote

import voluptuous as vol
from aiohttp import web
from homeassistant.components import persistent_notification
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv

from . import textes
from .const import DOMAIN, POLL_SECONDS, PRODUIT

_LOGGER = logging.getLogger(__name__)

APPAIRAGE_MINUTES = 5
# 2.4.7 : une demande d'appareil vit 10 min (avant 5) — le {decideur} répond depuis son téléphone, sans code.
DEMANDE_MINUTES = 10
# Sans 0/O, 1/I/L : un code se lit à voix haute et se tape sans erreur.
ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
LONGUEUR_CODE = 6
MAX_DEMANDES = 10
MAX_PAR_ADRESSE = 2
MAX_INVITATIONS = 5
INTERVALLE_S = 3
PANNEAU = "vision-appairage"
STATIQUE = "/pc_parental_static"
DOSSIER_PANNEAU = os.path.join(os.path.dirname(__file__), "panneau")
CLE_DONNEES = f"{DOMAIN}_appairage"


def _coordinateur(hass: HomeAssistant):
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


def _demandes(hass: HomeAssistant) -> dict[str, dict[str, Any]]:
    """Les demandes en cours, par identifiant ; les expirées sont oubliées au passage."""
    demandes: dict[str, dict[str, Any]] = hass.data.setdefault(CLE_DONNEES, {})
    maintenant = time.time()
    for ident in [i for i, d in demandes.items() if d["expire"] < maintenant and d["etat"] == "en_attente"]:
        demandes[ident]["etat"] = "expiree"
        persistent_notification.async_dismiss(hass, f"{DOMAIN}_appairage_{demandes[ident]['code']}")
    # Une réponse finale (validée, refusée, expirée) ne se garde pas au-delà d'une minute de plus.
    for ident in [i for i, d in demandes.items() if d["expire"] + 60 < maintenant]:
        demandes.pop(ident)
    return demandes


def par_code(hass: HomeAssistant, code: str) -> dict[str, Any] | None:
    code = str(code or "").strip().upper().replace("-", "").replace(" ", "")
    for d in _demandes(hass).values():
        if d["code"] == code and d["etat"] == "en_attente":
            return d
    return None


def _existant(coord, demande: dict[str, Any]) -> str | None:
    """La fiche que cette demande reprendrait (même appareil), sans rien y écrire."""
    store = coord.store
    if demande["uid"] and (pc_id := store.by_uid(demande["uid"])):
        return pc_id
    pc_id = store.by_host(demande["host"])
    if pc_id and (not demande["uid"] or not store.pcs[pc_id].get("uid")):
        return pc_id
    return None


def public(coord, demande: dict[str, Any]) -> dict[str, Any]:
    """Ce qu'un administrateur voit d'une demande : jamais le jeton d'attente."""
    existant = _existant(coord, demande) if coord is not None else None
    return {
        "code": demande["code"],
        "nom": demande["nom"] or demande["host"],
        "host": demande["host"],
        "plateforme": demande["platform"],
        "adresse": demande["adresse"],
        "depuis": int(demande["cree"]),
        "expire": int(demande["expire"]),
        "existant": coord.store.pcs[existant]["name"] if existant else None,
    }


def _verifier_personne(hass: HomeAssistant, personne: str) -> None:
    if personne and (not personne.startswith("person.") or hass.states.get(personne) is None):
        raise textes.Erreur("init.personne_introuvable", nom=personne)


async def _attribuer(hass: HomeAssistant, coord, pc_id: str | None, appareil: dict[str, Any], personne: str) -> dict[str, Any]:
    """La fiche (créée, ou reprise) et son secret, toujours neuf : celui d'une fiche reprise ne sort jamais."""
    store = coord.store
    cree = pc_id is None
    if cree:
        pc_id = store.nouvelle_fiche(appareil["host"], appareil["nom"], appareil["uid"], appareil["user"])
    pc = store.pcs[pc_id]
    pc["secret"] = secrets.token_hex(16)
    if appareil["uid"] and not pc.get("uid"):
        pc["uid"] = appareil["uid"]
    pc["platform"] = appareil["platform"]
    if personne:
        pc["person"] = personne
        pc.pop("personne_proposee", None)
    await store.async_save()
    if cree:
        hass.config_entries.async_schedule_reload(coord.config_entry.entry_id)
    else:
        await coord.async_refresh()
    _LOGGER.info("%s : appairage de « %s » (%s).", PRODUIT, pc["name"], "nouveau" if cree else "repris")
    return {"id": pc_id, "secret": pc["secret"], "name": pc["name"], "poll": POLL_SECONDS, "created": cree}


async def valider(hass: HomeAssistant, code: str, personne: str = "") -> dict[str, Any]:
    """Un administrateur valide : la fiche est créée (ou reprise avec un secret neuf)."""
    coord = _coordinateur(hass)
    demande = par_code(hass, code)
    if coord is None or demande is None:
        raise textes.Erreur("app.code_inconnu")
    _verifier_personne(hass, personne)
    reponse = await _attribuer(hass, coord, _existant(coord, demande), demande, personne)
    demande.update(etat="validee", reponse=reponse)
    persistent_notification.async_dismiss(hass, f"{DOMAIN}_appairage_{demande['code']}")
    return {"id": reponse["id"], "name": reponse["name"], "created": reponse["created"]}


# ------------------------------------------------- Dans l'autre sens : le QR vient de Home Assistant


def _invitations(hass: HomeAssistant) -> dict[str, dict[str, Any]]:
    invitations: dict[str, dict[str, Any]] = hass.data.setdefault(f"{CLE_DONNEES}_invitations", {})
    maintenant = time.time()
    for ident in [i for i, inv in invitations.items() if inv["expire"] < maintenant]:
        invitations.pop(ident)
    return invitations


def _resultats(hass: HomeAssistant) -> dict[str, dict[str, Any]]:
    """Ce qu'est devenue chaque invitation (relié, ou déjà inscrit) : en mémoire, 30 min."""
    resultats: dict[str, dict[str, Any]] = hass.data.setdefault(f"{CLE_DONNEES}_resultats", {})
    limite = time.time() - 1800
    for ident in [i for i, r in resultats.items() if r["quand"] < limite]:
        resultats.pop(ident)
    return resultats


def resultat(hass: HomeAssistant, ident: str) -> dict[str, Any] | None:
    """{etat: relie, id, name, created} | {etat: deja_inscrit, existant} | {etat: en_attente} | None (expirée)."""
    if r := _resultats(hass).get(ident):
        return r
    return {"etat": "en_attente"} if ident in _invitations(hass) else None


def adresses(hass: HomeAssistant, request: web.Request | None = None) -> tuple[str, str]:
    """(adresse, locale) : l'adresse de Home Assistant à retenir, et celle du réseau de la maison."""
    externe = str(hass.config.external_url or "").rstrip("/")
    interne = str(hass.config.internal_url or "").rstrip("/")
    vue = _adresse(request) if request is not None else ""
    adresse = externe or interne or vue
    locale = interne if interne and interne != adresse else ""
    return adresse, locale


def contenu_qr(adresse: str, locale: str, jeton: str) -> str:
    """Le texte du QR, et rien d'autre : l'adresse et un jeton à usage unique."""
    texte = f"vision://appairage?v=1&adresse={quote(adresse, safe='')}"
    if locale:
        texte += f"&locale={quote(locale, safe='')}"
    return f"{texte}&jeton={jeton}"


def qr_svg(texte: str) -> bytes:
    import pyqrcode

    tampon = io.BytesIO()
    pyqrcode.create(texte, error="M").svg(
        tampon, scale=6, quiet_zone=4, background="#ffffff", module_color="#000000", xmldecl=False, svgns=True,
    )
    return tampon.getvalue()


def creer_invitation(hass: HomeAssistant, adresse: str, locale: str, personne: str = "", appareil: str = "") -> dict[str, Any]:
    """Un QR d'appairage : un jeton neuf, à usage unique, valable quelques minutes."""
    coord = _coordinateur(hass)
    if coord is None:
        raise textes.Erreur("commun.pas_charge", produit=PRODUIT)
    _verifier_personne(hass, personne)
    if appareil and appareil not in coord.store.pcs:
        raise textes.Erreur("commun.appareil_inconnu")
    if not adresse:
        raise textes.Erreur("app.adresse_inconnue")
    invitations = _invitations(hass)
    if len(invitations) >= MAX_INVITATIONS:
        raise textes.Erreur("app.trop_qr", n=MAX_INVITATIONS)
    ident = secrets.token_hex(8)
    jeton = secrets.token_urlsafe(24)
    while jeton.startswith("-"):  # passé tel quel à PowerShell (-Jeton) : jamais pris pour un paramètre
        jeton = secrets.token_urlsafe(24)
    invitation = {
        "id": ident, "jeton": jeton, "personne": personne, "appareil": appareil,
        "expire": time.time() + APPAIRAGE_MINUTES * 60, "qr": contenu_qr(adresse, locale, jeton),
    }
    invitations[ident] = invitation
    return invitation


def chemin_qr(ident: str) -> str:
    return f"/api/pc_parental/appairage/invitation/{ident}/qr.svg"


def refuser(hass: HomeAssistant, code: str) -> None:
    demande = par_code(hass, code)
    if demande is None:
        raise textes.Erreur("app.code_inconnu")
    demande["etat"] = "refusee"
    persistent_notification.async_dismiss(hass, f"{DOMAIN}_appairage_{demande['code']}")


def _adresse(request: web.Request) -> str:
    hote = request.headers.get("Host") or request.host
    return f"{request.scheme}://{hote}".rstrip("/") if hote else ""


class PcParentalAppairageDemandeView(HomeAssistantView):
    """Un appareil demande à être inscrit. Il ne reçoit rien qui ouvre quoi que ce soit."""

    url = "/api/pc_parental/appairage/demande"
    name = "api:pc_parental:appairage_demande"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        if _coordinateur(self.hass) is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        host = str(corps.get("host") or "").strip()[:64]
        if not host:
            return self.json({"ok": False, "error": "host"}, status_code=400)
        demandes = _demandes(self.hass)
        adresse = str(request.remote or "")
        from . import appairage_decideur as _dec

        en_cours = [d for d in demandes.values() if d["etat"] == "en_attente"]
        # 2.4.7 : une nouvelle demande du même appareil remplace l'ancienne : elle ne compte pas dans la limite.
        meme = [d for d in en_cours if (corps.get("uid") and d["uid"] == str(corps.get("uid"))[:64])
                or (d["host"] == host and d["adresse"] == adresse)]
        en_cours = [d for d in en_cours if d not in meme]
        trop = len(en_cours) >= MAX_DEMANDES or sum(d["adresse"] == adresse for d in en_cours) >= MAX_PAR_ADRESSE
        if trop or _dec.trop_de_demandes(self.hass, adresse):
            return self.json({"ok": False, "error": "trop"}, status_code=429)
        codes = {d["code"] for d in demandes.values()}
        code = "".join(secrets.choice(ALPHABET) for _ in range(LONGUEUR_CODE))
        while code in codes:
            code = "".join(secrets.choice(ALPHABET) for _ in range(LONGUEUR_CODE))
        maintenant = time.time()
        ident = secrets.token_hex(8)
        demande = {
            "id": ident, "code": code, "attente": secrets.token_hex(16), "etat": "en_attente",
            "host": host, "nom": str(corps.get("nom") or "").strip()[:64], "uid": str(corps.get("uid") or "")[:64],
            "platform": str(corps.get("platform") or "")[:16], "user": str(corps.get("user") or "")[:64],
            "genre": "tele" if corps.get("genre") == "tele" else "",
            "adresse": adresse, "cree": maintenant, "expire": maintenant + DEMANDE_MINUTES * 60,
        }
        demandes[ident] = demande
        _dec.remplacer_anciennes(self.hass, demande)
        _dec.noter_demande(self.hass, adresse)
        # 2.4.7 (l'auteur : aucun code à l'écran) : la demande part sur les appareils des {decideur}s.
        coord = _coordinateur(self.hass)
        decideurs = _dec.notifier(self.hass, coord, demande)
        if decideurs:
            await coord.store.async_save()
        chemin = f"/{PANNEAU}?code={code}"
        langue = textes.ha(self.hass)
        persistent_notification.async_create(
            self.hass,
            textes.t("app.notif_demande", langue, nom=demande['nom'] or host, produit=PRODUIT, code=code, chemin=chemin,
                     minutes=DEMANDE_MINUTES),
            title=textes.t("app.titre_demande", langue),
            notification_id=f"{DOMAIN}_appairage_{code}",
        )
        # Pour une notification actionnable sur le téléphone d'un parent (automatisation).
        self.hass.bus.async_fire(f"{DOMAIN}_appairage_demande", {
            "code": code, "nom": demande["nom"] or host, "plateforme": demande["platform"], "chemin": chemin,
        })
        return self.json({
            "ok": True,
            "demande": ident,
            "attente": demande["attente"],
            "code": code,
            # Le QR : un lien profond de l'appli Home Assistant, rien d'autre que le code.
            "qr": f"homeassistant://navigate{chemin}",
            "lien": f"{_adresse(request)}{chemin}",
            "expire_dans": DEMANDE_MINUTES * 60,
            "intervalle": INTERVALLE_S,
            # 2.4.7 : combien d'appareils de {decideur} ont reçu la demande (0 : l'appareil propose « Autre méthode »).
            "decideurs": decideurs,
            "nom": demande["nom"] or host,
        })


class PcParentalAppairageAttenteView(HomeAssistantView):
    """L'appareil attend la décision ; il reçoit son secret une fois, puis plus jamais."""

    url = "/api/pc_parental/appairage/attente"
    name = "api:pc_parental:appairage_attente"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        demande = _demandes(self.hass).get(str(corps.get("demande") or ""))
        if demande is None or not secrets.compare_digest(demande["attente"], str(corps.get("attente") or "")):
            return self.json({"ok": False, "error": "inconnue"}, status_code=404)
        etat = demande["etat"]
        if etat == "en_attente":
            return self.json({"ok": True, "etat": etat, "expire_dans": max(0, int(demande["expire"] - time.time()))})
        # Une réponse finale ne se lit qu'une fois : le secret ne ressort jamais.
        _demandes(self.hass).pop(demande["id"], None)
        if etat == "validee":
            return self.json({"ok": True, "etat": etat, **demande["reponse"]})
        return self.json({"ok": True, "etat": etat})


class PcParentalAppairageAdminView(HomeAssistantView):
    """Les demandes en cours, et la décision : un administrateur, rien d'autre."""

    url = "/api/pc_parental/appairage"
    name = "api:pc_parental:appairage"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _refus(self, request: web.Request) -> web.Response | None:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        return None

    def _liste(self) -> dict[str, Any]:
        coord = _coordinateur(self.hass)
        personnes = sorted(
            ({"id": s.entity_id, "nom": s.name} for s in self.hass.states.async_all("person")),
            key=lambda p: p["nom"].lower(),
        )
        return {
            "ok": True,
            "demandes": [public(coord, d) for d in _demandes(self.hass).values() if d["etat"] == "en_attente"],
            "personnes": personnes,
            "appareils": sorted(
                ({"id": i, "nom": str(pc.get("name") or i)} for i, pc in (coord.store.pcs.items() if coord else [])),
                key=lambda a: a["nom"].lower(),
            ),
        }

    async def get(self, request: web.Request) -> web.Response:
        if refus := self._refus(request):
            return refus
        return self.json(self._liste())

    async def post(self, request: web.Request) -> web.Response:
        if refus := self._refus(request):
            return refus
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        # FAILLE DE SÉCURITÉ (l'auteur, 16/10) : valider un appareil l'admet dans le Lieu : code de déverrouillage à chaque fois.
        if corps.get("action") != "refuser":
            from . import octroi

            entrees = list(self.hass.data.get(DOMAIN, {}).values())
            if not entrees:
                return self.json({"ok": False, "error": "loading"}, status_code=503)
            if (refus_code := await octroi.exiger(self.hass, entrees[0], request, corps, True)) is not None:
                return self.json(refus_code, status_code=403)
        try:
            if corps.get("action") == "refuser":
                refuser(self.hass, str(corps.get("code") or ""))
                fait: dict[str, Any] = {"refusee": True}
            else:
                fait = await valider(self.hass, str(corps.get("code") or ""), str(corps.get("personne") or ""))
        except ValueError as err:
            return self.json({"ok": False, "error": str(err)}, status_code=404)
        return self.json({**self._liste(), "fait": fait})


class PcParentalAppairageInvitationView(HomeAssistantView):
    """Générer un QR d'appairage : un administrateur seulement."""

    url = "/api/pc_parental/appairage/invitation"
    name = "api:pc_parental:appairage_invitation"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            corps = {}
        # FAILLE DE SÉCURITÉ (l'auteur, 16/10) : un QR d'appairage relie un appareil tout seul : code de déverrouillage à chaque fois.
        from . import octroi

        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        if not entrees:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        if (refus_code := await octroi.exiger(self.hass, entrees[0], request, corps, True)) is not None:
            return self.json(refus_code, status_code=403)
        try:
            inv = creer_invitation(self.hass, *adresses(self.hass, request),
                                   str(corps.get("personne") or ""), str(corps.get("appareil") or ""))
        except ValueError as err:
            return self.json({"ok": False, "error": str(err)}, status_code=400)
        svg = await self.hass.async_add_executor_job(qr_svg, inv["qr"])
        return self.json({
            "ok": True, "id": inv["id"], "qr": inv["qr"], "svg": svg.decode("utf-8"),
            "expire_dans": int(inv["expire"] - time.time()),
        })


class PcParentalAppairageInvitationQrView(HomeAssistantView):
    """L'image du QR, pour Vision → Configurer (chemin signé : un administrateur, quelques minutes)."""

    url = "/api/pc_parental/appairage/invitation/{ident}/qr.svg"
    name = "api:pc_parental:appairage_invitation_qr"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request, ident: str) -> web.Response:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return web.Response(status=403)
        inv = _invitations(self.hass).get(ident)
        if inv is None:
            return web.Response(status=404, text=textes.t("app.qr_expire_utilise", textes.ha(self.hass)))
        svg = await self.hass.async_add_executor_job(qr_svg, inv["qr"])
        return web.Response(body=svg, content_type="image/svg+xml", headers={"Cache-Control": "no-store"})


class PcParentalAppairageJetonView(HomeAssistantView):
    """L'appareil a scanné le QR : il présente le jeton et reçoit son accès, une fois."""

    url = "/api/pc_parental/appairage/jeton"
    name = "api:pc_parental:appairage_jeton"
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
        jeton = str(corps.get("jeton") or "")
        inv = next((i for i in _invitations(self.hass).values() if secrets.compare_digest(i["jeton"], jeton)), None)
        if inv is None:
            return self.json({"ok": False, "error": "jeton"}, status_code=404)
        appareil = {
            "host": str(corps.get("host") or "").strip()[:64], "nom": str(corps.get("nom") or "").strip()[:64],
            "uid": str(corps.get("uid") or "")[:64], "platform": str(corps.get("platform") or "")[:16],
            "user": str(corps.get("user") or "")[:64],
        }
        if not appareil["host"]:
            return self.json({"ok": False, "error": "host"}, status_code=400)
        pc_id = inv["appareil"] or None
        if pc_id is None and (existant := _existant(coord, appareil)):
            # Un QR n'ouvre jamais la fiche d'un autre appareil : pour reprendre celui-ci,
            # l'administrateur génère un QR en le choisissant. Le jeton reste valable.
            _resultats(self.hass)[inv["id"]] = {"etat": "deja_inscrit", "existant": existant, "quand": time.time()}
            return self.json({"ok": False, "error": "deja_inscrit"}, status_code=409)
        # Consommé avant toute écriture : deux appareils ne peuvent pas s'en servir.
        _invitations(self.hass).pop(inv["id"], None)
        if pc_id is not None and pc_id not in coord.store.pcs:
            return self.json({"ok": False, "error": "jeton"}, status_code=404)
        reponse = await _attribuer(self.hass, coord, pc_id, appareil, inv["personne"])
        _resultats(self.hass)[inv["id"]] = {"etat": "relie", "id": reponse["id"], "name": reponse["name"],
                                            "created": reponse["created"], "quand": time.time()}
        langue = textes.ha(self.hass)
        persistent_notification.async_create(
            self.hass,
            textes.t("app.relie" if reponse["created"] else "app.relie_repris", langue, nom=reponse['name'], produit=PRODUIT),
            title=textes.t("app.titre_appaire", langue),
            notification_id=f"{DOMAIN}_appairage_qr_{reponse['id']}",
        )
        return self.json({"ok": True, **reponse})


def enregistrer_services(hass: HomeAssistant) -> None:
    from .entity import service_admin

    async def _valider(call: ServiceCall) -> None:
        try:
            await valider(hass, call.data["code"], call.data.get("personne", ""))
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err

    async def _refuser(call: ServiceCall) -> None:
        try:
            refuser(hass, call.data["code"])
        except ValueError as err:
            raise HomeAssistantError(str(err)) from err

    service_admin(hass, "valider_appairage", _valider, schema=vol.Schema({
        vol.Required("code"): cv.string, vol.Optional("personne", default=""): cv.string,
    }))
    service_admin(hass, "refuser_appairage", _refuser, schema=vol.Schema({vol.Required("code"): cv.string}))


async def async_installer(hass: HomeAssistant) -> None:
    """Les vues, et le panneau caché où un administrateur valide (lien du QR)."""
    if not hass.data.get(f"{CLE_DONNEES}_installe"):
        hass.data[f"{CLE_DONNEES}_installe"] = True
        hass.http.register_view(PcParentalAppairageDemandeView(hass))
        hass.http.register_view(PcParentalAppairageAttenteView(hass))
        hass.http.register_view(PcParentalAppairageAdminView(hass))
        hass.http.register_view(PcParentalAppairageInvitationView(hass))
        hass.http.register_view(PcParentalAppairageInvitationQrView(hass))
        hass.http.register_view(PcParentalAppairageJetonView(hass))
        from . import appairage_decideur as _dec

        hass.http.register_view(_dec.ListeView(hass))
        hass.http.register_view(_dec.DecisionView(hass))
        try:
            from homeassistant.components.http import StaticPathConfig

            # Le panneau vient avec l'intégration : rien à poser dans /config/www.
            await hass.http.async_register_static_paths([
                StaticPathConfig(STATIQUE, DOSSIER_PANNEAU, cache_headers=False),
            ])
        except Exception:  # noqa: BLE001
            _LOGGER.exception("Panneau d'appairage")
    try:
        from homeassistant.components import frontend

        from . import cartes

        # 2.2.13 : version = empreinte du fichier (et de ses imports), relue à chaque mise en route de
        # l'intégration ; avant : la version de Vision, figée jusqu'au redémarrage de Home Assistant.
        versions = await hass.async_add_executor_job(cartes.versions, DOSSIER_PANNEAU)
        module = cartes.adresse_versionnee(STATIQUE, "vision-appairage-panel.js", versions.get("vision-appairage-panel.js", "0"))
        deja = hass.data.get(frontend.DATA_PANELS, {}).get(PANNEAU)
        if deja is None or ((getattr(deja, "config", None) or {}).get("_panel_custom") or {}).get("module_url") != module:
            frontend.async_register_built_in_panel(
                hass,
                component_name="custom",
                frontend_url_path=PANNEAU,
                config={"_panel_custom": {
                    "name": "vision-appairage-panel",
                    "module_url": module,
                    "embed_iframe": False, "trust_external": False,
                }},
                require_admin=True,
                show_in_sidebar=False,
                update=deja is not None,
            )
    except Exception:  # noqa: BLE001 - sans panneau, Vision → Configurer et le service restent
        _LOGGER.exception("Panneau d'appairage")
