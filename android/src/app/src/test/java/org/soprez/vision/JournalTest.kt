// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** « Signaler un problème » : le tampon du journal (circulaire, borné, période, plantage). JVM pur : pas de logcat. */
class JournalTest {
    private val sorties = mutableListOf<String>()
    private var t = 1_000L

    @Before fun avant() {
        Journal.vider(); sorties.clear(); t = 1_000L
        Journal.sortie = { n, tag, texte -> sorties += "$n/$tag/$texte" }
        Journal.horloge = { t++ }
    }

    @After fun apres() {
        Journal.vider()
        Journal.sortie = { _, _, _ -> }
    }

    @Test fun chaque_ligne_va_au_tampon_et_a_logcat() {
        Journal.i("Lecteur", "démarrage 3")
        Journal.w("Lecteur", "tampon vide")
        assertEquals(listOf("I/Lecteur/démarrage 3", "W/Lecteur/tampon vide"), sorties)
        assertEquals(listOf("démarrage 3", "tampon vide"), Journal.periode(0).map { it.texte })
    }

    @Test fun le_tampon_est_circulaire_a_2000_lignes() {
        repeat(2_500) { Journal.d("T", "ligne $it") }
        val l = Journal.periode(0)
        assertEquals(Journal.MAX_LIGNES, l.size)
        assertEquals("ligne 500", l.first().texte)
        assertEquals("ligne 2499", l.last().texte)
    }

    @Test fun le_tampon_est_borne_en_octets() {
        repeat(1_900) { Journal.d("T", "x".repeat(2_000)) }
        val total = Journal.periode(0).sumOf { it.octets }
        assertTrue("$total", total <= Journal.MAX_OCTETS)
        assertTrue(Journal.periode(0).size < 1_900)
    }

    @Test fun la_periode_est_bornee() {
        Journal.i("T", "a"); Journal.i("T", "b"); Journal.i("T", "c")  // ts 1000, 1001, 1002
        assertEquals(listOf("b"), Journal.periode(1_001, 1_001).map { it.texte })
        assertEquals(listOf("b", "c"), Journal.periode(1_001).map { it.texte })
    }

    @Test fun une_erreur_laisse_sa_pile() {
        Journal.e("T", "échec", IllegalStateException("boum"))
        val texte = Journal.periode(0).single().texte
        assertTrue(texte.startsWith("échec : java.lang.IllegalStateException: boum"))
        assertTrue(texte.contains("at "))
    }

    @Test fun le_plantage_d_un_fil_est_journalise_puis_transmis() {
        val vu = mutableListOf<Throwable>()
        val ancien = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> vu += e }
            Journal.surveillerPlantages()
            val f = Thread { throw IllegalArgumentException("plantage d'essai") }
            f.start(); f.join()
            assertEquals(1, vu.size)
            assertTrue(Journal.periode(0).any { it.tag == "Plantage" && "plantage d'essai" in it.texte })
            // Une seconde pose ne double pas la surveillance.
            Journal.surveillerPlantages()
            assertFalse(Thread.getDefaultUncaughtExceptionHandler() == null)
        } finally { Thread.setDefaultUncaughtExceptionHandler(ancien) }
    }
}
