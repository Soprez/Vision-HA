"""L'écran de veille montre les vraies cartes de Home Assistant.

On crée son tableau de bord dans Home Assistant, on le rend disponible dans
Vision, et chaque appareil l'affiche tel quel, dans son propre thème : la page
`/vision-veille` (un panneau de Vision) dessine les cartes avec le moteur de
Home Assistant lui-même. L'appareil n'a rien à redessiner.

- `POST /api/pc_parental/veille/acces` (identifiant et secret de l'appareil) :
  la liste des tableaux de cet appareil. **Plus de jeton** depuis la 1.61.0 : le
  jeton de l'utilisateur d'affichage, remis à tout appareil inscrit (téléphone
  d'un enfant compris), ouvrait la lecture de tout Home Assistant. Le panneau ne
  s'ouvre donc plus que dans un navigateur déjà connecté à Home Assistant ;
  l'appareil garde son écran de veille à lui.
- `GET /api/pc_parental/veille/entree` : la porte d'entrée. Un jeton éventuel arrive
  dans le fragment de l'adresse (jamais envoyé au serveur), elle le range dans le
  navigateur puis ouvre le panneau.
- `GET /api/pc_parental/veille/page` : les tableaux rendus disponibles pour cet
  appareil, avec la configuration d'origine de chaque carte.
"""

from __future__ import annotations

import logging
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers.storage import Store

from . import veille_dash

_LOGGER = logging.getLogger(__name__)

NOM_UTILISATEUR = "Vision affichage"
PANNEAU = "vision-veille"
MAX_TABLEAUX = 16

ENTREE = """<!doctype html><html lang="fr"><head><meta charset="utf-8"><title>Vision</title>
<style>html,body{margin:0;height:100%;background:#000}</style></head><body><script>
(function(){
  // 1.77.0 : le ticket de CET appareil (jamais un jeton Home Assistant) ; il n'ouvre que la passerelle de la veille.
  var p=new URLSearchParams(location.hash.slice(1)),t=p.get('t')||'',q=p.get('q')||'';
  var c=new URLSearchParams(q).get('fond');if(c){document.body.style.background='#'+c;}
  if(!t){return;}
  try{
    localStorage.setItem('hassTokens',JSON.stringify({access_token:t,token_type:'Bearer',expires_in:86400,
      hassUrl:location.origin,clientId:location.origin+'/',expires:Date.now()+86000000,refresh_token:''}));
    localStorage.setItem('dockedSidebar','"always_hidden"');
    localStorage.setItem('selectedLanguage','"fr"');
  }catch(e){}
  // Le frontend parle à /api/websocket : on le dirige vers la passerelle, qui ne montre que les cartes de cet appareil.
  var W=window.WebSocket;
  var V=function(u,pr){u=String(u);if(u.slice(-14)==='/api/websocket'){u=u.slice(0,-14)+'/api/pc_parental/veille/ws';}return pr===undefined?new W(u):new W(u,pr);};
  V.prototype=W.prototype;V.CONNECTING=0;V.OPEN=1;V.CLOSING=2;V.CLOSED=3;window.WebSocket=V;
  // 1.86.5 : pas de service worker. Le frontend installe le sien puis recharge la page quand il prend la main ;
  // rechargée, la page parle directement à /api/websocket, le ticket y est refusé : écran de connexion (veille noire).
  // 1.97.5 : un service worker DÉJÀ installé (profil gardé d'une veille d'avant) survivait : il se mettait à jour,
  // rechargeait la page, et la veille finissait sur l'écran de connexion (blanc, logo bleu). On attend qu'il soit
  // vraiment désinscrit ; s'il tenait encore cette page, on la recharge une fois, libre (le ticket reste en adresse).
  var S=null;try{S=navigator.serviceWorker||null;}catch(e){}
  if(S){S.register=function(){return new Promise(function(){});};}
  var tenue=!!(S&&S.controller),deja=sessionStorage.getItem('vision_sw_libre')==='1';
  var liberer=S?S.getRegistrations().then(function(l){return Promise.all(l.map(function(r){return r.unregister();}));}).catch(function(){}):Promise.resolve();
  liberer.then(function(){
    if(tenue&&!deja){sessionStorage.setItem('vision_sw_libre','1');location.reload();return;}
    // Puis le vrai frontend de Home Assistant, sur le panneau de la veille, dans cette même fenêtre.
    history.replaceState(null,'','/vision-veille?'+q);
    fetch('/',{credentials:'same-origin',cache:'no-store'}).then(function(r){return r.text();}).then(function(h){
      document.open();document.write(h);document.close();
    });
  });
})();
</script></body></html>"""


def _coord(hass: HomeAssistant):
    from .const import DOMAIN

    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


async def revoquer_jeton(hass: HomeAssistant) -> None:
    """Révoque le jeton que les versions d'avant la 1.61.0 remettaient aux appareils.

    L'utilisateur « Vision affichage » reste (il appartient à la maison) ; seul le
    jeton créé par Vision disparaît, et Vision n'en recrée plus.
    """
    magasin = Store(hass, 1, "pc_parental.veille_acces")
    lu = await magasin.async_load() or {}
    if not lu.get("jeton"):
        return
    try:
        rafraichi = hass.auth.async_validate_access_token(lu["jeton"])
        if rafraichi is not None:
            hass.auth.async_remove_refresh_token(rafraichi)
            _LOGGER.warning("Écran de veille : le jeton d'affichage remis aux appareils est révoqué.")
    except Exception:  # noqa: BLE001 - un jeton illisible ne vaut rien de toute façon
        _LOGGER.exception("Écran de veille : révocation du jeton d'affichage")
    await magasin.async_save({"user_id": lu.get("user_id") or "", "jeton": ""})
    hass.data.pop("pc_parental_veille_jeton", None)


def _cartes_de(vue: dict[str, Any]) -> list[dict[str, Any]]:
    """Les sections d'une vue et leurs cartes d'origine ; chaque carte garde les clés de ses
    éléments (celles que l'on coche dans Vision et sur l'appareil)."""
    sections: list[dict[str, Any]] = []

    vues_cles: dict[str, int] = {}

    def _carte(carte: Any) -> dict[str, Any] | None:
        if not isinstance(carte, dict) or not carte.get("type"):
            return None
        cles = [f["cle"] for f in veille_dash._feuilles(carte) if f.get("cases")]
        # Une clé par carte, pour la cocher sur l'appareil : stable tant que la carte ne change pas.
        import hashlib
        import json

        cle = hashlib.sha1(json.dumps(carte, sort_keys=True, default=str).encode("utf-8")).hexdigest()[:10]
        n = vues_cles.get(cle, 0) + 1
        vues_cles[cle] = n
        if n > 1:
            cle = "{}-{}".format(cle, n)
        return {"cle": cle, "cles": cles, "config": carte}

    for section in vue.get("sections") or []:
        if not isinstance(section, dict):
            continue
        cartes = [c for c in (_carte(x) for x in section.get("cards") or []) if c]
        if cartes:
            portee = section.get("column_span")
            sections.append({"titre": str(section.get("title") or ""), "span": portee if isinstance(portee, int) else 1, "cartes": cartes})
    libres = [c for c in (_carte(x) for x in vue.get("cards") or []) if c]
    if libres:
        sections.extend({"titre": "", "span": 1, "cartes": [c]} for c in libres)
    return sections


async def _typees(hass: HomeAssistant, sections: list[dict[str, Any]], pc_id: str) -> dict[str, Any]:
    """Veille-lot : les cases typées (docs/veille-contrat.md §4.2) des cartes de ce tableau, résolues pour CET
    appareil, par clé de carte. Rangées à côté des cartes (jamais dedans) : la passerelle tire des cartes seules la
    liste des entités permises, une case typée ne l'élargit donc pas."""
    from . import veille_cases

    sortie: dict[str, Any] = {}
    for s in sections:
        for c in s["cartes"]:
            try:
                cases = veille_cases.carte_typee(c["config"], None)
                r = await veille_cases.resoudre(hass, cases[0], pc_id) if cases else None
            except Exception:  # noqa: BLE001 - la carte reste dessinée par Home Assistant
                _LOGGER.debug("Veille : case typée illisible", exc_info=True)
                r = None
            if r is not None:
                sortie[c["cle"]] = r
    return sortie


async def page(hass: HomeAssistant, coord, pc: dict[str, Any] | None, ecran: str, essai: str = "",
               typees: bool = True) -> dict[str, Any]:
    """`typees` : joindre les cases typées de l'appareil (la passerelle) ; jamais pour un essai ni sans appareil."""
    from . import veille_tableaux as vt

    ecran = ecran if ecran in veille_dash.ECRANS else "tele"
    qui = vt.profil(hass, coord, pc)
    tableaux: list[dict[str, Any]] = []
    # « essai » : montrer un tableau de bord précis, tel quel, sans qu'il soit rendu disponible (mise au point des styles).
    adresses = [essai] if essai else list((getattr(coord.store, "veille_dash", None) or {}).keys())
    for adresse in adresses:
        choix = veille_dash._choix(coord, adresse)
        if essai:
            choix = {**choix, "actif": True, "vues": {}, "cartes": {}}
        elif not choix["actif"] or not vt._vise(choix, ecran, qui):
            continue
        config = await veille_dash._config(hass, adresse)
        vues = [v for v in (config or {}).get("views") or [] if isinstance(v, dict)]
        tableau = veille_dash._lovelace(hass).get(None if adresse == "lovelace" else adresse)
        nom = veille_dash._titre_tableau(None if adresse == "lovelace" else adresse, tableau)
        for rang, vue in enumerate(vues):
            cv = veille_dash._cle_vue(vue, rang)
            if not choix["vues"].get(cv, True) or len(tableaux) >= MAX_TABLEAUX:
                continue
            sections = []
            for s in _cartes_de(vue):
                gardees = []
                for c in s["cartes"]:
                    # Masquée dans Vision seulement si tous ses éléments le sont.
                    if c["cles"] and all(not choix["cartes"].get("{}/{}".format(cv, k), True) for k in c["cles"]):
                        continue
                    gardees.append(c)
                if gardees:
                    sections.append({"titre": s["titre"], "span": s.get("span", 1), "cartes": gardees})
            if not sections:
                continue
            titre_vue = str(vue.get("title") or "")
            tableau_page = {
                "id": "dash_{}_{}".format(adresse, cv),
                "titre": titre_vue if titre_vue and len(vues) > 1 else nom,
                "duree": choix["duree"],
                "colonnes": vue.get("max_columns") if isinstance(vue.get("max_columns"), int) else 4,
                "sections": sections,
            }
            if typees and pc and pc.get("id") and not essai:
                tableau_page["typees"] = await _typees(hass, sections, str(pc["id"]))
            tableaux.append(tableau_page)
    horloge = next((t for t in vt.configuration(coord) if t["code"] == "horloge"), None)
    # 1.93.05 (l'auteur, retour 35) : plus de météo sous l'horloge de la veille ; la page ne nomme plus d'entité météo
    # (l'appareil ne peut donc plus la lire par la passerelle).
    return {
        "ok": True,
        "horloge": {"actif": bool(horloge and horloge["actif"]), "duree": int((horloge or {}).get("duree") or 15)},
        "tableaux": tableaux,
    }


def _nom_carte(hass: HomeAssistant, carte: dict[str, Any]) -> str:
    for champ in ("title", "name", "primary", "heading"):
        v = carte.get(champ)
        if isinstance(v, str) and v.strip() and "{{" not in v:
            return v.strip()[:60]
    eid = carte.get("entity")
    if not isinstance(eid, str):
        ents = carte.get("entities") or carte.get("include") or []
        premier = ents[0] if isinstance(ents, list) and ents else None
        eid = premier if isinstance(premier, str) else (premier or {}).get("entity") if isinstance(premier, dict) else None
    if isinstance(eid, str) and eid:
        etat = hass.states.get(eid)
        if etat is not None:
            return str(etat.attributes.get("friendly_name") or eid)[:60]
    genre = str(carte.get("type") or "carte").replace("custom:", "").replace("-card", "").replace("-", " ")
    return genre.capitalize()[:60]


def legere(hass: HomeAssistant, complet: dict[str, Any]) -> list[dict[str, Any]]:
    """Pour les réglages de l'appareil : les tableaux disponibles et leurs cartes, par nom (sans configuration)."""
    sortie = []
    for t in complet.get("tableaux") or []:
        cartes = []
        for s in t["sections"]:
            section = s["titre"]
            for c in s["cartes"]:
                config = c["config"]
                if config.get("type") == "heading":
                    section = str(config.get("heading") or section)
                    continue
                cartes.append({"cle": c["cle"], "cles": c.get("cles") or [], "nom": _nom_carte(hass, config), "section": section})
        sortie.append({"id": t["id"], "titre": t["titre"], "cartes": cartes})
    return sortie


class PcParentalVeilleAccesView(HomeAssistantView):
    url = "/api/pc_parental/veille/acces"
    name = "api:pc_parental:veille_acces"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        from . import textes

        textes.parler(textes.pour(self.hass, pc))
        from . import modules

        # 1.82.0 : les vraies cartes (tableaux de bord de HA) relèvent de « Tableaux HA dans la veille ».
        if refus := modules.refus(self, coord.store, "veille_tableaux"):
            return refus
        try:
            tableaux = legere(self.hass, await page(self.hass, coord, pc, str(corps.get("ecran") or "tele"), typees=False))
            # 1.77.0 : un ticket propre à l'appareil, qui n'ouvre que la passerelle (veille_passerelle.py).
            from . import veille_passerelle

            donner = tableaux and getattr(coord.store, "veille_rendu", False)
            return self.json({"ok": True, "jeton": veille_passerelle.ticket(pc) if donner else "", "tableaux": tableaux})
        except Exception as err:  # noqa: BLE001
            _LOGGER.exception("Accès d'affichage de l'écran de veille")
            return self.json({"ok": False, "error": str(err)}, status_code=500)


class PcParentalVeilleEntreeView(HomeAssistantView):
    url = "/api/pc_parental/veille/entree"
    name = "api:pc_parental:veille_entree"
    requires_auth = False

    async def get(self, request: web.Request) -> web.Response:
        return web.Response(text=ENTREE, content_type="text/html", headers={"Cache-Control": "no-store"})


class PcParentalVeillePageView(HomeAssistantView):
    url = "/api/pc_parental/veille/page"
    name = "api:pc_parental:veille_page"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.Response:
        coord = _coord(self.hass)
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        from . import modules

        if refus := modules.refus(self, coord.store, "veille_tableaux"):  # 1.82.0
            return refus
        pc = coord.store.get(str(request.query.get("id") or ""))
        return self.json(await page(self.hass, coord, pc, str(request.query.get("ecran") or "tele"), str(request.query.get("essai") or "")[:80], typees=False))


def _module(hass: HomeAssistant) -> str:
    """L'adresse du panneau, servi par l'intégration : elle change à chaque nouvelle version du fichier."""
    from . import cartes

    return cartes.adresse(hass, cartes.PANNEAU)


def installer(hass: HomeAssistant) -> None:
    hass.http.register_view(PcParentalVeilleAccesView(hass))
    hass.http.register_view(PcParentalVeilleEntreeView())
    hass.http.register_view(PcParentalVeillePageView(hass))
    from .veille_passerelle import VeillePasserelleView

    hass.http.register_view(VeillePasserelleView(hass))
    panneau(hass)


def panneau(hass: HomeAssistant) -> None:
    """Déclare le panneau de l'écran de veille, ou lui donne sa nouvelle adresse (rechargement de l'intégration)."""
    try:
        from homeassistant.components import frontend

        module = _module(hass)
        deja = hass.data.get(frontend.DATA_PANELS, {}).get(PANNEAU)
        if deja is not None and ((getattr(deja, "config", None) or {}).get("_panel_custom") or {}).get("module_url") == module:
            return
        frontend.async_register_built_in_panel(
            hass,
            component_name="custom",
            frontend_url_path=PANNEAU,
            config={"_panel_custom": {"name": "vision-veille-panel", "module_url": module, "embed_iframe": False, "trust_external": False}},
            require_admin=False,
            update=deja is not None,
        )
    except Exception:  # noqa: BLE001
        _LOGGER.exception("Panneau de l'écran de veille")
