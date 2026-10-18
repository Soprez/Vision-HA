// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.93.05 (l'auteur, règles d'Emil Kowalski) : le bandeau musique et le QR de la veille entrent en fondu (180 ms) et sortent
 * plus vite (120 ms), en « ease-out » (rapide au départ), et un fondu interrompu repart de là où il en est.
 */
class FonduCoinsTest {
    @Test fun entree_180_ms_sortie_120_ms_ease_out_sans_saut() {
        val f = CoinsVeille.Fondu()
        assertEquals(0f, f.pas(true, 1_000), 1e-4f)
        val mi = f.pas(true, 1_090)
        assertTrue("ease-out : plus de la moitié à mi-parcours ($mi)", mi > 0.5f && mi < 1f)
        assertEquals(1f, f.pas(true, 1_180), 1e-4f)
        assertTrue(!f.enCours())
        // Sortie : 120 ms, plus courte que l'entrée.
        assertTrue(f.pas(false, 2_000) == 1f)
        assertTrue(f.pas(false, 2_060) < 0.5f)
        assertEquals(0f, f.pas(false, 2_120), 1e-4f)
        // Interrompu : repart de la valeur atteinte, jamais de saut à 0 ou 1.
        val g = CoinsVeille.Fondu()
        g.pas(true, 0); val a = g.pas(true, 60)
        val b = g.pas(false, 60)
        assertEquals(a, b, 1e-4f)
        assertTrue(g.pas(false, 90) < b)
    }
}
