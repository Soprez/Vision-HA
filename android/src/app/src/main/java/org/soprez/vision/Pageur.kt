// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Les pages de l'accueil, comme sur Android : on feuillette au doigt, la
 * page s'aimante quand on lâche. Pendant un glisser, une page de plus est
 * atteignable à droite pour y poser une case.
 */
class Pageur(ctx: Context) : HorizontalScrollView(ctx) {
    val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    var pagesVisibles = 1
    var surPage: (Int) -> Unit = {}
    private var courante = 0
    private var enFling = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false; clipToPadding = false
        addView(rangee)
    }

    val page get() = courante
    val pages get() = rangee.childCount

    fun ajouterPage(v: View) { rangee.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        for (i in 0 until rangee.childCount) rangee.getChildAt(i).layoutParams.width = w
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun aller(p: Int, doux: Boolean = true) {
        val cible = p.coerceIn(0, (pages - 1).coerceAtLeast(0))
        if (cible != courante) { courante = cible; surPage(courante) }
        if (doux) smoothScrollTo(cible * width, 0) else scrollTo(cible * width, 0)
    }

    private fun aimanter(vx: Float = 0f) {
        if (width == 0) return
        val limite = (pagesVisibles - 1).coerceIn(0, pages - 1)
        // HorizontalScrollView passe l'opposé de la vitesse du doigt : positif = le contenu part vers la gauche, page suivante.
        val delta = scrollX - courante * width
        val p = when {
            vx > 400 -> courante + 1
            vx < -400 -> courante - 1
            delta > width * 0.22f -> courante + 1
            delta < -width * 0.22f -> courante - 1
            else -> courante
        }
        aller(p.coerceIn(0, limite))
    }

    override fun fling(velocityX: Int) { enFling = true; aimanter(velocityX.toFloat()) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) enFling = false
        val r = super.onTouchEvent(e)
        if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) post { if (!enFling) aimanter(); enFling = false }
        return r
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        post { scrollTo(courante * w, 0) }
    }
}
