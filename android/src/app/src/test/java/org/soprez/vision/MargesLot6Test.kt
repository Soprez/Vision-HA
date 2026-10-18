// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** Lot 6 (B8 / APP-29) : les écrans de gestion gardent une largeur de lecture sur tablette. */
class MargesLot6Test {
    @Test fun cote_de_lecture() {
        assertEquals(20f, Marges.coteLecture(411))           // téléphone
        assertEquals(20f, Marges.coteLecture(800))           // tient encore
        assertEquals(260f, Marges.coteLecture(1280))         // tablette en paysage : colonne de 760 dp centrée
        assertEquals(760f, 1280 - 2 * Marges.coteLecture(1280))
    }
}
