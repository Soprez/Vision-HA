package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soprez.vision.OeilModele.Genre
import org.soprez.vision.OeilModele.Variante

/** L'œil Vision : poses bornées, réactions qui se referment proprement, œil joyeux économe une fois posé. */
class OeilModeleTest {
    private val instants = (0..60).map { it / 60f }

    @Test fun posesBornees() {
        for (v in Variante.values()) for (g in listOf(Genre.SUCCES, Genre.ERREUR, Genre.INFO)) for (t in instants) {
            val p = OeilModele.reaction(v, g, t)
            assertTrue("$v $g $t ouverture", p.ouverture in 0f..1f)
            assertTrue("$v $g $t plissé", p.plisse in 0f..1f)
            assertTrue("$v $g $t symbole", p.symbole in 0f..1f)
            assertTrue("$v $g $t halo", p.halo in 0f..1f)
            assertTrue("$v $g $t décalage", kotlin.math.abs(p.dx) <= 0.06f && kotlin.math.abs(p.dy) <= 0.06f)
        }
    }

    @Test fun reactionSeReferme() {
        for (v in Variante.values()) for (g in listOf(Genre.SUCCES, Genre.ERREUR, Genre.INFO)) {
            val fin = OeilModele.reaction(v, g, 1f)
            assertEquals("$v $g : l'œil se rouvre", 1f, fin.ouverture, 0.02f)
            assertEquals("$v $g : plus de symbole", 0f, fin.symbole, 0.02f)
            assertEquals("$v $g : plus de halo", 0f, fin.halo, 0.02f)
        }
    }

    @Test fun bLeSymboleSeLitAuMilieu() {
        for (g in listOf(Genre.SUCCES, Genre.ERREUR, Genre.INFO)) assertEquals(1f, OeilModele.reaction(Variante.B, g, 0.6f).symbole, 0.01f)
    }

    @Test fun sansAnimationLeSymboleResteLisible() {
        assertEquals(1f, OeilModele.figee(Variante.B).symbole, 0f)
        assertEquals(1f, OeilModele.figee(Variante.B).ouverture, 0f)
    }

    @Test fun joyeuxClinDOeilPuisPose() {
        assertTrue("clin d'œil à l'apparition", OeilModele.joyeux(0.45f).ouverture < 0.2f)
        assertTrue("sourire des yeux", (0..40).any { OeilModele.joyeux(4.6f + it * 0.025f).plisse > 0.5f })
        // Après la respiration : immobile hors clignement et sourire (pas de dessin inutile).
        assertFalse(OeilModele.bouge(OeilModele.RESPIRE_S + 1f))
        assertTrue(OeilModele.bouge(OeilModele.RESPIRE_S + 3.4f + 6f - (OeilModele.RESPIRE_S % 6f)))
    }
}
