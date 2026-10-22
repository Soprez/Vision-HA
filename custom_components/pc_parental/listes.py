"""Les grandes listes (sites, logiciels, ce qui est bloqué sur un appareil), lues à la demande.

2.5.12 (diagnostic du 17/10) : ces listes voyageaient dans les attributs de `select.vision_site_*`,
`select.vision_logiciel_*` et `sensor.<appareil>_etat`. Elles pesaient 35 à 50 Ko par entité, deux fois chacune
(`lignes` et `lignes_bloques` portaient la même liste) et elles étaient recalculées à chaque mise à jour du
coordinateur : toutes les 15 s et à chaque relevé d'un appareil. Le capteur d'état d'un PC y passait 0,5 à 0,7 s,
dans la boucle d'événements, et chaque navigateur ouvert recevait le tout à chaque changement.

Maintenant :
- les entités ne portent que des nombres et une **empreinte** (`revision`), qui ne change que si la liste change ;
- les cartes lisent la liste ici, quand on l'ouvre, puis seulement quand l'empreinte change ;
- une seule liste par demande : plus de doublons.

`GET /api/pc_parental/listes?liste=<nom>[&pc=<id>][&revision=<empreinte>]`, réservé aux administrateurs
(ces listes disent tout ce que le lieu surveille, comme le catalogue).
"""

from __future__ import annotations

import hashlib
import json
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_platform
from homeassistant.util import dt as dt_util

from .const import DOMAIN

URL = "/api/pc_parental/listes"

# Nom de la liste → identifiant unique du sélecteur qui la porte.
SELECTEURS = {
    "sites_a_classer": f"{DOMAIN}_site_constate",
    "sites_autorises": f"{DOMAIN}_site_autorise",
    "sites_bloques": f"{DOMAIN}_site_bloque",
    "logiciels_a_classer": f"{DOMAIN}_logiciel_constate",
    "logiciels_autorises": f"{DOMAIN}_logiciel_autorise",
    "logiciels_bloques": f"{DOMAIN}_logiciel_bloque",
}
APPAREIL = "bloques_appareil"
# Une borne, pour qu'une réponse ne puisse pas enfler sans limite.
LIGNES_MAX = 5000


def empreinte(*valeurs: Any) -> str:
    """Courte et stable : même contenu, même empreinte, d'un redémarrage à l'autre."""
    brut = json.dumps(valeurs, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha1(brut.encode("utf-8")).hexdigest()[:12]


def avec_revision(donnees: dict[str, Any]) -> tuple[dict[str, Any], str]:
    """Les données et leur empreinte, calculées ensemble (pour les garder en cache : l'empreinte coûte un JSON complet)."""
    return donnees, empreinte(donnees)


def repondre(vue: HomeAssistantView, request: web.Request, donnees: dict[str, Any],
             revision: str | None = None) -> web.Response:
    """La réponse d'une API relue en boucle par une carte (toutes les 30 s), avec son empreinte.

    Si la carte envoie l'empreinte qu'elle a déjà (`?revision=…`) et que rien n'a changé, on ne renvoie que
    `{"ok": true, "inchange": true}` : ni les centaines de Ko, ni le redessin de la carte."""
    revision = revision or empreinte(donnees)
    if request.query.get("revision") == revision:
        return vue.json({"ok": True, "inchange": True, "revision": revision})
    return vue.json({**donnees, "revision": revision})


def revision_appareil(store: Any, pc: dict[str, Any]) -> str:
    """L'empreinte de ce qui est bloqué sur cet appareil. Le calcul de fond (`effectif`) est déjà gardé 5 s."""
    applique = store.effectif(pc)
    return empreinte(
        applique.get("apps"), applique.get("sites"), applique.get("exceptions"),
        store.exceptions(pc), store.laissez_passer_actifs(pc),
    )


def lignes_appareil(hass: HomeAssistant, store: Any, pc: dict[str, Any]) -> tuple[str, list[str]]:
    """Ce qui est bloqué **sur cette machine seulement**, en lignes de tableau, et l'en-tête.

    Ce qui vaut pour tout le lieu (ses décisions, la liste noire) n'y est pas : c'est identique sur toutes les
    cartes, et cela noyait la seule information qu'on vient y chercher.
    """
    from .entity import texte

    lignes = []
    for entree in store.bloques(pc):
        # La raison vient du magasin (lot A2), en français : c'est elle qui dit « sur cet appareil ».
        if "sur cet appareil" not in entree["raison"]:  # i18n-ok : marqueur du magasin, pas un texte affiché
            continue
        lignes.append(
            f"| 🚫 | {entree['genre']} | **{entree['nom']}** "
            f"| {entree['raison'].replace(', sur cet appareil', '')} |"  # i18n-ok : raison du magasin (lot A2)
        )
    # Les portes ouvertes se lisent au même endroit que les murs : une exception qu'on ne voit pas est une
    # exception qu'on oublie.
    for adresse in store.exceptions(pc):
        lignes.append(texte(hass, "ligne_adresse_epargnee", adresse=adresse))
    for domaine, fin in store.laissez_passer_actifs(pc):
        heure = dt_util.as_local(dt_util.utc_from_timestamp(fin)).strftime("%H:%M")
        lignes.append(texte(hass, "ligne_laissez_passer", domaine=domaine, heure=heure))
    return texte(hass, "entete_bloques_appareil"), lignes


def _entite(hass: HomeAssistant, unique_id: str) -> Any | None:
    for plateforme in entity_platform.async_get_platforms(hass, DOMAIN):
        for entite in plateforme.entities.values():
            if entite.unique_id == unique_id:
                return entite
    return None


class ListesView(HomeAssistantView):
    url = URL
    name = "api:pc_parental:listes"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.Response:
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        nom = request.query.get("liste", "")
        connue = request.query.get("revision", "")
        if nom == APPAREIL:
            pc = coord.store.get(request.query.get("pc", ""))
            if not pc:
                return self.json({"ok": False, "error": "pc"}, status_code=404)
            revision = revision_appareil(coord.store, pc)
            if connue and connue == revision:
                return self.json({"ok": True, "inchange": True, "revision": revision})
            entete, lignes = lignes_appareil(self.hass, coord.store, pc)
        elif nom in SELECTEURS:
            selecteur = _entite(self.hass, SELECTEURS[nom])
            if selecteur is None:
                return self.json({"ok": False, "error": "liste"}, status_code=404)
            revision = selecteur.revision()
            if connue and connue == revision:
                return self.json({"ok": True, "inchange": True, "revision": revision})
            entete, lignes = selecteur.entete(), selecteur.lignes_completes()
        else:
            return self.json({"ok": False, "error": "liste"}, status_code=404)
        return self.json({
            "ok": True, "liste": nom, "revision": revision, "entete": entete,
            "lignes": lignes[:LIGNES_MAX], "total": len(lignes), "tronque": len(lignes) > LIGNES_MAX,
        })


def enregistrer(hass: HomeAssistant) -> None:
    hass.http.register_view(ListesView(hass))
