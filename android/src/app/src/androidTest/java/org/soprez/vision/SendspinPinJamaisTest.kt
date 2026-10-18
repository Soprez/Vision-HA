// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Instrumentation
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 1.90.2 (exigence ferme de l'auteur : « jamais le code d'appairage à l'écran, pas même un instant ») : pendant l'appairage
 * automatique (Home Assistant soumet le PIN à Music Assistant), l'écran d'appairage ne s'ouvre JAMAIS : ni quand il
 * réussit, ni quand Home Assistant refuse, ni quand il traîne, ni pour l'autorisation à la télécommande. Il ne s'ouvre
 * qu'à la main (Réglages › Décideurs 🔒). Serveur local comme Home Assistant ; états de Music Assistant simulés.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.SendspinPinJamaisTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class SendspinPinJamaisTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private val sauves = HashMap<String, Map<String, *>>()
    private lateinit var moniteur: Instrumentation.ActivityMonitor
    /** Les étapes reçues par « Home Assistant ». */
    private val recues = CopyOnWriteArrayList<String>()
    @Volatile private var reponsePin = "ok"
    @Volatile private var delaiPinMs = 0L

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = try { org.json.JSONObject(String(buf, 0, lu)) } catch (_: Exception) { org.json.JSONObject() }
        val etape = corps.optString("etape")
        var resultat = "ok"
        if (premiere.contains("/sendspin/appairage")) {
            recues.add(etape)
            if (etape == "pin") { if (delaiPinMs > 0) SystemClock.sleep(delaiPinMs); resultat = reponsePin }
        }
        val r = org.json.JSONObject().put("resultat", resultat).toString().toByteArray()
        val crlf = "\r\n"
        s.getOutputStream().apply {
            write(("HTTP/1.1 200 OK" + crlf + "Content-Type: application/json" + crlf + "Content-Length: ${r.size}" + crlf + "Connection: close" + crlf + crlf).toByteArray())
            write(r); flush()
        }
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
        cfg.id = "essai_pin"; cfg.secret = "s".repeat(32)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
        instrumentation.runOnMainSync { LecteurSendspin.essaiRaz() }
        moniteur = instrumentation.addMonitor(AppairageSendspinActivity::class.java.name, null, false)
    }

    override fun tearDown() {
        instrumentation.removeMonitor(moniteur)
        instrumentation.runOnMainSync { AppairageSendspinActivity.fermer(); LecteurSendspin.essaiRaz() }
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x) } }
        }.commit()
        super.tearDown()
    }

    private fun etat(e: SendspinClient.Etat) = instrumentation.runOnMainSync { LecteurSendspin.essaiEtat(ctx, e) }

    private fun attendre(ms: Long, condition: () -> Boolean = { false }) {
        val fin = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < fin && !condition()) SystemClock.sleep(100)
    }

    private fun aucunEcran(quand: String) {
        instrumentation.waitForIdleSync()
        assertEquals("écran d'appairage ouvert ($quand)", 0, moniteur.hits)
    }

    /** Un appairage automatique complet : demande, PIN tiré par MA et soumis par HA, puis la suite donnée. */
    private fun parcours(fin: SendspinClient.Etat?) {
        val ouvertures = AppairageSendspinActivity.ouvertures
        etat(SendspinClient.Etat(connecte = true, confiance = "none"))
        attendre(5_000) { "demarrer" in recues }
        assertTrue("pas de demande à Home Assistant", "demarrer" in recues)
        aucunEcran("demande")
        etat(SendspinClient.Etat(connecte = true, confiance = "none", appairage = "commence", pin = "482913"))
        aucunEcran("PIN tiré par Music Assistant")
        attendre(15_000) { "pin" in recues }
        assertTrue("PIN pas transmis à Home Assistant", "pin" in recues)
        attendre(1_500)
        aucunEcran("réponse de Home Assistant")
        if (fin != null) { etat(fin); attendre(800); aucunEcran("fin") }
        assertEquals("écran d'appairage créé", ouvertures, AppairageSendspinActivity.ouvertures)
    }

    fun testAppairageAutomatiqueReussiSansEcran() {
        parcours(SendspinClient.Etat(connecte = true, confiance = "user", appairage = "fini"))
        assertNull("échec retenu après un appairage réussi", LecteurSendspin.echecAuto)
    }

    fun testHomeAssistantRefuseSansEcranEtEchecSansCode() {
        reponsePin = "refus"
        parcours(SendspinClient.Etat(connecte = true, confiance = "none", appairage = "abandon"))
        assertEquals("refus", LecteurSendspin.echecAuto)
        // L'erreur dite dans Réglages › Décideurs ne contient jamais le code.
        val note = ctx.getString(R.string.reglagestv_appairage_echec_note) + ctx.getString(R.string.reglagestv_appairage_echec)
        assertFalse(note.contains("482913") || note.contains("482"))
    }

    fun testHomeAssistantLentSansEcran() {
        delaiPinMs = 6_000
        parcours(null)
        // Le PIN reste là un moment, Music Assistant se répète : toujours rien à l'écran.
        etat(SendspinClient.Etat(connecte = true, confiance = "none", appairage = "commence", pin = "482913"))
        attendre(1_000); aucunEcran("lenteur")
        etat(SendspinClient.Etat(connecte = true, confiance = "user", appairage = "fini"))
        aucunEcran("fin après lenteur")
    }

    fun testAutorisationTelecommandeSansEcran() {
        etat(SendspinClient.Etat(connecte = true, confiance = "none", appairage = "attente_geste"))
        attendre(800); aucunEcran("autorisation à la télécommande")
    }

    fun testALaMainSeulementDepuisDecideurs() {
        // Demandé dans Réglages › Décideurs 🔒 : l'écran s'ouvre, d'abord sans code, puis avec le code de MA.
        instrumentation.runOnMainSync { LecteurSendspin.appairerALaMain(ctx) }
        val a = instrumentation.waitForMonitorWithTimeout(moniteur, 5_000)
        assertNotNull("écran d'appairage à la main pas ouvert", a)
        val texteAvant = StringBuilder(); instrumentation.runOnMainSync { textes(a.window.decorView, texteAvant) }
        assertTrue(texteAvant.contains(ctx.getString(R.string.appairage_connexion)))
        etat(SendspinClient.Etat(connecte = true, confiance = "none", appairage = "commence", pin = "482913"))
        attendre(3_000) { val t = StringBuilder(); instrumentation.runOnMainSync { textes(a.window.decorView, t) }; t.contains("482  913") }
        val t = StringBuilder(); instrumentation.runOnMainSync { textes(a.window.decorView, t) }
        assertTrue("code pas affiché à la main : $t", t.contains("482  913"))
        instrumentation.runOnMainSync { LecteurSendspin.annulerAppairage(); a.finish() }
        assertFalse(LecteurSendspin.manuel())
    }

    private fun textes(v: android.view.View, sortie: StringBuilder) {
        if (v is android.widget.TextView) sortie.append(v.text).append('\n')
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
    }
}
