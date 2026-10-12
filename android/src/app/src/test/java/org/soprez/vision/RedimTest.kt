// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le redimensionnement des widgets de l'accueil : pas de grille, bornes, collisions, pages (Redim). */
class RedimTest {
    private val libres = Redim.Bornes(1, 1, 4, 12)
    private fun c(cle: String, col: Int, row: Int, w: Int, h: Int) = Redim.Case(cle, Redim.Rect(col, row, w, h))

    @Test fun pas_de_grille() {
        assertEquals(0, Redim.pas(40f, 100f))
        assertEquals(1, Redim.pas(50f, 100f))
        assertEquals(2, Redim.pas(180f, 100f))
        assertEquals(-1, Redim.pas(-60f, 100f))
        assertEquals(0, Redim.pas(-40f, 100f))
        assertEquals(0, Redim.pas(500f, 0f))
    }

    @Test fun agrandir_a_droite_et_en_bas() {
        val w = c("w", 0, 0, 2, 1)
        assertEquals(Redim.Rect(0, 0, 3, 1), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.DROITE, 1, libres, 4))
        assertEquals(Redim.Rect(0, 0, 2, 3), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.BAS, 2, libres, 4))
    }

    @Test fun le_bord_de_la_grille_arrete() {
        val w = c("w", 1, 0, 2, 1)
        assertEquals(Redim.Rect(1, 0, 3, 1), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.DROITE, 5, libres, 4))
        assertEquals(Redim.Rect(0, 0, 3, 1), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.GAUCHE, -4, libres, 4))
        assertEquals(Redim.Rect(1, 0, 2, 1), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.HAUT, -2, libres, 4))
    }

    @Test fun reduire_par_la_gauche_et_le_haut() {
        val w = c("w", 0, 0, 4, 3)
        assertEquals(Redim.Rect(2, 0, 2, 3), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.GAUCHE, 2, libres, 4))
        assertEquals(Redim.Rect(0, 2, 4, 1), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.HAUT, 5, libres, 4))   // jamais moins d'une case
    }

    @Test fun sans_chevauchement() {
        val w = c("w", 0, 0, 1, 1)
        val appli = c("appli", 2, 0, 1, 1)
        val bas = c("bas", 0, 2, 4, 1)
        val l = listOf(w, appli, bas)
        // Vers la droite : s'arrête juste avant l'appli.
        assertEquals(Redim.Rect(0, 0, 2, 1), Redim.calculer(l, "w", w.r, Redim.Bord.DROITE, 3, libres, 4))
        // Vers le bas : s'arrête au-dessus du widget du bas.
        assertEquals(Redim.Rect(0, 0, 1, 2), Redim.calculer(l, "w", w.r, Redim.Bord.BAS, 4, libres, 4))
        assertFalse(Redim.libre(l, "w", Redim.Rect(0, 0, 3, 1), 4))
        assertTrue(Redim.libre(l, "w", Redim.Rect(0, 0, 2, 2), 4))
    }

    @Test fun bornes_respectees() {
        val w = c("w", 0, 0, 2, 2)
        val bornes = Redim.Bornes(2, 2, 3, 3)
        assertEquals(Redim.Rect(0, 0, 3, 2), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.DROITE, 2, bornes, 4))
        assertEquals(w.r, Redim.calculer(listOf(w), "w", w.r, Redim.Bord.DROITE, -1, bornes, 4))
        assertEquals(Redim.Rect(0, 0, 2, 3), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.BAS, 6, bornes, 4))
        // Un axe figé (resizeMode) ne bouge pas.
        val horizontalSeul = Redim.Bornes(1, 2, 4, 2, horizontal = true, vertical = false)
        assertEquals(w.r, Redim.calculer(listOf(w), "w", w.r, Redim.Bord.BAS, 2, horizontalSeul, 4))
    }

    @Test fun une_taille_hors_bornes_peut_revenir_dedans() {
        val w = c("w", 0, 0, 1, 1)   // posé avant que l'appli ne demande 2 × 2 au moins
        val bornes = Redim.Bornes(2, 2, 4, 4)
        assertEquals(Redim.Rect(0, 0, 2, 1), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.DROITE, 1, bornes, 4))
    }

    @Test fun jamais_sur_deux_pages() {
        val w = c("w", 0, 3, 2, 1)
        assertEquals(Redim.Rect(0, 3, 2, 2), Redim.calculer(listOf(w), "w", w.r, Redim.Bord.BAS, 3, libres, 4, lignesParPage = 5))
        assertFalse(Redim.libre(emptyList(), "w", Redim.Rect(0, 4, 1, 2), 4, 5))
    }

    @Test fun bornes_d_un_widget_android() {
        // Cellules de 80 × 100 dp ; l'appli demande 180 × 110 dp au moins, se laisse réduire à 70 × 40, au plus 250 × 250.
        val b = Redim.bornesAndroid(180, 110, 70, 40, 250, 250, Redim.RESIZE_HORIZONTAL or Redim.RESIZE_VERTICAL, 80f, 100f, 4, 3 to 2)
        assertEquals(Redim.Bornes(1, 1, 3, 2), b)
        // Sans minResize ni maxResize : la taille minimale, jusqu'à toute la largeur.
        val b2 = Redim.bornesAndroid(180, 110, 0, 0, 0, 0, Redim.RESIZE_HORIZONTAL or Redim.RESIZE_VERTICAL, 80f, 100f, 4, 3 to 2)
        assertEquals(3, b2.minW); assertEquals(2, b2.minH); assertEquals(4, b2.maxW); assertEquals(Redim.LIGNES_MAX, b2.maxH)
        // resizeMode = aucun : figé à la taille posée.
        val b3 = Redim.bornesAndroid(180, 110, 0, 0, 0, 0, 0, 80f, 100f, 4, 3 to 2)
        assertEquals(Redim.Bornes(3, 2, 3, 2, horizontal = false, vertical = false), b3)
        // Horizontal seulement.
        val b4 = Redim.bornesAndroid(180, 110, 0, 0, 0, 0, Redim.RESIZE_HORIZONTAL, 80f, 100f, 4, 3 to 2)
        assertTrue(b4.horizontal); assertFalse(b4.vertical); assertEquals(2, b4.minH); assertEquals(2, b4.maxH)
        // Une taille tout juste égale à des cellules entières n'en demande pas une de plus.
        assertEquals(2, Redim.cellulesPour(160, 80f))
    }

    @Test fun taille_annoncee_en_dp() {
        assertEquals(312 to 192, Redim.tailleDp(4, 2, 80f, 100f))
    }
}
