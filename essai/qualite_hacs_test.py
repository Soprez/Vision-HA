"""2.5.12.14.2 : retours de qualité de la revue HACS (hacs/default#12284), points 6 à 9.

6. `sensor.vision_processus_a_valider` est une vraie entité (registre, appareil Vision), plus un état posé à la main.
7. Aucun accès au disque dans une coroutine : `messages.py` (média, chemin) et `veille_plus.py` (dossier de photos).
8. La clé EC du déverrouillage (génération, chargement, empreinte) se calcule dans l'executor.
9. `store.async_effectif` : même résultat que `effectif`, mais par tranches : la boucle d'événements n'est jamais bloquée
   plus de quelques dizaines de millisecondes ; l'appel d'un relevé (http.py) l'utilise.

    python -m pytest essai/qualite_hacs_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import ast
import asyncio
import pathlib
import threading
import time

from homeassistant.helpers import entity_registry as er

from perf_autorisations_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, _installer, _integrations, expected_lingering_tasks, expected_lingering_timers,
)

RACINE = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental"


# ------------------------------------------------------------------ 6. le capteur « à valider »

async def test_capteur_processus_vraie_entite(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    registre = er.async_get(hass)
    entree = registre.async_get("sensor.vision_processus_a_valider")
    assert entree is not None and entree.platform == DOMAIN and entree.unique_id == f"{DOMAIN}_processus_a_valider"
    assert entree.device_id  # rattachée à l'appareil « Vision »
    assert "states.async_set(" not in (RACINE / "processus.py").read_text(encoding="utf-8")
    # Il suit la gestion des processus : une proposition de plus, le capteur bouge.
    gestion = coord.processus
    assert gestion is not None
    avant = int(float(hass.states.get("sensor.vision_processus_a_valider").state))
    gestion.catalogue.elements["essai.exe"] = {
        "nom": "essai.exe", "genre": "processus", "pcs": {"pc0": {"vu": time.time()}},
        "proposition": {"verdict": "hors_demarrage", "confiance": 90, "raison": "essai"},
    }
    gestion._publier()
    await hass.async_block_till_done()
    etat = hass.states.get("sensor.vision_processus_a_valider")
    assert int(float(etat.state)) == gestion.catalogue.compter()["propositions"] >= avant
    assert {"a_classer", "decides", "desinstallations"} <= set(etat.attributes)


# ------------------------------------------------------------------ 7. pas de disque dans une coroutine

APPELS_DISQUE = {("os.path", "isfile"), ("os.path", "isdir"), ("os.path", "exists"), ("os.path", "realpath"),
                 ("os.path", "getsize"), ("os", "listdir"), ("os", "scandir"), ("os", "stat")}
FONCTIONS_DISQUE = {"chemin_media", "_dossier_photos", "decrire_media", "open"}


def _appels_dans_coroutines(chemin: pathlib.Path) -> list[str]:
    arbre = ast.parse(chemin.read_text(encoding="utf-8"))
    fautes: list[str] = []

    def nom(n: ast.AST) -> str:
        if isinstance(n, ast.Attribute):
            return f"{nom(n.value)}.{n.attr}"
        return n.id if isinstance(n, ast.Name) else ""

    def visiter(noeud: ast.AST, dans_executor: bool, ligne_fn: int) -> None:
        for enfant in ast.iter_child_nodes(noeud):
            if isinstance(enfant, (ast.FunctionDef, ast.Lambda)):
                continue  # une fonction ou un lambda interne : c'est ce qu'on envoie à l'executor
            if isinstance(enfant, ast.Call):
                f = nom(enfant.func)
                en_executor = f.endswith("async_add_executor_job")
                module, _, attr = f.rpartition(".")
                if (module, attr) in APPELS_DISQUE or f in FONCTIONS_DISQUE:
                    fautes.append(f"{chemin.name}:{enfant.lineno} {f}() dans la coroutine ligne {ligne_fn}")
                visiter(enfant, dans_executor or en_executor, ligne_fn)
                continue
            visiter(enfant, dans_executor, ligne_fn)

    for fn in ast.walk(arbre):
        if isinstance(fn, ast.AsyncFunctionDef):
            visiter(fn, False, fn.lineno)
    return fautes


def test_pas_de_disque_dans_les_coroutines():
    # Un appel passé EN ARGUMENT de async_add_executor_job (`hass.async_add_executor_job(os.path.isfile, x)`) n'est pas un
    # appel : c'est une référence, que ce balayage ne signale donc pas.
    fautes = []
    for f in ("messages.py", "veille_plus.py"):
        fautes += _appels_dans_coroutines(RACINE / f)
    assert not fautes, fautes


# ------------------------------------------------------------------ 8. la clé EC dans l'executor

async def test_cle_ec_dans_l_executor(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental import deverrouillage as dv

    boucle = threading.get_ident()
    fils: dict[str, int] = {}

    def espion(nom, f):
        def _f(*a, **k):
            fils[nom] = threading.get_ident()
            return f(*a, **k)
        return _f

    for nom in ("_charger_pem", "_generer_cle", "_publique_de", "_pem_de"):
        monkeypatch.setattr(dv, nom, espion(nom, getattr(dv, nom)))
    cle = dv.Cle(hass)
    await cle.async_load()  # pas de clé enregistrée : elle est générée, publiée, écrite
    assert cle.pub and cle.k and {"_generer_cle", "_publique_de", "_pem_de"} <= set(fils)
    assert all(t != boucle for t in fils.values()), fils
    # Une deuxième instance relit la clé enregistrée : chargement et empreinte hors boucle, même clé.
    fils.clear()
    autre = dv.Cle(hass)
    await autre.async_load()
    assert autre.k == cle.k and autre.pub == cle.pub
    assert {"_charger_pem", "_publique_de"} <= set(fils) and all(t != boucle for t in fils.values()), fils


# ------------------------------------------------------------------ 9. ce que l'agent doit appliquer, par tranches

async def test_effectif_par_tranches(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    store = coord.store
    pc = store.pcs["pc1"]
    store._memos = {}
    t = time.perf_counter()
    attendu = store.effectif(pc)
    duree_sync = time.perf_counter() - t
    store._memos = {}
    ecarts: list[float] = []
    arret = asyncio.Event()

    async def metronome():
        dernier = time.perf_counter()
        while not arret.is_set():
            await asyncio.sleep(0)
            maintenant = time.perf_counter()
            ecarts.append(maintenant - dernier)
            dernier = maintenant

    tache = asyncio.ensure_future(metronome())
    obtenu = await store.async_effectif(pc)
    arret.set()
    await tache
    assert obtenu == attendu, "même résultat que le calcul d'un seul tenant"
    assert len(ecarts) >= 5, len(ecarts)  # la boucle a repris la main plusieurs fois pendant le calcul
    assert max(ecarts) < max(duree_sync / 3, 0.03), (max(ecarts), duree_sync)
    # Gardé : le deuxième appel est immédiat, et le calcul d'un seul tenant sert le même résultat.
    t = time.perf_counter()
    assert await store.async_effectif(pc) == attendu
    assert time.perf_counter() - t < 0.05
    assert store.effectif(pc) == attendu


async def test_effectif_demandes_partagees(hass, hass_storage):
    coord = await _installer(hass, hass_storage)
    store = coord.store
    store._memos = {}
    appels = []
    vrai = store._effectif_pas
    store._effectif_pas = lambda pc: (appels.append(1), vrai(pc))[1]
    a, b = await asyncio.gather(store.async_effectif(store.pcs["pc2"]), store.async_effectif(store.pcs["pc2"]))
    assert a == b and len(appels) == 1, appels


def test_le_releve_utilise_le_calcul_par_tranches():
    src = (RACINE / "http.py").read_text(encoding="utf-8")
    assert "_effectif = await coord.store.async_effectif(pc)" in src and "**coord.store.effectif(pc)" not in src
    assert "await self.store.async_effectif(_pc)" in (RACINE / "coordinator.py").read_text(encoding="utf-8")
