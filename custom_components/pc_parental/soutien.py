"""Soutien du projet : badge décoratif, débloqué par un code signé ou sur l'honneur (docs/soutien.md).

La vérification se fait ici, hors ligne, avec la clé publique embarquée : rien n'est envoyé nulle part.
Le code ne porte qu'un identifiant opaque et une date ; il ne débloque aucune fonction.
"""
from __future__ import annotations

import base64
import datetime as _dt
from dataclasses import dataclass
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

# Clé publique du service de soutien (soprez.org). La clé privée n'est jamais dans un dépôt.
CLE_PUBLIQUE_B64 = "sAzMHwyLhLYd7vpYx4Ua9bdDOiBkCabzzsOFYs55/fU="
LIEN_DON = "https://buymeacoffee.com/soprez"

DOMAINE = b"vision-soutien-v1"
VERSION = 1
TAILLE_MESSAGE = 9          # version (1) + identifiant (6) + jours (2)
TAILLE = TAILLE_MESSAGE + 64
LONGUEUR_TEXTE = (TAILLE * 8 + 4) // 5   # 117 caractères
EPOQUE = _dt.date(2025, 1, 1)
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_VALEURS = {c: i for i, c in enumerate(ALPHABET)} | {"O": 0, "I": 1, "L": 1}
# Au-delà, ce n'est pas un code recopié : on ne regarde même pas.
LONGUEUR_MAX_SAISIE = 400

MODE_CODE = "code"
MODE_HONNEUR = "honneur"


@dataclass(frozen=True)
class Resultat:
    ok: bool
    erreur: str = ""          # "vide", "format", "version", "signature"
    identifiant: str = ""     # 12 caractères hexadécimaux
    emis_le: str = ""         # date ISO


def encoder(octets: bytes) -> str:
    """Les octets en base32 de Crockford, groupés par 5 (bourrage à zéro)."""
    bits = int.from_bytes(octets, "big") << (LONGUEUR_TEXTE * 5 - len(octets) * 8)
    texte = "".join(ALPHABET[(bits >> (5 * (LONGUEUR_TEXTE - 1 - i))) & 31] for i in range(LONGUEUR_TEXTE))
    return "-".join(texte[i:i + 5] for i in range(0, len(texte), 5))


def decoder(texte: str) -> bytes | None:
    """Le texte saisi en octets ; None s'il n'a pas la forme d'un code."""
    if not isinstance(texte, str) or len(texte) > LONGUEUR_MAX_SAISIE:
        return None
    propre = "".join(c for c in texte.upper() if not c.isspace() and c not in "-_.")
    if propre.startswith("VISION"):
        propre = propre[len("VISION"):]
    if len(propre) != LONGUEUR_TEXTE:
        return None
    n = 0
    for c in propre:
        v = _VALEURS.get(c)
        if v is None:
            return None
        n = (n << 5) | v
    bourrage = LONGUEUR_TEXTE * 5 - TAILLE * 8
    if n & ((1 << bourrage) - 1):
        return None
    return (n >> bourrage).to_bytes(TAILLE, "big")


def verifier(texte: str, cle_publique_b64: str | None = None) -> Resultat:
    """Vérifie un code de soutien, hors ligne (clé embarquée par défaut)."""
    cle_publique_b64 = cle_publique_b64 or CLE_PUBLIQUE_B64
    if not (texte or "").strip():
        return Resultat(False, "vide")
    octets = decoder(texte)
    if octets is None:
        return Resultat(False, "format")
    if octets[0] != VERSION:
        return Resultat(False, "version")
    message, signature = octets[:TAILLE_MESSAGE], octets[TAILLE_MESSAGE:]
    try:
        Ed25519PublicKey.from_public_bytes(base64.b64decode(cle_publique_b64)).verify(signature, DOMAINE + message)
    except (InvalidSignature, ValueError):
        return Resultat(False, "signature")
    jours = int.from_bytes(message[7:9], "big")
    return Resultat(True, "", message[1:7].hex(), (EPOQUE + _dt.timedelta(days=jours)).isoformat())


# --------------------------------------------------------------------------- état gardé par l'intégration

def etat(store) -> dict[str, Any]:
    return dict(getattr(store, "soutien", None) or {})


def actif(store) -> bool:
    return etat(store).get("mode") in (MODE_CODE, MODE_HONNEUR)


def activer_code(store, texte: str, maintenant: float) -> Resultat:
    """Un code valide pose le badge ; un code refusé ne change rien à l'état."""
    r = verifier(texte)
    if r.ok:
        store.soutien = {"mode": MODE_CODE, "id": r.identifiant, "emis_le": r.emis_le, "depuis": maintenant}
    return r


def activer_honneur(store, maintenant: float) -> None:
    """« J'ai offert un café » : décoratif, sans vérification. Un badge par code n'est pas rétrogradé."""
    if etat(store).get("mode") != MODE_CODE:
        store.soutien = {"mode": MODE_HONNEUR, "depuis": maintenant}


def retirer(store) -> None:
    store.soutien = {}


def pour_releve(store) -> dict[str, Any]:
    """Ce que reçoivent les appareils : actif ou non, et comment. Ni identifiant, ni date."""
    e = etat(store)
    return {"actif": True, "mode": e["mode"]} if actif(store) else {"actif": False}
