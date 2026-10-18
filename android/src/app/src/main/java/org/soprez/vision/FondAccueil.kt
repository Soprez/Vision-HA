// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.math.pow

/**
 * 1.91.x (l'auteur, backlog de la refonte) : l'arrière-plan de l'accueil, réglé PAR APPAREIL.
 *
 * - [Sorte.DEFAUT] : le fond animé discret d'aujourd'hui (FondAnime), aux couleurs du thème ;
 * - [Sorte.UNI] : une couleur unie tirée du thème (fond, ou une de ses lueurs ramenée vers le fond) ;
 * - [Sorte.DEGRADE] : un dégradé à deux teintes du thème ;
 * - [Sorte.PHOTO] : une photo de la galerie de l'appareil, recadrée à l'écran, gardée en cache sur l'appareil ;
 * - [Sorte.ANIME] : le fond procédural du lecteur (FondLecteurVue), dans un de ses styles.
 *
 * Lisibilité : un VOILE de la couleur de fond du thème est posé par-dessus, juste assez opaque pour que TOUS les textes
 * de l'accueil (titres : encre ; noms d'applis : encre2) gardent un contraste d'au moins [CONTRASTE_MIN] (4,5:1) avec le
 * fond le plus défavorable ([voile]) ; au plus [VOILE_MAX] (85 % : une photo blanche sous un thème sombre). Le Décideur peut le renforcer ([renfort]), jamais le baisser sous le minimum. Les cartes gardent
 * leur propre fond opaque.
 *
 * Qui choisit : le Décideur (code des Décideurs) ; le Demandeur aussi si le Décideur l'a permis sur cet appareil
 * ([permisDemandeur]). Rien en dur : les couleurs viennent du thème, les libellés des catalogues de traduction.
 */
object FondAccueil {
    enum class Sorte(val cle: String, val nom: Int) {
        DEFAUT("defaut", R.string.fondacc_sorte_defaut), UNI("uni", R.string.fondacc_sorte_uni), DEGRADE("degrade", R.string.fondacc_sorte_degrade),
        PHOTO("photo", R.string.fondacc_sorte_photo), ANIME("anime", R.string.fondacc_sorte_anime)
    }

    const val CONTRASTE_MIN = 4.5
    const val VOILE_MAX = 0.85f
    const val TAG = "fond_accueil"
    const val TAG_VOILE = "fond_accueil_voile"
    private const val PREFS = "local"
    private const val FICHIER_PHOTO = "fond_accueil.jpg"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sorte(ctx: Context): Sorte = Sorte.values().firstOrNull { it.cle == p(ctx).getString("fond_sorte", null) } ?: Sorte.DEFAUT
    /** Pour UNI : 0 = le fond du thème, 1 à 3 = ses lueurs (ramenées vers le fond). */
    fun teinte(ctx: Context): Int = p(ctx).getInt("fond_teinte", 0)
    /** Renfort du voile choisi par le Décideur : 0, 0,15 ou 0,30 de plus. */
    fun renfort(ctx: Context): Float = p(ctx).getFloat("fond_renfort", 0f)
    fun permisDemandeur(ctx: Context): Boolean = p(ctx).getBoolean("fond_demandeur", false)
    /** Le style du fond animé de l'accueil (à part de celui de l'écran Musique). */
    fun styleAnime(ctx: Context): FondLecteur.Style = FondLecteur.style(p(ctx).getString("fond_anime_style", FondLecteur.Style.ONDES.cle))
    fun poserStyleAnime(ctx: Context, st: FondLecteur.Style) { p(ctx).edit().putString("fond_anime_style", st.cle).putString("fond_sorte", Sorte.ANIME.cle).apply(); Local.toucher() }

    fun poser(ctx: Context, s: Sorte, teinte: Int? = null) {
        p(ctx).edit().putString("fond_sorte", s.cle).apply { if (teinte != null) putInt("fond_teinte", teinte) }.apply()
        Local.toucher()
    }
    fun poserRenfort(ctx: Context, r: Float) { p(ctx).edit().putFloat("fond_renfort", r.coerceIn(0f, 0.3f)).apply(); Local.toucher() }
    fun poserPermisDemandeur(ctx: Context, oui: Boolean) { p(ctx).edit().putBoolean("fond_demandeur", oui).apply(); Local.toucher() }

    fun fichierPhoto(ctx: Context): File = File(ctx.filesDir, FICHIER_PHOTO)

    // ------------------------------------------------------------------ lisibilité (pur, essayé en JVM)

    /** Luminance relative WCAG d'une couleur (0 à 1). */
    fun luminance(c: Int): Double {
        fun canal(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * canal(c shr 16 and 0xFF) + 0.7152 * canal(c shr 8 and 0xFF) + 0.0722 * canal(c and 0xFF)
    }

    fun contraste(a: Int, b: Int): Double { val la = luminance(a); val lb = luminance(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }

    /** [dessous] vu à travers un voile [voile] d'opacité [a]. */
    fun melange(dessous: Int, voile: Int, a: Float): Int {
        fun m(d: Int) = ((dessous shr d and 0xFF) + ((voile shr d and 0xFF) - (dessous shr d and 0xFF)) * a).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /**
     * Le voile le plus léger (0 à [VOILE_MAX]) pour que chacun des [textes] garde [CONTRASTE_MIN] sur CHACUNE des
     * couleurs [fonds] (les plus défavorables d'une photo, ou les teintes d'un fond animé), plus le [renfort] du Décideur.
     */
    fun voile(fonds: List<Int>, voile: Int, textes: List<Int>, renfort: Float = 0f): Float {
        var a = 0f
        // Une marge de 0,1 au-dessus du seuil : la photo gardée en JPEG et l'arrondi des couleurs ne le font pas passer dessous.
        while (a < VOILE_MAX && fonds.any { f -> textes.any { contraste(melange(f, voile, a), it) < CONTRASTE_MIN + 0.1 } }) a += 0.01f
        return (minOf(a, VOILE_MAX) + renfort).coerceIn(0f, 0.95f)
    }

    /**
     * Les couleurs qui comptent pour la lisibilité d'une image : une grille de 32 × 32 points (les zones les plus claires
     * ou les plus sombres ne passent pas entre les mailles), sans doublons.
     */
    fun echantillon(b: Bitmap): List<Int> {
        val r = LinkedHashSet<Int>()
        for (y in 0 until 32) for (x in 0 until 32) r.add(b.getPixel((x * 2 + 1) * b.width / 64, (y * 2 + 1) * b.height / 64))
        return r.toList()
    }

    // ------------------------------------------------------------------ vues

    private fun versFond(c: Int, th: Theme, part: Float) = Palette.melanger(th.fond, c or 0xFF000000.toInt(), part)

    /** Les teintes proposées pour UNI : le fond du thème, puis ses lueurs ramenées vers le fond. */
    fun teintes(th: Theme): List<Int> = listOf(th.fond) + th.halos.map { versFond(it, th, if (th.sombre) 0.45f else 0.35f) }

    /**
     * Le fond de l'accueil pour [th] : la vue du fond et, par-dessus, le voile calculé (rien si le fond est déjà
     * assez calme). [tele] : cadence du fond animé.
     */
    fun vue(ctx: Context, th: Theme, tele: Boolean): View {
        val boite = FrameLayout(ctx).apply { tag = TAG }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val s = sorte(ctx)
        var dessous: List<Int> = listOf(th.fond)
        when (s) {
            Sorte.DEFAUT -> boite.addView(FondAnime(ctx) { th }, lp)
            Sorte.UNI -> {
                val c = teintes(th).getOrElse(teinte(ctx)) { th.fond }
                boite.addView(View(ctx).apply { setBackgroundColor(c) }, lp); dessous = listOf(c)
            }
            Sorte.DEGRADE -> {
                val a = th.fond; val b = versFond(th.halos.getOrElse(teinte(ctx).coerceAtLeast(1) - 1) { th.pourpre }, th, if (th.sombre) 0.55f else 0.45f)
                boite.addView(View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)) }, lp)
                dessous = listOf(a, b, Palette.melanger(a, b, 0.5f))
            }
            Sorte.PHOTO -> {
                val img = try { BitmapFactory.decodeFile(fichierPhoto(ctx).absolutePath) } catch (_: Exception) { null }
                if (img == null) boite.addView(FondAnime(ctx) { th }, lp)
                else {
                    boite.addView(ImageView(ctx).apply { setImageBitmap(img); scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp)
                    dessous = echantillon(img)
                }
            }
            Sorte.ANIME -> {
                val v = FondLecteurVue(ctx, th, tele).apply { style = styleAnime(ctx) }
                boite.addView(v, lp)
                dessous = v.couleurs().toList().map { it or 0xFF000000.toInt() } + th.fond
            }
        }
        val a = voile(dessous, th.fond, listOf(th.encre, th.encre2), renfort(ctx))
        if (a > 0f) boite.addView(View(ctx).apply { tag = TAG_VOILE; setBackgroundColor(Color.argb((a * 255).toInt(), Color.red(th.fond), Color.green(th.fond), Color.blue(th.fond))) }, lp)
        boite.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return boite
    }

    /**
     * Une photo choisie : réduite à la taille de l'écran (côté le plus long au plus [cote] px), recadrée au format de
     * l'écran au moment de l'affichage, gardée en JPEG dans les fichiers de l'appli (hors ligne, sans autorisation).
     */
    fun garderPhoto(ctx: Context, source: Bitmap, cote: Int): Boolean = try {
        val k = cote.toFloat() / maxOf(source.width, source.height)
        val b = if (k < 1f) Bitmap.createScaledBitmap(source, (source.width * k).toInt(), (source.height * k).toInt(), true) else source
        fichierPhoto(ctx).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    } catch (_: Exception) { false }

    /** Pour les essais : une image d'essai très claire (le cas difficile pour le texte). */
    fun imageClaire(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b); c.drawColor(0xFFF4F1EA.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        for (i in 0 until 12) c.drawCircle(w * (i % 4 + 0.5f) / 4f, h * (i / 4 + 0.5f) / 3f, minOf(w, h) / 6f, p)
    }
}
