package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 1.90.x (l'auteur, TV TCL) : les deux tableaux de bord de l'auteur, redessinés par la veille, à l'écran.
 * - « Cuve & livraisons » : à gauche « CUVE FIOUL », jauge et Autonomie, puis Conso moyenne ; à droite le graphique
 *   du niveau, sans titre de section (le haut des cartes doit s'aligner).
 * - Réseau : graphiques à gauche, tuiles valeur à droite (le même bord partout).
 * Une capture par tableau (files/recette/veille-*.png), pour la recette en 720p et 1080p ; les positions sont
 * vérifiées par DispositionDashTest (JVM).
 */
class VeilleTableauxTvTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private val sauves = HashMap<String, Map<String, *>>()

    private fun carte(nom: String, rendu: String, l: Int, h: Int, valeur: Double? = null, unite: String = "", serie: Boolean = false) =
        JSONObject().put("entite", "sensor.${nom.lowercase().replace(' ', '_')}").put("nom", nom).put("rendu", rendu)
            .put("largeur", l).put("hauteur", h).put("unite", unite).put("min", 0).put("max", 100)
            .apply { if (valeur != null) put("valeur", valeur) }
            .apply { if (serie) put("serie", JSONArray().apply { for (k in 0 until 24) put(JSONArray().put(1_760_000_000_000L + k * 3_600_000L).put(500 + 60 * Math.sin(k / 3.0))) }) }

    private fun veille(): JSONObject {
        val cuve = JSONObject().put("code", "dash").put("id", "cuve").put("titre", "Cuve & livraisons").put("duree", 600).put("colonnes", 2)
            .put("sections", JSONArray()
                .put(JSONObject().put("titre", "Cuve fioul").put("cases", JSONArray()
                    .put(carte("Niveau estimé", "jauge", 6, 2, 28.0, "%")).put(carte("Autonomie", "valeur", 6, 2, 161.0, "d"))
                    .put(carte("Conso moyenne", "valeur", 12, 1, 3.5, "L/d"))))
                .put(JSONObject().put("titre", "").put("cases", JSONArray().put(carte("Niveau", "courbe", 12, 2, 567.0, "L", serie = true)))))
        val reseau = JSONObject().put("code", "dash").put("id", "reseau").put("titre", "Réseau").put("duree", 600).put("colonnes", 2)
            .put("sections", JSONArray()
                .put(JSONObject().put("titre", "").put("cases", JSONArray()
                    .put(carte("Vitesse de réception", "courbe", 12, 2, 80.8, "Mbit/s", serie = true))
                    .put(carte("Débit montant", "courbe", 12, 2, 1.2, "MB/s", serie = true))))
                .put(JSONObject().put("titre", "").put("cases", JSONArray()
                    .put(carte("Wan", "texte", 12, 1).put("texte", "Connecté")).put(carte("Download", "valeur", 12, 1, 80.8, "Mbit/s"))
                    .put(carte("Upload", "valeur", 12, 1, 9.6, "Mbit/s")))))
        return JSONObject().put("ok", true).put("tableaux", JSONObject().put("liste", JSONArray().put(cuve).put(reseau)))
    }

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = if (premiere.contains("/veille") && !premiere.contains("/veille/")) veille().toString() else "{\"ok\":true}"
        val octets = corps.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    override fun setUp() {
        super.setUp()
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { while (!serveur.isClosed) { val s = try { serveur.accept() } catch (_: Exception) { break }; Thread { try { servir(s) } catch (_: Exception) {} }.start() } }
            .apply { isDaemon = true }.start()
        for (n in listOf("vision")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.id = "essai_tele"; cfg.secret = "s".repeat(32); cfg.nom = "Téléviseur du salon"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x)
                is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        super.tearDown()
    }

    private fun capturer(nom: String) {
        val b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dossier = File(ctx.getExternalFilesDir(null), "recette").apply { mkdirs() }
        val taille = instrumentationArgs("taille").ifEmpty { "${b.width}x${b.height}" }
        FileOutputStream(File(dossier, "veille-$nom-$taille.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun instrumentationArgs(cle: String): String = try {
        (instrumentation as? android.test.InstrumentationTestRunner)?.arguments?.getString(cle).orEmpty()
    } catch (_: Exception) { "" }

    fun testDeuxTableauxDeJA() {
        val a: Activity = instrumentation.startActivitySync(Intent(ctx, VeilleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            SystemClock.sleep(6_000)  // relevé, puis animations d'entrée
            capturer("cuve")
            val vue = a.javaClass.getDeclaredField("vue").apply { isAccessible = true }.get(a) as VeilleView
            instrumentation.runOnMainSync { vue.naviguer(1) }
            SystemClock.sleep(4_000)
            capturer("reseau")
        } finally {
            instrumentation.runOnMainSync { a.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
