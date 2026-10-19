// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.view.Choreographer

/**
 * 1.93.06 (point 2, 2e vidéo de la TCL, 18/10 à 22:35) : la montée d'une ligne de la file, visible même sur une télé lente.
 *
 * Réglée sur le temps seul (320 ms), la montée tenait en 2 ou 3 images sur la TCL : chaque image y prend 100 à 300 ms
 * (écran du lecteur, fond animé), et la liste semblait sauter d'un coup. Ici l'avancée est la plus petite entre le temps
 * écoulé ([DUREE_MS]) et [PAS_MIN] pas par image : sur un appareil rapide, 320 ms fluides ; sur une télé lente, au
 * moins [PAS_MIN] pas, chacun dessiné (la montée dure alors un peu plus). [reste] est ce qui reste à monter (px).
 */
class MonteeFile(
    val ecart: Int,
    private val surPas: (reste: Int) -> Unit,
    private val surFin: () -> Unit,
    private val horloge: () -> Long = { android.os.SystemClock.uptimeMillis() },
) : Choreographer.FrameCallback {
    companion object {
        const val DUREE_MS = 320L
        const val PAS_MIN = 8

        /** Pur : l'avancée (0 → 1) après [ecouleMs] et [pas] images dessinées. */
        fun avancee(ecouleMs: Long, pas: Int): Double =
            minOf(1.0, ecouleMs.toDouble() / DUREE_MS, pas.toDouble() / PAS_MIN).coerceAtLeast(0.0)

        /** Pur : ce qui reste à monter pour une avancée [a] (ralenti à l'arrivée, en douceur : quadratique). TCL : avec un
         *  ralenti plus fort, le premier pas faisait 60 % du chemin. */
        fun reste(ecart: Int, a: Double): Int {
            if (a <= 0.0) return ecart
            if (a >= 1.0) return 0
            return Math.round(ecart * (1.0 - a) * (1.0 - a)).toInt()
        }
    }

    var pas = 0; private set
    var enCours = false; private set
    private var debut = 0L
    private var a = 0.0

    fun reste(): Int = reste(ecart, a)

    fun demarrer() {
        enCours = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!enCours) return
        val maintenant = horloge()
        if (pas == 0) debut = maintenant
        pas++
        a = avancee(maintenant - debut, pas)
        surPas(reste())
        if (a >= 1.0) { enCours = false; surFin() } else Choreographer.getInstance().postFrameCallback(this)
    }

    /** Arrêtée (un doigt, un autre titre) : [finir] dit si la position d'arrivée doit être retenue. */
    fun annuler(finir: Boolean = true) {
        if (!enCours) return
        enCours = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (finir) surFin()
    }
}
