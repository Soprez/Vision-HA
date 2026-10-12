"""Ce que soixante-dix antivirus pensent d'un programme.

Le seul signal extérieur qui dise vraiment quelque chose sur un logiciel.
La signature Authenticode dit qu'un éditeur a engagé son certificat ;
elle ne dit pas que le programme est honnête, et un logiciel malveillant
signé avec un certificat volé existe. VirusTotal, lui, répond sur le
fichier lui-même.

Trois choses à savoir, et qui sont dites à l'écran plutôt que tues :

* **on n'envoie jamais le fichier**, seulement son empreinte SHA-256 ;
* une empreinte apprend quand même à un tiers **quels programmes tournent
  ici**, et ce n'est pas rien ;
* le palier gratuit tient en quatre requêtes par minute. On fait donc la
  queue, et on regarde d'abord ce qui n'est pas signé — c'est là que la
  réponse a des chances d'apprendre quelque chose.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.storage import Store

from . import textes
from .const import (
    STORAGE_KEY_VT,
    STORAGE_VERSION,
    VT_FRAICHEUR_JOURS,
    VT_PAR_JOUR,
    VT_PAR_MINUTE,
    VT_URL,
)

_LOGGER = logging.getLogger(__name__)

# Un verdict introuvable se redemande plus tôt qu'un verdict rendu : un
# programme inconnu de VirusTotal aujourd'hui peut y être demain.
REPOS_INTROUVABLE = 3 * 86400


class VirusTotal:
    """Interroge VirusTotal sur des empreintes, à son rythme."""

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self._store: Store = Store(hass, STORAGE_VERSION, STORAGE_KEY_VT)
        self.active: bool = False
        # La clé est saisie dans Home Assistant par la personne, jamais
        # écrite ailleurs, jamais journalisée.
        self.cle: str = ""
        # empreinte -> {malveillants, suspects, total, vu, nom}
        self.verdicts: dict[str, dict[str, Any]] = {}
        self.erreur: str = ""
        self._minute: list[float] = []
        self._jour: list[float] = []
        self._en_cours: bool = False

    # ------------------------------------------------------------------ E/S

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        self.active = bool(data.get("active", False))
        self.cle = str(data.get("cle") or "")
        self.verdicts = dict(data.get("verdicts") or {})
        self._jour = [float(t) for t in (data.get("jour") or [])]

    async def async_save(self) -> None:
        await self._store.async_save(
            {
                "active": self.active,
                "cle": self.cle,
                "verdicts": self.verdicts,
                "jour": self._jour[-VT_PAR_JOUR:],
            }
        )

    # ------------------------------------------------------------- Cadence

    def _elaguer(self) -> None:
        maintenant = time.time()
        self._minute = [t for t in self._minute if maintenant - t < 60]
        self._jour = [t for t in self._jour if maintenant - t < 86400]

    def reste_aujourdhui(self) -> int:
        self._elaguer()
        return max(0, VT_PAR_JOUR - len(self._jour))

    def peut_demander(self) -> bool:
        if not (self.active and self.cle) or self._en_cours:
            return False
        self._elaguer()
        return len(self._minute) < VT_PAR_MINUTE and len(self._jour) < VT_PAR_JOUR

    # ------------------------------------------------------------ Verdict

    def verdict(self, empreinte: str) -> dict[str, Any] | None:
        return self.verdicts.get(str(empreinte or "").lower()) or None

    def a_revoir(self, empreinte: str) -> bool:
        """Faut-il (re)demander ce verdict ?"""
        fiche = self.verdict(empreinte)
        if fiche is None:
            return True
        age = time.time() - float(fiche.get("vu") or 0)
        if fiche.get("etat") == "introuvable":
            return age > REPOS_INTROUVABLE
        return age > VT_FRAICHEUR_JOURS * 86400

    def libelle(self, empreinte: str, langue: str | None = None) -> str:
        """« 3/72 antivirus », « aucun antivirus », ou rien du tout (dans `langue`, sinon celle de HA)."""
        fiche = self.verdict(empreinte)
        if not fiche:
            return ""
        langue = langue or textes.ha(self.hass)
        if fiche.get("etat") == "introuvable":
            return textes.t("vt.inconnu", langue)
        total = int(fiche.get("total") or 0)
        mauvais = int(fiche.get("malveillants") or 0)
        suspects = int(fiche.get("suspects") or 0)
        if not total:
            return ""
        if mauvais:
            return textes.t("vt.malveillants", langue, mauvais=mauvais, total=total)
        if suspects:
            return textes.t("vt.suspects", langue, suspects=suspects, total=total)
        return textes.t("vt.aucun", langue, total=total)

    # ------------------------------------------------------------ Requête

    async def async_demander(self, empreinte: str, nom: str = "") -> bool:
        """Interroge VirusTotal pour une empreinte. Rend True si ça a bougé.

        La clé ne passe que dans l'en-tête, et n'est jamais écrite dans le
        journal — pas même en cas d'erreur, où l'on serait tenté de tout
        recracher pour comprendre.
        """
        empreinte = str(empreinte or "").strip().lower()
        if not empreinte or not self.peut_demander():
            return False

        self._en_cours = True
        maintenant = time.time()
        self._minute.append(maintenant)
        self._jour.append(maintenant)
        session = async_get_clientsession(self.hass)
        try:
            async with session.get(
                VT_URL + empreinte,
                headers={"x-apikey": self.cle},
                timeout=30,
            ) as reponse:
                if reponse.status == 404:
                    self.verdicts[empreinte] = {
                        "etat": "introuvable",
                        "nom": nom,
                        "vu": maintenant,
                    }
                    self.erreur = ""
                    await self.async_save()
                    return True
                if reponse.status in (401, 403):
                    self.erreur = textes.t("vt.cle_refusee", textes.ha(self.hass))
                    return False
                if reponse.status == 429:
                    self.erreur = textes.t("vt.quota", textes.ha(self.hass))
                    return False
                reponse.raise_for_status()
                charge = await reponse.json()
        except Exception as err:  # noqa: BLE001
            # Volontairement sans l'URL ni l'en-tête : l'une porte
            # l'empreinte, l'autre porte la clé.
            _LOGGER.warning("VirusTotal injoignable : %s", type(err).__name__)
            self.erreur = textes.t("vt.injoignable", textes.ha(self.hass))
            return False
        finally:
            self._en_cours = False

        stats = (
            (charge.get("data") or {}).get("attributes") or {}
        ).get("last_analysis_stats") or {}
        total = sum(int(v or 0) for v in stats.values())
        self.verdicts[empreinte] = {
            "etat": "vu",
            "nom": nom,
            "malveillants": int(stats.get("malicious") or 0),
            "suspects": int(stats.get("suspicious") or 0),
            "total": total,
            "vu": maintenant,
        }
        self.erreur = ""
        await self.async_save()
        return True
