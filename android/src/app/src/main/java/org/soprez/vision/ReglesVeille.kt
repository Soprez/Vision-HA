// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Règles pures (testées) de la veille sur télé quand l'économiseur d'écran du système
 * n'est pas Vision (TCL, Google TV masquent souvent ce choix).
 */
object ReglesVeille {
    /** Le composant du rêve de Vision, tel qu'Android l'écrit dans « screensaver_components ». */
    const val COMPOSANT_COURT = "org.soprez.vision/.VeilleService"
    const val COMPOSANT_LONG = "org.soprez.vision/org.soprez.vision.VeilleService"

    /** Délais proposés pour la veille de repli sur l'accueil (minutes ; 0 = jamais). Défaut : 10. */
    val DELAIS_REPLI = listOf(5, 10, 15, 30, 60, 0)
    const val REPLI_DEFAUT = 10

    /**
     * Vrai si la valeur de Settings.Secure « screensaver_components » (liste séparée par des virgules)
     * désigne la veille de Vision ; faux si c'est une autre ; null si la valeur est illisible.
     */
    fun economiseurEstVision(composants: String?, lisible: Boolean = true): Boolean? {
        if (!lisible) return null
        if (composants.isNullOrBlank()) return false
        return composants.split(',').map { it.trim() }.any { it.equals(COMPOSANT_COURT, true) || it.equals(COMPOSANT_LONG, true) }
    }

    /**
     * Minutes sans touche sur l'accueil de la télé avant que Vision lance sa veille, 0 = jamais.
     * Le délai choisi dans Vision l'emporte ; sinon, si l'économiseur du système n'est pas Vision
     * (ou illisible), le repli s'applique ; si c'est Vision, le système s'en charge.
     */
    fun delaiAccueil(delaiChoisi: Int, economiseurVision: Boolean?, delaiRepli: Int): Int = when {
        delaiChoisi > 0 -> delaiChoisi
        economiseurVision == true -> 0
        else -> delaiRepli.coerceAtLeast(0)
    }

    /** Le délai suivant dans la liste (pour un réglage qu'on fait tourner à la télécommande). */
    fun repliSuivant(actuel: Int): Int {
        val i = DELAIS_REPLI.indexOf(actuel)
        return DELAIS_REPLI[(if (i < 0) 0 else i + 1) % DELAIS_REPLI.size]
    }

    fun libelleRepli(min: Int): String = if (min <= 0) Tr.s(R.string.veille_repli_jamais) else Tr.s(R.string.veille_repli_apres, min)
}
