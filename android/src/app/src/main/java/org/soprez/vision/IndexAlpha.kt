// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * L'index alphabétique du mode « Liste alphabétique » (téléphone) : A à Z et « # », du haut en bas de la vue.
 *
 * - En glissant dessus, la lettre sous le doigt grossit et sort vers la liste, ses voisines un peu (effet loupe,
 *   [ListeAlpha.echelle] / [ListeAlpha.decalage]) ; un retour haptique marque chaque nouvelle lettre ; [surLettre]
 *   reçoit la lettre visée (la liste saute aux applis de cette lettre, ou de la plus proche si elle est grisée).
 * - Les lettres sans appli sont grisées ([presentes]).
 * - [gauche] : l'index est à gauche (gauchers) ; la loupe sort alors vers la droite.
 * - TalkBack : une commande réglable (« Index alphabétique, M ») ; balayer vers le haut ou le bas passe à la lettre
 *   suivante ou précédente qui a des applis, et la liste suit.
 * Le dessin ne fait que du texte : il tient les 60 images par seconde.
 */
class IndexAlpha(ctx: Context, private val th: Theme, private val gauche: Boolean,
                 private val surLettre: (Char, Float) -> Unit, private val surFin: () -> Unit) : View(ctx) {

    var presentes: Set<Char> = emptySet()
        set(v) { field = v; invalidate() }

    /** La lettre courante (la dernière visée), pour TalkBack et le dessin au repos. */
    var courante: Char = ListeAlpha.LETTRES.first()
        private set

    private var centre: Int? = null
    private val tailleTexte = Ui.dp(ctx, 11.5f).toFloat() * ctx.resources.configuration.fontScale.coerceIn(1f, 1.3f)
    private val sortie = Ui.dp(ctx, 26f).toFloat()
    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Polices.gras(ctx) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        contentDescription = ctx.getString(R.string.lanceur_index_alpha)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = ListeAlpha.LETTRES.size
        val pas = height / n.toFloat()
        val x0 = width / 2f
        ListeAlpha.LETTRES.forEachIndexed { i, l ->
            val e = ListeAlpha.echelle(i, centre)
            val dx = ListeAlpha.decalage(i, centre) * sortie * (if (gauche) 1f else -1f)
            peinture.textSize = tailleTexte * e
            peinture.color = when {
                centre == i -> th.or
                l !in presentes -> Palette.melanger(th.encre3, th.fond, 0.45f)
                centre != null && e > 1f -> th.encre
                else -> th.encre2
            }
            val y = pas * i + pas / 2f - (peinture.descent() + peinture.ascent()) / 2f
            c.drawText(l.toString(), x0 + dx, y, peinture)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = ListeAlpha.indexA(e.y, height.toFloat())
                if (i != centre) {
                    centre = i
                    courante = ListeAlpha.LETTRES[i]
                    performHapticFeedback(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK)
                    surLettre(courante, top + (height / ListeAlpha.LETTRES.size.toFloat()) * (i + 0.5f))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                centre = null
                invalidate()
                surFin()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    // ------------------------------------------------------------------ TalkBack

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        val k = ListeAlpha.LETTRES.indexOf(courante)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, (ListeAlpha.LETTRES.size - 1).toFloat(), k.toFloat())
        if (Build.VERSION.SDK_INT >= 30) info.stateDescription = courante.toString()
        else info.contentDescription = context.getString(R.string.lanceur_index_alpha) + ", " + courante
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val sens = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            else -> return super.performAccessibilityAction(action, args)
        }
        val l = ListeAlpha.voisine(courante, sens, presentes) ?: return false
        aller(l)
        return true
    }

    /** Sauter à une lettre (TalkBack, essais) : la liste suit, l'accessibilité annonce la nouvelle lettre. */
    fun aller(l: Char) {
        courante = l
        surLettre(l, top + (height / ListeAlpha.LETTRES.size.toFloat()) * (ListeAlpha.LETTRES.indexOf(l) + 0.5f))
        surFin()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        invalidate()
    }
}
