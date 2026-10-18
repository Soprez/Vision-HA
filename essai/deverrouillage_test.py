"""Déverrouillage à distance (1.66.0), dans un vrai Home Assistant : le parcours et les attaques.

La vérification hors ligne de l'appareil (docs/deverrouillage.md §4) est rejouée ici, en Python,
comme l'appli Android doit la faire : clé publique SubjectPublicKeyInfo, signature DER, message
de 8 lignes recomposé avec SON identifiant et SON défi.

Attaques couvertes : QR photographié rejoué ; QR sur un autre appareil, pour une autre action ou
une autre durée ; QR expiré ; défi réutilisé après une nouvelle demande ; code deviné ; appareil
enfant qui se fait passer pour parent ; décision sur une demande déjà traitée ou annulée ;
approbation par un compte qui n'est pas administrateur, ou par une automatisation.

Banc : Python 3.14 et pytest-homeassistant-custom-component==0.13.367 (Home Assistant 2026.9.4).
    python -m pytest essai/deverrouillage_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import base64
import json
import logging
import pathlib
import secrets
import shutil
import sys
import tempfile
import time
from urllib.parse import parse_qs, urlsplit

import pytest
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
from homeassistant.core import Context, HomeAssistant
from homeassistant.exceptions import Unauthorized
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import label_registry as lr
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

racine = pathlib.Path(__file__).resolve().parent.parent
source = racine / "custom_components" / "pc_parental"

_banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-deverrouillage-"))
shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
(_banc / "custom_components" / "__init__.py").write_text("")
sys.path.insert(0, str(_banc))
import custom_components  # noqa: E402

if str(_banc / "custom_components") not in list(custom_components.__path__):
    custom_components.__path__.append(str(_banc / "custom_components"))

from custom_components.pc_parental.registre import appareil_du_pc  # noqa: E402

DOMAIN = "pc_parental"
ENFANT = {"id": "e1", "secret": "e" * 32}
ENFANT2 = {"id": "e2", "secret": "f" * 32}
PARENT = {"id": "p1", "secret": "a" * 32}


@pytest.fixture(autouse=True)
async def _integrations(hass, enable_custom_integrations):
    for composant in ("http", "api", "websocket_api", "config"):
        assert await async_setup_component(hass, composant, {})
    yield


@pytest.fixture
def expected_lingering_timers() -> bool:
    return True


@pytest.fixture
def expected_lingering_tasks() -> bool:
    return True


def _fiche(pc_id: str, nom: str, secret: str, **plus) -> dict:
    return {"id": pc_id, "name": nom, "host": nom, "secret": secret, "enabled": True, "rules": [],
            "bloques": [], "autorises": [], "override": None, "last_seen": 0, "agent": {},
            "person": "", "update_asked": False, "tags": [], "messages": [], "next_message_id": 1, **plus}


async def _monter(hass: HomeAssistant, hass_storage, parents=("p1",)):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "pcs": {
            "e1": _fiche("e1", "TEL-LOU", ENFANT["secret"], person="person.lou", platform="android"),
            "e2": _fiche("e2", "TEL-NOA", ENFANT2["secret"], person="person.noa", platform="android"),
            "p1": _fiche("p1", "PIXEL-NOLAN", PARENT["secret"], person="person.nolan", platform="android"),
        }, "roles_migres": True}}
    # 1.69.0 : le rôle Parent est un réglage de l'appareil (l'étiquette « Parents » ne compte plus).
    for pc_id in parents:
        hass_storage[DOMAIN]["data"]["pcs"][pc_id]["role"] = "parent"
    for p in ("lou", "noa", "nolan"):
        hass.states.async_set(f"person.{p}", "home", {"friendly_name": p.capitalize()})
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    if parents:
        etiquette = lr.async_get(hass).async_create("Parents")
        for pc_id in parents:
            appareil = appareil_du_pc(hass, pc_id)
            dr.async_get(hass).async_update_device(appareil.id, labels={etiquette.label_id})
        await hass.async_block_till_done()


def _defi() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(16)).rstrip(b"=").decode()


class Appareil:
    """Ce que l'appli Android garde et vérifie : la clé publique, la demande en cours, le défi."""

    def __init__(self, client, ident):
        self.client, self.ident = client, ident
        self.cle = None
        self.demande = None
        self.defi = None

    async def appel(self, chemin, **corps):
        reponse = await self.client.post(f"/api/pc_parental/deverrouillage/{chemin}", json={**self.ident, **corps})
        return reponse.status, await reponse.json()

    async def demander(self, action="app:com.roblox.client", minutes=30, **plus):
        defi = _defi()
        statut, r = await self.appel("demande", action=action, minutes=minutes, defi=defi, **plus)
        if statut == 200:
            self.demande, self.defi, self.cle = r["demande"], defi, r["cle"]
        return statut, r

    def verifier(self, a: dict, maintenant: float | None = None) -> str:
        """docs/deverrouillage.md §4, dans l'ordre. Rend « ok » ou la raison du refus."""
        if a["k"] != self.cle["k"]:
            return "cle"
        if a["appareil"] != self.ident["id"]:
            return "appareil"
        if a["demande"] != self.demande or self.defi is None:
            return "demande"
        message = "\n".join(["vision-deverrouillage", "1", self.ident["id"], a["demande"], a["action"],
                             str(int(a["minutes"])), str(int(a["expire"])), self.defi]).encode("utf-8")
        publique = serialization.load_der_public_key(base64.b64decode(self.cle["pub"]))
        try:
            publique.verify(base64.urlsafe_b64decode(a["signature"] + "=" * (-len(a["signature"]) % 4)),
                            message, ec.ECDSA(hashes.SHA256()))
        except InvalidSignature:
            return "signature"
        if int(a["expire"]) < (maintenant or time.time()):
            return "expire"
        return "ok"

    def appliquer(self, a: dict, maintenant: float | None = None) -> str:
        verdict = self.verifier(a, maintenant)
        if verdict == "ok":
            self.defi = None  # effacé dès le premier usage
        return verdict


def lire_qr(texte: str) -> dict:
    morceaux = urlsplit(texte)
    assert (morceaux.scheme, morceaux.netloc) == ("vision", "deverrouillage")
    q = {k: v[0] for k, v in parse_qs(morceaux.query).items()}
    assert q["v"] == "1" and "defi" not in q and len(q) == 8
    return {"k": q["k"], "appareil": q["p"], "demande": q["d"], "action": q["a"], "minutes": int(q["m"]),
            "expire": int(q["x"]), "signature": q["s"]}


async def _parent_decide(client, demande, **corps):
    reponse = await client.post("/api/pc_parental/deverrouillage/decision",
                                json={**PARENT, "demande": demande, "decision": "approuver", **corps})
    return reponse.status, await reponse.json()


# ------------------------------------------------------------------------------- Le parcours


async def test_parcours_remise_directe(hass, hass_storage, hass_client, caplog):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    evenements = []
    hass.bus.async_listen(f"{DOMAIN}_deverrouillage", lambda e: evenements.append(e.data))

    statut, r = await lou.demander(motif="Exposé de géo")
    assert statut == 200 and r["cle"]["k"] and r["expire"] > time.time()
    # Le parent voit la demande, sans le défi.
    statut, liste = await Appareil(client, PARENT).appel("liste")
    assert statut == 200 and [x["demande"] for x in liste["demandes"]] == [lou.demande]
    assert lou.defi not in json.dumps(liste)
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "en_attente"

    statut, d = await _parent_decide(client, lou.demande, minutes=20)
    assert statut == 200 and d["remise"] == "directe" and d["minutes"] == 20 and "code" not in d

    statut, e = await lou.appel("etat", demande=lou.demande)
    a = e["approbation"]
    assert a["minutes"] == 20 and a["par"] == "Nolan" and a["action"] == "app:com.roblox.client"
    # Le relevé porte la clé, la biométrie (éteinte) et la même approbation.
    releve = await (await client.post("/api/pc_parental/poll", json=ENFANT)).json()
    assert releve["deverrouillage_cle"] == lou.cle and releve["biometrie"] is False
    assert releve["deverrouillage"] == a

    assert lou.appliquer(a) == "ok"
    caplog.set_level(logging.WARNING)
    statut, c = await lou.appel("consommer", demande=lou.demande, applique=int(time.time()))
    assert statut == 200 and c["fin"] >= time.time() + 19 * 60
    await hass.async_block_till_done()
    assert any("déverrouillage de « TEL-LOU » (Lou)" in m and "approuvé par Nolan" in m for m in caplog.messages)
    assert evenements and evenements[0]["remise"] == "directe" and evenements[0]["par"] == "Nolan"
    assert (await lou.appel("consommer", demande=lou.demande))[0] == 409
    releve = await (await client.post("/api/pc_parental/poll", json=ENFANT)).json()
    assert "deverrouillage" not in releve


async def test_signature_selon_le_contrat(hass, hass_storage, hass_client):
    """Le message signé est exactement celui du contrat (8 lignes, sans retour final), en DER."""
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    await lou.demander(action="parent", minutes=15)
    a = (await _parent_decide(client, lou.demande))[1]
    a = (await lou.appel("etat", demande=lou.demande))[1]["approbation"]
    signature = base64.urlsafe_b64decode(a["signature"] + "=" * (-len(a["signature"]) % 4))
    assert signature[0] == 0x30  # SEQUENCE DER
    der = base64.b64decode(lou.cle["pub"])
    import hashlib

    assert lou.cle["k"] == hashlib.sha256(der).hexdigest()[:16]
    assert lou.verifier(a) == "ok"


# ------------------------------------------------------------------------------- Le QR


async def test_qr_photographie_rejoue_ou_sur_un_autre_appareil(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou, noa = Appareil(client, ENFANT), Appareil(client, ENFANT2)
    await lou.demander()
    await noa.demander()  # Noa a sa propre demande, et son propre défi
    statut, d = await _parent_decide(client, lou.demande, remise="qr")
    assert statut == 200 and "qr" in d and d["expire"] - time.time() <= 90
    a = lire_qr(d["qr"])
    # La remise QR ne passe jamais par le relevé ni par l'état.
    assert "approbation" not in (await lou.appel("etat", demande=lou.demande))[1]
    assert "deverrouillage" not in await (await client.post("/api/pc_parental/poll", json=ENFANT)).json()

    # Photographié par Noa : pas son appareil ; et même en mentant sur l'identifiant, pas son défi.
    assert noa.verifier(a) == "appareil"
    menteur = Appareil(client, {"id": "e1", "secret": "?"})
    menteur.cle, menteur.demande, menteur.defi = noa.cle, a["demande"], noa.defi
    assert menteur.verifier(a) == "signature"

    assert lou.appliquer(a) == "ok"
    assert (await lou.appel("consommer", demande=lou.demande, applique=int(time.time())))[0] == 200
    # Rejoué plus tard sur le même appareil : le défi est effacé.
    assert lou.verifier(a) == "demande"
    assert (await lou.appel("consommer", demande=lou.demande))[0] == 409


async def test_qr_autre_action_ou_duree(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    await lou.demander(action="app:com.roblox.client", minutes=30)
    a = lire_qr((await _parent_decide(client, lou.demande, minutes=10, remise="qr"))[1]["qr"])
    assert lou.verifier({**a, "minutes": 60}) == "signature"
    assert lou.verifier({**a, "action": "parent"}) == "signature"
    assert lou.verifier({**a, "expire": a["expire"] + 3600}) == "signature"
    assert lou.verifier(a) == "ok"


async def test_qr_expire(hass, hass_storage, hass_client, monkeypatch):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    await lou.demander()
    a = lire_qr((await _parent_decide(client, lou.demande, remise="qr"))[1]["qr"])
    plus_tard = time.time() + 91
    assert lou.verifier(a, maintenant=plus_tard) == "expire"
    from custom_components.pc_parental import deverrouillage

    monkeypatch.setattr(deverrouillage.time, "time", lambda: plus_tard)
    # En ligne : expirée, rien à consommer.
    assert (await lou.appel("consommer", demande=lou.demande))[0] == 404
    # Appliquée hors ligne avant l'expiration (horloge de l'appareil), dite au retour : journalisée, rien de plus.
    statut, r = await lou.appel("consommer", demande=lou.demande, hors_ligne=True, applique=int(plus_tard - 60))
    assert statut == 200
    assert (await lou.appel("consommer", demande=lou.demande, hors_ligne=True))[0] == 409


async def test_defi_reutilise_apres_nouvelle_demande(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    await lou.demander()
    premiere = lou.demande
    a = lire_qr((await _parent_decide(client, premiere, remise="qr"))[1]["qr"])
    await lou.demander(action="reglages", minutes=5)  # annule la première
    assert lou.verifier(a) == "demande"
    lou.demande = premiere  # même en forçant : le défi a changé
    assert lou.verifier(a) == "signature"
    assert (await lou.appel("etat", demande=premiere))[1]["etat"] == "annulee"
    assert (await lou.appel("consommer", demande=premiere))[0] == 404
    assert (await _parent_decide(client, premiere))[0] == 404


# ------------------------------------------------------------------------------- Le code


async def test_code_devine(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    await lou.demander()
    d = (await _parent_decide(client, lou.demande, remise="code"))[1]
    code = d["code"]
    assert len(code) == 6 and code.isdigit()
    assert "approbation" not in (await lou.appel("etat", demande=lou.demande))[1]
    faux = f"{(int(code) + 1) % 10**6:06d}"
    for restants in (4, 3, 2, 1):
        statut, r = await lou.appel("code", demande=lou.demande, code=faux)
        assert statut == 403 and r["essais_restants"] == restants
    assert (await lou.appel("code", demande=lou.demande, code=faux))[0] == 423
    # Bloquée : même le bon code ne sert plus.
    assert (await lou.appel("code", demande=lou.demande, code=code))[0] == 404
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "bloquee"

    # Le bon code, une seule fois.
    await lou.demander()
    code = (await _parent_decide(client, lou.demande, remise="code"))[1]["code"]
    statut, r = await lou.appel("code", demande=lou.demande, code=code)
    assert statut == 200 and lou.appliquer(r["approbation"]) == "ok"
    assert (await lou.appel("code", demande=lou.demande, code=code))[0] == 404

    # Au-delà de 10 codes faux dans l'heure, toutes demandes confondues : plus d'essai.
    await lou.demander()
    code = (await _parent_decide(client, lou.demande, remise="code"))[1]["code"]
    faux = f"{(int(code) + 1) % 10**6:06d}"
    statuts = [(await lou.appel("code", demande=lou.demande, code=faux))[0] for _ in range(5)]
    assert statuts[-1] in (423, 429)
    await lou.demander()
    code = (await _parent_decide(client, lou.demande, remise="code"))[1]["code"]
    assert (await lou.appel("code", demande=lou.demande, code=code))[0] == 429


async def test_code_d_un_autre_appareil(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou, noa = Appareil(client, ENFANT), Appareil(client, ENFANT2)
    await lou.demander()
    code = (await _parent_decide(client, lou.demande, remise="code"))[1]["code"]
    assert (await noa.appel("code", demande=lou.demande, code=code))[0] == 404
    assert (await lou.appel("code", demande=lou.demande, code=code))[0] == 200
    # Ni l'état, ni l'approbation directe, ni la consommation de la demande d'un autre.
    await lou.demander()
    await _parent_decide(client, lou.demande)
    assert (await noa.appel("etat", demande=lou.demande))[0] == 404
    assert (await noa.appel("consommer", demande=lou.demande))[0] == 404
    assert "deverrouillage" not in await (await client.post("/api/pc_parental/poll", json=ENFANT2)).json()
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "approuvee"


# ------------------------------------------------------------------------------- Qui décide


async def test_enfant_qui_se_dit_parent(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou, noa = Appareil(client, ENFANT), Appareil(client, ENFANT2)
    await lou.demander()
    # Noa (sans l'étiquette « Parents ») décide pour son frère, ou liste : refusé.
    assert (await noa.appel("decision", demande=lou.demande, decision="approuver"))[0] == 403
    assert (await noa.appel("liste"))[0] == 403
    # Avec l'identifiant du parent mais pas son secret : refusé.
    faux = Appareil(client, {"id": "p1", "secret": "b" * 32})
    assert (await faux.appel("decision", demande=lou.demande, decision="approuver"))[0] == 401
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "en_attente"


async def test_parent_ne_decide_pas_pour_lui_meme(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    nolan = Appareil(client, PARENT)
    await nolan.demander(action="parent", minutes=10)
    assert (await _parent_decide(client, nolan.demande))[0] == 403


async def test_decision_deja_traitee_ou_annulee(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    await lou.demander()
    assert (await _parent_decide(client, lou.demande, decision="refuser"))[1]["etat"] == "refusee"
    assert (await _parent_decide(client, lou.demande))[0] == 404
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "refusee"
    await lou.demander()
    assert (await _parent_decide(client, lou.demande))[0] == 200
    assert (await _parent_decide(client, lou.demande, remise="qr"))[0] == 404  # pas deux approbations
    # Plus de minutes que demandées : refusé.
    await lou.demander(minutes=10)
    assert (await _parent_decide(client, lou.demande, minutes=11))[0] == 400


async def test_approbation_par_un_non_admin(hass, hass_storage, hass_client):
    admin = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(admin, ENFANT)
    await lou.demander()
    enfant_ha = await hass.auth.async_create_user("Lou", group_ids=["system-users"])
    rafraichi = await hass.auth.async_create_refresh_token(enfant_ha, client_name="essai",
                                                           token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN)
    client_enfant = await hass_client(hass.auth.async_create_access_token(rafraichi))
    assert (await client_enfant.get("/api/pc_parental/deverrouillage")).status == 403
    reponse = await client_enfant.post("/api/pc_parental/deverrouillage",
                                       json={"demande": lou.demande, "decision": "approuver"})
    assert reponse.status == 403
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "approuver_deverrouillage", {"demande": lou.demande},
                                       blocking=True, context=Context(user_id=enfant_ha.id), return_response=True)
    # Une automatisation (sans utilisateur) non plus.
    with pytest.raises(Unauthorized):
        await hass.services.async_call(DOMAIN, "approuver_deverrouillage", {"demande": lou.demande},
                                       blocking=True, return_response=True)
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "en_attente"

    # Un administrateur, si : par la vue, et par le service (code renvoyé).
    liste = await (await admin.get("/api/pc_parental/deverrouillage")).json()
    assert [x["demande"] for x in liste["demandes"]] == [lou.demande]
    nolan_ha = await hass.auth.async_create_user("Mia", group_ids=["system-admin"])
    r = await hass.services.async_call(DOMAIN, "approuver_deverrouillage",
                                       {"demande": lou.demande, "remise": "code", "minutes": 5},
                                       blocking=True, context=Context(user_id=nolan_ha.id), return_response=True)
    assert len(r["code"]) == 6
    statut, a = await lou.appel("code", demande=lou.demande, code=r["code"])
    assert statut == 200 and a["approbation"]["par"] == "Mia" and lou.verifier(a["approbation"]) == "ok"


# ------------------------------------------------------------------------------- Divers


async def test_demandes_invalides_et_limite(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    for corps, erreur in (
        ({"action": "app:com.x\nparent", "minutes": 5, "defi": _defi()}, "action"),
        ({"action": "tout", "minutes": 5, "defi": _defi()}, "action"),
        ({"action": "parent", "minutes": 61, "defi": _defi()}, "minutes"),
        ({"action": "reglages", "minutes": 0, "defi": _defi()}, "minutes"),
        ({"action": "parent", "minutes": 5, "defi": "court"}, "defi"),
        ({"action": "parent", "minutes": 5, "defi": _defi(), "motif": "a\nb"}, "motif"),
    ):
        statut, r = await lou.appel("demande", **corps)
        assert (statut, r["error"]) == (400, erreur), corps
    assert (await Appareil(client, {"id": "e1", "secret": "x"}).appel("demande"))[0] == 401
    statuts = [(await lou.demander())[0] for _ in range(11)]
    assert statuts[:10] == [200] * 10 and statuts[10] == 429


async def test_biometrie(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)

    async def flag(ident):
        return (await (await client.post("/api/pc_parental/poll", json=ident)).json())["biometrie"]

    assert await flag(PARENT) is False  # rien de coché par défaut
    from homeassistant.exceptions import HomeAssistantError
    from homeassistant.helpers import entity_registry as er

    # 1.71.0 : la biométrie est une fonction du rôle (★, administrateurs) ; le modèle Parent ne l'a pas par défaut.
    coord = next(iter(hass.data[DOMAIN].values()))
    from custom_components.pc_parental import roles

    roles.modifier(hass, coord.store, "parent", "Décideur", coord.store.roles["parent"]["fonctions"] + ["biometrie"], 60, "essai")
    assert await flag(PARENT) is True and await flag(ENFANT) is False
    # Une personne liée à un administrateur ne suffit pas : seul le rôle compte.
    mia = await hass.auth.async_create_user("Mia", group_ids=["system-admin"])
    hass.states.async_set("person.noa", "home", {"friendly_name": "Noa", "user_id": mia.id})
    assert await flag(ENFANT2) is False
    # L'appareil de nolan reprend le rôle Enfant (surcharge) : plus de biométrie.
    role_nolan = er.async_get(hass).async_get_entity_id("select", DOMAIN, f"{DOMAIN}_role_p1")
    enfant_ha = await hass.auth.async_create_user("Lou", group_ids=["system-users"])
    with pytest.raises(Unauthorized):
        await hass.services.async_call("select", "select_option", {"entity_id": role_nolan, "option": "Demandeur"}, blocking=True,
                                       context=Context(user_id=enfant_ha.id))
    # Le dernier appareil qui peut gérer et approuver ne peut pas perdre son rôle.
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call("select", "select_option", {"entity_id": role_nolan, "option": "Demandeur"}, blocking=True)
    coord.store.pcs["e2"]["role"] = "parent"  # un second appareil Parent : le premier peut redevenir Enfant
    await hass.services.async_call("select", "select_option", {"entity_id": role_nolan, "option": "Demandeur"}, blocking=True)
    assert await flag(PARENT) is False


async def test_rotation_de_la_cle(hass, hass_storage, hass_client):
    client = await hass_client()
    await _monter(hass, hass_storage)
    lou = Appareil(client, ENFANT)
    await lou.demander()
    a = lire_qr((await _parent_decide(client, lou.demande, remise="qr"))[1]["qr"])
    ancienne = lou.cle["k"]
    admin = await hass.auth.async_create_user("Mia", group_ids=["system-admin"])
    await hass.services.async_call(DOMAIN, "nouvelle_cle_deverrouillage", {}, blocking=True,
                                   context=Context(user_id=admin.id))
    releve = await (await client.post("/api/pc_parental/poll", json=ENFANT)).json()
    assert releve["deverrouillage_cle"]["k"] != ancienne
    lou.cle = releve["deverrouillage_cle"]
    assert lou.verifier(a) == "cle"
    assert (await lou.appel("etat", demande=lou.demande))[1]["etat"] == "annulee"
    # La clé est gardée : la même après un rechargement de Vision.
    entree = hass.config_entries.async_entries(DOMAIN)[0]
    assert await hass.config_entries.async_reload(entree.entry_id)
    await hass.async_block_till_done()
    releve = await (await client.post("/api/pc_parental/poll", json=ENFANT)).json()
    assert releve["deverrouillage_cle"] == lou.cle
    assert "privee" in hass_storage[f"{DOMAIN}.deverrouillage"]["data"]
