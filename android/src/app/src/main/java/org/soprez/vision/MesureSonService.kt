// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.util.Calendar

/**
 * Niveau sonore : la mesure par le micro (service de premier plan « microphone »).
 *
 * Toutes les 100 ms, une tranche d'échantillons devient UN nombre (dB relatifs), puis est oubliée : rien n'est
 * enregistré, rien d'audio ne quitte l'appareil. Le micro est partagé : une autre appli (visio) qui le prend
 * l'emporte, la mesure reprend quand il se libère. Un dépassement (hors plages : rien) est affiché aussitôt et
 * remis au relevé suivant, que le service demande tout de suite.
 */
class MesureSonService : Service() {
    companion object {
        private const val CANAL = "vision_son"
        private const val CANAL_ALERTE = "vision_son_alerte"
        private const val NOTIF = 7501
        private const val NOTIF_ALERTE = 7502
        private const val TAUX = 16_000
        private const val TRANCHE = TAUX / 10  // 100 ms
        @Volatile var enMarche = false
            private set
        @Volatile private var aReconfigurer = false
        /** 16-10 : un arrêt demandé pendant le démarrage, avant startForeground ; le service s'arrête juste après. */
        @Volatile private var arretDemande = false

        /** Lance la mesure (service de premier plan). */
        fun lancer(ctx: Context) {
            arretDemande = false
            val i = Intent(ctx, MesureSonService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /**
         * 16-10 (recette téléphone : l'appli tuée par Android) : arrêté avant d'avoir appelé startForeground, un service lancé
         * par startForegroundService fait tuer l'appli (ForegroundServiceDidNotStartInTimeException). Pendant le
         * démarrage, l'arrêt est donc seulement noté ; onCreate s'arrête de lui-même une fois au premier plan.
         */
        fun arreter(ctx: Context) {
            if (enMarche) try { ctx.stopService(Intent(ctx, MesureSonService::class.java)) } catch (_: Exception) {}
            else arretDemande = true
        }
        fun reconfigurer() { aReconfigurer = true }
    }

    @Volatile private var continuer = true
    private var fil: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        canaux()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF, notification())
        } catch (_: Exception) {
            // Lancé hors du premier plan (Android 14+) : on s'arrête, l'accueil réessaiera.
            enMarche = false; stopSelf(); return
        }
        // Au premier plan seulement : un arrêt peut maintenant passer par stopService sans faire tuer l'appli.
        enMarche = true
        if (arretDemande) { arretDemande = false; enMarche = false; stopSelf(); return }
        fil = Thread({ mesurer() }, "vision-son").apply { isDaemon = true; start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        continuer = false
        enMarche = false
        fil?.interrupt()
        NiveauSonore.niveau = null; NiveauSonore.maximum = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun mesurer() {
        val tampon = ShortArray(TRANCHE)
        val minute = NiveauSonoreCalcul.Minute()
        var r = NiveauSonore.reglage
        var detecteur = NiveauSonoreCalcul.Detecteur(r.seuil.toFloat(), r.sensibiliteS * 1000L)
        var dernierePublication = 0L
        while (continuer) {
            if (!NiveauSonore.autorise(this)) break
            val taille = maxOf(AudioRecord.getMinBufferSize(TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), TRANCHE * 4)
            val micro = try { AudioRecord(MediaRecorder.AudioSource.MIC, TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, taille) } catch (_: Exception) { null }
            if (micro == null || micro.state != AudioRecord.STATE_INITIALIZED) { micro?.release(); attendre(30_000); continue }
            try {
                micro.startRecording()
                while (continuer) {
                    val n = micro.read(tampon, 0, TRANCHE)
                    if (n <= 0) break  // micro pris par une autre appli, ou coupé : on réessaie plus tard
                    if (aReconfigurer) {
                        aReconfigurer = false; r = NiveauSonore.reglage
                        detecteur = NiveauSonoreCalcul.Detecteur(r.seuil.toFloat(), r.sensibiliteS * 1000L)
                    }
                    val db = NiveauSonoreCalcul.niveau(tampon, n, r.calibration)
                    java.util.Arrays.fill(tampon, 0)  // rien ne reste des échantillons
                    minute.pousser(db)
                    val t = System.currentTimeMillis()
                    if (t - dernierePublication > 5_000) {
                        NiveauSonore.niveau = minute.moyenne(); NiveauSonore.maximum = minute.maximum(); dernierePublication = t
                    }
                    if (detecteur.pousser(db, t) && dansPlage(r)) depassement(t, db, r)
                }
            } catch (_: Exception) {
            } finally {
                try { micro.stop() } catch (_: Exception) {}
                micro.release()
            }
            attendre(10_000)
        }
        stopSelf()
    }

    private fun attendre(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun dansPlage(r: NiveauSonoreCalcul.Reglage): Boolean {
        val c = Calendar.getInstance()
        return NiveauSonoreCalcul.dansPlages(r.plages, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }

    private fun depassement(t: Long, db: Float, r: NiveauSonoreCalcul.Reglage) {
        NiveauSonore.noterDepassement(this, t, db)
        // L'alerte affichée tout de suite : « alerte n sur N » (le compte de Home Assistant, plus celle-ci).
        val n = r.alertes + 1
        val texte = if (r.alertesAvantCoupure > 0) getString(R.string.son_alerte_sur, n.coerceAtMost(r.alertesAvantCoupure), r.alertesAvantCoupure)
            else getString(R.string.son_alerte)
        val nm = getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL_ALERTE) else @Suppress("DEPRECATION") Notification.Builder(this).apply { setPriority(Notification.PRIORITY_HIGH); setDefaults(Notification.DEFAULT_VIBRATE) }
        b.setSmallIcon(R.drawable.ic_vision).setContentTitle(getString(R.string.son_alerte_titre)).setContentText(texte)
            .setStyle(Notification.BigTextStyle().bigText(texte)).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(60_000)
        try { nm.notify(NOTIF_ALERTE, b.build()) } catch (_: Exception) {}
        // Le relevé suivant tout de suite : Home Assistant compte l'alerte et décide de la coupure.
        AgentService.demarrer(this)
    }

    private fun canaux() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, getString(R.string.son_canal), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false); description = getString(R.string.son_canal_desc)
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_ALERTE, getString(R.string.son_canal_alerte), NotificationManager.IMPORTANCE_HIGH).apply {
            setShowBadge(false); description = getString(R.string.son_canal_alerte_desc)
        })
    }

    private fun notification(): Notification {
        val ouvrir = PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL) else @Suppress("DEPRECATION") Notification.Builder(this).apply { setPriority(Notification.PRIORITY_LOW) }
        return b.setSmallIcon(R.drawable.ic_vision).setContentTitle(getString(R.string.son_notif_titre)).setContentText(getString(R.string.son_notif_texte))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.son_notif_texte)))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(ouvrir).build()
    }
}
