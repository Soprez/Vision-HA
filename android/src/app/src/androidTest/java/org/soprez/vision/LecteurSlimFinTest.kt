// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/**
 * l'auteur (16/10, 1.93.0 : « un morceau s'est terminé et la lecture s'est arrêtée là, alors qu'il restait des morceaux ») :
 * le lecteur Classique joue un titre jusqu'au bout et le suivant doit démarrer.
 *
 * Un faux Music Assistant suit les règles d'aioslimproto (client.py) : sur « décodeur prêt » (STMd), il prépare la suite
 * (ici 1,5 s, le temps de résoudre un titre YouTube Music) puis l'envoie sans « flush » ; sur « plus de son » (STMu) sans
 * suite envoyée, le lecteur passe à l'arrêt (STOPPED) et la suite est oubliée. Le titre est servi comme celui de Music
 * Assistant : MP3 sans longueur HTTP ni en-tête de durée. Avant le correctif,
 * STMd partait collé à STMu à la fin : la suite n'arrivait jamais.
 */
class LecteurSlimFinTest : InstrumentationTestCase() {
    private val recus = Collections.synchronizedList(ArrayList<String>())

    /** Les titres d'essai : MP3 à 128 kb/s sans en-tête de durée (ni Xing ni ID3), comme un flux de Music Assistant. */
    private fun son(nom: String): ByteArray = instrumentation.context.assets.open(nom).use { it.readBytes() }

    fun testLeTitreSuivantDemarreALaFin() {
        val ctx = instrumentation.targetContext
        val sons = mapOf("/a.mp3" to son("fin_a.mp3"), "/b.mp3" to son("fin_b.mp3"))
        val http = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!http.isClosed) {
                val s = try { http.accept() } catch (_: Exception) { break }
                Thread {
                    s.use {
                        val req = s.getInputStream().bufferedReader().readLine() ?: return@use
                        val chemin = req.split(" ").getOrNull(1) ?: ""
                        recus.add("GET $chemin")
                        val o = s.getOutputStream()
                        // Comme Music Assistant : pas de Content-Length (flux).
                        o.write("HTTP/1.0 200 OK\r\nContent-Type: audio/mpeg\r\nConnection: close\r\n\r\n".toByteArray())
                        o.write(sons[chemin] ?: ByteArray(0)); o.flush()
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()

        val slim = ServerSocket(3483, 2, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        val arrete = java.util.concurrent.atomic.AtomicBoolean(false)
        val suiteEnvoyee = java.util.concurrent.atomic.AtomicBoolean(false)
        val debuts = java.util.concurrent.atomic.AtomicInteger(0)
        fun strm(o: OutputStream, chemin: String) {
            val requete = "GET $chemin HTTP/1.0\r\n\r\n".toByteArray()
            val d = ByteBuffer.allocate(24 + requete.size)
            d.put('s'.code.toByte()); d.put('3'.code.toByte()); d.put(ByteArray(12))
            d.putInt(0); d.putShort(http.localPort.toShort()); d.putInt(0); d.put(requete)
            val trame = ByteBuffer.allocate(2 + 4 + d.capacity())
            trame.putShort((4 + d.capacity()).toShort()); trame.put("strm".toByteArray()); trame.put(d.array())
            synchronized(o) { o.write(trame.array()); o.flush() }
        }
        val fil = Thread {
            try {
                val c = slim.accept()
                val entree = DataInputStream(c.getInputStream())
                val sortie = c.getOutputStream()
                while (true) {
                    val op = ByteArray(4); entree.readFully(op)
                    val longueur = entree.readInt()
                    val p = ByteArray(longueur); entree.readFully(p)
                    val o = String(op)
                    if (o == "HELO") strm(sortie, "/a.mp3")
                    if (o != "STAT" || p.size < 4) continue
                    val ev = String(p, 0, 4)
                    if (ev != "STMt") recus.add(ev)
                    when (ev) {
                        "STMs" -> debuts.incrementAndGet()
                        "STMd" -> if (!suiteEnvoyee.get() && !arrete.get()) Thread {
                            SystemClock.sleep(1500)  // Music Assistant résout le titre suivant
                            if (!arrete.get()) { suiteEnvoyee.set(true); strm(sortie, "/b.mp3") }
                        }.start()
                        "STMu" -> if (!suiteEnvoyee.get()) arrete.set(true)  // aioslimproto : STOPPED, suite oubliée
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        try {
            LecteurSlim.arreter(); SystemClock.sleep(500)
            LecteurSlim.demarrer(ctx, "127.0.0.1")
            val fin = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < fin && debuts.get() < 2 && !arrete.get()) SystemClock.sleep(100)
            val vu = synchronized(recus) { recus.toList() }
            assertFalse("la file s'est arrêtée (STMu avant la suite) : $vu", arrete.get())
            assertEquals("le titre suivant n'a pas démarré : $vu", 2, debuts.get())
            assertTrue("le second titre n'a pas été demandé : $vu", "GET /b.mp3" in vu)
        } finally {
            LecteurSlim.arreter()
            try { slim.close() } catch (_: Exception) {}
            try { http.close() } catch (_: Exception) {}
            fil.interrupt()
        }
    }

    /**
     * l'auteur (17/10, point 25, 1.93.03 : « le morceau suivant ne s'est pas lancé », l'appli « en cours » bloquée à 4 s) :
     * Music Assistant renonce parfois au titre suivant (file, `_enqueue_next_item_on_player` : une seule tentative, rien
     * n'est envoyé si le lecteur n'est pas « en lecture » à ce moment, et personne ne réessaie). Ce faux Music Assistant
     * ne l'envoie donc jamais après STMd. À la fin du titre, Vision doit demander la suite à HA (action `suite`) ; HA
     * appelle media_next_track, que Music Assistant joue comme `next` : f, q, puis s sans démarrage (« 2 »), et u
     * quand le tampon est prêt (STMl). Avant le correctif, STMu partait 4 s après la fin et la file s'arrêtait.
     */
    fun testLaSuiteOublieeParMusicAssistantEstRedemandee() {
        val ctx = instrumentation.targetContext
        val sons = mapOf("/a.mp3" to son("fin_a.mp3"), "/b.mp3" to son("fin_b.mp3"))
        val http = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!http.isClosed) {
                val s = try { http.accept() } catch (_: Exception) { break }
                Thread {
                    s.use {
                        val req = s.getInputStream().bufferedReader().readLine() ?: return@use
                        val chemin = req.split(" ").getOrNull(1) ?: ""
                        recus.add("GET $chemin")
                        s.getOutputStream().apply {
                            write("HTTP/1.0 200 OK\r\nContent-Type: audio/mpeg\r\nConnection: close\r\n\r\n".toByteArray())
                            write(sons[chemin] ?: ByteArray(0)); flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()

        val slim = ServerSocket(3483, 2, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        val arrete = java.util.concurrent.atomic.AtomicBoolean(false)
        val sortieMa = java.util.concurrent.atomic.AtomicReference<OutputStream?>(null)
        val demandes = java.util.concurrent.atomic.AtomicInteger(0)
        fun trame(o: OutputStream, commande: Char, demarrage: Char, chemin: String = "") {
            val requete = if (chemin.isEmpty()) ByteArray(0) else "GET $chemin HTTP/1.0\r\n\r\n".toByteArray()
            val d = ByteBuffer.allocate(24 + requete.size)
            d.put(commande.code.toByte()); d.put(demarrage.code.toByte()); d.put(ByteArray(12))
            d.putInt(0); d.putShort(http.localPort.toShort()); d.putInt(0); d.put(requete)
            val t = ByteBuffer.allocate(2 + 4 + d.capacity())
            t.putShort((4 + d.capacity()).toShort()); t.put("strm".toByteArray()); t.put(d.array())
            synchronized(o) { o.write(t.array()); o.flush() }
        }
        val fil = Thread {
            try {
                val c = slim.accept()
                val entree = DataInputStream(c.getInputStream())
                val sortie = c.getOutputStream(); sortieMa.set(sortie)
                while (true) {
                    val op = ByteArray(4); entree.readFully(op)
                    val p = ByteArray(entree.readInt()); entree.readFully(p)
                    val o = String(op)
                    if (o == "HELO") trame(sortie, 's', '3', "/a.mp3")
                    if (o != "STAT" || p.size < 4) continue
                    val ev = String(p, 0, 4)
                    if (ev != "STMt") recus.add(ev)
                    when (ev) {
                        "STMl" -> trame(sortie, 'u', '0')        // Music Assistant : tampon prêt → lecture
                        "STMu" -> arrete.set(true)              // aioslimproto : STOPPED, rien de prévu
                        // STMd : rien — Music Assistant a renoncé à envoyer la suite.
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val avant = LecteurSlim.demanderSuite
        LecteurSlim.demanderSuite = { _ ->
            demandes.incrementAndGet()
            // HA → media_next_track → Music Assistant `next` (play_media : flush, puis le titre sans démarrage auto).
            sortieMa.get()?.let { o -> Thread { SystemClock.sleep(300); trame(o, 'f', '0'); trame(o, 'q', '0'); trame(o, 's', '2', "/b.mp3") }.start() }
            true
        }
        try {
            LecteurSlim.arreter(); SystemClock.sleep(500)
            LecteurSlim.demarrer(ctx, "127.0.0.1")
            val fin = SystemClock.elapsedRealtime() + 30_000
            // 1.93.04.1 (retour 40, 0fe745e) : le « u » après un titre chargé sans démarrage (STMl) reçoit STMs (titre démarré,
            // comme squeezelite) et non plus STMr ; les deux disent que le second titre joue.
            fun deuxiemeEnLecture() = synchronized(recus) { val i = recus.indexOf("GET /b.mp3"); i >= 0 && recus.drop(i).let { "STMs" in it || "STMr" in it } }
            while (SystemClock.elapsedRealtime() < fin && !deuxiemeEnLecture() && !arrete.get()) SystemClock.sleep(100)
            val vu = synchronized(recus) { recus.toList() }
            assertFalse("STMu sans demander la suite : la file s'est arrêtée : $vu", arrete.get())
            assertEquals("la suite doit être demandée une fois à HA : $vu", 1, demandes.get())
            assertTrue("le titre suivant n'a pas démarré : $vu", deuxiemeEnLecture())
        } finally {
            LecteurSlim.demanderSuite = avant
            LecteurSlim.arreter()
            try { slim.close() } catch (_: Exception) {}
            try { http.close() } catch (_: Exception) {}
            fil.interrupt()
        }
    }
}
