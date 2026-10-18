// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** Banc Musique (2026-10-12) : la position du lecteur Classique après une pause (le lecteur d'Android sautait de +16 s). */
class LecteurSlimPositionTest {
    @Test fun lecteur_android_tant_qu_il_suit_l_horloge() {
        assertEquals(21_400, LecteurSlim.choisirPosition(21_400, 21_601))
    }

    @Test fun horloge_quand_le_lecteur_saute_ou_manque() {
        assertEquals(22_600, LecteurSlim.choisirPosition(38_986, 22_600))  // +16 s après une reprise : l'horloge
        assertEquals(1_500, LecteurSlim.choisirPosition(0, 1_500))          // flux sans taille : l'horloge
        assertEquals(800, LecteurSlim.choisirPosition(800, null))           // pas encore d'horloge : le lecteur
        assertEquals(0, LecteurSlim.choisirPosition(0, null))
    }
}
