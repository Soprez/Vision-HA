// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 15-10 (l'auteur : « Aucune demande en attente » alors qu'une demande attendait) : le widget Demandes de l'accueil.
 * - sans nouvelles de Home Assistant : « indisponible », jamais « aucune » ;
 * - rien une à une mais un appareil à valider (par lots) : il le dit, avec « Voir ce qui est à décider » ;
 * - plus de quatre demandes : le titre donne le total annoncé.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.DemandesWidgetEcranTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class DemandesWidgetEcranTest : InstrumentationTestCase() {
    override fun tearDown() {
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
    }

    private val ctx get() = instrumentation.targetContext

    private fun textes(v: View): List<String> = if (v is TextView) listOf(v.text.toString()) else if (v is ViewGroup) (0 until v.childCount).flatMap { textes(v.getChildAt(it)) } else emptyList()

    private fun widget(a: Activity, donnees: JSONObject?): List<String> {
        var t = emptyList<String>()
        instrumentation.runOnMainSync {
            val f = LanceurActivity::class.java.getDeclaredField("donnees").apply { isAccessible = true }
            f.set(a, donnees)
            val m = LanceurActivity::class.java.getDeclaredMethod("widgetDemandes").apply { isAccessible = true }
            t = textes(m.invoke(a) as View)
        }
        return t
    }

    fun testLesTroisCas() {
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        try {
            // 1. Sans nouvelles : « indisponible », pas « aucune ».
            var t = widget(a, null)
            assertTrue("sans nouvelles : $t", t.contains(ctx.getString(R.string.lanceur_demandes_inconnues)))
            assertFalse(t.contains(ctx.getString(R.string.lanceur_aucune_demande)))
            // 2. Un appareil à valider (par lots), aucune demande une à une.
            val lots = JSONObject().put("demandes", JSONArray()).put("demandes_total", 0)
                .put("a_decider_lots", JSONArray().put(JSONObject().put("genre", "appairage").put("nombre", 1).put("libelle", "1 appareil à valider")))
            t = widget(a, lots)
            assertTrue("lots : $t", t.contains("1 appareil à valider") && t.contains(ctx.getString(R.string.lanceur_voir_a_decider)))
            assertFalse(t.contains(ctx.getString(R.string.lanceur_aucune_demande)))
            // 2 bis. 16-10 (retour d'essai) : seulement « 229 éléments à classer » : « À décider », jamais « 1 demande en attente ».
            val aClasser = JSONObject().put("demandes", JSONArray()).put("demandes_total", 0)
                .put("a_decider_lots", JSONArray().put(JSONObject().put("genre", "classer").put("nombre", 229).put("libelle", "229 éléments à classer")))
            t = widget(a, aClasser)
            assertTrue("à classer : $t", t.contains("229 éléments à classer") && t.any { it.equals(ctx.getString(R.string.adec_titre), ignoreCase = true) })
            assertFalse("à classer : $t", t.any { it.equals(ctx.getString(R.string.lanceur_demande_attente), ignoreCase = true)
                || it.equals(ctx.getString(R.string.lanceur_demande_attente_n, 229), ignoreCase = true) })
            // 3. Six demandes, quatre reçues : le titre dit six.
            val six = JSONObject().put("demandes_total", 6).put("demandes", JSONArray().apply {
                repeat(4) { put(JSONObject().put("id", "d$it").put("genre", "temps").put("minutes", 15).put("prenom", "Lou").put("libelle", "15 min")) }
            })
            t = widget(a, six)
            // Les titres des widgets s'affichent en capitales.
            assertTrue("total : $t", t.any { it.equals(ctx.getString(R.string.lanceur_demande_attente_n, 6), ignoreCase = true) })
            // 4. Vraiment rien : « Aucune demande en attente ».
            t = widget(a, JSONObject().put("demandes", JSONArray()).put("demandes_total", 0).put("a_decider_lots", JSONArray()))
            assertTrue("rien : $t", t.contains(ctx.getString(R.string.lanceur_aucune_demande)))
        } finally { a.finish(); instrumentation.waitForIdleSync() }
    }
}
