// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Client Sendspin natif (lot 3) : le son. Les morceaux horodatés de Music Assistant sont décodés (FLAC par MediaCodec,
 * ou PCM tel quel) puis écrits dans un AudioTrack, chacun à son heure (voir [SendspinSynchro]).
 *
 * Règle de l'auteur (2026-10-12) : « l'appli ne gère pas le volume ; le volume de l'appli est à 100 %, après c'est la télé
 * qui gère ». La sortie est TOUJOURS à 1,0 et jamais muette ; aucune commande de volume ne l'atteint.
 *
 * Fils : le décodage sur « sendspin-decodage », l'écriture (bloquante, elle cadence la sortie) sur « sendspin-son ».
 * Un numéro de génération écarte ce qui reste d'un flux vidé (`stream/clear`) ou fini (`stream/end`).
 */
class SendspinSortie(private val horloge: SendspinHorloge, private val retardMs: () -> Int) {
    data class Format(val codec: String, val debit: Int, val canaux: Int, val bits: Int, val entete: ByteArray?)

    private class Bloc(val generation: Int, val ts: Long, val pcm: ShortArray)

    /** Mesures pour le journal et les essais. */
    class Mesures {
        @Volatile var ecartUs = 0L
        @Volatile var recalages = 0
        @Volatile var corrections = 0L
        @Volatile var blocsEnRetard = 0
        @Volatile var sousAlimentations = 0
        @Volatile var premierSonUs = 0L
        @Volatile var tamponMs = 0L
        /** Trous dans la ligne de temps du serveur entre deux blocs d'un même flux (un enchaînement sans blanc n'en a aucun). */
        @Volatile var trous = 0
        @Volatile var trouMaxMs = 0L
        // Banc Musique (2026-10-12) : ce qui sort vraiment, pour les essais de bout en bout.
        /** Niveau RMS (dBFS) du dernier bloc écrit ; -120 pour un silence numérique. */
        @Volatile var niveauDb = -120.0
        /** Heure locale (µs, [maintenantUs]) jusqu'à laquelle du son non silencieux est programmé à la sortie. */
        @Volatile var sonJusquaUs = 0L
        /** Heure locale (µs) prévue pour le premier bloc non silencieux depuis le dernier début de flux. */
        @Volatile var premierSonFluxUs = 0L
        /** Débuts de flux (`stream/start` après un arrêt ou un vidage) et heure locale du dernier (µs). */
        @Volatile var debutsFlux = 0
        @Volatile var debutFluxUs = 0L
        @Volatile var tramesEcrites = 0L
        /** Sous-alimentations comptées par l'AudioTrack lui-même (Android 7+) : de vrais trous à la sortie. */
        @Volatile var sousAlimentationsPiste = 0
        /** Blocs mis en attente de la synchro de l'horloge, et silences coupés en demi-secondes (banc Musique). */
        @Volatile var attentesHorloge = 0
        @Volatile var silencesBornes = 0
        /** Recalages d'un flux livré en retard ([SendspinSynchro.reancrage]) et décalage en cours (µs) du flux joué. */
        @Volatile var reancrages = 0
        @Volatile var decalageUs = 0L
        /** Décodeurs FLAC rouverts après une erreur ou une ouverture ratée. */
        @Volatile var decodeurRelances = 0
    }
    val mesures = Mesures()

    @Volatile private var format: Format? = null
    @Volatile private var generation = 0
    private val blocs = LinkedBlockingDeque<Bloc>()
    private val decodage = android.os.HandlerThread("sendspin-decodage").apply { start() }
    private val hd = android.os.Handler(decodage.looper)
    private var decodeur: MediaCodec? = null
    private val enAttente = ArrayDeque<Long>()
    private var finAttendue = Long.MIN_VALUE
    @Volatile private var fil: Thread? = null
    @Volatile var actif = false; private set
    /** Un `stream/clear` a eu lieu : le prochain `stream/start` est un nouveau début (mesures). */
    @Volatile private var attenteDebut = false

    /** `stream/start` : nouveau flux, ou nouveau format sur le flux en cours (suite sans blanc sur la même ligne de temps). */
    fun demarrer(f: Format) {
        val ancien = format
        if (ancien == null || attenteDebut) { mesures.debutsFlux++; mesures.debutFluxUs = maintenantUs(); mesures.premierSonFluxUs = 0L; attenteDebut = false }
        format = f
        hd.post {
            if (ancien == null || ancien.codec != f.codec || ancien.debit != f.debit || ancien.canaux != f.canaux || !ancien.entete.contentEquals(f.entete)) {
                fermerDecodeur(); if (f.codec == "flac") ouvrirDecodeur(f)
            }
        }
        actif = true
        if (fil?.isAlive != true) fil = Thread({ boucle() }, "sendspin-son").apply { isDaemon = true; start() }
    }

    /** `stream/clear` : tout ce qui est en attente part ; la suite arrive avec de nouveaux horodatages. */
    fun vider() {
        attenteDebut = true
        generation++
        blocs.clear()
        hd.post { try { decodeur?.flush() } catch (_: Exception) {}; enAttente.clear(); finAttendue = Long.MIN_VALUE }
    }

    /** `stream/end` : la sortie s'arrête et se vide (la spec l'exige). */
    fun arreter() {
        actif = false
        vider()
        format = null
        hd.post { fermerDecodeur() }
    }

    fun liberer() { arreter(); fil?.interrupt(); decodage.quitSafely() }

    /** Un morceau d'audio (message binaire de type 4 : horodatage du serveur en µs, puis les données codées). */
    fun morceau(ts: Long, donnees: ByteArray) {
        val g = generation
        hd.post {
            val f = format ?: return@post
            if (g != generation) return@post
            try {
                when (f.codec) {
                    "pcm" -> pousser(g, ts, pcm16(donnees, f.bits))
                    "flac" -> decoder(g, ts, donnees)
                }
            } catch (e: Exception) {
                Log.w(TAG, "morceau illisible : $e")
                // Banc Musique (2026-10-12) : un décodeur en erreur (« work failed to complete », puis « Released state »)
                // refusait tous les morceaux suivants : silence jusqu'au titre suivant. On le referme ; le morceau suivant
                // en rouvre un ([decodeurPret]).
                if (f.codec == "flac") fermerDecodeur()
            }
        }
    }

    private fun pousser(g: Int, ts: Long, pcm: ShortArray) {
        if (pcm.isNotEmpty() && g == generation) blocs.add(Bloc(g, ts, pcm))
    }

    private fun pcm16(d: ByteArray, bits: Int): ShortArray {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return when (bits) {
            24 -> ShortArray(d.size / 3) { i -> ((d[3 * i + 1].toInt() and 0xFF) or (d[3 * i + 2].toInt() shl 8)).toShort() }
            32 -> ShortArray(d.size / 4) { (b.getInt(it * 4) shr 16).toShort() }
            else -> ShortArray(d.size / 2).also { b.asShortBuffer().get(it) }
        }
    }

    // ------------------------------------------------------------------------------------------- FLAC

    private fun ouvrirDecodeur(f: Format) {
        var c: MediaCodec? = null
        try {
            val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, f.debit, f.canaux)
            f.entete?.let { mf.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            if (Build.VERSION.SDK_INT >= 24) mf.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
            c.configure(mf, null, null, 0); c.start()
            decodeur = c
        } catch (e: Exception) {
            Log.w(TAG, "décodeur FLAC indisponible : $e")
            // Banc Musique : un codec créé mais pas configuré restait ouvert (les décodeurs de l'appareil s'épuisent).
            try { c?.release() } catch (_: Exception) {}
            decodeur = null
        }
    }

    private fun fermerDecodeur() {
        try { decodeur?.stop() } catch (_: Exception) {}
        try { decodeur?.release() } catch (_: Exception) {}
        decodeur = null; enAttente.clear(); finAttendue = Long.MIN_VALUE
    }

    private var dernierEssaiDecodeur = 0L

    /** Le décodeur FLAC, rouvert au besoin (après une erreur ou une ouverture ratée), au plus toutes les 0,5 s. */
    private fun decodeurPret(f: Format): MediaCodec? {
        decodeur?.let { return it }
        val maintenant = android.os.SystemClock.elapsedRealtime()
        if (maintenant - dernierEssaiDecodeur < 500) return null
        dernierEssaiDecodeur = maintenant
        ouvrirDecodeur(f)
        if (decodeur != null) { mesures.decodeurRelances++; Log.i(TAG, "décodeur FLAC rouvert") }
        return decodeur
    }

    private fun decoder(g: Int, ts: Long, d: ByteArray) {
        val f = format ?: return
        val c = decodeurPret(f) ?: return
        val i = c.dequeueInputBuffer(20_000)
        if (i >= 0) {
            val buf = c.getInputBuffer(i)!!
            buf.clear(); buf.put(d)
            c.queueInputBuffer(i, 0, d.size, ts, 0)
            enAttente.add(ts)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o < 0) break
            if (info.size > 0) {
                val out = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                out.position(info.offset); out.limit(info.offset + info.size)
                val pcm = ShortArray(info.size / 2).also { out.asShortBuffer().get(it) }
                // Première sortie d'un morceau : son horodatage ; une suite : juste après la précédente.
                val debut = if (enAttente.isNotEmpty() && info.presentationTimeUs == enAttente.peekFirst()) { enAttente.pollFirst()!! }
                            else if (finAttendue != Long.MIN_VALUE) finAttendue else info.presentationTimeUs
                while (enAttente.isNotEmpty() && enAttente.peekFirst()!! < info.presentationTimeUs) enAttente.pollFirst()
                finAttendue = debut + (pcm.size / f.canaux) * 1_000_000L / f.debit
                pousser(g, debut, pcm)
            }
            c.releaseOutputBuffer(o, false)
        }
    }

    // ------------------------------------------------------------------------------------------- sortie

    private fun nouvellePiste(f: Format): AudioTrack {
        val cfg = if (f.canaux == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(f.debit, cfg, AudioFormat.ENCODING_PCM_16BIT)
        val taille = maxOf(min * 2, f.debit * f.canaux * 2 / 10)  // au moins 100 ms
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val fmt = AudioFormat.Builder().setSampleRate(f.debit).setChannelMask(cfg).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        @Suppress("DEPRECATION")
        val t = if (Build.VERSION.SDK_INT >= 23) AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(taille).setTransferMode(AudioTrack.MODE_STREAM).build()
        else AudioTrack(attrs, fmt, taille, AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        t.setVolume(1f)  // règle du projet : toujours 100 %, la télé règle le son
        t.play()
        return t
    }

    private fun maintenantUs() = System.nanoTime() / 1000

    private fun boucle() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var piste: AudioTrack? = null
        var pisteFormat: Format? = null
        var pisteGeneration = -1
        var ecrits = 0L
        var finPrecedente = Long.MIN_VALUE
        var decalageFlux = 0L
        val ts = AudioTimestamp()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val b = blocs.poll(200, TimeUnit.MILLISECONDS)
                val f = format
                if (b == null) {
                    if (!actif && piste != null) { piste.pause(); piste.flush(); piste.release(); piste = null }
                    continue
                }
                if (f == null || b.generation != generation) continue
                // Banc Musique (2026-10-12) : avant la synchro de l'horloge (≈ 0,4 s après chaque connexion), l'heure d'un
                // bloc n'a pas de sens (versLocal rend l'heure du serveur telle quelle) : le bloc attend la synchro.
                if (!horloge.synchro) {
                    if (actif) { blocs.addFirst(b); mesures.attentesHorloge++; Thread.sleep(20) }
                    continue
                }
                // Nouvelle sortie à chaque flux vidé ou nouveau format : les compteurs de trames repartent de zéro.
                if (piste == null || pisteFormat?.debit != f.debit || pisteFormat?.canaux != f.canaux || pisteGeneration != b.generation) {
                    try { piste?.pause(); piste?.flush(); piste?.release() } catch (_: Exception) {}
                    piste = nouvellePiste(f); pisteFormat = f; pisteGeneration = b.generation; ecrits = 0
                    decalageFlux = 0L; mesures.decalageUs = 0L
                    finPrecedente = Long.MIN_VALUE
                }
                if (finPrecedente != Long.MIN_VALUE && b.ts - finPrecedente > 5_000) {
                    mesures.trous++; mesures.trouMaxMs = maxOf(mesures.trouMaxMs, (b.ts - finPrecedente) / 1000)
                    Log.i(TAG, "trou de ${(b.ts - finPrecedente) / 1000} ms dans le flux")
                }
                finPrecedente = b.ts + (b.pcm.size / f.canaux) * 1_000_000L / f.debit
                val p = piste
                val avecTs = p.getTimestamp(ts) && ts.framePosition > 0
                val prevue = SendspinSynchro.sortiePrevue(ecrits, f.debit, if (avecTs) ts.framePosition else null,
                    if (avecTs) ts.nanoTime / 1000 else null, maintenantUs(), p.playbackHeadPosition.toLong() and 0xFFFFFFFFL, LATENCE_NOMINALE_US)
                var voulue = horloge.versLocal(b.ts) - retardMs() * 1000L + decalageFlux
                // Flux livré en retard (démarrage d'un titre chez MA) : on se recale au lieu de tout jeter.
                SendspinSynchro.reancrage(prevue - voulue).takeIf { it > 0 }?.let { d ->
                    decalageFlux += d; voulue += d; mesures.reancrages++; mesures.decalageUs = decalageFlux
                    Log.i(TAG, "flux en retard de ${d / 1000} ms : recalé (décalage ${decalageFlux / 1000} ms)")
                }
                val ecart = prevue - voulue
                mesures.ecartUs = ecart
                mesures.tamponMs = (voulue - maintenantUs()) / 1000
                val trames = b.pcm.size / f.canaux
                val d = SendspinSynchro.decider(ecart, trames, f.debit)
                if (d.recalage) {
                    mesures.recalages++
                    Log.i(TAG, "recalage : ${ecart / 1000} ms ${if (ecart > 0) "en retard (trames jetées)" else "en avance (silence)"}, tampon ${mesures.tamponMs} ms, horloge ±${horloge.erreur / 1000} ms, génération ${b.generation}, écrites ${ecrits}")
                }
                var pcm = b.pcm
                if (d.jeter > 0) {
                    if (d.jeter >= trames) { mesures.blocsEnRetard++; continue }
                    pcm = pcm.copyOfRange(d.jeter * f.canaux, pcm.size)
                }
                if (d.silence > 0) {
                    // Jamais plus d'une demi-seconde de silence d'un coup : le bloc est réévalué ensuite.
                    val s = SendspinSynchro.silenceParPas(d.silence, f.debit)
                    ecrire(p, ShortArray(s * f.canaux)); ecrits += s
                    if (s < d.silence) { mesures.silencesBornes++; blocs.addFirst(b); continue }
                }
                if (d.retirer > 0 || d.doubler > 0) { pcm = SendspinSynchro.corriger(pcm, f.canaux, d.retirer, d.doubler); mesures.corrections += d.retirer + d.doubler }
                if (mesures.premierSonUs == 0L) mesures.premierSonUs = voulue
                ecrire(p, pcm)
                ecrits += pcm.size / f.canaux
                noterNiveau(pcm, voulue, pcm.size / f.canaux * 1_000_000L / f.debit)
                if (Build.VERSION.SDK_INT >= 24) try { mesures.sousAlimentationsPiste = p.underrunCount } catch (_: Exception) {}
                if (blocs.isEmpty() && actif) mesures.sousAlimentations++
            }
        } catch (_: InterruptedException) {
        } finally {
            try { piste?.pause(); piste?.flush(); piste?.release() } catch (_: Exception) {}
        }
    }

    /** Banc Musique : niveau du bloc et heure jusqu'à laquelle du son (au-dessus de -60 dBFS) est programmé. */
    private fun noterNiveau(pcm: ShortArray, debutUs: Long, dureeUs: Long) {
        var somme = 0.0
        for (x in pcm) somme += x.toDouble() * x
        val rms = if (pcm.isEmpty()) 0.0 else Math.sqrt(somme / pcm.size) / 32768.0
        val db = if (rms <= 1e-6) -120.0 else 20 * Math.log10(rms)
        mesures.niveauDb = db
        mesures.tramesEcrites += pcm.size
        if (db > SEUIL_SON_DB) {
            mesures.sonJusquaUs = debutUs + dureeUs
            if (mesures.premierSonFluxUs == 0L) mesures.premierSonFluxUs = debutUs
        }
    }

    private fun ecrire(p: AudioTrack, pcm: ShortArray) {
        // 1.91.0 : le niveau du son, pour le fond animé de l'écran Musique (réactif au son).
        NiveauSon.noter(pcm, android.os.SystemClock.elapsedRealtime())
        var o = 0
        while (o < pcm.size) {
            val n = p.write(pcm, o, pcm.size - o)
            if (n <= 0) break
            o += n
        }
    }

    companion object {
        private const val TAG = "VisionSendspin"
        /** Latence prise d'avance tant que la sortie n'a pas d'horodatage (corrigée dès qu'elle en a). */
        private const val LATENCE_NOMINALE_US = 60_000L
        /** En dessous, un bloc compte pour du silence (banc Musique). */
        const val SEUIL_SON_DB = -60.0

        /** FLAC si l'appareil sait le décoder (Android 8.1 et plus, en général), sinon PCM : MA sert les deux. */
        fun flacDecodable(): Boolean = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_FLAC, true) } }
        } catch (_: Exception) { false }
    }
}
