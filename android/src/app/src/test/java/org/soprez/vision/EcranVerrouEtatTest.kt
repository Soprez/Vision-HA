// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.79.0 (point 13 bis) : l'état montré sur l'écran verrouillé. */
class EcranVerrouEtatTest {
    private val m = 1_000_000L

    @Test
    fun etats() {
        assertEquals(EcranVerrou.Moment.OUVERT, EcranVerrou.etat(false, false, 0, "", m))
        assertEquals(EcranVerrou.Moment.PAUSE, EcranVerrou.etat(false, true, m + 60_000, "", m))
        assertEquals(EcranVerrou.Moment.TEMPS, EcranVerrou.etat(false, true, 0, "Temps d'écran écoulé pour aujourd'hui", m))
        assertEquals(EcranVerrou.Moment.PLAGE, EcranVerrou.etat(false, true, 0, "Plage « Soir »", m))
        assertEquals(EcranVerrou.Moment.HORS_LIGNE, EcranVerrou.etat(true, false, 0, "", m))
    }
}
