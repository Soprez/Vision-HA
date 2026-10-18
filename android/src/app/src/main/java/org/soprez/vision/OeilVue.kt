// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.min
import kotlin.math.pow

/**
 * L'œil Vision dessiné en procédural (OeilModele donne la pose, cette vue la dessine).
 * - [joyeux] : l'œil de la zone du haut (clin d'œil d'apparition, clignements, sourire des yeux) ;
 * - [reagir] : une réaction « OK » (A, B ou C), puis [fin] est appelée.
 * Coût : ne redessine que pendant un mouvement (l'œil joyeux posé ne redessine que pour cligner et sourire, à 30 i/s
 * au plus) ; s'arrête quand la vue n'est plus visible ; « réduire les animations » : une pose fixe.
 */
class OeilVue(ctx: Context) : View(ctx) {
    // Les couleurs de l'œil sont celles du logo Vision (identité), le halo prend la couleur du sens (thème).
    private val rouge = 0xFFB2222B.toInt()
    private val or = 0xFFE9B440.toInt()
    private val blanc = 0xFFFAF4E8.toInt()
    private val pupille = 0xFF1E6046.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val amande = Path()
    private var pose = OeilModele.Pose()
    private var genre = OeilModele.Genre.JOIE
    private var mode = 0  // 0 : immobile, 1 : joyeux, 2 : réaction
    private var variante = OeilModele.Variante.B
    private var debut = 0L
    private var dernier = 0L
    var fin: (() -> Unit)? = null
    /** Cadence maximale (images/s) ; 0 : celle de l'écran. L'œil sur le bouton Accueil reste à 30 au plus. */
    var cadenceMax = 0

    /** La prochaine image : à la cadence de l'écran, ou pas plus souvent que [cadenceMax]. */
    private fun suivante() { if (cadenceMax > 0) postInvalidateDelayed(1000L / cadenceMax) else postInvalidateOnAnimation() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    companion object {
        /** Les animations du système sont-elles coupées (« supprimer les animations », échelle 0) ? */
        fun animationsCoupees(ctx: Context): Boolean = try {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
    }

    private fun couleur(g: OeilModele.Genre) = when (g) {
        OeilModele.Genre.SUCCES -> Ui.VERT; OeilModele.Genre.ERREUR -> Ui.ROUGE; OeilModele.Genre.INFO -> Ui.BLEU
        OeilModele.Genre.JOIE -> or
    }

    /** Une pose donnée, sans animation (maquettes et essais : le rendu image par image du vrai dessin). */
    fun poser(pose: OeilModele.Pose, g: OeilModele.Genre) { mode = 0; this.pose = pose; genre = g; invalidate() }

    fun joyeux() {
        genre = OeilModele.Genre.JOIE; mode = 1; debut = SystemClock.uptimeMillis()
        if (animationsCoupees(context)) { mode = 0; pose = OeilModele.Pose(halo = 0.4f); invalidate(); return }
        postInvalidateOnAnimation()
    }

    fun reagir(v: OeilModele.Variante, g: OeilModele.Genre) {
        variante = v; genre = g; debut = SystemClock.uptimeMillis()
        if (animationsCoupees(context)) {
            mode = 0; pose = OeilModele.figee(v); invalidate()
            postDelayed({ fin?.invoke() }, OeilModele.DUREE_FIGEE_MS)
            return
        }
        mode = 2; postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && mode != 0) postInvalidateOnAnimation()
    }

    private fun avancer() {
        val maintenant = SystemClock.uptimeMillis()
        when (mode) {
            1 -> {
                val s = (maintenant - debut) / 1000f
                pose = OeilModele.joyeux(s)
                // Posé : seulement pendant un clignement ou un sourire ; sinon un réveil au prochain geste, sans dessin.
                if (isShown) {
                    if (OeilModele.bouge(s)) suivante()
                    else postDelayed({ if (isShown) postInvalidateOnAnimation() }, 250)
                }
            }
            2 -> {
                val t = (maintenant - debut) / OeilModele.DUREE_REACTION_MS.toFloat()
                pose = OeilModele.reaction(variante, genre, t.coerceAtMost(1f))
                if (t < 1f) suivante() else { mode = 0; post { fin?.invoke() } }
            }
        }
        dernier = maintenant
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        avancer()
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f + pose.dx * s; val cy = height / 2f + pose.dy * s
        val r = 0.40f * s * pose.echelle
        val teinte = couleur(genre)
        // Le halo (dégradé radial, sans flou coûteux) et l'onde.
        if (pose.halo > 0f) {
            // Halo et onde restent dans la vue (sinon coupés au bord) : 0,5 × la taille au plus.
            val rh = min(r * (1.18f + 0.1f * pose.halo), 0.5f * s)
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx, cy, rh, intArrayOf(alpha(teinte, 0.6f * pose.halo), alpha(teinte, 0f)), floatArrayOf(0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, rh, p); p.shader = null
        }
        if (pose.anneau > 0f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.02f; p.color = alpha(teinte, 0.9f * (1 - pose.anneau))
            c.drawCircle(cx, cy, r + (0.48f * s - r) * pose.anneau, p)
        }
        // Le disque rouge et l'anneau doré.
        p.style = Paint.Style.FILL; p.color = rouge; c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.07f; p.color = or; c.drawCircle(cx, cy, r * 0.86f, p)
        // L'amande : paupières (ouverture) et plissé joyeux (le bas remonte en arc).
        val demiL = r * 0.70f; val demiH = r * 0.42f * pose.ouverture.coerceAtLeast(0f)
        if (demiH > 0.5f) {
            amande.reset()
            val n = 24
            for (i in 0..n) {
                val u = -1f + 2f * i / n
                val k = (1 - u * u).coerceAtLeast(0f)
                val y = cy - demiH * k.pow(0.9f)
                if (i == 0) amande.moveTo(cx + u * demiL, y) else amande.lineTo(cx + u * demiL, y)
            }
            for (i in n downTo 0) {
                val u = -1f + 2f * i / n
                val k = (1 - u * u).coerceAtLeast(0f)
                val haut = cy - demiH * k.pow(0.9f)
                val bas = (cy + demiH * k.pow(0.9f) - pose.plisse * demiH * 1.9f * k.pow(1.15f)).coerceAtLeast(haut)
                amande.lineTo(cx + u * demiL, bas)
            }
            amande.close()
            c.save(); c.clipPath(amande)
            p.style = Paint.Style.FILL; p.color = blanc; c.drawCircle(cx, cy, r, p)
            val ix = cx + pose.gx * r * 0.25f; val iy = cy + pose.gy * r * 0.12f
            p.color = or; c.drawCircle(ix, iy, r * 0.27f, p)
            p.color = pupille; c.drawCircle(ix, iy, r * 0.27f * 0.48f, p)
            c.restore()
        }
        // Le symbole (✓, ✗, i) dans un disque de la couleur du sens.
        if (pose.symbole > 0f && genre != OeilModele.Genre.JOIE) symbole(c, cx, cy, r, teinte, pose.symbole)
    }

    private fun symbole(c: Canvas, cx: Float, cy: Float, r: Float, teinte: Int, prog: Float) {
        val q = OeilModele.lisser(prog)
        p.style = Paint.Style.FILL; p.color = alpha(teinte, 0.92f)
        val rs = r * 0.62f * min(1f, q * 1.6f)
        c.drawCircle(cx, cy, rs, p)
        // Liseré blanc : le disque se détache de l'œil rouge (l'erreur surtout).
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.05f; p.color = alpha(Color.WHITE, 0.9f * min(1f, q * 1.6f))
        c.drawCircle(cx, cy, rs, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.11f; p.strokeCap = Paint.Cap.ROUND; p.color = Color.WHITE
        val s = r * 0.5f
        when (genre) {
            OeilModele.Genre.SUCCES -> {
                val ax = cx - s * 0.55f; val ay = cy + s * 0.02f; val bx = cx - s * 0.12f; val by = cy + s * 0.42f
                val ex = cx + s * 0.62f; val ey = cy - s * 0.42f
                val a1 = min(1f, q * 2.2f); c.drawLine(ax, ay, ax + (bx - ax) * a1, ay + (by - ay) * a1, p)
                val a2 = ((q - 0.45f) / 0.55f).coerceIn(0f, 1f)
                if (a2 > 0f) c.drawLine(bx, by, bx + (ex - bx) * a2, by + (ey - by) * a2, p)
            }
            OeilModele.Genre.ERREUR -> {
                val k = s * 0.42f * min(1f, q * 1.4f)
                c.drawLine(cx - k, cy - k, cx + k, cy + k, p); c.drawLine(cx + k, cy - k, cx - k, cy + k, p)
            }
            else -> {
                val k = s * 0.5f
                p.style = Paint.Style.FILL; c.drawCircle(cx, cy - k * 0.85f, r * 0.07f, p)
                p.style = Paint.Style.STROKE; c.drawLine(cx, cy - k * 0.35f, cx, cy + k * 0.75f * min(1f, q * 1.3f), p)
            }
        }
        p.strokeCap = Paint.Cap.BUTT
    }

    private fun alpha(couleur: Int, a: Float) = (couleur and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}
