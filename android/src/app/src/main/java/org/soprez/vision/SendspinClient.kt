// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Client Sendspin natif : Vision devient un lecteur de Music Assistant par son protocole officiel (MA 2.10.6 et plus,
 * `ws://<hôte>:8927/sendspin`). Remplace l'imitation Squeezelite/SlimProto ([LecteurSlim]) derrière l'interrupteur
 * [LecteurMusique] : FLAC, vraie position, enchaînement sans blanc, pochettes dans le canal chiffré.
 *
 * Rôles annoncés : player@v1 (le son), metadata@v1 (titre, artiste, position), controller@v1 (lecture, pause,
 * suivant… depuis la télécommande), artwork@v1 (la pochette en JPEG, sans lire d'adresse http de MA).
 *
 * Volume (règle du projet) : aucune commande de volume ni de sourdine n'est annoncée ; l'état envoyé dit toujours 100 %
 * sans sourdine ; une commande reçue quand même est ignorée. Music Assistant et Home Assistant n'ont donc pas de
 * volume pour ce lecteur ; la télé règle le son.
 *
 * Fil : tout le protocole passe sur « sendspin » (un seul fil, dans l'ordre d'arrivée) ; le son a les siens.
 */
class SendspinClient(
    private val identite: SendspinIdentite,
    private val config: Config,
    private val ecouteur: Ecouteur,
) {
    data class Config(val url: String, val nom: String, val version: String, val codecs: List<String>)

    /** Ce que l'appli voit du lecteur. */
    data class Etat(
        val connecte: Boolean = false,
        val disponible: Boolean = false,
        val lit: Boolean = false,
        val groupe: String = "",
        val etatGroupe: String = "",
        val titre: String? = null, val artiste: String? = null, val album: String? = null,
        val positionMs: Long? = null, val dureeMs: Long? = null, val vitesse: Int = 1000, val positionA: Long = 0L,
        val pochette: ByteArray? = null,
        val commandes: List<String> = emptyList(),
        val codec: String? = null,
        val appairage: String? = null, val pin: String? = null,
        /** « none », « user »… d'après la poignée de main ; [CONFIANCE_INCONNUE] avant (banc Musique, 2026-10-12 : à chaque
         *  reconnexion, « none » par défaut faisait redemander l'appairage à HA par un lecteur déjà appairé : un « setup
         *  flow » de MA ouvert pour rien, et l'écran du PIN pouvait revenir). */
        val confiance: String = CONFIANCE_INCONNUE,
        /** Correction de la position de MA après une pause (voir [etatServeur]) : position à la pause, heure locale (µs)
         *  de la pause, et écart retiré pour le reste du titre. */
        val posPause: Long? = null, val pauseA: Long = 0L, val decalageMs: Long = 0L,
    )

    interface Ecouteur { fun etat(e: Etat) }

    private val fil = HandlerThread("sendspin").apply { start() }
    private val h = Handler(fil.looper)
    // Pas de ping WebSocket : le serveur aiohttp de MA n'y répond pas toujours ; la vie de la liaison se lit aux
    // réponses `server/time` (au moins toutes les 3 s), voir [SILENCE_MAX_MS].
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(8, TimeUnit.SECONDS).build()
    val horloge = SendspinHorloge()
    val sortie = SendspinSortie(horloge) { retardMs }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var marche = false
    private var essais = 0
    private var transport: SendspinTransport? = null
    private var active = false
    private var retardMs = 0
    @Volatile var etat = Etat(); private set
    private val appairage = SendspinAppairage(object : SendspinAppairage.Deps {
        override fun envoyer(msg: Map<String, Any?>) { transport?.envoyer(msg) }
        override fun fermer() { ws?.close(1000, null); ws = null; perdu() }
        override val identite get() = this@SendspinClient.identite
        override fun serverId() = transport?.serverId ?: ""
        override fun categorie() = transport?.info?.categorie ?: SendspinIdentite.SENTINELLE
        override fun hashPoignee() = transport?.hashPoignee ?: ByteArray(0)
        override fun pin(pin: String?) = publier(etat.copy(pin = pin))
        override fun evenement(e: String, detail: String?) {
            Log.i(TAG, "appairage : $e${detail?.let { " ($it)" } ?: ""}")
            publier(etat.copy(appairage = e))
        }
        override fun echecs() = identite.echecsPin
        override fun poserEchecs(n: Int) { identite.echecsPin = n }
        override fun minuter(ms: Long, f: () -> Unit): () -> Unit { val r = Runnable(f); h.postDelayed(r, ms); return { h.removeCallbacks(r) } }
    })

    /** Le geste sur place (télécommande) qui autorise un appairage exigeant. */
    fun ouvrirFenetreAppairage() = h.post { appairage.ouvrirFenetre() }

    fun annulerAppairage() = h.post { appairage.annuler() }
    /** Un moyen de suivre le protocole sans connexion (essais) : chaque message envoyé, en clair. */
    var journal: ((String) -> Unit)? = null

    fun demarrer() { h.post { if (!marche) { marche = true; essais = 0; connecter() } } }

    fun arreter(raison: String = "shutdown") {
        h.post {
            marche = false
            try { transport?.auRevoir(raison) } catch (_: Exception) {}
            ws?.close(1000, raison); ws = null
            sortie.arreter()
            publier(Etat())
        }
    }

    fun liberer() { arreter(); h.post { sortie.liberer(); fil.quitSafely() } }

    /** Une commande de lecture (rôle controller) : play, pause, stop, next, previous, seek… */
    fun commande(c: String, extra: Map<String, Any?> = emptyMap()) = h.post {
        if (c in VOLUME_INTERDIT) { Log.i(TAG, "commande « $c » refusée : la télé gère le volume"); return@post }
        val t = transport ?: return@post
        if (!t.pret) return@post
        t.envoyer(linkedMapOf("type" to "client/command", "payload" to linkedMapOf("controller" to (linkedMapOf<String, Any?>("command" to c) + extra))))
    }

    // ------------------------------------------------------------------------------- connexion

    private fun connecter() {
        if (!marche) return
        val t = SendspinTransport(identite, object : SendspinTransport.Sortie {
            override fun texte(t: String) { journal?.invoke("→ $t"); ws?.send(t) }
            override fun binaire(b: ByteArray) { ws?.send(b.toByteString()) }
            override fun fermer(raison: String) { Log.i(TAG, "liaison fermée : $raison"); ws?.close(1000, null); ws = null; perdu() }
            override fun poignee(info: SendspinTransport.Info) {
                // Nouvelle poignée (connexion, ou re-poignée après un appairage) : tout repart de l'activation.
                appairage.remettre(); active = false; h.removeCallbacks(tic); h.removeCallbacks(etatPeriodique)
                publier(etat.copy(confiance = info.confiance))
            }
            override fun controle(type: String, charge: Map<String, Any?>) { this@SendspinClient.controle(type, charge) }
            override fun role(b: ByteArray) { this@SendspinClient.role(b) }
        })
        t.trace = { Log.d(TAG, it) }
        transport = t
        active = false
        horloge.remettre()
        val req = Request.Builder().url(config.url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { dernierRecu = maintenantUs(); h.post { if (ws === webSocket) { essais = 0; publier(etat.copy(connecte = true)); t.demarrer() } } }
            override fun onMessage(webSocket: WebSocket, text: String) { dernierRecu = maintenantUs(); h.post { if (ws === webSocket) t.recuTexte(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val recu = maintenantUs()
                dernierRecu = recu
                val b = bytes.toByteArray()
                h.post { if (ws === webSocket) { recuA = recu; t.recuBinaire(b) } }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { h.post { if (ws === webSocket) { ws = null; perdu() } } }
            override fun onFailure(webSocket: WebSocket, e: Throwable, response: Response?) {
                h.post { if (ws === webSocket) { Log.i(TAG, "liaison impossible : ${e.message}"); ws = null; perdu() } }
            }
        })
    }

    private var recuA = 0L
    @Volatile private var dernierRecu = 0L

    // Banc Musique (2026-10-12) : la reprise après une coupure (voir [SendspinReprise]).
    private var lisaitALaPerte = false
    private var perteMs = 0L
    private var retourMs = 0L
    private var repriseFaite = false
    private var fluxAuRetour = false

    private fun perdu() {
        if (etat.connecte) {
            lisaitALaPerte = etat.lit || etat.etatGroupe == "playing"
            perteMs = maintenantUs() / 1000; retourMs = 0L; repriseFaite = false; fluxAuRetour = false
        }
        h.removeCallbacks(tic); h.removeCallbacks(etatPeriodique)
        transport?.ferme()
        sortie.arreter()
        publier(Etat())
        if (!marche) return
        // Reconnexion : 1 s, puis de plus en plus espacée jusqu'à 15 s.
        val delai = minOf(15_000L, 1_000L shl minOf(essais, 4))
        essais++
        h.postDelayed({ if (marche && ws == null) connecter() }, delai)
    }

    // ------------------------------------------------------------------------------- protocole

    private fun controle(type: String, p: Map<String, Any?>) {
        val t = transport ?: return
        if (type != "server/time") Log.d(TAG, "← $type ${if (type == "server/activate") p else ""}")
        when (type) {
            "server/hello" -> t.envoyer(hello())
            "server/activate" -> {
                @Suppress("UNCHECKED_CAST") val act = (p["activities"] as? List<String>).orEmpty()
                if (appairage.activation(act, p["pairing"] as? Map<*, *>)) {
                    // Pendant l'appairage, le reste du protocole attend (ni horloge, ni état, ni son).
                    active = false; h.removeCallbacks(tic); h.removeCallbacks(etatPeriodique)
                    return
                }
                activer(p)
            }
            "server/pair-init" -> appairage.pairInit(p)
            "server/pair-auth" -> appairage.pairAuth(p)
            "server/pair-confirm" -> appairage.pairConfirm(p)
            "server/pair-finalize" -> appairage.pairFinalize()
            "pair/abort" -> appairage.abandonServeur(p["reason"] as? String ?: "")
            "server/time" -> {
                Log.v(TAG, "server/time rtt=${(recuA - n(p["client_transmitted"])) / 1000} ms")
                val avant = horloge.synchro
                horloge.serveurTime(n(p["client_transmitted"]), n(p["server_received"]), n(p["server_transmitted"]), recuA)
                if (!avant && horloge.synchro) { etatClient(); publier(etat.copy(disponible = true)) }
            }
            "stream/start" -> streamStart(p)
            "stream/clear" -> { val r = p["roles"] as? List<*>; if (r == null || "player" in r) sortie.vider() }
            "stream/end" -> {
                val r = p["roles"] as? List<*>
                if (r == null || "player" in r) { sortie.arreter(); publier(etat.copy(lit = false, codec = null)); etatClient() }
                if (r == null || "artwork" in r) publier(etat.copy(pochette = null))
            }
            "server/command" -> commandeServeur(p)
            "server/state" -> serverState(p)
            "group/update" -> {
                Log.d(TAG, "groupe : ${p["playback_state"]}")
                publier(etat.copy(groupe = p["group_name"] as? String ?: etat.groupe, etatGroupe = p["playback_state"] as? String ?: etat.etatGroupe,
                    lit = (p["playback_state"] as? String ?: etat.etatGroupe) == "playing"))
                repriseApresCoupure(p["playback_state"] as? String)
            }
            else -> journal?.invoke("ignoré : $type")
        }
    }

    /** MA arrête la lecture au retour d'un lecteur qui jouait : on la relance une fois ([SendspinReprise]). */
    private fun repriseApresCoupure(etatGroupe: String?) {
        if (!lisaitALaPerte || retourMs == 0L || etatGroupe == null) return
        val maintenant = maintenantUs() / 1000
        if (SendspinReprise.reprendre(lisaitALaPerte, retourMs - perteMs, maintenant - retourMs, fluxAuRetour, etatGroupe, repriseFaite)) {
            repriseFaite = true; lisaitALaPerte = false
            val coupure = (retourMs - perteMs) / 1000
            // Tout de suite, MA l'ignore (il finit de fermer l'ancienne liaison) : on attend un peu, et seulement si rien
            // n'a changé entre-temps (personne n'a relancé ni choisi autre chose).
            h.postDelayed({
                if (etat.connecte && etat.etatGroupe == "stopped") {
                    Log.i(TAG, "lecture relancée après une coupure de $coupure s (MA l'avait arrêtée au retour)")
                    commande("play")
                }
            }, SendspinReprise.DELAI_RELANCE_MS)
        } else if (maintenant - retourMs > SendspinReprise.FENETRE_MS) lisaitALaPerte = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun activer(p: Map<String, Any?>) {
        val premiere = !active
        active = true
        if (premiere && lisaitALaPerte && retourMs == 0L) retourMs = maintenantUs() / 1000
        if (premiere) {
            etatClient()
            h.removeCallbacks(tic); h.post(tic)
            h.removeCallbacks(etatPeriodique); h.postDelayed(etatPeriodique, 5_000)
        } else etatClient()
    }

    private val tic = object : Runnable {
        override fun run() {
            val t = transport ?: return
            if (!t.pret) return
            if ((maintenantUs() - dernierRecu) / 1000 > SILENCE_MAX_MS) {
                Log.i(TAG, "plus rien du serveur depuis ${SILENCE_MAX_MS / 1000} s : reconnexion"); ws?.cancel(); ws = null; perdu(); return
            }
            t.envoyer(linkedMapOf("type" to "client/time", "payload" to linkedMapOf("client_transmitted" to maintenantUs())))
            h.postDelayed(this, horloge.intervalleMs())
        }
    }

    private val etatPeriodique = object : Runnable {
        override fun run() { etatClient(); h.postDelayed(this, 5_000) }
    }

    /** `client/hello` : rôles, formats (FLAC si décodable, sinon PCM), aucune commande de volume. */
    private fun hello(): Map<String, Any?> {
        val formats = ArrayList<Map<String, Any?>>()
        for (c in config.codecs) for (r in listOf(48_000, 44_100)) formats.add(linkedMapOf("codec" to c, "sample_rate" to r, "channels" to 2, "bit_depth" to 16))
        val capacite = (48_000 * 2 * 2 * 1.02 * 30).toInt()
        return linkedMapOf("type" to "client/hello", "payload" to linkedMapOf(
            "name" to config.nom,
            "supported_roles" to listOf("player@v1", "controller@v1", "metadata@v1", "artwork@v1"),
            "trust_level" to (transport?.info?.confiance ?: "none"),
            "supported_pair_methods" to appairage.methodes(),
            "unpaired_access" to linkedMapOf("enabled" to true),
            "device_info" to linkedMapOf("product_name" to "Vision", "manufacturer" to (Build.MANUFACTURER ?: ""), "software_version" to config.version),
            "player@v1_support" to linkedMapOf("supported_formats" to formats, "buffer_capacity" to capacite, "supported_commands" to emptyList<String>()),
            "artwork@v1_support" to linkedMapOf("channels" to listOf(linkedMapOf("source" to "album", "format" to "jpeg", "media_width" to 800, "media_height" to 800))),
        ))
    }

    /** `client/state` : ni volume ni sourdine (règle du projet : la télé règle le son) ; seule commande offerte : le retard d'enceinte. */
    private fun etatClient() {
        val t = transport ?: return
        if (!active) return
        val charge = linkedMapOf<String, Any?>("available" to horloge.synchro)
        // L'objet d'un rôle seulement s'il est actif (la spec, et aiosendspin strict, refusent sinon).
        if (t.roles.orEmpty().contains("player@v1"))
            charge["player"] = linkedMapOf("static_delay_ms" to retardMs,
                "required_lead_time_ms" to AVANCE_MS, "min_buffer_ms" to TAMPON_MS, "supported_commands" to listOf("set_static_delay"))
        t.envoyer(linkedMapOf("type" to "client/state", "payload" to charge))
    }

    private fun commandeServeur(p: Map<String, Any?>) {
        val c = p["player"] as? Map<*, *> ?: return
        when (c["command"]) {
            "volume", "mute" -> Log.i(TAG, "commande de volume ignorée (${c["command"]}) : la télé gère le son")
            "set_static_delay" -> (c["static_delay_ms"] as? Number)?.let { retardMs = it.toInt().coerceIn(0, 5_000) }
        }
        etatClient()
    }

    private fun streamStart(p: Map<String, Any?>) {
        val pl = p["player"] as? Map<*, *>
        if (pl != null) {
            if (retourMs > 0L) fluxAuRetour = true
            val f = SendspinSortie.Format(pl["codec"] as? String ?: "pcm", (pl["sample_rate"] as? Number)?.toInt() ?: 48_000,
                (pl["channels"] as? Number)?.toInt() ?: 2, (pl["bit_depth"] as? Number)?.toInt() ?: 16,
                (pl["codec_header"] as? String)?.let { SendspinNoise.deB64(it) })
            Log.i(TAG, "flux : ${f.codec} ${f.debit} Hz, ${f.canaux} canaux, ${f.bits} bits")
            sortie.demarrer(f)
            publier(etat.copy(lit = true, codec = f.codec))
        }
    }

    private fun serverState(p: Map<String, Any?>) {
        ((p["metadata"] as? Map<*, *>)?.get("progress") as? Map<*, *>)?.let { pr ->
            val ts = n((p["metadata"] as Map<*, *>)["timestamp"])
            Log.d(TAG, "progression : ${pr["track_progress"]} ms, vitesse ${pr["playback_speed"]}, horodatée ${(horloge.versLocal(ts) - maintenantUs()) / 1000} ms par rapport à maintenant")
        }
        publier(etatServeur(etat, p, { horloge.versLocal(it) }))
    }

    private fun role(b: ByteArray) {
        val type = b[0].toInt() and 0xFF
        if (b.size < 9) return
        val ts = java.nio.ByteBuffer.wrap(b, 1, 8).long
        when (type) {
            4 -> sortie.morceau(ts, b.copyOfRange(9, b.size))
            in 8..11 -> if (type == 8) {
                // La pochette du canal 0 (album), à afficher à son heure.
                val image = if (b.size > 9) b.copyOfRange(9, b.size) else null
                val dans = ((horloge.versLocal(ts) - maintenantUs()) / 1000).coerceIn(0, 10_000)
                h.postDelayed({ publier(etat.copy(pochette = image)) }, dans)
            }
        }
    }

    private fun publier(e: Etat) { etat = e; try { ecouteur.etat(e) } catch (_: Exception) {} }

    /** La position (ms) maintenant, d'après la dernière donnée par MA et sa vitesse. */
    fun positionMs(): Long? = position(etat, maintenantUs() - sortie.mesures.decalageUs)

    companion object {
        const val TAG = "VisionSendspin"
        const val PORT = 8927
        /** Avance demandée au serveur au démarrage d'un flux, et tampon minimal en lecture (ms). */
        const val AVANCE_MS = 500
        const val TAMPON_MS = 500
        const val SILENCE_MAX_MS = 15_000L
        /** L'écart (ms) toléré entre « position de reprise − position de pause » et la durée de la pause. */
        const val TOLERANCE_PAUSE_MS = 1_500L
        /** La confiance avant la poignée de main : ni « none » ni « user ». */
        const val CONFIANCE_INCONNUE = ""
        private val VOLUME_INTERDIT = setOf("volume", "mute")
        fun maintenantUs() = System.nanoTime() / 1000

        /**
         * `server/state` (lot 4) : les champs présents remplacent les anciens, les absents restent. La position de MA
         * était juste à son `timestamp` (µs serveur), traduit en heure locale par [versLocal].
         *
         * Banc Musique (2026-10-12, MA 2.10.6) : à la reprise après une pause, MA annonce la position de la pause PLUS la
         * durée de la pause (12,9 s en pause 8 s → 20,7 s), alors que le son reprend bien à 12,9 s et que sa file (HA) dit
         * 12 s. La barre de la Party finissait donc avant la musique, d'autant que les pauses avaient duré (l'auteur : « la
         * barre finit, la musique continue quelques secondes »). Correction : sur le même titre, si la position de reprise
         * dépasse celle de la pause de la durée de la pause (à [TOLERANCE_PAUSE_MS] près), cet écart est retiré jusqu'au
         * titre suivant. Si MA annonce juste, l'écart est nul et rien ne change.
         */
        fun etatServeur(e0: Etat, p: Map<String, Any?>, versLocal: (Long) -> Long, maintenant: Long = maintenantUs()): Etat {
            var e = e0
            (p["metadata"] as? Map<*, *>)?.let { m ->
                if ("title" in m) e = e.copy(titre = m["title"] as? String)
                if ("artist" in m) e = e.copy(artiste = m["artist"] as? String)
                if ("album" in m) e = e.copy(album = m["album"] as? String)
                if ("progress" in m) {
                    val pr = m["progress"] as? Map<*, *>
                    e = if (pr == null) e.copy(positionMs = null, dureeMs = null, posPause = null, decalageMs = 0L)
                    else {
                        val brut = n(pr["track_progress"]); val vitesse = n(pr["playback_speed"]).toInt()
                        val memeTitre = e.titre == e0.titre
                        var decalage = if (memeTitre) e0.decalageMs else 0L
                        var posPause = if (memeTitre) e0.posPause else null
                        var pauseA = e0.pauseA
                        if (vitesse == 0) { posPause = brut - decalage; pauseA = maintenant }
                        else if (posPause != null) {
                            val ecart = brut - decalage - posPause
                            val pauseMs = (maintenant - pauseA) / 1000
                            if (ecart > TOLERANCE_PAUSE_MS && Math.abs(ecart - pauseMs) <= TOLERANCE_PAUSE_MS) decalage += ecart
                            posPause = null
                        }
                        val duree = n(pr["track_duration"]).takeIf { it > 0 }
                        // Titre fini (MA annonce sa durée) : la fin est la fin, sans correction.
                        val pos = if (duree != null && brut >= duree) brut else brut - decalage
                        e.copy(positionMs = pos, dureeMs = duree, vitesse = vitesse,
                            positionA = versLocal(n(m["timestamp"])), posPause = posPause, pauseA = pauseA, decalageMs = decalage)
                    }
                }
            }
            (p["controller"] as? Map<*, *>)?.let { c -> e = e.copy(commandes = (c["supported_commands"] as? List<*>).orEmpty().map { it.toString() }) }
            return e
        }

        /** La position à [maintenant] (µs locales) : celle donnée par MA, avancée à sa vitesse, bornée par la durée. */
        fun position(e: Etat, maintenant: Long): Long? {
            val p = e.positionMs ?: return null
            val avance = (maintenant - e.positionA) / 1000 * e.vitesse / 1000
            return (p + maxOf(0, avance)).let { d -> e.dureeMs?.let { minOf(it, d) } ?: d }
        }
        private fun n(v: Any?): Long = (v as? Number)?.toLong() ?: 0L
    }
}
