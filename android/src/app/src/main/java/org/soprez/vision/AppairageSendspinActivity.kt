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
 * 1.90.2 (exigence ferme de l'auteur : « jamais le code à l'écran ») : cet écran ne s'ouvre QUE pour l'appairage à la main,
 * demandé dans Réglages › Décideurs 🔒 ([LecteurSendspin.appairerALaMain]) ; l'appairage automatique est muet.
 * - d'abord « Connexion à la musique… » : on lance l'appairage dans Music Assistant ;
 * - Music Assistant demande un PIN : il s'affiche en grand, l'opérateur le tape dans Music Assistant ;
 * - un appairage exigeant (après 10 échecs) : la télé demande d'abord l'autorisation, à la télécommande ;
 * - fini : « appairée », puis l'écran se ferme ; abandon : un message d'erreur, sans code.
 */
class AppairageSendspinActivity : Activity() {
    companion object {
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_GESTE = "geste"
        private const val EXTRA_ISSUE = "issue"
        @Volatile private var ouverte: AppairageSendspinActivity? = null
        /** Pour les essais : le nombre d'ouvertures de cet écran depuis le démarrage. */
        @Volatile var ouvertures = 0; private set

        fun attendre(ctx: Context) = ouvrir(ctx, Intent(ctx, AppairageSendspinActivity::class.java))
        fun montrerPin(ctx: Context, pin: String) = ouvrir(ctx, Intent(ctx, AppairageSendspinActivity::class.java).putExtra(EXTRA_PIN, pin))
        fun demanderGeste(ctx: Context) = ouvrir(ctx, Intent(ctx, AppairageSendspinActivity::class.java).putExtra(EXTRA_GESTE, true))
        /** Fini : « appairée », puis l'écran se ferme. */
        fun reussi() { ouverte?.let { a -> a.runOnUiThread { a.construire(Intent().putExtra(EXTRA_ISSUE, "ok")); a.fenetre.postDelayed({ a.finish() }, 2_500) } } }
        /** Abandon : un message d'erreur, sans code. */
        fun echec() { ouverte?.let { a -> a.runOnUiThread { a.construire(Intent().putExtra(EXTRA_ISSUE, "echec")) } } }
        fun fermer() { ouverte?.let { a -> a.runOnUiThread { a.finish() } } }

        private fun ouvrir(ctx: Context, i: Intent) {
            // Jamais hors de l'appairage à la main (garde-fou : l'appairage automatique n'ouvre rien).
            if (!LecteurSendspin.manuel()) return
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)) }
            catch (_: Exception) {}
        }
    }

    private lateinit var theme: Theme

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        theme = Local.themeEffectif(this, Palette.APPLI)
        if (!LecteurSendspin.manuel()) { finish(); return }
        ouverte = this; ouvertures++
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

    private lateinit var fenetre: View

    private fun construire(i: Intent) {
        val pin = i.getStringExtra(EXTRA_PIN)
        val issue = i.getStringExtra(EXTRA_ISSUE)
        val geste = i.getBooleanExtra(EXTRA_GESTE, false)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(px(32f), px(32f), px(32f), px(32f)); setBackgroundColor(theme.fond) }
        col.addView(texte(getString(R.string.appairage_titre), 26f, theme.encre, Polices.outfit(this)))
        // 1.89.0 (recette : « Abbrechen » coupé à 360 dp, police 130 %) : la rangée commune passe les boutons l'un sous
        // l'autre quand ils ne tiennent pas côte à côte.
        val boutons = Ui.rangeeBoutons(this).apply { gravity = Gravity.CENTER; setPadding(0, px(24f), 0, 0) }
        if (issue != null) {
            col.addView(texte(getString(if (issue == "ok") R.string.appairage_reussi else R.string.appairage_echec), 18f, theme.encre2).apply { setPadding(0, px(12f), 0, px(18f)) })
            boutons.addView(bouton(getString(R.string.appairage_fermer), issue == "ok") { finish() })
        } else if (pin != null) {
            col.addView(texte(getString(R.string.appairage_pin_phrase), 18f, theme.encre2).apply { setPadding(0, px(12f), 0, px(18f)) })
            // Le PIN en grand, par groupes de 3 chiffres ; lu chiffre par chiffre par TalkBack.
            col.addView(texte(pin.chunked(3).joinToString("  "), 64f, theme.encre, Polices.gras(this)).apply {
                letterSpacing = 0.12f; contentDescription = pin.toList().joinToString(" ")
            })
            boutons.addView(bouton(getString(R.string.commun_lot2_annuler), false) { LecteurSendspin.annulerAppairage(); finish() })
        } else if (geste) {
            col.addView(texte(getString(R.string.appairage_geste_phrase), 18f, theme.encre2).apply { setPadding(0, px(12f), 0, px(18f)) })
            boutons.addView(bouton(getString(R.string.appairage_autoriser), true) { LecteurSendspin.autoriserAppairage(); finish() })
            boutons.addView(bouton(getString(R.string.commun_lot2_annuler), false) { LecteurSendspin.annulerAppairage(); finish() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(16f) })
        } else {
            // En attendant le code : un état neutre, sans code.
            col.addView(texte(getString(R.string.appairage_connexion), 22f, theme.encre, Polices.moyen(this)).apply { setPadding(0, px(12f), 0, px(8f)) })
            col.addView(texte(getString(R.string.appairage_manuel_phrase, LecteurMusique.nom(this)), 18f, theme.encre2).apply { setPadding(0, 0, 0, px(18f)) })
            boutons.addView(bouton(getString(R.string.commun_lot2_annuler), false) { LecteurSendspin.annulerAppairage(); finish() })
        }
        col.addView(boutons)
        fenetre = col
        setContentView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.post { boutons.getChildAt(0)?.requestFocus() }
    }
}
