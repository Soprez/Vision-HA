// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15-10 (retour d'essai) : « Réglages de l'appareil » est réparti sur le téléphone et la tablette ; aucun réglage ne doit être perdu.
 * Chaque réglage de l'ancienne page a sa place : Couleurs, Disposition (menu de l'accueil) ou Réglages de l'appli.
 */
class RepartitionReglagesTest {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(nom: String) = File(src, nom).readText()

    @Test fun couleurs() {
        val c = lire("CouleursActivity.kt")
        for (f in listOf("choisirModeSombre", "choisirNuit", "choisirFondAccueil", "choisirVoileFond", "basculerPermisDemandeur", "ReglagesPartages.recevoirPhoto"))
            assertTrue(f, c.contains("ReglagesPartages.$f".replace("ReglagesPartages.ReglagesPartages.", "ReglagesPartages.")))
    }

    @Test fun disposition() {
        val p = lire("ReglagesPartages.kt")
        val menu = p.substring(p.indexOf("fun menuDisposition("))
        for (f in listOf("choisirDispositionTelephone", "poserCoteIndex", "poserFavorisEnHaut", "ZoneHaut.personnaliser", "poserPositionZone", "choisirApplis("))
            assertTrue(f, menu.contains(f))
        assertTrue(lire("LanceurActivity.kt").contains("ReglagesPartages.menuDisposition(this)"))
    }

    @Test fun reglages_de_l_appli() {
        val s = lire("SetupActivity.kt")
        val r = s.substring(s.indexOf("private fun espaceReglages("))
        // Le café d'abord, avant tout groupe.
        assertTrue(r.indexOf("carteSoutien()") in 0 until r.indexOf("lot5_pour_tous"))
        val a = s.substring(s.indexOf("private fun reglagesAppareil()"))
        for (f in listOf("CouleursActivity", "menuDisposition", "choisirSonMessages", "basculerOeilAccueil", "oeilaccueil_note", "basculerBulle",
            "reglagestv_faire_accueil", "reglagestv_encore_accueil", "nomModeAppareil", "\"s_veille\"", "ReglagesMusique.ouvrir"))
            assertTrue(f, a.contains(f))
    }
}
