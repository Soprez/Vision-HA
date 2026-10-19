// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.net.SocketTimeoutException
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
    /** 1.93.06 : pendant un fondu enchaîné, le titre qui finit (il descend pendant que le suivant monte). */
    private var sortant: MediaPlayer? = null
    /** 1.93.06 : la transition demandée par Music Assistant pour le titre préparé (durée, ms ; type, '0' à '4'). */
    @Volatile private var transitionMs = 0
    @Volatile private var transitionType = '0'
    /** Le titre préparé partira en fondu enchaîné (pas d'enchaînement sans blanc par Android). */
    private var suivantEnFondu = false
    private var volume = 1f
    /** Banc Musique : le volume logiciel appliqué au lecteur Android (règle du projet : toujours 1,0 sur un lecteur Vision). */
    val volumeApplique: Float get() = if (allume) volume * facteurFocus else 0f
    /** Banc Musique : la position du titre en cours (ms), comme l'envoie le lecteur à Music Assistant. */
    val positionCourante: Int get() = positionMs()
    private var allume = true
    /** 1.93.06 : le volume baissé pendant un son bref d'une autre appli (focus audio, [FocusMusique]) ; 1 sinon. */
    @Volatile private var facteurFocus = 1f
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
        if (suiviReseau == null) {
            // Le premier avis est l'état au lancement : pas un retour du réseau (pas d'attente au démarrage).
            var premier = true
            suiviReseau = ReseauLocal.Suivi(ctx.applicationContext) { n -> if (premier) premier = false else reseauChange(n != null) }
                .also { it.demarrer() }
        }
        if (reveilCtx !== ctx.applicationContext) {
            // 1.93.06.1 (TV du salon, 19/10) : au réveil de la télé, la liaison est vérifiée tout de suite (voir verifierLiaison).
            // Posé une fois par application (les essais Robolectric en recréent une à chaque essai).
            try {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) { Thread { verifierLiaison("réveil de l'écran") }.apply { isDaemon = true; start() } }
                }
                ctx.applicationContext.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_ON))
                reveilCtx = ctx.applicationContext
            } catch (_: Exception) {}
        }
        val t = Thread { boucle(ctx.applicationContext, hote) }
        t.isDaemon = true; fil = t; t.start()
    }

    @Synchronized
    fun arreter() {
        fil?.interrupt(); fil = null
        // 1.93.06 : fermée proprement (« BYE! », puis fermeture), hors du fil principal (écriture réseau).
        socket?.let { s -> Thread { fermer(s) }.apply { isDaemon = true; start() } }
        h?.post { liberer() }
        FocusMusique.lacher()
    }

    /**
     * 1.93.06 : ce que [FocusMusique] demande quand une autre appli joue du son. La pause est locale et dite à Music
     * Assistant (STMp, comme une pause qu'il aurait demandée) ; la reprise aussi (STMr). Tout passe par le fil du lecteur.
     */
    private val moteurFocus = object : FocusMusique.Moteur {
        override fun pauseFocus() { h?.post { if (etat == "lecture" && lecteur != null) { mettreEnPause(); Log.i(TAG, "pause : une autre appli joue du son") } } }
        override fun repriseFocus() { h?.post {
            if (etat == "pause" && lecteur != null) { reprendre(); Log.i(TAG, "reprise après le son d'une autre appli") }
            // 1.93.06 (point 6a) : après une longue pause (une partie sur HDMI), Music Assistant a arrêté le lecteur
            // (30 s) : la reprise passe par Home Assistant, pour la seule soirée de cet appareil.
            else if (etat == "arrêt") ctxApp?.let { relancerParHa(it) }
        } }
        override fun volumeFocus(facteur: Float) { h?.post { facteurFocus = facteur; appliquerVolume() } }
    }

    private fun mettreEnPause() {
        try { lecteur?.pause() } catch (_: Exception) {}
        if (horlogePause == 0L) horlogePause = SystemClock.elapsedRealtime()
        etat = "pause"; stat("STMp")
    }

    private fun reprendre() {
        try { lecteur?.start() } catch (_: Exception) {}
        if (horlogePause > 0L && horlogeDebut > 0L) { horlogeDebut += SystemClock.elapsedRealtime() - horlogePause; horlogePause = 0L }
        etat = "lecture"; stat("STMr")
    }

    private fun boucle(ctx: Context, hoteForce: String?) {
        val moi = Thread.currentThread()
        while (!moi.isInterrupted && fil === moi) {
            val reste = attenteAvantLiaison(SystemClock.elapsedRealtime() - retourReseauA, attenteRetourMs)
            if (retourReseauA > 0L && reste > 0L) try { Thread.sleep(reste) } catch (_: InterruptedException) { return }
            val hote = hoteForce ?: serveur(ctx)
            var toutDeSuite = false
            if (hote != null) try { session(ctx, hote) } catch (_: LiaisonMuette) {
                // 1.93.06.1 : Music Assistant a déjà abandonné une liaison muette ; on se reconnecte sans attendre.
                Log.i(TAG, "aucune commande de Music Assistant depuis ${sansCommandeMs / 1000} s : liaison tenue pour morte, reconnexion")
                toutDeSuite = true
            } catch (e: Exception) { Log.i(TAG, "liaison coupée : ${e.message}") }
            connecte = false
            if (moi.isInterrupted || fil !== moi) return
            // 1.93.06.1 (TCL éteinte au bouton, 19/10 13:38 → 13:57) : écran éteint, le réseau de la télé somnole (la
            // connexion s'ouvre, puis plus rien ne passe) ; se reconnecter sans cesse faisait clignoter le lecteur dans
            // Home Assistant toutes les minutes. Écran éteint : on attend le réveil (ou un retour du réseau), même
            // après une liaison muette.
            if (!ecranAllume(ctx)) { attendreReveil(ctx, moi); if (moi.isInterrupted || fil !== moi) return; continue }
            if (toutDeSuite || reconnexionDemandee) { reconnexionDemandee = false; retourReseauA = 0L; continue }
            // Entre deux essais : 5 s, sauf si une reconnexion est demandée entre-temps (réveil, lancement de la soirée).
            for (k in 0 until 50) {
                if (reconnexionDemandee) { reconnexionDemandee = false; retourReseauA = 0L; break }
                try { Thread.sleep(100) } catch (_: InterruptedException) { return }
            }
        }
    }

    /** 1.93.06.1 : la liaison n'a rien reçu de Music Assistant pendant [sansCommandeMs] (voir [SANS_COMMANDE_MS]). */
    private class LiaisonMuette : Exception("liaison muette")

    /** L'écran est-il allumé (télé réveillée) ? Vrai dans le doute. */
    private fun ecranAllume(ctx: Context): Boolean = try { Usage.ecranAllume(ctx) } catch (_: Exception) { true }

    /** Écran éteint : au plus [VEILLE_ESSAI_MS] sans nouvel essai de liaison, sauf réveil ou reconnexion demandée. */
    const val VEILLE_ESSAI_MS = 15L * 60_000L
    @Volatile var veilleEssaiMs = VEILLE_ESSAI_MS

    private fun attendreReveil(ctx: Context, moi: Thread) {
        Log.i(TAG, "écran éteint : liaison en attente du réveil de la télé")
        val fin = horlogeMs() + veilleEssaiMs
        while (!moi.isInterrupted && fil === moi && !ecranAllume(ctx) && !reconnexionDemandee && horlogeMs() < fin) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { return }
        }
        reconnexionDemandee = false
        retourReseauA = 0L
    }

    /**
     * 1.93.06 (TV du salon, après une coupure réseau) : jamais deux connexions vers Music Assistant. Une seconde
     * connexion avec la même adresse (« Duplicate connection ») faisait éjecter le lecteur par MA, qui le
     * réenregistrait sous son nom brut, file vide. Donc :
     * - l'ancienne connexion est fermée (« BYE! », puis fermeture) AVANT d'en ouvrir une nouvelle ;
     * - une tentative qui aboutit après un [arreter] (fil remplacé) est refermée sans rien envoyer ;
     * - quand le réseau validé change ou revient ([ReseauLocal.Suivi]), la connexion est fermée tout de suite (BYE!
     *   passe si le réseau est là), et la suivante attend [attenteRetourMs] : le temps que MA abandonne une
     *   ancienne connexion dont la fermeture s'est perdue pendant la coupure (son battement reçoit alors un refus).
     */
    private fun session(ctx: Context, hote: String) {
        socket?.let { fermer(it) }
        val s = Socket()
        try {
            s.connect(InetSocketAddress(hote, PORT), 8_000)
            s.tcpNoDelay = true
            s.keepAlive = true
            // 1.93.06.1 : chien de garde — sans commande de Music Assistant pendant sansCommandeMs, la lecture lève
            // SocketTimeoutException (LiaisonMuette) et la liaison est refaite (voir SANS_COMMANDE_MS).
            s.soTimeout = sansCommandeMs.toInt()
            synchronized(verrou) {
                if (fil !== Thread.currentThread()) return  // arrêté (ou remplacé) pendant la connexion : rien n'est envoyé
                socket = s
                sortie = s.getOutputStream()
            }
            liaisons++
            relieA = SystemClock.elapsedRealtime()
            derniereCommandeA = horlogeMs()
            gardeDepart = true
            // 1.93.06 (banc, coupure réseau) : une reconnexion en pleine lecture se dit comme squeezelite (bit 0x4000
            // du HELO) ; sinon Music Assistant voit un lecteur neuf, éteint, et la musique ne reprend pas.
            val reconnexion = liaisons > 1 && lecteur != null && (etat == "lecture" || etat == "pause")
            val jouait = reconnexion && etat == "lecture"
            envoyer("HELO", helo(ctx, reconnexion))
            if (jouait) relancerApresCoupure(ctx, s)
            connecte = true
            try { lireCommandes(ctx, s, hote) } catch (e: SocketTimeoutException) { throw LiaisonMuette() }
        } finally {
            connecte = false
            fermer(s)
        }
    }

    /** Ferme une connexion : « BYE! » si elle est encore ouverte (comme squeezelite à l'arrêt), puis fermeture. */
    private fun fermer(s: Socket) {
        synchronized(verrou) {
            if (socket === s) {
                if (!s.isClosed && s.isConnected) try {
                    val b = ByteBuffer.allocate(9); b.put("BYE!".toByteArray(Charsets.US_ASCII)); b.putInt(1); b.put(0)
                    s.getOutputStream().apply { write(b.array()); flush() }
                } catch (_: Exception) {}
                socket = null
                sortie = null
            }
        }
        try { s.shutdownOutput() } catch (_: Exception) {}
        try { s.close() } catch (_: Exception) {}
    }

    /** 1.93.06 : après un changement ou un retour du réseau, attente avant de se reconnecter (ms) ; réglable pour les essais. */
    @Volatile var attenteRetourMs = 10_000L
    @Volatile private var retourReseauA = 0L
    private var suiviReseau: ReseauLocal.Suivi? = null

    /** Pur : combien attendre encore avant de se reconnecter, [depuisRetourMs] après le retour du réseau. */
    fun attenteAvantLiaison(depuisRetourMs: Long, attenteMs: Long): Long =
        if (depuisRetourMs < 0) 0L else (attenteMs - depuisRetourMs).coerceAtLeast(0L)

    /** Le réseau validé a changé (ou est revenu) : on ferme la connexion actuelle ; la boucle se reconnecte après l'attente. */
    fun reseauChange(present: Boolean) {
        if (!present) return  // coupé : rien ne passe ; on attend le retour
        retourReseauA = SystemClock.elapsedRealtime()
        socket?.let { s -> Thread { fermer(s) }.apply { isDaemon = true; start() } }
    }
    /** 1.93.06 : nombre de connexions ouvertes depuis le lancement (essais) et heure de la dernière (elapsedRealtime). */
    @Volatile var liaisons = 0; private set
    @Volatile private var relieA = 0L

    // ------------------------------------------------------------ liaison muette (TV du salon, 19/10)

    /**
     * 1.93.06.1 (l'auteur, 19/10 : « 928 titres, je clique sur Jump Around, c'est The Golden Path qui s'affiche, ▶ ne donne
     * rien ») : la TCL s'est mise en veille à 09:27 ; Music Assistant a abandonné la liaison 15 s plus tard (plus de
     * réponse à ses « strm t »). Au réveil (09:37), la connexion TCP restait « ouverte » côté télé (rien ne l'avait
     * fermée, la lecture bloquait sans fin) : le lecteur est resté hors ligne 77 min, jusqu'à ce qu'un changement du
     * réseau la ferme à 10:54. La soirée lancée entre-temps depuis la télé est partie sur une autre enceinte.
     *
     * Music Assistant envoie une commande (« strm t », battement) toutes les quelques secondes : sans AUCUNE commande
     * pendant [SANS_COMMANDE_MS], la liaison est tenue pour morte, fermée et refaite tout de suite (il l'a déjà
     * abandonnée, pas de doublon). Au réveil de l'écran et avant de lancer la soirée depuis cet appareil, la même
     * vérification est faite sans attendre ([verifierLiaison]).
     */
    const val SANS_COMMANDE_MS = 20_000L
    /** Réglable pour les essais. */
    @Volatile var sansCommandeMs = SANS_COMMANDE_MS
    @Volatile private var derniereCommandeA = 0L
    @Volatile private var reconnexionDemandee = false
    private var reveilCtx: Context? = null

    /** Pur : la liaison est-elle muette depuis trop longtemps ? */
    fun liaisonMorte(depuisDerniereCommandeMs: Long, limiteMs: Long): Boolean = depuisDerniereCommandeMs > limiteMs

    /** L'horloge du chien de garde : monotone et VRAIE (celle de SystemClock est simulée dans les essais Robolectric). */
    private fun horlogeMs(): Long = System.nanoTime() / 1_000_000L

    /** La boucle de liaison tourne-t-elle ? */
    val enMarche: Boolean get() = fil?.isAlive == true

    /**
     * La liaison est-elle vivante (reliée, et une commande de Music Assistant depuis moins de [sansCommandeMs]) ? Sinon
     * elle est fermée tout de suite et la boucle se reconnecte sans attendre. Hors du fil principal (fermeture réseau).
     */
    fun verifierLiaison(pourquoi: String = ""): Boolean {
        val s = socket
        if (s != null && connecte && !liaisonMorte(horlogeMs() - derniereCommandeA, sansCommandeMs)) return true
        if (!enMarche) return false
        Log.i(TAG, "liaison ${if (s == null) "absente" else "muette"} ($pourquoi) : reconnexion tout de suite")
        reconnexionDemandee = true
        retourReseauA = 0L
        s?.let { fermer(it) }
        return false
    }

    /** Attend que la liaison soit établie (au plus [maxMs]) ; vrai si elle l'est. Hors du fil principal. */
    fun attendreLiaison(maxMs: Long): Boolean {
        val fin = horlogeMs() + maxMs
        while (!connecte && enMarche && horlogeMs() < fin) try { Thread.sleep(100) } catch (_: InterruptedException) { return connecte }
        return connecte
    }

    private fun lireCommandes(ctx: Context, s: Socket, hote: String) {
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
            derniereCommandeA = horlogeMs()
            val c = String(cmd, Charsets.US_ASCII)
            h?.post { try { traiter(ctx, c, data, hote) } catch (e: Exception) { Log.w(TAG, "commande $c ignorée : $e") } }
        }
    }

    /**
     * 1.93.06 : un flux qui échoue juste après une (re)connexion (réseau tout juste revenu) est rouvert une fois, 2 s
     * plus tard, au lieu de dire tout de suite « pas de son » (STMn) à Music Assistant. Pur (testé en JVM).
     */
    fun rouvrirFlux(essai: Int, depuisLiaisonMs: Long): Boolean = essai == 0 && depuisLiaisonMs in 0 until 30_000

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

    /**
     * 1.93.06 (banc, coupure réseau) : Music Assistant éteint un lecteur Squeezelite qui se reconnecte (même avec le bit
     * de reconnexion) ; la file et le titre restent, mais la musique ne reprend pas. Si ce lecteur jouait, et seulement
     * si la soirée tourne sur CET appareil avec CE lecteur ([relancePermise]), on la relance par Home Assistant, 8 s plus
     * tard (le temps que MA ait repris le lecteur). Jamais un autre lecteur : la commande agit sur celui de la soirée.
     */
    private fun relancerApresCoupure(ctx: Context, s: Socket) {
        h?.postDelayed({
            if (socket !== s || !connecte) return@postDelayed
            relancerParHa(ctx)
        }, 8_000)
    }

    /** La relance par Home Assistant, gardée par [relancePermise] (jamais un autre lecteur que celui-ci). */
    private fun relancerParHa(ctx: Context) {
        Thread {
            try {
                val cfg = Config(ctx)
                val e = Party.analyser(Net.post(ctx, cfg, "/api/pc_parental/party/etat", org.json.JSONObject().put("id", cfg.id).put("secret", cfg.secret), 8_000))
                // 1.93.06 (incident du salon) : jamais par-dessus une autre appli ou une entrée de la télé.
                if (relancePermise(e.actif, e.ici, e.lecteurNom, e.maintenant.etat, nom(ctx)) && !FocusMusique.autreSonEnCours(ctx)) {
                    Net.post(ctx, cfg, "/api/pc_parental/party/commande", org.json.JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("action", "lecture"), 9_000)
                    Log.i(TAG, "lecture relancée par Home Assistant")
                }
            } catch (e: Exception) { Log.i(TAG, "relance par Home Assistant : ${e.message}") }
        }.apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------ départ après une liaison (incident du salon, 18/10)

    /**
     * 1.93.06 (incident du 18/10, 17:50 : après une réinstallation, Music Assistant a relancé la soirée sur la télé du
     * salon pendant qu'on y regardait une appli de replay) : le PREMIER départ dans les [GARDE_MS] qui suivent une liaison (démarrage
     * de l'appli, reconnexion) n'a lieu que si rien d'autre ne joue sur la télé (son d'une autre appli, entrée HDMI ou TV
     * en direct au premier plan). Sinon Vision reste en pause et le dit à Music Assistant (STMp). « Jouait-elle juste
     * avant la coupure » est la règle de Home Assistant (reprise de soirée) : ici, un « lecture » demandé juste après
     * l'ouverture de l'appli doit passer. Un départ demandé plus tard n'est pas concerné.
     */
    const val GARDE_MS = 20_000L
    @Volatile private var gardeDepart = false

    /** Pur : le premier départ après une liaison est-il permis ? */
    fun departApresLiaisonPermis(autreSon: Boolean): Boolean = !autreSon

    private fun departPermis(): Boolean {
        if (!gardeDepart) return true
        gardeDepart = false
        if (SystemClock.elapsedRealtime() - relieA > GARDE_MS) return true
        val ctx = ctxApp ?: return true
        // Le son qui sort encore de Vision (reconnexion en pleine lecture) n'est pas « autre chose ».
        if (try { lecteur?.isPlaying == true } catch (_: Exception) { false }) return true
        val ok = departApresLiaisonPermis(FocusMusique.autreSonEnCours(ctx))
        if (!ok) Log.i(TAG, "départ refusé après la liaison : autre chose joue sur l'appareil")
        return ok
    }

    /** Pur : relancer seulement la soirée de cet appareil, jouée par ce lecteur, et qui ne joue pas déjà (« lecture » bascule). */
    fun relancePermise(actif: Boolean, ici: Boolean, lecteurNom: String, etatMa: String, nomCeLecteur: String): Boolean =
        actif && ici && lecteurNom == nomCeLecteur && etatMa != "playing"

    /** Pur : le champ « wlan » du HELO — 0x4000 quand le lecteur se reconnecte en pleine lecture (comme squeezelite). */
    fun champReconnexion(reconnexion: Boolean): Short = if (reconnexion) 0x4000 else 0

    private fun helo(ctx: Context, reconnexion: Boolean = false): ByteArray {
        val mac = adresse(ctx)
        val caps = "Model=squeezelite,ModelName=Vision,Firmware=Vision-${BuildConfigCompat.version(ctx)},MaxSampleRate=48000,mp3"
        val b = ByteBuffer.allocate(36 + caps.length)
        b.put(12.toByte()); b.put(0.toByte()); b.put(mac)
        b.put(ByteArray(16))          // uuid
        b.putShort(champReconnexion(reconnexion))  // wlan
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
        // 1.93.06 (retour d'essai) : la transition entre les titres (réglage « fondu enchaîné » du lecteur dans Music Assistant).
        val transitionS = d[9].toInt() and 0xFF
        val transitionT = d[10].toInt().toChar()
        b.position(14)
        val battement = b.int
        val port = b.short.toInt() and 0xFFFF
        val ip = b.int
        if (commande != 't') Log.d(TAG, "strm $commande (démarrage $autostart, position ${positionMs()} ms)")
        when (commande) {
            't' -> stat("STMt", battement)
            'p' -> { FocusMusique.gesteUtilisateur(); mettreEnPause() }
            'u' -> {
                // 1.93.06 (incident du salon, 17:50) : premier départ juste après une liaison, refusé si autre chose
                // joue sur la télé : on reste en pause (STMp).
                if (!departPermis()) { if (lecteur != null && horlogePause == 0L) horlogePause = SystemClock.elapsedRealtime(); etat = "pause"; stat("STMp"); return }
                FocusMusique.gesteUtilisateur(); ctxApp?.let { FocusMusique.demander(it, moteurFocus) }
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
            'q' -> { h?.removeCallbacks(finSansSuite); h?.removeCallbacks(sansRelance); fonduSortie(); liberer(); depuisFlush = true; etat = "arrêt"; stat("STMf"); FocusMusique.lacher() }
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
                transitionMs = transitionS * 1000; transitionType = transitionT
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
        setVolume(volumeApplique, volumeApplique)
    }

    /** Libère un lecteur et ferme son tampon de flux. */
    private fun relacher(m: MediaPlayer) {
        try { m.release() } catch (_: Exception) {}
        try { sources.remove(m)?.close() } catch (_: Exception) {}
    }

    /** Un nouveau flux : remplace (après un « flush ») ou se met à la suite du titre en cours (enchaînement sans blanc). */
    private var fluxDemandes = 0

    private fun ouvrir(url: String, auto: Boolean, essai: Int = 0) {
        h?.removeCallbacks(finSansSuite); h?.removeCallbacks(sansRelance)  // la suite est arrivée : pas de « plus de son »
        stat("STMc")
        val enchainer = enchaineALaSuite(depuisFlush, lecteur != null, etat)
        depuisFlush = false
        try {
            val mp = nouveauLecteur(url)
            mp.setOnPreparedListener { p ->
                if (enchainer && lecteur != null && lecteur !== p) {
                    // 1.93.06 : fondu enchaîné si Music Assistant le demande et si la fin du titre est connue (durée de
                    // HA ou du lecteur) ; sinon l'enchaînement sans blanc d'Android, comme avant.
                    suivantEnFondu = fonduEnchaine(transitionType, transitionMs, dureeConnueMs())
                    if (!suivantEnFondu) try { lecteur?.setNextMediaPlayer(p) } catch (_: Exception) {}
                    suivant = p
                    // En pause (suite envoyée quand même) : on le redit à Music Assistant, qui se croirait sinon en lecture.
                    if (etat == "pause") stat("STMp")
                } else {
                    lecteur = p
                    decodeurPret = false
                    if (auto && !departPermis()) {
                        // 1.93.06 (incident du salon) : chargé mais muet, comme une pause ; « u » le lancera.
                        debutTitre(); horlogePause = SystemClock.elapsedRealtime(); etat = "pause"; stat("STMs"); stat("STMp")
                    } else if (auto) { ctxApp?.let { FocusMusique.demander(it, moteurFocus) }; fonduEntree(p); p.start(); debutTitre(); etat = "lecture"; stat("STMs") } else stat("STMl")
                    surveillerFin()
                }
            }
            mp.setOnCompletionListener { fini -> fin(fini) }
            val numero = ++fluxDemandes
            mp.setOnErrorListener { m, quoi, extra ->
                Log.w(TAG, "lecture impossible ($quoi/$extra) : $url")
                if (rouvrirFlux(essai, SystemClock.elapsedRealtime() - relieA)) {
                    // Rouvert seulement si aucun autre flux n'a été demandé entre-temps.
                    if (lecteur === m) lecteur = null
                    relacher(m)
                    h?.postDelayed({ if (fluxDemandes == numero && connecte) { depuisFlush = true; ouvrir(url, auto, essai = 1) } }, 2_000)
                } else stat("STMn")
                true
            }
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

    private val sansRelance = Runnable { if (lecteur == null && etat == "arrêt") { stat("STMu"); FocusMusique.lacher() } }

    private val finSansSuite = Runnable {
        if (lecteur != null || etat != "arrêt") return@Runnable
        val c = ctxApp ?: run { stat("STMu"); return@Runnable }
        Thread {
            val relance = demanderSuite(c)
            h?.post {
                if (lecteur != null || etat != "arrêt") return@post
                if (relance) { h?.removeCallbacks(sansRelance); h?.postDelayed(sansRelance, ATTENTE_RELANCE_MS) } else { stat("STMu"); FocusMusique.lacher() }
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
                // 1.93.06 (endurance du banc) : jamais pendant une pause. Music Assistant enverrait la suite et se croirait
                // « en lecture » : la reprise ne partait plus. La suite est demandée juste après la reprise.
                if (stmdPermis(etat) && decodeurPretMaintenant(decodeurPret, duree, position, (sources[mp] as? FluxUnique)?.telecharge == true, dureeHaMs)) { decodeurPret = true; stat("STMd") }
                val fin = dureeConnueMs()
                if (suivantEnFondu && suivant != null && etat == "lecture" && fin > 0 && debutFondu(fin - (positionMs() + recalage.decalageMs), transitionMs)) { lancerFondu(); return }
                if (etat == "lecture") stat("STMt")
                h?.postDelayed(this, 1_000)
            }
        }, 1_000)
    }

    private fun fin(fini: MediaPlayer) {
        if (fini === sortant) { sortant = null; relacher(fini); return }  // fin du titre qui descendait (fondu enchaîné)
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

    /** La fin du titre en cours (ms dans le titre), d'après HA ou le lecteur d'Android ; 0 si inconnue. */
    private fun dureeConnueMs(): Long {
        if (dureeHaMs > 0L) return dureeHaMs
        val d = try { lecteur?.duration ?: -1 } catch (_: Exception) { -1 }
        return if (d > 0) d.toLong() else 0L
    }

    /**
     * 1.93.06 (l'auteur : « appliquer réellement la transition de 4 s ») : le titre suivant part [transitionMs] avant la fin,
     * monte pendant que l'autre descend (puissance constante : pas de creux au milieu), puis l'ancien est libéré. Music
     * Assistant apprend le nouveau titre au moment où il part (STMs), comme avec squeezelite.
     */
    private fun lancerFondu() {
        val ancien = lecteur ?: return
        val n = suivant ?: return
        suivant = null; suivantEnFondu = false
        val duree = transitionMs.toLong().coerceAtLeast(PAS_FONDU_MS)
        try { n.setVolume(0f, 0f); n.start() } catch (_: Exception) { relacher(n); return }
        sortant?.let { relacher(it) }
        sortant = ancien
        lecteur = n; decodeurPret = false; debutTitre(); etat = "lecture"
        stat("STMs"); surveillerFin()
        Log.i(TAG, "fondu enchaîné de ${duree} ms")
        val debut = SystemClock.elapsedRealtime()
        h?.post(object : Runnable {
            override fun run() {
                val t = ((SystemClock.elapsedRealtime() - debut).toFloat() / duree).coerceIn(0f, 1f)
                val (sortie, entree) = volumesFondu(t)
                val v = volumeApplique
                if (sortant === ancien) try { ancien.setVolume(v * sortie, v * sortie) } catch (_: Exception) {}
                if (lecteur === n) try { n.setVolume(v * entree, v * entree) } catch (_: Exception) {}
                if (t < 1f) h?.postDelayed(this, PAS_FONDU_MS)
                else if (sortant === ancien) { sortant = null; relacher(ancien) }
            }
        })
    }

    /**
     * 1.93.06 (endurance du banc) : un nouveau flux sans « flush » est la suite du titre en cours — en lecture, ET en
     * pause. Avant, en pause, il remplaçait le titre et partait aussitôt : mis en pause près de la fin d'un titre, le
     * lecteur annonçait « décodeur prêt », Music Assistant envoyait la suite, et la musique repartait toute seule sur le
     * titre suivant, en pleine pause. Comme squeezelite : la suite attend la reprise. Pur.
     */
    fun stmdPermis(etat: String): Boolean = etat == "lecture"

    fun enchaineALaSuite(depuisFlush: Boolean, aUnLecteur: Boolean, etat: String): Boolean =
        !depuisFlush && aUnLecteur && (etat == "lecture" || etat == "pause")

    /** Le pas du fondu enchaîné (ms). */
    const val PAS_FONDU_MS = 50L

    /** Fondu enchaîné : seulement le type « 1 » (crossfade) avec une durée, et une fin de titre connue. Pur. */
    fun fonduEnchaine(type: Char, transitionMs: Int, finConnueMs: Long): Boolean = type == '1' && transitionMs > 0 && finConnueMs > 0L

    /** Le fondu commence quand il reste au plus la durée de la transition (une seconde de tic d'avance au plus). Pur. */
    fun debutFondu(restantMs: Long, transitionMs: Int): Boolean = transitionMs > 0 && restantMs <= transitionMs

    /** Les volumes (sortant, entrant) à l'instant [t] du fondu (0 → 1), à puissance constante. Pur. */
    fun volumesFondu(t: Float): Pair<Float, Float> {
        val x = t.coerceIn(0f, 1f) * (Math.PI.toFloat() / 2f)
        return Math.cos(x.toDouble()).toFloat() to Math.sin(x.toDouble()).toFloat()
    }

    private fun appliquerVolume() {
        val v = volumeApplique
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
        val cible = volumeApplique
        for (k in 3 downTo 0) { try { m.setVolume(cible * k / 4f, cible * k / 4f) } catch (_: Exception) { return }; SystemClock.sleep(15) }
    }

    private fun fonduEntree(p: MediaPlayer) {
        val cible = volumeApplique
        try { p.setVolume(0f, 0f) } catch (_: Exception) { return }
        for (k in 1..5) h?.postDelayed({ if (lecteur === p) try { p.setVolume(cible * k / 5f, cible * k / 5f) } catch (_: Exception) {} }, k * 30L)
    }

    private fun liberer(except: MediaPlayer? = null) {
        for (m in listOf(lecteur, suivant, sortant)) if (m != null && m !== except) relacher(m)
        if (sortant !== except) sortant = null
        suivantEnFondu = false
        if (lecteur !== except) { lecteur = null; horlogeDebut = 0L; horlogePause = 0L }
        if (suivant !== except) suivant = null
    }
}
