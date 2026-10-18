// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 1.87.2 (l'auteur, 0 h 15 : « la lecture va jusqu'à 5 s et revient au début ») : le flux d'un titre de Music Assistant n'a
 * pas de longueur annoncée. Donné tel quel au lecteur d'Android, celui-ci lisait un premier bloc puis rouvrait la
 * connexion pour sonder le fichier ; Music Assistant voyait le lecteur partir (« disconnected prematurely », ~180 Ko)
 * et la lecture s'arrêtait après les 4-5 s reçues.
 *
 * Ici, **une seule** connexion HTTP, jamais rouverte : le flux est recopié au fil de l'eau dans un fichier tampon, et le
 * lecteur d'Android lit ce tampon ; la fin du flux donne -1. La taille reste inconnue (-1) : le lecteur ne cherche pas la fin du fichier. Une lecture plus loin
 * que ce qui est arrivé attend la suite (le flux arrive en temps réel), la fin du flux, ou la fermeture.
 */
@android.annotation.TargetApi(23)
class FluxUnique(private val url: String, dossier: File, private val ouvrir: (String) -> HttpURLConnection = ::connexion) :
    MediaDataSource() {

    companion object {
        private const val BLOC = 32 * 1024

        fun connexion(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Icy-MetaData", "0")
        }
    }

    private val fichier: File = File.createTempFile("flux", ".bin", dossier)
    private val ecriture = RandomAccessFile(fichier, "rw")
    private val lecture = RandomAccessFile(fichier, "r")
    private val verrou = Object()
    @Volatile private var recus = 0L
    @Volatile private var fini = false
    /** Le flux est entièrement reçu (le « décodeur prêt » de SlimProto : Music Assistant peut préparer la suite). */
    val telecharge: Boolean get() = fini
    @Volatile private var ferme = false
    @Volatile var connexions = 0; private set
    private val fil = Thread({ telecharger() }, "VisionFlux").apply { isDaemon = true; start() }

    /** Octets déjà reçus (pour les essais et le suivi). */
    val octetsRecus: Long get() = recus

    private fun telecharger() {
        var c: HttpURLConnection? = null
        try {
            c = ouvrir(url)
            connexions++
            c.inputStream.use { entree ->
                val tampon = ByteArray(BLOC)
                while (!ferme) {
                    val n = entree.read(tampon)
                    if (n < 0) break
                    synchronized(verrou) {
                        ecriture.seek(recus); ecriture.write(tampon, 0, n)
                        recus += n
                        verrou.notifyAll()
                    }
                }
            }
        } catch (_: Exception) {
            // Coupure du réseau ou arrêt demandé : ce qui est arrivé reste lisible, puis fin du flux.
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
            synchronized(verrou) { fini = true; verrou.notifyAll() }
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        synchronized(verrou) {
            while (!ferme && !fini && position >= recus) verrou.wait(1_000)
            if (ferme || position >= recus) return -1
            val n = minOf(size.toLong(), recus - position).toInt()
            lecture.seek(position)
            lecture.readFully(buffer, offset, n)
            return n
        }
    }

    override fun getSize(): Long = -1

    override fun close() {
        if (ferme) return
        ferme = true
        synchronized(verrou) { verrou.notifyAll() }
        try { fil.interrupt() } catch (_: Exception) {}
        try { ecriture.close() } catch (_: Exception) {}
        try { lecture.close() } catch (_: Exception) {}
        try { fichier.delete() } catch (_: Exception) {}
    }
}
