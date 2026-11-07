// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * La pastille discrète de « Signaler un problème » : « ● Enregistrement · Terminer », posée par-dessus les autres
 * applis (même permission que [Bulle]). Téléphone : toucher = Terminer, appui long = Capture maintenant.
 * Télé : appui long sur OK (capté par VisionAccessibility, seulement pendant l'enregistrement) = Terminer.
 * Rien ici ne s'affiche hors d'un enregistrement.
 */
object PastilleSignalement {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var vue: View? = null
    private var appCtx: Context? = null
    private val verifier = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            if (Signalement.expire()) { appui(ctx); return }
            if (vue != null) main.postDelayed(this, 5_000)
        }
    }

    /** Vrai si la pastille peut être posée (permission « par-dessus les autres applis »). */
    fun possible(ctx: Context): Boolean = Bulle.permise(ctx)

    fun poser(ctx: Context, tele: Boolean) {
        main.post {
            retirer()
            val app = ctx.applicationContext
            appCtx = app
            if (!possible(app)) return@post
            val theme = Ui.themeActif(app)
            val t = TextView(app).apply {
                text = app.getString(if (tele) R.string.signaler_pastille_tele else R.string.signaler_pastille)
                textSize = if (tele) 14f else 12f
                setTextColor(theme.encre)
                background = Ui.fond(theme.carte, 18f, bord = 0)
                val h = Ui.dp(app, 12f); val v = Ui.dp(app, 6f)
                setPadding(h, v, h, v)
                alpha = 0.4f
                if (!tele) {
                    setOnClickListener { appui(app) }
                    setOnLongClickListener { capturer(app); true }
                }
            }
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            // Sur la télé la pastille ne prend ni le focus ni les touches (la télécommande reste à l'appli en cours).
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (tele) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0), PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.x = Ui.dp(app, if (tele) 40f else 16f); lp.y = Ui.dp(app, if (tele) 40f else 96f)
            try { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(t, lp); vue = t } catch (_: Exception) { vue = null }
            main.removeCallbacks(verifier); main.postDelayed(verifier, 5_000)
        }
    }

    fun retirer() {
        main.removeCallbacks(verifier)
        val v = vue ?: return
        vue = null
        try { (appCtx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(v) } catch (_: Exception) {}
    }

    /** Termine l'enregistrement et ouvre l'aperçu. */
    fun appui(ctx: Context) {
        if (!Signalement.enCours) return
        SignalerActivity.terminer(ctx)
    }

    private fun capturer(ctx: Context) {
        val ok = CaptureEcran.capturer(ctx)
        Ui.toast(ctx, ctx.getString(if (ok) R.string.signaler_capture_prise else R.string.signaler_capture_impossible), court = true)
    }

    /** Les touches de la télécommande qui comptent pendant un enregistrement : OK (centre ou entrée) et Menu. */
    fun touchesTele(code: Int): Boolean = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
        code == android.view.KeyEvent.KEYCODE_MENU

    /** Appui long sur OK de la télécommande : Terminer (appelé par VisionAccessibility, jamais hors enregistrement). */
    fun toucheOkLongue(ctx: Context) = appui(ctx)

    /** Appui long sur Menu : capture maintenant. */
    fun toucheMenuLongue(ctx: Context) { if (Signalement.enCours) capturer(ctx) }
}
