"""Résultats eSport pour l'écran de veille : Valorant (VCT) et Rocket League (RLCS).

Les sources sont publiques et sans clé : la page de résultats de vlr.gg pour
Valorant (les logos viennent de la page de chaque match, lue une seule fois),
le fil des matchs de Liquipedia pour Rocket League (API MediaWiki, une
requête toutes les dix minutes, bien en deçà de leurs conditions). Home Assistant rafraîchit au
plus toutes les dix minutes, et seulement quand un écran de veille le demande ;
la télé ne parle qu'à Home Assistant, qui lui relaie aussi les logos.
"""

from __future__ import annotations

import asyncio
import hashlib
import html
import logging
import os
import re
import time
from typing import Any

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from . import textes
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

AGENT = "Vision-HomeAssistant/1.0 (ecran de veille ; https://github.com/Soprez/Vision-HA)"
RAFRAICHISSEMENT = 600
PAR_JEU = 8
LOGOS_HOTES = ("owcdn.net", "liquipedia.net", "upload.wikimedia.org")
VLR_RESULTATS = "https://www.vlr.gg/matches/results"
VLR_AVENIR = "https://www.vlr.gg/matches"
VLR_FILTRE = ("champions tour", "valorant champions", "masters")
LIQUIPEDIA_RL = "https://liquipedia.net/rocketleague/api.php?action=parse&page=Liquipedia:Matches&format=json&prop=text&disablelimitreport=1"
RL_FILTRE = ("rlcs", "championship series", "esports world cup", "major", "world championship")
JOLPICA = "https://api.jolpi.ca/ergast/f1"
WIKI_API = "https://en.wikipedia.org/w/api.php"
MOIS_EN = {m: i + 1 for i, m in enumerate(["january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"])}

_BLOC = re.compile(r'<a href="/(\d+)/[^"]*" class="wf-module-item match-item[^"]*">(.*?)</a>', re.S)
_EQUIPE = re.compile(
    r'<div class="match-item-vs-team\s*(mod-winner)?\s*">.*?<div class="text-of">\s*(?:<span class="flag mod-(\w+)"></span>)?\s*(.*?)\s*</div>.*?'
    r'<div class="match-item-vs-team-score[^"]*">\s*(\S*)\s*</div>',
    re.S,
)
_SERIE = re.compile(r'<div class="match-item-event-series text-of">\s*(.*?)\s*</div>', re.S)
_EVENEMENT = re.compile(r'<div class="match-item-event text-of">.*?</div>\s*(.*?)\s*</div>', re.S)
_ETA = re.compile(r'<div class="ml-eta[^"]*">\s*(.*?)\s*</div>', re.S)
_STATUT = re.compile(r'<div class="ml-status">\s*(.*?)\s*</div>', re.S)
_LOGO = re.compile(r'<img src="(//owcdn\.net/img/[^"]+)" alt="([^"]*) team logo">')
_UTC = re.compile(r'data-utc-ts="(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)"')
_LP_TS = re.compile(r'data-timestamp="(\d+)"')
_LP_CAMP = re.compile(r'<div class="match-info-header-opponent([^"]*)">')
_LP_EQUIPE = re.compile(
    r'<div class="block-team[^"]*">\s*<span class="team-template-image-icon">\s*<a href="[^"]*" title="([^"]*)">(?:<img alt="[^"]*" src="([^"]+)")?.*?'
    r'<span class="name"[^>]*>(?:<a [^>]*>)?([^<]+)',
    re.S,
)
_LP_SCORE = re.compile(r'match-info-header-scoreholder-score(?: match-info-header-winner)?">\s*(\d*)\s*<')
_LP_TOURNOI = re.compile(r'match-info-tournament-name"><a [^>]*title="([^"]*)"><span>([^<]*)')
_W_LIGNE = re.compile(r"<tr[^>]*>(.*?)</tr>", re.S)
_W_CELLULE = re.compile(r"<t([hd])[^>]*>(.*?)</t[hd]>", re.S)
_W_LIEN = re.compile(r'<a href="/wiki/([^"#]+)"[^>]*title="([^"]*)"[^>]*>([^<]+)</a>')
_W_BALISE = re.compile(r"<[^>]+>")


def _propre(s: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(s)).strip()


def _eta_en_secondes(texte: str) -> int:
    """« 10h 16m », « 3d 2h », « 45m » → secondes écoulées depuis la fin."""
    total = 0
    for nombre, unite in re.findall(r"(\d+)\s*([wdhm])", texte):
        total += int(nombre) * {"w": 604800, "d": 86400, "h": 3600, "m": 60}[unite]
    return total


class Esports:
    """Le cache des résultats, un par installation."""

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self.session = async_get_clientsession(hass)
        self.store = Store[dict[str, Any]](hass, 1, f"{DOMAIN}_esports")
        self.matchs_vlr: dict[str, dict[str, Any]] = {}  # id → {l1, l2, ts}
        self.resultats: list[dict[str, Any]] = []
        self.courses: list[dict[str, Any]] = []
        self.logos_wiki: dict[str, str] = {}  # titre de page Wikipédia → image
        self.f1_manches: dict[str, dict[str, Any]] = {}  # "2026-15" → épreuve (le passé ne bouge plus)
        self.avenir: list[dict[str, Any]] = []
        self.prochaines: dict[str, dict[str, Any]] = {}  # wrc / fe → prochaine manche (du calendrier Wikipédia)
        self.quand = 0.0
        self.en_cours: asyncio.Task | None = None
        self.charge = False
        self.dossier = hass.config.path(".storage", f"{DOMAIN}_logos")

    async def _charger(self) -> None:
        if self.charge:
            return
        self.charge = True
        data = await self.store.async_load() or {}
        self.matchs_vlr = dict(data.get("vlr") or {})
        self.resultats = list(data.get("resultats") or [])
        self.courses = list(data.get("courses") or [])
        self.avenir = list(data.get("avenir") or [])
        self.logos_wiki = dict(data.get("logos_wiki") or {})
        self.f1_manches = dict(data.get("f1_manches") or {})

    async def _sauver(self) -> None:
        # On ne garde que les matchs récents : la page de résultats ne remonte pas loin.
        if len(self.matchs_vlr) > 400:
            garde = sorted(self.matchs_vlr.items(), key=lambda kv: kv[1].get("ts", 0), reverse=True)[:300]
            self.matchs_vlr = dict(garde)
        if len(self.f1_manches) > 60:
            self.f1_manches = dict(sorted(self.f1_manches.items())[-40:])
        await self.store.async_save({
            "vlr": self.matchs_vlr, "resultats": self.resultats, "courses": self.courses,
            "logos_wiki": self.logos_wiki, "f1_manches": self.f1_manches, "avenir": self.avenir,
        })

    def demander(self) -> list[dict[str, Any]]:
        """Les résultats connus, tout de suite ; un rafraîchissement part en fond s'il est temps."""
        if time.time() - self.quand > RAFRAICHISSEMENT and (self.en_cours is None or self.en_cours.done()):
            self.en_cours = self.hass.async_create_background_task(self._rafraichir(), f"{DOMAIN}_esports")
        return self.resultats

    def courses_connues(self) -> list[dict[str, Any]]:
        self.demander()
        return self.courses

    def avenir_connu(self) -> list[dict[str, Any]]:
        """Ce qui vient : seulement ce qui n'est pas encore passé de plus de deux heures."""
        self.demander()
        limite = time.time() - 7200
        return [a for a in self.avenir if a.get("ts", 0) > limite]

    async def _rafraichir(self) -> None:
        await self._charger()
        self.quand = time.time()
        sorties: list[dict[str, Any]] = []
        for nom, fonction in (("Valorant", self._valorant), ("Rocket League", self._rocket_league)):
            try:
                resultats = await fonction()
            except Exception as err:  # noqa: BLE001 - une source en panne ne vide pas l'écran
                _LOGGER.debug("eSport %s : %s", nom, err)
                resultats = next((j["resultats"] for j in self.resultats if j["jeu"] == nom), [])
            if resultats:
                sorties.append({"jeu": nom, "resultats": resultats[:PAR_JEU]})
        self.resultats = sorties
        courses: list[dict[str, Any]] = []
        for nom, fonction in (("Formule 1", self._f1), ("WRC", self._wrc), ("Formule E", self._formule_e)):
            try:
                epreuves = await fonction()
            except Exception as err:  # noqa: BLE001
                _LOGGER.debug("courses %s : %s", nom, err)
                epreuves = next((c["epreuves"] for c in self.courses if c["sport"] == nom), [])
            if epreuves:
                courses.append({"sport": nom, "epreuves": epreuves[:4]})
        self.courses = courses
        try:
            self.avenir = await self._avenir()
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("avenir : %s", err)
        await self._sauver()

    async def _texte(self, url: str, delai: int = 20) -> str:
        async with asyncio.timeout(delai):
            async with self.session.get(url, headers={"User-Agent": AGENT}) as rep:
                rep.raise_for_status()
                return await rep.text()

    async def _json(self, url: str, delai: int = 20) -> Any:
        async with asyncio.timeout(delai):
            async with self.session.get(url, headers={"User-Agent": AGENT}) as rep:
                rep.raise_for_status()
                return await rep.json(content_type=None)

    # --- Valorant --------------------------------------------------------------

    async def _valorant(self) -> list[dict[str, Any]]:
        page = await self._texte(VLR_RESULTATS)
        maintenant = time.time()
        sortie: list[dict[str, Any]] = []
        nouveaux = 0
        for ident, bloc in _BLOC.findall(page):
            if len(sortie) >= PAR_JEU:
                break
            statut = _STATUT.search(bloc)
            if not statut or _propre(statut.group(1)).lower() != "completed":
                continue
            evenement = _EVENEMENT.search(bloc)
            nom_evenement = _propre(evenement.group(1)) if evenement else ""
            if not any(mot in nom_evenement.lower() for mot in VLR_FILTRE):
                continue
            equipes = _EQUIPE.findall(bloc)
            if len(equipes) != 2:
                continue
            serie = _SERIE.search(bloc)
            eta = _ETA.search(bloc)
            ts = int(maintenant - _eta_en_secondes(eta.group(1))) if eta else int(maintenant)
            detail = self.matchs_vlr.get(ident)
            if detail is None and nouveaux < 8:
                nouveaux += 1
                detail = await self._detail_vlr(ident, [_propre(e[2]) for e in equipes])
                self.matchs_vlr[ident] = detail
                await asyncio.sleep(1.5)  # un visiteur poli
            detail = detail or {}

            def score(s: str) -> int | None:
                return int(s) if s.isdigit() else None

            sortie.append(
                {
                    "id": f"vlr{ident}",
                    "e1": _propre(equipes[0][2]),
                    "e2": _propre(equipes[1][2]),
                    "p1": equipes[0][1],
                    "p2": equipes[1][1],
                    "s1": score(equipes[0][3]),
                    "s2": score(equipes[1][3]),
                    "v": 1 if equipes[0][0] else 2 if equipes[1][0] else 0,
                    "l1": detail.get("l1", ""),
                    "l2": detail.get("l2", ""),
                    "evenement": nom_evenement,
                    "serie": _propre(serie.group(1)) if serie else "",
                    "ts": int(detail.get("ts") or ts),
                }
            )
        return sortie

    async def _detail_vlr(self, ident: str, noms: list[str]) -> dict[str, Any]:
        """Les logos et l'heure exacte, sur la page du match."""
        try:
            page = await self._texte(f"https://www.vlr.gg/{ident}/")
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("vlr %s : %s", ident, err)
            return {"l1": "", "l2": "", "ts": 0}
        logos: dict[str, str] = {}
        for url, nom in _LOGO.findall(page):
            logos.setdefault(_propre(nom), "https:" + url)
        utc = _UTC.search(page)
        ts = 0
        if utc:
            try:
                ts = int(dt_util.as_timestamp(dt_util.parse_datetime(utc.group(1).replace(" ", "T") + "+00:00")))
            except Exception:  # noqa: BLE001
                ts = 0
        trouves = list(logos.values())
        return {
            "l1": logos.get(noms[0], trouves[0] if len(trouves) > 0 else ""),
            "l2": logos.get(noms[1], trouves[1] if len(trouves) > 1 else ""),
            "ts": ts,
        }

    # --- Rocket League -----------------------------------------------------------

    async def _rocket_league(self) -> list[dict[str, Any]]:
        data = await self._json(LIQUIPEDIA_RL)
        page = ((data.get("parse") or {}).get("text") or {}).get("*", "")
        debut = page.find('data-toggle-area-content="2"')
        if debut < 0:
            return []
        sortie: list[dict[str, Any]] = []
        for bloc in page[debut:].split('<div class="match-info">')[1:]:
            tournoi = _LP_TOURNOI.search(bloc)
            nom_tournoi = _propre(tournoi.group(2)) if tournoi else ""
            page_tournoi = _propre(tournoi.group(1)) if tournoi else ""
            if not any(mot in (nom_tournoi + " " + page_tournoi).lower() for mot in RL_FILTRE):
                continue
            equipes = _LP_EQUIPE.findall(bloc)
            camps = _LP_CAMP.findall(bloc)
            scores = _LP_SCORE.findall(bloc)
            if len(equipes) != 2 or len(camps) < 2 or len(scores) < 2:
                continue
            ts = _LP_TS.search(bloc)

            def logo(src: str) -> str:
                if not src:
                    return ""
                src = re.sub(r"/(\d+)px-", "/120px-", src, count=1)
                return src if src.startswith("http") else "https://liquipedia.net" + src

            def score(x: str) -> int | None:
                return int(x) if x.isdigit() else None

            v = 1 if "match-info-header-winner" in camps[0] else 2 if "match-info-header-winner" in camps[1] else 0
            sortie.append(
                {
                    "id": f"rl{ts.group(1) if ts else 0}-{_propre(equipes[0][0])[:12]}-{_propre(equipes[1][0])[:12]}",
                    "e1": _propre(equipes[0][0]) or _propre(equipes[0][2]),
                    "e2": _propre(equipes[1][0]) or _propre(equipes[1][2]),
                    "p1": "",
                    "p2": "",
                    "s1": score(scores[0]),
                    "s2": score(scores[1]),
                    "v": v,
                    "l1": logo(equipes[0][1]),
                    "l2": logo(equipes[1][1]),
                    "evenement": nom_tournoi,
                    "serie": "",
                    "ts": int(ts.group(1)) if ts else int(time.time()),
                }
            )
            if len(sortie) >= PAR_JEU:
                break
        return sortie

    # --- Sport auto ----------------------------------------------------------

    async def _logo_wiki(self, titre: str) -> str:
        """L'image de la page Wikipédia d'une écurie (son logo, le plus souvent), gardée pour de bon."""
        titre = titre.replace("_", " ").strip()
        if not titre:
            return ""
        if titre in self.logos_wiki:
            return self.logos_wiki[titre]
        url = ""
        try:
            data = await self._json(
                f"{WIKI_API}?action=query&prop=pageimages&titles={titre.replace(' ', '_')}&pithumbsize=240&redirects=1&format=json&formatversion=2"
            )
            pages = (data.get("query") or {}).get("pages") or []
            url = ((pages[0].get("thumbnail") or {}).get("source") or "") if pages else ""
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("wiki %s : %s", titre, err)
        self.logos_wiki[titre] = url
        return url

    async def _f1(self) -> list[dict[str, Any]]:
        """Les trois derniers Grands Prix, podium complet (Jolpica, l'héritier d'Ergast)."""
        dernier = await self._json(f"{JOLPICA}/current/last/results.json")
        courses = (((dernier.get("MRData") or {}).get("RaceTable") or {}).get("Races") or [])
        if not courses:
            return []
        saison = courses[0].get("season")
        manche = int(courses[0].get("round") or 0)
        sortie: list[dict[str, Any]] = []
        for r in range(manche, max(0, manche - 3), -1):
            cle = f"{saison}-{r}"
            epreuve = self.f1_manches.get(cle)
            if epreuve is None:
                if r != manche:
                    await asyncio.sleep(0.4)
                    data = await self._json(f"{JOLPICA}/{saison}/{r}/results.json")
                    liste = (((data.get("MRData") or {}).get("RaceTable") or {}).get("Races") or [])
                    if not liste:
                        continue
                    course = liste[0]
                else:
                    course = courses[0]
                podium = []
                for res in (course.get("Results") or [])[:3]:
                    pilote = res.get("Driver") or {}
                    ecurie = res.get("Constructor") or {}
                    titre = (ecurie.get("url") or "").rsplit("/", 1)[-1]
                    podium.append({
                        "pos": int(res.get("position") or len(podium) + 1),
                        "pilote": f"{(pilote.get('givenName') or '')[:1]}. {pilote.get('familyName') or ''}".strip(". "),
                        "equipe": ecurie.get("name") or "",
                        "logo": await self._logo_wiki(titre),
                    })
                try:
                    ts = int(dt_util.as_timestamp(dt_util.parse_datetime(f"{course.get('date')}T{course.get('time') or '12:00:00Z'}")))
                except Exception:  # noqa: BLE001
                    ts = 0
                epreuve = {
                    "id": f"f1-{cle}",
                    "nom": str(course.get("raceName") or "").replace("Grand Prix", "GP"),
                    "lieu": ((course.get("Circuit") or {}).get("Location") or {}).get("country", ""),
                    "manche": r,
                    "ts": ts,
                    "podium": podium,
                }
                if podium:
                    self.f1_manches[cle] = epreuve
            if epreuve and epreuve.get("podium"):
                sortie.append(epreuve)
        return sortie

    def _texte_cellule(self, cellule: str) -> tuple[str, str]:
        """(texte, titre de page) d'une cellule : le dernier lien avec du texte, sinon le texte nu."""
        liens = [(t, html.unescape(titre)) for _, titre, t in _W_LIEN.findall(cellule) if _propre(t)]
        propre = _propre(_W_BALISE.sub(" ", re.sub(r"<sup.*?</sup>", "", cellule, flags=re.S)))
        if liens:
            return _propre(liens[-1][0]), liens[-1][1]
        return propre, ""

    def _tables(self, page: str) -> list[list[list[tuple[str, str]]]]:
        """Toutes les tables de la page, en lignes de cellules (type, html)."""
        page = re.sub(r"<(style|script)[^>]*>.*?</\1>", "", page, flags=re.S)
        tables = []
        for table in re.findall(r"<table[^>]*>(.*?)</table>", page, flags=re.S):
            lignes = []
            for ligne in _W_LIGNE.findall(table):
                cellules = _W_CELLULE.findall(ligne)
                if cellules:
                    lignes.append(cellules)
            if lignes:
                tables.append(lignes)
        return tables

    def _colonnes(self, entete: list[tuple[str, str]]) -> dict[str, int]:
        noms = {}
        for i, (_, c) in enumerate(entete):
            noms[self._texte_cellule(c)[0].lower()] = i
        return noms

    def _date_wiki(self, texte: str, annee: int, bascule: bool) -> int:
        """« 25 January » (ou « 22–25 January ») → horodatage, l'année donnée ; saison à cheval : nov–déc l'année d'avant."""
        m = re.search(r"(\d{1,2})\s*(?:[–-]\s*\d{1,2}\s*)?([A-Za-z]+)", texte)
        if not m:
            return 0
        mois = MOIS_EN.get(m.group(2).lower())
        if not mois:
            return 0
        explicite = re.search(r"\b(20\d\d)\b", texte)
        an = int(explicite.group(1)) if explicite else (annee - 1 if bascule and mois >= 10 else annee)
        try:
            return int(dt_util.as_timestamp(dt_util.parse_datetime(f"{an}-{mois:02d}-{int(m.group(1)):02d}T14:00:00+00:00")))
        except Exception:  # noqa: BLE001
            return 0

    async def _saison_wiki(self, page_titre: str, prefixe: str, annee: int, bascule: bool) -> list[dict[str, Any]]:
        """Les épreuves gagnées d'une saison Wikipédia : vainqueur, écurie, date (table des résultats + calendrier)."""
        data = await self._json(f"{WIKI_API}?action=parse&page={page_titre}&prop=text&format=json&formatversion=2&disabletoc=1")
        page = ((data.get("parse") or {}).get("text") or "")
        tables = self._tables(page)
        dates: dict[str, int] = {}
        noms_calendrier: dict[str, str] = {}
        resultats: list[dict[str, Any]] = []
        for lignes in tables:
            cols = self._colonnes(lignes[0])
            if "round" not in cols:
                continue
            if "winning driver" in cols and not resultats:
                for cellules in lignes[1:]:
                    if len(cellules) <= max(cols.values()):
                        continue
                    manche = self._texte_cellule(cellules[cols["round"]][1])[0]
                    ic = next((cols[k] for k in ("event", "rally", "e-prix", "grand prix", "race") if k in cols), None)
                    ie = next((cols[k] for k in ("winning entrant", "winning team", "winning constructor") if k in cols), None)
                    if ic is None:
                        continue
                    pilote, _ = self._texte_cellule(cellules[cols["winning driver"]][1])
                    if not pilote or not manche.isdigit():
                        continue
                    nom, _ = self._texte_cellule(cellules[ic][1])
                    equipe, titre_equipe = self._texte_cellule(cellules[ie][1]) if ie is not None else ("", "")
                    resultats.append({"manche": int(manche), "nom": nom, "pilote": pilote, "equipe": equipe, "titre_equipe": titre_equipe})
            elif any(k in cols for k in ("finish date", "date", "start date")):
                idate = next(cols[k] for k in ("finish date", "date", "start date") if k in cols)
                for cellules in lignes[1:]:
                    if len(cellules) <= max(cols["round"], idate):
                        continue
                    manche = self._texte_cellule(cellules[cols["round"]][1])[0]
                    if manche.isdigit():
                        dates[manche] = self._date_wiki(self._texte_cellule(cellules[idate][1])[0], annee, bascule)
                        inom = next((cols[k] for k in ("rally", "event", "e-prix", "grand prix", "race", "circuit") if k in cols), None)
                        if inom is not None and len(cellules) > inom:
                            noms_calendrier[manche] = self._texte_cellule(cellules[inom][1])[0]
        # La prochaine manche : la première du calendrier après la dernière gagnée, pas encore passée.
        derniere = resultats[-1]["manche"] if resultats else 0
        maintenant = time.time()
        for manche_txt, ts_cal in sorted(dates.items(), key=lambda kv: int(kv[0])):
            if int(manche_txt) > derniere and ts_cal > maintenant - 36 * 3600:
                self.prochaines[prefixe] = {"manche": int(manche_txt), "ts": ts_cal, "nom": noms_calendrier.get(manche_txt, "")}
                break
        sortie = []
        for r in reversed(resultats[-4:]):
            sortie.append({
                "id": f"{prefixe}-{annee}-{r['manche']}",
                "nom": r["nom"],
                "lieu": "",
                "manche": r["manche"],
                "ts": dates.get(str(r["manche"]), 0),
                "podium": [{"pos": 1, "pilote": r["pilote"], "equipe": r["equipe"], "logo": await self._logo_wiki(r["titre_equipe"] or r["equipe"])}],
            })
        return sortie

    async def _wrc(self) -> list[dict[str, Any]]:
        annee = dt_util.now().year
        for an in (annee, annee - 1):
            epreuves = await self._saison_wiki(f"{an}_World_Rally_Championship", "wrc", an, False)
            if epreuves:
                return epreuves
        return []

    async def _formule_e(self) -> list[dict[str, Any]]:
        annee = dt_util.now().year
        for an in (annee + 1, annee):
            # Saison à cheval : « 2026–27 » se termine en 2027 ; les manches de fin d'année sont de l'année d'avant.
            epreuves = await self._saison_wiki(f"{an - 1}%E2%80%93{str(an)[2:]}_Formula_E_World_Championship", "fe", an, True)
            if epreuves:
                return epreuves
        return []

    # --- À venir ----------------------------------------------------------------------

    async def _avenir(self) -> list[dict[str, Any]]:
        """Les prochains matchs et courses, toutes disciplines confondues, dans les quinze jours."""
        maintenant = time.time()
        sortie: list[dict[str, Any]] = []
        # Valorant : la page des matchs à venir, même structure que les résultats, l'ETA est le temps restant.
        try:
            page = await self._texte(VLR_AVENIR)
            nouveaux = 0
            for ident, bloc in _BLOC.findall(page):
                statut = _STATUT.search(bloc)
                etat = _propre(statut.group(1)).lower() if statut else ""
                if etat not in ("upcoming", "live"):
                    continue
                evenement = _EVENEMENT.search(bloc)
                nom_evenement = _propre(evenement.group(1)) if evenement else ""
                if not any(mot in nom_evenement.lower() for mot in VLR_FILTRE):
                    continue
                equipes = _EQUIPE.findall(bloc)
                if len(equipes) != 2:
                    continue
                noms = [_propre(e[2]) for e in equipes]
                if any(n.upper() == "TBD" for n in noms):
                    continue
                eta = _ETA.search(bloc)
                ts = int(maintenant + _eta_en_secondes(eta.group(1))) if eta and etat == "upcoming" else int(maintenant)
                detail = self.matchs_vlr.get(ident)
                if detail is None and nouveaux < 6:
                    nouveaux += 1
                    detail = await self._detail_vlr(ident, noms)
                    self.matchs_vlr[ident] = detail
                    await asyncio.sleep(1.5)
                detail = detail or {}
                serie = _SERIE.search(bloc)
                sortie.append({
                    "id": f"vlr{ident}", "sport": "Valorant", "titre": f"{noms[0]}  ·  {noms[1]}",
                    "detail": " · ".join(x for x in (nom_evenement, _propre(serie.group(1)) if serie else "") if x),
                    "ts": int(detail.get("ts") or ts), "l1": detail.get("l1", ""), "l2": detail.get("l2", ""), "direct": etat == "live",
                })
                if sum(1 for a in sortie if a["sport"] == "Valorant") >= 6:
                    break
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("avenir vlr : %s", err)
        # Rocket League : l'onglet « Upcoming » du fil Liquipedia.
        try:
            data = await self._json(LIQUIPEDIA_RL)
            page = ((data.get("parse") or {}).get("text") or {}).get("*", "")
            debut = page.find('data-toggle-area-content="1"')
            fin = page.find('data-toggle-area-content="2"')
            zone = page[debut:fin] if debut >= 0 else ""
            n = 0
            for bloc in zone.split('<div class="match-info">')[1:]:
                tournoi = _LP_TOURNOI.search(bloc)
                nom_tournoi = _propre(tournoi.group(2)) if tournoi else ""
                page_tournoi = _propre(tournoi.group(1)) if tournoi else ""
                if not any(mot in (nom_tournoi + " " + page_tournoi).lower() for mot in RL_FILTRE):
                    continue
                equipes = _LP_EQUIPE.findall(bloc)
                ts = _LP_TS.search(bloc)
                if len(equipes) != 2 or not ts:
                    continue
                def logo(src: str) -> str:
                    if not src:
                        return ""
                    src = re.sub(r"/(\d+)px-", "/120px-", src, count=1)
                    return src if src.startswith("http") else "https://liquipedia.net" + src
                sortie.append({
                    "id": f"rl-av-{ts.group(1)}-{_propre(equipes[0][0])[:12]}", "sport": "Rocket League",
                    "titre": f"{_propre(equipes[0][0]) or _propre(equipes[0][2])}  ·  {_propre(equipes[1][0]) or _propre(equipes[1][2])}",
                    "detail": nom_tournoi, "ts": int(ts.group(1)), "l1": logo(equipes[0][1]), "l2": logo(equipes[1][1]), "direct": False,
                })
                n += 1
                if n >= 6:
                    break
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("avenir rl : %s", err)
        # Formule 1 : la prochaine course du calendrier.
        try:
            data = await self._json(f"{JOLPICA}/current/next.json")
            courses = (((data.get("MRData") or {}).get("RaceTable") or {}).get("Races") or [])
            if courses:
                c = courses[0]
                ts = int(dt_util.as_timestamp(dt_util.parse_datetime(f"{c.get('date')}T{c.get('time') or '12:00:00Z'}")))
                sortie.append({
                    "id": f"f1-av-{c.get('season')}-{c.get('round')}", "sport": "Formule 1",
                    "titre": str(c.get("raceName") or "").replace("Grand Prix", "GP"),
                    "detail": textes.t("esp.manche", None, n=c.get('round')) + " · "
                              + str(((c.get('Circuit') or {}).get('Location') or {}).get('locality', '')),
                    "ts": ts, "l1": "", "l2": "", "direct": False,
                })
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("avenir f1 : %s", err)
        for prefixe, sport in (("wrc", "WRC"), ("fe", "Formule E")):
            p = self.prochaines.get(prefixe)
            if p and p.get("ts"):
                sortie.append({
                    "id": f"{prefixe}-av-{p['manche']}", "sport": sport,
                    "titre": p.get("nom") or textes.t("esp.manche", None, n=p['manche']),
                    "detail": textes.t("esp.manche", None, n=p['manche']), "ts": int(p["ts"]), "l1": "", "l2": "", "direct": False,
                })
        limite = maintenant + 15 * 86400
        sortie = [a for a in sortie if maintenant - 7200 < a["ts"] < limite]
        sortie.sort(key=lambda a: a["ts"])
        return sortie[:10]

    # --- Logos -------------------------------------------------------------------

    async def logo(self, url: str) -> tuple[bytes, str] | None:
        """Le logo d'une équipe, mis en cache sur disque ; seuls les hôtes connus passent."""
        if not url.startswith("https://") or url.split("/")[2] not in LOGOS_HOTES:
            return None
        cle = hashlib.sha1(url.encode()).hexdigest()
        chemin = os.path.join(self.dossier, cle)

        def _lire() -> tuple[bytes, str] | None:
            if not os.path.exists(chemin):
                return None
            with open(chemin, "rb") as f:
                return f.read(), open(chemin + ".type", encoding="utf-8").read() if os.path.exists(chemin + ".type") else "image/png"

        connu = await self.hass.async_add_executor_job(_lire)
        if connu:
            return connu
        try:
            async with asyncio.timeout(15):
                async with self.session.get(url, headers={"User-Agent": AGENT}) as rep:
                    rep.raise_for_status()
                    octets = await rep.read()
                    genre = rep.headers.get("Content-Type", "image/png").split(";")[0]
        except Exception as err:  # noqa: BLE001
            _LOGGER.debug("logo %s : %s", url, err)
            return None
        if len(octets) > 2_000_000:
            return None

        def _ecrire() -> None:
            os.makedirs(self.dossier, exist_ok=True)
            with open(chemin, "wb") as f:
                f.write(octets)
            with open(chemin + ".type", "w", encoding="utf-8") as f:
                f.write(genre)

        await self.hass.async_add_executor_job(_ecrire)
        return octets, genre


def esports(hass: HomeAssistant) -> Esports:
    cle = f"{DOMAIN}_esports"
    if cle not in hass.data:
        hass.data[cle] = Esports(hass)
    return hass.data[cle]


class PcParentalVeilleLogoView(HomeAssistantView):
    """Un logo d'équipe pour l'écran de veille, relayé et mis en cache par Home Assistant."""

    url = "/api/pc_parental/veille/logo"
    name = "api:pc_parental:veille_logo"
    requires_auth = False

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass

    async def post(self, request: web.Request) -> web.Response:
        try:
            corps: dict[str, Any] = await request.json()
        except ValueError:
            return self.json({"ok": False, "error": "json"}, status_code=400)
        entrees = list(self.hass.data.get(DOMAIN, {}).values())
        coord = entrees[0] if entrees else None
        if coord is None:
            return self.json({"ok": False, "error": "loading"}, status_code=503)
        if coord.store.by_secret(str(corps.get("id") or ""), str(corps.get("secret") or "")) is None:
            return self.json({"ok": False, "error": "auth"}, status_code=401)
        from . import modules

        if refus := modules.refus(self, coord.store, "veille"):  # 1.82.0
            return refus
        trouve = await esports(self.hass).logo(str(corps.get("url") or ""))
        if trouve is None:
            return self.json({"ok": False, "error": "logo"}, status_code=404)
        octets, genre = trouve
        return web.Response(body=octets, content_type=genre, headers={"Cache-Control": "max-age=86400"})
