// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 1.91.2 (l'auteur : « on avait créé plusieurs types d'horloges ») : le style du widget Horloge et de la veille.
 * - le widget dessine le style choisi sur l'appareil (l'anneau d'avant par défaut ; un style de Vision ; une horloge
 *   de thème dans sa page, aux couleurs du thème de l'appareil) ;
 * - le choix montre chaque style en grand avant de le garder ;
 * - la veille suit l'accueil ou a son propre style ; rien de choisi : la page de veille garde l'horloge du thème.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.StylesHorlogeEcranTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class StylesHorlogeEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private var avantZone: String? = null
    private var avantVeille: String? = null

    override fun setUp() {
        super.setUp()
        avantZone = ctx.getSharedPreferences("local", Context.MODE_PRIVATE).getString("zone_haut_horloge", null)
        avantVeille = Local.styleHorlogeVeille(ctx)
        ctx.getSharedPreferences("local", Context.MODE_PRIVATE).edit().remove("zone_haut_horloge").commit()
        Local.poserStyleHorlogeVeille(ctx, StylesHorloge.SUIVRE_ACCUEIL)
    }

    override fun tearDown() {
        ctx.getSharedPreferences("local", Context.MODE_PRIVATE).edit().putString("zone_haut_horloge", avantZone).commit()
        Local.poserStyleHorlogeVeille(ctx, avantVeille ?: StylesHorloge.SUIVRE_ACCUEIL)
        super.tearDown()
    }

    private fun vues(v: View, l: ArrayList<View> = ArrayList()): ArrayList<View> {
        l.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), l); return l
    }

    private fun activite(): Activity {
        val a = instrumentation.startActivitySync(EcransReglages.hote(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        return a
    }

    fun testReglageParDefautEtChoixGarde() {
        assertEquals("défaut : l'anneau", HorlogesVeille.ANNEAU, ZoneHaut.reglagesHorloge(ctx).style)
        assertNull("rien de choisi", ZoneHaut.styleChoisi(ctx))
        // Changer la taille ne « choisit » pas de style.
        ZoneHaut.poserReglagesHorloge(ctx, ZoneHaut.reglagesHorloge(ctx).copy(taille = ZoneHaut.Taille.GRANDE))
        assertNull(ZoneHaut.styleChoisi(ctx))
        ZoneHaut.poserReglagesHorloge(ctx, ZoneHaut.reglagesHorloge(ctx).copy(style = "t-galaxie"), styleChoisi = true)
        val r = ZoneHaut.reglagesHorloge(ctx)
        assertEquals("t-galaxie", r.style); assertEquals(ZoneHaut.Taille.GRANDE, r.taille)
        assertEquals("t-galaxie", ZoneHaut.styleChoisi(ctx))
    }

    fun testLeWidgetDessineLeStyle() {
        val th = Local.themeEffectif(ctx, Palette.LANCEUR)
        var anneau: View? = null; var fine: View? = null; var web: View? = null
        instrumentation.runOnMainSync {
            anneau = StylesHorloge.vue(ctx, th, HorlogesVeille.ANNEAU, true)
            fine = StylesHorloge.vue(ctx, th, HorlogesVeille.FINE, true)
            web = StylesHorloge.vue(ctx, th, "t-galaxie", true)
        }
        assertTrue(anneau is HorlogeVision.Vue)
        assertTrue(fine is StylesHorloge.VueNative)
        assertTrue(web is StylesHorloge.VueWeb)
        // Chaque style de Vision dessine quelque chose aux couleurs du thème (ni vide ni hors du thème).
        for (style in HorlogesVeille.STYLES.filter { it != HorlogesVeille.ANNEAU }) {
            var b: Bitmap? = null
            instrumentation.runOnMainSync {
                val v = StylesHorloge.VueNative(ctx, th, style, true)
                val w = Ui.dp(ctx, 240f); val h = Ui.dp(ctx, 150f)
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, h)
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
            }
            var encre = 0
            for (x in 0 until b!!.width step 2) for (y in 0 until b!!.height step 2) if (proche(b!!.getPixel(x, y), th.encre) || proche(b!!.getPixel(x, y), th.pourpre)) encre++
            assertTrue("$style : rien de dessiné à l'encre ou à l'accent du thème ($encre)", encre > 20)
        }
    }

    /**
     * 1.91.2 (l'auteur : horloges en 12 h) : sur 12 h, les styles en chiffres ajoutent AM / PM — l'image change par rapport
     * au 24 h, et rien ne touche les bords (le repère n'est pas rogné), dans un widget large comme dans une case de
     * téléphone étroite. Les images vont dans files/recette-12h/ (recette visuelle : adb pull).
     */
    fun testHorlogesEn12Heures() {
        val th = Local.themeEffectif(ctx, Palette.LANCEUR)
        val dossier = java.io.File(ctx.filesDir, "recette-12h").apply { mkdirs() }
        fun dessin(style: String, h24: Boolean, wDp: Float, hDp: Float): Bitmap {
            var b: Bitmap? = null
            instrumentation.runOnMainSync {
                val v = StylesHorloge.vue(ctx, th, style, h24)
                val w = Ui.dp(ctx, wDp); val h = Ui.dp(ctx, hDp)
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, h)
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
            }
            return b!!
        }
        fun encreAuBord(b: Bitmap): Int {
            var n = 0
            for (y in 0 until b.height) { if (proche(b.getPixel(0, y), th.encre)) n++; if (proche(b.getPixel(b.width - 1, y), th.encre)) n++ }
            for (x in 0 until b.width) { if (proche(b.getPixel(x, 0), th.encre)) n++; if (proche(b.getPixel(x, b.height - 1), th.encre)) n++ }
            return n
        }
        // Les styles en chiffres (les aiguilles et « en toutes lettres » disent déjà l'heure autrement).
        val chiffres = listOf(HorlogesVeille.ANNEAU, HorlogesVeille.FINE, HorlogesVeille.GRASSE, HorlogesVeille.FLIP, HorlogesVeille.EMPILEE)
        val fautes = ArrayList<String>()
        for (style in chiffres) for ((wDp, hDp) in listOf(240f to 150f, 160f to 110f)) {
            val b12 = dessin(style, false, wDp, hDp); val b24 = dessin(style, true, wDp, hDp)
            dossier.resolve("$style-${wDp.toInt()}-12h.png").outputStream().use { b12.compress(Bitmap.CompressFormat.PNG, 100, it) }
            var differents = 0
            for (x in 0 until b12.width step 2) for (y in 0 until b12.height step 2) if (b12.getPixel(x, y) != b24.getPixel(x, y)) differents++
            if (differents < 10) fautes.add("$style ${wDp.toInt()} dp : 12 h dessiné comme 24 h (pas d'AM / PM)")
            val bord = encreAuBord(b12)
            if (bord > 2) fautes.add("$style ${wDp.toInt()} dp : $bord points d'encre au bord (rogné ?)")
        }
        assertTrue(fautes.joinToString(" ; "), fautes.isEmpty())
    }

    private fun proche(a: Int, b: Int): Boolean =
        android.graphics.Color.alpha(a) > 200 && Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) < 24 &&
            Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) < 24 && Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) < 24

    /** Une horloge de thème tourne dans sa page, avec l'accent du thème de l'appareil ; les gestes passent à l'accueil. */
    fun testHorlogeDeThemeAuxCouleursDeLAppareil() {
        val a = activite()
        val th = Local.themeEffectif(ctx, Palette.LANCEUR)
        var v: StylesHorloge.VueWeb? = null
        instrumentation.runOnMainSync {
            v = StylesHorloge.VueWeb(a, th, "galaxie", date = false)
            (a.window.decorView as ViewGroup).addView(v, FrameLayout.LayoutParams(Ui.dp(a, 320f), Ui.dp(a, 200f)))
        }
        var resultat = ""
        for (essai in 1..20) {
            SystemClock.sleep(500)
            val fini = CountDownLatch(1)
            instrumentation.runOnMainSync {
                (vues(v!!).first { it is WebView } as WebView).evaluateJavascript(
                    "(function(){var e=document.querySelector('.horloge.h-galaxie');return e?(getComputedStyle(document.documentElement).getPropertyValue('--v-accent').trim()+'|'+e.textContent.replace(/\\s+/g,'')):'';})()") { r -> resultat = r ?: ""; fini.countDown() }
            }
            fini.await(5, TimeUnit.SECONDS)
            if (resultat.length > 4) break
        }
        val accent = "#" + String.format("%06X", th.pourpre and 0xFFFFFF)
        assertTrue("la page de l'horloge est là, à l'accent $accent : $resultat", resultat.contains(accent, ignoreCase = true))
        assertTrue("l'heure est écrite : $resultat", Regex("""\d{1,2}:\d{2}""").containsMatchIn(resultat))
        assertFalse("jamais le focus de la télécommande", v!!.isFocusable || vues(v!!).any { it.isFocusable })
        instrumentation.runOnMainSync { a.finish() }
    }

    /** Le choix : 36 styles, un premier toucher montre en grand, un second garde. */
    fun testChoixAvecApercu() {
        val a = activite()
        var garde: String? = null
        instrumentation.runOnMainSync { StylesHorloge.choisir(a, HorlogesVeille.ANNEAU, veille = false) { garde = it } }
        instrumentation.waitForIdleSync(); SystemClock.sleep(600)
        val racines = rootsDialogue()
        val cellules = racines.flatMap { vues(it) }.filter { (it.tag as? String)?.startsWith("style_") == true }
        assertEquals("8 styles de Vision et 28 horloges de thèmes", 36, cellules.size)
        val nom = racines.flatMap { vues(it) }.first { it.tag == "horloge_apercu_nom" } as TextView
        assertEquals(ctx.getString(R.string.horloge_anneau), nom.text.toString())
        val mots = cellules.first { it.tag == "style_mots" }
        instrumentation.runOnMainSync { mots.performClick() }
        instrumentation.waitForIdleSync()
        if (mots.isInTouchMode) {
            assertEquals("premier toucher : l'aperçu", ctx.getString(R.string.horloge_mots), nom.text.toString())
            assertNull("pas encore gardé", garde)
            instrumentation.runOnMainSync { mots.performClick() }
            instrumentation.waitForIdleSync()
        }
        assertEquals("mots", garde)
        // Une horloge de thème : l'aperçu passe à sa page.
        instrumentation.runOnMainSync { StylesHorloge.choisir(a, "t-code", veille = true) { garde = it } }
        instrumentation.waitForIdleSync(); SystemClock.sleep(600)
        val r2 = rootsDialogue()
        assertTrue("« Comme l'accueil » en tête pour la veille", r2.flatMap { vues(it) }.any { it.tag == "style_" + StylesHorloge.SUIVRE_ACCUEIL })
        assertTrue("aperçu d'une horloge de thème", r2.flatMap { vues(it) }.any { it is StylesHorloge.VueWeb })
        instrumentation.runOnMainSync { a.finish() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootsDialogue(): List<View> {
        val gm = Class.forName("android.view.WindowManagerGlobal")
        val g = gm.getMethod("getInstance").invoke(null)
        val f = gm.getDeclaredField("mViews").apply { isAccessible = true }
        var l: List<View> = emptyList()
        instrumentation.runOnMainSync { l = ArrayList(f.get(g) as List<View>) }
        return l.filter { r -> vues(r).any { it.tag == "horloge_apercu" } }.takeLast(1)
    }

    /** Le dessin de la veille de l'appli suit le style choisi pour elle (images gardées dans les fichiers de l'appli). */
    fun testLeDessinDeLaVeilleSuitLeStyle() {
        val dossier = java.io.File(ctx.getExternalFilesDir(null), "veille-styles").apply { deleteRecursively(); mkdirs() }
        fun image(style: String): Bitmap {
            Local.poserStyleHorlogeVeille(ctx, style)
            var v: VeilleView? = null
            instrumentation.runOnMainSync {
                v = VeilleView(ctx)  // sans demarrer() : le dessin suffit, rien ne reste en marche après l'essai
                val w = 1280; val h = 720
                v!!.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v!!.layout(0, 0, w, h)
            }
            var dessine = ""
            instrumentation.runOnMainSync { dessine = v!!.styleHorloge() }
            assertEquals("la veille dessine le style choisi pour elle", style, dessine)
            SystemClock.sleep(1500)  // l'arrivée de l'horloge
            var b: Bitmap? = null
            instrumentation.runOnMainSync { b = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).also { v!!.draw(Canvas(it)) } }
            java.io.FileOutputStream(java.io.File(dossier, "$style.png")).use { b!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return b!!
        }
        val anneau = image(HorlogesVeille.ANNEAU)
        val flip = image(HorlogesVeille.FLIP)
        var differents = 0; var total = 0
        for (x in 0 until 1280 step 8) for (y in 0 until 720 step 8) { total++; if (!proche(anneau.getPixel(x, y), flip.getPixel(x, y))) differents++ }
        assertTrue("le style « flip » se voit dans la veille ($differents / $total points différents)", differents > total / 50)
    }

    fun testLaVeilleSuitLAccueilOuSonStyle() {
        val t = Local.themeEffectif(ctx, Palette.APPLI)
        val theme = Themes.cle(t.nom)
        // Rien de choisi : la page garde l'horloge de son thème, le dessin de l'appli l'anneau.
        assertEquals("horloge=$theme", PageVeille.horloge(ctx, t))
        assertNull(Local.styleHorlogeVeilleChoisi(ctx))
        // L'accueil prend « fine » ; la veille suit.
        ZoneHaut.poserReglagesHorloge(ctx, ZoneHaut.reglagesHorloge(ctx).copy(style = HorlogesVeille.FINE), styleChoisi = true)
        assertEquals("horloge=$theme&hstyle=fine", PageVeille.horloge(ctx, t))
        // La veille a son propre style : une horloge de thème.
        Local.poserStyleHorlogeVeille(ctx, "t-banquise")
        assertEquals("horloge=banquise", PageVeille.horloge(ctx, t))
        assertEquals("t-banquise", Local.styleHorlogeVeilleChoisi(ctx))
        // De retour à « comme l'accueil ».
        Local.poserStyleHorlogeVeille(ctx, StylesHorloge.SUIVRE_ACCUEIL)
        assertEquals(HorlogesVeille.FINE, Local.styleHorlogeVeilleChoisi(ctx))
        assertEquals(ctx.getString(R.string.horloge_comme_accueil), StylesHorloge.nom(ctx, Local.styleHorlogeVeille(ctx)))
    }
}
