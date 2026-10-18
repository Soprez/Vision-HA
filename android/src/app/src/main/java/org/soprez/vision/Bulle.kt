// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * La bulle Vision : une petite icône posée par-dessus les autres applis
 * quand de la musique joue (Spotify, YouTube Music…), pour ramener l'écran
 * de veille devant sans couper le son. Au doigt : toucher ouvre l'écran de
 * veille, un appui long range la bulle (elle se réactive dans les
 * réglages). À la télécommande, la bulle rappelle le geste : deux appuis
 * sur Accueil.
 *
 * Option de l'appareil (« bulle »), permission « par-dessus les autres
 * applis » requise. Tenue en vie par l'agent, qui tourne toujours.
 */
object Bulle {
    private const val PREFS = "local"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var vue: View? = null
    private var lance = false
    private var appCtx: Context? = null

    /**
     * 1.85.1 (bug télé d'essai) : sur la télé, l'astuce « Accueil ×2 » restait par-dessus le programme tant qu'il y
     * avait du son. Elle se montre maintenant [DUREE_TELE_MS] puis disparaît seule, et ne revient que si la
     * situation change (autre appli au premier plan, ou le son s'est arrêté puis a repris). Le minuteur vit ici,
     * dans l'objet de l'appli : ni l'accueil recréé, ni l'accueil passé en arrière-plan ne peuvent l'annuler.
     */
    const val DUREE_TELE_MS = 8000L
    /** L'appli au premier plan pour laquelle l'astuce a déjà été montrée (vide : à montrer à la prochaine occasion). */
    private var dejaMontreePour: String? = null
    private val masquer = Runnable { retirer() }

    /**
     * Veille-lot (l'auteur : « la petite info doit disparaître seule, comme avant ») : depuis que Vision joue elle-même la
     * musique de la télé (1.86.11), le son « s'arrête » un instant entre deux titres et l'écran de Vision passe parfois
     * devant (Musique, mini-lecteur). Chaque blanc effaçait la mémoire : l'astuce revenait à chaque titre et semblait
     * ne plus jamais partir. Une absence plus courte que [OUBLI_MS] ne compte plus comme un changement.
     */
    const val OUBLI_MS = 45_000L
    /** Depuis quand l'astuce n'a plus lieu d'être (0 : elle l'a). */
    private var sansObjetDepuis = 0L

    /**
     * La décision du tour, sans Android : montrer, laisser, retirer (et oublier), ou retirer en gardant la mémoire.
     * [absenteMs] : depuis combien de temps l'astuce n'a plus lieu d'être (0 au premier tour sans objet).
     */
    internal fun decision(voulue: Boolean, tele: Boolean, paquet: String, dejaPour: String?, absenteMs: Long = Long.MAX_VALUE): String = when {
        !voulue -> if (tele && dejaPour != null && absenteMs < OUBLI_MS) "retirer_garder" else "retirer"
        !tele -> "montrer"
        dejaPour == paquet -> "laisser"
        else -> "montrer_un_temps"
    }

    /** Pour les essais : la bulle est-elle à l'écran ? */
    fun visible(): Boolean = vue != null

    fun activee(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("bulle", true)
    fun poser(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("bulle", on).apply(); if (!on) retirer() }
    fun permise(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** Démarre la surveillance (idempotent). */
    fun verifier(ctx: Context) {
        appCtx = ctx.applicationContext
        if (lance) return
        lance = true
        main.post(tour)
    }

    private val tour = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val nous = Usage.dernierPaquet == ctx.packageName || Usage.dernierPaquet.isEmpty()
                val musique = am.isMusicActive && !Musique.nous
                val voulue = activee(ctx) && Modules.actif(ctx, Modules.VEILLE) && permise(ctx) && musique && !nous && !Veille.ouverte
                val maintenant = android.os.SystemClock.uptimeMillis()
                if (voulue) sansObjetDepuis = 0L else if (sansObjetDepuis == 0L) sansObjetDepuis = maintenant
                when (decision(voulue, ReglagesTvActivity.estTele(ctx), Usage.dernierPaquet, dejaMontreePour, if (voulue) 0L else maintenant - sansObjetDepuis)) {
                    "retirer" -> { retirer(); dejaMontreePour = null }
                    "retirer_garder" -> retirer()
                    "montrer" -> montrer(ctx)
                    "montrer_un_temps" -> montrerUnTemps(ctx, Usage.dernierPaquet)
                }
            } catch (_: Exception) {}
            main.postDelayed(this, 4000)
        }
    }

    /** Sur la télé : quelques secondes, puis elle s'efface d'elle-même. */
    fun montrerUnTemps(ctx: Context, pour: String) {
        appCtx = ctx.applicationContext
        dejaMontreePour = pour
        retirer()
        montrer(ctx)
        main.removeCallbacks(masquer)
        main.postDelayed(masquer, DUREE_TELE_MS)
    }

    private fun montrer(ctx: Context) {
        if (vue != null) return
        val tele = ReglagesTvActivity.estTele(ctx)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = { v: Float -> Ui.dp(ctx, v) }
        val theme = Local.themeEffectif(ctx, Palette.LANCEUR)
        val boite = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(999f).toFloat(); setColor(theme.carte); setStroke(dp(1f), theme.carteBord) }
            setPadding(dp(6f), dp(6f), dp(if (tele) 14f else 6f), dp(6f))
            alpha = ALPHA
        }
        boite.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_vision); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(if (tele) 34f else 40f), dp(if (tele) 34f else 40f)))
        if (tele) boite.addView(Ui.sansCesure(TextView(ctx)).apply { text = ctx.getString(R.string.bulle_astuce_tele); textSize = 12f; setTextColor(theme.encre2); typeface = Polices.moyen(ctx); setPadding(dp(8f), 0, 0, 0) })
        boite.setOnClickListener { Veille.ouvrir(ctx, demande = true) }
        boite.setOnLongClickListener { poser(ctx, false); Ui.toast(ctx, ctx.getString(R.string.bulle_rangee)); true }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.x = dp(if (tele) 40f else 16f); lp.y = dp(if (tele) 40f else 96f)
        // Lot Accueil (point 10) : entrée douce sur place (fondu + agrandissement, rien qui déborde de la petite
        // fenêtre), posée invisible avant d'être attachée ; pas d'animation de fenêtre du système par-dessus.
        lp.windowAnimations = 0
        Anim.preparer(boite, Anim.Sens.SUR_PLACE, ALPHA)
        try { wm.addView(boite, lp); vue = boite; Anim.entrer(boite, Anim.Sens.SUR_PLACE, ALPHA) } catch (_: Exception) { vue = null }
    }

    /** L'opacité de la bulle posée (elle laisse deviner ce qu'il y a dessous). */
    private const val ALPHA = 0.92f

    /** Retire la bulle : sortie douce (lot Accueil, point 10), la fenêtre n'est enlevée qu'à la fin. */
    fun retirer() {
        main.removeCallbacks(masquer)
        val v = vue ?: return
        vue = null
        val wm = appCtx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        Anim.sortir(v, Anim.Sens.SUR_PLACE) { try { wm?.removeView(v) } catch (_: Exception) {} }
    }
}
