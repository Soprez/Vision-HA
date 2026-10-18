"""2.4.5 (l'auteur : « on peut pas faire un truc automatique ? ») : l'appairage Sendspin sans saisie du PIN dans Music Assistant.

Un appareil Vision passé au lecteur Sendspin n'est pas encore appairé à Music Assistant (MA). Avant, il fallait ouvrir MA,
lancer l'appairage du lecteur et taper le PIN affiché sur la télé. Maintenant Home Assistant le fait, par la connexion
à MA qu'il a déjà, avec le setup flow de MA (le même que son interface) :

1. « demarrer » : l'appareil (inscrit dans Vision, authentifié par son identifiant et son secret) demande l'appairage de
   SON lecteur. Home Assistant vérifie que ce lecteur MA porte bien le nom de cet appareil (« <nom> · Vision »), puis
   lance `config/players/setup` ; MA ouvre l'appairage sur la télé, qui tire et affiche un PIN.
2. « pin » : l'appareil envoie ce PIN par le canal Vision ; Home Assistant le soumet au flow (`config/flows/submit`).

Sécurité : jamais d'appairage ouvert à n'importe quel client — seulement le lecteur d'un appareil inscrit, à sa demande,
avec un PIN à usage unique ; un flow ne vit que quelques minutes ; « legacy clients » n'est jamais touché. Si la connexion
de Home Assistant à MA n'a pas le droit `config.players.write`, la réponse le dit (« droits ») : l'appairage reste alors
manuel (le PIN est toujours affiché sur la télé).
"""
from __future__ import annotations

import asyncio
import logging
import re
import time
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import musique, textes
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/sendspin/appairage"
DUREE_S = 180          # un flow d'appairage vit au plus 3 min
ATTENTE_FORM_S = 20    # le temps laissé à MA pour présenter le formulaire du PIN
INTERVALLE_DEMARRER_S = 60
ATTENTE_LECTEUR_S = 10  # le temps laissé à MA pour enregistrer le lecteur qui vient de se connecter
_PIN = re.compile(r"^\d{4,12}$")
_DROITS = ("scope", "permission", "unauthorized", "forbidden", "insufficient")

# pc_id → {"flow": flow_id, "debut": monotonic, "joueur": player_id}
_FLOWS: dict[str, dict[str, Any]] = {}
_DERNIER_DEMARRER: dict[str, float] = {}


def _d(o: Any) -> dict[str, Any]:
    return musique._vers_dict(o) if o is not None else {}


def nom_attendu(pc: dict[str, Any]) -> str:
    """Le nom du lecteur de cet appareil dans MA (même règle que l'appli : « <nom> · Vision »)."""
    return f"{str(pc.get('name') or '').strip()} · Vision"


def meme_lecteur(nom_ma: str, pc: dict[str, Any]) -> bool:
    return bool(str(pc.get("name") or "").strip()) and nom_ma.strip().casefold() == nom_attendu(pc).casefold()


def _droits(err: Exception) -> bool:
    texte = str(err).lower()
    return any(m in texte for m in _DROITS)


def _expire(maintenant: float | None = None) -> None:
    m = time.monotonic() if maintenant is None else maintenant
    for pc_id in [k for k, v in _FLOWS.items() if m - v["debut"] > DUREE_S]:
        _FLOWS.pop(pc_id, None)


def _champ_pin(etape: dict[str, Any]) -> str | None:
    """La clé du champ PIN (type « pairing_code ») d'une étape FORM, s'il y en a un."""
    if str(etape.get("type") or "") != "form":
        return None
    for e in etape.get("entries") or []:
        e = _d(e)
        if str(e.get("type") or "") == "pairing_code" and e.get("key"):
            return str(e["key"])
    return None


async def demarrer(hass: HomeAssistant, pc: dict[str, Any], joueur: str) -> str:
    """Lance l'appairage du lecteur de cet appareil. « ok », « lecteur », « droits », « ma » ou « trop_vite »."""
    mass = musique._client(hass)
    if mass is None or not hasattr(mass, "send_command"):
        return "ma"
    pc_id = str(pc.get("id") or "")
    maintenant = time.monotonic()
    if maintenant - _DERNIER_DEMARRER.get(pc_id, -1e9) < INTERVALLE_DEMARRER_S:
        return "trop_vite"
    joueur = str(joueur or "").strip()
    if not joueur or len(joueur) > 128:
        return "lecteur"
    # 2.4.6 (essai réel du 12/10) : l'appareil demande dès sa connexion, MA enregistre le lecteur un instant après
    # (0,1 s plus tard ce jour-là) : on laisse à MA quelques secondes pour le connaître.
    p: dict[str, Any] = {}
    for essai in range(ATTENTE_LECTEUR_S):
        try:
            p = _d(await mass.send_command("players/get", player_id=joueur))
        except Exception as err:  # noqa: BLE001 - lecteur pas (encore) connu de MA
            _LOGGER.debug("Sendspin : lecteur %s pas encore dans MA (%s)", joueur, err)
            p = {}
        if p.get("name"):
            break
        await asyncio.sleep(1)
    if not p.get("name"):
        _LOGGER.info("Sendspin : lecteur %s introuvable dans MA", joueur)
        return "lecteur"
    if not meme_lecteur(str(p.get("name") or ""), pc):
        _LOGGER.warning("Sendspin : appairage refusé, le lecteur %s ne porte pas le nom de l'appareil %s", joueur,
                        pc.get("name"))
        return "lecteur"
    _DERNIER_DEMARRER[pc_id] = maintenant
    ancien = _FLOWS.pop(pc_id, None)
    if ancien:
        try:
            await mass.send_command("config/flows/abort", flow_id=ancien["flow"])
        except Exception:  # noqa: BLE001
            pass
    try:
        etape = _d(await mass.send_command("config/players/setup", player_id=joueur))
    except Exception as err:  # noqa: BLE001
        _LOGGER.warning("Sendspin : appairage de %s non lancé par MA : %s", pc.get("name"), err)
        return "droits" if _droits(err) else "ma"
    if not etape.get("flow_id"):
        return "ma"
    _FLOWS[pc_id] = {"flow": str(etape["flow_id"]), "debut": maintenant, "joueur": joueur}
    _LOGGER.info("Sendspin : appairage de %s lancé dans MA", pc.get("name"))
    return "ok"


async def soumettre_pin(hass: HomeAssistant, pc: dict[str, Any], pin: str) -> str:
    """Soumet le PIN affiché par l'appareil. « ok », « aucun » (pas d'appairage en cours), « pin », « refus » ou « ma »."""
    _expire()
    pc_id = str(pc.get("id") or "")
    f = _FLOWS.get(pc_id)
    if f is None:
        return "aucun"
    pin = str(pin or "").strip()
    if not _PIN.match(pin):
        return "pin"
    mass = musique._client(hass)
    if mass is None:
        return "ma"
    try:
        # MA peut d'abord montrer une étape d'attente (PROGRESS) : on attend le formulaire du PIN.
        cle, fin = None, time.monotonic() + ATTENTE_FORM_S
        while cle is None:
            etape = _d(await mass.send_command("config/flows/get", flow_id=f["flow"]))
            if str(etape.get("type") or "") in ("finish", "abort"):
                _FLOWS.pop(pc_id, None)
                return "ok" if etape.get("type") == "finish" else "refus"
            cle = _champ_pin(etape)
            if cle is None:
                if time.monotonic() > fin:
                    raise TimeoutError
                await asyncio.sleep(1)
        res = _d(await mass.send_command("config/flows/submit", flow_id=f["flow"], values={cle: pin}))
    except Exception as err:  # noqa: BLE001
        _LOGGER.warning("Sendspin : PIN de %s non soumis : %s", pc.get("name"), err)
        _FLOWS.pop(pc_id, None)
        return "ma"
    t = str(res.get("type") or "")
    if t == "finish":
        _FLOWS.pop(pc_id, None)
        _LOGGER.info("Sendspin : %s appairé à MA", pc.get("name"))
        return "ok"
    if t == "progress":
        return "ok"  # MA termine de son côté ; l'appareil le voit à sa reconnexion (confiance « user »)
    # Un formulaire qui revient (PIN refusé) ou un abandon : on ferme le flow, l'appareil pourra recommencer.
    _FLOWS.pop(pc_id, None)
    if t == "form":
        try:
            await mass.send_command("config/flows/abort", flow_id=f["flow"])
        except Exception:  # noqa: BLE001
            pass
    return "refus"


class PcParentalSendspinAppairageView(HomeAssistantView):
    """POST {id, secret, etape: "demarrer"|"pin", lecteur?, pin?} → {ok, resultat, retour}."""

    url = URL
    name = "api:pc_parental:sendspin_appairage"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        if not isinstance(corps, dict):
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        if not entrees:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        coord = entrees[0]
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        if str(pc.get("platform") or "") != "android":
            return self.json({"ok": False, "error": "appareil"}, status_code=403)
        lg = textes.pour(self.hass, pc)
        etape = str(corps.get("etape") or "")
        if etape == "demarrer":
            r = await demarrer(self.hass, pc, str(corps.get("lecteur") or ""))
        elif etape == "pin":
            r = await soumettre_pin(self.hass, pc, str(corps.get("pin") or ""))
        else:
            return self.json({"ok": False, "error": "etape"}, status_code=400)
        return self.json({"ok": r == "ok", "resultat": r, "retour": textes.t(f"ssp.{r}", lg)})


def enregistrer_vues(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalSendspinAppairageView(hass))
