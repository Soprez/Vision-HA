// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Le droit de voir ce qui joue. Android ne montre les sessions média qu'à un
 * service d'écoute des notifications activé par l'utilisateur ; celui-ci est
 * vide exprès : Vision ne lit, ne garde et n'envoie aucune notification.
 */
class EcouteFlux : NotificationListenerService()

/**
 * Ce qui joue sur l'appareil, d'où que ce soit lancé : à la télécommande, ou
 * à distance (cast depuis un téléphone). La dernière appli ouverte ne suffit
 * pas pour un cast : personne n'a rien ouvert sur la télé. On lit donc les
 * sessions média actives quand l'appareil nous le permet, et on retombe sur
 * l'ancien indice (du son + la dernière appli vue) sinon.
 */
object Flux {
    private val main = Handler(Looper.getMainLooper())
    private const val TAG = "Vision"

    private fun composant(ctx: Context) = ComponentName(ctx, EcouteFlux::class.java)

    /** Vrai si l'écoute est accordée (réglage système, ou `cmd notification allow_listener` par ADB sur télé). */
    fun droit(ctx: Context): Boolean = try {
        val brut = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        brut.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
    } catch (_: Exception) { false }

    private fun controleurs(ctx: Context): List<MediaController>? {
        if (!droit(ctx)) return null
        return try {
            (ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(composant(ctx))
        } catch (_: Exception) { null }
    }

    private fun joue(c: MediaController): Boolean = when (c.playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    /** Les applis qui jouent en ce moment d'après leurs sessions ; null quand l'appareil ne nous laisse pas voir. */
    fun paquets(ctx: Context): List<String>? =
        controleurs(ctx)?.filter { joue(it) }?.map { it.packageName }?.filter { it != ctx.packageName }?.distinct()

    /** Les applis qui jouent sans avoir le droit de rester en fond (Netflix…) ; vide quand on ne sait pas. */
    fun interdits(ctx: Context): Set<String> {
        val vus = paquets(ctx) ?: return emptySet()
        val permis = Local.fond(ctx)
        return if (vus.any { it in permis }) emptySet() else vus.toSet()
    }

    /** Lecture, pause, suivant… : à l'appli qui joue derrière la veille, directement quand on la connaît. */
    fun touche(ctx: Context, e: KeyEvent) {
        try {
            val permis = Local.fond(ctx)
            val liste = controleurs(ctx).orEmpty().filter { it.packageName != ctx.packageName }
            val c = liste.firstOrNull { joue(it) && it.packageName in permis } ?: liste.firstOrNull { it.packageName in permis }
            if (c != null && c.dispatchMediaButtonEvent(e)) return
        } catch (_: Exception) {}
        try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).dispatchMediaKeyEvent(e) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- Écoute

    private val abonnes = CopyOnWriteArraySet<Runnable>()
    private var ecoute: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val suivis = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val prevenir = Runnable { abonnes.forEach { try { it.run() } catch (_: Exception) {} } }

    /** Quelque chose a peut-être changé (session, état de lecture, radio de Vision qui cède le son). */
    fun signal() { main.removeCallbacks(prevenir); main.postDelayed(prevenir, 350) }

    private fun suivre(liste: List<MediaController>?) {
        suivis.values.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        suivis.clear()
        liste.orEmpty().forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { signal() }
                override fun onSessionDestroyed() { signal() }
            }
            try { c.registerCallback(cb, main); suivis[c.sessionToken] = c to cb } catch (_: Exception) {}
        }
    }

    private fun brancher(ctx: Context) {
        if (ecoute != null || !droit(ctx)) return
        val app = ctx.applicationContext
        try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val l = MediaSessionManager.OnActiveSessionsChangedListener { liste -> suivre(liste); signal() }
            msm.addOnActiveSessionsChangedListener(l, composant(app), main)
            ecoute = l
            suivre(msm.getActiveSessions(composant(app)))
        } catch (e: Exception) { android.util.Log.i(TAG, "Flux : sessions illisibles (${e.javaClass.simpleName})") }
    }

    private fun debrancher(ctx: Context) {
        val l = ecoute ?: return
        ecoute = null
        try { (ctx.applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).removeOnActiveSessionsChangedListener(l) } catch (_: Exception) {}
        suivre(null)
    }

    /**
     * Surveille le démarrage d'un flux tant que la veille est affichée. `autorise` reçoit l'appli quand un flux
     * permis en fond se met à jouer ; `interdit` quand c'est une appli qu'on ne recouvre pas.
     */
    class Guetteur(ctx: Context, private val autorise: (String) -> Unit, private val interdit: () -> Unit = {}) {
        private val app = ctx.applicationContext
        private var dernier: String? = null
        /** Ce qui jouait déjà sans droit de fond quand la veille s'est ouverte : on l'a recouvert exprès, on ne s'efface pas pour lui. */
        private var dejaLa: Set<String> = emptySet()
        private var actif = false
        private val voir = Runnable { regarder() }
        private val tour = object : Runnable { override fun run() { if (!actif) return; regarder(); main.postDelayed(this, 2500) } }

        private fun regarder() {
            if (!actif) return
            val pk = try { Local.appEnFond(app) } catch (_: Exception) { null }
            if (pk != null && pk != dernier) { dernier = pk; android.util.Log.i(TAG, "Flux : $pk joue pendant la veille"); autorise(pk); return }
            if (pk == null) {
                dernier = null
                val la = interdits(app)
                val nouveaux = la - dejaLa
                dejaLa = dejaLa intersect la
                if (nouveaux.isNotEmpty()) { android.util.Log.i(TAG, "Flux : $nouveaux démarre, la veille lui laisse l'écran"); interdit() }
            }
        }

        /** `connu` : l'appli qui jouait déjà à l'ouverture, pour ne pas la signaler une seconde fois. */
        fun demarrer(connu: String? = null) {
            if (actif) return
            actif = true; dernier = connu; dejaLa = try { interdits(app) } catch (_: Exception) { emptySet() }
            abonnes.add(voir); brancher(app)
            main.postDelayed(tour, 2500)
        }

        fun arreter() {
            if (!actif) return
            actif = false
            abonnes.remove(voir); main.removeCallbacks(tour)
            if (abonnes.isEmpty()) debrancher(app)
        }
    }

    // ------------------------------------------- Après une veille interrompue

    private var apres: Guetteur? = null
    private val finApres = Runnable { apres?.arreter(); apres = null }

    /**
     * Le système vient de fermer la veille sans qu'on le lui demande : c'est souvent un cast qui réveille la télé.
     * Pendant quelques secondes, si un flux permis en fond démarre, la veille revient devant, en couche.
     */
    fun guetterApres(ctx: Context) {
        val app = ctx.applicationContext
        if (!ReglagesTvActivity.estTele(app) || !Veille.permise(app)) return
        // Une touche vient d'être pressée (Accueil…) : c'est quelqu'un, pas un cast.
        if (android.os.SystemClock.uptimeMillis() - toucheA < 3000) return
        finApres.run()
        val g = Guetteur(app, { _ ->
            finApres.run(); main.removeCallbacks(finApres)
            if (!Veille.ouverte && Usage.ecranAllume(app)) { android.util.Log.i(TAG, "Flux : la veille revient en couche"); Veille.ouvrir(app) }
        })
        apres = g
        g.demarrer()
        main.postDelayed(finApres, 25_000)
    }

    /** Quelqu'un a repris la télécommande : la veille ne reviendra pas d'elle-même. */
    fun mainHumaine() { toucheA = android.os.SystemClock.uptimeMillis(); if (apres != null) { main.removeCallbacks(finApres); finApres.run() } }
    @Volatile private var toucheA = 0L
}
