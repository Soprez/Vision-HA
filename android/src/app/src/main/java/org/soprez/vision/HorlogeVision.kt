// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

/**
 * L'horloge stylisée de Vision, celle de l'écran de veille : un anneau fin des secondes qui s'écrit autour de l'heure,
 * un point d'or qui le parcourt, et les deux points de l'heure qui battent la seconde (couleur d'accent).
 * Un seul dessin ([dessiner]) pour la veille (VeilleView) et l'accueil du téléphone ([Vue], 1.90.5).
 */
object HorlogeVision {
    class Couleurs(val encre: Int, val accent: Int, val or: Int)

    /**
     * L'anneau de rayon [r] centré en ([cx], [cy]) et l'heure [heure] (« HH:mm ») au centre, en [taille] px.
     * [sec] : la seconde en cours (avec sa fraction) ; [apparition] 0 → 1 ; [echelle] : le léger rebond d'apparition ;
     * [battement] : faux quand les animations sont réduites (les deux points restent pleins).
     */
    fun dessiner(c: Canvas, cx: Float, cy: Float, r: Float, taille: Float, heure: String, sec: Float, couleurs: Couleurs,
                 police: Typeface, pForme: Paint, pTexte: Paint, alpha: Float = 1f, apparition: Float = 1f, echelle: Float = 1f,
                 battement: Boolean = true) {
        val cadre = RectF(cx - r, cy - r, cx + r, cy + r)
        pForme.style = Paint.Style.STROKE; pForme.strokeWidth = r * 0.0143f; pForme.strokeCap = Paint.Cap.ROUND
        pForme.color = couleurs.or; pForme.alpha = (alpha * 60).toInt()
        c.drawArc(cadre, -90f, 360f, false, pForme)
        pForme.alpha = (alpha * 220 * apparition).toInt()
        c.drawArc(cadre, -90f, 360f * sec / 60f * apparition, false, pForme)
        pForme.style = Paint.Style.FILL
        val ang = Math.toRadians((-90f + 360f * sec / 60f * apparition).toDouble())
        pForme.color = couleurs.or; pForme.alpha = (alpha * 255 * apparition).toInt()
        c.drawCircle(cx + (r * cos(ang)).toFloat(), cy + (r * sin(ang)).toFloat(), r * 0.0286f, pForme)

        // L'heure : les deux points battent la seconde.
        c.save(); c.scale(echelle, echelle, cx, cy)
        val parties = heure.split(":")
        pTexte.typeface = police; pTexte.textSize = taille; pTexte.letterSpacing = 0f; pTexte.textAlign = Paint.Align.LEFT
        val lHeure = pTexte.measureText(parties[0]); val lDeux = pTexte.measureText(":"); val lMin = pTexte.measureText(parties.getOrElse(1) { "" })
        var x = cx - (lHeure + lDeux + lMin) / 2
        val base = cy + taille / 6f
        fun ecrire(s: String, couleur: Int, a: Float) { pTexte.color = couleur; pTexte.alpha = (a * 255).toInt().coerceIn(0, 255); c.drawText(s, x, base, pTexte) }
        ecrire(parties[0], couleurs.encre, alpha * apparition); x += lHeure
        val batt = if (battement) 0.35f + 0.65f * (0.5f + 0.5f * cos((sec % 1f) * 2 * Math.PI).toFloat()) else 1f
        ecrire(":", couleurs.accent, alpha * apparition * batt); x += lDeux
        ecrire(parties.getOrElse(1) { "" }, couleurs.encre, alpha * apparition)
        c.restore()
    }

    /**
     * L'horloge comme une vue (accueil du téléphone) : carrée, l'heure du système (12 h / 24 h). Légère, puisqu'elle
     * reste à l'écran : deux images par seconde (le point de l'anneau avance, les deux points passent de plein à pâle),
     * jamais hors de l'écran ; « réduire les animations » : une image par seconde, sans battement.
     */
    class Vue(ctx: Context, private val theme: Theme, private val h24: Boolean = android.text.format.DateFormat.is24HourFormat(ctx)) : View(ctx) {
        private val pForme = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pTexte = Paint(Paint.ANTI_ALIAS_FLAG)
        private val police = Polices.outfitFin(ctx)
        private val heureFmt = java.text.SimpleDateFormat(if (h24) "HH:mm" else "h:mm", java.util.Locale.getDefault())
        private val ampmFmt = java.text.SimpleDateFormat("a", java.util.Locale.getDefault())
        private val couleurs = Couleurs(theme.encre, theme.pourpre, theme.or)
        private val sansAnimation = OeilVue.animationsCoupees(ctx)
        private var derniereLue = ""

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; tag = "horloge_vision" }

        /** L'heure dessinée (« 14:05 », ou « 2:05 » en 12 h : l'indication AM / PM est écrite à part, en petit). */
        private fun heure(): String = heureFmt.format(Date())

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val cal = Calendar.getInstance()
            // La demi-seconde seulement (0 ou 0,5) : les deux points pleins puis pâles, sans image intermédiaire.
            val sec = cal.get(Calendar.SECOND) + if (sansAnimation || cal.get(Calendar.MILLISECOND) < 500) 0f else 0.5f
            val s = minOf(width, height).toFloat()
            if (s <= 0f) return
            val r = s * 0.47f
            val h = heure()
            val taille = r * 0.714f * ajustement(h, r)
            dessiner(c, width / 2f, height / 2f, r, taille, h, sec, couleurs, police, pForme, pTexte, battement = !sansAnimation)
            if (!h24) {
                pTexte.typeface = police; pTexte.textSize = taille * 0.28f; pTexte.textAlign = Paint.Align.CENTER
                pTexte.color = theme.encre2; pTexte.alpha = 255
                c.drawText(ampmFmt.format(Date()), width / 2f, height / 2f + taille / 6f + taille * 0.42f, pTexte)
                pTexte.textAlign = Paint.Align.LEFT
            }
            if (h != derniereLue) {
                derniereLue = h
                contentDescription = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            }
            // Prochaine image : à la demi-seconde suivante (la seconde, sans animations) ; rien hors de l'écran.
            if (isShown) {
                val ms = System.currentTimeMillis()
                postInvalidateDelayed(if (sansAnimation) 1000L - ms % 1000L else 500L - ms % 500L)
            }
        }

        /** L'heure tient toujours dans l'anneau (4 ou 5 chiffres, police large) : la taille baisse au besoin. */
        private fun ajustement(h: String, r: Float): Float {
            pTexte.typeface = police; pTexte.textSize = r * 0.714f; pTexte.letterSpacing = 0f
            val l = pTexte.measureText(h)
            return if (l > r * 1.7f) r * 1.7f / l else 1f
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE) invalidate()
        }
    }
}
