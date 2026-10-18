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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 1.90.4 / HA 2.4.7 (décision du projet : liaison d'une télé SANS code à l'écran). Serveur local comme Home Assistant :
 * - la télé affiche « En attente de validation… », l'œil et son nom, JAMAIS le code ni le QR, sauf « Autre méthode » ;
 * - refus, expiration et remplacement se disent sans code ;
 * - chez le {décideur} : « Nouvel appareil », à qui (écran partagé proposé pour une télé), Accepter / Refuser.
 * Aucun texte coupé ni hors de l'écran ; une capture par écran dans files/recette-liaison (taille et police de
 * l'émulateur).
 *
 *   adb shell am instrument -w -e class org.soprez.vision.LiaisonSansCodeTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class LiaisonSansCodeTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private val sauves = HashMap<String, Map<String, *>>()
    private val corps = CopyOnWriteArrayList<String>()
    @Volatile private var etatAttente = "en_attente"
    @Volatile private var decideurs = 1
    private val dossier get() = File(ctx.filesDir, "recette-liaison").apply { mkdirs() }

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val texte = String(buf, 0, lu)
        val r = org.json.JSONObject()
        when {
            premiere.contains("/appairage/demande") -> r.put("ok", true).put("demande", "d1").put("attente", "a".repeat(32))
                .put("code", "K7M4QX").put("qr", "homeassistant://navigate/vision-appairage?code=K7M4QX").put("lien", "")
                .put("expire_dans", 600).put("intervalle", 2).put("decideurs", decideurs).put("nom", "Salle De jeux")
            premiere.contains("/appairage/attente") -> r.put("ok", true).put("etat", etatAttente).put("expire_dans", 590)
            premiere.contains("/decideur/liste") -> r.put("ok", true).put("demandes", org.json.JSONArray().put(
                org.json.JSONObject().put("demande", "d1").put("nom", "Salle De jeux").put("plateforme", "android").put("genre", "tele")
                    .put("depuis", System.currentTimeMillis() / 1000).put("expire", System.currentTimeMillis() / 1000 + 600).put("lieu", "Lieu")
                    .put("personnes", org.json.JSONArray().put(org.json.JSONObject().put("id", "person.alex").put("nom", "Alex"))
                        .put(org.json.JSONObject().put("id", "person.sam").put("nom", "Sam")))))
            premiere.contains("/decideur/decision") -> { corps.add(texte); r.put("ok", true).put("etat", "validee").put("nom", "Salle De jeux") }
        }
        val o = r.toString().toByteArray()
        val crlf = "\r\n"
        s.getOutputStream().apply {
            write(("HTTP/1.1 200 OK" + crlf + "Content-Type: application/json" + crlf + "Content-Length: ${o.size}" + crlf + "Connection: close" + crlf + crlf).toByteArray())
            write(o); flush()
        }
    }

    private val adresse get() = "http://127.0.0.1:${serveur.localPort}"

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
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x) } }
        }.commit()
        super.tearDown()
    }

    private fun textes(v: View, sortie: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView && v.isShown) sortie.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    /** Le texte visible ; aucun texte coupé ni hors de l'écran ; une capture. */
    private fun verifier(a: Activity, nom: String): String {
        val defauts = ArrayList<String>(); val vus = StringBuilder()
        instrumentation.runOnMainSync {
            val ecran = Rect(); a.window.decorView.getGlobalVisibleRect(ecran)
            for (tv in textes(a.window.decorView)) {
                vus.append(tv.text).append(" | ")
                val l = tv.layout
                if (l != null) for (i in 0 until l.lineCount) if (l.getEllipsisCount(i) > 0) defauts.add("coupé : « ${tv.text} »")
                val loc = IntArray(2); tv.getLocationOnScreen(loc)
                if (loc[0] + tv.width > ecran.right + 1 || loc[0] < ecran.left - 1) defauts.add("hors de l'écran : « ${tv.text} »")
            }
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { b -> FileOutputStream(File(dossier, "$nom.png")).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue("$nom : $defauts", defauts.isEmpty())
        return vus.toString()
    }

    private fun attendreTexte(a: Activity, attendu: String, ms: Long = 8_000): String {
        val fin = SystemClock.elapsedRealtime() + ms
        var t = ""
        while (SystemClock.elapsedRealtime() < fin) {
            val sb = StringBuilder(); instrumentation.runOnMainSync { textes(a.window.decorView).forEach { sb.append(it.text).append(" | ") } }
            t = sb.toString(); if (t.contains(attendu)) return t
            SystemClock.sleep(200)
        }
        return t
    }

    private fun ouvrirTele(): AppairageActivity = instrumentation.startActivitySync(
        Intent(ctx, AppairageActivity::class.java).putExtra(AppairageActivity.EXTRA_ADRESSE, adresse).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppairageActivity

    private fun cliquer(a: Activity, texte: String) {
        instrumentation.runOnMainSync { textes(a.window.decorView).first { it.text.toString() == texte }.performClick() }
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
    }

    fun testTeleEnAttenteSansCodePuisAutreMethode() {
        val a = ouvrirTele()
        val t = attendreTexte(a, ctx.getString(R.string.liaison_sans_code_attente))
        assertTrue(t, t.contains(ctx.getString(R.string.liaison_sans_code_attente)) && t.contains("Salle De jeux"))
        SystemClock.sleep(2500)  // au moins un tour d'attente
        val vu = verifier(a, "1-tele-attente")
        assertFalse("code visible sans « Autre méthode » : $vu", vu.contains("K7M") || vu.contains("QX"))
        assertFalse(a.autreMethode)
        cliquer(a, ctx.getString(R.string.liaison_sans_code_autre_methode))
        val secours = verifier(a, "2-tele-autre-methode")
        assertTrue(a.autreMethode && secours.contains("K7M 4QX"))
        a.finish()
    }

    fun testRefusExpirationRemplacementSansCode() {
        for ((etat, texte, capture) in listOf(
            Triple("refusee", R.string.appairageecran_demande_refusee, "3-tele-refus"),
            Triple("expiree", R.string.liaison_sans_code_expiree, "4-tele-expiree"),
            Triple("remplacee", R.string.liaison_sans_code_remplacee, "5-tele-remplacee"))) {
            etatAttente = etat
            val a = ouvrirTele()
            val t = attendreTexte(a, ctx.getString(texte))
            assertTrue("$etat : $t", t.contains(ctx.getString(texte)))
            val vu = verifier(a, capture)
            assertFalse(vu, vu.contains("K7M"))
            a.finish(); SystemClock.sleep(300)
        }
    }

    fun testAucunDecideurProposeAutreMethode() {
        decideurs = 0
        val a = ouvrirTele()
        val t = attendreTexte(a, ctx.getString(R.string.liaison_sans_code_autre_methode))
        assertTrue(t, t.contains(ctx.getString(R.string.liaison_sans_code_aucun, Vocabulaire.decideur(ctx))))
        assertFalse(t.contains("K7M"))
        verifier(a, "6-tele-aucun-decideur")
        a.finish()
    }

    fun testDecideurAccepteEcranPartage() {
        Config(ctx).apply { id = "essai_decideur"; secret = "s".repeat(32); urlInterne = adresse; urlExterne = ""; urlActive = adresse }
        val a = instrumentation.startActivitySync(NouvelAppareilActivity.intention(ctx))
        val t = attendreTexte(a, "Salle De jeux")
        assertTrue(t, t.contains("Alex") && t.contains("Sam") && t.contains(ctx.getString(R.string.nouvel_appareil_partage, "Lieu")))
        verifier(a, "7-decideur-nouvel-appareil")
        cliquer(a, ctx.getString(R.string.nouvel_appareil_accepter))
        val fin = SystemClock.elapsedRealtime() + 5_000
        while (corps.isEmpty() && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(100)
        val c = org.json.JSONObject(corps.first())
        assertEquals("accepter", c.optString("action"))
        assertEquals("partage", c.optString("personne"))  // une télé : écran partagé proposé par défaut
        assertEquals("d1", c.optString("demande"))
        a.finish()
    }
}
