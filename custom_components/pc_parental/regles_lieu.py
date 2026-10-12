"""Règles selon le lieu (1.91.0, point 14.8, module « Règles selon le lieu », dépend de « Blocage » ou « Filtrage »).

Tant qu'une personne est dans une zone de Home Assistant (le collège, chez les grands-parents…), des
catégories choisies sont fermées sur ses appareils : « Jeux » au collège, par exemple. Elle quitte la zone :
tout redevient comme avant, sans rien toucher d'autre.

- La position est celle de l'entité `person.*` de Home Assistant (son état vaut le nom de la zone, ou
  « home » pour la zone Maison). Personne sans position connue : la règle ne s'applique pas.
- Seules les catégories (étiquettes) sont fermées : applis (module « Blocage ») et sites (« Filtrage »).

Stockage : `store.regles_lieu = [{"personne", "zone", "etiquettes": [...]}]`.
"""

from __future__ import annotations

from typing import Any

MAX_REGLES = 40


def regles(store) -> list[dict[str, Any]]:
    r = getattr(store, "regles_lieu", None)
    if not isinstance(r, list):
        store.regles_lieu = r = []
    return r


def poser(store, personne: str, zone: str, etiquettes: list[str]) -> None:
    """Ajoute ou remplace la règle (personne, zone) ; sans étiquette, la retire."""
    personne, zone = str(personne or "").strip(), str(zone or "").strip()
    if not personne.startswith("person."):
        raise ValueError("personne")
    if not zone.startswith("zone."):
        raise ValueError("zone")
    etiquettes = [str(e).strip() for e in (etiquettes or []) if str(e).strip()][:20]
    liste = [r for r in regles(store) if not (r["personne"] == personne and r["zone"] == zone)]
    if etiquettes:
        if len(liste) >= MAX_REGLES:
            raise ValueError("trop")
        liste.append({"personne": personne, "zone": zone, "etiquettes": etiquettes})
    store.regles_lieu = liste


def dans_la_zone(hass, personne: str, zone: str) -> bool:
    """La personne est-elle dans cette zone, d'après Home Assistant ?"""
    if hass is None:
        return False
    p = hass.states.get(personne)
    if p is None or p.state in ("unknown", "unavailable", "not_home", ""):
        return False
    if zone == "zone.home":
        return p.state == "home"
    z = hass.states.get(zone)
    if z is None:
        return False
    return p.state in {z.name, z.attributes.get("friendly_name"), zone.split(".", 1)[1]}


def actives(hass, store, pc: dict[str, Any]) -> list[dict[str, Any]]:
    """Les règles qui valent maintenant pour cet appareil (celles de sa personne, qui est dans la zone)."""
    personne = str(pc.get("person") or "")
    if not personne:
        return []
    return [r for r in regles(store) if r["personne"] == personne and dans_la_zone(hass, personne, r["zone"])]


def nom_zone(hass, zone: str) -> str:
    z = hass.states.get(zone) if hass is not None else None
    return z.name if z is not None else zone.split(".", 1)[-1]


def motif(hass, r: dict[str, Any], langue: str | None = None) -> str:
    """Le motif « lieu|texte » de l'écran de blocage, dans la langue de l'appareil (sinon celle de HA)."""
    from . import textes

    return "lieu|" + textes.t("lieu.motif", langue or textes.ha(hass), zone=nom_zone(hass, r["zone"]))
