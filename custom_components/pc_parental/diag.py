"""1.94 · « Signaler un problème » (partie Home Assistant) : le journal d'une période, anonymisé avant de sortir.

- un `logging.Handler` garde les derniers enregistrements de l'intégration (INFO et plus) et les événements
  `pc_parental_*` / `vision_musique_commande` ;
- `GET`-like `POST /api/pc_parental/diag/periode` (appareil d'un responsable + code, id et secret dans le corps, jamais
  dans l'adresse) rend les lignes de la période, **déjà anonymisées**, avec le nombre de remplacements ;
- l'anonymisation est un module sans dépendance (`Anonymiseur`) : les mêmes règles et le même corpus d'essai
  (`essai/corpus_anonymisation.json`) servent côté appli (Kotlin).
"""
from __future__ import annotations

import collections
import logging
import re
import time
from datetime import datetime, timezone
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import Event, HomeAssistant, callback

from . import roles, textes
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

URL_DIAG = "/api/pc_parental/diag/periode"
CLE = "pc_parental_diag"
MAX_TAMPON = 3000
MAX_OCTETS = 1_000_000  # 1 Mo au plus dans la réponse
MAX_PERIODE_S = 3600  # une période au plus d'une heure (l'enregistrement dure 10 min)
EVENEMENTS_SUIVIS = ("pc_parental_", "vision_musique_commande")
MOTS_SECRETS = ("secret", "token", "jeton", "code_octroi", "code=", "code:", "password", "mot de passe")
# Les domaines de Vision restent lisibles ; tout autre domaine devient [site].
DOMAINES_GARDES = ("soprez.org", "github.com/soprez")

_IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
_IPV6 = re.compile(r"\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\b")
_MAC = re.compile(r"\b(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}\b")
_MAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_TEL = re.compile(r"(?<![\w.])(?:\+\d{1,3}|0)(?:[\s.-]?\d){8,9}(?![\w.])")
_VERSION_AVANT = re.compile(r"(?i)(?:\bv|version|versions|appli|agent|vision|int[ée]gration|ha|core)\s*[:=]?\s*$")
_URL = re.compile(r"https?://[^\s\"'<>)]+", re.I)
_DOMAINE = re.compile(r"\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:com|net|org|fr|io|tv|app|dev|co|me|gg|ly|be|eu|uk|de|us|ai|tk|xyz|cloud|info)\b", re.I)
_VALEUR_SECRETE = re.compile(
    r"(?i)((?:secret|token|jeton|code_octroi|code|password|mot de passe)[\"']?\s*[:=]\s*[\"']?)([^\s\"',;}&]+)")


class Anonymiseur:
    """Remplace ce qui identifie un lieu, une personne ou un appareil ; la table reste stable pour toute la période
    (le même objet donne toujours le même pseudonyme), ce qui garde le journal lisible."""

    def __init__(self, personnes: list[str] | None = None, appareils: list[str] | None = None) -> None:
        self.remplacements = 0
        self._ips: dict[str, str] = {}
        # Les pseudonymes se numérotent par groupe ; on remplace les noms les plus longs d'abord
        # (« TEL-LOU » avant « Lou »), sinon un prénom mangerait un bout du nom de l'appareil.
        noms = [(n, f"personne-{i}") for i, n in enumerate(_noms_utiles(personnes), 1)]
        noms += [(n, f"appareil-{i}") for i, n in enumerate(_noms_utiles(appareils), 1)]
        noms.sort(key=lambda x: -len(x[0]))
        self._noms: list[tuple[re.Pattern[str], str]] = [(_motif_nom(n), p) for n, p in noms]

    def _compter(self, remplace: str) -> str:
        self.remplacements += 1
        return remplace

    def _ip(self, m: re.Match[str]) -> str:
        brut = m.group(0)
        if ":" in brut and "::" not in brut and not re.search(r"[a-fA-F]", brut) and not _MAC.fullmatch(brut):
            return brut  # « 00:12:30 » est une durée, pas une adresse IPv6 (qui a presque toujours une lettre ou « :: »)
        if brut in ("127.0.0.1", "0.0.0.0", "::1"):
            return brut
        if _IPV4.fullmatch(brut):
            parties = brut.split(".")
            # Un numéro de version n'est pas une adresse : un nombre au-delà de 255, un zéro de tête (« 1.93.06.3 »),
            # ou « version » / « v » juste avant (« 2.5.12.15 » serait sinon une adresse valide).
            if any(int(x) > 255 or (len(x) > 1 and x[0] == "0") for x in parties):
                return brut
            if _VERSION_AVANT.search(m.string[max(0, m.start() - 16):m.start()]):
                return brut
        if brut not in self._ips:
            self._ips[brut] = f"ip-{len(self._ips) + 1}"
        return self._compter(self._ips[brut])

    def _site(self, m: re.Match[str]) -> str:
        brut = m.group(0)
        if any(d in brut.lower() for d in DOMAINES_GARDES):
            return brut
        return self._compter("[site]")

    def ligne(self, texte: str) -> str:
        """Une ligne anonymisée. Ordre voulu : secrets, contacts, adresses, sites, noms."""
        t = _VALEUR_SECRETE.sub(lambda m: m.group(1) + self._compter("[secret]"), texte)
        t = _MAIL.sub(lambda m: self._compter("[contact]"), t)
        t = _MAC.sub(self._ip, t)
        t = _IPV6.sub(self._ip, t)
        t = _IPV4.sub(self._ip, t)
        t = _URL.sub(self._site, t)
        t = _DOMAINE.sub(self._site, t)
        t = _TEL.sub(lambda m: self._compter("[contact]"), t)
        for motif, pseudo in self._noms:
            t = motif.sub(lambda m, p=pseudo: self._compter(p), t)
        return t


def _noms_utiles(noms: list[str] | None) -> list[str]:
    """Les noms à masquer, dans l'ordre fourni (la numérotation des pseudonymes en dépend, l'appli la reproduit) :
    au moins 3 caractères (« Lou » oui, « Al » trop risqué), sans doublon."""
    vus: dict[str, str] = {}
    for n in noms or []:
        n = str(n or "").strip()
        if len(n) >= 3:
            vus.setdefault(n.lower(), n)
    return list(vus.values())


def _motif_nom(nom: str) -> re.Pattern[str]:
    return re.compile(r"(?<![\w])" + re.escape(nom) + r"(?![\w])", re.I)


def contient_secret(ligne: str) -> bool:
    bas = ligne.lower()
    return any(m in bas for m in MOTS_SECRETS)


# ----------------------------------------------------------------------------- Le tampon


class Tampon(logging.Handler):
    """Les derniers enregistrements de l'intégration, en mémoire seulement (rien n'est écrit sur disque)."""

    def __init__(self) -> None:
        super().__init__(level=logging.INFO)
        self.lignes: collections.deque[tuple[float, str, str]] = collections.deque(maxlen=MAX_TAMPON)

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self.lignes.append((record.created, record.levelname[:1], f"{record.name.rsplit('.', 1)[-1]}: {record.getMessage()}"))
        except Exception:  # noqa: BLE001  (un journal ne doit jamais faire tomber l'intégration)
            pass


def _tampon(hass: HomeAssistant) -> Tampon | None:
    return hass.data.get(CLE)


def installer(hass: HomeAssistant) -> None:
    """Pose le tampon et l'écoute des événements (une fois)."""
    if hass.data.get(CLE) is not None:
        return
    tampon = Tampon()
    hass.data[CLE] = tampon
    logging.getLogger("custom_components.pc_parental").addHandler(tampon)

    @callback
    def _evenement(evt: Event) -> None:
        if evt.event_type.startswith(EVENEMENTS_SUIVIS):
            tampon.lignes.append((time.time(), "E", f"{evt.event_type} {_court(evt.data)}"))

    from homeassistant.const import MATCH_ALL

    hass.data[CLE + "_ecoute"] = hass.bus.async_listen(MATCH_ALL, _evenement)


def retirer(hass: HomeAssistant) -> None:
    tampon = hass.data.pop(CLE, None)
    ecoute = hass.data.pop(CLE + "_ecoute", None)
    if ecoute is not None:
        ecoute()
    if tampon is not None:
        logging.getLogger("custom_components.pc_parental").removeHandler(tampon)


def _court(donnees: Any) -> str:
    try:
        return str({k: v for k, v in dict(donnees).items() if k not in ("secret", "token")})[:300]
    except Exception:  # noqa: BLE001
        return ""


# ----------------------------------------------------------------------------- La période


def _horodatage(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).astimezone().isoformat(timespec="milliseconds")


def periode(hass: HomeAssistant, depuis: float, jusqua: float, anonymiseur: Anonymiseur) -> dict[str, Any]:
    """Les lignes de [depuis, jusqua], anonymisées, tronquées au début au-delà de 1 Mo."""
    tampon = _tampon(hass)
    sortie: list[str] = []
    for ts, niveau, texte in (tampon.lignes if tampon else ()):
        if depuis <= ts <= jusqua:
            sortie.append(f"{_horodatage(ts)} {niveau} {anonymiseur.ligne(texte)}")
    # Le journal musique est lu à la volée : ses lignes sont déjà courtes et sans identifiant d'appareil.
    from . import journal_musique

    musique = [l for l in journal_musique._etat(hass)["lignes"] if depuis <= float(l.get("ts") or 0) <= jusqua]
    texte_musique = [f"{_horodatage(float(l['ts']))} M {anonymiseur.ligne(journal_musique.phrase(l, 'fr'))}" for l in musique]
    tout = sorted(sortie + texte_musique)
    total, gardees = 0, []
    for l in reversed(tout):  # on garde la fin (le plus récent) quand ça dépasse
        total += len(l.encode("utf-8")) + 1
        if total > MAX_OCTETS:
            break
        gardees.append(l)
    gardees.reverse()
    return {"lignes": gardees, "tronque": len(gardees) < len(tout), "remplacements": anonymiseur.remplacements}


def _anonymiseur(hass: HomeAssistant, store) -> Anonymiseur:
    personnes = [str(e.name or "") for e in hass.states.async_all("person")]
    appareils = [str(pc.get("name") or "") for pc in store.pcs.values()]
    return Anonymiseur(personnes, appareils)


def versions(hass: HomeAssistant) -> dict[str, str]:
    from homeassistant.const import __version__ as ha_core

    from . import const

    return {"ha_core": ha_core, "integration": str(getattr(const, "VERSION", "") or "")}


# ----------------------------------------------------------------------------- La vue


class PcParentalDiagView(HomeAssistantView):
    """Le journal d'une période pour « Signaler un problème » : appareil d'un responsable + preuve d'octroi.

    POST (et non GET comme dans la spécification) : l'identifiant et le secret de l'appareil, et le code, ne doivent
    jamais figurer dans une adresse.
    """

    url = URL_DIAG
    name = "api:pc_parental:diag"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        moi = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if moi is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        if moi["id"] not in roles.avec(coord.store, "gerer"):
            return self.json({"ok": False, "error": "parent"}, status_code=403)
        from . import octroi

        personne = str(moi.get("person") or "") or None
        erreur = await octroi.verifier(self.hass, coord.store, "appareil:" + str(moi["id"]), corps.get("code_octroi"), personne)
        if erreur:
            return self.json({"ok": False, "error": erreur}, status_code=403)
        try:
            jusqua = float(corps.get("jusqua") or time.time())
            depuis = float(corps.get("depuis") or (jusqua - 600))
        except (TypeError, ValueError):
            return self.json({"ok": False, "error": "periode"}, status_code=400)
        if jusqua < depuis:
            return self.json({"ok": False, "error": "periode"}, status_code=400)
        depuis = max(depuis, jusqua - MAX_PERIODE_S)
        anon = _anonymiseur(self.hass, coord.store)
        res = periode(self.hass, depuis, jusqua, anon)
        # La table d'anonymisation n'est PAS rendue : l'appli applique ses propres règles (adresses, secrets)
        # et reçoit ici la liste des noms à masquer, utile seulement pour son propre journal.
        noms = {"personnes": [str(e.name or "") for e in self.hass.states.async_all("person") if len(str(e.name or "")) >= 3],
                "appareils": [str(pc.get("name") or "") for pc in coord.store.pcs.values() if len(str(pc.get("name") or "")) >= 3]}
        return self.json({"ok": True, **res, "versions": versions(self.hass), "noms": noms,
                          "depuis": _horodatage(depuis), "jusqua": _horodatage(jusqua)})


def async_register(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalDiagView(hass))
