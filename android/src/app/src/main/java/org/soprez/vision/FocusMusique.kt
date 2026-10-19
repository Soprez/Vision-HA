// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 1.93.06 (retour d'essai) : la musique de Vision se met en pause quand une autre appli joue du son (focus audio d'Android).
 *
 * Réglé par appareil, sur la télé (Réglages › Musique) ou depuis Home Assistant (service `pc_parental.musique_regler`,
 * bloc « musique_options » du relevé ; le dernier qui règle gagne, comme [OptionsVeille]) :
 * - [mode] : [TOUJOURS] (défaut), [APPLIS] (seulement si l'appli au premier plan est dans [applis]) ou [JAMAIS] (Vision
 *   ne demande pas le focus : le comportement d'avant la 1.93.06) ;
 * - [reprise] : la musique repart d'elle-même quand l'autre son s'arrête ; non par défaut (une musique qui reprend
 *   toute seule à plein volume surprend).
 *
 * Ce que dit Android, et ce que Vision en fait ([decision], pur, testé en JVM) :
 * - une autre appli prend le son pour un moment (assistant vocal, appel) ou pour de bon (vidéo, autre musique) :
 *   pause, sauf en [JAMAIS] ou si l'appli n'est pas dans la liste en [APPLIS] ;
 * - un son bref qui accepte qu'on baisse (notification, guidage) : le son baisse puis remonte (Android 8 et plus le fait
 *   lui-même ; avant, Vision baisse à 20 %) ;
 * - le focus revient (fin d'un son « pour un moment ») : reprise si [reprise] et si c'est Vision qui avait mis en pause.
 *   Après une perte « pour de bon », Android ne rend pas le focus : avec [reprise], Vision regarde toutes les 3 s si un
 *   autre son joue encore ([AudioManager.isMusicActive]) et redemande le focus quand tout s'est tu deux fois de suite.
 *
 * Le moteur ([LecteurSlim] ou [LecteurSendspin]) fournit pause, reprise et volume ([Moteur]) ; Music Assistant est
 * prévenu par le moteur (Slim : STMp / STMr ; Sendspin : commande pause / play).
 */
object FocusMusique {
    const val TOUJOURS = "toujours"
    const val APPLIS = "applis"
    const val JAMAIS = "jamais"
    val MODES = listOf(TOUJOURS, APPLIS, JAMAIS)
    const val VOLUME_BAISSE = 0.2f
    const val VERIFIER_MS = 3_000L

    private const val PREFS = "musique_options"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = p(ctx).getString("focus", null)?.takeIf { it in MODES } ?: TOUJOURS
    fun applis(ctx: Context): Set<String> = p(ctx).getStringSet("applis", emptySet())?.toSet() ?: emptySet()
    fun reprise(ctx: Context): Boolean = p(ctx).getBoolean("reprise", false)

    fun poserMode(ctx: Context, m: String) { if (m in MODES) { p(ctx).edit().putString("focus", m).apply(); Local.toucher(); appliquerReglage(ctx) } }
    fun poserApplis(ctx: Context, l: Set<String>) { p(ctx).edit().putStringSet("applis", l.toSet()).apply(); Local.toucher() }
    fun poserReprise(ctx: Context, oui: Boolean) { p(ctx).edit().putBoolean("reprise", oui).apply(); Local.toucher() }

    /** Le bloc « musique_options » du relevé ; rend vrai si un réglage a changé. Bloc absent (HA plus ancien) : rien. */
    fun releve(ctx: Context, o: JSONObject?): Boolean {
        if (o == null) return false
        val depuis = o.optLong("depuis", 0L)
        if (depuis <= 0L || depuis <= p(ctx).getLong("depuis_ha", 0L)) return false
        val e = p(ctx).edit().putLong("depuis_ha", depuis)
        o.optString("focus").takeIf { it in MODES }?.let { e.putString("focus", it) }
        if (o.has("reprise")) e.putBoolean("reprise", o.optBoolean("reprise", false))
        o.optJSONArray("applis")?.let { a -> e.putStringSet("applis", (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()) }
        e.apply()
        Local.toucher()
        appliquerReglage(ctx)
        return true
    }

    enum class Action { RIEN, PAUSE, BAISSER, REPRENDRE, REMONTER }

    /**
     * Ce que fait Vision quand Android annonce [changement] (AUDIOFOCUS_*). [premierPlan] : l'appli au premier plan
     * (null si inconnue : accès « usage » refusé). [pauseParFocus] : c'est Vision qui a mis la musique en pause. Pur.
     */
    fun decision(mode: String, applis: Set<String>, reprise: Boolean, changement: Int, premierPlan: String?,
                 pauseParFocus: Boolean, baisse: Boolean): Action = when (changement) {
        AudioManager.AUDIOFOCUS_GAIN -> when {
            pauseParFocus && reprise -> Action.REPRENDRE
            baisse -> Action.REMONTER
            else -> Action.RIEN
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (mode == JAMAIS) Action.RIEN else Action.BAISSER
        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> when (mode) {
            JAMAIS -> Action.RIEN
            // Appli inconnue (pas d'accès « usage ») : on ne coupe pas, faute de savoir si elle est dans la liste.
            APPLIS -> if (premierPlan != null && premierPlan in applis) Action.PAUSE else Action.RIEN
            else -> Action.PAUSE
        }
        else -> Action.RIEN
    }

    /**
     * 1.93.06 (point 6a) : une entrée de la télé (HDMI, TV en direct, [SourcesTele]) passe au premier plan ([entree]) ou
     * le quitte. Android ne retire pas toujours le focus dans ce cas : même réglage que pour une appli. En [APPLIS], il
     * faut [SourcesTele.JETON] dans la liste. Au retour, reprise si [reprise] et si c'est l'entrée qui avait mis en pause.
     * [joue] : la musique de Vision joue. Pur.
     */
    fun decisionSource(mode: String, applis: Set<String>, reprise: Boolean, entree: Boolean, pauseParSource: Boolean, joue: Boolean): Action = when {
        entree && !joue -> Action.RIEN
        entree -> when (mode) {
            JAMAIS -> Action.RIEN
            APPLIS -> if (SourcesTele.JETON in applis) Action.PAUSE else Action.RIEN
            else -> Action.PAUSE
        }
        pauseParSource && reprise -> Action.REPRENDRE
        else -> Action.RIEN
    }

    /** Avec [reprise] après une perte « pour de bon » : relancer quand aucun autre son n'a joué [fois] vérifications de suite. Pur. */
    fun silenceSuffisant(silencesDeSuite: Int, fois: Int = 2): Boolean = silencesDeSuite >= fois

    /** Ce que le moteur de lecture en cours sait faire. */
    interface Moteur {
        fun pauseFocus()
        fun repriseFocus()
        fun volumeFocus(facteur: Float)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var moteur: Moteur? = null
    @Volatile private var ctxApp: Context? = null
    @Volatile private var demande: Any? = null
    @Volatile var detenu = false; private set
    @Volatile var pauseParFocus = false; private set
    @Volatile private var baisse = false
    @Volatile private var pertePourDeBon = false
    private var silences = 0

    private val ecoute = AudioManager.OnAudioFocusChangeListener { c -> main.post { changer(c) } }

    private fun am(ctx: Context) = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** La musique de Vision commence ou reprend : Vision demande le focus (sauf en [JAMAIS]). Rend vrai si on peut jouer. */
    fun demander(ctx: Context, m: Moteur): Boolean {
        ctxApp = ctx.applicationContext; moteur = m
        pauseParFocus = false; pertePourDeBon = false; main.removeCallbacks(verifier)
        pauseParSource = false; dansSource = false
        if (mode(ctx) == JAMAIS) { lacher(ctx); return true }
        sonderSiBesoin(ctx)
        if (detenu) return true
        val r = try {
            if (Build.VERSION.SDK_INT >= 26) {
                val d = (demande as? AudioFocusRequest) ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(ecoute, main).build().also { demande = it }
                am(ctx).requestAudioFocus(d)
            } else @Suppress("DEPRECATION") am(ctx).requestAudioFocus(ecoute, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        } catch (_: Exception) { AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        // Refusé (un appel en cours) : on joue quand même, comme avant ; Android nous dira la suite.
        detenu = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return true
    }

    /** La musique s'arrête (arrêt, fin de file, moteur arrêté) : Vision rend le focus. */
    fun lacher(ctx: Context? = ctxApp) {
        main.removeCallbacks(verifier)
        if (!pauseParSource) main.removeCallbacks(sonder)
        pauseParFocus = false; pertePourDeBon = false; baisse = false
        if (!detenu || ctx == null) { detenu = false; return }
        try {
            if (Build.VERSION.SDK_INT >= 26) (demande as? AudioFocusRequest)?.let { am(ctx).abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am(ctx).abandonAudioFocus(ecoute)
        } catch (_: Exception) {}
        detenu = false
    }

    /** Une pause ou une reprise demandée par l'utilisateur (Music Assistant, télécommande) : Vision ne reprendra plus seul. */
    fun gesteUtilisateur() { pauseParFocus = false; pertePourDeBon = false; pauseParSource = false; main.removeCallbacks(verifier) }

    /** Un réglage changé pendant la lecture s'applique tout de suite : [JAMAIS] rend le focus, les autres le reprennent. */
    private fun appliquerReglage(ctx: Context) {
        main.post {
            if (mode(ctx) == JAMAIS) lacher(ctx)
            else { val m = moteur; if (m != null && !detenu && LecteurMusique.etat == "lecture") demander(ctx, m) }
        }
    }

    private fun changer(c: Int) {
        val ctx = ctxApp ?: return
        val m = moteur ?: return
        val premier = if (mode(ctx) == APPLIS) try { Usage.premierPlan(ctx) } catch (_: Exception) { null } else null
        when (decision(mode(ctx), applis(ctx), reprise(ctx), c, premier, pauseParFocus, baisse)) {
            Action.PAUSE -> {
                m.pauseFocus(); pauseParFocus = true
                if (c == AudioManager.AUDIOFOCUS_LOSS) {
                    // Pour de bon : Android ne rendra pas le focus. On le rend (pour le redemander plus tard).
                    detenu = false; pertePourDeBon = true
                    if (reprise(ctx)) { silences = 0; main.removeCallbacks(verifier); main.postDelayed(verifier, VERIFIER_MS) }
                }
            }
            // Android 8 et plus baisse lui-même le son d'une appli qui ne demande pas la pause (setWillPauseWhenDucked
            // faux) : Vision ne touche son volume (toujours 100 %, règle du projet) qu'avant Android 8.
            Action.BAISSER -> { baisse = true; if (Build.VERSION.SDK_INT < 26) m.volumeFocus(VOLUME_BAISSE) }
            Action.REMONTER -> { baisse = false; m.volumeFocus(1f) }
            Action.REPRENDRE -> { baisse = false; m.volumeFocus(1f); pauseParFocus = false; m.repriseFocus() }
            Action.RIEN -> if (c == AudioManager.AUDIOFOCUS_LOSS) detenu = false
        }
    }

    /**
     * 1.93.06 (incident du salon) : autre chose joue-t-il sur l'appareil, avant que Vision ne joue ? Un son d'une autre
     * appli (vidéo, TV en direct qui passe par le son du système), ou une entrée de la télé (HDMI) au premier plan, dont
     * le son ne passe pas toujours par Android. À appeler AVANT de démarrer la musique (sinon Vision se compterait).
     */
    fun autreSonEnCours(ctx: Context): Boolean {
        val actif = try { am(ctx).isMusicActive } catch (_: Exception) { false }
        if (actif) return true
        val devant = Usage.dernierPaquet.ifEmpty { try { Usage.premierPlan(ctx) } catch (_: Exception) { null }.orEmpty() }
        return devant.isNotEmpty() && SourcesTele.estEntree(ctx.applicationContext, devant, "")
    }

    // ------------------------------------------------------------ entrées de la télé (point 6a)

    @Volatile var pauseParSource = false; private set
    @Volatile private var dansSource = false
    @Volatile private var dernierPlan = ""

    /**
     * L'appli au premier plan a changé : [pkg], écran [classe] (accessibilité, ou historique d'usage : [activite] sûre).
     * Seules comptent les vraies activités (une bulle ou le volume posés par-dessus ne font pas quitter l'entrée).
     */
    fun premierPlan(ctx: Context, pkg: String, classe: String, activite: Boolean = false) {
        val app = ctx.applicationContext
        main.post {
            val m = moteur ?: return@post
            if (pkg.isEmpty() || (!activite && !SourcesTele.estActivite(app, pkg, classe))) return@post
            val entree = SourcesTele.estEntree(app, pkg, classe)
            val joue = LecteurMusique.etat == "lecture"
            if ("$pkg/$classe" != dernierPlan && (joue || pauseParSource))
                android.util.Log.i("VisionFocus", "premier plan : $pkg/$classe (entrée de la télé : ${if (entree) "oui" else "non"})")
            dernierPlan = "$pkg/$classe"
            if (entree == dansSource) return@post
            dansSource = entree
            when (decisionSource(mode(app), applis(app), reprise(app), entree, pauseParSource, joue)) {
                Action.PAUSE -> { m.pauseFocus(); pauseParSource = true; android.util.Log.i("VisionFocus", "pause : entrée de la télé ($pkg)") }
                Action.REPRENDRE -> { pauseParSource = false; m.repriseFocus(); android.util.Log.i("VisionFocus", "reprise : retour de l'entrée de la télé") }
                else -> if (!entree) pauseParSource = false
            }
        }
    }

    /**
     * Sans accessibilité (accès « usage » seulement) : pendant la lecture, ou après une pause par une entrée, Vision
     * relit l'appli au premier plan toutes les 2 s.
     */
    private val sonder = object : Runnable {
        override fun run() {
            val ctx = ctxApp ?: return
            if (VisionAccessibility.estActive(ctx) || moteur == null) return
            if (LecteurMusique.etat != "lecture" && !pauseParSource) return
            try { Usage.premierPlan(ctx) } catch (_: Exception) { null }?.let { premierPlan(ctx, it, "", activite = true) }
            main.postDelayed(this, SONDER_MS)
        }
    }
    const val SONDER_MS = 2_000L

    private fun sonderSiBesoin(ctx: Context) {
        main.removeCallbacks(sonder)
        if (!VisionAccessibility.estActive(ctx) && Usage.accesUsage(ctx)) main.postDelayed(sonder, SONDER_MS)
    }

    private val verifier = object : Runnable {
        override fun run() {
            val ctx = ctxApp ?: return
            val m = moteur ?: return
            if (!pauseParFocus || !pertePourDeBon || !reprise(ctx)) return
            silences = if (try { am(ctx).isMusicActive } catch (_: Exception) { true }) 0 else silences + 1
            if (silenceSuffisant(silences)) {
                pertePourDeBon = false; pauseParFocus = false
                demander(ctx, m)
                m.repriseFocus()
            } else main.postDelayed(this, VERIFIER_MS)
        }
    }
}
