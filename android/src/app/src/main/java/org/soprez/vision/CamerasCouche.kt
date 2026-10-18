// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.ParsingLoadable
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.net.ssl.HttpsURLConnection

/**
 * Les flux vidéo des caméras, en direct, posés par-dessus le dessin de
 * l'écran de veille. Chaque case reçoit un lecteur HLS (Home Assistant
 * remuxe la caméra en fMP4, ExoPlayer lit) ; tant que la première image
 * n'est pas là, la case reste transparente et le dessin montre
 * l'instantané du dessous.
 */
class CamerasCouche(ctx: Context) : FrameLayout(ctx) {

    private class Lecteur(val id: String) {
        var texture: TextureView? = null
        var joueur: ExoPlayer? = null
        var rendu = false
        var echec = false
        var vw = 0
        var vh = 0
        var ouvertA = 0L
        var dernierRendu = 0L
        var dernierePosition = -1L
    }

    private val main = Handler(Looper.getMainLooper())
    private val lecteurs = LinkedHashMap<String, Lecteur>()
    private val coins = object : android.view.ViewOutlineProvider() {
        override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, rayon) }
    }
    private var cfg: Config? = null

    /** Vrai si cette caméra affiche de la vidéo : le dessin peut alors cacher son instantané. */
    fun enDirect(id: String): Boolean = lecteurs[id]?.let { it.rendu && !it.echec } ?: false

    /** Le format (largeur / hauteur) de la vidéo, connu une fois le flux ouvert. */
    fun format(id: String): Float? = lecteurs[id]?.let { if (it.vw > 0 && it.vh > 0) it.vw.toFloat() / it.vh else null }

    /** Place (ou crée) les lecteurs des caméras données, dans les rectangles donnés, à l'opacité donnée. */
    /** Le rayon des coins de la vidéo, le même que la case dessinée dessous. */
    var rayon = 0f

    fun montrer(cameras: List<Pair<String, RectF>>, alpha: Float, rayonCoins: Float = rayon) {
        if (cfg == null) cfg = Config(context)
        rayon = rayonCoins
        val voulus = cameras.map { it.first }.toSet()
        // Les autres lecteurs restent ouverts (prêts pour leur tour), simplement invisibles.
        lecteurs.values.filter { it.id !in voulus }.forEach { it.texture?.alpha = 0f }
        cameras.forEach { (id, rect) ->
            val l = lecteurs.getOrPut(id) { Lecteur(id).also { ouvrir(it) } }
            l.texture?.let { tv ->
                val lw = rect.width().toInt().coerceAtLeast(1); val lh = rect.height().toInt().coerceAtLeast(1)
                val lp = tv.layoutParams as? LayoutParams
                if (lp == null || lp.width != lw || lp.height != lh) { tv.layoutParams = LayoutParams(lw, lh); if (l.vw > 0) ajuster(l) }
                tv.x = rect.left; tv.y = rect.top
                // La vidéo est découpée aux coins ronds de sa case, dès la première image.
                if (tv.outlineProvider !== coins) { tv.outlineProvider = coins; tv.clipToOutline = true } else if (lp == null || lp.width != lw || lp.height != lh) tv.invalidateOutline()
                tv.alpha = if (l.rendu && !l.echec) alpha else 0f
            }
        }
        visibility = if (cameras.isEmpty()) GONE else VISIBLE
    }

    /** Ouvre les flux à l'avance (le tableau d'avant), pour que la vidéo soit là dès l'arrivée des caméras. */
    fun prechauffer(ids: List<String>) {
        if (cfg == null) cfg = Config(context)
        ids.forEach { id -> lecteurs.getOrPut(id) { Lecteur(id).also { ouvrir(it) } } }
        if (lecteurs.isNotEmpty()) visibility = VISIBLE
    }

    fun actifs(): Boolean = lecteurs.isNotEmpty()

    /** Veille-lot : une seule caméra en direct à la fois — les autres flux sont fermés (pas seulement cachés). */
    fun garderSeulement(ids: Set<String>) {
        lecteurs.keys.filter { it !in ids }.forEach { fermer(it) }
        if (lecteurs.isEmpty()) { main.removeCallbacks(garde); visibility = GONE }
    }

    /** Veille-lot : le flux de cette caméra a échoué (la case retombe sur son image). */
    fun echoue(id: String): Boolean = lecteurs[id]?.echec == true

    /** Entre deux tableaux caméras : tout reste ouvert et chaud, mais rien ne se voit. */
    fun masquer() {
        lecteurs.values.forEach { it.texture?.alpha = 0f }
    }

    /** À l'arrêt de l'écran de veille : tout fermer. */
    fun cacher() {
        main.removeCallbacks(garde)
        lecteurs.keys.toList().forEach { fermer(it) }
        visibility = GONE
    }

    /** Le garde-fou : un flux qui n'a rien rendu depuis 25 s, ou raté depuis 30 s, est rouvert. */
    private val garde = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            lecteurs.values.toList().forEach { l ->
                // ExoPlayer garde le TextureView pour lui : on juge la vie du flux à sa position qui avance.
                val pos = try { l.joueur?.currentPosition ?: -1L } catch (_: Exception) { -1L }
                if (pos != l.dernierePosition) { l.dernierePosition = pos; l.dernierRendu = now }
                val gele = l.rendu && now - l.dernierRendu > 25_000
                val mort = l.echec && now - l.ouvertA > 30_000
                val muet = !l.rendu && !l.echec && now - l.ouvertA > 90_000
                if (gele || mort || muet) { val id = l.id; fermer(id); lecteurs[id] = Lecteur(id).also { ouvrir(it) } }
            }
            if (lecteurs.isNotEmpty()) main.postDelayed(this, 10_000)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun ouvrir(l: Lecteur) {
        val tv = TextureView(context).apply { alpha = 0f; isOpaque = false }
        l.texture = tv
        l.ouvertA = SystemClock.uptimeMillis()
        main.removeCallbacks(garde); main.postDelayed(garde, 10_000)
        addView(tv, LayoutParams(1, 1))
        // L'adresse du flux se demande à Home Assistant, hors du fil principal.
        Thread {
            val c = cfg ?: return@Thread
            val url = try {
                val r = Net.post(context, c, "/api/pc_parental/veille/flux",
                    JSONObject().put("id", c.id).put("secret", c.secret).put("entite", l.id), 15_000)
                val chemin = r.optString("url", "")
                if (chemin.isEmpty()) "" else if (chemin.startsWith("http")) chemin else c.urlActive.ifEmpty { c.adresses().first() } + chemin
            } catch (_: Exception) { "" }
            main.post {
                if (lecteurs[l.id] !== l) return@post
                if (url.isEmpty()) { l.echec = true; return@post }
                try {
                    // Le lecteur passe par HttpsURLConnection : il doit connaître nos racines (Let's Encrypt embarqué).
                    HttpsURLConnection.setDefaultSSLSocketFactory(Net.fabriqueSsl(context))
                    // La télé n'a souvent qu'un seul décodeur matériel : les autres flux passent en logiciel.
                    val rendus = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                        // Décodeurs logiciels d'abord : le décodeur matériel de la télé n'accepte qu'un flux, et le refuse même parfois.
                        .setMediaCodecSelector { mime, secure, tunneling ->
                            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                            // Le décodeur matériel Realtek (TCL) n'accepte qu'un flux : logiciel d'abord là-bas, matériel ailleurs.
                            if (infos.any { it.name.contains("realtek", ignoreCase = true) }) infos.sortedBy { it.hardwareAccelerated } else infos
                        }
                    val p = ExoPlayer.Builder(context, rendus).build()
                    l.joueur = p
                    p.volume = 0f
                    p.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { l.rendu = true; l.dernierRendu = SystemClock.uptimeMillis() }
                        override fun onPlayerError(error: PlaybackException) { l.echec = true }
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) { l.vw = videoSize.width; l.vh = videoSize.height; ajuster(l) }
                        }
                    })
                    p.setVideoTextureView(tv)
                    p.setMediaSource(sourceHls(url))
                    p.playWhenReady = true
                    p.prepare()
                } catch (_: Exception) { l.echec = true }
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * La source HLS, sans la basse latence : Home Assistant publie des listes LL-HLS (parties par
     * plages d'octets) que le lecteur suit mal ; on ne garde que les segments entiers.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun sourceHls(url: String): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Vision-Android/" + BuildConfigCompat.version(context))
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
        val fabrique = object : HlsPlaylistParserFactory {
            override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = SansBasseLatence(HlsPlaylistParser())
            override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?): ParsingLoadable.Parser<HlsPlaylist> =
                SansBasseLatence(HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist))
        }
        return HlsMediaSource.Factory(http).setPlaylistParserFactory(fabrique).createMediaSource(MediaItem.fromUri(url))
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private class SansBasseLatence(private val interne: ParsingLoadable.Parser<HlsPlaylist>) : ParsingLoadable.Parser<HlsPlaylist> {
        private val retirees = listOf("#EXT-X-PART", "#EXT-X-PRELOAD-HINT", "#EXT-X-SERVER-CONTROL", "#EXT-X-RENDITION-REPORT", "#EXT-X-SKIP")
        override fun parse(uri: android.net.Uri, inputStream: InputStream): HlsPlaylist {
            val lignes = inputStream.bufferedReader(Charsets.UTF_8).readText().lines()
            val garde = lignes.filterNot { l -> retirees.any { l.startsWith(it) } }
            return interne.parse(uri, ByteArrayInputStream(garde.joinToString("\n").toByteArray(Charsets.UTF_8)))
        }
    }

    /** Garde les proportions de la vidéo dans la case : l'image entière, jamais recadrée. */
    private fun ajuster(l: Lecteur) {
        val tv = l.texture ?: return
        val vw = l.vw; val vh = l.vh
        if (vw <= 0 || vh <= 0) return
        tv.post {
            val w = tv.width.toFloat(); val h = tv.height.toFloat()
            if (w <= 0 || h <= 0) return@post
            val echelle = minOf(w / vw, h / vh)
            val m = Matrix()
            m.setScale(vw * echelle / w, vh * echelle / h, w / 2, h / 2)
            tv.setTransform(m)
        }
    }

    private fun fermer(id: String) {
        val l = lecteurs.remove(id) ?: return
        try { l.joueur?.clearVideoTextureView(l.texture); l.joueur?.release() } catch (_: Exception) {}
        l.joueur = null
        l.texture?.let { removeView(it) }
        l.texture = null
    }
}
