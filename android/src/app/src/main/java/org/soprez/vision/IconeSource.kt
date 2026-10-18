// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Retours de l'auteur (15-10) : la source d'un résultat de musique (YouTube Music, Bibliothèque…) se dit par une petite
 * icône, plus par son nom écrit. Des pictogrammes simples aux couleurs de chaque service, dessinés ici (pas les
 * logos officiels) ; le nom du service reste dans la description lue par les lecteurs d'écran.
 */
class IconeSource(private val forme: Forme, private val couleur: Int, private val accent: Int) : Drawable() {
    enum class Forme { LECTURE, ONDES, BARRES, LOSANGES, NOTE, NUAGE, LIVRES, DOSSIER, RADIO, Q }

    companion object {
        /** La forme et la couleur d'une source (domaine de Music Assistant) ; [accent] pour la bibliothèque et l'inconnu. */
        fun pour(domaine: String, accent: Int): Pair<Forme, Int> = when {
            domaine == "ytmusic" || domaine == "youtube_music" -> Forme.LECTURE to 0xFFC8102E.toInt()
            domaine == "spotify" -> Forme.ONDES to 0xFF1DB954.toInt()
            domaine == "deezer" -> Forme.BARRES to 0xFFA238FF.toInt()
            domaine == "tidal" -> Forme.LOSANGES to 0xFF111111.toInt()
            domaine == "apple_music" -> Forme.NOTE to 0xFFFA243C.toInt()
            domaine == "qobuz" -> Forme.Q to 0xFF0170CC.toInt()
            domaine == "soundcloud" -> Forme.NUAGE to 0xFFFF5500.toInt()
            domaine.startsWith("filesystem") -> Forme.DOSSIER to accent
            domaine == "tunein" || domaine == "radiobrowser" || domaine == "radio" -> Forme.RADIO to 0xFF0170CC.toInt()
            domaine.isEmpty() || domaine == "library" -> Forme.LIVRES to accent
            else -> Forme.NOTE to accent
        }

        fun creer(domaine: String, accent: Int): IconeSource = pour(domaine, accent).let { (f, c) -> IconeSource(f, c, accent) }
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        c.save()
        c.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f)
        c.scale(s / 16f, s / 16f)
        p.style = Paint.Style.FILL; p.color = couleur
        val blanc = 0xFFFFFFFF.toInt()
        when (forme) {
            Forme.LECTURE -> { c.drawCircle(8f, 8f, 8f, p); p.color = blanc; c.drawPath(Path().apply { moveTo(6.3f, 5f); lineTo(11.3f, 8f); lineTo(6.3f, 11f); close() }, p) }
            Forme.ONDES -> {
                c.drawCircle(8f, 8f, 8f, p); p.color = 0xFF111111.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f; p.strokeCap = Paint.Cap.ROUND
                c.drawPath(Path().apply { moveTo(4f, 6.2f); quadTo(8f, 4.9f, 12f, 6.9f) }, p)
                c.drawPath(Path().apply { moveTo(4.6f, 8.5f); quadTo(7.8f, 7.6f, 10.9f, 9.1f) }, p)
                c.drawPath(Path().apply { moveTo(5.2f, 10.6f); quadTo(7.6f, 10f, 9.8f, 11f) }, p)
            }
            Forme.BARRES -> { c.drawRoundRect(RectF(0f, 0f, 16f, 16f), 4f, 4f, p); p.color = blanc
                c.drawRect(3f, 10f, 5f, 13f, p); c.drawRect(6f, 8f, 8f, 13f, p); c.drawRect(9f, 5f, 11f, 13f, p); c.drawRect(12f, 7f, 13.6f, 13f, p) }
            Forme.LOSANGES -> { c.drawRoundRect(RectF(0f, 0f, 16f, 16f), 4f, 4f, p); p.color = blanc
                for ((x, y) in listOf(4.5f to 7f, 8f to 7f, 11.5f to 7f, 8f to 10.5f))
                    c.drawPath(Path().apply { moveTo(x, y - 2f); lineTo(x + 2f, y); lineTo(x, y + 2f); lineTo(x - 2f, y); close() }, p) }
            Forme.NOTE -> { c.drawRoundRect(RectF(0f, 0f, 16f, 16f), 4f, 4f, p); p.color = blanc
                c.drawCircle(6f, 11.3f, 1.7f, p); c.drawCircle(10.5f, 10.3f, 1.7f, p); c.drawRect(7f, 4.6f, 7.9f, 11.3f, p); c.drawRect(11.5f, 3.6f, 12.3f, 10.3f, p)
                c.drawPath(Path().apply { moveTo(7f, 4.6f); lineTo(12.3f, 3.6f); lineTo(12.3f, 5f); lineTo(7f, 6f); close() }, p) }
            Forme.Q -> { c.drawCircle(8f, 8f, 8f, p); p.color = blanc; p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f
                c.drawCircle(7.6f, 7.6f, 3.4f, p); c.drawLine(9.6f, 9.6f, 12f, 12f, p) }
            Forme.NUAGE -> { c.drawCircle(8f, 8f, 8f, p); p.color = blanc
                c.drawCircle(6.2f, 9.2f, 2f, p); c.drawCircle(8.8f, 8f, 2.8f, p); c.drawCircle(11.3f, 9.4f, 1.8f, p); c.drawRect(5.2f, 9.4f, 11.6f, 11.2f, p) }
            Forme.LIVRES -> { c.drawRoundRect(RectF(2f, 3f, 5f, 13f), .6f, .6f, p); c.drawRoundRect(RectF(6.5f, 3f, 9.5f, 13f), .6f, .6f, p)
                c.save(); c.rotate(12f, 12f, 8f); c.drawRoundRect(RectF(10.6f, 3.4f, 13.6f, 13.4f), .6f, .6f, p); c.restore() }
            Forme.DOSSIER -> c.drawPath(Path().apply { moveTo(1.5f, 4f); lineTo(6f, 4f); lineTo(7.5f, 5.5f); lineTo(14.5f, 5.5f); lineTo(14.5f, 13f); lineTo(1.5f, 13f); close() }, p)
            Forme.RADIO -> { c.drawRoundRect(RectF(2f, 5f, 14f, 14f), 2f, 2f, p); p.color = blanc; c.drawCircle(10.5f, 9.5f, 2f, p)
                p.color = couleur; p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; c.drawLine(4f, 5f, 11f, 2f, p) }
        }
        p.style = Paint.Style.FILL; p.strokeWidth = 0f
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
    @Deprecated("API < 29") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
