// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * 1.85.0 (chantier Générique) : une adresse du réseau de la maison, quel que soit ce réseau. Avant, trois copies
 * de la même règle ne connaissaient que l'IPv4 ; une maison en IPv6 (fd00::/8, fe80::/10) ou sous un nom en
 * « .lan » / « .home.arpa » voyait son Home Assistant traité comme une adresse publique.
 */
object Reseau {
    /** L'hôte d'une adresse http(s), crochets IPv6 retirés : « https://[fd00::2]:8123/x » → « fd00::2 ». */
    fun hote(url: String): String {
        val reste = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
        return if (reste.startsWith("[")) reste.substringAfter('[').substringBefore(']').lowercase()
        else reste.substringBefore(':').lowercase()
    }

    fun estPrivee(url: String): Boolean {
        val h = hote(url)
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
        if (h.contains(':')) return h.startsWith("fd") || h.startsWith("fc") || h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb") || h == "::1"
        val o = h.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || o[0] == 127 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) || (o[0] == 169 && o[1] == 254)
    }
}
