// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Veille-lot : ce que la veille garde des cases de Home Assistant (exclusions, unités, dates). */
class CasesVeilleTest {
    @Test fun boutons_et_champs_exclus() {
        listOf("button.sonnette", "input_button.go", "script.x", "scene.soir", "automation.a", "input_number.n", "input_text.t",
            "input_select.s", "input_datetime.d", "number.n", "select.s", "text.t", "notify.x", "tts.y", "conversation.z")
            .forEach { assertTrue(it, CasesVeille.exclue(it)) }
        // Un input_* inconnu (ajouté par une version future de Home Assistant) reste une saisie.
        assertTrue(CasesVeille.exclue("input_futur.x"))
        listOf("sensor.temperature", "binary_sensor.porte", "input_boolean.vacances", "switch.lampe", "weather.x", "camera.jardin", "")
            .forEach { assertFalse(it, CasesVeille.exclue(it)) }
    }

    @Test fun unites_en_j_selon_la_langue() {
        assertEquals("j", CasesVeille.unite("d", "fr"))
        assertEquals("L/j", CasesVeille.unite("L/d", "fr"))
        assertEquals("kWh/j", CasesVeille.unite("kWh/d", "fr"))
        assertEquals("sem.", CasesVeille.unite("w", "fr"))
        assertEquals("mois", CasesVeille.unite("mo", "fr"))
        // Déjà traduit par Home Assistant : inchangé.
        assertEquals("j", CasesVeille.unite("j", "fr"))
        assertEquals("L/j", CasesVeille.unite("L/j", "fr"))
        // Rien d'autre ne bouge.
        listOf("°C", "%", "W", "kWh", "h", "min", "m³", "L/min", "/").forEach { assertEquals(it, CasesVeille.unite(it, "fr")) }
        // Autre langue : ce que Home Assistant envoie.
        assertEquals("d", CasesVeille.unite("d", "en"))
        assertEquals("L/d", CasesVeille.unite("L/d", "de"))
    }

    @Test fun dates_du_contrat() {
        val a = CasesVeille.instant("2026-10-11T05:24:39+02:00")
        val b = CasesVeille.instant("2026-10-11T03:24:39Z")
        assertNotNull(a)
        assertEquals(a, b)
        assertEquals(a, CasesVeille.instant("2026-10-11T05:24:39.123456+02:00"))
        assertNotNull(CasesVeille.instant("2026-10-12"))
        assertNull(CasesVeille.instant(""))
        assertNull(CasesVeille.instant("demain"))
    }

    @Test fun ecart_lisible() {
        val t = 1_000_000_000_000L
        assertEquals("instant" to 0L, CasesVeille.ecart(t, t + 20_000))
        assertEquals("min" to 5L, CasesVeille.ecart(t, t + 5 * 60_000))
        assertEquals("h" to 2L, CasesVeille.ecart(t, t + 125 * 60_000))
        assertEquals("hier" to 1L, CasesVeille.ecart(t, t + 30 * 3600_000L))
        assertEquals("j" to 3L, CasesVeille.ecart(t, t + 3 * 24 * 3600_000L))
        assertEquals("instant" to 0L, CasesVeille.ecart(t + 10_000, t))   // horloge en avance : jamais négatif
    }
}
