// SPDX-License-Identifier: MIT
package org.soprez.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Le redimensionnement des widgets de l'accueil (appui long → cadre à poignées → glisser), sans Android :
 * bornes d'un widget (AppWidgetProviderInfo d'une autre appli, ou tailles permises d'un widget Vision /
 * Home Assistant), calcul pas à pas, collisions et pages. Essayé en JUnit (RedimTest).
 */
object Redim {
    /** Une case de la grille, en cellules. */
    data class Rect(val col: Int, val row: Int, val w: Int, val h: Int) {
        fun chevauche(o: Rect): Boolean = col < o.col + o.w && o.col < col + w && row < o.row + o.h && o.row < row + h
    }

    /** Une case posée (sa clé et sa place). */
    data class Case(val cle: String, val r: Rect)

    /** Les tailles permises, en cellules ; un axe figé (resizeMode) garde la taille actuelle. */
    data class Bornes(val minW: Int, val minH: Int, val maxW: Int, val maxH: Int, val horizontal: Boolean = true, val vertical: Boolean = true) {
        fun permet(w: Int, h: Int): Boolean = w in minW..maxW && h in minH..maxH
    }

    enum class Bord { GAUCHE, DROITE, HAUT, BAS }

    const val RESIZE_HORIZONTAL = 1   // AppWidgetProviderInfo.RESIZE_HORIZONTAL
    const val RESIZE_VERTICAL = 2     // AppWidgetProviderInfo.RESIZE_VERTICAL
    const val LIGNES_MAX = 12

    /** Des dp en cellules (au plus juste : 1 % de marge pour les arrondis des applis). */
    fun cellulesPour(dp: Int, celluleDp: Float): Int = ceil(dp / celluleDp.coerceAtLeast(1f) - 0.01f).toInt().coerceAtLeast(1)

    /** Le plus grand nombre de cellules qui tiennent dans ces dp (pour un maximum). */
    fun cellulesDans(dp: Int, celluleDp: Float): Int = floor(dp / celluleDp.coerceAtLeast(1f) + 0.01f).toInt().coerceAtLeast(1)

    /**
     * Les bornes d'un widget d'une autre appli, d'après AppWidgetProviderInfo : minWidth/minHeight, minResizeWidth/
     * minResizeHeight (plus petites, si l'appli les donne), maxResizeWidth/maxResizeHeight (Android 12+, 0 = sans limite),
     * resizeMode. `actuelle` : la taille posée, gardée sur un axe que l'appli ne laisse pas changer.
     */
    fun bornesAndroid(
        minWidth: Int, minHeight: Int, minResizeWidth: Int, minResizeHeight: Int, maxResizeWidth: Int, maxResizeHeight: Int,
        resizeMode: Int, celluleLDp: Float, celluleHDp: Float, colonnes: Int, actuelle: Pair<Int, Int>,
    ): Bornes {
        val h = resizeMode and RESIZE_HORIZONTAL != 0
        val v = resizeMode and RESIZE_VERTICAL != 0
        fun mini(min: Int, minRedim: Int, cel: Float) = cellulesPour(if (minRedim in 1..min) minRedim else min.coerceAtLeast(1), cel)
        var minW = mini(minWidth, minResizeWidth, celluleLDp).coerceAtMost(colonnes)
        var minH = mini(minHeight, minResizeHeight, celluleHDp).coerceAtMost(LIGNES_MAX)
        var maxW = if (maxResizeWidth > 0) cellulesDans(maxResizeWidth, celluleLDp).coerceIn(minW, colonnes) else colonnes
        var maxH = if (maxResizeHeight > 0) cellulesDans(maxResizeHeight, celluleHDp).coerceIn(minH, LIGNES_MAX) else LIGNES_MAX
        if (!h) { minW = actuelle.first; maxW = actuelle.first }
        if (!v) { minH = actuelle.second; maxH = actuelle.second }
        return Bornes(minW, minH, maxW, maxH, h, v)
    }

    /** La case tient-elle dans la grille (colonnes, une seule page) sans chevaucher les autres ? */
    fun libre(autres: List<Case>, cle: String, r: Rect, colonnes: Int, lignesParPage: Int = 0): Boolean {
        if (r.col < 0 || r.row < 0 || r.w < 1 || r.h < 1 || r.col + r.w > colonnes) return false
        if (lignesParPage > 0 && r.row / lignesParPage != (r.row + r.h - 1) / lignesParPage) return false
        return autres.none { it.cle != cle && it.r.chevauche(r) }
    }

    /** La case après avoir tiré un bord de `pas` cellules (positif = vers la droite / le bas), sans bornes ni collisions. */
    fun tirer(r: Rect, bord: Bord, pas: Int): Rect = when (bord) {
        Bord.DROITE -> r.copy(w = r.w + pas)
        Bord.BAS -> r.copy(h = r.h + pas)
        Bord.GAUCHE -> r.copy(col = r.col + pas, w = r.w - pas)
        Bord.HAUT -> r.copy(row = r.row + pas, h = r.h - pas)
    }

    /**
     * La nouvelle place quand on tire `bord` de `pas` cellules depuis `depart` : on avance d'une cellule à la fois et
     * l'on s'arrête au dernier pas permis (bornes, bord de la grille, page, autre case). Le bord opposé ne bouge pas.
     */
    fun calculer(autres: List<Case>, cle: String, depart: Rect, bord: Bord, pas: Int, bornes: Bornes, colonnes: Int, lignesParPage: Int = 0): Rect {
        val horizontal = bord == Bord.GAUCHE || bord == Bord.DROITE
        if (horizontal && !bornes.horizontal || !horizontal && !bornes.vertical) return depart
        var courant = depart
        val sens = if (pas > 0) 1 else -1
        for (k in 1..abs(pas)) {
            val essai = tirer(depart, bord, k * sens)
            if (!bornes.permet(essai.w, essai.h)) {
                // Une taille de départ hors bornes (ancienne taille) : on laisse aller vers les bornes.
                val versBornes = if (horizontal) abs(essai.w - borne(essai.w, bornes.minW, bornes.maxW)) < abs(courant.w - borne(courant.w, bornes.minW, bornes.maxW))
                                 else abs(essai.h - borne(essai.h, bornes.minH, bornes.maxH)) < abs(courant.h - borne(courant.h, bornes.minH, bornes.maxH))
                if (!versBornes || essai.w < 1 || essai.h < 1) break
            }
            if (!libre(autres, cle, essai, colonnes, lignesParPage)) break
            courant = essai
        }
        return courant
    }

    private fun borne(x: Int, min: Int, max: Int) = x.coerceIn(min, maxOf(min, max))

    /** Combien de cellules entières le doigt a parcouru (un demi-pas suffit pour passer au suivant). */
    fun pas(deplacementPx: Float, cellulePx: Float): Int {
        if (cellulePx <= 0f) return 0
        val q = deplacementPx / cellulePx
        return if (q >= 0) floor(q + 0.5f).toInt() else -floor(-q + 0.5f).toInt()
    }

    /**
     * Les tailles à annoncer au widget (dp) pour une case w × h : la largeur et la hauteur réelles,
     * en portrait comme en paysage (l'accueil ne tourne pas : mêmes valeurs).
     */
    fun tailleDp(w: Int, h: Int, celluleLDp: Float, celluleHDp: Float, margeDp: Float = 8f): Pair<Int, Int> =
        ((w * celluleLDp - margeDp).toInt().coerceAtLeast(1)) to ((h * celluleHDp - margeDp).toInt().coerceAtLeast(1))
}
