// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * 1.90.2 — recette des écrans nouveaux de l'appairage Sendspin (exigence de l'auteur : « jamais le code à l'écran ») :
 * Réglages › Décideurs ouvert (lecteur Sendspin : état de l'appairage, « Appairer à la main »), puis l'écran
 * d'appairage à la main (« Connexion à la musique… », code, réussite, échec sans code). Aucun texte coupé ni hors de
 * l'écran ; une capture par écran dans files/recette-appairage (taille et police au choix de l'émulateur).
 *
 *   adb shell am instrument -w -e class org.soprez.vision.RecetteAppairageTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class RecetteAppairageTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private var moteurAvant: String? = null
    private var visionAvant: Map<String, *> = emptyMap<String, Any>()
    private val dossier get() = File(ctx.filesDir, "recette-appairage").apply { mkdirs() }

    override fun setUp() {
        super.setUp()
        moteurAvant = ctx.getSharedPreferences("lecteur", 0).getString("moteur", null)
        ctx.getSharedPreferences("lecteur", 0).edit().putString("moteur", LecteurMusique.SENDSPIN).commit()
        // Jamais le vrai Home Assistant : le faux code de l'essai ne doit partir nulle part.
        visionAvant = HashMap(ctx.getSharedPreferences("vision", 0).all)
        Config(ctx).apply { urlInterne = "http://127.0.0.1:9"; urlExterne = ""; urlActive = "http://127.0.0.1:9" }
        instrumentation.runOnMainSync { LecteurSendspin.essaiRaz() }
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { AppairageSendspinActivity.fermer(); LecteurSendspin.essaiRaz() }
        ctx.getSharedPreferences("vision", 0).edit().clear().apply {
            visionAvant.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x) } }
        }.commit()
        ctx.getSharedPreferences("lecteur", 0).edit().apply { if (moteurAvant == null) remove("moteur") else putString("moteur", moteurAvant) }.commit()
        super.tearDown()
    }

    private fun champ(o: Any, nom: String): Any? = o.javaClass.getDeclaredField(nom).apply { isAccessible = true }.get(o)
    private fun poser(o: Any, nom: String, v: Any) = o.javaClass.getDeclaredField(nom).apply { isAccessible = true }.set(o, v)

    private fun textes(v: View, sortie: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView && v.isShown) sortie.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    /** Aucun texte coupé (ellipse) ni sorti de l'écran ; renvoie le texte visible. */
    private fun verifier(a: Activity, nom: String): String {
        val defauts = ArrayList<String>()
        val vus = StringBuilder()
        instrumentation.runOnMainSync {
            val ecran = Rect(); a.window.decorView.getGlobalVisibleRect(ecran)
            for (tv in textes(a.window.decorView)) {
                vus.append(tv.text).append(" | ")
                val l = tv.layout
                if (l != null) for (i in 0 until l.lineCount) if (l.getEllipsisCount(i) > 0) defauts.add("coupé : « ${tv.text} »")
                val r = Rect(); val loc = IntArray(2); tv.getLocationOnScreen(loc)
                r.set(loc[0], loc[1], loc[0] + tv.width, loc[1] + tv.height)
                if (r.right > ecran.right + 1 || r.left < ecran.left - 1) defauts.add("hors de l'écran : « ${tv.text} » $r")
            }
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { b -> FileOutputStream(File(dossier, "$nom.png")).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue("$nom : $defauts", defauts.isEmpty())
        return vus.toString()
    }

    fun testEcransAppairage() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        // 1. Réglages › Décideurs ouvert (comme après le code), lecteur Sendspin pas encore appairé.
        val a = instrumentation.startActivitySync(Intent(ctx, ReglagesTvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(1200)
        instrumentation.runOnMainSync {
            poser(a, "decideursJusqua", SystemClock.elapsedRealtime() + 5 * 60_000L)
            (champ(a, "rail") as ViewGroup).findViewWithTag<View>("r_decideurs")?.requestFocus()
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(800)
        val dec = verifier(a, "1-decideurs-connexion")
        assertTrue(dec, dec.contains(ctx.getString(R.string.reglagestv_appairage_musique)) && dec.contains(ctx.getString(R.string.reglagestv_appairer_main)))
        assertFalse("un code dans Décideurs : $dec", Regex("\\d{3} *\\d{3}").containsMatchIn(dec))
        a.finish()
        // 2. L'appairage à la main : d'abord sans code, puis le code de Music Assistant, puis l'issue.
        val moniteur = instrumentation.addMonitor(AppairageSendspinActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { LecteurSendspin.appairerALaMain(ctx) }
        val p = instrumentation.waitForMonitorWithTimeout(moniteur, 5_000)
        assertNotNull(p); SystemClock.sleep(800)
        val attente = verifier(p, "2-a-la-main-connexion")
        assertTrue(attente, attente.contains(ctx.getString(R.string.appairage_connexion)))
        instrumentation.runOnMainSync { LecteurSendspin.essaiEtat(ctx, SendspinClient.Etat(connecte = true, confiance = "none", appairage = "commence", pin = "482913")) }
        SystemClock.sleep(1200)
        val code = verifier(p, "3-a-la-main-code")
        assertTrue(code, code.contains("482  913"))
        instrumentation.runOnMainSync { LecteurSendspin.essaiEtat(ctx, SendspinClient.Etat(connecte = true, confiance = "none", appairage = "abandon")) }
        SystemClock.sleep(1000)
        val echec = verifier(p, "4-a-la-main-echec")
        assertTrue(echec, echec.contains(ctx.getString(R.string.appairage_echec)))
        assertFalse("code dans l'erreur : $echec", echec.contains("482"))
        instrumentation.runOnMainSync { LecteurSendspin.essaiEtat(ctx, SendspinClient.Etat(connecte = true, confiance = "user", appairage = "fini")) }
        SystemClock.sleep(800)
        verifier(p, "5-a-la-main-reussi")
        instrumentation.removeMonitor(moniteur)
    }
}
