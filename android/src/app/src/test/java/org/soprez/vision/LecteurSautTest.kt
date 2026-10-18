// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 1.93.05 (l'auteur, 1.93.04 sur la TV du salon) — retour 31 : après un retour au début du morceau, l'écran du lecteur se
 * comportait « comme si on appuyait sans arrêt » (anneau d'appui rejoué, fond réactif qui repart), jusqu'après
 * « Suivant » ; retour 15 : la file sautait à chaque saut. Même cause : HA renvoie le dernier saut 30 s durant, avec un
 * « il y a » qui change à chaque relecture ; l'écran le comptait comme un changement et se rebâtissait à chaque relecture.
 */
class LecteurSautTest {
    private fun etat(position: Double?, ilYa: Double?, titre: String = "Groovy Beat", relire: Int = 2) = Party.Etat(
        actif = true, ecran = "t1", ecranNom = "Télé du salon", ici = true, lecteur = "media_player.salon", lecteurNom = "Salon",
        maintenant = Party.Maintenant("playing", titre, "The Funky Band", "", "", 0.4, 214.0, position, 1_000.0 + (position ?: 0.0), ""),
        file = listOf(Party.Titre("Suivant 1", "A", "q1"), Party.Titre("Suivant 2", "B", "q2")),
        piloter = true, ecrans = emptyList(), lecteurs = emptyList(), relire = relire,
        saut = ilYa?.let { Saut.Info(0.0, it) },
    )

    @Test fun apres_un_saut_a_zero_les_relectures_ne_rebatissent_pas_l_ecran() {
        // Trois relectures après « retour au début » : la position avance, le saut reste annoncé, son « il y a » change.
        val relectures = listOf(etat(0.4, 0.4), etat(2.5, 2.5, relire = 1), etat(4.6, 4.6), etat(31.0, null))
        val vus = relectures.map { Party.pourAffichage(it) }.toSet()
        assertEquals("rien à redessiner entre ces relectures (avant : un écran rebâti à chacune)", 1, vus.size)
    }

    @Test fun un_vrai_changement_redessine_toujours() {
        assertNotEquals(Party.pourAffichage(etat(4.6, 4.6)), Party.pourAffichage(etat(0.0, null, titre = "Titre suivant")))
        val pause = etat(4.6, 4.6).let { it.copy(maintenant = it.maintenant.copy(etat = "paused")) }
        assertNotEquals(Party.pourAffichage(etat(4.6, 4.6)), Party.pourAffichage(pause))
        val fileChangee = etat(4.6, 4.6).let { it.copy(file = it.file.drop(1)) }
        assertNotEquals(Party.pourAffichage(etat(4.6, 4.6)), Party.pourAffichage(fileChangee))
    }

    @Test fun l_ecran_compare_bien_par_pour_affichage() {
        // Le lecteur (PartyActivity) décide de redessiner avec cette comparaison, et pas une autre.
        val src = java.io.File("src/main/java/org/soprez/vision/PartyActivity.kt").readText()
        assert(src.contains("private fun sansPosition(x: Party.Etat) = Party.pourAffichage(x)"))
    }
}
