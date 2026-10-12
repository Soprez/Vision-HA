// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Les logos des équipes, demandés à Home Assistant (qui les relaie et les
 * garde), puis gardés ici en mémoire et sur le disque de la télé. `obtenir`
 * ne bloque jamais : il rend l'image si elle est là, sinon la fait venir et
 * rend null en attendant.
 */
class Logos(private val ctx: Context) {
    private val memoire = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val enCours = HashSet<String>()
    private val rates = HashMap<String, Long>()
    private val fil = Executors.newFixedThreadPool(2) { r -> Thread(r).also { it.isDaemon = true } }
    private val dossier = File(ctx.cacheDir, "logos").also { it.mkdirs() }
    private val cfg by lazy { Config(ctx) }

    fun obtenir(url: String): Bitmap? = demander(url, "/api/pc_parental/veille/logo", "url", 256)

    /** Une photo du dossier de Home Assistant, en grand (1280 px), même cache. */
    fun photo(nom: String): Bitmap? = demander(nom, "/api/pc_parental/veille/photo", "nom", 1280)

    private fun demander(cle: String, chemin: String, champ: String, max: Int): Bitmap? {
        if (cle.isEmpty()) return null
        memoire.get(cle)?.let { return it }
        synchronized(enCours) {
            if (cle in enCours) return null
            val rate = rates[cle]
            if (rate != null && System.currentTimeMillis() - rate < 120_000) return null
            enCours.add(cle)
        }
        fil.execute { charger(cle, chemin, champ, max) }
        return null
    }

    private fun charger(url: String, chemin: String, champ: String, max: Int) {
        try {
            val fichier = File(dossier, sha1(url))
            var octets: ByteArray? = if (fichier.exists()) fichier.readBytes() else null
            if (octets == null) {
                octets = Net.postBytes(ctx, cfg, chemin,
                    JSONObject().put("id", cfg.id).put("secret", cfg.secret).put(champ, url), 20_000)
                if (octets != null) try { fichier.writeBytes(octets) } catch (_: Exception) {}
            }
            val bm = octets?.let { decoder(it, max) }
            if (bm != null) memoire.put(url, bm) else synchronized(enCours) { rates[url] = System.currentTimeMillis() }
        } catch (_: Exception) {
            synchronized(enCours) { rates[url] = System.currentTimeMillis() }
        } finally {
            synchronized(enCours) { enCours.remove(url) }
        }
    }

    /** Un logo tient dans 256 px : inutile de garder du 1024 pour une vignette. */
    private fun decoder(octets: ByteArray, max: Int): Bitmap? {
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(octets, 0, octets.size, bornes)
        var echelle = 1
        while (maxOf(bornes.outWidth, bornes.outHeight) / (echelle * 2) >= max) echelle *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = echelle; inPreferredConfig = if (max > 512) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(octets, 0, octets.size, opts)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
