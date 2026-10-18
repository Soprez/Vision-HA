// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.90.2 (essai de l'auteur, Party sur la télé du bureau) : « après Suivant, carré gris ; la pochette arrive une minute
 * après » et « il faudrait les précharger ; à la fin, ça décharge ». Serveur local (comme Home Assistant) :
 * - la pochette du titre en cours n'existe pas encore aux deux premières demandes (réponse vide), puis arrive : elle
 *   doit se poser d'elle-même, en quelques secondes, sans redessin de l'écran ;
 * - les vignettes de la file se préchargent sans être montrées, sont gardées (mémoire et disque), puis déchargées.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.PochettesTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class PochettesTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private val demandesPochette = AtomicInteger()
    private val demandesImage = AtomicInteger()
    private val sauves = HashMap<String, Map<String, *>>()
    @Volatile private var lenteurImageMs = 0L

    private fun png(): ByteArray {
        val b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); b.eraseColor(Color.rgb(200, 80, 60))
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val crlf = "\r\n"
        val o = s.getOutputStream()
        val image: ByteArray? = when {
            premiere.contains("/party/pochette") -> if (demandesPochette.incrementAndGet() <= 2) null else png()
            premiere.contains("/party/image") -> { demandesImage.incrementAndGet(); if (lenteurImageMs > 0) Thread.sleep(lenteurImageMs); png() }
            else -> null
        }
        if (image == null) o.write(("HTTP/1.1 204 No Content" + crlf + "Content-Length: 0" + crlf + "Connection: close" + crlf + crlf).toByteArray())
        else { o.write(("HTTP/1.1 200 OK" + crlf + "Content-Type: image/png" + crlf + "Content-Length: ${image.size}" + crlf + "Connection: close" + crlf + crlf).toByteArray()); o.write(image) }
        o.flush()
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
        cfg.id = "essai_pochettes"; cfg.secret = "s".repeat(32)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
        Pochettes.vider(ctx); Vignettes.vider(ctx)
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        Pochettes.vider(ctx); Vignettes.vider(ctx)
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x) } }
        }.commit()
        super.tearDown()
    }

    fun testPochetteQuiArriveEnRetardSePoseDElleMeme() {
        var vue: ImageView? = null
        val t0 = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync { vue = ImageView(ctx).also { Pochettes.poser(ctx, it, "media_player.tele", "titre-suivant") } }
        var posee = false
        while (SystemClock.elapsedRealtime() - t0 < 10_000 && !posee) {
            SystemClock.sleep(200)
            instrumentation.runOnMainSync { posee = vue!!.drawable != null }
        }
        val duree = SystemClock.elapsedRealtime() - t0
        assertTrue("pochette jamais posée (demandes : ${demandesPochette.get()})", posee)
        assertTrue("trop lent : $duree ms", duree < 8_000)
        assertEquals("deux réponses vides puis l'image", 3, demandesPochette.get())
        assertNotNull(Pochettes.garde("media_player.tele", "titre-suivant"))
    }

    /** Banc Musique (2026-10-12) : la vignette montrée pendant son préchargement se pose quand il finit (une seule demande). */
    fun testVignetteMontreePendantSonPrechargement() {
        lenteurImageMs = 1_500
        val url = "relais:dddd4444"
        Vignettes.precharger(ctx, listOf(url), 96)
        SystemClock.sleep(200)
        var vue: ImageView? = null
        instrumentation.runOnMainSync { vue = ImageView(ctx).also { Vignettes.poser(it, url, 96) } }
        val t0 = SystemClock.elapsedRealtime()
        var posee = false
        while (SystemClock.elapsedRealtime() - t0 < 6_000 && !posee) {
            SystemClock.sleep(100)
            instrumentation.runOnMainSync { posee = vue!!.drawable != null }
        }
        assertTrue("vignette jamais posée alors que son préchargement a fini", posee)
        assertEquals("une seule demande", 1, demandesImage.get())
    }

    fun testPrechargementPuisDechargement() {
        val urls = listOf("relais:aaaa1111", "relais:bbbb2222", "relais:cccc3333")
        Vignettes.precharger(ctx, urls, 96)
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < 8_000 && urls.any { Vignettes.garde(it) == null }) SystemClock.sleep(100)
        urls.forEach { assertNotNull("pas préchargée : $it", Vignettes.garde(it)) }
        assertEquals(3, demandesImage.get())
        assertTrue("rien sur disque", CachePochettes.taille(ctx) > 0)
        // Déjà là : une vignette montrée ensuite ne redemande rien.
        instrumentation.runOnMainSync { ImageView(ctx).also { Vignettes.poser(it, urls[0], 96); assertNotNull(it.drawable) } }
        assertEquals(3, demandesImage.get())
        // Fin de la Party : tout est déchargé, mémoire et disque.
        Vignettes.vider(ctx); Pochettes.vider(ctx)
        assertNull(Vignettes.garde(urls[0]))
        assertEquals(0L, CachePochettes.taille(ctx))
    }
}
