// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.90.4 (liaison sans code) : les choix « à qui est cet appareil » chez un Décideur. */
class NouvelAppareilTest {
    @Test fun demandeurs_puis_ecran_partage() {
        assertEquals(listOf("person.a" to "A", "person.b" to "person.b", "partage" to "Écran partagé — Lieu"),
            NouvelAppareil.options(listOf("person.a" to "A", "pas.une.personne" to "X", "person.b" to ""), "Écran partagé — Lieu"))
    }

    @Test fun tele_partagee_par_defaut() {
        assertEquals("partage", NouvelAppareil.parDefaut("tele"))
        assertNull(NouvelAppareil.parDefaut("telephone"))
    }
}
