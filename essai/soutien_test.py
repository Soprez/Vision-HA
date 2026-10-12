"""Soutien du projet (docs/soutien.md) : code valide, invalide, falsifié ; état transmis aux appareils.

Ne charge pas Home Assistant : le module se teste seul.
    python -m pytest essai/soutien_test.py -q
    python essai/soutien_test.py          (sans pytest)
"""
from __future__ import annotations

import base64
import importlib.util
import pathlib
import secrets
import sys
import types

from cryptography.hazmat.primitives import serialization as _s
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

_chemin = pathlib.Path(__file__).resolve().parent.parent / "custom_components" / "pc_parental" / "soutien.py"
_spec = importlib.util.spec_from_file_location("soutien", _chemin)
soutien = importlib.util.module_from_spec(_spec)
sys.modules["soutien"] = soutien
_spec.loader.exec_module(soutien)

# Une clé d'essai : la vraie clé privée n'est jamais ici.
_CLE = Ed25519PrivateKey.generate()
_PUB = base64.b64encode(_CLE.public_key().public_bytes(_s.Encoding.Raw, _s.PublicFormat.Raw)).decode()


def fabriquer(jours: int = 648, ident: bytes | None = None, cle=_CLE, version: int = 1, domaine: bytes = b"vision-soutien-v1") -> str:
    """Ce que fait le service de soprez.org (mêmes règles, voir vision-soutien-service)."""
    message = bytes([version]) + (ident or secrets.token_bytes(6)) + jours.to_bytes(2, "big")
    return soutien.encoder(message + cle.sign(domaine + message))


def _avec_cle_essai():
    soutien.CLE_PUBLIQUE_B64, ancienne = _PUB, soutien.CLE_PUBLIQUE_B64
    return ancienne


# ----------------------------------------------------------------------------- format

def test_format_117_caracteres_groupes():
    code = fabriquer()
    assert len(code.replace("-", "")) == 117
    assert all(len(g) == 5 for g in code.split("-")[:-1])
    assert set(code.replace("-", "")) <= set(soutien.ALPHABET)


def test_aller_retour():
    octets = bytes(range(73))
    assert soutien.decoder(soutien.encoder(octets)) == octets


# ----------------------------------------------------------------------------- code valide

def test_code_valide():
    ident = bytes.fromhex("a1b2c3d4e5f6")
    r = soutien.verifier(fabriquer(jours=647, ident=ident), _PUB)
    assert r.ok and r.erreur == ""
    assert r.identifiant == "a1b2c3d4e5f6"
    assert r.emis_le == "2026-10-10"


def test_code_valide_saisie_tolerante():
    code = fabriquer()
    for variante in (code.lower(), code.replace("-", " "), code.replace("-", "\n"), "VISION-" + code,
                     code.replace("0", "O").replace("1", "I"), "  " + code + "  "):
        assert soutien.verifier(variante, _PUB).ok, variante


# ----------------------------------------------------------------------------- codes invalides

def test_code_invalide_forme():
    code = fabriquer()
    cas = {
        "": "vide", "   ": "vide",
        "bonjour": "format",
        code[:-1]: "format",                      # tronqué
        code + "0": "format",                     # trop long
        code[:-1] + "U": "format",                # U n'est pas dans l'alphabet
        "A" * 5000: "format",                     # saisie démesurée
    }
    for texte, erreur in cas.items():
        r = soutien.verifier(texte, _PUB)
        assert not r.ok and r.erreur == erreur, (texte[:20], r)


def test_bourrage_non_nul_refuse():
    # Le bit de poids faible du dernier caractère est du bourrage : un seul texte par code.
    code = fabriquer().replace("-", "")
    autre = code[:-1] + soutien.ALPHABET[soutien.ALPHABET.index(code[-1]) ^ 1]
    assert soutien.decoder(autre) is None
    assert soutien.verifier(autre, _PUB).erreur == "format"


def test_mauvaise_version():
    assert soutien.verifier(fabriquer(version=2), _PUB).erreur == "version"


# ----------------------------------------------------------------------------- codes falsifiés

def test_code_falsifie_un_caractere():
    code = fabriquer()
    brut = code.replace("-", "")
    refuses = 0
    for i in range(0, 116):   # le dernier caractère porte le bourrage
        c = soutien.ALPHABET[(soutien.ALPHABET.index(brut[i]) + 7) % 32]
        r = soutien.verifier(brut[:i] + c + brut[i + 1:], _PUB)
        assert not r.ok
        refuses += r.erreur in ("signature", "version")
    assert refuses == 116


def test_code_falsifie_date_ou_identifiant():
    octets = bytearray(soutien.decoder(fabriquer(jours=10)))
    octets[8] ^= 0x01        # autre date
    assert soutien.verifier(soutien.encoder(bytes(octets)), _PUB).erreur == "signature"
    octets = bytearray(soutien.decoder(fabriquer()))
    octets[3] ^= 0xFF        # autre identifiant
    assert soutien.verifier(soutien.encoder(bytes(octets)), _PUB).erreur == "signature"


def test_code_signe_par_une_autre_cle():
    assert soutien.verifier(fabriquer(cle=Ed25519PrivateKey.generate()), _PUB).erreur == "signature"


def test_signature_sans_le_prefixe_de_domaine():
    assert soutien.verifier(fabriquer(domaine=b""), _PUB).erreur == "signature"


def test_cle_embarquee_refuse_un_code_d_essai():
    # La clé publique de Vision n'est pas la clé d'essai : un code fabriqué ici n'y passe pas.
    assert soutien.verifier(fabriquer()).erreur == "signature"
    assert len(base64.b64decode(soutien.CLE_PUBLIQUE_B64)) == 32


# ----------------------------------------------------------------------------- état et relevé

def test_etat_par_code_puis_releve():
    ancienne = _avec_cle_essai()
    try:
        store = types.SimpleNamespace(soutien={})
        assert soutien.pour_releve(store) == {"actif": False}
        assert not soutien.activer_code(store, "faux", 1.0).ok
        assert soutien.pour_releve(store) == {"actif": False}
        assert soutien.activer_code(store, fabriquer(ident=bytes(6)), 2.0).ok
        assert store.soutien["mode"] == "code" and store.soutien["id"] == "000000000000"
        # Les appareils ne reçoivent ni identifiant ni date.
        assert soutien.pour_releve(store) == {"actif": True, "mode": "code"}
        # Un code falsifié ensuite ne retire rien.
        assert not soutien.activer_code(store, fabriquer(cle=Ed25519PrivateKey.generate()), 3.0).ok
        assert store.soutien["mode"] == "code"
        # « J'ai offert un café » ne rétrograde pas un badge par code.
        soutien.activer_honneur(store, 4.0)
        assert store.soutien["mode"] == "code"
        soutien.retirer(store)
        assert soutien.pour_releve(store) == {"actif": False}
    finally:
        soutien.CLE_PUBLIQUE_B64 = ancienne


def test_honneur_sans_verification():
    store = types.SimpleNamespace()
    soutien.activer_honneur(store, 5.0)
    assert soutien.pour_releve(store) == {"actif": True, "mode": "honneur"}


def test_etat_inconnu_ignore():
    store = types.SimpleNamespace(soutien={"mode": "premium"})
    assert soutien.pour_releve(store) == {"actif": False}


if __name__ == "__main__":
    essais = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    for nom, f in essais:
        f()
        print("ok  ", nom)
    print(f"{len(essais)} essais réussis")


def test_cle_compromise_retiree():
    """2026-10-11 : la seed de la première paire a fuité ; sa clé publique ne doit plus jamais être embarquée."""
    compromise = "SBPhRoSkiebp8Dk5bnT40iFjcrnEXY0P2IP7X20GTt4="
    assert soutien.CLE_PUBLIQUE_B64 != compromise
    assert len(base64.b64decode(soutien.CLE_PUBLIQUE_B64)) == 32
