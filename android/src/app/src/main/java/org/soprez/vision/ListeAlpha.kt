// SPDX-License-Identifier: MIT
package org.soprez.vision

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Le mode « Liste alphabétique » du téléphone (l'auteur, 13 octobre, inspiré de Niagara Launcher) : la partie pure, sans
 * Android (essayée en JUnit, ListeAlphaTest).
 *
 * - [lettre] : la lettre d'une appli, accents repliés (« Élan » → E, « Œuvre » → O) ; chiffres, symboles et écritures
 *   non latines → « # », rangé à la fin, comme dans l'index ;
 * - [trier] : l'ordre de la langue de l'appareil (Collator), par lettre ;
 * - [premiers] / [presentes] : où commence chaque lettre, et lesquelles ont au moins une appli (les autres sont grisées) ;
 * - [indexA] : la lettre sous le doigt ; [echelle] / [decalage] : l'effet loupe (la lettre visée grossit, ses voisines
 *   un peu, et s'écartent du bord) ; [cible] : une lettre grisée mène à la plus proche qui a des applis.
 */
object ListeAlpha {
    const val DIESE = '#'
    val LETTRES: List<Char> = ('A'..'Z').toList() + DIESE

    data class Entree(val cle: String, val nom: String, val lettre: Char)

    fun lettre(nom: String): Char {
        val c = nom.trim().firstOrNull { it.isLetterOrDigit() } ?: return DIESE
        val base = when (c) {
            'Œ', 'œ' -> 'O'; 'Æ', 'æ' -> 'A'; 'ß' -> 'S'; 'Ø', 'ø' -> 'O'; 'Ð', 'ð' -> 'D'; 'Þ', 'þ' -> 'T'; 'Ł', 'ł' -> 'L'
            else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
        }
        val u = base.uppercaseChar()
        return if (u in 'A'..'Z') u else DIESE
    }

    fun trier(applis: List<Pair<String, String>>, locale: Locale): List<Entree> {
        val coll = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return applis.map { (cle, nom) -> Entree(cle, nom, lettre(nom)) }
            .sortedWith { a, b ->
                val ra = LETTRES.indexOf(a.lettre); val rb = LETTRES.indexOf(b.lettre)
                if (ra != rb) ra - rb else coll.compare(a.nom, b.nom).takeIf { it != 0 } ?: a.cle.compareTo(b.cle)
            }
    }

    fun premiers(entrees: List<Entree>): Map<Char, Int> {
        val m = LinkedHashMap<Char, Int>()
        entrees.forEachIndexed { i, e -> if (e.lettre !in m) m[e.lettre] = i }
        return m
    }

    fun presentes(entrees: List<Entree>): Set<Char> = entrees.map { it.lettre }.toSet()

    /** L'indice de la lettre sous le doigt (y depuis le haut de l'index, hauteur de l'index), borné. */
    fun indexA(y: Float, hauteur: Float, n: Int = LETTRES.size): Int {
        if (hauteur <= 0f || n <= 0) return 0
        return ((y / hauteur) * n).toInt().coerceIn(0, n - 1)
    }

    /** Le grossissement d'une lettre de l'index : 1,9 sous le doigt, puis 1,5 et 1,2 pour les voisines ; 1 sinon. */
    fun echelle(i: Int, centre: Int?): Float = when (centre?.let { Math.abs(i - it) }) {
        0 -> 1.9f; 1 -> 1.5f; 2 -> 1.2f; else -> 1f
    }

    /** Le décalage d'une lettre vers la liste, en fraction du décalage maximal (la loupe sort de l'index). */
    fun decalage(i: Int, centre: Int?): Float = when (centre?.let { Math.abs(i - it) }) {
        0 -> 1f; 1 -> 0.6f; 2 -> 0.25f; else -> 0f
    }

    /** La lettre où sauter : elle-même si elle a des applis, sinon la plus proche qui en a (d'abord après, puis avant). */
    fun cible(lettre: Char, presentes: Set<Char>): Char? {
        if (lettre in presentes) return lettre
        val k = LETTRES.indexOf(lettre).takeIf { it >= 0 } ?: return null
        for (d in 1 until LETTRES.size) {
            LETTRES.getOrNull(k + d)?.let { if (it in presentes) return it }
            LETTRES.getOrNull(k - d)?.let { if (it in presentes) return it }
        }
        return null
    }

    /** La lettre suivante (ou précédente) qui a des applis, pour TalkBack (balayer vers le haut ou le bas). */
    fun voisine(lettre: Char, sens: Int, presentes: Set<Char>): Char? {
        var k = LETTRES.indexOf(lettre)
        while (true) {
            k += sens
            val l = LETTRES.getOrNull(k) ?: return null
            if (l in presentes) return l
        }
    }
}
