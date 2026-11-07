// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.94 : « Accorder du temps » (puces, défaut 30 min, durée libre bornée) et le sélecteur − / +. */
class TempsSelecteurTest {
    @Test fun le_depart_est_30_minutes_et_figure_parmi_les_puces() {
        assertEquals(30, Temps.depart())
        assertTrue(Temps.depart() in Durees.REPONSE)
    }

    @Test fun la_duree_libre_reste_dans_les_bornes_et_au_pas() {
        assertEquals(5, Temps.libreValide(0))
        assertEquals(5, Temps.libreValide(-40))
        assertEquals(720, Temps.libreValide(10_000))
        assertEquals(30, Temps.libreValide(30))
        assertEquals(35, Temps.libreValide(33))
        assertEquals(30, Temps.libreValide(32))
        assertEquals(60, Temps.libreValide(Durees.ACCES_TEMPORAIRE))
    }

    @Test fun les_bornes_couvrent_les_puces_et_les_durees_de_demande() {
        for (m in Durees.REPONSE + Durees.DEMANDE) assertTrue("$m min hors des bornes", m in Temps.MIN..Temps.MAX)
        assertEquals(0, Temps.MIN % Temps.PAS); assertEquals(0, Temps.MAX % Temps.PAS)
    }

    @Test fun le_selecteur_s_arrete_aux_bornes() {
        assertEquals(35, SelecteurPas.suivant(30, Temps.MIN, Temps.MAX, Temps.PAS, false, +1))
        assertEquals(25, SelecteurPas.suivant(30, Temps.MIN, Temps.MAX, Temps.PAS, false, -1))
        assertEquals(Temps.MIN, SelecteurPas.suivant(Temps.MIN, Temps.MIN, Temps.MAX, Temps.PAS, false, -1))
        assertEquals(Temps.MAX, SelecteurPas.suivant(Temps.MAX, Temps.MIN, Temps.MAX, Temps.PAS, false, +1))
    }

    @Test fun le_selecteur_cyclique_boucle_pour_les_heures() {
        assertEquals(0, SelecteurPas.suivant(23, 0, 23, 1, true, +1))
        assertEquals(23, SelecteurPas.suivant(0, 0, 23, 1, true, -1))
        assertEquals(0, SelecteurPas.suivant(59, 0, 59, 1, true, +1))
        assertEquals(8, SelecteurPas.suivant(7, 0, 23, 1, true, +1))
    }
}
