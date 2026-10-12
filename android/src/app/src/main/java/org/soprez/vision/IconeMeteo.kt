// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * L'icône météo de l'accueil, dessinée d'après l'état de Home Assistant (sunny, rainy…) :
 * la même famille de formes que la veille, sans animation, dans la couleur du thème.
 * Avant la 1.54.0, l'accueil montrait toujours un nuage de pluie, quel que soit le temps.
 */
class IconeMeteo(ctx: Context, private val couleur: Int) : View(ctx) {
    var etat: String = ""
        set(v) { if (field != v) { field = v; contentDescription = Meteo.libelle(v); invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleur }
    private val chemin = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = minOf(w, h) * 0.42f
        val x = w / 2f; val y = h / 2f
        p.color = couleur; p.alpha = 255; p.style = Paint.Style.FILL
        when (etat) {
            "sunny" -> soleil(c, x, y, r)
            "clear-night" -> lune(c, x, y, r)
            "partlycloudy" -> { soleil(c, x + r * 0.32f, y - r * 0.32f, r * 0.62f); nuage(c, x - r * 0.1f, y + r * 0.22f, r * 0.85f, 255) }
            "cloudy" -> { nuage(c, x + r * 0.25f, y - r * 0.2f, r * 0.65f, 130); nuage(c, x - r * 0.1f, y + r * 0.15f, r * 0.85f, 255) }
            "fog", "windy", "windy-variant" -> brume(c, x, y, r)
            "rainy" -> { nuage(c, x, y - r * 0.25f, r * 0.85f, 255); gouttes(c, x, y - r * 0.25f, r * 0.85f, 3) }
            "pouring" -> { nuage(c, x, y - r * 0.25f, r * 0.85f, 255); gouttes(c, x, y - r * 0.25f, r * 0.85f, 4) }
            "lightning", "lightning-rainy" -> { nuage(c, x, y - r * 0.3f, r * 0.85f, 255); eclair(c, x, y - r * 0.3f, r * 0.85f) }
            "snowy" -> { nuage(c, x, y - r * 0.25f, r * 0.85f, 255); flocons(c, x, y - r * 0.25f, r * 0.85f) }
            "snowy-rainy", "hail" -> { nuage(c, x, y - r * 0.25f, r * 0.85f, 255); gouttes(c, x - r * 0.2f, y - r * 0.25f, r * 0.85f, 2); flocons(c, x + r * 0.45f, y - r * 0.25f, r * 0.5f) }
            "" -> {}
            else -> nuage(c, x, y, r, 255)
        }
    }

    private fun soleil(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x, y, r * 0.45f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.11f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            c.drawLine(x + (r * 0.65f * cos(a)).toFloat(), y + (r * 0.65f * sin(a)).toFloat(),
                x + (r * 0.92f * cos(a)).toFloat(), y + (r * 0.92f * sin(a)).toFloat(), p)
        }
        p.style = Paint.Style.FILL
    }

    private fun lune(c: Canvas, x: Float, y: Float, r: Float) {
        chemin.reset()
        chemin.addCircle(x, y, r * 0.7f, Path.Direction.CW)
        val creux = Path().apply { addCircle(x + r * 0.35f, y - r * 0.25f, r * 0.6f, Path.Direction.CW) }
        chemin.op(creux, Path.Op.DIFFERENCE)
        c.drawPath(chemin, p)
    }

    private fun nuage(c: Canvas, x: Float, y: Float, r: Float, alpha: Int) {
        p.alpha = alpha
        c.drawRoundRect(RectF(x - r * 0.9f, y - r * 0.05f, x + r * 0.9f, y + r * 0.45f), r * 0.25f, r * 0.25f, p)
        c.drawCircle(x - r * 0.35f, y, r * 0.38f, p)
        c.drawCircle(x + r * 0.2f, y - r * 0.15f, r * 0.5f, p)
        p.alpha = 255
    }

    private fun gouttes(c: Canvas, x: Float, y: Float, r: Float, n: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.12f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until n) {
            val dx = x - r * 0.55f + i * (r * 1.1f / (n - 1).coerceAtLeast(1))
            c.drawLine(dx, y + r * 0.65f, dx - r * 0.12f, y + r * 0.95f, p)
        }
        p.style = Paint.Style.FILL
    }

    private fun flocons(c: Canvas, x: Float, y: Float, r: Float) {
        for (i in 0 until 3) c.drawCircle(x - r * 0.5f + i * r * 0.5f, y + r * 0.8f + (i % 2) * r * 0.15f, r * 0.1f, p)
    }

    private fun eclair(c: Canvas, x: Float, y: Float, r: Float) {
        chemin.reset()
        chemin.moveTo(x + r * 0.1f, y + r * 0.45f)
        chemin.lineTo(x - r * 0.2f, y + r * 0.95f)
        chemin.lineTo(x + r * 0.02f, y + r * 0.95f)
        chemin.lineTo(x - r * 0.12f, y + r * 1.3f)
        chemin.lineTo(x + r * 0.3f, y + r * 0.8f)
        chemin.lineTo(x + r * 0.08f, y + r * 0.8f)
        chemin.close()
        c.drawPath(chemin, p)
    }

    private fun brume(c: Canvas, x: Float, y: Float, r: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.14f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 3) c.drawLine(x - r * 0.8f + (i % 2) * r * 0.15f, y - r * 0.35f + i * r * 0.35f, x + r * 0.8f - ((i + 1) % 2) * r * 0.15f, y - r * 0.35f + i * r * 0.35f, p)
        p.style = Paint.Style.FILL
    }
}
