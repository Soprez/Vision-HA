// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** Recette visuelle (lot 1) : les noms longs se coupent à leurs séparateurs, jamais au milieu d'un mot. */
class CoupureTest {
    private val z = Coupure.ESPACE_NULLE

    @Test fun un_domaine_long_se_coupe_apres_ses_separateurs() {
        assertEquals("Zugriff auf unterrichtsmaterialien-${z}grundschule.${z}de anfragen",
            Coupure.douce("Zugriff auf unterrichtsmaterialien-grundschule.de anfragen"))
    }

    @Test fun un_nom_de_paquet_se_coupe_a_chaque_point() {
        assertEquals("com.${z}exemple.${z}application.${z}dessin", Coupure.douce("com.exemple.application.dessin"))
    }

    @Test fun les_mots_ordinaires_et_les_noms_courts_ne_changent_pas() {
        for (s in listOf("Einstellungen", "wiki.org", "Jean-Charlie", "Ablaufverfolgung im Hintergrund", "Tout fermer"))
            assertEquals(s, Coupure.douce(s))
    }

    @Test fun idempotent_et_texte_visible_inchange() {
        val une = Coupure.douce("https://unterrichtsmaterialien-grundschule-nordrhein-westfalen.de/arbeitsblaetter")
        assertEquals(une, Coupure.douce(une))
        assertEquals("https://unterrichtsmaterialien-grundschule-nordrhein-westfalen.de/arbeitsblaetter", une.replace(z.toString(), ""))
    }
}
