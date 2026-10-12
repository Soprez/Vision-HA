"""« Installer Vision sur un appareil » : les liens, le QR et les étapes courtes.

Une seule source pour le tableau de bord et Vision → Configurer : la dernière
version Android publiée avec l'intégration (lien stable, voir android_maj),
la ligne PowerShell de l'installateur Windows, et un QR du lien Android à
scanner depuis le téléphone ou la télé.

Aucune clé ici : ces liens ne servent qu'à télécharger, l'inscription se fait
ensuite (QR validé par un administrateur, ou clé lue dans Vision → Configurer).
Le QR est servi sans authentification, comme l'APK lui-même : une image du
tableau de bord est chargée par le navigateur sans le jeton de Home Assistant.
Il n'encode que le lien d'installation de cette maison, jamais un texte reçu.
"""

from __future__ import annotations

import io
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import android_maj

URL_QR = "/api/pc_parental/installation/qr.svg"
# L'adresse courte à taper sur une télé (Downloader) : elle renvoie au lien stable de l'APK.
URL_COURTE = "/vision"

_QR_CACHE: dict[str, bytes] = {}


def liens(hass: HomeAssistant) -> dict[str, Any]:
    """Les liens d'installation de cette maison (sans clé)."""
    from .dashboard import _adresse

    base = _adresse(hass)
    android = android_maj.installation(hass)
    return {
        "base": base,
        "android_version": android.get("version", ""),
        # Le lien stable (toujours la dernière version) ; les liens versionnés restent servis.
        "android": f"{base}{android_maj.LIEN_STABLE}" if android else "",
        "qr": f"{base}{URL_QR}?v={android['version']}" if android else "",
        "court": f"{base}{URL_COURTE}" if android else "",
        "windows": f"{base}/api/pc_parental/client/install",
        "windows_commande": (
            f"irm {base}/api/pc_parental/client/install "
            '-OutFile "$env:TEMP\\install-cp.ps1"; '
            '& "$env:TEMP\\install-cp.ps1"'
        ),
    }


def commande_windows(hass: HomeAssistant, jeton: str) -> str:
    """La commande PowerShell qui installe l'agent ET relie le PC (jeton d'appairage à usage unique)."""
    from .dashboard import _adresse

    base = _adresse(hass)
    return (
        f"irm {base}/api/pc_parental/client/install -OutFile \"$env:TEMP\\install-cp.ps1\"; "
        f"& \"$env:TEMP\\install-cp.ps1\" -Jeton '{jeton}'"
    )


def qr_svg(texte: str) -> bytes | None:
    """Le QR du texte en SVG (noir sur blanc), ou None si PyQRCode manque."""
    if texte in _QR_CACHE:
        return _QR_CACHE[texte]
    try:
        import pyqrcode
    except ImportError:
        return None
    tampon = io.BytesIO()
    pyqrcode.create(texte, error="M").svg(
        tampon, scale=6, quiet_zone=2, background="#ffffff", module_color="#000000",
        xmldecl=False, svgns=True,
    )
    _QR_CACHE.clear()  # un seul lien à la fois : celui de la version publiée
    _QR_CACHE[texte] = tampon.getvalue()
    return _QR_CACHE[texte]


def markdown(hass: HomeAssistant, avec_titre: bool = True, langue: str | None = None) -> str:
    """La section, en Markdown : pour le tableau de bord et pour Vision → Configurer (langue de HA par défaut)."""
    from . import textes

    lg = langue or textes.ha(hass)
    l = liens(hass)
    morceaux = [textes.t("inst.titre", lg)] if avec_titre else []
    if l["android"]:
        morceaux.append(textes.t("inst.android", lg, version=l['android_version'], lien=l['android'], qr=l['qr'],
                                 court=l['court']))
    else:
        morceaux.append(textes.t("inst.android_absent", lg))
    morceaux.append(textes.t("inst.windows", lg, commande=l['windows_commande'], lien=l['windows']))
    return "\n".join(morceaux)


class PcParentalInstallationQrView(HomeAssistantView):
    """Le QR du lien d'installation Android de cette maison."""

    url = URL_QR
    name = "api:pc_parental:installation:qr"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.Response:
        from . import textes

        lien = liens(self.hass)["android"]
        if not lien:
            return web.Response(status=404, text=textes.t("http.aucune_version", textes.ha(self.hass)))
        svg = await self.hass.async_add_executor_job(qr_svg, lien)
        if svg is None:
            return web.Response(status=503, text=textes.t("inst.pyqrcode", textes.ha(self.hass)))
        return web.Response(
            body=svg, content_type="image/svg+xml",
            headers={"Cache-Control": "no-cache"},
        )


class PcParentalAdresseCourteView(HomeAssistantView):
    """`/vision` : l'adresse courte qu'on tape sur une télé, vers la dernière version de l'APK."""

    url = URL_COURTE
    name = "api:pc_parental:adresse_courte"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.Response:
        if not android_maj.installation(self.hass):
            from . import textes

            return web.Response(status=404, text=textes.t("http.aucune_version", textes.ha(self.hass)))
        raise web.HTTPFound(android_maj.LIEN_STABLE)
