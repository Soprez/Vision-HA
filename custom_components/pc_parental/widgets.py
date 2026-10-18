"""Widgets Home Assistant dans l'appli (contrat : docs/widgets-ha.md, validé le 2026-10-08).

Un appareil ne voit que les entités qu'un administrateur lui a données, et ne pilote que ce qui est
sur la liste blanche. Il n'a jamais de jeton Home Assistant : tout passe par son identifiant et son
secret, comme le relevé.

- Entités visibles = (liste de sa personne ∪ « plus » de l'appareil) − « moins » de l'appareil ;
  un appareil sans personne : son « plus » seulement.
- Lire demande la fonction de rôle `entites_voir` ; agir demande `entites_piloter` ET que le service
  soit sur la liste blanche de l'entité (store.entites_actions) ET qu'il fasse partie des services
  proposés pour son domaine (SERVICES ci-dessous ; serrures et alarmes : jamais).
- Les données d'une action se limitent à quelques clés sûres par service (DONNEES), bornées ; tout le
  reste est refusé (jamais d'entity_id, de variables de script, de cible fournie par l'appareil).
- Module « entites » coupé : toutes les portes répondent 403 {"error": "module"}.

2.4.0 (widgets interactifs, proposition validée en essai le 2026-10-12) :
- « Pour tout le monde » (store.entites_tous) : vues par tous les appareils, en plus de leur personne.
- Réglage par entité (store.entites_reglages) : rôle (lecture, interrupteur, bouton, réglage — il fixe la liste
  blanche entites_actions, gardée pour les appareils plus anciens), qui peut agir (« decideurs » : la fonction
  entites_piloter ; « tous » : aussi les autres profils, pour cette entité seulement), confirmation (exigée par le
  serveur : 409 « confirmer » sans {"confirme": true}), nom et icône affichés, affichage (valeur, detail,
  valeur_detail ; détail = éléments d'une liste todo, ou attributs choisis).
- Une entité sans réglage garde exactement le comportement d'avant (rôle déduit de sa liste blanche).
"""

from __future__ import annotations

import asyncio
import datetime
import json
import logging
import time
import zlib
from typing import Any, Callable

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.const import EVENT_HOMEASSISTANT_STOP, EVENT_STATE_CHANGED
from homeassistant.core import Context, Event, HomeAssistant, State, callback
from homeassistant.util import dt as dt_util

from . import modules, roles, textes
from .const import DOMAIN, PRODUIT

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/widgets"
EVENEMENT = f"{DOMAIN}_entites"
MODULE = "entites"

# ----------------------------------------------------------------------------- Ce qui se pilote

# Les services proposés par domaine. Tout domaine absent est en lecture seule, quoi qu'en dise la liste blanche.
_INTERRUPTEUR = ("toggle", "turn_on", "turn_off")
SERVICES: dict[str, tuple[str, ...]] = {
    "light": _INTERRUPTEUR,
    "switch": _INTERRUPTEUR,
    "fan": _INTERRUPTEUR,
    "input_boolean": _INTERRUPTEUR,
    "script": ("turn_on",),
    "scene": ("turn_on",),
    "button": ("press",),
    "input_button": ("press",),
    "cover": ("open_cover", "close_cover", "stop_cover"),
    "climate": ("set_temperature", "set_hvac_mode"),
    # 1.98.0 (point 14 bis) : le widget Lecteur.
    "media_player": ("media_play_pause", "media_next_track", "media_previous_track", "volume_set"),
    # 2.4.0 : rôle « réglage » d'une valeur ou d'une liste (bornes et options lues sur l'entité elle-même).
    "input_number": ("set_value",),
    "number": ("set_value",),
    "input_select": ("select_option",),
    "select": ("select_option",),
}

# 2.4.0 : les rôles d'une entité dans Vision. « lecture » partout ; les autres selon le domaine (et leurs services).
LECTURE, INTERRUPTEUR, BOUTON, REGLAGE = "lecture", "interrupteur", "bouton", "reglage"
ROLES = (LECTURE, INTERRUPTEUR, BOUTON, REGLAGE)
ROLE_DOMAINES: dict[str, frozenset[str]] = {
    INTERRUPTEUR: frozenset({"light", "switch", "fan", "input_boolean"}),
    BOUTON: frozenset({"script", "scene", "button", "input_button"}),
    REGLAGE: frozenset({"input_number", "number", "input_select", "select", "climate", "cover", "media_player"}),
}
QUI = ("decideurs", "tous")
# 2.4.3 (l'auteur : « accéder directement à la carte ou au dashboard ») : ce que fait un toucher sur le widget.
# « role » : l'action du rôle (bascule, bouton, réglage), comme avant ; « details » : les détails de l'entité dans
# Home Assistant (more-info) ; « vue » : un tableau de bord ou une vue de Home Assistant (chemin) ; « rien ».
TOUCHER_ROLE, TOUCHER_DETAILS, TOUCHER_VUE, TOUCHER_RIEN = "role", "details", "vue", "rien"
TOUCHERS = (TOUCHER_ROLE, TOUCHER_DETAILS, TOUCHER_VUE, TOUCHER_RIEN)
CHEMIN_MAX = 200
_CHEMIN_PERMIS = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/_-.?=&%#")
AFFICHAGES = ("valeur", "detail", "valeur_detail")
# 2.5.3 (l'auteur, version suivante) : la mise en page d'un widget — la même pour l'onglet Widgets HA et le téléphone d'un
# Décideur. Alignement du titre et de la valeur, taille de la valeur (« remplir » : la plus grande qui tient), titre et
# icône montrés ou non. Défaut : valeur grande et centrée, titre et icône montrés.
ALIGNS_H = ("gauche", "centre", "droite")
ALIGNS_V = ("haut", "centre", "bas")
TAILLES = ("remplir", "s", "m", "l", "xl")
MISE_EN_PAGE_DEFAUT: dict[str, Any] = {"align_h": "centre", "align_v": "centre", "taille": "remplir", "titre": True, "icone": True}
# Confirmation cochée d'office quand le rôle est posé (modifiable ensuite) : ce qui agit sur plusieurs choses ou dure.
CONFIRMER_DEFAUT = frozenset({"script", "cover", "climate"})
NOM_MAX, ICONE_MAX, ATTRIBUTS_MAX, DETAIL_MAX = 60, 64, 8, 20
# 2.4.4 : un attribut liste prend une ligne par élément ; le détail des attributs va jusqu'à 40 lignes.
DETAIL_ATTRIBUTS_MAX = 40
# Jamais montrés comme « détail » (techniques, ou déjà montrés ailleurs).
ATTRIBUTS_CACHES = frozenset({"friendly_name", "icon", "entity_picture", "supported_features", "attribution",
                              "unit_of_measurement", "device_class", "state_class", "editable", "id", "restored",
                              "assumed_state", "entity_id", "access_token", "token"})
# Jamais pilotables depuis un appareil, même si quelqu'un les écrit à la main dans le magasin (sécurité).
INTERDITS = frozenset({"lock", "alarm_control_panel"})

HVAC_MODES = ("off", "heat", "cool", "heat_cool", "auto", "dry", "fan_only")


def _pourcent(v: Any) -> int:
    if isinstance(v, bool):
        raise ValueError
    n = int(float(v))
    if not 0 <= n <= 100 or float(v) != n:
        raise ValueError
    return n


def _temperature(v: Any) -> float:
    if isinstance(v, bool):
        raise ValueError
    n = float(v)
    if not 5.0 <= n <= 35.0 or n != n:
        raise ValueError
    return round(n * 2) / 2  # au demi-degré


def _volume(v: Any) -> float:
    if isinstance(v, bool):
        raise ValueError
    n = float(v)
    if not 0.0 <= n <= 1.0:
        raise ValueError
    return round(n, 2)


def _valeur(v: Any) -> float:
    """Une valeur numérique finie (les bornes de l'entité sont vérifiées avec son état)."""
    if isinstance(v, bool):
        raise ValueError
    n = float(v)
    if n != n or n in (float("inf"), float("-inf")) or abs(n) > 1e9:
        raise ValueError
    return n


def _option(v: Any) -> str:
    if not isinstance(v, str) or not v or len(v) > 255:
        raise ValueError
    return v


def _mode_hvac(v: Any) -> str:
    if not isinstance(v, str) or v not in HVAC_MODES:
        raise ValueError
    return v


# (domaine, service) → {clé: validateur}. Une clé absente d'ici est refusée.
DONNEES: dict[tuple[str, str], dict[str, Callable[[Any], Any]]] = {
    ("light", "turn_on"): {"brightness_pct": _pourcent},
    ("fan", "turn_on"): {"percentage": _pourcent},
    ("climate", "set_temperature"): {"temperature": _temperature},
    ("climate", "set_hvac_mode"): {"hvac_mode": _mode_hvac},
    ("media_player", "volume_set"): {"volume_level": _volume},
    ("input_number", "set_value"): {"value": lambda v: _valeur(v)},
    ("number", "set_value"): {"value": lambda v: _valeur(v)},
    ("input_select", "select_option"): {"option": lambda v: _option(v)},
    ("select", "select_option"): {"option": lambda v: _option(v)},
}
# Services dont une donnée est obligatoire.
REQUIS: dict[tuple[str, str], str] = {
    ("climate", "set_temperature"): "temperature",
    ("climate", "set_hvac_mode"): "hvac_mode",
    ("media_player", "volume_set"): "volume_level",
    ("input_number", "set_value"): "value",
    ("number", "set_value"): "value",
    ("input_select", "select_option"): "option",
    ("select", "select_option"): "option",
}


def domaine(eid: str) -> str:
    return eid.split(".", 1)[0] if "." in eid else ""


def services_proposes(eid: str) -> list[str]:
    """Les services qu'un administrateur peut cocher pour cette entité (vide : lecture seule)."""
    d = domaine(eid)
    if d in INTERDITS:
        return []
    return list(SERVICES.get(d, ()))


def pilotable(eid: str) -> bool:
    return bool(services_proposes(eid))


def roles_possibles(eid: str) -> list[str]:
    """Les rôles qu'un administrateur peut choisir pour cette entité (« lecture » toujours)."""
    d = domaine(eid)
    if d in INTERDITS or not pilotable(eid):
        return [LECTURE]
    return [LECTURE] + [r for r in (INTERRUPTEUR, BOUTON, REGLAGE) if d in ROLE_DOMAINES[r]]


def _role_deduit(store, eid: str) -> str:
    """Sans réglage enregistré : le rôle que donne la liste blanche (comportement d'avant la 2.4.0)."""
    if not [s for s in services_proposes(eid) if s in set(store.entites_actions.get(eid) or [])]:
        return LECTURE
    possibles = roles_possibles(eid)
    return possibles[1] if len(possibles) > 1 else LECTURE


def mise_en_page(m: Any) -> dict[str, Any]:
    """La mise en page complète (défauts compris) d'après ce qui est enregistré ; une valeur inconnue → le défaut."""
    m = m if isinstance(m, dict) else {}
    return {
        "align_h": m.get("align_h") if m.get("align_h") in ALIGNS_H else MISE_EN_PAGE_DEFAUT["align_h"],
        "align_v": m.get("align_v") if m.get("align_v") in ALIGNS_V else MISE_EN_PAGE_DEFAUT["align_v"],
        "taille": m.get("taille") if m.get("taille") in TAILLES else MISE_EN_PAGE_DEFAUT["taille"],
        "titre": bool(m.get("titre", MISE_EN_PAGE_DEFAUT["titre"])),
        "icone": bool(m.get("icone", MISE_EN_PAGE_DEFAUT["icone"])),
    }


def mise_en_page_voulue(actuelle: dict[str, Any], voulu: Any) -> dict[str, Any]:
    """Ce qui change dans la mise en page ([voulu] : une partie des clés). textes.Erreur si une valeur est fausse."""
    if not isinstance(voulu, dict) or not set(voulu) <= set(MISE_EN_PAGE_DEFAUT):
        raise textes.Erreur("wida.reglage_inconnu")
    nouvelle = dict(actuelle)
    for cle, permis in (("align_h", ALIGNS_H), ("align_v", ALIGNS_V), ("taille", TAILLES)):
        if cle in voulu:
            if voulu[cle] not in permis:
                raise textes.Erreur("wida.reglage_inconnu")
            nouvelle[cle] = voulu[cle]
    for cle in ("titre", "icone"):
        if cle in voulu:
            if not isinstance(voulu[cle], bool):
                raise textes.Erreur("wida.reglage_inconnu")
            nouvelle[cle] = voulu[cle]
    return nouvelle


# 17-10 (l'auteur, « widget chauffage lisible ») : une entité dont l'état s'écrit sous la valeur du widget (« Réduit jusqu'à
# 12:00, puis Confort 20 °C »). Choisie dans la carte Widgets HA ; aucun nom d'entité dans le code.
TEXTE_MAX = 160


def _texte_valide(t: Any) -> str:
    t = str(t or "").strip()
    if not t:
        return ""
    if len(t) > 255 or "." not in t or domaine(t) in INTERDITS or not all(c.isalnum() or c in "._" for c in t):
        raise textes.Erreur("wida.texte")
    return t


def texte_lie(hass: HomeAssistant | None, store, eid: str) -> str | None:
    """L'état de l'entité « texte » liée au widget de `eid` (None : pas d'entité liée ; « » : rien à dire)."""
    t = reglage(store, eid)["texte"]
    if not t:
        return None
    etat = hass.states.get(t) if hass is not None else None
    if etat is None or etat.state in ("unknown", "unavailable", ""):
        return ""
    return etat.state[:TEXTE_MAX]


def liens_texte(store, eids: Any) -> dict[str, list[str]]:
    """Entité texte → les entités (parmi `eids`) dont le widget l'affiche : un changement du texte redessine leur widget."""
    sortie: dict[str, list[str]] = {}
    for e in eids:
        t = reglage(store, e)["texte"]
        if t:
            sortie.setdefault(t, []).append(e)
    return sortie


def reglage(store, eid: str) -> dict[str, Any]:
    """Le réglage complet d'une entité, valeurs par défaut comprises (jamais None)."""
    r = dict(getattr(store, "entites_reglages", {}).get(eid) or {})
    role = r.get("role") if r.get("role") in roles_possibles(eid) else _role_deduit(store, eid)
    attributs = [str(a)[:64] for a in (r.get("attributs") or []) if isinstance(a, str)][:ATTRIBUTS_MAX]
    return {
        "role": role,
        "qui": r.get("qui") if r.get("qui") in QUI else "decideurs",
        "confirmer": bool(r.get("confirmer", False)),
        "nom": str(r.get("nom") or "")[:NOM_MAX],
        "icone": str(r.get("icone") or "")[:ICONE_MAX],
        "affichage": r.get("affichage") if r.get("affichage") in AFFICHAGES else "valeur",
        "attributs": attributs,
        "toucher": r.get("toucher") if r.get("toucher") in TOUCHERS else TOUCHER_ROLE,
        "chemin": str(r.get("chemin") or "")[:CHEMIN_MAX],
        "mise_en_page": mise_en_page(r.get("mise_en_page")),
        "texte": r.get("texte") if isinstance(r.get("texte"), str) and len(r.get("texte")) <= 255 else "",
    }


def chemin_sur(chemin: Any) -> str:
    """Un chemin de Home Assistant (« /tableau/vue », « /lovelace/0?x=1 ») ; jamais une autre adresse. Erreur sinon."""
    c = str(chemin or "").strip()
    if not c or len(c) > CHEMIN_MAX or not c.startswith("/") or c.startswith("//") or "://" in c \
            or any(ch not in _CHEMIN_PERMIS for ch in c):
        raise textes.Erreur("wida.chemin")
    return c


def toucher_pour(store, pc: dict[str, Any] | None, eid: str) -> tuple[str, str]:
    """2.4.3 : ce que fait un toucher sur CET appareil, et le chemin à ouvrir. Ouvrir les détails ou une vue de Home
    Assistant demande le droit d'agir sur l'entité (Décideur, ou entité réglée « tout le monde ») ; sans lui, le
    toucher retombe sur l'action du rôle (qui reste elle-même bornée par les actions permises)."""
    r = reglage(store, eid)
    t = r["toucher"]
    if t in (TOUCHER_DETAILS, TOUCHER_VUE) and not peut_agir(store, pc, eid):
        return TOUCHER_ROLE, ""
    if t == TOUCHER_VUE:
        try:
            return t, chemin_sur(r["chemin"])
        except ValueError:
            return TOUCHER_ROLE, ""
    return t, ""


async def vues_ha(hass: HomeAssistant) -> list[dict[str, str]]:
    """Les tableaux de bord et leurs vues, pour choisir où mène un toucher : [{chemin, titre}]."""
    from . import veille_dash

    sortie: list[dict[str, str]] = []
    for adresse, tableau in list(veille_dash._lovelace(hass).items()):
        nom = "lovelace" if adresse in (None, "lovelace") else str(adresse)
        titre = veille_dash._titre_tableau(adresse, tableau)
        sortie.append({"chemin": f"/{nom}", "titre": titre})
        try:
            config = await veille_dash._config(hass, nom)
        except Exception:  # noqa: BLE001 - un tableau illisible : seulement son entrée
            config = None
        for i, vue in enumerate((config or {}).get("views") or []):
            if not isinstance(vue, dict):
                continue
            chemin = f"/{nom}/{vue.get('path') or i}"
            try:
                chemin = chemin_sur(chemin)
            except ValueError:
                continue
            sortie.append({"chemin": chemin, "titre": f"{titre} › {vue.get('title') or vue.get('path') or i}"})
    sortie.sort(key=lambda v: v["titre"].lower())
    return sortie[:300]


def donnees_sures(eid: str, service: str, donnees: Any, etat: State | None = None) -> dict[str, Any]:
    """Les données d'une action, vérifiées : seulement les clés connues de ce service, bornées.

    ValueError sinon (clé inconnue, valeur hors bornes, mode non proposé par l'entité, donnée requise absente).
    """
    if donnees is None:
        donnees = {}
    if not isinstance(donnees, dict):
        raise ValueError("data")
    permis = DONNEES.get((domaine(eid), service), {})
    sortie: dict[str, Any] = {}
    for cle, valeur in donnees.items():
        if cle not in permis:
            raise textes.Erreur("wid.cle_refusee", nom=str(cle)[:40])
        try:
            sortie[cle] = permis[cle](valeur)
        except (TypeError, ValueError, OverflowError) as err:
            raise textes.Erreur("wid.valeur_refusee", nom=cle) from err
    requis = REQUIS.get((domaine(eid), service))
    if requis and requis not in sortie:
        raise textes.Erreur("wid.donnee_requise", nom=requis)
    if "hvac_mode" in sortie and etat is not None:
        modes = etat.attributes.get("hvac_modes")
        if isinstance(modes, (list, tuple)) and sortie["hvac_mode"] not in [str(m) for m in modes]:
            raise textes.Erreur("wid.valeur_refusee", nom="hvac_mode")
    if "value" in sortie and etat is not None:
        bas, haut = _nombre(etat.attributes.get("min")), _nombre(etat.attributes.get("max"))
        if (bas is not None and sortie["value"] < bas) or (haut is not None and sortie["value"] > haut):
            raise textes.Erreur("wid.valeur_refusee", nom="value")
    if "option" in sortie and etat is not None:
        options = etat.attributes.get("options")
        if not isinstance(options, (list, tuple)) or sortie["option"] not in [str(o) for o in options]:
            raise textes.Erreur("wid.valeur_refusee", nom="option")
    if "temperature" in sortie and etat is not None:
        bas, haut = etat.attributes.get("min_temp"), etat.attributes.get("max_temp")
        try:
            if bas is not None and sortie["temperature"] < float(bas) or haut is not None and sortie["temperature"] > float(haut):
                raise textes.Erreur("wid.valeur_refusee", nom="temperature")
        except (TypeError, ValueError) as err:
            raise textes.Erreur("wid.valeur_refusee", nom="temperature") from err
    return sortie


# ----------------------------------------------------------------------------- Droits


def _propre(liste: Any) -> list[str]:
    """Une liste d'entity_id sans doublon ni valeur bizarre, dans l'ordre."""
    vus: list[str] = []
    for e in liste or []:
        e = str(e or "").strip()
        if "." in e and e not in vus and len(e) <= 255:
            vus.append(e)
    return vus


def visibles(store, pc: dict[str, Any] | None) -> list[str]:
    """Les entités que cet appareil peut voir : (sa personne ∪ plus) − moins. Sans le rôle : voir `peut_voir`."""
    if pc is None:
        return []
    personne = str(pc.get("person") or "")
    base = list(getattr(store, "entites_tous", []) or [])
    base += list(store.entites_personnes.get(personne) or []) if personne else []
    exc = store.entites_appareils.get(str(pc.get("id") or "")) or {}
    moins = set(exc.get("moins") or [])
    return [e for e in _propre(base + list(exc.get("plus") or [])) if e not in moins]


def peut_voir(store, pc: dict[str, Any] | None) -> bool:
    return roles.a(store, pc, "entites_voir")


def peut_piloter(store, pc: dict[str, Any] | None) -> bool:
    return roles.a(store, pc, "entites_piloter")


def actions_permises(store, pc: dict[str, Any] | None, eid: str) -> list[str]:
    """Ce que cet appareil peut faire sur cette entité (vide : lecture seule)."""
    if eid not in visibles(store, pc) or not peut_agir(store, pc, eid):
        return []
    if reglage(store, eid)["role"] == LECTURE:
        return []
    proposes = services_proposes(eid)
    blanche = set(store.entites_actions.get(eid) or [])
    # 2.4.0 (l'auteur : « l'appli ne gère pas le volume ») : jamais de volume sur un lecteur « · Vision » (la télé le règle).
    from . import musique
    if eid in musique.lecteurs_vision():
        blanche.discard("volume_set")
    return [s for s in proposes if s in blanche]


def peut_agir(store, pc: dict[str, Any] | None, eid: str) -> bool:
    """2.4.0 : la fonction de rôle « piloter », ou une entité réglée « tout le monde » (et le droit de voir)."""
    if peut_piloter(store, pc):
        return True
    return reglage(store, eid)["qui"] == "tous" and peut_voir(store, pc)


def version(store, pc: dict[str, Any] | None) -> int:
    """Un entier qui change quand les droits de cet appareil changent (le relevé le porte)."""
    vis = visibles(store, pc)
    empreinte = {
        "voir": peut_voir(store, pc),
        "piloter": peut_piloter(store, pc),
        "module": modules.actif(store, MODULE),
        "entites": vis,
        "actions": {e: actions_permises(store, pc, e) for e in vis},
        "reglages": {e: reglage(store, e) for e in vis},
    }
    return zlib.crc32(json.dumps(empreinte, sort_keys=True).encode()) & 0x7FFFFFFF


# ----------------------------------------------------------------------------- Ce qu'on montre

ATTRIBUTS = (
    "friendly_name", "unit_of_measurement", "device_class", "icon", "state_class",
    "brightness", "color_mode", "percentage",
    "temperature", "current_temperature", "target_temp_low", "target_temp_high", "target_temp_step",
    "min_temp", "max_temp", "hvac_action", "hvac_modes", "preset_mode",
    "current_position", "min", "max", "step", "options",
    "humidity", "apparent_temperature", "wind_speed", "wind_bearing", "pressure", "precipitation_unit",
    "temperature_unit", "wind_speed_unit", "pressure_unit",
    # 1.98.0 : le lecteur (jamais entity_picture : son adresse porte un jeton ; voir « pochette »).
    "media_title", "media_artist", "media_album_name", "app_name", "volume_level", "is_volume_muted",
)


def _simple(v: Any) -> Any:
    if v is None or isinstance(v, (bool, int, float, str)):
        return v if not isinstance(v, str) else v[:200]
    if isinstance(v, (list, tuple)) and len(v) <= 20 and all(isinstance(x, (bool, int, float, str)) for x in v):
        return list(v)
    if isinstance(v, (datetime.datetime, datetime.date)):
        return v.isoformat()
    return str(v)[:200]


def attributs_utiles(etat: State | None, hass: HomeAssistant | None = None) -> dict[str, Any]:
    """Un petit sous-ensemble des attributs, utile au rendu ; jamais tout l'état."""
    if etat is None:
        return {}
    a = etat.attributes
    sortie = {k: _simple(a[k]) for k in ATTRIBUTS if k in a}
    previsions = a.get("forecast")
    if isinstance(previsions, list) and previsions:
        sortie["forecast"] = [
            {k: _simple(p.get(k)) for k in ("datetime", "condition", "temperature", "templow", "precipitation_probability")
             if isinstance(p, dict) and k in p}
            for p in previsions[:5] if isinstance(p, dict)
        ]
    if domaine(etat.entity_id) == "media_player":
        img = str(a.get("entity_picture") or "")
        if not img and hass is not None:
            # 2.4.0 (l'auteur : la pochette du widget Musique ne changeait plus) : un lecteur « · Vision » ne donne pas
            # d'image ; l'empreinte suit alors le titre en cours dans la file de Music Assistant (comme la Party).
            from . import musique
            try:
                img = musique.image_en_cours(hass, etat.entity_id)
            except Exception:  # noqa: BLE001 - une file illisible : pas de pochette, comme avant
                img = ""
        # Une empreinte qui change avec le morceau ; l'image se lit par /api/pc_parental/party/pochette.
        sortie["pochette"] = format(zlib.crc32(img.encode()) & 0xFFFFFFFF, "x") if img else ""
    return sortie


def _nombre(v: Any) -> float | None:
    try:
        n = float(v)
    except (TypeError, ValueError):
        return None
    return n if n == n and n not in (float("inf"), float("-inf")) else None


def numerique(etat: State | None) -> bool:
    if etat is None:
        return False
    if domaine(etat.entity_id) in ("sensor", "number", "input_number"):
        return _nombre(etat.state) is not None or "unit_of_measurement" in etat.attributes
    return False


OUVERTURES = ("door", "garage_door", "opening", "window", "gate")


def styles(etat: State | None, eid: str) -> list[str]:
    """Les styles proposés selon le domaine (l'appli en choisit un ; mini_carte groupe 2 à 4 entités)."""
    d = domaine(eid)
    sortie = ["tuile"]
    if d in ("script", "scene", "button", "input_button"):
        sortie.append("bouton")
    if d in ("light", "switch", "fan", "input_boolean"):
        sortie.append("interrupteur")
    if numerique(etat):
        sortie += ["jauge", "graphique"]
    if d == "weather":
        sortie.append("meteo")
    if d == "media_player":
        sortie.insert(0, "lecteur")
    # 2.4.0 : régler une valeur ou choisir dans une liste (l'appli : − / + ou la liste des options).
    if d in ("input_number", "number", "input_select", "select"):
        sortie.insert(0, "reglage")
    if d == "binary_sensor" and etat is not None and str(etat.attributes.get("device_class") or "") in OUVERTURES:
        sortie.append("ouverture")
    sortie.append("mini_carte")
    return sortie


def _depuis(etat: State) -> str:
    return etat.last_changed.isoformat()


def fiche(hass: HomeAssistant, store, pc: dict[str, Any], eid: str) -> dict[str, Any]:
    etat = hass.states.get(eid)
    a = etat.attributes if etat is not None else {}
    r = reglage(store, eid)
    return {
        "entity_id": eid,
        # 2.4.0 : le nom et l'icône choisis par un administrateur passent avant ceux de Home Assistant.
        "nom": r["nom"] or str(a.get("friendly_name") or eid),
        "nom_ha": str(a.get("friendly_name") or eid),
        "domaine": domaine(eid),
        "icone": r["icone"] or str(a.get("icon") or ""),
        "role": r["role"],
        "confirmer": r["confirmer"] and r["role"] != LECTURE,
        "affichage": r["affichage"],
        # 2.5.3 : la mise en page, commune à HA et aux téléphones des Décideurs.
        "mise_en_page": r["mise_en_page"],
        # 2.4.3 : le toucher, selon les droits de cet appareil ; « ouvrir » : il peut ouvrir les détails ou une vue
        # (l'éditeur de l'appli ne propose pas plus).
        "toucher": toucher_pour(store, pc, eid)[0],
        "chemin": toucher_pour(store, pc, eid)[1],
        "ouvrir": peut_agir(store, pc, eid),
        "unite": str(a.get("unit_of_measurement") or ""),
        "device_class": str(a.get("device_class") or ""),
        "etat": etat.state if etat is not None else "unavailable",
        "depuis": _depuis(etat) if etat is not None else "",
        "attributs_utiles": attributs_utiles(etat, hass),
        "actions": actions_permises(store, pc, eid),
        "styles": styles(etat, eid),
        **({"texte": t} if (t := texte_lie(hass, store, eid)) is not None else {}),
    }


def _humain(cle: str) -> str:
    return cle.replace("_", " ").strip().capitalize()


def detail_attributs(etat: State | None, choisis: list[str]) -> list[dict[str, str]]:
    """Le détail d'une entité : ses attributs choisis (tous les attributs simples si aucun n'est choisi)."""
    if etat is None:
        return []
    a = etat.attributes
    cles = [c for c in choisis if c in a] if choisis else [c for c in a if c not in ATTRIBUTS_CACHES]
    sortie = []
    for c in cles:
        brut = a[c]
        # 2.4.4 : un attribut liste se lit élément par élément — une ligne d'en-tête (nom de
        # l'attribut, nombre d'éléments), puis une ligne par élément ; avant, tout était collé en une ligne coupée.
        if isinstance(brut, (list, tuple)):
            elements = [x for x in brut if x is not None and str(x).strip() != ""]
            sortie.append({"texte": _humain(c), "valeur": str(len(elements))})
            sortie += [{"texte": _element(x), "valeur": ""} for x in elements]
            continue
        sortie.append({"texte": _humain(c), "valeur": str(_simple(brut))[:120]})
    return sortie


def _element(x: Any) -> str:
    """Un élément d'un attribut liste, en texte : tel quel, ou ses valeurs simples pour un dictionnaire."""
    if isinstance(x, dict):
        x = " · ".join(str(v) for v in x.values() if isinstance(v, (str, int, float)) and str(v).strip())
    return str(_simple(x) if not isinstance(x, str) else x)[:200]


_DETAILS_TODO: dict[str, tuple[float, list[dict[str, str]], int]] = {}
TODO_CACHE_S = 20.0


async def detail_todo(hass: HomeAssistant, eid: str) -> tuple[list[dict[str, str]], int]:
    """Les éléments d'une liste todo à faire (service todo.get_items), 20 au plus, gardés 20 s ; ([], 0) sinon."""
    garde = _DETAILS_TODO.get(eid)
    if garde and time.monotonic() - garde[0] < TODO_CACHE_S:
        return garde[1], garde[2]
    try:
        reponse = await hass.services.async_call(
            "todo", "get_items", {"status": ["needs_action"]}, target={"entity_id": eid},
            blocking=True, return_response=True, context=Context())
    except Exception as e:  # noqa: BLE001 - pas de détail : la valeur reste
        _LOGGER.debug("Détail de %s illisible : %s", eid, e)
        return [], 0
    items = ((reponse or {}).get(eid) or {}).get("items") or []
    sortie = []
    for it in items:
        if not isinstance(it, dict):
            continue
        texte = str(it.get("summary") or "").strip()[:120]
        if texte:
            sortie.append({"texte": texte, "valeur": str(it.get("due") or "")[:20]})
    total = len(sortie)
    _DETAILS_TODO[eid] = (time.monotonic(), sortie[:DETAIL_MAX], total)
    return sortie[:DETAIL_MAX], total


async def avec_detail(hass: HomeAssistant, store, eid: str, sortie: dict[str, Any], force: bool = False) -> dict[str, Any]:
    """Ajoute « detail » et « detail_total » quand l'affichage de l'entité le demande, ou quand un widget de l'appli
    l'a choisi (force) ; rien sinon."""
    r = reglage(store, eid)
    if r["affichage"] == "valeur" and not force:
        return sortie
    if domaine(eid) == "todo":
        detail, total = await detail_todo(hass, eid)
    else:
        tout = detail_attributs(hass.states.get(eid), r["attributs"])
        detail, total = tout[:DETAIL_ATTRIBUTS_MAX], len(tout)
    sortie["detail"], sortie["detail_total"] = detail, total
    return sortie


def etat_court(etat: State | None, hass: HomeAssistant | None = None, store=None, eid: str = "") -> dict[str, Any]:
    texte = texte_lie(hass, store, eid) if store is not None and eid else None
    plus = {"texte": texte} if texte is not None else {}
    if etat is None:
        return {"etat": "unavailable", "attributs_utiles": {}, "depuis": "", **plus}
    return {"etat": etat.state, "attributs_utiles": attributs_utiles(etat, hass), "depuis": _depuis(etat), **plus}


# ----------------------------------------------------------------------------- Historique réduit

PERIODES = {"24h": datetime.timedelta(hours=24), "7j": datetime.timedelta(days=7)}
MAX_POINTS = 200


def reduire_nombres(points: list[tuple[float, float]], debut: float, fin: float, n: int = MAX_POINTS) -> list[list[float]]:
    """Moyenne par intervalle : au plus n points [horodatage (s) du milieu de l'intervalle, moyenne].

    `points` : (horodatage, valeur) triés. Un intervalle sans mesure est sauté (pas d'invention).
    """
    if not points or fin <= debut:
        return []
    pas = (fin - debut) / n
    sommes = [0.0] * n
    comptes = [0] * n
    for t, v in points:
        if t < debut or t > fin:
            continue
        i = min(n - 1, int((t - debut) / pas))
        sommes[i] += v
        comptes[i] += 1
    return [[round(debut + (i + 0.5) * pas), round(sommes[i] / comptes[i], 2)] for i in range(n) if comptes[i]]


def reduire_etats(changements: list[tuple[float, str]], debut: float, fin: float, n: int = MAX_POINTS) -> list[list[Any]]:
    """Des segments [début, fin, état] (horodatages en s), états égaux consécutifs fusionnés, au plus n (les plus récents)."""
    segments: list[list[Any]] = []
    for t, s in changements:
        t = round(max(t, debut))
        if t > fin:
            break
        if segments and segments[-1][0] == t:
            segments.pop()  # deux états au même instant : le dernier compte
        if segments and segments[-1][2] == s:
            continue
        if segments:
            segments[-1][1] = t
        segments.append([t, round(fin), s])
    if segments:
        segments[-1][1] = round(fin)
    return segments[-n:]


def _lire_historique(hass: HomeAssistant, eid: str, debut: datetime.datetime, fin: datetime.datetime) -> list[State]:
    from homeassistant.components.recorder import history

    return history.state_changes_during_period(
        hass, debut, fin, eid, no_attributes=True, include_start_time_state=True,
    ).get(eid) or []


async def historique(hass: HomeAssistant, eid: str, periode: str) -> dict[str, Any]:
    fin = dt_util.utcnow()
    debut = fin - PERIODES[periode]
    from homeassistant.components.recorder import get_instance

    etats = await get_instance(hass).async_add_executor_job(_lire_historique, hass, eid, debut, fin)
    actuel = hass.states.get(eid)
    d, f = debut.timestamp(), fin.timestamp()
    if numerique(actuel):
        points = [(s.last_updated.timestamp(), v) for s in etats if (v := _nombre(s.state)) is not None]
        return {"type": "nombre", "unite": str(actuel.attributes.get("unit_of_measurement") or ""),
                "points": reduire_nombres(points, d, f)}
    return {"type": "etats", "segments": reduire_etats([(s.last_changed.timestamp(), str(s.state)) for s in etats], d, f)}


# ----------------------------------------------------------------------------- Réglages (administrateurs)


def _journal(hass: HomeAssistant, texte: str, par: str, **donnees: Any) -> None:
    _LOGGER.warning("%s : entités Home Assistant — %s (par %s).", PRODUIT, texte, par or "un administrateur")
    hass.bus.async_fire(EVENEMENT, {"texte": texte, "par": par, **donnees})


def _ecart(avant: list[str], apres: list[str]) -> str:
    plus_ = [f"+{e}" for e in apres if e not in avant]
    moins_ = [f"-{e}" for e in avant if e not in apres]
    return ", ".join(plus_ + moins_)


def regler_personne(hass: HomeAssistant, store, personne: str, entites: list[str], par: str) -> bool:
    if not personne.startswith("person."):
        raise textes.Erreur("wid.personne_inconnue")
    avant = list(store.entites_personnes.get(personne) or [])
    apres = _propre(entites)
    if avant == apres:
        return False
    if apres:
        store.entites_personnes[personne] = apres
    else:
        store.entites_personnes.pop(personne, None)
    _journal(hass, f"{personne} : {_ecart(avant, apres) or 'ordre changé'}", par, personne=personne, entites=apres)
    return True


def regler_appareil(hass: HomeAssistant, store, pc_id: str, plus: list[str], moins: list[str], par: str) -> bool:
    pc = store.pcs.get(pc_id)
    if pc is None:
        raise textes.Erreur("commun.appareil_inconnu")
    avant = store.entites_appareils.get(pc_id) or {"plus": [], "moins": []}
    apres = {"plus": _propre(plus), "moins": _propre(moins)}
    if avant.get("plus", []) == apres["plus"] and avant.get("moins", []) == apres["moins"]:
        return False
    if apres["plus"] or apres["moins"]:
        store.entites_appareils[pc_id] = apres
    else:
        store.entites_appareils.pop(pc_id, None)
    texte = "« {} » : plus {} ; moins {}".format(
        pc.get("name") or pc_id, _ecart(avant.get("plus", []), apres["plus"]) or "inchangé",
        _ecart(avant.get("moins", []), apres["moins"]) or "inchangé")
    _journal(hass, texte, par, pc=pc_id, **apres)
    return True


def regler_actions(hass: HomeAssistant, store, voulues: dict[str, list[str]], par: str) -> bool:
    """voulues : {entity_id: [services]} pour les entités montrées à l'écran. Les services hors de ceux
    proposés pour le domaine sont ignorés (serrures et alarmes : jamais)."""
    changements = []
    for eid, services in voulues.items():
        propres = [s for s in services_proposes(eid) if s in set(services or [])]
        avant = list(store.entites_actions.get(eid) or [])
        if propres == avant:
            continue
        if propres:
            store.entites_actions[eid] = propres
        else:
            store.entites_actions.pop(eid, None)
        changements.append("{} : {}".format(eid, ", ".join(propres) or "lecture seule"))
    if changements:
        _journal(hass, "actions permises — " + " ; ".join(changements), par, actions=dict(store.entites_actions))
    return bool(changements)


def regler_tous(hass: HomeAssistant, store, entites: list[str], par: str) -> bool:
    """2.4.0 : la liste « pour tout le monde » (vue par tous les profils)."""
    avant = list(store.entites_tous or [])
    apres = [e for e in _propre(entites) if domaine(e) not in INTERDITS]
    if avant == apres:
        return False
    store.entites_tous = apres
    _journal(hass, f"entites_tous : {_ecart(avant, apres) or '='}", par, tous=apres)
    return True


def regler_entite(hass: HomeAssistant, store, eid: str, voulu: dict[str, Any], par: str) -> bool:
    """2.4.0 : le réglage d'une entité (rôle, qui, confirmation, nom, icône, affichage, attributs). Le rôle fixe la
    liste blanche (tous les services du rôle proposés pour le domaine ; « lecture » : aucun). ValueError si faux."""
    if "." not in eid or domaine(eid) in INTERDITS:
        raise textes.Erreur("wida.non_pilotable")
    actuel = reglage(store, eid)
    nouveau = dict(actuel)
    if "role" in voulu:
        role = str(voulu.get("role") or "")
        if role not in roles_possibles(eid):
            raise textes.Erreur("wida.role_impossible")
        if role != actuel["role"] and "confirmer" not in voulu:
            nouveau["confirmer"] = role != LECTURE and domaine(eid) in CONFIRMER_DEFAUT
        nouveau["role"] = role
    if "qui" in voulu:
        if voulu.get("qui") not in QUI:
            raise textes.Erreur("wida.reglage_inconnu")
        nouveau["qui"] = voulu["qui"]
        if voulu["qui"] == "tous" and actuel["qui"] != "tous" and "confirmer" not in voulu:
            nouveau["confirmer"] = True
    if "confirmer" in voulu:
        nouveau["confirmer"] = bool(voulu.get("confirmer"))
    for cle, borne in (("nom", NOM_MAX), ("icone", ICONE_MAX)):
        if cle in voulu:
            nouveau[cle] = str(voulu.get(cle) or "").strip()[:borne]
    if nouveau["icone"] and not nouveau["icone"].startswith("mdi:"):
        raise textes.Erreur("wida.icone")
    if "affichage" in voulu:
        if voulu.get("affichage") not in AFFICHAGES:
            raise textes.Erreur("wida.reglage_inconnu")
        nouveau["affichage"] = voulu["affichage"]
    if "attributs" in voulu:
        nouveau["attributs"] = [str(a)[:64] for a in (voulu.get("attributs") or []) if isinstance(a, str)][:ATTRIBUTS_MAX]
    if "chemin" in voulu:
        nouveau["chemin"] = chemin_sur(voulu["chemin"]) if str(voulu.get("chemin") or "").strip() else ""
    if "toucher" in voulu:
        if voulu.get("toucher") not in TOUCHERS:
            raise textes.Erreur("wida.reglage_inconnu")
        nouveau["toucher"] = voulu["toucher"]
    if nouveau["toucher"] == TOUCHER_VUE and not nouveau["chemin"]:
        raise textes.Erreur("wida.chemin")
    if "mise_en_page" in voulu:
        nouveau["mise_en_page"] = mise_en_page_voulue(actuel["mise_en_page"], voulu["mise_en_page"])
    if "texte" in voulu:
        nouveau["texte"] = _texte_valide(voulu.get("texte"))
        if nouveau["texte"] == eid:
            raise textes.Erreur("wida.texte")
    if nouveau == actuel and eid in store.entites_reglages:
        return False
    store.entites_reglages[eid] = nouveau
    services = [] if nouveau["role"] == LECTURE else list(services_proposes(eid))
    if services:
        store.entites_actions[eid] = services
    else:
        store.entites_actions.pop(eid, None)
    _journal(hass, "{} : {} / {} / {}".format(eid, nouveau["role"], nouveau["qui"], int(nouveau["confirmer"])),
             par, entite=eid, reglage=nouveau)
    return True


def toutes_choisies(store) -> list[str]:
    """Toutes les entités données à quelqu'un (personnes et « plus » des appareils), pour l'écran des actions."""
    tout: list[str] = list(getattr(store, "entites_tous", []) or [])
    for liste in store.entites_personnes.values():
        tout += list(liste or [])
    for exc in store.entites_appareils.values():
        tout += list(exc.get("plus") or [])
    return sorted(set(_propre(tout)))


# ----------------------------------------------------------------------------- Les portes


def _coord(hass: HomeAssistant):
    entrees = list(hass.data.get(DOMAIN, {}).values())
    return entrees[0] if entrees else None


class _Porte(HomeAssistantView):
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def _ouvrir(self, request: web.Request, corps: dict[str, Any] | None = None):
        """(coord, pc, corps, None) ou (None, None, None, réponse d'erreur). Vérifie l'appareil, le module, entites_voir."""
        if corps is None:
            try:
                corps = await request.json()
            except ValueError:
                return None, None, None, self.json({"ok": False, "error": "json"}, status_code=400)
            if not isinstance(corps, dict):
                return None, None, None, self.json({"ok": False, "error": "json"}, status_code=400)
        coord = _coord(self.hass)
        if coord is None:
            return None, None, None, self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or ""))
        if pc is None:
            return None, None, None, self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.noter_langue(pc, corps)
        if refus := modules.refus(self, coord.store, MODULE, langue=textes.pour(self.hass, pc)):
            return None, None, None, refus
        if not peut_voir(coord.store, pc):
            return None, None, None, self.json({"ok": False, "error": "voir"}, status_code=403)
        return coord, pc, corps, None


class PcParentalWidgetsEntitesView(_Porte):
    url = URL + "/entites"
    name = "api:pc_parental:widgets_entites"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, _corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        store = coord.store
        entites = [await avec_detail(self.hass, store, e, fiche(self.hass, store, pc, e)) for e in visibles(store, pc)]
        sortie: dict[str, Any] = {"ok": True, "version": version(store, pc), "entites": entites}
        # 2.4.3 : les vues de Home Assistant, pour l'éditeur de l'appli d'un Décideur (« Au toucher » › vue).
        if peut_piloter(store, pc):
            sortie["vues_ha"] = await vues_ha(self.hass)
        return self.json(sortie)


class PcParentalWidgetsEtatsView(_Porte):
    url = URL + "/etats"
    name = "api:pc_parental:widgets_etats"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        permises = visibles(coord.store, pc)
        voulues = corps.get("entites")
        if isinstance(voulues, list):
            demandees = {str(e) for e in voulues[:500]}
            choisies = [e for e in permises if e in demandees]
        else:
            choisies = permises
        # 2.4.0 : « details » : les entités dont un widget de l'appli montre le détail (choix fait dans l'appli).
        details = {str(e) for e in (corps.get("details") or [])[:200]} if isinstance(corps.get("details"), list) else set()
        return self.json({"ok": True, "etats": {e: await avec_detail(self.hass, coord.store, e, etat_court(self.hass.states.get(e), self.hass, coord.store, e), e in details)
                                               for e in choisies}})


class PcParentalWidgetsHistoriqueView(_Porte):
    url = URL + "/historique"
    name = "api:pc_parental:widgets_historique"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        eid = str(corps.get("entite") or "")
        periode = str(corps.get("periode") or "24h")
        if periode not in PERIODES:
            return self.json({"ok": False, "error": "periode"}, status_code=400)
        if eid not in visibles(coord.store, pc):
            return self.json({"ok": False, "error": "entite"}, status_code=403)
        try:
            sortie = await historique(self.hass, eid, periode)
        except Exception as e:  # noqa: BLE001 - sans enregistreur, pas d'historique ; rien d'autre ne casse
            _LOGGER.debug("Historique de %s indisponible : %s", eid, e)
            return self.json({"ok": False, "error": "historique"}, status_code=503)
        return self.json({"ok": True, "entite": eid, "periode": periode, **sortie})


# Garde-fou : au plus N actions par appareil sur une minute glissante.
ACTIONS_PAR_MINUTE = 30
_recentes: dict[str, list[float]] = {}


def _trop_vite(pc_id: str, maximum: int = ACTIONS_PAR_MINUTE) -> bool:
    maintenant = time.monotonic()
    l = [t for t in _recentes.get(pc_id, []) if maintenant - t < 60]
    trop = len(l) >= maximum
    if not trop:
        l.append(maintenant)
    _recentes[pc_id] = l
    return trop


class PcParentalWidgetsActionView(_Porte):
    url = URL + "/action"
    name = "api:pc_parental:widgets_action"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        store = coord.store
        eid = str(corps.get("entite") or "")
        service = str(corps.get("service") or "")
        if not peut_agir(store, pc, eid):
            return self.json({"ok": False, "error": "piloter"}, status_code=403)
        if eid not in visibles(store, pc):
            return self.json({"ok": False, "error": "entite"}, status_code=403)
        if domaine(eid) in INTERDITS or service not in actions_permises(store, pc, eid):
            return self.json({"ok": False, "error": "action"}, status_code=403)
        etat = self.hass.states.get(eid)
        if etat is None:
            return self.json({"ok": False, "error": "absente"}, status_code=404)
        try:
            donnees = donnees_sures(eid, service, corps.get("data"), etat)
        except ValueError as e:
            return self.json({"ok": False, "error": "data", "retour": textes.erreur(e, textes.pour(self.hass, pc))},
                             status_code=400)
        # 2.4.0 : une action réglée « avec confirmation » n'est faite que si l'appareil dit l'avoir demandée.
        r = reglage(store, eid)
        if r["confirmer"] and corps.get("confirme") is not True:
            nom = r["nom"] or str(etat.attributes.get("friendly_name") or eid)
            return self.json({"ok": False, "error": "confirmer", "confirmer": True,
                              "retour": textes.t("wid.confirmer", textes.pour(self.hass, pc), nom=nom)}, status_code=409)
        if _trop_vite(str(pc.get("id"))):
            return self.json({"ok": False, "error": "trop_vite"}, status_code=429)
        try:
            # Contexte sans utilisateur : jamais les droits d'un administrateur.
            await self.hass.services.async_call(
                domaine(eid), service, dict(donnees), target={"entity_id": eid}, blocking=True, context=Context(),
            )
        except Exception as e:  # noqa: BLE001 - l'appareil reçoit une erreur courte, le journal le détail
            _LOGGER.info("%s : action %s.%s sur %s depuis « %s » échouée : %s", PRODUIT, domaine(eid), service, eid,
                         pc.get("name"), e)
            return self.json({"ok": False, "error": "service", "retour": str(e)[:120]}, status_code=502)
        _LOGGER.info("%s : « %s » a fait %s.%s sur %s %s", PRODUIT, pc.get("name"), domaine(eid), service, eid, donnees or "")
        return self.json({"ok": True})


BATTEMENT_S = 25.0
FILE_MAX = 256


class PcParentalWidgetsFluxView(_Porte):
    """Flux SSE des changements d'état, pour les seules entités permises à cet appareil."""

    url = URL + "/flux"
    name = "api:pc_parental:widgets_flux"
    battement = BATTEMENT_S

    async def get(self, request: web.Request) -> web.StreamResponse:
        corps = {"id": request.query.get("id", ""), "secret": request.query.get("secret", "")}
        return await self._flux(request, corps)

    async def post(self, request: web.Request) -> web.StreamResponse:
        return await self._flux(request, None)

    async def _flux(self, request: web.Request, corps: dict[str, Any] | None) -> web.StreamResponse:
        coord, pc, _corps, err = await self._ouvrir(request, corps)
        if err is not None:
            return err
        hass = self.hass
        pc_id = str(pc.get("id"))
        secret = str(pc.get("secret") or "")
        file: asyncio.Queue = asyncio.Queue(maxsize=FILE_MAX)
        permises: dict[str, Any] = {"ens": set(), "quand": 0.0, "liens": {}}

        def _ensemble() -> set[str]:
            permises["ens"], permises["quand"] = set(visibles(coord.store, coord.store.pcs.get(pc_id))), time.monotonic()
            permises["liens"] = liens_texte(coord.store, permises["ens"])
            return permises["ens"]

        def _pousser(eid: str, nouveau: State | None) -> None:
            try:
                file.put_nowait({"entity_id": eid, **etat_court(nouveau, hass, coord.store, eid)} if nouveau is not None else
                                {"entity_id": eid, "etat": None, "attributs_utiles": {}, "depuis": ""})
            except asyncio.QueueFull:
                pass  # un appareil trop lent rattrape par /etats

        @callback
        def _sur_etat(event: Event) -> None:
            eid = event.data.get("entity_id")
            # 17-10 : l'entité « texte » d'un widget a changé : le widget est redessiné avec son nouveau texte.
            for lie in permises["liens"].get(eid, []):
                if lie in permises["ens"]:
                    _pousser(lie, hass.states.get(lie))
            # Une entité inconnue de l'appareil : on ne recalcule ses droits qu'au plus toutes les 5 s (un ajout
            # arrive avec ce délai) ; une entité permise : droits recalculés à chaque fois (un retrait est immédiat).
            if eid not in permises["ens"] and time.monotonic() - permises["quand"] < 5:
                return
            if eid not in _ensemble():
                return
            _pousser(eid, event.data.get("new_state"))

        @callback
        def _arret(_event: Event) -> None:
            try:
                file.put_nowait(None)
            except asyncio.QueueFull:
                pass

        reponse = web.StreamResponse(headers={
            "Content-Type": "text/event-stream", "Cache-Control": "no-store", "X-Accel-Buffering": "no",
        })
        _ensemble()
        desabonner = hass.bus.async_listen(EVENT_STATE_CHANGED, _sur_etat)
        desabonner_arret = hass.bus.async_listen_once(EVENT_HOMEASSISTANT_STOP, _arret)
        try:
            await reponse.prepare(request)
            await reponse.write(b"retry: 5000\n\n")
            while True:
                try:
                    message = await asyncio.wait_for(file.get(), timeout=self.battement)
                except asyncio.TimeoutError:
                    message = ""
                if message is None:
                    break
                # Droits revérifiés en cours de route : appareil retiré, secret changé, module coupé, rôle changé.
                courant = coord.store.pcs.get(pc_id)
                if (courant is None or str(courant.get("secret") or "") != secret
                        or modules.coupe(coord.store, MODULE) or not peut_voir(coord.store, courant)):
                    await reponse.write(b"event: fin\ndata: {}\n\n")
                    break
                if message == "":
                    await reponse.write(b": battement\n\n")
                    continue
                if message["entity_id"] not in _ensemble():
                    continue
                await reponse.write(("data: " + json.dumps(message, ensure_ascii=False) + "\n\n").encode())
        except (ConnectionResetError, RuntimeError):
            pass  # l'appareil a raccroché
        finally:
            desabonner()
            try:
                desabonner_arret()
            except ValueError:
                pass  # déjà déclenché
        return reponse


class PcParentalWidgetsMiseEnPageView(_Porte):
    """2.5.3 : la mise en page d'un widget depuis le téléphone d'un Décideur — la même que dans l'onglet Widgets HA.
    Réservée aux appareils qui pilotent (entites_piloter), pour une entité qu'ils voient ; rien d'autre ne change."""

    url = URL + "/mise_en_page"
    name = "api:pc_parental:widgets_mise_en_page"

    async def post(self, request: web.Request) -> web.Response:
        coord, pc, corps, err = await self._ouvrir(request)
        if err is not None:
            return err
        store = coord.store
        if not peut_piloter(store, pc):
            return self.json({"ok": False, "error": "piloter"}, status_code=403)
        eid = str(corps.get("entite") or "")
        if eid not in visibles(store, pc):
            return self.json({"ok": False, "error": "entite"}, status_code=403)
        try:
            change = regler_entite(self.hass, store, eid, {"mise_en_page": corps.get("mise_en_page")}, str(pc.get("name") or pc.get("id") or ""))
        except textes.Erreur as e:
            return self.json({"ok": False, "error": "reglage", "message": textes.erreur(e, textes.pour(self.hass, pc))}, status_code=400)
        if change:
            await store.async_save()
        return self.json({"ok": True, "mise_en_page": reglage(store, eid)["mise_en_page"], "version": version(store, pc)})


def enregistrer_vues(hass: HomeAssistant) -> None:
    for vue in (PcParentalWidgetsEntitesView, PcParentalWidgetsEtatsView, PcParentalWidgetsHistoriqueView,
                PcParentalWidgetsActionView, PcParentalWidgetsFluxView, PcParentalWidgetsMiseEnPageView):
        hass.http.register_view(vue(hass))
