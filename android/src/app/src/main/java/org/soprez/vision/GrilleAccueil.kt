// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup

/**
 * La grille de l'accueil du téléphone, comme celle d'Android : quatre
 * colonnes, des cases de 1 × 1 (applis, dossiers) ou plus (widgets), posées
 * où l'on veut. Chaque enfant porte en tag sa `Accueil.Place`. Pendant un
 * glisser, la grille dessine la cellule visée en pointillés.
 */
class GrilleAccueil(ctx: Context) : ViewGroup(ctx) {
    var hauteurCellule = 0
    var lignesMin = 5
    /** En mode pages : la première ligne de cette page, et une hauteur fixe de `lignesMin` lignes. */
    var decalageLignes = 0
    var hauteurFixe = false
    /**
     * 1.90.1 (paysage) : la largeur d'une case, fixe, et la grille centrée dans sa place (0 = toute la largeur) ;
     * la hauteur minimale d'une case (0 = aucune) : une page en hauteur fixe ne tasse jamais une case sous son
     * contenu (c'est ce qui empilait trois icônes par case).
     */
    var largeurCelluleFixe = 0
    var hauteurMinCellule = 0
    var cible: RectF? = null
    var couleurCible = 0xFFE2B24A.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f) }

    init { setWillNotDraw(false); clipChildren = false; clipToPadding = false }

    val largeurCellule get() = if (largeurCelluleFixe > 0) minOf(largeurCelluleFixe, (width - paddingLeft - paddingRight) / Accueil.COLONNES)
        else (width - paddingLeft - paddingRight) / Accueil.COLONNES

    /** Le retrait à gauche qui centre la grille quand ses cases ont une largeur fixe. */
    val retrait get() = paddingLeft + maxOf(0, (width - paddingLeft - paddingRight - largeurCellule * Accueil.COLONNES) / 2)

    fun lignes(): Int {
        if (hauteurFixe) return lignesMin
        var m = lignesMin
        for (i in 0 until childCount) { val pl = getChildAt(i).tag as? Accueil.Place ?: continue; m = maxOf(m, pl.row - decalageLignes + pl.h) }
        return m
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val toute = (w - paddingLeft - paddingRight) / Accueil.COLONNES
        val lc = if (largeurCelluleFixe > 0) minOf(largeurCelluleFixe, toute) else toute
        if (hauteurCellule == 0) hauteurCellule = (lc * 1.12f).toInt()
        if (hauteurFixe && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val dispo = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
            if (dispo > 0 && lignesMin > 0) hauteurCellule = maxOf(hauteurMinCellule, minOf(hauteurCellule, dispo / lignesMin))
        }
        val h = paddingTop + paddingBottom + lignes() * hauteurCellule
        for (i in 0 until childCount) {
            val v = getChildAt(i); val pl = v.tag as? Accueil.Place ?: continue
            v.measure(MeasureSpec.makeMeasureSpec(lc * pl.w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hauteurCellule * pl.h, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val lc = largeurCellule
        for (i in 0 until childCount) {
            val v = getChildAt(i); val pl = v.tag as? Accueil.Place ?: continue
            val x = retrait + pl.col * lc; val y = paddingTop + (pl.row - decalageLignes) * hauteurCellule
            v.layout(x, y, x + lc * pl.w, y + hauteurCellule * pl.h)
        }
    }

    /** La cellule (colonne, ligne) sous un point de la grille. */
    fun cellule(x: Float, y: Float): Pair<Int, Int> {
        val lc = largeurCellule.coerceAtLeast(1); val hc = hauteurCellule.coerceAtLeast(1)
        return ((x - retrait) / lc).toInt().coerceIn(0, Accueil.COLONNES - 1) to (((y - paddingTop) / hc).toInt().coerceAtLeast(0) + decalageLignes)
    }

    fun montrerCible(col: Int, row: Int, w: Int, h: Int) {
        val lc = largeurCellule; val x = retrait + col * lc.toFloat(); val y = paddingTop + (row - decalageLignes) * hauteurCellule.toFloat()
        cible = RectF(x + 8f, y + 8f, x + lc * w - 8f, y + hauteurCellule * h - 8f)
        invalidate()
    }

    fun cacherCible() { cible = null; invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        cible?.let { p.color = couleurCible; c.drawRoundRect(it, 28f, 28f, p) }
    }
}
