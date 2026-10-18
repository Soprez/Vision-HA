// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Refonte des autorisations (HA 2.5.0) : le Décideur répond à une demande avec 15 min, 1 h, Refuser, Exception,
 * Toujours disponible. Ces choix n'apparaissent qu'avec une intégration qui les connaît (« autorisations_v2 » dans
 * l'état) : une intégration plus ancienne lirait la décision « accorder » comme un refus.
 */
class AutorisationsReponseTest {
    private val principal: File = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory } ?: error("Sources introuvables")
    private val parents = File(principal, "java/org/soprez/vision/ParentsActivity.kt").readText()
    private val acces = File(principal, "java/org/soprez/vision/Acces.kt").readText()

    @Test fun les_trois_modes_seulement_avec_ha_2_5() {
        assertTrue("drapeau lu dans l'état", parents.contains("troisModes = r.has(\"autorisations_v2\")"))
        val bloc = parents.substringAfter("if (troisModes) {").substringBefore("} else {")
        for (attendu in listOf("\"temporaire\", 15", "\"temporaire\", 60", "R.string.parents_refuser", "\"non\"",
            "\"accorder\", 0, \"exception\"", "\"accorder\", 0, \"toujours\"")) {
            assertTrue("absent des trois modes : $attendu", bloc.contains(attendu))
        }
        val ancien = parents.substringAfter("if (troisModes) {").substringAfter("} else {").substringBefore("c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.temps_autre_duree))")
        assertTrue("les anciens choix restent pour une intégration plus ancienne", ancien.contains("repondreAcces(pc, id, \"toujours\")") && !ancien.contains("\"accorder\""))
    }

    @Test fun le_mode_part_avec_la_decision() {
        assertTrue(acces.contains("if (mode.isNotEmpty()) put(\"mode\", mode)"))
    }

    @Test fun textes_dans_les_six_langues() {
        for (dossier in listOf("values", "values-fr", "values-de", "values-es", "values-it", "values-nl")) {
            val xml = File(principal, "res/$dossier/strings_autorisations.xml").readText()
            for (cle in listOf("parents_exception", "parents_toujours_disponible", "parents_refuser", "parents_aide_trois_modes")) {
                assertTrue("$dossier : $cle", Regex("""name="$cle">[^<]+<""").containsMatchIn(xml))
            }
        }
    }
}
