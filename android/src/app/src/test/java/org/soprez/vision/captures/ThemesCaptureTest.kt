// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.DispositionAccueil
import org.soprez.vision.LanceurActivity
import org.soprez.vision.Local
import org.soprez.vision.Palette
import org.soprez.vision.ReglagesTvActivity
import org.soprez.vision.Theme
import org.soprez.vision.ZoneHaut

/**
 * 17-10 (l'auteur, point 19, IMPORTANT) : tout suit les couleurs du thème. Chaque écran est affiché avec trois thèmes
 * contrastés (Écrin : sombre et or ; Beige : clair ; Corsaire : bleu nuit) et l'on repère :
 * A. un FOND hors de la famille du thème — un fond clair sur un thème sombre (ou l'inverse), qui n'est pas un accent du
 *    thème (or, pourpre, encre, vert, rouge, bleu) : c'est le « bouton blanc » sur le thème sombre et or ;
 * B. un TEXTE illisible : contraste de moins de 2,5 pour 1 avec le fond qui est réellement derrière lui (un texte
 *    blanc sur fond clair, ou foncé sur fond sombre). L'encre tertiaire des thèmes clairs est autour de 2,9 : noté à part.
 * Les images (icônes d'applis, pochettes, photos) n'y sont pas soumises.
 * Écrans : accueil télé (Rangées et Vertical avec zones), la fenêtre de choix des widgets d'une zone (le cas de l'auteur),
 * Réglages télé, et le même accueil sur téléphone.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThemesCaptureTest(private val nomTheme: String, private val taille: Recette.Taille) {
    companion object {
        val THEMES = listOf("Écrin", "Beige", "Corsaire")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parametres(): List<Array<Any>> = THEMES.flatMap { t ->
            listOf("tv-1080p", "360dp").map { n -> arrayOf<Any>(t, Recette.TAILLES.first { it.nom == n }) } }

        fun luminance(c: Int): Double {
            fun l(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * l(Color.red(c)) + 0.7152 * l(Color.green(c)) + 0.0722 * l(Color.blue(c))
        }
        fun contraste(a: Int, b: Int): Double { val x = luminance(a); val y = luminance(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
        fun distance(a: Int, b: Int): Int = Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b))
        /** Une couleur posée sur une autre (transparence). */
        fun sur(haut: Int, bas: Int): Int {
            val a = Color.alpha(haut) / 255f
            return Color.rgb((Color.red(haut) * a + Color.red(bas) * (1 - a)).toInt(), (Color.green(haut) * a + Color.green(bas) * (1 - a)).toInt(),
                (Color.blue(haut) * a + Color.blue(bas) * (1 - a)).toInt())
        }
    }

    /** La couleur de fond d'un dessin (couleur unie, forme, inset, couches, états), ou null. */
    private fun couleur(d: Drawable?): Int? = when (d) {
        null -> null
        is ColorDrawable -> d.color
        is GradientDrawable -> d.color?.defaultColor
        is InsetDrawable -> couleur(d.drawable)
        is StateListDrawable -> couleur(d.current)
        is LayerDrawable -> (0 until d.numberOfLayers).firstNotNullOfOrNull { couleur(d.getDrawable(it)) }
        else -> null
    }

    /** Les couleurs qu'un FOND peut prendre en plus de la famille du thème : ses accents (pas ses encres, faites pour le texte). */
    private fun accents(th: Theme): List<Int> = listOf(th.or, th.pourpre, 0xFF4CC38A.toInt(), 0xFFFF6B6B.toInt(),
        0xFF7FB8FF.toInt(), 0xFF1E8A4F.toInt(), 0xFFB3261E.toInt(), 0xFF1F5FBF.toInt())

    private fun verifier(v: View, ecran: String, th: Theme, derriere: Int, defauts: MutableList<String>) {
        if (v.visibility != View.VISIBLE || v.alpha == 0f || v.width <= 0 || v.height <= 0) return
        if (v is ImageView && v !is android.widget.ImageButton) return
        val nom = "${v.javaClass.simpleName}${(v.tag as? String)?.let { "[$it]" } ?: ""}" +
            ((v as? TextView)?.text?.toString()?.take(30)?.let { " « $it »" } ?: "")
        var fond = derriere
        couleur(v.background)?.let { c ->
            if (Color.alpha(c) > 0) {
                val vu = sur(c, derriere)
                fond = vu
                // A. Un fond opaque hors de la famille du thème (pas un accent).
                val ecart = luminance(vu) - luminance(th.fond)
                val horsFamille = if (th.sombre) luminance(vu) > 0.45 && ecart > 0.35 else luminance(vu) < 0.12 && ecart < -0.35
                if (Color.alpha(c) > 200 && horsFamille && accents(th).none { distance(vu, it) < 70 })
                    defauts += "$ecran · $nom : fond hors du thème ${th.nom} (#${Integer.toHexString(vu).takeLast(6)})"
            }
        }
        // B. Un texte illisible sur ce qui est derrière lui.
        if (v is TextView && v.text.isNotBlank()) {
            val t = sur(v.currentTextColor, fond)
            val r = contraste(t, fond)
            if (r < 2.5) defauts += "$ecran · $nom : texte peu lisible (contraste ${"%.1f".format(r)} : 1, thème ${th.nom})"
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) verifier(v.getChildAt(i), ecran, th, fond, defauts)
    }

    private fun poserTheme(ctx: Context) {
        Local.poserTheme(ctx, nomTheme)
    }

    private fun controler(racine: View, ecran: String, ctx: Context): List<String> {
        val th = Local.themeEffectif(ctx, Palette.APPLI)
        val defauts = ArrayList<String>()
        val base = couleur(racine.background)?.takeIf { Color.alpha(it) == 255 } ?: th.fond
        verifier(racine, ecran, th, base, defauts)
        Recette.capturer(racine, "theme-${nomTheme.lowercase().replace('é', 'e')}-$ecran", taille, 1.0f)
        return defauts.distinct()
    }

    @Test fun accueil_et_choix_des_widgets() {
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        poserTheme(ctx)
        org.soprez.vision.Config(ctx).apply { id = "essai_theme"; secret = "s".repeat(32); urlInterne = ""; urlExterne = ""; urlActive = "" }
        if (taille.tele) {
            DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_VERTICAL)
            DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_G, listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO))
            DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_G, true)
        }
        val a: Activity = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        ShadowLooper.idleMainLooper()
        val defauts = ArrayList(controler(a.window.decorView, "accueil", ctx))
        // Le cas de l'auteur : la fenêtre de choix des widgets d'une zone, ouverte depuis l'accueil.
        ZoneHaut.composerListe(a, "Zone de gauche", listOf(ZoneHaut.HORLOGE), DispositionAccueil.catalogueZone(ctx)) {}
        ShadowLooper.idleMainLooper()
        val d: Dialog? = ShadowDialog.getLatestDialog()
        if (d != null) defauts += controler(d.window!!.decorView, "choix-widgets", ctx)
        else defauts += "choix-widgets : la fenêtre ne s'est pas ouverte"
        assertEquals("thème $nomTheme, ${taille.nom} :\n" + defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    @Test fun reglages_tele() {
        if (!taille.tele) return
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        poserTheme(ctx)
        val a = Robolectric.buildActivity(ReglagesTvActivity::class.java, Intent(ctx, ReglagesTvActivity::class.java)).setup().visible().get()
        ShadowLooper.idleMainLooper()
        val defauts = controler(a.window.decorView, "reglages-tv", ctx)
        assertEquals("thème $nomTheme, ${taille.nom} :\n" + defauts.joinToString("\n"), emptyList<String>(), defauts)
    }
}
