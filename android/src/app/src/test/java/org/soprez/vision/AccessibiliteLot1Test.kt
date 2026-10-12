// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Refonte UX, lot 1 (A7 / APP-13, APP-14, APP-15, APP-20, APP-21, APP-22) : accessibilité de l'appli.
 * Le rendu réel (focus à la télécommande) est vérifié par les essais instrumentés ; ici, les règles et leur présence.
 */
class AccessibiliteLot1Test {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(nom: String) = File(src, nom).readText()

    @Test fun contraste_des_couleurs_a_la_carte() {
        assertEquals(4.5f, Themes.CONTRASTE_TEXTE_MIN)
        // Texte gris clair sur fond beige : sous le seuil ; encre sombre : au-dessus.
        assertTrue(Themes.contrasteTexte(0xFFB0A8A0.toInt(), 0xFFEFE6D6.toInt(), 0xFFFBF6EC.toInt()) < 4.5f)
        assertTrue(Themes.contrasteTexte(0xFF2A2026.toInt(), 0xFFEFE6D6.toInt(), 0xFFFBF6EC.toInt()) >= 4.5f)
        // Le plus faible des deux (fond, cartes) compte.
        assertEquals(Themes.contraste(0xFF777777.toInt(), 0xFFFFFFFF.toInt()), Themes.contrasteTexte(0xFF777777.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        val c = lire("CouleursActivity.kt")
        assertTrue(c.contains("Themes.contrasteTexte(th.encre, th.fond, th.carte).takeIf { it < Themes.CONTRASTE_TEXTE_MIN }"))
        assertTrue(c.contains("R.string.couleurs_contraste_faible"))
    }

    @Test fun cibles_de_48_dp() {
        assertEquals(48f, Ui.CIBLE_MIN)
        val ui = lire("Ui.kt")
        assertFalse("plus de cible de 44 dp dans Ui", Regex("""minHeight = dp\(ctx, 44f\)""").containsMatchIn(ui))
        assertTrue(lire("LanceurActivity.kt").contains("val roue = maxOf(tv(96f), Ui.CIBLE_MIN)"))
        assertTrue(lire("ReglagesTvActivity.kt").contains("minWidth = px(Ui.CIBLE_MIN); minHeight = px(Ui.CIBLE_MIN)"))
        assertTrue(lire("TableauEditeurActivity.kt").contains("minWidth = px(Ui.CIBLE_MIN); minHeight = px(Ui.CIBLE_MIN)"))
    }

    @Test fun focus_des_boites_de_dialogue_et_des_champs() {
        val ui = lire("Ui.kt")
        // Bouton au focus : encre sur carte, bord à l'encre (plus de doré sur blanc).
        assertTrue(ui.contains("d.getButton(b)?.let { boutonDeDialogue(it, th) }"))
        assertTrue(ui.contains("intArrayOf(th.encre, th.or)"))
        // ▼ depuis la liste : le bouton de validation.
        assertTrue(ui.contains("lv.nextFocusDownId = it.id") && ui.contains("BUTTON_POSITIVE)?.takeIf"))
        // Champ de saisie : bord épais à l'encre au focus.
        assertTrue(ui.contains("background = fondChamp(ctx, FOND, TEXTE, LIGNE)"))
    }

    @Test fun aide_du_rail_tv_lisible() {
        assertTrue(lire("ReglagesTvActivity.kt").contains("getString(R.string.reglagestv_aide_rail), maxOf(t(15f), 14f)"))
    }

    @Test fun boutons_a_icone_decrits() {
        assertTrue(lire("CouleursActivity.kt").contains("contentDescription = getString(R.string.a11y_retour)"))
        assertTrue(lire("ParentsActivity.kt").contains("contentDescription = getString(R.string.a11y_actualiser)"))
        assertTrue(lire("DeverrouillageParentActivity.kt").contains("contentDescription = getString(R.string.a11y_qr_deverrouillage)"))
        assertTrue(lire("ReglagesTvActivity.kt").contains("R.string.a11y_allonger_5_s else R.string.a11y_raccourcir_5_s"))
        val t = lire("TableauEditeurActivity.kt")
        assertTrue(t.contains("R.string.a11y_retirer_case") && t.contains("R.string.a11y_allonger_5_s") && t.contains("R.string.a11y_raccourcir_5_s"))
    }
}
