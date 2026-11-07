// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 1.94 · Découverte appli → sites : l'accès au système pour [AttributionUid], hors du fil de la pompe du VPN. Chaque
 * requête DNS est mise en file (500 au plus, le reste est abandonné : l'attribution ne ralentit jamais la résolution),
 * attribuée à son appli, puis notée dans [Origines]. Un appel Binder par requête, gardé une seconde par port.
 * Pas encore mesuré sur appareil (prototype D0).
 */
/** Comment chaque requête DNS a été attribuée (des comptes, jamais un nom de domaine) : sert à mesurer le taux d'attribution (D0). */
object StatsAttribution {
    private val c = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    fun noter(cle: String) { c.getOrPut(cle) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }
    fun instantane(): Map<String, Int> = c.mapValues { it.value.get() }.toSortedMap()
    fun vider() = c.clear()

    /** Un écouteur (copie de recette : écriture de la mesure), appelé au plus toutes les 5 s depuis le fil d'attribution. */
    @Volatile var ecouteur: (() -> Unit)? = null
    @Volatile private var dernier = 0L
    fun prevenir() {
        val e = ecouteur ?: return
        val t = System.currentTimeMillis()
        if (t - dernier < 5_000) return
        dernier = t
        try { e() } catch (_: Exception) {}
    }
}

internal class AttributionVpn(ctx: Context, private val dnsVirtuel: String) {
    private val app = ctx.applicationContext
    private val executeur = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(500)) { r ->
        Thread(r, "vision-attribution").apply { isDaemon = true }
    }
    private val cache = AttributionUid.Cache()

    /** [source] : l'adresse IPv4 source du paquet (4 octets, l'adresse du tunnel), [port] : son port source. */
    fun demander(hote: String, source: ByteArray, port: Int, bloque: Boolean) {
        val premierPlan = Usage.dernierPaquet
        StatsAttribution.noter("requetes")
        try {
            executeur.execute {
                try {
                    val uid = cache.uid(port) { uidDe(source, port) }
                    val r = AttributionUid.attribuer(uid) { u -> app.packageManager.getPackagesForUid(u)?.toList() ?: emptyList() }
                    StatsAttribution.noter(when {
                        uid == AttributionUid.INVALID_UID || uid < 0 -> "uid_invalide"
                        r.incertain && uid in setOf(0, 1000, 1051, 1052) -> "uid_systeme_$uid"
                        r.incertain -> "uid_sans_paquet"
                        r.paquets.size > 1 -> "uid_partage"
                        else -> "uid_appli"
                    })
                    Origines.noter(hote, r.paquets, premierPlan, bloque)
                    StatsAttribution.prevenir()
                } catch (e: Exception) { Journal.d("Origines", "attribution impossible", e) }
            }
        } catch (_: RejectedExecutionException) { StatsAttribution.noter("file_pleine") /* on perd cette requête, pas la résolution */ }
    }

    private fun uidDe(source: ByteArray, port: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                val (local, distant) = AttributionUid.adresses(source, port, dnsVirtuel)
                (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .getConnectionOwnerUid(android.system.OsConstants.IPPROTO_UDP, local, distant)
            } catch (e: Exception) { AttributionUid.INVALID_UID }
        }
        return try {
            val t = File("/proc/net/udp").readText() + (try { File("/proc/net/udp6").readText() } catch (_: Exception) { "" })
            procLisible = true
            AttributionUid.parseProcNet(t)[port] ?: AttributionUid.INVALID_UID
        } catch (_: Exception) { procLisible = false; AttributionUid.INVALID_UID }
    }

    fun arreter() { executeur.shutdownNow() }

    companion object {
        @Volatile var procLisible = true

        /** Comment cet appareil attribue, pour le relevé (« uid », « proc » ou « degrade »). */
        fun mode(): String = AttributionUid.mode(Build.VERSION.SDK_INT, procLisible)
    }
}
