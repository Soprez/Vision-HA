// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * l'auteur (15/10) : le choix de la playlist de veille n'est jamais une impasse — favorites en tête puis la bibliothèque,
 * un cœur par ligne qui appelle Home Assistant (/party/musique, quoi = favori, le même appel que l'appli Musique),
 * et une bibliothèque vide qui propose tout de suite la recherche. Faux Home Assistant local ; captures dans les
 * fichiers de l'appli (choix-playlist-*.png).
 */
class ChoixPlaylistVeilleEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private val sauves = HashMap<String, Map<String, *>>()
    @Volatile private var vide = false
    private val recus: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())

    private fun pl(uri: String, nom: String, favori: Boolean) = JSONObject().put("uri", uri).put("nom", nom).put("image", "").put("favori", favori).put("auto", false)

    private fun reponse(chemin: String, corps: JSONObject): JSONObject = when {
        chemin.contains("/veille/musique") && corps.optString("action") == "playlists" -> JSONObject().put("ok", true).put("lecteur", true).put("items",
            if (vide) JSONArray() else JSONArray().put(pl("library://playlist/17", "Disney classique", true)).put(pl("library://playlist/15", "Groovy Beats", true))
                .put(pl("library://playlist/16", "Hymnes Drum & Bass", false)).put(pl("library://playlist/14", "Histoire du soir", false))
                .put(pl("library://playlist/12", "Ready Player One - Book and Movie Playlist", false)))
        chemin.contains("/party/musique") && corps.optString("quoi") == "favori" -> { recus.add(corps); JSONObject().put("ok", true).put("favori", corps.optBoolean("favori")) }
        else -> JSONObject().put("ok", true)
    }

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = try { JSONObject(String(buf, 0, lu)) } catch (_: Exception) { JSONObject() }
        val octets = reponse(premiere, corps).toString().toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    override fun setUp() {
        super.setUp()
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { while (!serveur.isClosed) { val s = try { serveur.accept() } catch (_: Exception) { break }; Thread { try { servir(s) } catch (_: Exception) {} }.start() } }.apply { isDaemon = true }.start()
        for (n in listOf("vision", "local", "veille_audio")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        cfg.id = "essai_choix"; cfg.secret = "s".repeat(32)
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
        val image: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(image)
        val dossier = File(ctx.getExternalFilesDir(null), "recette").apply { mkdirs() }
        File(dossier, "choix-playlist-$nom.png").outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun coeurs(racine: View, out: MutableList<CoeurFavori> = ArrayList()): List<CoeurFavori> {
        if (racine is CoeurFavori) out.add(racine)
        if (racine is ViewGroup) for (i in 0 until racine.childCount) coeurs(racine.getChildAt(i), out)
        return out
    }

    private fun ouvrir(): Activity {
        val a: Activity = instrumentation.startActivitySync(EcransReglages.hote(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { ChoixPlaylistVeille.ouvrir(a) {} }
        SystemClock.sleep(2500)
        return a
    }

    fun testFavoritesEnTeteEtCoeur() {
        vide = false
        val a = ouvrir()
        try {
            capturer("liste")
            // Les fenêtres de la boîte : on cherche les cœurs dans toutes les vues racines de l'appli.
            var liste: List<CoeurFavori> = emptyList()
            instrumentation.runOnMainSync {
                val racines = try {
                    val wm = Class.forName("android.view.WindowManagerGlobal")
                    val inst = wm.getMethod("getInstance").invoke(null)
                    @Suppress("UNCHECKED_CAST")
                    (wm.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<View>)
                } catch (_: Exception) { listOf(a.window.decorView) }
                liste = racines.flatMap { coeurs(it) }
            }
            assertEquals("un cœur par playlist", 5, liste.size)
            assertEquals("favorites en tête", listOf(true, true, false, false, false), liste.map { it.favori })
            // Un appui sur le cœur de « Hymnes Drum & Bass » : favori ajouté par Home Assistant.
            instrumentation.runOnMainSync { liste[2].performClick() }
            SystemClock.sleep(1500)
            assertTrue("cœur plein après l'appui", liste[2].favori)
            val appel = synchronized(recus) { recus.lastOrNull() }
            assertNotNull("Home Assistant n'a pas reçu le cœur", appel)
            assertEquals("library://playlist/16", appel!!.optString("uri"))
            assertTrue(appel.optBoolean("favori"))
            capturer("coeur")
        } finally { a.finish(); instrumentation.waitForIdleSync() }
    }

    fun testBibliothequeVideProposeLaRecherche() {
        vide = true
        val a = ouvrir()
        try { capturer("vide") } finally { a.finish(); instrumentation.waitForIdleSync() }
    }
}
