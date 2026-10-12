// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Refonte UX, lot 1 (APP-27) : l'interrupteur de catégorie dit son état en toutes lettres (« Ouverte » / « Fermée »). */
class CategorieEtatTest {
    @Test fun libelles_dans_les_six_langues() {
        val attendus = mapOf("en" to ("Open" to "Blocked"), "fr" to ("Ouverte" to "Fermée"), "de" to ("Offen" to "Gesperrt"),
            "es" to ("Abierta" to "Cerrada"), "it" to ("Aperta" to "Chiusa"), "nl" to ("Open" to "Gesloten"))
        for ((l, p) in attendus) {
            val t = TrJvm.dossier(TrJvm.dossierDe(l)).chaines
            assertEquals(l, p.first, t["cat_etat_ouverte"]); assertEquals(l, p.second, t["cat_etat_fermee"])
            assertTrue("$l : l'aide nomme les deux états", t["cat_aide"]!!.contains(p.first) && t["cat_aide"]!!.contains(p.second))
        }
    }

    @Test fun l_ecran_affiche_et_annonce_l_etat() {
        val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
        val c = File(src, "CategoriesActivity.kt").readText()
        assertTrue(c.contains("R.string.cat_etat_fermee") && c.contains("R.string.cat_etat_ouverte"))
        assertTrue(c.contains("sw.contentDescription = getString(R.string.cat_etat_description"))
    }
}
