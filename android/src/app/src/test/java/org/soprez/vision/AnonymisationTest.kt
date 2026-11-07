// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * « Signaler un problème » : l'anonymisation de l'appli rend EXACTEMENT ce que rend celle de l'intégration
 * (`essai/diag_test.py`) sur le corpus commun `essai/corpus_anonymisation.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnonymisationTest {
    private fun corpus(): JSONObject {
        // Les essais JVM tournent dans android/src/app ; le corpus est à la racine du dépôt (essai/).
        val f = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "essai/corpus_anonymisation.json") }.first { it.exists() }
        return JSONObject(f.readText(Charsets.UTF_8))
    }

    private fun liste(a: JSONArray) = List(a.length()) { a.getString(it) }

    @Test fun le_corpus_commun_donne_les_memes_resultats_que_home_assistant() {
        val c = corpus()
        val cas = c.getJSONArray("cas")
        assertEquals(true, cas.length() >= 12)
        for (i in 0 until cas.length()) {
            val k = cas.getJSONObject(i)
            val a = Anonymisation(liste(c.getJSONArray("personnes")), liste(c.getJSONArray("appareils")))
            assertEquals(k.getString("nom"), k.getString("sortie"), a.ligne(k.getString("entree")))
            assertEquals(k.getString("nom") + " (remplacements)", k.getInt("n"), a.remplacements)
        }
    }

    @Test fun la_table_reste_stable_sur_plusieurs_lignes() {
        val a = Anonymisation()
        assertEquals("a ip-1", a.ligne("a 10.1.1.5"))
        assertEquals("b ip-2 puis ip-1", a.ligne("b 10.1.1.6 puis 10.1.1.5"))
    }

    @Test fun un_nom_trop_court_n_est_pas_masque() {
        assertEquals("Al est la", Anonymisation(listOf("Al")).ligne("Al est la"))
    }
}
