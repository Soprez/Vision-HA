"""Les cartes du tableau de bord et le panneau de l'écran de veille, servis par l'intégration.

Rien à copier dans /config/www, aucune ressource à ajouter à la main : les fichiers voyagent
avec l'intégration (dossier frontend/), Home Assistant les sert à une adresse fixe, et chaque
page de l'interface charge les cartes d'elle-même.

L'adresse de chaque fichier porte l'empreinte de son contenu ET de ce qu'il importe : après
une mise à jour, les navigateurs (et les télés, et l'appli compagnon) prennent la nouvelle
version au lieu de garder l'ancienne en cache ; un fichier inchangé garde son adresse.
"""

from __future__ import annotations

import hashlib
import logging
import os
import re
from urllib.parse import urlsplit

from homeassistant.core import HomeAssistant

from .const import DOMAIN

URL = "/pc_parental/frontend"
DOSSIER = os.path.join(os.path.dirname(__file__), "frontend")

# Les cartes que l'interface charge partout. Le panneau de l'écran de veille et son cœur
# ne sont chargés que par le panneau lui-même.
CARTES = (
    "vision-etiquettes-card.js",
    "vision-planning-card.js",
    "vision-processus-card.js",
    "vision-maison-card.js",
    "vision-veille-card.js",
    "vision-dashboards-card.js",
    "vision-widgets-card.js",
    "vision-apparence-card.js",
    "vision-messages-card.js",
)
PANNEAU = "vision-veille-panel.js"

_CLE = f"{DOMAIN}_cartes"
_CLE_SERVI = f"{DOMAIN}_cartes_servi"
_LOGGER = logging.getLogger(__name__)

# 2.2.13 : ce qu'un fichier charge d'un fichier voisin : `import … from "./x.js"`, `import "./x.js"`,
# `import("./x.js")`, `new URL("x.js…", import.meta.url)` (guillemets simples, doubles ou gabarit).
_IMPORTS = re.compile(
    r"""(?:\bfrom\s*|\bimport\s*\(?\s*|\bnew\s+URL\s*\(\s*)[`'"](?:\./)?([\w.-]+\.js)(?=[?`'"#])"""
)


def dependances(texte: str) -> set[str]:
    """Les fichiers voisins qu'un module charge (par leur nom)."""
    return set(_IMPORTS.findall(texte))


def versions(dossier: str = DOSSIER) -> dict[str, str]:
    """La version de chaque fichier .js du dossier : empreinte (sha256 courte) de son contenu et de ses
    dépendances, de proche en proche. Lue sur le disque : hors de la boucle.

    2.2.13 : plus la date de modification (copie, horloges : une mise à jour pouvait garder la même date, et un
    fichier recopié tel quel en changer) ni la version de Vision (elle changeait toutes les adresses à chaque
    version, et n'était pas relue au rechargement de l'intégration). Un module importé sans version restait en
    cache même quand celui qui l'importe changeait : l'empreinte d'un fichier couvre maintenant ses imports."""
    contenus: dict[str, bytes] = {}
    for nom in sorted(os.listdir(dossier)):
        if nom.endswith(".js"):
            with open(os.path.join(dossier, nom), "rb") as f:
                contenus[nom] = f.read()
    deps = {nom: sorted(d for d in dependances(c.decode("utf-8", "replace")) if d in contenus and d != nom)
            for nom, c in contenus.items()}

    def fermeture(nom: str) -> list[str]:
        vus: set[str] = set()
        a_voir = [nom]
        while a_voir:
            n = a_voir.pop()
            if n in vus:
                continue
            vus.add(n)
            a_voir.extend(deps.get(n, ()))
        return sorted(vus - {nom})

    sortie: dict[str, str] = {}
    for nom, contenu in contenus.items():
        h = hashlib.sha256(contenu)
        for d in fermeture(nom):  # dans un ordre fixe : même contenu → même empreinte
            h.update(b"\0" + d.encode() + b"\0" + hashlib.sha256(contenus[d]).digest())
        sortie[nom] = h.hexdigest()[:12]
    return sortie


def adresse_versionnee(base: str, nom: str, version: str) -> str:
    """LA fabrique d'adresse d'un fichier servi par Vision (cartes, panneaux) : toujours avec sa version."""
    return f"{base}/{nom}?v={version}"


def adresse(hass: HomeAssistant, nom: str) -> str:
    """L'adresse d'un fichier du dossier frontend/, avec sa version pour que les caches suivent."""
    v = (hass.data.get(_CLE) or {}).get(nom)
    if not v:  # pas encore lu (ne devrait pas arriver) : lu tout de suite plutôt qu'une adresse sans version
        v = versions().get(nom, "0")
    return adresse_versionnee(URL, nom, v)


async def async_installer(hass: HomeAssistant) -> None:
    """Sert le dossier et fait charger les cartes.

    Le dossier n'est servi qu'une fois par démarrage de Home Assistant ; les versions, elles, sont relues à
    chaque mise en route de l'intégration (2.2.13 : un rechargement sans redémarrage gardait les anciennes
    adresses), et les ressources Lovelace et le panneau de l'écran de veille suivent."""
    hass.data[_CLE] = await hass.async_add_executor_job(versions)
    if not hass.data.get(_CLE_SERVI):
        from homeassistant.components.http import StaticPathConfig

        await hass.http.async_register_static_paths([StaticPathConfig(URL, DOSSIER, False)])
        hass.data[_CLE_SERVI] = True
    else:
        # Rechargement : le panneau déjà déclaré prend sa nouvelle adresse.
        from . import veille_page

        veille_page.panneau(hass)
    # 1.97.3 : plus de chargement par la page elle-même (add_extra_js_url). Cette page est gardée par le
    # navigateur (service worker de Home Assistant) : après une mise à jour, la copie gardée chargeait l'ANCIENNE
    # carte en premier, qui prenait la place de la nouvelle (vu en vrai : cartes 1.97.1 sous Vision 1.97.3).
    # Les ressources Lovelace, elles, sont relues à chaque ouverture du tableau : une seule version, la bonne.
    # 1.86.2 : en ressources Lovelace (attendues par le tableau avant d'afficher ses cartes), à jour.
    from homeassistant.helpers.start import async_at_started

    async def _ressources(_hass: HomeAssistant) -> None:
        try:
            await async_ressources(hass)
        except Exception:  # noqa: BLE001 - une ressource ratée ne doit rien casser
            _LOGGER.exception("Cartes de Vision : ressources Lovelace non mises à jour")

    # 2.2.13 : tout de suite aussi quand Lovelace est déjà là. Pendant un démarrage, l'interface est servie avant
    # « Home Assistant démarré » : une appli compagnon qui rechargeait la page dans ce creux lisait les anciennes
    # ressources (ou pas encore celle d'une nouvelle carte : « élément inconnu » jusqu'au rechargement suivant).
    if not hass.is_running and hass.data.get("lovelace") is not None:
        await _ressources(hass)
    # Au démarrage : quand Home Assistant a fini ; au rechargement (déjà démarré) : tout de suite.
    async_at_started(hass, _ressources)


def _notre_fichier(url: str) -> str | None:
    """Le nom du fichier si l'adresse est l'une des nôtres (/pc_parental/frontend/x.js, même écrite en entier)."""
    chemin = urlsplit(str(url or "")).path
    if not chemin.startswith(URL + "/"):
        return None
    nom = chemin[len(URL) + 1:]
    return nom if nom and "/" not in nom else None


async def async_ressources(hass: HomeAssistant) -> int:
    """Une ressource Lovelace par carte de Vision, à l'adresse versionnée. Rend le nombre de changements.

    2.2.13 : une ressource en double (posée deux fois, ou à la main en plus de Vision) est ramenée à une ; une
    ressource vers un fichier de Vision qui n'existe plus est retirée ; un autre fichier de Vision posé à la main
    (le cœur de la veille, par exemple) garde sa ressource, à sa version. Rien hors de /pc_parental/frontend."""
    lovelace = hass.data.get("lovelace")
    ressources = getattr(lovelace, "resources", None) if lovelace is not None else None
    if ressources is None:
        return 0
    versions_ = hass.data.get(_CLE) or {}
    if not versions_:
        return 0  # versions pas encore lues : surtout ne rien retirer
    if not hasattr(ressources, "async_create_item"):
        # Ressources en YAML : on ne peut rien y écrire. On dit quoi mettre, sans rien casser.
        try:
            for r in ressources.async_items() or []:
                nom = _notre_fichier(r.get("url", ""))
                if nom in versions_ and r.get("url") != adresse(hass, nom):
                    _LOGGER.warning("Cartes de Vision : ressource en YAML à remplacer par %s (ancienne : %s)",
                                    adresse(hass, nom), r.get("url"))
        except Exception:  # noqa: BLE001
            pass
        return 0
    if not getattr(ressources, "loaded", True):
        await ressources.async_load()
        ressources.loaded = True
    existantes = list(ressources.async_items())
    changements = 0
    par_nom: dict[str, list[dict]] = {}
    for r in existantes:
        nom = _notre_fichier(r.get("url", ""))
        if nom is not None:
            par_nom.setdefault(nom, []).append(r)
    for nom in sorted(set(CARTES) | set(par_nom)):
        siennes = par_nom.get(nom, [])
        if nom not in versions_:
            # Un fichier de Vision qui n'existe plus : sa ressource ne chargerait rien (ou une erreur).
            for r in siennes:
                await ressources.async_delete_item(r["id"])
                changements += 1
            continue
        voulue = adresse(hass, nom)
        if not siennes:
            await ressources.async_create_item({"res_type": "module", "url": voulue})
            changements += 1
            continue
        # La première déjà à la bonne adresse est gardée (sinon la première tout court) ; les autres partent.
        garde = next((r for r in siennes if r.get("url") == voulue), siennes[0])
        for r in siennes:
            if r is not garde:
                await ressources.async_delete_item(r["id"])
                changements += 1
        if garde.get("url") != voulue or garde.get("res_type", "module") != "module":
            await ressources.async_update_item(garde["id"], {"res_type": "module", "url": voulue})
            changements += 1
    if changements:
        _LOGGER.info("Cartes de Vision : %d ressource(s) Lovelace mise(s) à jour.", changements)
    return changements
