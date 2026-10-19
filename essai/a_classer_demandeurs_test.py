"""2.5.9 — compteur « à classer » (l'auteur, 17/10) : un site déjà rangé dans une catégorie et fermé pour les Demandeurs
ne compte plus.

Règle (session de classement, RAPPORT-classement-1017) : une catégorie ET une décision (bloqué ou autorisé) sur chaque
appareil d'un Demandeur qui a une personne. La télé du salon et le banc d'essai, sans personne, ont aussi le rôle
Demandeur par défaut mais aucun nom bloqué : ils ne doivent pas garder l'élément dans la file (sinon 115 → 115).

Avant : seule une décision pour tout le Lieu sortait un élément de la file. Adulte n'est fermée que sur les appareils
des Demandeurs (choix de l'auteur) : les sites adultes, bloqués un par un sur chacun de leurs appareils le 16/10, restaient
comptés (111).

Ce qu'on vérifie, sur un vrai PcStore :
- étiqueté et fermé sur chaque appareil d'un Demandeur (par le nom ou par la catégorie) : tranché, plus compté ;
- ouvert sur un seul appareil d'un Demandeur : toujours compté ;
- fermé partout mais sans catégorie : toujours compté (il faut une catégorie ET une décision) ;
- ce qui est ouvert chez un Décideur, sur un appareil SANS PERSONNE (télé, banc), un écran commun ou un appareil
  d'essai ne change rien ;
- une autorisation sur un appareil de Demandeur est aussi une décision ;
- sans aucun appareil de Demandeur : rien ne sort de la file.

    bash lancer.sh a_classer_demandeurs_test.py
"""
from __future__ import annotations

import copy

from party_musique_test import expected_lingering_tasks, expected_lingering_timers  # noqa: F401  (banc : custom_components)


async def _magasin(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental.store import PcStore

    hass_storage.pop("pc_parental", None)
    store = PcStore(hass)
    await store.async_load()
    hass.states.async_set("person.alex", "home", {"friendly_name": "Alex"})
    hass.states.async_set("person.sam", "home", {"friendly_name": "Sam"})
    hass.states.async_set("person.ja", "home", {"friendly_name": "l'auteur"})
    hass.states.async_set("person.affichage", "home", {"friendly_name": "Affichage"})
    from custom_components.pc_parental import roles
    store.roles = copy.deepcopy(roles.MODELES)
    store.roles_personnes.update({"person.alex": "enfant", "person.sam": "enfant", "person.ja": "parent"})
    store.pcs.clear()
    store.pcs.update({
        "pc-alex": {"id": "pc-alex", "person": "person.alex", "bloques": ["adulte.example"]},
        "tel-alex": {"id": "tel-alex", "person": "person.alex", "bloques": ["adulte.example"]},
        # Chez Sam, c'est la catégorie entière qui est bloquée.
        "pc-sam": {"id": "pc-sam", "person": "person.sam", "bloques": ["Adulte"]},
        # Décideur, écran commun, appareil d'essai : rien n'y est fermé.
        "pc-ja": {"id": "pc-ja", "person": "person.ja"},
        "tele-salon": {"id": "tele-salon", "person": "person.affichage"},
        "banc": {"id": "banc", "person": "person.alex"},
        # Comme le Téléviseur du salon et le banc de test réels : sans personne, donc Demandeurs par défaut.
        "tele-sans-personne": {"id": "tele-sans-personne", "person": ""},
        "banc-sans-personne": {"id": "banc-sans-personne"},
    })
    store.appareils_essai = ["banc"]
    etiquettes = {"adulte.example": ["Adulte"], "autre-adulte.example": ["Adulte"], "sans-etiquette.example": []}
    monkeypatch.setattr(store, "etiquettes_de", lambda genre, nom: list(etiquettes.get(nom, [])))
    monkeypatch.setattr(store, "catalogue_maison", lambda genre: list(etiquettes) if genre == "sites" else [])
    return store


async def test_ferme_chez_chaque_demandeur_ne_compte_plus(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental.store import FILTRE_A_CLASSER, FILTRE_BLOQUES

    store = await _magasin(hass, hass_storage, monkeypatch)
    # adulte.example : nommé chez Alex (2 appareils), catégorie chez Sam ; ouvert en essai, la télé et le banc.
    assert store.classe(None, "sites", "adulte.example") == FILTRE_BLOQUES
    # autre-adulte.example : fermé chez Sam seulement, ouvert sur les appareils de Alex.
    assert store.classe(None, "sites", "autre-adulte.example") == FILTRE_A_CLASSER
    assert store._compter_a_classer(None) == 2
    # Bloqué aussi chez Alex : il sort de la file.
    for i in ("pc-alex", "tel-alex"):
        store.pcs[i]["bloques"].append("autre-adulte.example")
    store._cache_demandeurs = None
    assert store._compter_a_classer(None) == 1
    # Le compteur par appareil ne change pas : en essai, rien n'est décidé.
    assert store.classe(store.pcs["pc-ja"], "sites", "adulte.example") == FILTRE_A_CLASSER


async def test_sans_categorie_ou_sans_demandeur_reste_compte(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental.store import FILTRE_A_CLASSER

    store = await _magasin(hass, hass_storage, monkeypatch)
    for pc in store.pcs.values():
        pc["bloques"] = [*pc.get("bloques", []), "sans-etiquette.example"]
    store._cache_demandeurs = None
    assert store.classe(None, "sites", "sans-etiquette.example") == FILTRE_A_CLASSER
    # Plus aucun appareil de Demandeur (tout le monde Décideur) : rien ne sort de la file.
    store.roles_personnes.update({"person.alex": "parent", "person.sam": "parent"})
    store._cache_demandeurs = None
    assert store.appareils_demandeurs() == []
    assert store.classe(None, "sites", "adulte.example") == FILTRE_A_CLASSER


async def test_un_demandeur_de_plus_ouvert_le_remet_dans_la_file(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental.store import FILTRE_A_CLASSER

    store = await _magasin(hass, hass_storage, monkeypatch)
    hass.states.async_set("person.pl", "home", {"friendly_name": "PL"})
    store.roles_personnes["person.pl"] = "enfant"
    store.pcs["pc-pl"] = {"id": "pc-pl", "person": "person.pl"}
    await store.async_save()  # toute écriture vide les caches
    assert store.classe(None, "sites", "adulte.example") == FILTRE_A_CLASSER


async def test_appareils_sans_personne_ignores_et_autorisation_comptee(hass, hass_storage, monkeypatch):
    """Le cas de la session de classement : la télé et le banc sans personne sont Demandeurs par le rôle par défaut,
    sans aucun nom bloqué ; ils ne retiennent pas l'élément. Une autorisation chez un Demandeur vaut décision."""
    from custom_components.pc_parental import roles
    from custom_components.pc_parental.store import (
        FILTRE_A_CLASSER,
        FILTRE_AUTORISES,
        FILTRE_BLOQUES,
    )

    store = await _magasin(hass, hass_storage, monkeypatch)
    for i in ("tele-sans-personne", "banc-sans-personne"):
        assert roles.a(store, store.pcs[i], "demander_acces"), "Demandeur par défaut, comme la télé du salon"
    assert sorted(p["id"] for p in store.appareils_demandeurs()) == sorted(["pc-sam", "pc-alex", "tel-alex"])
    assert store.classe(None, "sites", "adulte.example") == FILTRE_BLOQUES
    # Autorisé exprès sur les 3 appareils des Demandeurs (sans blocage) : décidé aussi, compté comme autorisé.
    for i in ("pc-alex", "tel-alex", "pc-sam"):
        store.pcs[i]["autorises"] = ["autre-adulte.example"]
    store.pcs["pc-sam"]["bloques"] = []
    store._cache_demandeurs = None
    assert store.classe(None, "sites", "autre-adulte.example") == FILTRE_AUTORISES
    # Un seul appareil de Demandeur sans décision le remet dans la file.
    store.pcs["tel-alex"]["autorises"] = []
    assert store.classe(None, "sites", "autre-adulte.example") == FILTRE_A_CLASSER
