// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * À la sortie de l'écran de veille, quand une appli jouait en fond (Spotify,
 * YouTube Music…) : on y retourne, ou on va à l'accueil ? Petite carte au
 * milieu de l'écran, « Reprendre » sélectionné d'office ; sans réponse en
 * dix secondes, on reprend l'appli.
 */
class RetourActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val fin = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("pkg").orEmpty()
        val nom = if (pkg.isEmpty()) "" else Accueil.etiquette(this, pkg)
        val theme = Local.themeEffectif(this, Palette.APPLI)
        val dp = { v: Float -> Ui.dp(this, v) }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x66000000))
        val racine = FrameLayout(this)
        val carte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(26f).toFloat(); setColor(theme.carte); setStroke(dp(1f), theme.carteBord) }
            setPadding(dp(28f), dp(24f), dp(28f), dp(24f)); clipChildren = false; clipToPadding = false
        }
        carte.addView(Ui.sansCesure(TextView(this)).apply { text = "VISION"; /* i18n-exception : nom propre en capitales */ textSize = 12f; setTextColor(theme.or); typeface = Polices.gras(this@RetourActivity); letterSpacing = 0.28f })
        carte.addView(Ui.sansCesure(TextView(this)).apply { text = if (nom.isEmpty()) getString(R.string.retour_jouait_appli) else getString(R.string.retour_jouait, nom); textSize = 20f; setTextColor(theme.encre); typeface = Polices.demiGras(this@RetourActivity); setPadding(0, dp(8f), 0, dp(18f)) })
        val rang = Ui.rangeeBoutons(this).apply { clipChildren = false; clipToPadding = false }
        fun bouton(t: String, plein: Boolean, action: () -> Unit): TextView = Ui.sansCesure(TextView(this)).apply {
            text = t; textSize = 16f; typeface = Polices.gras(this@RetourActivity); gravity = Gravity.CENTER
            setTextColor(if (plein) theme.fond else theme.encre)
            setPadding(dp(22f), dp(12f), dp(22f), dp(12f)); isFocusable = true; isClickable = true
            val normal = GradientDrawable().apply { cornerRadius = dp(999f).toFloat(); setColor(if (plein) theme.or else Palette.melanger(theme.carte, theme.encre, 0.08f)) }
            background = normal
            setOnFocusChangeListener { v, a -> v.background = GradientDrawable().apply { cornerRadius = dp(999f).toFloat(); setColor(if (plein) theme.or else Palette.melanger(theme.carte, theme.encre, 0.08f)); if (a) setStroke(dp(1.5f), if (plein) theme.encre else theme.or) }; main.removeCallbacks(fin); main.postDelayed(fin, 10_000) }
            setOnClickListener { action() }
        }
        val reprendre = bouton(if (nom.isEmpty()) getString(R.string.retour_reprendre_appli) else getString(R.string.retour_reprendre, nom), true) { finish() }
        rang.addView(reprendre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12f) })
        rang.addView(bouton(getString(R.string.retour_accueil_vision), false) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        })
        carte.addView(rang)
        racine.addView(carte, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(racine)
        reprendre.post { reprendre.requestFocus() }
        main.postDelayed(fin, 10_000)
    }

    override fun onDestroy() { main.removeCallbacks(fin); super.onDestroy() }

    companion object {
        fun proposer(ctx: android.content.Context, pkg: String) {
            try { ctx.startActivity(Intent(ctx, RetourActivity::class.java).putExtra("pkg", pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) } catch (_: Exception) {}
        }
    }
}
