// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONObject

/**
 * 1.93.05 (l'auteur, retours 37 et 38, TV du salon) : les touches MÉDIA de la télécommande pilotent la musique de Music
 * Assistant de cet appareil — dans le lecteur, sur l'accueil et dans la veille.
 *
 * Avant : le lecteur ne gérait que lecture/pause, suivant et précédent ; l'accueil, rien ; la veille renvoyait la touche
 * aux AUTRES applis (Flux.touche), et Vision n'avait pas de MediaSession pour la musique de Music Assistant : la touche
 * n'arrivait nulle part.
 * Maintenant :
 * - [action] traduit les huit touches (lecture, pause, lecture/pause, stop, suivant, précédent, avance et recul de
 *   [SAUT_S] s) ; [commande] en fait l'ordre pour Home Assistant (le même que les boutons de l'écran) ;
 * - une MediaSession de Vision ([maintenir]) est active seulement quand Vision est DEVANT et que la musique de Music
 *   Assistant joue sur cet appareil : le système lui envoie alors les touches qu'aucun écran n'a prises (veille, accueil).
 *   Une autre appli au premier plan (TV en direct, YouTube) : session inactive, Vision ne prend rien.
 * - Volume (retour 38) : la session est en lecture LOCALE ; Vision ne prend jamais les touches de volume : le système
 *   règle le son et montre sa jauge (celle de la TCL).
 */
object ToucheMedia {
    enum class Action { BASCULER, LECTURE, PAUSE, ARRET, SUIVANT, PRECEDENT, AVANCER, RECULER }

    const val SAUT_S = 10.0

    /** La touche → l'action ; null pour toute autre touche (le volume en particulier n'est jamais pris). Pur. */
    fun action(keyCode: Int): Action? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Action.BASCULER
        KeyEvent.KEYCODE_MEDIA_PLAY -> Action.LECTURE
        KeyEvent.KEYCODE_MEDIA_PAUSE -> Action.PAUSE
        KeyEvent.KEYCODE_MEDIA_STOP -> Action.ARRET
        KeyEvent.KEYCODE_MEDIA_NEXT -> Action.SUIVANT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Action.PRECEDENT
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.AVANCER
        KeyEvent.KEYCODE_MEDIA_REWIND -> Action.RECULER
        else -> null
    }

    /**
     * L'ordre pour Home Assistant (action de /party/commande et son corps), ou null s'il n'y a rien à faire (avance ou
     * recul sans position connue). [joue] : la musique joue maintenant ; [positionS] / [dureeS] : où en est le titre. Pur.
     */
    fun commande(a: Action, joue: Boolean, positionS: Double?, dureeS: Double?): Pair<String, JSONObject>? = when (a) {
        Action.BASCULER -> "lecture" to JSONObject().put("voulu", if (joue) "pause" else "lecture")
        Action.LECTURE -> "lecture" to JSONObject().put("voulu", "lecture")
        Action.PAUSE, Action.ARRET -> "lecture" to JSONObject().put("voulu", "pause")
        Action.SUIVANT -> "suivant" to JSONObject()
        Action.PRECEDENT -> "precedent" to JSONObject()
        Action.AVANCER, Action.RECULER -> positionS?.let { p ->
            val v = p + if (a == Action.AVANCER) SAUT_S else -SAUT_S
            val borne = if (dureeS != null && dureeS > 0) Saut.borner(v, dureeS) else v.coerceAtLeast(0.0)
            "position" to JSONObject().put("position", Math.round(borne * 10) / 10.0)
        }
    }

    /** La musique de Music Assistant joue-t-elle sur cet appareil ? (HA le dit au relevé ; ou le lecteur de Vision joue.) */
    fun joueIci(ctx: Context): Boolean = VeilleMusique.enLecture() || LecteurMusique.etat == "lecture"

    /** Vision est-il devant (accueil, lecteur, veille) ? Sinon, aucune touche n'est prise. */
    fun visionDevant(): Boolean = LanceurActivity.devant || PartyActivity.affichee || Veille.ouverte || VeilleActivity.devant

    /**
     * Envoie l'ordre à Home Assistant, hors du fil principal (même chemin que les boutons du lecteur). L'état de la musique
     * est relu d'abord : lecture/pause part avec l'état VOULU, l'avance et le recul avec la position du titre.
     */
    fun envoyer(ctx: Context, a: Action) {
        val app = ctx.applicationContext
        Thread {
            try {
                val cfg = Config(app)
                val e = try { Party.analyser(Net.post(app, cfg, "/api/pc_parental/party/etat", JSONObject().put("id", cfg.id).put("secret", cfg.secret), 6_000)) } catch (_: Exception) { null }
                val joue = e?.let { it.maintenant.etat == "playing" } ?: joueIci(app)
                val pos = e?.let { Party.positionAffichee(app, it, System.currentTimeMillis() / 1000.0) }
                val c = commande(a, joue, pos, e?.maintenant?.duree) ?: return@Thread
                Net.post(app, cfg, "/api/pc_parental/party/commande", c.second.put("action", c.first).put("id", cfg.id).put("secret", cfg.secret), 9_000)
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()
    }

    /** Une touche reçue par un écran de Vision (accueil, veille) : rend vrai si elle est prise. */
    fun touche(ctx: Context, e: KeyEvent): Boolean {
        val a = action(e.keyCode) ?: return false
        if (!joueIci(ctx)) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) envoyer(ctx, a)
        return true
    }

    // ------------------------------------------------------------------ MediaSession

    private var session: MediaSession? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Doit-elle être active ? Vision devant et la musique de Music Assistant qui joue ici. Pur. */
    fun sessionVoulue(devant: Boolean, joue: Boolean, sendspin: Boolean): Boolean = devant && joue && !sendspin

    /** Pose ou retire la session selon l'état du moment (appelé au relevé et quand un écran de Vision passe devant). */
    fun maintenir(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            try {
                val voulue = sessionVoulue(visionDevant(), joueIci(app), LecteurSendspin.actif)
                if (!voulue) { session?.isActive = false; return@post }
                val s = session ?: MediaSession(app, "vision-musique").also { s ->
                    // Retour 38 : volume local — le système règle le son et montre SA jauge.
                    s.setPlaybackToLocal(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    s.setCallback(object : MediaSession.Callback() {
                        override fun onPlay() = envoyer(app, Action.LECTURE)
                        override fun onPause() = envoyer(app, Action.PAUSE)
                        override fun onStop() = envoyer(app, Action.ARRET)
                        override fun onSkipToNext() = envoyer(app, Action.SUIVANT)
                        override fun onSkipToPrevious() = envoyer(app, Action.PRECEDENT)
                        override fun onFastForward() = envoyer(app, Action.AVANCER)
                        override fun onRewind() = envoyer(app, Action.RECULER)
                    })
                    session = s
                }
                s.setPlaybackState(PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND).build())
                s.isActive = true
            } catch (_: Exception) {}
        }
    }

    /** Pour les essais : la session active du moment. */
    fun sessionActive(): Boolean = session?.isActive == true
}
