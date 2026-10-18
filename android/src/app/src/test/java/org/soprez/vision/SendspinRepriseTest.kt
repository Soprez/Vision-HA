// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Banc Musique (2026-10-12) : après une coupure, la lecture que MA arrête au retour est relancée — une pause, jamais. */
class SendspinRepriseTest {
    @Test fun relance_apres_une_coupure_courte() {
        // Mesuré contre MA 2.10.6 : au retour, flux relancé, puis « playing », puis « stopped » 20 ms plus tard.
        assertTrue(SendspinReprise.reprendre(true, 22_000, 20, true, "stopped", false))
    }

    @Test fun jamais_une_pause_ni_ce_qui_ne_jouait_pas() {
        // Pause demandée ailleurs pendant la coupure : MA ne relance aucun flux au retour (et dit « stopped », comme pour
        // une pause ordinaire).
        assertFalse(SendspinReprise.reprendre(true, 22_000, 20, false, "stopped", false))
        assertFalse(SendspinReprise.reprendre(true, 22_000, 20, true, "playing", false))
        assertFalse(SendspinReprise.reprendre(false, 22_000, 20, true, "stopped", false))
    }

    @Test fun une_fois_et_seulement_juste_apres_le_retour() {
        assertFalse(SendspinReprise.reprendre(true, 22_000, 20, true, "stopped", true))
        assertFalse(SendspinReprise.reprendre(true, 22_000, SendspinReprise.FENETRE_MS + 1, true, "stopped", false))
        assertFalse(SendspinReprise.reprendre(true, SendspinReprise.PERTE_MAX_MS + 1, 20, true, "stopped", false))
    }
}
