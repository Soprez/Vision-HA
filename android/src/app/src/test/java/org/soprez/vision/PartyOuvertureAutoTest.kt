// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.93.06.1 (l'auteur, 19/10 : « une action qui part toute seule »). Sur la TCL à 12:39:07, l'écran Musique s'est ouvert tout
 * seul (relevé : « la télé choisie ouvre l'écran Party une fois par lancement »), 35 min après une soirée lancée depuis
 * cette télé, pendant qu'on naviguait sur l'accueil ; l'OK qui suivait a mis la musique en pause.
 *
 * Règles : l'écran ne s'ouvre de lui-même que pour une soirée lancée d'ailleurs, dans les 90 s, et jamais par-dessus
 * une autre appli.
 */
class PartyOuvertureAutoTest {
    private val t = 1_792_404_229L  // 12:03:49, le lancement du 19/10

    @Test fun une_soiree_lancee_depuis_cette_tele_n_ouvre_rien() {
        val r = Party.ouvertureAutoPermise(depuisS = t, maintenantS = t + 5, lanceeIciS = t - 2, autreAppliDevant = false)
        assertTrue(r ?: "", r == "lancee_ici")
        // L'horloge de la télé et celle de Home Assistant peuvent différer de quelques secondes.
        assertTrue(Party.ouvertureAutoPermise(t, t + 5, lanceeIciS = t + 20, autreAppliDevant = false) != null)
    }

    @Test fun une_soiree_lancee_d_ailleurs_ouvre_l_ecran_tout_de_suite() {
        assertNull(Party.ouvertureAutoPermise(depuisS = t, maintenantS = t + 12, lanceeIciS = 0L, autreAppliDevant = false))
        assertNull(Party.ouvertureAutoPermise(t, t + Party.OUVERTURE_AUTO_MAX_S, lanceeIciS = t - 3_600, autreAppliDevant = false))
    }

    @Test fun trop_tard_le_relevé_arrive_bien_apres_le_lancement() {
        // 12:39:07 : 35 min après le lancement.
        val r = Party.ouvertureAutoPermise(depuisS = t, maintenantS = t + 35 * 60, lanceeIciS = 0L, autreAppliDevant = false)
        assertTrue(r ?: "", r?.startsWith("trop_tard_") == true)
    }

    @Test fun jamais_par_dessus_une_autre_appli() {
        val r = Party.ouvertureAutoPermise(depuisS = t, maintenantS = t + 10, lanceeIciS = 0L, autreAppliDevant = true)
        assertTrue(r ?: "", r == "autre_appli_devant")
    }
}
