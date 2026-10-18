// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lot 6 (B5 / APP-13, APP-15, APP-17) : focus visible, textes TV lisibles, réglages en listes (le rendu se voit en recette). */
class FocusTvLot6Test {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(nom: String) = File(src, nom).readText()

    @Test fun reglages_en_listes() {
        val r = lire("ReglagesTvActivity.kt")
        // Plus de réglage qui tourne à chaque OK (Démarre après, Veille sur l'accueil, Mode nuit, Mode sombre).
        assertFalse(r.contains("(l.indexOf(delai) + 1) % l.size"))
        assertFalse(r.contains("ReglesVeille.repliSuivant(repli)"))
        assertFalse(r.contains("(Local.PLAGES_NUIT.indexOf(nuit) + 1)"))
        assertFalse(r.contains("Themes.MODES_SOMBRE[(i + 1)"))
        for (f in listOf("choisirDelai()", "choisirRepli()", "choisirNuit()", "choisirModeSombre()")) assertTrue(f, r.contains("{ $f }"))
        // 15-10 : les boîtes de choix sont partagées avec les menus du téléphone (ReglagesPartages).
        assertTrue(lire("ReglagesPartages.kt").contains(".setSingleChoiceItems(libelles.toTypedArray(), courant)"))
    }

    @Test fun textes_tv_au_moins_14_sp() {
        val r = lire("ReglagesTvActivity.kt")
        assertTrue(r.contains("private fun tl(pxMaquette: Float, min: Float = 14f): Float = if (tele) maxOf(t(pxMaquette), min) else t(pxMaquette)"))
        assertTrue(r.contains("private fun note(s: String) = contenu.addView(texte(s, tl(16f)"))
        assertTrue(r.contains("r.addView(texte(valeur, tl(20f)"))
        assertTrue(r.contains("getString(R.string.reglagestv_seulement, grise), tl(16f)"))
    }

    @Test fun focus_visible() {
        val m = lire("MessageEnvoiActivity.kt")
        assertTrue(m.contains("Ui.anneauFocus(this, theme, 999f, agrandir = false)"))
        assertTrue(m.contains("background = Ui.fondChamp(this@MessageEnvoiActivity, theme.carte, theme.encre, theme.carteBord)"))
        assertFalse(m.contains("minHeight = px(44f)"))
        assertTrue(lire("SetupActivity.kt").contains("Ui.anneauFocus(this, Ui.themeActif(this@SetupActivity), 12f, agrandir = false)"))
    }
}
