// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Le cadre de redimensionnement d'un widget de l'accueil, comme sur Android : posé par-dessus l'écran après un
 * appui long, aux couleurs du thème, avec une poignée au milieu de chaque bord que le widget permet de tirer.
 * Glisser une poignée agrandit ou réduit par pas de la grille ; chaque pas franchi donne une vibration légère.
 * Le calcul (bornes, collisions, pages) est fait par l'hôte, avec Redim : `pas` rend le rectangle (px) accepté.
 * Toucher hors du cadre le ferme ; « Options » ouvre le menu du widget.
 */
class CadreRedim(
    ctx: Context,
    private val theme: Theme,
    private var rect: RectF,
    private val horizontal: Boolean,
    private val vertical: Boolean,
    private val celluleL: Float,
    private val celluleH: Float,
    private val debut: () -> Unit,
    private val pas: (Redim.Bord, Int) -> RectF,
    private val fin: () -> Unit,
    private val fermer: () -> Unit,
    menu: () -> Unit,
) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = theme.or }
    private val voile = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = (theme.or and 0x00FFFFFF) or 0x1A000000 }
    private val plein = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var bord: Redim.Bord? = null
    private var x0 = 0f
    private var y0 = 0f
    private var touchePosee = false
    private val options: LinearLayout

    init {
        setWillNotDraw(false)
        isClickable = true
        contentDescription = ctx.getString(R.string.redim_description)
        options = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 999 * d; setColor(theme.carte); setStroke((1.5f * d).toInt(), theme.or) }
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { menu() }
        }
        options.addView(Ui.sansCesure(TextView(ctx).apply { text = ctx.getString(R.string.redim_options); textSize = 14f; setTextColor(theme.encre); typeface = Polices.demiGras(ctx) }))
        val ok = Ui.sansCesure(TextView(ctx).apply {
            text = ctx.getString(R.string.commun_ok); textSize = 14f; setTextColor(theme.or); typeface = Polices.gras(ctx)
            setPadding((16 * d).toInt(), 0, 0, 0); isClickable = true; setOnClickListener { fermer() }
        })
        options.addView(ok)
        addView(options, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        placerOptions()
    }

    /** « Options » sous le cadre (au-dessus s'il manque de place en bas). */
    private fun placerOptions() {
        post {
            val h = options.height.takeIf { it > 0 } ?: (40 * d).toInt()
            val w = options.width.takeIf { it > 0 } ?: (160 * d).toInt()
            val enBas = rect.bottom + 14 * d + h < height || height == 0
            options.translationX = (rect.centerX() - w / 2f).coerceIn(8 * d, (width - w - 8 * d).coerceAtLeast(8 * d))
            options.translationY = if (enBas) rect.bottom + 14 * d else (rect.top - 14 * d - h).coerceAtLeast(0f)
        }
    }

    private fun poignees(): List<Pair<Redim.Bord, Pair<Float, Float>>> {
        val l = ArrayList<Pair<Redim.Bord, Pair<Float, Float>>>()
        if (horizontal) { l.add(Redim.Bord.GAUCHE to (rect.left to rect.centerY())); l.add(Redim.Bord.DROITE to (rect.right to rect.centerY())) }
        if (vertical) { l.add(Redim.Bord.HAUT to (rect.centerX() to rect.top)); l.add(Redim.Bord.BAS to (rect.centerX() to rect.bottom)) }
        return l
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val r = 18 * d
        c.drawRoundRect(rect, r, r, voile)
        c.drawRoundRect(rect, r, r, trait)
        for ((_, p) in poignees()) {
            plein.color = theme.carte; c.drawCircle(p.first, p.second, 11 * d, plein)
            plein.color = theme.or; c.drawCircle(p.first, p.second, 7 * d, plein)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; touchePosee = true
                bord = poignees().minByOrNull { (_, p) -> hypot(p.first - e.x, p.second - e.y) }
                    ?.takeIf { (_, p) -> hypot(p.first - e.x, p.second - e.y) < 34 * d }?.first
                if (bord != null) { parent?.requestDisallowInterceptTouchEvent(true); debut() }
            }
            MotionEvent.ACTION_MOVE -> {
                val b = bord ?: return true
                val n = when (b) {
                    Redim.Bord.GAUCHE, Redim.Bord.DROITE -> Redim.pas(e.x - x0, celluleL)
                    Redim.Bord.HAUT, Redim.Bord.BAS -> Redim.pas(e.y - y0, celluleH)
                }
                val nouveau = pas(b, n)
                if (abs(nouveau.left - rect.left) > 0.5f || abs(nouveau.right - rect.right) > 0.5f || abs(nouveau.top - rect.top) > 0.5f || abs(nouveau.bottom - rect.bottom) > 0.5f) {
                    rect = nouveau
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    placerOptions()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (bord != null) { bord = null; fin() }
                else if (touchePosee && !rect.contains(e.x, e.y)) fermer()
                touchePosee = false
            }
            MotionEvent.ACTION_CANCEL -> { if (bord != null) { bord = null; fin() }; touchePosee = false }
        }
        return true
    }
}
