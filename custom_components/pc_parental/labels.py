"""Étiquettes : celles de Home Assistant, pas un système parallèle.

Chaque PC est un **appareil** Home Assistant. Ses étiquettes sont donc celles
que Home Assistant connaît déjà (Paramètres → Zones et étiquettes), les mêmes
que pour une prise ou une lampe, et elles restent utilisables partout ailleurs
— automatisations, scripts, filtres. L'intégration n'en garde aucune copie :
le registre de Home Assistant fait foi.
"""

from __future__ import annotations

from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import label_registry as lr

from .registre import appareil_du_pc


def appareil(hass: HomeAssistant, pc_id: str):
    """Appareil Home Assistant de ce PC, ou None s'il n'existe pas encore."""
    return appareil_du_pc(hass, pc_id)


def noms_du_pc(hass: HomeAssistant, pc_id: str) -> list[str]:
    """Noms des étiquettes posées sur ce PC."""
    dispositif = appareil(hass, pc_id)
    if dispositif is None:
        return []
    registre = lr.async_get(hass)
    noms = []
    for identifiant in dispositif.labels:
        etiquette = registre.async_get_label(identifiant)
        if etiquette is not None:
            noms.append(etiquette.name)
    return sorted(noms, key=str.lower)


def definir(hass: HomeAssistant, pc_id: str, noms: list[str]) -> bool:
    """Pose exactement ces étiquettes sur le PC.

    Celles qui n'existent pas encore dans Home Assistant sont créées, comme
    le ferait l'interface. Rend True si quelque chose a changé.
    """
    dispositif = appareil(hass, pc_id)
    if dispositif is None:
        from . import textes

        raise textes.Erreur("labels.appareil_inconnu")
    registre = lr.async_get(hass)
    voulus: set[str] = set()
    for nom in noms:
        nom = str(nom).strip()
        if not nom:
            continue
        etiquette = registre.async_get_label_by_name(nom)
        if etiquette is None:
            etiquette = registre.async_create(nom)
        voulus.add(etiquette.label_id)
    if voulus == set(dispositif.labels):
        return False
    dr.async_get(hass).async_update_device(dispositif.id, labels=voulus)
    return True


def posees(hass: HomeAssistant, pcs) -> list[str]:
    """Étiquettes portées par au moins un PC — celles qui savent viser."""
    vues: set[str] = set()
    for pc_id in pcs:
        vues.update(noms_du_pc(hass, pc_id))
    return sorted(vues, key=str.lower)


def resoudre(hass: HomeAssistant, cible: str) -> str | None:
    """Nom d'étiquette à partir d'un nom **ou** d'un identifiant.

    Le sélecteur d'étiquettes de Home Assistant transmet un `label_id` ; un
    humain qui écrit une automatisation à la main tape le nom. Les deux
    doivent marcher.
    """
    registre = lr.async_get(hass)
    cible = str(cible or "").strip()
    if not cible:
        return None
    etiquette = registre.async_get_label(cible)
    if etiquette is None:
        etiquette = registre.async_get_label_by_name(cible)
    return etiquette.name if etiquette is not None else None


def pcs_avec(hass: HomeAssistant, pcs, cible: str) -> list[str]:
    """Identifiants des PC qui portent cette étiquette."""
    nom = resoudre(hass, cible)
    if nom is None:
        return []
    bas = nom.lower()
    return [
        pc_id
        for pc_id in pcs
        if any(n.lower() == bas for n in noms_du_pc(hass, pc_id))
    ]


# --------------------------------------------------- Vocabulaire Vision
#
# Depuis la 1.45.0, les catégories de sites et de logiciels ne sont plus un
# vocabulaire à part : ce sont les **mêmes** étiquettes que celles de Home
# Assistant. Une seule liste à tenir, un seul endroit où la renommer, une
# couleur et une icône offertes par l'interface — et le sélecteur natif
# remplace la saisie au clavier, qui était la vraie plaie.


def details(hass: HomeAssistant) -> list[dict]:
    """Toutes les étiquettes connues de Home Assistant, avec leur allure."""
    registre = lr.async_get(hass)
    sortie = [
        {
            "id": e.label_id,
            "nom": e.name,
            "icone": e.icon or "",
            "couleur": e.color or "",
        }
        for e in registre.async_list_labels()
    ]
    return sorted(sortie, key=lambda e: e["nom"].lower())


def vocabulaire(hass: HomeAssistant) -> list[str]:
    """Les noms seuls, dans l'ordre où on les lit."""
    return [e["nom"] for e in details(hass)]


# ------------------------------------------- Catégories utilisées par Vision
#
# Toutes les étiquettes de la maison ne sont pas des catégories : « Chauffage »
# range des automatisations de chaudière, « Chambre » une zone. Comme pour les
# cartes de l'écran de veille, on coche dans Vision celles qui servent
# (Autorisations → Étiquettes) ; le magasin ne garde que les écarts au défaut.
# Décocher ne retire rien : une étiquette encore posée quelque part continue de
# s'appliquer, et l'écran le dit.

# Cochées d'office, même si elles ne rangent aucun site ni logiciel : le code
# de Vision les lit par leur nom.
CATEGORIES_LUES_PAR_LE_CODE = ("parents", "enfants", "toujours autorisé")


def details_proposes(hass: HomeAssistant, magasin) -> list[dict]:
    """Les étiquettes que Vision montre et propose : cochées, ou encore posées quelque part."""
    garder = magasin.etiquettes_visibles()
    return [e for e in details(hass) if e["nom"].lower() in garder]


def proposees(hass: HomeAssistant, magasin) -> list[str]:
    """Les noms des étiquettes que Vision montre et propose."""
    return [e["nom"] for e in details_proposes(hass, magasin)]


def creer(hass: HomeAssistant, nom: str) -> str:
    """Fait exister cette étiquette dans Home Assistant, et rend son nom exact.

    Rendre le nom **du registre** et non celui qu'on a tapé évite de créer
    « jeux » à côté de « Jeux » : la casse est décidée une fois, à la
    création, et tout le reste s'y range.
    """
    nom = str(nom or "").strip()
    if not nom:
        from . import textes

        raise textes.Erreur("store.nom_etiquette")
    registre = lr.async_get(hass)
    etiquette = registre.async_get_label_by_name(nom)
    if etiquette is None:
        etiquette = registre.async_create(nom)
    return etiquette.name


def supprimer(hass: HomeAssistant, nom: str) -> bool:
    """Retire l'étiquette du registre de Home Assistant."""
    registre = lr.async_get(hass)
    cible = str(nom or "").strip()
    etiquette = registre.async_get_label(cible) or registre.async_get_label_by_name(
        cible
    )
    if etiquette is None:
        return False
    registre.async_delete(etiquette.label_id)
    return True


def noms(hass: HomeAssistant, valeurs) -> list[str]:
    """Résout une liste mêlant identifiants et noms en noms seuls.

    Le sélecteur d'étiquettes de Home Assistant transmet des `label_id` ;
    une automatisation écrite à la main transmet des noms. Les deux entrent
    ici et ressortent pareil, sans doublon.
    """
    sortie: list[str] = []
    for valeur in valeurs or []:
        nom = resoudre(hass, valeur)
        if nom is None:
            nom = str(valeur or "").strip()
        if nom and nom.lower() not in [s.lower() for s in sortie]:
            sortie.append(nom)
    return sortie


def migrer(hass: HomeAssistant, pcs) -> bool:
    """Reprend une fois pour toutes les anciennes étiquettes maison.

    Les versions ≤ 1.6.0 gardaient les étiquettes dans leur propre stockage.
    Elles deviennent de vraies étiquettes Home Assistant, sans rien perdre.
    Un PC dont l'appareil n'existe pas encore sera repris au tour suivant.
    """
    bouge = False
    for pc_id, pc in pcs.items():
        anciennes = [str(t).strip() for t in (pc.get("tags") or []) if str(t).strip()]
        if not anciennes:
            continue
        try:
            definir(
                hass,
                pc_id,
                sorted(
                    set(noms_du_pc(hass, pc_id)) | set(anciennes),
                    key=str.lower,
                ),
            )
        except ValueError:
            continue
        pc["tags"] = []
        bouge = True
    return bouge
