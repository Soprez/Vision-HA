// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Recette visuelle (lot 1) : un nom long sans espace (domaine, nom de paquet, adresse) passe à la ligne à ses
 * séparateurs (. - _ / : @) au lieu d'être coupé au milieu d'un mot. Une espace sans chasse (U+200B : invisible,
 * ignorée par la lecture vocale) est posée après chaque séparateur d'un « mot » d'au moins 16 caractères ; les mots
 * ordinaires ne changent pas. Pur Kotlin (testé sur la JVM) ; Ui.coupable s'en sert pour tous les textes et boutons.
 */
object Coupure {
    const val ESPACE_NULLE = '\u200B'
    private const val SEPARATEURS = "./-_:@"
    private val LONG = Regex("""\S{16,}""")

    fun douce(s: String): String {
        if (s.length < 16) return s
        return LONG.replace(s) { m ->
            val mot = m.value
            val b = StringBuilder(mot.length + 8)
            mot.forEachIndexed { i, c ->
                b.append(c)
                if (c in SEPARATEURS && i < mot.length - 1 && mot[i + 1] != ESPACE_NULLE) b.append(ESPACE_NULLE)
            }
            b.toString()
        }
    }
}
