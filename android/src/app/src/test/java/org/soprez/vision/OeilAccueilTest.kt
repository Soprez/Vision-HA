// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.91.3 (l'auteur : l'œil sur le bouton Accueil en paysage et après une rotation) : où est la barre de navigation à trois
 * boutons, et où se pose l'œil. Limites en [gauche, haut, droite, bas], en pixels d'un écran de 1080 × 2400 (portrait).
 */
class OeilAccueilTest {
    private fun b(g: Int, h: Int, d: Int, bas: Int) = intArrayOf(g, h, d, bas)

    @Test fun portrait_une_ligne_en_bas() {
        assertTrue(OeilAccueil.trioValide(b(150, 2280, 330, 2400), b(450, 2280, 630, 2400), b(750, 2280, 930, 2400), 1080, 2400))
        // Pas en bas de l'écran (une appli avec ses propres boutons) : jamais.
        assertFalse(OeilAccueil.trioValide(b(150, 1000, 330, 1120), b(450, 1000, 630, 1120), b(750, 1000, 930, 1120), 1080, 2400))
        // Dans le désordre : jamais.
        assertFalse(OeilAccueil.trioValide(b(750, 2280, 930, 2400), b(450, 2280, 630, 2400), b(150, 2280, 330, 2400), 1080, 2400))
    }

    @Test fun paysage_colonne_a_droite_et_a_gauche() {
        // Rotation 90° : la barre en colonne collée au bord droit (écran 2400 × 1080), retour en bas, aperçu en haut.
        assertTrue(OeilAccueil.trioValide(b(2280, 750, 2400, 930), b(2280, 450, 2400, 630), b(2280, 150, 2400, 330), 2400, 1080))
        // Rotation 270° : collée au bord gauche, dans l'autre sens.
        assertTrue(OeilAccueil.trioValide(b(0, 150, 120, 330), b(0, 450, 120, 630), b(0, 750, 120, 930), 2400, 1080))
        // Une colonne au milieu de l'écran (une appli) : jamais.
        assertFalse(OeilAccueil.trioValide(b(1140, 150, 1260, 330), b(1140, 450, 1260, 630), b(1140, 750, 1260, 930), 2400, 1080))
        // L'accueil pas entre les deux autres : jamais.
        assertFalse(OeilAccueil.trioValide(b(2280, 150, 2400, 330), b(2280, 750, 2400, 930), b(2280, 450, 2400, 630), 2400, 1080))
    }

    @Test fun tablette_en_paysage_barre_des_taches() {
        // Les trois boutons à droite de la barre des tâches, en bas (écran 2560 × 1600).
        assertTrue(OeilAccueil.trioValide(b(2080, 1510, 2160, 1590), b(2200, 1510, 2280, 1590), b(2320, 1510, 2400, 1590), 2560, 1600))
    }

    @Test fun boutons_vides_jamais() {
        assertFalse(OeilAccueil.trioValide(b(0, 0, 0, 0), b(450, 2280, 630, 2400), b(750, 2280, 930, 2400), 1080, 2400))
    }

    @Test fun le_carre_de_l_oeil() {
        // 62 % du petit côté, centré : sur un bouton de 180 × 120, un carré de 74 au centre (540, 2340).
        assertArrayEquals(intArrayOf(503, 2303, 577, 2377), OeilAccueil.carre(b(450, 2280, 630, 2400)))
        // En paysage (bouton en hauteur), même règle.
        assertArrayEquals(intArrayOf(2303, 503, 2377, 577), OeilAccueil.carre(b(2280, 450, 2400, 630)))
    }

    @Test fun nouvelles_mesures_apres_rotation() {
        // Plusieurs mesures, de plus en plus espacées, la dernière quand la barre a fini de bouger (au plus 3 s).
        val d = OeilAccueil.DELAIS_ROTATION
        assertTrue(d.size >= 3)
        assertTrue((1 until d.size).all { d[it] > d[it - 1] })
        assertTrue(d.last() <= 3000L)
    }
}
