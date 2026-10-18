// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.View

/**
 * 17-10 (l'auteur, point 12) : Réglages › Affichage › « Ajuster l'écran », comme sur les consoles.
 *
 * Un cadre doré et quatre flèches, posés à la zone sûre du moment. On les aligne sur les bords VISIBLES de la télé :
 * ◀ ▶ règlent les côtés, ▲ ▼ le haut et le bas (▶ et ▲ agrandissent le cadre, ◀ et ▼ le resserrent), 1 % par appui.
 * OK enregistre (ZoneSure, propre à cet appareil) ; Retour annule ; appui long sur OK : revenir à 5 %.
 * Toute l'interface de la télé suit ensuite : accueil, veille, lecteur, menus et réglages.
 */
class AjusterEcranActivity : Activity() {
    companion object {
        fun ouvrir(ctx: Context) = ctx.startActivity(Intent(ctx, AjusterEcranActivity::class.java))

        /** Logique pure : la touche → le nouveau réglage (x, y), ou null si la touche ne règle rien. */
        fun appliquer(touche: Int, x: Int, y: Int): Pair<Int, Int>? = when (touche) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> ZoneSure.borner(x - 1) to y
            KeyEvent.KEYCODE_DPAD_LEFT -> ZoneSure.borner(x + 1) to y
            KeyEvent.KEYCODE_DPAD_UP -> x to ZoneSure.borner(y - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> x to ZoneSure.borner(y + 1)
            else -> null
        }
    }

    // Pas « x » ni « y » : dans la vue intérieure Cadre, ces noms désignent la position de la vue (View.getX / getY).
    private var zoneX = ZoneSure.DEFAUT
    private var zoneY = ZoneSure.DEFAUT
    private lateinit var vue: Cadre

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        zoneX = ZoneSure.pourcentX(this); zoneY = ZoneSure.pourcentY(this)
        vue = Cadre(this).apply { isFocusable = true; isFocusableInTouchMode = true; contentDescription = getString(R.string.zonesure_titre) }
        setContentView(vue)
        vue.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        appliquer(keyCode, zoneX, zoneY)?.let { (nx, ny) -> zoneX = nx; zoneY = ny; vue.invalidate(); return true }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { event.startTracking(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            zoneX = ZoneSure.DEFAUT; zoneY = ZoneSure.DEFAUT; vue.invalidate(); return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !event.isCanceled && event.isTracking) {
            if (event.eventTime - event.downTime < android.view.ViewConfiguration.getLongPressTimeout()) {
                ZoneSure.poser(this, zoneX, zoneY)
                Ui.toast(this, getString(R.string.zonesure_enregistre, zoneX, zoneY))
                finish()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Le cadre, les flèches aux quatre bords de la zone sûre, l'aide et les valeurs au centre. */
    private inner class Cadre(ctx: Context) : View(ctx) {
        private val th = Local.themeEffectif(ctx, Palette.LANCEUR)
        private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = th.or }
        private val plein = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = th.or }
        private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.encre; textAlign = Paint.Align.CENTER; typeface = Polices.moyen(ctx) }
        private val texte2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.encre2; textAlign = Paint.Align.CENTER; typeface = Polices.texte(ctx) }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawColor(th.fond)
            val gx = w * zoneX / 100f; val gy = h * zoneY / 100f
            val r = RectF(gx, gy, w - gx, h - gy)
            trait.strokeWidth = h * 0.006f
            c.drawRect(r, trait)
            // Les flèches : pointe sur le bord du cadre, vers l'extérieur.
            val f = h * 0.045f
            fun fleche(px: Float, py: Float, dx: Float, dy: Float) {
                val p = Path()
                p.moveTo(px, py)
                p.lineTo(px - dx * f - dy * f * 0.7f, py - dy * f - dx * f * 0.7f)
                p.lineTo(px - dx * f + dy * f * 0.7f, py - dy * f + dx * f * 0.7f)
                p.close(); c.drawPath(p, plein)
            }
            fleche(r.left, h / 2, -1f, 0f); fleche(r.right, h / 2, 1f, 0f)
            fleche(w / 2, r.top, 0f, -1f); fleche(w / 2, r.bottom, 0f, 1f)
            texte.textSize = h * 0.05f
            c.drawText(context.getString(R.string.zonesure_titre), w / 2, h * 0.36f, texte)
            texte2.textSize = h * 0.032f
            c.drawText(context.getString(R.string.zonesure_aide_1), w / 2, h * 0.45f, texte2)
            c.drawText(context.getString(R.string.zonesure_aide_2), w / 2, h * 0.51f, texte2)
            texte.textSize = h * 0.04f
            c.drawText(context.getString(R.string.zonesure_valeurs, zoneX, zoneY), w / 2, h * 0.62f, texte)
            texte2.textSize = h * 0.028f
            c.drawText(context.getString(R.string.zonesure_touches), w / 2, h * 0.70f, texte2)
        }
    }
}
