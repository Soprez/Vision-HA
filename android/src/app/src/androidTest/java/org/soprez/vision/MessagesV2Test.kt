// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * 2.0.0 (point 8, notifications v2) : ce que voit et fait la personne.
 * Téléphone : notification avec les réponses proposées et « Répondre », jamais plein écran pour « Important »,
 * la réponse part à Home Assistant. Télé : réponses à la télécommande seulement. Décideur : l'écran d'envoi.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.MessagesV2Test org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class MessagesV2Test : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }
    private lateinit var serveur: ServerSocket
    private val recus = ArrayList<Pair<String, JSONObject>>()
    private val sauves = HashMap<String, Map<String, *>>()

    private fun lire(): JSONObject = JSONObject().put("ok", true)
        .put("destinataires", JSONObject().put("personnes", JSONArray().put(JSONObject().put("id", "person.alex").put("nom", "Alex")))
            .put("appareils", JSONArray().put(JSONObject().put("id", "t1").put("nom", "Téléviseur du salon").put("type", "tv"))))
        .put("types", JSONArray().put(JSONObject().put("id", "message").put("nom", "Message").put("priorite", "normale").put("bloquant", "non"))
            .put(JSONObject().put("id", "important").put("nom", "Important").put("priorite", "importante").put("bloquant", "non")))
        .put("reponses_proposees", JSONArray().put("J'arrive").put("Dans 5 min").put("OK"))
        .put("envois", JSONArray())

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        // Content-Length compte des octets, le lecteur des caractères (accents) : on s'arrête quand plus rien n'arrive.
        while (lu < longueur && (lu == 0 || entree.ready())) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = try { JSONObject(String(buf, 0, lu)) } catch (_: Exception) { JSONObject() }
        val chemin = premiere.split(" ").getOrElse(1) { "" }
        synchronized(recus) { recus.add(chemin to corps) }
        if (chemin.contains("/enroll")) {  // un appareil non inscrit ne doit pas écraser l'identifiant posé par le test
            s.getOutputStream().apply { write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        val r = when {
            chemin.endsWith("/messages/app") && corps.optString("action") == "lire" -> lire()
            chemin.endsWith("/messages/app") -> { SystemClock.sleep(600); JSONObject().put("ok", true).put("retour", "Message envoyé.") }
            else -> JSONObject().put("ok", true)
        }
        val octets = r.toString().toByteArray()
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
        for (n in listOf("vision", "vision_messages", "messages_riches")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        cfg.id = "essai_msg"; cfg.secret = "s".repeat(32)
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

    private fun capturer(nom: String) {
        val b = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun vues(v: View, sortie: ArrayList<View> = ArrayList()): ArrayList<View> {
        sortie.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), sortie)
        return sortie
    }

    private fun attendre(quoi: String, ms: Long = 8000, cond: () -> Boolean) {
        val fin = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < fin) { if (cond()) return; SystemClock.sleep(100) }
        throw AssertionError("délai dépassé : $quoi — reçu : " + synchronized(recus) { recus.map { it.first + " " + it.second } })
    }

    private fun message(id: Int, important: Boolean) = JSONObject().put("v", 2).put("id", id).put("uid", "u$id${System.nanoTime()}")
        .put("title", "Nolan").put("text", "À table dans 10 minutes !").put("de", "Nolan").put("important", important)
        .put("reponses", JSONArray().put("J'arrive").put("Dans 5 min")).put("libre", true).put("bloquant", "non")
        .put("urgent", false).put("sound", "Notification.Default").put("type", "texte").put("position", "centre").put("duree", 0)

    fun testNotificationTelephoneAvecReponses() {
        if (ReglagesTvActivity.estTele(ctx)) return
        Messages.afficher(ctx, JSONArray().put(message(4242, important = true)))
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var n: android.app.Notification? = null
        attendre("notification") { n = nm.activeNotifications.firstOrNull { it.id == 3000 + 4242 % 500 }?.notification; n != null }
        val actions = n!!.actions.map { it.title.toString() }
        assertEquals(listOf("J'arrive", "Dans 5 min", "Répondre"), actions)
        assertEquals("important : canal mis en avant", MessagesV2.CANAL_IMPORTANT, n!!.channelId)
        assertNull("important ne passe jamais en plein écran", n!!.fullScreenIntent)
        assertNotNull("« Répondre » en texte libre", n!!.actions[2].remoteInputs)
        // « J'arrive » touché : l'accusé « repondu » part à Home Assistant.
        n!!.actions[0].actionIntent.send()
        attendre("réponse envoyée") { synchronized(recus) { recus.any { it.first.endsWith("/api/pc_parental/message") && it.second.optString("etat") == "repondu" && it.second.optString("detail") == "J'arrive" } } }
        assertTrue("reçu accusé", synchronized(recus) { recus.any { it.second.optString("etat") == "affiche" && it.second.optInt("message") == 4242 } })
        nm.cancel(3000 + 4242 % 500)
    }

    fun testSonSilencieuxChoisiSurLAppareil() {
        if (ReglagesTvActivity.estTele(ctx)) return
        MessagesV2.noterSon(ctx, MessagesV2.SON_SILENCIEUX)
        Messages.afficher(ctx, JSONArray().put(message(4243, important = true)))
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var n: android.app.Notification? = null
        attendre("notification") { n = nm.activeNotifications.firstOrNull { it.id == 3000 + 4243 % 500 }?.notification; n != null }
        assertEquals(MessagesV2.CANAL_SILENCIEUX, n!!.channelId)
        nm.cancel(3000 + 4243 % 500)
    }

    fun testEnvoiDecideur() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = instrumentation.startActivitySync(Intent(ctx, MessageEnvoiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun texte(): String { var t = ""; instrumentation.runOnMainSync { t = vues(a.window.decorView).filterIsInstance<TextView>().joinToString(" | ") { it.text } }; return t }
        attendre("écran chargé") { "Alex · tous ses appareils" in texte() }
        for (x in listOf("Pour", "Type", "Réponses proposées (3 au plus)", "Important", "Mettre en pause la télé", "Lire à voix haute", "Envoyer maintenant"))
            assertTrue("« $x » absent : " + texte(), x in texte())
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Alex · tous ses appareils" }.performClick() }
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "J'arrive" }.performClick() }
        instrumentation.runOnMainSync { vues(a.window.decorView).filterIsInstance<android.widget.EditText>().first { it.hint == "Ton message" }.setText("À table !") }
        // La pause de la télé est décochée d'office.
        var pause = true
        instrumentation.runOnMainSync { pause = (vues(a.window.decorView).first { it.contentDescription == "Mettre en pause la télé" } as android.widget.Switch).isChecked }
        assertFalse("pause télé cochée d'office", pause)
        SystemClock.sleep(300)
        capturer("message_envoi")
        instrumentation.runOnMainSync { vues(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Envoyer maintenant" }.performClick() }
        SystemClock.sleep(150)
        assertTrue("pas de retour immédiat", "En cours…" in texte())
        attendre("envoyé") { "Message envoyé." in texte() }
        val c = synchronized(recus) { recus.last { it.second.optString("action") == "envoyer" }.second }
        assertEquals("À table !", c.optString("texte")); assertEquals("person.alex", c.getJSONArray("destinataires").getString(0))
        assertEquals("J'arrive", c.getJSONArray("reponses").getString(0)); assertFalse(c.optBoolean("pause_tele"))
        a.finish()
    }

    fun testTeleReponsesALaTelecommande() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        // L'hôte : un cadre plein écran (comme la veille), pour ne pas dépendre de « Afficher par-dessus ».
        val a: Activity = instrumentation.startActivitySync(Intent(ctx, RetourActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var cadre: FrameLayout? = null
        instrumentation.runOnMainSync { cadre = FrameLayout(a); a.setContentView(cadre); MessagesRiches.poserHote(a, cadre!!) }
        MessagesRiches.recevoir(ctx, JSONArray().put(message(4244, important = true)))
        fun texte(): String { var t = ""; instrumentation.runOnMainSync { t = vues(cadre!!).filterIsInstance<TextView>().joinToString(" | ") { it.text } }; return t }
        attendre("message à l'écran") { "À table dans 10 minutes !" in texte() }
        assertTrue(texte(), "J'arrive" in texte() && "Dans 5 min" in texte() && "Fermer" in texte() && "Nolan · important" in texte())
        // Pas de texte libre à la télé.
        var champs = 1
        instrumentation.runOnMainSync { champs = vues(cadre!!).filterIsInstance<android.widget.EditText>().size }
        assertEquals("pas de champ de texte libre à la télé", 0, champs)
        SystemClock.sleep(300)
        capturer("message_tele")
        instrumentation.runOnMainSync { vues(cadre!!).filterIsInstance<TextView>().first { it.text == "Dans 5 min" }.performClick() }
        attendre("réponse envoyée") { synchronized(recus) { recus.any { it.second.optString("etat") == "repondu" && it.second.optString("detail") == "Dans 5 min" } } }
        instrumentation.runOnMainSync { MessagesRiches.retirerHote(a, cadre!!) }
        a.finish()
    }
}
