// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * 1.93.06 (photo de l'auteur, veille de la TV du salon, widgets de la box) : « 1,7 Mbit/s » en réception mais « 0,1 MB/s » en
 * envoi. Un débit réseau se montre toujours en Mbit/s, quelle que soit l'unité de l'entité (bits ou octets, k, M, G,
 * Ki, Mi, Gi ; écritures françaises « Mo/s » comprises), dans les widgets, leurs courbes et la veille. Pur.
 */
object Debit {
    const val MBITS = "Mbit/s"

    private const val KI = 1024.0
    private val FACTEURS: Map<String, Double> = mapOf(
        "bit/s" to 1e-6, "kbit/s" to 1e-3, "Kbit/s" to 1e-3, "Mbit/s" to 1.0, "Gbit/s" to 1e3,
        "bps" to 1e-6, "kbps" to 1e-3, "Kbps" to 1e-3, "Mbps" to 1.0, "Gbps" to 1e3,
        "B/s" to 8e-6, "kB/s" to 8e-3, "KB/s" to 8e-3, "MB/s" to 8.0, "GB/s" to 8e3,
        "KiB/s" to 8 * KI / 1e6, "MiB/s" to 8 * KI * KI / 1e6, "GiB/s" to 8 * KI * KI * KI / 1e6,
        "o/s" to 8e-6, "ko/s" to 8e-3, "Ko/s" to 8e-3, "Mo/s" to 8.0, "Go/s" to 8e3,
    )

    /** Le facteur vers les Mbit/s si [unite] est un débit, sinon null. */
    fun facteur(unite: String): Double? = FACTEURS[unite.trim()]

    fun estDebit(unite: String): Boolean = facteur(unite) != null

    /** La valeur et l'unité à montrer : en Mbit/s pour un débit, telles quelles sinon. */
    fun normaliser(v: Double, unite: String): Pair<Double, String> = facteur(unite)?.let { v * it to MBITS } ?: (v to unite)

    /** L'unité à montrer. */
    fun unite(unite: String): String = if (estDebit(unite)) MBITS else unite

    /** Un historique de valeurs, converti en Mbit/s s'il s'agit d'un débit. */
    fun historique(h: WidgetsHa.Historique): WidgetsHa.Historique {
        if (h !is WidgetsHa.Historique.Nombres) return h
        val f = facteur(h.unite) ?: return h
        return WidgetsHa.Historique.Nombres(MBITS, h.points.map { it.copy(v = it.v * f) })
    }
}
