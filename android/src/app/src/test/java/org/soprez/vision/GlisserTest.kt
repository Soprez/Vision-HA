// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le glisser-déposer de l'accueil : toute fin de glisser remet la zone et la case (bug d'essai, widget VLC). */
class GlisserTest {
    private var zone = false
    private val opacites = HashMap<String, Float>()
    private fun etat() = EtatGlisser<String>({ zone = it }, { v, a -> opacites[v] = a })

    @Test fun deplacement_puis_depot_remet_tout() {
        val e = etat()
        e.debut("widget:android:7", "vlc", menu = { })
        assertTrue(zone); assertEquals(0.25f, opacites["vlc"])
        e.sortir()
        assertNull(e.fin(depotReussi = true))
        assertFalse(zone); assertEquals(1f, opacites["vlc"])
    }

    @Test fun depot_hors_zone_ou_annulation_remet_tout() {
        val e = etat()
        e.debut("widget:android:7", "vlc", menu = { })
        e.sortir()
        assertNull(e.fin(depotReussi = false))  // lâché hors de la grille : pas de menu
        assertFalse(zone); assertEquals(1f, opacites["vlc"])
    }

    @Test fun appui_long_sans_bouger_ouvre_le_menu_une_seule_fois() {
        val e = etat()
        e.debut("widget:android:7", "vlc", menu = { })
        assertNotNull(e.fin(depotReussi = false))
        assertNull(e.fin(depotReussi = false))  // la 2e fin (case + racine) ne rouvre rien
        assertFalse(zone); assertEquals(1f, opacites["vlc"])
    }

    @Test fun depot_sur_la_zone_de_retrait() {
        val e = etat()
        e.debut("com.example", "appli", menu = { })
        e.sortir()
        assertNull(e.fin(depotReussi = true))
        assertFalse(zone); assertEquals(1f, opacites["appli"])
    }

    @Test fun un_grand_widget_bouge_d_une_case() {
        // Un widget 4 × 2 en (0, 0), saisi sur sa 2e ligne : lâché sur place, il ne bouge pas ;
        // une ligne plus bas, il bouge d'une ligne (avant : le doigt restait « sur lui », rien ne bougeait).
        val prise = Glisser.prise(1, 1, 0, 0, 4, 2)
        assertFalse(Glisser.aBouge(0, 0, Glisser.cible(1, 1, 4, 2, 4, prise)))
        assertEquals(0 to 1, Glisser.cible(1, 2, 4, 2, 4, prise))
        assertTrue(Glisser.aBouge(0, 0, Glisser.cible(1, 2, 4, 2, 4, prise)))
        assertEquals(0 to 0, Glisser.cible(3, 0, 4, 2, 4))  // jamais hors de la grille
    }
    // ---- Appui long puis glisser depuis le tiroir ou un dossier (comme un lanceur Android).

    @Test fun le_menu_reste_tant_que_le_doigt_ne_part_pas() {
        val a = AppuiPuisGlisser(seuil = 24f)
        a.debut(100f, 100f)
        assertFalse(a.bouge(140f, 100f))           // avant l'appui long : rien (c'est le défilement qui décide)
        a.appuiLong(102f, 101f)
        assertEquals(AppuiPuisGlisser.Etape.MENU, a.etape)
        assertFalse(a.bouge(110f, 110f))           // tremblement sous le seuil : le menu reste
        assertFalse(a.bouge(102f, 125f))           // 24 px pile : pas encore
        assertTrue(a.bouge(102f, 126f))            // au-delà : le menu s'efface, le glisser commence
        assertEquals(AppuiPuisGlisser.Etape.GLISSER, a.etape)
        assertFalse(a.bouge(300f, 300f))           // une seule fois par geste
        a.fin()
        assertFalse(a.bouge(300f, 300f))           // geste suivant : il faut un nouvel appui long
    }

    @Test fun la_distance_compte_depuis_l_appui_long() {
        val a = AppuiPuisGlisser(seuil = 24f)
        a.debut(0f, 0f)
        a.appuiLong(20f, 0f)                        // le doigt a glissé un peu dans l'icône avant l'appui long
        assertFalse(a.bouge(40f, 0f))
        assertTrue(a.bouge(45f, 0f))
    }

    @Test fun case_visee_libre_ou_la_plus_proche() {
        // Accueil 4 colonnes : (0,0) (1,0) (2,0) prises.
        val prises = setOf(0 to 0, 1 to 0, 2 to 0)
        val libre = { c: Int, r: Int -> (c to r) !in prises }
        assertEquals(3 to 1, Glisser.caseLibre(3, 1, 1, 1, 4, 3, libre))   // libre : là où on lâche
        assertEquals(3 to 0, Glisser.caseLibre(2, 0, 1, 1, 4, 3, libre))   // prise : la voisine sur la ligne
        assertEquals(0 to 1, Glisser.caseLibre(0, 0, 1, 1, 4, 3, libre))   // une ligne compte double : (0,1) avant (3,0)
        assertEquals(3 to 0, Glisser.caseLibre(9, 0, 1, 1, 4, 3, libre))   // jamais hors de la grille
        assertNull(Glisser.caseLibre(0, 0, 1, 1, 4, 3) { _, _ -> false })  // rien de libre
    }

    @Test fun la_zone_du_haut_annule_un_ajout_sans_rien_retirer() {
        assertEquals(Glisser.Retrait.ANNULER_AJOUT, Glisser.surRetrait("com.example", ajout = true))
        assertEquals(Glisser.Retrait.RETIRER, Glisser.surRetrait("com.example", ajout = false))
        assertEquals(Glisser.Retrait.DEFAIRE_DOSSIER, Glisser.surRetrait(Accueil.PREFIXE_DOSSIER + "1", ajout = false))
        assertEquals(Glisser.Retrait.RETIRER, Glisser.surRetrait(Accueil.PREFIXE_WIDGET + "horloge", ajout = false))
    }
}
