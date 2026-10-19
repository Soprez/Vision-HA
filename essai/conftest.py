"""Banc d'essai de Vision : toute la suite en un seul `pytest` (dossier essai/).

- Fichiers-scripts : certains essais sont des scripts (`python3 essai/x_test.py`, sans fonction `test_`), qui
  comptent leurs ratés et finissent parfois par `sys.exit`. Importés par pytest, ils tournaient à la collecte et
  l'arrêtaient net (SystemExit). Ici, chacun devient UN essai lancé dans son propre processus : il passe si le
  script finit à 0 et annonce « 0 raté(s) » ; sinon ses lignes « RATE » sont montrées.
- Routeur jamais figé : aiohttp fige le routeur d'une application qui démarre. Home Assistant l'en empêche
  (components/http/server.py : `self.app._router.freeze = lambda: None`), puisque les intégrations ajoutent
  leurs adresses après le démarrage. Le client d'essai (`hass_client`) démarre l'application sans passer par là :
  l'intégration montée ensuite tombait sur « Cannot register a resource into frozen router ». On fait comme HA.
- Dossier temporaire propre à chaque session : les essais créent leurs bancs (`tempfile.mkdtemp`) dès l'import ;
  ils vont dans un dossier neuf, retiré en fin de session, au lieu de s'accumuler dans le TMP du système et de se
  mêler à ceux d'une autre session qui tourne en même temps.
- Home Assistant en français : le banc officiel démarre Home Assistant en anglais (`hass.config.language = "en"`).
  Vision fabrique ses textes dans la langue de Home Assistant ou de l'appareil destinataire (pc_parental/textes.py) :
  les essais qui vérifient des textes français tournent donc avec Home Assistant réglé en français. Un essai qui veut
  une autre langue la règle lui-même (`hass.config.language = "de"`, ou le champ « langue » d'un relevé).
- Rechargements attendus : un relevé, une inscription ou une demande peut programmer un rechargement de Vision
  (`async_schedule_reload` : appareil créé, étiquettes nouvelles…). La réponse part avant ; la requête suivante
  tombait alors en plein rechargement (503 « loading », `hass.data` vide). Sous Windows, le rechargement finissait
  presque toujours avant ; sous Linux (CI), non. Après une requête du client d'essai qui a programmé un
  rechargement, et seulement dans ce cas, on attend qu'il soit fini (`hass.async_block_till_done()`), comme un
  appareil qui réessaie.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

ICI = Path(__file__).resolve().parent
_TEST = re.compile(r"^(?:async\s+)?def test_", re.M)
_DOSSIER_SESSION: str | None = None


def pytest_configure(config: pytest.Config) -> None:
    """Un TMP neuf pour la session, posé avant la collecte (les essais s'en servent dès l'import)."""
    from aiohttp import web_urldispatcher

    web_urldispatcher.UrlDispatcher.freeze = lambda self: None  # comme Home Assistant en vrai
    _attendre_les_rechargements()

    global _DOSSIER_SESSION
    if _DOSSIER_SESSION is None:
        _DOSSIER_SESSION = tempfile.mkdtemp(prefix="vision-session-")
        tempfile.tempdir = _DOSSIER_SESSION
        for nom in ("TMPDIR", "TMP", "TEMP"):
            os.environ[nom] = _DOSSIER_SESSION


_RECHARGEMENTS = [0]


def _attendre_les_rechargements() -> None:
    """Après une requête du client d'essai qui a programmé un rechargement, on attend ce rechargement (voir plus haut)."""
    from aiohttp import test_utils
    from homeassistant import config_entries
    from homeassistant.helpers.http import KEY_HASS

    if getattr(test_utils.TestClient._request, "_vision", False):
        return
    programmer = config_entries.ConfigEntries.async_schedule_reload

    def async_schedule_reload(self, entry_id):
        _RECHARGEMENTS[0] += 1
        return programmer(self, entry_id)

    config_entries.ConfigEntries.async_schedule_reload = async_schedule_reload
    requete = test_utils.TestClient._request

    async def _request(self, *args, **kwargs):
        avant = _RECHARGEMENTS[0]
        reponse = await requete(self, *args, **kwargs)
        hass = self.app.get(KEY_HASS) if self.app is not None else None
        if hass is not None and _RECHARGEMENTS[0] != avant:
            await hass.async_block_till_done()
        return reponse

    _request._vision = True
    test_utils.TestClient._request = _request


def pytest_unconfigure(config: pytest.Config) -> None:
    global _DOSSIER_SESSION
    if _DOSSIER_SESSION:
        tempfile.tempdir = None
        shutil.rmtree(_DOSSIER_SESSION, ignore_errors=True)
        _DOSSIER_SESSION = None


def _banc_de(module) -> str | None:
    """Le banc (copie de l'intégration) d'un fichier d'essai : le sien, ou celui du fichier dont il importe ses outils."""
    banc = getattr(module, "_banc", None)
    if banc:
        return str(banc)
    for valeur in vars(module).values():
        autre = sys.modules.get(getattr(valeur, "__module__", "") or "")
        if autre is not None and autre is not module and getattr(autre, "_banc", None):
            return str(autre._banc)
    return None


@pytest.fixture(autouse=True, scope="module")
def _integration_du_fichier(request):
    """Chaque fichier d'essai monte SA copie de l'intégration.

    Chacun copie pc_parental dans un banc à lui (installation_test y range ses APK d'essai, par exemple) et l'ajoute
    à custom_components.__path__. Dans un seul processus, le premier banc importé servait à tous les fichiers
    suivants : leurs fichiers à eux étaient ignorés. Avant chaque fichier, son banc passe en tête et l'intégration
    déjà importée est oubliée.
    """
    banc = _banc_de(request.module)
    if banc:
        import importlib

        import custom_components

        chemin = str(Path(banc) / "custom_components")
        autres = [p for p in custom_components.__path__ if p != chemin]
        custom_components.__path__[:] = [chemin, *autres]
        for nom in [n for n in sys.modules if n == "custom_components.pc_parental"
                    or n.startswith("custom_components.pc_parental.")]:
            del sys.modules[nom]
        importlib.invalidate_caches()
    yield


def _est_script(chemin: Path) -> bool:
    return chemin.suffix == ".py" and chemin.name.endswith("_test.py") and not _TEST.search(
        chemin.read_text(encoding="utf-8"))


def pytest_pycollect_makemodule(module_path: Path, parent):
    """Un fichier-script n'est pas importé : il devient un seul essai, lancé à part."""
    if _est_script(module_path):
        return FichierScript.from_parent(parent, path=module_path)
    return None


class FichierScript(pytest.File):
    def collect(self):
        yield EssaiScript.from_parent(self, name=self.path.stem)


class EchecScript(Exception):
    pass


class EssaiScript(pytest.Item):
    def runtest(self) -> None:
        env = {**os.environ, "PYTHONUTF8": "1", "PYTHONDONTWRITEBYTECODE": "1"}
        fini = subprocess.run([sys.executable, str(self.path)], cwd=self.path.parent, env=env,
                              capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
        sortie = fini.stdout + fini.stderr
        bilan = re.findall(r"^(\d+) raté\(s\)", sortie, re.M)
        if fini.returncode != 0 or (bilan and bilan[-1] != "0"):
            raise EchecScript(sortie)

    def repr_failure(self, excinfo):
        if isinstance(excinfo.value, EchecScript):
            lignes = str(excinfo.value).splitlines()
            ratees = [l for l in lignes if l.startswith("RATE")]
            return "\n".join(ratees or lignes[-40:])
        return super().repr_failure(excinfo)

    def reportinfo(self):
        return self.path, 0, f"script {self.path.name}"


# i18n : Home Assistant en français dans le banc (les essais vérifient des textes français).
@pytest.fixture
async def hass(hass):  # noqa: F811 - surcharge le banc officiel, qui fournit le `hass` d'origine
    hass.config.language = "fr"
    yield hass
