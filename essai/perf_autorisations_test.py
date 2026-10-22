"""2.5.12 (diagnostic du 17/10) : le tableau de bord Autorisations doit rester rapide avec un vrai catalogue.

Avant : les listes (sites, logiciels, ce qui est bloqué sur chaque appareil) voyageaient dans les attributs de
`select.vision_site_*`, `select.vision_logiciel_*`, `sensor.*_etat` et `switch.*_bloquer_*`, recalculées à chaque
mise à jour du coordinateur (toutes les 15 s et à chaque relevé d'un appareil), et en double (`lignes` et
`lignes_bloques` portaient la même liste). Sur le HA de l'auteur : 35 à 50 Ko par sélecteur, 32 Ko et 0,5 à 0,7 s par
capteur d'état.

Ici : un catalogue de la taille de celui de l'auteur. On vérifie que
- les attributs restent petits (aucune liste) ;
- une mise à jour de toutes les entités prend peu de temps, et le capteur d'état sert son cache ;
- les listes se lisent à la demande (`GET /api/pc_parental/listes`), réservées aux administrateurs ;
- l'empreinte (`revision`) ne change que si la liste change.

    python -m pytest essai/perf_autorisations_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import json
import time

from deverrouillage_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN,
    _fiche,
    _integrations,
    expected_lingering_tasks,
    expected_lingering_timers,
)
from homeassistant.helpers import entity_platform
from pytest_homeassistant_custom_component.common import MockConfigEntry

NB_PC = 8
NB_SITES = 1500
NB_APPS = 150
ETIQUETTES = ["Jeux", "Réseaux sociaux", "Vidéo", "Musique", "Achats", "Pub & traçage", "Travail", "Divers"]


def _donnees() -> dict:
    sites = [f"site{i:04d}.example" for i in range(NB_SITES)]
    apps = [f"Logiciel {i:03d}" for i in range(NB_APPS)]
    pcs = {}
    for n in range(NB_PC):
        pid = f"pc{n}"
        fiche = _fiche(pid, f"PC-{n}", f"{n}" * 32, person=f"person.p{n}")
        fiche["sites_vus"] = {s: {"premier": 1.0, "dernier": 2.0, "releves": i % 37 + 1, "adresses": []}
                              for i, s in enumerate(sites) if (i + n) % 3}
        fiche["apps_vues"] = {a: {"premier": 1.0, "dernier": 2.0, "secondes": 60 * (i + 1)}
                              for i, a in enumerate(apps) if (i + n) % 2}
        # Ce que cette machine a de particulier : une catégorie coupée et quelques noms.
        fiche["bloques"] = [ETIQUETTES[n % len(ETIQUETTES)], sites[n], sites[n + 100], apps[n]]
        pcs[pid] = fiche
    return {
        "enroll_key": "x" * 16, "enroll_key_v": 2, "roles_migres": True, "pcs": pcs,
        "etiquettes": {
            "sites": {s: [ETIQUETTES[i % len(ETIQUETTES)]] for i, s in enumerate(sites) if i % 2},
            "apps": {a: [ETIQUETTES[i % len(ETIQUETTES)]] for i, a in enumerate(apps) if i % 2},
        },
        "bannis": sites[200:500] + apps[20:40],
        "autorises_maison": sites[600:900] + apps[60:80],
    }


async def _installer(hass, hass_storage):
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": _donnees()}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return hass.data[DOMAIN][entree.entry_id]


def _entites(hass):
    return [e for p in entity_platform.async_get_platforms(hass, DOMAIN) for e in p.entities.values()]


def _cible(hass, prefixe: str):
    return [e for e in _entites(hass) if e.entity_id.startswith(prefixe)]


def _taille(entites) -> int:
    return sum(len(json.dumps(e.extra_state_attributes or {}, default=str)) for e in entites)


async def test_attributs_sans_listes(hass, hass_storage):
    await _installer(hass, hass_storage)
    selects = [e for e in _cible(hass, "select.") if "_site_" in e.entity_id or "_logiciel_" in e.entity_id]
    etats = [e for e in _cible(hass, "sensor.") if e.entity_id.endswith("_etat") and hasattr(e, "pc_id")]
    bascules = [e for e in _cible(hass, "switch.") if "bloquer" in (e.translation_key or "")]
    assert len(selects) == 6 and len(etats) == NB_PC and bascules
    for e in selects:
        attrs = e.extra_state_attributes
        for cle in ("lignes", "lignes_autorises", "lignes_bloques"):
            assert cle not in attrs, f"{e.entity_id} porte encore {cle}"
        assert attrs["total"] >= 0 and attrs["revision"]
    for e in etats:
        attrs = e.extra_state_attributes
        for cle in ("lignes_bloques", "bloques_sites", "bloques_logiciels", "exceptions"):
            assert cle not in attrs, f"{e.entity_id} porte encore {cle}"
        assert attrs["nb_sites_bloques"] > 0 and attrs["revision"]
    for e in bascules:
        attrs = e.extra_state_attributes
        assert "logiciels" not in attrs and "sites" not in attrs
    # Bornes larges : il ne reste que des nombres, des titres et des empreintes.
    assert _taille(selects) < 6 * 2500, _taille(selects)
    assert _taille(etats) < NB_PC * 1500, _taille(etats)
    assert _taille(bascules) < len(bascules) * 300, _taille(bascules)


async def test_grosses_listes_hors_historique(hass, hass_storage):
    """Étude des menus (L.3, étape 1) : les attributs des capteurs d'état, des interrupteurs de catégorie et des
    listes du Lieu ne vont plus dans l'historique de HA (seuls l'état, le nom et l'unité y restent)."""
    from homeassistant.const import MATCH_ALL

    await _installer(hass, hass_storage)
    cibles = [e for e in _cible(hass, "sensor.") if e.entity_id.endswith("_etat") and hasattr(e, "pc_id")]
    cibles += [e for e in _cible(hass, "switch.") if "bloquer" in (e.translation_key or "")]
    cibles += [e for e in _cible(hass, "sensor.") if type(e).__name__ in (
        "AClasserSensor", "ExceptionsSensor", "ListeNoireSensor", "AlertesSensor", "BloquesSensor",
        "RechercheNoireSensor")]
    assert len({type(e).__name__ for e in cibles}) >= 5
    for e in cibles:
        assert MATCH_ALL in e._unrecorded_attributes, e.entity_id


async def test_mise_a_jour_rapide(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    entites = _entites(hass)
    # Premier passage : les caches se remplissent.
    _taille(entites)
    debut = time.perf_counter()
    for _ in range(5):
        coord.async_update_listeners()
        _taille(entites)
    duree = (time.perf_counter() - debut) / 5
    # Avant (2.5.10), sur ce banc : plusieurs secondes par tour. Une borne large pour une machine lente.
    assert duree < 0.6, f"{duree:.3f} s par mise à jour de toutes les entités"


async def test_capteur_etat_en_cache(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    etat = next(e for e in _cible(hass, "sensor.") if e.entity_id.endswith("_etat") and getattr(e, "pc_id", "") == "pc1")
    premier = etat.extra_state_attributes
    appels = []
    vrai = coord.store.bloques
    coord.store.bloques = lambda pc: appels.append(pc["id"]) or vrai(pc)
    try:
        for _ in range(10):
            assert etat.extra_state_attributes == premier
        assert not appels, "le capteur d'état recalcule la liste à chaque lecture"
        # Une écriture (décision, étiquette…) invalide le cache, et l'empreinte change avec la liste.
        coord.store.bannir("site0003.example")
        await coord.store.async_save()
        apres = etat.extra_state_attributes
        assert apres["revision"] != premier["revision"]
        assert apres["nb_sites_bloques"] == premier["nb_sites_bloques"] + 1
    finally:
        coord.store.bloques = vrai


async def test_revision_stable_sans_changement(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    bloque = next(e for e in _cible(hass, "select.") if e.unique_id == f"{DOMAIN}_site_bloque")
    avant = bloque.extra_state_attributes["revision"]
    coord.async_update_listeners()
    await coord.store.async_save()  # une écriture qui ne change pas la liste
    assert bloque.extra_state_attributes["revision"] == avant
    coord.store.bannir("site0001.example")
    await coord.store.async_save()
    assert bloque.extra_state_attributes["revision"] != avant


async def test_listes_a_la_demande(hass, hass_storage, hass_client, hass_read_only_user):
    await _installer(hass, hass_storage)
    admin = await hass_client()
    r = await admin.get("/api/pc_parental/listes", params={"liste": "sites_bloques"})
    assert r.status == 200
    corps = await r.json()
    assert corps["ok"] and corps["entete"].startswith("|") and corps["revision"]
    assert len(corps["lignes"]) == corps["total"] > 0
    assert any("site0200.example" in ligne for ligne in corps["lignes"])
    # Une seule liste par demande : pas de doublons à transporter.
    assert "lignes_bloques" not in corps and "lignes_autorises" not in corps
    # Même empreinte que l'attribut de l'entité : la carte sait quand relire.
    bloque = next(e for e in _cible(hass, "select.") if e.unique_id == f"{DOMAIN}_site_bloque")
    assert bloque.extra_state_attributes["revision"] == corps["revision"]
    # Rien à renvoyer si la carte a déjà cette version.
    r = await admin.get("/api/pc_parental/listes", params={"liste": "sites_bloques", "revision": corps["revision"]})
    assert (await r.json()) == {"ok": True, "inchange": True, "revision": corps["revision"]}

    r = await admin.get("/api/pc_parental/listes", params={"liste": "bloques_appareil", "pc": "pc1"})
    assert r.status == 200
    appareil = await r.json()
    assert appareil["ok"] and any("site0001.example" in ligne for ligne in appareil["lignes"])

    for params in ({"liste": "inconnue"}, {"liste": "bloques_appareil", "pc": "absent"}):
        r = await admin.get("/api/pc_parental/listes", params=params)
        assert r.status == 404, params

    jeton = hass.auth.async_create_access_token(
        await hass.auth.async_create_refresh_token(hass_read_only_user, "https://exemple.invalid/"))
    lecteur = await hass_client(jeton)
    r = await lecteur.get("/api/pc_parental/listes", params={"liste": "sites_bloques"})
    assert r.status == 403
    assert "site0200" not in await r.text()


async def test_catalogue_et_maison_inchanges(hass, hass_storage, hass_client):
    """Les cartes Autorisations et Lieu relisent toutes les 30 s : rien ne repart si rien n'a changé."""
    coord = await _installer(hass, hass_storage)
    admin = await hass_client()
    for chemin in ("/api/pc_parental/catalogue", "/api/pc_parental/maison"):
        complet = await (await admin.get(chemin)).json()
        revision = complet["revision"]
        coord.async_update_listeners()
        await hass.async_block_till_done()
        r = await admin.get(chemin, params={"revision": revision})
        assert (await r.json()) == {"ok": True, "inchange": True, "revision": revision}, chemin
        # Une décision change la réponse : tout repart, avec une nouvelle empreinte.
        coord.store.bannir(f"site{len(chemin):04d}.example")
        await coord.store.async_save()
        apres = await (await admin.get(chemin, params={"revision": revision})).json()
        assert not apres.get("inchange") and apres["revision"] != revision, chemin


def test_textes_sans_lecture_disque(monkeypatch):
    """« Detected blocking call to open … langues.py, line 48 » (journal de HA, 18/10) : un catalogue créé dans la
    boucle d'événements (import de dashboard.py au démarrage) ne doit plus lire le disque."""
    import builtins
    import importlib

    from custom_components.pc_parental import dashboard, langues

    def interdit(*_a, **_k):
        raise AssertionError("lecture du disque pendant la création d'un catalogue")

    monkeypatch.setattr(builtins, "open", interdit)
    catalogue = langues.Catalogue("tableau")
    assert catalogue.cles("fr") and catalogue.cles("en")
    module = importlib.reload(dashboard)
    assert module._TEXTES.cles("de")


async def test_maison_sans_listes_de_blocages(hass, hass_storage, hass_client):
    """2.5.12.13 (mesure sur les vraies données : 1 s de calcul et 682 Ko, dont 300 Ko de listes de blocages) : l'état du
    tableau ne porte plus ce qui est fermé appareil par appareil ; il se lit à la demande pour l'appareil qu'on ouvre."""
    coord = await _installer(hass, hass_storage)
    admin = await hass_client()
    brut = await admin.get("/api/pc_parental/maison")
    texte = await brut.text()
    etat = json.loads(texte)
    assert len(etat["appareils"]) == NB_PC
    for a in etat["appareils"]:
        assert a["apps"] == [] and a["sites"] == [] and a["motifs"] == [] and a["raisons"] == {}
        assert a["n_sites"] > 100 and a["n_apps"] > 10  # le nombre reste affichable, la liste se lit à part
    assert len(texte.encode()) < 250_000, len(texte)
    # La même fiche, lue à l'ouverture de l'appareil : la liste complète, avec ses motifs.
    pc = coord.store.pcs["pc0"]
    complet = coord.store.effectif(pc)
    lue = await (await admin.post("/api/pc_parental/maison", json={"action": "bloques", "pc": "pc0"})).json()
    assert lue["ok"]
    assert {s["nom"] if isinstance(s, dict) else s for s in lue["sites"]} == set(complet["sites"])
    assert {a["nom"] if isinstance(a, dict) else a for a in lue["apps"]} == set(complet["apps"])
    # Un appareil inconnu : refus propre, pas d'exception.
    inconnu = await admin.post("/api/pc_parental/maison", json={"action": "bloques", "pc": "nulle-part"})
    assert inconnu.status == 404


async def test_maison_calculee_une_fois(hass, hass_storage, hass_client):
    """Plusieurs navigateurs ouverts : l'état du tableau n'est calculé qu'une fois (gardé, oublié à chaque écriture)."""
    coord = await _installer(hass, hass_storage)
    admin = await hass_client()
    from custom_components.pc_parental import parent

    appels = []
    vrai = parent.etat_maison
    parent.etat_maison = lambda *a, **k: (appels.append(1), vrai(*a, **k))[1]
    try:
        for _ in range(3):
            assert (await admin.get("/api/pc_parental/maison")).status == 200
        assert len(appels) == 1, appels
        coord.store.bannir("site-nouveau.example")
        await coord.store.async_save()
        assert (await admin.get("/api/pc_parental/maison")).status == 200
        assert len(appels) == 2, appels
    finally:
        parent.etat_maison = vrai


def test_carte_lit_les_blocages_a_l_ouverture():
    """2.5.12.13 : la fiche d'un appareil lit ce qui est fermé à l'ouverture (et à chaque relecture tant qu'elle reste
    ouverte) ; la carte ne lit plus les listes dans l'état du tableau."""
    import pathlib
    src = (pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental" / "frontend"
           / "vision-maison-card.js").read_text(encoding="utf-8")
    assert 'action: "bloques"' in src and src.count("_lireBloques(id)") >= 2
    assert "a.apps.map" not in src and "a.sites.map" not in src and "a.raisons" not in src
