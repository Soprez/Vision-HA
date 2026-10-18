// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relecture sécurité (6104948) : la page de veille ne quitte jamais l'origine de Home Assistant. */
class VeilleOrigineTest {
    private val ha = "https://ha.exemple.org:8123/api/pc_parental/veille/page?appareil=t1"

    @Test fun memeOriginePermise() {
        assertTrue(memeOrigine("https://ha.exemple.org:8123/api/pc_parental/veille/autre", ha))
        assertTrue(memeOrigine("https://HA.exemple.org:8123/", ha))
        assertTrue(memeOrigine("https://ha.exemple.org/x", "https://ha.exemple.org:443/y"))
    }

    @Test fun autreSiteRefuse() {
        assertFalse(memeOrigine("https://autre.exemple.org:8123/", ha))
        assertFalse(memeOrigine("http://ha.exemple.org:8123/", ha))
        assertFalse(memeOrigine("https://ha.exemple.org:8124/", ha))
        assertFalse(memeOrigine("https://ha.exemple.org.piege.com:8123/", ha))
        assertFalse(memeOrigine("javascript:alert(1)", ha))
        assertFalse(memeOrigine("pas une adresse", ha))
    }
}
