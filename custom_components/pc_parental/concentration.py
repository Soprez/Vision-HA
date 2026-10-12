"""Mode « Concentration » (1.88.0, point 14.5, module « Concentration », dépend de « Blocage d'applis »).

Pendant une durée choisie (les devoirs), seules les applis choisies restent ouvertes sur un appareil :
Home Assistant ajoute à ce qu'il ferme toutes les autres applis qu'il connaît sur cet appareil.
Rien de nouveau à installer : l'appli Android et l'agent PC ferment déjà ce qu'on leur dit de fermer.

- Android : Home Assistant connaît toutes les applis installées (inventaire) ; tout le reste est fermé.
- PC : il connaît les logiciels déjà vus au premier plan ; un logiciel jamais lancé n'est pas encore connu.
- Restent toujours ouverts : Vision, le téléphone, les contacts, les messages, l'horloge, la calculatrice,
  le clavier et les réglages (protégés par Vision), et les applis « Toujours autorisé ».

Stockage : `pc["concentration"] = {"fin": horodatage, "apps": [...], "par": "Prénom"}` ; le dernier choix
d'applis est retenu par personne (ou par appareil sans personne) dans `store.concentration_choix`.
"""

from __future__ import annotations

import time
from typing import Any

from . import textes
from .const import PAQUETS_VISION

MINUTES_MAX = 360
CODE = "concentration"

# Ce qui ne se ferme jamais : de quoi appeler, prévenir, lire l'heure, taper, et Vision elle-même.
ESSENTIELS = frozenset({
    *PAQUETS_VISION,
    "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer",
    "com.google.android.contacts", "com.android.contacts", "com.samsung.android.app.contacts",
    "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
    "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
    "com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator",
    "com.android.settings", "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard",
    "com.android.systemui", "com.google.android.permissioncontroller",
    "explorer", "taskmgr", "systemsettings",
})


def active(pc: dict[str, Any], maintenant: float | None = None) -> dict[str, Any] | None:
    c = pc.get("concentration")
    if not isinstance(c, dict):
        return None
    if float(c.get("fin") or 0) <= (maintenant if maintenant is not None else time.time()):
        return None
    return c


def heure(fin: float, langue: str | None = None) -> str:
    """L'heure de fin, lisible (« 18h30 ») ; sans langue : celle de Home Assistant."""
    return textes.heure(float(fin), langue)


def motif(c: dict[str, Any], langue: str | None = None) -> str:
    """Le motif « code|texte » de l'écran de blocage, dans la langue de l'appareil."""
    return "{}|{}".format(CODE, textes.t("conc.motif", langue, heure=heure(float(c["fin"]), langue)))


def a_fermer(pc: dict[str, Any], c: dict[str, Any], nom_app) -> list[str]:
    """Les applis connues de cet appareil à fermer pendant la concentration (noms du catalogue)."""
    gardees = {nom_app(a) for a in (c.get("apps") or [])} | {nom_app(e) for e in ESSENTIELS}
    return sorted(n for n in (pc.get("apps_vues") or {}) if n and nom_app(n) not in gardees)


def cle_choix(pc: dict[str, Any]) -> str:
    return str(pc.get("person") or "") or "pc:" + str(pc.get("id") or "")


def proposees(pc: dict[str, Any], limite: int = 80) -> list[dict[str, str]]:
    """Les applis de l'appareil à cocher, les plus utilisées d'abord, avec leur nom lisible."""
    vues = pc.get("apps_vues") or {}
    rangees = sorted(vues.items(), key=lambda kv: (-int((kv[1] or {}).get("secondes") or 0), str((kv[1] or {}).get("libelle") or kv[0]).lower()))
    sortie = []
    for nom, fiche in rangees:
        if nom in ESSENTIELS:
            continue
        sortie.append({"nom": nom, "libelle": str((fiche or {}).get("libelle") or nom)})
        if len(sortie) >= limite:
            break
    return sortie


def demarrer(store, pc: dict[str, Any], minutes: int, apps: list[str] | None, qui: str) -> dict[str, Any]:
    """Lance (ou remplace) la concentration ; sans liste, reprend le dernier choix retenu."""
    minutes = int(minutes)
    if not 1 <= minutes <= MINUTES_MAX:
        raise ValueError("minutes")
    choix_retenus = getattr(store, "concentration_choix", None)
    if choix_retenus is None:
        store.concentration_choix = choix_retenus = {}
    if apps is None:
        apps = list(choix_retenus.get(cle_choix(pc)) or [])
    apps = [str(a).strip() for a in apps if str(a).strip()][:100]
    choix_retenus[cle_choix(pc)] = apps
    c = {"fin": time.time() + minutes * 60, "apps": apps, "par": qui}
    pc["concentration"] = c
    return c


def arreter(pc: dict[str, Any]) -> bool:
    return pc.pop("concentration", None) is not None


def fiche(pc: dict[str, Any], store) -> dict[str, Any]:
    """Ce que l'espace gestion montre : en cours ou non, jusqu'à quand, et les applis à proposer."""
    c = active(pc)
    retenues = list((getattr(store, "concentration_choix", None) or {}).get(cle_choix(pc)) or [])
    return {
        "fin": int(c["fin"]) if c else 0,
        "apps": list(c.get("apps") or []) if c else retenues,
        "proposees": proposees(pc),
    }
