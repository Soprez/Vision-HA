// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.91.0 (retour d'essai) : la musique de la veille — une playlist favorite de Music Assistant, jouée par le lecteur « · Vision »
 * de cet appareil pendant l'écran de veille (Home Assistant 2.4.8, pc_parental/veille_musique.py).
 *
 * Règles de l'auteur :
 * - option par appareil, désactivée par défaut ([choisie] : rien tant qu'aucune playlist n'est choisie) ;
 * - la musique lancée par la veille s'arrête TOUJOURS en sortie de veille ([fin], appelé par VeilleView.arreter) ;
 * - si le lecteur de l'appareil joue déjà, sa musique continue et la veille ne lance rien (HA le vérifie aussi) ;
 * - autoplay coupé, volume à 100 % (Home Assistant) ;
 * - la nuit, ou quand une autre appli joue, rien n'est lancé (comme la radio de la veille).
 *
 * Pendant la veille, l'appareil redit « maintenir » toutes les [MAINTENIR_MS] ; sans nouvelles, HA arrête lui-même
 * la musique (appli fermée ou plantée). La radio de la veille se tait quand une playlist est choisie.
 */
object VeilleAudio {
    private const val PREFS = "local"
    private const val CLE_URI = "veille_playlist_uri"
    private const val CLE_NOM = "veille_playlist_nom"
    const val MAINTENIR_MS = 120_000L
    /** Pour les essais : l'intervalle de « maintenir ». */
    @Volatile var maintenirMs = MAINTENIR_MS

    /** Une veille a demandé sa musique (jusqu'à [fin]) : une seule demande par veille. */
    @Volatile var demandee = false; private set
    /** Home Assistant a lancé la musique pour cette veille : elle ne compte pas comme « le lecteur joue » (VeilleMusique). */
    @Volatile var lancee = false; private set

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var appli: Context? = null
    /**
     * l'auteur (17/10, 1.93.02 : « la playlist de la veille s'est arrêtée après le deuxième ou troisième morceau ») : ce rappel
     * tourne sur le fil principal, où Android refuse le réseau (NetworkOnMainThreadException, avalée par [envoyer]) ;
     * « maintenir » n'arrivait jamais et HA arrêtait la musique au bout de 6 min (EXPIRE_S). L'envoi part sur un fil à lui.
     */
    private val maintenir = object : Runnable {
        override fun run() {
            val c = appli ?: return
            if (!demandee) return
            Thread { envoyer(c, JSONObject().put("action", "maintenir")) }.apply { isDaemon = true }.start()
            main.postDelayed(this, maintenirMs)
        }
    }

    /** [favori] : marquée d'un cœur dans Music Assistant ; [source] : le fournisseur (YouTube Music…) d'un résultat de recherche. */
    data class Playlist(val uri: String, val nom: String, val image: String = "", val favori: Boolean = false, val source: String = "")

    fun choisie(ctx: Context): Playlist? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = p.getString(CLE_URI, "") ?: ""
        return if (uri.isEmpty()) null else Playlist(uri, p.getString(CLE_NOM, "") ?: "")
    }

    /** Choisir la playlist de la veille (null : désactivée). */
    fun choisir(ctx: Context, pl: Playlist?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLE_URI, pl?.uri ?: "").putString(CLE_NOM, pl?.nom ?: "").apply()
        Local.toucher()
    }

    /**
     * Ce que la veille doit faire à son début : rien (pas de playlist choisie, nuit, autre appli, lecteur qui joue
     * déjà) ou demander la playlist. Pur, pour les essais.
     */
    fun decider(choisie: Boolean, nuit: Boolean, autreAppli: Boolean, lecteurJoue: Boolean, dejaDemandee: Boolean): Boolean =
        choisie && !nuit && !autreAppli && !lecteurJoue && !dejaDemandee

    /** Début de veille (appelé à chaque rafraîchissement : une seule demande par veille). */
    fun debut(ctx: Context, nuit: Boolean, autreAppli: Boolean) {
        val pl = choisie(ctx) ?: return
        if (!decider(true, nuit, autreAppli, VeilleMusique.enLecture(), demandee)) return
        demandee = true; lancee = false
        val c = ctx.applicationContext; appli = c
        Thread {
            val r = envoyer(c, JSONObject().put("action", "debut").put("uri", pl.uri))
            // La veille a pu finir pendant l'appel : fin() a déjà été envoyée après celui-ci (même fil d'envoi).
            if (r?.optBoolean("lance") == true && demandee) lancee = true
        }.apply { isDaemon = true }.start()
        main.removeCallbacks(maintenir); main.postDelayed(maintenir, maintenirMs)
    }

    /** Sortie de veille : la musique qu'elle a lancée s'arrête toujours (HA ne touche à rien s'il n'a rien lancé). */
    fun fin(ctx: Context) {
        if (!demandee) return
        demandee = false
        val avait = lancee
        lancee = false
        main.removeCallbacks(maintenir)
        val c = ctx.applicationContext
        if (avait) VeilleMusique.oublier()
        Thread { envoyer(c, JSONObject().put("action", "fin")) }.apply { isDaemon = true }.start()
    }

    /** Les playlists de Music Assistant, favorites en premier (pour le réglage) ; une exception parlante si HA ne répond pas. */
    fun playlists(ctx: Context): List<Playlist> {
        val cfg = Config(ctx)
        val r = Net.post(ctx, cfg, "/api/pc_parental/veille/musique", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("action", "playlists"), 9_000)
        return analyser(r.optJSONArray("items"))
    }

    /** l'auteur (15/10) : les playlists de tous les fournisseurs connectés (YouTube Music…), par leur nom. */
    fun recherche(ctx: Context, texte: String): List<Playlist> {
        val cfg = Config(ctx)
        val r = Net.post(ctx, cfg, "/api/pc_parental/veille/musique", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("action", "recherche").put("texte", texte), 12_000)
        return analyser(r.optJSONArray("items"))
    }

    fun analyser(a: JSONArray?): List<Playlist> {
        if (a == null) return emptyList()
        return garder((0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Playlist(it.optString("uri"), it.optString("nom"), it.optString("image"), it.optBoolean("favori"), it.optString("source")) } })
    }

    /** Seulement les playlists lisibles (uri et nom), dans l'ordre de Music Assistant. */
    fun garder(l: List<Playlist>): List<Playlist> = l.filter { it.uri.isNotEmpty() && it.nom.isNotEmpty() }

    // Un seul fil d'envoi à la fois : « fin » part toujours après le « debut » de la même veille.
    @Synchronized private fun envoyer(ctx: Context, corps: JSONObject): JSONObject? = try {
        val cfg = Config(ctx)
        Net.post(ctx, cfg, "/api/pc_parental/veille/musique", corps.put("id", cfg.id).put("secret", cfg.secret), 9_000)
    } catch (_: Exception) { null }

    /** Pour les essais. */
    fun oublier() { demandee = false; lancee = false; main.removeCallbacks(maintenir) }
}
