"""Point d'entrée interrogé par les agents installés sur les PC.

Cet endpoint n'utilise volontairement **pas** l'authentification de Home
Assistant : un jeton HA posé sur le PC d'un enfant lui donnerait les moyens de
se débloquer lui-même. Chaque PC dispose à la place d'un secret propre, qui ne
donne accès qu'à cette unique réponse : « ce PC est-il ouvert ou fermé ».
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import logging
import secrets
import time
from typing import Any

from aiohttp import web
from homeassistant.components import persistent_notification
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from . import android_maj, client_files, installation, labels, listes, messages
from . import accueil_options, lieu, messages_v2, modules, musique_options, nouveautes, party, preavis, roles, soutien, textes, veille_options, veille_pc, widgets
from .parent import PcParentalMaisonView, PcParentalParentView
from .demandes import PcParentalDemandeView
from .veille import PcParentalVeilleEntitesView, PcParentalVeilleFluxView, PcParentalVeilleImageView, PcParentalVeilleView
from .esports import PcParentalVeilleLogoView
from .veille_plus import PcParentalVeillePhotoView, PcParentalVeilleReglagesView
from .veille_tableaux import PcParentalVeilleTableauxView
from .const import (
    CLIENT_VERSION,
    DNS_FILTRANTS,
    DOMAIN,
    MAX_HOSTS_LISTE_NOIRE,
    PRESENCE_ACTIF,
    MARQUEUR_URL,
    MESSAGE_SECONDS,
    MODE_OPEN,
    POLL_SECONDS,
    PRODUIT,
    PRODUIT_AFFICHE,
)
from .coordinator import PcParentalCoordinator
from .planner import next_lock

_LOGGER = logging.getLogger(__name__)

URL_POLL = "/api/pc_parental/poll"
URL_ENROLL = "/api/pc_parental/enroll"
URL_CLIENT = "/api/pc_parental/client/{nom}"
URL_NOIRE = "/api/pc_parental/liste-noire"
URL_CATALOGUE = "/api/pc_parental/catalogue"
URL_REPONSE = "/api/pc_parental/reponse"
URL_ANDROID = "/api/pc_parental/android/{nom}"


def _personnes(hass: HomeAssistant) -> list[dict[str, str]]:
    """Les personnes de la maison, pour l'ecran d'inscription de l'app."""
    liste = []
    for etat in hass.states.async_all("person"):
        liste.append({"entity_id": etat.entity_id, "nom": etat.name})
    liste.sort(key=lambda x: x["nom"].lower())
    return liste



def hosts_version(reponse: dict[str, Any]) -> str:
    """Empreinte de tout ce que l'agent PC met dans son fichier hosts (1.93.2).

    Sites bloqués, adresses épargnées, version de la liste noire et SafeSearch : exactement les champs que l'agent
    passe à Sync-Hosts. Une liste qui ne change pas donne la même empreinte d'un relevé à l'autre ; le moindre
    changement en donne une autre, et l'agent reconstruit.
    """
    contenu = json.dumps(
        [
            list(reponse.get("sites") or []),
            list(reponse.get("exceptions") or []),
            str(reponse.get("liste_noire") or ""),
            bool(reponse.get("safesearch")),
        ],
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return hashlib.sha256(contenu.encode("utf-8")).hexdigest()[:16]


def _charge_agent(brut: Any) -> dict[str, float | int | None] | None:
    """La charge déclarée par l'agent, bornée : on ne range que des nombres plausibles."""
    if not isinstance(brut, dict):
        return None

    def nombre(cle: str, maxi: float) -> float | None:
        try:
            v = float(brut.get(cle))
        except (TypeError, ValueError):
            return None
        return round(v, 1) if 0 <= v <= maxi else None

    charge = {
        "cpu_pct": nombre("cpu_pct", 100.0),
        "mem_mo": nombre("mem_mo", 65536.0),
        "hosts_ms": nombre("hosts_ms", 3_600_000.0),
    }
    return charge if any(v is not None for v in charge.values()) else None

class PcParentalPollView(HomeAssistantView):
    """Répond à l'agent d'un PC."""

    url = URL_POLL
    name = "api:pc_parental:poll"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _coordinator(self) -> PcParentalCoordinator | None:
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        return entrees[0] if entrees else None

    async def _congedier(
        self,
        coord: PcParentalCoordinator,
        corps: dict[str, Any],
        pc_id: str,
        secret: str,
    ) -> web.Response | None:
        """Répond à l'agent d'un PC qu'on vient de supprimer.

        Supprimer un PC dans Home Assistant ne suffit pas : son agent tourne
        toujours sur la machine et continuerait à la brider. On garde donc son
        secret le temps qu'il repasse, et on lui répond « désinstalle-toi ».
        Il confirme, et la consigne disparaît.
        """
        retrait = coord.store.retrait_par_secret(pc_id, secret)
        if retrait is None:
            return None
        nom = retrait.get("name") or pc_id
        if corps.get("uninstalled"):
            coord.store.oublier_retrait(pc_id)
            await coord.store.async_save()
            _LOGGER.info("%s : agent désinstallé sur « %s ».", PRODUIT, nom)
            return self.json({"ok": True, "action": "none"})
        _LOGGER.info("%s : ordre de désinstallation pour « %s ».", PRODUIT, nom)
        return self.json(
            {
                "ok": True,
                "action": "uninstall",
                "poll": POLL_SECONDS,
                "message": "",
                "warn": 0,
                "apps": [],
                "sites": [],
            }
        )

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)

        coord = self._coordinator()
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)

        pc_id = str(corps.get("id") or "")
        secret = str(corps.get("secret") or "")
        pc = coord.store.by_secret(pc_id, secret)
        if pc is None:
            reponse = await self._congedier(coord, corps, pc_id, secret)
            if reponse is not None:
                return reponse
            _LOGGER.debug("Agent inconnu ou secret invalide (%s)", pc_id)
            return self.json({"ok": False, "error": "auth"}, status_code=401)

        # La langue de l'appareil (« langue », BCP 47) : ses raisons de blocage, ses messages la parlent.
        if textes.noter_langue(pc, corps):
            await coord.store.async_save()

        # --- À qui est cette fiche ? ---------------------------------------
        # Deux appareils du même modèle ont pu s'inscrire sous le même nom de
        # machine et partager une fiche. L'identifiant propre à l'appareil
        # les départage : le premier la garde, l'autre en reçoit une neuve.
        _uid = str(corps.get("uid") or "")[:64]
        _nom_app = str(corps.get("nom") or "").strip()[:64]
        if _uid and not pc.get("uid"):
            pc["uid"] = _uid
            if _nom_app and pc.get("name") == pc.get("host") and coord.store.by_name(_nom_app) is None:
                pc["name"] = _nom_app
            await coord.store.async_save()
        elif _uid and pc.get("uid") != _uid:
            _neuf = coord.store.nouvelle_fiche(
                str(corps.get("host") or "")[:64], _nom_app, _uid, str(corps.get("user") or "")[:64]
            )
            _fiche = coord.store.get(_neuf)
            _fiche["platform"] = str(corps.get("platform") or "")[:16]
            _pers0 = str(corps.get("person") or "")
            if _pers0.startswith("person.") and self.hass.states.get(_pers0) is not None:
                _fiche["personne_proposee"] = _pers0
            await coord.store.async_save()
            self.hass.config_entries.async_schedule_reload(coord.config_entry.entry_id)
            _LOGGER.info("%s : « %s » partageait une fiche, il a maintenant la sienne.", PRODUIT, _fiche["name"])
            return self.json({
                "ok": True, "action": "none", "poll": 5, "apps": [], "sites": [], "message": "", "warn": 0,
                "reinscription": {"id": _neuf, "secret": _fiche["secret"], "name": _fiche["name"]},
            })
        elif not _uid and pc.get("uid") and pc.get("platform") == "android":
            # Une ancienne appli sur une fiche qui appartient à un autre appareil :
            # rien ne s'applique chez elle, on lui propose seulement la mise à jour.
            _neutre: dict[str, Any] = {
                "ok": True, "action": "none", "poll": POLL_SECONDS, "apps": [], "sites": [],
                "message": "", "warn": 0, "liste_noire": "", "dns": [], "safesearch": False, "parent": False,
            }
            _maj0 = android_maj.infos(self.hass)
            if _maj0 and str(corps.get("version") or "") != _maj0["version"]:
                _neutre["update"] = _maj0
            return self.json(_neutre)

        # Le rapport de l'agent : session ouverte, état appliqué, version.
        precedent = float(pc.get("last_seen") or 0)
        maintenant_ts = time.time()
        pc["last_seen"] = maintenant_ts
        inactif = corps.get("idle_seconds")
        admin = corps.get("admin")
        pc["agent"] = {
            "user": str(corps.get("user") or "")[:64],
            "locked": bool(corps.get("locked")),
            "version": str(corps.get("version") or "")[:16],
            "host": str(corps.get("host") or "")[:64],
            "idle_seconds": (
                max(0, int(float(inactif))) if inactif is not None else None
            ),
            "focus": str(corps.get("focus") or "")[:64],
            "admin": None if admin is None else bool(admin),
        }
        # 1.93.2 : ce que l'agent PC coûte à sa machine (processeur, mémoire, durée du fichier hosts), s'il le dit.
        _charge = _charge_agent(corps.get("agent_charge"))
        if _charge:
            pc["agent"]["charge"] = _charge
        pc["agent"]["platform"] = str(corps.get("platform") or "")[:16]
        # 2.4.0 (retour d'essai) : écran éteint (appli Vision, DisplayManager) → la lecture du lecteur « · Vision » se met en pause.
        if str(corps.get("ecran") or "") in ("eteint", "allume"):
            from . import musique_arret
            await musique_arret.ecran(self.hass, coord.store, pc, str(corps["ecran"]))
        # L'app a fait choisir la personne : ce n'est qu'une proposition. Seul un
        # administrateur la fixe (sélecteur « Utilisateur ») : un enfant qui se
        # dirait parent hériterait sinon de ses exceptions et de ses tableaux.
        _pers = str(corps.get("person") or "")
        if _pers.startswith("person.") and self.hass.states.get(_pers) is not None:
            if pc.get("person") != _pers and pc.get("personne_proposee") != _pers:
                pc["personne_proposee"] = _pers
                await coord.store.async_save()
        # 1.97.7 (point 10, diagnostic) : qui administre ce PC (lecture seule), gardé tel quel, borné.
        _diag = corps.get("diag_admin")
        if isinstance(_diag, dict) and isinstance(_diag.get("membres"), list):
            pc["diag_admin"] = {
                "membres": [
                    {"nom": str(m.get("nom") or "")[:64], "type": str(m.get("type") or "")[:16], "source": str(m.get("source") or "")[:24],
                     "actif": None if m.get("actif") is None else bool(m.get("actif"))}
                    for m in _diag["membres"][:20] if isinstance(m, dict)
                ],
                "uac": _diag.get("uac") if isinstance(_diag.get("uac"), int) else None,
                "invite_admin": _diag.get("invite_admin") if isinstance(_diag.get("invite_admin"), int) else None,
                "ts": maintenant_ts,
            }
            self.hass.async_create_task(coord.store.async_save())
        _prot = corps.get("protections")
        if isinstance(_prot, dict):
            pc["agent"]["protections"] = {k: bool(v) for k, v in _prot.items()}
            # 2.5.12 (point de sécurité) : « Protection désactivée sur <appareil> » quand l'accessibilité tombe.
            from . import protection as _protection

            _protection.releve(self.hass, coord, pc, maintenant_ts)
        # Matrice réglages × type d'appareil (1.72.0).
        from . import matrice as _matrice

        _type = _matrice.type_appareil(corps)
        if pc.get("type") != _type:
            pc["type"] = _type
            await coord.store.async_save()
            _matrice.appliquer(self.hass, coord.store)
        # Pauses de la protection (1.70.0) : journalisées une fois chacune.
        from . import pauses as _pauses

        if _pauses.recevoir(self.hass, pc, corps.get("pauses"), roles.pause_max(coord.store, pc)):
            await coord.store.async_save()

        # --- Ce que Vision observe -----------------------------------
        # Le premier relevé après une absence ne compte pas : on ne sait pas
        # ce qui s'est passé entre-temps.
        ecart = maintenant_ts - precedent if precedent else 0
        presence = coord.store.presence(pc, True)
        coord.store.compter_usage(pc, presence, ecart)

        focus = str(corps.get("focus") or "")
        # 2.5.12 (incident du salon) : autre chose joue sur l'appareil (pas de relance de soirée par-dessus).
        from . import party as _party_son

        _party_son.noter_autre_son(pc_id, bool(corps.get("autre_son")), maintenant_ts)
        if focus and presence == PRESENCE_ACTIF:
            # Le temps ne se compte que sur le logiciel réellement utilisé.
            coord.store.noter_app(pc, focus, ecart)
        for ouvert in list(corps.get("apps_ouverts") or [])[:60]:
            coord.store.noter_app_ouverte(pc, str(ouvert))
        # Android : noms affichés et inventaire des applis installées.
        if isinstance(corps.get("apps_libelles"), dict):
            coord.store.noter_libelles(pc, corps["apps_libelles"])
        if corps.get("apps_installees"):
            coord.store.noter_inventaire(pc, corps["apps_installees"])
        coord.store.oublier_bruit(pc)
        # L'agent choisit déjà ce qu'il remonte, en tournant pour qu'aucun
        # domaine ne reste invisible : ce plafond-ci n'est qu'une ceinture.
        for site in list(corps.get("sites_vus") or [])[:200]:
            coord.store.noter_site(pc, str(site))
        # Après les sites, jamais avant : une origine ne fait pas entrer un
        # domaine au catalogue, elle ne fait que dire qui lui parlait.
        coord.store.noter_origines(pc, corps.get("origines") or {})
        coord.store.noter_signatures(pc, corps.get("signatures") or {})
        # L'inventaire des processus : range par le module « processus »,
        # qui n'existe peut-etre pas (chargement partiel).
        gestion = getattr(coord, "processus", None)
        if gestion is not None:
            gestion.recevoir(pc_id, corps)
        coord.store.purger_catalogue(pc)
        # --- Tentatives vers un domaine dangereux ------------------------
        # L'agent ne décide de rien : il dit ce qu'il a vu passer, Home
        # Assistant décide s'il faut en parler.
        _proposer: list[str] = []
        # 2.5.1 : le logiciel au premier plan, pour lier à lui les sites bloqués pendant qu'il tourne.
        _app_focus = coord.store.famille_premier_plan(focus)
        for brut in list(corps.get("alertes") or [])[:20]:
            if isinstance(brut, dict):
                domaine = str(brut.get("domaine") or "")
                origine = str(brut.get("source") or "")[:32]
            else:
                domaine, origine = str(brut), ""
            # Un site fermé par une règle de la maison (pas la liste noire,
            # pas la pub) : on propose à l'enfant de demander l'accès.
            if origine == "regle" and len(_proposer) < 2:
                try:
                    _ets = {e.lower() for e in coord.store.etiquettes_de("sites", domaine)}
                except Exception:  # noqa: BLE001
                    _ets = set()
                _muets = {"pub & traçage", "dangereux", "système", "systeme", "contournement", "adulte"}
                if _ets and not (_ets & _muets) and "." in domaine:
                    _proposer.append(domaine)
            # Aucune notification, nulle part : ni sur le PC, ni ici. Un
            # site bloqué est simplement injoignable. Ce qui reste, c'est la
            # trace — le journal dit *quoi*, la courbe dit *combien*.
            if coord.store.noter_alerte(pc, domaine, origine, _app_focus if origine == "regle" else ""):
                coord.store.compter_blocage(pc)
        # 2.5.1 (décision du projet du 14-10) : les sites vus pendant qu'un logiciel tourne lui sont liés tout seuls,
        # avec les garde-fous ; un site fermé au moment du lien le reste.
        try:
            coord.store.lier_auto(pc)
        except Exception:  # un lien raté ne bloque jamais le relevé
            _LOGGER.debug("Vision : liens automatiques non faits.", exc_info=True)

        coord.marquer_observation()
        _mins = corps.get("demande_temps")
        if _mins and modules.coupe(coord.store, "demandes"):
            # 1.82.0 : module « Demandes » coupé : la demande est ignorée, personne n'est prévenu.
            _LOGGER.info("%s : demande de temps de « %s » ignorée (module « Demandes » coupé).",
                         PRODUIT, pc.get("name") or pc_id)
            _mins = 0
        if _mins:
            try:
                _mins = max(1, min(240, int(_mins)))
            except (TypeError, ValueError):
                _mins = 0
            if _mins:
                _nom = pc.get("name") or pc_id
                # 1.97.8 : le prénom d'abord (« Lou demande 20 minutes de plus (TEL-LOU). »), comme les autres demandes.
                from . import demandes as _dem_mod
                _prenom = _dem_mod.prenom(self.hass, pc)

                def _texte_temps(langue: str) -> str:
                    if _prenom and _prenom != _nom:
                        return textes.n("http.demande_temps_qui", _mins, langue, prenom=_prenom, nom=_nom)
                    return textes.n("http.demande_temps", _mins, langue, nom=_nom)

                try:
                    from . import bilan as _bilan

                    _bilan.compter(coord.store, str(pc.get("person") or ""), "demande")
                except Exception:  # noqa: BLE001
                    pass
                persistent_notification.async_create(
                    self.hass,
                    _texte_temps(textes.ha(self.hass)) + "\n\n" + textes.ou_decider(textes.ha(self.hass)),
                    title=textes.t("http.titre_demande_temps_ha", textes.ha(self.hass)),
                    notification_id="{}_temps_{}".format(DOMAIN, pc_id),
                )
                self.hass.bus.async_fire(
                    "{}_demande_temps".format(DOMAIN),
                    {"id": pc_id, "name": _nom, "minutes": _mins},
                )
                # La demande part aussi sur les appareils des parents (etiquette
                # Home Assistant « Parents »), avec de quoi repondre d'un geste.
                _dem = {"id": secrets.token_hex(4), "minutes": _mins, "ts": maintenant_ts}
                _prochain = coord.store.evaluate(pc).get("next_change")
                pc["demande"] = _dem
                # Lot 2 : sur les téléphones des Décideurs (appli Compagnon), avec ses boutons.
                from . import a_decider as _adec
                self.hass.async_create_task(_adec.notifier(self.hass, coord, "temps", pc_id, _dem["id"],
                                                           _texte_temps(textes.ha(self.hass)), _mins))
                # 1.97.2 : gardée tout de suite (un redémarrage la perdait) ; elle attend dans la liste des demandes.
                self.hass.async_create_task(coord.store.async_save())
                from . import canaux as _canaux

                for _pid in roles.avec(coord.store, "gerer"):
                    # 2.5.6 : dans l'appli Vision des Décideurs qui l'ont choisie (canaux.py).
                    if _pid == pc_id or not _canaux.vision_pour(self.hass, coord.store, _pid, "demandes"):
                        continue
                    _langue_g = textes.pour(self.hass, coord.store.pcs.get(_pid))
                    try:
                        _msg = coord.store.add_message(
                            _pid,
                            _texte_temps(_langue_g),
                            titre=textes.t("http.titre_demande_temps", _langue_g),
                        )
                    except ValueError:
                        continue
                    _msg["demande"] = {
                        "id": _dem["id"], "pc": pc_id, "nom": _nom, "minutes": _mins,
                        # 1.80.0 : « Jusqu'à la prochaine plage (18h30) » chez qui répond.
                        "prochain": _prochain.isoformat() if _prochain else "",
                    }

        etat = coord.store.evaluate(pc)
        maintenant = coord.local_now()
        # 1.82.0 : les modules coupés n'agissent plus à partir de ce relevé (modules.py ; leurs réglages restent).
        filtrage = not modules.coupe(coord.store, "filtrage")
        _annoncer_modules(coord.store, pc)
        # 2.5.12.14.2 (revue de HACS) : ce que l'agent doit appliquer se calcule par tranches, sans bloquer la boucle.
        _effectif = await coord.store.async_effectif(pc)
        reponse: dict[str, Any] = {
            "ok": True,
            "action": "lock" if etat["locked"] else "none",
            "poll": POLL_SECONDS,
            "lieu": lieu.nom(coord.store),
            # 2.4.2 : jamais de veille sur un PC Windows (retirée) ; les télés et téléphones gardent la leur.
            "modules": veille_pc.modules_pour(pc, modules.public(coord.store)),
            # Widgets Home Assistant (docs/widgets-ha.md) : l'appli recharge sa liste quand ce nombre change.
            "widgets": {"version": widgets.version(coord.store, pc)},
            # 1.98.0 (point 14 bis) : la tuile Mode Party et l'écran Party de la télé choisie.
            "party": party.pour_releve(self.hass, coord.store, pc),
            # 1.93.05 (l'auteur, retour 36) : les options de la veille de cet appareil (pc_parental.veille_regler), si réglées.
            "veille_options": veille_options.pour_releve(pc),
            # 1.93.06 : la musique de cet appareil face au son des autres applis (pc_parental.musique_regler), si réglé.
            "musique_options": musique_options.pour_releve(pc),
            # 1.93.06.1 : « Afficher la liste des applications » de cet appareil (pc_parental.accueil_regler), si réglé.
            "accueil_options": accueil_options.pour_releve(pc),
            # Soutien du projet (docs/soutien.md) : badge et animation, rien d'autre.
            "soutien": soutien.pour_releve(coord.store),
            # L'agent reçoit le résultat, pas les règles : les étiquettes
            # et les exceptions sont résolues ici.
            **_effectif,
            "message": "",
            "warn": 0,
            "message_seconds": MESSAGE_SECONDS,
            # L'agent ne reçoit ici que la **version** de la liste noire :
            # cinquante mille domaines toutes les vingt secondes n'auraient
            # aucun sens. Il va les chercher lui-même quand elle change.
            "liste_noire": coord.noire.version if coord.noire.active and filtrage else "",
            "dns": DNS_FILTRANTS.get(coord.store.dns_filtrant, []) if filtrage else [],
            # SafeSearch et YouTube restreint : seulement sur une machine
            # ou la categorie Adulte est coupee. Un adulte garde ses resultats.
            "safesearch": filtrage and bool(
                coord.store.etiquette_bloquee(pc, "Adulte")
                or coord.store.est_banni("Adulte")
            ),
            # Un appareil « Parents » voit l'espace parents dans l'application.
            "parent": roles.a(coord.store, pc, "gerer"),
            # Les fonctions du rôle de cet appareil (1.71.0) : l'appli montre ce qui est permis.
            "role": roles.nom(coord.store, pc),
            "fonctions": modules.fonctions_effectives(coord.store, roles.fonctions(coord.store, pc)),
            # FAILLE (l'auteur, 16/10) : le code de déverrouillage de ce Décideur est-il déjà dans HA ? (octroi.py)
            "code_deverrouillage": _code_deverrouillage(coord.store, pc),
        }
        # 1.93.2 (l'auteur, perte d'images par seconde chez un Demandeur) : Home Assistant décide quand l'agent PC
        # reconstruit son fichier hosts. Tant que cette version ne bouge pas, l'agent n'a rien à refaire.
        reponse["hosts_version"] = hosts_version(reponse)
        # Profils (1.73.0) : la personne voit son temps du jour, cumulé sur ses appareils.
        if pc.get("person") and roles.a(coord.store, pc, "temps") and not modules.coupe(coord.store, "temps"):
            from .parent import temps_personne

            reponse["temps_personne"] = temps_personne(coord.store, str(pc["person"]))
        # Ce qu'un parent vient de refuser : l'appareil sait qu'il ne peut pas le redemander tout de suite.
        try:
            reponse["refus"] = [] if modules.coupe(coord.store, "demandes") else coord.store.refus_en_cours(pc)
        except Exception:  # noqa: BLE001
            reponse["refus"] = []
        # 2.5.1 (ajouts 5 et 6 de l'auteur) : les sites bloqués récemment que le Demandeur peut demander, et les mêmes
        # regroupés par logiciel (« Roblox ne marche pas : 6 sites bloqués »), pour une demande d'un geste.
        try:
            if modules.coupe(coord.store, "demandes"):
                reponse["bloques_recents"], reponse["groupes"] = [], []
            else:
                reponse["bloques_recents"] = coord.store.blocages_recents(pc, textes.pour(self.hass, pc), pour_demandeur=True, maximum=12)
                reponse["groupes"] = coord.store.groupes_a_demander(pc, textes.pour(self.hass, pc))
        except Exception:  # jamais au prix du relevé
            _LOGGER.debug("Vision : blocages récents non calculés.", exc_info=True)
        # Les applis « Toujours autorisé » : ouvertes même appareil fermé.
        try:
            if "Toujours autorisé" not in coord.store.toutes_etiquettes():
                coord.store.declarer_etiquette("Toujours autorisé")
            reponse["toujours"] = list(coord.store.noms_par_etiquette("apps", "Toujours autorisé"))
            # Plus celles de la personne : posées sur un de ses appareils, elles valent sur tous.
            moi_pc = coord.store.pcs.get(pc_id) or {}
            ma_personne = str(moi_pc.get("person") or "")
            for autre in coord.store.pcs.values():
                if autre is moi_pc or (ma_personne and str(autre.get("person") or "") == ma_personne):
                    for nom_tj in autre.get("toujours") or []:
                        if nom_tj not in reponse["toujours"]:
                            reponse["toujours"].append(nom_tj)
            # Refonte des autorisations, lot 1 : les applis « toujours disponibles » du Demandeur.
            for nom_tj in coord.store.noms_durables(moi_pc, "apps", "toujours"):
                if nom_tj not in reponse["toujours"]:
                    reponse["toujours"].append(nom_tj)
        except Exception:  # noqa: BLE001
            reponse["toujours"] = []
        if gestion is not None:
            # Ce que ce PC doit tenir : demarrage, taches, services,
            # processus a arreter. Tout le reste, l'agent le restaure.
            reponse["processus"] = gestion.consignes(pc_id)
            if modules.coupe(coord.store, "optimisations"):
                # 1.82.0 : « Optimisations PC » coupé : des consignes VIDES (et non absentes). L'agent
                # restaure alors tout ce qu'il avait changé ; sans la clé, il garderait l'état d'avant.
                reponse["processus"] = {"demarrage": [], "taches": [], "services": [], "arreter": []}

        # La durée max d'une pause sur cet appareil (code parent ou empreinte) ; 0 : module « Pause » coupé.
        reponse["pause_max"] = 0 if modules.coupe(coord.store, "pause") else roles.pause_max(coord.store, pc)

        # Module Réveil (docs/reveil-contrat-appareils.md) — ce que l'appareil a armé et ses événements,
        # puis la liste à armer (48 h) et les ordres (sonner, reporter, arrêter). L'appareil sonne seul ensuite.
        try:
            from . import reveil as _reveil

            _rv_change = _reveil.recevoir(self.hass, coord.store, pc, corps.get("reveils"))
            reponse["reveils"], _rv_pris = _reveil.pour_releve(self.hass, coord.store, pc)
            if _rv_change or _rv_pris:
                # Ordres remis une fois et ordres d'arrêt partagé pour les autres appareils : enregistré tout de suite.
                await coord.store.async_save()
                coord.async_update_listeners()
        except Exception:  # noqa: BLE001 - le relevé passe avant tout
            _LOGGER.exception("%s : réveils (relevé)", PRODUIT)

        # Module Niveau sonore (docs/son-contrat-appareils.md) — des nombres seulement (niveau, dépassements),
        # puis les réglages de la mesure ; « actif: false » arrête le micro de l'appareil.
        try:
            from . import son as _son

            if _son.recevoir(self.hass, coord.store, pc, corps.get("son")):
                await coord.store.async_save()
                coord.async_update_listeners()
            reponse["son"] = _son.pour_releve(coord.store, pc)
        except Exception:  # noqa: BLE001 - le relevé passe avant tout
            _LOGGER.exception("%s : niveau sonore (relevé)", PRODUIT)
        # 17-10 (retour d'essai) : l'écran de cet appareil (veille de Vision, écran gardé allumé), réglé par pc_parental.ecran_regler.
        try:
            from . import ecran as _ecran

            reponse["ecran"] = _ecran.pour_releve(pc)
        except Exception:  # noqa: BLE001 - le relevé passe avant tout
            _LOGGER.exception("%s : écran (relevé)", PRODUIT)

        # Messages à afficher sur ce PC (poubelle, rappel, avertissement…).
        if attente := coord.store.take_messages(pc):
            # Demandes d'accès : la remise est notée (accusé de réception), une demande déjà réglée ne sonne plus.
            from . import demandes_livraison as _livraison

            attente = _livraison.a_remettre(coord.store, pc["id"], attente)
            if attente:
                reponse["notify"] = [messages.pour_appareil(m) for m in attente]
            attente = True
        # Messages bloquants « parent » libérés depuis Home Assistant.
        if liberes := messages.prendre_liberations(pc):
            reponse["liberer"] = liberes
        if attente or liberes:
            # Délivrés une fois : enregistré tout de suite, sinon un rechargement de Vision
            # (nouvel appareil, étiquette…) relirait la file d'avant et les redonnerait.
            await coord.store.async_save()

        # Mise à jour du client : proposée si la version installée diffère
        # de celle hébergée, et que la maison est en automatique ou que la
        # mise à jour a été demandée pour ce PC.
        installee = str((pc.get("agent") or {}).get("version") or "")
        if pc.get("platform") == "android":
            _maj = android_maj.infos_pour(self.hass, str(pc.get("name") or ""), installee,
                                          str(pc.get("id") or ""), coord.store.appareils_essai)
            if _maj and installee and installee != _maj["version"]:
                if coord.store.auto_update or pc.get("update_asked"):
                    reponse["update"] = _maj
                    pc["update_asked"] = False
        elif installee and installee != CLIENT_VERSION:
            if coord.store.auto_update or pc.get("update_asked"):
                reponse["update"] = client_files.info_maj(self.hass)
                pc["update_asked"] = False

        suivant = etat.get("next_change")
        langue = textes.pour(self.hass, pc)
        if etat["locked"]:
            reponse["message"] = (
                textes.t("http.acces_ferme_jusqua", langue, heure=textes.heure(suivant, langue))
                if suivant
                else textes.t("http.acces_ferme", langue)
            )
            # Pause générale (point 14.2) : l'écran de blocage le dit, avec l'heure de fin (module « Messages »).
            from . import pause_generale as _pause_g

            if (texte_pg := _pause_g.message(coord.store, pc)) is not None:
                reponse["message"] = texte_pg
        elif not modules.coupe(coord.store, "messages"):
            # Le préavis relève du module « Messages » ; le planning, de « Plages » (regles_pour).
            prochaine = next_lock(coord.store.regles_pour(pc), maintenant)
            if prochaine is not None:
                restant = (prochaine - maintenant).total_seconds() / 60
                if 0 < restant <= coord.store.warn_minutes:
                    reponse["warn"] = int(restant) + 1
                    reponse["message"] = textes.t("http.verrouille_a", langue, heure=textes.heure(prochaine, langue))
        # Une plage d'étiquettes ferme sans fermer le PC : elle a donc son
        # propre préavis, qui ne doit pas prendre la place de celui du
        # verrouillage — les deux peuvent tomber le même quart d'heure.
        if not modules.coupe(coord.store, "messages") and (annonces := coord.store.preavis_plages(pc, maintenant)):
            reponse["avertissements"] = annonces
        # 1.85.0 : décompte X / 5 / 1 min avant une fermeture (module « Préavis » ; X = warn_minutes).
        try:
            reponse.update(preavis.calcul(coord.store, pc, maintenant))
        except Exception:  # noqa: BLE001 - un préavis raté ne doit jamais casser le relevé
            _LOGGER.exception("%s : calcul du préavis", PRODUIT)

        if suivant is not None:
            reponse["next_change"] = suivant.isoformat()
        # Lot Accueil : les mots du vocabulaire (Décideur, Demandeur, Lieu…) dans la langue de l'appareil, réglages de
        # l'administrateur compris (Configurer → Vocabulaire). L'appli 1.86.14 ne s'en sert pas encore (rebase i18n).
        try:
            from . import vocabulaire

            # 2.3.4 : seulement les minuscules (l'appli met elle-même la majuscule). « Decideur » à côté de
            # « decideur » rendait la réponse illisible pour ConvertFrom-Json de Windows PowerShell 5.1 (clés
            # en double, sans tenir compte de la casse) : chaque relevé des agents PC échouait depuis 2.3.1.
            reponse["vocabulaire"] = {c: v for c, v in vocabulaire.termes(langue, coord.store).items() if c == c.lower()}
        except Exception:  # noqa: BLE001 - le relevé passe avant tout
            _LOGGER.exception("%s : vocabulaire du relevé", PRODUIT)
        # Déverrouillage (1.66.0) : clé publique, biométrie, approbation directe en attente.
        try:
            from . import deverrouillage

            if not modules.coupe(coord.store, "deverrouillage"):
                reponse.update(await deverrouillage.pour_releve(self.hass, coord, pc))
        except Exception:  # noqa: BLE001 - le relevé passe avant tout
            _LOGGER.exception("Déverrouillage : relevé")

        # « Quoi de neuf » (docs/nouveautes.md) : la langue et le « vu » éventuels de l'appareil ; s'il reste
        # des notes à voir, seulement la version (l'appli va les lire sur /api/pc_parental/nouveautes).
        try:
            _neuf_v, _neuf_sauver = await nouveautes.pour_releve(self.hass, coord.store, pc, corps)
            if _neuf_v:
                reponse["nouveautes"] = _neuf_v
            if _neuf_sauver:
                await coord.store.async_save()  # rare (langue changée, « vu ») : gardé tout de suite
        except Exception:  # noqa: BLE001 - le relevé passe avant tout
            _LOGGER.exception("%s : nouveautés au relevé", PRODUIT)

        coord.async_set_updated_data(
            {p: coord.store.evaluate(x) for p, x in coord.store.pcs.items()}
        )
        return self.json(reponse)


_MODULES_ANNONCES: dict[str, tuple[str, ...]] = {}


def _annoncer_modules(store, pc: dict[str, Any]) -> None:
    """Journal : la première fois qu'un appareil reçoit une liste de modules changée (la coupure l'a atteint)."""
    liste = tuple(modules.public(store))
    avant = _MODULES_ANNONCES.get(str(pc.get("id") or ""))
    _MODULES_ANNONCES[str(pc.get("id") or "")] = liste
    if avant is not None and avant != liste:
        coupes = sorted(set(avant) - set(liste))
        revenus = sorted(set(liste) - set(avant))
        _LOGGER.warning("%s : « %s » a reçu les modules à jour (coupés : %s ; revenus : %s).", PRODUIT,
                        pc.get("name") or pc.get("id"), ", ".join(coupes) or "aucun", ", ".join(revenus) or "aucun")


def _du_reseau_local(request: web.Request) -> bool:
    """Vrai si la demande arrive en direct d'un appareil de la maison."""
    if request.headers.get("X-Forwarded-For") or request.headers.get("Forwarded"):
        return False
    try:
        ip = ipaddress.ip_address(request.remote or "")
    except ValueError:
        return False
    return adresse_locale(ip)


# Les réseaux d'une maison, quelle que soit la box : RFC 1918 en entier, IPv6 locale unique (ULA) et
# lien local. Pas la boucle locale : un mandataire posé sur la machine de Home Assistant n'est pas un appareil.
RESEAUX_LOCAUX = tuple(ipaddress.ip_network(r) for r in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10",
))


def adresse_locale(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """Vrai pour une adresse d'un réseau privé ; une IPv4 vue en IPv6 (::ffff:a.b.c.d) compte comme son IPv4."""
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return any(ip.version == r.version and ip in r for r in RESEAUX_LOCAUX)


class PcParentalEnrollView(HomeAssistantView):
    """Inscrit automatiquement un nouveau PC.

    L'agent n'a que la clé d'inscription de la maison ; c'est Home Assistant
    qui crée l'appareil, ses entités et le secret propre à ce PC.
    """

    url = URL_ENROLL
    name = "api:pc_parental:enroll"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _coordinator(self) -> PcParentalCoordinator | None:
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        return entrees[0] if entrees else None

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)

        coord = self._coordinator()
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)

        store = coord.store
        _cle = str(corps.get("key") or "")
        _locale = not _cle and store.inscriptions_ouvertes() and _du_reseau_local(request)
        # 2.2.12 : l'ancienne clé, remplacée parce que connue ou faible, ne vaut
        # plus que pour un appareil déjà inscrit qui redemande sa réinscription
        # (pendant son sursis). Elle ne crée jamais de fiche.
        if not _locale and not store.check_enroll_key(_cle) and not store.check_ancienne_cle(
            _cle, str(corps.get("host") or "")[:64], str(corps.get("uid") or "")[:64]
        ):
            _LOGGER.warning(
                "Inscription refusée pour « %s » : clé invalide ou "
                "inscriptions fermées.",
                str(corps.get("host") or "?")[:64],
            )
            return self.json(
                {"ok": False, "error": "enroll"}, status_code=403
            )

        host = str(corps.get("host") or "")[:64]
        user = str(corps.get("user") or "")[:64]
        try:
            pc_id, cree = store.enroll(
                host, user, str(corps.get("uid") or "")[:64], str(corps.get("nom") or "").strip()[:64]
            )
        except ValueError as err:
            return self.json(
                {"ok": False, "error": textes.erreur(err, textes.normaliser_corps(corps, self.hass))}, status_code=400
            )

        pc = store.get(pc_id)
        if not cree:
            # Un appareil déjà inscrit : son secret ne sort jamais d'ici. Il n'en
            # reçoit un neuf que si un administrateur a permis la réinscription.
            neuf = store.reinscrire(pc_id)
            if neuf is None:
                _LOGGER.warning(
                    "Inscription refusée pour « %s » : appareil déjà inscrit, "
                    "réinscription non autorisée.", pc["name"],
                )
                persistent_notification.async_create(
                    self.hass,
                    textes.t("http.reinscription_texte", textes.ha(self.hass), nom=pc["name"],
                             chemin=textes.chemin_fiche(self.hass, pc_id)),
                    title=textes.t("http.reinscription_titre", textes.ha(self.hass)),
                    notification_id=f"{DOMAIN}_reinscription_{pc_id}",
                )
                return self.json({"ok": False, "error": "deja_inscrit"}, status_code=409)
            _uid = str(corps.get("uid") or "")[:64]
            if _uid and not pc.get("uid"):
                pc["uid"] = _uid
            persistent_notification.async_dismiss(self.hass, f"{DOMAIN}_reinscription_{pc_id}")
            _LOGGER.info("%s : « %s » réinscrit avec un secret neuf.", PRODUIT, pc["name"])
        pc["platform"] = str(corps.get("platform") or "")[:16]
        textes.noter_langue(pc, corps)
        await store.async_save()
        if cree:
            # Un nouveau PC signifie de nouvelles entités : on recharge.
            self.hass.config_entries.async_schedule_reload(
                coord.config_entry.entry_id
            )
        else:
            await coord.async_refresh()

        if cree:
            _LOGGER.info("Nouvel appareil inscrit : %s", pc["name"])
            persistent_notification.async_create(
                self.hass,
                textes.t("http.nouvel_appareil_texte", textes.ha(self.hass), nom=pc["name"], produit=PRODUIT,
                         tableau=PRODUIT_AFFICHE),
                title=textes.t("http.nouvel_appareil_titre", textes.ha(self.hass)),
                notification_id=f"{DOMAIN}_{pc_id}",
            )
            self.hass.bus.async_fire(
                f"{DOMAIN}_pc_decouvert",
                {"id": pc_id, "name": pc["name"], "host": host},
            )

        return self.json(
            {
                "ok": True,
                "id": pc_id,
                "secret": pc["secret"],
                "name": pc["name"],
                "created": cree,
                "poll": POLL_SECONDS,
                # Pour que l'app propose « qui utilise cet appareil ? »
                "personnes": _personnes(self.hass),
            }
        )


class PcParentalClientView(HomeAssistantView):
    """Sert les scripts du client.

    Volontairement accessible sans authentification : l'installateur doit
    pouvoir être téléchargé avant que la machine ne possède le moindre
    identifiant. Ces fichiers ne contiennent aucun secret — les secrets sont
    générés par Home Assistant et n'existent que sur le PC concerné.
    """

    url = URL_CLIENT
    name = "api:pc_parental:client"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _adresse(self, request: web.Request) -> str:
        """Adresse par laquelle ce PC vient de joindre Home Assistant.

        C'est exactement celle que son agent devra interroger ensuite : si le
        PC a téléchargé l'installateur depuis `http://homeassistant.local:8123`,
        c'est là qu'il doit revenir, quelle que soit l'adresse externe
        configurée. On se rabat sur la configuration seulement si l'en-tête
        Host manque.
        """
        hote = request.headers.get("Host") or request.host
        if hote:
            return f"{request.scheme}://{hote}".rstrip("/")
        for cle in ("internal_url", "external_url"):
            if valeur := getattr(self.hass.config, cle, None):
                return str(valeur).rstrip("/")
        return ""

    async def get(self, request: web.Request, nom: str) -> web.Response:
        if nom == "version":
            return self.json(
                {
                    "version": CLIENT_VERSION,
                    "sha256": client_files.empreinte("agent"),
                }
            )
        texte = client_files.contenu(nom)
        if texte is None:
            return web.Response(status=404, text=textes.t("http.fichier_inconnu", textes.ha(self.hass)))
        if MARQUEUR_URL in texte:
            # L'installateur part avec l'adresse déjà remplie : plus rien à
            # taper sur le PC de l'enfant.
            if adresse := self._adresse(request):
                texte = texte.replace(MARQUEUR_URL, adresse)
        if nom == "agent":
            # 2.3.4 : l'agent se met à jour sous le compte SYSTEM avec Invoke-WebRequest, sans -UseBasicParsing
            # jusqu'en 1.90.0. Windows PowerShell 5.1 lit alors toute réponse « text/* » avec le moteur
            # d'Internet Explorer, absent pour SYSTEM : le téléchargement échouait à chaque relevé. En binaire,
            # PowerShell n'analyse rien ; les octets, donc l'empreinte, ne changent pas.
            return web.Response(
                body=texte.encode("utf-8"),
                content_type="application/octet-stream",
                headers={"X-Client-Version": CLIENT_VERSION},
            )
        return web.Response(
            text=texte,
            content_type="text/plain",
            charset="utf-8",
            headers={"X-Client-Version": CLIENT_VERSION},
        )


class PcParentalListeNoireView(HomeAssistantView):
    """Sert la liste noire à un agent, qui vient la chercher quand elle change.

    Même règle que le reste : pas de jeton Home Assistant sur le PC d'un
    enfant, le secret de la machine suffit et ne donne accès qu'à ceci.
    """

    url = URL_NOIRE
    name = "api:pc_parental:liste_noire"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord: PcParentalCoordinator | None = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        pc = coord.store.by_secret(
            str(corps.get("id") or ""), str(corps.get("secret") or "")
        )
        if pc is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        return self.json(
            {"ok": True, **coord.noire.pour_agent(MAX_HOSTS_LISTE_NOIRE)}
        )


class PcParentalReponseView(HomeAssistantView):
    """Un parent repond, depuis son telephone, a une demande de temps.

    Meme regle que les autres vues d'agent : le secret de l'appareil suffit,
    mais seul un appareil portant l'etiquette « Parents » a le droit de
    decider. L'enfant recoit la reponse comme un message ordinaire.
    """

    url = URL_REPONSE
    name = "api:pc_parental:reponse"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    def _prenom(self, pc: dict[str, Any]) -> str:
        etat = self.hass.states.get(str(pc.get("person") or ""))
        if etat is not None and etat.name:
            return str(etat.name)
        return str(pc.get("name") or "")

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord: PcParentalCoordinator | None = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        parent = coord.store.by_secret(
            str(corps.get("id") or ""), str(corps.get("secret") or "")
        )
        if parent is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        textes.noter_langue(parent, corps)
        if parent["id"] not in roles.avec(coord.store, "gerer"):
            return self.json({"ok": False, "error": "parent"}, status_code=403)
        if refus := modules.refus(self, coord.store, "demandes"):
            return refus
        enfant = coord.store.pcs.get(str(corps.get("pc") or ""))
        if enfant is None:
            return self.json({"ok": False, "error": "pc"}, status_code=404)
        demande = enfant.get("demande") or {}
        if not demande or demande.get("id") != str(corps.get("demande") or ""):
            return self.json({"ok": True, "deja": True})
        try:
            minutes = max(0, min(720, int(corps.get("minutes") or 0)))
        except (TypeError, ValueError):
            minutes = 0
        qui = self._prenom(parent)
        langue_parent = textes.pour(self.hass, parent)
        langue_enfant = textes.pour(self.hass, enfant)

        def quel(langue: str) -> str:
            return qui or textes.t("commun.un_gestionnaire", langue)

        retour = ""
        if corps.get("jusqua_plage") and coord.store.evaluate(enfant).get("next_change"):  # sans plages : None
            # 1.80.0 : jusqu'au prochain changement de l'agenda (l'heure est dite à chacun).
            coord.store.set_override(enfant, MODE_OPEN, 0)
            fin = float((enfant.get("override") or {}).get("until") or 0)
            minutes = max(1, int((fin - time.time() + 59) // 60)) if fin else minutes

            def dire(langue: str) -> str:
                return textes.t("http.temps_jusqua", langue, qui=quel(langue), heure=textes.heure(fin, langue) if fin else "")

            texte, retour = dire(langue_enfant), dire(langue_parent)
        elif minutes:
            coord.store.set_override(enfant, MODE_OPEN, minutes)
            texte = textes.n("http.temps_accorde", minutes, langue_enfant, qui=quel(langue_enfant))
        else:
            texte = textes.t("http.temps_refuse", langue_enfant, qui=quel(langue_enfant))
        qui = quel(textes.ha(self.hass))
        enfant["demande"] = None
        try:
            # La réponse à une demande reste envoyée même sans le module « Messages » : c'est la suite
            # d'un geste de l'appareil (le module « Demandes »), pas un message libre.
            coord.store.add_message(enfant["id"], texte, titre=textes.t("commun.reponse", langue_enfant))
        except ValueError:
            pass
        persistent_notification.async_dismiss(
            self.hass, "{}_temps_{}".format(DOMAIN, enfant["id"])
        )
        await coord.store.async_save()
        coord.async_set_updated_data(
            {p: coord.store.evaluate(x) for p, x in coord.store.pcs.items()}
        )
        self.hass.bus.async_fire(
            "{}_reponse_temps".format(DOMAIN),
            {"id": enfant["id"], "name": enfant.get("name"), "minutes": minutes, "par": qui},
        )
        return self.json({"ok": True, "minutes": minutes, "retour": retour})


class PcParentalAndroidView(HomeAssistantView):
    """Sert l'application Android livrée avec l'intégration (APK et fiche de version).

    Sans authentification, comme les scripts du client : le téléphone doit
    pouvoir télécharger l'application avant d'être inscrit. L'APK ne contient
    aucun secret.
    """

    url = URL_ANDROID
    name = "api:pc_parental:android"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request, nom: str) -> web.Response:
        nom_servi = "vision.apk"
        if nom == "vision.apk":
            # Le lien stable : toujours la dernière version (pas de redirection, pour
            # les navigateurs de télé et l'appli Downloader).
            chemin, nom_servi = android_maj.fichier_stable()
        elif nom.startswith("vision-") and nom.endswith(".apk"):
            # Le lien d'installation, versionné : un ancien lien mène à la dernière version.
            chemin, publiee = android_maj.fichier_versionne(nom)
            if chemin is None:
                if publiee:
                    raise web.HTTPFound(android_maj.MODELE_VERSIONNE.format(publiee))
                return web.Response(status=404, text=textes.t("http.aucune_version", textes.ha(self.hass)))
            nom_servi = nom
        else:
            chemin = android_maj.fichier(nom)
        if chemin is None:
            return web.Response(status=404, text=textes.t("http.fichier_inconnu", textes.ha(self.hass)))
        if nom.endswith(".apk"):
            return web.FileResponse(
                chemin,
                headers={
                    "Content-Type": "application/vnd.android.package-archive",
                    "Content-Disposition": f"attachment; filename={nom_servi}",
                    "Cache-Control": "no-cache",
                },
            )
        return web.FileResponse(chemin, headers={"Cache-Control": "no-cache"})


class PcParentalCatalogueView(HomeAssistantView):
    """Le catalogue et les étiquettes, pour la carte d'étiquetage.

    Celle-ci **exige** l'authentification de Home Assistant, à l'inverse
    des vues des agents : elle est lue par le navigateur d'un adulte déjà
    connecté, et elle dit tout ce que la maison surveille. Elle passe par
    une vue plutôt que par les attributs d'un capteur parce qu'un
    catalogue de plusieurs centaines de lignes n'a rien à faire dans
    l'historique, réécrit à chaque changement.
    """

    url = URL_CATALOGUE
    name = "api:pc_parental:catalogue"
    requires_auth = True

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def get(self, request: web.Request) -> web.Response:
        # Tout ce que la maison surveille : un administrateur (1.61.0), pas un enfant connecté.
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord: PcParentalCoordinator | None = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        # 2.5.12 : la carte relit toutes les 30 s ; rien ne repart si le catalogue n'a pas changé.
        # 2.5.12.13 : calculé une fois pour tous les navigateurs ouverts (gardé 10 s, oublié à chaque écriture).
        donnees, revision = coord.store._memo(
            ("catalogue_http",), 10.0, lambda: listes.avec_revision({"ok": True, **coord.store.catalogue_etiquetage()}))
        return listes.repondre(self, request, donnees, revision)

    async def post(self, request: web.Request) -> web.Response:
        """Coche ou décoche des catégories : {"categories": {"Nom": true|false}}.

        Même convention que l'écran de veille (veille/dashboards) : réservé aux
        administrateurs, et le magasin ne garde que les écarts au défaut."""
        utilisateur = request.get("hass_user")
        if utilisateur is None or not utilisateur.is_admin:
            return self.json({"ok": False, "error": "admin"}, status_code=403)
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord: PcParentalCoordinator | None = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        choix = corps.get("categories")
        if not isinstance(choix, dict):
            return self.json({"ok": False, "error": textes.t("http.rien_a_enregistrer", textes.ha(self.hass))},
                             status_code=400)
        from .entity import appliquer

        try:
            await appliquer(coord, lambda: coord.store.choisir_categories(choix))
        except Exception as err:  # noqa: BLE001
            return self.json({"ok": False, "error": textes.erreur(err, textes.ha(self.hass))}, status_code=400)
        return self.json({"ok": True, **coord.store.catalogue_etiquetage()})


def _code_deverrouillage(store, pc: dict[str, Any]) -> bool | None:
    """Pour l'appareil d'un Décideur : son code est-il dans HA ? None pour les autres appareils."""
    from . import octroi

    personne = str(pc.get("person") or "")
    if not personne or personne not in octroi.decideurs(store):
        return None
    return octroi.code_pose(store, personne)


def async_register(hass: HomeAssistant) -> None:
    """Enregistre les vues une seule fois."""
    if hass.data.get(f"{DOMAIN}_view"):
        return
    hass.http.register_view(PcParentalPollView(hass))
    hass.http.register_view(PcParentalEnrollView(hass))
    hass.http.register_view(PcParentalClientView(hass))
    hass.http.register_view(PcParentalListeNoireView(hass))
    listes.enregistrer(hass)  # 2.5.12 : les grandes listes, à la demande
    hass.http.register_view(PcParentalCatalogueView(hass))
    hass.http.register_view(PcParentalReponseView(hass))
    hass.http.register_view(PcParentalAndroidView(hass))
    messages.enregistrer_vues(hass)
    hass.http.register_view(installation.PcParentalInstallationQrView(hass))
    hass.http.register_view(installation.PcParentalAdresseCourteView(hass))
    hass.http.register_view(PcParentalParentView(hass))
    hass.http.register_view(PcParentalMaisonView(hass))
    from .widgets_admin import PcParentalWidgetsAdminView
    hass.http.register_view(PcParentalWidgetsAdminView(hass))  # 1.94.0 : onglet « Widgets HA »
    from .personne_fiche import PcParentalPersonneAdminView
    hass.http.register_view(PcParentalPersonneAdminView(hass))  # Refonte lot 3 : la fiche d'une personne
    from .reglages_admin import PcParentalReglagesAdminView
    hass.http.register_view(PcParentalReglagesAdminView(hass))  # Refonte lot 5 : Réglages › Réservé
    from .canaux import PcParentalCanauxView
    hass.http.register_view(PcParentalCanauxView(hass))  # 2.5.6 : Réglages › Mes notifications
    from .apparence import PcParentalApparenceView
    hass.http.register_view(PcParentalApparenceView(hass))  # 1.95.0 : apparence du tableau
    hass.http.register_view(PcParentalDemandeView(hass))
    hass.http.register_view(PcParentalVeilleView(hass))
    hass.http.register_view(PcParentalVeilleImageView(hass))
    from .veille_cases import PcParentalVeilleMediaView
    hass.http.register_view(PcParentalVeilleMediaView(hass))  # 2.2.12 : images des cases typées
    hass.http.register_view(PcParentalVeilleEntitesView(hass))
    hass.http.register_view(PcParentalVeilleFluxView(hass))
    hass.http.register_view(PcParentalVeilleLogoView(hass))
    hass.http.register_view(PcParentalVeillePhotoView(hass))
    hass.http.register_view(PcParentalVeilleReglagesView(hass))
    from .veille_dash import PcParentalVeilleDashView
    hass.http.register_view(PcParentalVeilleDashView(hass))
    from . import veille_page
    veille_page.installer(hass)
    hass.http.register_view(PcParentalVeilleTableauxView(hass))
    widgets.enregistrer_vues(hass)
    # 2.4.5 : l'appairage Sendspin automatique (PIN transmis par l'appareil, soumis à Music Assistant par HA).
    from . import sendspin_appairage
    sendspin_appairage.enregistrer_vues(hass)
    # 1.98.0 (point 14 bis) : le Mode Party.
    party.enregistrer_vues(hass)
    from . import veille_musique
    veille_musique.enregistrer_vues(hass)
    from . import reveil_http

    reveil_http.enregistrer_vues(hass)
    # 2.0.0 : notifications v2 (point 8).
    messages_v2.enregistrer_vues(hass)
    # « Quoi de neuf » : notes de version par appareil, une fois par version.
    nouveautes.enregistrer_vues(hass)
    hass.data[f"{DOMAIN}_view"] = True
