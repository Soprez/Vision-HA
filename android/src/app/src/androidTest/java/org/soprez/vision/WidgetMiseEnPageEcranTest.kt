// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 1.91.3 (l'auteur, version suivante) : la mise en page d'une tuile à l'écran — défaut grand et centré, alignements,
 * « remplir » plus grand que S, titre et icône cachables. Catalogue d'essai : AntiDebordementTest.CATALOGUE_WIDGETS.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.WidgetMiseEnPageEcranTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class WidgetMiseEnPageEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    override fun setUp() {
        super.setUp()
        WidgetsHaStock.garderCatalogue(ctx, AntiDebordementTest.CATALOGUE_WIDGETS)
        FluxWidgets.etats.clear()
    }

    private fun vues(v: View, l: ArrayList<View> = ArrayList()): ArrayList<View> {
        l.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), l); return l
    }

    /** Une tuile 2 × 2 de « input_number.consigne » (valeur seule), dessinée à 340 × 300 dp, avec la mise en page [m]. */
    private fun tuile(m: WidgetsHa.MiseEnPage): VueWidgetHa {
        var v: VueWidgetHa? = null
        instrumentation.runOnMainSync {
            val th = Local.themeEffectif(ctx, Palette.LANCEUR)
            val r = WidgetsHa.Reglage("mep", listOf("input_number.consigne"), WidgetsHa.TUILE, 2, 2, affichage = WidgetsHa.AFF_VALEUR, apercu = m)
            v = VueWidgetHa(ctx, th, r, lectureSeule = true)
            val w = Ui.dp(ctx, 340f); val h = Ui.dp(ctx, 300f)
            val sw = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY); val sh = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            v!!.measure(sw, sh); v!!.layout(0, 0, w, h); v!!.maj(); v!!.measure(sw, sh); v!!.layout(0, 0, w, h)
            v!!.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)))
        }
        return v!!
    }

    private fun valeur(v: VueWidgetHa): TextView = vues(v).first { it.tag == "widget_valeur" } as TextView

    fun testDefautGrandEtCentre() {
        val v = tuile(WidgetsHa.MiseEnPage())
        val t = valeur(v)
        assertTrue("la valeur est portée (lecteur d'écran) : ${t.text}", t.text.isNotBlank())
        val remplie = t.javaClass.getMethod("getTailleDessinee").invoke(t) as Float
        assertTrue("« remplir » : grande ($remplie px)", remplie > 40f * ctx.resources.displayMetrics.scaledDensity)
        assertNotNull("titre montré par défaut", vues(v).firstOrNull { it.tag == "widget_titre" })
    }

    fun testTitreEtIconeCaches() {
        val v = tuile(WidgetsHa.MiseEnPage(titre = false, icone = false))
        assertNull("ni titre ni icône", vues(v).firstOrNull { it.tag == "widget_titre" })
    }

    fun testTailleFixeEtAlignement() {
        val v = tuile(WidgetsHa.MiseEnPage(alignH = WidgetsHa.DROITE, alignV = WidgetsHa.BAS, taille = "s"))
        val t = valeur(v)
        assertEquals(WidgetsHa.tailleSp("s", false), t.textSize / ctx.resources.displayMetrics.scaledDensity, 2f)
        assertEquals(android.view.Gravity.END, t.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertEquals(android.view.Gravity.BOTTOM, t.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK)
    }
}
