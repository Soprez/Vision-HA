// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.94 · Découverte appli → sites : l'écran Autorisations montre l'appareil regardé, le badge de l'appli (vert, orange avec ses
 * sites fermés, rouge avec sa raison) et, sur chaque site lié tout seul, sa preuve ou sa nature de secours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR")
class CategorieBadgeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun avant() { CategorieActivity.pc = "t1"; CategorieActivity.etiquette = "Jeux"; CategorieActivity.horsLigne = true }
    @After fun apres() { CategorieActivity.horsLigne = false; CategorieActivity.pc = ""; CategorieActivity.etiquette = "" }

    private fun textes(v: View, sortie: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView && v.text.isNotEmpty()) sortie += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    private fun site(nom: String, ouvert: Boolean, vararg extra: Pair<String, Any>) = JSONObject().put("genre", "sites").put("nom", nom).put("ouvert", ouvert)
        .also { o -> extra.forEach { (k, v) -> o.put(k, v) } }

    private fun detail(vararg logiciels: JSONObject) = JSONObject().put("etiquette", "Jeux").put("fermee", false)
        .put("logiciels", JSONArray(logiciels.toList())).put("sites", JSONArray()).put("autres", 0)
        .put("appareil", JSONObject().put("id", "t1").put("nom", "Tablette de Lou"))

    private fun logiciel(nom: String, badge: String, ouvert: Boolean, sitesFermes: List<String>, sites: List<JSONObject>) =
        JSONObject().put("genre", "apps").put("nom", nom).put("libelle", nom).put("ouvert", ouvert).put("badge", badge)
            .put("sites_fermes", JSONArray(sitesFermes)).put("sites", JSONArray(sites)).put("services", JSONArray())
            .put("appareil", JSONObject().put("id", "t1").put("nom", "Tablette de Lou"))

    private fun ecran(d: JSONObject, deplier: String? = null): List<String> {
        val a = Robolectric.buildActivity(CategorieActivity::class.java).create().start().resume().get()
        a.montrer(d)
        if (deplier != null) {
            val ligne = generateSequence(listOf(a.findViewById<View>(android.R.id.content))) { vs -> vs.filterIsInstance<ViewGroup>().flatMap { g -> (0 until g.childCount).map { g.getChildAt(it) } } }
                .takeWhile { it.isNotEmpty() }.flatten().firstOrNull { it is TextView && it.text.toString().endsWith(deplier) }
            // Le nom n'est pas cliquable lui-même : c'est sa colonne (l'accordéon).
            checkNotNull(ligne) { "ligne $deplier introuvable" }
            (ligne.parent as View).performClick()
        }
        return textes(a.findViewById(android.R.id.content))
    }

    @Test fun l_ecran_dit_sur_quel_appareil_on_regarde() {
        val t = ecran(detail())
        assertTrue(t.toString(), t.contains("Sur : Tablette de Lou"))
    }

    @Test fun un_logiciel_ouvert_dont_un_site_lie_reste_ferme_est_partiellement_bloque() {
        val t = ecran(detail(logiciel("Roblox", "orange", true, listOf("rbxnouveau.com"), listOf(site("rbxnouveau.com", false)))), "Roblox")
        assertTrue(t.toString(), t.contains("Partiellement bloqué"))
        assertTrue(t.toString(), t.contains("Fermés : rbxnouveau.com"))
    }

    @Test fun un_logiciel_ferme_est_rouge_avec_sa_raison_et_sans_liste_de_sites() {
        val lg = logiciel("Roblox", "rouge", false, emptyList(), emptyList()).put("ferme_par", "plage").put("raison", "Fermé par la plage Soir.")
        val t = ecran(detail(lg))
        assertTrue(t.contains("Fermé par la plage Soir.")); assertFalse(t.any { it.startsWith("Fermés :") }); assertFalse(t.contains("Partiellement bloqué"))
    }

    @Test fun un_logiciel_vert_n_affiche_ni_orange_ni_liste() {
        val t = ecran(detail(logiciel("Roblox", "vert", true, emptyList(), emptyList()).put("mode", "exception")))
        assertFalse(t.contains("Partiellement bloqué")); assertFalse(t.any { it.startsWith("Fermés :") })
    }

    @Test fun un_site_lie_tout_seul_montre_sa_preuve_et_un_secours_se_dit_a_verifier() {
        val mesure = site("cdn-jeu.exemple", true, "auto" to true, "preuve" to JSONObject().put("n", 42).put("jours", 5).put("part", 0.96).put("source", "mesure"))
        val secours = site("rbxnouveau.com", true, "auto" to true, "secours" to true, "a_verifier" to true)
        val t = ecran(detail(logiciel("Roblox", "vert", true, emptyList(), listOf(mesure, secours))), "Roblox")
        assertTrue(t.toString(), t.contains("Lié tout seul : 42 demandes sur 5 jours, 96 %"))
        assertTrue(t.contains("Lien de secours")); assertTrue(t.contains("À vérifier"))
    }
}
