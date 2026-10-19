// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
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
 *
 * 1.93.06 (retour d'essai) :
 * - **la pochette reste jusqu'à l'arrivée de la suivante** : jamais de case vide entre deux titres. En attendant, l'image
 *   d'attente (le titre suivant, préchargé en grand) ou, à défaut, la dernière pochette montrée pour ce lecteur ;
 * - **fondu de 200 ms, ease-out** (courbe d'entrée de Vision) de l'ancienne vers la nouvelle ; immédiat si les
 *   animations sont supprimées ;
 * - **un vrai cache** : en mémoire, borné en octets (et plus en nombre d'images), libéré à la fermeture du dernier écran
 *   Musique ([liberer]) ; sur disque, gardé d'un écran et d'un lancement à l'autre (borné, les plus anciennes partent
 *   d'abord), vidé seulement à la fin de la Party ([vider]).
 */
object Pochettes {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** En mémoire : au plus 1/8 de la mémoire de l'appli, et jamais plus de 16 Mo (une télé a souvent 2 Go en tout). */
    private val cache = object : android.util.LruCache<String, Bitmap>(memoireMax(Runtime.getRuntime().maxMemory())) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** La dernière pochette posée pour chaque lecteur : elle reste à l'écran jusqu'à l'arrivée de la suivante. */
    private val derniere = ConcurrentHashMap<String, Bitmap>()

    /** La taille du cache en mémoire (octets) pour une appli qui a droit à [maxOctets]. Pur. */
    fun memoireMax(maxOctets: Long): Int = minOf(maxOctets / 8, 16L * 1024 * 1024).coerceAtLeast(2L * 1024 * 1024).toInt()

    /** La durée du fondu entre deux pochettes. */
    const val FONDU_MS = 200L
    private val enCours = ConcurrentHashMap.newKeySet<String>()
    /** Les essais ratés d'une pochette : (nombre, heure du prochain essai). */
    private val essais = ConcurrentHashMap<String, Pair<Int, Long>>()
    /** Délais des nouveaux essais après une réponse vide ou un échec (puis on attend un nouveau titre). */
    val ATTENTES_MS = longArrayOf(800, 1_500, 3_000, 6_000, 12_000, 25_000)

    fun garde(entite: String, empreinte: String): Bitmap? = cache.get("$entite|$empreinte")

    /** 1.93.05 (retour 36, bandeau de la veille) : la pochette tout de suite, gardée ou chargée — hors du fil principal. */
    fun lire(ctx: Context, entite: String, empreinte: String): Bitmap? =
        if (empreinte.isEmpty()) null else garde(entite, empreinte) ?: charger(ctx.applicationContext, entite, "$entite|$empreinte")

    /** Pure : le délai avant le n-ième nouvel essai (n à partir de 1), ou null s'il n'y en a plus. */
    fun attente(n: Int): Long? = ATTENTES_MS.getOrNull(n - 1)

    /**
     * Pose la pochette dans l'image (tout de suite si elle est gardée, sinon dès qu'elle arrive) ; vide sans empreinte.
     * [attente] : l'image montrée en attendant (sinon le fond de la case).
     */
    /** Pur : l'image à montrer en attendant. [garderAncienne] faux : jamais celle du titre d'avant (lecteur TV, 1.93.06). */
    fun enAttendant(attente: Bitmap?, derniere: Bitmap?, garderAncienne: Boolean): Bitmap? = attente ?: derniere.takeIf { garderAncienne }

    fun poser(ctx: Context, vue: ImageView, entite: String, empreinte: String, attente: Bitmap? = null, garderAncienne: Boolean = true) {
        val cle = "$entite|$empreinte"
        if (vue.tag == cle && vue.drawable != null && garde(entite, empreinte) != null) return  // déjà posée
        vue.tag = cle
        // 1.93.06 (TCL, 19/10) : lecteur en pause ou au repos, HA n'envoie pas d'empreinte ; la vignette du titre (si on l'a)
        // plutôt qu'une case vide.
        if (empreinte.isEmpty()) { if (attente != null) vue.setImageBitmap(attente) else vue.setImageDrawable(null); return }
        garde(entite, empreinte)?.let { montrer(vue, entite, it); return }
        // En attendant : le titre suivant préchargé, sinon la pochette d'avant (jamais une case vide).
        val enAttente = enAttendant(attente, derniere[entite], garderAncienne)
        if (enAttente != null) { if ((vue.drawable as? BitmapDrawable)?.bitmap !== enAttente) vue.setImageBitmap(enAttente) }
        else if (!garderAncienne) vue.setImageDrawable(null)  // la case du thème, pas l'image d'un autre titre
        val e = essais[cle]
        if (e != null && System.currentTimeMillis() < e.second) return  // un nouvel essai est déjà prévu
        if (!enCours.add(cle)) return
        val app = ctx.applicationContext
        Thread {
            val bmp = charger(app, entite, cle)
            enCours.remove(cle)
            if (bmp != null) {
                essais.remove(cle)
                main.post { if (vue.tag == cle) montrer(vue, entite, bmp) }
                return@Thread
            }
            // Pas encore d'image (titre qui vient de changer) ou échec : nouvel essai bientôt, si la vue montre encore ce titre.
            val n = (essais[cle]?.first ?: 0) + 1
            // 1.93.06 (essai 9.7 de la TCL) : une pochette qui n'arrive pas se lit dans le journal.
            android.util.Log.i("VisionPochette", "pochette de $entite pas encore là (essai $n${if (attente(n) == null) ", abandon jusqu'au titre suivant" else ""})")
            val delai = attente(n) ?: return@Thread
            essais[cle] = n to (System.currentTimeMillis() + delai)
            main.postDelayed({ if (vue.tag == cle) poser(app, vue, entite, empreinte, attente, garderAncienne) }, delai)
        }.also { it.isDaemon = true }.start()
    }

    /** Pose [bmp] : un fondu depuis ce que la vue montre (l'ancienne pochette), sauf si c'est la même ou rien. */
    private fun montrer(vue: ImageView, entite: String, bmp: Bitmap) {
        derniere[entite] = bmp
        val avant: Drawable? = vue.drawable
        if (avant == null || (avant as? BitmapDrawable)?.bitmap === bmp || !Anim.actif(vue.context) || !vue.isAttachedToWindow) {
            vue.setImageBitmap(bmp); return
        }
        val bas = (avant as? LayerDrawable)?.let { it.getDrawable(it.numberOfLayers - 1) } ?: avant
        val haut = BitmapDrawable(vue.resources, bmp).apply { alpha = 0 }
        vue.setImageDrawable(LayerDrawable(arrayOf(bas, haut)))
        android.animation.ValueAnimator.ofInt(0, 255).apply {
            duration = FONDU_MS
            interpolator = Anim.interpolateurEntree
            addUpdateListener { haut.alpha = it.animatedValue as Int; vue.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if ((vue.drawable as? LayerDrawable)?.getDrawable(1) === haut) vue.setImageBitmap(bmp) }
            })
            start()
        }
    }

    private fun charger(app: Context, entite: String, cle: String): Bitmap? {
        CachePochettes.lire(app, cle)?.let { o -> decoder(o)?.let { b -> cache.put(cle, b); return b } }
        val octets = try {
            val cfg = Config(app)
            Net.postBytes(app, cfg, "/api/pc_parental/party/pochette",
                JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entite", entite), 10_000)
        } catch (_: Exception) { null }
        if (octets == null || octets.isEmpty()) return null
        val bmp = decoder(octets) ?: return null
        cache.put(cle, bmp)
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

    /** Essais : une pochette gardée en mémoire, comme si elle venait d'arriver. */
    internal fun essaiGarder(entite: String, empreinte: String, b: Bitmap) { cache.put("$entite|$empreinte", b) }

    /** Fermeture du dernier écran Musique : la mémoire est rendue ; le disque reste (réouverture sans rechargement). */
    fun liberer() {
        cache.evictAll()
        derniere.clear()
    }

    /** Fin de la Party : tout est déchargé, mémoire et disque. */
    fun vider(ctx: Context) {
        liberer()
        essais.clear()
        CachePochettes.vider(ctx)
    }
}
