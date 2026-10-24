"""L'appairage Sendspin d'un appareil Vision : à faire dans Music Assistant.

2.4.5 à 2.5.12.14.1 : Home Assistant lançait lui-même le setup flow de Music Assistant (`config/players/setup`, puis
`config/flows/submit` avec le PIN) par le client interne de l'intégration Music Assistant. Revue de HACS
(hacs/default#12284) : ce client n'a pas à servir à écrire la configuration de Music Assistant, et aucun service Home
Assistant ne fait cet appairage. Vision ne le fait donc plus.

La route reste pour les applis déjà installées (1.93.06.x) : elle vérifie l'appareil comme avant (identifiant et secret,
appareil Android) puis répond, dans la langue de l'appareil, que l'appairage se fait dans Music Assistant (le PIN est
affiché sur la télé). La proposition faite à Music Assistant : docs/a-proposer-a-music-assistant.md.
"""
from __future__ import annotations

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import textes
from .const import DOMAIN

URL = "/api/pc_parental/sendspin/appairage"


class PcParentalSendspinAppairageView(HomeAssistantView):
    """POST {id, secret, etape, …} → {ok: false, resultat: "ma", retour: « à faire dans Music Assistant »}."""

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
        pc = entrees[0].store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        if str(pc.get("platform") or "") != "android":
            return self.json({"ok": False, "error": "appareil"}, status_code=403)
        return self.json({"ok": False, "resultat": "ma", "retour": textes.t("mus.a_faire_dans_ma", textes.pour(self.hass, pc))})


def enregistrer_vues(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalSendspinAppairageView(hass))
