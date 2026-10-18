// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView

/**
 * 1.91.2 (l'auteur : « les widgets ne sont toujours pas scrollables ») : le contenu d'un widget (liste d'une to-do, d'un
 * agenda, attributs) qui DÉFILE dans la tuile, sans « +N » ni texte coupé.
 *
 * - Gestes imbriqués : tant que la liste peut défiler dans le sens du doigt, elle garde le geste ; arrivée au bout,
 *   elle le rend à l'accueil (qui défile à son tour). Un geste plutôt horizontal reste à l'accueil (pages).
 * - Un toucher simple et un appui long sont ceux du widget ([tuile]) : ouvrir Home Assistant (règle « Au toucher »),
 *   déplacer ou régler le widget sur l'accueil.
 * - Indicateur discret : un fondu en haut et en bas quand il reste à lire, et la fine barre de défilement du système.
 * - Hauteur : au plus [hauteurMax] px (la hauteur maximale choisie pour le widget), sinon la place de la tuile.
 */
class DefilementWidget(ctx: Context, private val tuile: View, private val hauteurMax: Int) : ScrollView(ctx) {
    private val pente = ViewConfiguration.get(ctx).scaledTouchSlop
    private var departX = 0f
    private var departY = 0f
    private var decide = false

    private val gestes = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean { tuile.performClick(); return true }
        override fun onLongPress(e: MotionEvent) {
            // L'appui long de l'accueil (déplacer, régler) : la tuile, ou le cadre qui le porte.
            var v: View? = tuile
            while (v != null && !v.isLongClickable) v = v.parent as? View
            (v ?: tuile).performLongClick()
        }
    })

    init {
        isVerticalScrollBarEnabled = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((16 * resources.displayMetrics.density).toInt())
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
        // Sur la télé, le focus reste à la tuile : elle fait défiler la liste aux flèches (VueWidgetHa).
        isFocusable = false; descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    /** Peut-il défiler ? (le contenu dépasse) */
    fun defilable(): Boolean = canScrollVertically(1) || canScrollVertically(-1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var spec = heightMeasureSpec
        if (hauteurMax > 0) {
            val mode = MeasureSpec.getMode(spec); val taille = MeasureSpec.getSize(spec)
            spec = if (mode == MeasureSpec.UNSPECIFIED) MeasureSpec.makeMeasureSpec(hauteurMax, MeasureSpec.AT_MOST)
                else MeasureSpec.makeMeasureSpec(minOf(taille, hauteurMax), mode)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Les lignes ne sont pas cliquables : le toucher arrive ici, dans onTouchEvent.
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestes.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                departX = ev.x; departY = ev.y; decide = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!decide) {
                val dx = ev.x - departX; val dy = ev.y - departY
                if (Math.abs(dx) > pente || Math.abs(dy) > pente) {
                    decide = true
                    val vertical = Math.abs(dy) > Math.abs(dx)
                    // Vers le bas du contenu quand le doigt monte (dy < 0).
                    val garde = vertical && canScrollVertically(if (dy < 0) 1 else -1)
                    parent?.requestDisallowInterceptTouchEvent(garde)
                    if (!garde) return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(ev) || true
    }

    /** La télécommande (VueWidgetHa) : descendre / monter d'un pas ; faux au bout (le focus repart). */
    fun pas(versLeBas: Boolean): Boolean {
        if (!canScrollVertically(if (versLeBas) 1 else -1)) return false
        smoothScrollBy(0, (if (versLeBas) 1 else -1) * (height * 2 / 3).coerceAtLeast(1))
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
