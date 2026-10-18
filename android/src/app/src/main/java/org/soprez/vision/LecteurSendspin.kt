// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Le moteur Sendspin de [LecteurMusique] : un [SendspinClient] relié au Music Assistant de la maison (même hôte que
 * Home Assistant, port 8927), sous le nom de l'appareil « · Vision », et une MediaSession (Media3) pour que la
 * télécommande (lecture/pause, suivant, précédent) et le système voient ce qui joue.
 *
 * Volume (règle du projet) : la MediaSession n'offre AUCUNE commande de volume ; le son se règle sur la télé.
 */
object LecteurSendspin {
    private const val TAG = "VisionSendspin"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var client: SendspinClient? = null
    private var session: MediaSession? = null
    private var joueur: Joueur? = null
    @Volatile var actif = false; private set
    @Volatile private var ctxApp: Context? = null

    val connecte: Boolean get() = client?.etat?.connecte == true

    /** « lecture », « pause » (un titre est là mais rien ne sort) ou « arrêt ». */
    val etat: String get() {
        val e = client?.etat ?: return "arrêt"
        return when { e.lit -> "lecture"; e.titre != null && e.etatGroupe.isNotEmpty() -> "pause"; else -> "arrêt" }
    }

    @Synchronized fun demarrer(ctx: Context) {
        if (actif) return
        actif = true
        ctxApp = ctx.applicationContext
        relier()
    }

    /** L'hôte de Home Assistant peut manquer (appareil pas encore relié) : nouvel essai toutes les 30 s. */
    private fun relier() {
        val ctx = ctxApp ?: return
        if (!actif || client != null) return
        val hote = LecteurSlim.serveur(ctx)
        if (hote == null) { main.postDelayed({ relier() }, 30_000); return }
        val codecs = if (SendspinSortie.flacDecodable()) listOf("flac", "pcm") else listOf("pcm")
        val c = SendspinClient(SendspinIdentite.de(ctx), SendspinClient.Config("ws://$hote:${SendspinClient.PORT}/sendspin",
            LecteurMusique.nom(ctx), BuildConfigCompat.version(ctx), codecs), object : SendspinClient.Ecouteur {
            override fun etat(e: SendspinClient.Etat) { main.post { majSession(e) } }
        })
        client = c
        c.demarrer()
        Log.i(TAG, "lecteur Sendspin démarré (${LecteurMusique.nom(ctx)} → $hote, ${codecs.joinToString()})")
    }

    @Synchronized fun arreter() {
        if (!actif) return
        actif = false
        main.removeCallbacksAndMessages(null)
        client?.liberer(); client = null
        main.post { session?.release(); session = null; joueur = null }
    }

    /** La position (s) quand ce lecteur est celui de la Party (même règle que l'ancien moteur). */
    fun positionLocale(ctx: Context, ici: Boolean, lecteurNom: String): Double? {
        val c = client ?: return null
        if (etat == "arrêt") return null
        if (lecteurNom != LecteurMusique.nom(ctx) && !(ici && lecteurNom.endsWith(" · Vision"))) return null
        return c.positionMs()?.let { it / 1000.0 }
    }

    /** Banc Musique (essais de bout en bout) : l'état vu du serveur et les mesures de la sortie, en lecture seule. */
    val etatClient: SendspinClient.Etat? get() = client?.etat
    val mesures: SendspinSortie.Mesures? get() = client?.sortie?.mesures
    fun positionMs(): Long? = client?.positionMs()

    /** Une commande de lecture vers Music Assistant (rôle controller). Jamais de volume. */
    fun commande(c: String) { client?.commande(c) }

    /** L'appairage (lot 6) : autoriser un appairage exigeant à la télécommande, ou l'annuler. */
    fun autoriserAppairage() { client?.ouvrirFenetreAppairage() }
    fun annulerAppairage() { client?.annulerAppairage(); manuelJusqua = 0L }
    private var pinMontre: String? = null
    private var gesteMontre = false

    // ------------------------------------------------------------------ appairage automatique (1.90.1)

    // 1.90.1 / HA 2.4.5 (l'auteur : « on peut pas faire un truc automatique ? ») : pas encore appairé à Music Assistant, ce
    // lecteur demande à Home Assistant (canal Vision authentifié) de lancer son appairage ; le PIN que MA fait tirer
    // part vers Home Assistant (jamais ailleurs), qui le soumet à MA.
    // 1.90.2 (exigence ferme de l'auteur) : l'appairage automatique est muet. Le code ne s'affiche JAMAIS, même en cas d'échec
    // ou de lenteur ; un échec se lit dans Réglages › Décideurs 🔒 (sans code), d'où l'on peut « Appairer à la main ».
    @Volatile private var dernierDemarrage = 0L
    private var pinEnvoye: String? = null
    /** La raison du dernier échec de l'appairage automatique (null : aucun). */
    @Volatile var echecAuto: String? = null; private set
    /** L'appairage à la main, demandé dans Réglages › Décideurs 🔒 : le code peut s'afficher jusqu'à cette heure. */
    @Volatile private var manuelJusqua = 0L
    const val MANUEL_MS = 5 * 60_000L

    fun manuel(): Boolean = android.os.SystemClock.elapsedRealtime() < manuelJusqua

    /** L'état de l'appairage, pour Réglages › Décideurs. */
    fun statut(): SendspinAppairageAuto.Statut {
        val e = client?.etat ?: SendspinClient.Etat()
        return SendspinAppairageAuto.statut(e.connecte, e.confiance, echecAuto)
    }

    /** Réglages › Décideurs 🔒 « Appairer à la main » : l'écran d'appairage s'ouvre (« Connexion à la musique… »),
     *  le code de Music Assistant s'y affichera quand on lancera l'appairage dans Music Assistant. */
    fun appairerALaMain(ctx: Context) {
        manuelJusqua = android.os.SystemClock.elapsedRealtime() + MANUEL_MS
        pinMontre = null; gesteMontre = false
        AppairageSendspinActivity.attendre(ctx)
        dernierEtat?.let { majSession(it) }
    }

    private fun appairageAuto(ctx: Context, e: SendspinClient.Etat) {
        if (e.connecte && e.confiance == "user") echecAuto = null
        val maintenant = android.os.SystemClock.elapsedRealtime()
        if (SendspinAppairageAuto.demarrer(e.connecte, e.confiance, e.appairage, e.pin, dernierDemarrage, maintenant)) {
            dernierDemarrage = maintenant
            demanderHa(ctx, org.json.JSONObject().put("etape", "demarrer").put("lecteur", SendspinIdentite.de(ctx).clientId)) { r ->
                SendspinAppairageAuto.echec("demarrer", r)?.let { echecAuto = it }
            }
        }
        SendspinAppairageAuto.pinAEnvoyer(e.pin, pinEnvoye)?.let { pin ->
            pinEnvoye = pin
            demanderHa(ctx, org.json.JSONObject().put("etape", "pin").put("pin", pin)) { r ->
                echecAuto = SendspinAppairageAuto.echec("pin", r)
            }
        }
        if (e.pin == null) pinEnvoye = null
    }

    private var dernierEtat: SendspinClient.Etat? = null

    private fun demanderHa(ctx: Context, corps: org.json.JSONObject, fin: (String) -> Unit) {
        val etape = corps.optString("etape")
        Thread {
            val resultat = try {
                val cfg = Config(ctx)
                if (cfg.id.isEmpty()) "" else
                    Net.post(ctx, cfg, "/api/pc_parental/sendspin/appairage", corps.put("id", cfg.id).put("secret", cfg.secret), 30_000).optString("resultat")
            } catch (ex: Exception) {
                Log.i(TAG, "appairage par Home Assistant impossible ($etape) : ${ex.javaClass.simpleName}"); ""
            }
            Log.i(TAG, "appairage par Home Assistant : $etape → $resultat")
            main.post { fin(resultat) }
        }.start()
    }

    /** Pour les essais instrumentés : un état de Music Assistant comme s'il venait du client. */
    internal fun essaiEtat(ctx: Context, e: SendspinClient.Etat) {
        ctxApp = ctx.applicationContext; actif = true
        majSession(e)
    }

    /** Pour les essais : tout remettre à zéro. */
    internal fun essaiRaz() {
        actif = false; dernierEtat = null; dernierDemarrage = 0L; pinEnvoye = null; echecAuto = null; manuelJusqua = 0L
        pinMontre = null; gesteMontre = false
    }

    private fun majSession(e: SendspinClient.Etat) {
        val ctx = ctxApp ?: return
        if (!actif) return
        dernierEtat = e
        appairageAuto(ctx, e)
        // L'appairage à l'écran : seulement à la main (Réglages › Décideurs 🔒), jamais pendant l'automatique (1.90.2).
        val manuel = manuel()
        val pinVu = SendspinAppairageAuto.pinAMontrer(e.confiance, e.pin, manuel)
        if (pinVu != pinMontre) { pinMontre = pinVu; if (pinVu != null) AppairageSendspinActivity.montrerPin(ctx, pinVu) }
        val geste = SendspinAppairageAuto.gesteAMontrer(e.confiance, e.appairage, manuel)
        if (geste != gesteMontre) { gesteMontre = geste; if (geste) AppairageSendspinActivity.demanderGeste(ctx) }
        if (manuel && e.connecte && e.confiance == "user") { manuelJusqua = 0L; AppairageSendspinActivity.reussi() }
        else if (manuel && e.appairage == "abandon") AppairageSendspinActivity.echec()
        if (session == null && (e.lit || e.titre != null)) {
            try {
                val j = Joueur()
                joueur = j
                session = MediaSession.Builder(ctx, j).setId("vision-sendspin").build()
            } catch (ex: Exception) { Log.w(TAG, "MediaSession impossible : $ex") }
        }
        joueur?.majEtat()
    }

    /** Le lecteur vu par le système : l'état vient de Music Assistant, les commandes y repartent. */
    private class Joueur : SimpleBasePlayer(Looper.getMainLooper()) {
        fun majEtat() = invalidateState()

        override fun getState(): State {
            val c = client
            val e = c?.etat ?: SendspinClient.Etat()
            val cmds = Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE)
            if ("next" in e.commandes) cmds.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if ("previous" in e.commandes) cmds.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            val b = State.Builder().setAvailableCommands(cmds.build())
                .setPlayWhenReady(e.lit, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setPlaybackState(if (e.titre != null || e.lit) Player.STATE_READY else Player.STATE_IDLE)
            if (e.titre != null || e.lit) {
                val md = MediaMetadata.Builder().setTitle(e.titre).setArtist(e.artiste).setAlbumTitle(e.album)
                    .apply { e.pochette?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }.build()
                val item = MediaItemData.Builder("sendspin").setMediaMetadata(md)
                    .setDurationUs(e.dureeMs?.let { it * 1000 } ?: C.TIME_UNSET).setIsSeekable(false).build()
                b.setPlaylist(listOf(item)).setContentPositionMs { client?.positionMs() ?: 0L }
            }
            return b.build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            commande(if (playWhenReady) "play" else "pause"); return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> { commande("stop"); return Futures.immediateVoidFuture() }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> commande("next")
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> commande("previous")
            }
            return Futures.immediateVoidFuture()
        }
    }
}
