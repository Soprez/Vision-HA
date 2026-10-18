// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

/**
 * 1.91.2 (l'auteur : « je ne vois pas les widgets sur la télé ») : la zone du haut de la télé, un tableau de bord.
 * - télé reliée, rien de choisi : l'horloge stylisée puis les widgets partagés par Home Assistant (quatre au plus, dans
 *   l'ordre de HA) ; une zone restée au défaut d'avant (« classique ») devient ce tableau ;
 * - le choix du Décideur (composer) est gardé ; « Tableau par défaut » y revient ;
 * - télécommande : le widget a le focus, bas fait défiler son contenu, puis le focus repart ;
 * - avant d'être reliée : le merci et l'œil joyeux.
 * Catalogue d'essai : AntiDebordementTest.CATALOGUE_WIDGETS (aucun Home Assistant). Sur un téléphone : rien à essayer.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.TableauTvEcranTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class TableauTvEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val sauves = HashMap<String, Map<String, *>>()
    private val tele get() = ReglagesTvActivity.estTele(ctx)

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
        for (n in listOf("vision", "lanceur", "local", "widgets_ha")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        WidgetsHaStock.garderCatalogue(ctx, AntiDebordementTest.CATALOGUE_WIDGETS)
        FluxWidgets.etats.clear()
        val cfg = Config(ctx)
        cfg.id = "essai_tableau"; cfg.secret = "s".repeat(32)
        ZoneHaut.revenirAuDefaut(ctx)
    }

    override fun tearDown() {
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) ->
                when (x) {
                    is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x)
                    is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>)
                }
            }
        }.commit()
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    private fun vues(v: View, l: ArrayList<View> = ArrayList()): ArrayList<View> {
        l.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), l); return l
    }

    private fun accueil(): Activity {
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(800)
        return a
    }

    private fun zone(a: Activity): View? { var z: View? = null; instrumentation.runOnMainSync { z = a.window.decorView.findViewWithTag(ZoneHaut.TAG) }; return z }

    /** Les widgets partagés, dans l'ordre du catalogue de l'appareil (celui que suit le tableau par défaut). */
    private val ha get() = ZoneHaut.partagesHa(ctx)

    fun testDefautEtChoix() {
        if (!tele) return
        // Rien de choisi : l'horloge et les quatre premiers widgets partagés, dans l'ordre de Home Assistant.
        assertEquals(listOf(ZoneHaut.HORLOGE) + ha.take(4), ZoneHaut.widgets(ctx))
        // Une zone restée au défaut d'avant (« classique », enregistrée avant 1.91.2) devient le tableau de bord.
        ctx.getSharedPreferences("local", 0).edit().putString("zone_haut_widgets", "[\"heure_meteo\"]").commit()
        assertEquals(listOf(ZoneHaut.HORLOGE) + ha.take(4), ZoneHaut.widgets(ctx))
        // Le choix du Décideur est gardé, dans son ordre.
        ZoneHaut.poser(ctx, listOf(ha[4], ZoneHaut.HORLOGE))
        assertEquals(listOf(ha[4], ZoneHaut.HORLOGE), ZoneHaut.widgets(ctx))
        assertEquals("le nom donné dans HA", WidgetsHaStock.entite(ctx, ZoneHaut.entiteHa(ha[4]))!!.nom, ZoneHaut.nom(ctx, ha[4]))
        // « Tableau par défaut » : de retour au défaut, qui suit Home Assistant.
        ZoneHaut.revenirAuDefaut(ctx)
        assertEquals(listOf(ZoneHaut.HORLOGE) + ha.take(4), ZoneHaut.widgets(ctx))
        // Pas encore reliée : le merci (et l'œil joyeux).
        Config(ctx).id = ""
        assertEquals(listOf(ZoneHaut.REMERCIEMENT), ZoneHaut.widgets(ctx))
    }

    /** À l'écran : l'horloge stylisée et les widgets dans la zone du haut, focalisables, au-dessus des applications. */
    fun testTableauALEcran() {
        if (!tele) return
        val a = accueil()
        try {
            val z = zone(a)
            assertNotNull("zone du haut absente", z)
            var tags = emptyList<String>(); var focalisables = 0; var haut = 0; var hautApplis = Int.MAX_VALUE
            instrumentation.runOnMainSync {
                val l = vues(z!!)
                tags = l.mapNotNull { it.tag as? String }
                focalisables = l.count { (it.tag as? String)?.startsWith(ZoneHaut.PREFIXE_HA) == true && it.isFocusable }
                val p = IntArray(2); z.getLocationOnScreen(p); haut = p[1] + z.height
                vues(a.window.decorView).firstOrNull { it.tag == "rangee_applis" || it.tag == "applis" }?.let { r -> r.getLocationOnScreen(p); hautApplis = p[1] }
            }
            // L'image de l'accueil (revue) : fichiers de l'appli, « tableau-tele.png ».
            SystemClock.sleep(1500)
            instrumentation.runOnMainSync {
                val r = a.window.decorView.rootView
                val b = android.graphics.Bitmap.createBitmap(r.width, r.height, android.graphics.Bitmap.Config.ARGB_8888)
                r.draw(android.graphics.Canvas(b))
                java.io.FileOutputStream(java.io.File(ctx.getExternalFilesDir(null), "tableau-tele.png")).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue("horloge stylisée : $tags", "zone_horloge" in tags)
            for (c in ha.take(4)) assertTrue("$c absent : $tags", c in tags)
            assertFalse("le cinquième n'est pas montré par défaut", ha[4] in tags)
            assertEquals("chaque widget se prend à la télécommande", 4, focalisables)
            if (hautApplis != Int.MAX_VALUE) assertTrue("au-dessus des applications ($haut / $hautApplis)", haut <= hautApplis)
        } finally { a.finish(); instrumentation.waitForIdleSync() }
    }

    /** Télécommande : le focus sur la longue liste, bas fait défiler le contenu, puis, au bout, le focus repart. */
    fun testTelecommande() {
        if (!tele) return
        val longue = ZoneHaut.PREFIXE_HA + "sensor.liste_longue"
        ZoneHaut.poser(ctx, listOf(ZoneHaut.HORLOGE, longue))
        val a = accueil()
        try {
            var tuile: View? = null
            // Comme sur une vraie télé : la télécommande, pas le toucher (en mode tactile, le cadre ne prend pas le focus).
            instrumentation.setInTouchMode(false)
            var pris = false; var diag = ""
            instrumentation.runOnMainSync {
                tuile = a.window.decorView.findViewWithTag(longue); pris = tuile?.requestFocus() == true
                var p = tuile?.parent; val l = ArrayList<String>()
                while (p is ViewGroup) { l.add("${p.javaClass.simpleName}:${p.descendantFocusability}:${p.tag}"); p = p.parent }
                diag = "pris=$pris shown=${tuile?.isShown} focusable=${tuile?.isFocusable} touch=${tuile?.isInTouchMode} parents=$l"
            }
            assertNotNull("le widget de la liste longue est dans la zone", tuile)
            instrumentation.waitForIdleSync()
            assertTrue("il a le focus : $diag", tuile!!.isFocused)
            var d: DefilementWidget? = null
            instrumentation.runOnMainSync { d = tuile!!.findViewWithTag("defilement_widget") }
            assertNotNull("défilement activé par défaut", d)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            SystemClock.sleep(400)
            assertTrue("bas : le contenu défile (${d!!.scrollY})", d!!.scrollY > 0)
            assertTrue("le focus reste sur le widget", tuile!!.isFocused)
            for (i in 0 until 40) { if (!tuile!!.isFocused) break; instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); SystemClock.sleep(120) }
            assertFalse("au bout, le focus repart vers les applications", tuile!!.isFocused)
        } finally { a.finish(); instrumentation.waitForIdleSync() }
    }
}
