// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.87.2 (l'auteur, 0 h 15 : « la lecture va jusqu'à 5 s et revient au début ») : un flux comme celui de Music Assistant
 * (MP3, sans longueur annoncée, servi en temps réel, qui ne se rouvre pas) est lu en entier par le lecteur d'Android
 * à travers FluxUnique, avec UNE seule connexion. Le flux : 10 s de silence MP3 (MPEG-1 couche III, 128 kb/s, 44,1 kHz).
 *
 *   adb shell am instrument -w -e class org.soprez.vision.FluxUniqueTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class FluxUniqueTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    /** Une trame MP3 de silence : en-tête FF FB 90 C4 (128 kb/s, 44,1 kHz, mono), 417 octets, données nulles. */
    private fun silence(secondes: Int): ByteArray {
        val trame = ByteArray(417).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0xC4.toByte() }
        val n = secondes * 44_100 / 1152 + 1
        return ByteArray(n * trame.size).also { for (i in 0 until n) System.arraycopy(trame, 0, it, i * trame.size, trame.size) }
    }

    /** Un serveur qui ressemble à celui de Music Assistant : pas de Content-Length, débit ~2× le temps réel, une connexion coupée = fin. */
    private fun serveur(donnees: ByteArray, connexions: AtomicInteger): Pair<ServerSocket, String> {
        val s = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                connexions.incrementAndGet()
                Thread {
                    try {
                        val r = BufferedReader(InputStreamReader(c.getInputStream()))
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        val o = c.getOutputStream()
                        // Comme Music Assistant (aiohttp, longueur inconnue) : HTTP/1.1 en morceaux (« chunked »), une
                        // salve de ~180 Ko au départ, puis ~2× le débit du flux.
                        o.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                        var i = 0
                        while (i < donnees.size) {
                            val n = minOf(8 * 1024, donnees.size - i)
                            o.write((Integer.toHexString(n) + "\r\n").toByteArray()); o.write(donnees, i, n); o.write("\r\n".toByteArray()); o.flush(); i += n
                            if (i > 180_000) SystemClock.sleep(250)
                        }
                        o.write("0\r\n\r\n".toByteArray()); o.flush()
                    } catch (_: Exception) {
                    } finally { try { c.close() } catch (_: Exception) {} }
                }.start()
            }
        }.start()
        return s to "http://127.0.0.1:${s.localPort}/flux.mp3"
    }

    fun testLectureEntiereSurUneSeuleConnexion() = lectureEntiere()

    fun testLectureDesOctetsDansLOrdre() = octetsDansLOrdre()

    private fun octetsDansLOrdre() {
        val donnees = silence(3).also { it[1000] = 7; it[40_000] = 9 }
        val connexions = AtomicInteger()
        val (s, url) = serveur(donnees, connexions)
        val f = FluxUnique(url, ctx.cacheDir)
        try {
            val lu = java.io.ByteArrayOutputStream()
            val b = ByteArray(5000)
            var pos = 0L
            while (true) {
                val n = f.readAt(pos, b, 0, b.size)
                if (n < 0) break
                lu.write(b, 0, n); pos += n
            }
            assertTrue("octets identiques", lu.toByteArray().contentEquals(donnees))
            assertEquals("une seule connexion", 1, connexions.get())
            assertEquals(-1L, f.size)
        } finally { f.close(); s.close() }
    }

    private fun lectureEntiere() {
        val connexions = AtomicInteger()
        val (s, url) = serveur(silence(10), connexions)
        val f = FluxUnique(url, ctx.cacheDir)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setVolume(0f, 0f)
            mp.setDataSource(f)
            mp.prepare()
            mp.start()
            var max = 0
            val limite = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < limite && max < 7_000) {
                SystemClock.sleep(250)
                max = maxOf(max, try { mp.currentPosition } catch (_: Exception) { 0 })
            }
            assertTrue("la lecture dépasse 6 s (avant : coupure vers 4-5 s) : $max ms", max >= 6_000)
            assertEquals("une seule connexion au flux", 1, connexions.get())
        } finally {
            try { mp.release() } catch (_: Exception) {}
            f.close(); s.close()
        }
    }
}
