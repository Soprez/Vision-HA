// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Veille sur télé : économiseur du système, et repli de Vision sur son accueil. */
class ReglesVeilleTest {
    @Test fun lecture_de_screensaver_components() {
        assertEquals(true, ReglesVeille.economiseurEstVision("org.soprez.vision/.VeilleService"))
        assertEquals(true, ReglesVeille.economiseurEstVision("com.google.android.backdrop/.Backdrop, org.soprez.vision/org.soprez.vision.VeilleService"))
        assertEquals(false, ReglesVeille.economiseurEstVision("com.google.android.backdrop/.Backdrop"))
        assertEquals(false, ReglesVeille.economiseurEstVision(null))
        assertEquals(false, ReglesVeille.economiseurEstVision("${Migration.ANCIEN}/.VeilleService"))
        assertNull(ReglesVeille.economiseurEstVision("x", lisible = false))
    }

    @Test fun delai_sur_l_accueil() {
        assertEquals(5, ReglesVeille.delaiAccueil(5, true, 10))     // délai choisi : il l'emporte
        assertEquals(0, ReglesVeille.delaiAccueil(0, true, 10))     // économiseur = Vision : le système s'en charge
        assertEquals(10, ReglesVeille.delaiAccueil(0, false, 10))   // repli
        assertEquals(10, ReglesVeille.delaiAccueil(0, null, 10))    // illisible : repli
        assertEquals(0, ReglesVeille.delaiAccueil(0, false, 0))     // repli « jamais »
    }

    @Test fun reglage_qui_tourne() {
        var v = ReglesVeille.REPLI_DEFAUT
        val vus = HashSet<Int>()
        repeat(ReglesVeille.DELAIS_REPLI.size) { vus.add(v); v = ReglesVeille.repliSuivant(v) }
        assertEquals(ReglesVeille.DELAIS_REPLI.toSet(), vus)
        assertEquals("Jamais", ReglesVeille.libelleRepli(0))
    }
}
