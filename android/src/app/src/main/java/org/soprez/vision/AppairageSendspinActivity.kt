// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Client Sendspin natif (lot 6) : l'appairage de cette télé avec Music Assistant, à l'écran.
 * - Music Assistant demande un PIN : il s'affiche en grand, l'opérateur le tape dans Music Assistant ;
 * - un appairage exigeant (après 10 échecs) : la télé demande d'abord l'autorisation, à la télécommande.
 * L'écran se ferme tout seul quand l'appairage finit ou s'arrête.
 */
class AppairageSendspinActivity : Activity() {
    companion object {
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_GESTE = "geste"
        @Volatile private var ouverte: AppairageSendspinActivity? = null

        fun montrerPin(ctx: Context, pin: String) = ouvrir(ctx, Intent(ctx, AppairageSendspinActivity::class.java).putExtra(EXTRA_PIN, pin))
        fun demanderGeste(ctx: Context) = ouvrir(ctx, Intent(ctx, AppairageSendspinActivity::class.java).putExtra(EXTRA_GESTE, true))
        fun fermer() { ouverte?.let { a -> a.runOnUiThread { a.finish() } } }

        private fun ouvrir(ctx: Context, i: Intent) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)) }
            catch (_: Exception) {}
        }
    }

    private lateinit var theme: Theme

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        theme = Local.themeEffectif(this, Palette.APPLI)
        ouverte = this
        construire(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); construire(i) }

    override fun onDestroy() { if (ouverte === this) ouverte = null; super.onDestroy() }

    private fun px(dp: Float) = Ui.dp(this, dp)

    private fun texte(t: String, taille: Float, couleur: Int, police: android.graphics.Typeface = Polices.texte(this)) = TextView(this).apply {
        text = t; setTextColor(couleur); typeface = police; setTextSize(TypedValue.COMPLEX_UNIT_SP, taille); gravity = Gravity.CENTER
    }

    private fun bouton(t: String, plein: Boolean, action: () -> Unit) = texte(t, 18f, if (plein) theme.fond else theme.encre, Polices.gras(this)).apply {
        minHeight = px(52f); setPadding(px(26f), px(12f), px(26f), px(12f)); isClickable = true; isFocusable = true
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = px(999f).toFloat(); if (plein) setColor(theme.or) else { setColor(0); setStroke(px(1.5f), theme.carteBord) } }
        Ui.anneauFocus(this, theme, 26f)
        setOnClickListener { action() }
    }

    private fun construire(i: Intent) {
        val pin = i.getStringExtra(EXTRA_PIN)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(px(32f), px(32f), px(32f), px(32f)); setBackgroundColor(theme.fond) }
        col.addView(texte(getString(R.string.appairage_titre), 26f, theme.encre, Polices.outfit(this)))
        // 1.89.0 (recette : « Abbrechen » coupé à 360 dp, police 130 %) : la rangée commune passe les boutons l'un sous
        // l'autre quand ils ne tiennent pas côte à côte.
        val boutons = Ui.rangeeBoutons(this).apply { gravity = Gravity.CENTER; setPadding(0, px(24f), 0, 0) }
        if (pin != null) {
            col.addView(texte(getString(R.string.appairage_pin_phrase), 18f, theme.encre2).apply { setPadding(0, px(12f), 0, px(18f)) })
            // Le PIN en grand, par groupes de 3 chiffres ; lu chiffre par chiffre par TalkBack.
            col.addView(texte(pin.chunked(3).joinToString("  "), 64f, theme.encre, Polices.gras(this)).apply {
                letterSpacing = 0.12f; contentDescription = pin.toList().joinToString(" ")
            })
            boutons.addView(bouton(getString(R.string.commun_lot2_annuler), false) { LecteurSendspin.annulerAppairage(); finish() })
        } else {
            col.addView(texte(getString(R.string.appairage_geste_phrase), 18f, theme.encre2).apply { setPadding(0, px(12f), 0, px(18f)) })
            boutons.addView(bouton(getString(R.string.appairage_autoriser), true) { LecteurSendspin.autoriserAppairage(); finish() })
            boutons.addView(bouton(getString(R.string.commun_lot2_annuler), false) { LecteurSendspin.annulerAppairage(); finish() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(16f) })
        }
        col.addView(boutons)
        setContentView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.post { boutons.getChildAt(0)?.requestFocus() }
    }
}
