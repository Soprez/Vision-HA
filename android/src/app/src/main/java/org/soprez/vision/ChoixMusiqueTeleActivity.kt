// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * l'auteur (16/10) : le choix de la musique dans la moitié droite de la télé, par-dessus le lecteur (fenêtre translucide,
 * voir le manifeste) : le lecteur et le code invités restent visibles et vivants à gauche. Même écran que
 * [ChoixMusiqueActivity].
 */
class ChoixMusiqueTeleActivity : ChoixMusiqueActivity() {
    companion object {
        /** 17-10 (l'auteur, point 22 : « TOUT est trop gros, à réduire d'environ 30 % ») : l'échelle du panneau. */
        const val ECHELLE = 0.7f

        /** La configuration du panneau : la même, en densité réduite (tout est mesuré en dp et sp : tout suit). */
        fun reduite(c: android.content.res.Configuration): android.content.res.Configuration =
            android.content.res.Configuration(c).apply { densityDpi = maxOf(1, (c.densityDpi * ECHELLE).toInt()) }
    }

    override val moitie = true

    // Textes, clavier, onglets, filtres, playlists, résultats et marges : 30 % plus petits, d'un coup et en proportion.
    // La fenêtre garde la moitié de l'écran (sa largeur est prise en pixels) ; la zone sûre aussi (en pixels).
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.createConfigurationContext(reduite(base.resources.configuration)))
    }
}
