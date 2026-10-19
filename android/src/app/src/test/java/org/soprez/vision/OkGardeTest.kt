// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.93.06.1 (l'auteur, 19/10 : « clics fantômes » sur la télé). Un appui sur OK vaut pour l'élément focalisé à l'enfoncement :
 * même vue → Android agit ; même élément sur une vue rebâtie → rejoué ; autre élément (ou plus rien) → ignoré et dit.
 */
class OkGardeTest {
    @Test fun meme_vue_android_agit() {
        assertEquals(OkGarde.Decision.NORMAL, OkGarde.decider("file:q10", "file:q10", memeVue = true))
    }

    @Test fun meme_element_sur_une_vue_rebatie_est_rejoue() {
        // La file est montée d'une ligne pendant l'appui : la ligne du même titre est une nouvelle vue.
        assertEquals(OkGarde.Decision.REJOUER, OkGarde.decider("file:q10", "file:q10", memeVue = false))
    }

    @Test fun un_autre_element_est_ignore() {
        // 11:27:59 sur la TCL : l'appui est parti sur un autre élément que celui choisi.
        assertEquals(OkGarde.Decision.IGNORER, OkGarde.decider("file:q10", "file:q11", memeVue = false))
        assertEquals(OkGarde.Decision.IGNORER, OkGarde.decider("app:com.tcl.notereminder", "app:org.soprez.vision.musique", memeVue = false))
        // Plus de focus du tout au relâché (écran en cours de reconstruction).
        assertEquals(OkGarde.Decision.IGNORER, OkGarde.decider("Lecture", null, memeVue = false))
    }

    @Test fun sans_enfoncement_connu_android_decide() {
        assertEquals(OkGarde.Decision.NORMAL, OkGarde.decider(null, "file:q10", memeVue = false))
    }

    @Test fun le_journal_nomme_les_deux_elements() {
        val l = OkGarde.journal(OkGarde.Decision.IGNORER, "Titre en file : Jump Around", "Titre en file : The Golden Path")
        assertTrue(l, l.contains("Jump Around") && l.contains("The Golden Path") && l.startsWith("OK ignoré"))
        assertTrue(OkGarde.journal(OkGarde.Decision.REJOUER, "file:q10", "file:q10").contains("rejoué"))
        assertTrue(OkGarde.journal(OkGarde.Decision.IGNORER, "Lecture", null).contains("rien"))
    }
}
