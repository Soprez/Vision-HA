// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décision de l'auteur (16/10) : le dernier chiffre de la version a deux positions (1.93.01, 1.93.02 … 1.93.99) pour repousser
 * la 2.0. La mise à jour de l'appli (MiseAJour.plusRecente) doit les ranger dans l'ordre.
 */
class VersionsDeuxChiffresTest {
    @Test fun deux_positions_apres_la_version_publiee() {
        assertTrue(MiseAJour.plusRecente("1.93.01", "1.93.0"))
        assertFalse(MiseAJour.plusRecente("1.93.0", "1.93.01"))
        assertTrue(MiseAJour.plusRecente("1.93.02", "1.93.01"))
    }

    @Test fun dix_apres_neuf() {
        assertTrue(MiseAJour.plusRecente("1.93.10", "1.93.09"))
        assertFalse(MiseAJour.plusRecente("1.93.09", "1.93.10"))
        assertTrue(MiseAJour.plusRecente("1.93.99", "1.93.10"))
    }

    @Test fun ni_plus_recente_que_soi_ni_qu_une_suivante() {
        assertFalse(MiseAJour.plusRecente("1.93.01", "1.93.01"))
        assertTrue(MiseAJour.plusRecente("1.94.0", "1.93.99"))
    }
}
