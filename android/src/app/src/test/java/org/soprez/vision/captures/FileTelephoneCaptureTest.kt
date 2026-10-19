// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
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
import org.soprez.vision.PartyActivity
import org.soprez.vision.R

/**
 * 17-10 (l'auteur, point 24) : sur le téléphone, la file est sous le lecteur (plus dans la page Réglages), avec la poignée ≡
 * (décideur), « Retirer », ⋯ et « Vider la file » ; glisser une ligne sur le côté la retire. 360 et 412 dp, 100 et 130 %.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FileTelephoneCaptureTest(private val taille: Recette.Taille, private val police: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parametres(): List<Array<Any>> = Recette.combinaisons(Recette.TAILLES.filter { it.nom == "360dp" || it.nom == "412dp" })
    }

    private fun etat(): JSONObject = JSONObject().put("ma", true).put("ici", false).put("actif", true).put("lecteur", "media_player.salon")
        .put("lecteur_nom", "Salon").put("ecran", "t1").put("ecran_nom", "Télé du salon").put("piloter", true).put("participer", true)
        .put("lecteurs", JSONArray().put(JSONObject().put("entity_id", "media_player.salon").put("nom", "Salon").put("etat", "playing").put("ma", true)))
        .put("maintenant", JSONObject().put("etat", "playing").put("titre", "Groovy Beat").put("artiste", "The Funky Band").put("duree", 214.0).put("position", 61.0).put("volume", 0.4))
        .put("file", JSONArray((1..6).map { JSONObject().put("titre", "Titre suivant $it").put("artiste", "Artiste $it").put("id", "q$it") }))

    private fun texteVisible(v: View, t: String): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is TextView && v.text.toString() == t) return true
        return v is ViewGroup && (0 until v.childCount).any { texteVisible(v.getChildAt(it), t) }
    }

    @Test fun file_sous_le_lecteur_et_glisser_pour_retirer() {
        FauxHa().use { ha ->
            Recette.poser(taille, police)
            val ctx = RuntimeEnvironment.getApplication()
            org.soprez.vision.Config(ctx).apply { id = "essai_tel"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
            ha.reponses["/api/pc_parental/party/etat"] = { etat() }
            ha.reponses["/api/pc_parental/party/commande"] = { JSONObject().put("ok", true).put("retour", "") }
            val a: Activity = Robolectric.buildActivity(PartyActivity::class.java).setup().visible().get()
            val fin = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < fin && a.window.decorView.findViewWithTag<View>("file:q1") == null) { ShadowLooper.idleMainLooper(); Thread.sleep(40) }
            ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            val d = a.window.decorView
            val defauts = ArrayList(Recette.capturer(d, "file-telephone", taille, police))
            if (d.findViewWithTag<View>("file:q1") == null) defauts += "la file n'est pas sous le lecteur"
            if (!texteVisible(d, "≡")) defauts += "pas de poignée ≡"
            if (d.findViewWithTag<View>("vider_file") == null) defauts += "pas de « Vider la file »"
            // Glisser la 2e ligne vers la droite, au-delà du tiers de sa largeur.
            val ligne = d.findViewWithTag<View>("file:q2")
            if (ligne != null) {
                val p = IntArray(2).also { ligne.getLocationOnScreen(it) }
                val y = p[1] + ligne.height / 2f; val x = p[0] + ligne.width * 0.5f
                val t0 = SystemClock.uptimeMillis()
                fun ev(action: Int, xx: Float, dt: Long) = MotionEvent.obtain(t0, t0 + dt, action, xx, y, 0)
                d.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, 0))
                for (k in 1..8) d.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x + ligne.width * 0.06f * k, 16L * k))
                d.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x + ligne.width * 0.48f, 160))
                ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                val attente = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < attente && ha.recus.none { it.second.optString("action") == "retirer" }) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
                val r = ha.recus.firstOrNull { it.second.optString("action") == "retirer" }
                if (r == null || r.second.optString("element") != "q2") defauts += "glisser n'a pas retiré le titre q2 (${r?.second})"
            }
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }
}
