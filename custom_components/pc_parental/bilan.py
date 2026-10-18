"""Bilan hebdomadaire (1.90.0, point 14.7, module « Bilan », dépend de « Temps d'écran »).

Chaque dimanche à 19h00, les décideurs reçoivent le bilan des sept derniers jours, personne par personne :
temps d'écran (total, moyenne par jour, écart avec la semaine d'avant), applis les plus utilisées, demandes
reçues, accès accordés et refusés, temps ajouté.

- Sur leurs appareils : un message Vision par personne (un message tient en 500 caractères).
- Dans Home Assistant : une notification avec le bilan complet.
- Tout de suite : l'action `pc_parental.bilan_hebdo`.

Les décisions se comptent au fil de la semaine (`store.bilan_compteurs`) ; le temps d'écran vient des
relevés quotidiens des appareils (`pc["usage"]`, quatorze jours gardés).
"""

from __future__ import annotations

import logging
from datetime import timedelta
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util

from . import textes

_LOGGER = logging.getLogger(__name__)

JOUR_ENVOI = 6  # dimanche
HEURE_ENVOI = 19
TOP_APPS = 3


def _compteurs(store) -> dict[str, dict[str, int]]:
    c = getattr(store, "bilan_compteurs", None)
    if not isinstance(c, dict):
        store.bilan_compteurs = c = {}
    return c


def compter(store, personne: str, quoi: str, n: int = 1) -> None:
    """« demande », « accordee », « refusee » ou « minutes » (temps ajouté), pour la semaine en cours."""
    if not personne:
        return
    p = _compteurs(store).setdefault(personne, {})
    p[quoi] = int(p.get(quoi) or 0) + int(n)


def remettre_a_zero(store) -> None:
    store.bilan_compteurs = {}


def duree(secondes: int, langue: str | None = None) -> str:
    """« 45 min », « 2 h 05 » (en français ; chaque langue suit son usage)."""
    m = int(secondes) // 60
    if m < 60:
        return textes.t("format.duree_min", langue, m=m)
    return textes.t("format.duree_h", langue, h=m // 60, m="{:02d}".format(m % 60))


def _jours(fin, n: int) -> list[str]:
    return [(fin - timedelta(days=k)).strftime("%Y-%m-%d") for k in range(n)]


def resume_personne(store, personne: str, nom: str, maintenant=None) -> dict[str, Any]:
    """Les chiffres d'une personne sur ses propres appareils (les écrans partagés ne comptent pas)."""
    maintenant = maintenant or dt_util.now()
    semaine = _jours(maintenant, 7)
    avant = _jours(maintenant - timedelta(days=7), 7)
    appareils = [pc for pc in store.pcs.values() if str(pc.get("person") or "") == personne]
    total = precedent = 0
    apps: dict[str, int] = {}
    libelles: dict[str, str] = {}
    for pc in appareils:
        usage = pc.get("usage") or {}
        for j in semaine:
            jour = usage.get(j) or {}
            total += int(jour.get("actif") or 0)
            for a, s in (jour.get("apps") or {}).items():
                apps[a] = apps.get(a, 0) + int(s or 0)
        for j in avant:
            precedent += int((usage.get(j) or {}).get("actif") or 0)
        for a, fiche in (pc.get("apps_vues") or {}).items():
            if isinstance(fiche, dict) and fiche.get("libelle"):
                libelles[a] = str(fiche["libelle"])
    top = sorted(apps.items(), key=lambda kv: -kv[1])[:TOP_APPS]
    c = _compteurs(store).get(personne) or {}
    return {
        "personne": personne, "nom": nom, "appareils": len(appareils), "total": total, "precedent": precedent,
        "top": [(libelles.get(a, a), s) for a, s in top if s >= 60],
        "demandes": int(c.get("demande") or 0), "accordees": int(c.get("accordee") or 0),
        "refusees": int(c.get("refusee") or 0), "minutes": int(c.get("minutes") or 0),
    }


def texte_personne(r: dict[str, Any], langue: str | None = None) -> str:
    """Le paragraphe d'une personne : complet, neutre, sous 500 caractères (dans `langue`, sinon celle de HA)."""
    lg = langue or textes.ha()
    morceaux = [textes.t("bilan.ligne", lg, nom=r['nom'], total=duree(r['total'], lg), moyenne=duree(r['total'] // 7, lg))]
    if r["precedent"] > 0:
        ecart = r["total"] - r["precedent"]
        if abs(ecart) >= 600:
            morceaux[0] += textes.t("bilan.plus" if ecart > 0 else "bilan.moins", lg, duree=duree(abs(ecart), lg))
    morceaux[0] += "."
    if r["top"]:
        morceaux.append(textes.t("bilan.top", lg, liste=", ".join(f"{n} ({duree(s, lg)})" for n, s in r["top"])))
    if r["demandes"] or r["accordees"] or r["refusees"]:
        morceaux.append(textes.t("bilan.demandes", lg, recues=textes.n("bilan.recues", r["demandes"], lg),
                                 accordees=textes.n("bilan.accordees", r["accordees"], lg),
                                 refusees=textes.n("bilan.refusees", r["refusees"], lg)))
    if r["minutes"]:
        morceaux.append(textes.t("bilan.temps_ajoute", lg, duree=duree(r['minutes'] * 60, lg)))
    return " ".join(morceaux)[:500]


def preparer(hass: HomeAssistant, store) -> list[dict[str, Any]]:
    """Les personnes suivies : celles qui ont un appareil à elles, hors décideurs."""
    from . import roles

    decideurs = {str(pc.get("person") or "") for pc in store.pcs.values() if pc.get("id") in set(roles.avec(store, "gerer"))}
    personnes = sorted({str(pc.get("person") or "") for pc in store.pcs.values() if pc.get("person")} - decideurs - {""})
    sortie = []
    for p in personnes:
        etat = hass.states.get(p)
        sortie.append(resume_personne(store, p, etat.name if etat else p.split(".", 1)[-1].capitalize()))
    return sortie


async def async_envoyer(hass: HomeAssistant, coord, remise_a_zero: bool = True) -> list[dict[str, Any]]:
    """Envoie le bilan aux décideurs (messages Vision) et dans Home Assistant (notification)."""
    from homeassistant.components import persistent_notification

    from . import modules, roles

    store = coord.store
    if modules.coupe(store, "bilan") or modules.coupe(store, "temps"):
        return []
    resumes = preparer(hass, store)
    if not resumes:
        return []
    langue_ha = textes.ha(hass)
    persistent_notification.async_create(
        hass, "\n\n".join(texte_personne(r, langue_ha) for r in resumes), title=textes.t("bilan.titre_ha", langue_ha),
        notification_id="pc_parental_bilan_hebdo")
    # 2.5.6 (l'auteur, 15/10) : le résumé par les canaux que chaque Décideur a choisis (canaux.py).
    from . import canaux

    tout_ha = "\n\n".join(texte_personne(r, langue_ha) for r in resumes)
    await canaux.compagnon_simple(hass, store, "resume", textes.t("bilan.titre_ha", langue_ha), tout_ha, tag="vision_bilan")
    await canaux.discord(hass, store, "resume", textes.t("bilan.titre_ha", langue_ha), tout_ha)
    if not modules.coupe(store, "messages"):
        for pc_id in roles.avec(store, "gerer"):
            if not canaux.vision_pour(hass, store, pc_id, "resume"):
                continue
            # Chaque décideur reçoit le bilan dans la langue de son appareil.
            langue = textes.pour(hass, store.pcs.get(pc_id))
            for r in resumes:
                try:
                    store.add_message(pc_id, texte_personne(r, langue), titre=textes.t("bilan.titre", langue))
                except ValueError:
                    pass
    if remise_a_zero:
        remettre_a_zero(store)
    await coord.async_ecrire(structurel=False)
    _LOGGER.info("Vision : bilan de la semaine envoyé (%d personne(s)).", len(resumes))
    return resumes
