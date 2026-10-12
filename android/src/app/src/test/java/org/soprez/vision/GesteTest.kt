// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.77.0 (règle du projet) : l'état affiché tout de suite après un geste, et les refus dits en clair. */
class GesteTest {
    @Test
    fun etatAttenduDesInterrupteurs() {
        assertEquals("off", Geste.etatAttendu("on", "toggle"))
        assertEquals("on", Geste.etatAttendu("off", "toggle"))
        assertEquals("on", Geste.etatAttendu("off", "turn_on"))
        assertEquals("off", Geste.etatAttendu("on", "turn_off"))
        assertEquals("closed", Geste.etatAttendu("open", "close_cover"))
        // Inconnu : on n'invente rien, l'affichage attend Home Assistant.
        assertNull(Geste.etatAttendu("unavailable", "toggle"))
        assertNull(Geste.etatAttendu("idle", "press"))
    }

    @Test
    fun refusEnClairSansCode() {
        for (code in listOf(400, 401, 403, 404, 409, 422, 500, 502, 503)) {
            val m = Geste.messageEchec(code)
            assertFalse("code brut dans « $m »", Regex("\\d{3}").containsMatchIn(m))
            assertTrue(m, m.length > 20 && m.endsWith("."))
        }
        assertTrue(Geste.messageEchec(403).contains("droits de gestion"))
        assertTrue(Geste.messageEchec(503).contains("rien n'a changé"))
    }
}
