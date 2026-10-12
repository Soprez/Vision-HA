// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.UiAutomation
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * 1.78.0 (demande d'essai) — sur la télé : le menu d'une tuile d'appli propose « Forcer l'arrêt », « Vider le cache »,
 * « Infos de l'appli » ; chaque geste ouvre la page système de l'appli, le focus de la télécommande posé sur le bon
 * bouton par le service d'accessibilité de Vision. Sauté hors télé.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.ActionsAppliTvTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class ActionsAppliTvTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val cible = "com.google.android.youtube.tv"
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }
    private lateinit var ui: UiAutomation

    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use { it.readBytes() }
    }

    private fun tele() = (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    override fun setUp() {
        super.setUp()
        // Le service d'accessibilité de Vision doit continuer à tourner pendant le test.
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // « am instrument » relance le processus de Vision : Android marque alors son service d'accessibilité comme
        // planté et ne le relie plus. On le retire puis on le remet, et on attend qu'il tourne.
        shell("settings put secure enabled_accessibility_services null")
        SystemClock.sleep(800)
        shell("settings put secure enabled_accessibility_services ${ctx.packageName}/${VisionAccessibility::class.java.name}")
        shell("settings put secure accessibility_enabled 1")
        val fin = SystemClock.elapsedRealtime() + 8000
        while (!VisionAccessibility.actif && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(200)
        assertTrue("service d'accessibilité de Vision non relié", VisionAccessibility.actif)
        // 1.78.1 : comme un enfant devant la télé, sans code (Forcer l'arrêt et Vider le cache sont libres).
        Garde.fermer()
        SystemClock.sleep(1500)
    }

    override fun tearDown() {
        Garde.fermer()
        shell("input keyevent KEYCODE_HOME")
        super.tearDown()
    }

    private fun accueil(): Activity {
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(1500)
        return a
    }

    private fun textes(n: AccessibilityNodeInfo?, sortie: MutableList<String> = ArrayList()): MutableList<String> {
        if (n == null) return sortie
        n.text?.let { sortie.add(it.toString()) }
        for (i in 0 until n.childCount) textes(n.getChild(i), sortie)
        return sortie
    }

    private fun capturer(nom: String) {
        val b: Bitmap = ui.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Le texte de l'élément qui a le focus de la télécommande dans la fenêtre active (et de ses enfants). */
    private fun focus(): Pair<String, String> {
        val r = ui.rootInActiveWindow ?: return "" to ""
        val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        return (r.packageName?.toString() ?: "") to textes(f).joinToString(" ")
    }

    private fun attendreFocus(motifs: List<String>): Pair<String, String> {
        val fin = SystemClock.elapsedRealtime() + 12_000
        var vu = "" to ""
        while (SystemClock.elapsedRealtime() < fin) {
            vu = focus()
            if (vu.first.contains("settings") && motifs.any { vu.second.contains(it, ignoreCase = true) }) return vu
            SystemClock.sleep(300)
        }
        return vu
    }

    fun testMenuDeLaTuile() {
        if (!tele()) return
        val a = accueil()
        val c = Accueil.Case("essai", cible, null)
        instrumentation.runOnMainSync {
            val m = a.javaClass.declaredMethods.first { it.name == "menuTele" }
            m.isAccessible = true; m.invoke(a, c, listOf("essai"))
        }
        SystemClock.sleep(1200)
        val t = textes(ui.rootInActiveWindow)
        capturer("tv_menu_tuile")
        for (x in listOf("Forcer l'arrêt", "Vider le cache", "Infos de l'appli")) assertTrue("« $x » absent : $t", t.contains(x))
        shell("input keyevent KEYCODE_BACK")
    }

    private fun ouvrirDecideur() =
        Garde::class.java.getDeclaredField("jusqua").apply { isAccessible = true }.setLong(Garde, SystemClock.elapsedRealtime() + 600_000L)

    private fun pid(): String = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("pidof $cible")).use { String(it.readBytes()).trim() }

    /** 1.78.2 : sans code, sans page système : l'appli est vraiment arrêtée (Android 13 et moins). */
    fun testForcerArretSansCodeSansPage() {
        if (!tele() || !ActionsAppli.arretDirectPossible()) return
        shell("monkey -p $cible -c android.intent.category.LEANBACK_LAUNCHER 1")
        SystemClock.sleep(7000)
        assertTrue("YouTube ne tourne pas avant l'essai", pid().isNotEmpty())
        val a = accueil()
        instrumentation.runOnMainSync { ActionsAppli.forcerArret(a, cible, "YouTube") }
        SystemClock.sleep(3000)
        val page = ui.rootInActiveWindow?.packageName?.toString() ?: ""
        capturer("tv_forcer_arret_direct")
        assertFalse("une page système s'est ouverte : $page", page.contains("settings"))
        assertEquals("YouTube tourne encore", "", pid())
    }

    /** Vider le cache passe forcément par la page système : sans code, rien ne s'ouvre. */
    fun testViderLeCacheDemandeLeCode() {
        if (!tele()) return
        val a = accueil()
        instrumentation.runOnMainSync { ActionsAppli.viderCache(a, cible, "YouTube") }
        SystemClock.sleep(2500)
        val page = ui.rootInActiveWindow?.packageName?.toString() ?: ""
        capturer("tv_vider_cache_sans_code")
        // Lot Accueil (point 7, demande d'essai) : avec le service d'accessibilité de Vision, la page système s'ouvre
        // SANS code en mode restreint (seul « Vider le cache » reste libre) ; sans lui, le code d'abord.
        if (VisionAccessibility.actif) assertTrue("page restreinte attendue : $page", page.contains("settings"))
        else assertFalse("la page système s'est ouverte sans code : $page", page.contains("tv.settings"))
        shell("input keyevent KEYCODE_BACK")
    }

    fun testViderLeCacheDecideurFocus() {
        if (!tele()) return
        ouvrirDecideur()
        val a = accueil()
        instrumentation.runOnMainSync { ActionsAppli.viderCache(a, cible, "YouTube") }
        val (pkg, texte) = attendreFocus(listOf("cache"))
        capturer("tv_vider_cache")
        assertTrue("page : $pkg, focus : « $texte »", pkg.contains("settings") && texte.contains("cache", ignoreCase = true))
        shell("input keyevent KEYCODE_BACK")
    }

    fun testInfosReserveesAuxDecideurs() {
        if (!tele()) return
        val a = accueil()
        instrumentation.runOnMainSync { ActionsAppli.infosProtegees(a, cible) }
        SystemClock.sleep(2500)
        val pkg = ui.rootInActiveWindow?.packageName?.toString() ?: ""
        capturer("tv_infos_sans_code")
        assertFalse("la page système s'est ouverte sans code : $pkg", pkg.contains("tv.settings"))
        shell("input keyevent KEYCODE_BACK")
    }

    fun testDecideurPageEntiere() {
        if (!tele()) return
        ouvrirDecideur()
        val a = accueil()
        instrumentation.runOnMainSync { ActionsAppli.infosProtegees(a, cible) }
        SystemClock.sleep(3000)
        assertTrue((ui.rootInActiveWindow?.packageName?.toString() ?: "").contains("settings"))
        shell("input keyevent KEYCODE_BACK")
    }

    /** Android 14 et plus : pas d'arrêt direct possible ; sans code rien ne s'ouvre, avec code la page et le focus. */
    fun testForcerArretAndroid14() {
        if (!tele() || ActionsAppli.arretDirectPossible()) return
        val a = accueil()
        instrumentation.runOnMainSync { ActionsAppli.forcerArret(a, cible, "YouTube") }
        SystemClock.sleep(2500)
        val page = ui.rootInActiveWindow?.packageName?.toString() ?: ""
        // Lot Accueil (point 7) : page restreinte sans code quand l'accessibilité de Vision tourne, code sinon.
        if (VisionAccessibility.actif) assertTrue("page restreinte attendue : $page", page.contains("settings"))
        else assertFalse("la page système s'est ouverte sans code : $page", page.contains("tv.settings"))
        shell("input keyevent KEYCODE_BACK")
        ouvrirDecideur()
        val b = accueil()
        instrumentation.runOnMainSync { ActionsAppli.forcerArret(b, cible, "YouTube") }
        val (pkg, texte) = attendreFocus(listOf("stop", "arrêt"))
        capturer("tv_forcer_arret_decideur")
        assertTrue("page : $pkg, focus : « $texte »", pkg.contains("settings") && (texte.contains("stop", true) || texte.contains("arrêt", true)))
        shell("input keyevent KEYCODE_BACK")
    }
}
