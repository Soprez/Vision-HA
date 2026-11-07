// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 1.94 (retour de l'auteur : « deux sélecteurs façon années 2010, gros boutons gris dégradés ») : le sélecteur de nombre de
 * Vision, qui remplace le NumberPicker d'Android partout : un bouton −, la valeur, un bouton +. Aux couleurs du thème
 * (même fond, même liseré, même anneau de focus que les autres boutons), deux cibles de 48 dp, appui long qui répète,
 * flèches haut / bas de la télécommande sur la valeur. [value] : la valeur choisie.
 *
 * [pas] : l'écart d'un appui ; [cycle] : au bout, on repart de l'autre côté (heures : 23 → 0) au lieu de s'arrêter.
 */
class SelecteurPas(
    ctx: Context,
    val min: Int,
    val max: Int,
    valeur: Int,
    val pas: Int = 1,
    private val cycle: Boolean = false,
    private val format: (Int) -> String = { it.toString() },
    private val th: Theme = Ui.themeActif(ctx),
    private val change: (Int) -> Unit = {},
) : LinearLayout(ctx) {

    companion object {
        /** Le délai avant la répétition, puis entre deux répétitions (ms). */
        const val ATTENTE_MS = 450L
        const val REPETE_MS = 90L

        /** La valeur après un appui ([sens] : +1 ou −1). Au bord : on s'arrête, ou, avec [cycle], on passe de l'autre côté. Pur. */
        fun suivant(v: Int, min: Int, max: Int, pas: Int, cycle: Boolean, sens: Int): Int {
            val n = v + sens * pas
            if (!cycle) return n.coerceIn(min, max)
            val taille = max - min + 1
            return ((n - min) % taille + taille) % taille + min
        }
    }

    var value: Int = valeur.coerceIn(min, max)
        set(v) { field = v.coerceIn(min, max); lire.text = format(field); lire.contentDescription = lire.text }

    private val main = Handler(Looper.getMainLooper())
    private val lire: TextView = Ui.texte(ctx, "", 20f, th.encre, gras = true).apply {
        gravity = Gravity.CENTER; minWidth = Ui.dp(ctx, 88f)
        isFocusable = true
        Ui.anneauFocus(this, th, 12f, agrandir = false)
        // Télécommande : haut / bas sur la valeur, comme la molette qu'elle remplace.
        setOnKeyListener { _, code, ev ->
            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { bouger(+1); true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { bouger(-1); true }
                else -> false
            }
        }
    }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(rond("−", R.string.temps_moins, -1))  // i18n-exception : pictogramme
        addView(lire)
        addView(rond("+", R.string.temps_plus, +1))
        value = value
    }

    private fun bouger(sens: Int) {
        val n = suivant(value, min, max, pas, cycle, sens)
        if (n == value) return
        value = n; change(n)
    }

    /** Un bouton rond du thème (fond de carte haute, liseré, anneau de focus), qui répète tant qu'on le tient. */
    private fun rond(signe: String, description: Int, sens: Int): View {
        val ctx = context
        val d = Ui.dp(ctx, 48f)
        val haute = Palette.melanger(th.carte, th.encre, 0.06f)
        val repete = object : Runnable { override fun run() { bouger(sens); main.postDelayed(this, REPETE_MS) } }
        return TextView(ctx).apply {
            text = signe; gravity = Gravity.CENTER; textSize = 22f; setTextColor(th.encre); typeface = Polices.gras(ctx)
            contentDescription = ctx.getString(description)
            layoutParams = LayoutParams(d, d)
            background = Ui.fondInteractif(ctx, haute, Palette.melanger(haute, th.encre, 0.1f), d / 2f, th.encre, Palette.melanger(haute, th.encre, 0.28f))
            isFocusable = true; isClickable = true
            Ui.anneauFocus(this, th, 24f, agrandir = false)
            // Un appui bref = un pas (clic, aussi pour OK de la télécommande) ; un appui long répète sans clic de plus.
            var repetait = false
            setOnClickListener { if (!repetait) bouger(sens); repetait = false }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { repetait = false; main.postDelayed({ repetait = true; repete.run() }, ATTENTE_MS) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { main.removeCallbacksAndMessages(null) }
                }
                false
            }
        }
    }

    override fun onDetachedFromWindow() { main.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
}
