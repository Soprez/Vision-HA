// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.69.0 : décompte 10 / 5 / 1 min avant une fermeture ; chaque seuil dit une seule fois, le plus urgent d'abord. */
class PreavisTest {
    private val s = listOf(10, 5, 1)

    @Test fun seuils() {
        assertNull(Preavis.seuilAAnnoncer(11 * 60L, s, "a", emptySet()))
        assertEquals(10, Preavis.seuilAAnnoncer(9 * 60L, s, "a", emptySet()))
        assertEquals(5, Preavis.seuilAAnnoncer(4 * 60L, s, "a", emptySet()))           // 10 manqué : on dit 5
        assertNull(Preavis.seuilAAnnoncer(4 * 60L, s, "a", setOf("a|5", "a|10")))
        assertEquals(1, Preavis.seuilAAnnoncer(50L, s, "a", setOf("a|5", "a|10")))
        assertNull(Preavis.seuilAAnnoncer(0L, s, "a", emptySet()))
    }

    /** Lot Accueil : les seuils viennent du réglage de HA ; absents (HA d'avant) = 10 / 5 / 1. */
    @Test fun seuilsDuReleve() {
        assertEquals(listOf(10, 5, 1), Preavis.seuilsDe(null))
        assertEquals(listOf(5, 1), Preavis.seuilsDe(listOf(5, 1)))
        assertEquals(listOf(1), Preavis.seuilsDe(listOf(1)))
        assertEquals(emptyList<Int>(), Preavis.seuilsDe(emptyList()))      // présent mais vide : aucun préavis
        assertEquals(listOf(15, 5, 1), Preavis.seuilsDe(listOf(1, 0, 15, 5, 5)))
    }

    @Test fun delaiReglable() {
        val cinq = Preavis.seuilsDe(listOf(5, 1))
        assertNull(Preavis.seuilAAnnoncer(6 * 60L, cinq, "b", emptySet()))           // 6 min : trop tôt avec X = 5
        assertEquals(5, Preavis.seuilAAnnoncer(5 * 60L, cinq, "b", emptySet()))
        assertEquals(1, Preavis.seuilAAnnoncer(40L, cinq, "b", setOf("b|5")))
        val vingt = Preavis.seuilsDe(listOf(20, 5, 1))
        assertEquals(20, Preavis.seuilAAnnoncer(19 * 60L, vingt, "c", emptySet()))
        assertNull(Preavis.seuilAAnnoncer(60L, emptyList(), "d", emptySet()))
    }

    @Test fun textes() {
        assertEquals("dans 1 minute", Preavis.quand(1))
        assertEquals("Roblox : plus que 5 minutes aujourd'hui.", Preavis.texteAppli(5, "Roblox"))
    }
}
