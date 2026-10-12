// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * 1.86.2 (retour d'essai) : les petites pochettes des résultats et de la file de la Party. Chargées en différé, réduites
 * à la taille voulue (environ 96 à 160 px), gardées en mémoire ; l'image garde sa place pendant le chargement (fond
 * neutre), rien ne saute. 2 Mo au plus par image.
 * 1.87.1 : une image https publique est lue directement ; une pochette de Music Assistant (réseau local, http) est
 * relayée par Home Assistant, avec la référence qu'il a donnée (« relais:<ref> », voir Party.image) et l'identifiant
 * de l'appareil. Jamais d'adresse http lue par l'appareil.
 */
object Vignettes {
    const val RELAIS = "relais:"
    private const val CHEMIN_RELAIS = "/api/pc_parental/party/image"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val echecs = java.util.Collections.synchronizedSet(HashSet<String>())

    fun poser(vue: ImageView, url: String, cotePx: Int) {
        vue.tag = url
        val relais = url.startsWith(RELAIS)
        if (!relais && !url.startsWith("https://")) { vue.setImageDrawable(null); return }
        val app = vue.context.applicationContext
        cache.get(url)?.let { vue.setImageBitmap(it); return }
        vue.setImageDrawable(null)
        if (url in echecs) return
        pool.execute {
            val bmp = try { if (relais) relayer(app, url.removePrefix(RELAIS), cotePx) else charger(url, cotePx) } catch (_: Exception) { null }
            if (bmp == null) { echecs.add(url); return@execute }
            cache.put(url, bmp)
            main.post { if (vue.tag == url) vue.setImageBitmap(bmp) }
        }
    }

    /** La pochette lue par Home Assistant (référence qu'il a donnée) ; rien (pas d'image, ou Home Assistant trop ancien) : null. */
    private fun relayer(ctx: Context, ref: String, cote: Int): Bitmap? {
        val cfg = Config(ctx)
        val octets = Net.postBytes(ctx, cfg, CHEMIN_RELAIS, JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("ref", ref), 10_000)
            ?: return null
        if (octets.isEmpty() || octets.size > 2 * 1024 * 1024) return null
        return decoder(octets, cote)
    }

    private fun decoder(octets: ByteArray, cote: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(octets, 0, octets.size, o)
        var ech = 1
        while (o.outWidth / (ech * 2) >= cote && o.outHeight / (ech * 2) >= cote) ech *= 2
        return BitmapFactory.decodeByteArray(octets, 0, octets.size, BitmapFactory.Options().apply { inSampleSize = ech })
    }

    private fun charger(url: String, cote: Int): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 6000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) return null
            val octets = c.inputStream.use { it.readNBytesCompat(2 * 1024 * 1024) } ?: return null
            return decoder(octets, cote)
        } finally { c.disconnect() }
    }

    /** Lit au plus [max] octets ; null si l'image est plus grosse. */
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray? {
        val sortie = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf); if (n < 0) break
            sortie.write(buf, 0, n)
            if (sortie.size() > max) return null
        }
        return sortie.toByteArray()
    }
}
