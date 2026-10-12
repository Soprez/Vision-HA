// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.76.0 (point 6 bis) : le statut permanent, en clair. */
class StatutNotifTest {
    private val h: (Long) -> String = { "${it / 3_600_000}h00" }
    private val t0 = 10 * 3_600_000L

    @Test fun ouvertAvecFermeture() = assertEquals("Ouvert · encore 1 h 20" to "Fermeture à 11h00.",
        StatutNotif.textes(false, false, 0, t0 + 80 * 60_000L - 1 + 1, "", t0) { "11h00" })

    @Test fun ferme() = assertEquals("Fermé · réouverture à 12h00" to "Plage du soir.",
        StatutNotif.textes(false, true, 0, 12 * 3_600_000L, "Plage du soir.", t0, h))

    @Test fun pause() = assertEquals("Protection suspendue jusqu'à 11h00" to "La protection reprend toute seule.",
        StatutNotif.textes(false, false, 11 * 3_600_000L, 0, "", t0, h))

    @Test fun horsLigne() = assertEquals("Hors ligne", StatutNotif.textes(true, true, 0, 0, "", t0, h).first)

    @Test fun ouvertSansFin() = assertEquals("Ouvert" to "Aucune fermeture prévue.", StatutNotif.textes(false, false, 0, 0, "", t0, h))
}
