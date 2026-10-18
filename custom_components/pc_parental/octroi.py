"""FAILLE DE SÉCURITÉ (l'auteur, 16/10) : accorder exige une preuve à CHAQUE geste, sans délai de grâce.

l'auteur a déverrouillé Alex depuis son téléphone de Décideur en touchant la demande, sans empreinte ni code. Règle :
toute action qui déverrouille ou accorde (accepter une demande, déverrouiller, prolonger, autoriser, ouvrir, valider
un appareil, donner un rôle ou du temps) exige à chaque fois l'empreinte ou le code, sur tous les chemins.

**Un seul code : le code de déverrouillage du Décideur** (décision du projet, 16/10 : « un superviseur est un
superviseur »). C'est celui qu'il utilise déjà dans l'appli Vision ; Home Assistant en garde l'empreinte, PAR
DÉCIDEUR (sa personne), et c'est elle que vérifient le tableau ET l'appli. L'empreinte digitale reste une alternative
locale, sur l'appareil.
- Migration : l'appli d'un Décideur qui a déjà un code envoie son empreinte locale (SHA-256 « vision:<code> ») ;
  Home Assistant la garde telle quelle, puis la remplace par une empreinte PBKDF2 la première fois que le code est
  tapé (il a alors le code en clair, le temps du calcul). Rien n'est redemandé à personne.
- Sans code nulle part : le Décideur le choisit une fois (appli, ou Configurer › Sécurité pour un administrateur).
- Essais faux limités : par compte ou appareil (5 par quart d'heure), et pour tout le Lieu (15 en 24 h : verrouillé
  jusqu'à ce qu'un code soit reposé).

Côté Home Assistant :
- les notifications de l'appli Compagnon ne portent plus que « Refuser » (a_decider, notifs_mobiles) ;
- le tableau de bord (cartes Vision, comptes administrateurs de HA) exige le code de déverrouillage à chaque geste qui
  accorde : celui de la personne liée au compte HA, sinon celui de n'importe quel Décideur. Sans aucun code, le
  tableau n'accorde rien ;
- l'appli : Octroi.kt (preuve à usage unique) ; le code tapé y est vérifié ici (POST /parent, action
  « code_deverrouillage »).

Les services de Home Assistant (automatisations, scripts) ne sont pas concernés : ce sont des réglages
d'administrateur, sans écran à prêter.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import time
from typing import Any

from homeassistant.core import HomeAssistant

from . import roles

RESTRICTIFS = frozenset({"fermer", "message", "concentration", "pause_generale"})
LECTURES = frozenset({"", "categorie", "recherche", "recents", "canaux"})
BLOCAGES = frozenset({"bloquer", "bloque", "non", "fermer"})
# La fiche d'une personne : donner un rôle (Décideur…) ou du temps gagné accorde ; une règle de lieu restreint.
OPS_FICHE = frozenset({"role", "temps_gagne"})

ESSAIS_FAUX_MAX = 5
FENETRE_S = 15 * 60
ECHECS_LIEU_MAX = 15
ECHECS_LIEU_S = 24 * 3600
DONNEES = "pc_parental_code_deverrouillage_essais"

# Le code de l'appli : au moins 4 chiffres (Garde) ; ici jusqu'à 32 caractères.
LONGUEUR_MIN, LONGUEUR_MAX = 4, 32
ITERATIONS = 210_000
FORMAT_PBKDF2 = "pbkdf2"
FORMAT_APPLI = "appli_sha256"  # l'empreinte locale de l'appli, migrée telle quelle, remplacée au premier usage


def est_octroi(corps: dict[str, Any]) -> bool:
    """Ce geste du tableau (POST /api/pc_parental/maison, même corps que l'appli) accorde-t-il quelque chose ?
    Même règle que l'appli (Octroi.kt) ; une action inconnue compte comme un octroi (prudence)."""
    action = str(corps.get("action") or "")
    decision = str(corps.get("decision") or corps.get("etat") or "")
    if action in LECTURES or action in RESTRICTIFS:
        return False
    if action == "acces":
        return decision != "non"
    if action in ("autoriser", "etiquette"):
        return decision not in BLOCAGES
    return True


# ----------------------------------------------------------------------------- Le code de déverrouillage


def nettoyer(code: Any) -> str:
    return str(code or "").strip()


def valide(code: str) -> bool:
    return LONGUEUR_MIN <= len(code) <= LONGUEUR_MAX


def empreinte_appli(code: str) -> str:
    """Ce que l'appli garde (Config.hacher) : SHA-256 de « vision:<code> », en hexadécimal."""
    return hashlib.sha256(("vision:" + code).encode("utf-8")).hexdigest()


def _pbkdf2(code: str, sel: bytes, iterations: int) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", code.encode("utf-8"), sel, iterations)


def fabriquer(code: str) -> dict[str, Any]:
    code = nettoyer(code)
    if not valide(code):
        raise ValueError("code")
    sel = secrets.token_bytes(16)
    return {"format": FORMAT_PBKDF2, "sel": base64.b64encode(sel).decode("ascii"),
            "empreinte": base64.b64encode(_pbkdf2(code, sel, ITERATIONS)).decode("ascii"),
            "iterations": ITERATIONS, "maj": time.time()}


def correspond(fiche: dict[str, Any] | None, code: str) -> bool:
    if not fiche or not fiche.get("empreinte"):
        return False
    code = nettoyer(code)
    if not valide(code):
        return False
    if fiche.get("format") == FORMAT_APPLI:
        return hmac.compare_digest(empreinte_appli(code), str(fiche["empreinte"]))
    try:
        sel = base64.b64decode(fiche["sel"])
        attendu = base64.b64decode(fiche["empreinte"])
        essai = _pbkdf2(code, sel, int(fiche.get("iterations") or ITERATIONS))
    except (KeyError, ValueError, TypeError):
        return False
    return hmac.compare_digest(essai, attendu)


def _fiches(store) -> dict[str, dict[str, Any]]:
    fiches = getattr(store, "codes_decideurs", None)
    if not isinstance(fiches, dict):
        fiches = {}
        store.codes_decideurs = fiches
    return fiches


def decideurs(store) -> set[str]:
    """Les personnes Décideurs : un appareil à leur nom qui gère ou approuve, ou un rôle Décideur choisi pour elles."""
    vues = {str(pc.get("person") or "") for pc in store.pcs.values()
            if roles.a(store, pc, "gerer") or roles.a(store, pc, "approuver")}
    for personne, role_id in (getattr(store, "roles_personnes", None) or {}).items():
        fonctions = (store.roles.get(role_id) or {}).get("fonctions") or []
        if "gerer" in fonctions or "approuver" in fonctions:
            vues.add(str(personne))
    return {p for p in vues if p.startswith("person.")}


def code_pose(store, personne: str | None = None) -> bool:
    """Ce Décideur a-t-il un code ? Sans personne : au moins un Décideur en a-t-il un ?"""
    fiches = _fiches(store)
    if personne:
        return bool((fiches.get(personne) or {}).get("empreinte"))
    return any((f or {}).get("empreinte") for f in fiches.values())


def est_pose(store) -> bool:
    return code_pose(store)


def poser(store, personne: str, code: str) -> None:
    """Un code neuf pour ce Décideur (en clair le temps du calcul), ou "" pour le retirer."""
    code = nettoyer(code)
    if code:
        _fiches(store)[personne] = fabriquer(code)
    else:
        _fiches(store).pop(personne, None)
    _echecs_lieu(store).clear()


def migrer(store, personne: str, empreinte: str) -> bool:
    """L'appli envoie l'empreinte de son code local ; gardée seulement si ce Décideur n'a encore aucun code ici."""
    empreinte = str(empreinte or "").strip().lower()
    if code_pose(store, personne) or len(empreinte) != 64 or any(c not in "0123456789abcdef" for c in empreinte):
        return False
    _fiches(store)[personne] = {"format": FORMAT_APPLI, "empreinte": empreinte, "maj": time.time()}
    return True


def _echecs_lieu(store) -> list[float]:
    limite = time.time() - ECHECS_LIEU_S
    echecs = [float(t) for t in (getattr(store, "codes_decideurs_echecs", None) or []) if float(t) > limite]
    store.codes_decideurs_echecs = echecs
    return echecs


def _faux(hass: HomeAssistant, qui: str) -> list[float]:
    tous = hass.data.setdefault(DONNEES, {})
    limite = time.time() - FENETRE_S
    tous[qui] = [t for t in tous.get(qui, []) if t > limite]
    return tous[qui]


async def verifier(hass: HomeAssistant, store, qui: str, code: Any, personne: str | None = None) -> str:
    """"" si le code est juste — celui de [personne] si elle a un code, sinon celui de n'importe quel Décideur ;
    sinon l'erreur : code_octroi (à demander), code_octroi_absent (aucun code), code_octroi_faux,
    code_octroi_suspendu (trop d'essais pour ce compte ou cet appareil), code_octroi_verrouille (pour le Lieu).
    Une empreinte migrée de l'appli devient PBKDF2 au premier code juste."""
    fiches = _fiches(store)
    candidats = [personne] if personne and code_pose(store, personne) else [p for p, f in fiches.items() if (f or {}).get("empreinte")]
    if not candidats:
        return "code_octroi_absent"
    if len(_echecs_lieu(store)) >= ECHECS_LIEU_MAX:
        return "code_octroi_verrouille"
    if len(_faux(hass, qui)) >= ESSAIS_FAUX_MAX:
        return "code_octroi_suspendu"
    code = nettoyer(code)
    if not code:
        return "code_octroi"
    for p in candidats:
        if await hass.async_add_executor_job(correspond, fiches.get(p), code):
            if (fiches.get(p) or {}).get("format") == FORMAT_APPLI:
                fiches[p] = await hass.async_add_executor_job(fabriquer, code)
                await store.async_save()
            return ""
    _faux(hass, qui).append(time.time())
    _echecs_lieu(store).append(time.time())
    await store.async_save()
    return "code_octroi_faux"


def personne_du_compte(hass: HomeAssistant, user_id: str | None) -> str:
    if not user_id:
        return ""
    for etat in hass.states.async_all("person"):
        if etat.attributes.get("user_id") == user_id:
            return etat.entity_id
    return ""


async def exiger(hass: HomeAssistant, coord, request, corps: dict[str, Any], accorde: bool) -> dict[str, Any] | None:
    """Pour une vue du tableau : None si le geste peut passer, sinon le corps de la réponse 403 (avec « retour »)."""
    if not accorde:
        return None
    from . import textes

    utilisateur = request.get("hass_user")
    qui = str(getattr(utilisateur, "id", "") or "?")
    personne = personne_du_compte(hass, getattr(utilisateur, "id", None))
    erreur = await verifier(hass, coord.store, qui, corps.get("code_octroi"), personne or None)
    if not erreur:
        return None
    return {"ok": False, "error": erreur, "retour": textes.t("octroi." + erreur, textes.ha(hass))}


# ----------------------------------------------------------------------------- L'appli d'un Décideur


async def depuis_appli(hass: HomeAssistant, coord, moi: dict[str, Any], corps: dict[str, Any]) -> tuple[dict[str, Any], int]:
    """POST /api/pc_parental/parent {"action": "code_deverrouillage", "op": …} — l'appareil d'un Décideur.

    - « etat » : {pose} pour la personne de l'appareil ;
    - « migrer » {empreinte} : l'empreinte locale de l'appli, gardée si ce Décideur n'a pas encore de code ici ;
    - « verifier » {code} : le code tapé dans l'appli (mêmes limites que le tableau) ;
    - « definir » {code, ancien} : un code neuf ; s'il y en a déjà un, l'ancien doit être juste."""
    personne = str(moi.get("person") or "")
    store = coord.store
    if not personne or personne not in decideurs(store):
        return {"ok": False, "error": "personne"}, 403
    op = str(corps.get("op") or "etat")
    qui = "appareil:" + str(moi.get("id") or "")
    if op == "etat":
        return {"ok": True, "pose": code_pose(store, personne)}, 200
    if op == "migrer":
        fait = migrer(store, personne, str(corps.get("empreinte") or ""))
        if fait:
            await store.async_save()
        return {"ok": True, "migre": fait, "pose": code_pose(store, personne)}, 200
    if op == "verifier":
        erreur = await verifier(hass, store, qui, corps.get("code"), personne)
        return ({"ok": True}, 200) if not erreur else ({"ok": False, "error": erreur}, 403)
    if op == "definir":
        code = nettoyer(corps.get("code"))
        if not valide(code):
            return {"ok": False, "error": "code_court"}, 400
        if code_pose(store, personne):
            erreur = await verifier(hass, store, qui, corps.get("ancien"), personne)
            if erreur:
                return {"ok": False, "error": erreur}, 403
        await hass.async_add_executor_job(poser, store, personne, code)
        await store.async_save()
        return {"ok": True, "pose": True}, 200
    return {"ok": False, "error": "op"}, 400
