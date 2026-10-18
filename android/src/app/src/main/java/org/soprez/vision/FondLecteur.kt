// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * 1.91.0 (l'auteur : « une animation un peu procédurale qu'on pourrait choisir, qui va avec le thème choisi ») : le fond
 * animé de l'écran Musique, la partie sans Android (essayée en JUnit, FondLecteurTest). La vue : [FondLecteurVue].
 *
 * - Styles : ondes douces, particules, aurore, dégradé vivant ; ou aucun (le fond uni du thème).
 * - Couleurs : celles du thème (lueurs, accent, or) ; en option, celles de la pochette du titre en cours.
 * - Intensité : douce, moyenne, vive (mouvement et opacité).
 * - Réactif au son quand cet appareil joue lui-même la musique (lecteur Sendspin : niveau lu sur le son qu'il produit) ;
 *   ailleurs, le mouvement reste régulier.
 * - Léger : cadence réduite sur une télé (et plus encore sur un appareil modeste, Chromecast), arrêt quand l'écran
 *   n'est plus visible, une seule image fixe quand le système demande de réduire les animations.
 * Réglé sur l'appareil, dans l'écran Musique.
 */
object FondLecteur {
    enum class Style(val cle: String) { AUCUN("aucun"), ONDES("ondes"), PARTICULES("particules"), AURORE("aurore"), DEGRADE("degrade") }
    enum class Intensite(val cle: String, val mouvement: Float, val opacite: Float) {
        DOUCE("douce", 0.55f, 0.55f), MOYENNE("moyenne", 1f, 0.8f), VIVE("vive", 1.6f, 1f)
    }

    val STYLE_DEFAUT = Style.ONDES
    val INTENSITE_DEFAUT = Intensite.MOYENNE

    fun style(cle: String?): Style = Style.values().firstOrNull { it.cle == cle } ?: STYLE_DEFAUT
    fun intensite(cle: String?): Intensite = Intensite.values().firstOrNull { it.cle == cle } ?: INTENSITE_DEFAUT

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("party", Context.MODE_PRIVATE)
    fun style(ctx: Context): Style = style(prefs(ctx).getString("fond_style", null))
    fun intensite(ctx: Context): Intensite = intensite(prefs(ctx).getString("fond_intensite", null))
    fun pochette(ctx: Context): Boolean = prefs(ctx).getBoolean("fond_pochette", true)
    fun son(ctx: Context): Boolean = prefs(ctx).getBoolean("fond_son", true)
    fun poserStyle(ctx: Context, s: Style) = prefs(ctx).edit().putString("fond_style", s.cle).apply()
    fun poserIntensite(ctx: Context, i: Intensite) = prefs(ctx).edit().putString("fond_intensite", i.cle).apply()
    fun poserPochette(ctx: Context, oui: Boolean) = prefs(ctx).edit().putBoolean("fond_pochette", oui).apply()
    fun poserSon(ctx: Context, oui: Boolean) = prefs(ctx).edit().putBoolean("fond_son", oui).apply()

    fun nomStyle(ctx: Context, s: Style): String = ctx.getString(when (s) {
        Style.AUCUN -> R.string.fond_style_aucun; Style.ONDES -> R.string.fond_style_ondes; Style.PARTICULES -> R.string.fond_style_particules
        Style.AURORE -> R.string.fond_style_aurore; Style.DEGRADE -> R.string.fond_style_degrade
    })

    fun nomIntensite(ctx: Context, i: Intensite): String = ctx.getString(when (i) {
        Intensite.DOUCE -> R.string.fond_intensite_douce; Intensite.MOYENNE -> R.string.fond_intensite_moyenne; Intensite.VIVE -> R.string.fond_intensite_vive
    })

    /** Le résumé d'une ligne de réglage : « Aurore · moyenne », ou « Aucun ». */
    fun resume(ctx: Context): String {
        val s = style(ctx)
        return if (s == Style.AUCUN) nomStyle(ctx, s) else nomStyle(ctx, s) + " · " + nomIntensite(ctx, intensite(ctx))
    }

    /** Images par seconde : un mouvement lent n'en demande pas plus ; une télé modeste (Chromecast) encore moins. */
    fun cadence(tele: Boolean, modeste: Boolean): Int = when {
        modeste -> 15
        tele -> 24
        else -> 30
    }

    // ------------------------------------------------------------------ couleurs (sans android.graphics)

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun melange(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun m(x: Int, y: Int) = (x + (y - x) * u).toInt()
        return rgb(m(r(a), r(b)), m(g(a), g(b)), m(this.b(a), this.b(b)))
    }
    fun luminance(c: Int): Float = (0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)) / 255f
    /** Saturation (0 à 1) au sens HSV. */
    fun saturation(c: Int): Float {
        val mx = maxOf(r(c), g(c), b(c)); val mn = minOf(r(c), g(c), b(c))
        return if (mx == 0) 0f else (mx - mn) / mx.toFloat()
    }
    /** Teinte (0 à 360°). */
    fun teinte(c: Int): Float {
        val rr = r(c) / 255f; val gg = g(c) / 255f; val bb = b(c) / 255f
        val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb); val d = mx - mn
        if (d == 0f) return 0f
        val h = when (mx) { rr -> ((gg - bb) / d) % 6f; gg -> (bb - rr) / d + 2f; else -> (rr - gg) / d + 4f }
        return ((h * 60f) + 360f) % 360f
    }

    /**
     * Les trois couleurs d'une pochette : les teintes les plus présentes parmi les pixels assez vifs (12 familles de
     * teinte, pondérées par la vivacité), la plus présente d'abord. Moins de trois familles : on complète à partir de
     * la première. Rien de vif (pochette grise) : null — on garde le thème.
     */
    fun couleursPochette(pixels: IntArray): IntArray? {
        val poids = FloatArray(12); val sommeR = FloatArray(12); val sommeG = FloatArray(12); val sommeB = FloatArray(12)
        for (c in pixels) {
            if ((c ushr 24) < 0x80) continue
            val s = saturation(c); val v = maxOf(r(c), g(c), b(c)) / 255f
            if (s < 0.25f || v < 0.2f) continue
            val k = (teinte(c) / 30f).toInt().coerceIn(0, 11)
            val w = s * v
            poids[k] += w; sommeR[k] += r(c) * w; sommeG[k] += g(c) * w; sommeB[k] += b(c) * w
        }
        val total = poids.sum()
        if (total <= 0f) return null
        val ordre = (0 until 12).filter { poids[it] >= total * 0.04f }.sortedByDescending { poids[it] }.take(3)
        if (ordre.isEmpty()) return null
        val l = ordre.map { k -> rgb((sommeR[k] / poids[k]).toInt(), (sommeG[k] / poids[k]).toInt(), (sommeB[k] / poids[k]).toInt()) }.toMutableList()
        while (l.size < 3) l.add(melange(l[0], if (l.size == 1) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.35f))
        return l.toIntArray()
    }

    /**
     * Les couleurs du fond : le fond du thème, puis trois teintes (lueurs du thème, ou pochette ramenée vers le fond
     * pour rester lisible). Sur un thème sombre, les teintes ne dépassent jamais une clarté qui gênerait le texte ;
     * sur un thème clair, elles restent pâles.
     */
    fun couleurs(fond: Int, halos: IntArray, accent: Int, sombre: Boolean, pochette: IntArray?): IntArray {
        val base = pochette ?: intArrayOf(
            (halos.getOrElse(0) { accent }) or 0xFF000000.toInt(),
            (halos.getOrElse(1) { accent }) or 0xFF000000.toInt(),
            melange(accent, (halos.getOrElse(2) { accent }) or 0xFF000000.toInt(), 0.5f),
        )
        return intArrayOf(fond) + base.map { c -> adoucir(c, fond, sombre) }
    }

    /** Une teinte ramenée vers le fond jusqu'à garder le texte lisible par-dessus. */
    fun adoucir(c: Int, fond: Int, sombre: Boolean): Int {
        var x = c or 0xFF000000.toInt()
        // Sombre : pas plus claire que 0.42 de luminance ; clair : pas plus sombre que 0.62.
        var k = 0
        while (k < 10 && (if (sombre) luminance(x) > 0.42f else luminance(x) < 0.62f)) { x = melange(x, fond, 0.2f); k++ }
        return x
    }

    /** L'opacité d'une forme selon l'intensité et le niveau sonore (0 à 1) ; jamais au-delà d'un voile. */
    fun opacite(base: Float, i: Intensite, niveau: Float): Float = (base * i.opacite * (1f + 0.6f * niveau.coerceIn(0f, 1f))).coerceIn(0f, 0.85f)

    /** Un nombre de 0 à 1, toujours le même pour une particule [i] et un paramètre [k]. */
    fun hasard(i: Int, k: Int): Float {
        var x = i * 374761393 + k * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0x7FFFFFFF) / 2147483647f
    }

    /** Un bruit doux et déterministe (somme de sinus) : le même instant donne toujours la même image. */
    fun bruit(x: Float, t: Float, graine: Int): Float {
        val a = graine * 1.7f
        return (Math.sin((x * 1.3f + t * 0.9f + a).toDouble()) * 0.5 +
            Math.sin((x * 2.9f - t * 0.6f + a * 2.1f).toDouble()) * 0.3 +
            Math.sin((x * 5.3f + t * 0.35f + a * 0.7f).toDouble()) * 0.2).toFloat()
    }
}

/**
 * 17-10 (l'auteur, point 17 : « l'arrière-plan animé est SACCADÉ sur la TCL, Android 9 peu puissant ») : le profil de
 * performance du fond, choisi et ajusté tout seul. Il part léger sur un appareil modeste (peu de mémoire) ou sur une
 * télé d'Android 9 ou plus ancien ; puis il mesure l'écart RÉEL entre deux images : si l'appareil ne tient pas la cadence
 * (en moyenne 1,5 fois l'intervalle visé, sur 30 images), il descend d'un cran — moins d'images par seconde et moins de
 * formes à dessiner. Il ne remonte jamais pendant l'écran (pas de va-et-vient). Logique pure (testée en JVM).
 */
class ProfilFond(private val tele: Boolean, modeste: Boolean, ancien: Boolean) {
    /** 0 : normal ; 1 : léger ; 2 : très léger. */
    var niveau = if (modeste || (tele && ancien)) 1 else 0
        private set
    /** La moyenne mesurée de l'écart entre deux images (ms), pour le journal. */
    var moyenneMs = 0f
        private set
    private var n = 0

    fun cadence(): Int = when (niveau) { 0 -> FondLecteur.cadence(tele, false); 1 -> 15; else -> 10 }

    /** La part des formes dessinées (points d'une vague, particules) : tout au niveau 0. */
    fun detail(): Float = when (niveau) { 0 -> 1f; 1 -> 0.6f; else -> 0.4f }

    /** Note l'écart réel entre deux images ; rend vrai si le profil vient de descendre d'un cran. */
    fun noter(ecartMs: Long): Boolean {
        // Une pause (écran caché, rebâti, animation coupée) ne dit rien de la vitesse de l'appareil.
        if (ecartMs <= 0 || ecartMs > 1000) return false
        moyenneMs = if (n == 0) ecartMs.toFloat() else moyenneMs * 0.9f + ecartMs * 0.1f
        n++
        if (n >= 30 && moyenneMs > 1000f / cadence() * 1.5f && niveau < 2) { niveau++; n = 0; return true }
        return false
    }
}

/**
 * 1.91.0 : le niveau du son que produit CET appareil (lecteur Sendspin), pour le fond « réactif au son ». Mesuré sur
 * les échantillons écrits vers la sortie, sans permission ni micro. Rien ne joue ici depuis une demi-seconde : 0.
 */
object NiveauSon {
    @Volatile private var niveau = 0f
    @Volatile private var quand = 0L

    /** Le niveau (0 à 1) d'un bloc d'échantillons 16 bits : moyenne quadratique, un échantillon sur [pas], courbe douce. */
    fun mesurer(pcm: ShortArray, pas: Int = 4): Float {
        if (pcm.isEmpty()) return 0f
        var s = 0.0; var n = 0
        var i = 0
        while (i < pcm.size) { val x = pcm[i] / 32768.0; s += x * x; n++; i += pas }
        val rms = Math.sqrt(s / n)
        // Une musique « normale » tourne autour de 0,1 à 0,3 de RMS : on l'étale sur 0 à 1.
        return Math.sqrt((rms / 0.35).coerceIn(0.0, 1.0)).toFloat()
    }

    fun noter(pcm: ShortArray, maintenant: Long) {
        val m = mesurer(pcm)
        // Monte vite, redescend doucement.
        niveau = if (m > niveau) niveau + (m - niveau) * 0.6f else niveau + (m - niveau) * 0.15f
        quand = maintenant
    }

    fun niveau(maintenant: Long): Float = if (maintenant - quand > 500) 0f else niveau
}
