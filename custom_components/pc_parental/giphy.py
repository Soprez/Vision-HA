"""GIF Giphy et images par URL pour les messages riches (1.94, point 4 ; conception §1.10).

Option désactivée par défaut. Tout part de Home Assistant : l'appareil ne contacte jamais l'extérieur et ne voit que des
fichiers servis par jeton (messages.py). Giphy ne voit que l'adresse de Home Assistant et le mot-clé ; aucun identifiant de
personne ni d'appareil ne lui est transmis.

- **clé API** fournie par l'utilisateur, gardée dans le stockage de l'intégration, jamais dans le code ni dans les journaux ;
- **classification** « g » (tout public) par défaut ; l'administrateur peut la relever (g, pg, pg-13, r) : elle est envoyée à Giphy
  et Home Assistant écarte en plus tout GIF dont la classification renvoyée la dépasse ;
- **limites** : 10 appels à l'API par minute (clés gratuites), 4 Mo par GIF téléchargé, https seulement, redirections suivies
  seulement entre hôtes Giphy ;
- **cache** : `.storage/pc_parental_giphy/` (50 Mo, 30 jours), recherches gardées 24 h ; un mot-clé prend le premier résultat de
  `translate`, stable grâce au cache ;
- le GIF est réencodé en WebP animé (images.py) avant d'être servi : même nombre d'images, 480 px, 15 images/s, 6 s au plus.
"""
from __future__ import annotations

import base64
import logging
import os
import re
import time
from collections import deque
from typing import Any, Awaitable, Callable
from urllib.parse import urlparse

from homeassistant.core import HomeAssistant

from . import images, textes
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

CLASSIFICATIONS = ("g", "pg", "pg-13", "r")
CLASSIFICATION_DEFAUT = "g"
LIMITE_PAR_MINUTE = 10
GIF_MAX_OCTETS = 4_000_000
IMAGE_URL_MAX_OCTETS = 12_000_000
CACHE_OCTETS = 50 * 1024 * 1024
CACHE_DUREE_S = 30 * 86400
RECHERCHE_DUREE_S = 24 * 3600
URL_API = "https://api.giphy.com/v1/gifs"
_HOTE = re.compile(r"^(api|i|media\d*)\.giphy\.com$")
_ID = re.compile(r"^[A-Za-z0-9]{5,30}$")

# fetch(url, params, max_octets) -> octets ; lève ErreurReseau. Remplaçable (essais : faux serveur Giphy).
Fetch = Callable[[str, "dict[str, Any] | None", int], Awaitable[bytes]]


class ErreurReseau(Exception):
    """Le serveur n'a pas répondu comme il faut (ou trop lourd, ou hôte refusé)."""


def rating_autorise(rating: str, maximum: str) -> bool:
    """Vrai si `rating` (celui que Giphy renvoie) ne dépasse pas `maximum`. Un rating inconnu ou absent n'est jamais accepté."""
    r = str(rating or "").strip().lower()
    m = str(maximum or CLASSIFICATION_DEFAUT).strip().lower()
    if r not in CLASSIFICATIONS:
        return False
    return CLASSIFICATIONS.index(r) <= CLASSIFICATIONS.index(m if m in CLASSIFICATIONS else CLASSIFICATION_DEFAUT)


def hote_giphy(url: str) -> bool:
    p = urlparse(url)
    return p.scheme == "https" and bool(_HOTE.match(p.hostname or "")) and not p.username


def identifiant(valeur: str) -> str | None:
    """L'identifiant d'un GIF d'après une URL Giphy (page ou fichier) ou l'identifiant lui-même ; None pour un mot-clé."""
    v = str(valeur or "").strip()
    if v.startswith("https://") or v.startswith("http://"):
        p = urlparse(v)
        if not re.match(r"^((www|media\d*|i)\.)?giphy\.com$", p.hostname or ""):
            return None
        chemins = [s for s in p.path.split("/") if s]
        if "media" in (p.hostname or "") or (p.hostname or "").startswith("i."):
            # media.giphy.com/media/<id>/giphy.gif  ou  i.giphy.com/<id>.gif
            for s in chemins:
                s = s.split(".")[0]
                if _ID.match(s) and s not in ("media", "giphy"):
                    return s
            return None
        if chemins and chemins[0] in ("gifs", "stickers", "embed"):
            fin = chemins[-1].split("-")[-1]
            return fin if _ID.match(fin) else None
        return None
    # Un mot-clé (« bravo », « bien joué ») n'est pas un identifiant : un identifiant Giphy mêle chiffres et/ou majuscules et minuscules.
    if re.fullmatch(r"[A-Za-z0-9]{10,30}", v) and (any(c.isdigit() for c in v) or (v != v.lower() and v != v.upper())):
        return v
    return None


def reglages(store) -> dict[str, Any]:
    brut = getattr(store, "giphy", None) or {}
    classification = str(brut.get("classification") or CLASSIFICATION_DEFAUT).lower()
    return {"actif": bool(brut.get("actif")), "cle": str(brut.get("cle") or ""),
            "classification": classification if classification in CLASSIFICATIONS else CLASSIFICATION_DEFAUT}


def regler(store, *, actif: bool | None = None, cle: str | None = None, classification: str | None = None) -> dict[str, Any]:
    """Pose les réglages (None : inchangé) ; rend l'état SANS la clé. textes.Erreur si une valeur est refusée."""
    courant = dict(getattr(store, "giphy", None) or {})
    if classification is not None:
        c = str(classification).lower()
        if c not in CLASSIFICATIONS:
            raise textes.Erreur("giphy.classification")
        courant["classification"] = c
    if cle is not None:
        k = str(cle).strip()
        if k and not re.fullmatch(r"[A-Za-z0-9]{16,64}", k):
            raise textes.Erreur("giphy.cle_forme")
        courant["cle"] = k
    if actif is not None:
        courant["actif"] = bool(actif)
    store.giphy = courant
    return etat(store)


def etat(store) -> dict[str, Any]:
    """Ce qu'on peut montrer : jamais la clé elle-même."""
    r = reglages(store)
    return {"actif": r["actif"], "cle_posee": bool(r["cle"]), "classification": r["classification"],
            "au_dessus_de_g": r["classification"] != CLASSIFICATION_DEFAUT}


class Giphy:
    def __init__(self, hass: HomeAssistant, store, dossier: str, langue: Callable[[], str], fetch: Fetch | None = None,
                 horloge: Callable[[], float] = time.time) -> None:
        self.hass = hass
        self.store = store
        self.langue = langue
        self.horloge = horloge
        self.cache = images.CacheDisque(dossier, CACHE_OCTETS, CACHE_DUREE_S)
        self._fetch = fetch or self._fetch_reel
        self._appels: deque[float] = deque()
        self._recherches: dict[str, tuple[float, list[dict[str, Any]]]] = {}

    # ------------------------------------------------------------ réseau

    async def _fetch_reel(self, url: str, params: dict[str, Any] | None, max_octets: int) -> bytes:
        from homeassistant.helpers.aiohttp_client import async_get_clientsession

        session = async_get_clientsession(self.hass)
        courant, param = url, params
        for _ in range(4):
            if not hote_giphy(courant):
                raise ErreurReseau("hôte refusé")
            try:
                async with session.get(courant, params=param, allow_redirects=False, timeout=15) as r:
                    if r.status in (301, 302, 303, 307, 308):
                        courant, param = str(r.headers.get("Location") or ""), None
                        continue
                    if r.status != 200:
                        raise ErreurReseau(f"état {r.status}")
                    if int(r.headers.get("Content-Length") or 0) > max_octets:
                        raise ErreurReseau("trop lourd")
                    morceaux, total = [], 0
                    async for bloc in r.content.iter_chunked(65536):
                        total += len(bloc)
                        if total > max_octets:
                            raise ErreurReseau("trop lourd")
                        morceaux.append(bloc)
                    return b"".join(morceaux)
            except ErreurReseau:
                raise
            except Exception as err:  # noqa: BLE001 - jamais l'adresse (elle porte la clé) dans le message
                raise ErreurReseau(type(err).__name__) from err
        raise ErreurReseau("trop de redirections")

    def _limiter(self) -> None:
        maintenant = self.horloge()
        while self._appels and maintenant - self._appels[0] > 60:
            self._appels.popleft()
        if len(self._appels) >= LIMITE_PAR_MINUTE:
            raise textes.Erreur("giphy.limite")
        self._appels.append(maintenant)

    def _exiger(self) -> dict[str, Any]:
        r = reglages(self.store)
        if not r["actif"]:
            raise textes.Erreur("giphy.desactive")
        if not r["cle"]:
            raise textes.Erreur("giphy.sans_cle")
        return r

    async def _api(self, chemin: str, params: dict[str, Any], reglage: dict[str, Any]) -> Any:
        import json

        self._limiter()
        parametres = {**params, "api_key": reglage["cle"], "rating": reglage["classification"], "lang": self.langue()}
        try:
            octets = await self._fetch(f"{URL_API}{chemin}", parametres, 2_000_000)
            return json.loads(octets.decode("utf-8"))
        except (ErreurReseau, ValueError) as err:
            _LOGGER.warning("Giphy : réponse indisponible (%s).", err)
            raise textes.Erreur("giphy.indisponible") from err

    # ------------------------------------------------------------ recherche et résolution

    def _retenu(self, item: Any, reglage: dict[str, Any]) -> bool:
        return isinstance(item, dict) and bool(_ID.match(str(item.get("id") or ""))) and rating_autorise(item.get("rating"), reglage["classification"])

    async def rechercher(self, q: str, limite: int = 12) -> list[dict[str, Any]]:
        """Des vignettes pour l'écran de recherche de l'appli : [{id, titre, rating, vignette (data:image/webp)}]."""
        reglage = self._exiger()
        q = " ".join(str(q or "").split())[:60]
        if len(q) < 2:
            raise textes.Erreur("giphy.recherche_courte")
        cle = f"s:{reglage['classification']}:{q.lower()}"
        trouve = self._recherches.get(cle)
        if trouve and self.horloge() - trouve[0] < RECHERCHE_DUREE_S:
            return trouve[1]
        donnees = await self._api("/search", {"q": q, "limit": max(1, min(limite, 24)), "offset": 0}, reglage)
        sortie = []
        for item in (donnees.get("data") or []) if isinstance(donnees, dict) else []:
            if not self._retenu(item, reglage):
                continue
            vignette = await self._vignette(item)
            if vignette:
                sortie.append({"id": item["id"], "titre": str(item.get("title") or "")[:80], "rating": item["rating"].lower(),
                               "vignette": "data:image/webp;base64," + base64.b64encode(vignette).decode("ascii")})
        if not sortie:
            raise textes.Erreur("giphy.rien", q=q)
        self._recherches[cle] = (self.horloge(), sortie)
        return sortie

    async def _vignette(self, item: dict[str, Any]) -> bytes | None:
        """Une petite image fixe (160 px) du résultat, gardée dans le cache."""
        identifiant_ = item["id"]
        gardee = await self.hass.async_add_executor_job(self.cache.lire, f"vignette:{identifiant_}", ".webp")
        if gardee:
            return await self.hass.async_add_executor_job(_lire, gardee)
        imgs = item.get("images") or {}
        url = ((imgs.get("fixed_width_small_still") or imgs.get("fixed_width_still") or imgs.get("preview_gif") or {}).get("url") or "")
        if not hote_giphy(str(url)):
            return None
        try:
            brut = await self._fetch(str(url), None, 600_000)
            petite = await self.hass.async_add_executor_job(images.vignette, brut, 160)
        except (ErreurReseau, images.ImageRefusee):
            return None
        await self.hass.async_add_executor_job(self.cache.ecrire, f"vignette:{identifiant_}", petite, ".webp")
        return petite

    async def mot_cle(self, mot: str) -> str:
        """Le premier résultat de `translate` pour un mot-clé (gardé 24 h : le même mot-clé donne le même GIF)."""
        reglage = self._exiger()
        mot = " ".join(str(mot or "").split())[:60]
        if len(mot) < 2:
            raise textes.Erreur("giphy.recherche_courte")
        cle = f"t:{reglage['classification']}:{mot.lower()}"
        trouve = self._recherches.get(cle)
        if trouve and self.horloge() - trouve[0] < RECHERCHE_DUREE_S:
            return trouve[1][0]["id"]
        donnees = await self._api("/translate", {"s": mot, "weirdness": 0}, reglage)
        item = donnees.get("data") if isinstance(donnees, dict) else None
        if not self._retenu(item, reglage):
            raise textes.Erreur("giphy.rien", q=mot)
        self._recherches[cle] = (self.horloge(), [{"id": item["id"]}])
        return item["id"]

    async def gif(self, valeur: str) -> dict[str, Any]:
        """Un GIF (mot-clé, URL Giphy ou identifiant) → le média prêt pour un message : {chemin, mime, type, octets}."""
        reglage = self._exiger()
        ident = identifiant(valeur)
        if ident is None:
            ident = await self.mot_cle(valeur)
        gardee = await self.hass.async_add_executor_job(self.cache.lire, f"gif:{ident}", ".webp")
        if not gardee:
            donnees = await self._api(f"/{ident}", {}, reglage)
            item = donnees.get("data") if isinstance(donnees, dict) else None
            if not self._retenu(item, reglage):
                # Un GIF connu dont la classification dépasse le réglage : refusé, quel que soit son chemin d'arrivée.
                raise textes.Erreur("giphy.classification_depassee" if isinstance(item, dict) and item.get("id") else "giphy.rien", q=str(valeur)[:60])
            imgs = item.get("images") or {}
            brut = None
            for nom in ("original", "downsized", "fixed_height", "fixed_width"):
                url = str((imgs.get(nom) or {}).get("url") or "")
                taille = int((imgs.get(nom) or {}).get("size") or 0)
                if not hote_giphy(url) or (taille and taille > GIF_MAX_OCTETS):
                    continue
                try:
                    brut = await self._fetch(url, None, GIF_MAX_OCTETS)
                    break
                except ErreurReseau:
                    continue
            if brut is None:
                raise textes.Erreur("giphy.trop_lourd")
            try:
                anime, fixe, _infos = await self.hass.async_add_executor_job(images.gif_vers_webp, brut)
            except images.ImageRefusee as err:
                raise textes.Erreur("giphy.indisponible") from err
            gardee = await self.hass.async_add_executor_job(self.cache.ecrire, f"gif:{ident}", anime, ".webp")
            await self.hass.async_add_executor_job(self.cache.ecrire, f"gif-fixe:{ident}", fixe, ".webp")
        return {"chemin": gardee, "mime": "image/webp", "type": "image", "octets": os.path.getsize(gardee)}


def _lire(chemin: str) -> bytes:
    with open(chemin, "rb") as f:
        return f.read()


# ---------------------------------------------------------------- Images par URL (https)


async def image_par_url(hass: HomeAssistant, url: str, dossier: str, fetch: Fetch | None = None) -> dict[str, Any]:
    """Une image https → le média d'un message : téléchargée par HA, type vérifié par les octets, réduite à 1280 px, en WebP."""
    from urllib.parse import urlparse as _p

    p = _p(str(url or ""))
    if p.scheme != "https" or not p.hostname or p.username:
        raise textes.Erreur("images.url_https")
    cache = images.CacheDisque(dossier, CACHE_OCTETS, CACHE_DUREE_S)
    gardee = await hass.async_add_executor_job(cache.lire, f"url:{url}", ".webp")
    if not gardee:
        if fetch is None:
            from homeassistant.helpers.aiohttp_client import async_get_clientsession

            async def fetch(u: str, params: Any, maxi: int) -> bytes:  # noqa: ANN001 - même forme que Fetch
                try:
                    async with async_get_clientsession(hass).get(u, allow_redirects=False, timeout=15) as r:
                        if r.status != 200 or int(r.headers.get("Content-Length") or 0) > maxi:
                            raise ErreurReseau("refusé")
                        octets = await r.content.read(maxi + 1)
                        if len(octets) > maxi:
                            raise ErreurReseau("trop lourd")
                        return octets
                except ErreurReseau:
                    raise
                except Exception as err:  # noqa: BLE001
                    raise ErreurReseau(type(err).__name__) from err
        try:
            brut = await fetch(url, None, IMAGE_URL_MAX_OCTETS)
            if images.detecter(brut) == "image/gif":
                webp, _fixe, _i = await hass.async_add_executor_job(images.gif_vers_webp, brut)
            else:
                webp, _mime = await hass.async_add_executor_job(images.image_vers_webp, brut)
        except ErreurReseau as err:
            raise textes.Erreur("images.indisponible") from err
        except images.ImageRefusee as err:
            raise textes.Erreur("images.refusee") from err
        gardee = await hass.async_add_executor_job(cache.ecrire, f"url:{url}", webp, ".webp")
    return {"chemin": gardee, "mime": "image/webp", "type": "image", "octets": os.path.getsize(gardee)}


def dossier_cache(hass: HomeAssistant) -> str:
    return hass.config.path(".storage", "pc_parental_giphy")


def instance(hass: HomeAssistant, coord) -> Giphy:
    """Le client Giphy de cette installation (un seul, pour que la limite par minute soit commune)."""
    cle = f"{DOMAIN}_giphy"
    g = hass.data.get(cle)
    if g is None:
        g = Giphy(hass, coord.store, dossier_cache(hass), lambda: textes.ha(hass))
        hass.data[cle] = g
    return g


# ---------------------------------------------------------------- La recherche de l'appli (écran « Envoyer un message »)

from aiohttp import web  # noqa: E402
from homeassistant.components.http import HomeAssistantView  # noqa: E402


class PcParentalGiphyView(HomeAssistantView):
    """POST /api/pc_parental/giphy {id, secret, q} : des vignettes (Décideurs seulement). La clé API ne sort jamais d'ici."""

    url = "/api/pc_parental/giphy"
    name = "api:pc_parental:giphy"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        from . import modules, roles

        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or "")) if isinstance(corps, dict) else None
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        langue = textes.pour(self.hass, pc)
        if modules.coupe(coord.store, "messages") or not roles.a(coord.store, pc, "gerer"):
            return self.json({"ok": False, "error": "droit", "retour": textes.t("commun.reserve_decideurs", langue)}, status_code=403)
        try:
            resultats = await instance(self.hass, coord).rechercher(str(corps.get("q") or ""))
        except textes.Erreur as err:
            return self.json({"ok": False, "error": "refus", "retour": textes.erreur(err, langue)}, status_code=400)
        return self.json({"ok": True, "resultats": resultats, "attribution": "Powered by GIPHY"})
