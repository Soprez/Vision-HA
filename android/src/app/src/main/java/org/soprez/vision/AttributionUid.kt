// SPDX-License-Identifier: MIT
package org.soprez.vision

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 1.94 · Découverte appli → sites : quelle appli a demandé ce domaine ? Le VPN de Vision voit chaque requête DNS avec le
 * port source du socket de l'appli ; Android dit à qui appartient ce socket :
 *  - Android 10 et plus (API 29+) : `ConnectivityManager.getConnectionOwnerUid` (documenté pour les applis VPN) ;
 *  - Android 5 à 9 (API 21 à 28) : la table `/proc/net/udp` (lisible jusqu'à Android 9), colonne `uid` par port local ;
 *  - sinon (UID perdu, système) : requête **incertaine** : comptée pour l'appli au premier plan, jamais pour un lien.
 * Cette classe est pure (les essais JVM la couvrent) : l'accès au système est injecté. Elle n'a PAS été mesurée sur un
 * appareil : c'est le prototype D0 (Pixel 8a, TCL, Galaxy Tab 4) qui dira le taux d'attribution réel.
 */
object AttributionUid {
    const val INVALID_UID = -1
    /** UID de l'utilisateur système, de `dns` et de `netd` : le résolveur, pas une appli. */
    private val UID_SYSTEME = setOf(0, 1000, 1051, 1052)

    /** Comment l'appareil attribue (dit à HA dans le relevé) : « uid » (API 29+), « proc » (API 21 à 28), « degrade » (rien de lisible). */
    fun mode(sdk: Int, procLisible: Boolean): String = when {
        sdk >= 29 -> "uid"
        procLisible -> "proc"
        else -> "degrade"
    }

    /**
     * Les ports locaux UDP et leur UID, d'après le texte de `/proc/net/udp` ou `/proc/net/udp6`. Une ligne :
     * `sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid timeout inode …`, port en hexadécimal
     * après les deux-points de `local_address`. Les lignes illisibles sont ignorées.
     */
    fun parseProcNet(texte: String): Map<Int, Int> {
        val sortie = HashMap<Int, Int>()
        for (ligne in texte.lineSequence().drop(1)) {
            val c = ligne.trim().split(Regex("\\s+"))
            if (c.size < 8) continue
            val port = c[1].substringAfter(':', "").toIntOrNull(16) ?: continue
            val uid = c[7].toIntOrNull() ?: continue
            sortie[port] = uid
        }
        return sortie
    }

    /** Un résultat gardé une seconde par port : une même requête n'interroge pas Android deux fois. */
    class Cache(private val ttlMs: Long = 1_000L, private val horloge: () -> Long = { System.currentTimeMillis() }) {
        private class Entree(val uid: Int, val a: Long)
        private val m = LinkedHashMap<Int, Entree>()

        @Synchronized fun uid(port: Int, calcul: () -> Int): Int {
            val t = horloge()
            m[port]?.let { if (t - it.a < ttlMs) return it.uid }
            val uid = calcul()
            m[port] = Entree(uid, t)
            if (m.size > 256) m.entries.removeAll(m.entries.take(64).toSet())
            return uid
        }
    }

    /** L'attribution d'une requête : les paquets de l'UID (plusieurs si l'UID est partagé), ou `incertain` (liste vide). */
    class Resultat(val paquets: List<String>) {
        val incertain: Boolean get() = paquets.isEmpty()
    }

    fun attribuer(uid: Int, paquetsDe: (Int) -> List<String>): Resultat {
        if (uid == INVALID_UID || uid < 0 || uid in UID_SYSTEME) return Resultat(emptyList())
        return Resultat(paquetsDe(uid).filter { it.isNotBlank() })
    }

    /** L'adresse locale (tunnel, port source) et l'adresse distante (DNS virtuel, 53) d'une requête, pour getConnectionOwnerUid. */
    fun adresses(source: ByteArray, portSource: Int, dns: String): Pair<InetSocketAddress, InetSocketAddress> =
        InetSocketAddress(InetAddress.getByAddress(source), portSource) to InetSocketAddress(InetAddress.getByName(dns), 53)
}
