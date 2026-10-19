// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * 1.93.06 (retour d'essai) : le menu du bas du téléphone (le tiroir de toutes les applis, sous la barre du bas) s'ouvre
 * - en glissant vers le haut n'importe où sur la barre du bas (dock, poignée, et leurs bords), jusqu'à la limite des
 *   gestes du système (la barre s'arrête au-dessus : marges de Bords) ;
 * - par un appui sur la poignée, plus visible ;
 * - **dès que le geste dépasse un seuil de vitesse ([VITESSE_DP_MS], 0,11 dp/ms) ou de distance ([DISTANCE_DP])**.
 *   Avant : 450 px/s (environ 0,17 dp/ms sur un Pixel), sinon au-delà de 30 % de la course (environ 230 dp).
 *
 * La règle est la même pour refermer (vers le bas, depuis la feuille). Pur, testé en JVM (GesteMenuBasTest).
 */
object GesteMenuBas {
    /** Vitesse du doigt au-delà de laquelle le geste décide, quelle que soit la distance (dp par milliseconde). */
    const val VITESSE_DP_MS = 0.11f
    /** Distance parcourue au-delà de laquelle le geste lent ouvre (ou referme) quand même. */
    const val DISTANCE_DP = 48f

    /**
     * À la fin du geste : le menu finit-il ouvert ?
     * [vitesseDpMs] : vitesse verticale du doigt (négative vers le haut) ; [distanceDp] : chemin parcouru vers le haut
     * (négatif vers le bas) depuis le début du geste ; [ouvrant] : le geste part du menu fermé (sinon de la feuille ouverte).
     */
    fun finitOuvert(vitesseDpMs: Float, distanceDp: Float, ouvrant: Boolean): Boolean = when {
        vitesseDpMs < -VITESSE_DP_MS -> true            // lancé vers le haut
        vitesseDpMs > VITESSE_DP_MS -> false            // lancé vers le bas
        ouvrant -> distanceDp >= DISTANCE_DP            // lent : assez loin vers le haut pour ouvrir
        else -> distanceDp > -DISTANCE_DP               // lent depuis la feuille : reste ouverte sauf assez loin vers le bas
    }

    /** La durée de la fin du mouvement (ms), ease-out : courte si le doigt allait vite, jamais plus de 280 ms. Pur. */
    fun duree(resteFraction: Float, coursePx: Float, vitessePxS: Float): Long {
        val v = Math.abs(vitessePxS)
        return if (v > 300f) (resteFraction * coursePx / v * 1000f * 1.6f).toLong().coerceIn(140L, 280L)
        else (160 + 120 * resteFraction).toLong()
    }
}
