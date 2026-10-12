"""2.2.11 (l'auteur : « crée un système de log pour savoir d'où ça vient ») : le journal de la Musique.

Chaque commande (heure, appareil, personne, profil, action, URI, mode d'ajout, nombre de titres, résultat) et chaque
perte du premier plan de Vision sur une télé (avec l'appli qui a pris l'écran quand Android le dit) :
- un événement Home Assistant `vision_musique_commande` (le journal de bord / logbook le décrit dans la langue de HA) ;
- le capteur « Musique — dernière commande » ;
- l'écran « Journal Musique » des options (Décideurs / administrateurs), conservé quelques jours seulement.
"""
from __future__ import annotations

import time
from typing import Any

from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store

EVENEMENT = "vision_musique_commande"
SIGNAL = "pc_parental_journal_musique"
_CLE = "pc_parental_journal_musique"
MAX_LIGNES = 1000
JOURS_DEFAUT = 7
JOURS_POSSIBLES = (1, 7, 30)


def _etat(hass: HomeAssistant) -> dict[str, Any]:
    return hass.data.setdefault(_CLE, {"lignes": [], "jours": JOURS_DEFAUT, "store": None})


async def charger(hass: HomeAssistant) -> None:
    st = _etat(hass)
    st["store"] = Store(hass, 1, "pc_parental_journal_musique")
    data = await st["store"].async_load() or {}
    st["jours"] = int(data.get("jours") or JOURS_DEFAUT) if int(data.get("jours") or JOURS_DEFAUT) in JOURS_POSSIBLES else JOURS_DEFAUT
    st["lignes"] = [l for l in (data.get("lignes") or []) if isinstance(l, dict)]
    _elaguer(st)


def _elaguer(st: dict[str, Any]) -> None:
    limite = time.time() - st["jours"] * 86400
    st["lignes"] = [l for l in st["lignes"] if float(l.get("ts") or 0) >= limite][-MAX_LIGNES:]


def _sauver(hass: HomeAssistant) -> None:
    st = _etat(hass)
    if st["store"] is not None:
        st["store"].async_delay_save(lambda: {"jours": st["jours"], "lignes": st["lignes"]}, 10)


@callback
def noter(hass: HomeAssistant, **ligne: Any) -> dict[str, Any]:
    """Une ligne du journal (champs libres, courts) ; l'événement et le capteur suivent."""
    st = _etat(hass)
    propre = {k: (str(v)[:300] if isinstance(v, str) else v) for k, v in ligne.items() if v not in (None, "")}
    propre["ts"] = time.time()
    st["lignes"].append(propre)
    _elaguer(st)
    _sauver(hass)
    hass.bus.async_fire(EVENEMENT, propre)
    async_dispatcher_send(hass, SIGNAL)
    return propre


def lignes(hass: HomeAssistant, n: int = 50) -> list[dict[str, Any]]:
    return list(reversed(_etat(hass)["lignes"][-n:]))


def derniere(hass: HomeAssistant) -> dict[str, Any] | None:
    l = _etat(hass)["lignes"]
    return l[-1] if l else None


def jours(hass: HomeAssistant) -> int:
    return int(_etat(hass)["jours"])


def poser_jours(hass: HomeAssistant, n: int) -> None:
    st = _etat(hass)
    st["jours"] = n if n in JOURS_POSSIBLES else JOURS_DEFAUT
    _elaguer(st)
    _sauver(hass)


# Les mots du journal : catalogue serveur (i18n/serveur), dans la langue du lecteur (Home Assistant).
ACTIONS = (
    "jouer", "retirer", "ensuite_element", "jouer_element", "deplacer", "a_la_fin", "lecture", "suivant", "precedent",
    "volume", "enceinte", "lancer", "lancer_ici", "arreter", "similaires", "enchainement", "premier_plan_perdu",
)
MODES = ("remplacer", "ajouter", "ensuite")
TYPES = ("track", "album", "playlist", "artist", "radio")
# Le résultat d'une commande : un code (party._journal), sauf les lignes écrites avant la 2.2.13 (texte libre).
RESULTATS = ("ok", "note", "confirmer", "echec_enceinte", "refuse")


def phrase(d: dict[str, Any], langue: str | None = None) -> str:
    """Une ligne lisible : « Pixel 9 (Lou) : ajoute à la suite — playlist « 500 Random tracks » (500 titres) — ok »."""
    from . import textes

    qui = str(d.get("appareil") or "?") + (f" ({d['personne']})" if d.get("personne") else "")
    action = str(d.get("action") or "")
    quoi = textes.t(f"mus.j.action_{action}", langue) if action in ACTIONS else action
    if action == "jouer" and str(d.get("mode") or "") in MODES:
        quoi = textes.t(f"mus.j.mode_{d['mode']}", langue)
    cible = ""
    if d.get("titre") or d.get("uri"):
        type_ = str(d.get("type") or "")
        type_ = textes.t(f"mus.j.type_{type_}", langue) if type_ in TYPES else type_
        cible = textes.t("mus.j.cible", langue, type=type_, titre=d.get("titre") or d.get("uri")).replace("—  ", "— ")
    if d.get("nb"):
        try:
            cible += textes.n("mus.j.nb_titres", int(d["nb"]), langue)
        except (TypeError, ValueError):
            pass
    if d.get("paquet"):
        cible += textes.t("mus.j.ecran_pris", langue, paquet=d["paquet"])
    resultat = str(d.get("resultat") or "ok")
    if resultat in RESULTATS:
        resultat = textes.t(f"mus.j.r_{resultat}", langue, detail=str(d.get("detail") or ""))
    return textes.t("mus.j.ligne", langue, qui=qui, quoi=quoi, cible=cible, resultat=resultat)
