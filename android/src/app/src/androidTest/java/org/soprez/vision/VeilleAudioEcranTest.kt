package org.soprez.vision

import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * 1.91.0 (retour d'essai) : la musique de la veille, sur l'appareil, contre un faux Home Assistant :
 * - playlist choisie, rien ne joue : la veille demande « debut » avec la playlist, puis « fin » quand elle se ferme ;
 * - option désactivée (par défaut) : la veille ne demande rien ;
 * - le lecteur de l'appareil joue déjà : la veille ne lance rien.
 */
class VeilleAudioEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private val demandes = Collections.synchronizedList(ArrayList<JSONObject>())
    private val sauves = HashMap<String, Map<String, *>>()

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = when {
            premiere.contains("/veille/musique") -> {
                val o = try { JSONObject(String(buf, 0, lu)) } catch (_: Exception) { JSONObject() }
                demandes.add(o)
                if (o.optString("action") == "debut") "{\"ok\":true,\"lance\":true,\"raison\":\"\"}" else "{\"ok\":true}"
            }
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
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { while (!serveur.isClosed) { val s = try { serveur.accept() } catch (_: Exception) { break }; Thread { try { servir(s) } catch (_: Exception) {} }.start() } }
            .apply { isDaemon = true }.start()
        for (n in listOf("vision", "local")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.id = "essai_veille"; cfg.secret = "s".repeat(32); cfg.nom = "Téléviseur du salon"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
        ctx.getSharedPreferences("local", 0).edit().putString("nuit", "").commit()
        VeilleAudio.oublier(); VeilleMusique.oublier()
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        VeilleAudio.oublier(); VeilleMusique.oublier()
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x)
                is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        super.tearDown()
    }

    private fun actions() = synchronized(demandes) { demandes.map { it.optString("action") } }

    /** Une veille ouverte [ms], puis fermée ; rend les actions reçues par le faux Home Assistant. */
    private fun uneVeille(ms: Long): List<String> {
        var v: VeilleView? = null
        instrumentation.runOnMainSync { v = VeilleView(ctx); v!!.demarrer() }
        SystemClock.sleep(ms)
        instrumentation.runOnMainSync { v!!.arreter() }
        val fin = SystemClock.elapsedRealtime() + 4_000
        while (SystemClock.elapsedRealtime() < fin && "fin" !in actions() && "debut" in actions()) SystemClock.sleep(100)
        SystemClock.sleep(300)
        return actions()
    }

    fun testPlaylistChoisieDebutPuisFinToujours() {
        VeilleAudio.choisir(ctx, VeilleAudio.Playlist("library://playlist/7", "Disney classique"))
        val a = uneVeille(2_500)
        assertEquals("la veille demande sa playlist, une fois, puis l'arrête : $a", listOf("debut", "fin"), a)
        val debut = synchronized(demandes) { demandes.first { it.optString("action") == "debut" } }
        assertEquals("library://playlist/7", debut.optString("uri"))
        assertFalse("plus rien en cours après la veille", VeilleAudio.demandee)
    }

    /**
     * l'auteur (17/10, 1.93.02) : la playlist de la veille s'arrêtait après 2 ou 3 titres. L'appareil doit redire « maintenir »
     * pendant toute la veille, sinon HA arrête la musique au bout de 6 min. Avant le correctif, l'envoi partait du fil
     * principal : Android le refusait en silence et HA ne recevait jamais « maintenir ».
     */
    fun testLaVeilleRediMaintenirPendantLaMusique() {
        VeilleAudio.choisir(ctx, VeilleAudio.Playlist("library://playlist/7", "Disney classique"))
        VeilleAudio.maintenirMs = 1_000
        try {
            val a = uneVeille(4_500)
            assertTrue("HA n'a reçu aucun « maintenir » pendant la veille : $a", a.count { it == "maintenir" } >= 2)
            assertEquals("debut d'abord, fin à la fin : $a", listOf("debut", "fin"), a.filter { it != "maintenir" })
        } finally {
            VeilleAudio.maintenirMs = VeilleAudio.MAINTENIR_MS
        }
    }

    fun testDesactiveeParDefautRien() {
        VeilleAudio.choisir(ctx, null)
        assertEquals(emptyList<String>(), uneVeille(2_000))
    }

    fun testLeLecteurJoueDejaRienNestLance() {
        VeilleAudio.choisir(ctx, VeilleAudio.Playlist("library://playlist/7", "Disney classique"))
        VeilleMusique.releve(ctx, true)
        assertEquals(emptyList<String>(), uneVeille(2_000))
    }
}
