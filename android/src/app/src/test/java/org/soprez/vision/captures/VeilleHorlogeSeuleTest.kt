// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.soprez.vision.HorlogesVeille
import org.soprez.vision.Local
import org.soprez.vision.R

/**
 * 1.93.05 (l'auteur, retour 35, TV du salon) : la page horloge de l'écran de veille ne montre QUE l'horloge stylisée — ni
 * température, ni ciel, ni vigilance, ni pluie (« Pluie dans 10 min » compris) —, quelle que soit l'horloge choisie,
 * même quand Home Assistant envoie la météo, la vigilance et la pluie. Télé 720p et 1080p, captures.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VeilleHorlogeSeuleTest(private val taille: Recette.Taille) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.map { arrayOf<Any>(it) }

        /** Ce que Home Assistant envoie : de la pluie, 12 °C, une vigilance orange. */
        fun meteo(): JSONObject = JSONObject()
            .put("meteo", JSONObject().put("etat", "rainy").put("temperature", 12.3).put("ressenti", 10.0))
            .put("vigilance", JSONObject().put("niveau", "Orange").put("rang", 2))
            .put("pluie", JSONObject().put("pluie", true).put("dans", 10).put("points", JSONArray(listOf(JSONObject().put("min", 10).put("niveau", 2)))))
    }

    @Test fun page_horloge_sans_meteo_quelle_que_soit_l_horloge() {
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        val defauts = ArrayList<String>()
        val interdits = listOf("°", ctx.getString(R.string.veilleview_meteo_pluie), "Orange", ctx.getString(R.string.lanceur_pluie_dans, 10),
            ctx.getString(R.string.lanceur_pas_de_pluie))
        for (style in listOf(HorlogesVeille.ANNEAU, HorlogesVeille.FINE, HorlogesVeille.GRASSE, HorlogesVeille.ANALOGIQUE, HorlogesVeille.CLASSIQUE,
                HorlogesVeille.MOTS, HorlogesVeille.FLIP, HorlogesVeille.EMPILEE)) FauxHa().use { ha ->
            Local.poserStyleHorlogeVeille(ctx, style)
            val v = VeilleEssai.vue(ha) { meteo() }
            val textes = VeilleEssai.dessiner(v, "veille-horloge-$style", taille)
            v.arreter()
            if (textes.isEmpty()) defauts += "$style : rien n'est dessiné"
            for (t in textes) for (i in interdits) if (t.contains(i, ignoreCase = true)) defauts += "$style : « $t » est dessiné sur la page horloge"
        }
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }
}
