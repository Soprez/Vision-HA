"""Déverrouillage à distance : une demande de l'enfant, une approbation signée par Home Assistant.

Contrat complet : docs/deverrouillage.md. En bref :
- l'appareil enfant demande une action (« app:<paquet> », « reglages », « parent ») pour N minutes,
  avec un défi aléatoire qu'il garde en mémoire ;
- un appareil « Parents » ou un administrateur décide, et choisit la remise : directe (relevé),
  code (6 chiffres, vérifié ici) ou QR (vérifié hors ligne par l'appareil) ;
- Home Assistant signe (ECDSA P-256) l'appareil, la demande, l'action, la durée, l'expiration et
  le défi ; le défi n'est jamais dans le QR, un QR photographié ne sert donc qu'à son appareil,
  pour sa demande, une fois ;
- l'appareil applique puis fait consommer l'approbation : c'est là qu'on journalise.

Les demandes vivent en mémoire ; seule la clé de signature (et l'option biométrie) est gardée.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import logging
import re
import secrets
import time
from typing import Any
from urllib.parse import quote

import voluptuous as vol
from aiohttp import web
from homeassistant.components import persistent_notification
from homeassistant.components.http import HomeAssistantView
from homeassistant.components.switch import SwitchEntity
from homeassistant.core import HomeAssistant, ServiceCall, SupportsResponse
from homeassistant.exceptions import HomeAssistantError, Unauthorized
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.storage import Store

from . import labels
from . import modules
from . import roles
from . import textes
from .const import DOMAIN, PRODUIT, PRODUIT_AFFICHE

_LOGGER = logging.getLogger(__name__)

STOCKAGE = f"{DOMAIN}.deverrouillage"
DONNEES = f"{DOMAIN}_deverrouillage"

DEMANDE_S = 10 * 60
APPROBATION_S = {"qr": 90, "code": 5 * 60, "directe": 10 * 60}
REMISES = tuple(APPROBATION_S)
MINUTES_MAX = {"app": 240, "reglages": 30, "parent": 60}
DEMANDES_PAR_HEURE = 10
ESSAIS_PAR_DEMANDE = 5
CODES_FAUX_PAR_HEURE = 10
RE_ACTION = re.compile(r"^(app:[A-Za-z0-9._]{1,100}|reglages|parent)$")
RE_DEFI = re.compile(r"^[A-Za-z0-9_-]{22}$")
ENTETE = "vision-deverrouillage"
VERSION_SIGNATURE = "1"
# 2.5.12 (Déverrouillage simplifié) : un déverrouillage à l'initiative d'un Décideur, sans demande de l'appareil.
# Version de signature à part (« 2 ») : une approbation de demande ne passe jamais pour une initiative, ni l'inverse.
VERSION_INITIATIVE = "2"
DEFI_INITIATIVE = "initiative"
INITIATIVE_MINUTES = (15, 30, 60)
INITIATIVE_S = 10 * 60  # le temps de la remettre à l'appareil (relevé) ; la durée choisie part de son application


def _b64url(octets: bytes) -> str:
    return base64.urlsafe_b64encode(octets).rstrip(b"=").decode("ascii")


# ----------------------------------------------------------------------------- La clé


def _charger_pem(pem: str):
    """La clé privée d'un PEM. Import de `cryptography` et calcul : à lancer dans l'executor (2.5.12.14.2, revue de HACS)."""
    from cryptography.hazmat.primitives import serialization

    return serialization.load_pem_private_key(pem.encode("ascii"), password=None)


def _generer_cle():
    """Une clé ECDSA P-256 neuve, dans l'executor."""
    from cryptography.hazmat.primitives.asymmetric import ec

    return ec.generate_private_key(ec.SECP256R1())


def _publique_de(privee) -> tuple[str, str]:
    """(pub, k) de la clé privée : la clé publique DER en base64, et son empreinte courte."""
    from cryptography.hazmat.primitives import serialization

    der = privee.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return base64.b64encode(der).decode("ascii"), hashlib.sha256(der).hexdigest()[:16]


def _pem_de(privee) -> str:
    from cryptography.hazmat.primitives import serialization

    return privee.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode("ascii")


class Cle:
    """La clé de signature de cette maison (ECDSA P-256), et l'option biométrie."""

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass
        self._store: Store = Store(hass, 1, STOCKAGE)
        self._privee = None
        self.k = ""
        self.pub = ""
        self.biometrie = False

    async def async_load(self) -> None:
        lu = await self._store.async_load() or {}
        self.biometrie = bool(lu.get("biometrie", False))
        if lu.get("privee"):
            await self._poser(await self._hass.async_add_executor_job(_charger_pem, lu["privee"]))
        else:
            await self.async_tourner()

    async def _poser(self, privee) -> None:
        self._privee = privee
        self.pub, self.k = await self._hass.async_add_executor_job(_publique_de, privee)

    async def async_tourner(self) -> None:
        """Une clé neuve : les approbations signées avec l'ancienne ne valent plus. Générée dans l'executor."""
        await self._poser(await self._hass.async_add_executor_job(_generer_cle))
        await self.async_save()

    async def async_save(self) -> None:
        pem = await self._hass.async_add_executor_job(_pem_de, self._privee)
        await self._store.async_save({"privee": pem, "biometrie": self.biometrie})

    def publique(self) -> dict[str, str]:
        return {"k": self.k, "pub": self.pub}

    def signer(self, message: bytes) -> str:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec

        return _b64url(self._privee.sign(message, ec.ECDSA(hashes.SHA256())))


def message(appareil: str, demande: str, action: str, minutes: int, expire: int, defi: str,
            version: str = VERSION_SIGNATURE) -> bytes:
    """Le message signé : 8 lignes, dans cet ordre, sans retour final (docs/deverrouillage.md §4)."""
    return "\n".join(
        [ENTETE, version, appareil, demande, action, str(int(minutes)), str(int(expire)), defi]
    ).encode("utf-8")


# ----------------------------------------------------------------------------- L'état


def _etat(hass: HomeAssistant) -> dict[str, Any]:
    return hass.data.setdefault(DONNEES, {"demandes": {}, "creations": {}, "codes_faux": {}, "cle": None})


def cle(hass: HomeAssistant) -> Cle:
    return _etat(hass)["cle"]


def _coordinateur(hass: HomeAssistant):
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


def _recents(liste: list[float], fenetre: float = 3600) -> list[float]:
    limite = time.time() - fenetre
    liste[:] = [t for t in liste if t > limite]
    return liste


def _demandes(hass: HomeAssistant) -> dict[str, dict[str, Any]]:
    """Les demandes ; ce qui a expiré passe à « expiree », et on oublie au bout d'une heure."""
    demandes = _etat(hass)["demandes"]
    maintenant = time.time()
    for d in list(demandes.values()):
        if d["etat"] == "en_attente" and d["expire"] < maintenant:
            d["etat"] = "expiree"
            persistent_notification.async_dismiss(hass, f"{DOMAIN}_deverrouillage_{d['id']}")
        elif d["etat"] == "en_attente" and _repli_atteint(d) and (coord := _coordinateur(hass)) is not None:
            _signer(hass, coord, d, repli=True)
        elif d["etat"] == "approuvee" and d["approbation"]["expire"] < maintenant:
            d["etat"] = "expiree"
        if d["cree"] + 3600 < maintenant:
            demandes.pop(d["id"], None)
    return demandes


def libelle(action: str, langue: str | None = None) -> str:
    """Ce qui est demandé, dit dans une phrase (« une pause de Vision ») ; sans langue : celle de HA."""
    if action == "parent":
        return textes.t("dev.libelle_parent", langue)
    if action == "reglages":
        return textes.t("dev.libelle_reglages", langue)
    return textes.t("dev.libelle_appli", langue, appli=action.split(':', 1)[1])


def _qui(hass: HomeAssistant, pc: dict[str, Any]) -> str:
    etat = hass.states.get(str(pc.get("person") or ""))
    return str(etat.name) if etat is not None and etat.name else ""


def est_parent(hass: HomeAssistant, coord, pc_id: str) -> bool:
    """Un appareil de rôle Parent (1.69.0, posé par un administrateur ; avant : l'étiquette « Parents »)."""
    return roles.a(coord.store, coord.store.pcs.get(pc_id), "approuver")


def public(hass: HomeAssistant, coord, d: dict[str, Any]) -> dict[str, Any]:
    """Ce qu'un parent voit d'une demande : jamais le défi."""
    pc = coord.store.pcs.get(d["pc"]) or {}
    sortie = {
        "demande": d["id"], "appareil": d["pc"], "nom": str(pc.get("name") or d["pc"]),
        "personne": _qui(hass, pc), "action": d["action"], "minutes": d["minutes"],
        "motif": d["motif"], "depuis": int(d["cree"]), "expire": int(d["expire"]),
    }
    if d.get("requis", 1) > 1 or d.get("voix"):
        sortie.update({"voix": len(d.get("voix") or {}), "requis": _requis_effectif(d),
                       "approuve_par": [v["par"] for v in (d.get("voix") or {}).values()],
                       "en_attente_de": en_attente_de(hass, coord, d)})
    return sortie


class Refus(Exception):
    """Une erreur du contrat : statut HTTP et code d'erreur."""

    def __init__(self, statut: int, erreur: str, **plus: Any) -> None:
        super().__init__(erreur)
        self.statut, self.erreur, self.plus = statut, erreur, plus


# ----------------------------------------------------------------------------- Les gestes


def creer(hass: HomeAssistant, coord, pc: dict[str, Any], corps: dict[str, Any]) -> dict[str, Any]:
    action = str(corps.get("action") or "")
    if not RE_ACTION.match(action):
        raise Refus(400, "action")
    try:
        minutes = int(corps.get("minutes"))
    except (TypeError, ValueError):
        raise Refus(400, "minutes") from None
    if not 1 <= minutes <= MINUTES_MAX[action.split(":", 1)[0]]:
        raise Refus(400, "minutes")
    if action == "parent" and modules.coupe(coord.store, "pause"):
        # 1.82.0 : « une pause de Vision » demandée à distance : sans le module « Pause », refusée.
        raise Refus(403, "module", module="pause")
    defi = str(corps.get("defi") or "")
    if not RE_DEFI.match(defi) or len(base64.urlsafe_b64decode(defi + "==")) != 16:
        raise Refus(400, "defi")
    motif = str(corps.get("motif") or "").strip()
    if len(motif) > 140 or "\n" in motif:
        raise Refus(400, "motif")
    etat = _etat(hass)
    creations = _recents(etat["creations"].setdefault(pc["id"], []))
    if len(creations) >= DEMANDES_PAR_HEURE:
        raise Refus(429, "trop")
    creations.append(time.time())
    demandes = _demandes(hass)
    # Une seule demande en cours par appareil : la nouvelle annule l'ancienne (et son code, son QR).
    for ancienne in demandes.values():
        if ancienne["pc"] == pc["id"] and ancienne["etat"] in ("en_attente", "approuvee"):
            ancienne["etat"] = "annulee"
            persistent_notification.async_dismiss(hass, f"{DOMAIN}_deverrouillage_{ancienne['id']}")
    maintenant = time.time()
    d = {
        "id": secrets.token_hex(8), "pc": pc["id"], "action": action, "minutes": minutes, "defi": defi,
        "motif": motif, "cree": maintenant, "expire": maintenant + DEMANDE_S, "etat": "en_attente",
        "approbation": None, "code": None,
    }
    # Validation à plusieurs (docs/deverrouillage-quorum.md) : figée à la création.
    d["requis"], d["habilites"] = roles.requis(coord.store, pc, action, minutes)
    d["voix"] = {}
    repli = roles.validation(coord.store, pc)["repli_minutes"]
    d["repli"] = maintenant + repli * 60 if repli and d["requis"] > 1 else None
    demandes[d["id"]] = d
    nom, qui = str(pc.get("name") or pc["id"]), _qui(hass, pc)

    def texte(langue: str) -> str:
        return textes.t("dev.demande_motif" if motif else "dev.demande", langue, qui=qui or nom,
                        quoi=libelle(action, langue), minutes=minutes, motif=motif)

    from . import canaux

    for pid in roles.avec(coord.store, "approuver"):
        # 2.5.6 : dans l'appli Vision des Décideurs qui l'ont choisie (canaux.py).
        if pid == pc["id"] or not canaux.vision_pour(hass, coord.store, pid, "demandes"):
            continue
        langue_p = textes.pour(hass, coord.store.pcs.get(pid))
        try:
            msg = coord.store.add_message(pid, texte(langue_p), titre=textes.t("dev.titre_demande", langue_p))
        except ValueError:
            continue
        msg["deverrouillage"] = public(hass, coord, d)
    langue_ha = textes.ha(hass)
    persistent_notification.async_create(
        hass, texte(langue_ha) + textes.t("dev.notif_suite", langue_ha, chemin=textes.chemin_tableau(),
                                          lien=textes.t("notif.lien_tableau", langue_ha, produit=PRODUIT_AFFICHE)),
        title=textes.t("dev.titre_demande", langue_ha), notification_id=f"{DOMAIN}_deverrouillage_{d['id']}",
    )
    hass.bus.async_fire(f"{DOMAIN}_deverrouillage_demande", public(hass, coord, d))
    # Lot 2 : sur les téléphones des Décideurs (appli Compagnon), avec ses boutons.
    from . import a_decider
    hass.async_create_task(a_decider.notifier(hass, coord, "deverrouillage", d["pc"], d["id"], texte(langue_ha),
                                              int(d["minutes"])))
    return d


def votant_admin(hass: HomeAssistant, user_id: str | None) -> str:
    """La voix d'un administrateur qui décide depuis Home Assistant : la personne liée à son compte."""
    if not user_id:
        return "admin:?"
    for etat in hass.states.async_all("person"):
        if etat.attributes.get("user_id") == user_id:
            return etat.entity_id
    return f"admin:{user_id}"


def _nom_votant(hass: HomeAssistant, coord, v: str) -> str:
    if v.startswith("appareil:"):
        return str((coord.store.pcs.get(v.split(":", 1)[1]) or {}).get("name") or textes.t("dev.un_appareil", textes.ha(hass)))
    etat = hass.states.get(v) if v.startswith("person.") else None
    return str(etat.name) if etat is not None and etat.name else textes.t("commun.un_administrateur_min", textes.ha(hass))


def _repli_atteint(d: dict[str, Any]) -> bool:
    """Le repli : passé le délai, une voix déjà donnée suffit — si elle acceptait une remise directe.
    Un parent qui a choisi de remettre un code ou un QR en personne n'a pas voulu d'une remise directe :
    sa voix attend la suivante, ou l'expiration."""
    return bool(d.get("repli") and time.time() >= d["repli"]
                and any(v["remise"] == "directe" for v in (d.get("voix") or {}).values()))


def _requis_effectif(d: dict[str, Any]) -> int:
    return 1 if _repli_atteint(d) else int(d.get("requis") or 1)


def en_attente_de(hass: HomeAssistant, coord, d: dict[str, Any]) -> list[str]:
    return [_nom_votant(hass, coord, v) for v in d.get("habilites") or [] if v not in d.get("voix", {})]


def _compter_bilan(coord, d: dict[str, Any], quoi: str) -> None:
    """1.90.0 : le bilan de la semaine compte les déverrouillages accordés et refusés."""
    try:
        from . import bilan

        pc = coord.store.pcs.get(d.get("pc")) or {}
        bilan.compter(coord.store, str(pc.get("person") or ""), quoi)
    except Exception:  # noqa: BLE001 - un compteur de bilan ne bloque jamais une décision
        pass


def decider(hass: HomeAssistant, coord, demande: str, decision: str, minutes: Any, remise: str, par: str,
            voix: str | None = None) -> dict[str, Any]:
    """Une voix : refuser (refuse tout de suite) ou approuver ; on signe quand le quorum est atteint.
    Seuls les appelants déjà reconnus (rôle qui approuve, ou admins) arrivent ici."""
    d = _demandes(hass).get(str(demande or ""))
    if d is None or d["etat"] != "en_attente":
        raise Refus(404, "demande")
    voix = voix or f"par:{par}"
    if decision == "refuser":
        d["etat"] = "refusee"
        _compter_bilan(coord, d, "refusee")
        persistent_notification.async_dismiss(hass, f"{DOMAIN}_deverrouillage_{d['id']}")
        _LOGGER.warning("%s : déverrouillage refusé par %s (%s, %s).", PRODUIT, par, _nom(coord, d), d["action"])
        return {"ok": True, "etat": "refusee"}
    if decision != "approuver":
        raise Refus(400, "decision")
    remise = remise or "directe"
    if remise not in REMISES:
        raise Refus(400, "remise")
    if minutes in (None, ""):
        minutes = d["minutes"]
    try:
        minutes = int(minutes)
    except (TypeError, ValueError):
        raise Refus(400, "minutes") from None
    if not 1 <= minutes <= d["minutes"]:
        raise Refus(400, "minutes")  # raccourcir, jamais allonger
    d.setdefault("voix", {})
    if int(d.get("requis") or 1) > 1 and modules.coupe(coord.store, "double_validation"):
        # 1.82.0 : « Double validation » coupée après la demande : une seule voix suffit désormais.
        d["requis"] = 1
    demandeur = roles.votant(coord.store.pcs.get(d["pc"]) or {"id": d["pc"]})
    if voix == demandeur:
        raise Refus(403, "parent")  # on ne valide pas sa propre demande, même d'un autre appareil
    if int(d.get("requis") or 1) > 1:
        # Plusieurs voix : une par personne habilitée au moment de la demande, rien d'autre.
        if not voix.startswith("person."):
            _LOGGER.warning("%s : voix de %s refusée pour la demande de « %s » : pas liée à une personne.",
                            PRODUIT, par, _nom(coord, d))
            raise Refus(403, "personne")
        if voix not in (d.get("habilites") or []):
            _LOGGER.warning("%s : voix de %s refusée pour la demande de « %s » : pas habilitée à la demande.",
                            PRODUIT, par, _nom(coord, d))
            raise Refus(403, "non_habilite")
    if voix in d["voix"]:
        raise Refus(409, "deja_vote")
    d["voix"][voix] = {"par": par, "minutes": minutes, "remise": remise, "t": time.time()}
    requis_ = _requis_effectif(d)
    if requis_ > 1 or d.get("requis", 1) > 1:
        _LOGGER.warning("%s : voix de %s pour la demande de « %s » (%s) : approuve (%d/%d).", PRODUIT, par,
                        _nom(coord, d), d["action"], len(d["voix"]), requis_)
        hass.bus.async_fire(f"{DOMAIN}_deverrouillage_voix", {
            "demande": d["id"], "appareil": d["pc"], "par": par, "voix": len(d["voix"]), "requis": requis_})
    if len(d["voix"]) < requis_:
        attente = en_attente_de(hass, coord, d)
        for pid in roles.avec(coord.store, "approuver"):
            pc_v = coord.store.pcs[pid]
            if pid == d["pc"] or roles.votant(pc_v) in d["voix"]:
                continue
            langue_v = textes.pour(hass, pc_v)
            texte = textes.t("dev.validation_attendue", langue_v, par=par, nom=_nom(coord, d),
                             quoi=libelle(d["action"], langue_v), voix=len(d["voix"]), requis=requis_)
            try:
                msg = coord.store.add_message(pid, texte, titre=textes.t("dev.titre_demande", langue_v))
            except ValueError:
                continue
            msg["deverrouillage"] = public(hass, coord, d)
        return {"ok": True, "etat": "partielle", "voix": len(d["voix"]), "requis": requis_, "en_attente_de": attente}
    return _signer(hass, coord, d)


def _signer(hass: HomeAssistant, coord, d: dict[str, Any], repli: bool = False) -> dict[str, Any]:
    """Quorum atteint : la remise de la dernière voix, la plus courte durée proposée (personne n'allonge)."""
    voix = sorted(d["voix"].values(), key=lambda x: x["t"])
    minutes = min(v["minutes"] for v in voix)
    remise = "directe" if repli else voix[-1]["remise"]
    par = " + ".join(v["par"] for v in voix)
    if repli:
        _LOGGER.warning("%s : repli sur la demande de « %s » : une seule validation suffit (%s).", PRODUIT,
                        _nom(coord, d), par)
    expire = int(time.time()) + APPROBATION_S[remise]
    signature = cle(hass).signer(message(d["pc"], d["id"], d["action"], minutes, expire, d["defi"]))
    d["etat"] = "approuvee"
    _compter_bilan(coord, d, "accordee")
    d["approbation"] = {
        "k": cle(hass).k, "appareil": d["pc"], "demande": d["id"], "action": d["action"], "minutes": minutes,
        "expire": expire, "signature": signature, "par": par, "remise": remise,
    }
    persistent_notification.async_dismiss(hass, f"{DOMAIN}_deverrouillage_{d['id']}")
    reponse = {"ok": True, "etat": "approuvee", "remise": remise, "minutes": minutes, "expire": expire}
    if remise == "code":
        code = f"{secrets.randbelow(10**6):06d}"
        sel = secrets.token_bytes(16)
        d["code"] = {"sel": sel, "empreinte": hashlib.sha256(sel + code.encode()).digest(), "essais": 0, "utilise": False}
        reponse["code"] = code
    elif remise == "qr":
        reponse["qr"] = qr(d["approbation"])
    return reponse


def initiative(hass: HomeAssistant, coord, appareil: str, minutes: Any, par: str, voix: str = "") -> dict[str, Any]:
    """2.5.12 (Déverrouillage simplifié) : un Décideur (son appli, le tableau, un service d'administrateur)
    déverrouille un appareil pour 15, 30 ou 60 min, sans demande de l'appareil. Accès total (« parent ») pendant la
    durée : réglages d'Android, installations, options pour les développeurs, plus aucun écran de blocage de Vision.

    L'approbation est signée en version [VERSION_INITIATIVE], liée à l'appareil et à un identifiant tiré ici (à
    usage unique : l'appareil le retient jusqu'à son expiration), remise par le relevé (« directe »). Tous les
    Décideurs sont prévenus ; l'application est journalisée à la consommation, comme une approbation de demande."""
    pc = coord.store.pcs.get(str(appareil or ""))
    if pc is None:
        raise Refus(404, "appareil")
    # Seule l'appli Android sait appliquer cette approbation (l'agent d'un PC ne la lirait jamais).
    if str(pc.get("platform") or "") != "android":
        raise Refus(400, "appareil", raison="android")
    try:
        minutes = int(minutes)
    except (TypeError, ValueError):
        raise Refus(400, "minutes") from None
    if minutes not in INITIATIVE_MINUTES:
        raise Refus(400, "minutes", minutes_permises=list(INITIATIVE_MINUTES))
    maintenant = time.time()
    demandes = _demandes(hass)
    # Une seule initiative en attente par appareil : la nouvelle remplace l'ancienne (jamais deux remises).
    for vieille in demandes.values():
        if vieille["pc"] == pc["id"] and vieille.get("initiative") and vieille["etat"] == "approuvee":
            vieille["etat"] = "annulee"
    ident = "init-" + secrets.token_urlsafe(12)
    expire = int(maintenant) + INITIATIVE_S
    signature = cle(hass).signer(message(pc["id"], ident, "parent", minutes, expire, DEFI_INITIATIVE, VERSION_INITIATIVE))
    d = {
        "id": ident, "pc": pc["id"], "action": "parent", "minutes": minutes, "motif": "", "defi": DEFI_INITIATIVE,
        "cree": maintenant, "expire": expire, "etat": "approuvee", "requis": 1, "repli": None, "code": None,
        "voix": {voix or par: {"par": par, "minutes": minutes, "remise": "directe", "t": maintenant}},
        "initiative": True,
        "approbation": {"k": cle(hass).k, "appareil": pc["id"], "demande": ident, "action": "parent",
                        "minutes": minutes, "expire": expire, "signature": signature, "par": par,
                        "remise": "directe", "version": VERSION_INITIATIVE},
    }
    demandes[ident] = d
    nom, qui = str(pc.get("name") or pc["id"]), _qui(hass, pc)
    _LOGGER.warning("%s : %s déverrouille « %s »%s pour %s min (à son initiative).", PRODUIT, par, nom,
                    f" ({qui})" if qui else "", minutes)
    hass.bus.async_fire("logbook_entry", {"name": PRODUIT, "domain": DOMAIN, "message": textes.t(
        "dev.initiative", textes.ha(hass), par=par, nom=nom, minutes=minutes)})
    hass.bus.async_fire(f"{DOMAIN}_deverrouillage_initiative", {"demande": ident, "appareil": pc["id"], "nom": nom,
                                                                 "personne": qui, "minutes": minutes, "par": par})
    _prevenir_parents(hass, coord, textes.Phrase("dev.initiative", par=par, nom=nom, minutes=minutes))
    return {"ok": True, "demande": ident, "appareil": pc["id"], "minutes": minutes, "expire": expire}


def annuler_initiative(hass: HomeAssistant, coord, appareil: str, par: str) -> dict[str, Any]:
    """2.5.12 : un Décideur annule une initiative pas encore remise (l'appareil, lui, se reverrouille par son bandeau)."""
    n = 0
    for d in _demandes(hass).values():
        if d["pc"] == appareil and d.get("initiative") and d["etat"] == "approuvee":
            d["etat"] = "annulee"
            n += 1
    if n:
        _LOGGER.warning("%s : %s annule le déverrouillage pas encore remis de « %s ».", PRODUIT, par,
                        _nom(coord, {"pc": appareil}))
    return {"ok": True, "annulees": n}


def qr(a: dict[str, Any]) -> str:
    """Le QR d'approbation : tout sauf le défi (docs/deverrouillage.md §3)."""
    return (
        f"vision://deverrouillage?v=1&k={a['k']}&p={quote(a['appareil'], safe='')}&d={a['demande']}"
        f"&a={quote(a['action'], safe='')}&m={a['minutes']}&x={a['expire']}&s={a['signature']}"
    )


def _a_lui(hass: HomeAssistant, pc: dict[str, Any], demande: str) -> dict[str, Any]:
    d = _demandes(hass).get(str(demande or ""))
    if d is None or d["pc"] != pc["id"]:
        raise Refus(404, "demande")
    return d


def etat_pour(hass: HomeAssistant, pc: dict[str, Any], demande: str) -> dict[str, Any]:
    d = _a_lui(hass, pc, demande)
    reponse: dict[str, Any] = {"ok": True, "etat": d["etat"]}
    if d["etat"] == "approuvee" and d["approbation"]["remise"] == "directe":
        reponse["approbation"] = d["approbation"]
    elif d["etat"] == "en_attente" and d.get("voix"):
        coord = _coordinateur(hass)
        reponse.update({"etat": "partielle", "voix": len(d["voix"]), "requis": _requis_effectif(d),
                        "en_attente_de": en_attente_de(hass, coord, d) if coord else []})
    return reponse


def verifier_code(hass: HomeAssistant, coord, pc: dict[str, Any], demande: str, code: str) -> dict[str, Any]:
    faux = _recents(_etat(hass)["codes_faux"].setdefault(pc["id"], []))
    if len(faux) >= CODES_FAUX_PAR_HEURE:
        raise Refus(429, "trop")
    d = _a_lui(hass, pc, demande)
    c = d["code"]
    if d["etat"] != "approuvee" or c is None or c["utilise"]:
        raise Refus(404, "demande")
    essai = hashlib.sha256(c["sel"] + str(code or "").strip().encode()).digest()
    if hmac.compare_digest(essai, c["empreinte"]):
        c["utilise"] = True
        return {"ok": True, "approbation": d["approbation"]}
    faux.append(time.time())
    c["essais"] += 1
    if c["essais"] >= ESSAIS_PAR_DEMANDE:
        d["etat"] = "bloquee"
        _LOGGER.warning("%s : %d codes faux sur « %s » : demande de déverrouillage bloquée.",
                        PRODUIT, c["essais"], _nom(coord, d))
        _prevenir_parents(hass, coord, textes.Phrase("dev.codes_faux", essais=c["essais"], nom=_nom(coord, d)))
        raise Refus(423, "bloquee")
    raise Refus(403, "code", essais_restants=ESSAIS_PAR_DEMANDE - c["essais"])


def consommer(hass: HomeAssistant, coord, pc: dict[str, Any], corps: dict[str, Any]) -> dict[str, Any]:
    hors_ligne = bool(corps.get("hors_ligne"))
    d = _demandes(hass).get(str(corps.get("demande") or ""))
    if d is None or d["pc"] != pc["id"]:
        if hors_ligne:
            _LOGGER.warning("%s : « %s » dit avoir appliqué hors ligne une approbation que Home Assistant "
                            "ne connaît plus (demande %s).", PRODUIT, pc.get("name"), str(corps.get("demande") or "?")[:32])
        raise Refus(404, "demande")
    if d["etat"] == "consommee":
        raise Refus(409, "deja")
    a = d["approbation"]
    # Appliquée hors ligne avec un QR : elle a pu expirer (ou être remplacée) depuis ; on le note.
    if a is None or not (d["etat"] == "approuvee" or (hors_ligne and a["remise"] == "qr")):
        raise Refus(404, "demande")
    if a["remise"] == "code" and not (d["code"] or {}).get("utilise"):
        raise Refus(404, "demande")  # un code jamais saisi n'a rien pu ouvrir
    try:
        applique = int(corps.get("applique") or time.time())
    except (TypeError, ValueError):
        applique = int(time.time())
    d["etat"] = "consommee"
    fin = applique + a["minutes"] * 60
    nom, qui = _nom(coord, d), _qui(hass, pc)
    texte = textes.Phrase("dev.journal", nom=nom, qui=f" ({qui})" if qui else "", action=d["action"],
                          minutes=a["minutes"], par=a["par"], remise=a["remise"])
    if hors_ligne:
        texte("dev.hors_ligne")
    _LOGGER.warning("%s : %s.", PRODUIT, texte.en("fr"))
    # Le journal de bord (logbook) écoute cet événement ; sans lui, la ligne WARNING reste.
    hass.bus.async_fire("logbook_entry", {"name": PRODUIT, "message": texte.en(textes.ha(hass)), "domain": DOMAIN})
    hass.bus.async_fire(f"{DOMAIN}_deverrouillage", {
        "demande": d["id"], "appareil": d["pc"], "nom": nom, "personne": qui, "action": d["action"],
        "minutes": a["minutes"], "par": a["par"], "remise": a["remise"], "hors_ligne": hors_ligne,
    })
    return {"ok": True, "fin": fin}


def _nom(coord, d: dict[str, Any]) -> str:
    return str((coord.store.pcs.get(d["pc"]) or {}).get("name") or d["pc"])


def _prevenir_parents(hass: HomeAssistant, coord, texte: textes.Phrase) -> None:
    """Une alerte de protection : par les canaux que chaque Décideur a choisis pour les alertes (2.5.6)."""
    from . import canaux

    langue_ha = textes.ha(hass)
    titre_ha = textes.t("dev.titre", langue_ha)
    hass.async_create_task(canaux.compagnon_simple(hass, coord.store, "alertes", titre_ha, texte.en(langue_ha)))
    hass.async_create_task(canaux.discord(hass, coord.store, "alertes", titre_ha, texte.en(langue_ha)))
    for pid in roles.avec(coord.store, "approuver"):
        if not canaux.vision_pour(hass, coord.store, pid, "alertes"):
            continue
        langue = textes.pour(hass, coord.store.pcs.get(pid))
        try:
            coord.store.add_message(pid, texte.en(langue), titre=textes.t("dev.titre", langue))
        except ValueError:
            continue


async def biometrie(hass: HomeAssistant, coord, pc: dict[str, Any]) -> bool:
    """Vrai seulement si la biométrie est cochée sur CET appareil et qu'il est de rôle Parent (1.69.0),
    jamais sur un appareil d'affichage."""
    if coord.store.partage_tous(pc) or coord.store.est_ecran_commun(str(pc.get("person") or "")):
        return False
    return roles.a(coord.store, pc, "biometrie")


async def pour_releve(hass: HomeAssistant, coord, pc: dict[str, Any]) -> dict[str, Any]:
    """Ce que le relevé porte : la clé publique, la biométrie, l'approbation directe en attente."""
    if cle(hass) is None:
        return {}
    sortie: dict[str, Any] = {"deverrouillage_cle": cle(hass).publique(), "biometrie": await biometrie(hass, coord, pc)}
    for d in _demandes(hass).values():
        if d["pc"] == pc["id"] and d["etat"] == "approuvee" and d["approbation"]["remise"] == "directe":
            sortie["deverrouillage"] = d["approbation"]
    return sortie


# ----------------------------------------------------------------------------- Les vues


class _VueAppareil(HomeAssistantView):
    """Les vues des appareils : identifiant et secret dans le corps, comme le relevé."""

    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None or cle(self.hass) is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.noter_langue(pc, corps)
        # 1.82.0 : module « Déverrouillage » coupé : toutes ses portes répondent 403 « module ».
        if refus := modules.refus(self, coord.store, "deverrouillage", langue=textes.pour(self.hass, pc)):
            return refus
        try:
            return self.json(await self.traiter(coord, pc, corps))
        except Refus as refus:
            return self.json({"ok": False, "error": refus.erreur, **refus.plus}, status_code=refus.statut)

    async def traiter(self, coord, pc: dict[str, Any], corps: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError


class DemandeView(_VueAppareil):
    url = "/api/pc_parental/deverrouillage/demande"
    name = "api:pc_parental:deverrouillage_demande"

    async def traiter(self, coord, pc, corps):
        d = creer(self.hass, coord, pc, corps)
        await coord.store.async_save()  # les messages aux parents
        return {"ok": True, "demande": d["id"], "expire": int(d["expire"]), "cle": cle(self.hass).publique()}


class EtatView(_VueAppareil):
    url = "/api/pc_parental/deverrouillage/etat"
    name = "api:pc_parental:deverrouillage_etat"

    async def traiter(self, coord, pc, corps):
        return etat_pour(self.hass, pc, corps.get("demande"))


class CodeView(_VueAppareil):
    url = "/api/pc_parental/deverrouillage/code"
    name = "api:pc_parental:deverrouillage_code"

    async def traiter(self, coord, pc, corps):
        return verifier_code(self.hass, coord, pc, corps.get("demande"), corps.get("code"))


class ConsommerView(_VueAppareil):
    url = "/api/pc_parental/deverrouillage/consommer"
    name = "api:pc_parental:deverrouillage_consommer"

    async def traiter(self, coord, pc, corps):
        return consommer(self.hass, coord, pc, corps)


def _parent(hass: HomeAssistant, coord, pc: dict[str, Any]) -> str:
    if not est_parent(hass, coord, pc["id"]):
        _LOGGER.warning("%s : « %s » a tenté de décider d'un déverrouillage sans avoir un rôle qui peut gérer.",
                        PRODUIT, pc.get("name"))
        raise Refus(403, "parent")
    return _qui(hass, pc) or str(pc.get("name") or textes.t("commun.un_gestionnaire", textes.pour(hass, pc)))


class DecisionView(_VueAppareil):
    url = "/api/pc_parental/deverrouillage/decision"
    name = "api:pc_parental:deverrouillage_decision"

    async def traiter(self, coord, pc, corps):
        par = _parent(self.hass, coord, pc)
        d = _demandes(self.hass).get(str(corps.get("demande") or ""))
        if d is not None and d["pc"] == pc["id"]:
            raise Refus(403, "parent")  # on ne décide pas pour soi-même
        reponse = decider(self.hass, coord, corps.get("demande"), str(corps.get("decision") or ""),
                          corps.get("minutes"), str(corps.get("remise") or ""), par, voix=roles.votant(pc))
        await coord.async_refresh()
        return reponse


class InitiativeView(_VueAppareil):
    """2.5.12 : l'appli d'un Décideur, Appareils › <appareil> › Déverrouiller 15/30/60 min (preuve exigée par
    l'appli à chaque fois : Octroi.kt)."""

    url = "/api/pc_parental/deverrouillage/initiative"
    name = "api:pc_parental:deverrouillage_initiative"

    async def traiter(self, coord, pc, corps):
        par = _parent(self.hass, coord, pc)
        cible = str(corps.get("appareil") or "")
        if cible == pc["id"]:
            raise Refus(403, "parent")  # on ne se déverrouille pas soi-même
        if str(corps.get("decision") or "deverrouiller") == "annuler":
            return annuler_initiative(self.hass, coord, cible, par)
        reponse = initiative(self.hass, coord, cible, corps.get("minutes"), par, voix=roles.votant(pc))
        await coord.async_refresh()
        return reponse


class ListeView(_VueAppareil):
    url = "/api/pc_parental/deverrouillage/liste"
    name = "api:pc_parental:deverrouillage_liste"

    async def traiter(self, coord, pc, corps):
        _parent(self.hass, coord, pc)
        moi = roles.votant(pc)
        return {"ok": True, "demandes": [{**public(self.hass, coord, d), "deja_vote": moi in (d.get("voix") or {})}
                                         for d in _demandes(self.hass).values()
                                         if d["etat"] == "en_attente" and d["pc"] != pc["id"]]}


class AdminView(HomeAssistantView):
    """Les demandes, et la décision, pour un administrateur de Home Assistant."""

    url = "/api/pc_parental/deverrouillage"
    name = "api:pc_parental:deverrouillage"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _refus(self, request: web.Request) -> web.Response | None:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        return None

    async def get(self, request: web.Request) -> web.Response:
        if refus := self._refus(request):
            return refus
        coord = _coordinateur(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        if refus := modules.refus(self, coord.store, "deverrouillage"):
            return refus
        return self.json({"ok": True, "demandes": [public(self.hass, coord, d) for d in _demandes(self.hass).values()
                                                   if d["etat"] == "en_attente"]})

    async def post(self, request: web.Request) -> web.Response:
        if refus := self._refus(request):
            return refus
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coordinateur(self.hass)
        if coord is None or cle(self.hass) is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        if refus := modules.refus(self, coord.store, "deverrouillage"):
            return refus
        if corps.get("initiative"):
            # 2.5.12 : déverrouiller depuis la carte, sans demande ; le code du Décideur est exigé comme pour accorder.
            from . import octroi
            if (refus_code := await octroi.exiger(self.hass, coord, request, corps, True)) is not None:
                return self.json(refus_code, status_code=403)
            try:
                reponse = initiative(self.hass, coord, str(corps["initiative"]), corps.get("minutes"),
                                     str(request["hass_user"].name or textes.t("commun.un_administrateur", textes.ha(self.hass))),
                                     voix=votant_admin(self.hass, request["hass_user"].id))
            except Refus as refus:
                return self.json({"ok": False, "error": refus.erreur, **refus.plus}, status_code=refus.statut)
            await coord.async_refresh()
            return self.json(reponse)
        try:
            reponse = decider(self.hass, coord, corps.get("demande"), str(corps.get("decision") or ""),
                              corps.get("minutes"), str(corps.get("remise") or ""),
                              str(request["hass_user"].name or textes.t("commun.un_administrateur", textes.ha(self.hass))),
                              voix=votant_admin(self.hass, request["hass_user"].id))
        except Refus as refus:
            return self.json({"ok": False, "error": refus.erreur, **refus.plus}, status_code=refus.statut)
        await coord.async_refresh()
        return self.json(reponse)



# ----------------------------------------------------------------------------- Mise en route


def _enregistrer_services(hass: HomeAssistant) -> None:
    from .entity import service_admin

    async def _par(call: ServiceCall) -> str:
        """Un administrateur identifié : pas une automatisation (docs/deverrouillage.md §2)."""
        store = modules.store_de(hass)
        if store is not None and modules.coupe(store, "deverrouillage"):
            raise HomeAssistantError(textes.t("commun.module_coupe", textes.ha(hass), module=modules.nom("deverrouillage", textes.ha(hass))))
        utilisateur = await hass.auth.async_get_user(call.context.user_id) if call.context.user_id else None
        if utilisateur is None or not utilisateur.is_admin:
            raise Unauthorized(context=call.context, permission="control")
        return str(utilisateur.name or textes.t("commun.un_administrateur", textes.ha(hass)))

    async def _approuver(call: ServiceCall):
        coord = _coordinateur(hass)
        try:
            reponse = decider(hass, coord, call.data["demande"], "approuver", call.data.get("minutes"),
                              call.data.get("remise", "directe"), await _par(call),
                              voix=votant_admin(hass, call.context.user_id))
        except Refus as refus:
            raise HomeAssistantError(textes.t("dev.erreur_service", textes.ha(hass), erreur=refus.erreur)) from refus
        await coord.async_refresh()
        return reponse

    async def _refuser(call: ServiceCall) -> None:
        coord = _coordinateur(hass)
        try:
            decider(hass, coord, call.data["demande"], "refuser", None, "", await _par(call),
                    voix=votant_admin(hass, call.context.user_id))
        except Refus as refus:
            raise HomeAssistantError(textes.t("dev.erreur_service", textes.ha(hass), erreur=refus.erreur)) from refus

    async def _tourner(call: ServiceCall) -> None:
        await cle(hass).async_tourner()
        for d in _demandes(hass).values():
            if d["etat"] == "approuvee":
                d["etat"] = "annulee"
        _LOGGER.warning("%s : nouvelle clé de déverrouillage (%s) ; les approbations en cours sont annulées.",
                        PRODUIT, cle(hass).k)

    service_admin(hass, "approuver_deverrouillage", _approuver, schema=vol.Schema({
        vol.Required("demande"): cv.string,
        vol.Optional("minutes"): vol.Coerce(int),
        vol.Optional("remise", default="directe"): vol.In(REMISES),
    }), supports_response=SupportsResponse.OPTIONAL)
    service_admin(hass, "refuser_deverrouillage", _refuser, schema=vol.Schema({vol.Required("demande"): cv.string}))
    service_admin(hass, "nouvelle_cle_deverrouillage", _tourner, schema=vol.Schema({}))

    async def _initiative(call: ServiceCall):
        coord = _coordinateur(hass)
        try:
            reponse = initiative(hass, coord, call.data["pc"], call.data.get("minutes", 15), await _par(call),
                                 voix=votant_admin(hass, call.context.user_id))
        except Refus as refus:
            raise HomeAssistantError(textes.t("dev.erreur_service", textes.ha(hass), erreur=refus.erreur)) from refus
        await coord.async_refresh()
        return reponse

    service_admin(hass, "deverrouiller_appareil", _initiative, schema=vol.Schema({
        vol.Required("pc"): cv.string,
        vol.Optional("minutes", default=15): vol.All(vol.Coerce(int), vol.In(INITIATIVE_MINUTES)),
    }), supports_response=SupportsResponse.OPTIONAL)


async def async_installer(hass: HomeAssistant) -> None:
    """La clé (créée au premier démarrage), les vues et les services, une seule fois."""
    etat = _etat(hass)
    if etat["cle"] is not None:
        return
    nouvelle = Cle(hass)
    await nouvelle.async_load()
    etat["cle"] = nouvelle
    for vue in (DemandeView, EtatView, CodeView, ConsommerView, DecisionView, InitiativeView, ListeView, AdminView):
        hass.http.register_view(vue(hass))
    _enregistrer_services(hass)
