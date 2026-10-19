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
import org.soprez.vision.ZoneSure

/**
 * 1.93.06 (photo de l'auteur, veille de la TV du salon) : les graphiques de la box de la veille. Avant : « 0,0 Mbit/s … 24 h …
 * 322 Mbit/s » sous la courbe, et « 0,1 MB/s » en envoi à côté de « 1,7 Mbit/s » en réception. Maintenant : la valeur
 * actuelle en haut, l'échelle à gauche, les heures dessous, et tout débit en Mbit/s. Télé 720p et 1080p.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VeilleCourbeDebitTest(private val taille: Recette.Taille) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.map { arrayOf<Any>(it) }
    }

    private fun serie(base: Double, ampleur: Double): JSONArray {
        val fin = System.currentTimeMillis() / 1000
        return JSONArray((0..48).map { i -> JSONArray(listOf(fin - (48 - i) * 1800L, base + ampleur * (1 + Math.sin(i / 5.0)) / 2)) })
    }

    private fun box(): JSONObject {
        val reception = JSONObject().put("entite", "sensor.box_reception").put("nom", "Réception").put("rendu", "courbe")
            .put("valeur", 1.7).put("unite", "Mbit/s").put("serie", serie(0.0, 322.0)).put("largeur", 6).put("hauteur", 2)
        val envoi = JSONObject().put("entite", "sensor.box_envoi").put("nom", "Envoi").put("rendu", "courbe")
            .put("valeur", 0.1).put("unite", "MB/s").put("serie", serie(0.0, 4.0)).put("largeur", 6).put("hauteur", 2)
        val section = JSONObject().put("titre", "Box").put("cases", JSONArray(listOf(reception, envoi)))
        val tableau = JSONObject().put("code", "dash").put("duree", 30).put("id", "box").put("titre", "Box")
            .put("sections", JSONArray(listOf(section)))
        return JSONObject().put("tableaux", JSONObject().put("liste", JSONArray(listOf(tableau))))
    }

    @Test fun graphiques_box_lisibles_et_en_mbit_s() {
        Recette.poser(taille, 1.0f)
        ZoneSure.poser(RuntimeEnvironment.getApplication(), 0, 0)
        val defauts = ArrayList<String>()
        FauxHa().use { ha ->
            val v = VeilleEssai.vue(ha) { box() }
            // L'horloge d'abord : on passe à la page des graphiques, et on laisse finir son entrée.
            v.naviguer(1)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            val textes = VeilleEssai.dessiner(v, "veille-box-courbes", taille)
            v.arreter()
            if (textes.none { it == "0,8 Mbit/s" }) defauts += "l'envoi n'est pas en Mbit/s (« 0,8 Mbit/s » attendu) : $textes"
            if (textes.none { it == "1,7 Mbit/s" }) defauts += "la réception ne montre pas sa valeur actuelle : $textes"
            if (textes.any { it.contains("MB/s") || it.contains("Mo/s") }) defauts += "une unité en octets reste affichée"
            if (textes.any { it == "24 h" }) defauts += "« 24 h » est encore écrit sous la courbe"
            // L'échelle à gauche (l'unité sur la plus haute graduation) et des heures dessous.
            if (textes.count { it.endsWith("Mbit/s") } < 4) defauts += "pas d'échelle des valeurs : $textes"
            if (textes.none { Regex("^\\d{1,2}:\\d{2}").containsMatchIn(it) }) defauts += "pas de repère d'heure sous les courbes : $textes"
        }
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }
}
