// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.util.TimeZone

/**
 * Réveil (lot 4) : la sonnerie. Service au premier plan de type « mediaPlayback » (le démarrer depuis une alarme
 * `setAlarmClock` est permis), avec un wake lock, la notification plein écran (ReveilActivity par-dessus l'écran
 * verrouillé) et la lecture sur le flux « alarme » (passe le mode silencieux).
 *
 * - Volume : le flux alarme est réglé au volume de fin, le lecteur monte de début/fin à 1 en montee_s (remis après).
 * - Source : un flux (radio, podcast, adresse) ; s'il n'a rien joué au bout de 20 s, ou s'il casse, la sonnerie
 *   embarquée prend le relais au volume de fin et `echec` (raison source, repli_joue) part. Jamais désactivable.
 * - Répéter (au plus repetition.max fois) : alarme locale ; Arrêter : fin. Personne ? Arrêt automatique au bout de
 *   arret_auto_min (arrete par auto + echec non_acquitte).
 * - Un arrêt ou un report sur un autre appareil arrive en ordre (par partage) et s'applique ici sans être renvoyé.
 */
class ReveilService : Service() {
    data class Sonnerie(val r: ReveilsModele.Reveil, val occ: String, val prevu: Long, val reports: Int, val debut: Long,
                        val ordre: Boolean, val repli: Boolean = false)

    companion object {
        const val ACTION_SONNER = "org.soprez.vision.REVEIL_SERVICE_SONNER"
        const val ACTION_REPORTER = "org.soprez.vision.REVEIL_SERVICE_REPORTER"
        const val ACTION_ARRETER = "org.soprez.vision.REVEIL_SERVICE_ARRETER"
        const val ACTION_ARRETER_TOUT = "org.soprez.vision.REVEIL_SERVICE_ARRETER_TOUT"
        private const val CANAL = "vision_reveil"
        private const val NOTIF = 7400

        /** La sonnerie en cours (null : rien ne sonne) ; l'écran de réveil s'y abonne. */
        @Volatile var courant: Sonnerie? = null
        @Volatile var ecoute: (() -> Unit)? = null

        /** Une occurrence, un report ou un ordre « sonner » ; `ordre` = JSON de l'ordre HA (sonnerie sans liste). */
        fun sonner(ctx: Context, reveil: String, occ: String, prevu: Long, reports: Int, ordre: String?) {
            val i = Intent(ctx, ReveilService::class.java).setAction(ACTION_SONNER)
                .putExtra(ReveilReceiver.EXTRA_REVEIL, reveil).putExtra(ReveilReceiver.EXTRA_OCC, occ)
                .putExtra(ReveilReceiver.EXTRA_PREVU, prevu).putExtra(ReveilReceiver.EXTRA_REPORTS, reports)
            if (ordre != null) i.putExtra(ReveilReceiver.EXTRA_ORDRE, ordre)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) {}
        }

        /** Un ordre venu de HA (contrat §2) : sonner, reporter, arreter. */
        fun ordre(ctx: Context, o: JSONObject) {
            val reveil = o.optString("reveil")
            when (o.optString("type")) {
                "sonner" -> sonner(ctx, reveil, o.optString("occurrence"), System.currentTimeMillis(), 0, o.toString())
                "reporter" -> commande(ctx, ACTION_REPORTER, o.optString("par", "service"), reveil,
                    ReveilsModele.lireIso(o.optString("jusqu_a")).takeIf { it > 0 } ?: (System.currentTimeMillis() + o.optInt("minutes", 9) * 60_000L))
                "arreter" -> commande(ctx, if (reveil.isEmpty()) ACTION_ARRETER_TOUT else ACTION_ARRETER, o.optString("par", "service"), reveil)
            }
        }

        /** Reporter ou arrêter ce qui sonne (écran, notification, ordre). Rien ne sonne : seul un report en attente s'annule. */
        fun commande(ctx: Context, action: String, par: String, reveil: String = "", jusqua: Long = 0L) {
            val c = courant
            if (c == null || (reveil.isNotEmpty() && c.r.id != reveil)) {
                if (action != ACTION_REPORTER) Reveils.annulerReport(ctx)
                return
            }
            val i = Intent(ctx, ReveilService::class.java).setAction(action).putExtra("par", par).putExtra("jusqua", jusqua)
            try { ctx.startService(i) } catch (_: Exception) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var joueur: ExoPlayer? = null
    private var verrou: PowerManager.WakeLock? = null
    private var volumeAvant = -1
    private val tz get() = TimeZone.getDefault()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        when (i?.action) {
            ACTION_SONNER -> demarrer(i)
            ACTION_REPORTER -> reporter(i.getStringExtra("par") ?: "appareil", i.getLongExtra("jusqua", 0L))
            ACTION_ARRETER, ACTION_ARRETER_TOUT -> arreter(i.getStringExtra("par") ?: "appareil")
            else -> if (courant == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ Démarrer

    private fun demarrer(i: Intent) {
        val reveilId = i.getStringExtra(ReveilReceiver.EXTRA_REVEIL).orEmpty()
        val ordre = i.getStringExtra(ReveilReceiver.EXTRA_ORDRE)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        val r = ordre?.let { ReveilsModele.lireReveil(it.put("id", reveilId), occsAussi = false) }
            ?: Reveils.reveil(this, reveilId)
            ?: ReveilsModele.lireReveil(JSONObject().put("id", reveilId).put("nom", getString(R.string.reveil_nom_defaut)), occsAussi = false)
        val occ = i.getStringExtra(ReveilReceiver.EXTRA_OCC).orEmpty()
        val prevu = i.getLongExtra(ReveilReceiver.EXTRA_PREVU, System.currentTimeMillis())
        courant?.let { arreterSon() }
        try { verrou?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}  // une sonnerie remplacée rend son verrou
        val s = Sonnerie(r, occ, prevu, i.getIntExtra(ReveilReceiver.EXTRA_REPORTS, 0), System.currentTimeMillis(), ordre != null)
        courant = s
        premierPlan(s)
        verrou = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vision:reveil").apply { acquire((r.arretAutoMin + 2) * 60_000L) }
        jouer(s)
        vibrer(true)
        ouvrirEcran()
        if (s.reports == 0) Reveils.noter(this, ReveilsModele.evenement("sonne", r.id, occ, System.currentTimeMillis(), tz,
            "heure_prevue" to ReveilsModele.iso(prevu, tz), "heure_reelle" to ReveilsModele.iso(System.currentTimeMillis(), tz),
            "source" to r.source.type))
        main.removeCallbacks(auto)
        main.postDelayed(auto, r.arretAutoMin * 60_000L)
        ecoute?.invoke()
    }

    private fun canal(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, getString(R.string.reveil_canal), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    private fun servicePi(action: String, rc: Int): PendingIntent = PendingIntent.getService(this, rc,
        Intent(this, ReveilService::class.java).setAction(action).putExtra("par", "appareil"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun premierPlan(s: Sonnerie) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        canal(nm)
        val ecran = PendingIntent.getActivity(this, NOTIF, ReveilActivity.intention(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL)
                else @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_MAX)
        b.setSmallIcon(R.drawable.ic_vision)
            .setContentTitle(s.r.nom.ifEmpty { getString(R.string.reveil_nom_defaut) })
            .setContentText(getString(R.string.reveil_sonne_a, Tr.heure(s.prevu)))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(ecran)
            .setFullScreenIntent(ecran, true)
        if (ReveilsModele.peutRepeter(s.r, s.reports))
            b.addAction(Notification.Action.Builder(null, getString(R.string.reveil_repeter_n, s.r.repMin), servicePi(ACTION_REPORTER, 1)).build())
        b.addAction(Notification.Action.Builder(null, getString(R.string.reveil_arreter), servicePi(ACTION_ARRETER, 2)).build())
        val n = b.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF, n)
        } catch (_: Exception) {
            // Premier plan refusé par le système : on sonne quand même, avec une notification ordinaire.
            try { nm.notify(NOTIF, n) } catch (_: Exception) {}
        }
    }

    private fun ouvrirEcran() {
        // L'intention plein écran suffit écran verrouillé ; appareil en main, on tente aussi l'ouverture directe.
        try { startActivity(ReveilActivity.intention(this)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ Son

    private fun jouer(s: Sonnerie) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (volumeAvant < 0) volumeAvant = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, ReveilsModele.cranAlarme(s.r.volFin, max), 0)
        } catch (_: Exception) {}
        if (s.r.source.flux) lancer(MediaItem.fromUri(s.r.source.uri), boucle = false, s)
        else lancerSonnerie(s, echec = false)
        main.post(fondu)
        if (s.r.source.flux) main.postDelayed(veille, ReveilsModele.REPLI_APRES_MS)
    }

    private fun lancer(item: MediaItem, boucle: Boolean, s: Sonnerie) {
        try { joueur?.release() } catch (_: Exception) {}
        val p = ExoPlayer.Builder(this).build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION).build(), false)
        p.repeatMode = if (boucle) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.volume = ReveilsModele.volume(s.r, System.currentTimeMillis() - s.debut)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { main.post { if (!boucle) repli("lecture") } }
            override fun onPlaybackStateChanged(state: Int) {
                // Un podcast qui se termine ou un flux qui tombe : on ne reste jamais muet.
                if (state == Player.STATE_ENDED && !boucle) main.post { repli("fin") }
            }
        })
        p.setMediaItem(item); p.playWhenReady = true; p.prepare()
        joueur = p
    }

    private fun lancerSonnerie(s: Sonnerie, echec: Boolean) {
        lancer(MediaItem.fromUri(Uri.parse("android.resource://$packageName/${R.raw.vision_alarme}")), boucle = true, s)
        if (echec) joueur?.volume = 1f
    }

    /** 20 s après le départ : un flux qui n'a rien joué passe la main à la sonnerie embarquée. */
    private val veille = Runnable { if (joueur?.isPlaying != true) repli("muet") }

    private fun repli(detail: String) {
        val s = courant ?: return
        if (s.repli) return
        courant = s.copy(repli = true)
        main.removeCallbacks(veille)
        lancerSonnerie(s, echec = true)
        Reveils.noter(this, ReveilsModele.evenement("echec", s.r.id, s.occ, System.currentTimeMillis(), tz,
            "raison" to "source", "detail" to detail, "repli_joue" to true))
    }

    private val fondu = object : Runnable {
        override fun run() {
            val s = courant ?: return
            val p = joueur ?: return
            if (!s.repli) p.volume = ReveilsModele.volume(s.r, System.currentTimeMillis() - s.debut)
            if (p.volume < 1f) main.postDelayed(this, 500)
        }
    }

    private fun vibrer(oui: Boolean) {
        if (ReglagesTvActivity.estTele(this)) return
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (!oui) { v.cancel(); return }
            val motif = longArrayOf(0, 600, 900)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(motif, 0)) else @Suppress("DEPRECATION") v.vibrate(motif, 0)
        } catch (_: Exception) {}
    }

    private fun arreterSon() {
        main.removeCallbacks(fondu); main.removeCallbacks(veille)
        try { joueur?.release() } catch (_: Exception) {}
        joueur = null
        vibrer(false)
        if (volumeAvant >= 0) try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, volumeAvant, 0)
        } catch (_: Exception) {}
        volumeAvant = -1
    }

    // ------------------------------------------------------------------ Répéter, arrêter

    private val auto = Runnable {
        val s = courant ?: return@Runnable
        Reveils.noter(this, ReveilsModele.evenement("arrete", s.r.id, s.occ, System.currentTimeMillis(), tz, "par" to "auto"))
        Reveils.noter(this, ReveilsModele.evenement("echec", s.r.id, s.occ, System.currentTimeMillis(), tz,
            "raison" to "non_acquitte", "repli_joue" to s.repli))
        terminer()
    }

    private fun reporter(par: String, jusquaVoulu: Long) {
        val s = courant ?: run { stopSelf(); return }
        val partage = par == "partage" || par == "service" || par == "appli"
        if (!partage && !ReveilsModele.peutRepeter(s.r, s.reports)) return
        val jusqua = if (jusquaVoulu > System.currentTimeMillis()) jusquaVoulu else System.currentTimeMillis() + s.r.repMin * 60_000L
        val nombre = s.reports + 1
        Reveils.armerReport(this, s.r.id, s.occ, s.prevu, jusqua, nombre, null)
        // L'événement d'abord : qui voit la sonnerie finie trouve déjà l'événement en file.
        Reveils.noter(this, ReveilsModele.evenement("reporte", s.r.id, s.occ, System.currentTimeMillis(), tz,
            "nombre" to nombre, "jusqu_a" to ReveilsModele.iso(jusqua, tz), "par" to par))
        terminer()
    }

    private fun arreter(par: String) {
        val s = courant
        Reveils.annulerReport(this)
        if (s != null) Reveils.noter(this, ReveilsModele.evenement("arrete", s.r.id, s.occ, System.currentTimeMillis(), tz, "par" to par))
        terminer()
    }

    private fun terminer() {
        main.removeCallbacks(auto)
        arreterSon()
        courant = null
        try { verrou?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        verrou = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF) } catch (_: Exception) {}
        ecoute?.invoke()
        stopSelf()
    }

    override fun onDestroy() {
        if (courant != null) { arreterSon(); courant = null; ecoute?.invoke() }
        try { verrou?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
