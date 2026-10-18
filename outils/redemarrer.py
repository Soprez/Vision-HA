"""Redémarre Home Assistant Core seulement quand c'est permis (règle du projet : un réveil ne doit jamais rester muet).

Refus :
- pendant la fenêtre des réveils (binary_sensor.vision_fenetre_reveil allumé : une heure avant la première
  occurrence armée du jour, jusqu'à 30 min après la dernière) ;
- si l'état de la fenêtre est illisible alors que le module Réveil existe (dans le doute, on ne redémarre pas).

À lancer sur l'hôte (module SSH, qui fournit SUPERVISOR_TOKEN) :
    python3 redemarrer.py --verifier     # dit seulement si c'est permis (code 0 / 1)
    python3 redemarrer.py                # ha core check, puis ha core restart si permis
    python3 redemarrer.py --plage 23:00-08:00   # plage interdite, au besoin (une nuit de travail, par exemple)

l'auteur, 17/10 : la plage fixe de 6 h 30 à 8 h est supprimée ; seule la fenêtre des réveils protège le matin.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.request
from datetime import datetime

ENTITE = "binary_sensor.vision_fenetre_reveil"


def dans_plage(hhmm: str, debut: str, fin: str) -> bool:
    """Vrai si hhmm est dans [debut, fin[ ; une plage qui passe minuit (23:00-08:00) est gérée."""
    if debut <= fin:
        return debut <= hhmm < fin
    return hhmm >= debut or hhmm < fin


def decider(hhmm: str, fenetre: str | None, plages: list[tuple[str, str]] | None = None) -> tuple[bool, str]:
    """(permis, raison). `fenetre` : état de binary_sensor.vision_fenetre_reveil ; None = entité absente
    (module Réveil jamais activé : rien à protéger) ; "unknown"/"unavailable"/"" = illisible → refus."""
    for debut, fin in plages or []:
        if dans_plage(hhmm, debut, fin):
            return False, f"REFUS : {hhmm} est dans la plage interdite {debut}–{fin}."
    if fenetre == "on":
        return False, "REFUS : fenêtre des réveils en cours (binary_sensor.vision_fenetre_reveil)."
    if fenetre is not None and fenetre != "off":
        return False, f"REFUS : état de la fenêtre des réveils illisible ({fenetre!r}) ; dans le doute, pas de redémarrage."
    return True, f"Permis à {hhmm}."


def _api(chemin: str) -> dict | None:
    jeton = os.environ.get("SUPERVISOR_TOKEN", "")
    req = urllib.request.Request("http://supervisor/core/api/" + chemin, headers={"Authorization": f"Bearer {jeton}"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        if err.code == 404:
            return None
        raise


def heure_locale() -> str:
    from zoneinfo import ZoneInfo

    fuseau = (_api("config") or {}).get("time_zone") or "UTC"
    return datetime.now(ZoneInfo(fuseau)).strftime("%H:%M")


def main(args: list[str]) -> int:
    plages = []
    if "--plage" in args:
        debut, fin = args[args.index("--plage") + 1].split("-")
        plages.append((debut, fin))
    try:
        etat = _api(f"states/{ENTITE}")
        fenetre = None if etat is None else str(etat.get("state") or "")
        hhmm = heure_locale()
    except Exception as err:  # noqa: BLE001 - HA injoignable : on ne sait rien, on ne redémarre pas
        print(f"REFUS : Home Assistant injoignable ({err}).")
        return 1
    permis, raison = decider(hhmm, fenetre, plages)
    print(raison)
    if not permis:
        return 1
    if "--verifier" in args:
        return 0
    if subprocess.call(["ha", "core", "check"]) != 0:
        print("REFUS : ha core check en échec.")
        return 1
    return subprocess.call(["ha", "core", "restart"])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
