// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
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
import org.soprez.vision.ChoixMusiqueTeleActivity
import org.soprez.vision.R
import org.soprez.vision.ReglagesMusique
import org.soprez.vision.ZoneSure

/**
 * 17-10 (l'auteur, point 22) : le panneau « Ajouter un titre » de la télé (moitié droite, clavier de Vision), sans émulateur.
 * - tout 30 % plus petit (onglets, clavier, textes), la moitié d'écran gardée ;
 * - plus de « Bibliothèque » ; les onglets gardent la place de l'anneau du focus (plus de bord mangé à gauche) ;
 * - une séparation douce aux couleurs du thème ; rien ne sort de la zone sûre (5 et 9 %), polices 100 et 130 %.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PanneauMusiqueCaptureTest(private val taille: Recette.Taille, private val police: Float, private val zone: Int) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-zone{2}")
        fun parametres(): List<Array<Any>> = Recette.combinaisons(Recette.TAILLES.filter { it.tele }).flatMap { c ->
            listOf(0, 5).map { z -> arrayOf(c[0], c[1], z) } }
    }

    private fun texteVisible(v: View, t: String): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is TextView && v.text.toString() == t) return true
        return v is ViewGroup && (0 until v.childCount).any { texteVisible(v.getChildAt(it), t) }
    }

    private fun trouver(v: View, t: String): TextView? {
        if (v is TextView && v.text.toString() == t) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) trouver(v.getChildAt(i), t)?.let { return it }
        return null
    }

    @Test fun panneau_reduit_sans_bibliotheque() {
        FauxHa().use { ha ->
            Recette.poser(taille, police)
            val ctx = RuntimeEnvironment.getApplication()
            ZoneSure.poser(ctx, zone, zone)
            ReglagesMusique.clavier(ctx, ReglagesMusique.CLAVIER_VISION)
            org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
            ha.reponses["/api/pc_parental/party/musique"] = { JSONObject().put("items", JSONArray(listOf("Disney classique", "Groovy", "Soirée calme", "Jazz du dimanche").map {
                JSONObject().put("uri", "library://playlist/${it.hashCode()}").put("nom", it).put("type", "playlist").put("source", "Spotify") })) }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
            val a: Activity = Robolectric.buildActivity(ChoixMusiqueTeleActivity::class.java).setup().visible().get()
            val fin = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < fin && !texteVisible(a.window.decorView, "Soirée calme")) { ShadowLooper.idleMainLooper(); Thread.sleep(40) }
            ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            val d = a.window.decorView
            val defauts = ArrayList(Recette.capturer(d, "panneau-musique-zone$zone", taille, police))
            if (texteVisible(d, a.getString(R.string.musrech_onglet_bibliotheque))) defauts += "« Bibliothèque » est encore là"
            if (!texteVisible(d, "Soirée calme")) defauts += "les playlists ne s'affichent pas"
            // 30 % plus petit : l'onglet « Playlists » fait 0,7 × sa taille d'avant (15 sp × 1,25 sur la télé).
            val onglet = trouver(d, a.getString(R.string.choixmusique_playlists))
            val avant = 15f * 1.25f * ctx.resources.displayMetrics.density * police
            if (onglet == null) defauts += "onglet Playlists absent"
            else {
                val r = onglet.textSize / avant
                if (r > 0.75f || r < 0.6f) defauts += "onglet Playlists : ${"%.2f".format(r)} × sa taille d'avant (attendu 0,7)"
                // L'anneau du focus (quelques px autour) ne doit pas être mangé par la rangée qui défile.
                val hsv = generateSequence(onglet.parent) { it.parent }.filterIsInstance<android.widget.HorizontalScrollView>().firstOrNull()
                if (hsv != null) {
                    val po = IntArray(2).also { onglet.getLocationOnScreen(it) }; val ph = IntArray(2).also { hsv.getLocationOnScreen(it) }
                    val premier = (hsv.getChildAt(0) as ViewGroup).getChildAt(0)
                    val pp = IntArray(2).also { premier.getLocationOnScreen(it) }
                    if (pp[0] - ph[0] < org.soprez.vision.Ui.dp(a, 3f)) defauts += "le premier onglet touche le bord gauche de sa rangée (anneau mangé)"
                    if (po[1] < ph[1]) defauts += "onglet au-dessus de sa rangée"
                }
            }
            // La zone sûre : à droite, en haut et en bas de l'écran (le panneau touche le bord droit de la télé).
            val zx = ZoneSure.x(a); val zy = ZoneSure.y(a)
            val ecran = a.resources.displayMetrics
            fun dedans(v: View) {
                if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return
                val p = IntArray(2).also { v.getLocationOnScreen(it) }
                var r = android.graphics.Rect(p[0], p[1], p[0] + v.width, p[1] + v.height)
                for (sv in generateSequence(v.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>()) {
                    val q = IntArray(2).also { sv.getLocationOnScreen(it) }
                    if (!r.intersect(q[0], q[1], q[0] + sv.width, q[1] + sv.height)) return
                }
                // Le panneau est posé à droite de l'écran : sa position à l'écran = celle dans la fenêtre + (écran − fenêtre).
                val decal = ecran.widthPixels - d.width
                val contenu = v is TextView || (v !is ViewGroup && (v.isFocusable || v.isClickable))
                if (contenu && (r.right + decal > ecran.widthPixels - zx + 2 || r.top < zy - 2 || r.bottom > ecran.heightPixels - zy + 2))
                    defauts += "${v.javaClass.simpleName}${(v as? TextView)?.text?.toString()?.take(20)?.let { " « $it »" } ?: ""} sort de la zone sûre (${r.toShortString()})"
                else if (v is ViewGroup) for (i in 0 until v.childCount) dedans(v.getChildAt(i))
            }
            dedans(d)
            if (d.findViewWithTag<View>("separation") == null) defauts += "pas de séparation stylisée"
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }
}
