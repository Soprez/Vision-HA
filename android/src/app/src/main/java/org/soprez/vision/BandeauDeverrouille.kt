// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 1.93.06 (Déverrouillage simplifié) : pendant l'accès total (déverrouillé par un responsable, ou pause par le
 * code), un bandeau discret en haut de l'écran, par-dessus toutes les applis : « Déverrouillé, reste X min » et un
 * bouton « Reverrouiller ». Posé par le service d'accessibilité (TYPE_ACCESSIBILITY_OVERLAY, sans autre permission),
 * revu toutes les [PAS_MS] ; il disparaît de lui-même à l'expiration. Il ne prend jamais le focus (la télécommande d'une
 * télé reste sur l'appli) : sur une télé, « Reverrouiller » est aussi dans les réglages de Vision.
 */
object BandeauDeverrouille {
    private const val PAS_MS = 15_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var service: java.lang.ref.WeakReference<AccessibilityService>? = null
    private var vue: LinearLayout? = null
    private var texte: TextView? = null

    /** Pur : le texte du bandeau, ou null s'il n'a pas lieu d'être. */
    fun libelle(ctx: Context, minutes: Int): String? =
        if (minutes <= 0) null else ctx.getString(R.string.bandeau_deverrouille, minutes)

    private val tic = object : Runnable {
        override fun run() {
            val s = service?.get() ?: return
            mettreAJour(s)
            main.postDelayed(this, PAS_MS)
        }
    }

    fun connecter(s: AccessibilityService) {
        service = java.lang.ref.WeakReference(s)
        main.removeCallbacks(tic); main.post(tic)
    }

    /** Tout de suite (déverrouillage appliqué, reverrouillé) : sans attendre le prochain pas. */
    fun maintenant() { main.post { service?.get()?.let { mettreAJour(it) } } }

    fun retirer(s: Context) {
        main.removeCallbacks(tic)
        vue?.let { v -> try { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {} }
        vue = null; texte = null
    }

    private fun dp(ctx: Context, v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun mettreAJour(s: AccessibilityService) {
        val t = if (Config(s).inscrit) libelle(s, Deverrouillage.minutesRestantes(s)) else null
        if (t == null) {
            vue?.let { v -> try { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {} }
            vue = null; texte = null
            return
        }
        texte?.let { it.text = t; return }
        val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ligne = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(s, 14f), dp(s, 6f), dp(s, 6f), dp(s, 6f))
            background = GradientDrawable().apply { cornerRadius = dp(s, 20f).toFloat(); setColor(0xE6201A14.toInt()) }
            contentDescription = t
        }
        val txt = TextView(s).apply { text = t; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f }
        val bouton = TextView(s).apply {
            text = s.getString(R.string.bandeau_reverrouiller); setTextColor(0xFF201A14.toInt()); textSize = 13f
            setPadding(dp(s, 12f), dp(s, 6f), dp(s, 12f), dp(s, 6f))
            background = GradientDrawable().apply { cornerRadius = dp(s, 16f).toFloat(); setColor(0xFFD9B26A.toInt()) }
            isClickable = true; minHeight = dp(s, 36f); gravity = Gravity.CENTER
            setOnClickListener {
                Deverrouillage.reverrouiller(s, "bandeau")
                Messages.info(s, "Vision", s.getString(R.string.bandeau_reverrouille))
                mettreAJour(s)
            }
        }
        ligne.addView(txt)
        ligne.addView(bouton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(s, 10f) })
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            // Banc (18/10) : en haut d'une télé, il couvrait le menu de l'accueil de Google TV ; il y passe en bas.
            val tele = ReglagesTvActivity.estTele(s)
            gravity = (if (tele) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL; y = dp(s, if (tele) 27f else 28f)
            title = "Vision : déverrouillé"
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { wm.addView(ligne, lp); vue = ligne; texte = txt } catch (_: Exception) { vue = null; texte = null }
        ligne.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}
