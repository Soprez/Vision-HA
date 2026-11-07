// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Les captures d'écran de « Signaler un problème » (MediaProjection : la boîte Android « Vision va capturer l'écran »
 * est demandée au lancement). Refusée : l'enregistrement continue sans captures, ce que l'écran dit.
 * Les fenêtres `FLAG_SECURE` (saisie du code) restent noires ; elles ne sont jamais retouchées après coup.
 */
object CaptureEcran {
    const val LARGEUR_MAX = 1280
    const val OCTETS_MAX = 300 * 1024
    @Volatile internal var service: CaptureService? = null

    fun disponible(): Boolean = service?.pret == true

    /** L'intention de la boîte de dialogue d'Android (à lancer avec startActivityForResult). */
    fun demande(ctx: Context): Intent =
        (ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    fun demarrer(ctx: Context, code: Int, data: Intent) {
        val i = Intent(ctx, CaptureService::class.java).putExtra("code", code).putExtra("data", data)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun arreter(ctx: Context) { ctx.stopService(Intent(ctx, CaptureService::class.java)) }

    /** Une capture de l'écran courant, rangée dans le cache ; faux si rien n'est disponible ou si la limite est atteinte. */
    fun capturer(ctx: Context): Boolean {
        val bmp = service?.derniereImage() ?: return false
        val octets = try { jpeg(bmp) } finally { bmp.recycle() }
        val dossier = File(ctx.cacheDir, "signalement").apply { mkdirs() }
        val f = File(dossier, "c%d_%d.jpg".format(Signalement.fichiersCaptures().size + 1, System.currentTimeMillis()))
        f.writeBytes(octets)
        if (Signalement.ajouterCapture(f)) return true
        f.delete(); return false
    }

    /** JPEG d'au plus [LARGEUR_MAX] px de large et [OCTETS_MAX] octets (qualité 80, abaissée au besoin). Pur : testable. */
    fun jpeg(source: Bitmap, largeurMax: Int = LARGEUR_MAX, octetsMax: Int = OCTETS_MAX): ByteArray {
        val b = if (source.width > largeurMax)
            Bitmap.createScaledBitmap(source, largeurMax, (source.height.toLong() * largeurMax / source.width).toInt().coerceAtLeast(1), true) else source
        try {
            var q = 80
            while (true) {
                val sortie = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, q, sortie)
                if (sortie.size() <= octetsMax || q <= 20) return sortie.toByteArray()
                q -= 15
            }
        } finally { if (b !== source) b.recycle() }
    }
}

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var lecteur: ImageReader? = null
    private var ecran: VirtualDisplay? = null
    private var derniere: Image? = null
    @Volatile var pret = false
        private set

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val code = i?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data = i?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        demarrerPremierPlan()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("projection refusée")
            val main = Handler(Looper.getMainLooper())
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { liberer() } }, main)
            projection = p
            val dm = resources.displayMetrics
            val l = minOf(dm.widthPixels, CaptureEcran.LARGEUR_MAX)
            val h = (dm.heightPixels.toLong() * l / dm.widthPixels).toInt()
            val r = ImageReader.newInstance(l, h, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ lec ->
                val im = try { lec.acquireLatestImage() } catch (_: Exception) { null }
                if (im != null) synchronized(this) { derniere?.close(); derniere = im }
            }, main)
            lecteur = r
            ecran = p.createVirtualDisplay("vision-signalement", l, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
            pret = true
            CaptureEcran.service = this
        } catch (e: Exception) {
            Journal.w("Signalement", "captures indisponibles", e)
            liberer(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun demarrerPremierPlan() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("vision_signalement", getString(R.string.signaler_titre), NotificationManager.IMPORTANCE_LOW))
        @Suppress("DEPRECATION")
        val constructeur = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "vision_signalement") else Notification.Builder(this)
        val n = constructeur.setSmallIcon(applicationInfo.icon).setContentTitle(getString(R.string.signaler_titre))
            .setContentText(getString(R.string.signaler_en_cours)).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(4210, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(4210, n)
    }

    /** La dernière image reçue, copiée en bitmap (null si l'écran n'a encore rien envoyé). À recycler par l'appelant. */
    fun derniereImage(): Bitmap? = synchronized(this) {
        val im = derniere ?: return null
        try {
            val p = im.planes[0]
            val largeurLue = p.rowStride / p.pixelStride
            val b = Bitmap.createBitmap(largeurLue, im.height, Bitmap.Config.ARGB_8888)
            b.copyPixelsFromBuffer(p.buffer)
            if (largeurLue == im.width) b else Bitmap.createBitmap(b, 0, 0, im.width, im.height).also { b.recycle() }
        } catch (e: Exception) { Journal.w("Signalement", "capture illisible", e); null }
    }

    private fun liberer() {
        pret = false
        if (CaptureEcran.service === this) CaptureEcran.service = null
        try { ecran?.release() } catch (_: Exception) {}
        synchronized(this) { derniere?.close(); derniere = null }
        try { lecteur?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        ecran = null; lecteur = null; projection = null
    }

    override fun onDestroy() { liberer(); super.onDestroy() }
}
