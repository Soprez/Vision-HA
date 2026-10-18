// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 15-10 (règle UX de l'auteur) : étiquettes si 3 options ou moins tiennent sur une ligne, sinon liste déroulante. */
class ChoixAdaptatifTest {
    @Test fun trois_au_plus_qui_tiennent() {
        assertTrue(ChoixAdaptatif.enEtiquettes(3, largeurEtiquettes = 300, dispo = 360))
        assertTrue(ChoixAdaptatif.enEtiquettes(1, 80, 360))
        // Plus de trois : liste, même si ça tiendrait.
        assertFalse(ChoixAdaptatif.enEtiquettes(4, 200, 1000))
        // Trois qui débordent (grande police, écran étroit, libellés longs) : liste.
        assertFalse(ChoixAdaptatif.enEtiquettes(3, 420, 360))
        // Aucune option : jamais d'étiquettes.
        assertFalse(ChoixAdaptatif.enEtiquettes(0, 0, 360))
        // Largeur inconnue (mesure libre) : on compte seulement.
        assertTrue(ChoixAdaptatif.enEtiquettes(3, 5000, 0))
        assertFalse(ChoixAdaptatif.enEtiquettes(5, 100, 0))
    }

    @Test fun le_champ_montre_le_choix() {
        assertEquals("Aucun", ChoixAdaptatif.resume(emptyList(), "Aucun"))
        assertEquals("Titres", ChoixAdaptatif.resume(listOf("Titres"), "Aucun"))
        assertEquals("Bibliothèque, YouTube Music", ChoixAdaptatif.resume(listOf("Bibliothèque", "YouTube Music"), "Aucun"))
        assertEquals("A, B +2", ChoixAdaptatif.resume(listOf("A", "B", "C", "D"), "Aucun"))
    }

    /** Les écrans qui avaient des rangées d'étiquettes de choix passent par le composant (plus de rangée maison). */
    @Test fun ecrans_branches() {
        val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
        fun lire(n: String) = File(src, n).readText()
        val e = lire("EditeurWidgetHaActivity.kt")
        assertFalse(e.contains("rangeeChoix("))
        for (t in listOf("ew_style", "ew_variante", "ew_periode", "ew_affichage", "ew_defilement", "ew_hauteur", "ew_mep_h", "ew_mep_v", "ew_mep_taille",
            "ew_mep_montrer", "ew_toucher", "ew_taille", "ew_icone")) assertTrue(t, e.contains("\"$t\""))
        val m = lire("MessageEnvoiActivity.kt")
        assertFalse(m.contains("Pastilles("))
        assertTrue(m.contains("maxChoix = 3"))
        val s = lire("SetupActivity.kt")
        assertFalse(s.contains("Pastilles("))
        assertTrue(s.contains("\"pause_duree\"") && s.contains("\"garde_minutes\""))
        assertTrue(lire("DeverrouillageParentActivity.kt").contains("\"dpa_duree\""))
    }
}
