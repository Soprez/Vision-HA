// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 1.91.0 (l'auteur : « avancer et reculer avec un petit bouton qu'on fait glisser dans la barre ») : la barre d'avancement
 * du titre, qui devient un curseur quand on peut sauter ([interactif]).
 *
 * - Au doigt : le curseur suit le doigt, une bulle au-dessus montre le temps visé ; le saut part au lâcher.
 * - À la télécommande : la barre prend le focus (anneau du thème) ; gauche / droite déplacent l'aperçu de 10 s, plus
 *   vite en appui long ([Saut.pas]) ; le saut part quand la touche se calme, ou tout de suite à OK.
 * - Lecteurs d'écran : un curseur (SeekBar) avec sa valeur, et « avancer / reculer » de 10 s.
 * Couleurs du thème uniquement ; la bulle se dessine au-dessus de la barre, hors de son cadre (les parents ne la rognent
 * pas : [Ui.sansCoupe]).
 */
class BarreLecture(ctx: Context, private val th: Theme, private val tele: Boolean) : View(ctx) {
    /** La part jouée (0 à 1), posée par l'écran à chaque seconde. */
    var part = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** La durée du titre (s) ; null : pas de curseur. */
    var duree: Double? = null
    var interactif = false
        set(v) { field = v; isFocusable = v; isClickable = v; if (!v) annulerApercu(); invalidate() }
    /** Le saut demandé (s), au lâcher du doigt ou quand la télécommande se calme. */
    var surSaut: (Double) -> Unit = {}
    /** Le texte lu par l'accessibilité pour une position (« 1:23 sur 3:45 »). */
    var dire: (Double, Double) -> String = { p, d -> Party.temps(p) + " / " + Party.temps(d) }

    /** La position visée pendant un glisser ou des appuis (s) ; null hors réglage. */
    var apercu: Double? = null
        private set

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Polices.demiGras(ctx)
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, if (tele) 15f else 13f, ctx.resources.displayMetrics)
    }
    private val rect = RectF()
    private var glisse = false
    private var repetitions = 0
    private val partir = Runnable { valider() }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val epaisseur get() = dp(if (tele) 5f else 4f)
    private val rayonRepos get() = dp(if (tele) 7f else 7f)
    private val rayonActif get() = dp(if (tele) 10f else 10f)
    /**
     * La piste, la part jouée et le curseur partagent la même course (Saut.xDePart), retenue à cette distance des bords
     * pour que le curseur n'en sorte jamais, anneau de focus compris : la fin de la piste est la fin du titre.
     */
    internal val retenue get() = rayonActif + dp(if (tele) 6.5f else 1f)

    init {
        contentDescription = context.getString(R.string.lecture_position)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnFocusChangeListener { _, a -> if (!a) valider(); if (a) Ui.sansCoupe(this); invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val x0 = Saut.xDePart(0f, w, retenue); val x1 = Saut.xDePart(1f, w, retenue)
        val cy = h / 2f; val e = epaisseur; val r = e / 2f
        val d = duree
        val visee = apercu
        val q = if (visee != null && d != null && d > 0) (visee / d).toFloat().coerceIn(0f, 1f) else part
        p.color = Palette.melanger(th.carte, th.encre, 0.18f)
        rect.set(x0, cy - e / 2f, x1, cy + e / 2f); c.drawRoundRect(rect, r, r, p)
        p.color = th.or
        rect.set(x0, cy - e / 2f, x0 + (x1 - x0) * q, cy + e / 2f); c.drawRoundRect(rect, r, r, p)
        if (!interactif) return
        val x = Saut.xDePart(q, w, retenue)
        val actif = glisse || visee != null || isFocused
        val rc = if (actif) rayonActif else rayonRepos
        if (isFocused) {
            // L'anneau de focus commun (encre puis fond), autour du curseur.
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(3f); p.color = th.encre or 0xFF000000.toInt(); c.drawCircle(x, cy, rc + dp(4.5f), p)
            p.strokeWidth = dp(2f); p.color = th.fond or 0xFF000000.toInt(); c.drawCircle(x, cy, rc + dp(2f), p)
            p.style = Paint.Style.FILL
        }
        p.color = th.or; c.drawCircle(x, cy, rc, p)
        p.color = th.fond; c.drawCircle(x, cy, rc * 0.38f, p)
        if (visee != null) bulle(c, x, cy - rc - dp(8f), Party.temps(visee))
    }

    /** La bulle du temps visé, posée au-dessus du curseur et gardée dans la largeur de la barre. */
    private fun bulle(c: Canvas, x: Float, bas: Float, t: String) {
        val fm = pTexte.fontMetrics
        val hTexte = fm.descent - fm.ascent
        val l = pTexte.measureText(t) + dp(16f)
        val hb = hTexte + dp(8f)
        val cx = x.coerceIn(l / 2f, width - l / 2f)
        rect.set(cx - l / 2f, bas - hb, cx + l / 2f, bas)
        p.color = th.encre or 0xFF000000.toInt(); c.drawRoundRect(rect, hb / 2f, hb / 2f, p)
        pTexte.color = th.fond or 0xFF000000.toInt()
        c.drawText(t, cx, rect.centerY() - (fm.ascent + fm.descent) / 2f, pTexte)
    }

    // ------------------------------------------------------------------ doigt

    private fun sousLeDoigt(x: Float): Double? {
        val d = duree ?: return null
        return Saut.depuisPart(Saut.partDeX(x, width.toFloat(), retenue), d)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!interactif || duree == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                glisse = true; Ui.sansCoupe(this)
                apercu = sousLeDoigt(ev.x); invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (glisse) { apercu = sousLeDoigt(ev.x); invalidate() }
            MotionEvent.ACTION_UP -> if (glisse) {
                glisse = false
                apercu = sousLeDoigt(ev.x)
                valider()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { glisse = false; annulerApercu() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // ------------------------------------------------------------------ télécommande

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val d = duree
        if (interactif && d != null) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val sens = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                repetitions = if (event.repeatCount == 0) 0 else repetitions + 1
                val depuis = apercu ?: (part * d)
                apercu = Saut.borner(depuis + sens * Saut.pas(repetitions), d)
                removeCallbacks(partir); postDelayed(partir, Saut.CALME_MS)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (apercu != null) { valider(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (interactif && apercu != null && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            removeCallbacks(partir); postDelayed(partir, Saut.CALME_MS)
            return true
        }
        if (interactif && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun valider() {
        removeCallbacks(partir)
        val v = apercu ?: return
        val d = duree
        if (d != null) part = (v / d).toFloat()
        apercu = null; repetitions = 0
        invalidate()
        surSaut(v)
    }

    private fun annulerApercu() { removeCallbacks(partir); apercu = null; repetitions = 0; invalidate() }

    override fun onDetachedFromWindow() { removeCallbacks(partir); super.onDetachedFromWindow() }

    // ------------------------------------------------------------------ accessibilité

    override fun getAccessibilityClassName(): CharSequence = if (interactif) "android.widget.SeekBar" else "android.widget.ProgressBar"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val d = duree ?: return
        val pos = (apercu ?: (part * d))
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, d.toFloat(), pos.toFloat())
        if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = dire(pos, d)
        if (interactif) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val d = duree
        if (interactif && d != null && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            val sens = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1
            apercu = Saut.borner(part * d + sens * Saut.PAS_S, d)
            valider()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
