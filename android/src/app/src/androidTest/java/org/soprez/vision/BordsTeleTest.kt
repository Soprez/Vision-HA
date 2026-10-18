// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import java.io.File

/**
 * 1.91.0 (photos de l'auteur : sur l'accueil de la télé, les icônes de la rangée « Applications » étaient coupées net au bord
 * de la marge) : les rangées télé défilent d'un bord de l'écran à l'autre.
 *
 * - la zone de défilement touche les deux bords de l'écran ;
 * - au repos, le premier élément est sur la zone sûre (5 %) ;
 * - le dernier, une fois atteint à la télécommande, est entier et dans la zone sûre ;
 * - ce qui déborde s'efface en fondu (fading edge de la largeur de l'overscan), jamais coupé net au bord de la marge ;
 * - l'appli agrandie au focus (×1,12) n'est jamais rognée en haut ni en bas.
 * L'accueil est rempli de dossiers (une même appli dans chacun) pour que la rangée déborde à coup sûr ; la disposition
 * de l'appareil est remise à la fin. Captures dans /sdcard/Android/data/org.soprez.vision/files/captures.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.BordsTeleTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class BordsTeleTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }
    private var sauve: Map<String, *> = emptyMap<String, Any>()

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
        sauve = HashMap(ctx.getSharedPreferences("lanceur", 0).all)
    }

    override fun tearDown() {
        ctx.getSharedPreferences("lanceur", 0).edit().clear().apply {
            sauve.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    private fun capturer(nom: String) {
        val b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun resolution(): String { val d = ctx.resources.displayMetrics; return "${d.widthPixels}x${d.heightPixels}_police${ctx.resources.configuration.fontScale}" }

    private fun vues(v: View, sortie: ArrayList<View> = ArrayList()): ArrayList<View> {
        sortie.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), sortie)
        return sortie
    }

    private fun ecran(v: View): Rect { val p = IntArray(2); v.getLocationOnScreen(p); return Rect(p[0], p[1], p[0] + v.width, p[1] + v.height) }

    /** Le rectangle à l'écran d'une vue agrandie autour de son pivot (le focus des applis de l'accueil). */
    private fun agrandi(v: View): Rect {
        val r = ecran(v)
        val dx = (v.width * (v.scaleX - 1f) / 2f).toInt(); val dy = (v.height * (v.scaleY - 1f) / 2f).toInt()
        return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    private fun remplir() {
        val pm = ctx.packageManager
        val appli = Accueil.applicationsInstallees(ctx).map { it.activityInfo.packageName }.firstOrNull { it != ctx.packageName }
            ?: ctx.packageName
        // Assez de cases pour déborder même en 720p : douze dossiers (une même appli, permise dans plusieurs dossiers).
        Accueil.ecrireDossiers(ctx, (1..12).map { Accueil.Dossier("essai$it", "Dossier $it", mutableListOf(appli)) })
        Accueil.ecrireOrdre(ctx, emptyList())
        assertTrue(pm != null)
    }

    private fun attendreFocus(a: Activity, v: View) {
        val fin = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < fin) {
            var ok = false
            instrumentation.runOnMainSync { ok = v.isFocused }
            if (ok) break
            SystemClock.sleep(50)
        }
        SystemClock.sleep(450)  // agrandissement (140 ms) et défilement doux
        instrumentation.waitForIdleSync()
    }

    fun testRangeesTeleBordABord() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        remplir()
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(800)
        val defauts = ArrayList<String>()
        var rangees = emptyList<HorizontalScrollView>()
        var w = 0; var marge = 0
        instrumentation.runOnMainSync {
            w = a.window.decorView.width
            marge = (ctx.resources.displayMetrics.widthPixels * Marges.ZONE_SURE).toInt()
            rangees = vues(a.window.decorView).filterIsInstance<HorizontalScrollView>().filter { it.tag == BordABord.TAG && it.isShown }
        }
        assertTrue("aucune rangée bord à bord sur l'accueil télé", rangees.isNotEmpty())
        instrumentation.runOnMainSync {
            for (h in rangees) {
                val r = ecran(h)
                if (r.left > 1 || r.right < w - 1) defauts.add("rangée pas bord à bord : $r (écran $w)")
                if (!h.isHorizontalFadingEdgeEnabled) defauts.add("pas de fondu sur une rangée")
                if (Math.abs(h.horizontalFadingEdgeLength - marge) > 2) defauts.add("fondu ${h.horizontalFadingEdgeLength} ≠ zone sûre $marge")
                val rangee = h.getChildAt(0) as ViewGroup
                val premier = (0 until rangee.childCount).map { rangee.getChildAt(it) }.firstOrNull { it.isShown }
                if (premier != null) {
                    val p = ecran(premier)
                    if (p.left < marge - 1) defauts.add("premier élément dans l'overscan : $p (zone $marge)")
                    if (p.left > marge + Ui.dp(ctx, 12f)) defauts.add("premier élément loin de la zone sûre : $p (zone $marge)")
                }
            }
        }
        capturer("bords_tele_repos_" + resolution())
        // La rangée des applications : elle déborde (douze dossiers) et va jusqu'au bord droit.
        val apps = rangees.first { h -> var n = 0; instrumentation.runOnMainSync { n = (h.getChildAt(0) as ViewGroup).childCount }; n >= 10 }
        var deborde = false
        instrumentation.runOnMainSync { deborde = apps.canScrollHorizontally(1) }
        assertTrue("la rangée d'essai devrait déborder", deborde)
        val cases = ArrayList<View>()
        instrumentation.runOnMainSync { val r = apps.getChildAt(0) as ViewGroup; for (i in 0 until r.childCount) if (r.getChildAt(i).isFocusable) cases.add(r.getChildAt(i)) }
        // Au repos, une case dépasse sur la droite : elle passe sous le bord de l'écran (fondu), pas sous la marge.
        instrumentation.runOnMainSync {
            val coupee = cases.firstOrNull { val r = ecran(it); r.left < w && r.right > w - marge }
            if (coupee == null) defauts.add("aucune case ne passe dans l'overscan à droite (rangée coupée à la marge ?)")
            else { val vis = Rect(); coupee.getGlobalVisibleRect(vis); if (vis.right < w - 1 && ecran(coupee).right > w) defauts.add("case coupée avant le bord de l'écran : visible jusqu'à ${vis.right} / $w") }
        }
        // Premier élément au focus : agrandi, jamais rogné en haut ni en bas, ni sorti de la zone sûre à gauche.
        instrumentation.runOnMainSync { cases.first().requestFocus() }
        attendreFocus(a, cases.first())
        instrumentation.runOnMainSync {
            val g = agrandi(cases.first()); val h = ecran(apps)
            if (g.top < h.top - 1 || g.bottom > h.bottom + 1) defauts.add("case agrandie rognée par la rangée : $g dans $h")
            if (g.left < marge - Ui.dp(ctx, 10f)) defauts.add("case agrandie dans l'overscan : $g")
        }
        capturer("bords_tele_premier_" + resolution())
        // Jusqu'au bout à la télécommande : la dernière case est entière, dans la zone sûre.
        repeat(cases.size + 2) { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); SystemClock.sleep(90) }
        SystemClock.sleep(900)  // le défilement doux s'achève
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val f = a.currentFocus
            if (f != null && f.parent === apps.getChildAt(0)) {
                val r = ecran(f)
                if (r.right > w - marge + 2) defauts.add("élément choisi dans l'overscan à droite : $r (zone ${w - marge})")
                if (r.left < marge - 2) defauts.add("élément choisi dans l'overscan à gauche : $r")
            }
        }
        capturer("bords_tele_fin_" + resolution())
        a.finish()
        assertTrue(defauts.joinToString("\n"), defauts.isEmpty())
    }
}
