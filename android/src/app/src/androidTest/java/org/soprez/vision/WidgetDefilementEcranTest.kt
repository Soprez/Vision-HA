package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 1.91.2 (l'auteur : « les widgets ne sont toujours pas scrollables ») : le défilement, option de chaque widget.
 * - activé (par défaut) : toutes les lignes, sans « +N » ; le doigt fait défiler le widget sans faire défiler l'accueil,
 *   puis, le widget au bout, l'accueil défile ; un toucher simple reste celui du widget ; la télécommande défile puis
 *   rend la main ;
 * - désactivé : ce qui tient, puis « Voir tout » (le détail complet).
 */
class WidgetDefilementEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    @Volatile private var details = 0

    override fun setUp() {
        super.setUp()
        WidgetsHaStock.garderCatalogue(ctx, AntiDebordementTest.CATALOGUE_WIDGETS)
    }

    private class Banc(val a: Activity, val page: ScrollView, val vue: VueWidgetHa)

    /** Un widget 4 × 3 dans une page qui défile (comme l'accueil), sur un écran de Vision. */
    private fun banc(defilement: Boolean): Banc {
        val a = instrumentation.startActivitySync(EcransReglages.hote(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        var b: Banc? = null
        instrumentation.runOnMainSync {
            val th = Local.themeEffectif(a, Palette.LANCEUR)
            val r = WidgetsHa.Reglage("essai-defil", listOf("sensor.liste_longue"), WidgetsHa.TUILE, 4, 3, affichage = WidgetsHa.AFF_DETAIL,
                defilement = defilement, hauteur = WidgetsHa.HAUTEUR_MOYENNE)
            val v = VueWidgetHa(a, th, r, lectureSeule = true, details = { _, _ -> details++ }).apply { isFocusable = true }
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.fond) }
            col.addView(v, LinearLayout.LayoutParams(Ui.dp(a, 340f), Ui.dp(a, 300f)))
            col.addView(View(a), LinearLayout.LayoutParams(1, Ui.dp(a, 2000f)))
            val page = ScrollView(a).apply { addView(col) }
            (a.window.decorView as ViewGroup).addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            b = Banc(a, page, v)
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
        return b!!
    }

    private fun textes(v: View): List<String> = if (v is TextView) listOf(v.text.toString()) else if (v is ViewGroup) (0 until v.childCount).flatMap { textes(v.getChildAt(it)) } else emptyList()

    private fun glisser(v: View, dyPx: Int) {
        val p = IntArray(2); instrumentation.runOnMainSync { v.getLocationOnScreen(p) }
        val x = p[0] + v.width / 2f; val y0 = p[1] + v.height * 0.8f
        val t0 = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y0, 0))
        for (i in 1..10) instrumentation.sendPointerSync(MotionEvent.obtain(t0, t0 + i * 20L, MotionEvent.ACTION_MOVE, x, y0 + dyPx * i / 10f, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(t0, t0 + 240L, MotionEvent.ACTION_UP, x, y0 + dyPx, 0))
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
    }

    fun testDefileSansPlusNEtSansGenerLAccueil() {
        val b = banc(true)
        try {
            var d: DefilementWidget? = null
            instrumentation.runOnMainSync { d = b.vue.findViewWithTag("defilement_widget") }
            assertNotNull("défilement activé : la liste défile dans le widget", d)
            val t = textes(d!!)
            assertTrue("toutes les lignes reçues sont là (${t.size})", t.count { it.startsWith("•") } >= 20)
            assertFalse("pas de « +N »", t.any { Regex("^\\+\\d+").containsMatchIn(it) })
            assertTrue("le contenu dépasse : il défile", d!!.defilable())
            // Le doigt monte sur le widget : le widget défile, l'accueil non.
            glisser(d!!, -Ui.dp(ctx, 120f))
            assertTrue("le widget a défilé (${d!!.scrollY})", d!!.scrollY > 0)
            assertEquals("l'accueil n'a pas bougé", 0, b.page.scrollY)
            // Le widget au bout : le même geste fait défiler l'accueil.
            instrumentation.runOnMainSync { d!!.fullScroll(View.FOCUS_DOWN) }
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            glisser(d!!, -Ui.dp(ctx, 120f))
            assertTrue("le widget au bout, l'accueil défile (${b.page.scrollY})", b.page.scrollY > 0)
            // Un toucher simple reste celui du widget (ici : le détail complet).
            instrumentation.runOnMainSync { b.page.scrollTo(0, 0) }
            val avant = details
            val p = IntArray(2); instrumentation.runOnMainSync { d!!.getLocationOnScreen(p) }
            val t0 = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, p[0] + 20f, p[1] + 20f, 0))
            instrumentation.sendPointerSync(MotionEvent.obtain(t0, t0 + 60, MotionEvent.ACTION_UP, p[0] + 20f, p[1] + 20f, 0))
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            assertEquals("un toucher simple sur la liste agit comme sur le widget", avant + 1, details)
        } finally { b.a.finish(); instrumentation.waitForIdleSync() }
    }

    fun testDesactiveVoirTout() {
        val b = banc(false)
        try {
            var d: View? = null; var t = emptyList<String>()
            instrumentation.runOnMainSync { d = b.vue.findViewWithTag("defilement_widget"); t = textes(b.vue) }
            assertNull("défilement désactivé : pas de liste qui défile", d)
            assertTrue("« Voir tout » vers le détail : $t", t.contains(ctx.getString(R.string.wha_voir_tout)))
            assertFalse("pas de « +N »", t.any { Regex("^\\+\\d+").containsMatchIn(it) })
        } finally { b.a.finish(); instrumentation.waitForIdleSync() }
    }

    /** Télé : le widget a le focus ; bas fait défiler son contenu, puis, au bout, la touche repart (focus voisin). */
    fun testTelecommande() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        val b = banc(true)
        try {
            var d: DefilementWidget? = null
            instrumentation.runOnMainSync { d = b.vue.findViewWithTag("defilement_widget"); b.vue.requestFocus() }
            var garde = false
            instrumentation.runOnMainSync { garde = b.vue.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)) }
            SystemClock.sleep(500)
            assertTrue("bas : le contenu défile", garde && d!!.scrollY > 0)
            instrumentation.runOnMainSync { d!!.fullScroll(View.FOCUS_DOWN) }
            SystemClock.sleep(300)
            instrumentation.runOnMainSync { garde = !d!!.canScrollVertically(1) && b.vue.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)) }
            assertFalse("au bout, la touche repart vers la navigation", garde)
        } finally { b.a.finish(); instrumentation.waitForIdleSync() }
    }
}
