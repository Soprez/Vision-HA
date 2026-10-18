// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * Réveil (lot 4) : l'écran qui sonne, en plein écran par-dessus le verrouillage (téléphone, tablette, télé).
 * Le nom du réveil, l'heure en grand, « Répéter (9 min) » (tant que c'est permis) et « Arrêter ».
 * À la télécommande : le focus part sur « Répéter » (un appui distrait ne coupe pas tout) ; Retour ne ferme pas.
 * L'écran se ferme dès que la sonnerie s'arrête, d'ici ou d'ailleurs (arrêt partagé, arrêt automatique).
 */
class ReveilActivity : Activity() {
    companion object {
        fun intention(ctx: Context): Intent = Intent(ctx, ReveilActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    private val main = Handler(Looper.getMainLooper())
    private var heure: android.widget.TextView? = null
    private val horloge = object : Runnable {
        override fun run() { heure?.text = Tr.heure(System.currentTimeMillis()); main.postDelayed(this, 10_000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        if (android.os.Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        dessiner()
    }

    override fun onResume() {
        super.onResume()
        ReveilService.ecoute = { runOnUiThread { if (ReveilService.courant == null) finish() else dessiner() } }
        if (ReveilService.courant == null && !intent.getBooleanExtra("essai", false)) { finish(); return }
        main.post(horloge)
    }

    override fun onPause() {
        main.removeCallbacks(horloge)
        super.onPause()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); dessiner() }

    @Deprecated("Retour : la sonnerie continue, l'écran reste")
    override fun onBackPressed() {}

    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean =
        if (code == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(code, e)

    internal fun dessiner() {
        val s = ReveilService.courant
        val nom = s?.r?.nom?.ifEmpty { null } ?: intent.getStringExtra("nom") ?: getString(R.string.reveil_nom_defaut)
        val repMin = s?.r?.repMin ?: intent.getIntExtra("rep_min", 9)
        val peutRepeter = s?.let { ReveilsModele.peutRepeter(it.r, it.reports) } ?: intent.getBooleanExtra("repeter", true)
        val tele = ReglagesTvActivity.estTele(this)
        val marge = Ui.dp(this, if (tele) 48f else 24f)

        val fond = Ui.colonne(this, marge).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.melanger(Ui.FOND, Ui.OR, 0.18f), Ui.FOND))
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, ins ->
                v.setPadding(marge + ins.systemWindowInsetLeft, marge + ins.systemWindowInsetTop, marge + ins.systemWindowInsetRight, marge + ins.systemWindowInsetBottom)
                ins
            }
        }
        val colonne = Ui.colonne(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        // L'œil de Vision tel quel (une teinte en ferait un rond uni).
        val oeil = Ui.dp(this, if (tele) 56f else 44f)
        colonne.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_vision) }, LinearLayout.LayoutParams(oeil, oeil))
        colonne.addView(Ui.marge(this, Ui.sansCesure(Ui.texte(this, nom, if (tele) 30f else 24f, Ui.TEXTE, gras = true)).apply {
            gravity = Gravity.CENTER
        }, haut = 14f))
        heure = Ui.texte(this, Tr.heure(System.currentTimeMillis()), if (tele) 96f else 72f, Ui.OR, gras = true).apply { gravity = Gravity.CENTER }
        colonne.addView(Ui.marge(this, heure!!, haut = 6f))
        s?.takeIf { it.repli }?.let {
            colonne.addView(Ui.marge(this, Ui.texte(this, getString(R.string.reveil_repli_note), 14f, Ui.TEXTE_2).apply { gravity = Gravity.CENTER }, haut = 4f))
        }

        val boutons = LinearLayout(this).apply {
            orientation = if (tele || !Ui.etroit(this@ReveilActivity)) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val horizontal = boutons.orientation == LinearLayout.HORIZONTAL
        var premier: android.view.View? = null
        if (peutRepeter) {
            val rep = Ui.boutonSecondaire(this, getString(R.string.reveil_repeter_n, repMin)) {
                ReveilService.commande(this, ReveilService.ACTION_REPORTER, "appareil")
            }
            boutons.addView(rep, parametres(horizontal, premier = true))
            premier = rep
        }
        val stop = Ui.boutonPrimaire(this, getString(R.string.reveil_arreter)) {
            ReveilService.commande(this, ReveilService.ACTION_ARRETER, "appareil")
            if (intent.getBooleanExtra("essai", false)) finish()
        }
        boutons.addView(stop, parametres(horizontal, premier = premier == null))
        colonne.addView(Ui.marge(this, boutons, haut = 28f))
        fond.addView(colonne, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val defile = android.widget.ScrollView(this).apply { isFillViewport = true; addView(fond) }
        setContentView(defile)
        (premier ?: stop).requestFocus()
    }

    private fun parametres(horizontal: Boolean, premier: Boolean): LinearLayout.LayoutParams {
        val ecart = Ui.dp(this, 12f)
        return if (horizontal) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (!premier) leftMargin = ecart
        } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (!premier) topMargin = ecart
        }
    }
}
