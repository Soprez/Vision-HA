"""Vision → Configurer → « Demandes de déverrouillage » (1.68.0) : l'écran de l'administrateur.

Les briques (demande, décision signée, code, QR) sont dans deverrouillage.py ; ici, seulement ce
qu'il faut pour les montrer : le QR d'une approbation, servi en SVG derrière un chemin signé (la
session de cet administrateur, le temps de l'approbation), comme le QR d'appairage.
"""

from __future__ import annotations

import secrets
import time
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from .const import DOMAIN

_CLE = f"{DOMAIN}_deverrouillage_qr_ecran"


def _qrs(hass: HomeAssistant) -> dict[str, tuple[str, float]]:
    d = hass.data.setdefault(_CLE, {})
    maintenant = time.time()
    for j in [j for j, (_, fin) in d.items() if fin < maintenant]:
        d.pop(j, None)
    return d


def deposer_qr(hass: HomeAssistant, contenu: str, secondes: int) -> str:
    """Garde le contenu du QR le temps de l'approbation ; renvoie le chemin (à signer) de l'image."""
    jeton = secrets.token_urlsafe(16)
    _qrs(hass)[jeton] = (contenu, time.time() + secondes)
    return f"/api/pc_parental/deverrouillage/qr/{jeton}.svg"


def libelle_demande(d: dict[str, Any], langue: str | None = None) -> str:
    """Une demande en une ligne, dans `langue` (sans langue : celle de Home Assistant)."""
    from . import textes
    from .deverrouillage import libelle

    qui = d.get("personne") or d.get("nom") or "?"
    texte = textes.t("dev.ecran_libelle", langue, qui=qui, nom=d.get('nom'), quoi=libelle(str(d.get('action') or ''), langue),
                     minutes=d.get('minutes'))
    if d.get("motif"):
        texte += textes.t("dev.ecran_motif", langue, motif=d['motif'])
    return texte


class QrEcranView(HomeAssistantView):
    url = "/api/pc_parental/deverrouillage/qr/{jeton}.svg"
    name = "api:pc_parental:deverrouillage_qr_ecran"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request, jeton: str) -> web.Response:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return web.Response(status=403)
        trouve = _qrs(self.hass).get(jeton)
        if trouve is None:
            from . import textes

            return web.Response(status=404, text=textes.t("commun.qr_expire", textes.ha(self.hass)))
        from .appairage import qr_svg

        svg = await self.hass.async_add_executor_job(qr_svg, trouve[0])
        return web.Response(body=svg, content_type="image/svg+xml", headers={"Cache-Control": "no-store"})


def enregistrer(hass: HomeAssistant) -> None:
    if hass.data.get(f"{_CLE}_vue"):
        return
    hass.data[f"{_CLE}_vue"] = True
    hass.http.register_view(QrEcranView(hass))
