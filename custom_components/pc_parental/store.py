"""Persistance et logique métier : les PC, leurs plages et leurs listes."""

from __future__ import annotations

import copy
import functools
import hashlib
import json
import secrets
import time
from datetime import datetime, timedelta
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .const import (
    MODES_AUTORISATION,
    MODES_DURABLES,
    ETIQUETTES_SANS_LIEN_AUTO,
    NAVIGATEURS,
    LIENS_AUTO_MAX,
    ETIQUETTES_SECURITE,
    ETIQUETTE_ECOLE,
    PAQUETS_VISION,
    DELAI_REFUS,
    APPS_INTOUCHABLES,
    MAX_ORIGINES,
    AUTORISE,
    BLOQUE,
    FILTRE_A_CLASSER,
    FILTRE_AUTORISES,
    FILTRE_BLOQUES,
    CATALOGUE_MAX_APPS,
    CATALOGUE_MAX_SITES,
    BRUIT_APPS,
    BRUIT_SITES,
    ALERTE_REPOS,
    BLOCAGES_JOURS,
    CATALOGUE_TTL,
    DNS_AUCUN,
    DNS_FILTRANTS,
    CLE_GRACE_JOURS,
    CLE_MIN,
    CLES_PUBLIEES_SHA256,
    ENROLL_KEY_V,
    MAX_ALERTES,
    ORIGINE_CONSTATE,
    ORIGINE_INSCRIT,
    ORIGINE_JAMAIS,
    FAMILLES_APPS,
    DEPENDANCES_APPS,
    FAMILLES_SITES,
    SERVEURS_PARTAGES,
    LAISSEZ_PASSER_MAX,
    LAISSEZ_PASSER_MIN,
    MAX_ADRESSES,
    SECONDS_GENERIQUES,
    DEFAULT_SOUND,
    IDLE_MINUTES,
    MAX_MESSAGES,
    MESSAGE_TTL,
    MODE_LOCKED,
    MODE_OPEN,
    PRESENCE_ACTIF,
    PRESENCE_HORS_LIGNE,
    PRESENCE_INACTIF,
    PRESENCE_VERROUILLE,
    REINSCRIPTION_MINUTES,
    RETRAIT_TTL,
    SOURCE_OVERRIDE,
    PREFIXE_TAG,
    SOUNDS,
    SOURCE_PLANNING,
    STORAGE_KEY,
    TOUS_LES_PC,
    STORAGE_VERSION,
    USAGE_ECART_MAX,
    USAGE_JOURS,
    WARN_MINUTES,
)
from . import labels
from . import modules as _modules
from . import textes
from .planner import _sans_accent
from .planner import (
    PORTEE_ETIQUETTES,
    cibles_label,
    de_portee,
    is_locked,
    next_change,
    normalise_rule,
    prochaine_fermeture,
    rule_covers,
)


def _ts(naive_local: datetime) -> float:
    """Horodatage d'une date/heure locale naïve."""
    return naive_local.replace(tzinfo=dt_util.DEFAULT_TIME_ZONE).timestamp()


def _hm(secondes) -> str:
    s = int(secondes or 0) % 86400
    return "{:02d}:{:02d}".format(s // 3600, (s % 3600) // 60)


@functools.lru_cache(maxsize=4096)
def _ensemble_bas(valeurs: tuple) -> frozenset:
    """Les valeurs d'une liste en minuscules, mémoïsées par contenu : `_dans` est appelé
    des centaines de milliers de fois par relevé sur les mêmes listes."""
    return frozenset((m or "").strip().lower() for m in valeurs)


@functools.lru_cache(maxsize=65536)
def _nom_app(nom: str) -> str:
    """Nom de programme tel que les règles et le catalogue le connaissent.

    Minuscules, sans chemin ni « .exe », et ramené à sa famille :
    « robloxplayerbeta » et « robloxcrashhandler » sont Roblox, pas trois
    logiciels à trancher séparément.
    """
    nom = str(nom or "").strip().lower()
    if not nom:
        return ""
    nom = nom.replace("\\", "/").rsplit("/", 1)[-1]
    if nom.endswith(".exe"):
        nom = nom[:-4]
    return FAMILLES_APPS.get(nom, nom)


@functools.lru_cache(maxsize=65536)
def _hote(adresse: str) -> str:
    """Nom de machine d'une adresse : « https://m.youtube.com/x » → « m.youtube.com »."""
    adresse = str(adresse or "").strip().lower().rstrip(".")
    if "://" in adresse:
        adresse = adresse.split("://", 1)[1]
    adresse = adresse.split("/", 1)[0].split("?", 1)[0]
    adresse = adresse.split("@")[-1].split(":", 1)[0]
    return adresse if "." in adresse else ""


@functools.lru_cache(maxsize=65536)
def domaine_principal(hote: str) -> str:
    """Le site auquel appartient cette adresse.

    « m.youtube.com », « www.youtube.com » et « youtube.com » sont **un**
    site. Décider sur chaque sous-domaine n'a aucun sens : ils naissent et
    meurent tout seuls, par centaines, sans que personne ne les ait
    demandés.

    Reste le piège des suffixes composés : « bbc.co.uk » est un site,
    « co.uk » n'en est pas un. On le reconnaît à un pays de deux lettres
    précédé d'un préfixe générique.
    """
    hote = _hote(hote)
    if not hote:
        return ""
    morceaux = hote.split(".")
    if len(morceaux) <= 2:
        return hote
    if (
        len(morceaux[-1]) == 2
        and morceaux[-2] in SECONDS_GENERIQUES
        and len(morceaux) >= 3
    ):
        return ".".join(morceaux[-3:])
    return ".".join(morceaux[-2:])


@functools.lru_cache(maxsize=65536)
def _cle_fermeture(etiquette: str, source: str = "") -> str:
    """Une fermeture par catégorie et par source : « jeux|moyenne », ou « jeux| » sans source."""
    return "{}|{}".format(str(etiquette or "").strip().lower(), str(source or "").strip())


def _nom_site(domaine: str) -> str:
    """Nom d'un site tel que les règles et le catalogue le connaissent.

    Son domaine principal, puis sa famille : « rbxcdn.com » est Roblox. On
    ne décide pas deux fois pour le même endroit.
    """
    domaine = domaine_principal(domaine)
    return FAMILLES_SITES.get(domaine, domaine)


def membres_site(nom: str) -> list[str]:
    """Tous les domaines d'une famille — le chef de file en tête.

    L'agent bloque des noms, pas des idées : couper « roblox.com » sans
    couper « rbxcdn.com » laisserait le jeu tourner sur ses serveurs de
    contenu.
    """
    nom = _nom_site(nom)
    if not nom:
        return []
    return [nom] + sorted(a for a, c in FAMILLES_SITES.items() if c == nom)


def membres_app(nom: str) -> list[str]:
    """Tous les exécutables d'une même famille de logiciels."""
    nom = _nom_app(nom)
    if not nom:
        return []
    return [nom] + sorted(a for a, c in FAMILLES_APPS.items() if c == nom)


def est_bruit_site(nom: str) -> bool:
    """De l'infrastructure au service d'autres sites, jamais une décision.

    On ne la range pas dans la file : elle reviendrait indéfiniment sous
    d'autres noms. À distinguer d'un site sans intérêt, qui se tranche une
    fois et sort de la file tout seul.
    """
    return domaine_principal(nom) in BRUIT_SITES


# Composants d'Android qui ne sont pas des applis qu'on choisit d'utiliser :
# interface système, lanceur, clavier, installateur, services Google... et
# l'agent Vision lui-même. Chrome et le Play Store restent visibles, on peut
# vouloir les régler.
_BRUIT_ANDROID = (
    "com.android.systemui", "com.android.settings", "com.android.inputmethod",
    "com.google.android.inputmethod", "com.android.packageinstaller",
    "com.google.android.packageinstaller", "com.android.permissioncontroller",
    "com.google.android.permissioncontroller", "com.google.android.gms",
    "com.google.android.gsf", "com.android.providers", "com.android.server",
    "com.android.phone", "com.android.incallui", "com.android.pixeldisplayservice",
    "com.google.android.apps.nexuslauncher", "com.android.launcher",
    "com.android.bluetooth", "com.android.nfc", "com.android.shell",
    "com.android.emergency", "com.google.android.ext", "com.android.cellbroadcast",
    "com.google.android.cellbroadcast", "com.android.wifi", "com.android.carrier",
    "com.google.android.overlay", *PAQUETS_VISION,
)


def est_bruit_app(nom: str) -> bool:
    """Programme de Windows, utilitaire de barre des tâches ou composant Android."""
    n = _nom_app(nom)
    if n in BRUIT_APPS:
        return True
    if "." in n:
        if n.endswith(".launcher") or n.endswith("launcher3") or ".overlay." in n:
            return True
        if any(n.startswith(p) for p in _BRUIT_ANDROID):
            return True
    return False


def _slug(nom: str) -> str:
    garde = [c.lower() if c.isalnum() else "_" for c in nom]
    brut = "".join(garde).strip("_")
    while "__" in brut:
        brut = brut.replace("__", "_")
    return brut or "pc"


def _replier_etiquettes(etiquettes: dict) -> dict:
    """Les étiquettes posées sur un sous-domaine reviennent au domaine."""
    replie: dict[str, list[str]] = {}
    for nom, valeurs in (etiquettes or {}).items():
        domaine = domaine_principal(nom)
        if not domaine:
            continue
        garde = replie.setdefault(domaine, [])
        for valeur in valeurs or []:
            if valeur.lower() not in [g.lower() for g in garde]:
                garde.append(valeur)
    return replie


def _replier_apps(vus: dict) -> dict:
    """Écarte les programmes de Windows et réunit les familles.

    Un catalogue où « dwm », « runtimebroker » et « svchost » côtoient
    « minecraft » ne se lit pas : on n'y voit plus ce qui se décide.
    """
    replie: dict[str, dict] = {}
    for nom, fiche in (vus or {}).items():
        if est_bruit_app(nom):
            continue
        cle = _nom_app(nom)
        if not cle:
            continue
        garde = replie.get(cle)
        if garde is None:
            replie[cle] = dict(fiche)
            continue
        garde["premier"] = min(
            float(garde.get("premier") or 0) or float(fiche.get("premier") or 0),
            float(fiche.get("premier") or 0) or float(garde.get("premier") or 0),
        )
        garde["dernier"] = max(
            float(garde.get("dernier") or 0), float(fiche.get("dernier") or 0)
        )
        garde["secondes"] = int(garde.get("secondes") or 0) + int(
            fiche.get("secondes") or 0
        )
    return replie


def _replier_sites(vus: dict) -> dict:
    """Range les relevés sous le domaine, en gardant trace des adresses.

    Les versions ≤ 1.13.0 comptaient chaque sous-domaine comme un site :
    quelques centaines de lignes dont personne ne voulait. On les replie
    une fois pour toutes, sans rien perdre — les relevés s'additionnent et
    les adresses vues restent disponibles pour écrire une exception.

    Depuis la 1.15.0, le repli va au bout : l'infrastructure sort du
    catalogue, et les domaines d'un même service se rejoignent sous un seul
    nom.
    """
    replie: dict[str, dict] = {}
    for nom, fiche in (vus or {}).items():
        hote = _hote(nom)
        if est_bruit_site(hote):
            continue
        domaine = _nom_site(hote)
        if not domaine:
            continue
        garde = replie.get(domaine)
        if garde is None:
            garde = {
                "premier": float(fiche.get("premier") or 0),
                "dernier": float(fiche.get("dernier") or 0),
                "releves": 0,
                "adresses": [],
            }
            replie[domaine] = garde
        garde["premier"] = min(
            garde["premier"] or float(fiche.get("premier") or 0),
            float(fiche.get("premier") or 0) or garde["premier"],
        )
        garde["dernier"] = max(garde["dernier"], float(fiche.get("dernier") or 0))
        garde["releves"] = int(garde["releves"]) + int(fiche.get("releves") or 0)
        adresses = list(fiche.get("adresses") or [])
        if hote and hote != domaine:
            adresses.append(hote)
        elif hote and domaine_principal(hote) != domaine:
            adresses.append(domaine_principal(hote))
        for adresse in adresses:
            if adresse not in garde["adresses"] and len(garde["adresses"]) < MAX_ADRESSES:
                garde["adresses"].append(adresse)
    for garde in replie.values():
        garde["adresses"] = sorted(garde["adresses"])
    return replie


def cle_aleatoire() -> str:
    """Une clé d'inscription neuve : 64 bits tirés au hasard, en hexadécimal."""
    return secrets.token_hex(8)


def cle_faible(cle: str) -> bool:
    """Clé connue de tous ou trop faible pour protéger les inscriptions.

    Exemple publié (anciennes aides de l'installateur), moins de CLE_MIN
    caractères, ou presque toujours le même caractère (« xxxxxxxxxxxxxxxx »).
    """
    cle = str(cle or "").strip()
    if hashlib.sha256(cle.lower().encode("utf-8")).hexdigest() in CLES_PUBLIEES_SHA256:
        return True
    return len(cle) < CLE_MIN or len(set(cle.lower())) < 4


class PcStore:
    """Contient l'ensemble des PC pilotés et sait les faire évoluer."""

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self._store: Store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
        self.pcs: dict[str, dict[str, Any]] = {}
        # Clé unique pour toute la maison, saisie une fois dans l'installateur.
        # Réservée aux administrateurs (options de l'intégration) depuis la 1.61.0.
        self.enroll_key: str = ""
        # Vrai quand le chargement vient de la changer : il faut l'enregistrer.
        self.cle_changee: bool = False
        # 2.2.12 : l'ancienne clé remplacée parce que connue ou faible, et jusqu'à
        # quand elle vaut encore, pour un appareil déjà inscrit seulement.
        self.enroll_key_ancienne: str = ""
        self.enroll_key_ancienne_jusqua: float = 0.0
        # Permet de fermer les inscriptions une fois tous les PC équipés.
        self.enroll_open: bool = True
        # « Autoriser 5 min » : ouvertes jusqu'à cet instant (horodatage), 0 sinon.
        self.enroll_jusqua: float = 0.0
        self.roles_migres: bool = False
        # 1.80.0 : noms de rôles neutres et personnes rattachées, une seule fois (roles.neutraliser).
        self.vocabulaire_neutre: bool = False
        # Refonte lot 5 : installation faite avec l'assistant, sur un magasin vide → moins d'entités (entites_reduites.py).
        self.entites_reduites: bool = False
        # 1.81.0 : le nom du lieu (lieu.py) ; vide = le mot neutre par défaut (« Lieu », lieu.DEFAUTS).
        self.lieu: str = ""
        # 2.2.5 (retour d'essai) : Musique — les lecteurs Music Assistant proposés comme enceintes (None = tous, réglage par défaut)
        # et les lecteurs hors ligne masqués ou montrés grisés.
        # 2.5.9 (l'auteur, point 16) : « invites_mode », le mode des invités de la Party une fois choisi, est gardé ici aussi ;
        # avant, il ne vivait qu'en mémoire et un redémarrage de HA le remettait en silence sur « en fin de file ».
        self.musique: dict = {"enceintes": None, "masquer_hors_ligne": False}
        # Chantier « Générique » : ce qui était propre à une maison devient un réglage (réglages généraux).
        # Vide = découverte : les personnes nommées « Affichage ».
        self.personnes_affichage: list[str] = []
        # Appareils Android qui reçoivent l'APK d'essai, par identifiant (en plus des noms d'essai.json).
        self.appareils_essai: list[str] = []
        # Messages d'accueil de l'appli (accueil.py) : messages, mode, meteo, modele ; vide = défaut.
        self.accueil: dict[str, str] = {}
        # Lot Accueil : les mots du vocabulaire choisis par l'administrateur (Configurer → Vocabulaire), par langue :
        # {"fr": {"decideur": "responsable", ...}} ; absent ou vide = les mots par défaut (vocabulaire.DEFAUTS).
        # À ne pas confondre avec self.vocabulaire (la liste des étiquettes).
        self.mots_vocabulaire: dict[str, dict[str, str]] = {}
        # 1.82.0 : les modules activables (modules.py) ; absent = actif.
        self.modules: dict[str, bool] = {}
        # Module Réveil (reveil.py) — les réveils {id: réveil}, les réglages du module (écarts au défaut
        # seulement) et la migration des rôles (roles.fonctions_reveil), une fois.
        self.reveils: dict[str, dict[str, Any]] = {}
        self.reveil_reglages: dict[str, Any] = {}
        self.roles_reveil: bool = False
        # 1.86.1 : l'empreinte du dernier tableau de bord généré (refait au démarrage si elle change).
        self.signature_tableau: str = ""
        # Pause générale (pause_generale.py) : {"fin", "debut", "par", "appareils": {pc_id: dérogation d'avant}} ou None.
        self.pause_generale: dict[str, Any] | None = None
        # Widgets Home Assistant (docs/widgets-ha.md, widgets.py) : liste par personne, exceptions par
        # appareil ({pc_id: {"plus": [...], "moins": [...]}}) et liste blanche d'actions par entité.
        self.entites_personnes: dict[str, list[str]] = {}
        self.entites_appareils: dict[str, dict[str, list[str]]] = {}
        self.entites_actions: dict[str, list[str]] = {}
        # 2.4.0 (widgets interactifs, proposition validée en essai le 2026-10-12) : les entités « pour tout le monde »
        # (vues par tous les profils, en plus de la liste de chaque personne) et le réglage de chaque entité
        # (rôle, qui peut agir, confirmation, nom et icône, affichage) — voir widgets.py et docs/widgets-ha.md.
        self.entites_tous: list[str] = []
        self.entites_reglages: dict[str, dict[str, Any]] = {}
        # 2.0.0 (notifications v2) : types de messages de l'utilisateur, et les derniers envois (suivi).
        self.types_messages: list[dict[str, Any]] = []
        self.envois_messages: list[dict[str, Any]] = []
        # Lot 2 (« À décider ») : les services notify.mobile_app_* des Décideurs (notifications avec boutons).
        self.notif_decideurs: list[str] = []
        # 2.5.6 (l'auteur, 15/10) : par Décideur, les canaux de chaque évènement (canaux.py) ; le salon Discord du lieu.
        self.canaux_notif: dict[str, dict[str, list[str]]] = {}
        self.discord: dict[str, str] = {}
        # Soutien du projet (docs/soutien.md) : {"mode": "code"|"honneur", ...} ; décoratif.
        self.soutien: dict[str, Any] = {}
        # 1.71.0 : modèles de rôles (id → nom, fonctions, pause_max) et rôle de chaque personne.
        self.roles: dict[str, dict[str, Any]] = {}
        self.roles_personnes: dict[str, str] = {}
        # Les agents se mettent à jour seuls dès qu'une version est publiée.
        self.auto_update: bool = True
        # Faille 2.2-1 : empreinte du code de gestion des PC, jamais le code (code_gestion.py).
        self.code_gestion: dict[str, Any] = {}
        # FAILLE (l'auteur, 16/10) : le code de déverrouillage de chaque Décideur (octroi.py) — l'empreinte, jamais le code ;
        # le même pour l'appli et le tableau. Et les essais faux récents, pour tout le Lieu.
        self.codes_decideurs: dict[str, dict[str, Any]] = {}
        self.codes_decideurs_echecs: list[float] = []
        # 1.77.0 : la veille des appareils montre les vraies cartes par la passerelle (essai, éteint par défaut).
        self.veille_rendu: bool = False
        # Les agents ont-ils le droit de désinstaller ce qu'un adulte a
        # désigné ? Éteint tant que personne ne l'a voulu.
        self.desinstallation: bool = False
        # Minutes d'avertissement avant une fermeture programmée.
        self.warn_minutes: int = WARN_MINUTES
        # Sans frappe ni souris pendant ce temps, la machine est inactive.
        self.idle_minutes: int = IDLE_MINUTES
        self.delai_refus: int = DELAI_REFUS
        # PC supprimés dont l'agent doit encore apprendre son congé.
        self.retraits: dict[str, dict[str, Any]] = {}
        # Étiquettes des logiciels et des sites. Elles appartiennent à la
        # maison, pas à un PC : « minecraft est un jeu » reste vrai partout.
        self.etiquettes: dict[str, dict[str, list[str]]] = {
            "apps": {},
            "sites": {},
        }
        # Éléments inscrits à la main. Attendre qu'un enfant ouvre un site
        # pour avoir le droit d'en décider, c'est arriver après : on doit
        # pouvoir trancher d'avance, sur un nom qu'on est seul à connaître.
        self.ajouts: dict[str, list[str]] = {"apps": [], "sites": []}
        # Vocabulaire des étiquettes Vision. Une étiquette existe dès
        # qu'on l'a écrite, même si rien ne la porte encore : sans cela,
        # « Vidéo » tapé dans les règles d'un PC n'était pas reconnu comme
        # une étiquette et devenait un logiciel fantôme.
        self.vocabulaire: list[str] = []
        # Résolveur filtrant posé sur les cartes réseau des PC. C'est lui qui
        # encaisse les millions de domaines dangereux ; le fichier hosts ne
        # porte que la part que Vision doit connaître nommément.
        self.dns_filtrant: str = DNS_AUCUN
        # Bannis pour **toute la maison**. Certains sites ne se discutent pas
        # machine par machine : les interdire quatre fois serait quatre
        # occasions d'en oublier une. Un PC peut malgré tout poser son
        # exception — le plus précis l'emporte, et le PC est plus précis que
        # la maison.
        self.bannis: list[str] = []
        # Et son symétrique : ce qu'on a explicitement autorisé pour toute
        # la maison. Sans lui, « autorisé » et « pas encore regardé » se
        # confondraient, et la file ne se viderait jamais.
        self.autorises_maison: list[str] = []
        # Les étiquettes cochées ou décochées comme catégories de Vision, par
        # identifiant d'étiquette (un renommage ne perd pas le choix). Seuls
        # les écarts au défaut sont gardés, comme pour veille_dash.
        self.categories_choix: dict[str, bool] = {}
        # La liste noire, posée ici après son chargement. Un domaine
        # malveillant n'est pas une décision de la maison : il est écarté
        # d'office, sur toutes les machines. Il n'a donc rien à faire ni
        # dans le catalogue, ni dans les règles — l'y voir laisserait croire
        # qu'il reste quelque chose à trancher.
        self.dangereux: set[str] = set()
        # Ce que la télé montre en veille : des entités, « id|Titre » au choix.
        self.veille_entites: list[str] = []
        # Les deux températures de la courbe (dehors, dedans) ; vide = déduit des tuiles.
        self.veille_courbe: list[str] = []
        # Les caméras que l'écran de veille peut montrer ; aucune tant que personne ne les a choisies.
        self.veille_cameras: list[str] = []
        # Suffixe du flux secondaire des caméras pour la télé (« _sub » chez Frigate) ; vide = flux normal.
        self.veille_flux_suffixe: str = ""
        # Adresse d'un flux audio (radio sans publicité) joué par l'écran de veille ; vide = silence.
        self.veille_musique: str = ""
        # Thème de couleurs de l'écran de veille, et plage horaire du mode nuit (« 23:00-07:00 », vide = jamais).
        self.veille_theme: str = "Beige"
        self.veille_nuit: str = "23:00-07:00"
        # Les tableaux de l'écran de veille : ordre, durée, public, et les tableaux composés d'entités.
        self.veille_tableaux: list[dict] = []
        self.veille_dash: dict = {}
        # L'heure, isolée derrière un appel : les tests la figent, le reste
        # du code ne sait pas qu'elle est réglable.
        self.horloge = lambda: dt_util.now().replace(tzinfo=None)

    # ------------------------------------------------------------------ E/S

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        self.pcs = data.get("pcs") or {}
        self.enroll_key = str(data.get("enroll_key") or "")
        self.enroll_key_ancienne = str(data.get("enroll_key_ancienne") or "")
        self.enroll_key_ancienne_jusqua = float(data.get("enroll_key_ancienne_jusqua") or 0)
        generation = int(data.get("enroll_key_v") or 1)
        if not self.enroll_key:
            # Installation neuve : une clé aléatoire, rien à signaler.
            self.enroll_key = cle_aleatoire()
        elif generation < 2:
            # 1.61.0 : la clé était lisible par tout compte Home Assistant (entité
            # « Clé d'inscription », tableau de bord). On la considère connue.
            self.enroll_key = cle_aleatoire()
            self.cle_changee = True
        elif generation < 3 and cle_faible(self.enroll_key):
            # 2.2.12 : un exemple publié (« -Cle … » de l'aide) ou une clé faible
            # est remplacé une fois. Les appareils inscrits gardent leur secret.
            self.enroll_key_ancienne = self.enroll_key
            self.enroll_key_ancienne_jusqua = time.time() + CLE_GRACE_JOURS * 86400
            self.enroll_key = cle_aleatoire()
            self.cle_changee = True
        if self.enroll_key_ancienne_jusqua < time.time():
            # Sursis échu : l'ancienne clé n'a plus à rester dans le magasin.
            self.enroll_key_ancienne, self.enroll_key_ancienne_jusqua = "", 0.0
        self.enroll_open =bool(data.get("enroll_open", True))
        self.enroll_jusqua = float(data.get("enroll_jusqua") or 0)
        # 1.69.0 : rôle et biométrie par appareil (au lieu de l'étiquette « Parents » et du réglage global).
        self.roles_migres = bool(data.get("roles_migres", False))
        self.vocabulaire_neutre = bool(data.get("vocabulaire_neutre", False))
        self.entites_reduites = bool(data.get("entites_reduites", False))
        self.lieu = str(data.get("lieu") or "")
        m = data.get("musique") if isinstance(data.get("musique"), dict) else {}
        enc = m.get("enceintes")
        self.musique = {"enceintes": [str(x) for x in enc] if isinstance(enc, list) else None,
                        "masquer_hors_ligne": bool(m.get("masquer_hors_ligne"))}
        if m.get("invites_mode") in ("validation", "ensuite", "direct"):
            self.musique["invites_mode"] = m["invites_mode"]
        self.personnes_affichage = [str(p) for p in (data.get("personnes_affichage") or []) if p]
        self.appareils_essai = [str(p) for p in (data.get("appareils_essai") or []) if p]
        accueil = data.get("accueil")
        self.accueil = {str(k): str(v) for k, v in accueil.items()} if isinstance(accueil, dict) else {}
        mots = data.get("mots_vocabulaire")
        self.mots_vocabulaire = {
            str(lg): {str(c): str(v) for c, v in m.items() if isinstance(v, str) and v.strip()}
            for lg, m in (mots.items() if isinstance(mots, dict) else ()) if isinstance(m, dict)
        }
        self.mots_vocabulaire = {lg: m for lg, m in self.mots_vocabulaire.items() if m}
        self.modules = {str(k): bool(v) for k, v in dict(data.get("modules") or {}).items()}
        self.reveils = {str(k): dict(v) for k, v in dict(data.get("reveils") or {}).items() if isinstance(v, dict)}
        self.reveil_reglages = dict(data.get("reveil_reglages") or {}) if isinstance(data.get("reveil_reglages"), dict) else {}
        self.roles_reveil = bool(data.get("roles_reveil", False))
        self.signature_tableau = str(data.get("signature_tableau") or "")
        self.code_gestion = dict(data.get("code_gestion") or {})
        self.codes_decideurs = {str(k): dict(v) for k, v in dict(data.get("codes_decideurs") or {}).items()
                                if str(k).startswith("person.") and isinstance(v, dict)}
        self.codes_decideurs_echecs = [float(t) for t in (data.get("codes_decideurs_echecs") or []) if isinstance(t, (int, float))]
        self.pause_generale = data.get("pause_generale") if isinstance(data.get("pause_generale"), dict) else None
        # 1.95.0 : l'apparence du tableau de bord (palette, mode, accent).
        self.apparence = dict(data.get("apparence") or {}) if isinstance(data.get("apparence"), dict) else {}
        # 1.91.0 : règles selon le lieu.
        self.regles_lieu = [dict(r) for r in (data.get("regles_lieu") or []) if isinstance(r, dict)]
        # 1.90.0 : les compteurs du bilan de la semaine (demandes, accès accordés / refusés, temps ajouté).
        self.bilan_compteurs = {str(k): dict(v) for k, v in dict(data.get("bilan_compteurs") or {}).items() if isinstance(v, dict)}
        # 1.89.0 : temps gagné (réglage par personne, et le compte du jour).
        self.temps_gagne = {str(k): dict(v) for k, v in dict(data.get("temps_gagne") or {}).items() if isinstance(v, dict)}
        self.temps_gagne_suivi = {str(k): dict(v) for k, v in dict(data.get("temps_gagne_suivi") or {}).items() if isinstance(v, dict)}
        # 1.88.0 : le dernier choix d'applis du mode concentration, par personne (ou par appareil sans personne).
        self.concentration_choix = {str(k): [str(a) for a in (v or [])]
                                    for k, v in dict(data.get("concentration_choix") or {}).items()}
        self.entites_personnes = {str(k): [str(e) for e in (v or [])]
                                  for k, v in dict(data.get("entites_personnes") or {}).items()}
        self.entites_appareils = {str(k): {"plus": [str(e) for e in (dict(v or {}).get("plus") or [])],
                                           "moins": [str(e) for e in (dict(v or {}).get("moins") or [])]}
                                  for k, v in dict(data.get("entites_appareils") or {}).items()}
        self.entites_actions = {str(k): [str(e) for e in (v or [])]
                                for k, v in dict(data.get("entites_actions") or {}).items()}
        self.entites_tous = [str(e) for e in (data.get("entites_tous") or []) if isinstance(e, str)]
        self.entites_reglages = {str(k): dict(v) for k, v in dict(data.get("entites_reglages") or {}).items()
                                 if isinstance(v, dict)}
        self.types_messages = [dict(t) for t in (data.get("types_messages") or []) if isinstance(t, dict)]
        self.envois_messages = [dict(e) for e in (data.get("envois_messages") or []) if isinstance(e, dict)][-30:]
        # 2.4.2 : « veille_pc » (interrupteur de la veille des PC, retirée) est ignoré s'il est encore enregistré.
        self.notif_decideurs = [str(s) for s in (data.get("notif_decideurs") or []) if isinstance(s, str)]
        from . import canaux as _canaux

        self.canaux_notif = _canaux.nettoyer(data.get("canaux_notif"))
        self.discord = {k: str(v) for k, v in dict(data.get("discord") or {}).items() if k in ("service", "cible")}
        self.soutien = dict(data.get("soutien") or {})
        self.roles = dict(data.get("roles") or {})
        self.roles_personnes = dict(data.get("roles_personnes") or {})
        self.auto_update = bool(data.get("auto_update", True))
        self.veille_rendu = bool(data.get("veille_rendu", False))
        self.desinstallation = bool(data.get("desinstallation", False))
        self.warn_minutes = int(data.get("warn_minutes", WARN_MINUTES))
        self.idle_minutes = int(data.get("idle_minutes", IDLE_MINUTES))
        self.delai_refus = int(data.get("delai_refus", DELAI_REFUS))
        self.retraits = data.get("retraits") or {}
        etiquettes = data.get("etiquettes") or {}
        self.etiquettes = {
            "apps": dict(etiquettes.get("apps") or {}),
            "sites": dict(etiquettes.get("sites") or {}),
        }
        self.bannis = [
            str(v).strip() for v in (data.get("bannis") or []) if str(v).strip()
        ]
        self.autorises_maison = [
            str(v).strip()
            for v in (data.get("autorises_maison") or [])
            if str(v).strip()
        ]
        self.categories_choix = {
            str(k): bool(v) for k, v in (data.get("categories_choix") or {}).items()
        }
        self.fermetures = {}
        for personne, fiches in (data.get("fermetures") or {}).items():
            if not isinstance(fiches, dict):
                continue
            rangees: dict[str, dict[str, Any]] = {}
            for cle, fiche in fiches.items():
                if not isinstance(fiche, dict):
                    continue
                fiche = dict(fiche)
                if "|" not in str(cle):
                    fiche["source"] = ""
                fiche["etiquette"] = str(fiche.get("etiquette") or str(cle).split("|")[0])
                rangees[_cle_fermeture(fiche["etiquette"], fiche["source"])] = fiche
            self.fermetures[str(personne)] = rangees
        # Refonte des autorisations (CONCEPTION-autorisations.md). Les trois modes s'écrivent dans les champs de
        # toujours : « exception » = `autorises` (recopié sur tous les appareils de la personne), « toujours
        # disponible » = `toujours` (applis) ou `toujours_sites`, lus pour toute la personne ; « pour un temps » =
        # `laissez_passer` (cet appareil). Le nouveau moteur de décision ne s'applique qu'une fois la reprise
        # validée par un Décideur (lot 2) ; une installation neuve n'a rien à reprendre.
        if "autorisations_v2" in data:
            self.autorisations_v2 = bool(data.get("autorisations_v2"))
        else:
            self.autorisations_v2 = not (data.get("pcs") or {})
        # Lot 3 (fiches logiciels) : les sites liés d'une appli validés par un Décideur, en plus de la table connue.
        self.domaines_lies = {
            str(app): [str(d) for d in doms if _nom_site(str(d))]
            for app, doms in (data.get("domaines_lies") or {}).items() if isinstance(doms, list)
        }
        # 2.5.1 : les sites liés tout seuls (« suit » : il suit le logiciel ; sinon il était fermé au moment du lien et
        # attend un geste du Décideur), et ceux qu'un Décideur a déliés à la main (jamais reliés tout seuls).
        self.domaines_auto = {
            str(app): {str(d): dict(f) for d, f in doms.items() if _nom_site(str(d)) and isinstance(f, dict)}
            for app, doms in (data.get("domaines_auto") or {}).items() if isinstance(doms, dict)
        }
        self.domaines_delies = {
            str(app): [str(d) for d in doms if _nom_site(str(d))]
            for app, doms in (data.get("domaines_delies") or {}).items() if isinstance(doms, list)
        }
        self.reprise_autorisations = data.get("reprise_autorisations") if isinstance(
            data.get("reprise_autorisations"), dict) else None
        self.veille_entites = [
            str(v).strip() for v in (data.get("veille_entites") or []) if str(v).strip()
        ]
        self.veille_courbe = [
            str(v).strip() for v in (data.get("veille_courbe") or []) if str(v).strip()
        ]
        self.veille_cameras = [
            str(v).strip() for v in (data.get("veille_cameras") or []) if str(v).strip().startswith("camera.")
        ]
        self.veille_flux_suffixe = str(data.get("veille_flux_suffixe") or "").strip()
        self.veille_musique = str(data.get("veille_musique") or "").strip()
        self.veille_theme = str(data.get("veille_theme") or "Beige")
        self.veille_nuit = str(data.get("veille_nuit") if data.get("veille_nuit") is not None else "23:00-07:00")
        self.veille_tableaux = [t for t in (data.get("veille_tableaux") or []) if isinstance(t, dict)]
        self.veille_dash = dict(data.get("veille_dash") or {})
        choix = str(data.get("dns_filtrant") or DNS_AUCUN)
        self.dns_filtrant = choix if choix in DNS_FILTRANTS else DNS_AUCUN
        self.vocabulaire = []
        for nom in data.get("vocabulaire") or []:
            self.declarer_etiquette(nom)
        # Lot 1 : la catégorie dédiée à l'école existe d'emblée (son nom suit la langue, i18n/categories.json).
        self.declarer_etiquette(ETIQUETTE_ECOLE)
        ajouts = data.get("ajouts") or {}
        self.ajouts = {
            "apps": [_nom_app(n) for n in (ajouts.get("apps") or []) if _nom_app(n)],
            "sites": [_nom_site(n) for n in (ajouts.get("sites") or []) if _nom_site(n)],
        }
        self.etiquettes["sites"] = _replier_etiquettes(self.etiquettes["sites"])
        # Reprise 1.45.0 : les étiquettes déjà posées deviennent de vraies
        # étiquettes Home Assistant. Sans cela, « Jeux » aurait classé des
        # sites sans exister au sélecteur, et on aurait dû la retaper.
        for genre in ("apps", "sites"):
            for valeurs in self.etiquettes[genre].values():
                for valeur in valeurs:
                    self.declarer_etiquette(valeur)
        self.purger_retraits()
        for pc in self.pcs.values():
            self._completer(pc)
            self._replier_regles(pc)

    # Ce qui est décidé pour une personne vaut sur tous ses appareils :
    # son téléphone et son PC ont le même niveau d'autorisation. Le temps
    # accordé et les laissez-passer, eux, restent propres à chaque appareil.
    CHAMPS_PERSONNE = ("bloques", "autorises", "exceptions", "rules", "limites")

    def _empreinte_regles(self, pc: dict[str, Any]) -> str:
        brut = json.dumps({c: pc.get(c) for c in self.CHAMPS_PERSONNE}, sort_keys=True, default=str)
        return hashlib.sha1(brut.encode("utf-8")).hexdigest()

    def harmoniser_personnes(self) -> bool:
        """Recopie les règles entre les appareils d'une même personne.

        L'appareil dont les règles ont changé depuis la dernière sauvegarde
        est la source ; les autres s'alignent. Un appareil nouveau, ou qui
        vient de changer de personne, hérite de celui qui a le plus de
        règles. Rend True si quelque chose a bougé.
        """
        groupes: dict[str, list[dict[str, Any]]] = {}
        for pc in self.pcs.values():
            personne = str(pc.get("person") or "")
            if personne:
                groupes.setdefault(personne, []).append(pc)
        bouge = False
        for personne, membres in groupes.items():
            if len(membres) < 2:
                for pc in membres:
                    pc["regles_hash"] = self._empreinte_regles(pc)
                    pc["regles_personne"] = personne
                continue
            def poids(pc: dict[str, Any]) -> int:
                return sum(len(pc.get(c) or []) for c in ("rules", "bloques", "autorises", "exceptions"))
            anciens = [pc for pc in membres if pc.get("regles_hash") and pc.get("regles_personne") == personne]
            nouveaux = [pc for pc in membres if pc not in anciens]
            changes = [pc for pc in anciens if self._empreinte_regles(pc) != pc["regles_hash"]]
            source = None
            if changes:
                source = max(changes, key=lambda pc: float(pc.get("last_seen") or 0))
            elif nouveaux:
                source = max(anciens or membres, key=poids)
            if source is not None:
                for pc in membres:
                    if pc is source:
                        continue
                    for c in self.CHAMPS_PERSONNE:
                        valeur = copy.deepcopy(source.get(c))
                        if pc.get(c) != valeur:
                            pc[c] = valeur
                            bouge = True
            for pc in membres:
                pc["regles_hash"] = self._empreinte_regles(pc)
                pc["regles_personne"] = personne
        return bouge

    async def async_save(self) -> None:
        # Toute écriture invalide les caches de classement (listes, compteurs, étiquettes).
        self.version_cache = getattr(self, "version_cache", 0) + 1
        self._memos = {}
        self._cache_a_classer = {}
        self._cache_demandeurs = None
        self._cache_etiquettes_bas = None
        try:
            self.harmoniser_personnes()
        except Exception:  # noqa: BLE001 - jamais au prix de la sauvegarde
            pass
        await self._store.async_save(
            {
                "pcs": self.pcs,
                "enroll_key": self.enroll_key,
                "enroll_key_v": ENROLL_KEY_V,
                "enroll_key_ancienne": self.enroll_key_ancienne,
                "enroll_key_ancienne_jusqua": self.enroll_key_ancienne_jusqua,
                "enroll_open": self.enroll_open,
                "enroll_jusqua": self.enroll_jusqua,
                "roles_migres": self.roles_migres,
                "vocabulaire_neutre": self.vocabulaire_neutre,
                "entites_reduites": self.entites_reduites,
                "lieu": self.lieu,
                "musique": getattr(self, "musique", {"enceintes": None, "masquer_hors_ligne": False}),
                "personnes_affichage": self.personnes_affichage,
                "appareils_essai": self.appareils_essai,
                "accueil": self.accueil,
                "mots_vocabulaire": self.mots_vocabulaire,
                "modules": self.modules,
                "reveils": getattr(self, "reveils", {}),
                "reveil_reglages": getattr(self, "reveil_reglages", {}),
                "roles_reveil": getattr(self, "roles_reveil", False),
                "signature_tableau": self.signature_tableau,
                "pause_generale": self.pause_generale,
                "concentration_choix": getattr(self, "concentration_choix", {}),
                "temps_gagne": getattr(self, "temps_gagne", {}),
                "bilan_compteurs": getattr(self, "bilan_compteurs", {}),
                "regles_lieu": getattr(self, "regles_lieu", []),
                "apparence": getattr(self, "apparence", {}),
                "temps_gagne_suivi": getattr(self, "temps_gagne_suivi", {}),
                "entites_personnes": self.entites_personnes,
                "entites_appareils": self.entites_appareils,
                "entites_actions": self.entites_actions,
                "entites_tous": self.entites_tous,
                "entites_reglages": self.entites_reglages,
                "types_messages": self.types_messages,
                "envois_messages": self.envois_messages,
                "notif_decideurs": list(getattr(self, "notif_decideurs", []) or []),
                "canaux_notif": getattr(self, "canaux_notif", {}) or {},
                "discord": getattr(self, "discord", {}) or {},
                "soutien": self.soutien,
                "roles": self.roles,
                "roles_personnes": self.roles_personnes,
                "auto_update": self.auto_update,
                "code_gestion": self.code_gestion,
                "codes_decideurs": getattr(self, "codes_decideurs", {}) or {},
                "codes_decideurs_echecs": list(getattr(self, "codes_decideurs_echecs", []) or []),
                "veille_rendu": self.veille_rendu,
                "desinstallation": self.desinstallation,
                "warn_minutes": self.warn_minutes,
                "idle_minutes": self.idle_minutes,
                "delai_refus": self.delai_refus,
                "retraits": self.retraits,
                "etiquettes": self.etiquettes,
                "ajouts": self.ajouts,
                "vocabulaire": self.vocabulaire,
                "dns_filtrant": self.dns_filtrant,
                "bannis": self.bannis,
                "autorises_maison": self.autorises_maison,
                "categories_choix": self.categories_choix,
                "domaines_lies": getattr(self, "domaines_lies", {}),
                "domaines_auto": getattr(self, "domaines_auto", {}),
                "domaines_delies": getattr(self, "domaines_delies", {}),
                "fermetures": getattr(self, "fermetures", {}),
                "autorisations_v2": getattr(self, "autorisations_v2", False),
                "reprise_autorisations": getattr(self, "reprise_autorisations", None),
                "veille_entites": self.veille_entites,
                "veille_courbe": self.veille_courbe,
                "veille_cameras": self.veille_cameras,
                "veille_flux_suffixe": self.veille_flux_suffixe,
                "veille_musique": self.veille_musique,
                "veille_theme": self.veille_theme,
                "veille_nuit": self.veille_nuit,
                "veille_tableaux": self.veille_tableaux,
                "veille_dash": self.veille_dash,
            }
        )

    def _replier_regles(self, pc: dict[str, Any]) -> None:
        """Une règle écrite sur un sous-domaine vaut désormais pour le site.

        Se fait au chargement, une fois les étiquettes connues : elles, on
        n'y touche pas, même si quelqu'un a nommé une étiquette « ma.regle ».
        """
        # Les applis connues (catalogue, relevés des appareils) : un paquet Android a des points
        # (« com.snapchat.android ») sans être un site, on ne le replie donc pas.
        applis: dict[str, str] = {}
        try:
            for n in (self.etiquettes.get("apps") or {}):
                applis.setdefault(str(n).lower(), str(n))
            for autre in [pc, *list((getattr(self, "pcs", None) or {}).values())]:
                for n in (autre.get("apps_vues") or {}):
                    applis.setdefault(str(n).lower(), str(n))
            sites_connus = {str(n).lower() for n in (self.etiquettes.get("sites") or {})}
        except Exception:  # noqa: BLE001 - sans catalogue, on replie comme avant
            applis, sites_connus = {}, set()
        for champ in ("bloques", "autorises"):
            garde: list[str] = []
            for valeur in pc.get(champ) or []:
                valeur = str(valeur).strip()
                if not valeur:
                    continue
                if valeur.lower() in applis:
                    pass
                elif "." in valeur and not self._est_etiquette(valeur):
                    # Une règle déjà abîmée (« snapchat.android ») retrouve son appli quand une seule lui correspond.
                    if valeur.lower() not in sites_connus:
                        retrouvees = [a for bas, a in applis.items() if bas.count(".") >= 2 and (_nom_site(bas) or "") == valeur.lower()]
                        if len(retrouvees) == 1:
                            valeur = retrouvees[0]
                            if valeur.lower() not in [g.lower() for g in garde]:
                                garde.append(valeur)
                            continue
                    # On ramène la règle au site, famille comprise — mais on
                    # ne la **supprime** jamais, même posée sur ce que la
                    # 1.15.0 considère comme du bruit : c'est une décision
                    # prise par quelqu'un, pas un relevé.
                    valeur = _nom_site(valeur) or valeur
                if valeur.lower() not in [g.lower() for g in garde]:
                    garde.append(valeur)
            pc[champ] = garde
        pc["exceptions"] = sorted(
            {_hote(a) for a in (pc.get("exceptions") or []) if _hote(a)}
        )

    @staticmethod
    def _completer(pc: dict[str, Any]) -> None:
        pc.setdefault("enabled", True)
        pc.setdefault("rules", [])
        pc.setdefault("override", None)
        pc.setdefault("last_seen", 0)
        pc.setdefault("agent", {})
        pc.setdefault("host", "")
        pc.setdefault("person", "")
        pc.setdefault("partage", [])
        pc.setdefault("update_asked", False)
        pc.setdefault("tags", [])
        pc.setdefault("messages", [])
        pc.setdefault("next_message_id", 1)
        # Ce que Vision a constaté : temps par jour, logiciels, sites.
        # Avant la 1.9.0, les blocages étaient deux listes séparées et il
        # n'existait pas d'autorisation : tout ce qui n'était pas listé
        # passait. On reprend l'existant tel quel dans la nouvelle liste.
        if "bloques" not in pc:
            pc["bloques"] = [
                m for m in (pc.get("apps") or []) + (pc.get("sites") or []) if m
            ]
        pc.setdefault("autorises", [])
        pc.pop("apps", None)
        pc.pop("sites", None)
        pc.setdefault("usage", {})
        pc.setdefault("apps_vues", {})
        pc.setdefault("sites_vus", {})
        # Adresses précises épargnées quoi qu'en dise la règle du domaine,
        # et domaines rouverts pour un temps compté.
        pc.setdefault("exceptions", [])
        pc.setdefault("laissez_passer", {})
        # Couleur d'affichage de chaque plage (carte planning) : propre à
        # Home Assistant, l'agent n'en sait rien.
        pc.setdefault("couleurs", {})
        # Tentatives vers un domaine signalé dangereux.
        pc.setdefault("alertes", [])
        # Combien de tentatives vers un site bloqué, par jour. C'est ce qui
        # fait la courbe : le journal dit *quoi*, ceci dit *combien*, et il
        # survit à la purge de l'historique de Home Assistant.
        pc.setdefault("blocages", {})
        pc["apps_vues"] = _replier_apps(pc["apps_vues"])
        pc["sites_vus"] = _replier_sites(pc["sites_vus"])
        pc["rules"] = [normalise_rule(r) for r in pc["rules"]]

    # -------------------------------------------------------------- Accès

    def get(self, pc_id: str) -> dict[str, Any] | None:
        return self.pcs.get(pc_id)

    def by_name(self, nom: str) -> str | None:
        cible = (nom or "").strip().lower()
        for pc_id, pc in self.pcs.items():
            if pc["name"].strip().lower() == cible:
                return pc_id
        return None

    def names(self) -> list[str]:
        return sorted(pc["name"] for pc in self.pcs.values())

    @staticmethod
    def slug(pc: dict[str, Any]) -> str:
        return _slug(pc["name"])

    def by_host(self, host: str) -> str | None:
        """Retrouve un PC déjà inscrit à partir de son nom de machine."""
        cible = (host or "").strip().lower()
        if not cible:
            return None
        for pc_id, pc in self.pcs.items():
            if str(pc.get("host") or "").strip().lower() == cible:
                return pc_id
        return None

    def nouvelle_cle(self, cle: str = "") -> str:
        """Change la clé d'inscription : celle donnée, sinon une neuve."""
        cle = str(cle or "").strip()
        if not cle:
            cle = cle_aleatoire()
        elif len(cle) > 64 or any(c.isspace() for c in cle) or cle_faible(cle):
            raise textes.Erreur("store.cle_invalide")
        self.enroll_key = cle
        # Une clé changée à la main : l'ancienne ne vaut plus, même en sursis.
        self.enroll_key_ancienne = ""
        self.enroll_key_ancienne_jusqua = 0.0
        return cle

    def ouvrir_reinscription(self, pc_id: str, minutes: int = REINSCRIPTION_MINUTES) -> None:
        """Un administrateur accepte qu'un appareil déjà inscrit se réinscrive.

        Il reçoit alors un secret **neuf** ; l'ancien n'est jamais redonné.
        """
        pc = self.pcs.get(pc_id)
        if pc is None:
            raise textes.Erreur("commun.appareil_inconnu")
        pc["reinscription_jusqua"] = time.time() + minutes * 60

    def reinscrire(self, pc_id: str) -> str | None:
        """Le secret neuf d'un appareil autorisé à se réinscrire, sinon None."""
        pc = self.pcs.get(pc_id)
        if pc is None or float(pc.get("reinscription_jusqua") or 0) < time.time():
            return None
        pc.pop("reinscription_jusqua", None)
        pc["secret"] = secrets.token_hex(16)
        return pc["secret"]

    def inscriptions_ouvertes(self) -> bool:
        """Ouvertes pour de bon, ou pendant une ouverture temporaire pas encore échue."""
        return self.enroll_open or float(self.enroll_jusqua or 0) > time.time()

    def check_enroll_key(self, cle: str) -> bool:
        if not self.inscriptions_ouvertes() or not self.enroll_key:
            return False
        return secrets.compare_digest(self.enroll_key, str(cle or ""))

    def check_ancienne_cle(self, cle: str, host: str, uid: str = "") -> bool:
        """L'ancienne clé (remplacée car faible) pendant son sursis, pour un appareil déjà connu.

        Elle ne crée jamais de fiche : l'appareil retombe ensuite dans le chemin
        ordinaire de la réinscription, qu'un administrateur doit permettre.
        """
        if not self.inscriptions_ouvertes() or not self.enroll_key_ancienne:
            return False
        if self.enroll_key_ancienne_jusqua < time.time():
            return False
        if not secrets.compare_digest(self.enroll_key_ancienne, str(cle or "")):
            return False
        return self.appareil_connu(host, uid)

    def appareil_connu(self, host: str, uid: str = "") -> bool:
        """Vrai si enroll() retrouverait une fiche existante (sans rien créer)."""
        host = (host or "").strip()
        if uid and self.by_uid(uid):
            return True
        existant = self.by_host(host) if host else None
        if not existant:
            return False
        return not uid or not self.pcs[existant].get("uid")

    def by_uid(self, uid: str) -> str | None:
        """Retrouve la fiche d'un appareil par son identifiant propre."""
        if not uid:
            return None
        for pc_id, pc in self.pcs.items():
            if pc.get("uid") == uid:
                return pc_id
        return None

    def nouvelle_fiche(self, host: str, nom: str = "", uid: str = "", user: str = "") -> str:
        """Une fiche neuve pour un appareil, même si un autre porte déjà ce nom de machine."""
        base = (nom or host or "Appareil").strip()
        libelle, suffixe = base, 2
        while self.by_name(libelle) is not None:
            libelle = f"{base} ({suffixe})"
            suffixe += 1
        pc_id = self.create_pc(libelle)
        # Le nom de machine d'une fiche à uid n'est jamais réutilisé par un autre appareil.
        self.pcs[pc_id]["host"] = libelle if self.by_host(host) else host
        self.pcs[pc_id]["uid"] = uid
        self.pcs[pc_id]["agent"] = {"user": user}
        # Le nom de compte vient de l'appareil : une proposition, qu'un parent confirme.
        self.pcs[pc_id]["personne_proposee"] = self.devine_personne(user) or ""
        return pc_id

    def enroll(self, host: str, user: str = "", uid: str = "", nom: str = "") -> tuple[str, bool]:
        """Inscrit un PC (ou retrouve celui déjà inscrit).

        Renvoie l'identifiant et un booléen indiquant s'il vient d'être créé.
        Un PC fraîchement inscrit n'a aucune plage et reste ouvert : installer
        l'agent ne doit jamais verrouiller quelqu'un par surprise.
        """
        host = (host or "").strip()
        if not host:
            raise textes.Erreur("store.machine_manquante")
        if uid:
            a_lui = self.by_uid(uid)
            if a_lui:
                return a_lui, False
        existant = self.by_host(host)
        if existant:
            pris = self.pcs[existant].get("uid")
            if not uid or not pris:
                # Rien ne s'écrit sur une fiche existante tant qu'un
                # administrateur n'a pas permis la réinscription.
                return existant, False
            # Même modèle, autre appareil : sa propre fiche.
            return self.nouvelle_fiche(host, nom, uid, user), True
        nom = host
        suffixe = 2
        while self.by_name(nom) is not None:
            nom = f"{host} ({suffixe})"
            suffixe += 1
        pc_id = self.create_pc(nom)
        self.pcs[pc_id]["host"] = host
        self.pcs[pc_id]["agent"] = {"user": user}
        self.pcs[pc_id]["personne_proposee"] = self.devine_personne(user) or ""
        return pc_id, True

    def devine_personne(self, user: str) -> str | None:
        """Rapproche un compte Windows d'une personne de la maison.

        « lou » ou « PC-MAISON\\Lou » correspond à la personne « Lou ».
        """
        compte = (user or "").split("\\")[-1].strip().lower()
        if not compte:
            return None
        for etat in self.hass.states.async_all("person"):
            nom = str(etat.attributes.get("friendly_name") or "").strip().lower()
            if not nom:
                continue
            if compte == nom or compte == nom.split()[0]:
                return etat.entity_id
        return None

    def by_secret(self, pc_id: str, secret: str) -> dict[str, Any] | None:
        pc = self.pcs.get(pc_id)
        if pc and secrets.compare_digest(str(pc.get("secret", "")), secret):
            return pc
        return None

    # ------------------------------------------------------------ Écriture

    def create_pc(self, nom: str) -> str:
        nom = (nom or "").strip()
        if not nom:
            raise textes.Erreur("store.nom_appareil")
        if self.by_name(nom) is not None:
            raise textes.Erreur("store.nom_pris", nom=nom)
        pc_id = secrets.token_hex(6)
        self.pcs[pc_id] = {
            "id": pc_id,
            "name": nom,
            "secret": secrets.token_hex(16),
            "enabled": True,
            "rules": [],
            "bloques": [],
            "autorises": [],
            "override": None,
            "last_seen": 0,
            "agent": {},
            "host": "",
            "person": "",
            "update_asked": False,
            "tags": [],
            "messages": [],
            "next_message_id": 1,
        }
        # Les champs garantis, tout de suite. Le rechargement qui suit une
        # création les aurait posés — mais un agent qui interroge dans cette
        # fenêtre-là tombait sur un KeyError et recevait un 500.
        self._completer(self.pcs[pc_id])
        return pc_id

    def rename_pc(self, pc_id: str, nom: str) -> None:
        """Renomme un PC. Le nom de machine, lui, ne change pas."""
        nom = (nom or "").strip()
        if not nom:
            raise textes.Erreur("store.nom_appareil")
        autre = self.by_name(nom)
        if autre is not None and autre != pc_id:
            raise textes.Erreur("store.nom_pris", nom=nom)
        pc = self.pcs.get(pc_id)
        if pc is None:
            raise textes.Erreur("commun.appareil_disparu")
        pc["name"] = nom

    # --------------------------------------------------------- Messages

    # ------------------------------------------------------- Étiquettes

    def tags(self) -> list[str]:
        """Étiquettes Home Assistant portées par au moins un PC."""
        return labels.posees(self.hass, self.pcs)

    def tags_du_pc(self, pc_id: str) -> list[str]:
        return labels.noms_du_pc(self.hass, pc_id)

    def set_tags(self, pc_id: str, etiquettes: list[str]) -> None:
        if pc_id not in self.pcs:
            raise textes.Erreur("commun.appareil_disparu")
        labels.definir(self.hass, pc_id, etiquettes)

    def destinataires(self, cible: str | None) -> list[str]:
        """Identifiants des PC visés par un destinataire.

        Accepte « Tous les PC », « Étiquette : xxx », une étiquette seule,
        ou le nom d'un PC. Sans cible, tous les PC.
        """
        # L'ancien libellé (« Tous les PC ») reste compris : des automatisations l'ont peut-être écrit.
        if not cible or cible in (TOUS_LES_PC, "Tous les PC"):
            return list(self.pcs)
        if cible.startswith(PREFIXE_TAG):
            cible = cible[len(PREFIXE_TAG):].strip()
        pc_id = self.by_name(cible)
        if pc_id is not None:
            return [pc_id]
        vises = labels.pcs_avec(self.hass, self.pcs, cible)
        if not vises:
            raise textes.Erreur("store.destinataire_inconnu", cible=cible)
        return vises

    def add_message(
        self,
        pc_id: str,
        texte: str,
        titre: str = "Home Assistant",
        urgent: bool = False,
        son: str = DEFAULT_SOUND,
    ) -> dict[str, Any]:
        """Met un message en attente ; l'agent l'affichera au prochain appel."""
        pc = self.pcs.get(pc_id)
        if pc is None:
            raise textes.Erreur("commun.appareil_disparu")
        texte = (texte or "").strip()
        if not texte:
            raise textes.Erreur("commun.message_vide")
        message = {
            "id": int(pc.get("next_message_id", 1)),
            "title": (titre or "Home Assistant").strip()[:60],
            "text": texte[:500],
            "urgent": bool(urgent),
            "sound": SOUNDS.get(son, SOUNDS[DEFAULT_SOUND])[0],
            "loop": SOUNDS.get(son, SOUNDS[DEFAULT_SOUND])[1],
            "ts": time.time(),
        }
        pc["next_message_id"] = message["id"] + 1
        pc["messages"] = (pc.get("messages") or [])[-(MAX_MESSAGES - 1):]
        pc["messages"].append(message)
        return message

    def take_messages(self, pc: dict[str, Any]) -> list[dict[str, Any]]:
        """Retire et renvoie les messages encore valables."""
        maintenant = time.time()
        # Un message riche (1.67.0) porte son expiration ; les autres vivent MESSAGE_TTL.
        messages = [
            m
            for m in (pc.get("messages") or [])
            if (float(m["expire"]) > maintenant if m.get("expire")
                else maintenant - float(m.get("ts") or 0) <= MESSAGE_TTL)
        ]
        pc["messages"] = []
        return messages

    # -------------------------------------------------------- Présence

    def presence(self, pc: dict[str, Any], en_ligne: bool) -> str:
        """Statut de la machine, à la façon d'une messagerie."""
        if not en_ligne:
            return PRESENCE_HORS_LIGNE
        agent = pc.get("agent") or {}
        if agent.get("locked"):
            return PRESENCE_VERROUILLE
        inactif = agent.get("idle_seconds")
        if inactif is None:
            return PRESENCE_ACTIF
        if float(inactif) >= self.idle_minutes * 60:
            return PRESENCE_INACTIF
        return PRESENCE_ACTIF

    # ------------------------------------------------ Ce qui est constaté

    def compter_usage(
        self, pc: dict[str, Any], presence: str, ecart: float
    ) -> None:
        """Ajoute le temps écoulé au compteur du jour.

        `ecart` est le temps depuis le relevé précédent. Un écart trop grand
        n'est pas du temps passé devant l'écran : c'est un PC éteint ou un
        réseau coupé, et le compter fausserait tout.
        """
        if ecart <= 0 or ecart > USAGE_ECART_MAX:
            return
        if presence in (PRESENCE_HORS_LIGNE, PRESENCE_VERROUILLE):
            return
        jour = dt_util.now().strftime("%Y-%m-%d")
        journee = pc["usage"].setdefault(jour, {"actif": 0, "ouvert": 0})
        journee["ouvert"] = int(journee.get("ouvert", 0) + ecart)
        if presence == PRESENCE_ACTIF:
            journee["actif"] = int(journee.get("actif", 0) + ecart)
            # Heure par heure : c'est ce qui montre quand l'appareil sert vraiment.
            heures = journee.setdefault("heures", {})
            heure = dt_util.now().strftime("%H")
            heures[heure] = int(heures.get(heure, 0) + ecart)
        self._purger_usage(pc)

    @staticmethod
    def _purger_usage(pc: dict[str, Any]) -> None:
        jours = sorted(pc["usage"])
        for vieux in jours[:-USAGE_JOURS]:
            pc["usage"].pop(vieux, None)

    def usage_du_jour(self, pc: dict[str, Any], decalage: int = 0) -> dict[str, int]:
        jour = (dt_util.now() - timedelta(days=decalage)).strftime("%Y-%m-%d")
        brut = pc.get("usage", {}).get(jour) or {}
        return {
            "actif": int(brut.get("actif", 0)),
            "ouvert": int(brut.get("ouvert", 0)),
        }

    def usage_semaine(self, pc: dict[str, Any]) -> dict[str, int]:
        total = {"actif": 0, "ouvert": 0}
        for jour in range(7):
            journee = self.usage_du_jour(pc, jour)
            total["actif"] += journee["actif"]
            total["ouvert"] += journee["ouvert"]
        return total

    def noter_app(self, pc: dict[str, Any], nom: str, secondes: float) -> None:
        """Retient un logiciel vu au premier plan, et le temps qu'il y a passé."""
        if est_bruit_app(nom):
            return
        nom = _nom_app(nom)
        if not nom:
            return
        fiche = pc["apps_vues"].get(nom)
        if fiche is None:
            if len(pc["apps_vues"]) >= CATALOGUE_MAX_APPS:
                return
            fiche = {"premier": time.time(), "secondes": 0}
            pc["apps_vues"][nom] = fiche
        fiche["dernier"] = time.time()
        if 0 < secondes <= USAGE_ECART_MAX:
            fiche["secondes"] = int(fiche.get("secondes", 0) + secondes)
            # Et le même temps, rangé dans la journée : « combien de Roblox aujourd'hui ? ».
            try:
                journee = pc.setdefault("usage", {}).setdefault(dt_util.now().strftime("%Y-%m-%d"), {"actif": 0, "ouvert": 0})
                par_app = journee.setdefault("apps", {})
                par_app[nom] = int(par_app.get(nom, 0) + secondes)
            except Exception:  # noqa: BLE001 - le cumul reste juste même si le détail du jour échoue
                pass

    def noter_libelles(self, pc: dict[str, Any], libelles: dict[str, Any]) -> None:
        """Nom affiché des applis Android (« YouTube »), le paquet restant la clé."""
        vues = pc.setdefault("apps_vues", {})
        for brut, lib in list(libelles.items())[:300]:
            nom = _nom_app(str(brut or ""))
            lib = str(lib or "").strip()[:60]
            if nom and lib and nom in vues:
                vues[nom]["libelle"] = lib

    def noter_inventaire(self, pc: dict[str, Any], apps: Any) -> None:
        """Applis installées sur un appareil Android, même jamais ouvertes.

        Un contrôle parental doit permettre de bloquer une appli avant
        qu'elle ne serve. Elles entrent au catalogue sans temps d'usage.
        """
        vues = pc.setdefault("apps_vues", {})
        maintenant = time.time()
        for brut in list(apps or [])[:400]:
            if not isinstance(brut, dict):
                continue
            nom = _nom_app(str(brut.get("pkg") or ""))
            if not nom or est_bruit_app(nom):
                continue
            fiche = vues.get(nom)
            if fiche is None:
                if len(vues) >= CATALOGUE_MAX_APPS:
                    continue
                fiche = {"premier": maintenant, "dernier": maintenant, "secondes": 0}
                vues[nom] = fiche
            fiche["installee"] = True
            lib = str(brut.get("label") or "").strip()[:60]
            if lib:
                fiche["libelle"] = lib

    def oublier_bruit(self, pc: dict[str, Any]) -> None:
        """Retire du catalogue ce qui n'aurait jamais dû y entrer."""
        vues = pc.get("apps_vues") or {}
        for nom in [n for n in vues if est_bruit_app(n)]:
            vues.pop(nom, None)

    def noter_app_ouverte(self, pc: dict[str, Any], nom: str) -> None:
        """Retient un logiciel simplement ouvert, sans lui compter de temps."""
        if est_bruit_app(nom):
            return
        nom = _nom_app(nom)
        if not nom or nom in pc["apps_vues"]:
            if nom:
                pc["apps_vues"][nom]["dernier"] = time.time()
            return
        if len(pc["apps_vues"]) >= CATALOGUE_MAX_APPS:
            return
        pc["apps_vues"][nom] = {
            "premier": time.time(),
            "dernier": time.time(),
            "secondes": 0,
        }

    def noter_origines(self, pc: dict[str, Any], origines: dict) -> None:
        """Retient quel programme parlait à quel domaine.

        L'agent croise le cache DNS et les connexions ouvertes ; il ne voit
        donc que ce qui durait au moment du relevé. On accumule sans
        écraser : un site ouvert hier par un programme et aujourd'hui par
        un autre garde les deux, avec la date du dernier passage.

        Rien n'est créé ici : si le domaine n'est pas déjà au catalogue,
        c'est que `noter_site` l'a écarté — bruit, ou liste noire — et une
        origine ne doit pas le faire entrer par la fenêtre.
        """
        if not isinstance(origines, dict):
            return
        for adresse, programmes in list(origines.items())[:120]:
            domaine = _nom_site(_hote(str(adresse)))
            fiche = (pc.get("sites_vus") or {}).get(domaine)
            if not domaine or fiche is None:
                continue
            connues = fiche.setdefault("origines", {})
            for programme in list(programmes or [])[:MAX_ORIGINES]:
                nom = _nom_app(str(programme))
                if not nom or est_bruit_app(nom):
                    continue
                if nom not in connues and len(connues) >= MAX_ORIGINES:
                    continue
                trace = connues.setdefault(nom, {"fois": 0})
                trace["fois"] = int(trace.get("fois", 0)) + 1
                trace["dernier"] = time.time()

    def noter_signatures(self, pc: dict[str, Any], signatures: dict) -> None:
        """Retient qui a signé chaque programme, et d'où il tourne.

        Comme pour les origines : rien n'est créé ici. Un programme que le
        catalogue a écarté comme bruit ne doit pas entrer par cette porte.
        """
        if not isinstance(signatures, dict):
            return
        for brut, fiche in list(signatures.items())[:120]:
            nom = _nom_app(str(brut))
            connue = (pc.get("apps_vues") or {}).get(nom)
            if not nom or connue is None or not isinstance(fiche, dict):
                continue
            connue["signature"] = {
                "signataire": str(fiche.get("signataire") or "")[:80],
                "statut": str(fiche.get("statut") or "")[:32],
                "ou": str(fiche.get("ou") or "")[:32],
                "empreinte": str(fiche.get("empreinte") or "")[:64].lower(),
            }

    def signature_de(self, nom: str) -> dict[str, str]:
        """Ce qu'on sait de la signature de ce programme, tous PC confondus.

        La première qu'on trouve suffit : un même exécutable est signé de
        la même façon sur toutes les machines. Si elles divergeaient, c'est
        un tout autre problème que celui-ci.
        """
        cible = _nom_app(nom)
        for pc in self.pcs.values():
            fiche = (pc.get("apps_vues") or {}).get(cible) or {}
            if signature := fiche.get("signature"):
                return dict(signature)
        return {}

    def empreinte_de(self, nom: str) -> str:
        """L'empreinte SHA-256 de ce programme, si un agent l'a calculée."""
        return str(self.signature_de(nom).get("empreinte") or "")

    def a_interroger(self) -> list[tuple[str, str]]:
        """Les programmes à soumettre, les moins rassurants d'abord.

        Quatre requêtes par minute : l'ordre n'est pas un détail. Un
        programme non signé lancé depuis les téléchargements passe avant
        un exécutable signé par Microsoft, parce que c'est de celui-là
        qu'on attend quelque chose.
        """
        candidats: list[tuple[int, str, str]] = []
        for nom in self.catalogue_maison("apps"):
            empreinte = self.empreinte_de(nom)
            if not empreinte:
                continue
            signature = self.signature_de(nom)
            urgence = 0
            if (signature.get("statut") or "") != "Valid":
                urgence -= 2
            if (signature.get("ou") or "") in ("Telechargements", "TEMP", "AppData",
                                               "Public"):
                urgence -= 1
            candidats.append((urgence, nom, empreinte))
        candidats.sort(key=lambda c: (c[0], c[1]))
        return [(nom, empreinte) for _u, nom, empreinte in candidats]

    def confiance_logiciel(self, nom: str) -> str:
        """Ce qu'on affiche dans la colonne, en toutes lettres.

        Trois cas seulement, parce qu'il n'y en a que trois qui se
        distinguent honnêtement : signé par quelqu'un de nommé, non signé,
        ou pas encore vu. L'emplacement s'ajoute quand il dit quelque
        chose — un programme non signé lancé depuis les téléchargements
        n'est pas la même chose qu'un pilote non signé de Windows.
        """
        signature = self.signature_de(nom)
        if not signature:
            return "—"
        statut = signature.get("statut") or ""
        ou = signature.get("ou") or ""
        signataire = signature.get("signataire") or ""
        if statut == "Valid" and signataire:
            detail = signataire
        elif statut in ("NotSigned", ""):
            detail = textes.t("store.non_signe", textes.ha(self.hass))
        elif statut == "illisible":
            detail = textes.t("store.illisible", textes.ha(self.hass))
        else:
            detail = textes.t("store.signature", textes.ha(self.hass), statut=statut.lower())
        if ou and ou not in ("ailleurs", "Windows", "Program Files"):
            return f"{detail} — {ou}"
        return detail

    def origines_de(self, domaine: str) -> list[str]:
        """Les programmes vus parler à ce domaine, le plus récent d'abord.

        Tous PC confondus : la question « qui a ouvert ça » ne se pose pas
        machine par machine, elle se pose tout court.
        """
        cible = _nom_site(domaine)
        vues: dict[str, float] = {}
        for pc in self.pcs.values():
            fiche = (pc.get("sites_vus") or {}).get(cible) or {}
            for nom, trace in (fiche.get("origines") or {}).items():
                quand = float((trace or {}).get("dernier") or 0)
                if quand > vues.get(nom, 0):
                    vues[nom] = quand
        return sorted(vues, key=lambda n: (-vues[n], n))

    @staticmethod
    def est_intouchable(nom: str) -> bool:
        """Windows lui-même, et Vision. On ne coupe pas la branche."""
        return _nom_app(str(nom)) in APPS_INTOUCHABLES

    def noter_site(self, pc: dict[str, Any], adresse: str) -> None:
        """Retient un site consulté, rangé sous son domaine.

        L'adresse exacte n'entre pas dans la liste — elle est gardée à part,
        au cas où il faille un jour épargner celle-là précisément.
        """
        hote = _hote(adresse)
        if est_bruit_site(hote) or self.est_dangereux(hote):
            return
        domaine = _nom_site(hote)
        if not domaine:
            return
        fiche = pc["sites_vus"].get(domaine)
        if fiche is None:
            if len(pc["sites_vus"]) >= CATALOGUE_MAX_SITES:
                return
            fiche = {"premier": time.time(), "releves": 0, "adresses": []}
            pc["sites_vus"][domaine] = fiche
        fiche["dernier"] = time.time()
        fiche["releves"] = int(fiche.get("releves", 0)) + 1
        adresses = fiche.setdefault("adresses", [])
        garde = hote if hote != domaine else ""
        if garde and garde not in adresses and len(adresses) < MAX_ADRESSES:
            adresses.append(garde)
            adresses.sort()

    def adresses_maison(self, domaine: str) -> list[str]:
        """Adresses précises constatées sous ce site, tous PC confondus.

        C'est là — et seulement là — que les sous-domaines resservent :
        pour désigner celui qu'on veut épargner.
        """
        domaine = _nom_site(domaine)
        if not domaine:
            return []
        vues: set[str] = set()
        for pc in self.pcs.values():
            fiche = (pc.get("sites_vus") or {}).get(domaine) or {}
            vues.update(fiche.get("adresses") or [])
        return sorted(vues)

    def purger_catalogue(self, pc: dict[str, Any]) -> None:
        """Oublie ce qui n'a plus été vu depuis longtemps."""
        limite = time.time() - CATALOGUE_TTL
        for cle in ("apps_vues", "sites_vus"):
            for nom, fiche in list(pc[cle].items()):
                if float(fiche.get("dernier") or 0) < limite:
                    pc[cle].pop(nom, None)

    def _memo(self, cle, ttl, calc):
        """Valeur gardée `ttl` secondes (et oubliée à chaque écriture) : les relevés déclenchent
        des milliers de recalculs identiques dans la même seconde."""
        memos = getattr(self, "_memos", None)
        if memos is None:
            memos = {}
            self._memos = memos
        v = memos.get(cle)
        now = time.monotonic()
        if v is not None and now - v[0] < ttl:
            return v[1]
        r = calc()
        memos[cle] = (now, r)
        if len(memos) > 50000:
            memos.clear()
        return r

    def catalogue_maison(self, genre: str) -> list[str]:
        return list(self._memo(("catalogue", genre), 5.0, lambda: self._catalogue_maison_calc(genre)))

    def _catalogue_maison_calc(self, genre: str) -> list[str]:
        """Tout ce que la maison connaît, PC confondus.

        Un logiciel étiqueté une fois l'est pour toutes les machines : le
        catalogue est donc commun, même si le temps passé, lui, est compté
        par PC. S'y ajoute ce qu'on a inscrit à la main : on n'attend pas
        qu'un site ait été ouvert pour avoir le droit d'en décider.
        """
        champ = "apps_vues" if genre == "apps" else "sites_vus"
        poids: dict[str, int] = {}
        for pc in self.pcs.values():
            for nom, fiche in (pc.get(champ) or {}).items():
                mesure = int(
                    fiche.get("secondes", 0)
                    if genre == "apps"
                    else fiche.get("releves", 0)
                )
                poids[nom] = poids.get(nom, 0) + mesure
        for nom in self.etiquettes.get(genre, {}):
            poids.setdefault(nom, 0)
        for nom in self.ajouts.get(genre) or []:
            poids.setdefault(nom, 0)
        if genre == "sites" and self.dangereux:
            for nom in [n for n in poids if self.est_dangereux(n)]:
                poids.pop(nom, None)
        # Par ordre alphabétique, pas par temps passé : on cherche un nom,
        # et un classement qui bouge à chaque relevé le fait fuir. Le poids
        # reste lisible dans la colonne de mesure.
        return sorted(poids, key=lambda n: (n.lower(), n))

    # -------------------------------------------------- Inscrire à la main

    def _cle(self, genre: str, nom: str) -> str:
        return _nom_app(nom) if genre == "apps" else _nom_site(nom)

    def ajouter_au_catalogue(self, genre: str, nom: str) -> str:
        """Inscrit un site ou un logiciel qu'on n'a pas encore vu passer.

        Attendre qu'un enfant ouvre un site pour avoir le droit d'en
        décider, c'est arriver après. Ici on tranche d'avance, sur un nom
        qu'on est seul à connaître — le reste se comporte exactement comme
        pour ce qui a été constaté : étiquettes, règles, file à classer.

        Rend le nom retenu, qui n'est pas forcément celui qu'on a tapé :
        une adresse devient son site, un exécutable son logiciel.
        """
        cle = self._cle(genre, nom)
        if not cle:
            raise textes.Erreur("store.nom_ajout")
        liste = self.ajouts.setdefault(genre, [])
        if cle not in liste:
            liste.append(cle)
            liste.sort()
        return cle

    def retirer_du_catalogue(self, genre: str, nom: str) -> bool:
        """Retire une inscription faite à la main.

        Seulement une inscription : ce qui a été **constaté** ne s'efface
        pas d'un bouton — la machine l'a vu, il reviendrait au prochain
        relevé. Ses étiquettes et ses règles partent avec, sans quoi on
        garderait des décisions sur un nom devenu invisible.
        """
        cle = self._cle(genre, nom)
        liste = self.ajouts.setdefault(genre, [])
        if cle not in liste:
            return False
        champ = "apps_vues" if genre == "apps" else "sites_vus"
        for pc in self.pcs.values():
            if cle in (pc.get(champ) or {}):
                raise textes.Erreur("store.constate", nom=cle)
        liste.remove(cle)
        self.etiquettes.setdefault(genre, {}).pop(cle, None)
        for pc in self.pcs.values():
            self.oublier_regle(pc, cle)
        return True

    # ------------------------------------------------------------ Alertes

    def noter_alerte(self, pc: dict[str, Any], domaine: str, source: str, app: str = "") -> bool:
        """Retient une tentative vers un domaine dangereux.

        Rend True s'il faut en parler. Un même domaine réessayé dix fois
        dans la minute — un onglet qui recharge — ne fait qu'une alerte :
        prévenir dix fois, c'est apprendre à ne plus lire les alertes.
        """
        domaine = _hote(domaine)
        if not domaine:
            return False
        maintenant = time.time()
        alertes = pc.setdefault("alertes", [])
        for alerte in alertes:
            if alerte.get("domaine") == domaine:
                recent = (maintenant - float(alerte.get("quand") or 0)) < ALERTE_REPOS
                alerte["quand"] = maintenant
                alerte["fois"] = int(alerte.get("fois") or 1) + 1
                alerte["source"] = source or alerte.get("source") or ""
                if app:
                    alerte["app"] = app
                alertes.sort(key=lambda a: -float(a.get("quand") or 0))
                return not recent
        alertes.insert(
            0,
            {
                "domaine": domaine,
                "source": source,
                "quand": maintenant,
                "fois": 1,
                # 2.5.1 : le logiciel au premier plan pendant la tentative (jamais un navigateur) : le site lui est
                # lié tout seul (`lier_auto`) et la liste des blocages récents le nomme.
                **({"app": app} if app else {}),
            },
        )
        del alertes[MAX_ALERTES:]
        return True

    def alertes(self, pc: dict[str, Any]) -> list[dict[str, Any]]:
        return list(pc.get("alertes") or [])

    def compter_blocage(self, pc: dict[str, Any]) -> None:
        """Une tentative de plus aujourd'hui, sur cette machine."""
        jour = dt_util.now().strftime("%Y-%m-%d")
        blocages = pc.setdefault("blocages", {})
        blocages[jour] = int(blocages.get(jour, 0)) + 1
        limite = (
            dt_util.now() - timedelta(days=BLOCAGES_JOURS)
        ).strftime("%Y-%m-%d")
        for cle in [c for c in blocages if c < limite]:
            blocages.pop(cle, None)

    def serie_blocages(self, pc: dict[str, Any]) -> list[tuple[str, int]]:
        """Un mois de comptes, jour par jour, les trous comblés par des zéros.

        Une courbe qui saute les jours vides ment sur la forme : trois
        tentatives lundi et trois le vendredi ne dessinent pas la même chose
        selon qu'on montre ou non les jours sans rien.
        """
        blocages = pc.get("blocages") or {}
        aujourd_hui = dt_util.now()
        serie: list[tuple[str, int]] = []
        for recul in range(BLOCAGES_JOURS - 1, -1, -1):
            jour = (aujourd_hui - timedelta(days=recul)).strftime("%Y-%m-%d")
            serie.append((jour, int(blocages.get(jour, 0))))
        return serie

    def origine(self, genre: str, nom: str) -> str:
        """D'où vient cette ligne : constatée, inscrite, ou ni l'un ni l'autre.

        Le troisième cas est le plus utile : une règle sur un nom qu'aucune
        machine n'a jamais vu et que personne n'a inscrit est presque
        toujours une faute de frappe, ou une étiquette écrite avant
        d'exister. Sans cette colonne, elle reste invisible pour toujours.
        """
        cle = self._cle(genre, nom)
        champ = "apps_vues" if genre == "apps" else "sites_vus"
        if any(cle in (pc.get(champ) or {}) for pc in self.pcs.values()):
            return ORIGINE_CONSTATE
        if cle in (self.ajouts.get(genre) or []):
            return ORIGINE_INSCRIT
        return ORIGINE_JAMAIS

    def bloques(self, pc: dict[str, Any]) -> list[dict[str, str]]:
        """Tout ce qui est bloqué sur ce PC, avec d'où ça vient et pourquoi.

        Les listes de travail ne montrent que ce qui reste à trancher : c'est
        leur raison d'être. Mais il faut aussi pouvoir relire ce qu'on a
        décidé, sans changer de filtre ni chercher — d'où cette vue-là.
        """
        sortie: list[dict[str, str]] = []
        for genre in ("apps", "sites"):
            for nom in self.catalogue_maison(genre):
                if self.statut(pc, genre, nom) != BLOQUE:
                    continue
                sortie.append(
                    {
                        "genre": "logiciel" if genre == "apps" else "site",
                        "nom": nom,
                        "origine": self.origine(genre, nom),
                        "raison": self.raison(pc, genre, nom),
                    }
                )
        # Un nom bloqué que le catalogue ignore doit apparaître quand même,
        # sinon une faute de frappe reste invisible pour toujours.
        connus = {(e["genre"], e["nom"]) for e in sortie}
        for valeur in list(pc.get("bloques") or []) + list(self.bannis):
            valeur = str(valeur).strip()
            if not valeur or self._est_etiquette(valeur):
                continue
            genre = self._genre_de(valeur)
            cle = self._cle(genre, valeur)
            libelle = "logiciel" if genre == "apps" else "site"
            if not cle or (libelle, cle) in connus:
                continue
            sortie.append(
                {
                    "genre": libelle,
                    "nom": cle,
                    "origine": self.origine(genre, cle),
                    "raison": self.raison(pc, genre, cle),
                }
            )
        return sorted(sortie, key=lambda e: (e["genre"], e["nom"]))

    # -------------------------------------------- Plages sur des étiquettes

    def plages_etiquettes(self, pc: dict[str, Any]) -> list[dict[str, Any]]:
        return de_portee(self.regles_pour(pc), PORTEE_ETIQUETTES)

    def plage_ferme(
        self, pc: dict[str, Any], etiquette: str, moment: datetime | None = None
    ) -> dict[str, Any] | None:
        """La plage qui ferme cette étiquette maintenant, s'il y en a une."""
        if not etiquette:
            return None
        moment = moment or self.horloge()
        bas = etiquette.strip().lower()
        for regle in self.plages_etiquettes(pc):
            if not regle.get("enabled", True):
                continue
            if bas not in [e.lower() for e in regle["etiquettes"]]:
                continue
            if rule_covers(regle, moment):
                return regle
        return None

    def plage_nomme(
        self, pc: dict[str, Any], genre: str, nom: str, moment: datetime | None = None
    ) -> dict[str, Any] | None:
        """La plage qui ferme **nommément** cet élément à cette heure.

        Une plage qui cite un nom est ce qu'on peut dire de plus précis sur
        lui : « plus de Discord après 21h » doit tenir même si Discord a
        par ailleurs été autorisé. Elle passe donc avant les listes, et
        seulement derrière les deux portes qu'un adulte ouvre exprès — le
        laissez-passer et l'adresse épargnée.
        """
        cible = self._cle(genre, nom)
        if not cible:
            return None
        moment = moment or self.horloge()
        bas = cible.lower()
        for regle in self.plages_etiquettes(pc):
            if not regle.get("enabled", True):
                continue
            vise = any(
                e.get("genre") == genre
                and self._cle(genre, str(e.get("nom") or "")).lower() == bas
                for e in regle.get("elements") or []
            )
            if vise and rule_covers(regle, moment):
                return regle
        return None

    def _plage_de(
        self, pc: dict[str, Any], genre: str, nom: str, moment: datetime | None = None
    ) -> tuple[dict[str, Any], str] | None:
        """La première étiquette de cet élément fermée par une plage."""
        for etiquette in self.etiquettes_de(genre, nom):
            if regle := self.plage_ferme(pc, etiquette, moment):
                return regle, etiquette
        return None

    def preavis_plages(
        self, pc: dict[str, Any], moment: datetime | None = None
    ) -> list[str]:
        """Ce qui va fermer bientôt, dit à voix haute avant de fermer.

        Une partie coupée en plein milieu, c'est une leçon sur l'arbitraire,
        pas sur l'heure. On prévient donc, avec la même avance que pour le
        verrouillage de la session.
        """
        moment = moment or self.horloge()
        avance = max(0, int(self.warn_minutes))
        if not avance:
            return []
        annonces: list[str] = []
        for regle in self.plages_etiquettes(pc):
            if not regle.get("enabled", True):
                continue
            debut = prochaine_fermeture(regle, moment)
            if debut is None:
                continue
            restant = (debut - moment).total_seconds() / 60
            if 0 < restant <= avance:
                langue = textes.pour(self.hass, pc)
                annonces.append(textes.t("store.fermeture_a", langue, cibles=cibles_label(regle, langue),
                                         heure=textes.heure(debut, langue)))
        return annonces

    def raison(self, pc: dict[str, Any], genre: str, nom: str) -> str:
        """Pourquoi celui-là est bloqué : par son nom, ou par une étiquette ?

        Sans cette colonne, une liste de blocages issue d'étiquettes serait
        incompréhensible — on ne saurait pas où aller la défaire. L'ordre
        suit donc celui de `statut`, sans quoi la carte désignerait une
        règle qui n'est pas celle qui ferme réellement la porte.
        """
        cible = self._cle(genre, nom)
        etiquettes = self.etiquettes_de(genre, nom)
        lg = textes.ha(self.hass)
        if self.est_banni(cible):
            return textes.t("store.r_banni", lg)
        for etiquette in etiquettes:
            if self.est_banni(etiquette):
                return textes.t("store.r_etiquette_bannie", lg, etiquette=textes.categorie(etiquette, lg))
        if (atteinte := self.limite_atteinte(pc, genre, nom)) is not None and not self._laissez_passer(pc, genre, nom):
            return textes.t("store.r_limite", lg, minutes=atteinte[0])
        if regle := self.plage_nomme(pc, genre, nom):
            return textes.t("store.r_nom_plage", lg, plage=regle['name'])
        if self._dans(pc.get("bloques"), cible):
            return textes.t("store.r_nom", lg)
        if trouve := self._plage_de(pc, genre, nom):
            regle, etiquette = trouve
            return textes.t("store.r_etiquette_plage", lg, etiquette=textes.categorie(etiquette, lg), plage=regle['name'])
        for etiquette in etiquettes:
            if self._dans(pc.get("bloques"), etiquette):
                return textes.t("store.r_etiquette", lg, etiquette=textes.categorie(etiquette, lg))
        return textes.t("store.r_regle", lg)

    def est_dangereux(self, nom: str) -> bool:
        """Signalé par une source extérieure. Ni discutable, ni à afficher."""
        return bool(self.dangereux) and _nom_site(nom) in self.dangereux

    def poser_liste_noire(self, domaines) -> bool:
        """Reçoit la liste noire et nettoie ce qu'elle rend inutile.

        Les versions ≤ 1.23.0 laissaient ces domaines entrer au catalogue —
        Windows charge le fichier hosts dans son cache DNS, et nos propres
        blocages en ressortaient comme autant de « sites consultés ». La
        liste de la maison s'est retrouvée noyée. On la remet au propre une
        fois pour toutes, et on empêche que ça recommence.

        Rend True s'il y avait quelque chose à enlever.
        """
        self.dangereux = {d for d in domaines if d}
        if not self.dangereux:
            return False
        bouge = False
        for pc in self.pcs.values():
            for nom in [n for n in (pc.get("sites_vus") or {}) if self.est_dangereux(n)]:
                pc["sites_vus"].pop(nom, None)
                bouge = True
            for champ in ("bloques", "autorises"):
                garde = [
                    v for v in (pc.get(champ) or []) if not self.est_dangereux(v)
                ]
                if len(garde) != len(pc.get(champ) or []):
                    pc[champ] = garde
                    bouge = True
        for liste in ("bannis", "autorises_maison"):
            valeurs = getattr(self, liste)
            garde = [v for v in valeurs if not self.est_dangereux(v)]
            if len(garde) != len(valeurs):
                setattr(self, liste, garde)
                bouge = True
        for genre in ("apps", "sites"):
            for nom in [
                n
                for n in self.etiquettes.get(genre, {})
                if genre == "sites" and self.est_dangereux(n)
            ]:
                self.etiquettes[genre].pop(nom, None)
                bouge = True
        return bouge

    def est_ajout(self, genre: str, nom: str) -> bool:
        """Inscrit à la main et jamais constaté : rien à afficher comme mesure."""
        cle = self._cle(genre, nom)
        if cle not in (self.ajouts.get(genre) or []):
            return False
        champ = "apps_vues" if genre == "apps" else "sites_vus"
        return not any(cle in (pc.get(champ) or {}) for pc in self.pcs.values())

    def temps_maison(self, nom: str) -> int:
        """Temps cumulé passé sur ce logiciel, tous PC confondus."""
        return sum(
            int(((pc.get("apps_vues") or {}).get(nom) or {}).get("secondes", 0))
            for pc in self.pcs.values()
        )

    def releves_maison(self, domaine: str) -> int:
        return sum(
            int(((pc.get("sites_vus") or {}).get(domaine) or {}).get("releves", 0))
            for pc in self.pcs.values()
        )

    def catalogue_apps(self, pc: dict[str, Any]) -> list[str]:
        """Logiciels constatés, les plus utilisés d'abord."""
        return [
            nom
            for nom, _ in sorted(
                pc.get("apps_vues", {}).items(),
                key=lambda x: (-int(x[1].get("secondes", 0)), x[0]),
            )
        ]

    def catalogue_sites(self, pc: dict[str, Any]) -> list[str]:
        """Domaines constatés, les plus revus d'abord."""
        return [
            nom
            for nom, _ in sorted(
                pc.get("sites_vus", {}).items(),
                key=lambda x: (-int(x[1].get("releves", 0)), x[0]),
            )
        ]

    # ------------------------------------------------------- Étiquettes

    def etiquettes_de(self, genre: str, nom: str) -> list[str]:
        """Étiquettes posées sur un logiciel ou un site."""
        cle = _nom_app(nom) if genre == "apps" else _nom_site(nom)
        return list(self._memo(("etq", genre, cle), 5.0, lambda: list(self.etiquettes.get(genre, {}).get(cle) or [])))

    def definir_etiquettes(self, genre: str, nom: str, valeurs: list[str]) -> None:
        cle = _nom_app(nom) if genre == "apps" else _nom_site(nom)
        if not cle:
            raise textes.Erreur("store.choisir_element")
        propres: list[str] = []
        for valeur in valeurs:
            valeur = str(valeur).strip()
            if valeur and valeur.lower() not in [p.lower() for p in propres]:
                propres.append(valeur)
        # 2.2.13 : le lien d'abord, l'étiquette de Home Assistant ensuite. Créer une étiquette déclenche aussitôt
        # (tâche immédiate) l'enregistrement puis le rechargement de Vision (__init__._etiquettes_changees) : posé
        # après, le lien manquait à ce qui était enregistré et se perdait au rechargement.
        if propres:
            self.etiquettes.setdefault(genre, {})[cle] = propres
        else:
            self.etiquettes.setdefault(genre, {}).pop(cle, None)
        for valeur in propres:
            self.declarer_etiquette(valeur)

    def ajouter_etiquette(self, genre: str, nom: str, etiquette: str) -> bool:
        """Pose une étiquette de plus, sans effacer les précédentes.

        Un site en porte autant qu'on veut : « vidéo » et « école » ne
        s'excluent pas. Retaper la liste entière pour en ajouter une était
        le meilleur moyen d'en perdre une.
        """
        etiquette = str(etiquette or "").strip()
        if not etiquette:
            raise textes.Erreur("store.choisir_etiquette")
        actuelles = self.etiquettes_de(genre, nom)
        if any(e.lower() == etiquette.lower() for e in actuelles):
            return False
        self.definir_etiquettes(genre, nom, actuelles + [etiquette])
        return True

    def retirer_etiquette(self, genre: str, nom: str, etiquette: str) -> bool:
        """Retire une étiquette et laisse les autres en place."""
        etiquette = str(etiquette or "").strip().lower()
        actuelles = self.etiquettes_de(genre, nom)
        garde = [e for e in actuelles if e.lower() != etiquette]
        if len(garde) == len(actuelles):
            return False
        self.definir_etiquettes(genre, nom, garde)
        return True

    # ------------------------------------------------------- Exceptions

    # ------------------------------------------------- Refus et attente
    #
    # Un parent a dit non : la même demande ne peut pas revenir avant un
    # moment (« delai_refus », réglable). Le refus vaut pour la personne :
    # refusé sur son PC, il l'est aussi sur son téléphone.
    def _siens(self, pc: dict[str, Any]) -> list[dict[str, Any]]:
        personne = str(pc.get("person") or "")
        return [a for a in self.pcs.values() if a is pc or (personne and str(a.get("person") or "") == personne)]

    # ------------------------------------------------ Limites de temps
    #
    # « Une heure de Roblox par jour » : un nombre de minutes par logiciel,
    # pour la personne. Le temps compté est celui passé devant, sur tous ses
    # appareils ; à minuit le compteur du jour repart de zéro. Une limite
    # atteinte ferme le logiciel comme une règle, et l'agent n'a rien à
    # savoir de plus : Home Assistant lui donne le nom à fermer.

    LIMITE_MAX_MINUTES = 1440

    def limites(self, pc: dict[str, Any]) -> dict[str, int]:
        brut = pc.get("limites")
        if not isinstance(brut, dict):
            return {}
        sortie: dict[str, int] = {}
        for nom, minutes in brut.items():
            try:
                m = int(minutes)
            except (TypeError, ValueError):
                continue
            if m > 0 and _nom_app(str(nom)):
                sortie[_nom_app(str(nom))] = min(m, self.LIMITE_MAX_MINUTES)
        return sortie

    def poser_limite(self, pc: dict[str, Any], nom: str, minutes: int) -> bool:
        """Pose (ou retire, à 0) la limite quotidienne d'un logiciel. Rend True si quelque chose change."""
        cle = _nom_app(nom)
        if not cle:
            raise textes.Erreur("store.logiciel_limite")
        minutes = max(0, min(self.LIMITE_MAX_MINUTES, int(minutes or 0)))
        actuelles = self.limites(pc)
        if minutes == 0:
            if cle not in actuelles:
                return False
            actuelles.pop(cle)
        else:
            if actuelles.get(cle) == minutes:
                return False
            actuelles[cle] = minutes
        for a in self._siens(pc):
            a["limites"] = dict(actuelles)
        return True

    def temps_du_jour(self, pc: dict[str, Any], nom: str) -> int:
        """Secondes passées aujourd'hui devant ce logiciel, sur tous les appareils de la personne."""
        cle = _nom_app(nom)
        jour = dt_util.now().strftime("%Y-%m-%d")
        total = 0
        for a in self._siens(pc):
            par_app = ((a.get("usage") or {}).get(jour) or {}).get("apps") or {}
            total += int(par_app.get(cle) or 0)
        return total

    def limite_atteinte(self, pc: dict[str, Any], genre: str, nom: str) -> tuple[int, int] | None:
        """(minutes permises, secondes passées) si la limite du jour est atteinte ; None sinon."""
        if genre != "apps":
            return None
        # 1.82.0 : module « Temps d'écran » coupé, les limites sont ignorées (gardées pour son retour).
        if _modules.coupe(self, "temps"):
            return None
        limites = pc.get("limites")
        if not limites:
            return None
        minutes = self.limites(pc).get(_nom_app(nom))
        if not minutes:
            return None
        passe = self.temps_du_jour(pc, nom)
        return (minutes, passe) if passe >= minutes * 60 else None

    def noter_refus(self, pc: dict[str, Any], genre: str, cle: str) -> float:
        """Retient le refus. Rend l'heure à partir de laquelle on peut redemander (0 : pas d'attente)."""
        minutes = max(0, int(self.delai_refus or 0))
        if not minutes or not cle:
            return 0.0
        jusqua = time.time() + minutes * 60
        for a in self._siens(pc):
            a.setdefault("refus", {})["{}:{}".format(genre, str(cle).lower())] = jusqua
        return jusqua

    def refus_jusqua(self, pc: dict[str, Any], genre: str, cle: str) -> float:
        """Jusqu'à quand cette demande est fermée pour cette personne (0 : elle peut la faire)."""
        nom = "{}:{}".format(genre, str(cle).lower())
        maintenant = time.time()
        # Le délai a pu être raccourci depuis : on ne fait jamais attendre plus que le réglage du moment.
        plafond = maintenant + max(0, int(self.delai_refus or 0)) * 60
        fin = 0.0
        for a in self._siens(pc):
            fin = max(fin, float((a.get("refus") or {}).get(nom) or 0))
        fin = min(fin, plafond)
        return fin if fin > maintenant else 0.0

    def lever_refus(self, pc: dict[str, Any], genre: str, cle: str) -> None:
        nom = "{}:{}".format(genre, str(cle).lower())
        for a in self._siens(pc):
            (a.get("refus") or {}).pop(nom, None)

    def refus_en_cours(self, pc: dict[str, Any]) -> list[dict[str, Any]]:
        """Ce que cette personne ne peut pas redemander pour l'instant. Les refus échus sont oubliés."""
        maintenant = time.time()
        plafond = maintenant + max(0, int(self.delai_refus or 0)) * 60
        vus: dict[str, float] = {}
        for a in self._siens(pc):
            fiches = a.get("refus") or {}
            for nom in [n for n, fin in fiches.items() if float(fin or 0) <= maintenant]:
                fiches.pop(nom, None)
            for nom, fin in fiches.items():
                vus[nom] = max(vus.get(nom, 0.0), min(float(fin), plafond))
        sortie = []
        for nom, fin in sorted(vus.items()):
            if fin > maintenant and ":" in nom:
                genre, cle = nom.split(":", 1)
                sortie.append({"genre": genre, "nom": cle, "jusqua": int(fin)})
        return sortie

    # ---------------------------------------------- À qui est l'appareil
    #
    # Un appareil a une personne « principale » (champ « person ») : c'est elle
    # dont il suit les règles, et ses appareils s'alignent entre eux. Il peut
    # en plus être partagé (champ « partage ») : la télé du salon est à toute
    # la maison, celle d'une chambre à deux personnes. Le partage ne déplace
    # aucune règle : il dit seulement chez qui l'appareil s'affiche, et qui
    # peut recevoir du temps dessus.
    TOUS = "*"

    def _est_pseudo(self, entite: str) -> bool:
        """Une « personne » créée pour un écran d'affichage n'est propriétaire de rien."""
        return self.est_ecran_commun(entite)

    def est_ecran_commun(self, entite: str) -> bool:
        """Vrai si cette personne de Home Assistant tient lieu d'écran commun (télé du salon, affichage) :
        celles choisies dans les réglages généraux ; rien de choisi, celle qui s'appelle « Affichage »."""
        if not entite:
            return False
        if self.personnes_affichage:
            return entite in self.personnes_affichage
        etat = self.hass.states.get(entite)
        nom = str(etat.name or "") if etat is not None else entite.split(".")[-1]
        return nom.strip().lower() == "affichage"

    def personnes_maison(self) -> list[str]:
        """Toutes les personnes de Home Assistant, sans les écrans d'affichage."""
        return sorted(
            etat.entity_id
            for etat in self.hass.states.async_all("person")
            if not self._est_pseudo(etat.entity_id)
        )

    def partage_tous(self, pc: dict[str, Any]) -> bool:
        return self.TOUS in (pc.get("partage") or [])

    def proprietaires(self, pc: dict[str, Any]) -> list[str]:
        """Les personnes à qui cet appareil appartient, la principale en tête."""
        sortie: list[str] = []
        principale = str(pc.get("person") or "")
        if principale and not self._est_pseudo(principale):
            sortie.append(principale)
        if self.partage_tous(pc):
            autres = self.personnes_maison()
        else:
            autres = [str(p) for p in (pc.get("partage") or []) if str(p).startswith("person.")]
        for p in autres:
            if p not in sortie and not self._est_pseudo(p):
                sortie.append(p)
        return sortie

    def appareils_de(self, personne: str) -> list[dict[str, Any]]:
        """Les appareils de cette personne : les siens d'abord, puis ceux qu'elle partage."""
        personne = str(personne or "")
        if not personne:
            return []
        siens = [pc for pc in self.pcs.values() if personne in self.proprietaires(pc)]
        siens.sort(key=lambda pc: (str(pc.get("person") or "") != personne, str(pc.get("name") or "").lower()))
        return siens

    def definir_partage(self, pc: dict[str, Any], personnes: list[str] | None, tous: bool = False) -> None:
        """Dit avec qui l'appareil est partagé. Liste vide : il n'est qu'à sa personne principale."""
        if tous:
            pc["partage"] = [self.TOUS]
            return
        principale = str(pc.get("person") or "")
        garde: list[str] = []
        for p in personnes or []:
            p = str(p).strip()
            if p.startswith("person.") and p != principale and p not in garde and self.hass.states.get(p) is not None:
                garde.append(p)
        pc["partage"] = garde

    def attribuer(self, pc: dict[str, Any], personne: str, mode: str) -> None:
        """Donne un appareil à une personne, le lui fait partager, ou le lui retire.

        « principal » : l'appareil devient le sien et suit ses règles et son planning.
        « partage » : il garde ses règles, mais s'affiche aussi chez elle.
        « retirer » : il n'est plus à elle, ni en propre ni en partage.
        """
        personne = str(personne or "")
        if not personne.startswith("person.") or self.hass.states.get(personne) is None:
            raise textes.Erreur("store.personne_inconnue")
        partage = [p for p in (pc.get("partage") or []) if p != personne]
        if mode == "principal":
            pc["person"] = personne
            pc["partage"] = partage if self.TOUS not in partage else [self.TOUS]
        elif mode == "partage":
            if str(pc.get("person") or "") == personne:
                raise textes.Erreur("store.deja_sien")
            if self.TOUS not in partage:
                partage.append(personne)
            pc["partage"] = partage
        elif mode == "retirer":
            if str(pc.get("person") or "") == personne:
                pc["person"] = ""
            elif self.TOUS in partage:
                raise textes.Erreur("store.a_tous")
            pc["partage"] = partage
        else:
            raise textes.Erreur("commun.geste_inconnu")

    def ajouter_temps(self, pc: dict[str, Any], minutes: float) -> float:
        """Ouvre l'appareil pour « minutes » de plus. Rend l'heure de fin.

        Un temps déjà accordé et encore en cours est prolongé : « +30 min »
        deux fois de suite donne une heure, pas trente minutes à partir du
        second clic.
        """
        maintenant = time.time()
        depart = maintenant
        actuel = pc.get("override") or None
        if actuel and actuel.get("mode") == MODE_OPEN:
            fin = float(actuel.get("until") or 0)
            if fin > maintenant:
                depart = fin
        fin = depart + float(minutes) * 60
        pc["override"] = {"mode": MODE_OPEN, "until": fin}
        return fin

    # ------------------------------------------------------- Personnes

    def personnes_suivies(self) -> list[str]:
        """Les personnes à qui au moins un appareil est attribué."""
        return sorted(
            {str(pc.get("person") or "") for pc in self.pcs.values()} - {""}
        )

    # ------------------------------ Catégories fermées par une automatisation
    #
    # Les primitives des automatisations : fermer une catégorie pour une personne en disant
    # pourquoi, la rouvrir, et lire ce qui est fermé. Une fermeture a une **source** (le nom
    # que l'automatisation se donne, « moyenne », « devoirs ») : une catégorie reste fermée
    # tant qu'une source au moins la ferme, et rouvrir ne lève que la sienne. Sans source,
    # tout se range sous la source vide, comme avant la 1.60.0.
    # Rangées par personne : {"person.lou": {"jeux|moyenne": {etiquette, raison, source, depuis}}}.

    def fermer_categorie(self, personne: str, etiquette: str, raison: str = "", source: str = "") -> bool:
        """Ferme une catégorie pour une personne, en disant pourquoi. Rend True si quelque chose a changé."""
        personne = str(personne or "").strip()
        etiquette = str(etiquette or "").strip()
        source = str(source or "").strip()[:80]
        if not personne or not etiquette:
            return False
        fiches = self.__dict__.setdefault("fermetures", {}).setdefault(personne, {})
        cle = _cle_fermeture(etiquette, source)
        avant = fiches.get(cle)
        nouveau = {
            "etiquette": etiquette,
            "raison": str(raison or "").strip()[:200],
            "source": source,
            "depuis": (avant or {}).get("depuis") or int(time.time()),
        }
        if avant == nouveau:
            return False
        fiches[cle] = nouveau
        return True

    def ouvrir_categorie(self, personne: str, etiquette: str | None = None, source: str = "") -> int:
        """Rouvre ce que cette source a fermé pour cette personne : une catégorie, ou toutes si
        aucune n'est nommée. Les fermetures des autres sources restent. Rend leur nombre."""
        fiches = self.__dict__.setdefault("fermetures", {}).get(str(personne or "").strip())
        if not fiches:
            return 0
        source = str(source or "").strip()
        bas = None if etiquette is None else str(etiquette).strip().lower()
        a_lever = [
            cle for cle, f in fiches.items()
            if str(f.get("source") or "") == source
            and (bas is None or str(f.get("etiquette") or "").lower() == bas)
        ]
        for cle in a_lever:
            fiches.pop(cle, None)
        return len(a_lever)

    def fermetures_de(self, personne: str) -> list[dict[str, Any]]:
        """Toutes les fermetures en cours pour cette personne, une par catégorie et par source."""
        fiches = (getattr(self, "fermetures", None) or {}).get(str(personne or "")) or {}
        return sorted(
            (dict(f) for f in fiches.values()),
            key=lambda f: (str(f.get("etiquette") or "").lower(), str(f.get("source") or "")),
        )

    def fermeture_de(self, pc: dict[str, Any], etiquettes: list[str]) -> dict[str, Any] | None:
        """La fermeture par automatisation qui touche cet élément, s'il y en a une (la plus ancienne)."""
        if not etiquettes:
            return None
        fiches = (getattr(self, "fermetures", None) or {}).get(str(pc.get("person") or ""))
        if not fiches:
            return None
        visees = {str(e).lower() for e in etiquettes}
        touchent = [f for f in fiches.values() if str(f.get("etiquette") or "").lower() in visees]
        if not touchent:
            return None
        return min(touchent, key=lambda f: int(f.get("depuis") or 0))

    def exceptions(self, pc: dict[str, Any]) -> list[str]:
        return sorted(pc.get("exceptions") or [])

    def ajouter_exception(self, pc: dict[str, Any], adresse: str) -> bool:
        """Épargne une adresse précise, quoi que dise la règle de son site.

        C'est la porte laissée ouverte dans un mur : le site reste bloqué,
        mais l'adresse citée passe. Elle doit donc être plus précise que le
        site lui-même — épargner « youtube.com » alors qu'on bloque
        « youtube.com » n'aurait aucun sens, cela s'appelle l'autoriser.
        """
        hote = _hote(adresse)
        if not hote:
            raise textes.Erreur("store.adresse_complete")
        if hote == domaine_principal(hote):
            raise textes.Erreur("store.site_entier", hote=hote)
        listes = pc.setdefault("exceptions", [])
        if hote in listes:
            return False
        listes.append(hote)
        listes.sort()
        return True

    def retirer_exception(self, pc: dict[str, Any], adresse: str) -> bool:
        hote = _hote(adresse)
        listes = pc.setdefault("exceptions", [])
        if hote not in listes:
            return False
        listes.remove(hote)
        return True

    # ---------------------------------------------------- Autorisations (refonte des autorisations)

    @staticmethod
    def personne_de(pc: dict[str, Any]) -> str:
        """À qui une autorisation durable est rattachée : la personne de l'appareil, sinon l'appareil seul."""
        return str((pc or {}).get("person") or "").strip() or "pc:" + str((pc or {}).get("id") or "")

    @staticmethod
    def _champ_toujours(genre: str) -> str:
        return "toujours" if genre == "apps" else "toujours_sites"

    def _toujours_de(self, pc: dict[str, Any], genre: str) -> list[str]:
        """« Toujours disponible » pour la personne de cet appareil (tous ses appareils confondus), noms normalisés."""
        vus: dict[str, None] = {}
        for a in self._siens(pc):
            for n in a.get(self._champ_toujours(genre)) or []:
                cle = self._cle(genre, str(n).strip())
                if cle:
                    vus.setdefault(cle, None)
        return list(vus)

    def mode_accorde(self, pc: dict[str, Any], genre: str, nom: str) -> str | None:
        """« toujours » ou « exception » si le Demandeur de cet appareil l'a pour ce nom, sinon None."""
        cible = self._cle(genre, nom)
        if not cible:
            return None
        if cible in self._toujours_de(pc, genre):
            return "toujours"
        if self._dans(pc.get("autorises"), cible):
            return "exception"
        return None

    def bloque_explicite(self, pc: dict[str, Any], genre: str, nom: str) -> str | None:
        """Ce qu'aucune autorisation ne passe (CONCEPTION § 2), ou None.

        « lieu » : le nom ou une de ses catégories bloqué pour tout le Lieu ; « securite » : une catégorie de
        sécurité ou d'âge fermée sur cet appareil ; « liste_noire » : un site signalé dangereux ; « nomme » : le
        nom bloqué exprès sur cet appareil (il cède à la décision suivante du Décideur, validé en essai)."""
        cible = self._cle(genre, nom)
        if not cible:
            return None
        etiquettes = self.etiquettes_de(genre, nom)
        if self.est_banni(cible) or any(self.est_banni(e) for e in etiquettes):
            return "lieu"
        securite = [e for e in etiquettes if str(e).strip().lower() in ETIQUETTES_SECURITE]
        if securite and (any(self._dans(pc.get("bloques"), e) for e in securite)
                         or self.fermeture_de(pc, securite) is not None):
            return "securite"
        if genre == "sites" and self.est_dangereux(cible):
            return "liste_noire"
        if self._dans(pc.get("bloques"), cible):
            return "nomme"
        return None

    def accorder(self, pc: dict[str, Any], genre: str, nom: str, mode: str, minutes: int = 0,
                 par: str = "") -> dict[str, Any]:
        """Accorde une appli ou un site au Demandeur de cet appareil, selon l'un des trois modes.

        « temps » : sur cet appareil seulement, pour `minutes` ; « exception » et « toujours » : sur toutes ses
        machines, sans fin. Le Lieu, la sécurité et l'âge, la liste noire ne sont jamais percés : l'erreur le dit.
        Le « Bloquer » d'un Décideur cède : « temps » le passe sur cet appareil, une exception ou « toujours »
        le lève sur toutes les machines du Demandeur (décision du projet)."""
        if mode not in MODES_AUTORISATION:
            raise textes.Erreur("store.mode_inconnu")
        if genre not in ("apps", "sites"):
            genre = self._genre_de(nom)
        cible = self._cle(genre, nom)
        if not cible:
            raise textes.Erreur("store.choisir_logiciel" if genre == "apps" else "store.choisir_site")
        if self.bloque_explicite(pc, genre, cible) in ("lieu", "securite", "liste_noire"):
            raise textes.Erreur("store.bloque_explicite", nom=cible)
        if mode == "temps":
            fin = (self.laisser_passer_app if genre == "apps" else self.laisser_passer)(pc, cible, minutes)
            return {"genre": genre, "nom": cible, "mode": "temps", "fin": fin, "appareil": pc.get("id")}
        champ_tj = self._champ_toujours(genre)
        for a in self._siens(pc):
            a["bloques"] = [v for v in (a.get("bloques") or []) if not self._dans([str(v)], cible)]
            autorises = a.setdefault("autorises", [])
            if not self._dans(autorises, cible):
                autorises.append(cible)
            a[champ_tj] = [n for n in (a.get(champ_tj) or []) if self._cle(genre, str(n)) != cible]
        if mode == "toujours":
            pc.setdefault(champ_tj, []).append(cible)
        return {"genre": genre, "nom": cible, "mode": mode, "par": str(par or "")}

    def retirer_autorisation(self, pc: dict[str, Any], genre: str, nom: str, mode: str | None = None) -> bool:
        """Retire une autorisation. Sans mode : tout (pour un temps ici, exception et « toujours » partout) ;
        « toujours » : redevient une exception ; « exception » : plus rien de durable ; « temps » : ce seul accord."""
        if genre not in ("apps", "sites"):
            genre = self._genre_de(nom)
        cible = self._cle(genre, nom)
        if not cible:
            return False
        retire = False
        if mode in (None, "temps"):
            cle_lp = "app:" + cible if genre == "apps" else cible
            retire = (pc.get("laissez_passer") or {}).pop(cle_lp, None) is not None
        if mode in (None, *MODES_DURABLES):
            champ_tj = self._champ_toujours(genre)
            for a in self._siens(pc):
                avant = list(a.get(champ_tj) or [])
                a[champ_tj] = [n for n in avant if self._cle(genre, str(n)) != cible]
                retire = retire or len(a[champ_tj]) != len(avant)
                if mode in (None, "exception"):
                    avant_a = list(a.get("autorises") or [])
                    a["autorises"] = [v for v in avant_a if not self._dans([str(v)], cible)]
                    retire = retire or len(a["autorises"]) != len(avant_a)
        return retire

    # --- Lot 3 : la fiche d'un logiciel (ses sites liés) ------------------

    def dependances(self, app: str) -> tuple[str, ...]:
        """Les sites dont l'appli a besoin : ceux connus de Vision, ceux qu'un Décideur a liés, puis ceux liés tout
        seuls qui suivent le logiciel (2.5.1). Un site délié à la main n'en fait plus partie."""
        famille = FAMILLES_APPS.get(_nom_app(app), _nom_app(app))
        delies = set((getattr(self, "domaines_delies", None) or {}).get(famille, []))
        connus = [d for d in DEPENDANCES_APPS.get(famille, ()) if d not in delies]
        if delies:
            # Délier le chef d'une famille de sites (« discord.com ») délie aussi ses membres.
            connus = [d for d in connus if _nom_site(d) not in delies]
        for d in (getattr(self, "domaines_lies", None) or {}).get(famille, []):
            if d not in connus:
                connus.append(d)
        for d, fiche in ((getattr(self, "domaines_auto", None) or {}).get(famille) or {}).items():
            if fiche.get("suit") and d not in connus and d not in delies:
                connus.append(d)
        return tuple(connus)

    def sites_du_logiciel(self, app: str) -> list[dict[str, Any]]:
        """Tous les sites rangés sous un logiciel, pour l'affichage : `auto` s'il a été lié tout seul, `attente`
        s'il était fermé au moment du lien (il ne suit le logiciel qu'après un geste du Décideur)."""
        famille = FAMILLES_APPS.get(_nom_app(app), _nom_app(app))
        auto = (getattr(self, "domaines_auto", None) or {}).get(famille) or {}
        rendu: list[dict[str, Any]] = [{"nom": d, **({"auto": True} if d in auto else {})} for d in self.dependances(famille)]
        delies = set((getattr(self, "domaines_delies", None) or {}).get(famille, []))
        for d, fiche in auto.items():
            if not fiche.get("suit") and d not in delies:
                rendu.append({"nom": d, "auto": True, "attente": True})
        return rendu

    def domaines_proposes(self, pc: dict[str, Any], app: str, maximum: int = 8) -> list[str]:
        """Des sites vus sur les appareils de la personne qui portent le nom de l'appli (« roblox » → robloxlabs.com,
        rbx…) et pas encore liés. Depuis la 2.5.1, `lier_auto` les lie tout seuls : la liste se vide."""
        famille = FAMILLES_APPS.get(_nom_app(app), _nom_app(app))
        racine = famille.split(".")[0]
        if len(racine) < 4:
            return []
        deja = {s["nom"] for s in self.sites_du_logiciel(famille)}
        deja |= set((getattr(self, "domaines_delies", None) or {}).get(famille, []))
        vus: list[str] = []
        for a in self._siens(pc):
            for site in (a.get("sites_vus") or {}):
                site = _nom_site(str(site))
                if site and racine in site.replace("-", "") and site not in deja and site not in vus:
                    vus.append(site)
        return sorted(vus)[:maximum]

    @staticmethod
    def famille_premier_plan(focus: str) -> str:
        """Le logiciel au premier plan, ramené à sa famille ; vide pour un navigateur ou un programme du système."""
        nom = _nom_app(str(focus or ""))
        famille = FAMILLES_APPS.get(nom, nom)
        if not famille or famille in NAVIGATEURS or est_bruit_app(famille):
            return ""
        return famille

    def lien_auto_permis(self, famille: str, site: str) -> bool:
        """Les garde-fous du lien automatique (décision du projet du 14-10) : ni Adulte, Dangereux, Contournement ou
        Pub & traçage, ni l'infrastructure commune, ni la liste noire, ni ce qui est banni du Lieu, ni par un
        navigateur, ni un site délié à la main."""
        if not famille or not site or famille in NAVIGATEURS or est_bruit_app(famille):
            return False
        if est_bruit_site(site) or self.est_dangereux(site) or self.est_banni(site):
            return False
        if site in (getattr(self, "domaines_delies", None) or {}).get(famille, []):
            return False
        return not ({str(e).strip().lower() for e in self.etiquettes_de("sites", site)} & ETIQUETTES_SANS_LIEN_AUTO)

    def lier_auto(self, pc: dict[str, Any]) -> int:
        """Lie tout seuls les sites vus pendant qu'un logiciel tourne : les origines remontées par l'agent (quel
        programme parlait à quel domaine), les tentatives bloquées notées avec le logiciel au premier plan, et les
        sites qui portent le nom d'un logiciel vu. Rend le nombre de liens nouveaux.

        Un lien ne débloque rien (« On garde ce qui est bloqué ») : un site fermé sur l'un des appareils au moment du
        lien est rangé sous le logiciel, mais ne le suit qu'après un geste du Décideur (demande groupée, ou son
        propre interrupteur)."""
        candidats: dict[str, set[str]] = {}
        for site, fiche in (pc.get("sites_vus") or {}).items():
            for programme in (fiche.get("origines") or {}):
                nom = _nom_app(str(programme))
                candidats.setdefault(FAMILLES_APPS.get(nom, nom), set()).add(str(site))
        for alerte in pc.get("alertes") or []:
            if alerte.get("app"):
                candidats.setdefault(str(alerte["app"]), set()).add(domaine_principal(str(alerte.get("domaine") or "")))
        for app in {FAMILLES_APPS.get(n, n) for a in self._siens(pc) for n in (a.get("apps_vues") or {})}:
            for site in self.domaines_proposes(pc, app, LIENS_AUTO_MAX):
                candidats.setdefault(app, set()).add(site)
        nouveaux = 0
        auto = self.__dict__.setdefault("domaines_auto", {})
        for famille, sites in candidats.items():
            deja = set(self.dependances(famille))
            liste = auto.get(famille) or {}
            for site in sorted(s for s in sites if s):
                if site in deja or site in liste or len(liste) >= LIENS_AUTO_MAX:
                    continue
                if not self.lien_auto_permis(famille, site):
                    continue
                ouvert = all(self.statut(a, "sites", site) == AUTORISE for a in self.pcs.values() if a.get("person"))
                liste[site] = {"depuis": time.time(), "suit": ouvert}
                auto[famille] = liste
                nouveaux += 1
        return nouveaux

    def lier_domaine(self, app: str, domaine: str, lier: bool = True) -> bool:
        """Un Décideur lie (ou délie) un site à une appli, pour tout le Lieu. Lier un site lié tout seul et en
        attente le fait suivre le logiciel ; délier l'écarte pour de bon des liens automatiques (un site connu de
        Vision aussi)."""
        famille = FAMILLES_APPS.get(_nom_app(app), _nom_app(app))
        # Le domaine tel quel (« rbxcdn.com »), pas le chef de sa famille : c'est lui qu'on range sous le logiciel.
        site = domaine_principal(domaine)
        if not famille or not site:
            raise textes.Erreur("store.choisir_site")
        auto = self.__dict__.setdefault("domaines_auto", {})
        delies = self.__dict__.setdefault("domaines_delies", {})
        lies = self.__dict__.setdefault("domaines_lies", {})
        liste = lies.get(famille) or []
        change = False
        if lier:
            if site in (delies.get(famille) or []):
                delies[famille].remove(site)
                change = True
            fiche = (auto.get(famille) or {}).get(site)
            if fiche is not None:
                change = change or not fiche.get("suit")
                fiche["suit"] = True
            elif site not in liste and site not in DEPENDANCES_APPS.get(famille, ()):
                liste.append(site)
                change = True
        else:
            if site in liste:
                liste.remove(site)
                change = True
            if site in (auto.get(famille) or {}):
                auto[famille].pop(site)
                change = True
            if (change or site in DEPENDANCES_APPS.get(famille, ())) and site not in (delies.get(famille) or []):
                delies.setdefault(famille, []).append(site)
                change = True
        for table, valeur in ((lies, liste), (auto, auto.get(famille)), (delies, delies.get(famille))):
            if valeur:
                table[famille] = valeur
            else:
                table.pop(famille, None)
        return change

    def suivre_categorie(self, pc: dict[str, Any], genre: str, nom: str) -> bool:
        """« Suivre la catégorie » (le mode par défaut, 2.5.1) : retire le réglage propre de l'élément pour le
        Demandeur de cet appareil (pour un temps ici, exception et « toujours disponible » partout, nom bloqué
        exprès sur ses machines). Il suit de nouveau sa catégorie, ses plages et son temps."""
        if genre not in ("apps", "sites"):
            genre = self._genre_de(nom)
        cible = self._cle(genre, nom)
        if not cible:
            return False
        change = self.retirer_autorisation(pc, genre, cible)
        for a in self._siens(pc):
            for champ in ("bloques", "autorises"):
                avant = list(a.get(champ) or [])
                a[champ] = [v for v in avant if not self._dans([str(v)], cible)]
                change = change or len(a[champ]) != len(avant)
        return change

    def noms_durables(self, pc: dict[str, Any], genre: str, mode: str | None = None) -> list[str]:
        """Les noms accordés durablement au Demandeur de cet appareil (un mode, ou les deux)."""
        toujours = self._toujours_de(pc, genre)
        if mode == "toujours":
            return toujours
        exceptions = []
        for v in pc.get("autorises") or []:
            v = str(v).strip()
            if not v or self._est_etiquette(v) or self._genre_de(v) != genre:
                continue
            cle = self._cle(genre, v)
            if cle and cle not in toujours and cle not in exceptions:
                exceptions.append(cle)
        return exceptions if mode == "exception" else toujours + exceptions

    def autorisations_de(self, pc: dict[str, Any]) -> list[dict[str, Any]]:
        """Ce qui est accordé au Demandeur de cet appareil : exceptions et « toujours disponible » (toutes ses
        machines), et ce qui est ouvert pour un temps sur cet appareil (avec l'heure de fin)."""
        rendu = []
        for genre in ("apps", "sites"):
            for mode in MODES_DURABLES:
                rendu += [{"genre": genre, "nom": n, "mode": mode} for n in self.noms_durables(pc, genre, mode)]
        for cle_lp, fin in self.laissez_passer_actifs(pc):
            genre, nom = ("apps", cle_lp[4:]) if cle_lp.startswith("app:") else ("sites", cle_lp)
            rendu.append({"genre": genre, "nom": nom, "mode": "temps", "fin": fin, "appareil": pc.get("id")})
        for f in rendu:
            if f["genre"] == "apps":
                famille = FAMILLES_APPS.get(f["nom"], f["nom"])
                f["lies"] = list(self.dependances(f["nom"]))
                f["lies_valides"] = list((getattr(self, "domaines_lies", None) or {}).get(famille, []))
                f["proposes"] = self.domaines_proposes(pc, f["nom"])
        return sorted(rendu, key=lambda f: (f["genre"], f["nom"], f["mode"]))

    def ferme_par(self, pc: dict[str, Any], genre: str, nom: str) -> str:
        """Ce qui ferme cet élément, en un code : « lieu », « securite », « liste_noire », « nomme » (bloqué exprès),
        « temps » (temps du jour épuisé), « plage » ou « categorie ». Même ordre que `statut`."""
        if explicite := self.bloque_explicite(pc, genre, nom):
            return explicite
        if self.limite_atteinte(pc, genre, nom) is not None:
            return "temps"
        if self.plage_nomme(pc, genre, nom) is not None or self._plage_de(pc, genre, nom) is not None:
            return "plage"
        return "categorie"

    def etat_element(self, pc: dict[str, Any], genre: str, nom: str, langue: str | None = None) -> dict[str, Any]:
        """L'état d'une ligne de l'écran d'une catégorie : ouvert ou fermé, le mode accordé (avec la fin d'un
        « pour un temps »), ce qui ferme et pourquoi (dans `langue`). Sans mode : il suit sa catégorie. Lecture
        seule."""
        rendu: dict[str, Any] = {"genre": genre, "nom": nom, "ouvert": self.statut(pc, genre, nom) == AUTORISE}
        if genre == "apps" and (libelle := self._libelle_de("apps", nom)):
            rendu["libelle"] = libelle  # le nom affiché d'une appli Android (« Roblox » pour com.roblox.client)
        cle_lp = "app:" + _nom_app(nom) if genre == "apps" else _nom_site(nom)
        fin = float((pc.get("laissez_passer") or {}).get(cle_lp) or 0)
        if fin > time.time():
            rendu.update(mode="temps", fin=fin)
        elif mode := self.mode_accorde(pc, genre, nom):
            rendu["mode"] = mode
        elif self.bloque_explicite(pc, genre, nom) == "nomme":
            rendu["mode"] = "bloquer"
        if not rendu["ouvert"]:
            rendu["ferme_par"] = self.ferme_par(pc, genre, nom)
            if langue:
                raison = self.raison_enfant(pc, genre, nom, langue)
                if raison:
                    rendu["raison"] = raison["texte"]
        return rendu

    def logiciel_des_sites(self) -> dict[str, str]:
        """Pour chaque site rangé sous un logiciel (connu, lié à la main ou tout seul), la famille du logiciel."""
        familles = set(DEPENDANCES_APPS) | set(getattr(self, "domaines_lies", None) or {}) | set(
            getattr(self, "domaines_auto", None) or {})
        rendu: dict[str, str] = {}
        for famille in sorted(familles):
            for s in self.sites_du_logiciel(famille):
                rendu.setdefault(s["nom"], famille)
        return rendu

    def _ligne_logiciel(self, pc: dict[str, Any], app: str, langue: str | None) -> dict[str, Any]:
        """Un logiciel, ses services (les programmes de sa famille : ils suivent toujours le logiciel, une règle
        porte sur la famille) et ses sites (ils suivent le logiciel tant qu'ils n'ont pas de réglage propre)."""
        ligne = self.etat_element(pc, "apps", app, langue)
        ligne["services"] = [{"nom": s, "suit": True, "ouvert": ligne["ouvert"]} for s in membres_app(app)[1:]]
        sites = []
        for fiche in self.sites_du_logiciel(app):
            s = {**self.etat_element(pc, "sites", fiche["nom"], langue), **fiche}
            propre = "mode" in s or s.get("ferme_par") in ("lieu", "securite", "liste_noire")
            if not fiche.get("attente") and not propre:
                s["suit"] = True
                if ligne["ouvert"] and "mode" in ligne:
                    # L'appli accordée garde ses sites ouverts (règle des sites liés, lot 1).
                    s.update(ouvert=True, par_logiciel=True)
                    s.pop("ferme_par", None)
                    s.pop("raison", None)
            sites.append(s)
        ligne["sites"] = sites
        return ligne

    def detail_categorie(self, pc: dict[str, Any], etiquette: str, sites_max: int = 8,
                         langue: str | None = None) -> dict[str, Any]:
        """L'écran d'une catégorie pour le Demandeur de cet appareil (maquette du 13-10, validée en essai le 14-10) :
        chaque logiciel avec ses services et ses sites, puis les sites sans logiciel, les fermés d'abord, puis les
        premiers ; « autres » compte le reste. Lecture seule : rien n'est ouvert ni fermé ici."""
        vues = {n for a in self._siens(pc) for n in (a.get("apps_vues") or {})}
        apps = sorted({n for n in self.noms_par_etiquette("apps", etiquette)
                       if n in vues or self.etiquettes.get("apps", {}).get(n)})
        logiciels = [self._ligne_logiciel(pc, app, langue) for app in apps]
        rattaches = {s["nom"] for lg in logiciels for s in lg["sites"]}
        rattaches |= {s for s in self.noms_par_etiquette("sites", etiquette)
                      if any(s.endswith("." + r) for r in rattaches)}
        ailleurs = self.logiciel_des_sites()
        seuls = []
        for s in self.noms_par_etiquette("sites", etiquette):
            if s in rattaches:
                continue
            ligne = self.etat_element(pc, "sites", s, langue)
            if s in ailleurs:
                ligne["logiciel"] = ailleurs[s]
            seuls.append(ligne)
        seuls.sort(key=lambda s: (s["ouvert"], s["nom"]))
        fermee = bool(self._dans(pc.get("bloques"), etiquette) or self.fermeture_de(pc, [etiquette]) is not None)
        return {"etiquette": etiquette, "fermee": fermee, "logiciels": logiciels,
                "sites": seuls[:sites_max], "autres": max(0, len(seuls) - sites_max)}

    def rechercher(self, pc: dict[str, Any], texte: str, langue: str | None = None,
                   maximum: int = 30) -> list[dict[str, Any]]:
        """Retrouve une catégorie, un logiciel (ou l'un de ses services) ou un site, où qu'il soit rangé, avec son
        état, ce qui le ferme et pourquoi, ses catégories et son logiciel (2.5.1, ajout 4 de l'auteur). Lecture seule."""
        cherche = str(texte or "").strip().lower()
        if len(cherche) < 2:
            return []
        trouves: list[dict[str, Any]] = []
        noms_cat = sorted({str(e) for g in ("apps", "sites") for es in (self.etiquettes.get(g) or {}).values()
                           for e in es if str(e).strip()})
        for e in noms_cat:
            if cherche in e.lower() or cherche in textes.categorie(e, langue).lower():
                trouves.append({"genre": "categorie", "nom": e,
                                "fermee": bool(self._dans(pc.get("bloques"), e) or self.fermeture_de(pc, [e]))})
        familles: dict[str, str] = {}
        for n in self.catalogue_maison("apps"):
            for m in membres_app(n):
                familles.setdefault(m, _nom_app(n))
        for programme, famille in FAMILLES_APPS.items():
            familles.setdefault(programme, famille)
            familles.setdefault(famille, famille)
        vus_apps = set()
        for programme, famille in sorted(familles.items()):
            if cherche in programme and famille not in vus_apps:
                vus_apps.add(famille)
                ligne = self.etat_element(pc, "apps", famille, langue)
                ligne["categories"] = self.etiquettes_de("apps", famille)
                if programme != famille:
                    ligne["service"] = programme
                trouves.append(ligne)
        ailleurs = self.logiciel_des_sites()
        for s in sorted(set(self.catalogue_maison("sites")) | set(ailleurs)):
            if cherche not in s:
                continue
            ligne = self.etat_element(pc, "sites", s, langue)
            ligne["categories"] = self.etiquettes_de("sites", s)
            if s in ailleurs:
                ligne["logiciel"] = ailleurs[s]
            trouves.append(ligne)
        trouves.sort(key=lambda r: (not str(r["nom"]).lower().startswith(cherche), r.get("ouvert", True), r["nom"]))
        return trouves[:maximum]

    def groupes_a_demander(self, pc: dict[str, Any], langue: str | None = None) -> list[dict[str, Any]]:
        """Les sites bloqués récemment qu'on peut demander, regroupés par logiciel (ajout 5 de l'auteur) : « Roblox ne
        marche pas : 6 sites bloqués → demander l'accès pour Roblox ». Avec les sites liés en attente du logiciel,
        s'ils sont fermés. Un logiciel sans site à demander n'y figure pas."""
        groupes: dict[str, list[str]] = {}
        for ligne in self.blocages_recents(pc, langue, pour_demandeur=True, maximum=60):
            if ligne.get("logiciel"):
                groupes.setdefault(ligne["logiciel"], []).append(ligne["nom"])
        rendu = []
        for famille, sites in groupes.items():
            for fiche in self.sites_du_logiciel(famille):
                if fiche.get("attente") and fiche["nom"] not in sites and self.statut(pc, "sites", fiche["nom"]) != AUTORISE \
                        and self.bloque_explicite(pc, "sites", fiche["nom"]) not in ("lieu", "securite", "liste_noire"):
                    sites.append(fiche["nom"])
            rendu.append({"logiciel": famille, "libelle": self._libelle_de("apps", famille) or famille,
                          "ouvert": self.statut(pc, "apps", famille) == AUTORISE, "sites": sites})
        return sorted(rendu, key=lambda g: (-len(g["sites"]), g["logiciel"]))

    def blocages_recents(self, pc: dict[str, Any], langue: str | None = None, pour_demandeur: bool = False,
                         maximum: int = 20) -> list[dict[str, Any]]:
        """Les derniers sites qui ont posé problème sur les appareils de cette personne (ajout 6 de l'auteur) : l'heure,
        l'appareil, le logiciel s'il est connu, le nombre de tentatives, ce qui ferme et si on peut le demander.
        Côté Demandeur, seulement ce qu'il peut demander (ni sécurité, ni âge, ni liste noire, ni Lieu)."""
        ailleurs = self.logiciel_des_sites()
        par_site: dict[str, dict[str, Any]] = {}
        for a in self._siens(pc):
            for alerte in a.get("alertes") or []:
                domaine = domaine_principal(str(alerte.get("domaine") or ""))
                if not domaine or est_bruit_site(domaine):
                    continue
                quand = float(alerte.get("quand") or 0)
                fiche = par_site.get(domaine)
                if fiche is None or quand > fiche["quand"]:
                    fois = int(alerte.get("fois") or 1) + (fiche["fois"] if fiche else 0)
                    fiche = par_site[domaine] = {
                        "nom": domaine, "quand": quand, "fois": fois, "appareil": a.get("id"),
                        "appareil_nom": a.get("name") or a.get("id"), "source": alerte.get("source") or "",
                        "logiciel": alerte.get("app") or ailleurs.get(domaine) or ""}
                else:
                    fiche["fois"] += int(alerte.get("fois") or 1)
        rendu = []
        for fiche in sorted(par_site.values(), key=lambda f: -f["quand"]):
            ligne = {**fiche, **self.etat_element(self.pcs.get(fiche["appareil"]) or pc, "sites", fiche["nom"], langue)}
            ligne["demandable"] = not ligne["ouvert"] and ligne.get("ferme_par") not in ("lieu", "securite", "liste_noire")
            if pour_demandeur and not ligne["demandable"]:
                continue
            if not ligne["logiciel"]:
                ligne.pop("logiciel")
            rendu.append(ligne)
            if len(rendu) >= maximum:
                break
        return rendu

    # ---------------------------------------------------- Laissez-passer

    def laisser_passer(
        self, pc: dict[str, Any], nom: str, minutes: int
    ) -> float:
        """Rouvre un site pour un temps compté, puis se referme seul.

        Une ressource pédagogique sur un site par ailleurs fermé ne justifie
        pas de démonter la règle : on ouvre, on regarde, cela se referme.
        """
        domaine = _nom_site(nom)
        if not domaine:
            raise textes.Erreur("store.choisir_site")
        minutes = int(minutes or 0)
        if minutes < LAISSEZ_PASSER_MIN or minutes > LAISSEZ_PASSER_MAX:
            raise textes.Erreur("store.duree_entre", min=LAISSEZ_PASSER_MIN, max=LAISSEZ_PASSER_MAX)
        fin = time.time() + minutes * 60
        passes = pc.setdefault("laissez_passer", {})
        # 2.4.4 : un second accord ne raccourcit jamais un accord en cours (la fin d'un accord ne coupe pas l'autre).
        fin = max(fin, float(passes.get(domaine) or 0))
        passes[domaine] = fin
        return fin

    def fin_laissez_passer(self, pc: dict[str, Any], nom: str) -> bool:
        """Referme tout de suite, sans attendre l'heure."""
        domaine = _nom_site(nom)
        return pc.setdefault("laissez_passer", {}).pop(domaine, None) is not None

    def purger_laissez_passer(self, pc: dict[str, Any]) -> bool:
        """Oublie les laissez-passer expirés. Rend True si l'un vient d'expirer."""
        maintenant = time.time()
        passes = pc.setdefault("laissez_passer", {})
        finis = [d for d, fin in passes.items() if float(fin or 0) <= maintenant]
        for domaine in finis:
            passes.pop(domaine, None)
        return bool(finis)

    def laissez_passer_actifs(self, pc: dict[str, Any]) -> list[tuple[str, float]]:
        maintenant = time.time()
        return sorted(
            (d, float(fin))
            for d, fin in (pc.get("laissez_passer") or {}).items()
            if float(fin or 0) > maintenant
        )

    def laisser_passer_app(self, pc: dict[str, Any], nom: str, minutes: int) -> float:
        """Rouvre un logiciel pour un temps compté, comme un site."""
        cle = _nom_app(nom)
        if not cle:
            raise textes.Erreur("store.choisir_logiciel")
        minutes = max(LAISSEZ_PASSER_MIN, min(LAISSEZ_PASSER_MAX, int(minutes or 0)))
        fin = sum([time.time(), minutes * 60])
        passes = pc.setdefault("laissez_passer", {})
        # 2.4.4 : comme pour un site, un second accord plus court ne raccourcit pas celui en cours.
        fin = max(fin, float(passes.get("".join(["app:", cle])) or 0))
        passes["".join(["app:", cle])] = fin
        return fin

    def _laissez_passer(self, pc: dict[str, Any], genre: str, nom: str) -> bool:
        if genre == "apps":
            fin = (pc.get("laissez_passer") or {}).get("".join(["app:", _nom_app(nom)]))
            return bool(fin) and float(fin) > time.time()
        domaine = _nom_site(nom)
        fin = (pc.get("laissez_passer") or {}).get(domaine)
        return bool(fin) and float(fin) > time.time()

    def _excepte(self, pc: dict[str, Any], genre: str, nom: str) -> bool:
        if genre != "sites":
            return False
        hote = _hote(nom)
        return bool(hote) and hote in (pc.get("exceptions") or [])

    def declarer_etiquette(self, nom: str) -> bool:
        """Fait exister une étiquette, même si rien ne la porte encore.

        Une étiquette qui n'existe qu'à travers ce qu'elle étiquette est
        introuvable au moment où l'on veut écrire la règle : on tape
        « Vidéo » dans les blocages d'un PC, personne ne la reconnaît, et
        le mot devient un nom de logiciel qui n'existe pas. Déclarée, elle
        est utilisable tout de suite — pour les sites comme pour les
        logiciels.

        Depuis la 1.45.0 le vocabulaire n'est plus une liste à part : c'est
        le **registre d'étiquettes de Home Assistant**. Une étiquette créée
        ici apparaît dans Paramètres → Zones et étiquettes, avec sa couleur
        et son icône, et se choisit ensuite au sélecteur natif partout où
        il fallait la taper. Une seule liste, un seul endroit où la
        renommer.
        """
        nom = str(nom or "").strip()
        if not nom:
            raise textes.Erreur("store.nom_etiquette")
        neuve = not any(
            v.lower() == nom.lower() for v in labels.vocabulaire(self.hass)
        )
        labels.creer(self.hass, nom)
        # La liste maison ne sert plus qu'à reprendre l'existant : une fois
        # l'étiquette entrée au registre, la copie locale n'a plus lieu
        # d'être et s'efface d'elle-même.
        self.vocabulaire = [
            v for v in self.vocabulaire if v.lower() != nom.lower()
        ]
        return neuve

    def oublier_etiquette(self, nom: str) -> bool:
        """Retire une étiquette du vocabulaire, et de tout ce qui la porte."""
        bas = str(nom or "").strip().lower()
        if not bas:
            return False
        avant = len(self.vocabulaire)
        self.vocabulaire = [v for v in self.vocabulaire if v.lower() != bas]
        touche = len(self.vocabulaire) != avant
        # Le registre de Home Assistant fait foi : l'étiquette disparaît
        # aussi de Paramètres → Zones et étiquettes, sinon on la reverrait
        # au sélecteur alors qu'elle ne classe plus rien.
        if labels.supprimer(self.hass, nom):
            touche = True
        for genre in ("apps", "sites"):
            for cle, valeurs in list(self.etiquettes.get(genre, {}).items()):
                garde = [v for v in valeurs if v.lower() != bas]
                if len(garde) != len(valeurs):
                    touche = True
                    if garde:
                        self.etiquettes[genre][cle] = garde
                    else:
                        self.etiquettes[genre].pop(cle, None)
        for pc in self.pcs.values():
            if self.oublier_regle(pc, nom):
                touche = True
            # Et les plages qui la fermaient. Sans cela, « Soir — Jeux »
            # restait affichée et active en ne fermant plus rien ; pire,
            # recréer une étiquette « Jeux » plus tard l'aurait réveillée.
            gardees = []
            for regle in pc.get("rules") or []:
                etiquettes = [
                    e for e in (regle.get("etiquettes") or []) if e.lower() != bas
                ]
                if etiquettes != list(regle.get("etiquettes") or []):
                    touche = True
                    regle["etiquettes"] = etiquettes
                if (
                    regle.get("portee") == PORTEE_ETIQUETTES
                    and not etiquettes
                    and not (regle.get("elements") or [])
                ):
                    continue
                gardees.append(regle)
            if len(gardees) != len(pc.get("rules") or []):
                pc["rules"] = gardees
        return touche

    # ------------------------------------ Catégories utilisées par Vision

    def _porteuses(self) -> dict[str, int]:
        """Combien de sites et de logiciels chaque étiquette range (clé en minuscules)."""
        compte: dict[str, int] = {}
        for genre in ("apps", "sites"):
            for valeurs in (self.etiquettes.get(genre) or {}).values():
                for v in valeurs or []:
                    compte[str(v).lower()] = compte.get(str(v).lower(), 0) + 1
        return compte

    def categorie_par_defaut(self, nom: str, porteuses: dict[str, int] | None = None) -> bool:
        """Cochée d'office : elle range au moins un site ou un logiciel, ou le code la lit."""
        bas = str(nom or "").lower()
        porteuses = self._porteuses() if porteuses is None else porteuses
        return bas in porteuses or bas in labels.CATEGORIES_LUES_PAR_LE_CODE

    def categorie_cochee(self, label_id: str, nom: str, porteuses: dict[str, int] | None = None) -> bool:
        choix = self.categories_choix.get(str(label_id))
        return self.categorie_par_defaut(nom, porteuses) if choix is None else bool(choix)

    def categories_cochees(self) -> set[str]:
        """Les noms (en minuscules) des étiquettes cochées comme catégories de Vision."""
        porteuses = self._porteuses()
        return {
            e["nom"].lower()
            for e in labels.details(self.hass)
            if self.categorie_cochee(e["id"], e["nom"], porteuses)
        }

    def usages_etiquette(self, nom: str) -> list[str]:
        """Où cette étiquette s'applique encore, dit pour un parent (hors rangement des éléments)."""
        bas = str(nom or "").lower()
        if not bas:
            return []
        dans = lambda liste: any(str(v).lower() == bas for v in liste or [])  # noqa: E731
        sortie: list[str] = []
        lg = textes.ha(self.hass)
        if dans(self.bannis):
            sortie.append(textes.t("store.u_bloquee_tous", lg))
        if dans(self.autorises_maison):
            sortie.append(textes.t("store.u_autorisee_tous", lg))
        for pc in sorted(self.pcs.values(), key=lambda p: (p.get("name") or "").lower()):
            nom_pc = pc.get("name") or pc.get("id")
            for regle in pc.get("rules") or []:
                if dans(regle.get("etiquettes")):
                    sortie.append(textes.t("store.u_plage", lg, plage=regle.get('name'), appareil=nom_pc))
            if dans(pc.get("bloques")):
                sortie.append(textes.t("store.u_bloquee_sur", lg, appareil=nom_pc))
            if dans(pc.get("autorises")):
                sortie.append(textes.t("store.u_autorisee_sur", lg, appareil=nom_pc))
        for personne, fiches in sorted((getattr(self, "fermetures", None) or {}).items()):
            if any(str(f.get("etiquette") or "").lower() == bas for f in (fiches or {}).values()):
                prenom = personne.split(".")[-1].replace("_", " ").title()
                sortie.append(textes.t("store.u_automatisation", lg, prenom=prenom))
        for pc in self.pcs.values():
            if any(n.lower() == bas for n in labels.noms_du_pc(self.hass, pc["id"])):
                sortie.append(textes.t("store.u_posee", lg, appareil=pc.get('name') or pc['id']))
        return sortie

    def etiquettes_visibles(self) -> set[str]:
        """Ce que Vision montre et propose (en minuscules) : les cochées, plus celles encore
        posées quelque part. Décocher ne fait rien disparaître de ce qui s'applique."""
        garder = set(self.categories_cochees())
        porteuses = self._porteuses()
        for e in labels.details(self.hass):
            bas = e["nom"].lower()
            if bas not in garder and (bas in porteuses or self.usages_etiquette(e["nom"])):
                garder.add(bas)
        return garder

    def choisir_categories(self, choix: dict[str, bool]) -> bool:
        """Coche ou décoche des étiquettes (par nom). Ne garde que les écarts au défaut."""
        par_nom = {e["nom"].lower(): e for e in labels.details(self.hass)}
        touche = False
        for nom, coche in (choix or {}).items():
            e = par_nom.get(str(nom).strip().lower())
            if e is None:
                raise textes.Erreur("store.etiquette_absente", nom=nom)
            avant = dict(self.categories_choix)
            if bool(coche) == self.categorie_par_defaut(e["nom"]):
                self.categories_choix.pop(e["id"], None)
            else:
                self.categories_choix[e["id"]] = bool(coche)
            touche = touche or avant != self.categories_choix
        return touche

    def verifier_etiquettes_plage(self, voulues, deja) -> list[str]:
        """Les étiquettes d'une plage, au nom exact du registre ; refuse, en disant où
        cocher, celles qu'on ajoute et qui ne sont pas des catégories de Vision.

        Celles que la plage porte déjà passent toujours, même décochées ou disparues :
        changer l'heure d'une plage ne doit pas en modifier le contenu en silence.
        Vision ne crée plus d'étiquette depuis le planning."""
        registre = {n.lower(): n for n in labels.vocabulaire(self.hass)}
        anciennes = {str(e).lower(): str(e) for e in deja or []}
        cochees = self.categories_cochees()
        sortie: list[str] = []
        for brut in voulues or []:
            nom = str(brut or "").strip()
            bas = nom.lower()
            if not nom:
                continue
            if bas in anciennes:
                nom = anciennes[bas]
            elif bas not in registre:
                raise textes.Erreur("store.etiquette_a_creer", nom=nom, ou=lambda lg: textes.t("store.ou_creer", lg))
            elif bas not in cochees:
                raise textes.Erreur("store.etiquette_pas_cochee", nom=registre[bas],
                                    ou=lambda lg: textes.t("store.ou_cocher", lg))
            else:
                nom = registre[bas]
            if nom.lower() not in [s.lower() for s in sortie]:
                sortie.append(nom)
        return sortie

    def toutes_etiquettes(self) -> list[str]:
        """Vocabulaire des étiquettes, tous logiciels et sites confondus.

        Celles qui sont posées quelque part **et** celles qu'on a seulement
        déclarées : les secondes doivent être utilisables dans une règle
        avant de servir à étiqueter quoi que ce soit.
        """
        vues: dict[str, str] = {}
        # Le registre de Home Assistant d'abord : c'est lui le vocabulaire.
        for valeur in labels.vocabulaire(self.hass):
            vues.setdefault(valeur.lower(), valeur)
        for genre in ("apps", "sites"):
            for valeurs in self.etiquettes.get(genre, {}).values():
                for valeur in valeurs:
                    vues.setdefault(valeur.lower(), valeur)
        for valeur in self.vocabulaire:
            vues.setdefault(valeur.lower(), valeur)
        return [vues[c] for c in sorted(vues)]

    # ------------------------------------ Étiquettes : poser, renommer, PC

    def poser_etiquettes(self, genre: str, nom: str, valeurs) -> bool:
        """Remplace d'un coup les étiquettes d'un élément.

        Le sélecteur natif rend des `label_id` ; une automatisation écrite
        à la main rend des noms. On accepte les deux et on range en noms —
        c'est ce que lit le reste du code depuis toujours.
        """
        voulues = labels.noms(self.hass, valeurs)
        if [v.lower() for v in voulues] == [
            v.lower() for v in self.etiquettes_de(genre, nom)
        ]:
            return False
        self.definir_etiquettes(genre, nom, voulues)
        return True

    def renommer_etiquette(self, ancien: str, nouveau: str) -> bool:
        """Suit un renommage fait dans Home Assistant, partout à la fois.

        Les étiquettes se stockent par leur **nom** : renommée dans
        Paramètres sans être suivie ici, une étiquette laisserait derrière
        elle des règles qui ne visent plus rien — et qui se rallumeraient
        le jour où quelqu'un recréerait l'ancien nom.
        """
        bas = str(ancien or "").strip().lower()
        neuf = str(nouveau or "").strip()
        if not bas or not neuf or bas == neuf.lower():
            return False
        touche = False

        def _remplacer(liste) -> list[str]:
            nonlocal touche
            sortie: list[str] = []
            for valeur in liste or []:
                mot = str(valeur).strip()
                if mot.lower() == bas:
                    mot = neuf
                    touche = True
                if mot and mot.lower() not in [s.lower() for s in sortie]:
                    sortie.append(mot)
            return sortie

        for genre in ("apps", "sites"):
            for cle, valeurs in list(self.etiquettes.get(genre, {}).items()):
                self.etiquettes[genre][cle] = _remplacer(valeurs)
        self.bannis = _remplacer(self.bannis)
        self.autorises_maison = _remplacer(self.autorises_maison)
        self.vocabulaire = _remplacer(self.vocabulaire)
        for pc in self.pcs.values():
            for champ in ("bloques", "autorises"):
                pc[champ] = _remplacer(pc.get(champ))
            for regle in pc.get("rules") or []:
                regle["etiquettes"] = _remplacer(regle.get("etiquettes"))
        return touche

    def etiquette_bloquee(self, pc: dict[str, Any], nom: str) -> bool:
        """Cette étiquette est-elle coupée sur ce PC ?"""
        return self._dans(pc.get("bloques"), nom)

    def basculer_etiquette(
        self, pc: dict[str, Any], nom: str, bloquer: bool
    ) -> bool:
        """Allume ou éteint une étiquette sur un PC.

        Allumée, tout ce qui la porte est coupé sur cette machine ;
        éteinte, la machine n'en dit plus rien — ce qui n'est pas la même
        chose que « autorisé », décision qui se prend ailleurs et reste
        plus forte.
        """
        nom = str(nom or "").strip()
        if not nom:
            raise textes.Erreur("store.choisir_etiquette")
        bloques = [
            str(v).strip() for v in (pc.get("bloques") or []) if str(v).strip()
        ]
        present = self._dans(bloques, nom)
        if bloquer and not present:
            bloques.append(nom)
        elif not bloquer and present:
            bloques = [v for v in bloques if v.lower() != nom.lower()]
        else:
            return False
        pc["bloques"] = bloques
        return True

    def _plateformes_de(self, genre: str, nom: str) -> list[str]:
        """Sur quels types d'appareils cet élément a été vu : pc, android."""
        champ = "apps_vues" if genre == "apps" else "sites_vus"
        vues: set[str] = set()
        for pc in self.pcs.values():
            if nom in (pc.get(champ) or {}):
                vues.add("android" if (pc.get("platform") or "") == "android" else "pc")
        return sorted(vues)

    def _libelle_de(self, genre: str, nom: str) -> str:
        """Nom affiché d'une appli Android, s'il est connu."""
        if genre != "apps":
            return ""
        for pc in self.pcs.values():
            fiche = (pc.get("apps_vues") or {}).get(nom) or {}
            if fiche.get("libelle"):
                return str(fiche["libelle"])
        return ""

    def catalogue_etiquetage(self) -> dict[str, Any]:
        """Tout ce que la carte d'étiquetage a besoin de savoir, d'un coup.

        Servi par une vue HTTP plutôt que par les attributs d'un capteur :
        un catalogue de plusieurs centaines de lignes n'a rien à faire dans
        l'historique de Home Assistant, réécrit à chaque changement.
        """
        etiquettes = labels.details(self.hass)
        compte: dict[str, dict[str, int]] = {}
        elements: list[dict[str, Any]] = []
        for genre in ("apps", "sites"):
            for nom in self.catalogue_maison(genre):
                posees = self.etiquettes_de(genre, nom)
                for etiquette in posees:
                    compte.setdefault(
                        etiquette.lower(), {"apps": 0, "sites": 0}
                    )[genre] += 1
                elements.append(
                    {
                        "genre": genre,
                        "nom": nom,
                        "etiquettes": posees,
                        "classe": self.classe(None, genre, nom),
                        "plateformes": self._plateformes_de(genre, nom),
                        "libelle": self._libelle_de(genre, nom),
                    }
                )
        for etiquette in etiquettes:
            vues = compte.get(etiquette["nom"].lower()) or {}
            etiquette["apps"] = vues.get("apps", 0)
            etiquette["sites"] = vues.get("sites", 0)
            # La case « Catégorie Vision », son défaut, et ce qui s'applique encore.
            etiquette["categorie"] = self.categorie_cochee(etiquette["id"], etiquette["nom"])
            etiquette["defaut"] = self.categorie_par_defaut(etiquette["nom"])
            etiquette["lue_par_le_code"] = etiquette["nom"].lower() in labels.CATEGORIES_LUES_PAR_LE_CODE
            etiquette["usages"] = self.usages_etiquette(etiquette["nom"])
            etiquette["bannie"] = self.est_banni(etiquette["nom"])
            etiquette["autorisee"] = self.est_autorise_maison(etiquette["nom"])
            etiquette["pcs"] = [
                pc["id"]
                for pc in self.pcs.values()
                if self._dans(pc.get("bloques"), etiquette["nom"])
            ]
        return {
            "etiquettes": etiquettes,
            "elements": elements,
            "pcs": [
                {
                    "id": pc["id"],
                    "nom": pc.get("name") or pc["id"],
                    "etiquettes": labels.noms_du_pc(self.hass, pc["id"]),
                }
                for pc in sorted(
                    self.pcs.values(),
                    key=lambda p: (p.get("name") or "").lower(),
                )
            ],
        }

    # ---------------------------------------------------- Ce qui s'applique

    @staticmethod
    def _dans(liste: list[str] | None, valeur: str) -> bool:
        bas = valeur.strip().lower()
        if not liste:
            return False
        try:
            return bas in _ensemble_bas(tuple(liste))
        except TypeError:
            return any((m or "").strip().lower() == bas for m in liste)

    def noms_par_etiquette(self, genre: str, etiquette: str) -> list[str]:
        """Les logiciels (ou sites) du catalogue qui portent cette étiquette.
        Un seul index pour tous les interrupteurs, refait au plus toutes les 5 s."""
        def _index() -> dict[str, list[str]]:
            idx: dict[str, list[str]] = {}
            for n in self.catalogue_maison(genre):
                for e in self.etiquettes_de(genre, n):
                    idx.setdefault(str(e).lower(), []).append(n)
            return idx
        return list(self._memo(("index_etq", genre), 5.0, _index).get(etiquette.lower(), []))

    def genre_coupe(self, genre: str) -> bool:
        """1.82.0 : le module qui ferme ce genre est-il coupé ? (apps : « blocage », sites : « filtrage »)."""
        return _modules.coupe(self, "blocage" if genre == "apps" else "filtrage")

    def statut(self, pc: dict[str, Any], genre: str, nom: str) -> str:
        return self._memo(("statut", (pc or {}).get("id") or "", genre, nom), 5.0, lambda: self._statut_calc(pc, genre, nom))

    def _statut_calc(self, pc: dict[str, Any], genre: str, nom: str) -> str:
        """Autorisé ou bloqué, pour ce PC.

        Deux étages, et c'est tout le système.

        **La maison décide d'abord, et sans appel.** Ce qui est banni pour
        la famille est fermé partout : ni une autorisation posée sur une
        machine, ni une étiquette, ni un laissez-passer ne le rouvre. C'est
        la seule décision irrévocable — c'est ce qui lui donne son sens.

        **Le PC décide ensuite, et le plus restrictif l'emporte.** À cet
        étage, « autorisé » veut dire *admis dans la maison*, pas
        *intouchable* : YouTube peut être autorisé chez nous et fermé sur
        le PC d'un enfant en période d'école. Une étiquette coupée sur la
        machine, ou une plage en cours, ferme donc un site autorisé — c'est
        exactement ce qu'on attend d'un contrôle parental.

        L'ordre, du plus fort au plus faible :

        1. le **nom banni pour la maison** — définitif ;
        2. une de ses **étiquettes bannies pour la maison** — définitif ;
        3. un **laissez-passer** en cours, puis l'**adresse exacte
           épargnée** : les deux portes qu'un adulte ouvre exprès. Elles
           percent l'étage du PC, jamais celui de la maison ;
        4. une **plage qui cite ce nom** à cette heure ;
        5. le **nom** cité dans les bloqués du PC ;
        6. une **plage** qui ferme l'une de ses étiquettes à cette heure ;
        7. le **nom** cité dans les autorisés du PC — la dérogation par
           machine : elle exempte des étiquettes coupées ici, rien de plus ;
        8. une de ses **étiquettes** coupée sur ce PC ;
        9. une de ses **étiquettes** autorisée sur ce PC ;
        10. sinon **autorisé** : ce que personne n'a fermé passe.

        Ainsi « bloque les jeux, sauf minecraft » s'écrit toujours en deux
        lignes, et « autorise YouTube, mais pas pendant l'école » aussi —
        ce qui était impossible avant 1.48.0 : une autorisation de maison
        rendait alors un site insensible à toutes les étiquettes, et
        l'étiquette « Vidéos » coupée sur un PC ne fermait rien.

        Un test échoue si un blocage de maison redevient franchissable, si
        une autorisation repasse devant une étiquette ou une plage, ou si
        le défaut cesse d'être « autorisé ».
        """
        # 1.82.0 : « Blocage d'applis » (apps) ou « Filtrage web » (sites) coupé : plus rien ne ferme ce
        # genre (ni maison, ni catégories, ni âge, ni noms, ni plages d'étiquettes), sauf la limite du
        # jour d'une appli, qui relève du module « Temps d'écran ».
        if self.genre_coupe(genre):
            if self.limite_atteinte(pc, genre, nom) is not None and not self._laissez_passer(pc, genre, nom):
                return BLOQUE
            return AUTORISE
        bloques = pc.get("bloques") or []
        autorises = pc.get("autorises") or []
        # Une règle porte sur un site, jamais sur l'un de ses sous-domaines :
        # « m.youtube.com » se juge donc comme « youtube.com ».
        cible = _nom_app(nom) if genre == "apps" else _nom_site(nom)
        etiquettes = self.etiquettes_de(genre, nom)

        if not getattr(self, "autorisations_v2", False):
            return self._statut_v1(pc, genre, nom)
        # --- Le blocage explicite, sans appel (refonte, lot 1) --------------
        # Le Lieu, une catégorie de sécurité ou d'âge fermée ici, un nom bloqué exprès sur cet appareil : aucune
        # autorisation ne passe (CONCEPTION-autorisations.md § 2). Une adresse exacte épargnée garde son sens
        # sous un site bloqué nommément (l'adresse précise d'un cours sous « youtube.com »).
        # Le Lieu et une catégorie de sécurité ou d'âge fermée ici : aucune autorisation ne passe
        # (CONCEPTION-autorisations.md § 2). Le « Bloquer » d'un Décideur sur ce nom cède à un « pour un temps »
        # (la rallonge qu'il accorde lui-même) ; une exception ou « toujours disponible » le lève à l'octroi.
        # Une adresse exacte épargnée garde son sens sous un site bloqué nommément.
        explicite = self.bloque_explicite(pc, genre, nom)
        if explicite in ("lieu", "securite"):
            return BLOQUE
        mode = self.mode_accorde(pc, genre, cible)

        # --- Les deux portes qu'un adulte ouvre exprès ---------------------
        if self._laissez_passer(pc, genre, nom):
            return AUTORISE
        if explicite == "nomme" and not self._excepte(pc, genre, nom):
            return BLOQUE
        # --- « Toujours disponible » : ni catégorie, ni plage, ni temps --------
        if mode == "toujours":
            return AUTORISE
        # La limite de temps du jour : un laissez-passer la perce (c'est la
        # rallonge qu'un adulte accorde), une autorisation permanente non,
        # sinon limiter un logiciel autorisé ne voudrait rien dire.
        if self.limite_atteinte(pc, genre, nom) is not None:
            return BLOQUE
        if self._excepte(pc, genre, nom):
            return AUTORISE

        # --- Le PC : le plus restrictif l'emporte -------------------------
        # Une plage qui **cite ce nom** est ce qu'on peut dire de plus
        # précis sur lui : elle passe avant les listes, sinon « Discord
        # autorisé » viderait de son sens « plus de Discord après 21h ».
        if self.plage_nomme(pc, genre, nom) is not None:
            return BLOQUE
        if self._dans(bloques, cible):
            return BLOQUE
        # La plage horaire passe devant l'autorisation par machine : sans
        # cela, un site autorisé sur le PC ne connaîtrait plus d'horaires,
        # et « plus d'écrans après 21h » ne voudrait rien dire.
        if self._plage_de(pc, genre, nom) is not None:
            return BLOQUE
        # « Exception » du Demandeur (toutes ses machines), ou l'ancienne autorisation posée sur ce PC : passe la
        # catégorie fermée, suit les plages et le temps (déjà jugés plus haut).
        if mode == "exception" or self._dans(autorises, cible):
            return AUTORISE
        # Une catégorie fermée par une automatisation : comme une catégorie coupée par les parents, donc
        # derrière le « toujours autorisé » posé sur ce nom, qui reste ouvert.
        if self.fermeture_de(pc, etiquettes) is not None:
            return BLOQUE
        if any(self._dans(bloques, e) for e in etiquettes):
            return BLOQUE
        if any(self._dans(autorises, e) for e in etiquettes):
            return AUTORISE

        # Ce que la maison autorise n'a plus rien à dire ici : à ce stade,
        # plus personne ne le ferme. L'autorisation de maison n'est pas une
        # protection, c'est une admission — la nuance est tout le sujet.
        return AUTORISE

    def _statut_v1(self, pc: dict[str, Any], genre: str, nom: str) -> str:
        """L'ancien moteur de décision (jusqu'à la reprise des autorisations) : autorisé ou bloqué, pour ce PC.

        Deux étages, et c'est tout le système.

        **La maison décide d'abord, et sans appel.** Ce qui est banni pour
        la famille est fermé partout : ni une autorisation posée sur une
        machine, ni une étiquette, ni un laissez-passer ne le rouvre. C'est
        la seule décision irrévocable — c'est ce qui lui donne son sens.

        **Le PC décide ensuite, et le plus restrictif l'emporte.** À cet
        étage, « autorisé » veut dire *admis dans la maison*, pas
        *intouchable* : YouTube peut être autorisé chez nous et fermé sur
        le PC d'un enfant en période d'école. Une étiquette coupée sur la
        machine, ou une plage en cours, ferme donc un site autorisé — c'est
        exactement ce qu'on attend d'un contrôle parental.

        L'ordre, du plus fort au plus faible :

        1. le **nom banni pour la maison** — définitif ;
        2. une de ses **étiquettes bannies pour la maison** — définitif ;
        3. un **laissez-passer** en cours, puis l'**adresse exacte
           épargnée** : les deux portes qu'un adulte ouvre exprès. Elles
           percent l'étage du PC, jamais celui de la maison ;
        4. une **plage qui cite ce nom** à cette heure ;
        5. le **nom** cité dans les bloqués du PC ;
        6. une **plage** qui ferme l'une de ses étiquettes à cette heure ;
        7. le **nom** cité dans les autorisés du PC — la dérogation par
           machine : elle exempte des étiquettes coupées ici, rien de plus ;
        8. une de ses **étiquettes** coupée sur ce PC ;
        9. une de ses **étiquettes** autorisée sur ce PC ;
        10. sinon **autorisé** : ce que personne n'a fermé passe.

        Ainsi « bloque les jeux, sauf minecraft » s'écrit toujours en deux
        lignes, et « autorise YouTube, mais pas pendant l'école » aussi —
        ce qui était impossible avant 1.48.0 : une autorisation de maison
        rendait alors un site insensible à toutes les étiquettes, et
        l'étiquette « Vidéos » coupée sur un PC ne fermait rien.

        Un test échoue si un blocage de maison redevient franchissable, si
        une autorisation repasse devant une étiquette ou une plage, ou si
        le défaut cesse d'être « autorisé ».
        """
        # 1.82.0 : « Blocage d'applis » (apps) ou « Filtrage web » (sites) coupé : plus rien ne ferme ce
        # genre (ni maison, ni catégories, ni âge, ni noms, ni plages d'étiquettes), sauf la limite du
        # jour d'une appli, qui relève du module « Temps d'écran ».
        if self.genre_coupe(genre):
            if self.limite_atteinte(pc, genre, nom) is not None and not self._laissez_passer(pc, genre, nom):
                return BLOQUE
            return AUTORISE
        bloques = pc.get("bloques") or []
        autorises = pc.get("autorises") or []
        # Une règle porte sur un site, jamais sur l'un de ses sous-domaines :
        # « m.youtube.com » se juge donc comme « youtube.com ».
        cible = _nom_app(nom) if genre == "apps" else _nom_site(nom)
        etiquettes = self.etiquettes_de(genre, nom)

        # --- La maison, sans appel ----------------------------------------
        if self.est_banni(cible):
            return BLOQUE
        if any(self.est_banni(e) for e in etiquettes):
            return BLOQUE

        # --- Les deux portes qu'un adulte ouvre exprès ---------------------
        if self._laissez_passer(pc, genre, nom):
            return AUTORISE
        # La limite de temps du jour : un laissez-passer la perce (c'est la
        # rallonge qu'un adulte accorde), une autorisation permanente non,
        # sinon limiter un logiciel autorisé ne voudrait rien dire.
        if self.limite_atteinte(pc, genre, nom) is not None:
            return BLOQUE
        if self._excepte(pc, genre, nom):
            return AUTORISE

        # --- Le PC : le plus restrictif l'emporte -------------------------
        # Une plage qui **cite ce nom** est ce qu'on peut dire de plus
        # précis sur lui : elle passe avant les listes, sinon « Discord
        # autorisé » viderait de son sens « plus de Discord après 21h ».
        if self.plage_nomme(pc, genre, nom) is not None:
            return BLOQUE
        if self._dans(bloques, cible):
            return BLOQUE
        # La plage horaire passe devant l'autorisation par machine : sans
        # cela, un site autorisé sur le PC ne connaîtrait plus d'horaires,
        # et « plus d'écrans après 21h » ne voudrait rien dire.
        if self._plage_de(pc, genre, nom) is not None:
            return BLOQUE
        if self._dans(autorises, cible):
            return AUTORISE
        # Une catégorie fermée par une automatisation : comme une catégorie coupée par les parents, donc
        # derrière le « toujours autorisé » posé sur ce nom, qui reste ouvert.
        if self.fermeture_de(pc, etiquettes) is not None:
            return BLOQUE
        if any(self._dans(bloques, e) for e in etiquettes):
            return BLOQUE
        if any(self._dans(autorises, e) for e in etiquettes):
            return AUTORISE

        # Ce que la maison autorise n'a plus rien à dire ici : à ce stade,
        # plus personne ne le ferme. L'autorisation de maison n'est pas une
        # protection, c'est une admission — la nuance est tout le sujet.
        return AUTORISE

    def classe(self, pc: dict[str, Any] | None, genre: str, nom: str) -> str:
        """Ce qui a été **décidé** pour cet élément sur ce PC.

        À ne pas confondre avec `statut`, qui donne le résultat appliqué :
        un élément peut être autorisé *parce que personne n'a rien dit*.
        Ici on distingue justement ce cas — c'est lui qui alimente la file
        de ce qui reste à trancher.

        Sans PC choisi, la question se pose au niveau de la **maison** : le
        catalogue lui appartient de toute façon, et on doit pouvoir le
        parcourir sans commencer par désigner une machine.
        """
        cible = _nom_app(nom) if genre == "apps" else _nom_site(nom)
        etiquettes = self.etiquettes_de(genre, nom)
        if pc is None:
            # Sans PC choisi, on regarde la maison : le catalogue lui
            # appartient, et une décision prise là est déjà une décision.
            if self.est_autorise_maison(cible) or any(
                self.est_autorise_maison(e) for e in etiquettes
            ):
                return FILTRE_AUTORISES
            if self.est_banni(cible) or any(self.est_banni(e) for e in etiquettes):
                return FILTRE_BLOQUES
            # 2.5.9 (l'auteur, 17/10) : un élément déjà rangé dans une catégorie et décidé sur chaque appareil des Demandeurs
            # est tranché, même sans décision pour tout le Lieu (Adulte reste ouvert aux Décideurs, choix de l'auteur).
            if etiquettes:
                decide = self._decide_chez_demandeurs(genre, nom)
                if decide:
                    return decide
            return FILTRE_A_CLASSER
        for champ, reponse in (
            ("autorises", FILTRE_AUTORISES),
            ("bloques", FILTRE_BLOQUES),
        ):
            if self._dans(pc.get(champ), cible):
                return reponse
            if any(self._dans(pc.get(champ), e) for e in etiquettes):
                return reponse
        # Une décision de maison est une décision : elle ne doit pas revenir
        # dans la file de chaque PC. Le bannissement était traité ici, son
        # symétrique manquait — « Autoriser » ne faisait donc jamais baisser
        # le compteur « reste à classer », alors que « Bloquer » le faisait.
        if self.est_autorise_maison(cible) or any(
            self.est_autorise_maison(e) for e in etiquettes
        ):
            return FILTRE_AUTORISES
        if self.est_banni(cible) or any(self.est_banni(e) for e in etiquettes):
            return FILTRE_BLOQUES
        return FILTRE_A_CLASSER

    def a_classer(self, pc: dict[str, Any] | None) -> int:
        """Combien d'éléments attendent encore une décision sur ce PC (compté au plus toutes les 5 s)."""
        import time as _t
        cle = (pc or {}).get("id") or ""
        cache = getattr(self, "_cache_a_classer", None) or {}
        if cle in cache and _t.monotonic() - cache[cle][0] < 5:
            return cache[cle][1]
        n = self._compter_a_classer(pc)
        cache[cle] = (_t.monotonic(), n)
        self._cache_a_classer = cache
        return n

    def appareils_demandeurs(self) -> list[dict[str, Any]]:
        """2.5.9 : les appareils personnels des Demandeurs : rôle effectif avec « Demander l'accès », et une personne.

        Sans personne (télé du salon, banc d'essai), un appareil a aussi le rôle Demandeur par défaut, mais il ne reçoit
        pas les décisions prises nom par nom pour les Demandeurs : il n'en est pas. Pas davantage un écran commun
        (personne d'affichage) ni un appareil d'essai. Relu au plus toutes les 5 s (la file est recomptée souvent)."""
        import time as _t

        from . import roles as _roles
        cache = getattr(self, "_cache_demandeurs", None)
        if cache is not None and _t.monotonic() - cache[0] < 5:
            return cache[1]
        sortie = [
            pc for i, pc in self.pcs.items()
            if pc.get("person") and not self._est_pseudo(str(pc["person"])) and i not in self.appareils_essai
            and _roles.a(self, pc, "demander_acces")
        ]
        self._cache_demandeurs = (_t.monotonic(), sortie)
        return sortie

    def _decide_chez_demandeurs(self, genre: str, nom: str) -> str | None:
        """Bloqué (au moins un appareil le bloque) ou autorisé, si chacun des appareils des Demandeurs (au moins un) en
        a décidé ; None sinon."""
        demandeurs = self.appareils_demandeurs()
        if not demandeurs:
            return None
        decisions = [self.classe(pc, genre, nom) for pc in demandeurs]
        if FILTRE_A_CLASSER in decisions:
            return None
        return FILTRE_BLOQUES if FILTRE_BLOQUES in decisions else FILTRE_AUTORISES

    def _compter_a_classer(self, pc: dict[str, Any] | None) -> int:
        return sum(
            1
            for genre in ("apps", "sites")
            for nom in self.catalogue_maison(genre)
            if self.classe(pc, genre, nom) == FILTRE_A_CLASSER
        )

    def _est_etiquette(self, valeur: str) -> bool:
        import time as _t
        bas = valeur.strip().lower()
        # L'ensemble des étiquettes en minuscules, recalculé au plus toutes les 5 s : cette
        # fonction est appelée des milliers de fois par relevé.
        cache = getattr(self, "_cache_etiquettes_bas", None)
        if cache is None or _t.monotonic() - cache[0] > 5:
            cache = (_t.monotonic(), {e.lower() for e in self.toutes_etiquettes()})
            self._cache_etiquettes_bas = cache
        return bas in cache[1]

    def _genre_de(self, valeur: str) -> str:
        """Logiciel ou site ? Le catalogue tranche, le point sert de repli."""
        app = _nom_app(valeur)
        # 2.3.3 : un exécutable connu d'une famille (« minecraft.windows ») reste un logiciel malgré son point.
        if app in FAMILLES_APPS.values():
            return "apps"
        site = _nom_site(valeur)
        for pc in self.pcs.values():
            if app and app in (pc.get("apps_vues") or {}):
                return "apps"
            if site and site in (pc.get("sites_vus") or {}):
                return "sites"
        if app in self.etiquettes.get("apps", {}):
            return "apps"
        if site and site in self.etiquettes.get("sites", {}):
            return "sites"
        return "sites" if "." in valeur else "apps"

    # Étiquettes qui relèvent de la sécurité : la maison les coupe pour tout le monde.
    ETIQUETTES_SECURITE = ("dangereux", "contournement", "pub & traçage", "pub & tracage")
    ETIQUETTES_AGE = ("pegi 12", "pegi 16", "pegi 18", "adulte")

    def raison_enfant(self, pc: dict[str, Any], genre: str, nom: str, langue: str | None = None) -> dict[str, str] | None:
        """Pourquoi cet élément est fermé sur ce PC, dit pour l'enfant.

        Même ordre que `statut` : la première règle qui ferme est la raison.
        Rend None si l'élément n'est pas bloqué. `code` : securite, maison,
        plage, age, regle.
        """
        cible = _nom_app(nom) if genre == "apps" else _nom_site(nom)
        etiquettes = self.etiquettes_de(genre, nom)
        bas = [e.lower() for e in etiquettes]
        lg = langue or textes.pour(getattr(self, "hass", None), pc)

        def dire(code: str, cle: str, **champs: Any) -> dict[str, str]:
            return {"code": code, "texte": textes.t(cle, lg, **champs)}

        def cat(e: str) -> str:
            return textes.categorie(e, lg)

        if self.genre_coupe(genre):
            # 1.82.0 : genre coupé (voir `_statut_calc`) : seule la limite du jour d'une appli peut fermer.
            atteinte = None if self._laissez_passer(pc, genre, nom) else self.limite_atteinte(pc, genre, nom)
            if atteinte is None:
                return None
            return dire("regle", "store.e_limite", minutes=atteinte[0])

        if self.est_banni(cible):
            if any(e in self.ETIQUETTES_SECURITE for e in bas):
                return dire("securite", "store.e_securite")
            return dire("maison", "commun.interdit_tous")
        for e in etiquettes:
            if self.est_banni(e):
                if e.lower() in self.ETIQUETTES_SECURITE:
                    return dire("securite", "store.e_securite_cat", categorie=cat(e))
                return dire("maison", "store.e_interdit_cat", categorie=cat(e))
        if self._laissez_passer(pc, genre, nom):
            return None
        atteinte = self.limite_atteinte(pc, genre, nom)
        if atteinte is not None:
            # Code « regle » : les applis déjà installées savent le ranger ; le texte dit la vraie raison.
            return dire("regle", "store.e_limite", minutes=atteinte[0])
        if self._excepte(pc, genre, nom):
            return None
        regle = self.plage_nomme(pc, genre, nom)
        if regle is not None:
            return dire("plage", "store.e_planning", plage=regle.get("name") or textes.t("commun.plage", lg),
                        debut=_hm(regle.get("start")), fin=_hm(regle.get("end")))
        bloques = pc.get("bloques") or []
        if self._dans(bloques, cible):
            return dire("regle", "store.e_coupe")
        trouve = self._plage_de(pc, genre, nom)
        if trouve is not None:
            regle, etiquette = trouve
            return dire("plage", "store.e_planning_cat", plage=regle.get("name") or textes.t("commun.plage", lg),
                        categorie=cat(etiquette), debut=_hm(regle.get("start")), fin=_hm(regle.get("end")))
        if not self._dans(pc.get("autorises") or [], cible):
            fermeture = self.fermeture_de(pc, etiquettes)
            if fermeture is not None:
                # Code « regle » : les applis déjà installées savent le ranger ; le texte dit la vraie raison.
                # La raison d'une automatisation est écrite par la maison : elle passe telle quelle.
                if fermeture.get("raison"):
                    return {"code": "regle", "texte": str(fermeture.get("raison"))}
                return dire("regle", "store.e_categorie_fermee", categorie=cat(str(fermeture.get("etiquette") or "")))
        for e in etiquettes:
            if self._dans(bloques, e):
                if e.lower() in self.ETIQUETTES_AGE:
                    return dire("age", "store.e_age", categorie=cat(e))
                return dire("regle", "store.e_coupe_cat", categorie=cat(e))
        if self.statut(pc, genre, nom) == BLOQUE:
            return dire("regle", "store.e_coupe")
        return None

    @staticmethod
    def serveurs_partages_requis(pc: dict[str, Any]) -> bool:
        """Faut-il couper aussi les serveurs que YouTube partage avec YouTube Music sur cet appareil ?

        Oui sur un Android qui ne remonte pas l'accessibilité active : il ne ferme l'appli qu'à la relève
        suivante des statistiques d'usage, et une vidéo (ou un cast) a le temps de démarrer. Non ailleurs :
        avec l'accessibilité, l'appli est fermée à l'ouverture ; sur un PC, YouTube passe par le navigateur,
        déjà coupé par youtube.com."""
        agent = pc.get("agent") or {}
        if str(pc.get("platform") or agent.get("platform") or "") != "android":
            return False
        return not bool((agent.get("protections") or {}).get("accessibilite"))

    def effectif(self, pc: dict[str, Any]) -> dict[str, list[str]]:
        # Demandé par le relevé de l'agent, le capteur et l'espace parents dans la même
        # seconde : calculé une fois par PC toutes les 5 s (et à chaque écriture).
        return copy.deepcopy(self._memo(("effectif", (pc or {}).get("id") or ""), 5.0, lambda: self._effectif_calc(pc)))

    def _effectif_calc(self, pc: dict[str, Any]) -> dict[str, list[str]]:
        """Ce que l'agent doit réellement appliquer.

        L'agent ne connaît ni les étiquettes, ni les familles, ni l'heure
        à laquelle un laissez-passer expire : Home Assistant résout tout et
        ne lui transmet que des noms. Un site jamais constaté mais bloqué
        nommément doit l'être quand même — on ne se limite donc pas au
        catalogue. Les adresses épargnées, elles, voyagent à part : c'est
        l'agent qui étend un domaine bloqué à ses sous-domaines, lui seul
        peut donc savoir lesquels ne pas toucher.
        """
        self.purger_laissez_passer(pc)
        apps: list[str] = []
        sites: list[str] = []
        # Pourquoi chaque nom est fermé : l'agent le dit à l'enfant. Les textes
        # sont mutualisés (motifs) et chaque nom pointe dessus (raisons).
        motifs: list[str] = []
        raisons: dict[str, int] = {}

        def _ajouter(genre: str, nom: str) -> None:
            # Une décision porte sur un service, l'agent bloque des noms :
            # on lui transmet toute la famille, sans quoi couper
            # « roblox.com » laisserait le jeu vivre sur « rbxcdn.com ».
            cible = apps if genre == "apps" else sites
            membres = membres_app(nom) if genre == "apps" else membres_site(nom)
            if genre == "sites" and not self.serveurs_partages_requis(pc):
                # googlevideo.com sert aussi YouTube Music : on ne le coupe pas en coupant YouTube quand
                # l'appareil sait fermer l'appli elle-même. Nommé exprès, il reste coupé.
                membres = [m for m in membres if m == nom or m not in SERVEURS_PARTAGES]
            idx = -1
            try:
                r = self.raison_enfant(pc, genre, nom)
            except Exception:  # noqa: BLE001
                r = None
            if r:
                texte = "{}|{}".format(r["code"], r["texte"])
                if texte not in motifs:
                    motifs.append(texte)
                idx = motifs.index(texte)
            for membre in membres:
                if membre and membre not in cible:
                    cible.append(membre)
                if membre and idx >= 0 and membre not in raisons:
                    raisons[membre] = idx

        # Les noms cités directement dans les bloqués du PC, puis ceux
        # bannis pour toute la maison.
        for valeur in list(pc.get("bloques") or []) + list(self.bannis):
            valeur = (valeur or "").strip()
            if not valeur or self._est_etiquette(valeur):
                continue
            genre = self._genre_de(valeur)
            if self.statut(pc, genre, valeur) == BLOQUE:
                _ajouter(genre, valeur)

        # Tout ce que le catalogue connaît et que les règles condamnent.
        for genre, champ in (("apps", "apps_vues"), ("sites", "sites_vus")):
            connus: set[str] = set(self.etiquettes.get(genre, {}))
            for autre in self.pcs.values():
                connus.update(autre.get(champ) or {})
            for nom in connus:
                if self.statut(pc, genre, nom) == BLOQUE:
                    _ajouter(genre, nom)

        # 1.88.0 : mode concentration : toutes les autres applis connues de cet appareil se ferment.
        from . import concentration as _concentration

        conc = None if self.genre_coupe("apps") or not _modules.actif(self, "concentration") else _concentration.active(pc)
        if conc is not None:
            texte_c = _concentration.motif(conc, textes.pour(getattr(self, "hass", None), pc))
            if texte_c not in motifs:
                motifs.append(texte_c)
            idx_c = motifs.index(texte_c)
            for nom_c in _concentration.a_fermer(pc, conc, _nom_app):
                if nom_c not in apps:
                    apps.append(nom_c)
                raisons.setdefault(nom_c, idx_c)

        # 1.91.0 : règles selon le lieu : tant que la personne est dans la zone, ses catégories choisies se ferment.
        if _modules.actif(self, "regles_lieu"):
            from . import regles_lieu as _lieu

            for regle in _lieu.actives(getattr(self, "hass", None), self, pc):
                texte_l = _lieu.motif(getattr(self, "hass", None), regle, textes.pour(getattr(self, "hass", None), pc))
                if texte_l not in motifs:
                    motifs.append(texte_l)
                idx_l = motifs.index(texte_l)
                for genre_l, cible_l, membres_l in (("apps", apps, membres_app), ("sites", sites, membres_site)):
                    if self.genre_coupe(genre_l):
                        continue
                    for etq in regle["etiquettes"]:
                        for nom_l in self.noms_par_etiquette(genre_l, etq):
                            for membre in membres_l(nom_l):
                                if membre and membre not in cible_l:
                                    cible_l.append(membre)
                                if membre:
                                    raisons.setdefault(membre, idx_l)

        # Ce que l'agent doit laisser tranquille, quoi qu'il arrive :
        #   * les adresses épargnées, à l'intérieur d'un site bloqué ;
        #   * les sites sous laissez-passer ;
        #   * et ce qui est **explicitement autorisé** sur ce PC — y compris
        #     contre la liste noire. Cent cinquante mille domaines signalés
        #     par des tiers, c'est cent cinquante mille occasions de se
        #     tromper : il faut pouvoir dire « celui-là, je le connais ».
        epargnes = list(self.exceptions(pc))

        def _epargner(valeur: str) -> None:
            """Épargne un nom — mais seulement s'il passe **sur ce PC**.

            Une autorisation de la maison ne doit pas défaire un blocage
            posé sur une machine : « autorisé partout, sauf sur le PC des
            enfants » est exactement ce qu'on vient écrire ici. On repasse
            donc par `statut`, qui sait déjà qui l'emporte, au lieu de
            recopier une liste dont on ne sait plus si elle s'applique.
            """
            valeur = str(valeur).strip()
            if not valeur or self._est_etiquette(valeur):
                return
            genre = self._genre_de(valeur)
            nom = self._cle(genre, valeur)
            if not nom or nom in epargnes:
                return
            if self.statut(pc, genre, nom) != AUTORISE:
                return
            if getattr(self, "autorisations_v2", False) and genre == "sites" and self.est_dangereux(nom):
                return
            epargnes.append(nom)

        for valeur in list(pc.get("autorises") or []) + list(self.autorises_maison):
            _epargner(valeur)
        # Lot 1 : les sites accordés durablement au Demandeur (jamais contre la liste noire : seule une adresse
        # exacte épargnée le fait).
        for nom_d in self.noms_durables(pc, "sites"):
            if nom_d not in epargnes and not self.est_dangereux(nom_d) and self.statut(pc, "sites", nom_d) == AUTORISE:
                epargnes.append(nom_d)
        for domaine, _fin in self.laissez_passer_actifs(pc):
            if domaine.startswith("app:"):
                continue
            if domaine not in epargnes:
                epargnes.append(domaine)
        # 2.0.3 : une appli autorisée sur ce PC garde les sites dont elle dépend (Roblox et ses serveurs
        # d'images), même sous une catégorie fermée. Un site bloqué nommément sur le PC ou dans toute la
        # maison reste bloqué : c'est une décision prise exprès.
        if not self.genre_coupe("sites"):
            nommes = {
                self._cle("sites", str(v).strip())
                for v in list(pc.get("bloques") or []) + list(self.bannis)
                if str(v).strip() and not self._est_etiquette(str(v).strip()) and self._genre_de(str(v).strip()) == "sites"
            }
            ouvertes: list[str] = []
            for valeur in list(pc.get("autorises") or []) + list(self.autorises_maison):
                valeur = str(valeur).strip()
                if not valeur or self._est_etiquette(valeur) or self._genre_de(valeur) != "apps":
                    continue
                ouvertes.append(_nom_app(valeur))
            # 2.4.4 : une appli ouverte « pour un temps » garde aussi ses sites, le temps de l'accord ; à
            # l'échéance, le laissez-passer est purgé et les sites se referment avec l'appli.
            for cle_lp, _fin in self.laissez_passer_actifs(pc):
                if cle_lp.startswith("app:") and cle_lp[4:]:
                    ouvertes.append(cle_lp[4:])
            # Lot 1 : une appli accordée en exception ou « toujours disponible » garde aussi ses sites.
            ouvertes += self.noms_durables(pc, "apps")
            for app in dict.fromkeys(ouvertes):
                deps = self.dependances(app)
                if not deps or self.statut(pc, "apps", app) != AUTORISE:
                    continue
                for dep in deps:
                    if dep in nommes:
                        continue
                    if dep in sites:
                        sites.remove(dep)
                        raisons.pop(dep, None)
                    if dep not in epargnes:
                        epargnes.append(dep)
        # 1.82.0 : un genre coupé n'a plus d'exceptions à transmettre (elles restent enregistrées).
        if self.genre_coupe("sites") or self.genre_coupe("apps"):
            epargnes = [e for e in epargnes if not self.genre_coupe(self._genre_de(e))]
        return {
            "apps": sorted(apps),
            "sites": sorted(sites),
            # L'agent bloque un domaine avec tous ses sous-domaines : il lui
            # faut donc savoir lesquels il doit laisser tranquilles.
            "exceptions": sorted(epargnes),
            "motifs": motifs,
            "raisons": raisons,
        }

    def definir_bloques(
        self, pc: dict[str, Any], genre: str, liste: list[str]
    ) -> None:
        """Remplace les **noms** bloqués d'un genre, sans toucher au reste.

        Les services historiques `set_apps` et `set_sites` ne connaissent
        qu'un genre à la fois : ils ne doivent effacer ni l'autre genre ni
        les règles par étiquette.
        """
        garde = [
            m
            for m in (pc.get("bloques") or [])
            if self._est_etiquette(m) or self._genre_de(m) != genre
        ]
        propres: list[str] = []
        for valeur in liste:
            valeur = str(valeur).strip()
            valeur = _nom_app(valeur) if genre == "apps" else _nom_site(valeur)
            if valeur and valeur not in propres:
                propres.append(valeur)
        pc["bloques"] = garde + propres

    def basculer(self, pc: dict[str, Any], valeur: str, bloquer: bool) -> bool:
        """Range un nom ou une étiquette dans les bloqués ou les autorisés.

        Une même valeur ne peut pas être dans les deux listes : la poser
        d'un côté la retire de l'autre. Rend True si quelque chose a changé.
        """
        valeur = (valeur or "").strip()
        if not valeur:
            return False
        vers = "bloques" if bloquer else "autorises"
        depuis = "autorises" if bloquer else "bloques"
        change = False
        if self._dans(pc.get(depuis), valeur):
            pc[depuis] = [
                m for m in pc[depuis] if m.strip().lower() != valeur.lower()
            ]
            change = True
        if not self._dans(pc.get(vers), valeur):
            pc.setdefault(vers, []).append(valeur)
            change = True
        return change

    def decider_maison(self, valeur: str, bloquer: bool) -> bool:
        """Tranche pour toute la maison. Rend True si quelque chose a changé.

        Une valeur ne peut pas être dans les deux listes : la poser d'un
        côté la retire de l'autre.
        """
        valeur = (valeur or "").strip()
        if not valeur:
            raise textes.Erreur("store.rien_selectionne")
        if bloquer and self.est_intouchable(valeur):
            raise textes.Erreur("store.essentiel", nom=valeur)
        vers = self.bannis if bloquer else self.autorises_maison
        depuis = self.autorises_maison if bloquer else self.bannis
        change = False
        if self._dans(depuis, valeur):
            depuis[:] = [v for v in depuis if v.strip().lower() != valeur.lower()]
            change = True
        if not self._dans(vers, valeur):
            vers.append(valeur)
            vers.sort(key=str.lower)
            change = True
        return change

    def oublier_maison(self, valeur: str) -> bool:
        """Retire des deux listes de la maison : retour au défaut."""
        bas = (valeur or "").strip().lower()
        change = False
        for liste in (self.bannis, self.autorises_maison):
            avant = len(liste)
            liste[:] = [v for v in liste if v.strip().lower() != bas]
            if len(liste) != avant:
                change = True
        return change

    def est_autorise_maison(self, valeur: str) -> bool:
        return self._dans(self.autorises_maison, valeur)

    def bannir(self, valeur: str) -> bool:
        """Interdit un nom ou une étiquette sur toutes les machines.

        Certains sites ne se discutent pas machine par machine. Les
        interdire quatre fois serait quatre occasions d'en oublier une —
        et la cinquième machine, celle qu'on installera l'an prochain,
        serait ouverte sans que personne n'y pense.
        """
        valeur = (valeur or "").strip()
        if not valeur:
            raise textes.Erreur("store.rien_selectionne")
        if self.est_banni(valeur):
            return False
        self.bannis.append(valeur)
        self.bannis.sort(key=str.lower)
        return True

    def lever_bannissement(self, valeur: str) -> bool:
        bas = (valeur or "").strip().lower()
        avant = len(self.bannis)
        self.bannis = [v for v in self.bannis if v.strip().lower() != bas]
        return len(self.bannis) != avant

    def est_banni(self, valeur: str) -> bool:
        return self._dans(self.bannis, valeur)

    def oublier_regle(self, pc: dict[str, Any], valeur: str) -> bool:
        """Retire une valeur des deux listes : retour au comportement par défaut."""
        valeur = (valeur or "").strip()
        if not valeur:
            return False
        change = False
        for champ in ("bloques", "autorises"):
            if self._dans(pc.get(champ), valeur):
                pc[champ] = [
                    m for m in pc[champ] if m.strip().lower() != valeur.lower()
                ]
                change = True
        return change

    def delete_pc(self, pc_id: str) -> None:
        """Retire le PC et laisse à son agent l'ordre de se désinstaller.

        L'agent ne sait pas qu'on l'a supprimé : il continuerait à interroger
        Home Assistant indéfiniment, et le poste resterait bridé. On garde
        donc son secret le temps qu'il repasse — au prochain contact il reçoit
        l'ordre de tout enlever, puis la consigne disparaît.
        """
        pc = self.pcs.pop(pc_id, None)
        if pc is None:
            return
        self.retraits[pc_id] = {
            "secret": str(pc.get("secret") or ""),
            "name": str(pc.get("name") or ""),
            "since": time.time(),
        }
        self.purger_retraits()

    # --------------------------------------------------- Désinstallation

    def retrait_par_secret(
        self, pc_id: str, secret: str
    ) -> dict[str, Any] | None:
        """Consigne de désinstallation d'un agent, si son secret concorde."""
        retrait = self.retraits.get(pc_id)
        if retrait and secrets.compare_digest(
            str(retrait.get("secret", "")), secret
        ):
            return retrait
        return None

    def oublier_retrait(self, pc_id: str) -> bool:
        """L'agent a confirmé : la consigne n'a plus lieu d'être."""
        return self.retraits.pop(pc_id, None) is not None

    def purger_retraits(self) -> None:
        """Un agent qui ne revient jamais ne doit pas encombrer pour autant."""
        limite = time.time() - RETRAIT_TTL
        for pc_id, retrait in list(self.retraits.items()):
            if float(retrait.get("since") or 0) < limite:
                self.retraits.pop(pc_id, None)

    def add_rule(
        self,
        pc_id: str,
        nom: str,
        debut: int,
        fin: int,
        weekdays: list[bool],
        active: bool = True,
        portee: str = "session",
        etiquettes: list[str] | None = None,
        elements: list[dict[str, str]] | None = None,
    ) -> dict[str, Any]:
        pc = self.pcs[pc_id]
        nom = (nom or "").strip()
        etiquettes = self.verifier_etiquettes_plage(etiquettes, [])
        elements = list(elements or [])
        if not nom:
            raise textes.Erreur("store.nom_plage")
        if portee == PORTEE_ETIQUETTES and not etiquettes and not elements:
            raise textes.Erreur("store.plage_vide")
        if any(r["name"].strip().lower() == nom.lower() for r in pc["rules"]):
            raise textes.Erreur("store.plage_existe", nom=nom)
        if int(debut) % 86400 == int(fin) % 86400:
            raise textes.Erreur("store.debut_fin")
        if not any(weekdays):
            raise textes.Erreur("store.coche_jour")
        rid = max((r["id"] for r in pc["rules"]), default=0) + 1
        rule = normalise_rule(
            {
                "id": rid,
                "name": nom,
                "start": debut,
                "end": fin,
                "weekdays": weekdays,
                "enabled": active,
                "portee": portee,
                "etiquettes": etiquettes,
                "elements": elements,
            }
        )
        pc["rules"].append(rule)
        return rule

    def update_rule(self, pc_id: str, rule_id: int, **champs: Any) -> dict[str, Any]:
        """Modifie une plage existante, avec les garde-fous de la création.

        Seuls les champs fournis (non None) changent ; la plage garde son id.
        """
        pc = self.pcs[pc_id]
        actuel = self.rule(pc_id, rule_id)
        if actuel is None:
            raise textes.Erreur("commun.plage_disparue")
        if champs.get("etiquettes") is not None:
            champs["etiquettes"] = self.verifier_etiquettes_plage(
                champs["etiquettes"], actuel.get("etiquettes")
            )
        brouillon = dict(actuel)
        brouillon.update({c: v for c, v in champs.items() if v is not None})
        nouveau = normalise_rule(brouillon)
        nouveau["id"] = actuel["id"]
        nom = nouveau["name"].strip()
        if not nom:
            raise textes.Erreur("store.nom_plage")
        if any(
            r["id"] != actuel["id"] and r["name"].strip().lower() == nom.lower()
            for r in pc["rules"]
        ):
            raise textes.Erreur("store.plage_existe", nom=nom)
        if nouveau["start"] == nouveau["end"]:
            raise textes.Erreur("store.debut_fin")
        if not any(nouveau["weekdays"]):
            raise textes.Erreur("store.coche_jour")
        if (
            nouveau["portee"] == PORTEE_ETIQUETTES
            and not nouveau["etiquettes"]
            and not nouveau["elements"]
        ):
            raise textes.Erreur("store.plage_vide")
        pc["rules"] = [
            nouveau if r["id"] == actuel["id"] else r for r in pc["rules"]
        ]
        return nouveau

    def couleur_plage(self, pc: dict[str, Any], rule_id: int) -> str | None:
        cle = str(int(rule_id))
        couleur = (pc.get("couleurs") or {}).get(cle)
        if couleur:
            return couleur
        # Le planning est celui de la personne : la couleur choisie sur un de ses appareils vaut pour tous.
        for a in self._siens(pc):
            couleur = (a.get("couleurs") or {}).get(cle)
            if couleur:
                return couleur
        return None

    def set_couleur_plage(
        self, pc: dict[str, Any], rule_id: int, couleur: str | None
    ) -> None:
        """Enregistre (ou efface) la couleur d'affichage d'une plage."""
        for a in self._siens(pc):
            couleurs = a.setdefault("couleurs", {})
            if couleur:
                couleurs[str(int(rule_id))] = str(couleur)
            else:
                couleurs.pop(str(int(rule_id)), None)

    def remove_rule(self, pc_id: str, rule_id: int) -> None:
        pc = self.pcs[pc_id]
        pc["rules"] = [r for r in pc["rules"] if r["id"] != int(rule_id)]
        (pc.get("couleurs") or {}).pop(str(int(rule_id)), None)

    def rule(self, pc_id: str, rule_id: int) -> dict[str, Any] | None:
        for r in self.pcs.get(pc_id, {}).get("rules", []):
            if r["id"] == int(rule_id):
                return r
        return None

    def rule_by_name(self, pc_id: str, nom: str) -> dict[str, Any] | None:
        cible = (nom or "").strip().lower()
        for r in self.pcs.get(pc_id, {}).get("rules", []):
            if r["name"].strip().lower() == cible:
                return r
        return None

    # ---------------------------------------------------------- Évaluation

    def regles_pour(self, pc: dict[str, Any]) -> list[dict[str, Any]]:
        """Les plages qui valent sur cet appareil.

        Le planning est celui de la personne : ses appareils portent les mêmes
        plages. Une plage peut en épargner certains (« sauf ») : la nuit ferme
        le téléphone et le PC, pas la liseuse.
        """
        # 1.82.0 : module « Plages » coupé, aucune plage ne vaut (elles restent enregistrées, intactes).
        if _modules.coupe(self, "plages"):
            return []
        moi = str(pc.get("id") or "")
        return [r for r in (pc.get("rules") or []) if moi not in (r.get("sauf") or [])]

    def planning_locked(self, pc: dict[str, Any], moment: datetime) -> bool:
        # `is_locked` écarte lui-même les plages d'étiquettes : une plage
        # qui ne ferme que « Jeux » ne verrouille pas la machine.
        if not pc.get("enabled", True):
            return False
        return is_locked(self.regles_pour(pc), moment)

    def next_change(
        self, pc: dict[str, Any], moment: datetime
    ) -> datetime | None:
        if not pc.get("enabled", True):
            return None
        return next_change(self.regles_pour(pc), moment)

    def evaluate(self, pc: dict[str, Any]) -> dict[str, Any]:
        """État complet d'un PC à cet instant."""
        moment = dt_util.now().replace(tzinfo=None)
        planning = self.planning_locked(pc, moment)
        suivant = self.next_change(pc, moment)
        override = pc.get("override") or None
        maintenant = time.time()
        if override:
            fin = float(override.get("until") or 0)
            if fin and fin <= maintenant:
                override = None
                pc["override"] = None
        if override:
            ferme = override.get("mode") == MODE_LOCKED
            source = SOURCE_OVERRIDE
        else:
            ferme = planning
            source = SOURCE_PLANNING
        return {
            "locked": ferme,
            "source": source,
            "planning": MODE_LOCKED if planning else MODE_OPEN,
            "override": override,
            "next_change": suivant,
        }

    def set_override(
        self, pc: dict[str, Any], mode: str, minutes: float = 0
    ) -> None:
        """Pose une dérogation.

        Sans durée, elle est bornée au prochain basculement du planning :
        une dérogation sans fin neutraliserait le planning indéfiniment.
        """
        maintenant = time.time()
        if minutes:
            fin = maintenant + float(minutes) * 60
        else:
            moment = dt_util.now().replace(tzinfo=None)
            suivant = self.next_change(pc, moment)
            fin = _ts(suivant) if suivant else 0
            if fin and fin <= maintenant + 30:
                fin = 0
        pc["override"] = {"mode": mode, "until": fin}

    def clear_override(self, pc: dict[str, Any]) -> None:
        pc["override"] = None
