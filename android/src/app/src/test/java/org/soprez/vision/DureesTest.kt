// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lot 6 (B10 / APP-08) : une seule série de durées à demander, une seule série de réponses, partout. */
class DureesTest {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(nom: String) = File(src, nom).readText()

    @Test fun series() {
        assertEquals(listOf(15, 30, 60), Durees.DEMANDE)
        assertEquals(listOf(15, 30, 60, 120), Durees.REPONSE)
        // Tout ce qu'on peut demander, on peut l'accorder tel quel.
        assertTrue(Durees.REPONSE.containsAll(Durees.DEMANDE))
        assertEquals(listOf("15 min", "30 min", "1 h"), Durees.libelles(Durees.DEMANDE))
    }

    @Test fun reponses() {
        assertEquals(15, Durees.accordee(15))
        assertEquals(Durees.PAR_DEFAUT, Durees.accordee(0))
        assertEquals(listOf("Accorder 15 min", "Autre durée…", "Refuser"), Durees.reponsesTemps(15))
        assertEquals(listOf("Accorder 30 min", "Autre durée…", "Refuser"), Durees.reponsesTemps(0))
        assertEquals(listOf("1 h", "Autoriser", "Refuser"), Durees.reponsesAcces())
    }

    /** Plus de liste de durées écrite à la main dans les écrans qui demandent ou qui répondent. */
    @Test fun ecrans_branches_sur_durees() {
        for (f in listOf("BlockActivity.kt", "LanceurActivity.kt", "SetupActivity.kt", "DeverrouillageActivity.kt")) {
            val t = lire(f)
            assertTrue(f, t.contains("Durees.DEMANDE"))
            assertFalse(f, t.contains("listOf(15, 30, 60)"))
        }
        for (f in listOf("Temps.kt", "ParentsActivity.kt")) assertTrue(f, lire(f).contains("Durees.REPONSE"))
        for (f in listOf("Messages.kt", "MessageActivity.kt", "ParentsActivity.kt")) {
            val t = lire(f)
            assertTrue(f, t.contains("Durees.reponsesTemps") && t.contains("Durees.reponsesAcces"))
            assertFalse(f, t.contains("listOf(30 to Tr.duree(30)"))
        }
    }
}
