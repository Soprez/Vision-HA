// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 1.93.05 (l'auteur, retour 38, TV du salon) : dans le lecteur, la jauge de volume de la TCL ne venait plus. La touche reste
 * au SYSTÈME (c'est lui qui règle le son : Vision ne la prend jamais) et Vision montre en plus sa propre jauge, à droite,
 * dans la zone sûre, aux couleurs du thème, avec le pourcentage — le son voulu se voit quoi qu'affiche la télé.
 */
class JaugeVolume(private val activite: Activity, private val theme: Theme) {
    private val main = Handler(Looper.getMainLooper())
    private var vue: Barre? = null
    // 1.93.05 (l'auteur, règles d'Emil Kowalski) : la jauge apparaît en fondu (et de 0,95 à 1, sauf animations réduites) en
    // 180 ms, et s'efface plus vite (120 ms), toujours en « ease-out » ; jamais d'apparition ni de disparition sèche.
    private val courbe = android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val cacher = Runnable {
        vue?.animate()?.alpha(0f)?.setDuration(120)?.setInterpolator(courbe)?.withEndAction { vue?.visibility = View.GONE }?.start()
    }

    /** À appeler pour chaque touche reçue par l'écran ; ne la consomme JAMAIS (rend toujours faux). */
    fun touche(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode in TOUCHES) main.postDelayed({ montrer() }, 120)
        return false
    }

    /** Le volume musique du moment (0–100), ou null si le système ne le dit pas. */
    private fun pourcent(): Int? = try {
        val am = activite.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (am.isStreamMute(AudioManager.STREAM_MUSIC)) 0 else if (max > 0) pourcentDe(am.getStreamVolume(AudioManager.STREAM_MUSIC), max) else null
    } catch (_: Exception) { null }

    fun montrer() {
        if (activite.isFinishing) return
        val p = pourcent() ?: return
        val v = vue ?: Barre(activite, theme).also { b ->
            val d = activite.window.decorView as? ViewGroup ?: return
            val dp = activite.resources.displayMetrics.density
            d.addView(b, FrameLayout.LayoutParams((56 * dp).toInt(), (260 * dp).toInt(), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = ZoneSure.x(activite, 0.05f)
            })
            b.tag = "jauge_volume"
            vue = b
        }
        v.valeur = p; v.contentDescription = activite.getString(R.string.jauge_volume, p)
        if (v.visibility != View.VISIBLE || v.alpha < 1f) {
            val bouger = Anim.actif(activite)
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.scaleX = if (bouger) 0.95f else 1f; v.scaleY = v.scaleX }
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(courbe).start()
        }
        v.bringToFront(); v.invalidate()
        main.removeCallbacks(cacher); main.postDelayed(cacher, 2_000)
    }

    /** La barre verticale et son pourcentage. */
    private class Barre(ctx: Context, private val th: Theme) : View(ctx) {
        var valeur = 0
        private val dp = ctx.resources.displayMetrics.density
        private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val texte = 36 * dp
            // Fond de carte, bord du thème.
            pinceau.style = Paint.Style.FILL; pinceau.color = th.carte
            r.set(0f, 0f, w, h); c.drawRoundRect(r, 14 * dp, 14 * dp, pinceau)
            pinceau.style = Paint.Style.STROKE; pinceau.strokeWidth = 1.5f * dp; pinceau.color = th.carteBord
            c.drawRoundRect(r, 14 * dp, 14 * dp, pinceau)
            // La piste, puis le niveau.
            val l = 10 * dp; val x0 = w / 2 - l / 2; val haut = 14 * dp; val bas = h - texte - 6 * dp
            pinceau.style = Paint.Style.FILL; pinceau.color = th.carteBord
            r.set(x0, haut, x0 + l, bas); c.drawRoundRect(r, l / 2, l / 2, pinceau)
            pinceau.color = th.or
            r.set(x0, bas - (bas - haut) * valeur / 100f, x0 + l, bas); c.drawRoundRect(r, l / 2, l / 2, pinceau)
            pinceau.color = th.encre; pinceau.textAlign = Paint.Align.CENTER; pinceau.textSize = 15 * dp; pinceau.isFakeBoldText = true
            c.drawText("$valeur %", w / 2, h - texte / 2 + 5 * dp, pinceau)
        }
    }

    companion object {
        private val TOUCHES = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

        /** Le pourcentage d'un volume [v] sur [max], arrondi. Pur. */
        fun pourcentDe(v: Int, max: Int): Int = Math.round(100f * v.coerceIn(0, max) / max)
    }
}
