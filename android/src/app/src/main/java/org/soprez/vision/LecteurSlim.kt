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
    /** 1.91.0 : où commence le flux en cours après un saut (le flux, lui, repart de zéro). */
    private val recalage = Saut.Recalage()
    private var suivant: MediaPlayer? = null
    private var volume = 1f
    /** Banc Musique : le volume logiciel appliqué au lecteur Android (règle du projet : toujours 1,0 sur un lecteur Vision). */
    val volumeApplique: Float get() = if (allume) volume else 0f
    /** Banc Musique : la position du titre en cours (ms), comme l'envoie le lecteur à Music Assistant. */
    val positionCourante: Int get() = positionMs()
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
        // 1.90.3 (télé d'essai, reliée par l'adresse externe : aucun lecteur créé) : l'adresse locale vérifiée, sinon on
        // la cherche (annonce du réseau + certificat de Home Assistant) ; le lecteur réessaie toutes les 30 s.
        c.hoteLocal.takeIf { it.isNotEmpty() && Reseau.estPrivee("http://$it") }?.let { return it }
        HoteLocal.chercher(ctx)
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
        // La position annoncée à Music Assistant reste celle du flux (il ajoute lui-même le saut) ; l'écran, lui, montre
        // la position dans le titre.
        return (positionMs() + recalage.decalageMs) / 1000.0
    }

    /** 1.91.0 : un saut vers [positionS] à [quand] (elapsedRealtime), si cet appareil est le lecteur (voir positionLocale). */
    fun saut(ctx: Context, ici: Boolean, lecteurNom: String, positionS: Double, quand: Long) {
        if (!connecte || (lecteurNom != nom(ctx) && !(ici && lecteurNom.endsWith(" · Vision")))) return
        recalage.saut(Math.round(positionS * 1000), quand, SystemClock.elapsedRealtime())
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
        // 1.90.4 (télé d'essai : lecteur « squeezeplay: 5a:7a:… » sans nom dans Music Assistant) : MA enregistre le
        // lecteur en tâche de fond juste après le HELO, et ignore le nom s'il arrive avant la fin (course selon la
        // vitesse de l'appareil). Le nom est donc renvoyé, sans qu'on le demande, une fois le lecteur connu de MA.
        for (delai in RENVOIS_NOM_MS) h?.postDelayed({ if (socket === s && connecte) envoyerNom(ctx) }, delai)
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

    /** Les renvois du nom après la connexion (ms). */
    val RENVOIS_NOM_MS = longArrayOf(3_000, 15_000)

    /** Le nom de ce lecteur, « <appareil> · Vision » (réponse SETD 0, ou renvoi spontané). */
    private fun envoyerNom(ctx: Context) {
        val n = nom(ctx).toByteArray(Charsets.UTF_8)
        envoyer("SETD", byteArrayOf(0) + n + byteArrayOf(0))
    }

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
        val horloge = if (horlogeDebut == 0L) null else
            ((if (horlogePause > 0L) horlogePause else SystemClock.elapsedRealtime()) - horlogeDebut).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return choisirPosition(p, horloge)
    }

    /**
     * La position à montrer : celle du lecteur d'Android, sauf si elle manque (flux sans taille : 0) ou s'écarte de plus
     * de [ECART_HORLOGE_MS] de l'horloge du titre (pauses déduites). Banc Musique (2026-10-12) : après une pause, le
     * lecteur d'Android sautait de +16 s (barre de la Party). Sans Android : testé en JVM.
     */
    fun choisirPosition(lecteurMs: Int, horlogeMs: Int?): Int = when {
        horlogeMs == null -> maxOf(0, lecteurMs)
        lecteurMs <= 0 -> horlogeMs
        Math.abs(lecteurMs - horlogeMs) > ECART_HORLOGE_MS -> horlogeMs
        else -> lecteurMs
    }

    const val ECART_HORLOGE_MS = 3_000

    private fun debutTitre() { horlogeDebut = SystemClock.elapsedRealtime(); horlogePause = 0L; recalage.nouveauFlux(horlogeDebut); debutsTitre++; dernierDebutMs = horlogeDebut; relireDuree() }

    /** Banc Musique : titres démarrés, heure (elapsedRealtime) du dernier début et de la dernière fin de son. */
    @Volatile var debutsTitre = 0; private set
    @Volatile var dernierDebutMs = 0L; private set
    @Volatile var derniereFinMs = 0L; private set

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
            "setd" -> if (d.size == 1 && d[0].toInt() == 0) envoyerNom(ctx)
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
        if (commande != 't') Log.d(TAG, "strm $commande (démarrage $autostart, position ${positionMs()} ms)")
        when (commande) {
            't' -> stat("STMt", battement)
            'p' -> {
                try { lecteur?.pause() } catch (_: Exception) {}
                if (horlogePause == 0L) horlogePause = SystemClock.elapsedRealtime()
                etat = "pause"; stat("STMp")
            }
            'u' -> {
                try { lecteur?.start() } catch (_: Exception) {}
                val reprise = horlogePause > 0L && horlogeDebut > 0L
                val nouveau = reponseAuDemarrage(chargeSansDemarrage = horlogeDebut == 0L && lecteur != null, enPause = reprise) == "STMs"
                if (reprise) { horlogeDebut += SystemClock.elapsedRealtime() - horlogePause; horlogePause = 0L }
                // Banc Musique (2026-10-12) : un titre chargé sans démarrage (STMl) puis lancé par « u » n'avait jamais son
                // début noté (position et premier son faux).
                else if (nouveau) debutTitre()
                etat = "lecture"
                // 1.93.05 (l'auteur, retour 40, boucle « Bad Times ») : un titre chargé sans démarrage puis lancé par « u » est un
                // titre qui COMMENCE — « STMs », comme squeezelite. Avant : « STMr » (reprise) ; Music Assistant ne voyait
                // jamais le titre commencer, gardait l'index d'avant (position figée à 5 s) et redonnait le même titre.
                stat(if (nouveau) "STMs" else "STMr")
            }
            'q' -> { h?.removeCallbacks(finSansSuite); h?.removeCallbacks(sansRelance); fonduSortie(); liberer(); depuisFlush = true; etat = "arrêt"; stat("STMf") }
            'f' -> { fonduSortie(); liberer(); depuisFlush = true; stat("STMf") }
            's' -> {
                val requete = String(d, 24, d.size - 24, Charsets.ISO_8859_1)
                val chemin = Regex("^GET (\\S+) HTTP").find(requete)?.groupValues?.get(1) ?: return
                // 1.93.05 (retour 40, endurance) : l'identifiant du flux demandé (fin du chemin de Music Assistant, sans hôte),
                // pour vérifier à chaque titre que le son décodé est celui que Music Assistant annonce.
                Log.d(TAG, "flux ${chemin.substringBefore('?').split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")}")
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
        h?.removeCallbacks(finSansSuite); h?.removeCallbacks(sansRelance)  // la suite est arrivée : pas de « plus de son »
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
                    if (auto) { fonduEntree(p); p.start(); debutTitre(); etat = "lecture"; stat("STMs") } else stat("STMl")
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
    /**
     * l'auteur (16/10, 1.93.0 : « un morceau s'est terminé et la lecture s'est arrêtée là ») : « décodeur prêt » (STMd) dit à
     * Music Assistant de préparer le titre suivant. Il partait 10 s avant la fin d'après la durée du lecteur d'Android,
     * inconnue pour un flux sans longueur (celui de Music Assistant : FluxUnique.getSize() = -1) : STMd ne partait qu'à
     * la fin, collé à STMu ; Music Assistant (aioslimproto) recevait « plus de son » avant d'avoir préparé la suite et
     * arrêtait la file. STMd part maintenant dès que le flux est entièrement reçu (le sens de STMd chez squeezelite),
     * la durée restant un secours. Pur (testé en JVM).
     */
    fun decodeurPretMaintenant(deja: Boolean, dureeMs: Int, positionMs: Int, fluxRecu: Boolean, dureeHaMs: Long = 0L): Boolean = when {
        deja -> false
        // 1.93.06 (l'auteur, retour 40) : STMd dès le flux reçu (une minute après le début) faisait passer Music Assistant au titre
        // suivant 64 s après le début (« Fine Day Anthem » 194 s → « BAM BAM » à 15:39:05) : titre, position et état
        // dérivaient, et le bouton Pause redevenait « Lecture ». Avec la durée du titre connue de HA : 15 s avant la fin.
        dureeHaMs > 0L -> dureeHaMs - positionMs <= AVANCE_STMD_MS
        else -> fluxRecu || (dureeMs > 0 && (dureeMs - positionMs) in 0..10_000)
    }

    /**
     * La réponse à « u » (démarrer / reprendre). Un titre chargé sans démarrage (STMl, « démarrage 2 » de Music Assistant
     * après une relance) qui part : « STMs » (titre démarré, comme squeezelite) ; une pause qui reprend : « STMr ». Pur.
     */
    fun reponseAuDemarrage(chargeSansDemarrage: Boolean, enPause: Boolean): String =
        if (chargeSansDemarrage && !enPause) "STMs" else "STMr"

    /** Retour 40 : « décodeur prêt » part tant d'avance sur la fin réelle (Music Assistant prépare la suite en ~1,5 s). */
    const val AVANCE_STMD_MS = 15_000L

    /** La durée du titre en cours d'après Home Assistant (ms), 0 si inconnue ; relue au début de chaque titre. */
    @Volatile private var dureeHaMs = 0L

    /** La durée (s) du titre que joue le lecteur de CET appareil, d'après HA ; null si inconnue. Remplaçable (essais). */
    @Volatile var lireDuree: (Context) -> Double? = { c ->
        try {
            val cfg = Config(c)
            val e = Party.analyser(Net.post(c, cfg, "/api/pc_parental/party/etat", org.json.JSONObject().put("id", cfg.id).put("secret", cfg.secret), 8_000))
            if (e.ici && LecteurMusique.positionLocale(c, e.ici, e.lecteurNom) != null) e.maintenant.duree else null
        } catch (_: Exception) { null }
    }

    /** Au début d'un titre : sa durée selon HA, relue une fois que Music Assistant a pris le nouveau titre (2 s). */
    private fun relireDuree() {
        dureeHaMs = 0L
        val c = ctxApp ?: return
        val debut = debutsTitre
        h?.postDelayed({
            Thread {
                val d = lireDuree(c)
                if (debutsTitre == debut && d != null && d > 1.0) dureeHaMs = (d * 1000).toLong()
            }.apply { isDaemon = true }.start()
        }, 2_000)
    }

    /** À la fin d'un titre sans suite prête : le temps laissé à Music Assistant pour envoyer la suite avant STMu. */
    const val ATTENTE_SUITE_MS = 4_000L

    /** Après une demande de suite à HA : le temps laissé à Music Assistant pour envoyer le titre demandé. */
    const val ATTENTE_RELANCE_MS = 8_000L

    /**
     * l'auteur (17/10, point 25 : « un morceau s'est terminé et le suivant ne s'est pas lancé », 1.93.03, toujours « en cours »
     * bloqué à 4 s) : Music Assistant n'envoie le titre suivant qu'une fois, et seulement si le lecteur est alors « en
     * lecture » (file, `_enqueue_next_item_on_player`) ; s'il y renonce, rien ne le relance, et une file qui a encore des
     * titres reste à l'arrêt après STMu. Avant de dire « plus de son », on demande donc à HA le titre suivant (action
     * `suite`, sur le lecteur de l'appareil) ; STMu ne part que si HA refuse (fin de file) ou si rien n'arrive.
     * Remplaçable pour les essais.
     */
    @Volatile var demanderSuite: (Context) -> Boolean = { c ->
        try {
            val cfg = Config(c)
            Net.post(c, cfg, "/api/pc_parental/veille/musique",
                org.json.JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("action", "suite"), 9_000).optBoolean("relance")
        } catch (_: Exception) { false }
    }

    private val sansRelance = Runnable { if (lecteur == null && etat == "arrêt") stat("STMu") }

    private val finSansSuite = Runnable {
        if (lecteur != null || etat != "arrêt") return@Runnable
        val c = ctxApp ?: run { stat("STMu"); return@Runnable }
        Thread {
            val relance = demanderSuite(c)
            h?.post {
                if (lecteur != null || etat != "arrêt") return@post
                if (relance) { h?.removeCallbacks(sansRelance); h?.postDelayed(sansRelance, ATTENTE_RELANCE_MS) } else stat("STMu")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun surveillerFin() {
        val mp = lecteur ?: return
        h?.postDelayed(object : Runnable {
            override fun run() {
                if (lecteur !== mp) return
                val duree = try { mp.duration } catch (_: Exception) { -1 }
                val position = try { mp.currentPosition } catch (_: Exception) { 0 }
                if (decodeurPretMaintenant(decodeurPret, duree, position, (sources[mp] as? FluxUnique)?.telecharge == true, dureeHaMs)) { decodeurPret = true; stat("STMd") }
                if (etat == "lecture") stat("STMt")
                h?.postDelayed(this, 1_000)
            }
        }, 1_000)
    }

    private fun fin(fini: MediaPlayer) {
        if (fini !== lecteur) return
        derniereFinMs = SystemClock.elapsedRealtime()
        val n = suivant
        suivant = null
        relacher(fini)
        if (n != null) {
            lecteur = n; decodeurPret = false; debutTitre(); etat = "lecture"
            stat("STMs"); surveillerFin()
        } else {
            lecteur = null
            etat = "arrêt"
            // Pas encore de suite : STMd (si pas déjà dit), puis STMu seulement si rien n'arrive (voir decodeurPretMaintenant).
            if (!decodeurPret) stat("STMd")
            h?.removeCallbacks(finSansSuite); h?.postDelayed(finSansSuite, ATTENTE_SUITE_MS)
        }
    }

    private fun appliquerVolume() {
        val v = if (allume) volume else 0f
        try { lecteur?.setVolume(v, v) } catch (_: Exception) {}
        try { suivant?.setVolume(v, v) } catch (_: Exception) {}
    }

    /**
     * l'auteur (16/10, « un petit cliquetis » à chaque saut) : couper net un son qui joue fait un clic. Avant un arrêt (saut,
     * titre changé à la main), le volume descend en 60 ms ; un nouveau flux qui part de lui-même monte en 150 ms.
     */
    private fun fonduSortie() {
        val m = lecteur ?: return
        if (etat != "lecture") return
        val cible = if (allume) volume else 0f
        for (k in 3 downTo 0) { try { m.setVolume(cible * k / 4f, cible * k / 4f) } catch (_: Exception) { return }; SystemClock.sleep(15) }
    }

    private fun fonduEntree(p: MediaPlayer) {
        val cible = if (allume) volume else 0f
        try { p.setVolume(0f, 0f) } catch (_: Exception) { return }
        for (k in 1..5) h?.postDelayed({ if (lecteur === p) try { p.setVolume(cible * k / 5f, cible * k / 5f) } catch (_: Exception) {} }, k * 30L)
    }

    private fun liberer(except: MediaPlayer? = null) {
        for (m in listOf(lecteur, suivant)) if (m != null && m !== except) relacher(m)
        if (lecteur !== except) { lecteur = null; horlogeDebut = 0L; horlogePause = 0L }
        if (suivant !== except) suivant = null
    }
}
