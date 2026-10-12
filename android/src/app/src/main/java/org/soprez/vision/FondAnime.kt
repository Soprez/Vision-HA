// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Le fond du lanceur : le même ciel que l'écran de veille, trois lueurs qui
 * dérivent lentement sur le fond du thème, redessiné en douceur (20 images
 * par seconde suffisent pour un mouvement aussi lent).
 */
class FondAnime(ctx: Context, private val theme: () -> Theme) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depart = SystemClock.uptimeMillis()

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - depart) / 1000f
        val th = theme()
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(th.fond)
        val f = if (th.sombre) 0.5f else 1f
        halo(c, w * (0.22f + 0.08f * sin(t / 47f)), h * (0.30f + 0.10f * cos(t / 53f)), h * 0.95f, th.halos[0], 0.22f * f)
        halo(c, w * (0.80f + 0.07f * cos(t / 59f)), h * (0.72f + 0.08f * sin(t / 43f)), h * 0.85f, th.halos[1], 0.17f * f)
        halo(c, w * (0.62f + 0.09f * sin(t / 71f)), h * (0.12f + 0.07f * cos(t / 61f)), h * 0.70f, th.halos[2], 0.15f * f)
        if (isAttachedToWindow) postInvalidateDelayed(80)
    }

    private fun halo(c: Canvas, x: Float, y: Float, r: Float, rgb: Int, force: Float) {
        val centre = Color.argb((force * 255).toInt().coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        p.shader = RadialGradient(x, y, r, intArrayOf(centre, Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }
}
