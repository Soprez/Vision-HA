"""2.4.8 (retour d'essai) : la musique de la veille — une playlist de Music Assistant pendant l'écran de veille.

Règles de l'auteur (2026-10-12) :
- option par appareil, désactivée par défaut (le choix est rangé sur l'appareil, rien ici) ;
- la musique lancée par la veille s'arrête TOUJOURS en sortie de veille ;
- si le lecteur de l'appareil joue déjà, sa musique continue et la veille ne lance rien ;
- aucun ajout automatique de titres (autoplay coupé), volume du lecteur « · Vision » à 100 %.

Le lecteur est celui de l'appareil lui-même, « <appareil> · Vision », jamais une autre enceinte. La playlist tourne en
boucle (une longue veille ne retombe pas dans le silence) ; la répétition d'avant est remise à la fin.

Sûreté : l'appli redit « maintenir » toutes les 2 min pendant la veille ; sans nouvelles depuis [EXPIRE_S] (appli
fermée, plantée, réseau tombé), HA arrête lui-même la musique de veille. L'arrêt automatique après inactivité
(musique_arret) ne touche pas une musique de veille en cours : personne n'interagit pendant une veille.

Point d'accès (POST JSON `{id, secret, action, ...}`) : `/api/pc_parental/veille/musique`
- `playlists` : les playlists de Music Assistant, favorites d'abord (nom, uri, image, favori, auto) ;
- `recherche` (`texte`) : les playlists de tous les fournisseurs connectés ;
- `favori` (`uri`, `favori`) : le cœur, ajoute ou retire le favori dans Music Assistant (Décideur ou télé) ;
- `debut` (`uri`) : joue la playlist sur le lecteur de l'appareil, sauf s'il joue déjà ;
- `maintenir` : la veille est toujours là ;
- `fin` : la veille est finie, la musique qu'elle a lancée s'arrête (stop, file vidée) ;
- `suite` (2.5.9) : le lecteur Classique de l'appareil a fini un titre sans recevoir le suivant ; HA demande le titre
  suivant à Music Assistant, sur le lecteur de l'appareil seulement (veille ou non).
"""
from __future__ import annotations

import logging
import time
from typing import Any

from aiohttp import web
from homeassistant.core import HomeAssistant

from . import musique, textes

_LOGGER = logging.getLogger(__name__)

URL = "/api/pc_parental/veille/musique"
EXPIRE_S = 360
JOUE = ("playing", "buffering")

# id de l'appareil → {"lecteur", "uri", "depuis", "vu", "repeter"} : les musiques lancées par une veille.
_EN_COURS: dict[str, dict[str, Any]] = {}


def lecteur_propre(hass: HomeAssistant, pc: dict[str, Any]) -> str:
    """Le lecteur Music Assistant de l'appareil lui-même (« <appareil> · Vision »), vide s'il n'y en a pas."""
    nom = str(pc.get("name") or "").strip().lower()
    if not nom:
        return ""
    propre = nom + musique.SUFFIXE_VISION.lower()
    for eid in sorted(musique.lecteurs_ma(hass)):
        s = hass.states.get(eid)
        if s is not None and str(s.attributes.get("friendly_name") or "").strip().lower() == propre:
            return eid
    return ""


def exemptes() -> set[str]:
    """Les lecteurs qui jouent une musique de veille (hors de l'arrêt automatique après inactivité)."""
    return {v["lecteur"] for v in _EN_COURS.values()}


async def playlists(hass: HomeAssistant) -> list[dict[str, Any]]:
    """Les playlists de Music Assistant pour la veille (relues à chaque demande, rien en dur) : d'abord les favorites,
    puis toutes les autres de la bibliothèque, celles que Music Assistant fabrique lui-même en dernier.

    l'auteur (15/10) : avec seulement les favorites, une bibliothèque sans aucun cœur affichait « aucune playlist » alors
    qu'elle en contenait 17. Les appareils montrent la liste dans cet ordre (les anciennes applis comprises).
    """
    favoris = [x for x in await musique.bibliotheque(hass, "favoris") if x.get("type") == "playlist"]
    vus = {x["uri"] for x in favoris}
    autres = [x for x in await musique.playlists_bibliotheque(hass) if x.get("type") == "playlist" and x["uri"] not in vus]
    autres.sort(key=lambda x: bool(x.get("auto")))  # tri stable : l'ordre de Music Assistant est gardé
    return [{"uri": x["uri"], "nom": x["nom"], "image": x.get("image") or "", "auto": bool(x.get("auto")), "favori": i < len(favoris)}
            for i, x in enumerate(favoris + autres)]


async def debut(hass: HomeAssistant, pc: dict[str, Any], uri: str, maintenant: float | None = None) -> dict[str, Any]:
    """Lance la playlist [uri] sur le lecteur de l'appareil. Rend {"lance": bool, "raison": …}."""
    maintenant = maintenant if maintenant is not None else time.time()
    pid = str(pc.get("id"))
    lecteur = lecteur_propre(hass, pc)
    if not lecteur:
        return {"lance": False, "raison": "lecteur"}
    deja = _EN_COURS.get(pid)
    if deja and deja["lecteur"] == lecteur and deja["uri"] == uri:
        deja["vu"] = maintenant
        return {"lance": True, "raison": "deja"}
    s = hass.states.get(lecteur)
    if s is None or s.state == "unavailable":
        return {"lance": False, "raison": "lecteur"}
    # Le lecteur joue déjà (Party, musique choisie…) : sa musique continue, la veille ne lance rien.
    if s.state in JOUE:
        return {"lance": False, "raison": "joue_deja"}
    # Une playlist de la bibliothèque doit y être encore ; celle d'un fournisseur (trouvée par la recherche) se lance.
    fournisseur = "://playlist/" in uri and not uri.startswith("library://")
    if not fournisseur and not any(p["uri"] == uri for p in await playlists(hass)):
        return {"lance": False, "raison": "introuvable"}
    repeter = s.attributes.get("repeat")
    await musique.jouer(hass, lecteur, uri, "playlist", "replace")
    # Jamais de titres ajoutés par Music Assistant, même si la Party les permet ailleurs.
    await musique.sans_ajout_auto(hass, lecteur, False)
    await musique.tenir_volume(hass, lecteur)
    try:
        await hass.services.async_call("media_player", "repeat_set", {"entity_id": lecteur, "repeat": "all"}, blocking=True)
    except Exception as err:  # noqa: BLE001 - la boucle est un confort
        _LOGGER.debug("Veille : répétition de %s : %s", lecteur, err)
    _EN_COURS[pid] = {"lecteur": lecteur, "uri": uri, "depuis": maintenant, "vu": maintenant,
                      "repeter": repeter if repeter in ("off", "one", "all") else None}
    _LOGGER.info("Veille : %s joue %s sur %s", pc.get("name"), uri, lecteur)
    return {"lance": True, "raison": ""}


def maintenir(pc: dict[str, Any], maintenant: float | None = None) -> bool:
    v = _EN_COURS.get(str(pc.get("id")))
    if v is None:
        return False
    v["vu"] = maintenant if maintenant is not None else time.time()
    return True


async def _arreter(hass: HomeAssistant, v: dict[str, Any]) -> None:
    lecteur = v["lecteur"]
    for service, donnees in (("media_stop", {}), ("clear_playlist", {})):
        try:
            await hass.services.async_call("media_player", service, {"entity_id": lecteur, **donnees}, blocking=True)
        except Exception as err:  # noqa: BLE001 - un lecteur parti : rien à arrêter
            _LOGGER.debug("Veille : %s de %s : %s", service, lecteur, err)
    if v.get("repeter"):
        try:
            await hass.services.async_call("media_player", "repeat_set", {"entity_id": lecteur, "repeat": v["repeter"]},
                                           blocking=True)
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("Veille : répétition de %s : %s", lecteur, err)


async def fin(hass: HomeAssistant, pc: dict[str, Any]) -> bool:
    """La veille est finie : la musique qu'ELLE a lancée s'arrête toujours (rien sinon)."""
    v = _EN_COURS.pop(str(pc.get("id")), None)
    if v is None:
        return False
    await _arreter(hass, v)
    _LOGGER.info("Veille : fin, %s arrêté", v["lecteur"])
    return True


async def suite(hass: HomeAssistant, pc: dict[str, Any]) -> bool:
    """2.5.9 (l'auteur, 17/10, point 25 : « un morceau s'est terminé et le suivant ne s'est pas lancé ») : Music Assistant
    (file, `_enqueue_next_item_on_player`) n'envoie le titre suivant qu'une fois, et seulement si le lecteur est alors
    « en lecture » ; sinon il abandonne sans réessayer, et en fin de titre une file qui a encore des titres reste à
    l'arrêt. Le lecteur Classique de l'appareil le dit (fin de titre, rien reçu) : on demande le titre suivant, sur
    son propre lecteur seulement. Faux : pas de lecteur à lui, ou Music Assistant refuse (fin de file)."""
    lecteur = lecteur_propre(hass, pc)
    if not lecteur:
        return False
    try:
        await hass.services.async_call("media_player", "media_next_track", {"entity_id": lecteur}, blocking=True)
    except Exception as err:  # noqa: BLE001 - fin de file ou lecteur parti : l'appareil s'arrête comme avant
        _LOGGER.info("Fin de titre sans suite sur %s : pas de titre suivant (%s)", lecteur, err)
        return False
    _LOGGER.warning("Fin de titre sans suite sur %s : Music Assistant n'avait rien envoyé, titre suivant demandé", lecteur)
    return True


async def tour(hass: HomeAssistant, maintenant: float | None = None) -> list[str]:
    """Toutes les 30 s : une veille sans nouvelles depuis EXPIRE_S est finie (appli fermée ou plantée)."""
    maintenant = maintenant if maintenant is not None else time.time()
    arretes = []
    for pid in [p for p, v in _EN_COURS.items() if maintenant - v["vu"] > EXPIRE_S]:
        v = _EN_COURS.pop(pid)
        await _arreter(hass, v)
        arretes.append(v["lecteur"])
        _LOGGER.warning("Veille : plus de nouvelles de l'appareil, %s arrêté", v["lecteur"])
    return arretes


def _vue():
    from .party import _Porte

    class PcParentalVeilleMusiqueView(_Porte):
        url = URL
        name = "api:pc_parental:veille_musique"

        async def post(self, request: web.Request) -> web.Response:
            coord, pc, corps, err = await self._ouvrir(request)
            if err is not None:
                return err
            lg = textes.pour(self.hass, pc)
            action = str(corps.get("action") or "")
            try:
                if action == "playlists":
                    return self.json({"ok": True, "items": await playlists(self.hass),
                                      "lecteur": bool(lecteur_propre(self.hass, pc))})
                if action == "debut":
                    uri = str(corps.get("uri") or "")
                    if not uri or len(uri) > 300 or "://" not in uri:
                        return self.json({"ok": False, "error": "uri", "retour": textes.t("commun.choix_inconnu", lg)}, status_code=400)
                    return self.json({"ok": True, **await debut(self.hass, pc, uri)})
                if action == "recherche":
                    # Les playlists de tous les fournisseurs connectés (YouTube Music…), pas seulement la bibliothèque.
                    texte = str(corps.get("texte") or "").strip()[:100]
                    res = await musique.chercher(self.hass, texte, ("playlist",)) if texte else {}
                    items = [{"uri": x["uri"], "nom": x["nom"], "image": x.get("image") or "", "auto": bool(x.get("auto")),
                              "favori": bool(x.get("favori")), "source": x.get("source") or ""}
                             for x in (res.get("playlist") or [])]
                    return self.json({"ok": True, "items": items})
                if action == "favori":
                    # Le cœur : Décideur, ou télé (les mêmes droits que le choix de musique de Vision).
                    from .party import est_tele, peut_participer

                    if not peut_participer(self.hass, coord.store, pc) and not est_tele(pc):
                        return self.json({"ok": False, "error": "droits", "retour": textes.t("commun.reserve_decideurs", lg)}, status_code=403)
                    favori = await musique.basculer_favori(self.hass, str(corps.get("uri") or ""), bool(corps.get("favori")))
                    return self.json({"ok": True, "favori": favori})
                if action == "maintenir":
                    return self.json({"ok": True, "en_cours": maintenir(pc)})
                if action == "fin":
                    return self.json({"ok": True, "arrete": await fin(self.hass, pc)})
                if action == "suite":
                    return self.json({"ok": True, "relance": await suite(self.hass, pc)})
            except musique.MusiqueIndisponible as e:
                return self.json({"ok": False, "error": "musique", "retour": textes.erreur(e, lg)}, status_code=503)
            return self.json({"ok": False, "error": "action", "retour": textes.t("commun.choix_inconnu", lg)}, status_code=400)

    return PcParentalVeilleMusiqueView


def enregistrer_vues(hass: HomeAssistant) -> None:  # noqa: D103
    hass.http.register_view(_vue()(hass))
