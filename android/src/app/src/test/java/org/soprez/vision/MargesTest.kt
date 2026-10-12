package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les marges de l'écran de blocage : zone sûre de 5 % sur télé, contenu limité à 560 dp sur grand écran. */
class MargesTest {
    @Test fun telephone_marge_minimale() {
        val m = Marges.calculer(1080, 2400, 2.625f, tele = false)
        assertEquals(Math.round(28 * 2.625f), m.cote)
        assertEquals(0, m.zoneCote); assertEquals(0, m.zoneHaut)
    }

    @Test fun tablette_contenu_centre_et_borne() {
        val m = Marges.calculer(1600, 2560, 2f, tele = false)   // 800 dp de large
        assertEquals((1600 - 1120) / 2, m.cote)                  // 560 dp de contenu, centré
    }

    @Test fun telephone_paysage_borne_aussi() {
        val m = Marges.calculer(2400, 1080, 2.625f, tele = false)
        assertTrue(2400 - 2 * m.cote <= Math.round(560 * 2.625f) + 1)
    }

    @Test fun tele_zone_sure_cinq_pour_cent() {
        val m = Marges.calculer(3840, 2160, 4f, tele = true)
        assertEquals(192, m.zoneCote); assertEquals(108, m.zoneHaut)
        assertTrue(m.cote >= Math.round(16 * 4f))
    }
}
