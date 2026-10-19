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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * 1.86.2 (retour d'essai) : les petites pochettes des résultats et de la file de la Party. Chargées en différé, réduites
 * à la taille voulue (environ 96 à 160 px), gardées en mémoire ; l'image garde sa place pendant le chargement (fond
 * neutre), rien ne saute. 2 Mo au plus par image.
 * 1.87.1 : une image https publique est lue directement ; une pochette de Music Assistant (réseau local, http) est
 * relayée par Home Assistant, avec la référence qu'il a donnée (« relais:<ref> », voir Party.image) et l'identifiant
 * de l'appareil. Jamais d'adresse http lue par l'appareil.
 * 1.90.2 (l'auteur : « il faudrait les précharger ») : les pochettes des titres suivants se chargent avant d'être montrées
 * ([precharger]) ; un échec n'est plus retenu pour de bon (nouvel essai après [ATTENTES_MS]) ; gardées aussi sur
 * disque ([CachePochettes]) et déchargées avec [vider].
 */
object Vignettes {
    const val RELAIS = "relais:"
    private const val CHEMIN_RELAIS = "/api/pc_parental/party/image"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Les échecs : (nombre, heure à partir de laquelle on peut réessayer). */
    private val echecs = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val enCours = ConcurrentHashMap.newKeySet<String>()
    /** Banc Musique (2026-10-12) : ceux qui attendent une image déjà en chargement (préchargement, autre vue). Avant, une
     *  vue qui demandait une vignette en cours de préchargement n'était jamais rappelée : la ligne de la file restait vide
     *  (1 à 3 vignettes sur 6 après 10 s, mesuré au banc), jusqu'à une reconstruction de l'écran. */
    private val attentes = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()
    val ATTENTES_MS = longArrayOf(3_000, 10_000, 30_000)

    fun garde(url: String): Bitmap? = if (url.isEmpty()) null else cache.get(url)

    /** 1.93.06 : la même image, décodée en grand (pochette du lecteur) : le titre suivant, préchargé pour l'attente. */
    fun gardeGrand(url: String): Bitmap? = if (url.isEmpty()) null else cache.get(GRAND + url)
    private const val GRAND = "grand|"

    /** 1.93.06 (retour d'essai) : précharge en grand le titre qui vient ensuite, pour que la pochette du lecteur soit prête. */
    fun prechargerGrand(ctx: Context, url: String, cotePx: Int) {
        if (!chargeable(url) || cache.get(GRAND + url) != null) return
        charger(ctx.applicationContext, url, cotePx, GRAND + url) {}
    }

    private fun chargeable(url: String) = url.startsWith(RELAIS) || url.startsWith("https://")

    private fun peutEssayer(url: String): Boolean {
        val e = echecs[url] ?: return true
        return e.first <= ATTENTES_MS.size && System.currentTimeMillis() >= e.second
    }

    fun poser(vue: ImageView, url: String, cotePx: Int) {
        vue.tag = url
        if (!chargeable(url)) { vue.setImageDrawable(null); return }
        val app = vue.context.applicationContext
        cache.get(url)?.let { vue.setImageBitmap(it); return }
        vue.setImageDrawable(null)
        echecs[url]?.let { e ->
            val reste = e.second - System.currentTimeMillis()
            if (reste > 0) { if (e.first <= ATTENTES_MS.size) main.postDelayed({ if (vue.tag == url) poser(vue, url, cotePx) }, reste); return }
        }
        charger(app, url, cotePx) { bmp ->
            if (bmp != null) { if (vue.tag == url) vue.setImageBitmap(bmp); return@charger }
            // Échec : un nouvel essai plus tard, si la vue montre encore cette image.
            val delai = echecs[url]?.let { it.second - System.currentTimeMillis() } ?: return@charger
            if (delai > 0) main.postDelayed({ if (vue.tag == url) poser(vue, url, cotePx) }, delai)
        }
    }

    /** Charge à l'avance (sans vue) : la file de la Party, avant qu'elle soit montrée ou que le titre passe. */
    fun precharger(ctx: Context, urls: List<String>, cotePx: Int) {
        val app = ctx.applicationContext
        urls.filter { chargeable(it) && cache.get(it) == null }.distinct().forEach { charger(app, it, cotePx) {} }
    }

    private fun charger(app: Context, url: String, cotePx: Int, cle: String = url, fin: (Bitmap?) -> Unit) {
        if (!peutEssayer(cle)) return
        synchronized(attentes) { attentes.getOrPut(cle) { ArrayList() }.add(fin) }
        if (!enCours.add(cle)) return  // déjà en chargement : [fin] sera rappelé avec les autres
        pool.execute {
            val bmp = try {
                CachePochettes.lire(app, url)?.let { decoder(it, cotePx) } ?: run {
                    val octets = if (url.startsWith(RELAIS)) relayer(app, url.removePrefix(RELAIS)) else lire(url)
                    octets?.let { o -> decoder(o, cotePx)?.also { CachePochettes.ecrire(app, url, o) } }
                }
            } catch (_: Exception) { null }
            if (bmp != null) {
                echecs.remove(cle); cache.put(cle, bmp)
            } else {
                val n = (echecs[cle]?.first ?: 0) + 1
                echecs[cle] = n to (System.currentTimeMillis() + (ATTENTES_MS.getOrNull(n - 1) ?: Long.MAX_VALUE / 2))
            }
            val fins = synchronized(attentes) { enCours.remove(cle); attentes.remove(cle).orEmpty() }
            main.post { fins.forEach { it(bmp) } }
        }
    }

    /** La pochette lue par Home Assistant (référence qu'il a donnée) ; rien (pas d'image, ou Home Assistant trop ancien) : null. */
    private fun relayer(ctx: Context, ref: String): ByteArray? {
        val cfg = Config(ctx)
        val octets = Net.postBytes(ctx, cfg, CHEMIN_RELAIS, JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("ref", ref), 10_000)
            ?: return null
        if (octets.isEmpty() || octets.size > 2 * 1024 * 1024) return null
        return octets
    }

    private fun decoder(octets: ByteArray, cote: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(octets, 0, octets.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var ech = 1
        while (o.outWidth / (ech * 2) >= cote && o.outHeight / (ech * 2) >= cote) ech *= 2
        return BitmapFactory.decodeByteArray(octets, 0, octets.size, BitmapFactory.Options().apply { inSampleSize = ech })
    }

    private fun lire(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 6000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) return null
            return c.inputStream.use { it.readNBytesCompat(2 * 1024 * 1024) }
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

    /** 1.93.06 : fermeture du dernier écran Musique : la mémoire est rendue, le disque reste. */
    fun liberer() { cache.evictAll() }

    /** Fin de la Party : tout est déchargé (mémoire et disque). */
    fun vider(ctx: Context) {
        // Les vues qui attendent une image en cours de chargement restent prévenues (avant, elles ne l'étaient plus jamais).
        cache.evictAll(); echecs.clear()
        CachePochettes.vider(ctx)
    }
}
