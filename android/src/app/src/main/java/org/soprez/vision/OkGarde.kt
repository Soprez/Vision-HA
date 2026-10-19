// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * 1.93.06.1 (l'auteur, 19/10 : « clics fantômes » sur la télé — un appui qui agit sur un autre élément que celui sélectionné).
 *
 * Un appui sur OK commence par un enfoncement et finit par un relâché ; Android fait agir la vue qui a le focus AU
 * RELÂCHÉ. Entre les deux, l'écran peut avoir été rebâti (relecture de Home Assistant, montée de la file, accueil
 * remis à jour) : le focus se retrouve sur une autre vue, parfois un autre élément. Règle : un appui vaut pour
 * l'élément qui avait le focus à l'enfoncement, jamais pour un autre.
 *
 * - [NORMAL] : même vue à l'enfoncement et au relâché — Android fait son travail ;
 * - [REJOUER] : même élément (même clé) mais la vue a été rebâtie entre-temps — la nouvelle vue n'a pas vu l'enfoncement,
 *   il faut cliquer dessus nous-mêmes ;
 * - [IGNORER] : un autre élément (ou plus de focus) — l'appui ne fait rien, et le journal le dit.
 *
 * Pur : testé en JVM (OkGardeTest).
 */
object OkGarde {
    enum class Decision { NORMAL, REJOUER, IGNORER }

    /**
     * [cleBas] : la clé de l'élément focalisé à l'enfoncement (null : rien de connu, Android décide) ; [cleHaut] : celle
     * au relâché ; [memeVue] : la vue du relâché est bien la même instance qu'à l'enfoncement.
     */
    fun decider(cleBas: String?, cleHaut: String?, memeVue: Boolean): Decision = when {
        cleBas == null -> Decision.NORMAL
        cleHaut != cleBas -> Decision.IGNORER
        memeVue -> Decision.NORMAL
        else -> Decision.REJOUER
    }

    /** La ligne du journal pour une décision autre que [Decision.NORMAL]. */
    fun journal(d: Decision, cleBas: String?, cleHaut: String?): String = when (d) {
        Decision.IGNORER -> "OK ignoré : le focus est passé de « $cleBas » à « ${cleHaut ?: "rien"} » pendant l'appui (écran rebâti)"
        Decision.REJOUER -> "OK rejoué sur « $cleBas » : la vue a été rebâtie pendant l'appui"
        Decision.NORMAL -> ""
    }

    val TOUCHES_OK = setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
}
