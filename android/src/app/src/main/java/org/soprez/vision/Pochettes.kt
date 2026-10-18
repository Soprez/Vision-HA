// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.81.0 (point 14 bis) : les pochettes des lecteurs (widget Lecteur, écran Party), lues par Vision dans Home
 * Assistant (/api/pc_parental/party/pochette) avec l'identifiant de l'appareil : jamais l'adresse de l'image ni
 * son jeton. Une empreinte (`pochette`, dans les attributs) dit quand le morceau change ; on garde les dernières.
 *
 * 1.90.2 (essai de l'auteur, Party sur la télé du bureau : « carré gris après Suivant, la pochette arrive une minute
 * après ») : juste après un changement de titre, Home Assistant n'a parfois pas encore l'image (réponse vide ou
 * délai). Avant, cette absence était retenue pour de bon ; maintenant on réessaie vite ([ATTENTES_MS]) et l'image se
 * pose d'elle-même. Pendant l'attente, une image d'attente (la vignette du titre, déjà chargée avec la file) remplace
 * le carré gris. Les pochettes sont aussi gardées sur disque ([CachePochettes]), vidées avec [vider].
 */
object Pochettes {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }
    private val enCours = ConcurrentHashMap.newKeySet<String>()
    /** Les essais ratés d'une pochette : (nombre, heure du prochain essai). */
    private val essais = ConcurrentHashMap<String, Pair<Int, Long>>()
    /** Délais des nouveaux essais après une réponse vide ou un échec (puis on attend un nouveau titre). */
    val ATTENTES_MS = longArrayOf(800, 1_500, 3_000, 6_000, 12_000, 25_000)

    fun garde(entite: String, empreinte: String): Bitmap? = synchronized(cache) { cache["$entite|$empreinte"] }

    /** 1.93.05 (retour 36, bandeau de la veille) : la pochette tout de suite, gardée ou chargée — hors du fil principal. */
    fun lire(ctx: Context, entite: String, empreinte: String): Bitmap? =
        if (empreinte.isEmpty()) null else garde(entite, empreinte) ?: charger(ctx.applicationContext, entite, "$entite|$empreinte")

    /** Pure : le délai avant le n-ième nouvel essai (n à partir de 1), ou null s'il n'y en a plus. */
    fun attente(n: Int): Long? = ATTENTES_MS.getOrNull(n - 1)

    /**
     * Pose la pochette dans l'image (tout de suite si elle est gardée, sinon dès qu'elle arrive) ; vide sans empreinte.
     * [attente] : l'image montrée en attendant (sinon le fond de la case).
     */
    fun poser(ctx: Context, vue: ImageView, entite: String, empreinte: String, attente: Bitmap? = null) {
        val cle = "$entite|$empreinte"
        vue.tag = cle
        if (empreinte.isEmpty()) { vue.setImageDrawable(null); return }
        garde(entite, empreinte)?.let { vue.setImageBitmap(it); return }
        if (attente != null) vue.setImageBitmap(attente) else vue.setImageDrawable(null)
        val e = essais[cle]
        if (e != null && System.currentTimeMillis() < e.second) return  // un nouvel essai est déjà prévu
        if (!enCours.add(cle)) return
        val app = ctx.applicationContext
        Thread {
            val bmp = charger(app, entite, cle)
            enCours.remove(cle)
            if (bmp != null) {
                essais.remove(cle)
                main.post { if (vue.tag == cle) vue.setImageBitmap(bmp) }
                return@Thread
            }
            // Pas encore d'image (titre qui vient de changer) ou échec : nouvel essai bientôt, si la vue montre encore ce titre.
            val n = (essais[cle]?.first ?: 0) + 1
            val delai = attente(n) ?: return@Thread
            essais[cle] = n to (System.currentTimeMillis() + delai)
            main.postDelayed({ if (vue.tag == cle) poser(app, vue, entite, empreinte, attente) }, delai)
        }.also { it.isDaemon = true }.start()
    }

    private fun charger(app: Context, entite: String, cle: String): Bitmap? {
        CachePochettes.lire(app, cle)?.let { o -> decoder(o)?.let { b -> synchronized(cache) { cache[cle] = b }; return b } }
        val octets = try {
            val cfg = Config(app)
            Net.postBytes(app, cfg, "/api/pc_parental/party/pochette",
                JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entite", entite), 10_000)
        } catch (_: Exception) { null }
        if (octets == null || octets.isEmpty()) return null
        val bmp = decoder(octets) ?: return null
        synchronized(cache) { cache[cle] = bmp }
        CachePochettes.ecrire(app, cle, octets)
        return bmp
    }

    private fun decoder(o: ByteArray): Bitmap? = try { BitmapFactory.decodeByteArray(o, 0, o.size) } catch (_: Exception) { null }

    /**
     * Campagne Musique (15-10) : les écrans Musique ouverts (lecteur, choix de la musique). Un écran fermé n'est
     * détruit qu'après l'ouverture du suivant (souvent 0,3 à 0,5 s plus tard) : sa fermeture vidait alors les pochettes
     * que le nouvel écran venait de charger (fond animé resté aux couleurs du thème, vignettes jamais posées). On ne
     * décharge donc qu'à la fermeture du dernier écran Musique.
     */
    private val ecrans = java.util.concurrent.atomic.AtomicInteger()

    fun ecranOuvert() { ecrans.incrementAndGet() }

    /** Un écran Musique détruit : vrai s'il se ferme pour de bon et qu'aucun autre n'est ouvert (on peut décharger). */
    fun ecranFerme(fin: Boolean): Boolean = ecrans.updateAndGet { maxOf(0, it - 1) } == 0 && fin

    /** Fin de la Party, fermeture du dernier écran Musique, démarrage de l'appli : tout est déchargé. */
    fun vider(ctx: Context) {
        synchronized(cache) { cache.clear() }
        essais.clear()
        CachePochettes.vider(ctx)
    }
}
