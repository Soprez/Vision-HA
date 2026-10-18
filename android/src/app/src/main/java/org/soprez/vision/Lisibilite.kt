// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Refonte UX, lot 6 (B9 / APP-20, APP-22) : ce qui rend l'appli lisible et audible, en logique pure (essayée en JUnit,
 * LisibiliteTest) :
 * - le garde-fou de contraste des couleurs à la carte (texte ≥ 4,5:1 sur le fond et les cartes, accent ≥ 3:1 sur le
 *   fond, comme un grand texte ou un bouton) : la couleur choisie est ajustée vers le noir ou le blanc juste assez ;
 * - le nom d'une couleur pour le lecteur d'écran (« bleu foncé » plutôt que « #0F1830 ») ;
 * - les émojis décoratifs retirés de ce qui est lu (« Réservé aux décideurs 🔒 » se lit sans « cadenas »).
 */
object Lisibilite {
    /** Le contraste minimal d'un texte courant (WCAG AA). */
    const val TEXTE_MIN = Themes.CONTRASTE_TEXTE_MIN
    /** Le contraste minimal d'un grand texte, d'un bouton ou d'un accent (WCAG AA). */
    const val GRAND_MIN = 3f

    /** Le résultat d'un ajustement : la couleur à garder, si elle a changé, et le contraste obtenu. */
    class Ajustement(val couleur: Int, val change: Boolean, val contraste: Float, val atteint: Boolean)

    /**
     * La couleur [c], rapprochée du noir ou du blanc (celui qui contraste le mieux avec [fonds]) par pas de 5 %, jusqu'à
     * atteindre [min] contre chacun des [fonds]. Inchangée si elle suffit déjà ; au mieux, le noir ou le blanc.
     */
    fun ajuster(c: Int, fonds: List<Int>, min: Float): Ajustement {
        fun pire(x: Int) = fonds.minOf { Themes.contraste(x, it) }
        val depart = pire(c)
        if (depart >= min) return Ajustement(c, false, depart, true)
        val noir = 0xFF000000.toInt(); val blanc = 0xFFFFFFFF.toInt()
        val cible = if (pire(noir) >= pire(blanc)) noir else blanc
        var meilleur = c; var meilleurC = depart
        for (k in 1..20) {
            val x = Themes.melanger(c, cible, k * 0.05f)
            val v = pire(x)
            if (v > meilleurC) { meilleur = x; meilleurC = v }
            if (v >= min) return Ajustement(x, true, v, true)
        }
        return Ajustement(meilleur, meilleur != c, meilleurC, false)
    }

    /** Le texte sur le fond et sur les cartes. */
    fun ajusterTexte(encre: Int, fond: Int, carte: Int): Ajustement = ajuster(encre, listOf(fond, carte), TEXTE_MIN)

    /** L'accent (boutons, pastilles) sur le fond. */
    fun ajusterAccent(accent: Int, fond: Int): Ajustement = ajuster(accent, listOf(fond), GRAND_MIN)

    // ------------------------------------------------------------------ nom d'une couleur

    /** La teinte (0–360), la saturation et la valeur (0–1) d'une couleur opaque, sans Android. */
    fun hsv(c: Int): FloatArray {
        val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
    }

    /** La couleur de base (ressource) et sa nuance : -1 foncée, 0 franche, 1 claire. */
    fun famille(c: Int): Pair<Int, Int> {
        val (h, s, v) = hsv(c).let { Triple(it[0], it[1], it[2]) }
        if (v < 0.09f || (v < 0.16f && s < 0.35f)) return R.string.lot6_couleur_noir to 0
        if (h in 20f..55f && s in 0.06f..0.35f && v > 0.75f) return R.string.lot6_couleur_beige to 0
        if (s < 0.12f) return when {
            v > 0.93f -> R.string.lot6_couleur_blanc to 0
            else -> R.string.lot6_couleur_gris to (if (v < 0.35f) -1 else if (v > 0.7f) 1 else 0)
        }
        val nuance = if (v < 0.4f) -1 else if (v > 0.85f && s < 0.5f) 1 else 0
        val base = when {
            h < 12f || h >= 345f -> R.string.lot6_couleur_rouge
            h < 40f -> if (v < 0.6f) R.string.lot6_couleur_marron else R.string.lot6_couleur_orange
            h < 66f -> if (v < 0.5f) R.string.lot6_couleur_marron else R.string.lot6_couleur_jaune
            h < 165f -> R.string.lot6_couleur_vert
            h < 195f -> R.string.lot6_couleur_turquoise
            h < 255f -> R.string.lot6_couleur_bleu
            h < 290f -> R.string.lot6_couleur_violet
            else -> R.string.lot6_couleur_rose
        }
        // Le marron est déjà foncé : on ne le redit pas.
        return base to (if (base == R.string.lot6_couleur_marron) 0 else nuance)
    }

    /** « bleu foncé », « gris clair », « beige »… dans la langue de l'appareil. */
    fun nomCouleur(c: Int): String {
        val (base, nuance) = famille(c)
        val nom = Tr.s(base)
        return when (nuance) {
            -1 -> Tr.s(R.string.lot6_couleur_foncee, nom)
            1 -> Tr.s(R.string.lot6_couleur_claire, nom)
            else -> nom
        }
    }

    // ------------------------------------------------------------------ émojis décoratifs

    private fun emoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x1F1E6..0x1F1FF ||
            cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F

    /** Le texte sans ses émojis (et sans les espaces qu'ils laissent) : ce que le lecteur d'écran doit dire. */
    fun sansEmojis(s: String): String {
        if (s.none { it.isSurrogate() || emoji(it.code) }) return s
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!emoji(cp)) b.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return b.toString().replace(Regex("[ \\u00A0]{2,}"), " ").trim()
    }
}
