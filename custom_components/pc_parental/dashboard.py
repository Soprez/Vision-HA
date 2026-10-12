"""Tableau de bord Vision, construit par l'intégration.

Il est régénéré dès qu'un PC s'inscrit ou qu'une plage apparaît : il n'y a
jamais rien à recopier à la main. Il est réservé aux administrateurs.
"""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.components import persistent_notification
from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er

from .const import (
    DOMAIN,
    PRESENCE_LIBELLES,
    PRESENCE_PASTILLES,
    PRODUIT,
    PRODUIT_AFFICHE,
)
from . import labels, langues, lieu, modules, vocabulaire
from . import android_maj
from . import installation as installation_appareils
from .coordinator import PcParentalCoordinator

_LOGGER = logging.getLogger(__name__)

# Les textes du tableau, dans les six langues de Vision (pc_parental/i18n/tableau/<langue>.json) : le tableau est
# écrit dans la langue de Home Assistant, et refait quand elle change (elle fait partie de son empreinte). Le
# vocabulaire ({decideur}, {demandeur}, {lieu}…) est rempli par vocabulaire.py, avec le nom du lieu configuré.
_TEXTES = vocabulaire.Catalogue("tableau")


def _t(hass: HomeAssistant, cle: str, **champs: Any) -> str:
    """Un texte du tableau, dans la langue de Home Assistant, vocabulaire du lieu compris."""
    return _TEXTES.t(cle, langues.langue_hass(hass), store=modules.store_de(hass), **champs)


# Un seul tableau de bord dans le menu : « Vision ». Le back-office
# séparé faisait deux entrées pour une seule chose, et l'on ne savait
# jamais laquelle ouvrir.
URL_PATH = "vision-maison"
TITRE = "Vision"
ICONE = "mdi:eye"
THEME = "Vision"

# L'ancien chemin, du temps où les onglets vivaient à part. On s'en sert
# encore pour réparer les liens de navigation écrits à la main.
URL_ANCIEN = "controle-parental-pc"

# Les onglets que Vision écrit lui-même. Tout autre onglet trouvé dans
# le tableau de bord a été fait à la main : on le garde, et on le garde
# **devant** — c'est la page d'accueil de la maison, elle passe d'abord.
CHEMINS = ("maison", "veille", "pc", "listes", "processus", "plages", "statistiques", "message", "ajouter", "reglages", "protection", "appareils",
           "widgets")
# Les sous-vues d'une personne portent son nom : on les reconnaît au préfixe.
PREFIXE_PERSONNE = "personne-"

def _eid(hass: HomeAssistant, platform: str, unique_id: str) -> str | None:
    """L'entité, si elle existe et n'est pas désactivée (matrice réglages × type d'appareil, 1.72.0)."""
    registre = er.async_get(hass)
    entity_id = registre.async_get_entity_id(platform, DOMAIN, unique_id)
    entree = registre.async_get(entity_id) if entity_id else None
    # 1.82.0 : ni celle d'un module coupé (même si le registre n'est pas encore à jour).
    store = modules.store_de(hass)
    if store is not None and modules.entite_coupee(store, platform, unique_id):
        return None
    return entity_id if entree is not None and entree.disabled_by is None else None


def _presence_markdown(capteur: str) -> dict[str, Any]:
    """Pastille de présence, à la façon d'un statut de messagerie."""
    pastilles = "".join(
        "{% elif p == '" + etat + "' %}"
        + PRESENCE_PASTILLES[etat]
        + " **"
        + PRESENCE_LIBELLES[etat].capitalize()
        + "**\n"
        for etat in list(PRESENCE_PASTILLES)[1:]
    )
    premier = list(PRESENCE_PASTILLES)[0]
    return {
        "type": "markdown",
        "content": (
            "{% set p = states('" + capteur + "') %}\n"
            "{% set i = state_attr('" + capteur + "','inactif_depuis_minutes') %}\n"
            "{% if p == '" + premier + "' %}"
            + PRESENCE_PASTILLES[premier]
            + " **"
            + PRESENCE_LIBELLES[premier].capitalize()
            + "**\n"
            + pastilles
            + "{% else %}⚪ État inconnu\n"
            "{% endif %}"
            "{% if i is not none and i > 0 %} — sans activité depuis "
            "{{ i }} min{% endif %}"
        ),
    }


def _etat_markdown(sw: str) -> dict[str, Any]:
    """Carte qui explique en clair qui commande."""
    return {
        "type": "markdown",
        "content": (
            "{% set sw = '" + sw + "' %}\n"
            "{% set src = state_attr(sw,'source') %}\n"
            "{% set fin = state_attr(sw,'fin_derogation') %}\n"
            "{% set pl = state_attr(sw,'planning') %}\n"
            "{% set ag = state_attr(sw,'agent_en_ligne') %}\n"
            "{% if not ag %}\n"
            "🔌 **Agent injoignable** — cet appareil n'applique plus rien. "
            "Vérifie qu'il est allumé et connecté.\n"
            "{% elif src == 'planning' %}\n"
            "{{ '🔒' if pl == 'fermé' else '✅' }} **Le planning pilote** — "
            "Appareil {{ 'verrouillé' if pl == 'fermé' else 'utilisable' }}.\n"
            "{% else %}\n"
            "⚠️ **Dérogation en cours** — "
            "{{ 'verrouillage forcé' if is_state(sw,'off') else 'ouverture forcée' }}"
            "{% if fin %}, jusqu'à "
            "{{ fin | as_timestamp | timestamp_custom('%Hh%M') }}{% endif %}.\n"
            "Le planning demande&nbsp;: "
            "{{ 'Appareil verrouillé' if pl == 'fermé' else 'Appareil utilisable' }}.\n"
            "{% endif %}"
        ),
    }


def _section_pc(
    hass: HomeAssistant, coord: PcParentalCoordinator, pc_id: str
) -> dict[str, Any] | None:
    pc = coord.store.get(pc_id)
    if not pc:
        return None
    sw = _eid(hass, "switch", f"{DOMAIN}_acces_{pc_id}")
    if not sw:
        return None

    lignes: list[Any] = [{"entity": sw, "name": "Accès à l'appareil"}]
    for platform, uid, nom in (
        ("text", f"{DOMAIN}_liste_nom_{pc_id}", "Nom"),
        ("select", f"{DOMAIN}_personne_{pc_id}", "Utilisateur"),
        ("sensor", f"{DOMAIN}_suivant_{pc_id}", "Prochain changement"),
        ("sensor", f"{DOMAIN}_session_{pc_id}", "Session ouverte"),
        ("sensor", f"{DOMAIN}_temps_ecran_{pc_id}", "Temps d'écran aujourd'hui"),
        ("binary_sensor", f"{DOMAIN}_compte_admin_{pc_id}", "Compte administrateur"),
        ("update", f"{DOMAIN}_maj_{pc_id}", "Version de l'agent"),
    ):
        if eid := _eid(hass, platform, uid):
            lignes.append({"entity": eid, "name": nom})

    cartes: list[dict[str, Any]] = [
        _entete_appareil(pc, pc["name"], "title"),
    ]
    if presence := _eid(hass, "sensor", f"{DOMAIN}_presence_{pc_id}"):
        cartes.append(_presence_markdown(presence))
    cartes.append(
        {"type": "entities", "state_color": True, "entities": lignes}
    )
    cartes.append(_etat_markdown(sw))

    boutons = []
    for uid, nom, icone in (
        (f"{DOMAIN}_verrouiller_{pc_id}", "Verrouiller", "mdi:lock"),
        (f"{DOMAIN}_ouvrir30_{pc_id}", "Ouvrir 30 min", "mdi:lock-clock"),
        (f"{DOMAIN}_planning_{pc_id}", _t(hass, "planning"), "mdi:calendar-sync"),
    ):
        if eid := _eid(hass, "button", uid):
            boutons.append(
                {
                    "type": "button",
                    "entity": eid,
                    "name": nom,
                    "icon": icone,
                    "show_state": False,
                    "tap_action": {
                        "action": "perform-action",
                        "perform_action": "button.press",
                        "target": {"entity_id": eid},
                    },
                }
            )
    if boutons:
        cartes.append({"type": "horizontal-stack", "cards": boutons})

    # Les plages de verrouillage ont leur propre onglet. Les répéter ici
    # allongeait la carte de chaque machine avec un réglage qu'on ne vient
    # pas changer en passant.

    # Un interrupteur par étiquette : c'est le geste du soir, « plus de
    # jeux sur cette machine ». Il se voit et se fait sans ouvrir de liste,
    # et il vaut aussi pour les automatisations.
    #
    # La maison a déjà des étiquettes qui n'ont rien à voir avec les écrans
    # — « Radiateur », « Caméras ». Les afficher toutes ici noierait celles
    # qui comptent. On ne montre donc que celles qui classent vraiment
    # quelque chose, plus celles déjà coupées sur cette machine. Les autres
    # existent quand même en entités : une automatisation les trouve, et
    # elles apparaissent ici dès qu'elles servent.
    magasin = coord.store
    coupes = []
    for etiquette in labels.details(hass):
        nom = etiquette["nom"]
        sert = any(
            any(e.lower() == nom.lower() for e in magasin.etiquettes_de(genre, n))
            for genre in ("apps", "sites")
            for n in magasin.catalogue_maison(genre)
        )
        if not sert and not magasin.etiquette_bloquee(pc, nom):
            continue
        eid = _eid(hass, "switch", f"{DOMAIN}_etq_{pc_id}_{etiquette['id']}")
        if eid:
            coupes.append({"entity": eid, "name": nom})
    # Ce que les règles coupent en plus, en ce moment : le planning et les
    # automatisations ferment des catégories sans toucher aux interrupteurs. Sans
    # cette ligne, Discord est fermé chez un enfant et le tableau n'en dit rien.
    if etat_eid := _eid(hass, "sensor", f"{DOMAIN}_etat_{pc_id}"):
        cartes.append(
            {
                "type": "markdown",
                "content": (
                    "{% set l = state_attr('" + etat_eid + "', 'categories_coupees') or [] %}"
                    "{% if l %}**Coupé en plus par les règles, en ce moment**\n"
                    "{% for x in l %}- {{ x }}\n{% endfor %}"
                    "{% else %}_Rien de coupé en plus par le planning ou une automatisation._{% endif %}"
                ),
            }
        )
    if coupes:
        cartes.append(
            {
                "type": "heading",
                "heading": "Étiquettes coupées sur cet appareil",
                "heading_style": "subtitle",
                "icon": "mdi:tag-off-outline",
            }
        )
        cartes.append({"type": "entities", "entities": coupes})

    listes = []
    for champ, nom in (
        ("bloques", "Bloqués"),
        ("autorises", "Autorisés (exceptions)"),
    ):
        if eid := _eid(hass, "text", f"{DOMAIN}_liste_{champ}_{pc_id}"):
            listes.append({"entity": eid, "name": nom})
    if listes:
        cartes.append(
            {
                "type": "heading",
                "heading": "Règles de cet appareil",
                "heading_style": "subtitle",
                "icon": "mdi:gavel",
            }
        )
        cartes.append(
            {
                "type": "markdown",
                "content": (
                    "Un nom de logiciel ou un domaine, séparés par des "
                    "virgules. Ce qui n'est listé nulle part reste "
                    "autorisé, et un nom précis l'emporte sur une "
                    "étiquette.\n\n"
                    "Pour couper une **catégorie entière** sur cette "
                    "machine, ne l'écris pas ici : sers-toi des "
                    "interrupteurs ci-dessus. Ils portent le nom exact de "
                    "l'étiquette, et une faute de frappe n'est plus "
                    "possible."
                ),
            }
        )
        cartes.append({"type": "entities", "entities": listes})

        # Les deux champs ci-dessus disent ce qu'on a demandé. Celui-ci dit
        # ce qui s'applique vraiment sur la machine, étiquettes résolues —
        # avec la raison, sinon on ne saurait pas où aller la défaire.
        if etat := _eid(hass, "sensor", f"{DOMAIN}_etat_{pc_id}"):
            cartes.append(
                {
                    "type": "markdown",
                    "content": (
                        "<details><summary><b>Ce qui est réellement bloqué</b> (ouvrir le détail)</summary>\n\n"
                        "{% set l = state_attr('" + etat + "', "
                        "'lignes_bloques') %}"
                        "{% if l %}"
                        "{{ state_attr('" + etat + "', 'entete_bloques') }}\n"
                        "{% for r in l %}{{ r }}\n{% endfor %}"
                        "{% else %}_rien : cet appareil a accès à tout_"
                        "{% endif %}\n\n</details>"
                    ),
                }
            )

    # Les tentatives vers un site dangereux sont dans l'onglet
    # **Statistiques**, avec le reste des chiffres. La liste noire vaut
    # pour toute la maison : la répéter sur chaque carte, c'était afficher
    # quatre fois la même chose et noyer ce que cette machine a de
    # particulier.

    return {"type": "grid", "cards": cartes}


def _section_gestion(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> dict[str, Any]:
    lignes = []
    for platform, uid, nom in (
        ("select", f"{DOMAIN}_pc_cible", "Appareil ciblé"),
        ("text", f"{DOMAIN}_saisie_nom_plage", "Nom de la plage"),
        ("select", f"{DOMAIN}_portee_plage", "Cette plage ferme"),
        ("text", f"{DOMAIN}_saisie_etiquettes_plage", "Étiquettes à fermer"),
        ("time", f"{DOMAIN}_new_rule_debut", "Début"),
        ("time", f"{DOMAIN}_new_rule_fin", "Fin"),
        ("select", f"{DOMAIN}_jours", "Jours"),
        ("button", f"{DOMAIN}_creer_plage", "Créer la plage"),
        ("select", f"{DOMAIN}_plage_a_supprimer", "Plage à supprimer"),
        ("button", f"{DOMAIN}_supprimer_plage", "Supprimer la plage"),
    ):
        if eid := _eid(hass, platform, uid):
            lignes.append({"entity": eid, "name": nom})
    return {
        "type": "grid",
        "cards": [
            {
                "type": "heading",
                "heading": "Ajouter une plage",
                "heading_style": "title",
                "icon": "mdi:calendar-plus",
            },
            {
                "type": "markdown",
                "content": (
                    "Nomme la plage, donne l'heure de début et de fin, les "
                    "jours, puis **Créer la plage**.\n\n"
                    "Laisse **Appareil** sur « Tous les appareils » et la plage est posée "
                    "sur toutes les machines d'un coup — c'est le cas "
                    "courant : l'heure du coucher ne se négocie pas machine "
                    "par machine. Choisis-en une pour ne la poser que là. La "
                    "suppression suit la même règle : ce qui se pose partout "
                    "se retire partout.\n\n"
                    "**Cette plage ferme** : soit la *session* — l'appareil est "
                    "verrouillé, c'est le comportement historique — soit des "
                    "*étiquettes*. « Jeux, Vidéo » de 8h à 18h coupe ces "
                    "deux catégories et laisse le reste de la machine "
                    "utilisable : c'est la différence entre une supervision "
                    "et une punition.\n\n"
                    "Une plage d'étiquettes l'emporte sur les règles "
                    "d'étiquette, mais cède devant un nom cité : "
                    "« minecraft » explicitement autorisé le reste à l'heure "
                    "du blocage. C'est ce qui permet une exception sans "
                    "défaire l'horaire.\n\n"
                    "Une plage qui enjambe minuit appartient au jour où elle "
                    "commence."
                ),
            },
            {"type": "entities", "entities": lignes},
        ],
    }


def _adresse(hass: HomeAssistant) -> str:
    """Adresse de Home Assistant utilisable depuis un PC de la maison."""
    for cle in ("internal_url", "external_url"):
        if valeur := getattr(hass.config, cle, None):
            return str(valeur).rstrip("/")
    return "http://homeassistant.local:8123"


def _section_message(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> dict[str, Any]:
    """Lot 1 (HA-06) : l'onglet Messages n'a plus qu'un formulaire, celui de la carte Vision au-dessus.

    Le second (Destinataire, Message, Son, Envoyer) faisait la même chose autrement : il quitte le tableau (ses
    entités restent, pour les automatisations). Ne reste ici, replié, que l'envoi depuis une automatisation, avec
    un exemple d'étiquette neutre (et non plus la première étiquette du lieu, « Enfants » au relevé).
    """
    exemple = _t(hass, "message_exemple_etiquette")
    return {
        "type": "grid",
        "column_span": 2,
        "cards": [
            {
                "type": "markdown",
                "content": (
                    "<details><summary>" + _t(hass, "message_automatisation_titre") + "</summary>\n\n"
                    + _t(hass, "message_aide", produit=PRODUIT) + "\n\n"
                    + _t(hass, "message_automatisation") + "\n\n"
                    "```yaml\n"
                    "action: pc_parental.notify\n"
                    "data:\n"
                    f"  tag: {exemple}\n"
                    f"  message: {_t(hass, 'message_exemple_texte')}\n"
                    # « Rappel » est le nom d'un son (une valeur du sélecteur), pas un texte à traduire.
                    "  son: Rappel\n"
                    "```\n"
                    + _t(hass, "message_sans_cible")
                    + "\n\n</details>"
                ),
            },
        ],
    }


def _entete_appareil(pc: dict[str, Any], titre: str, style: str) -> dict[str, Any]:
    """En-tête d'un appareil : icône et couleur selon la plateforme.

    Un coup d'œil suffit : ordinateur sombre pour un PC, robot vert pour un
    appareil Android (téléphone, tablette ou TV).
    """
    android = (pc.get("platform") or "") == "android"
    icone = "mdi:android" if android else "mdi:desktop-classic"
    couleur = "#3DDC84" if android else "var(--primary-text-color)"
    return {
        "type": "heading",
        "heading": titre,
        "heading_style": style,
        "icon": icone,
        "card_mod": {"style": f"ha-icon, ha-state-icon {{ color: {couleur}; }}"},
    }


def installation(
    hass: HomeAssistant, coord: PcParentalCoordinator, avec_cle: bool = True
) -> dict[str, str]:
    """La ligne d'installation d'un PC et le bloc de connexion de l'appli.

    Avec la clé : seulement pour les options de l'intégration (administrateurs).
    """
    base = _adresse(hass)
    commande = (
        f"irm {base}/api/pc_parental/client/install "
        '-OutFile "$env:TEMP\\install-cp.ps1"; '
        '& "$env:TEMP\\install-cp.ps1"'
    )
    android = f"adresse: {base}"
    if avec_cle:
        commande += f" -Cle {coord.store.enroll_key}"
        android += f"\ncle: {coord.store.enroll_key}"
    return {"commande": commande, "android": android}


def _section_installation(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> dict[str, Any]:
    lignes = []
    for platform, uid, cle in (
        ("switch", f"{DOMAIN}_inscriptions", "installation_inscriptions"),
        ("switch", f"{DOMAIN}_maj_auto", "installation_maj_auto"),
        ("number", f"{DOMAIN}_avertissement", "installation_avertir"),
        ("number", f"{DOMAIN}_inactivite", "installation_inactif"),
        ("text", f"{DOMAIN}_saisie_nom_pc", "installation_nom"),
        ("button", f"{DOMAIN}_creer_pc", "installation_creer"),
    ):
        if eid := _eid(hass, platform, uid):
            lignes.append({"entity": eid, "name": _t(hass, cle)})

    base = _adresse(hass)
    return {
        "type": "grid",
        "cards": [
            {
                "type": "heading",
                "heading": _t(hass, "installation_titre"),
                "heading_style": "title",
                "icon": "mdi:download",
            },
            {
                # Liens sans clé, servis par l'intégration : la dernière version
                # publiée, à jour à chaque régénération du tableau.
                "type": "markdown",
                "content": installation_appareils.markdown(hass, avec_titre=False),
            },
            {
                "type": "markdown",
                "content": (
                    _t(hass, "installation_aide") + "\n\n"
                    f"```\nirm {base}/api/pc_parental/client/uninstall "
                    '-OutFile "$env:TEMP\\desinstall-cp.ps1"; '
                    '& "$env:TEMP\\desinstall-cp.ps1"\n```'
                ),
            },
            {"type": "entities", "entities": lignes},
        ],
    }


def _section_courbe(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> dict[str, Any]:
    """Un mois de tentatives bloquées, une ligne par machine.

    Deux représentations de la même chose, et c'est voulu : le graphique de
    Home Assistant est plus lisible, mais son historique est purgé au bout
    de quelques jours ; le tableau, lui, est lu dans notre propre stockage
    et couvre toujours trente jours.
    """
    capteurs = []
    lignes = []
    for pc_id in sorted(coord.pcs, key=lambda p: coord.pc_name(p).lower()):
        eid = _eid(hass, "sensor", f"{DOMAIN}_blocages_{pc_id}")
        if not eid:
            continue
        capteurs.append(eid)
        lignes.append(
            "| **" + coord.pc_name(pc_id) + "** | `{{ state_attr('" + eid
            + "', 'courbe') }}` | {{ state_attr('" + eid
            + "', 'total_30_jours') }} | {{ states('" + eid + "') }} |"
        )

    cartes: list[dict[str, Any]] = [
        {
            "type": "heading",
            "heading": _t(hass, "courbe_titre"),
            "heading_style": "title",
            "icon": "mdi:chart-line",
        },
        {
            "type": "markdown",
            "content": _t(hass, "courbe_aide"),
        },
    ]
    if capteurs:
        cartes.append(
            {
                "type": "history-graph",
                "hours_to_show": 720,
                "entities": [{"entity": e} for e in capteurs],
            }
        )
    if lignes:
        cartes.append(
            {
                "type": "markdown",
                "content": (
                    _t(hass, "courbe_entete") + "\n"
                    "|---|---|---|---|\n" + "\n".join(lignes)
                ),
            }
        )
    return {"type": "grid", "cards": cartes}


def _lignes(hass: HomeAssistant, triplets) -> list[dict[str, Any]]:
    """Les lignes d'une carte d'entités, pour celles qui existent."""
    return [
        {"entity": eid, "name": nom}
        for platform, uid, nom in triplets
        if (eid := _eid(hass, platform, uid))
    ]


def _sections_securite(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> list[dict[str, Any]]:
    """Ce qui est dangereux, par opposition à ce qui est discutable.

    Trois sections, une par question : qu'est-ce qui est coupé d'office
    (liste noire et filtre DNS), tel domaine y est-il (recherche), et que
    dit le reste du monde d'un site ou d'un logiciel (fréquentation et
    VirusTotal). Rien ici ne se tranche : un domaine qui héberge un
    logiciel malveillant est coupé partout, sans passer par la file.
    """
    etat = _eid(hass, "sensor", f"{DOMAIN}_liste_noire_etat") or ""
    rech = _eid(hass, "sensor", f"{DOMAIN}_recherche_noire") or ""
    freq = _eid(hass, "switch", f"{DOMAIN}_notoriete") or ""
    vt = _eid(hass, "switch", f"{DOMAIN}_virustotal") or ""

    liste_noire = {
        "type": "grid",
        "cards": [
            _titre(_t(hass, "securite_liste_noire_titre"), "mdi:shield-alert"),
            {
                "type": "entities",
                "entities": _lignes(
                    hass,
                    (
                        ("switch", f"{DOMAIN}_liste_noire", _t(hass, "securite_liste_noire")),
                        ("select", f"{DOMAIN}_dns_filtrant", _t(hass, "securite_filtre_dns")),
                        ("sensor", f"{DOMAIN}_liste_noire_etat", _t(hass, "securite_domaines_connus")),
                        ("number", f"{DOMAIN}_rythme_liste_noire", _t(hass, "securite_rafraichir_toutes")),
                        ("button", f"{DOMAIN}_rafraichir_liste_noire", _t(hass, "securite_rafraichir_maintenant")),
                    ),
                ),
            },
            {
                "type": "markdown",
                "content": (
                    "{% set l = state_attr('" + etat + "', 'lignes') %}"
                    "{% if l %}"
                    "{{ state_attr('" + etat + "', 'entete') }}\n"
                    "{% for r in l %}{{ r }}\n{% endfor %}"
                    "{% else %}_" + _t(hass, "securite_liste_non_chargee") + "_{% endif %}"
                    "{% set p = state_attr('" + etat + "', 'probleme') %}"
                    "{% if p %}\n\n⚠️ {{ p }}{% endif %}"
                ),
            },
            _mot(_t(hass, "securite_liste_noire_aide")),
        ],
    }

    # Lot 1 (HA-23) : une recherche restée d'une autre fois (« 0 domaines » au premier regard) se voit et s'efface :
    # le terme cherché est rappelé au-dessus des résultats, et un bouton « Effacer la recherche » vide le champ
    # (text.set_value, l'entité ne change pas). Il n'apparaît que lorsqu'il y a quelque chose à effacer.
    saisie = _eid(hass, "text", f"{DOMAIN}_saisie_recherche") or ""
    effacer = (
        [{
            "type": "conditional",
            "conditions": [{"condition": "state", "entity": saisie, "state_not": ["", "unknown", "unavailable"]}],
            "card": {
                "type": "button",
                "name": _t(hass, "securite_effacer_recherche"),
                "icon": "mdi:close-circle-outline",
                "show_state": False,
                "tap_action": {"action": "perform-action", "perform_action": "text.set_value",
                               "target": {"entity_id": saisie}, "data": {"value": ""}},
            },
        }]
        if saisie else []
    )
    recherche = {
        "type": "grid",
        "cards": [
            _titre(_t(hass, "securite_recherche_titre"), "mdi:magnify"),
            {
                "type": "entities",
                "entities": _lignes(
                    hass,
                    (
                        ("text", f"{DOMAIN}_saisie_recherche", _t(hass, "securite_chercher")),
                        ("sensor", f"{DOMAIN}_recherche_noire", _t(hass, "securite_trouves")),
                    ),
                ),
            },
            *effacer,
            {
                "type": "markdown",
                "content": (
                    "{% set q = state_attr('" + rech + "', 'recherche') %}"
                    "{% if q %}**" + _t(hass, "securite_recherche_en_cours", terme="{{ q }}") + "**\n\n{% endif %}"
                    "{% set l = state_attr('" + rech + "', 'lignes') %}"
                    "{% if l %}"
                    "{{ state_attr('" + rech + "', 'entete') }}\n"
                    "{% for r in l %}{{ r }}\n{% endfor %}"
                    "{% set t = state_attr('" + rech + "', 'total') %}"
                    "{% set m = state_attr('" + rech + "', 'montres') %}"
                    "{% if state_attr('" + rech + "', 'tronque') %}"
                    "\n_" + _t(hass, "securite_et_autres", nombre="{{ t - m }}") + "_{% endif %}"
                    "{% else %}_" + _t(hass, "securite_rien_ne_correspond") + "_{% endif %}"
                ),
            },
        ],
    }

    avis = {
        "type": "grid",
        "cards": [
            _titre(_t(hass, "securite_avis_titre"), "mdi:chart-bar"),
            {
                "type": "entities",
                "entities": _lignes(
                    hass,
                    (
                        ("switch", f"{DOMAIN}_notoriete", _t(hass, "securite_frequentation_sites")),
                        ("button", f"{DOMAIN}_rafraichir_notoriete", _t(hass, "securite_recharger_classement")),
                        ("text", f"{DOMAIN}_cle_virustotal", _t(hass, "securite_cle_virustotal")),
                        ("switch", f"{DOMAIN}_virustotal", _t(hass, "securite_avis_virustotal")),
                    ),
                ),
            },
            {
                # L'état vrai, pas seulement l'interrupteur : allumé ne veut
                # pas dire que ça marche.
                "type": "markdown",
                "content": (
                    "{% set e = state_attr('" + freq + "', 'erreur') %}"
                    "{% set n = state_attr('" + freq + "', 'domaines_classes') | int(0) %}"
                    "**" + _t(hass, "securite_frequentation") + "** : "
                    "{% if e %}⚠️ {{ e }}"
                    "{% elif n %}" + _t(hass, "securite_domaines_classes", nombre="{{ n }}")
                    + "{% elif is_state('" + freq + "', 'on') %}" + _t(hass, "securite_classement_pas_charge")
                    + "{% else %}" + _t(hass, "securite_eteinte") + "{% endif %}\n\n"
                    "{% set e = state_attr('" + vt + "', 'erreur') %}"
                    "**VirusTotal** : "
                    "{% if e %}⚠️ {{ e }}"
                    "{% elif is_state('" + vt + "', 'on') %}"
                    + _t(hass, "securite_virustotal_etat",
                         avis="{{ state_attr('" + vt + "', 'verdicts_connus') | int(0) }}",
                         requetes="{{ state_attr('" + vt + "', 'requetes_restantes_aujourdhui') | int(0) }}")
                    + "{% elif not state_attr('" + vt + "', 'cle_posee') %}" + _t(hass, "securite_pas_de_cle")
                    + "{% else %}" + _t(hass, "securite_eteint") + "{% endif %}"
                ),
            },
            _mot(_t(hass, "securite_avis_aide")),
        ],
    }
    return [liste_noire, recherche, avis]


def _boutons(hass: HomeAssistant, paires) -> dict[str, Any] | None:
    """Une rangée de boutons plutôt qu'une pile de lignes.

    Trois décisions qui s'excluent — bloquer, autoriser, remettre à classer
    — sont un seul geste à faire, pas trois lignes à lire. Côte à côte, la
    main va au bon endroit sans que l'œil ait à descendre.

    Rendu vide, on ne renvoie rien : une carte sans bouton est un trou.
    """
    cartes = []
    for uid, nom, icone in paires:
        if eid := _eid(hass, "button", uid):
            cartes.append(
                {
                    "type": "button",
                    "entity": eid,
                    "name": nom,
                    "icon": icone,
                    "show_state": False,
                }
            )
    if not cartes:
        return None
    return {"type": "horizontal-stack", "cards": cartes}


def _bloc(cartes) -> dict[str, Any]:
    """Tient ensemble ce qui va ensemble.

    Dans une vue en sections, chaque carte est un pavé indépendant qui se
    replace tout seul selon la largeur : un titre pouvait se retrouver loin
    du tableau qu'il annonce. Empilées, les cartes d'un même geste font un
    seul pavé et ne se séparent plus.
    """
    return {"type": "vertical-stack", "cards": [c for c in cartes if c]}


def _pagination(hass: HomeAssistant) -> dict:
    """Les deux boutons pour feuilleter, sous le tableau."""
    boutons = []
    for uid, nom, icone in (
        (f"{DOMAIN}_page_precedente", "Précédente", "mdi:chevron-left"),
        (f"{DOMAIN}_page_suivante", "Suivante", "mdi:chevron-right"),
    ):
        if eid := _eid(hass, "button", uid):
            boutons.append(
                {"type": "button", "entity": eid, "name": nom, "icon": icone}
            )
    return {"type": "horizontal-stack", "cards": boutons}


def _carte_tableau(
    hass: HomeAssistant,
    uid: str,
    titre: str,
    vide: str,
    platform: str = "select",
) -> dict:
    """Carte markdown qui rend un vrai tableau, lu en direct.

    L'en-tête et les lignes viennent des attributs du sélecteur : le tableau
    se met donc à jour tout seul, sans que le tableau de bord soit
    reconstruit à chaque relevé.
    """
    eid = _eid(hass, platform, uid) or ""
    return {
        "type": "markdown",
        "content": (
            f"## {titre}\n"
            "{% set lignes = state_attr('" + eid + "', 'lignes') %}"
            "{% if lignes %}"
            "{{ state_attr('" + eid + "', 'entete') }}\n"
            "{% for l in lignes %}{{ l }}\n{% endfor %}"
            "{% set p = state_attr('" + eid + "', 'page') %}"
            "{% if p %}\n_" + _t(hass, "tableau_page", page="{{ p }}", pages="{{ state_attr('" + eid + "', 'pages') }}",
                                total="{{ state_attr('" + eid + "', 'total') }}") + "_{% endif %}"
            "{% else %}_" + vide + "_{% endif %}"
        ),
    }


def _outils(hass: HomeAssistant, paires) -> list[dict[str, Any]]:
    sortie = []
    for platform, uid, nom in paires:
        if eid := _eid(hass, platform, uid):
            sortie.append({"entity": eid, "name": nom})
    return sortie


def _titre(texte: str, icone: str, style: str = "title") -> dict[str, Any]:
    return {
        "type": "heading",
        "heading": texte,
        "heading_style": style,
        "icon": icone,
    }


def _mot(texte: str) -> dict[str, Any]:
    return {"type": "markdown", "content": texte}


def _table_etat(hass: HomeAssistant, uid: str, cle: str, total: str, vide: str) -> dict:
    """Un tableau lu en direct dans un attribut précis du sélecteur."""
    eid = _eid(hass, "select", uid) or ""
    return {
        "type": "markdown",
        "content": (
            "{% set l = state_attr('" + eid + "', '" + cle + "') %}"
            "{% if l %}"
            "{{ state_attr('" + eid + "', 'entete') }}\n"
            "{% for r in l %}{{ r }}\n{% endfor %}"
            "{% set t = state_attr('" + eid + "', '" + total + "') %}"
            "{% if t and t > l | length %}"
            "\n_… et {{ t - l | length }} autres. Augmente **Résultats par "
            "page**._{% endif %}"
            "{% else %}_" + vide + "_{% endif %}"
        ),
    }


def _section_inscrire(hass: HomeAssistant) -> dict[str, Any]:
    """Inscrire à la main ce qu'aucune machine n'a encore vu.

    Presque tout arrive tout seul : ce qu'une machine voit entre dans la
    liste au relevé suivant. On n'inscrit ici que ce qu'on veut trancher
    **avant** qu'il n'arrive.
    """
    return _bloc(
        [
            _titre(_t(hass, "inscrire_titre"), "mdi:playlist-plus", "subtitle"),
            _mot(_t(hass, "inscrire_aide")),
            {
                "type": "entities",
                "entities": _outils(
                    hass,
                    (
                        ("text", f"{DOMAIN}_saisie_ajout", _t(hass, "inscrire_nom")),
                        ("button", f"{DOMAIN}_inscrire_site", _t(hass, "inscrire_site")),
                        ("button", f"{DOMAIN}_inscrire_logiciel", _t(hass, "inscrire_logiciel")),
                        ("sensor", f"{DOMAIN}_a_classer", _t(hass, "inscrire_reste")),
                    ),
                ),
            },
        ]
    )


def _section_portes(hass: HomeAssistant) -> dict[str, Any]:
    """Les portes qu'un adulte ouvre exprès, sur une machine précise.

    Tout le reste se décide pour la maison entière, et c'est voulu : le
    décider quatre fois serait quatre occasions d'en oublier une. Ici, et
    ici seulement, la décision redevient machine par machine.
    """
    return _bloc(
        [
            _titre(_t(hass, "portes_titre"), "mdi:door-open", "subtitle"),
            _mot(_t(hass, "portes_aide")),
            {
                "type": "entities",
                "entities": _outils(
                    hass,
                    (
                        ("select", f"{DOMAIN}_site_constate", _t(hass, "portes_quel_site")),
                        ("select", f"{DOMAIN}_pc_cible", _t(hass, "portes_quel_appareil")),
                        ("select", f"{DOMAIN}_origine_cible", _t(hass, "portes_ouvert_par")),
                        ("select", f"{DOMAIN}_adresse_constatee", _t(hass, "portes_adresse_constatee")),
                        ("text", f"{DOMAIN}_saisie_adresse", _t(hass, "portes_adresse_a_ecrire")),
                        ("number", f"{DOMAIN}_duree_laissez_passer", _t(hass, "portes_duree")),
                    ),
                ),
            },
            _boutons(
                hass,
                (
                    (f"{DOMAIN}_bloquer_origine", _t(hass, "portes_bloquer_programme"), "mdi:application-cog-outline"),
                    (f"{DOMAIN}_epargner_adresse", _t(hass, "portes_epargner"), "mdi:door-open"),
                    (f"{DOMAIN}_retirer_adresse", _t(hass, "portes_ne_plus_epargner"), "mdi:door-closed"),
                ),
            ),
            _boutons(
                hass,
                (
                    (f"{DOMAIN}_laisser_passer", _t(hass, "portes_laisser_passer"), "mdi:timer-play"),
                    (f"{DOMAIN}_fermer_laissez_passer", _t(hass, "portes_refermer"), "mdi:timer-off"),
                ),
            ),
            _carte_tableau(
                hass,
                f"{DOMAIN}_exceptions",
                "",
                _t(hass, "portes_aucune"),
                platform="sensor",
            ),
        ]
    )


def _vue_listes(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> dict[str, Any]:
    """Sites, logiciels et étiquettes : un seul onglet, une seule carte.

    Avant : un onglet *Logiciels*, un onglet *Sites*, un onglet
    *Étiquettes*, et dans chacun une liste déroulante, trois boutons et
    deux tableaux. On choisissait dans la liste, on cliquait, puis on
    cherchait dans quel tableau l'élément était parti. Trois onglets pour
    une seule question — qu'est-ce qui est permis chez nous ?

    Ici, une ligne par élément, son état écrit dessus, et les boutons au
    bout de la ligne. Ce qu'on décide, on le voit, et on peut revenir
    dessus sans changer d'onglet. Les mêmes gestes valent sur douze lignes
    cochées d'un coup.
    """
    return {
        "type": "sections",
        "title": _t(hass, "onglet_autorisations"),
        "path": "listes",
        "icon": "mdi:check-decagram-outline",
        "max_columns": 2,
        "sections": [
            {
                "type": "grid",
                "column_span": 2,
                "cards": [
                    {
                        "type": "custom:vision-etiquettes-card",
                        "title": _t(hass, "listes_carte_titre"),
                        "grid_options": {"columns": "full"},
                    },
                ],
            },
            {
                "type": "grid",
                "cards": [
                    _bloc(
                        [
                            _titre(
                                _t(hass, "comment_ca_marche"),
                                "mdi:help-circle-outline",
                                "subtitle",
                            ),
                            _mot(_t(hass, "listes_aide") + "\n\n_" + _t(hass, "carte_vide") + "_"),
                        ]
                    ),
                    _section_inscrire(hass),
                ],
            },
            {
                "type": "grid",
                "cards": [_section_portes(hass)],
            },
        ],
    }


def _vue_processus(hass: HomeAssistant) -> dict[str, Any]:
    """Ce qui tourne sur les machines, et ce qui démarre tout seul.

    Même geste que pour les sites et les logiciels : un classeur propose,
    un adulte valide, et rien ne bouge sur un PC avant cette validation.
    Un jeu n'est jamais *bloqué* — au pire sorti du démarrage automatique,
    ce qui ne l'empêche pas de se lancer quand on le lance.
    """
    return {
        "type": "sections",
        "title": _t(hass, "onglet_processus"),
        "path": "processus",
        "icon": "mdi:cog-transfer-outline",
        "max_columns": 1,
        "sections": [
            {
                "type": "grid",
                "cards": [
                    _titre(_t(hass, "processus_titre"), "mdi:cog-transfer-outline"),
                    {
                        "type": "custom:vision-processus-card",
                        "title": _t(hass, "processus_carte_titre"),
                        "grid_options": {"columns": "full"},
                    },
                    *(
                        [
                            {
                                "type": "entities",
                                "entities": [
                                    {
                                        "entity": desinstall,
                                        "name": _t(hass, "processus_desinstallation"),
                                    }
                                ],
                            }
                        ]
                        if (desinstall := _eid(hass, "switch", f"{DOMAIN}_desinstallation"))
                        else []
                    ),
                    _titre(
                        _t(hass, "comment_ca_marche"),
                        "mdi:help-circle-outline",
                        "subtitle",
                    ),
                    _mot(_t(hass, "processus_aide") + "\n\n_" + _t(hass, "carte_vide") + "_"),
                ],
            }
        ],
    }


def _vue_veille(hass: HomeAssistant, coord: PcParentalCoordinator) -> dict[str, Any]:
    """L'écran de veille des télés : les tableaux à composer, et les réglages d'ambiance."""
    reglages = []
    for plateforme, uid in (("select", "veille_theme"), ("select", "veille_radio"), ("text", "veille_nuit"), ("text", "veille_musique"),
                            ("text", "veille_entites"), ("text", "veille_courbe"), ("text", "veille_flux_suffixe")):
        if eid := _eid(hass, plateforme, f"{DOMAIN}_{uid}"):
            reglages.append(eid)
    return {
        "type": "sections",
        "title": _t(hass, "onglet_veille"),
        "path": "veille",
        "icon": "mdi:television-ambient-light",
        "max_columns": 3,
        "sections": [
            {
                "type": "grid",
                "column_span": 2,
                "cards": [
                    {"type": "custom:vision-dashboards-card", "grid_options": {"columns": "full"}},
                    {
                        "type": "markdown",
                        "content": "_" + _t(hass, "carte_vide") + "_",
                    },
                ],
            },
            {
                "type": "grid",
                "cards": [
                    _titre(_t(hass, "veille_sur_chaque_appareil"), "mdi:palette-outline"),
                    {
                        "type": "markdown",
                        "content": _t(hass, "veille_aide"),
                    },
                ],
            },
        ],
    }


def _vue_statistiques(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> dict[str, Any]:
    """Ce que les machines ont fait, en chiffres.

    Les courbes et le temps d'écran n'appellent aucune décision : on vient
    les regarder, pas les changer. Les mêler aux cartes des PC, c'était
    faire descendre tout le monde pour rien.
    """
    sections = [] if modules.coupe(coord.store, "filtrage") else [_section_courbe(hass, coord)]
    # 1.97.0 (point 16.3) : d'abord le temps d'écran des 7 derniers jours, en barres, un appareil par couleur.
    temps = [e for pc_id in sorted(coord.pcs, key=lambda p: coord.pc_name(p).lower())
             if (e := _eid(hass, "sensor", f"{DOMAIN}_temps_ecran_{pc_id}"))]
    if temps and not modules.coupe(coord.store, "temps"):
        sections.insert(0, {"type": "grid", "column_span": 2, "cards": [
            {"type": "heading", "heading": _t(hass, "statistiques_temps_7_jours"), "icon": "mdi:chart-bar"},
            {"type": "statistics-graph", "entities": temps, "chart_type": "bar", "period": "day", "days_to_show": 7,
             "stat_types": ["change"], "hide_legend": False},
        ]})
    for pc_id in sorted(coord.pcs, key=lambda p: coord.pc_name(p).lower()):
        lignes = _outils(
            hass,
            (
                ("sensor", f"{DOMAIN}_temps_ecran_{pc_id}", _t(hass, "statistiques_temps_ecran")),
                ("sensor", f"{DOMAIN}_blocages_{pc_id}", _t(hass, "statistiques_sites_bloques")),
                ("sensor", f"{DOMAIN}_alertes_{pc_id}", _t(hass, "statistiques_tentatives")),
                ("sensor", f"{DOMAIN}_presence_{pc_id}", _t(hass, "statistiques_presence")),
            ),
        )
        if not lignes:
            continue
        cartes = [
            _titre(coord.pc_name(pc_id), "mdi:chart-box-outline", "subtitle"),
            {"type": "entities", "entities": lignes},
        ]
        if eid := _eid(hass, "sensor", f"{DOMAIN}_alertes_{pc_id}"):
            cartes.append(
                {
                    "type": "markdown",
                    "content": (
                        "{% set l = state_attr('" + eid + "', 'lignes') %}"
                        "{% if l %}"
                        "{{ state_attr('" + eid + "', 'entete') }}\n"
                        "{% for r in l %}{{ r }}\n{% endfor %}"
                        "{% else %}_" + _t(hass, "statistiques_aucune_tentative") + "_{% endif %}"
                    ),
                }
            )
        sections.append({"type": "grid", "cards": [_bloc(cartes)]})

    return {
        "type": "sections",
        "title": _t(hass, "onglet_activite"),
        "path": "statistiques",
        "icon": "mdi:chart-timeline-variant",
        "max_columns": 3,
        "sections": sections,
    }


def _vue_message(hass: HomeAssistant, coord: PcParentalCoordinator) -> dict[str, Any]:
    """Envoyer un message sur l'écran d'un enfant.

    C'est le seul geste de Vision qui parle à quelqu'un plutôt qu'à une
    machine. Coincé entre les cartes des PC, on ne le trouvait qu'en
    descendant ; il mérite d'être à un clic.
    """
    return {
        "type": "sections",
        "title": _t(hass, "onglet_messages"),
        "path": "message",
        "icon": "mdi:message-text",
        "max_columns": 2,
        # 2.0.0 (notifications v2) : écrire, suivre les réponses, gérer les types. Lot 1 (HA-06) : dessous, plus de
        # second formulaire, seulement l'aide (repliée) pour envoyer depuis une automatisation.
        "sections": [{"type": "grid", "column_span": 2, "cards": [{"type": "custom:vision-messages-card"}]},
                     _section_message(hass, coord)],
    }


def _section_retrait(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> dict[str, Any]:
    """Retirer une machine, au même endroit qu'on en ajoute une.

    Le bouton vivait au bas de la carte de chaque PC, là où l'on va pour
    tout autre chose. Un geste irréversible n'a rien à faire sur le chemin
    des gestes quotidiens : il va avec son contraire, dans la page où l'on
    vient exprès.

    Lot 1 (HA-01) : plus de tuile « Supprimer cet appareil » en un appui, avec une cible préremplie. Le retrait se
    fait sous l'appareil, dans la page de sa personne (carte Vision) : un dialogue dit ce qui sera perdu et demande
    le nom de l'appareil. Ici ne reste que l'explication. Les entités (appareil ciblé, bouton) ne changent pas.
    """
    return {
        "type": "grid",
        "cards": [
            _bloc(
                [
                    _titre(_t(hass, "retrait_titre"), "mdi:delete-outline"),
                    _mot(_t(hass, "retrait_aide")),
                ]
            )
        ],
    }


def _vue_plages(hass: HomeAssistant, coord: PcParentalCoordinator) -> dict[str, Any]:
    """Les plages horaires, pour une machine ou pour toutes.

    Elles étaient au fond de la page de gestion, entre le code d'appairage
    et la désinstallation. C'est pourtant le réglage qu'on revient changer
    le plus souvent : il mérite son onglet.
    """
    sections = [_section_gestion(hass, coord)]
    for pc_id in sorted(coord.pcs, key=lambda p: coord.pc_name(p).lower()):
        cartes: list[dict[str, Any]] = [
            _entete_appareil(coord.store.pcs.get(pc_id) or {}, coord.pc_name(pc_id), "subtitle"),
        ]
        # La grille semaine remplace les listes d'entités : les plages se
        # créent au glisser, se déplacent, s'étirent et se règlent au clic
        # (portée, étiquettes, couleur comprises).
        if eid := _eid(hass, "sensor", f"{DOMAIN}_plages_{pc_id}"):
            cartes.append(
                {
                    "type": "custom:vision-planning-card",
                    "entity": eid,
                    "title": _t(hass, "planning"),
                }
            )
        else:
            cartes.append(
                {
                    "type": "markdown",
                    "content": (
                        "_La carte planning attend le capteur « Plages » "
                        "de cette machine : recharge l'intégration._"
                    ),
                }
            )
        if eid := _eid(hass, "sensor", f"{DOMAIN}_suivant_{pc_id}"):
            cartes.append(
                {
                    "type": "entities",
                    "entities": [
                        {"entity": eid, "name": "Prochain changement"}
                    ],
                }
            )
        sections.append({"type": "grid", "cards": cartes})

    return {
        "type": "sections",
        "title": _t(hass, "planning"),
        "path": "plages",
        "icon": "mdi:calendar-clock",
        "max_columns": 3,
        "sections": sections,
    }


def premiere_phrase(texte: str, longueur_max: int = 220) -> tuple[str, str]:
    """La première phrase d'un texte d'aide (si elle est courte et simple), et le reste. Logique pure, testée."""
    t = texte.strip()
    if t.startswith(("|", "#", "- ", "* ", ">", "<")):
        return "", t
    for i, ch in enumerate(t[:longueur_max]):
        if ch in ".!?" and (i + 1 == len(t) or t[i + 1] in " \n"):
            if t.count("**", 0, i) % 2 or t.count("[", 0, i) != t.count("]", 0, i):
                continue  # pas au milieu d'un gras ni d'un lien
            return t[: i + 1], t[i + 1:].strip()
    return "", t


def _entites_en_tuiles(vue: dict[str, Any]) -> None:
    """1.96.0 (point 16.3) : une liste d'entités simples (interrupteurs, capteurs, boutons) devient une grille de
    tuiles. Les formulaires (textes à saisir) restent en liste : une tuile ne sait pas les remplir."""
    simples = ("switch.", "sensor.", "binary_sensor.", "button.", "number.", "select.", "update.")
    conteneurs = [*(vue.get("sections") or [])]
    # Aussi dans les piles (vertical-stack) : les cartes des appareils de l'onglet Activité, par exemple.
    i = 0
    while i < len(conteneurs):
        for c in conteneurs[i].get("cards") or []:
            if isinstance(c, dict) and c.get("type") in ("vertical-stack", "horizontal-stack"):
                conteneurs.append(c)
        i += 1
    for section in conteneurs:
        neuves: list[Any] = []
        for carte in section.get("cards") or []:
            ents = carte.get("entities") if isinstance(carte, dict) and carte.get("type") == "entities" else None
            if ents and all(isinstance(e, (str, dict)) for e in ents):
                ids = [e if isinstance(e, str) else e.get("entity") for e in ents]
                if all(isinstance(i, str) and i.startswith(simples) for i in ids):
                    tuiles = []
                    for e, i in zip(ents, ids):
                        t: dict[str, Any] = {"type": "tile", "entity": i}
                        if isinstance(e, dict) and e.get("name"):
                            t["name"] = e["name"]
                        tuiles.append(t)
                    neuves.append({"type": "grid", "columns": 2, "square": False, "cards": tuiles})
                    continue
            neuves.append(carte)
        section["cards"] = neuves


def _sans_titre_aide(noeud: Any, titre: str) -> None:
    """1.96.0 : plus de titre « Comment ça marche » : l'aide dit sa première phrase, le reste est sous « En savoir plus »."""
    if isinstance(noeud, list):
        for n in noeud:
            _sans_titre_aide(n, titre)
        return
    if not isinstance(noeud, dict):
        return
    if isinstance(noeud.get("cards"), list):
        noeud["cards"] = [c for c in noeud["cards"] if not (isinstance(c, dict) and c.get("type") == "heading"
                                                             and str(c.get("heading") or "").strip() == titre)]
    for cle in ("cards", "sections"):
        if cle in noeud:
            _sans_titre_aide(noeud[cle], titre)


def _replier_longs(noeud: Any, plus: str, seuil: int = 420) -> None:
    """Replie les cartes markdown bavardes derrière « Comment ça marche ».

    Un tableau de bord se lit d'un coup d'œil ; le mode d'emploi reste à
    portée de clic, sans occuper la page. Les cartes qui affichent des
    données (gabarits Jinja) ne sont pas touchées. `plus` : « En savoir plus », dans la langue du tableau.
    """
    if isinstance(noeud, list):
        for n in noeud:
            _replier_longs(n, plus, seuil)
        return
    if not isinstance(noeud, dict):
        return
    if noeud.get("type") == "markdown":
        texte = str(noeud.get("content") or "")
        if len(texte) > seuil and "{%" not in texte and "<details" not in texte:
            # 1.96.0 (point 16.3) : l'aide en une phrase, visible ; le reste replié sous « En savoir plus ».
            phrase, reste = premiere_phrase(texte)
            noeud["content"] = (phrase + "\n\n" if phrase else "") + (
                "<details><summary>" + plus + "</summary>\n\n" + reste + "\n\n</details>" if reste else "")
        return
    for cle in ("cards", "sections", "views"):
        if cle in noeud:
            _replier_longs(noeud[cle], plus, seuil)


def _personnes_du_tableau(
    hass: HomeAssistant, coord: PcParentalCoordinator
) -> list[dict[str, Any]]:
    """Une page par personne qu'un appareil désigne, et une pour les
    appareils que personne ne revendique encore."""
    personnes: dict[str, dict[str, Any]] = {}
    orphelins = False
    for pc in coord.store.pcs.values():
        entite = str(pc.get("person") or "")
        if not entite:
            orphelins = True
            continue
        if entite in personnes:
            continue
        etat = hass.states.get(entite)
        cle = entite.split(".")[-1]
        personnes[entite] = {
            "entite": entite,
            "cle": cle,
            "prenom": str(etat.name) if etat is not None and etat.name else cle,
        }
    # 1.86.2 : une personne de Home Assistant sans appareil n'a sa page que si un rôle
    # lui a été choisi (Configurer → Personnes et rôles) ou si un appareil lui est partagé ; sinon pas de
    # page vide (« Affichage »…).
    partagees = {str(x) for pc in coord.store.pcs.values() for x in (pc.get("partage") or []) if str(x).startswith("person.")}
    for etat in hass.states.async_all("person"):
        if etat.entity_id in personnes or (etat.entity_id not in coord.store.roles_personnes and etat.entity_id not in partagees):
            continue
        cle = etat.entity_id.split(".")[-1]
        personnes[etat.entity_id] = {
            "entite": etat.entity_id,
            "cle": cle,
            "prenom": str(etat.name) if etat.name else cle,
        }
    sortie = sorted(personnes.values(), key=lambda p: p["prenom"].lower())
    if orphelins:
        sortie.append({"entite": "", "cle": "autres", "prenom": _t(hass, "sans_personne")})
    return sortie


def _vue_personne(
    hass: HomeAssistant, coord: PcParentalCoordinator, personne: dict[str, Any]
) -> dict[str, Any]:
    """La page d'une personne : on y arrive depuis la ligne de la maison."""
    sections: list[dict[str, Any]] = [
        {
            "type": "grid",
            "column_span": 2,
            "cards": [
                {
                    "type": "custom:vision-maison-card",
                    "mode": "personne",
                    "grid_options": {"columns": "full"},
                    "personne": personne["entite"] or personne["cle"],
                    "chemin": f"/{URL_PATH}",
                }
            ],
        }
    ]
    # Le planning est celui de la personne : un seul, quel que soit le nombre de ses appareils.
    # (Les appareils sans personne gardent chacun le leur.) 1.82.0 : pas de planning sans le module « Plages ».
    appareils_planifies = 0
    for pc_id in ([] if modules.coupe(coord.store, "plages") else sorted(coord.pcs, key=lambda p: coord.pc_name(p).lower())):
        pc = coord.store.pcs.get(pc_id) or {}
        if str(pc.get("person") or "") != personne["entite"]:
            continue
        if personne["entite"] and appareils_planifies:
            break
        if eid := _eid(hass, "sensor", f"{DOMAIN}_plages_{pc_id}"):
            nombre = len(coord.store._siens(pc))
            sections.append(
                {
                    "type": "grid",
                    "column_span": 2,
                    "cards": [
                        {
                            "type": "heading",
                            "heading": _t(hass, "planning_de", prenom=personne["prenom"]),
                            "heading_style": "subtitle",
                            "icon": "mdi:calendar-clock",
                        }
                        if personne["entite"]
                        else _entete_appareil(pc, coord.pc_name(pc_id), "subtitle"),
                        {
                            "type": "custom:vision-planning-card",
                            "entity": eid,
                            "title": _t(hass, "planning") if nombre < 2
                            else _TEXTES.n("planning_sur_appareils", nombre, langues.langue_hass(hass), store=coord.store),
                        },
                        *(
                            [{"type": "tile", "entity": suivant, "name": _t(hass, "prochain_changement"),
                              "icon": "mdi:calendar-arrow-right"}]
                            if (suivant := _eid(hass, "sensor", f"{DOMAIN}_suivant_{pc_id}"))
                            else []
                        ),
                    ],
                }
            )
            appareils_planifies += 1
    # 1.96.0 (point 16.3) : plus de formulaire « Ajouter une plage » répété sur chaque page : la carte planning
    # (« Nouvelle plage ») le fait, au bon endroit, sans liste d'entités à remplir.
    return {
        "type": "sections",
        "title": personne["prenom"],
        "path": f"{PREFIXE_PERSONNE}{personne['cle']}",
        "icon": "mdi:account",
        "subview": True,
        "back_path": f"/{URL_PATH}/maison",
        "max_columns": 3,
        "sections": sections,
    }


def _section_pause_generale(hass: HomeAssistant, coord: PcParentalCoordinator) -> dict[str, Any] | None:
    """« Tout fermer » (ex-pause générale) sur l'accueil : état (jusqu'à quelle heure) et deux boutons. None sans le module."""
    if modules.coupe(coord.store, "pause_generale"):
        return None
    # Lot 1 (HA-08) : « Pause générale » s'appelle « Tout fermer » (sa fin, « Rouvrir »), et fermer tous les appareils
    # protégés demande une confirmation : un appui de trop sur l'accueil ne ferme plus tout le lieu. Les entités
    # (boutons, capteur) ne changent pas.
    boutons = _boutons(hass, (
        (f"{DOMAIN}_pause_generale_30", _t(hass, "pause_generale_30"), "mdi:lock-clock"),
        (f"{DOMAIN}_pause_generale_fin", _t(hass, "pause_generale_fin"), "mdi:lock-open-variant-outline"),
    ))
    if boutons is not None:
        for bouton in boutons["cards"]:
            action: dict[str, Any] = {"action": "perform-action", "perform_action": "button.press",
                                      "target": {"entity_id": bouton["entity"]}}
            if bouton["entity"] == _eid(hass, "button", f"{DOMAIN}_pause_generale_30"):
                action["confirmation"] = {"text": _t(hass, "pause_generale_confirmation")}
            bouton["tap_action"] = action
            bouton["hold_action"] = {"action": "more-info"}
    capteur = _eid(hass, "binary_sensor", f"{DOMAIN}_pause_generale")
    if boutons is None and capteur is None:
        return None
    cartes: list[dict[str, Any]] = [_titre(_t(hass, "pause_generale"), "mdi:lock-alert-outline")]
    if capteur:
        cartes.append(_mot(
            "{% if is_state('" + capteur + "', 'on') %}"
            + _t(hass, "pause_generale_en_cours", heure="{{ state_attr('" + capteur + "', 'fin_heure') }}")
            + "{% else %}" + _t(hass, "pause_generale_aucune") + "{% endif %}"))
    if boutons is not None:
        cartes.append(boutons)
    return {"type": "grid", "column_span": 2, "cards": cartes}


def build(hass: HomeAssistant, coord: PcParentalCoordinator) -> dict[str, Any]:
    """Un seul tableau de bord, un onglet par chose à faire.

    Deux tableaux de bord, c'était un de trop : on cherchait dans lequel se
    trouvait ce qu'on voulait. Les onglets font le travail, et Home Assistant
    les affiche avec leur icône en haut de page.
    """
    # Lot 1 (HA-04) : la première vue s'appelle « Aujourd'hui » (et non plus du nom du lieu). Son chemin reste
    # « maison » : les liens déjà écrits (notifications, cartes, favoris) continuent de mener ici.
    vue_maison = {
        "type": "sections",
        "title": _t(hass, "onglet_aujourdhui"),
        "path": "maison",
        "icon": "mdi:home-heart",
        "max_columns": 2,
        "sections": [
            {
                "type": "grid",
                "column_span": 2,
                "cards": [
                    {
                        "type": "custom:vision-maison-card",
                        "mode": "maison",
                        "grid_options": {"columns": "full"},
                        "chemin": f"/{URL_PATH}",
                    },
                ],
            },
        ],
    }
    # « Tout fermer » (ex-pause générale, point 14.2) : les deux boutons et l'état, sur l'accueil, si le module est actif.
    if (section_pg := _section_pause_generale(hass, coord)) is not None:
        vue_maison["sections"].append(section_pg)
    vues_personnes = [
        _vue_personne(hass, coord, personne)
        for personne in _personnes_du_tableau(hass, coord)
    ]

    # Protection : les sites dangereux ou inconnus, puis ce qui démarre tout seul sur les machines.
    sections_processus = list(_vue_processus(hass).get("sections") or [])
    for section in sections_processus:
        if isinstance(section, dict):
            section.setdefault("column_span", 3)
    # 1.82.0 : les sites dangereux relèvent du « Filtrage web », le démarrage des « Optimisations PC ».
    if modules.coupe(coord.store, "optimisations"):
        sections_processus = []
    vue_protection = {
        "type": "sections",
        "title": _t(hass, "onglet_protection"),
        "path": "protection",
        "icon": "mdi:shield-lock-outline",
        "max_columns": 3,
        "sections": [
            # 1.96.0 (point 16.3) : d'abord l'essentiel, une tuile par appareil (tout est en place, ou quoi faire).
            # (seulement si un module qui en dépend vit : protections, blocage d'applis ou filtrage web).
            *([] if modules.coupe(coord.store, "protections", "blocage", "filtrage") else [
                {"type": "grid", "column_span": 3, "cards": [
                    {"type": "heading", "heading": _t(hass, "protection_sur_appareils"), "icon": "mdi:cellphone-check"},
                    {"type": "custom:vision-maison-card", "mode": "protections"},
                ]}]),
            *([] if modules.coupe(coord.store, "filtrage") else _sections_securite(hass, coord)),
            *sections_processus],
    }
    # Appareils : installer un PC, ajouter un téléphone ou une télé, retirer un appareil.
    vue_appareils = {
        "type": "sections",
        "title": _t(hass, "onglet_appareils"),
        "path": "appareils",
        "icon": "mdi:devices",
        "max_columns": 3,
        "sections": [
            _section_installation(hass, coord),
            _section_retrait(hass, coord),
            # 1.95.0 (point 16.2) : l'apparence du tableau (palette, clair / sombre / automatique, accent).
            {"type": "grid", "cards": [{"type": "custom:vision-apparence-card"}]},
        ],
    }

    # 1.82.0 : l'onglet d'un module coupé n'est plus construit (il revient avec le module).
    coupe = lambda *m: modules.coupe(coord.store, *m)  # noqa: E731
    vues = [
        vue_maison,
        *vues_personnes,
        *([] if coupe("blocage", "filtrage") else [_vue_listes(hass, coord)]),
        _vue_statistiques(hass, coord),
        *([] if coupe("messages") else [_vue_message(hass, coord)]),
        *([vue_protection] if vue_protection["sections"] else []),
        *([] if coupe("veille") else [_vue_veille(hass, coord)]),
        vue_appareils,
        # 1.94.0 (point 16.1) : les entités Home Assistant données aux personnes, réservé aux administrateurs (la carte le vérifie).
        *([] if coupe("entites") else [{"type": "sections", "title": _t(hass, "onglet_widgets"), "path": "widgets", "icon": "mdi:home-import-outline",
                                          "max_columns": 2, "sections": [{"type": "grid", "column_span": 2,
                                                                          "cards": [{"type": "custom:vision-widgets-card"}]}]}]),
    ]
    plus, comment = _t(hass, "en_savoir_plus"), _t(hass, "comment_ca_marche")
    for v in vues:
        # Lot 1 (HA-04) : des onglets avec leur nom écrit. Home Assistant montre l'icône d'une vue à la place de son
        # titre (8 pictogrammes muets, 3 cachés derrière « › » sur téléphone) : sans icône, il écrit le titre.
        if not v.get("subview"):
            v.pop("icon", None)
        _replier_longs(v, plus)
        _sans_titre_aide(v, comment)
        _entites_en_tuiles(v)  # 1.97.0 : partout, les listes d'entités simples deviennent des tuiles
    # Le tableau de Vision porte son thème (rouge profond, or, vert) quand il
    # est installé dans themes/. Il ne touche qu'à ce tableau : chacun garde
    # son thème ailleurs, et peut retirer la ligne depuis l'éditeur.
    if THEME in (hass.data.get("frontend_themes") or {}):
        for v in vues:
            v["theme"] = THEME
    return nettoyer({"views": vues})


def nettoyer(noeud: Any) -> Any:
    """1.85.1 : retire toute carte nulle ou sans type, à toute profondeur.

    Une carte qui vaut `null` (une ligne `None` restée dans le générateur depuis la 1.52) s'affichait en
    « Erreur de configuration » dans l'appli compagnon et masquait la vue. Le tableau généré n'en contient
    plus jamais, même si une fonction de construction en rend une.
    """
    if isinstance(noeud, dict):
        for cle in ("cards", "sections", "views", "badges"):
            if isinstance(noeud.get(cle), list):
                noeud[cle] = [nettoyer(x) for x in noeud[cle] if isinstance(x, dict) and (cle != "cards" or x.get("type"))]
        if isinstance(noeud.get("card"), dict):
            nettoyer(noeud["card"])
    return noeud


def signature(coord: PcParentalCoordinator) -> str:
    """Empreinte de la structure : change quand il faut reconstruire."""
    morceaux = []
    for pc_id in sorted(coord.pcs):
        pc = coord.store.get(pc_id) or {}
        regles = ",".join(str(r["id"]) for r in coord.rules(pc_id))
        morceaux.append(f"{pc_id}:{pc.get('name')}:{pc.get('person')}:{pc.get('type')}:{regles}")
    morceaux.append(",".join(coord.store.personnes_suivies()))
    # Le nom du lieu titre l'onglet d'accueil : le changer refait le tableau.
    morceaux.append("lieu:" + lieu.nom(coord.store))
    morceaux.append("mots:" + repr(sorted((lg, sorted(m.items())) for lg, m in (getattr(coord.store, "mots_vocabulaire", None) or {}).items())))
    # 1.82.0 : les modules actifs choisissent les onglets.
    morceaux.append("modules:" + ",".join(modules.public(coord.store)))
    # 1.84.0 : les textes du tableau changent aussi (vocabulaire neutre, nom du lieu) ; sans cette marque, un
    # tableau déjà en place gardait ses anciens textes. À changer à chaque nouvelle rédaction.
    from .const import VERSION

    morceaux.append("version:" + VERSION)  # 1.86.4 : chaque version refait le tableau au démarrage
    # i18n : le tableau est écrit dans la langue de Home Assistant ; la changer le refait.
    morceaux.append("langue:" + langues.langue_hass(coord.hass))
    # Une personne ajoutée ou retirée dans Home Assistant refait le tableau.
    try:
        morceaux.append(",".join(sorted(e.entity_id for e in coord.hass.states.async_all("person"))))
    except Exception:  # noqa: BLE001 - l'empreinte ne doit jamais casser
        pass
    return "|".join(morceaux)


def _tableaux(hass: HomeAssistant):
    """Dictionnaire des tableaux de bord Lovelace, quelle que soit la version."""
    lovelace = hass.data.get("lovelace")
    if lovelace is None:
        return None
    tableaux = getattr(lovelace, "dashboards", None)
    if tableaux is None and isinstance(lovelace, dict):
        tableaux = lovelace.get("dashboards")
    return tableaux


async def _async_reclamer(hass: HomeAssistant, url: str, titre: str) -> None:
    """Demande à l'utilisateur de créer un tableau de bord manquant.

    Une intégration **ne peut pas** créer ni renommer un tableau de bord :
    la collection Lovelace est une variable locale de son `async_setup`,
    elle n'est rangée nulle part dans `hass.data` (vérifié de 2024.3 à
    2026.x). Passer par une seconde instance de la collection écraserait
    la liste tenue en mémoire par Home Assistant à la première
    modification faite depuis l'interface : hors de question.

    On le dit donc clairement, une fois, plutôt que d'échouer en silence —
    c'est exactement ce qui s'était produit avec le renommage.
    """
    persistent_notification.async_create(
        hass,
        (
            f"**{PRODUIT_AFFICHE}** a besoin du tableau de bord "
            f"« {titre} », qu'une intégration n'a pas le droit de créer "
            "elle-même.\n\n"
            "**Paramètres → Tableaux de bord → Ajouter un tableau de "
            "bord → Nouveau tableau de bord à partir de zéro**, puis :\n\n"
            f"- Titre : `{titre}`\n"
            "- Icône : `mdi:table-eye`\n"
            "- Réservé aux administrateurs : oui\n\n"
            "Ouvre ensuite ses **paramètres** (les trois points, en haut à "
            f"droite) et donne-lui l'URL `{url}`.\n\n"
            "Vision le remplira tout seul dans la minute qui suit."
        ),
        title=f"{PRODUIT} : tableau de bord à créer",
        notification_id=f"{DOMAIN}_tableau_{url}",
    )


def _repointer(valeur):
    """Remplace l'ancien chemin par le nouveau, partout où il traîne.

    Les onglets faits à la main renvoient vers `/controle-parental-pc/…` :
    ces liens ne mènent plus nulle part maintenant que tout vit dans
    « Vision ». On les répare plutôt que de les laisser casser.
    """
    if isinstance(valeur, str):
        return valeur.replace(f"/{URL_ANCIEN}/", f"/{URL_PATH}/")
    if isinstance(valeur, list):
        return [_repointer(v) for v in valeur]
    if isinstance(valeur, dict):
        return {c: _repointer(v) for c, v in valeur.items()}
    return valeur


async def _a_la_main(store, propres: set[str] | frozenset[str] = frozenset()) -> list[dict[str, Any]]:
    """Les onglets que Vision n'a pas écrits : ceux de la maison.

    Le tableau de bord n'appartient pas à l'intégration. La page d'accueil
    que l'on s'est faite doit survivre à chaque régénération, et rester
    devant : c'est elle qu'on ouvre en premier.
    """
    try:
        # `async_load` de Lovelace réclame son drapeau « force » — sans lui,
        # TypeError, et l'on perdrait la page d'accueil à la première
        # régénération. C'est exactement ce qu'on essaie d'éviter ici.
        config = await store.async_load(False)
    except Exception:  # noqa: BLE001 - un tableau vide n'est pas une panne
        return []
    vues = (config or {}).get("views") or []
    # 1.97.1 : un onglet que Vision écrit lui-même n'est jamais « fait à la main », même s'il manque à CHEMINS
    # (l'onglet Widgets HA s'empilait à chaque régénération) ; et un même chemin n'est gardé qu'une fois.
    gardees: list[dict[str, Any]] = []
    vus: set[str] = set()
    for vue in vues:
        if not isinstance(vue, dict):
            continue
        chemin = str(vue.get("path") or "")
        if chemin in CHEMINS or chemin in propres or chemin.startswith(PREFIXE_PERSONNE):
            continue
        if chemin and chemin in vus:
            continue
        vus.add(chemin)
        gardees.append(_repointer(vue))
    return gardees


async def async_build(hass: HomeAssistant, coord: PcParentalCoordinator) -> bool:
    """Met à jour le contenu du tableau de bord. Sans casse en cas d'échec.

    Home Assistant ne permet pas à une intégration de *créer* un tableau de
    bord : il doit exister au préalable (Paramètres → Tableaux de bord →
    Ajouter, chemin « vision-maison »). Une fois créé, Vision y tient
    ses onglets — et laisse devant ceux qu'on a faits à la main.
    """
    try:
        tableaux = _tableaux(hass)
        if tableaux is None:
            _LOGGER.warning(
                "Lovelace indisponible : tableau de bord non mis à jour."
            )
            return False
        store = tableaux.get(URL_PATH)
        if store is None:
            _LOGGER.warning(
                "Le tableau de bord « %s » n'existe pas encore. "
                "Crée-le dans Paramètres → Tableaux de bord (chemin « %s », "
                "réservé aux administrateurs), puis appuie sur "
                "« Régénérer le tableau de bord ».",
                TITRE,
                URL_PATH,
            )
            return False
        # Les adultes : les administrateurs de Home Assistant. Ils sont les
        # seuls à voir les réglages qu'un enfant ne doit pas toucher.
        try:
            hass.data[f"{DOMAIN}_adultes"] = sorted(
                u.id
                for u in await hass.auth.async_get_users()
                if u.is_admin and u.is_active and not u.system_generated
            )
        except Exception:  # noqa: BLE001 - sans la liste, les réglages restent cachés
            hass.data[f"{DOMAIN}_adultes"] = []
        config = build(hass, coord)
        propres = {str(v.get("path")) for v in config["views"] if isinstance(v, dict) and v.get("path")}
        config["views"] = (await _a_la_main(store, propres)) + config["views"]
        await store.async_save(config)
        _LOGGER.debug("Tableau de bord « %s » mis à jour.", TITRE)

        return True
    except Exception:  # noqa: BLE001 - un tableau raté ne doit rien casser
        _LOGGER.exception("Impossible de générer le tableau de bord")
        return False
