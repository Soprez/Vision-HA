// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.90.2 (l'auteur : notifications muettes sur la télé) : le son de l'appli choisi pour un message. */
class SonTeleTest {
    @Test fun carillon_alarme_ou_rien() {
        assertEquals(R.raw.vision_carillon, SonTele.choix("Default", loop = false, important = false))
        assertEquals(R.raw.vision_alarme, SonTele.choix("Alarme", loop = false, important = false))
        assertEquals(R.raw.vision_alarme, SonTele.choix("Default", loop = true, important = false))
        assertEquals(R.raw.vision_alarme, SonTele.choix("Default", loop = false, important = true))
        assertNull(SonTele.choix("", loop = true, important = true))
        assertNull(SonTele.choix("Silent", loop = false, important = false))
        assertNull(SonTele.choix("silencieux", loop = false, important = false))
    }
}
