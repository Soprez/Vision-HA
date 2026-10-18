// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Banc Musique (2026-10-12) : la lecture après une coupure de la liaison avec Music Assistant (Wi-Fi qui décroche,
 * box qui redémarre). Mesuré contre MA 2.10.6 : au retour du lecteur, MA relance le flux puis, 20 ms plus tard, désactive
 * le lecteur (`server/activate` sans activité) et passe le groupe à « stopped » — la musique de la Party s'arrêtait.
 *
 * Règle : on relance (commande « play » après [DELAI_RELANCE_MS], MA reprend au même endroit) seulement si le lecteur
 * jouait quand la liaison s'est perdue, si la coupure a duré moins de [PERTE_MAX_MS], si MA a relancé le flux au retour
 * (`stream/start`) puis dit « stopped » dans les [FENETRE_MS] qui suivent. Une pause demandée ailleurs pendant la coupure
 * ne relance aucun flux au retour : elle reste une pause (MA dit aussi « stopped » pour une pause, d'où ce critère). Une
 * seule relance par retour. Sans Android : testé en JVM.
 */
object SendspinReprise {
    const val PERTE_MAX_MS = 120_000L
    const val FENETRE_MS = 5_000L
    /** Une commande « play » envoyée dès le « stopped » est ignorée par MA (mesuré) ; 1,5 s plus tard, elle passe. */
    const val DELAI_RELANCE_MS = 1_500L

    fun reprendre(lisaitALaPerte: Boolean, perteMs: Long, depuisRetourMs: Long, fluxAuRetour: Boolean, etatGroupe: String, dejaFait: Boolean): Boolean =
        lisaitALaPerte && fluxAuRetour && !dejaFait && perteMs in 0..PERTE_MAX_MS && depuisRetourMs in 0..FENETRE_MS && etatGroupe == "stopped"
}
