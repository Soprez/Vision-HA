"""2.5.12.25 : retours de la revue HACS (hacs/default#12284, 09/11) : rien de bloquant sur la boucle d'événements.

1. `giphy.py` : la taille du média gardé se lit dans l'executor (GIF par mot-clé, image par URL).
2. L'écran Autorisations (GET /autorisations) : relevé construit par tranches, un seul calcul partagé, oublié à chaque écriture.
3. La vue d'ensemble : même principe (action « ensemble » du tableau).
4. Garde générale : aucun accès au disque dans AUCUNE coroutine du paquet, et aucune vue HTTP n'appelle un calcul d'un seul tenant.

    python -m pytest essai/qualite_hacs_2512_25_test.py -q -o asyncio_mode=auto
"""
from __future__ import annotations

import ast
import asyncio
import io
import threading

from PIL import Image

from perf_autorisations_test import (  # noqa: F401  (fixtures du banc)
    DOMAIN, NB_APPS, _installer, _integrations, expected_lingering_tasks, expected_lingering_timers,
)
from qualite_hacs_test import RACINE, _appels_dans_coroutines

AUT = "/api/pc_parental/autorisations"
MAISON = "/api/pc_parental/maison"


# ------------------------------------------------------------------ 4. la garde générale

def test_aucun_acces_disque_dans_aucune_coroutine():
    fautes: list[str] = []
    for f in sorted(RACINE.rglob("*.py")):
        fautes += _appels_dans_coroutines(f)
    assert not fautes, fautes


def test_aucun_appel_synchrone_a_un_calcul_lourd_dans_une_coroutine():
    """Les calculs qui ont un pendant par tranches ne s'appellent d'un seul tenant que depuis du code synchrone."""
    lourds = {("autorisations", "lire"), ("ensemble", "_jeu")}
    fautes: list[str] = []
    for f in sorted(RACINE.rglob("*.py")):
        arbre = ast.parse(f.read_text(encoding="utf-8"))
        for fn in ast.walk(arbre):
            if not isinstance(fn, ast.AsyncFunctionDef):
                continue
            pile = list(ast.iter_child_nodes(fn))
            while pile:
                n = pile.pop()
                if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
                    continue
                if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and isinstance(n.func.value, ast.Name):
                    if (n.func.value.id, n.func.attr) in lourds and f.name not in ("autorisations.py", "ensemble.py"):
                        fautes.append(f"{f.name}:{n.lineno} {n.func.value.id}.{n.func.attr}() dans {fn.name}()")
                pile.extend(ast.iter_child_nodes(n))
    assert not fautes, fautes


# ------------------------------------------------------------------ 1. giphy.py

def _jpeg(taille=(300, 200)) -> bytes:
    b = io.BytesIO()
    Image.new("RGB", taille, (200, 30, 30)).save(b, "JPEG")
    return b.getvalue()


async def test_la_taille_du_media_se_lit_dans_l_executor(hass, tmp_path, monkeypatch):
    from custom_components.pc_parental import giphy

    boucle = threading.get_ident()
    vues: list[int] = []
    vrai = giphy.os.path.getsize

    def taille(chemin):
        vues.append(threading.get_ident())
        return vrai(chemin)

    monkeypatch.setattr(giphy.os.path, "getsize", taille)

    async def fetch(url, params, maxi):
        return _jpeg()

    for _ in range(2):  # le deuxième tour sert le cache : la taille se lit aussi hors de la boucle
        m = await giphy.image_par_url(hass, "https://exemple.org/p.jpg", str(tmp_path / "ic"), fetch=fetch)
        assert m["octets"] > 0
    assert vues and all(t != boucle for t in vues), "os.path.getsize() appelé sur la boucle d'événements"


# ------------------------------------------------------------------ 2. l'écran Autorisations

async def _pendant(coro):
    """Attend `coro` en comptant combien de fois la boucle reprend la main pendant qu'elle travaille."""
    tours = 0
    fini = False

    async def pouls():
        nonlocal tours
        while not fini:
            tours += 1
            await asyncio.sleep(0)

    p = asyncio.ensure_future(pouls())
    try:
        return await coro, tours
    finally:
        fini = True
        await p


async def test_autorisations_par_tranches_meme_resultat(hass, hass_storage):
    from custom_components.pc_parental import autorisations

    coord = await _installer(hass, hass_storage)
    store = coord.store
    store._memos = {}
    attendu = autorisations.lire(hass, coord, {})
    store._memos = {}
    lu, tours = await _pendant(autorisations.async_lire(hass, coord, {}))
    assert tours >= NB_APPS // (autorisations.PAS_EVALUATIONS * 4), f"la boucle n'a repris la main que {tours} fois"
    assert lu["resume"] == attendu["resume"]
    assert lu["applis"] == attendu["applis"]


async def test_autorisations_un_seul_calcul_partage_et_garde(hass, hass_storage, monkeypatch):
    from custom_components.pc_parental import autorisations

    coord = await _installer(hass, hass_storage)
    store = coord.store
    store._memos = {}
    n = 0
    vrai = autorisations._etat_logiciel

    def compte(*a, **k):
        nonlocal n
        n += 1
        return vrai(*a, **k)

    monkeypatch.setattr(autorisations, "_etat_logiciel", compte)
    a, b, c = await asyncio.gather(*(autorisations.async_lire(hass, coord, {}) for _ in range(3)))
    assert a == b == c
    une_passe = n
    assert 0 < une_passe <= NB_APPS
    await autorisations.async_lire(hass, coord, {})
    assert n == une_passe, "le relevé gardé 10 s doit être servi tel quel"
    await store.async_save()  # une écriture oublie le relevé
    await autorisations.async_lire(hass, coord, {})
    assert n == 2 * une_passe


async def test_autorisations_une_ecriture_pendant_le_calcul_est_vue(hass, hass_storage):
    from custom_components.pc_parental import autorisations

    coord = await _installer(hass, hass_storage)
    store = coord.store
    store._memos = {}
    tache = asyncio.ensure_future(autorisations.async_lire(hass, coord, {}))
    await asyncio.sleep(0)
    nouveau = "essai-pendant-le-releve.example"
    if isinstance(store.bannis, list):
        store.bannis.append(nouveau)
    else:
        store.bannis.add(nouveau)
    await store.async_save()
    lu = await tache
    assert any(b.get("site") == nouveau for b in lu["bloques_pour_tous"])


async def test_la_vue_autorisations_n_appelle_pas_le_calcul_d_un_seul_tenant(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import autorisations

    await _installer(hass, hass_storage)

    def interdit(*a, **k):
        raise AssertionError("autorisations.lire() (d'un seul tenant) appelé par la vue")

    monkeypatch.setattr(autorisations, "lire", interdit)
    admin = await hass_client()
    r = await admin.get(AUT)
    assert r.status == 200, await r.text()
    d = await r.json()
    assert d["ok"] and d["applis"]


# ------------------------------------------------------------------ 3. la vue d'ensemble

async def test_vue_d_ensemble_par_tranches(hass, hass_storage):
    from custom_components.pc_parental import ensemble

    coord = await _installer(hass, hass_storage)
    coord.store._memos = {}
    attendu = ensemble._jeu(hass, coord)
    coord.store._memos = {}
    _, tours = await _pendant(ensemble.async_preparer(hass, coord))
    jeu = coord.store._memos[("ensemble",)][1]
    assert tours >= len(attendu["logiciels"]) // (ensemble.PAS_LIGNES * 2), tours
    assert jeu["logiciels"] == attendu["logiciels"] and jeu["limites"] == attendu["limites"]


async def test_la_vue_d_ensemble_du_tableau_ne_construit_pas_le_jeu_sur_la_boucle(hass, hass_storage, hass_client, monkeypatch):
    from custom_components.pc_parental import ensemble

    await _installer(hass, hass_storage)

    def interdit(*a, **k):
        raise AssertionError("ensemble._jeu() (d'un seul tenant) appelé par une coroutine")

    monkeypatch.setattr(ensemble, "_jeu", interdit)
    admin = await hass_client()
    r = await admin.post(MAISON, json={"action": "ensemble", "vue": "logiciels"})
    assert r.status == 200, await r.text()
    d = await r.json()
    assert d["ok"] and d["total"] > 0
