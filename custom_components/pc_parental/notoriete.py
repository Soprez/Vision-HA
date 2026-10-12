"""Notoriété d'un domaine : combien de monde le visite.

À ne pas confondre avec la liste noire, qui dit « celui-là est dangereux ».
Ici on ne dit rien de tel. On dit combien de monde visite ce domaine, et
c'est tout — parce que c'est tout ce qui se télécharge gratuitement.

**Fréquenté ne veut pas dire convenable.** Les sites pour adultes sont
parmi les plus visités au monde ; un classement de fréquentation les
placera toujours en tête. Cette colonne ne remplace donc aucune décision
de parent, et le tableau de bord le dit à côté.

Ce qu'elle apporte est l'inverse : un domaine que personne au monde ne
visite, apparu ce matin, contacté par un programme qu'on ne connaît pas.
Celui-là mérite un regard, et sans ce classement rien ne le distinguait
des deux cents autres lignes de la liste.
"""

from __future__ import annotations

import io
import logging
import time
import zipfile
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.storage import Store

from . import textes
from .const import (
    NOTORIETE_HEURES,
    NOTORIETE_PALIERS,
    NOTORIETE_PROFONDEUR,
    NOTORIETE_SOURCE,
    STORAGE_KEY_NOTORIETE,
    STORAGE_VERSION,
)

_LOGGER = logging.getLogger(__name__)

# Ce qu'on refuse d'avaler : au-delà, ce n'est pas le fichier attendu.
TAILLE_MAX = 64 * 1024 * 1024


def lire_classement(donnees: bytes, profondeur: int) -> dict[str, int]:
    """Lit le zip et rend domaine → rang, jusqu'à la profondeur voulue.

    Fonction pure, appelée dans un fil à part : dérouler un million de
    lignes dans la boucle d'événements de Home Assistant y bloquerait tout
    le reste de la maison pendant plusieurs secondes.
    """
    rangs: dict[str, int] = {}
    with zipfile.ZipFile(io.BytesIO(donnees)) as archive:
        membres = [n for n in archive.namelist() if n.lower().endswith(".csv")]
        if not membres:
            raise ValueError("Aucun CSV dans l'archive.")
        with archive.open(membres[0]) as flux:
            for ligne in io.TextIOWrapper(flux, encoding="utf-8", errors="replace"):
                rang, _, domaine = ligne.strip().partition(",")
                domaine = domaine.strip().lower()
                if not domaine or not rang.isdigit():
                    continue
                rangs[domaine] = int(rang)
                if len(rangs) >= profondeur:
                    break
    if not rangs:
        raise ValueError("Classement vide.")
    return rangs


class Notoriete:
    """Télécharge, garde et sert le classement de fréquentation."""

    # Après un échec, on ne réessaie pas avant ce délai : une source
    # injoignable ne doit pas relancer un téléchargement de dix méga-octets
    # à chaque tour du coordinateur.
    REPOS_ECHEC = 3600

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self._store: Store = Store(hass, STORAGE_VERSION, STORAGE_KEY_NOTORIETE)
        self.active: bool = True
        self.heures: int = NOTORIETE_HEURES
        self.rangs: dict[str, int] = {}
        self.maj: float = 0
        self.erreur: str = ""
        self._en_cours: bool = False
        self._dernier_essai: float = 0

    # ------------------------------------------------------------------ E/S

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        self.active = bool(data.get("active", True))
        self.rangs = {
            str(k): int(v) for k, v in (data.get("rangs") or {}).items()
        }
        self.maj = float(data.get("maj") or 0)
        self.erreur = ""

    async def async_save(self) -> None:
        await self._store.async_save(
            {"active": self.active, "rangs": self.rangs, "maj": self.maj}
        )

    # -------------------------------------------------------- Rafraîchir

    def perimee(self) -> bool:
        if self._en_cours:
            return False
        if (time.time() - self._dernier_essai) < self.REPOS_ECHEC:
            return False
        return (time.time() - self.maj) > (self.heures * 3600)

    async def async_rafraichir(self, force: bool = False) -> bool:
        """Recharge le classement. Rend True s'il a changé.

        Comme pour la liste noire : une source injoignable ne vide pas ce
        qu'on avait. Un classement qui disparaît parce qu'un serveur a
        éternué ferait passer tous les sites connus pour inconnus, et la
        colonne dirait le contraire de la vérité.
        """
        if not force and not self.perimee():
            return False
        if self._en_cours:
            return False
        self._en_cours = True
        self._dernier_essai = time.time()
        try:
            return await self._async_rafraichir()
        finally:
            self._en_cours = False

    async def _async_rafraichir(self) -> bool:
        session = async_get_clientsession(self.hass)
        try:
            async with session.get(NOTORIETE_SOURCE["url"], timeout=180) as reponse:
                reponse.raise_for_status()
                # « read(n) » rend ce qui est déjà arrivé, pas n octets : une archive de plusieurs
                # mégaoctets revenait tronquée, donc illisible. On lit jusqu'au bout, morceau par
                # morceau, en gardant le plafond : au-delà, ce n'est pas le fichier attendu.
                morceaux: list[bytes] = []
                recu = 0
                async for morceau in reponse.content.iter_chunked(65536):
                    recu += len(morceau)
                    if recu > TAILLE_MAX:
                        raise ValueError("archive plus grosse que prévu")
                    morceaux.append(morceau)
                donnees = b"".join(morceaux)
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("Notoriété, source injoignable : %s", err)
            self.erreur = textes.t("noto.injoignable", textes.ha(self.hass), source=NOTORIETE_SOURCE['nom'])
            return False

        try:
            rangs = await self.hass.async_add_executor_job(
                lire_classement, donnees, NOTORIETE_PROFONDEUR
            )
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("Notoriété, archive illisible : %s", err)
            self.erreur = textes.t("noto.illisible", textes.ha(self.hass), source=NOTORIETE_SOURCE['nom'])
            return False

        change = rangs != self.rangs
        self.rangs = rangs
        self.maj = time.time()
        self.erreur = ""
        await self.async_save()
        return change

    # ------------------------------------------------------------ Service

    def rang(self, domaine: str) -> int:
        """Le rang de ce domaine, ou 0 s'il n'est pas au classement."""
        if not self.active or not domaine:
            return 0
        return int(self.rangs.get(str(domaine).strip().lower(), 0))

    def libelle(self, domaine: str, langue: str | None = None) -> str:
        """« très fréquenté (rang 412) », ou « inconnu » (dans `langue`, sinon celle de Home Assistant).

        Le rang est écrit en toutes lettres plutôt que résumé en pastille :
        entre le 900ᵉ et le 90 000ᵉ site du monde, il y a une différence
        qu'un mot seul n'exprime pas.
        """
        langue = langue or textes.ha(self.hass)
        place = self.rang(domaine)
        if not place:
            return textes.t("noto.inconnu", langue)
        for i, (plafond, _mot) in enumerate(NOTORIETE_PALIERS):
            if place <= plafond:
                return textes.t("noto.rang", langue, mot=textes.t(f"noto.palier_{i}", langue), rang=place)
        return textes.t("noto.inconnu", langue)
