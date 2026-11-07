// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 1.94 (point 4, conception §1.11) : le cache disque des images de l'appareil, un seul pour les médias des messages riches
 * (image, GIF) ; les pochettes ([CachePochettes]) et les logos gardent le leur en attendant d'y être repris.
 *
 * - `cacheDir/images/<famille>/<sha1 de la clé>` ; familles [NOTIF], [GIF], [POCHETTE], [CAMERA], [LOGO] ; Android peut vider
 *   `cacheDir` sous pression : on recharge ;
 * - la clé est celle de l'image, pas son adresse signée (le jeton change, l'image non) ;
 * - purge LRU à chaque écriture : les moins récemment lus partent d'abord, jusqu'à tenir dans la taille réglée ; une famille ne
 *   dépasse pas [PLAFOND_FAMILLE] du total (un GIF lourd ne chasse pas toutes les pochettes) ;
 * - taille réglable par appareil, en Mo (0 = automatique) : par défaut `min(5 % de l'espace libre, plafond)`, plafond 256 Mo
 *   (téléphone, tablette) ou 128 Mo (télé), plancher 32 Mo, 16 Mo si la mémoire vive est sous 3 Go (la TCL).
 */
object CacheImages {
    const val NOTIF = "notif"
    const val GIF = "gif"
    const val POCHETTE = "pochette"
    const val CAMERA = "camera"
    const val LOGO = "logo"
    val FAMILLES = listOf(NOTIF, GIF, POCHETTE, CAMERA, LOGO)

    const val PLAFOND_FAMILLE = 0.6
    const val PLAFOND_TELEPHONE_MO = 256L
    const val PLAFOND_TELE_MO = 128L
    const val PLANCHER_MO = 32L
    const val PLANCHER_PEU_DE_MEMOIRE_MO = 16L
    const val PEU_DE_MEMOIRE_MO = 3072L
    const val MO = 1024L * 1024L

    private const val PREFS = "cache_images"
    private fun racine(ctx: Context): File = File(ctx.applicationContext.cacheDir, "images")
    private fun dossier(ctx: Context, famille: String): File = File(racine(ctx), famille.filter { it.isLetterOrDigit() })

    /** Un fichier du cache, pour la purge. */
    data class Entree(val chemin: String, val famille: String, val octets: Long, val dernierAcces: Long)

    fun nom(cle: String): String = MessageDigest.getInstance("SHA-1").digest(cle.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ pur (essais JVM)

    /** La taille par défaut, en Mo, d'après l'espace libre, la mémoire vive totale et le type d'appareil. */
    fun tailleParDefautMo(espaceLibreMo: Long, memoireMo: Long, tele: Boolean): Long {
        val plafond = if (tele) PLAFOND_TELE_MO else PLAFOND_TELEPHONE_MO
        val plancher = if (memoireMo in 1 until PEU_DE_MEMOIRE_MO) PLANCHER_PEU_DE_MEMOIRE_MO else PLANCHER_MO
        return (espaceLibreMo * 5 / 100).coerceAtMost(plafond).coerceAtLeast(plancher)
    }

    /**
     * Les fichiers à retirer pour tenir dans [maxOctets] : d'abord, dans chaque famille qui dépasse son plafond, les moins
     * récemment lus ; puis, si le total dépasse encore, les moins récemment lus de tout le cache.
     */
    fun aRetirer(entrees: List<Entree>, maxOctets: Long, plafondFamille: Double = PLAFOND_FAMILLE): List<String> {
        val retirer = LinkedHashSet<String>()
        val limiteFamille = (maxOctets * plafondFamille).toLong()
        for ((_, l) in entrees.groupBy { it.famille }) {
            var total = l.sumOf { it.octets }
            for (e in l.sortedBy { it.dernierAcces }) {
                if (total <= limiteFamille) break
                retirer += e.chemin; total -= e.octets
            }
        }
        var total = entrees.filter { it.chemin !in retirer }.sumOf { it.octets }
        for (e in entrees.filter { it.chemin !in retirer }.sortedBy { it.dernierAcces }) {
            if (total <= maxOctets) break
            retirer += e.chemin; total -= e.octets
        }
        return retirer.toList()
    }

    // ------------------------------------------------------------------ réglage

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Le réglage local en Mo ; 0 = automatique. */
    fun reglageMo(ctx: Context): Long = prefs(ctx).getLong("mo", 0L).coerceAtLeast(0L)
    fun poserReglageMo(ctx: Context, mo: Long) { prefs(ctx).edit().putLong("mo", mo.coerceAtLeast(0L)).apply(); borner(ctx) }

    /** La taille en vigueur, en octets : le réglage, sinon le défaut calculé sur l'espace libre actuel. */
    fun tailleMaxOctets(ctx: Context): Long {
        val r = reglageMo(ctx)
        if (r > 0) return r * MO
        val libre = try { racine(ctx).apply { mkdirs() }.usableSpace / MO } catch (_: Exception) { 0L }
        val memoire = try {
            val i = android.app.ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(i); i.totalMem / MO
        } catch (_: Exception) { 0L }
        return tailleParDefautMo(libre, memoire, ReglagesTvActivity.estTele(ctx)) * MO
    }

    // ------------------------------------------------------------------ disque

    fun lire(ctx: Context, famille: String, cle: String): ByteArray? = try {
        File(dossier(ctx, famille), nom(cle)).takeIf { it.isFile }?.let { f -> f.setLastModified(System.currentTimeMillis()); f.readBytes() }
    } catch (_: Exception) { null }

    @Synchronized fun ecrire(ctx: Context, famille: String, cle: String, octets: ByteArray) {
        try {
            val d = dossier(ctx, famille).apply { mkdirs() }
            val tmp = File(d, nom(cle) + ".tmp")
            tmp.writeBytes(octets)
            tmp.renameTo(File(d, nom(cle)))
            borner(ctx)
        } catch (_: Exception) {}
    }

    private fun entrees(ctx: Context): List<Entree> = FAMILLES.flatMap { fam ->
        dossier(ctx, fam).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { Entree(it.absolutePath, fam, it.length(), it.lastModified()) }.orEmpty()
    }

    @Synchronized fun borner(ctx: Context) {
        val retirer = aRetirer(entrees(ctx), tailleMaxOctets(ctx)).toSet()
        retirer.forEach { try { File(it).delete() } catch (_: Exception) {} }
    }

    /** Les octets gardés, toutes familles. */
    fun utilise(ctx: Context): Long = entrees(ctx).sumOf { it.octets }

    @Synchronized fun vider(ctx: Context) {
        try { racine(ctx).deleteRecursively() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ réglages (téléphone et télé)

    /** « Utilisé : 41 Mo sur 128 », pour la ligne « Cache des images » des réglages. */
    fun libelle(ctx: Context): String =
        ctx.getString(R.string.cache_images_utilise, (utilise(ctx) / MO).toInt(), (tailleMaxOctets(ctx) / MO).toInt())

    /** « Vider le cache » : confirmation, puis « Vidé ». */
    fun proposerVider(act: Activity, apres: () -> Unit) {
        Ui.dialogue(act).setTitle(R.string.cache_images_titre).setMessage(R.string.cache_images_vider_texte)
            .setNegativeButton(R.string.commun_annuler, null)
            .setPositiveButton(R.string.cache_images_vider) { _, _ ->
                vider(act); Ui.toast(act, act.getString(R.string.cache_images_vide), court = true); apres()
            }.show()
    }
}
