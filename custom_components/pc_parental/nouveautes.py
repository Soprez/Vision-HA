"""« Quoi de neuf » : les notes de version servies aux appareils, une seule fois par version.

Les notes sont des fichiers de données livrés avec l'intégration, un par version :
``notes_version/<version>.json`` (format dans docs/nouveautes.md). Chaque point dit pour quelles
cibles il vaut (``ha`` : tableau et pages de Home Assistant, ``telephone``, ``tele``, ``pc``) et,
au besoin, s'il est réservé aux Décideurs (``"public": "decideurs"``) ou à un module optionnel (``"module": "son"`` :
le point n'est servi que si ce module est allumé ; un module coupé ne laisse aucune trace, notes comprises).

Chaque appareil garde dans sa fiche la dernière version qu'il a vue (``nouveautes_vu``). Un
appareil qui n'a encore rien vu (appareil neuf, ou appli d'avant cette fonction) ne reçoit que
la dernière version : l'historique entier serait un mur de texte qui ne lui apprend rien.
Les notes d'une version plus récente que celle de l'intégration (préparées d'avance) ne sortent pas.
"""

from __future__ import annotations

import json
import logging
import pathlib
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import modules, roles
from .const import DOMAIN, VERSION

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/nouveautes"
DOSSIER = pathlib.Path(__file__).parent / "notes_version"
LANGUES = ("fr", "en", "de", "es", "it", "nl")
CIBLES = ("ha", "telephone", "tele", "pc")
# Un appareil resté longtemps éteint : trois versions au plus, de la plus récente à la plus ancienne.
MAX_VERSIONS = 3
CLE_VU = "nouveautes_vu"
_CACHE = f"{DOMAIN}_nouveautes"
# La version de l'intégration ; les essais la changent pour simuler une mise à jour.
COURANTE = VERSION


def cle_version(v: Any) -> tuple[int, ...] | None:
    """« 2.2.12 » → (2, 2, 12) ; None si ce n'est pas une version."""
    try:
        parts = tuple(int(x) for x in str(v or "").strip().split("."))
    except ValueError:
        return None
    return parts if parts and all(x >= 0 for x in parts) else None


def _lire_tout(dossier: pathlib.Path) -> dict[str, dict[str, Any]]:
    """Lit tous les fichiers de notes (hors boucle d'événements). Un fichier abîmé est ignoré, pas fatal."""
    notes: dict[str, dict[str, Any]] = {}
    if not dossier.is_dir():
        return notes
    for fichier in sorted(dossier.glob("*.json")):
        try:
            d = json.loads(fichier.read_text(encoding="utf-8"))
            v = str(d.get("version") or fichier.stem)
            if cle_version(v) is None or not isinstance(d.get("points"), list):
                raise ValueError("version_ou_points_manquants")  # pour le journal seulement
            notes[v] = d
        except (OSError, ValueError, AttributeError) as err:
            _LOGGER.warning("Notes de version illisibles (%s) : %s", fichier.name, err)
    return notes


async def charger(hass: HomeAssistant) -> dict[str, dict[str, Any]]:
    """Les notes, lues une fois puis gardées en mémoire."""
    if _CACHE not in hass.data:
        hass.data[_CACHE] = await hass.async_add_executor_job(_lire_tout, DOSSIER)
    return hass.data[_CACHE]


def cible_de(pc: dict[str, Any], demandee: str = "") -> str:
    """La cible de l'appareil : celle qu'il demande si elle existe, sinon déduite de son type."""
    if demandee in CIBLES:
        return demandee
    t = str(pc.get("type") or "")
    if t == "tv":
        return "tele"
    if t in ("telephone", "tablette", "android") or pc.get("platform") == "android":
        return "telephone"
    return "pc"


def langue_de(hass: HomeAssistant, pc: dict[str, Any], demandee: str = "") -> str:
    """Langue demandée, sinon celle que l'appareil a donnée au relevé, sinon celle de Home Assistant."""
    for brut in (demandee, pc.get("langue"), hass.config.language):
        lg = str(brut or "").split("-")[0].split("_")[0].lower()
        if lg in LANGUES:
            return lg
    return "fr"


def texte(point: dict[str, Any], langue: str) -> str:
    """Le texte d'un point dans la langue voulue ; repli en anglais, puis en français."""
    t = point.get("texte") or {}
    for lg in (langue, "en", "fr"):
        if str(t.get(lg) or "").strip():
            return str(t[lg]).strip()
    return ""


def a_voir(hass: HomeAssistant, store, pc: dict[str, Any], cible: str, langue: str) -> list[dict[str, Any]]:
    """Les notes que cet appareil n'a pas encore vues, pour sa cible et sa langue (plus récentes d'abord)."""
    notes = hass.data.get(_CACHE) or {}
    courante = cle_version(COURANTE) or ()
    versions = sorted(
        (v for v in notes if (cle_version(v) or ()) <= courante), key=lambda v: cle_version(v) or (), reverse=True
    )
    vu = cle_version(pc.get(CLE_VU))
    # Rien de vu encore : seulement la dernière version (pas tout l'historique).
    versions = [v for v in versions if cle_version(v) > vu] if vu is not None else versions[:1]
    decideur = roles.a(store, pc, "gerer")
    sortie: list[dict[str, Any]] = []
    for v in versions[:MAX_VERSIONS]:
        points = [
            texte(p, langue)
            for p in notes[v].get("points") or []
            if isinstance(p, dict) and cible in (p.get("cibles") or [])
            and (p.get("public") != "decideurs" or decideur)
            and not (p.get("module") and modules.coupe(store, str(p["module"])))
        ]
        points = [p for p in points if p]
        if points:
            sortie.append({"version": v, "date": str(notes[v].get("date") or ""), "points": points})
    return sortie


def marquer_vu(pc: dict[str, Any], version: Any) -> bool:
    """« Vu jusqu'à la version X » : ne recule jamais, ne dépasse pas la version installée. Vrai si changé."""
    v = cle_version(version)
    if v is None:
        return False
    courante = cle_version(COURANTE) or ()
    if v > courante:
        v = courante
    ancien = cle_version(pc.get(CLE_VU))
    if ancien is not None and v <= ancien:
        return False
    pc[CLE_VU] = ".".join(str(x) for x in v)
    return True


def noter_langue(pc: dict[str, Any], langue: Any) -> bool:
    """La langue que l'appareil donne au relevé (« fr », « de-CH »…). Vrai si changée."""
    lg = str(langue or "").split("-")[0].split("_")[0].lower()
    if lg in LANGUES and pc.get("langue") != lg:
        pc["langue"] = lg
        return True
    return False


async def pour_releve(hass: HomeAssistant, store, pc: dict[str, Any], corps: dict[str, Any]) -> tuple[str, bool]:
    """Au relevé : note la langue et le « vu » éventuels ; rend (version la plus récente à voir ou "", à enregistrer)."""
    changer = noter_langue(pc, corps.get("langue"))
    if "nouveautes_vu" in corps:
        changer = marquer_vu(pc, corps.get("nouveautes_vu")) or changer
    await charger(hass)
    notes = a_voir(hass, store, pc, cible_de(pc), langue_de(hass, pc))
    return (notes[0]["version"] if notes else ""), changer


class PcParentalNouveautesView(HomeAssistantView):
    """POST /api/pc_parental/nouveautes : lire les notes non vues, ou dire « vu jusqu'à X ».

    POST et non GET : le secret de l'appareil voyage dans le corps, jamais dans l'adresse
    (les adresses finissent dans les journaux). Même porte que les autres vues d'appareil.
    """

    url = URL
    name = "api:pc_parental:nouveautes"
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
        await charger(self.hass)
        changer = noter_langue(pc, corps.get("langue"))
        if "vu" in corps:
            changer = marquer_vu(pc, corps.get("vu")) or changer
        if changer:
            await coord.store.async_save()
        cible = cible_de(pc, str(corps.get("cible") or ""))
        langue = langue_de(self.hass, pc, str(corps.get("langue") or ""))
        return self.json({
            "ok": True,
            "version": COURANTE,
            "cible": cible,
            "langue": langue,
            "vu": str(pc.get(CLE_VU) or ""),
            "nouveautes": a_voir(self.hass, coord.store, pc, cible, langue),
        })


def enregistrer_vues(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalNouveautesView(hass))
