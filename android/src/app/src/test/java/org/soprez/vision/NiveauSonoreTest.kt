// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Niveau sonore (backlog n° 31) : calcul du niveau sur des signaux de synthèse, détection, plages, minute glissante. */
class NiveauSonoreTest {
    private fun sinus(amplitude: Double, n: Int = 1600) = ShortArray(n) { (amplitude * 32767 * sin(2 * PI * 440 * it / 16000.0)).toInt().toShort() }

    @Test fun silence_et_signaux() {
        assertEquals(0f, NiveauSonoreCalcul.niveau(ShortArray(1600)), 0f)
        // Sinus pleine échelle : RMS = 1/√2 → −3 dBFS → 97 dB relatifs.
        assertEquals(97f, NiveauSonoreCalcul.niveau(sinus(1.0)), 0.3f)
        // Dix fois plus faible : 20 dB de moins.
        assertEquals(77f, NiveauSonoreCalcul.niveau(sinus(0.1)), 0.3f)
        // Bruit pseudo-aléatoire uniforme (±0,5 pleine échelle) : RMS ≈ 0,289 → ≈ 89 dB.
        val r = java.util.Random(7)
        val bruit = ShortArray(16000) { ((r.nextDouble() - 0.5) * 32767).toInt().toShort() }
        assertEquals(89.2f, NiveauSonoreCalcul.niveau(bruit), 0.5f)
        // Calibration et bornes.
        assertEquals(87f, NiveauSonoreCalcul.niveau(sinus(1.0), calibration = -10), 0.3f)
        assertEquals(100f, NiveauSonoreCalcul.niveau(sinus(1.0), calibration = 30), 0f)
        assertEquals(0f, NiveauSonoreCalcul.niveau(ShortArray(0)), 0f)
    }

    @Test fun pic_court_ignore_et_un_seul_depassement_par_passage() {
        val d = NiveauSonoreCalcul.Detecteur(75f, 3000)
        var t = 0L
        fun pendant(ms: Long, db: Float): Int { var n = 0; val fin = t + ms; while (t < fin) { if (d.pousser(db, t)) n++; t += 100 }; return n }
        assertEquals("calme", 0, pendant(5000, 50f))
        assertEquals("pic de 2 s (sensibilité 3 s) : rien", 0, pendant(2000, 90f))
        assertEquals("retour au calme", 0, pendant(1000, 50f))
        assertEquals("4 s au-dessus : un dépassement", 1, pendant(4000, 80f))
        assertEquals("toujours au-dessus : pas de second", 0, pendant(10000, 85f))
        // Redescendre juste sous le seuil (dans l'hystérésis) ne réarme pas…
        assertEquals(0, pendant(500, 73.5f))
        assertEquals(0, pendant(4000, 80f))
        // … redescendre franchement, si.
        assertEquals(0, pendant(500, 60f))
        assertEquals(1, pendant(4000, 80f))
    }

    @Test fun plages() {
        val m = { s: String -> NiveauSonoreCalcul.minutes(s)!! }
        val nuit = listOf(m("22:00") to m("07:00"))
        assertTrue(NiveauSonoreCalcul.dansPlages(nuit, m("23:30")))
        assertTrue(NiveauSonoreCalcul.dansPlages(nuit, m("06:59")))
        assertFalse(NiveauSonoreCalcul.dansPlages(nuit, m("07:00")))
        assertFalse(NiveauSonoreCalcul.dansPlages(listOf(m("08:00") to m("20:00")), m("21:00")))
        assertTrue(NiveauSonoreCalcul.dansPlages(emptyList(), m("12:00")))
        assertNull(NiveauSonoreCalcul.minutes("x"))
        assertNull(NiveauSonoreCalcul.minutes("25:00"))
        assertEquals(24 * 60, NiveauSonoreCalcul.minutes("24:00"))
    }

    @Test fun minute_glissante() {
        val mn = NiveauSonoreCalcul.Minute(tranches = 10)
        assertNull(mn.moyenne())
        repeat(10) { mn.pousser(60f) }
        assertEquals(60f, mn.moyenne()!!, 0.01f)
        // Moyenne en énergie : une tranche à 80 dB à la place d'une des dix à 60 pèse bien plus que la moyenne des dB (62).
        mn.pousser(80f)
        assertEquals(70.37f, mn.moyenne()!!, 0.05f)  // 10·log10((9·10⁶ + 10⁸) / 10)
        assertEquals(80f, mn.maximum()!!, 0f)
        // Les plus anciennes sortent de la fenêtre.
        repeat(10) { mn.pousser(50f) }
        assertEquals(50f, mn.moyenne()!!, 0.01f)
        assertEquals(50f, mn.maximum()!!, 0f)
        assertEquals(61.2, NiveauSonoreCalcul.arrondi(61.24f), 0.0)
    }
}
