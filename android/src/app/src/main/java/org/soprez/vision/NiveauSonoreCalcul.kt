// SPDX-License-Identifier: MIT
package org.soprez.vision

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Niveau sonore (backlog n° 31) : le calcul, sans Android (essayé en JUnit, NiveauSonoreTest).
 *
 * - **Niveau** : la moyenne quadratique (RMS) d'une tranche d'échantillons 16 bits, en dB relatifs de 0 (silence
 *   numérique) à 100 (pleine échelle du micro), plus la calibration réglée dans Home Assistant (micros différents).
 * - **Dépassement** : au-dessus du seuil pendant toute la « sensibilité » (un pic plus court ne compte pas) ; un seul
 *   par passage au-dessus : il faut redescendre de [HYSTERESIS] dB sous le seuil avant le suivant.
 * - **Plages** : vide = toujours ; une plage peut passer minuit (22:00 → 07:00).
 * - **Minute glissante** : la moyenne (en énergie) et le maximum de la dernière minute, pour le relevé.
 * Rien n'est gardé des échantillons : chaque tranche devient un nombre, puis est oubliée.
 */
object NiveauSonoreCalcul {
    const val HYSTERESIS = 3f
    private const val PLEINE_ECHELLE = 32768.0

    /** Le niveau d'une tranche (dB relatifs 0–100, calibration comprise). */
    fun niveau(echantillons: ShortArray, n: Int = echantillons.size, calibration: Int = 0): Float {
        if (n <= 0) return 0f
        var somme = 0.0
        for (i in 0 until n) { val v = echantillons[i].toDouble(); somme += v * v }
        return niveauRms(sqrt(somme / n), calibration)
    }

    fun niveauRms(rms: Double, calibration: Int = 0): Float {
        if (rms <= 0.0) return 0f
        val dbfs = 20.0 * log10(rms / PLEINE_ECHELLE)
        return (dbfs + 100.0 + calibration).coerceIn(0.0, 100.0).toFloat()
    }

    data class Reglage(val actif: Boolean, val seuil: Int, val sensibiliteS: Int, val plages: List<Pair<Int, Int>>,
                       val calibration: Int, val alertes: Int, val alertesAvantCoupure: Int) {
        companion object { val ETEINT = Reglage(false, 75, 5, emptyList(), 0, 0, 3) }
    }

    /** "HH:MM" → minutes depuis minuit ; null si illisible. */
    fun minutes(hhmm: String): Int? {
        val p = hhmm.split(":")
        if (p.size != 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        val v = h * 60 + m
        return if (h in 0..24 && m in 0..59 && v <= 24 * 60) v else null
    }

    fun dansPlages(plages: List<Pair<Int, Int>>, minuteDuJour: Int): Boolean {
        if (plages.isEmpty()) return true
        return plages.any { (d, f) -> d == f || (d < f && minuteDuJour in d until f) || (d > f && (minuteDuJour >= d || minuteDuJour < f)) }
    }

    /** Le détecteur de dépassements (un par passage au-dessus du seuil, après la sensibilité). */
    class Detecteur(private val seuil: Float, private val sensibiliteMs: Long) {
        private var auDessusDepuis = -1L
        private var signale = false

        /** Une mesure à l'instant [t] (ms) ; rend true au moment où un dépassement est constaté. */
        fun pousser(db: Float, t: Long): Boolean {
            if (db >= seuil) {
                if (auDessusDepuis < 0) auDessusDepuis = t
                if (!signale && t - auDessusDepuis >= sensibiliteMs) { signale = true; return true }
            } else {
                auDessusDepuis = -1L
                if (db < seuil - HYSTERESIS) signale = false
            }
            return false
        }
    }

    /** La dernière minute : moyenne en énergie (pas la moyenne des dB) et maximum, sur des tranches à heure fixe. */
    class Minute(private val tranches: Int = 600) {
        private val energies = DoubleArray(tranches)
        private val niveaux = FloatArray(tranches)
        private var n = 0
        private var i = 0

        fun pousser(db: Float) {
            energies[i] = Math.pow(10.0, db / 10.0); niveaux[i] = db
            i = (i + 1) % tranches; if (n < tranches) n++
        }

        fun moyenne(): Float? = if (n == 0) null else (10.0 * log10((0 until n).sumOf { energies[it] } / n)).toFloat()
        fun maximum(): Float? = if (n == 0) null else (0 until n).maxOf { niveaux[it] }
        fun vider() { n = 0; i = 0 }
    }

    fun arrondi(v: Float): Double = Math.round(v * 10.0) / 10.0
}
