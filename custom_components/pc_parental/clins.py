"""2.2.1 (demande d'essai) : le clin d'œil de Musique quand la file est vide et que la musique s'arrête.

Un titre de chanson connu (court, jamais de paroles) avec un émoji, tiré au hasard, puis une action claire. Les phrases
sont dans le catalogue serveur (i18n/serveur, clés « mus.fin_<n> ») pour être adaptées par langue ; l'appli Android a la même
liste, dans le même ordre (res/values/strings.xml, « musique_fin_clins »). Home Assistant tire le numéro une fois par
« fin de file » et l'envoie : la télé, le téléphone et la page invité montrent la même phrase, sans clignoter.
"""
from __future__ import annotations

import random
from typing import Any

from homeassistant.core import HomeAssistant

NB_CLINS = 8
_EN_LECTURE = ("playing", "paused", "buffering")


def indice(st: dict[str, Any], etat_lecteur: str, a_une_file: bool) -> int:
    """Le numéro du clin d'œil à montrer, ou -1 si la musique joue encore (ou si Musique n'est pas lancée)."""
    if not st.get("actif") or a_une_file or etat_lecteur in _EN_LECTURE:
        st["clin"] = None
        return -1
    if st.get("clin") is None:
        avant = st.get("clin_avant")
        choix = [k for k in range(NB_CLINS) if k != avant] or [0]
        st["clin"] = random.choice(choix)
        st["clin_avant"] = st["clin"]
    return int(st["clin"])


def phrase(hass: HomeAssistant, n: int, langue: str | None = None) -> tuple[str, str]:
    """(clin d'œil, action) dans `langue` (sinon celle de Home Assistant) : catalogue serveur, « mus.fin_<n> »."""
    from . import textes

    langue = langue or textes.ha(hass)
    return textes.t(f"mus.fin_{n % NB_CLINS + 1}", langue), textes.t("mus.fin_action", langue)
