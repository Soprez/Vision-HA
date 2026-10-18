// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * L'écran de veille à la demande (deux appuis sur Accueil, la bulle, le
 * réglage « Lancer l'écran de veille »). Posé en **couche par-dessus**
 * l'appli ouverte plutôt qu'en activité : YouTube, Spotify ou un jeu restent
 * au premier plan pour Android et continuent, image et son compris. La
 * veille se feuillette (gauche, droite, OK) et se retire sur Retour ou un
 * glissement vers le haut, rendant la main à ce qui jouait. Sans le droit d'afficher par-dessus, on retombe sur
 * l'activité classique (qui, elle, met l'appli du dessous en pause). Seule
 * l'inactivité (le rêve du système, ou la minuterie de l'accueil) coupe ce
 * qui est en cours.
 */
object Veille {
    private val main = Handler(Looper.getMainLooper())
    private var couche: View? = null
    private var vue: VeilleView? = null
    private var appCtx: Context? = null
    private var ouvertA = 0L
    private var appFond: String? = null
    private var recepteur: android.content.BroadcastReceiver? = null
    private var params: WindowManager.LayoutParams? = null
    private var guetteur: Flux.Guetteur? = null
    private var reposes = 0
    private var enRepose = false
    /** Instant (horloge murale) de la dernière fermeture : l'inactivité repart de là. */
    @Volatile var fermeeA = 0L

    fun permise(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** Veille de repli (accueil du fabricant) : le premier appui la retire, sans autre effet (volume excepté). */
    private var premierAppuiFerme = false

    /** Montre l'écran de veille sans interrompre l'appli ouverte quand c'est possible. */
    fun ouvrir(ctx: Context, demande: Boolean = false, premierAppui: Boolean = false) {
        if (couche != null) return
        // 1.86.14 (retour d'essai) : le lecteur Music Assistant de cette télé joue : pas de veille automatique (seule une demande explicite passe).
        if (!demande && VeilleMusique.bloque(ctx)) return
        // 1.86.2 (retour d'essai) : pendant la Party, aucune veille (ni la nôtre, ni sur inactivité).
        if (PartyActivity.affichee) return
        // Module « veille » coupé dans Home Assistant : l'écran de veille de Vision ne vient plus, d'où qu'on l'appelle.
        if (!Modules.actif(ctx, Modules.VEILLE)) return
        if (!permise(ctx)) { ctx.startActivity(Intent(ctx, VeilleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        val app = ctx.applicationContext
        appCtx = app
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val gestes = GestesVeille(app, { vue }) { fermer(parUtilisateur = true) }
        val cadre = object : FrameLayout(app) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (premierAppuiFerme && !MessagesRiches.touche(e)) {
                    if (e.keyCode in intArrayOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) return false
                    if (e.action == KeyEvent.ACTION_DOWN) fermer()
                    return true
                }
                return MessagesRiches.touche(e) || gestes.touche(e)
            }
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (premierAppuiFerme && !MessagesRiches.toucher(e)) { if (e.actionMasked == MotionEvent.ACTION_DOWN) fermer(); return true }
                return MessagesRiches.toucher(e) || gestes.toucher(e)
            }
            // Quelque chose est passé devant (le rêve du système, une autre appli) : la couche n'a plus lieu d'être.
            // Sinon elle resterait dessous, musique et caméras comprises, sans que personne puisse l'arrêter.
            // Un flux qui démarre dessous (cast, YouTube) peut nous prendre la main un instant : on regarde avant de fermer.
            override fun onWindowFocusChanged(a: Boolean) {
                super.onWindowFocusChanged(a)
                main.removeCallbacks(sansFocus)
                if (!a && !enRepose && SystemClock.uptimeMillis() - ouvertA > 1500) main.postDelayed(sansFocus, 1200)
            }
        }
        cadre.isFocusable = true; cadre.isFocusableInTouchMode = true
        val v = VeilleView(app)
        val cameras = CamerasCouche(app)
        v.cameras = cameras
        cadre.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        cadre.addView(cameras, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        VeilleWeb.attacher(cadre, v)
        // 1.93.05 (retour 36) : par-dessus tout (page de HA comprise), le bandeau « Lecteur musique ».
        cadre.addView(CoinsVeille(cadre.context, v).apply { tag = "coins_veille" }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE)
        // Jusque sous l'encoche : pas de bande nue en haut de l'écran.
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        try {
            wm.addView(cadre, lp)
            couche = cadre; vue = v; params = lp; reposes = 0; ouvertA = SystemClock.uptimeMillis(); premierAppuiFerme = premierAppui
            ToucheMedia.maintenir(app)  // 1.93.05 (retour 37)
            MessagesRiches.poserHote(app, cadre)
            appFond = try { Local.appEnFond(app) } catch (_: Exception) { null }
            // Un flux démarre pendant la veille : s'il a le droit de rester en fond, la veille reste devant et il joue derrière ;
            // sinon (Netflix lancé d'un téléphone…) elle lui laisse l'écran.
            guetteur = Flux.Guetteur(app, { pk -> appFond = pk; vue?.sonChange() }, { fermer() }).also { it.demarrer(appFond) }
            Bulle.retirer()
            // L'écran de veille du système ou l'extinction de l'écran ferment la couche.
            val r = object : android.content.BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { fermer() } }
            recepteur = r
            try {
                val filtre = android.content.IntentFilter().apply { addAction(Intent.ACTION_DREAMING_STARTED); addAction(Intent.ACTION_SCREEN_OFF) }
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filtre, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, filtre)
            } catch (_: Exception) { recepteur = null }
            main.post { cadre.requestFocus(); v.demarrer() }
            // Filet de sécurité : une couche ne vit jamais plus de deux heures.
            main.postDelayed(gardien, 2 * 3600_000L)
        } catch (_: Exception) {
            couche = null; vue = null
            ctx.startActivity(Intent(ctx, VeilleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val gardien = Runnable { fermer() }

    /** La couche n'a plus la main depuis plus d'une seconde. */
    private val sansFocus = Runnable {
        val c = couche ?: return@Runnable
        if (c.hasWindowFocus()) return@Runnable
        val app = appCtx
        val pk = try { app?.let { Local.appEnFond(it) } } catch (_: Exception) { null }
        // Un flux permis joue dessous : on se repose tout en haut pour garder la télécommande (trois fois au plus, puis on cède).
        if (app != null && pk != null && reposes < 3) { reposes++; appFond = pk; reposer(app, c) } else fermer()
    }

    private fun reposer(app: Context, c: View) {
        val lp = params ?: return fermer()
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        enRepose = true
        try {
            wm.removeViewImmediate(c); wm.addView(c, lp)
            ouvertA = SystemClock.uptimeMillis()
            main.post { c.requestFocus(); vue?.demarrer(); vue?.sonChange() }
        } catch (_: Exception) { couche = c; fermer() }
        main.postDelayed({ enRepose = false }, 600)
    }

    fun fermer(parUtilisateur: Boolean = false) {
        val c = couche ?: return
        couche = null
        ToucheMedia.maintenir(c.context)
        fermeeA = System.currentTimeMillis()
        val pk = appFond; appFond = null
        if (parUtilisateur && pk != null) main.postDelayed({ appCtx?.let { RetourActivity.proposer(it, pk) } }, 150)
        main.removeCallbacks(gardien); main.removeCallbacks(sansFocus)
        guetteur?.arreter(); guetteur = null; params = null
        recepteur?.let { r -> try { appCtx?.unregisterReceiver(r) } catch (_: Exception) {} }
        recepteur = null
        (c as? FrameLayout)?.let { cadre -> appCtx?.let { MessagesRiches.retirerHote(it, cadre) } }
        try { vue?.arreter() } catch (_: Exception) {}
        vue = null
        try { (appCtx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.removeView(c) } catch (_: Exception) {}
    }

    val ouverte get() = couche != null
}
