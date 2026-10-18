package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

/**
 * Correctif (l'auteur : « je ne joue pas de musique sur un matériel à moi, donc pourquoi je vois cette musique ? ») :
 * à l'écran, sur l'accueil du téléphone relié à un faux Home Assistant, la carte « en cours de lecture »
 * - reste cachée quand Home Assistant renvoie un lecteur d'essai qui joue ailleurs (le cas de l'auteur) ;
 * - paraît pour le lecteur « · Vision » de cet appareil ;
 * - paraît pour un lecteur piloté depuis l'appli.
 */
class CarteLectureEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    @Volatile private var etat = JSONObject()
    private val sauves = HashMap<String, Map<String, *>>()

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = when {
            premiere.contains("/party/etat") -> etat.toString()
            premiere.contains("/poll") -> JSONObject().put("ok", true).put("action", "none").put("poll", 60).put("party", JSONObject().put("tuile", true)).toString()
            premiere.contains("/enroll") -> ""
            else -> "{\"ok\":true}"
        }
        val octets = corps.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { while (!serveur.isClosed) { val s = try { serveur.accept() } catch (_: Exception) { break }; Thread { try { servir(s) } catch (_: Exception) {} }.start() } }
            .apply { isDaemon = true }.start()
        for (n in listOf("vision", "profil", "party", "lecteurs_suivis")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.id = "essai_ecran"; cfg.secret = "s".repeat(32); cfg.nom = "Pixel de l'auteur"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
        Etat.derniereReponse = System.currentTimeMillis()
        ctx.getSharedPreferences("party", 0).edit().putBoolean("tuile", true).commit()
        ctx.getSharedPreferences("lecteurs_suivis", 0).edit().clear().commit()
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x)
                is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    private fun lecture(lecteur: String, nom: String) = JSONObject().put("ok", true).put("actif", false).put("ici", false)
        .put("lecteur", lecteur).put("lecteur_nom", nom).put("piloter", false)
        .put("maintenant", JSONObject().put("etat", "playing").put("titre", "Medley électro").put("artiste", "Jenifer").put("pochette", ""))

    /** La carte de l'accueil est-elle visible, après quelques relectures de l'état ? */
    private fun carteVisible(): Boolean {
        val a: Activity = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            var vue: View? = null
            var visible = false
            val fin = SystemClock.elapsedRealtime() + 7_000
            while (SystemClock.elapsedRealtime() < fin) {
                instrumentation.waitForIdleSync(); SystemClock.sleep(500)
                instrumentation.runOnMainSync { vue = a.window.decorView.findViewWithTag("mini_lecteur"); visible = vue?.visibility == View.VISIBLE }
                if (visible) break
            }
            assertNotNull("la carte existe sur l'accueil", vue)
            return visible
        } finally {
            instrumentation.runOnMainSync { a.finish() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
        }
    }

    fun testLecteurDEssaiQuiJoueAilleursCache() {
        etat = lecture("media_player.banc_essai_vision", "Banc d'essai · Vision")
        assertFalse("la musique du banc d'essai ne doit pas paraître sur l'accueil", carteVisible())
    }

    fun testLecteurDeCetAppareilVisible() {
        etat = lecture("media_player.pixel_de_ja_vision", "Pixel d'essai · Vision")
        assertTrue("la musique de cet appareil paraît", carteVisible())
    }

    fun testLecteurPiloteDepuisLAppliVisible() {
        LecteursSuivis.noter(ctx, "media_player.salon")
        etat = lecture("media_player.salon", "Salon")
        assertTrue("un lecteur piloté depuis l'appli paraît", carteVisible())
    }
}
