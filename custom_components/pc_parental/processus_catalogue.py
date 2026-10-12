"""Le catalogue de ce qui tourne sur les PC, sans rien de Home Assistant.

Quatre genres, parce que Windows lance un programme de quatre façons :

* **demarrage** — une clé Run du registre ou un raccourci du dossier
  Démarrage : ce que montre l'onglet « Démarrage » du Gestionnaire des
  tâches ;
* **tache** — une tâche planifiée d'un éditeur tiers (les tâches de
  Microsoft ne remontent pas) ;
* **service** — un service Windows d'un éditeur tiers ;
* **processus** — ce qui tourne, tout simplement.

Le chemin est celui des sites et des logiciels : l'agent relève, un
classeur **propose** (la tâche planifiée de Claude, par le MCP de Home
Assistant), un adulte **tranche**. Une proposition ne change rien sur une
machine ; seule une décision le fait. Et tout ce qui change se défait.

Ce module ne connaît pas Home Assistant : il se teste seul.
"""

from __future__ import annotations

import secrets
from typing import Any

from . import textes


def genre_libelle(genre: str, langue: str | None = None) -> str:
    """Le nom d'un genre d'élément dans une langue (sans langue : celle de Home Assistant)."""
    return textes.t(f"proc.genre_{genre}", langue)


def verdict_libelle(verdict: str, langue: str | None = None) -> str:
    return textes.t(f"proc.verdict_{verdict}", langue)


# Identifiant → libellé en français (les libellés de chaque langue : `genre_libelle`, catalogue serveur).
# « logiciel » : ce que Windows liste dans « Applications installées » ; on ne le classe pas, on le garde ou on
# le désinstalle. C'est la liste qu'un parent parcourt.
GENRES: dict[str, str] = {g: genre_libelle(g, "fr") for g in ("demarrage", "tache", "service", "processus", "logiciel")}

VERDICTS: dict[str, str] = {v: verdict_libelle(v, "fr") for v in ("garder", "hors_demarrage", "nuisible", "desinstaller")}


class _Inconnu(textes.Erreur, KeyError):
    """Un élément inconnu : une KeyError (comme avant) qui se dit dans la langue du lecteur."""

# Un processus nu ne « démarre » pas : on le garde ou on l'arrête.
VERDICTS_PAR_GENRE: dict[str, tuple[str, ...]] = {
    "demarrage": ("garder", "hors_demarrage", "nuisible", "desinstaller"),
    "tache": ("garder", "hors_demarrage", "nuisible", "desinstaller"),
    "service": ("garder", "hors_demarrage", "nuisible", "desinstaller"),
    "processus": ("garder", "nuisible", "desinstaller"),
    "logiciel": ("garder", "desinstaller"),
}

# Désinstaller ne se défait pas. Le verdict ne se **propose** donc jamais (un classeur peut se
# tromper, et « valider tout » l'emporterait) : seul un adulte le pose, un logiciel à la fois.
VERDICTS_SANS_RETOUR = ("desinstaller",)

# Ce qu'on ne désinstalle jamais, même sur un clic : l'anti-triche d'un jeu (le jeu ne se lance
# plus, et il ne se réinstalle pas toujours seul), les pilotes du matériel, Vision lui-même.
ANTITRICHE = frozenset(
    {
        "vgc", "vgk", "vgtray", "vgm", "beservice", "beservice_x64", "bedaisy",
        "easyanticheat", "easyanticheat_eos", "easyanticheat_setup", "eac_launcher",
        "faceit", "faceitservice", "faceit_ac", "esportal", "esea", "xigncode", "gameguard",
        "mhyprot2", "mhyprot3", "nprotect", "punkbuster", "pnkbstra", "pnkbstrb", "ricochet",
    }
)
EDITEURS_PILOTES = (
    "nvidia", "intel", "advanced micro devices", "amd ", "realtek", "qualcomm", "mediatek",
    "synaptics", "elan ", "broadcom", "marvell", "asmedia", "killer networking", "rivet networks",
)

FILTRES = ("a_classer", "propositions", "decides", "logiciels", "tous")

# Ce qu'aucune décision ne touche, même mal prise. L'agent a la même liste :
# deux verrous valent mieux qu'un, et celui-ci dit non avant qu'on clique.
PROTEGES = frozenset(
    {
        "system", "registry", "idle", "smss", "csrss", "wininit", "winlogon",
        "services", "lsass", "svchost", "dwm", "explorer", "sihost",
        "taskhostw", "fontdrvhost", "conhost", "ctfmon", "audiodg",
        "spoolsv", "msmpeng", "nissrv", "securityhealthservice",
        "securityhealthsystray", "mpdefendercoreservice",
        # L'agent lui-même, et ce qui le fait tourner.
        "powershell", "pwsh",
    }
)

# Un relevé qu'aucune machine n'a refait depuis un mois ne décrit plus rien.
OUBLI_SECONDES = 30 * 86400

# Champs descriptifs acceptés de l'agent, et leur longueur maximale.
CHAMPS: dict[str, int] = {
    "nom": 120,
    "exe": 80,
    "chemin": 300,
    "commande": 400,
    "signataire": 120,
    "statut": 24,
    "editeur": 120,
    "description": 200,
    "ou": 24,
    # Logiciel installé : ses programmes vus en marche (« a,b,c »), pour pouvoir les bloquer par leur nom.
    "exes": 400,
    "version": 40,
}


def cle_de(genre: str, ident: str) -> str:
    return f"{genre}|{ident.strip().lower()}"


def _nombre(valeur: Any) -> float:
    try:
        return round(float(valeur), 1)
    except (TypeError, ValueError):
        return 0.0


def protege(fiche: dict[str, Any]) -> bool:
    """Hors d'atteinte : Windows lui-même, et ce qui le défend.

    « Microsoft Corporation » ne suffit pas à protéger : OneDrive, Teams ou
    Edge sont signés ainsi et sont précisément ce qu'on veut pouvoir retirer
    du démarrage. « Microsoft Windows » (le système) et C:\\Windows, si.
    Un service, lui, est protégé dès que Microsoft l'a signé : c'est là
    qu'une erreur coûte le plus cher.
    """
    exe = (fiche.get("exe") or "").strip().lower()
    if exe in PROTEGES:
        return True
    chemin = (fiche.get("chemin") or "").strip().lower().replace("/", "\\")
    if chemin.startswith("c:\\windows\\"):
        return True
    signataire = (fiche.get("signataire") or "").strip().lower()
    if signataire.startswith("microsoft windows"):
        return True
    if fiche.get("genre") == "service" and signataire.startswith("microsoft"):
        return True
    return False


def raison_non_desinstallable(fiche: dict[str, Any], langue: str | None = None) -> str:
    """Pourquoi Vision refuse de désinstaller ceci — chaîne vide s'il veut bien (sans langue : celle de HA)."""
    cle = _cle_non_desinstallable(fiche)
    return textes.t(cle, langue) if cle else ""


def _cle_non_desinstallable(fiche: dict[str, Any]) -> str:
    """La clé du catalogue serveur qui dit pourquoi Vision refuse de désinstaller ceci, ou ""."""
    if protege(fiche):
        return "proc.r_windows"
    exe = (fiche.get("exe") or "").strip().lower()
    chemin = (fiche.get("chemin") or "").strip().lower().replace("/", "\\")
    qui = " ".join(
        str(fiche.get(c) or "") for c in ("signataire", "editeur")
    ).strip().lower() + " "
    if exe in ANTITRICHE or "anticheat" in chemin or "anti-cheat" in chemin or "vanguard" in chemin:
        return "proc.r_antitriche"
    if "\\haparental" in chemin or "\\vision\\" in chemin and "agent" in chemin:
        return "proc.r_agent"
    if qui.strip().startswith("microsoft"):
        return "proc.r_microsoft"
    if "\\drivers\\" in chemin or "\\driverstore\\" in chemin:
        return "proc.r_pilote"
    if any(e in qui for e in EDITEURS_PILOTES):
        return "proc.r_fabricant"
    nom = (fiche.get("nom") or "").strip().lower()
    if fiche.get("genre") == "logiciel":
        # Un logiciel installé se reconnaît à son nom et à son éditeur ; l'agent refait ces contrôles sur place.
        if nom.startswith("microsoft ") or nom.startswith("windows "):
            return "proc.r_microsoft"
        if any(m in nom for m in ("vanguard", "easyanticheat", "easy anti-cheat", "battleye", "faceit", "anti-cheat", "anticheat")):
            return "proc.r_antitriche"
        if any(m in (" " + nom + " ") for m in (" driver ", " drivers ", " pilote ", " pilotes ", " chipset ", " firmware ", " bios ")):
            return "proc.r_pilote"
        if nom in ("vision", "haparental", "ha parental") or "haparental" in nom:
            return "proc.r_agent"
        return ""
    if not chemin:
        return "proc.r_sans_emplacement"
    return ""


class Catalogue:
    """Toutes les fiches de la maison, indexées par « genre|identifiant »."""

    def __init__(self, elements: dict[str, dict[str, Any]] | None = None) -> None:
        self.elements: dict[str, dict[str, Any]] = elements or {}

    # ------------------------------------------------------------ Relevés

    def noter(self, pc_id: str, releve: list[Any], maintenant: float) -> int:
        """Range un inventaire envoyé par un PC. Rend le nombre retenu.

        Un élément absent d'un inventaire n'est **pas** effacé : un processus
        arrêté parce qu'il est nuisible disparaît de la liste, et doit rester
        visé au prochain lancement. C'est l'âge qui fait oublier (`purger`).
        """
        retenus = 0
        for brut in list(releve or [])[:800]:
            if not isinstance(brut, dict):
                continue
            genre = str(brut.get("g") or "")
            ident = str(brut.get("id") or "").strip()[:300]
            if genre not in GENRES or not ident:
                continue
            cle = cle_de(genre, ident)
            fiche = self.elements.get(cle)
            if fiche is None:
                fiche = self.elements[cle] = {
                    "genre": genre,
                    "id": ident,
                    "premier_vu": maintenant,
                    "proposition": None,
                    "decision": None,
                    "pcs": {},
                }
            for champ, limite in CHAMPS.items():
                valeur = brut.get(champ)
                if valeur not in (None, ""):
                    fiche[champ] = str(valeur)[:limite]
            precedent = fiche["pcs"].get(pc_id) or {}
            fiche["pcs"][pc_id] = {
                "vu": maintenant,
                "actif": bool(brut.get("actif", True)),
                "mode": str(brut.get("mode") or "")[:24],
                "ram_mo": _nombre(brut.get("ram_mo")),
                "cpu_s": _nombre(brut.get("cpu_s")),
                "instances": int(_nombre(brut.get("instances"))),
                "applique": bool(precedent.get("applique")),
            }
            # Le compte rendu d'une désinstallation survit aux inventaires :
            # sans lui, la consigne repartirait à chaque relevé.
            if precedent.get("desinstall"):
                fiche["pcs"][pc_id]["desinstall"] = precedent["desinstall"]
            retenus += 1
        return retenus

    def noter_appliques(self, pc_id: str, appliques: Any) -> None:
        """Ce que l'agent a effectivement changé sur ce PC, genre par genre."""
        if not isinstance(appliques, dict):
            return
        faits: set[str] = set()
        for genre, cles in (
            ("demarrage", appliques.get("demarrage")),
            ("tache", appliques.get("taches")),
            ("service", appliques.get("services")),
        ):
            for ident in list(cles or [])[:500]:
                faits.add(cle_de(genre, str(ident)))
        for cle, fiche in self.elements.items():
            chez = fiche["pcs"].get(pc_id)
            if chez is not None:
                chez["applique"] = cle in faits

    def purger(self, maintenant: float) -> int:
        """Oublie ce qu'aucun PC n'a revu depuis un mois — sauf les décisions."""
        oublies = 0
        for cle in list(self.elements):
            fiche = self.elements[cle]
            for pc_id in list(fiche["pcs"]):
                if maintenant - float(fiche["pcs"][pc_id].get("vu") or 0) > OUBLI_SECONDES:
                    del fiche["pcs"][pc_id]
            if not fiche["pcs"] and not fiche.get("decision"):
                del self.elements[cle]
                oublies += 1
        return oublies

    def oublier_pc(self, pc_id: str) -> None:
        for fiche in self.elements.values():
            fiche["pcs"].pop(pc_id, None)

    # ------------------------------------------------ Proposer, trancher

    def resoudre(self, cle: str) -> dict[str, Any]:
        genre, _, ident = str(cle or "").partition("|")
        fiche = self.elements.get(cle_de(genre, ident))
        if fiche is None:
            raise _Inconnu("proc.element_inconnu", element=cle)
        return fiche

    @staticmethod
    def _verifier(fiche: dict[str, Any], verdict: str) -> None:
        permis = VERDICTS_PAR_GENRE[fiche["genre"]]
        if verdict not in permis:
            raise textes.Erreur("proc.sans_sens", verdict=verdict, permis=', '.join(permis),
                                genre=lambda lg: genre_libelle(fiche['genre'], lg).lower())
        if verdict != "garder" and protege(fiche):
            raise textes.Erreur("proc.protege", nom=fiche.get('nom') or fiche['id'])
        if verdict in VERDICTS_SANS_RETOUR:
            refus = _cle_non_desinstallable(fiche)
            if refus:
                raise textes.Erreur("proc.pas_desinstalle", nom=fiche.get('nom') or fiche['id'],
                                    raison=lambda lg: textes.t(refus, lg))

    def proposer(
        self,
        cle: str,
        verdict: str,
        raison: str,
        confiance: int,
        par: str,
        maintenant: float,
    ) -> dict[str, Any]:
        fiche = self.resoudre(cle)
        if verdict in VERDICTS_SANS_RETOUR:
            raise textes.Erreur("proc.pas_proposer")
        self._verifier(fiche, verdict)
        fiche["proposition"] = {
            "verdict": verdict,
            "raison": str(raison or "")[:500],
            "confiance": max(0, min(100, int(confiance))),
            "par": str(par or "classeur")[:40],
            "date": maintenant,
        }
        return fiche

    @staticmethod
    def jeton_pour(fiche: dict[str, Any], pc_id: str) -> str:
        """Le jeton de désinstallation qui vaut pour cet appareil ('' s'il n'est pas visé)."""
        decision = fiche.get("decision") or {}
        if decision.get("verdict") not in VERDICTS_SANS_RETOUR:
            return ""
        vises = decision.get("pcs")
        if isinstance(vises, dict):
            return str(vises.get(pc_id) or "")
        return str(decision.get("jeton") or "")

    def decider(
        self, cle: str, decision: str, maintenant: float, pcs: list[str] | None = None
    ) -> dict[str, Any]:
        """Tranche. « valider » reprend la proposition ; « neutre » défait.

        Défaire n'est pas « garder » : garder est une décision (l'élément ne
        revient plus dans la file), neutre remet à classer — et l'agent
        restaure ce qu'il avait changé.
        """
        fiche = self.resoudre(cle)
        if decision == "neutre":
            fiche["decision"] = None
            return fiche
        if decision == "valider":
            proposition = fiche.get("proposition")
            if not proposition:
                raise textes.Erreur("proc.aucune_proposition", nom=fiche.get('nom') or cle)
            decision = proposition["verdict"]
        self._verifier(fiche, decision)
        if decision in VERDICTS_SANS_RETOUR and pcs:
            # Demandé pour certains appareils seulement (depuis la fiche d'un appareil) : un jeton
            # par appareil, et les autres ne sont pas touchés, même s'ils ont le même logiciel.
            vises = [p for p in pcs if p in fiche["pcs"]]
            if not vises:
                raise textes.Erreur("proc.pas_sur_appareil", nom=fiche.get('nom') or cle)
            avant = fiche.get("decision") or {}
            jetons = dict(avant.get("pcs") or {}) if avant.get("verdict") == decision and isinstance(avant.get("pcs"), dict) else {}
            for p in vises:
                jetons[p] = secrets.token_hex(6)
                fiche["pcs"][p].pop("desinstall", None)
            fiche["decision"] = {"verdict": decision, "date": maintenant, "pcs": jetons}
            return fiche
        fiche["decision"] = {"verdict": decision, "date": maintenant}
        if decision in VERDICTS_SANS_RETOUR:
            # Un jeton par décision : l'agent ne rejoue jamais deux fois la même, et une nouvelle
            # décision (après un échec, par exemple) est bien une nouvelle tentative.
            fiche["decision"]["jeton"] = secrets.token_hex(6)
            for chez in fiche["pcs"].values():
                chez.pop("desinstall", None)
        return fiche

    def noter_desinstallations(self, pc_id: str, comptes_rendus: Any) -> bool:
        """Ce que l'agent dit avoir fait d'une désinstallation demandée. Rend True si du nouveau."""
        bouge = False
        for brut in list(comptes_rendus or [])[:40]:
            if not isinstance(brut, dict):
                continue
            try:
                fiche = self.resoudre(str(brut.get("cle") or ""))
            except KeyError:
                continue
            chez = fiche["pcs"].get(pc_id)
            attendu = self.jeton_pour(fiche, pc_id)
            if chez is None or not attendu or attendu != brut.get("jeton"):
                continue
            decision = {"jeton": attendu}
            neuf = {
                "etat": str(brut.get("etat") or "")[:24],
                "detail": str(brut.get("detail") or "")[:240],
                "produit": str(brut.get("produit") or "")[:120],
                "date": _nombre(brut.get("date")),
                "jeton": decision["jeton"],
            }
            if chez.get("desinstall") != neuf:
                chez["desinstall"] = neuf
                bouge = True
        return bouge

    # ------------------------------------------------------ Ce qui s'applique

    def consignes(self, pc_id: str, desinstallation: bool = False) -> dict[str, list[Any]]:
        """Ce que l'agent de ce PC doit tenir. Tout le reste, il le restaure.

        Les désinstallations ne partent que si la maison les a permises
        (« desinstallation »), et une seule fois : dès que l'agent a rendu
        compte, la consigne n'est plus envoyée.
        """
        sortie: dict[str, list[Any]] = {
            "demarrage": [],
            "taches": [],
            "services": [],
            "arreter": [],
        }
        a_retirer: list[dict[str, str]] = []
        for cle, fiche in self.elements.items():
            decision = (fiche.get("decision") or {}).get("verdict")
            if decision in VERDICTS_SANS_RETOUR:
                chez = fiche["pcs"].get(pc_id)
                jeton = self.jeton_pour(fiche, pc_id)
                if (
                    desinstallation and chez is not None and jeton
                    and (chez.get("desinstall") or {}).get("jeton") != jeton
                    and not raison_non_desinstallable(fiche)
                ):
                    a_retirer.append({
                        "cle": cle, "jeton": jeton, "genre": fiche["genre"], "id": fiche["id"],
                        "nom": str(fiche.get("nom") or ""), "exe": str(fiche.get("exe") or ""),
                        "chemin": str(fiche.get("chemin") or ""),
                        "editeur": str(fiche.get("editeur") or fiche.get("signataire") or ""),
                    })
                continue
            if decision not in ("hors_demarrage", "nuisible"):
                continue
            if pc_id not in fiche["pcs"] or protege(fiche):
                continue
            genre = fiche["genre"]
            if genre == "demarrage":
                sortie["demarrage"].append(fiche["id"])
            elif genre == "tache":
                sortie["taches"].append(fiche["id"])
            elif genre == "service":
                sortie["services"].append(fiche["id"])
            if decision == "nuisible":
                exe = (fiche.get("exe") or "").strip().lower()
                if exe and exe not in PROTEGES and exe not in sortie["arreter"]:
                    sortie["arreter"].append(exe)
        for liste in sortie.values():
            liste.sort()
        if a_retirer:
            sortie["desinstaller"] = sorted(a_retirer, key=lambda d: d["cle"])
        return sortie

    def logiciels_de(self, pc_id: str) -> list[dict[str, Any]]:
        """Les logiciels installés sur cet appareil, tels que son dernier inventaire les a vus."""
        vus = [
            float(f["pcs"][pc_id].get("vu") or 0)
            for f in self.elements.values()
            if f.get("genre") == "logiciel" and pc_id in f["pcs"]
        ]
        if not vus:
            return []
        dernier = max(vus)
        sortie = []
        for cle, fiche in self.elements.items():
            chez = fiche["pcs"].get(pc_id) if fiche.get("genre") == "logiciel" else None
            if chez is None:
                continue
            decision = (fiche.get("decision") or {}).get("verdict") or ""
            rendu = chez.get("desinstall") or None
            # Disparu du dernier inventaire : on ne le montre plus, sauf s'il vient d'être désinstallé.
            if dernier - float(chez.get("vu") or 0) > 120 and not (rendu and decision == "desinstaller"):
                continue
            sortie.append({
                "cle": cle,
                "nom": str(fiche.get("nom") or fiche["id"]),
                "editeur": str(fiche.get("editeur") or ""),
                "version": str(fiche.get("version") or ""),
                "exes": [e for e in str(fiche.get("exes") or "").split(",") if e],
                "decision": decision,
                "refus": raison_non_desinstallable(fiche),
                "retour": rendu,
            })
        sortie.sort(key=lambda x: x["nom"].lower())
        return sortie

    # --------------------------------------------------------------- Lecture

    def etat(self, fiche: dict[str, Any]) -> str:
        if fiche.get("decision"):
            return "decides"
        if fiche.get("proposition"):
            return "propositions"
        if fiche.get("genre") == "logiciel":
            # Rien à classer : la liste des logiciels se consulte, elle n'attend pas de verdict.
            return "logiciels"
        return "a_classer"

    def lister(
        self,
        filtre: str = "a_classer",
        limite: int = 60,
        noms_pc: dict[str, str] | None = None,
        pc_id: str | None = None,
    ) -> dict[str, Any]:
        noms_pc = noms_pc or {}
        choisies = []
        for cle, fiche in self.elements.items():
            if pc_id and pc_id not in fiche["pcs"]:
                continue
            if filtre != "tous" and self.etat(fiche) != filtre:
                continue
            if filtre != "tous" and protege(fiche) and filtre != "decides":
                # Rien à proposer ni à trancher : inutile de les montrer.
                continue
            choisies.append((cle, fiche))

        def _poids(paire):
            fiche = paire[1]
            ram = max((p.get("ram_mo") or 0) for p in fiche["pcs"].values()) if fiche["pcs"] else 0
            return (list(GENRES).index(fiche["genre"]), -ram, (fiche.get("nom") or fiche["id"]).lower())

        choisies.sort(key=_poids)
        elements = []
        for cle, fiche in choisies[: max(1, int(limite))]:
            chez = fiche["pcs"]
            elements.append(
                {
                    "cle": cle,
                    "genre": fiche["genre"],
                    "genre_libelle": genre_libelle(fiche["genre"]),
                    **{c: fiche.get(c, "") for c in CHAMPS},
                    "protege": protege(fiche),
                    "non_desinstallable": raison_non_desinstallable(fiche),
                    "etat": self.etat(fiche),
                    "proposition": fiche.get("proposition"),
                    "decision": fiche.get("decision"),
                    "pcs": [
                        {
                            "pc": noms_pc.get(p, p),
                            "actif": d.get("actif"),
                            "mode": d.get("mode"),
                            "ram_mo": d.get("ram_mo"),
                            "cpu_s": d.get("cpu_s"),
                            "instances": d.get("instances"),
                            "applique": d.get("applique"),
                            "desinstall": d.get("desinstall"),
                            "vise": bool(self.jeton_pour(fiche, p)),
                        }
                        for p, d in sorted(chez.items(), key=lambda x: noms_pc.get(x[0], x[0]))
                    ],
                }
            )
        return {
            "filtre": filtre,
            "total": len(choisies),
            "rendus": len(elements),
            "elements": elements,
        }

    def compter(self) -> dict[str, int]:
        compte = {"a_classer": 0, "propositions": 0, "decides": 0, "logiciels": 0}
        for fiche in self.elements.values():
            if protege(fiche) and not fiche.get("decision"):
                continue
            compte[self.etat(fiche)] += 1
        return compte
