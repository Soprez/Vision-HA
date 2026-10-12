// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lot Accueil (point 4) : la grille « Toujours accessibles » de l'écran de blocage revient à la ligne. */
class GrilleAccesTest {
    @Test fun tout_tient_sur_une_rangee() {
        assertEquals(4, GrilleAcces.colonnes(4, 1000, 200))
        assertEquals(1, GrilleAcces.rangees(4, 4))
    }

    @Test fun pas_assez_de_place_on_revient_a_la_ligne_en_rangees_equilibrees() {
        // 6 cases, 4 tiennent : 3 + 3 plutôt que 4 + 2.
        assertEquals(3, GrilleAcces.colonnes(6, 800, 200))
        // 5 cases, 3 tiennent : 3 + 2.
        assertEquals(3, GrilleAcces.colonnes(5, 600, 200))
        // 6 cases, 2 tiennent : 3 rangées de 2.
        assertEquals(2, GrilleAcces.colonnes(6, 450, 200))
        assertEquals(3, GrilleAcces.rangees(6, 2))
    }

    @Test fun jamais_plus_large_que_la_place() {
        for (n in 1..6) for (dispo in listOf(150, 320, 480, 720, 1080, 1920)) for (case in listOf(76, 110, 160, 240)) {
            val c = GrilleAcces.colonnes(n, dispo, case)
            assertTrue("n=$n dispo=$dispo case=$case → $c", c in 1..n)
            // Plusieurs colonnes seulement si elles tiennent toutes.
            if (c > 1) assertTrue("n=$n dispo=$dispo case=$case → $c colonnes débordent", c * case <= dispo)
            // Toutes les cases sont placées, et aucune rangée n'est vide.
            val r = GrilleAcces.rangees(n, c)
            assertTrue(r * c >= n && (r - 1) * c < n)
        }
    }

    @Test fun case_plus_large_que_l_ecran_une_seule_colonne() {
        assertEquals(1, GrilleAcces.colonnes(6, 100, 300))
        assertEquals(0, GrilleAcces.colonnes(0, 1000, 100))
        // Largeur de case nulle (mesure ratée) : pas de division par zéro, tout sur une rangée.
        assertEquals(3, GrilleAcces.colonnes(3, 1000, 0))
    }
}
