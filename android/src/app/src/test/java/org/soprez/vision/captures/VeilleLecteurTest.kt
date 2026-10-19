// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.CoinsVeille
import org.soprez.vision.OptionsVeille
import org.soprez.vision.Party
import org.soprez.vision.ReglagesTvActivity
import org.soprez.vision.VeilleView
import java.io.File

/**
 * 1.93.05 (l'auteur, retour 36, TV du salon) : le bandeau « Lecteur musique » dans l'écran de veille.
 * - Non par défaut ; réglable par appareil sur la télé (Réglages › Écran de veille) et depuis Home Assistant (bloc
 *   « veille_options » du relevé, le plus récent gagne).
 * - Montré seulement pendant la lecture de la musique de cet appareil (encore une minute après une pause) : pochette,
 *   titre, artiste, lecture/pause ; discret, dans la zone sûre, en haut à gauche ; l'horloge reste seule au centre.
 * Télé 720p et 1080p, captures.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VeilleLecteurTest(private val taille: Recette.Taille) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.map { arrayOf<Any>(it) }

        fun musique(etat: String, ici: Boolean = true): Party.Etat = Party.analyser(JSONObject().put("ma", true).put("ici", ici).put("actif", ici)
            .put("lecteur", "media_player.salon").put("lecteur_nom", "Salon")
            .put("maintenant", JSONObject().put("etat", etat).put("titre", "Groovy Beat").put("artiste", "The Funky Band").put("duree", 214.0)))
    }

    /** La veille et ses coins, dans un cadre à la taille de l'écran (comme Veille, VeilleActivity, VeilleService). */
    private fun cadre(v: VeilleView): Pair<FrameLayout, CoinsVeille> {
        val ctx = RuntimeEnvironment.getApplication()
        val f = FrameLayout(ctx)
        val coins = CoinsVeille(ctx, v)
        f.addView(v, FrameLayout.LayoutParams(-1, -1)); f.addView(coins, FrameLayout.LayoutParams(-1, -1))
        val dm = ctx.resources.displayMetrics
        f.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        f.layout(0, 0, dm.widthPixels, dm.heightPixels)
        return f to coins
    }

    private fun dessiner(f: FrameLayout, nom: String?): List<String> {
        val b = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
        // 1.93.05 (fondus d'Emil) : une image lance le fondu, puis l'image enregistrée une fois le fondu fini.
        f.draw(android.graphics.Canvas(b)); ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        val c = VeilleEssai.Enregistreur(b)
        f.draw(c)
        if (nom != null) File(Recette.dossier, "recette-calc-$nom-${taille.nom}-100.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return c.textes
    }

    @Test fun bandeau_seulement_avec_l_option_et_pendant_la_lecture() {
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        val defauts = ArrayList<String>()
        FauxHa().use { ha ->
            val v = VeilleEssai.vue(ha) { VeilleHorlogeSeuleTest.meteo() }
            val (f, coins) = cadre(v)
            // Par défaut : rien, même pendant la lecture.
            if (OptionsVeille.lecteur(ctx)) defauts += "l'option est mise par défaut"
            coins.poserEtat(musique("playing"))
            if (dessiner(f, null).any { it.contains("Groovy Beat") }) defauts += "sans l'option, le bandeau est montré"
            // L'option : le bandeau, en haut à gauche dans la zone sûre, hors de l'horloge.
            OptionsVeille.poserLecteur(ctx, true)
            val textes = dessiner(f, "veille-bandeau-lecteur")
            if (textes.none { it.contains("Groovy Beat") }) defauts += "avec l'option, le titre n'est pas dessiné"
            if (textes.none { it.contains("The Funky Band") }) defauts += "avec l'option, l'artiste n'est pas dessiné"
            val b = RectF(coins.boiteBandeau)
            val w = f.width.toFloat(); val h = f.height.toFloat()
            if (b.isEmpty) defauts += "pas de bandeau"
            else {
                if (b.left < w * 0.05f - 1 || b.top < h * 0.05f - 1 || b.right > w * 0.95f + 1 || b.bottom > h * 0.95f + 1) defauts += "le bandeau sort de la zone sûre ($b)"
                // L'horloge du centre : l'anneau (rayon 0,42 h autour du centre) ne touche pas le bandeau.
                val dx = maxOf(b.left - w / 2, 0f, w / 2 - b.right); val dy = maxOf(b.top - h / 2, 0f, h / 2 - b.bottom)
                if (dx * dx + dy * dy < (h * 0.42f) * (h * 0.42f)) defauts += "le bandeau touche l'horloge du centre ($b)"
            }
            // Rien ne joue ici (une autre enceinte), ou la musique est arrêtée : pas de bandeau.
            coins.poserEtat(musique("playing", ici = false))
            if (dessiner(f, null).any { it.contains("Groovy Beat") }) defauts += "musique d'un autre lecteur : bandeau montré"
            coins.poserEtat(musique("idle"))
            if (dessiner(f, null).any { it.contains("Groovy Beat") }) defauts += "musique arrêtée : bandeau montré"
            // En pause : il reste une minute (reprendre à la télécommande), puis s'en va.
            coins.poserEtat(musique("paused"))
            if (dessiner(f, "veille-bandeau-pause").none { it.contains("Groovy Beat") }) defauts += "juste après une pause, le bandeau disparaît"
            ShadowLooper.idleMainLooper(61_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (dessiner(f, null).any { it.contains("Groovy Beat") }) defauts += "une minute après la pause, le bandeau reste"
            v.arreter()
        }
        OptionsVeille.poserLecteur(ctx, false)
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    @Test fun reglage_tele_et_home_assistant() {
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = "http://127.0.0.1:9"; urlExterne = ""; urlActive = "" }
        val a = Robolectric.buildActivity(ReglagesTvActivity::class.java,
            android.content.Intent(ctx, ReglagesTvActivity::class.java).putExtra(ReglagesTvActivity.EXTRA_SECTION, "veille")).setup().visible().get()
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        val ligne = a.window.decorView.findViewWithTag<View>("veille_lecteur")
        assertEquals("la ligne « Lecteur musique dans la veille » manque", true, ligne != null)
        ligne!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals("la ligne met l'option", true, OptionsVeille.lecteur(ctx))
        // Home Assistant : un réglage plus récent s'applique ; un plus ancien, non.
        assertEquals(true, OptionsVeille.releve(ctx, JSONObject().put("lecteur", false).put("depuis", 1000L)))
        assertEquals(false, OptionsVeille.lecteur(ctx))
        assertEquals(false, OptionsVeille.releve(ctx, JSONObject().put("lecteur", true).put("depuis", 900L)))
        assertEquals(false, OptionsVeille.lecteur(ctx))
        assertEquals(false, OptionsVeille.releve(ctx, null))
    }
}
