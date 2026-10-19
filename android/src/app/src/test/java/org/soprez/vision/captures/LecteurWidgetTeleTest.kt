// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.content.Context
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
import org.soprez.vision.DispositionAccueil
import org.soprez.vision.LanceurActivity
import org.soprez.vision.R
import org.soprez.vision.ZoneHaut

/**
 * 1.93.05 (l'auteur, retour 34, TV du salon) : le lecteur de musique apparaissait tout seul en haut de l'accueil de la télé.
 * C'est maintenant le widget « Lecteur musique » (pochette, titre, lecture/pause, suivant), au catalogue des zones de la
 * télé comme du téléphone, qu'on ajoute ou non :
 * - un accueil SANS ce widget ne montre aucun lecteur, même pendant la lecture (accueil horizontal et vertical) ;
 * - ajouté (zone du haut, ou zone de côté de l'accueil vertical), il montre le titre qui joue, dans la zone sûre, et se
 *   prend à la télécommande ; rien ne joue : « Rien ne joue » ;
 * - à la mise à jour, il n'est ajouté à aucun accueil (aucun défaut ne le contient).
 * Télé 720p et 1080p, captures.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LecteurWidgetTeleTest(private val taille: Recette.Taille) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.map { arrayOf<Any>(it) }
    }

    private fun etat(joue: Boolean): JSONObject = JSONObject().put("ma", true).put("ici", true).put("actif", true).put("lecteur", "media_player.salon")
        .put("lecteur_nom", "Salon").put("piloter", true)
        .put("maintenant", if (!joue) JSONObject().put("etat", "idle") else JSONObject().put("etat", "playing").put("titre", "Groovy Beat")
            .put("artiste", "The Funky Band").put("album", "Night Moves").put("duree", 214.0).put("position", 61.0))
        .put("file", JSONArray())

    private fun accueil(ha: FauxHa, joue: Boolean, preparer: (Context) -> Unit): Activity {
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
        // Musique existe dans ce foyer (la tuile le dit) et joue sur cette télé.
        ctx.getSharedPreferences("party", Context.MODE_PRIVATE).edit().putBoolean("tuile", true).apply()
        ha.reponses["/api/pc_parental/party/etat"] = { etat(joue) }
        preparer(ctx)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        // Le mini-lecteur relit l'état d'un fil : on lui laisse le temps (lecture : jusqu'au titre).
        val fin = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < fin) {
            ShadowLooper.idleMainLooper(); Thread.sleep(40)
            if (ha.recus.any { it.first == "/api/pc_parental/party/etat" } && (!joue || texteVisible(a.window.decorView, "Groovy Beat"))) break
        }
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        return a
    }

    private fun texteVisible(v: View, t: String): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is TextView && v.text.toString().contains(t)) return true
        return v is ViewGroup && (0 until v.childCount).any { texteVisible(v.getChildAt(it), t) }
    }

    private fun visible(v: View): Boolean = v.visibility == View.VISIBLE && v.width > 0 &&
        generateSequence(v.parent) { it.parent }.filterIsInstance<View>().all { it.visibility == View.VISIBLE }

    private fun lecteurs(v: View, l: MutableList<View> = ArrayList()): MutableList<View> {
        if (v.tag == "mini_lecteur" && visible(v)) l.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) lecteurs(v.getChildAt(i), l)
        return l
    }

    private fun dansZoneSure(a: Activity, v: View, defauts: MutableList<String>) {
        val d = a.window.decorView
        val p = IntArray(2).also { v.getLocationOnScreen(it) }
        val zx = d.width * 5 / 100; val zy = d.height * 5 / 100
        if (p[0] < zx - 2 || p[1] < zy - 2 || p[0] + v.width > d.width - zx + 2 || p[1] + v.height > d.height - zy + 2)
            defauts += "le lecteur sort de la zone sûre (${p[0]},${p[1]} ${v.width}×${v.height}, écran ${d.width}×${d.height})"
    }

    @Test fun sans_widget_aucun_lecteur_meme_en_lecture() {
        val defauts = ArrayList<String>()
        for (vertical in listOf(false, true)) FauxHa().use { ha ->
            val a = accueil(ha, joue = true) { ctx ->
                DispositionAccueil.poserDisposTele(ctx, if (vertical) DispositionAccueil.TELE_VERTICAL else DispositionAccueil.TELE_RANGEES)
                ZoneHaut.poser(ctx, listOf(ZoneHaut.HORLOGE))
            }
            val nom = if (vertical) "vertical" else "horizontal"
            defauts.addAll(Recette.capturer(a.window.decorView, "accueil-$nom-sans-widget-lecteur", taille, 1.0f))
            val l = lecteurs(a.window.decorView)
            if (l.isNotEmpty()) defauts += "accueil $nom sans le widget : un lecteur est affiché pendant la lecture"
            if (texteVisible(a.window.decorView, "Groovy Beat")) defauts += "accueil $nom sans le widget : le titre qui joue est affiché"
            a.finish()
        }
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    @Test fun widget_ajoute_zone_du_haut() {
        FauxHa().use { ha ->
            val a = accueil(ha, joue = true) { ctx ->
                DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_RANGEES)
                ZoneHaut.poser(ctx, listOf(ZoneHaut.HORLOGE, ZoneHaut.LECTEUR))
            }
            val d = a.window.decorView
            val defauts = ArrayList(Recette.capturer(d, "accueil-horizontal-widget-lecteur", taille, 1.0f))
            val l = lecteurs(d)
            if (l.size != 1) defauts += "${l.size} lecteur(s) au lieu d'un (le widget ajouté)"
            l.firstOrNull()?.let { v ->
                if (!texteVisible(v, "Groovy Beat")) defauts += "le widget ne montre pas le titre qui joue"
                dansZoneSure(a, v, defauts)
                // À la télécommande : le widget prend le focus (OK ouvre Musique), ses boutons aussi.
                if (!v.isFocusable) defauts += "le widget ne prend pas le focus"
                if (v.getFocusables(View.FOCUS_FORWARD).size < 3) defauts += "lecture/pause et suivant ne se prennent pas à la télécommande"
            }
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }

    @Test fun widget_ajoute_zone_de_cote_et_rien_ne_joue() {
        FauxHa().use { ha ->
            val a = accueil(ha, joue = false) { ctx ->
                DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_VERTICAL)
                DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_D, listOf(ZoneHaut.LECTEUR))
                DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_D, true)
            }
            val d = a.window.decorView
            val defauts = ArrayList(Recette.capturer(d, "accueil-vertical-widget-lecteur-rien", taille, 1.0f))
            val l = lecteurs(d)
            if (l.size != 1) defauts += "${l.size} lecteur(s) au lieu d'un (le widget de la zone)"
            l.firstOrNull()?.let { v ->
                if (!texteVisible(v, a.getString(R.string.partyecran_rien_ne_joue))) defauts += "le widget ne dit pas « Rien ne joue »"
                dansZoneSure(a, v, defauts)
            }
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }

    @Test fun meme_catalogue_et_jamais_ajoute_a_la_mise_a_jour() {
        assertEquals(true, ZoneHaut.LECTEUR in ZoneHaut.catalogue(true))
        assertEquals(true, ZoneHaut.LECTEUR in ZoneHaut.catalogue(false))
        assertEquals(true, ZoneHaut.LECTEUR in DispositionAccueil.catalogueZone())
        for (tele in listOf(true, false)) for (relie in listOf(true, false))
            assertEquals(false, ZoneHaut.LECTEUR in ZoneHaut.initial(tele, relie, listOf("ha:sensor.x")))
        // Un accueil composé avant la mise à jour garde exactement sa liste.
        assertEquals(listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO), ZoneHaut.nettoyer(listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO), true))
    }
}
