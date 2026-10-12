// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Pause générale : lecture de l'état reçu, texte de la bannière, visibilité du bouton, bornes de durée. */
class PauseGeneraleTest {
    private val paris: TimeZone = TimeZone.getTimeZone("Europe/Paris")

    @Test fun lecture_de_l_etat() {
        val maintenant = 1_791_500_000_000L
        assertNull(PauseGenerale.finMs(null, maintenant))      // champ null : pas de pause
        assertNull(PauseGenerale.finMs(0, maintenant))
        assertNull(PauseGenerale.finMs(maintenant / 1000 - 1, maintenant))  // échue
        assertEquals(maintenant + 1_800_000, PauseGenerale.finMs(maintenant / 1000 + 1800, maintenant))
    }

    @Test fun texte_de_la_banniere() {
        // 2026-10-09 17:45 UTC = 19h45 à Paris (heure d'été).
        val fin = java.time.Instant.parse("2026-10-09T17:45:00Z").toEpochMilli()
        assertEquals("19h45", PauseGenerale.heure(fin, paris))
        assertEquals("Tout est fermé jusqu'à 19h45", PauseGenerale.banniere(fin, paris))
        val matin = java.time.Instant.parse("2026-01-05T07:05:00Z").toEpochMilli()
        assertEquals("Tout est fermé jusqu'à 08h05", PauseGenerale.banniere(matin, paris))
    }

    @Test fun visibilite_du_bouton() {
        assertTrue(PauseGenerale.visible(true, setOf("gerer", "approuver")))
        assertTrue(PauseGenerale.visible(true, emptySet()))
        assertFalse(PauseGenerale.visible(true, setOf("protege", "approuver")))
        assertFalse(PauseGenerale.visible(false, setOf("gerer")))
        assertTrue(Modules.actifDans(null, Modules.PAUSE_GENERALE))
        assertFalse(Modules.actifDans(setOf("plages"), Modules.PAUSE_GENERALE))
        assertTrue(Modules.PAUSE_GENERALE in Modules.TOUS)
    }

    @Test fun bornes_de_duree() {
        assertTrue(PauseGenerale.dureeValide(15)); assertTrue(PauseGenerale.dureeValide(240))
        assertFalse(PauseGenerale.dureeValide(0)); assertFalse(PauseGenerale.dureeValide(245))
    }
}
