// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 17-10 (l'auteur, « widget chauffage lisible ») : le double envoi de la consigne (15/10, 18 h 13 : 20,5 puis 20,0 en 3 s) et
 * la ligne sous la valeur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppuisReglageTest {
    private val consigne = WidgetsHa.Entite("input_number.consigne", "Consigne", "input_number", "", "°C", "", "20.0",
        mapOf("min" to 10.0, "max" to 25.0, "step" to 0.5), listOf("set_value"), listOf("reglage"))

    private fun etat(v: String) = WidgetsHa.EtatEntite(v, consigne.attributs, "")

    @After fun vider() = AppuisReglage.vider()

    @Test fun un_etat_perime_ne_fait_pas_revenir_la_consigne() {
        var t = 1_000L
        // + à 20,0 : 20,5 demandé.
        assertEquals(20.5, AppuisReglage.appui(consigne, etat("20.0"), +1, t)!!, 1e-9)
        // Une relecture arrive avant la confirmation de HA, avec l'état d'AVANT (20,0) : l'écran garde 20,5…
        t += 1500
        assertEquals("20.5", AppuisReglage.affiche(consigne.entityId, etat("20.0"), t).etat)
        // … et l'appui suivant part de 20,5 (avant : de 20,0 — il renvoyait 20,5, ou 20,0 avec −).
        assertEquals(21.0, AppuisReglage.appui(consigne, etat("20.0"), +1, t)!!, 1e-9)
        assertEquals(20.5, AppuisReglage.appui(consigne, etat("20.0"), -1, t + 100)!!, 1e-9)
        // HA confirme 20,5 : la demande s'efface, l'état réel est montré.
        assertEquals("20.5", AppuisReglage.affiche(consigne.entityId, etat("20.5"), t + 2000).etat)
        // Plus tard, le planning passe à 16 : l'écran suit HA (rien ne « retient » la valeur).
        assertEquals("16.0", AppuisReglage.affiche(consigne.entityId, etat("16.0"), t + 60_000).etat)
    }

    @Test fun une_demande_sans_reponse_s_oublie() {
        AppuisReglage.appui(consigne, etat("20.0"), +1, 0L)
        assertEquals("20.5", AppuisReglage.affiche(consigne.entityId, etat("20.0"), 5_000L).etat)
        assertEquals("20.0", AppuisReglage.affiche(consigne.entityId, etat("20.0"), AppuisReglage.MEMOIRE_MS + 1).etat)
    }

    @Test fun appuis_rapproches_un_seul_envoi() {
        val envoyes = ArrayList<Double>()
        var t = 0L
        repeat(3) {
            val v = AppuisReglage.appui(consigne, etat("20.0"), +1, t)!!
            AppuisReglage.programmer(consigne.entityId) { envoyes += v }
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS); t += 200
        }
        assertEquals(emptyList<Double>(), envoyes)
        ShadowLooper.idleMainLooper(AppuisReglage.REGROUPER_MS, TimeUnit.MILLISECONDS)
        assertEquals(listOf(21.5), envoyes)  // trois « + » : un seul envoi, 20,0 → 21,5
    }

    @Test fun ligne_sous_la_valeur() {
        assertEquals("Réduit → 12:00", WidgetsHa.texteCourt("Réduit jusqu'à 12:00, puis Confort 20 °C"))
        assertEquals("Confort → 13:30", WidgetsHa.texteCourt("Confort jusqu'à 13:30"))
        assertEquals("Chaudière", WidgetsHa.texteCourt("Chaudière en pause : maison déjà à 20,1 °C"))
        val e = WidgetsHa.analyserEtats("""{"ok":true,"etats":{"input_number.c":{"etat":"16.0","texte":"Réduit jusqu'à 12:00"},"sensor.x":{"etat":"1"}}}""")
        assertEquals("Réduit jusqu'à 12:00", e["input_number.c"]!!.texte)
        assertEquals(null, e["sensor.x"]!!.texte)  // ancienne version de HA : rien
        val f = WidgetsHa.analyserEvenement("""{"entity_id":"input_number.c","etat":"20.0","texte":"Confort jusqu'à 13:30"}""")
        assertEquals("Confort jusqu'à 13:30", f!!.second.texte)
    }
}
