// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 1.88.0 (widgets interactifs, proposition validée en essai le 2026-10-12), à l'écran : détail tronqué avec « +N » et
 * détail complet à l'appui ; réglage d'une valeur par − / + (pas et bornes de l'entité) ; 16-10 (l'auteur, parité) : la télé
 * aussi règle (avant : lecture seule).
 * Catalogue d'essai : AntiDebordementTest.CATALOGUE_WIDGETS (aucun Home Assistant).
 *
 *   adb shell am instrument -w -e class org.soprez.vision.WidgetsInteractifsTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class WidgetsInteractifsTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    override fun setUp() {
        super.setUp()
        WidgetsHaStock.garderCatalogue(ctx, AntiDebordementTest.CATALOGUE_WIDGETS)
        FluxWidgets.etats.clear()
        assertEquals("catalogue d'essai illisible", 5, WidgetsHaStock.catalogue(ctx).size)
    }

    private fun vues(v: View, l: ArrayList<View> = ArrayList()): ArrayList<View> {
        l.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), l); return l
    }

    private fun poser(r: WidgetsHa.Reglage, lectureSeule: Boolean, agirAvec: (WidgetsHa.Entite, String, JSONObject) -> Unit = { _, _, _ -> },
                      details: (String, String) -> Unit = { _, _ -> }): Pair<Activity, VueWidgetHa> {
        val a = instrumentation.startActivitySync(Intent(ctx, EditeurWidgetHaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var v: VueWidgetHa? = null
        instrumentation.runOnMainSync {
            val th = Local.themeEffectif(ctx, Palette.LANCEUR)
            v = VueWidgetHa(a, th, r, lectureSeule = lectureSeule, agir = { _, _ -> }, details = details, agirAvec = agirAvec)
            a.setContentView(FrameLayout(a).apply { setPadding(Ui.dp(a, 16f), Ui.dp(a, 80f), Ui.dp(a, 16f), 0); addView(v, FrameLayout.LayoutParams(Ui.dp(a, 170f) * r.w / 2, Ui.dp(a, 190f) * r.h / 2)) })
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
        return a to v!!
    }

    private fun textes(a: Activity): String {
        var t = ""
        instrumentation.runOnMainSync { t = vues(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString(" | ") { it.text.toString() } }
        return t
    }

    fun testDetailTronqueEtCompletAlAppui() {
        var titre = ""; var detail = ""
        // 1.91.2 : le défilement coupé (le défaut, défilement, est essayé par WidgetDefilementEcranTest).
        val (a, v) = poser(WidgetsHa.Reglage("t1", listOf("todo.devoirs"), WidgetsHa.TUILE, 2, 2, affichage = WidgetsHa.AFF_VALEUR_DETAIL, defilement = false),
            lectureSeule = false, details = { t, m -> titre = t; detail = m })
        val t = textes(a)
        assertTrue("valeur absente : $t", "25" in t)
        assertTrue("première ligne absente : $t", "Exercices 12 à 18 page 154" in t)
        // 1.91.2 (retour d'essai) : défilement coupé — ce qui tient, puis « Voir tout » (plus de « +N »).
        val montrees = t.split(" | ").count { it.startsWith("• ") }
        assertTrue("aucune ligne : $t", montrees >= 1)
        assertTrue("« Voir tout » absent : $t", ctx.getString(R.string.wha_voir_tout) in t)
        assertFalse("plus de « +N » : $t", Regex("""(^| )\+\d+""").containsMatchIn(t))
        instrumentation.runOnMainSync { v.performClick() }
        instrumentation.waitForIdleSync()
        assertTrue(titre.startsWith("Devoirs de la semaine"))
        assertTrue("détail complet : $detail", "Fiche de vocabulaire" in detail && ctx.getString(R.string.wha_detail_autres, 21) in detail)
        a.finish()
    }

    fun testValeurSeuleSansDetail() {
        val (a, _) = poser(WidgetsHa.Reglage("t2", listOf("todo.devoirs"), WidgetsHa.TUILE, 2, 2, affichage = WidgetsHa.AFF_VALEUR), lectureSeule = false)
        val t = textes(a)
        assertTrue("25" in t && "Exercices 12" !in t)
        a.finish()
    }

    fun testReglerUneValeur() {
        val recus = ArrayList<Pair<String, JSONObject>>()
        val tele = ReglagesTvActivity.estTele(ctx)
        val (a, _) = poser(WidgetsHa.Reglage("t3", listOf("input_number.consigne"), WidgetsHa.REGLAGE, 2, 1), lectureSeule = false,
            agirAvec = { _, s, d -> recus.add(s to d) })
        val nom = "Chauffage — température cible confort du séjour"
        var plus: View? = null
        instrumentation.runOnMainSync { plus = vues(a.window.decorView).firstOrNull { it.contentDescription == ctx.getString(R.string.wha_augmenter, nom) } }
        assertNotNull("bouton + absent", plus)
        if (tele) assertTrue("+ doit prendre le focus de la télécommande", plus!!.isFocusable)
        instrumentation.runOnMainSync { plus!!.performClick() }
        instrumentation.waitForIdleSync()
        // 1.93.05 (AppuisReglage, double envoi du 15/10) : les appuis rapprochés partent en un seul envoi, 600 ms après
        // le dernier. On attend cet envoi, puis on vérifie qu'il n'y en a qu'un.
        val fin = android.os.SystemClock.uptimeMillis() + 3_000
        while (synchronized(recus) { recus.isEmpty() } && android.os.SystemClock.uptimeMillis() < fin) android.os.SystemClock.sleep(50)
        android.os.SystemClock.sleep(800)
        assertEquals("set_value", recus.single().first)
        assertEquals(21.0, recus.single().second.getDouble("value"), 1e-9)  // 20,5 + pas de 0,5
        a.finish()
    }
}
