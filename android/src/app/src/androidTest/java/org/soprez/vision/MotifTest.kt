// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.AlertDialog
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

/**
 * 1.82.2 — bug signalé par Alex : sur l'écran de blocage, la fenêtre « Pourquoi ? » se fermait toute seule pendant
 * qu'il écrivait (le clavier passait au premier plan, l'écran croyait qu'il avait quitté l'appli et se retirait).
 * Écran de blocage → « Demander » → clavier ouvert → 30 s de saisie → envoi → « Demande envoyée ».
 *
 *   adb shell am instrument -w -e class org.soprez.vision.MotifTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class MotifTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    @Volatile private var recu: JSONObject? = null
    private val sauves = HashMap<String, Map<String, *>>()
    private val pkg = "com.exemple.bloquee"

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        if (premiere.contains("/api/pc_parental/demande")) recu = JSONObject(String(buf, 0, lu))
        // Le relevé (/poll) garde l'appli fermée, comme Home Assistant le ferait.
        val octets = (if (premiere.contains("/poll")) JSONObject().put("ok", true).put("action", "none").put("poll", 60)
            .put("apps", org.json.JSONArray().put(pkg)).put("motifs", org.json.JSONArray().put("regle|Fermée par une règle"))
            .put("raisons", JSONObject().put(pkg, 0)).toString() else "{\"ok\":true}").toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    override fun setUp() {
        super.setUp()
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!serveur.isClosed) {
                val s = try { serveur.accept() } catch (_: Exception) { break }
                Thread { try { servir(s) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
        sauves["vision"] = HashMap(ctx.getSharedPreferences("vision", 0).all)
        val cfg = Config(ctx)
        cfg.id = "essai_motif"; cfg.secret = "s".repeat(32); cfg.parentJusqua = 0L
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        super.tearDown()
    }

    private fun vues(v: View, sortie: ArrayList<View> = ArrayList()): ArrayList<View> {
        sortie.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), sortie)
        return sortie
    }

    private fun champ(o: Any, nom: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) { try { return c.getDeclaredField(nom).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass } }
        throw AssertionError("champ $nom")
    }

    /** Le paquet « au premier plan » que verrait l'accessibilité ; tenu par un fil (l'émulateur n'a pas l'appli). */
    @Volatile private var premierPlan = ""

    fun testMotifReste30sPuisDemandeEnvoyee() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val appsAvant = Etat.apps; val raisonsAvant = Etat.raisons; val paquetAvant = Usage.dernierPaquet
        try {
            Etat.apps = setOf(pkg); Etat.raisons = mapOf(pkg to "regle|Fermée par une règle")
            Usage.dernierPaquet = pkg
            premierPlan = pkg
            Thread { while (premierPlan.isNotEmpty()) { Usage.dernierPaquet = premierPlan; SystemClock.sleep(20) } }.apply { isDaemon = true; start() }
            android.util.Log.i("MotifTest", "doitBloquer=" + Etat.doitBloquer(ctx, pkg) + " modules=" + Modules.actifs(ctx) + " parent=" + Config(ctx).modeParent)
            val a = instrumentation.startActivitySync(Intent(ctx, BlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("raison", "Cette appli est bloquée").putExtra("pkg", pkg))
            instrumentation.waitForIdleSync()
            var bouton: TextView? = null
            val fin = SystemClock.elapsedRealtime() + 5000
            while (bouton == null && SystemClock.elapsedRealtime() < fin) {
                instrumentation.runOnMainSync { bouton = vues(a.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text.startsWith("Demander l'accès") } }
                SystemClock.sleep(100)
            }
            assertNotNull("bouton « Demander l'accès » absent", bouton)
            instrumentation.runOnMainSync { bouton!!.performClick() }
            instrumentation.waitForIdleSync()
            val dlg = champ(a, "dialogueDemande") as AlertDialog
            var motif: EditText? = null
            instrumentation.runOnMainSync {
                motif = vues(dlg.window!!.decorView).filterIsInstance<EditText>().first { it.hint?.toString() == "Pourquoi ? (facultatif)" }
                motif!!.requestFocus()
                (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(motif, 0)
            }
            // Le clavier passe au premier plan (ce que voit l'accessibilité).
            val clavier = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/').orEmpty()
            val texte = StringBuilder()
            for (s in 1..30) {
                premierPlan = clavier.ifEmpty { "com.google.android.inputmethod.latin" }
                texte.append(if (s % 6 == 0) " " else "a")
                instrumentation.runOnMainSync { motif!!.append(texte.last().toString()) }
                SystemClock.sleep(1000)
                var ouvert = false; var fini = true
                instrumentation.runOnMainSync { ouvert = dlg.isShowing; fini = a.isFinishing }
                android.util.Log.i("MotifTest", "s=$s dernier=${Usage.dernierPaquet} ouvert=$ouvert fini=$fini dlgChamp=${champ(a, "dialogueDemande") === dlg}")
                assertTrue("la fenêtre du motif s'est fermée après $s s", ouvert)
                assertFalse("l'écran de blocage s'est retiré après $s s", fini)
            }
            instrumentation.runOnMainSync { dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            val f2 = SystemClock.elapsedRealtime() + 8000
            while (recu == null && SystemClock.elapsedRealtime() < f2) SystemClock.sleep(100)
            assertEquals("motif envoyé", texte.toString().trim(), recu?.optString("motif"))
            assertEquals(pkg, recu?.optString("nom"))
            var envoye = false
            val f3 = SystemClock.elapsedRealtime() + 5000
            while (!envoye && SystemClock.elapsedRealtime() < f3) {
                instrumentation.runOnMainSync { envoye = vues(a.window.decorView).filterIsInstance<TextView>().any { it.text == "Demande envoyée" } }
                SystemClock.sleep(100)
            }
            assertTrue("« Demande envoyée » absent", envoye)
            a.finish()
        } finally {
            premierPlan = ""; SystemClock.sleep(50)
            Etat.apps = appsAvant; Etat.raisons = raisonsAvant; Usage.dernierPaquet = paquetAvant
        }
    }
}
