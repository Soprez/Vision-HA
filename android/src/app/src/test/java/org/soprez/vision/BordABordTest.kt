// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.91.0 (photos de l'auteur : icônes de la rangée « Applications » coupées net au bord de la marge) : une rangée télé
 * bord à bord — elle sort de sa colonne jusqu'aux bords de l'écran, son premier élément reste sur la zone sûre.
 */
class BordABordTest {
    @Test fun laRangeeSortDeSaColonneEtGardeLaZoneSure() {
        // Télé 1080p : zone sûre de 5 % (96 px) de chaque côté, petite marge d'anneau de 12 px.
        val v = BordABord.calculer(96, 96, 12)
        assertEquals(96, v.sortieGauche); assertEquals(96, v.sortieDroite)
        // Au repos, le premier élément commence sur la zone sûre (+ la marge d'anneau), comme avant : rien ne bouge.
        assertEquals(108, v.debut); assertEquals(108, v.fin)
        // Le fondu couvre exactement l'overscan.
        assertEquals(96, v.fondu)
    }

    @Test fun uneRangeeApresUneEtiquetteNeSortQueParLaDroite() {
        val v = BordABord.calculer(115, 115, 6, gauche = false)
        assertEquals(0, v.sortieGauche); assertEquals(115, v.sortieDroite)
        assertEquals(6, v.debut); assertEquals(121, v.fin)
        assertEquals(115, v.fondu)
    }

    @Test fun lesMargesDeLaTeleSuiventLaZoneSure() {
        // La zone sûre est celle des autres écrans télé (Marges) : 5 % de la largeur.
        val m = Marges.calculer(1920, 1080, 2f, tele = true)
        assertEquals(Math.round(1920 * Marges.ZONE_SURE), m.zoneCote)
        val v = BordABord.calculer(m.zoneCote, m.zoneCote, 0)
        assertEquals(m.zoneCote, v.debut)
    }
}
