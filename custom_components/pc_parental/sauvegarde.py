"""Sauvegarde et restauration de toute la configuration Vision (1.87.0, point 14.3, dans le socle).

Ce qui est gardé : le magasin principal de l'intégration (rôles, personnes, appareils et leurs règles,
modules, lieu, widgets, écran de veille, étiquettes…), tel qu'il est enregistré dans `.storage`.
Les caches (catalogue des logiciels, liste noire, notoriété, VirusTotal) et l'état du moment (demandes
de déverrouillage, tickets de veille) ne le sont pas : ils se refont seuls.

Les fichiers restent dans Home Assistant, dans `/config/vision_sauvegardes/` : ils portent les secrets
des appareils, comme `.storage`, et ne sont jamais servis par le réseau. Ils entrent donc aussi dans
les sauvegardes de Home Assistant.

- Manuelle : Configurer → Avancé → Sauvegarde. Les 20 plus récentes sont gardées.
- Automatique : chaque nuit à 03h30, les 7 dernières sont gardées.
- Avant chaque restauration : l'état du moment, pour pouvoir revenir en arrière.
"""

from __future__ import annotations

import json
import logging
import os
import re
from datetime import datetime
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from . import lieu, textes
from .const import DOMAIN, STORAGE_KEY, STORAGE_VERSION, VERSION

_LOGGER = logging.getLogger(__name__)

DOSSIER = "vision_sauvegardes"
FORMAT = "vision-sauvegarde"
FORMAT_VERSION = 1
# Motif → son nom en français (journal) ; à l'écran, `textes.t("sauv.motif_<motif>", langue)`.
MOTIFS = {m: textes.t(f"sauv.motif_{m}", "fr") for m in ("manuelle", "automatique", "avant")}
GARDER = {"manuelle": 20, "automatique": 7, "avant": 10}
_NOM = re.compile(r"^vision-(\d{8}-\d{6})-(manuelle|automatique|avant)\.json$")


def dossier(hass: HomeAssistant) -> str:
    return hass.config.path(DOSSIER)


def resume(donnees: dict[str, Any]) -> dict[str, Any]:
    """Ce qu'une sauvegarde contient, en chiffres : pour la reconnaître avant de la restaurer."""
    pcs = dict(donnees.get("pcs") or {})
    personnes = {str(pc.get("person")) for pc in pcs.values() if pc.get("person")}
    personnes |= set(dict(donnees.get("roles_personnes") or {}))
    return {
        "lieu": str(donnees.get("lieu") or lieu.defaut()),
        "appareils": len(pcs),
        "personnes": len(personnes),
        "roles": len(dict(donnees.get("roles") or {})),
    }


def libelle(entree: dict[str, Any], langue: str | None = None) -> str:
    """« 9 octobre 2026 à 10h40 · manuelle · 6 appareils, 4 personnes » (dans `langue`, sinon celle de HA)."""
    lg = langue or textes.ha()
    r = entree.get("resume") or {}
    try:
        quand = dt_util.as_local(datetime.fromisoformat(str(entree.get("date"))))
        date = textes.t("sauv.date", lg, jour=quand.day, mois=textes.t(f"format.mois_{quand.month}", lg), annee=quand.year,
                        heure=textes.heure(quand, lg))
    except (TypeError, ValueError):
        date = str(entree.get("nom"))
    n_a, n_p = int(r.get("appareils") or 0), int(r.get("personnes") or 0)
    motif = str(entree.get("motif"))
    return (f"{date} · {textes.t(f'sauv.motif_{motif}', lg) if motif in MOTIFS else entree.get('motif')} · "
            f"{textes.n('sauv.appareils', n_a, lg)}, {textes.n('sauv.personnes', n_p, lg)}")


def _ecrire(chemin: str, contenu: dict[str, Any]) -> None:
    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    provisoire = chemin + ".tmp"
    with open(provisoire, "w", encoding="utf-8") as f:
        json.dump(contenu, f, ensure_ascii=False, indent=1)
    try:
        os.chmod(provisoire, 0o600)
    except OSError:
        pass
    os.replace(provisoire, chemin)


def _lister(rep: str) -> list[dict[str, Any]]:
    sortie: list[dict[str, Any]] = []
    try:
        noms = os.listdir(rep)
    except FileNotFoundError:
        return []
    for nom in noms:
        m = _NOM.match(nom)
        if not m:
            continue
        try:
            with open(os.path.join(rep, nom), encoding="utf-8") as f:
                tete = json.load(f)
            if tete.get("format") != FORMAT:
                continue
            sortie.append({"nom": nom, "date": tete.get("date"), "motif": m.group(2),
                           "vision": tete.get("vision"), "resume": tete.get("resume") or {}})
        except (OSError, ValueError):
            continue
    return sorted(sortie, key=lambda e: e["nom"][7:22], reverse=True)


def _elaguer(rep: str) -> None:
    for motif, garder in GARDER.items():
        for e in [e for e in _lister(rep) if e["motif"] == motif][garder:]:
            try:
                os.remove(os.path.join(rep, e["nom"]))
            except OSError:
                pass


async def async_lister(hass: HomeAssistant) -> list[dict[str, Any]]:
    return await hass.async_add_executor_job(_lister, dossier(hass))


async def async_sauver(hass: HomeAssistant, coord, motif: str = "manuelle") -> dict[str, Any]:
    """Écrit la configuration du moment dans un fichier ; rend son entrée (nom, date, résumé)."""
    if motif not in MOTIFS:
        raise ValueError(motif)
    # D'abord ce qui est en mémoire, sur le disque : la sauvegarde part du fichier enregistré.
    await coord.store.async_save()
    from homeassistant.helpers.storage import Store

    donnees = dict(await Store(hass, STORAGE_VERSION, STORAGE_KEY).async_load() or {})
    maintenant = dt_util.now()
    nom = f"vision-{maintenant:%Y%m%d-%H%M%S}-{motif}.json"
    contenu = {
        "format": FORMAT, "format_version": FORMAT_VERSION, "vision": VERSION,
        "date": maintenant.isoformat(), "motif": motif, "cle": STORAGE_KEY,
        "storage_version": STORAGE_VERSION, "resume": resume(donnees), "donnees": donnees,
    }
    rep = dossier(hass)

    def _faire() -> None:
        _ecrire(os.path.join(rep, nom), contenu)
        _elaguer(rep)

    await hass.async_add_executor_job(_faire)
    _LOGGER.info("Vision : sauvegarde %s de la configuration (%s).", MOTIFS[motif], nom)
    return {"nom": nom, "date": contenu["date"], "motif": motif, "vision": VERSION, "resume": contenu["resume"]}


def _lire_sauvegarde(rep: str, nom: str) -> dict[str, Any]:
    if not _NOM.match(nom or ""):
        raise ValueError("nom")
    with open(os.path.join(rep, nom), encoding="utf-8") as f:
        contenu = json.load(f)
    if contenu.get("format") != FORMAT or int(contenu.get("format_version") or 0) > FORMAT_VERSION:
        raise ValueError("format")
    donnees = contenu.get("donnees")
    if not isinstance(donnees, dict) or not isinstance(donnees.get("pcs"), dict):
        raise ValueError("contenu")
    return donnees


async def async_restaurer(hass: HomeAssistant, entry_id: str, nom: str) -> dict[str, Any]:
    """Remplace la configuration par celle de `nom`, après avoir sauvegardé l'état du moment.

    L'intégration est déchargée, le magasin réécrit, puis elle repart : tout est relu d'un coup.
    Rend l'entrée de la sauvegarde « avant restauration ».
    """
    rep = dossier(hass)
    donnees = await hass.async_add_executor_job(_lire_sauvegarde, rep, nom)
    coord = hass.data.get(DOMAIN, {}).get(entry_id)
    if coord is None:
        raise RuntimeError("non_chargee")
    avant = await async_sauver(hass, coord, "avant")
    await hass.config_entries.async_unload(entry_id)
    from homeassistant.helpers.storage import Store

    await Store(hass, STORAGE_VERSION, STORAGE_KEY).async_save(donnees)
    await hass.config_entries.async_setup(entry_id)
    _LOGGER.warning("Vision : configuration restaurée depuis %s (l'état d'avant est dans %s).", nom, avant["nom"])
    return avant
