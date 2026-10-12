// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Refonte UX, lot 1 (zone Android) :
 *  - APP-03 : un serveur trouvé n'est contacté qu'après « Se relier » ; « Pas celui-ci » n'envoie rien (serveur local
 *    127.0.0.1 lancé par le test, qui compte les requêtes et refuse l'inscription) ;
 *  - APP-01 : appareil non relié : bandeau « Non relié » sur l'accueil, focus initial sur « Relier » à la TV ;
 *    carte d'état « Non relié » sur l'écran Bienvenue ;
 *  - APP-02 : écran Bienvenue : sur TV, focus initial visible sur l'action principale (QR), autres voies repliées.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.LiaisonEcransTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 *   (appareil SANS réseau vers le LAN : mode avion)
 */
class LiaisonEcransTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }
    private lateinit var serveur: ServerSocket
    private val recus = java.util.Collections.synchronizedList(ArrayList<String>())
    private var sauve: Map<String, *> = emptyMap<String, Any>()

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break }
        recus.add(premiere.split(" ").getOrElse(1) { "" })
        // Jamais d'inscription réelle : le serveur d'essai refuse.
        s.getOutputStream().apply { write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
    }

    override fun setUp() {
        super.setUp()
        sauve = HashMap(ctx.getSharedPreferences("vision", 0).all)
        if (android.os.Build.VERSION.SDK_INT >= 33) try { instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS") } catch (_: Exception) {}
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!serveur.isClosed) {
                val s = try { serveur.accept() } catch (_: Exception) { break }
                Thread { try { servir(s) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
        val cfg = Config(ctx)
        cfg.oublierInscription(); cfg.urlInterne = ""; cfg.urlExterne = ""; cfg.urlActive = ""; cfg.cleInscription = ""; cfg.sansCle = false
        Liaison.oublierEcartes()
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        try { ctx.stopService(Intent(ctx, AgentService::class.java)) } catch (_: Exception) {}
        Decouverte.trouve = null
        ctx.getSharedPreferences("vision", 0).edit().clear().apply {
            sauve.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        super.tearDown()
    }

    private fun capturer(nom: String) {
        val b = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun parTag(a: Activity, tag: String): View? {
        var v: View? = null
        instrumentation.runOnMainSync { v = a.window.decorView.findViewWithTag(tag) }
        return v
    }

    private fun textes(v: View, sortie: MutableList<String> = ArrayList()): List<String> {
        if (v is TextView && v.isShown) sortie.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    private fun ecran(a: Activity): String { var t = ""; instrumentation.runOnMainSync { t = textes(a.window.decorView).joinToString(" | ") }; return t }

    private fun focus(a: Activity): String { var t = ""; instrumentation.runOnMainSync { t = (a.currentFocus?.tag as? String).orEmpty() }; return t }

    private fun ouvrir(c: Class<out Activity>): Activity {
        val a = instrumentation.startActivitySync(Intent(ctx, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(1500)
        return a
    }

    /** La recherche (sans réseau) se termine, puis le test « trouve » le serveur local et repeint la carte. */
    private fun proposer(a: Activity, nom: String) {
        val fin = SystemClock.elapsedRealtime() + Decouverte.DELAI_MS + 3000
        while (Decouverte.enCours && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(300)
        assertFalse("la recherche devait se terminer", Decouverte.enCours)
        Decouverte.trouve = Liaison.Serveur(nom, "", "http://127.0.0.1:${serveur.localPort}")
        instrumentation.runOnMainSync {
            @Suppress("UNCHECKED_CAST")
            (a.javaClass.getDeclaredField("peindreDecouverte").apply { isAccessible = true }.get(a) as? () -> Unit)?.invoke()
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
    }

    fun testAucuneInscriptionSansConfirmation() {
        val a = ouvrir(SetupActivity::class.java)
        proposer(a, "Serveur d'essai")
        assertTrue(ecran(a), ecran(a).contains("Serveur d'essai"))
        assertNotNull(parTag(a, "liaison_oui")); assertNotNull(parTag(a, "liaison_non"))
        capturer("liaison_question")
        SystemClock.sleep(3000)
        assertEquals("aucune requête avant le choix", emptyList<String>(), ArrayList(recus))

        // « Pas celui-ci » : rien n'est envoyé, le serveur est écarté.
        parTag(a, "liaison_non")!!.let { v -> instrumentation.runOnMainSync { v.performClick() } }
        SystemClock.sleep(3000)
        assertEquals("« Pas celui-ci » n'envoie rien", emptyList<String>(), ArrayList(recus))
        assertFalse(Config(ctx).sansCle)
        assertNull(parTag(a, "liaison_oui"))

        // « Se relier » : la demande d'inscription part (et le serveur d'essai la refuse).
        Liaison.oublierEcartes()
        proposer(a, "Serveur d'essai")
        parTag(a, "liaison_oui")!!.let { v -> instrumentation.runOnMainSync { v.performClick() } }
        val fin = SystemClock.elapsedRealtime() + 15_000
        while (recus.none { it.contains("/enroll") } && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(300)
        assertTrue("« Se relier » doit envoyer l'inscription : $recus", recus.any { it.contains("/enroll") })
        a.finish()
    }

    fun testBienvenueNonRelieEtFocusInitial() {
        val a = ouvrir(SetupActivity::class.java)
        assertNotNull("carte d'état « Non relié »", parTag(a, "carte_non_relie"))
        assertNotNull(parTag(a, "bienvenue_principal"))
        assertEquals(View.GONE, parTag(a, "autres_methodes")!!.visibility)
        if (ReglagesTvActivity.estTele(ctx)) assertEquals("focus initial TV", "bienvenue_principal", focus(a))
        capturer(if (ReglagesTvActivity.estTele(ctx)) "bienvenue_tele" else "bienvenue_telephone")
        // « Autres méthodes » ouvre les voies repliées.
        var bascule: View? = null
        val fin = SystemClock.elapsedRealtime() + Decouverte.DELAI_MS + 3000
        while (SystemClock.elapsedRealtime() < fin) {
            bascule = listOf("autres_bascule", "liaison_autres").mapNotNull { parTag(a, it) }.firstOrNull { it.isShown }
            if (bascule != null) break
            SystemClock.sleep(300)
        }
        assertNotNull(bascule)
        instrumentation.runOnMainSync { bascule!!.performClick() }
        instrumentation.waitForIdleSync(); SystemClock.sleep(600)
        assertEquals(View.VISIBLE, parTag(a, "autres_methodes")!!.visibility)
        a.finish()
    }

    fun testBandeauNonRelieSurLAccueil() {
        val a = ouvrir(LanceurActivity::class.java)
        SystemClock.sleep(1000)
        assertNotNull("bandeau « Non relié »", parTag(a, "non_relie"))
        assertTrue(ecran(a), ecran(a).contains(ctx.getString(R.string.nonrelie_titre)))
        if (ReglagesTvActivity.estTele(ctx)) assertEquals("focus initial TV sur « Relier »", "non_relie_relier", focus(a))
        capturer(if (ReglagesTvActivity.estTele(ctx)) "accueil_non_relie_tele" else "accueil_non_relie_telephone")
        a.finish()
    }
}
