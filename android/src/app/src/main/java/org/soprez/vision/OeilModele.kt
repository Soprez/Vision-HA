// SPDX-License-Identifier: MIT
package org.soprez.vision

import kotlin.math.PI
import kotlin.math.sin

/**
 * L'œil Vision animé, sans Android (refonte UX, zone du haut et réactions « OK ») : une pose par instant.
 * La vue (OeilVue) ne fait que dessiner la pose ; tout le mouvement est ici, essayé en JVM.
 *
 * Base : l'œil de soprez.org (halo qui respire, clignement, regard qui erre). Ajouts : le plissé joyeux (la paupière
 * du bas remonte en arc, forme « ^ »), le clin d'œil d'apparition, et trois réactions « OK » à soumettre à l'auteur :
 * A (hochement), B (clin d'œil puis symbole), C (sourire et onde). Les maquettes GIF sortent du même modèle.
 */
object OeilModele {
    enum class Genre { SUCCES, ERREUR, INFO, JOIE }
    enum class Variante { A, B, C }

    /**
     * ouverture 0 (fermé) → 1 ; plisse 0 → 1 (yeux rieurs) ; gx, gy : le regard (−1 → 1) ; dx, dy : décalage de l'œil
     * (fraction de sa taille) ; echelle ; halo 0 → 1 (couleur du genre) ; symbole 0 → 1 (✓, ✗, i tracé) ; anneau 0 → 1
     * (onde qui part de l'œil).
     */
    data class Pose(val ouverture: Float = 1f, val plisse: Float = 0f, val gx: Float = 0f, val gy: Float = 0f,
                    val dx: Float = 0f, val dy: Float = 0f, val echelle: Float = 1f, val halo: Float = 0f,
                    val symbole: Float = 0f, val anneau: Float = 0f)

    const val DUREE_REACTION_MS = 750L
    /** Sans animation (réglage du système) : le symbole fixe, le temps de le lire. */
    const val DUREE_FIGEE_MS = 1200L
    const val CYCLE_JOYEUX_S = 6f

    fun lisser(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

    /** 0 → 1 → 0 entre a et b. */
    fun bosse(t: Float, a: Float, b: Float): Float = if (t <= a || t >= b) 0f else sin(PI * (t - a) / (b - a)).toFloat()

    /** La réaction « OK » à l'instant t (0 → 1 sur DUREE_REACTION_MS). */
    fun reaction(v: Variante, g: Genre, t: Float): Pose = when (v) {
        Variante.A -> when (g) {
            Genre.ERREUR -> Pose(ouverture = 1 - 0.3f * bosse(t, 0f, 0.6f), gx = sin(t * PI * 4).toFloat() * (1 - t),
                dx = 0.05f * sin(t * PI * 4).toFloat() * (1 - t), halo = bosse(t, 0f, 1f))
            Genre.INFO -> Pose(gx = 0.3f, gy = -bosse(t, 0.05f, 0.95f), dy = -0.02f * bosse(t, 0.05f, 0.95f), halo = bosse(t, 0f, 1f))
            else -> Pose(ouverture = 1 - 0.2f * bosse(t, 0.05f, 0.5f), plisse = 0.7f * bosse(t, 0.15f, 0.85f),
                dy = -0.05f * bosse(t, 0.05f, 0.28f) - 0.04f * bosse(t, 0.3f, 0.5f), halo = bosse(t, 0f, 1f),
                symbole = if (t < 0.45f) 0f else ((t - 0.45f) / 0.3f).coerceAtMost(1f) * (1 - lisser((t - 0.85f) / 0.15f)))
        }
        Variante.B -> {
            val fondu = if (t > 0.8f) 1 - lisser((t - 0.8f) / 0.2f) else 1f
            Pose(ouverture = 1 - 0.96f * bosse(t, 0f, 0.28f), halo = 0.8f * bosse(t, 0.1f, 1f),
                symbole = (if (t < 0.2f) 0f else ((t - 0.2f) / 0.3f).coerceAtMost(1f)) * fondu)
        }
        Variante.C -> when (g) {
            Genre.ERREUR -> Pose(ouverture = 1 - 0.55f * bosse(t, 0.05f, 0.95f), halo = bosse(t, 0f, 1f), anneau = lisser(t),
                dx = 0.03f * sin(t * PI * 6).toFloat() * (1 - t))
            Genre.INFO -> Pose(gy = -bosse(t, 0.05f, 0.95f), gx = 0.4f, halo = bosse(t, 0f, 1f), anneau = lisser(t))
            else -> Pose(plisse = 0.85f * bosse(t, 0.05f, 0.95f), halo = bosse(t, 0f, 1f), anneau = lisser(t),
                echelle = 1 + 0.06f * bosse(t, 0f, 0.4f))
        }
    }

    /** Sans animation : une seule pose, le symbole lisible (B), ou l'œil posé avec son halo (A, C). */
    fun figee(v: Variante): Pose = if (v == Variante.B) Pose(halo = 0.6f, symbole = 1f) else Pose(halo = 0.6f)

    /**
     * L'œil joyeux de la zone du haut, à s secondes de son apparition : clin d'œil rieur à l'apparition, puis un cycle
     * de 6 s (regard qui erre, un clignement, un sourire des yeux). Après [RESPIRE_S], il ne bouge plus que pour
     * cligner et sourire (le halo se pose) : coût presque nul.
     */
    const val RESPIRE_S = 20f
    fun joyeux(s: Float): Pose {
        if (s < 0.9f) {
            return Pose(ouverture = 1 - 0.95f * bosse(s, 0.25f, 0.65f), plisse = 0.75f * bosse(s, 0.5f, 0.9f),
                echelle = 0.6f + 0.4f * lisser(s / 0.4f), halo = 0.4f)
        }
        val c = s % CYCLE_JOYEUX_S
        val respire = if (s < RESPIRE_S) 0.4f + 0.3f * sin(s * 1.3f) else 0.4f
        return Pose(ouverture = if (c in 3.3f..3.55f) 1 - 0.94f * bosse(c, 3.3f, 3.55f) else 1f,
            plisse = if (c in 4.6f..5.6f) 0.6f * bosse(c, 4.6f, 5.6f) else 0f,
            gx = if (s < RESPIRE_S) 0.6f * sin(s * 0.9f) else 0f, halo = respire)
    }

    /** Vrai si la pose demande un dessin de plus que la précédente (sinon on ne redessine pas : économie). */
    fun bouge(s: Float): Boolean {
        if (s < RESPIRE_S) return true
        val c = s % CYCLE_JOYEUX_S
        return c in 3.25f..3.6f || c in 4.55f..5.65f
    }
}
