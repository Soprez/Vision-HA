// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/**
 * Carte « ☕ Payez-moi un café » (retour d'essai, photo de la télé) : sur la télé, en bas du menu des réglages,
 * dernier élément, atteinte à la télécommande, sans code ; sur le téléphone, en tête des Réglages de l'appli (15-10). Petit QR de la page
 * de don, badge seulement si le soutien est actif, texte jamais coupé. Captures dans files/captures/.
 *
 *   adb -s emulator-5592 shell am instrument -w -e class org.soprez.vision.SoutienCarteTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class SoutienCarteTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val tele get() = ReglagesTvActivity.estTele(ctx)

    private fun champ(o: Any, nom: String): Any? = o.javaClass.getDeclaredField(nom).apply { isAccessible = true }.get(o)

    private fun textes(v: View, sortie: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView && v.isShown) sortie.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    private fun touche(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync(); SystemClock.sleep(250) }

    private fun capturer(nom: String) {
        val b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val d = File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() }
        File(d, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // Intégration du 15/10 : hors télé, la carte est dans l'espace Réglages, que l'appli ne montre qu'une fois reliée
    // (sinon : l'écran de connexion). L'essai pose un appareil relié le temps de l'essai, puis remet l'état d'avant.
    private var sauveId: String? = null
    private var sauveSecret: String? = null

    override fun setUp() {
        super.setUp()
        if (!tele) { val c = Config(ctx); sauveId = c.id; sauveSecret = c.secret; if (!c.inscrit) { c.id = "essai_soutien"; c.secret = "s".repeat(32) } }
    }

    override fun tearDown() {
        if (!tele) { val c = Config(ctx); c.id = sauveId ?: ""; c.secret = sauveSecret ?: "" }
        super.tearDown()
    }

    private fun ouvrir(): Activity {
        val a = instrumentation.startActivitySync(EcransReglages.reglages(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(1200)
        return a
    }

    private fun carte(a: Activity): View {
        var v: View? = null
        instrumentation.runOnMainSync { v = a.window.decorView.findViewWithTag(if (tele) "r_soutien" else "soutien") }
        assertNotNull("carte Soutien absente", v)
        return v!!
    }

    /** Le texte de la carte est visible, en entier (ni ellipse, ni largeur nulle, au plus deux lignes). */
    private fun verifierTexte(c: View, attendus: List<String>) {
        instrumentation.runOnMainSync {
            val t = textes(c)
            for (s in attendus) {
                val tv = t.firstOrNull { it.text.toString() == s }
                assertNotNull("« $s » absent de la carte", tv)
                assertTrue("« $s » sans largeur", tv!!.width > 0 && tv.height > 0)
                val l = tv.layout
                assertNotNull(l); assertTrue("« $s » sur plus de 2 lignes", l!!.lineCount <= 2)
                for (i in 0 until l.lineCount) assertEquals("« $s » coupé", 0, l.getEllipsisCount(i))
            }
        }
    }

    private fun avecSoutien(actif: Boolean, bloc: () -> Unit) {
        val avant = Etat.soutien
        val voulu = if (actif) Soutien.Statut(true, "code") else Soutien.INACTIF
        // 1.87.0 : l'agent de l'émulateur (appairé à un vrai HA) réécrit l'état du soutien à chaque relevé : on le fige.
        val fige = java.util.concurrent.atomic.AtomicBoolean(true)
        val figeur = Thread { while (fige.get()) { Etat.soutien = voulu; SystemClock.sleep(50) } }
        Etat.soutien = voulu; figeur.start()
        try { bloc() } finally { fige.set(false); figeur.join(); Etat.soutien = avant }
    }


    /** l'auteur (14/10) : « Payez-MOI un café », au singulier, dans les 6 langues (plus jamais « nous », « us », « uns »…). */
    fun testTexteAuSingulier() {
        val attendus = mapOf(
            "fr" to "☕ Payez-moi un café", "en" to "☕ Buy me a coffee", "de" to "☕ Spendier mir einen Kaffee",
            "es" to "☕ Invítame a un café", "it" to "☕ Offrimi un caffè", "nl" to "☕ Trakteer me op koffie")
        for ((lg, texte) in attendus) {
            val conf = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale(lg)) }
            assertEquals("texte de la carte ($lg)", texte, ctx.createConfigurationContext(conf).getString(R.string.soutien_carte))
        }
    }

    fun testCarteSansBadge() = avecSoutien(false) {
        val a = ouvrir()
        try {
            val c = carte(a)
            verifierTexte(c, listOf(ctx.getString(R.string.soutien_carte)))
            instrumentation.runOnMainSync {
                assertTrue("la carte doit prendre le focus", c.isFocusable)
                assertNull("pas de badge sans soutien", textes(c).firstOrNull { it.text.toString() == ctx.getString(R.string.soutien_badge) })
                val qr = (c as ViewGroup).findViewWithTag<ImageView>("qr_soutien")
                assertNotNull("QR absent", qr)
            }
            if (tele) {
                // Dernier élément focalisable du menu : on y descend depuis la dernière section, et on y reste.
                val rail = champ(a, "rail") as ViewGroup
                var dernier: View? = null
                for (i in 0 until rail.childCount) rail.getChildAt(i).let { if (it.isFocusable) dernier = it }
                assertSame("la carte doit être le dernier élément du menu", c, dernier)
                val sections = (0 until rail.childCount).map { rail.getChildAt(it) }.filter { (it.tag as? String)?.let { t -> t.startsWith("r_") && t != "r_soutien" } == true }
                instrumentation.runOnMainSync { sections.last().requestFocus() }
                instrumentation.waitForIdleSync()
                capturer("soutien_carte_tv_repos")
                touche(KeyEvent.KEYCODE_DPAD_DOWN)
                assertTrue("▼ depuis la dernière section doit atteindre la carte", c.isFocused)
                capturer("soutien_carte_tv_focus")
                touche(KeyEvent.KEYCODE_DPAD_DOWN)
                assertTrue("▼ sur la carte : le focus reste", c.isFocused)
                // ▲ revient à la dernière section.
                touche(KeyEvent.KEYCODE_DPAD_UP)
                assertTrue("▲ depuis la carte revient à la dernière section", sections.last().isFocused)
            } else {
                // 15-10 (retour d'essai) : le café saute aux yeux dès l'entrée dans les Réglages : entier à l'écran, sans défiler.
                instrumentation.runOnMainSync {
                    val r = android.graphics.Rect()
                    assertTrue("carte Soutien hors de l'écran à l'ouverture", c.getGlobalVisibleRect(r))
                    assertEquals("carte Soutien coupée à l'ouverture (il faudrait défiler)", c.height, r.height())
                }
                capturer("soutien_carte_telephone")
            }
        } finally { a.finish(); instrumentation.waitForIdleSync() }
    }

    fun testCarteAvecBadge() = avecSoutien(true) {
        val a = ouvrir()
        try {
            val c = carte(a)
            verifierTexte(c, listOf(ctx.getString(R.string.soutien_carte), ctx.getString(R.string.soutien_badge)))
            if (tele) capturer("soutien_carte_tv_badge")
            else capturer("soutien_carte_telephone_badge")  // en tête des Réglages : rien à faire défiler
        } finally { a.finish(); instrumentation.waitForIdleSync() }
    }
}
