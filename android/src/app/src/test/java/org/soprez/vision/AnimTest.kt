// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lot Accueil (point 10) : la logique pure des animations des messages (Anim.kt). */
class AnimTest {
    @Test fun durees_dans_la_fourchette_et_sortie_plus_courte() {
        assertTrue(Anim.DUREE_ENTREE_MS in 200L..300L)
        assertTrue(Anim.DUREE_SORTIE_MS in 150L..180L)
        assertTrue(Anim.DUREE_SORTIE_MS < Anim.DUREE_ENTREE_MS)
        assertEquals(16f, Anim.GLISSEMENT_DP, 0f)
        assertEquals(0.96f, Anim.ECHELLE_DEPART, 0f)
    }

    @Test fun animations_supprimees_duree_nulle() {
        assertFalse(Anim.actifSelon(0f, true))
        assertFalse(Anim.actifSelon(1f, false))
        assertTrue(Anim.actifSelon(1f, true))
        assertTrue(Anim.actifSelon(0.5f, true))
        assertEquals(0L, Anim.duree(Anim.DUREE_ENTREE_MS, Anim.actifSelon(0f, true)))
        assertEquals(0L, Anim.duree(Anim.DUREE_SORTIE_MS, false))
        assertEquals(Anim.DUREE_ENTREE_MS, Anim.duree(Anim.DUREE_ENTREE_MS, true))
    }

    @Test fun courbes_entree_et_sortie_ease_out() {
        val e = Anim.COURBE_ENTREE; val s = Anim.COURBE_SORTIE
        assertTrue(e.contentEquals(floatArrayOf(0f, 0f, 0.2f, 1f)))
        assertEquals(0f, Anim.courbe(e, 0f), 1e-4f); assertEquals(1f, Anim.courbe(e, 1f), 1e-4f)
        // Entrée : rapide au début, ralentit à l'arrivée (au-dessus de la diagonale).
        for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) assertTrue("entrée à $x", Anim.courbe(e, x) > x)
        // 1.93.05 (règles d'Emil Kowalski) : la sortie aussi part vite et se pose (au-dessus de la diagonale) — jamais d'ease-in.
        for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) assertTrue("sortie à $x", Anim.courbe(s, x) > x)
        assertEquals(0f, Anim.courbe(s, 0f), 1e-4f); assertEquals(1f, Anim.courbe(s, 1f), 1e-4f)
        // Monotone : pas de rebond.
        var avant = 0f
        for (i in 1..100) { val v = Anim.courbe(e, i / 100f); assertTrue(v >= avant - 1e-4f); avant = v }
    }

    @Test fun sens_selon_la_position() {
        assertEquals(Anim.Sens.HAUT, Anim.sensPourPosition("haut", false))
        assertEquals(Anim.Sens.HAUT, Anim.sensPourPosition("haut_gauche", false))
        assertEquals(Anim.Sens.BAS, Anim.sensPourPosition("bas_droite", false))
        assertEquals(Anim.Sens.GAUCHE, Anim.sensPourPosition("gauche", false))
        assertEquals(Anim.Sens.DROITE, Anim.sensPourPosition("droite", false))
        assertEquals(Anim.Sens.CENTRE, Anim.sensPourPosition("centre", false))
        assertEquals(Anim.Sens.CENTRE, Anim.sensPourPosition("", false))
        // Plein écran : toujours centré, quelle que soit la position demandée.
        assertEquals(Anim.Sens.CENTRE, Anim.sensPourPosition("haut", true))
    }

    @Test fun depart_glisse_depuis_le_bon_bord() {
        val g = 40f
        assertEquals(Anim.Depart(0f, -g, 0.96f, 0f), Anim.depart(Anim.Sens.HAUT, g))  // bandeau du haut : descend
        assertEquals(Anim.Depart(0f, g, 0.96f, 0f), Anim.depart(Anim.Sens.BAS, g))    // du bas : monte
        assertEquals(Anim.Depart(-g, 0f, 0.96f, 0f), Anim.depart(Anim.Sens.GAUCHE, g))
        assertEquals(Anim.Depart(g, 0f, 0.96f, 0f), Anim.depart(Anim.Sens.DROITE, g))
        assertEquals(Anim.Depart(0f, g, 0.96f, 0f), Anim.depart(Anim.Sens.CENTRE, g))
        assertEquals(Anim.Depart(0f, 0f, 0.96f, 0f), Anim.depart(Anim.Sens.SUR_PLACE, g))
        for (s in Anim.Sens.values()) assertEquals(0f, Anim.depart(s, g).alpha, 0f)
    }

    /** Une seule famille : les écrans de message passent par Anim, et Anim n'anime que alpha, translation, échelle. */
    @Test fun ecrans_de_message_passent_par_anim() {
        val racine = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
        for (f in listOf("MessageActivity.kt", "MessagesRiches.kt", "Bulle.kt"))
            assertTrue("$f n'utilise pas Anim", File(racine, f).readText().contains("Anim.entrer("))
        val anim = File(racine, "Anim.kt").readText()
        assertTrue(anim.contains("withLayer()"))
        for (interdit in listOf(".translationZ(", ".z(", ".rotation(", "requestLayout", "setElevation"))
            assertFalse("Anim anime $interdit", anim.contains(interdit))
    }
}
