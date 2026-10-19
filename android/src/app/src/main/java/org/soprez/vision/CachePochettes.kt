// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 1.90.2 (l'auteur : « il faudrait les précharger, et quand la partie est terminée ou qu'on ferme l'appli, ça décharge
 * automatiquement ») : les pochettes de la Party gardées aussi sur disque, dans le cache de l'appli, bornées
 * ([MAX_OCTETS], [MAX_FICHIERS], les plus anciennes partent d'abord). Vidé à la fin de la Party. 1.93.06 (l'auteur, « un vrai
 * cache ») : plus vidé à la fermeture de l'écran Party ni au démarrage de l'appli ; les bornes suffisent.
 */
object CachePochettes {
    const val MAX_OCTETS = 20L * 1024 * 1024
    const val MAX_FICHIERS = 120
    private const val DOSSIER = "party-pochettes"

    private fun dossier(ctx: Context): File = File(ctx.applicationContext.cacheDir, DOSSIER)

    fun nom(cle: String): String =
        MessageDigest.getInstance("SHA-1").digest(cle.toByteArray()).joinToString("") { "%02x".format(it) }

    fun lire(ctx: Context, cle: String): ByteArray? = try {
        File(dossier(ctx), nom(cle)).takeIf { it.isFile }?.let { f -> f.setLastModified(System.currentTimeMillis()); f.readBytes() }
    } catch (_: Exception) { null }

    @Synchronized fun ecrire(ctx: Context, cle: String, octets: ByteArray) {
        try {
            val d = dossier(ctx).apply { mkdirs() }
            val tmp = File(d, nom(cle) + ".tmp")
            tmp.writeBytes(octets)
            tmp.renameTo(File(d, nom(cle)))
            borner(d)
        } catch (_: Exception) {}
    }

    /** Pure sur la liste : les fichiers à retirer (les plus anciens) pour tenir dans les bornes. */
    fun aRetirer(fichiers: List<Triple<String, Long, Long>>, maxOctets: Long = MAX_OCTETS, maxFichiers: Int = MAX_FICHIERS): List<String> {
        var total = fichiers.sumOf { it.second }
        var n = fichiers.size
        val sortie = mutableListOf<String>()
        for ((nom, taille, _) in fichiers.sortedBy { it.third }) {
            if (total <= maxOctets && n <= maxFichiers) break
            sortie += nom; total -= taille; n--
        }
        return sortie
    }

    private fun borner(d: File) {
        val l = d.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        val retirer = aRetirer(l.map { Triple(it.name, it.length(), it.lastModified()) }).toSet()
        l.filter { it.name in retirer }.forEach { it.delete() }
    }

    @Synchronized fun vider(ctx: Context) {
        try { dossier(ctx).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }

    fun taille(ctx: Context): Long = dossier(ctx).listFiles()?.sumOf { it.length() } ?: 0L
}
