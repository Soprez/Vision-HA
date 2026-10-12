// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer

/**
 * 1.86.11 (l'auteur : « on doit rester toujours dans notre lecteur ») : Vision est lui-même un lecteur de Music Assistant sur
 * la télé, par le protocole SlimProto (« Squeezelite », fournisseur intégré à Music Assistant). Le son sort de Vision :
 * jamais de récepteur Cast qui prend l'écran.
 *
 * Fonctionnement : une liaison TCP vers le serveur (port 3483) ; Music Assistant envoie des commandes (« strm » :
 * démarrer / pause / reprise / arrêt / battement, « audg » : volume, « aude » : marche) ; Vision lit le flux HTTP
 * indiqué avec le lecteur d'Android et répond par des « STAT » (connecté, démarré, pause, fin…). Le titre suivant est
 * préparé à l'avance (gapless) quand Music Assistant l'envoie.
 */
object LecteurSlim {
    private const val TAG = "VisionLecteur"
    private const val PORT = 3483

    @Volatile var connecte = false; private set
    @Volatile var etat = "arrêt"; private set
    @Volatile private var fil: Thread? = null
    @Volatile private var socket: Socket? = null
    private var sortie: OutputStream? = null
    private val verrou = Any()
    private var fond: HandlerThread? = null
    private var h: Handler? = null
    private var lecteur: MediaPlayer? = null
    // 1.88.0 (l'auteur : progression bloquée à 0:00 sur le Chromecast) : l'horloge du titre en cours, pour quand le lecteur
    // Android ne donne pas sa position (flux sans taille connue : currentPosition reste à 0). Repart à chaque titre.
    @Volatile private var horlogeDebut = 0L
    @Volatile private var horlogePause = 0L
    private var suivant: MediaPlayer? = null
    private var volume = 1f
    private var allume = true
    private var depuisFlush = true
    private var decodeurPret = false
    private var ctxApp: Context? = null
    /** 1.87.2 : le tampon de flux de chaque lecteur (une seule connexion vers Music Assistant), fermé avec lui. */
    private val sources = HashMap<MediaPlayer, java.io.Closeable>()

    /** Le serveur à joindre : l'adresse de Home Assistant sur le réseau de la maison (Music Assistant tourne à côté). */
    fun serveur(ctx: Context): String? {
        val c = Config(ctx)
        for (u in listOf(c.urlInterne, c.urlActive)) {
            val hote = try { URI(u).host } catch (_: Exception) { null }
            if (!hote.isNullOrEmpty() && Reseau.estPrivee("http://$hote")) return hote
        }
        return null
    }

    /**
     * 1.87.1 : la position (en secondes) du titre que joue ce lecteur, quand l'appareil est lui-même le lecteur de la
     * Party ([ici] et lecteur « · Vision » de cet appareil) ; null sinon (rien ne joue ici, ou un autre lecteur).
     */
    fun positionLocale(ctx: Context, ici: Boolean, lecteurNom: String): Double? {
        if (!connecte || (etat != "lecture" && etat != "pause")) return null
        if (lecteurNom != nom(ctx) && !(ici && lecteurNom.endsWith(" · Vision"))) return null
        if (lecteur == null) return null
        return positionMs() / 1000.0
    }

    /** Le nom affiché dans Music Assistant : celui de l'appareil, avec « · Vision ». */
    fun nom(ctx: Context): String = (Config(ctx).nom.ifEmpty { "Télé" }) + " · Vision"

    @Synchronized
    fun demarrer(ctx: Context, hote: String? = null) {
        if (fil?.isAlive == true) return
        ctxApp = ctx.applicationContext
        if (fond == null) fond = HandlerThread("VisionLecteurSon").also { it.start(); h = Handler(it.looper) }
        val t = Thread { boucle(ctx.applicationContext, hote) }
        t.isDaemon = true; fil = t; t.start()
    }

    @Synchronized
    fun arreter() {
        fil?.interrupt(); fil = null
        try { socket?.close() } catch (_: Exception) {}
        h?.post { liberer() }
    }

    private fun boucle(ctx: Context, hoteForce: String?) {
        val moi = Thread.currentThread()
        while (!moi.isInterrupted && fil === moi) {
            val hote = hoteForce ?: serveur(ctx)
            if (hote != null) try { session(ctx, hote) } catch (e: Exception) { Log.i(TAG, "liaison coupée : ${e.message}") }
            connecte = false
            if (moi.isInterrupted || fil !== moi) return
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { return }
        }
    }

    private fun session(ctx: Context, hote: String) {
        val s = Socket()
        s.connect(InetSocketAddress(hote, PORT), 8_000)
        s.tcpNoDelay = true
        socket = s
        synchronized(verrou) { sortie = s.getOutputStream() }
        envoyer("HELO", helo(ctx))
        connecte = true
        Log.i(TAG, "relié à Music Assistant ($hote:$PORT)")
        val entree = DataInputStream(s.getInputStream().buffered())
        while (!Thread.currentThread().isInterrupted) {
            val longueur = entree.readUnsignedShort()
            val cmd = ByteArray(4); entree.readFully(cmd)
            val data = ByteArray(longueur - 4); entree.readFully(data)
            val c = String(cmd, Charsets.US_ASCII)
            h?.post { try { traiter(ctx, c, data, hote) } catch (e: Exception) { Log.w(TAG, "commande $c ignorée : $e") } }
        }
    }

    // --------------------------------------------------------------- messages vers Music Assistant

    private fun envoyer(op: String, payload: ByteArray) {
        val b = ByteBuffer.allocate(8 + payload.size)
        b.put(op.toByteArray(Charsets.US_ASCII)); b.putInt(payload.size); b.put(payload)
        synchronized(verrou) { try { sortie?.write(b.array()); sortie?.flush() } catch (_: Exception) {} }
    }

    private fun helo(ctx: Context): ByteArray {
        val mac = adresse(ctx)
        val caps = "Model=squeezelite,ModelName=Vision,Firmware=Vision-${BuildConfigCompat.version(ctx)},MaxSampleRate=48000,mp3"
        val b = ByteBuffer.allocate(36 + caps.length)
        b.put(12.toByte()); b.put(0.toByte()); b.put(mac)
        b.put(ByteArray(16))          // uuid
        b.putShort(0)                 // wlan
        b.putLong(0)                  // octets reçus
        b.put("fr".toByteArray())     // langue
        b.put(caps.toByteArray(Charsets.US_ASCII))
        return b.array()
    }

    /** Une adresse matérielle fixe pour cet appareil (Music Assistant reconnaît ainsi le lecteur d'une fois à l'autre). */
    fun adresse(ctx: Context): ByteArray {
        val p = ctx.getSharedPreferences("lecteur", 0)
        var hex = p.getString("mac", "") ?: ""
        if (hex.length != 12) {
            val r = java.security.SecureRandom(); val o = ByteArray(6); r.nextBytes(o)
            o[0] = ((o[0].toInt() and 0xFC) or 0x02).toByte()  // adresse « locale », jamais celle d'un vrai matériel
            hex = o.joinToString("") { "%02x".format(it) }
            p.edit().putString("mac", hex).apply()
        }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** La position du titre en cours (ms) : celle du lecteur Android, sinon l'horloge du titre (pauses déduites). */
    private fun positionMs(): Int {
        val mp = lecteur ?: return 0
        val p = try { mp.currentPosition } catch (_: Exception) { 0 }
        if (p > 0) return p
        if (horlogeDebut == 0L) return 0
        val fin = if (horlogePause > 0L) horlogePause else SystemClock.elapsedRealtime()
        return (fin - horlogeDebut).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun debutTitre() { horlogeDebut = SystemClock.elapsedRealtime(); horlogePause = 0L }

    private fun stat(evenement: String, battement: Int = 0) {
        val pos = positionMs()
        val b = ByteBuffer.allocate(4 + 47)
        b.put(evenement.toByteArray(Charsets.US_ASCII))
        b.put(0); b.put(0); b.put(0)          // crlf, mas
        b.putInt(0); b.putInt(0)               // pointeurs tampon
        b.putInt(0); b.putInt(0)               // octets reçus
        b.putShort(0)                          // signal
        b.putInt((SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt())  // jiffies
        b.putInt(0); b.putInt(0)               // tampon de sortie
        b.putInt(pos / 1000)                   // secondes écoulées
        b.putShort(0)                          // tension
        b.putInt(pos)                          // millisecondes écoulées
        b.putInt(battement)                    // battement du serveur
        envoyer("STAT", b.array())
    }

    // --------------------------------------------------------------- commandes de Music Assistant

    private fun traiter(ctx: Context, c: String, d: ByteArray, hote: String) {
        when (c) {
            "strm" -> strm(ctx, d, hote)
            // 1.87.2 (l'auteur : « le volume est géré par la TV ») : le volume logiciel reste à 100 % ; c'est la télécommande qui
            // règle le son. Le gain envoyé par Music Assistant (10 % sur un lecteur recréé : son quasi nul) est ignoré.
            "audg" -> { volume = 1f; appliquerVolume() }
            "aude" -> if (d.isNotEmpty()) { allume = d[0].toInt() != 0; appliquerVolume() }
            "setd" -> if (d.size == 1 && d[0].toInt() == 0) {
                val n = nom(ctx).toByteArray(Charsets.UTF_8)
                envoyer("SETD", byteArrayOf(0) + n + byteArrayOf(0))
            }
            else -> {}  // vers, cont, codc, grfe… : rien à faire (le flux est lu par le lecteur d'Android)
        }
    }

    private fun strm(ctx: Context, d: ByteArray, hote: String) {
        if (d.size < 24) return
        val b = ByteBuffer.wrap(d)
        val commande = b.get().toInt().toChar()
        val autostart = b.get().toInt().toChar()
        // « !cc5sBcBcBBBLHL » : commande, démarrage, codec (5), seuil, spdif, durée et type de transition, drapeaux,
        // seuil de sortie, réservé, battement / gain (4), port (2), adresse (4), puis la requête HTTP.
        val drapeaux = d[11].toInt()
        b.position(14)
        val battement = b.int
        val port = b.short.toInt() and 0xFFFF
        val ip = b.int
        when (commande) {
            't' -> stat("STMt", battement)
            'p' -> {
                try { lecteur?.pause() } catch (_: Exception) {}
                if (horlogePause == 0L) horlogePause = SystemClock.elapsedRealtime()
                etat = "pause"; stat("STMp")
            }
            'u' -> {
                try { lecteur?.start() } catch (_: Exception) {}
                if (horlogePause > 0L && horlogeDebut > 0L) { horlogeDebut += SystemClock.elapsedRealtime() - horlogePause; horlogePause = 0L }
                etat = "lecture"; stat("STMr")
            }
            'q' -> { liberer(); depuisFlush = true; etat = "arrêt"; stat("STMf") }
            'f' -> { liberer(); depuisFlush = true; stat("STMf") }
            's' -> {
                val requete = String(d, 24, d.size - 24, Charsets.ISO_8859_1)
                val chemin = Regex("^GET (\\S+) HTTP").find(requete)?.groupValues?.get(1) ?: return
                val hoteHttp = if (ip != 0) "%d.%d.%d.%d".format(ip ushr 24 and 255, ip ushr 16 and 255, ip ushr 8 and 255, ip and 255) else hote
                val https = (drapeaux and 0x20) != 0
                val url = (if (https) "https://" else "http://") + hoteHttp + (if (port != 0 && port != 80 && port != 443) ":$port" else "") + chemin
                ouvrir(url, autostart == '1' || autostart == '3')
            }
            else -> {}
        }
    }

    private fun nouveauLecteur(url: String): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        // 1.87.2 : une seule connexion vers Music Assistant (FluxUnique) ; avant Android 6, l'adresse comme avant.
        val dossier = ctxApp?.cacheDir
        if (android.os.Build.VERSION.SDK_INT >= 23 && dossier != null) {
            val src = FluxUnique(url, dossier)
            sources[this] = src
            setDataSource(src)
        } else setDataSource(url)
        setVolume(if (allume) volume else 0f, if (allume) volume else 0f)
    }

    /** Libère un lecteur et ferme son tampon de flux. */
    private fun relacher(m: MediaPlayer) {
        try { m.release() } catch (_: Exception) {}
        try { sources.remove(m)?.close() } catch (_: Exception) {}
    }

    /** Un nouveau flux : remplace (après un « flush ») ou se met à la suite du titre en cours (enchaînement sans blanc). */
    private fun ouvrir(url: String, auto: Boolean) {
        stat("STMc")
        val enchainer = !depuisFlush && lecteur != null && etat == "lecture"
        depuisFlush = false
        try {
            val mp = nouveauLecteur(url)
            mp.setOnPreparedListener { p ->
                if (enchainer && lecteur != null && lecteur !== p) {
                    try { lecteur?.setNextMediaPlayer(p) } catch (_: Exception) {}
                    suivant = p
                } else {
                    lecteur = p
                    decodeurPret = false
                    if (auto) { p.start(); debutTitre(); etat = "lecture"; stat("STMs") } else stat("STMl")
                    surveillerFin()
                }
            }
            mp.setOnCompletionListener { fini -> fin(fini) }
            mp.setOnErrorListener { _, quoi, extra -> Log.w(TAG, "lecture impossible ($quoi/$extra) : $url"); stat("STMn"); true }
            mp.prepareAsync()
            if (!enchainer) { liberer(except = mp) }
        } catch (e: Exception) {
            Log.w(TAG, "flux refusé : ${e.message}"); stat("STMn")
        }
    }

    /** 10 s avant la fin du titre : « décodeur prêt » — Music Assistant envoie alors le suivant, préparé à l'avance. */
    private fun surveillerFin() {
        val mp = lecteur ?: return
        h?.postDelayed(object : Runnable {
            override fun run() {
                if (lecteur !== mp) return
                val reste = try { mp.duration - mp.currentPosition } catch (_: Exception) { -1 }
                if (!decodeurPret && mp.duration > 0 && reste in 0..10_000) { decodeurPret = true; stat("STMd") }
                if (etat == "lecture") stat("STMt")
                h?.postDelayed(this, 1_000)
            }
        }, 1_000)
    }

    private fun fin(fini: MediaPlayer) {
        if (fini !== lecteur) return
        val n = suivant
        suivant = null
        relacher(fini)
        if (n != null) {
            lecteur = n; decodeurPret = false; debutTitre(); etat = "lecture"
            stat("STMs"); surveillerFin()
        } else {
            lecteur = null
            if (!decodeurPret) stat("STMd")
            etat = "arrêt"; stat("STMu")
        }
    }

    private fun appliquerVolume() {
        val v = if (allume) volume else 0f
        try { lecteur?.setVolume(v, v) } catch (_: Exception) {}
        try { suivant?.setVolume(v, v) } catch (_: Exception) {}
    }

    private fun liberer(except: MediaPlayer? = null) {
        for (m in listOf(lecteur, suivant)) if (m != null && m !== except) relacher(m)
        if (lecteur !== except) { lecteur = null; horlogeDebut = 0L; horlogePause = 0L }
        if (suivant !== except) suivant = null
    }
}
