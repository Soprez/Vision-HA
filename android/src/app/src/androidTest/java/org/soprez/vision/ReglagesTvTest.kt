// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 1.84.0 (point 21) — réglages de la télé selon la maquette docs/maquettes/tv-reglages.html : un menu de
 * sections, une page par section avec une action principale, la télécommande seule suffit, aucun texte
 * coupé, la section Décideurs fermée sans le code.
 *
 *   adb -s emulator-5556 shell am instrument -w -e class org.soprez.vision.ReglagesTvTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 *   (#testVisiteLente : chaque écran reste 3 s, pour les captures)
 */
class ReglagesTvTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    private fun champ(o: Any, nom: String): Any? = o.javaClass.getDeclaredField(nom).apply { isAccessible = true }.get(o)

    private fun textes(v: View, sortie: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView && v.isShown) sortie.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    private fun touche(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync(); SystemClock.sleep(250) }

    private fun page(a: Activity): String {
        var t = ""
        instrumentation.runOnMainSync { t = textes(champ(a, "contenu") as View).joinToString(" | ") { it.text.toString() } }
        return t
    }

    private fun focus(a: Activity): String {
        var t = ""
        instrumentation.runOnMainSync { t = (a.currentFocus?.tag as? String).orEmpty() }
        return t
    }

    /** Aucun texte coupé : chaque ligne tient (pas d'ellipse, pas de dépassement). */
    private fun sansTroncature(a: Activity) {
        instrumentation.runOnMainSync {
            for (tv in textes(a.window.decorView)) {
                val l = tv.layout ?: continue
                for (i in 0 until l.lineCount) assertEquals("texte coupé : « ${tv.text} »", 0, l.getEllipsisCount(i))
            }
        }
    }

    private var lent = false
    private fun attendre() { if (lent) SystemClock.sleep(3000) }

    /** 1.87.0 (« sursaut » vu par l'auteur) : 20 appuis bas puis 20 appuis haut dans le menu ; le focus passe d'un élément
     *  à son voisin, jamais par un autre (avant : « Accueil » un instant à chaque section). */
    fun testFocusSansSursaut() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        val a = instrumentation.startActivitySync(Intent(ctx, ReglagesTvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(1200)
        val rail = champ(a, "rail") as ViewGroup
        val ordre = ArrayList<String>()
        for (i in 0 until rail.childCount) (rail.getChildAt(i).tag as? String)?.takeIf { it.startsWith("r_") }?.let { ordre.add(it) }
        instrumentation.runOnMainSync { rail.findViewWithTag<View>(ordre.first())?.requestFocus() }
        instrumentation.waitForIdleSync()
        val vus = java.util.Collections.synchronizedList(ArrayList<String>())
        instrumentation.runOnMainSync {
            a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, nouveau -> vus.add((nouveau?.tag as? String) ?: nouveau?.javaClass?.simpleName ?: "rien") }
        }
        repeat(20) { touche(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(20) { touche(KeyEvent.KEYCODE_DPAD_UP) }
        SystemClock.sleep(300)
        val suite = synchronized(vus) { ArrayList(vus) }
        // Chaque événement de focus est un voisin direct du précédent dans le menu (ou rien de nouveau aux bouts).
        var avant = ordre.first()
        for (x in suite) {
            assertTrue("focus hors du menu : $x (suite : $suite)", x in ordre)
            val ecart = Math.abs(ordre.indexOf(x) - ordre.indexOf(avant))
            assertTrue("sursaut de $avant à $x (suite : $suite)", ecart == 1)
            avant = x
        }
        assertEquals("ordre : $ordre ; suite : $suite", (ordre.size - 1) * 2, suite.size)
        a.finish()
    }

    /**
     * 1.86.7 (l'auteur : « quand ça descend, le focus entoure Accueil ») : dans chaque section, on entre dans la page et on
     * descend jusqu'en bas : le focus ne repasse JAMAIS par le menu (ni « Accueil », ni une autre section).
     */
    fun testDescendreDansLesPagesSansPasserParAccueil() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        val a = instrumentation.startActivitySync(Intent(ctx, ReglagesTvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(1200)
        val rail = champ(a, "rail") as ViewGroup
        val ordre = ArrayList<String>()
        for (i in 0 until rail.childCount) (rail.getChildAt(i).tag as? String)?.takeIf { it.startsWith("r_") }?.let { ordre.add(it) }
        val vus = java.util.Collections.synchronizedList(ArrayList<String>())
        instrumentation.runOnMainSync {
            a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, nouveau -> vus.add((nouveau?.tag as? String) ?: (nouveau as? android.widget.TextView)?.text?.toString() ?: nouveau?.javaClass?.simpleName ?: "rien") }
        }
        for (section in ordre) {
            instrumentation.runOnMainSync { rail.findViewWithTag<View>(section)?.requestFocus() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            touche(KeyEvent.KEYCODE_DPAD_RIGHT)
            synchronized(vus) { vus.clear() }
            repeat(15) { touche(KeyEvent.KEYCODE_DPAD_DOWN) }
            SystemClock.sleep(300)
            val suite = synchronized(vus) { ArrayList(vus) }
            assertTrue("$section : le focus est repassé par le menu en descendant : $suite", suite.none { it.startsWith("r_") })
            touche(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            if (a.isFinishing) break
        }
        a.finish()
    }

    /** La même visite, lente (3 s par écran), pour les captures du rendu réel. */
    fun testVisiteLente() { lent = true; testSectionsPagesEtDecideurs() }

    fun testSectionsPagesEtDecideurs() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        val a = instrumentation.startActivitySync(Intent(ctx, ReglagesTvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(1500)
        instrumentation.runOnMainSync { (champ(a, "rail") as ViewGroup).let { r -> textes(r).firstOrNull { (it.tag as? String)?.startsWith("r_") == true }?.requestFocus() } }
        instrumentation.waitForIdleSync()
        // Le menu : chaque section, et la page qui suit le focus.
        val vues = LinkedHashMap<String, String>()
        repeat(7) {
            val f = focus(a)
            // La carte Soutien (r_soutien) est en bas du menu mais n'est pas une section.
            if (f.startsWith("r_") && f != "r_soutien") vues[f] = page(a)
            sansTroncature(a); attendre()
            touche(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertTrue("sections vues : ${vues.keys}", vues.keys.containsAll(listOf("r_affichage", "r_accueil", "r_son", "r_parametres", "r_decideurs")))
        assertTrue(vues["r_affichage"]!!.contains("Thème de couleurs"))
        assertTrue(vues["r_accueil"]!!.contains("Choisir les applis visibles"))
        val p = vues["r_parametres"]!!
        for (x in listOf("APPAREIL", "PERSONNE", "CONNEXION", "À PROPOS", "Version")) assertTrue("« $x » absent : $p", p.contains(x))
        assertFalse(p, p.contains("Nom, personne, connexion"))
        // 1.86.0 (retour d'essai) : les sections par ordre alphabétique (selon la langue de l'appareil).
        val ordre = vues.keys.toList()
        assertEquals(listOf("r_accueil", "r_affichage", "r_decideurs", "r_veille", "r_parametres", "r_son").filter { it in ordre }, ordre)
        // Décideurs : fermé sans le code, rien de sensible n'est montré.
        val dec = vues["r_decideurs"]!!
        assertTrue(dec, dec.contains("Réservé aux décideurs") && dec.contains("Entrer le code"))
        assertFalse(dec, dec.contains("Protections de la télé"))
        // ► entre dans la page : l'action principale prend le focus.
        instrumentation.runOnMainSync { (champ(a, "rail") as ViewGroup).findViewWithTag<View>("r_affichage")?.requestFocus() }
        instrumentation.waitForIdleSync()
        touche(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("theme", focus(a))
        // Retour : de la page au menu, sans fermer.
        touche(KeyEvent.KEYCODE_BACK)
        assertEquals("r_affichage", focus(a))
        assertFalse(a.isFinishing)
        // Sous-page des cartes (si la veille et ses tableaux sont là), et retour à la section.
        if (vues.containsKey("r_veille") && vues["r_veille"]!!.contains("Cartes de Home Assistant")) {
            instrumentation.runOnMainSync { (champ(a, "rail") as ViewGroup).findViewWithTag<View>("r_veille")?.requestFocus() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            instrumentation.runOnMainSync { (champ(a, "contenu") as ViewGroup).findViewWithTag<View>("sp_cartes")?.performClick() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            assertTrue(page(a), page(a).contains("Style des cartes"))
            sansTroncature(a); attendre()
            touche(KeyEvent.KEYCODE_BACK)
            assertTrue(page(a), page(a).contains("Voir l'écran de veille"))
        }
        a.finish()
    }
}
