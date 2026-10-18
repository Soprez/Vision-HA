// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test réel de l'auteur (16/10), partie Musique : sauts en série, thème de la page invités. */
class MusiqueTestJaTest {
    /** « Un petit cliquetis, et la lecture est revenue à zéro » : un seul saut à la fois, les suivants fondus en un. */
    @Test fun un_seul_saut_a_la_fois_le_dernier_gagne() {
        val s = Saut.EnSerie()
        assertEquals(40.0, s.demander(40.0, 0L)!!, 0.0)          // part tout de suite
        assertTrue(s.enVol)
        assertNull(s.demander(50.0, 300L))                         // gardé
        assertNull(s.demander(60.0, 600L))                         // remplace le précédent : seule la dernière cible compte
        assertEquals(60.0, s.pris(1_000L)!!, 0.0)                  // le premier a pris : la dernière part
        assertTrue(s.enVol)
        assertNull(s.pris(2_000L))                                 // rien en attente
        assertFalse(s.enVol)
        assertEquals(10.0, s.demander(10.0, 2_100L)!!, 0.0)        // libre : part tout de suite
    }

    @Test fun un_saut_sans_reponse_ne_bloque_jamais() {
        val s = Saut.EnSerie()
        s.demander(40.0, 0L)
        assertNull(s.demander(50.0, 1_000L))
        // Au-delà de l'attente maximale, la demande suivante part d'elle-même.
        assertEquals(70.0, s.demander(70.0, Saut.EnSerie.ATTENTE_MAX_MS + 1)!!, 0.0)
    }

    @Test fun le_theme_voyage_avec_le_code_invites() {
        val th = Themes.liste.first { it.nom == "Sombre" }
        val a = Party.qrAvecTheme("https://ha.exemple/api/pc_parental/party/invite/JETON", th)
        val couleurs = a.substringAfter("?th=").split(",")
        assertEquals(8, couleurs.size)
        assertTrue(couleurs.all { Regex("^[0-9a-f]{6}$").matches(it) })
        assertEquals("17121c", couleurs[0])                          // le fond du thème
        assertEquals("e2b24a", couleurs[5])                          // l'accent (or)
        assertEquals(a, Party.qrAvecTheme(a, th))                   // jamais deux fois
        assertTrue(Party.qrAvecTheme("https://x/y?a=1", th).contains("?a=1&th="))
        assertEquals("", Party.qrAvecTheme("", th))
    }
}
