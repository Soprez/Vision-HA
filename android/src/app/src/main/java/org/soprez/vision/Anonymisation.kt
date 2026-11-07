// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * « Signaler un problème » : ce qui identifie un lieu, une personne ou un appareil est remplacé AVANT l'aperçu.
 * Mêmes règles que l'intégration (`diag.py`, `Anonymiseur`) et même corpus d'essai (`essai/corpus_anonymisation.json`) :
 * toute modification se fait des deux côtés, sinon l'un des deux essais échoue.
 *
 * La table est stable pour toute la période : une même adresse donne toujours le même pseudonyme (le journal reste
 * lisible). Les domaines de Vision restent lisibles ; titres et artistes de musique sont gardés (diagnostic musique).
 */
class Anonymisation(personnes: List<String> = emptyList(), appareils: List<String> = emptyList()) {
    var remplacements = 0
        private set
    private val ips = LinkedHashMap<String, String>()
    private val noms: List<Pair<Regex, String>>

    init {
        // Pseudonymes numérotés dans l'ordre fourni, par groupe ; remplacés du plus long au plus court
        // (« TEL-LOU » avant « Lou »).
        val liste = utiles(personnes).mapIndexed { i, n -> n to "personne-${i + 1}" } +
            utiles(appareils).mapIndexed { i, n -> n to "appareil-${i + 1}" }
        noms = liste.sortedByDescending { it.first.length }
            .map { (n, p) -> Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(n) + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) to p }
    }

    private fun compter(remplace: String): String { remplacements++; return remplace }

    private fun ip(brut: String, avant: String): String {
        if (brut == "127.0.0.1" || brut == "0.0.0.0" || brut == "::1") return brut
        if (':' in brut && "::" !in brut && !Regex("[a-fA-F]").containsMatchIn(brut) && !MAC.matches(brut)) return brut
        if (IPV4.matches(brut)) {
            val parties = brut.split('.')
            if (parties.any { it.toInt() > 255 || (it.length > 1 && it[0] == '0') }) return brut
            if (VERSION_AVANT.containsMatchIn(avant.takeLast(16))) return brut
        }
        return compter(ips.getOrPut(brut) { "ip-${ips.size + 1}" })
    }

    private fun site(brut: String): String =
        if (DOMAINES_GARDES.any { it in brut.lowercase() }) brut else compter("[site]")

    /** Une ligne anonymisée. Ordre voulu : secrets, contacts, adresses, sites, noms. */
    fun ligne(texte: String): String {
        var t = VALEUR_SECRETE.replace(texte) { it.groupValues[1] + compter("[secret]") }
        t = MAIL.replace(t) { compter("[contact]") }
        t = remplacerIp(MAC, t)
        t = remplacerIp(IPV6, t)
        t = remplacerIp(IPV4, t)
        t = URL.replace(t) { site(it.value) }
        t = DOMAINE.replace(t) { site(it.value) }
        t = TEL.replace(t) { compter("[contact]") }
        for ((motif, pseudo) in noms) t = motif.replace(t) { compter(pseudo) }
        return t
    }

    private fun remplacerIp(motif: Regex, t: String): String =
        motif.replace(t) { m -> ip(m.value, t.substring(0, m.range.first)) }

    companion object {
        val DOMAINES_GARDES = listOf("soprez.org", "github.com/soprez")
        private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        private val IPV6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\b""")
        private val MAC = Regex("""\b(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}\b""")
        private val MAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
        private val TEL = Regex("""(?<![\w.])(?:\+\d{1,3}|0)(?:[\s.-]?\d){8,9}(?![\w.])""")
        private val URL = Regex("""https?://[^\s"'<>)]+""", RegexOption.IGNORE_CASE)
        private val DOMAINE = Regex(
            """\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:com|net|org|fr|io|tv|app|dev|co|me|gg|ly|be|eu|uk|de|us|ai|tk|xyz|cloud|info)\b""",
            RegexOption.IGNORE_CASE)
        private val VALEUR_SECRETE = Regex(
            """(?i)((?:secret|token|jeton|code_octroi|code|password|mot de passe)["']?\s*[:=]\s*["']?)([^\s"',;}&]+)""")
        private val VERSION_AVANT = Regex("""(?i)(?:\bv|version|versions|appli|agent|vision|int[ée]gration|ha|core)\s*[:=]?\s*$""")

        /** Au moins 3 caractères (« Lou » oui, « Al » trop risqué), sans doublon, dans l'ordre fourni. */
        private fun utiles(noms: List<String>): List<String> {
            val vus = LinkedHashMap<String, String>()
            for (n in noms) { val s = n.trim(); if (s.length >= 3) vus.putIfAbsent(s.lowercase(), s) }
            return vus.values.toList()
        }
    }
}
