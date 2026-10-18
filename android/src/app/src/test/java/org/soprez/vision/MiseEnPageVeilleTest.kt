// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Veille-lot : grille au pixel, dérive anti-marquage, cadence, direct des caméras. */
class MiseEnPageVeilleTest {
    private fun verifier(debut: Int, longueur: Int, poids: IntArray, ecart: Int) {
        val r = GrilleVeille.repartir(debut, longueur, poids, ecart)
        val n = poids.size
        assertEquals(debut, r[0])
        // Fin exacte : dernière part + son début = debut + longueur.
        assertEquals(debut + longueur, r[2 * (n - 1)] + r[2 * (n - 1) + 1])
        // Espacements tous égaux.
        for (i in 1 until n) assertEquals(ecart, r[2 * i] - (r[2 * (i - 1)] + r[2 * (i - 1) + 1]))
    }

    @Test fun grille_au_pixel_1080p_et_4k() {
        for (largeur in listOf(1920, 3840, 1280, 1366, 1000, 999)) {
            for (n in 1..5) verifier(96, largeur - 192, IntArray(n) { 1 }, 24)
            verifier(0, largeur, intArrayOf(6, 3, 3), 17)
            verifier(0, largeur, intArrayOf(4, 4, 4), 8)
            verifier(0, largeur, intArrayOf(12), 8)
        }
    }

    @Test fun parts_egales_a_un_pixel_pres() {
        val r = GrilleVeille.repartir(0, 1001, intArrayOf(1, 1, 1), 10)
        val l = listOf(r[1], r[3], r[5])
        assertTrue(l.max() - l.min() <= 1)
        // Les proportions sont tenues (6/12 = le double de 3/12, au pixel près).
        val p = GrilleVeille.repartir(0, 1236, intArrayOf(6, 3, 3), 12)
        assertTrue(abs(p[1] - 2 * p[3]) <= 2)
    }

    @Test fun derive_lente_entiere_et_bornee() {
        val a = DeriveVeille.amplitude(2f)
        assertEquals(6, a)
        assertEquals(2, DeriveVeille.amplitude(0.5f))
        assertEquals(8, DeriveVeille.amplitude(4f))
        val d = IntArray(2); val avant = IntArray(2)
        DeriveVeille.decalage(0, a, avant)
        var pas = 0
        // Une heure, seconde par seconde : jamais plus d'un pixel d'un coup, jamais hors de l'amplitude.
        for (s in 1..3600) {
            DeriveVeille.decalage(s * 1000L, a, d)
            assertTrue(abs(d[0]) <= a && abs(d[1]) <= a)
            assertTrue(abs(d[0] - avant[0]) <= 1 && abs(d[1] - avant[1]) <= 1)
            if (d[0] != avant[0] || d[1] != avant[1]) pas++
            avant[0] = d[0]; avant[1] = d[1]
        }
        // Imperceptible : moins d'un pas toutes les dix secondes en moyenne, mais ça bouge.
        assertTrue("pas = $pas", pas in 10..360)
    }

    @Test fun cadence_60_pendant_les_transitions() {
        assertEquals(CadenceVeille.FLUIDE_MS, CadenceVeille.intervalle(100, 14_000, 900, 1500, false))
        assertEquals(CadenceVeille.REPOS_MS, CadenceVeille.intervalle(5_000, 9_000, 900, 1500, false))
        assertEquals(CadenceVeille.FLUIDE_MS, CadenceVeille.intervalle(14_500, 500, 900, 1500, false))
        assertEquals(CadenceVeille.REPOS_MS, CadenceVeille.intervalle(100, 500, 900, 1500, true))
    }

    @Test fun suivi_de_la_peine() {
        val s = SuiviPeine(fenetre = 30, tenueMs = 60_000)
        var t = 0L
        repeat(60) { t += 16; s.mesurer(16, 16, t) }
        assertFalse(s.peine(t))
        // Une vue cachée (intervalle énorme) n'est pas une mesure.
        s.mesurer(5_000, 16, t); assertFalse(s.peine(t))
        repeat(30) { k -> t += 60; s.mesurer(if (k % 2 == 0) 60 else 16, 16, t) }
        assertTrue(s.peine(t))
        assertTrue(s.peine(t + 30_000))
        assertFalse(s.peine(t + 61_000))
    }

    @Test fun une_seule_camera_en_direct() {
        assertEquals("camera.jardin_sub", DirectVeille.choisir(DirectVeille.AUTO, listOf("", "camera.jardin_sub", "camera.rue_sub"), false, 0))
        assertNull(DirectVeille.choisir(DirectVeille.AUTO, listOf("", ""), false, 0))
        assertNull(DirectVeille.choisir(DirectVeille.IMAGES, listOf("camera.jardin_sub"), false, 0))
        assertNull(DirectVeille.choisir(DirectVeille.AUTO, listOf("camera.jardin_sub"), true, 0))
        assertNull(DirectVeille.choisir(DirectVeille.AUTO, listOf("camera.jardin_sub"), false, 2))
    }
}
