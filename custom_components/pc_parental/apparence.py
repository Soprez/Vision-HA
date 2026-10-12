"""Apparence du tableau de bord Vision (1.95.0, point 16.2).

L'administrateur choisit dans le tableau (onglet Appareils → Apparence) une palette parmi les thèmes de l'appli
Vision, le mode (automatique, clair, sombre) et, s'il veut, une couleur d'accent. Vision en fait le thème
Home Assistant « Vision » (fichier themes/vision.yaml, avec ses deux modes), que toutes les vues du tableau
portent déjà ; les cartes Vision prennent ses couleurs. Le choix est gardé dans le magasin.

- Automatique : le mode clair ou sombre suit le réglage de chaque utilisateur de Home Assistant.
- Clair / Sombre : les deux modes du thème portent les mêmes couleurs.
- La variante sombre d'une palette claire (et l'inverse) est calculée comme dans l'appli : fond teinté de
  l'accent, encre lisible, accent éclairci ou foncé jusqu'à se lire.
"""

from __future__ import annotations

import logging
import os
import re
from typing import Any

from homeassistant.core import HomeAssistant

_LOGGER = logging.getLogger(__name__)

THEME = "Vision"
FICHIER = "themes/vision.yaml"
MODES = ("auto", "clair", "sombre")

# (nom, fond, carte, encre, encre2, accent, or, sombre) : les palettes de l'appli Vision (Themes.kt), plus
# « Vision », le thème d'origine du tableau (rouge profond, or).
PALETTES: tuple[tuple[str, str, str, str, str, str, str, bool], ...] = (
    ("Vision", "#120609", "#27111A", "#FBF3EE", "#CDA8A6", "#B3282D", "#F2C14E", True),
    ("Sombre", "#17121C", "#251C2D", "#F4EDE1", "#BDB1C4", "#C2577B", "#E2B24A", True),
    ("Beige", "#EFE6D6", "#FBF6EC", "#2A2026", "#6E5A5E", "#8C2F4B", "#8F6110", False),
    ("Bleu nuit", "#0F1830", "#1A2647", "#EAF0FA", "#B6C1D4", "#6FA8E8", "#E2B24A", True),
    ("Sauge", "#DDE5DA", "#F2F6EF", "#1F2A24", "#5E6E64", "#2F6B55", "#7F5C0E", False),
    ("Salon", "#D9D1C5", "#F4F1EB", "#2A2724", "#6B645C", "#B07E4A", "#8A6433", False),
    ("Rose poudré", "#F0DEDD", "#FBF1F0", "#2C1F24", "#7A5A60", "#96405C", "#875A12", False),
    ("Tactique", "#0F1923", "#1B2733", "#ECE8E1", "#9AA7B1", "#FF4655", "#FF4655", True),
    ("Briques", "#F2F4F5", "#FFFFFF", "#191B1D", "#60666C", "#0A84D6", "#0A84D6", False),
    ("Corsaire", "#0B2A3A", "#123C50", "#F4EBD0", "#A9C3C9", "#F2A93B", "#F2A93B", True),
    ("Royale", "#1B1035", "#2A1B55", "#F5F3FF", "#B9AEE0", "#F8D21A", "#F8D21A", True),
    ("Circuit", "#121212", "#1E1E1E", "#F2F2F2", "#A0A0A0", "#E5281B", "#E5281B", True),
    ("Cockpit", "#0E1A24", "#16283A", "#E8F1F8", "#9DB2C4", "#FFB000", "#FFB000", True),
    ("Bourse", "#0B0F0E", "#141B19", "#E6F2EC", "#8FA59B", "#21C77A", "#21C77A", True),
    ("Écrin", "#0E0C0A", "#1A1714", "#F3EBDD", "#B4A68F", "#C9A45C", "#C9A45C", True),
    ("Prairie", "#EEF3E6", "#FAFCF5", "#22301F", "#5F7058", "#4F8A2B", "#4F8A2B", False),
    ("Large", "#E6F1F5", "#F7FBFD", "#12303D", "#55737F", "#0F7EA3", "#0F7EA3", False),
    ("Sommet", "#E9EDF1", "#F8FAFC", "#1F2933", "#5C6B7A", "#C2553A", "#C2553A", False),
    ("Sous-bois", "#1A2119", "#252E23", "#ECE6D6", "#A9B19C", "#C58B3B", "#C58B3B", True),
    ("Code", "#030A05", "#0A1A10", "#D7FFE0", "#6FBF87", "#2BEA6B", "#2BEA6B", True),
    ("Plume", "#E8F1F7", "#F8FBFD", "#23313D", "#5F7482", "#A8552F", "#A8552F", False),
    ("Galaxie", "#05060A", "#10131C", "#F4F1E4", "#9AA0B4", "#FFD426", "#FFD426", True),
    ("Rétro 85", "#14100E", "#221B17", "#F6EDE2", "#B8A698", "#FF7A1A", "#FF7A1A", True),
    ("Grimoire", "#1C1210", "#2A1C18", "#F1E6CF", "#B9A583", "#D4A843", "#D4A843", True),
    ("Féerie", "#141A3C", "#1F2757", "#F6F3FF", "#B4B8E0", "#FFD36E", "#FFD36E", True),
    ("Banquise", "#E9F4FB", "#F8FCFF", "#173247", "#5C7C92", "#2F8FCB", "#2F8FCB", False),
    ("Lagon", "#06343B", "#0C4750", "#F3F0DC", "#9CC7C2", "#FF8A5B", "#FF8A5B", True),
    ("Étincelle", "#FFF4CC", "#FFFBEA", "#2B2416", "#75683F", "#D93A2B", "#D93A2B", False),
)


# ----------------------------------------------------------------------------- couleurs (calcul pur, testé)

def _rgb(c: str) -> tuple[int, int, int]:
    c = c.lstrip("#")
    return int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16)


def _hex(r: float, g: float, b: float) -> str:
    return "#{:02X}{:02X}{:02X}".format(*(max(0, min(255, int(v))) for v in (r, g, b)))


def melanger(a: str, b: str, t: float) -> str:
    """a vers b, à la proportion t (0 = a, 1 = b)."""
    t = max(0.0, min(1.0, t))
    (r1, g1, b1), (r2, g2, b2) = _rgb(a), _rgb(b)
    return _hex(r1 + (r2 - r1) * t, g1 + (g2 - g1) * t, b1 + (b2 - b1) * t)


def luminance(c: str) -> float:
    def lin(v: int) -> float:
        s = v / 255
        return s / 12.92 if s <= 0.03928 else ((s + 0.055) / 1.055) ** 2.4
    r, g, b = _rgb(c)
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contraste(a: str, b: str) -> float:
    x, y = luminance(a), luminance(b)
    return (max(x, y) + 0.05) / (min(x, y) + 0.05)


def lisible(couleur: str, sur: str, seuil: float) -> str:
    """Éclaircit (fond sombre) ou fonce (fond clair) une couleur jusqu'à ce contraste sur le fond."""
    vers = "#FFFFFF" if luminance(sur) < 0.2 else "#000000"
    n = 0
    while contraste(couleur, sur) < seuil and n < 20:
        couleur = melanger(couleur, vers, 0.1)
        n += 1
    return couleur


def lisible_partout(couleur: str, fonds: tuple[str, ...], seuil: float) -> str:
    """`lisible`, mais sur plusieurs fonds à la fois (carte, fond de page, blanc…) : le pire décide."""
    vers = "#FFFFFF" if luminance(fonds[0]) < 0.2 else "#000000"
    n = 0
    while min(contraste(couleur, f) for f in fonds) < seuil and n < 40:
        couleur = melanger(couleur, vers, 0.08)
        n += 1
    return couleur


def palette(nom: str) -> tuple[str, str, str, str, str, str, str, bool]:
    return next((p for p in PALETTES if p[0] == nom), PALETTES[0])


def variante(nom: str, sombre: bool, accent: str = "") -> dict[str, str]:
    """Les couleurs d'une palette en clair ou en sombre (la variante manquante est calculée), accent facultatif."""
    _n, fond, carte, encre, encre2, acc, orr, est_sombre = palette(nom)
    acc = accent or acc
    if sombre and not est_sombre:
        fond = melanger("#141118", acc, 0.07)
        carte = melanger("#221D27", acc, 0.10)
        encre = melanger("#F5EFE6", palette(nom)[1], 0.15)
        encre2 = melanger(encre, fond, 0.30)
    elif not sombre and est_sombre:
        fond = melanger("#FBF8F4", acc, 0.06)
        carte = "#FFFFFF"
        encre = melanger("#211A1E", acc, 0.10)
        encre2 = melanger(encre, fond, 0.38)
    principale = lisible(orr if sombre else acc, fond, 4.5)
    secondaire = lisible(acc if sombre else orr, fond, 3.0)
    encre2 = lisible(encre2, carte, 4.6)
    # L'encre posée sur la couleur principale (boutons) : la plus lisible des trois.
    sur = max((fond, "#FFFFFF", "#111111"), key=lambda e: contraste(e, principale))
    ligne = melanger(carte, encre, 0.16)
    # A7 : en sombre, la bordure des cartes se voit (≥ 3:1 sur la carte et sur le fond de page) ; le séparateur
    # (`ligne`) reste discret.
    bordure = lisible_partout(ligne, (carte, fond), 3.0) if sombre else ligne
    return {"fond": fond, "carte": carte, "encre": encre, "encre2": encre2, "encre3": melanger(encre, fond, 0.5),
            "principale": principale, "secondaire": secondaire,
            "fond2": melanger(fond, carte, 0.5), "ligne": ligne, "bordure": bordure,
            "sur_principale": sur}


# Couleurs d'état (succès, alerte, erreur, info) avant ajustement, en sombre puis en clair.
ETATS = {
    "success-color": ("#4CC38A", "#237047"),
    "warning-color": ("#E2A33A", "#7A5600"),
    "error-color": ("#FF6B6B", "#B3261E"),
    "info-color": ("#7FB8FF", "#376FA3"),
}


def couleurs_etat(c: dict[str, str], sombre: bool) -> dict[str, str]:
    """A7 : les couleurs d'état servent aussi de texte : ≥ 4,5:1 sur la carte et le fond (et sur blanc en clair)."""
    fonds = (c["carte"], c["fond"], c["fond2"]) + (() if sombre else ("#FFFFFF",))
    return {cle: lisible_partout(sombre_clair[0 if sombre else 1], fonds, 4.5) for cle, sombre_clair in ETATS.items()}


def variables(c: dict[str, str], sombre: bool) -> dict[str, str]:
    """Le jeu de variables d'un thème Home Assistant (les mêmes clés que l'ancien vision.yaml)."""
    etats = couleurs_etat(c, sombre)
    return {
        "primary-color": c["principale"], "accent-color": c["secondaire"],
        "primary-background-color": c["fond"], "secondary-background-color": c["fond2"],
        "card-background-color": c["carte"], "ha-card-background": c["carte"], "ha-card-border-color": c.get("bordure", c["ligne"]),
        "ha-card-border-radius": "18px", "divider-color": c["ligne"],
        "primary-text-color": c["encre"], "secondary-text-color": c["encre2"], "disabled-text-color": c["encre3"],
        "text-primary-color": c["sur_principale"],
        "app-header-background-color": c["fond2"], "app-header-text-color": c["principale"],
        "sidebar-background-color": c["fond2"], "sidebar-text-color": c["encre2"],
        "sidebar-selected-text-color": c["principale"], "sidebar-selected-icon-color": c["principale"], "sidebar-icon-color": c["encre3"],
        "state-icon-color": c["encre2"], "state-icon-active-color": c["principale"],
        "switch-checked-color": c["principale"], "switch-checked-button-color": c["principale"],
        "switch-checked-track-color": melanger(c["principale"], c["fond"], 0.5),
        "switch-unchecked-button-color": c["encre3"], "switch-unchecked-track-color": c["ligne"],
        "slider-color": c["principale"], "slider-bar-color": c["ligne"],
        **etats,
        "mdc-theme-primary": c["principale"], "mdc-theme-surface": c["carte"],
        "input-fill-color": c["fond2"], "input-ink-color": c["encre"], "input-label-ink-color": c["encre2"],
        "table-row-background-color": c["carte"], "table-row-alternative-background-color": c["fond2"],
    }


def reglage_propre(r: Any) -> dict[str, str]:
    r = r if isinstance(r, dict) else {}
    nom = str(r.get("palette") or "Vision")
    mode = str(r.get("mode") or "auto")
    accent = str(r.get("accent") or "")
    return {"palette": nom if any(p[0] == nom for p in PALETTES) else "Vision",
            "mode": mode if mode in MODES else "auto",
            "accent": accent.upper() if re.fullmatch(r"#[0-9A-Fa-f]{6}", accent) else ""}


def theme(r: dict[str, str]) -> dict[str, Any]:
    """Le thème « Vision » : ses deux modes ; en clair ou sombre forcé, les deux modes sont identiques."""
    r = reglage_propre(r)
    clair = variables(variante(r["palette"], False, r["accent"]), False)
    sombre = variables(variante(r["palette"], True, r["accent"]), True)
    if r["mode"] == "clair":
        sombre = clair
    elif r["mode"] == "sombre":
        clair = sombre
    return {THEME: {"modes": {"light": clair, "dark": sombre}}}


def yaml_theme(r: dict[str, str]) -> str:
    t = theme(r)[THEME]["modes"]
    r = reglage_propre(r)
    lignes = ["# Thème « Vision », écrit par Vision (Tableau → Appareils → Apparence) : palette « {} », mode {}{}.".format(
        r["palette"], {"auto": "automatique", "clair": "clair", "sombre": "sombre"}[r["mode"]],
        f", accent {r['accent']}" if r["accent"] else ""),
        "# Choisir l'apparence depuis le tableau de bord : ce fichier est réécrit à chaque changement.", f"{THEME}:", "  modes:"]
    for mode in ("dark", "light"):
        lignes.append(f"    {mode}:")
        lignes += [f'      {k}: "{v}"' for k, v in t[mode].items()]
    return "\n".join(lignes) + "\n"


def palettes_publiques() -> list[dict[str, Any]]:
    return [{"nom": p[0], "fond": p[1], "accent": p[5], "or": p[6], "sombre": p[7]} for p in PALETTES]


# ----------------------------------------------------------------------------- écriture et rechargement

async def async_appliquer(hass: HomeAssistant, store, r: dict[str, str], par: str) -> dict[str, str]:
    """Écrit le thème, le recharge, retient le choix. Le vision.yaml d'origine est gardé une fois (.avant-apparence)."""
    r = reglage_propre(r)
    chemin = hass.config.path(FICHIER)

    def _ecrire() -> None:
        os.makedirs(os.path.dirname(chemin), exist_ok=True)
        garde = chemin + ".avant-apparence"
        if os.path.exists(chemin) and not os.path.exists(garde):
            with open(chemin, encoding="utf-8") as f_in, open(garde, "w", encoding="utf-8") as f_out:
                f_out.write(f_in.read())
        provisoire = chemin + ".tmp"
        with open(provisoire, "w", encoding="utf-8") as f:
            f.write(yaml_theme(r))
        os.replace(provisoire, chemin)

    await hass.async_add_executor_job(_ecrire)
    store.apparence = r
    await store.async_save()
    if hass.services.has_service("frontend", "reload_themes"):
        await hass.services.async_call("frontend", "reload_themes", {}, blocking=True)
    _LOGGER.warning("Vision : apparence du tableau — palette « %s », mode %s%s (par %s).", r["palette"], r["mode"],
                    f", accent {r['accent']}" if r["accent"] else "", par)
    return r


# ----------------------------------------------------------------------------- l'API de la carte « Apparence »

from aiohttp import web  # noqa: E402
from homeassistant.components.http import HomeAssistantView  # noqa: E402

URL = "/api/pc_parental/apparence"


class PcParentalApparenceView(HomeAssistantView):
    """GET : le choix actuel et les palettes ; POST {palette, mode, accent} : l'applique. Administrateurs seulement."""

    url = URL
    name = "api:pc_parental:apparence"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _coord(self):
        from .const import DOMAIN

        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        return entrees[0] if entrees else None

    def _refus(self, request: web.Request) -> web.Response | None:
        u = request.get("hass_user")
        if u is None or not u.is_admin:
            from . import textes

            return self.json({"ok": False, "error": "admin", "retour": textes.t("commun.reserve_admins", textes.ha(self.hass))},
                             status_code=403)
        return None

    def _etat(self, coord) -> dict[str, Any]:
        return {"ok": True, "reglage": reglage_propre(getattr(coord.store, "apparence", None)), "palettes": palettes_publiques(),
                "apercu": {m: variante(reglage_propre(getattr(coord.store, "apparence", None))["palette"], m == "sombre",
                                       reglage_propre(getattr(coord.store, "apparence", None))["accent"]) for m in ("clair", "sombre")}}

    async def get(self, request: web.Request) -> web.Response:
        if r := self._refus(request):
            return r
        coord = self._coord()
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        return self.json(self._etat(coord))

    async def post(self, request: web.Request) -> web.Response:
        if r := self._refus(request):
            return r
        coord = self._coord()
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        par = str(getattr(request.get("hass_user"), "name", "") or "un administrateur")
        try:
            await async_appliquer(self.hass, coord.store, corps if isinstance(corps, dict) else {}, par)
        except OSError as err:
            from . import textes

            return self.json({"ok": False, "error": "fichier", "retour": textes.t(
                "apparence.ecriture", textes.ha(self.hass), erreur=err.strerror or err)}, status_code=500)
        return self.json(self._etat(coord))
