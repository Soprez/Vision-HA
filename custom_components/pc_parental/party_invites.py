"""Point 14 bis (1.99.0) : la page invité du Mode Party (QR code sur la télé). PAGE PUBLIQUE : lire avant de toucher.

Ce que peut faire un invité, et rien d'autre : chercher un titre dans Music Assistant et le proposer pour la file de
la Party en cours. Sans compte, sans accès à Home Assistant ni à Vision.

Garde-fous :
- jeton aléatoire (192 bits) créé au lancement de la Party, révoqué à l'arrêt (et au redémarrage de HA : il ne vit
  qu'en mémoire), expiré après 12 h, remplaçable par un décideur (« nouveau lien ») ; comparaison à temps constant ;
  mauvais jeton = même page « lien plus valable » ; jeton revérifié après chaque attente (Party arrêtée entre-temps) ;
- l'invité ne manipule jamais d'adresse Music Assistant : la recherche lui rend des références courtes, tirées au
  hasard et gardées côté serveur pour cette Party ; proposer = choisir une de ces références (titres seulement) ;
- limites : recherches et propositions par minute, par appareil (identifiant tiré par la page : les invités du Wi-Fi
  de la maison partagent la même adresse) et par Party ; au plus 10 titres d'invités pas encore joués dans la file,
  30 propositions en attente ; références gardées par ordre d'arrivée (les plus anciennes s'effacent) ; titres
  « explicites » écartés (enfants présents) ; corps ≤ 2 Ko (Content-Length exigé), texte ≤ 80 caractères ;
- en-têtes : CSP stricte avec nonce, aucune ressource externe (2.2.12 : les pochettes passent par Home Assistant,
  jamais l'adresse d'origine : image d'une référence de cette Party seulement, type image vérifié, taille bornée,
  cache en mémoire, compteur à part), pas de cache, pas d'indexation, pas d'iframe, pas de référent ;
- réponses courtes dans la langue du navigateur de l'invité (Accept-Language), jamais de détail technique.
"""
from __future__ import annotations

import asyncio
import html
import json
import logging
import re
import secrets
import time
from collections import OrderedDict
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from . import clins, musique, textes

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/party/invite"
CORPS_MAX = 2048
RECHERCHES_MIN_APPAREIL = 15
RECHERCHES_MIN_PARTY = 60
PROPOSITIONS_MIN_APPAREIL = 3
PROPOSITIONS_MIN_PARTY = 20
INVITES_EN_FILE_MAX = 10
ATTENTE_MAX = 30
REFS_MAX = 600
DUREE_JETON_S = 12 * 3600
COMPTEURS_MAX = 10000
REQUETES_MIN_IP = 60  # quel que soit l'identifiant d'appareil annoncé
VUES_MIN_IP = 600  # 2.2.12 : pochettes et titre en cours (plusieurs invités derrière la même box)
IMAGE_MAX = 1_500_000
IMAGES_GARDEES = 200
TYPES_IMAGE = ("image/jpeg", "image/png", "image/webp", "image/gif")


class Complet(Exception):
    """File des invités pleine : message en français, refus (pas « proposé »)."""

_compteurs: dict[str, list[float]] = {}
_purge = [0.0]


def _trop(cle: str, n: int, fenetre: float = 60.0) -> bool:
    maintenant = time.monotonic()
    if maintenant - _purge[0] > fenetre:  # purge régulière, pas seulement au-delà d'une taille
        _purge[0] = maintenant
        for k in [k for k, v in _compteurs.items() if not v or maintenant - v[-1] > fenetre]:
            _compteurs.pop(k, None)
    if cle not in _compteurs and len(_compteurs) >= COMPTEURS_MAX:
        return True  # trop de clés différentes en même temps : on refuse plutôt que de grossir
    l = [t for t in _compteurs.get(cle, []) if maintenant - t < fenetre]
    trop = len(l) >= n
    if not trop:
        l.append(maintenant)
    _compteurs[cle] = l
    return trop


def nouveau_jeton() -> str:
    return secrets.token_urlsafe(24)


def jeton_valide(st: dict[str, Any], jeton: str) -> bool:
    attendu = st.get("jeton") or ""
    if not (st.get("actif") and attendu and isinstance(jeton, str) and len(jeton) <= 64):
        return False
    if time.time() - float(st.get("jeton_ts") or 0) > DUREE_JETON_S:
        return False
    return secrets.compare_digest(jeton.encode(), attendu.encode())


def refs_de(st: dict[str, Any]) -> OrderedDict:
    r = st.get("refs")
    if not isinstance(r, OrderedDict):
        r = OrderedDict(r or {})
        st["refs"] = r
    return r


def adresse(hass: HomeAssistant, jeton: str) -> str:
    """L'adresse publique de la page invité (QR), ou "" si Home Assistant n'a pas d'adresse externe."""
    try:
        from homeassistant.helpers.network import get_url
        base = get_url(hass, allow_internal=False, prefer_external=True)
    except Exception:  # noqa: BLE001 - pas d'adresse externe : pas de QR
        base = str(getattr(hass.config, "external_url", "") or "")
    return f"{base.rstrip('/')}{URL}/{jeton}" if base and jeton else ""


def _ip(request: web.Request) -> str:
    return str(request.remote or "?")


def _appareil(request: web.Request, corps: dict[str, Any] | None) -> str:
    """Adresse + identifiant tiré par la page (les invités du Wi-Fi de la maison partagent une adresse)."""
    a = str((corps or {}).get("appareil") or "")[:32]
    a = a if a.replace("-", "").replace("_", "").isalnum() else ""
    return _ip(request) + "|" + a


_PAGE = """<!doctype html>
<html lang="__LANGUE__"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow"><meta name="referrer" content="no-referrer">
<meta name="theme-color" content="__FOND__"><meta name="color-scheme" content="__SCHEMA__">
<title>__T_titre_page__</title>
<style nonce="__NONCE__">
:root{__THEME__}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;text-size-adjust:100%}
body{margin:0;font:17px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--fond);color:var(--encre)}
.cache{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
header{position:sticky;top:0;z-index:2;background:var(--fond);padding:10px 16px;padding-top:max(10px,env(safe-area-inset-top));border-bottom:1px solid var(--bord)}
.np{display:flex;align-items:center;gap:12px;min-height:56px;max-width:600px;margin:0 auto}
.np .txt{flex:1;min-width:0}
.np small{display:block;font-size:13px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:var(--accent-texte)}
.np b,.np span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.np b{font-size:17px} .np span{font-size:16px;color:var(--encre2)}
.pochette,.vide{flex:none;width:56px;height:56px;border-radius:10px;object-fit:cover;background:var(--carte);border:1px solid var(--bord)}
.vide{display:flex;align-items:center;justify-content:center;font-size:24px;color:var(--encre2)}
[hidden]{display:none!important}
form{display:flex;gap:8px;max-width:600px;margin:10px auto 0}
input{flex:1;min-width:0;font:inherit;font-size:17px;padding:0 14px;height:48px;border:1px solid var(--bord);border-radius:12px;background:var(--carte);color:var(--encre)}
input::placeholder{color:var(--encre2)}
button{font:inherit;font-weight:600;border:0;border-radius:999px;cursor:pointer;min-height:48px;padding:0 18px;background:var(--accent);color:var(--sur-accent)}
button:disabled{opacity:.6}
button:focus-visible,input:focus-visible{outline:3px solid var(--accent);outline-offset:2px}
main{max-width:600px;margin:0 auto;padding:14px 16px 32px;padding-bottom:max(32px,env(safe-area-inset-bottom))}
h1{font-size:22px;margin:4px 0 2px} p{color:var(--encre2);margin:2px 0 0}
h2{font-size:17px;margin:14px 0 2px;color:var(--accent-texte)}
.puces{display:flex;gap:8px;overflow-x:auto;margin:12px -16px 0;padding:2px 16px 4px;scrollbar-width:none}
.puces::-webkit-scrollbar{display:none}
.puce{flex:none;background:transparent;color:var(--encre);border:1px solid var(--bord);font-weight:500;min-height:44px;padding:0 16px}
.puce[aria-pressed="true"]{background:var(--accent);color:var(--sur-accent);border-color:var(--accent)}
.fin{margin:12px 0 0;padding:12px 14px;border-radius:14px;background:var(--carte);border:1px solid var(--bord)} .fin b{display:block;font-size:19px} .fin span{color:var(--encre2)}
#statut{min-height:1.4em;font-weight:600;margin:10px 0 4px} .ok{color:var(--ok)} .ko{color:var(--ko)}
ul{list-style:none;padding:0;margin:0}
li{display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid var(--bord)}
li:last-child{border-bottom:0}
li .txt{flex:1;min-width:0}
li b,li span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
li b{font-size:17px;font-weight:600} li span{font-size:16px;color:var(--encre2)}
.plus{flex:none;width:48px;height:48px;min-height:48px;padding:0;font-size:26px;line-height:1;display:flex;align-items:center;justify-content:center}
.plus.fait{background:var(--carte);color:var(--ok);border:1px solid var(--bord);opacity:1}
.note{margin-top:20px;font-size:16px}
#mes li span.att{color:var(--accent-texte);font-weight:600}
</style></head><body>
<header>
<div class="np" id="np" aria-live="polite"><div class="vide" id="np-vide" aria-hidden="true">♪</div><img class="pochette" id="np-img" alt="" width="56" height="56" hidden>
<div class="txt"><small>__T_en_cours__</small><b id="np-titre">__T_va_commencer__</b><span id="np-artiste"></span></div></div>
<form id="f" role="search"><label for="q" class="cache">__T_champ__</label>
<input id="q" name="q" type="search" maxlength="80" autocomplete="off" enterkeyhint="search" placeholder="__T_champ__" required>
<button type="submit">__T_chercher__</button></form>
</header>
<main>
<h1>__T_titre_page__</h1>
<p>__T_intro__</p>
__PUCES____FIN__<div id="statut" role="status" aria-live="polite"></div>
<h2 id="mes-titre" hidden>__T_mes_titres__</h2>
<ul id="mes"></ul>
<h2 id="res-titre" hidden>__T_souvent__</h2>
<ul id="res"></ul>
<p class="note">__T_note__</p>
</main>
<script nonce="__NONCE__">
(function(){
  // 2.2.13 (l'auteur : « rendu de bureau dézoomé ») : Chrome en « version pour ordinateur » ignore la largeur de l'écran ;
  // la page se remet alors à l'échelle du téléphone (texte lisible, pleine largeur).
  try {
    var ecran = Math.min(screen.width || 0, screen.height || 0), large = document.documentElement.clientWidth;
    if (ecran > 0 && ecran <= 600 && large > ecran * 1.25) document.documentElement.style.zoom = String(Math.round(large / ecran * 100) / 100);
  } catch (e) {}
  var base = __BASE__, T = __TEXTES__;
  var statut = document.getElementById("statut"), res = document.getElementById("res");
  var appareil = "";
  try { appareil = sessionStorage.getItem("vision_appareil") || ""; } catch (e) {}
  if (!appareil) {
    var o = new Uint8Array(12); crypto.getRandomValues(o);
    appareil = Array.prototype.map.call(o, function(x){ return ("0" + x.toString(16)).slice(-2); }).join("");
    try { sessionStorage.setItem("vision_appareil", appareil); } catch (e) {}
  }
  // Les puces de plateforme : cumulables, « Toutes » quand aucune n'est choisie ; le choix reste sur ce téléphone.
  var choisies = [];
  try { choisies = JSON.parse(localStorage.getItem("vision_plateformes") || "[]") || []; } catch (e) { choisies = []; }
  var puces = Array.prototype.slice.call(document.querySelectorAll("#puces .puce"));
  function peindre(){
    choisies = choisies.filter(function(d){ return puces.some(function(p){ return p.getAttribute("data-d") === d; }); });
    puces.forEach(function(p){ var d = p.getAttribute("data-d"); p.setAttribute("aria-pressed", String(d ? choisies.indexOf(d) >= 0 : !choisies.length)); });
    try { localStorage.setItem("vision_plateformes", JSON.stringify(choisies)); } catch (e) {}
  }
  puces.forEach(function(p){ p.addEventListener("click", function(){
    var d = p.getAttribute("data-d");
    if (!d) choisies = []; else if (choisies.indexOf(d) >= 0) choisies.splice(choisies.indexOf(d), 1); else choisies.push(d);
    peindre();
    if (document.getElementById("q").value.trim().length >= 2) document.getElementById("f").requestSubmit();
  }); });
  peindre();
  function dire(t, ok){ statut.textContent = t; statut.className = ok === undefined ? "" : (ok ? "ok" : "ko"); }
  function envoyer(chemin, corps){
    return fetch(base + chemin, {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(Object.assign({appareil: appareil}, corps)), credentials: "omit", cache: "no-store"})
      .then(function(r){ return r.json().catch(function(){ return {}; }).then(function(j){ j.__code = r.status; return j; }); });
  }
  // La pochette passe par Home Assistant (jamais l'adresse d'origine) ; sans image, une note de musique.
  function image(src){
    var v = document.createElement("div"); v.className = "vide"; v.setAttribute("aria-hidden", "true"); v.textContent = "♪";
    if (!src) return v;
    var i = document.createElement("img"); i.className = "pochette"; i.alt = ""; i.width = 56; i.height = 56; i.loading = "lazy"; i.decoding = "async";
    i.addEventListener("error", function(){ if (i.parentNode) i.parentNode.replaceChild(v, i); });
    i.src = src; return i;
  }
  function afficher(titres){
    titres.forEach(function(t){
      var li = document.createElement("li"), d = document.createElement("div"), b = document.createElement("b"), s = document.createElement("span"), bt = document.createElement("button");
      d.className = "txt";
      b.textContent = t.titre; s.textContent = t.source ? t.artiste + " · " + t.source : t.artiste; d.appendChild(b); d.appendChild(s);
      bt.type = "button"; bt.className = "plus"; bt.textContent = "+"; bt.setAttribute("aria-label", T.proposer_titre.split("{titre}").join(t.titre));
      bt.addEventListener("click", function(){
        bt.disabled = true; bt.textContent = "…";
        envoyer("/proposer", {ref: t.ref}).then(function(r){
          if (r.ok) { bt.textContent = "✓"; bt.className = "plus fait"; bt.setAttribute("aria-label", T.propose); dire(r.message, true); var fin = document.getElementById("fin"); if (fin) fin.remove(); mesTitres(); }
          else { bt.disabled = false; bt.textContent = "+"; dire(r.message || T.pas_passe, false); }
        }, function(){ bt.disabled = false; bt.textContent = "+"; dire(T.pas_reseau, false); });
      });
      li.appendChild(image(t.img ? base + "/image/" + encodeURIComponent(t.ref) : "")); li.appendChild(d); li.appendChild(bt); res.appendChild(li);
    });
  }
  var titreRes = document.getElementById("res-titre");
  // « Souvent joués » dès l'ouverture (avant toute recherche).
  fetch(base + "/suggestions", {credentials: "omit", cache: "no-store"}).then(function(r){ return r.json(); }).then(function(j){
    if (!j || !j.ok || !j.titres.length || res.childNodes.length) return;
    titreRes.textContent = T.souvent; titreRes.hidden = false; afficher(j.titres);
  }).catch(function(){});
  document.getElementById("f").addEventListener("submit", function(e){
    e.preventDefault();
    var q = document.getElementById("q").value.trim();
    if (q.length < 2) { dire(T.deux_lettres, false); return; }
    document.getElementById("q").blur();
    dire(T.recherche_en_cours); res.textContent = ""; titreRes.hidden = true;
    envoyer("/chercher", {texte: q, plateformes: choisies}).then(function(j){
      if (!j.ok) { dire(j.message || T.recherche_echec, false); return; }
      if (!j.titres.length) { dire(T.aucun); return; }
      dire("");
      titreRes.textContent = T.resultats; titreRes.hidden = false; afficher(j.titres);
    }, function(){ dire(T.pas_reseau, false); });
  });
  // Le titre en cours (et sa pochette), relu toutes les 15 s tant que la page est visible.
  var npImg = document.getElementById("np-img"), npVide = document.getElementById("np-vide"), vue = "";
  npImg.addEventListener("error", function(){ npImg.hidden = true; npVide.hidden = false; });
  npImg.addEventListener("load", function(){ npImg.hidden = false; npVide.hidden = true; });
  function maintenant(){
    if (document.visibilityState === "hidden") return;
    fetch(base + "/maintenant", {credentials: "omit", cache: "no-store"}).then(function(r){ return r.json(); }).then(function(j){
      if (!j || !j.ok) return;
      document.getElementById("np-titre").textContent = j.titre || T.va_commencer;
      document.getElementById("np-artiste").textContent = j.artiste || "";
      if (j.pochette !== vue) {
        vue = j.pochette || "";
        if (vue) npImg.src = base + "/pochette?v=" + encodeURIComponent(vue); else { npImg.hidden = true; npVide.hidden = false; npImg.removeAttribute("src"); }
      }
    }).catch(function(){});
  }
  // 2.5.8 (retour d'essai) : « Vos titres » — chaque titre proposé ici, avec son état (en attente, dans la file, joué…).
  var mes = document.getElementById("mes"), mesTitre = document.getElementById("mes-titre");
  function mesTitres(){
    if (document.visibilityState === "hidden") return;
    envoyer("/mes", {}).then(function(j){
      if (!j || !j.ok) return;
      mes.textContent = "";
      (j.titres || []).forEach(function(t){
        var li = document.createElement("li"), d = document.createElement("div"), b = document.createElement("b"), s = document.createElement("span");
        d.className = "txt"; b.textContent = t.titre; s.textContent = t.etat; if (t.attente) s.className = "att";
        d.appendChild(b); d.appendChild(s); li.appendChild(d); mes.appendChild(li);
      });
      mesTitre.hidden = !(j.titres || []).length;
    }, function(){});
  }
  maintenant(); setInterval(maintenant, 15000);
  mesTitres(); setInterval(mesTitres, 15000);
  document.addEventListener("visibilitychange", maintenant);
})();
</script></body></html>"""

_FINI = """<!doctype html><html lang="__LANGUE__"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow"><title>__T_fini_titre__</title></head>
<body>
<h1>__T_fini_h1__</h1><p>__T_fini_texte__</p></body></html>"""

# Les textes du script de la page (dictionnaire T), catalogue serveur « inv.<clé> ».
_TEXTES_SCRIPT = ("proposer_titre", "propose", "pas_passe", "pas_reseau", "souvent", "deux_lettres", "recherche_en_cours",
                  "recherche_echec", "aucun", "resultats", "va_commencer")


def _t(cle: str, langue: str) -> str:
    """Un texte de la page invité (catalogue serveur, « inv.<clé> »), dans la langue du navigateur de l'invité."""
    return textes.t("inv." + cle, langue)


def _traduire(gabarit: str, langue: str) -> str:
    """Remplit les __T_<clé>__ (texte échappé pour le HTML) et __LANGUE__ d'un gabarit de page."""
    return re.sub(r"__T_([a-z0-9_]+?)__", lambda m: html.escape(_t(m.group(1), langue)),
                  gabarit.replace("__LANGUE__", langue))


def _fini(langue: str) -> str:
    return _traduire(_FINI, langue)


# 2.2.12 (l'auteur : « respect du thème ») : les couleurs du thème de Vision choisi dans Home Assistant.
# fond, encre, encre2, carte, bord, accent, texte sur l'accent, accent lisible en texte, ok, ko, sombre
THEMES_PAGE = {
    "Beige": ("#EFE6D6", "#2A2026", "#6E5A5E", "#FBF6EC", "#D8CABE", "#8C2F4B", "#FFFFFF", "#8C2F4B", "#2E6B2E", "#A12A2A", False),
    "Sombre": ("#17121C", "#F4EDE1", "#BDB1C4", "#251C2D", "#3A2D45", "#E2B24A", "#17121C", "#E2B24A", "#8FD48F", "#F0A0A0", True),
    "Bleu nuit": ("#0F1830", "#EAF0FA", "#B6C1D4", "#1A2647", "#2B3A64", "#6FA8E8", "#0F1830", "#8DBBEE", "#8FD48F", "#F0A0A0", True),
    "Sauge": ("#DDE5DA", "#1F2A24", "#5E6E64", "#F2F6EF", "#BACBBE", "#2F6B55", "#FFFFFF", "#2F6B55", "#2E6B2E", "#A12A2A", False),
    "Rose poudré": ("#F0DEDD", "#2C1F24", "#7A5A60", "#FBF1F0", "#DDBDC2", "#96405C", "#FFFFFF", "#96405C", "#2E6B2E", "#A12A2A", False),
}


def _theme(hass: HomeAssistant) -> tuple:
    nom = "Beige"
    for donnees in (hass.data.get("pc_parental") or {}).values():
        store = getattr(donnees, "store", None)
        if store is not None and getattr(store, "veille_theme", None):
            nom = str(store.veille_theme)
            break
    return THEMES_PAGE.get(nom, THEMES_PAGE["Beige"])


_RE_COULEUR = re.compile(r"^[0-9a-fA-F]{6}$")


def theme_de_l_appareil(valeur: str | None) -> tuple | None:
    """2.5.8 (l'auteur : « la page invités doit prendre le thème de l'appareil qui affiche le QR code ») : l'appareil ajoute
    ses couleurs à l'adresse du code (`th` : fond, encre, encre2, carte, bord, accent, texte sur l'accent, accent en
    texte — 8 couleurs hexadécimales sans « # », séparées par des virgules). Tout ce qui n'est pas exactement cela est
    ignoré (thème de Home Assistant). Les couleurs d'état (ok, ko) suivent le clair ou le sombre du fond."""
    if not valeur or len(valeur) > 80:
        return None
    morceaux = valeur.split(",")
    if len(morceaux) != 8 or not all(_RE_COULEUR.match(m) for m in morceaux):
        return None
    c = ["#" + m.upper() for m in morceaux]
    r, g, b = (int(morceaux[0][i:i + 2], 16) for i in (0, 2, 4))
    sombre = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 < 0.5
    ok, ko = ("#8FD48F", "#F0A0A0") if sombre else ("#2E6B2E", "#A12A2A")
    return (*c, ok, ko, sombre)


def _page(hass: HomeAssistant, nonce: str, base: str, fin: str, puces: str, langue: str | None = None,
          theme: tuple | None = None) -> str:
    t = theme or _theme(hass)
    lg = langue or textes.ha(hass)
    noms = ("fond", "encre", "encre2", "carte", "bord", "accent", "sur-accent", "accent-texte", "ok", "ko")
    css = ";".join(f"--{n}:{v}" for n, v in zip(noms, t[:10]))
    # Les textes du script : du JSON, sans « < » (jamais de fin de balise <script> dans un texte).
    script = json.dumps({c: _t(c, lg) for c in _TEXTES_SCRIPT}, ensure_ascii=False).replace("<", "\\u003c")
    rempl = {"__NONCE__": html.escape(nonce), "__BASE__": base, "__FIN__": fin, "__PUCES__": puces, "__THEME__": css,
             "__FOND__": t[0], "__SCHEMA__": "dark" if t[10] else "light", "__TEXTES__": script}
    page = re.sub("__(?:NONCE|BASE|FIN|PUCES|THEME|FOND|SCHEMA|TEXTES)__", lambda m: rempl[m.group(0)], _PAGE)
    return _traduire(page, lg)


def _entetes(nonce: str = "") -> dict[str, str]:
    csp = ("default-src 'none'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; "
           "frame-ancestors 'none'")
    if nonce:
        csp += f"; script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'"
    return {"Content-Security-Policy": csp, "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
            "Cache-Control": "no-store", "X-Robots-Tag": "noindex, nofollow", "X-Frame-Options": "DENY"}


def _json(donnees: dict[str, Any], status: int = 200) -> web.Response:
    return web.Response(text=json.dumps(donnees, ensure_ascii=False), status=status,
                        content_type="application/json", headers=_entetes())


async def _corps(request: web.Request) -> dict[str, Any] | None:
    # Content-Length exigé et borné, puis le corps entier (pas seulement ce qui est déjà arrivé).
    if request.content_length is None or request.content_length > CORPS_MAX:
        return None
    brut = await request.read()
    if len(brut) > CORPS_MAX:
        return None
    try:
        c = json.loads(brut.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    return c if isinstance(c, dict) else None


class _PorteInvite(HomeAssistantView):
    requires_auth = False
    cors_allowed = False

    def __init__(self, hass: HomeAssistant, etat) -> None:
        self.hass = hass
        self._etat = etat

    def st(self) -> dict[str, Any]:
        return self._etat(self.hass)


async def _fin(hass: HomeAssistant, st: dict[str, Any], langue: str | None = None) -> str:
    """2.2.1 (retour d'essai) : le même clin d'œil que la télé quand la file est vide et que la musique s'arrête."""
    lect = hass.states.get(st.get("lecteur") or "") if st.get("lecteur") else None
    try:
        a_une_file = bool(await musique.file(hass, st.get("lecteur") or "", 1))
    except Exception:  # noqa: BLE001 - dans le doute, pas de clin d'œil
        return ""
    n = clins.indice(st, lect.state if lect else "unavailable", a_une_file)
    if n < 0:
        return ""
    clin, action = clins.phrase(hass, n, langue)
    return f'<div class="fin" id="fin" role="status"><b>{html.escape(clin)}</b><span>{html.escape(action)}</span></div>'


class PcParentalPartyInvitePageView(_PorteInvite):
    url = URL + "/{jeton}"
    name = "api:pc_parental:party_invite"

    async def get(self, request: web.Request, jeton: str) -> web.Response:
        lg = textes.langue_requete(request, self.hass)
        textes.parler(lg)  # tout texte demandé sans langue pendant cette requête (sources…) : celle de l'invité
        if not jeton_valide(self.st(), jeton):
            return web.Response(text=_fini(lg), status=404, content_type="text/html", headers=_entetes())
        nonce = secrets.token_urlsafe(16)
        base = json.dumps(URL + "/" + jeton)
        page = _page(self.hass, nonce, base, await _fin(self.hass, self.st(), lg), _puces(self.hass, lg), lg,
                     theme_de_l_appareil(request.query.get("th")))
        return web.Response(text=page, content_type="text/html", headers=_entetes(nonce))


class PcParentalPartyInviteChercherView(_PorteInvite):
    url = URL + "/{jeton}/chercher"
    name = "api:pc_parental:party_invite_chercher"

    async def post(self, request: web.Request, jeton: str) -> web.Response:
        st = self.st()
        lg = textes.langue_requete(request, self.hass)
        textes.parler(lg)  # tout texte demandé sans langue pendant cette requête (sources…) : celle de l'invité
        if not jeton_valide(st, jeton):
            return _json({"ok": False, "message": _t("lien_expire", lg)}, 404)
        c = await _corps(request)
        if c is None:
            return _json({"ok": False, "message": _t("deux_lettres", lg)}, 400)
        # Party d'abord, puis l'adresse seule, puis l'appareil (un identifiant inventé ne remplit pas les compteurs).
        if (_trop("chp:" + jeton[:12], RECHERCHES_MIN_PARTY) or _trop("ip:" + _ip(request), REQUETES_MIN_IP)
                or _trop("ch:" + _appareil(request, c), RECHERCHES_MIN_APPAREIL)):
            return _json({"ok": False, "message": _t("doucement", lg)}, 429)
        texte = " ".join(str(c.get("texte") or "").split())[: musique.MAX_TEXTE]
        if len(texte) < 2:
            return _json({"ok": False, "message": _t("deux_lettres", lg)}, 400)
        try:
            # 2.2.8 : la plateforme se choisit aussi ici ; le type reste « titres » (un invité ne propose que des titres).
            _types, doms = musique.choix_filtres(c, ("track",))
            r = musique.filtrer(await musique.chercher(self.hass, texte, ("track",)), None, doms)
        except musique.MusiqueIndisponible:
            return _json({"ok": False, "message": _t("recherche_indispo", lg)}, 503)
        if not jeton_valide(st, jeton):  # Party arrêtée pendant la recherche
            return _json({"ok": False, "message": _t("lien_expire", lg)}, 404)
        refs = refs_de(st)
        titres = []
        for t in r.get("track", [])[:15]:
            if t.get("explicit"):
                continue  # enfants présents : pas de titre « explicite »
            ref = secrets.token_urlsafe(9)
            refs[ref] = {"uri": t["uri"], "titre": t["nom"], "artiste": t["artiste"], "image": t.get("image") or ""}
            while len(refs) > REFS_MAX:
                refs.popitem(last=False)  # les plus anciennes s'effacent
            titres.append({"ref": ref, "titre": t["nom"], "artiste": t["artiste"], "source": t.get("source") or "",
                           "img": bool(t.get("image"))})
        return _json({"ok": True, "titres": titres})


def _references(st: dict[str, Any], pistes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Des références courtes pour ces titres (gardées côté serveur), comme la recherche."""
    refs = refs_de(st)
    titres = []
    for t in pistes:
        if t.get("explicit"):
            continue
        ref = secrets.token_urlsafe(9)
        refs[ref] = {"uri": t["uri"], "titre": t["nom"], "artiste": t["artiste"], "image": t.get("image") or ""}
        while len(refs) > REFS_MAX:
            refs.popitem(last=False)
        titres.append({"ref": ref, "titre": t["nom"], "artiste": t["artiste"], "source": t.get("source") or "",
                       "img": bool(t.get("image"))})
    return titres


class PcParentalPartyInviteSuggestionsView(_PorteInvite):
    """2.2.13 (retour d'essai) : « souvent joués » dès l'ouverture de la page, avec pochettes."""

    url = URL + "/{jeton}/suggestions"
    name = "api:pc_parental:party_invite_suggestions"

    async def get(self, request: web.Request, jeton: str) -> web.Response:
        st = self.st()
        if not jeton_valide(st, jeton):
            return _json({"ok": False}, 404)
        if _trop("vu:" + _ip(request), VUES_MIN_IP):
            return _json({"ok": False}, 429)
        try:
            pistes = await musique.souvent_joues(self.hass, 12)
        except Exception:  # noqa: BLE001 - pas de suggestions : la page garde la recherche
            pistes = []
        if not jeton_valide(st, jeton):
            return _json({"ok": False}, 404)
        return _json({"ok": True, "titres": _references(st, pistes)})


def _puces(hass: HomeAssistant, langue: str | None = None) -> str:
    """2.2.8 (retour d'essai) : les plateformes de musique reliées, en puces cumulables (« Toutes » = aucune choisie)."""
    lg = langue or textes.ha(hass)
    l = ['<button type="button" class="puce" data-d="" aria-pressed="true">{}</button>'.format(html.escape(_t("toutes", lg)))]
    for f in musique.plateformes(hass, lg):
        l.append('<button type="button" class="puce" data-d="{}" aria-pressed="false">{}</button>'.format(
            html.escape(f["domaine"]), html.escape(f["nom"])))
    return '<div class="puces" id="puces" role="group" aria-label="{}">'.format(html.escape(_t("plateformes", lg))) + "".join(l) + "</div>"


class PcParentalPartyInviteProposerView(_PorteInvite):
    url = URL + "/{jeton}/proposer"
    name = "api:pc_parental:party_invite_proposer"

    def __init__(self, hass: HomeAssistant, etat, proposer) -> None:
        super().__init__(hass, etat)
        self._proposer = proposer

    async def post(self, request: web.Request, jeton: str) -> web.Response:
        st = self.st()
        lg = textes.langue_requete(request, self.hass)
        textes.parler(lg)  # tout texte demandé sans langue pendant cette requête (sources…) : celle de l'invité
        if not jeton_valide(st, jeton):
            return _json({"ok": False, "message": _t("lien_expire", lg)}, 404)
        c = await _corps(request)
        if c is None:
            return _json({"ok": False, "message": _t("titre_plus_dispo", lg)}, 400)
        if (_trop("party:" + jeton[:12], PROPOSITIONS_MIN_PARTY) or _trop("ip:" + _ip(request), REQUETES_MIN_IP)
                or _trop("pr:" + _appareil(request, c), PROPOSITIONS_MIN_APPAREIL)):
            return _json({"ok": False, "message": _t("trop_propositions", lg)}, 429)
        ref = str(c.get("ref") or "")[:32]
        t = refs_de(st).get(ref)
        if t is None:
            return _json({"ok": False, "message": _t("titre_plus_dispo", lg)}, 400)
        if len([p for p in st.get("propositions", []) if p.get("etat") == "attente"]) >= ATTENTE_MAX:
            return _json({"ok": False, "message": _t("trop_attente", lg)}, 429)
        if not jeton_valide(st, jeton):  # Party arrêtée pendant la lecture du corps
            return _json({"ok": False, "message": _t("lien_expire", lg)}, 404)
        try:
            message = await self._proposer(self.hass, t, lg, _appareil(request, c))
        except Complet as e:
            return _json({"ok": False, "message": str(e)}, 429)
        except musique.MusiqueIndisponible:
            return _json({"ok": False, "message": _t("ajout_echec", lg)}, 503)
        return _json({"ok": True, "message": message})


_images: OrderedDict = OrderedDict()


async def telecharger_image(hass: HomeAssistant, url: str) -> tuple[bytes, str] | None:
    """Une image lue par Home Assistant (adresse venue de Music Assistant), bornée et typée ; en cache."""
    return await _telecharger(hass, url)


async def _telecharger(hass: HomeAssistant, url: str) -> tuple[bytes, str] | None:
    """Une pochette lue par Home Assistant (adresse venue de Music Assistant, jamais de l'invité), bornée et typée."""
    if url in _images:
        _images.move_to_end(url)
        return _images[url]
    if not url.startswith(("http://", "https://")):
        return None
    try:
        async with asyncio.timeout(8):
            async with async_get_clientsession(hass).get(url, allow_redirects=True) as r:
                type_ = str(r.headers.get("Content-Type") or "").split(";")[0].strip().lower()
                if r.status != 200 or type_ not in TYPES_IMAGE:
                    return None
                # 2.3.9 (l'auteur : pas de pochette pour « BAM BAM », file et « Souvent joués ») : content.read(n) rend AU PLUS n
                # octets — souvent le seul premier morceau arrivé. Une pochette de 128 Ko (Google) arrivait tronquée et
                # ne s'affichait pas. On lit jusqu'au bout, borné.
                morceaux, total = [], 0
                async for m in r.content.iter_chunked(64 * 1024):
                    morceaux.append(m)
                    total += len(m)
                    if total > IMAGE_MAX:
                        break
                corps = b"".join(morceaux)
    except Exception as e:  # noqa: BLE001 - pas d'image : la page montre sa note de musique
        _LOGGER.debug("Pochette invité illisible : %s", e)
        return None
    if not corps or len(corps) > IMAGE_MAX:
        return None
    _images[url] = (corps, type_)
    while len(_images) > IMAGES_GARDEES:
        _images.popitem(last=False)
    return _images[url]


def _image(corps: bytes, type_: str) -> web.Response:
    h = _entetes()
    h["Cache-Control"] = "private, max-age=3600"
    return web.Response(body=corps, content_type=type_, headers=h)


class PcParentalPartyInviteImageView(_PorteInvite):
    """2.2.12 (l'auteur : « je ne vois pas la pochette ») : la pochette d'un résultat, par Home Assistant."""

    url = URL + "/{jeton}/image/{ref}"
    name = "api:pc_parental:party_invite_image"

    async def get(self, request: web.Request, jeton: str, ref: str) -> web.Response:
        st = self.st()
        if not jeton_valide(st, jeton) or _trop("vu:" + _ip(request), VUES_MIN_IP):
            return web.Response(status=404, headers=_entetes())
        t = refs_de(st).get(str(ref)[:32])
        image = await _telecharger(self.hass, str(t.get("image") or "")) if t else None
        if image is None or not jeton_valide(st, jeton):
            return web.Response(status=404, headers=_entetes())
        return _image(*image)


class PcParentalPartyInviteMaintenantView(_PorteInvite):
    """2.2.12 : le titre en cours en haut de la page (titre, artiste, empreinte de la pochette ; rien d'autre)."""

    url = URL + "/{jeton}/maintenant"
    name = "api:pc_parental:party_invite_maintenant"

    async def get(self, request: web.Request, jeton: str) -> web.Response:
        from . import party
        st = self.st()
        if not jeton_valide(st, jeton):
            return _json({"ok": False}, 404)
        if _trop("vu:" + _ip(request), VUES_MIN_IP):
            return _json({"ok": False}, 429)
        m = party.maintenant(self.hass, st.get("lecteur") or "")
        joue = m.get("etat") in ("playing", "paused", "buffering")
        return _json({"ok": True, "titre": m.get("titre", "") if joue else "", "artiste": m.get("artiste", "") if joue else "",
                      "pochette": m.get("pochette", "") if joue else ""})


class PcParentalPartyInviteMesTitresView(_PorteInvite):
    """2.5.8 (retour d'essai) : « Vos titres » — ce que cet invité a proposé, et où chaque titre en est (en attente, juste après le
    titre en cours, n° X dans la file, en cours, joué, refusé). Rien des autres invités."""

    url = URL + "/{jeton}/mes"
    name = "api:pc_parental:party_invite_mes"

    async def post(self, request: web.Request, jeton: str) -> web.Response:
        from . import party
        st = self.st()
        lg = textes.langue_requete(request, self.hass)
        if not jeton_valide(st, jeton):
            return _json({"ok": False}, 404)
        if _trop("vu:" + _ip(request), VUES_MIN_IP):
            return _json({"ok": False}, 429)
        c = await _corps(request)
        return _json({"ok": True, "titres": await party.mes_titres(self.hass, _appareil(request, c), lg)})


class PcParentalPartyInvitePochetteView(_PorteInvite):
    """2.2.12 : la pochette du titre en cours (lue par Home Assistant, sans exposer son jeton)."""

    url = URL + "/{jeton}/pochette"
    name = "api:pc_parental:party_invite_pochette"

    async def get(self, request: web.Request, jeton: str) -> web.Response:
        from . import party
        st = self.st()
        if not jeton_valide(st, jeton) or _trop("vu:" + _ip(request), VUES_MIN_IP) or not st.get("lecteur"):
            return web.Response(status=404, headers=_entetes())
        image = await party.pochette(self.hass, st["lecteur"])
        if image is None:
            return web.Response(status=404, headers=_entetes())
        type_ = image[1].split(";")[0].strip().lower()
        if type_ not in TYPES_IMAGE or len(image[0]) > IMAGE_MAX:
            return web.Response(status=404, headers=_entetes())
        return _image(image[0], type_)


def enregistrer_vues(hass: HomeAssistant, etat, proposer) -> None:
    hass.http.register_view(PcParentalPartyInvitePageView(hass, etat))
    hass.http.register_view(PcParentalPartyInviteImageView(hass, etat))
    hass.http.register_view(PcParentalPartyInviteSuggestionsView(hass, etat))
    hass.http.register_view(PcParentalPartyInviteMaintenantView(hass, etat))
    hass.http.register_view(PcParentalPartyInviteMesTitresView(hass, etat))
    hass.http.register_view(PcParentalPartyInvitePochetteView(hass, etat))
    hass.http.register_view(PcParentalPartyInviteChercherView(hass, etat))
    hass.http.register_view(PcParentalPartyInviteProposerView(hass, etat, proposer))
