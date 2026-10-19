// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.93.06 (point 6e, vidéo de l'écran de la TCL au salon) : la file du lecteur TV ne doit jamais sauter. Deux défauts
 * vus sur la vidéo au changement de titre : une image de la liste tout en haut (les titres passés) avant le titre en
 * cours, et un aller-retour vers l'ancien titre (état en retard de Home Assistant).
 */
class FileSansSautTest {
    private val ancien = Party.cleTitre("The Golden Path", "The Chemical Brothers")
    private val nouveau = Party.cleTitre("Come and Get Your Love", "Redbone")

    @Test fun l_ancien_titre_qui_revient_juste_apres_le_changement_est_ignore() {
        assertTrue(Party.retourEnArriere(ancien, nouveau, ancien, depuisMs = 1_500, precedentDemande = false))
    }

    @Test fun mais_pas_apres_un_precedent_demande_ni_plus_tard() {
        assertFalse(Party.retourEnArriere(ancien, nouveau, ancien, depuisMs = 1_500, precedentDemande = true))
        assertFalse(Party.retourEnArriere(ancien, nouveau, ancien, depuisMs = 9_000, precedentDemande = false))
        // Un autre titre, ou le même titre : rien à ignorer.
        assertFalse(Party.retourEnArriere(ancien, nouveau, Party.cleTitre("Role Model", "Fatboy Slim"), 1_500, false))
        assertFalse(Party.retourEnArriere(ancien, nouveau, nouveau, 1_500, false))
        assertFalse(Party.retourEnArriere("", nouveau, ancien, 1_500, false))
    }

    @Test fun seul_le_titre_suivant_fait_monter_la_liste() {
        // Point 2 (retour d'essai) : l'ancien titre en cours est devenu le dernier passé → montée d'une ligne.
        assertTrue(Party.monteeDUneLigne(ancien, nouveau, dernierPasse = ancien))
        // Précédent, titre choisi plus loin, premier affichage, même titre : rien ne s'anime.
        assertFalse(Party.monteeDUneLigne(ancien, nouveau, dernierPasse = Party.cleTitre("Role Model", "Fatboy Slim")))
        assertFalse(Party.monteeDUneLigne("", nouveau, dernierPasse = ""))
        assertFalse(Party.monteeDUneLigne(nouveau, nouveau, dernierPasse = nouveau))
        assertFalse(Party.monteeDUneLigne(ancien, "", dernierPasse = ancien))
    }

    private fun t(n: Int) = Party.Titre("Titre $n", "Artiste $n", "q$n")
    private fun cle(n: Int) = Party.cleTitre("Titre $n", "Artiste $n")

    @Test fun les_titres_passes_restent_stables_meme_sans_file_passes() {
        // TCL, 19:45 : HA envoie les passés sur un état, pas sur le suivant. La liste ne change plus de forme.
        val avec = Party.passesStables(emptyList(), listOf(t(1), t(2)), ancien = t(2), nouveauCle = cle(3))
        assertTrue(avec == listOf(t(1), t(2)))
        assertTrue(Party.passesStables(avec, emptyList(), ancien = Party.Titre("Titre 3", "Artiste 3"), nouveauCle = cle(3)) == avec)
        // HA n'en envoie jamais (changements 2 et 3 sur la TCL) : l'ancien titre en cours est ajouté au titre suivant.
        val seul = Party.passesStables(emptyList(), emptyList(), ancien = t(3), nouveauCle = cle(4))
        assertTrue(seul == listOf(t(3)))
        // HA en retard (sa liste ne finit pas encore par l'ancien titre) : ajouté une fois, sans doublon.
        assertTrue(Party.passesStables(emptyList(), listOf(t(1), t(2)), ancien = t(3), nouveauCle = cle(4)) == listOf(t(1), t(2), t(3)))
        assertTrue(Party.passesStables(emptyList(), listOf(t(3), t(1)), ancien = t(3), nouveauCle = cle(4)) == listOf(t(1), t(3)))
        // Précédent avec la liste de HA (TCL) : l'ancien titre n'est pas ajouté, aucune montée.
        assertTrue(Party.passesStables(listOf(t(0), t(1), t(2)), listOf(t(0), t(1)), ancien = t(3), nouveauCle = cle(2)) == listOf(t(0), t(1)))
        // Précédent : le titre revenu en cours sort, avec ceux d'après.
        assertTrue(Party.passesStables(listOf(t(1), t(2), t(3)), emptyList(), ancien = t(4), nouveauCle = cle(3)) == listOf(t(1), t(2)))
        // Au plus PASSES_MAX.
        assertTrue(Party.passesStables((1..20).map { t(it) }, emptyList(), ancien = null, nouveauCle = cle(30)).size == Party.PASSES_MAX)
    }

    @Test fun la_montee_se_voit_meme_sur_une_tele_lente() {
        // 2e vidéo de la TCL (18/10, 22:35) : réglée sur le temps seul, 320 ms tenaient en 2 ou 3 images (150 ms chacune).
        val e = 114
        fun pas(msParImage: Long): List<Int> {
            val r = mutableListOf(e); var k = 0
            while (r.last() > 0 && k < 100) { k++; r += MonteeFile.reste(e, MonteeFile.avancee(k * msParImage, k)) }
            return r
        }
        val rapide = pas(16); val lente = pas(150)
        assertTrue("rapide : environ 320 ms ($rapide)", rapide.size - 1 in 18..22)
        assertTrue("lente : au moins ${MonteeFile.PAS_MIN} pas dessinés ($lente)", lente.size - 1 >= MonteeFile.PAS_MIN)
        for (l in listOf(rapide, lente)) for (i in 1 until l.size) assertTrue("jamais en arrière : $l", l[i] <= l[i - 1])
        // Ralenti à l'arrivée : la première moitié du temps fait plus de chemin que la seconde.
        assertTrue(e - MonteeFile.reste(e, 0.5) > MonteeFile.reste(e, 0.5))
    }

    @Test fun la_position_de_la_file_est_posee_avant_le_premier_dessin() {
        val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
        val t = File(src, "PartyActivity.kt").readText()
        assertTrue(t.contains("sv.viewTreeObserver.addOnPreDrawListener"))
        assertFalse("plus de défilement posé après le dessin", t.contains("sv.post { if (garder) sv.scrollTo"))
        assertTrue(t.contains("Party.retourEnArriere(titrePrecedent"))
    }
}
