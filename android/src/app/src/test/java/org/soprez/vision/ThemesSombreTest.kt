// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.72.0 (point 14.4) : la variante sombre de chaque thème clair reste lisible. */
class ThemesSombreTest {

    @Test
    fun chaqueThemeClairALaSienneLisible() {
        val clairs = Themes.liste.filter { !it.sombre }
        assertTrue("au moins un thème clair", clairs.isNotEmpty())
        for (t in clairs) {
            val s = Themes.versionSombre(t)
            assertTrue("${t.nom} : sombre", s.sombre)
            assertEquals("${t.nom} : même nom (l'horloge de la veille en dépend)", t.nom, s.nom)
            assertTrue("${t.nom} : fond sombre", Themes.luminance(s.fond) < 0.15f)
            // Texte principal : AAA ; secondaire : AA ; accent et or : au moins le seuil des grands textes et des icônes.
            assertTrue("${t.nom} : encre sur fond ${Themes.contraste(s.encre, s.fond)}", Themes.contraste(s.encre, s.fond) >= 7f)
            assertTrue("${t.nom} : encre sur carte ${Themes.contraste(s.encre, s.carte)}", Themes.contraste(s.encre, s.carte) >= 7f)
            assertTrue("${t.nom} : encre2 sur carte ${Themes.contraste(s.encre2, s.carte)}", Themes.contraste(s.encre2, s.carte) >= 4.5f)
            assertTrue("${t.nom} : accent sur fond ${Themes.contraste(s.pourpre, s.fond)}", Themes.contraste(s.pourpre, s.fond) >= 3f)
            assertTrue("${t.nom} : or sur carte ${Themes.contraste(s.or, s.carte)}", Themes.contraste(s.or, s.carte) >= 4.5f)
        }
    }

    @Test
    fun unThemeDejaSombreResteTelQuel() {
        val t = Themes.parNom("Bleu nuit")
        assertSame(t, Themes.versionSombre(t))
    }

    @Test
    fun leChoix() {
        assertFalse(Themes.sombreVoulu(Themes.SOMBRE_JAMAIS, systemeSombre = true, nuit = false))
        assertTrue(Themes.sombreVoulu(Themes.SOMBRE_JAMAIS, systemeSombre = false, nuit = true))
        assertTrue(Themes.sombreVoulu(Themes.SOMBRE_SYSTEME, systemeSombre = true, nuit = false))
        assertFalse(Themes.sombreVoulu(Themes.SOMBRE_SYSTEME, systemeSombre = false, nuit = false))
        assertTrue(Themes.sombreVoulu(Themes.SOMBRE_TOUJOURS, systemeSombre = false, nuit = false))
    }
}
