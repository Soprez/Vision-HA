// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.93.06 (essai 9.3 sur la TCL, 18/10 à 22:36) : la grande pochette restait sur celle du titre d'avant. Home Assistant
 * n'écrit pas le titre en cours comme la file (« Dubdogz/FEZZO/Zaark » contre « Dubdogz, FEZZO, Zaark », « (Afro House
 * Remix) » en plus) : la vignette du titre n'était pas retrouvée, et Vision gardait l'image du titre d'avant en
 * attendant celle de Home Assistant. Désormais la vignette est retrouvée, et sinon la case reste vide, jamais fausse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PochetteEnRetardTest {
    @Test fun la_vignette_du_titre_est_retrouvee_malgre_l_ecriture_de_ha() {
        // Les deux cas vus sur la TCL.
        assertEquals(Party.cleTitreLarge("How Does It Feel", "Dubdogz, FEZZO, Zaark"), Party.cleTitreLarge("How Does It Feel", "Dubdogz/FEZZO/Zaark"))
        assertEquals(Party.cleTitreLarge("Jamaican (Bam Bam)", "ZENO"), Party.cleTitreLarge("Jamaican (Bam Bam) (Afro House Remix)", "ZENO"))
        assertEquals(Party.cleTitreLarge("Reckless", "Eddie Thoneick, Kurd Maverick"), Party.cleTitreLarge("Reckless", "Eddie Thoneick/Kurd Maverick"))
        assertEquals(Party.cleTitreLarge("Titre", "A feat. B"), Party.cleTitreLarge("Titre", "B & A"))
        // Deux titres différents restent différents.
        assertNotEquals(Party.cleTitreLarge("How Does It Feel", "Dubdogz"), Party.cleTitreLarge("Reckless", "Dubdogz"))
        assertNotEquals(Party.cleTitreLarge("Lovely Flight", "Didier Sinclair"), Party.cleTitreLarge("Lovely Flight", "Autre"))
    }

    @Test fun jamais_la_pochette_du_titre_d_avant_sur_le_lecteur() {
        val avant = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val vignette = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        // Lecteur TV : la vignette du titre si on l'a, sinon rien (la case du thème), jamais l'image d'avant.
        assertSame(vignette, Pochettes.enAttendant(vignette, avant, garderAncienne = false))
        assertNull(Pochettes.enAttendant(null, avant, garderAncienne = false))
        // Mini-lecteur et widgets (même lecteur, le titre change moins souvent) : comme avant.
        assertSame(avant, Pochettes.enAttendant(null, avant, garderAncienne = true))
    }
}
