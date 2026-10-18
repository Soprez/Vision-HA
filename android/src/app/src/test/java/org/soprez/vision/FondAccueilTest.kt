package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.91.x (retour d'essai) : l'arrière-plan de l'accueil — le voile garde le texte lisible, sans assombrir pour rien. */
class FondAccueilTest {
    private val encreClaire = 0xFFF4EDE1.toInt()  // texte d'un thème sombre
    private val fondSombre = 0xFF17121C.toInt()
    private val encre2Claire = 0xFFBDB1C4.toInt()  // noms d'applis d'un thème sombre
    private val encreSombre = 0xFF2A2026.toInt()  // texte d'un thème clair
    private val fondClair = 0xFFEFE6D6.toInt()

    @Test fun fondDejaCalmePasDeVoile() {
        assertEquals(0f, FondAccueil.voile(listOf(fondSombre), fondSombre, listOf(encreClaire)), 0f)
    }

    @Test fun photoTresClaireSousUnThemeSombreUnVoileQuiSuffit() {
        val claire = listOf(0xFFFFFFFF.toInt(), 0xFFF4F1EA.toInt(), 0xFFE0E8F0.toInt())
        val a = FondAccueil.voile(claire, fondSombre, listOf(encreClaire, encre2Claire))
        assertTrue("voile attendu : $a", a > 0.3f)
        assertTrue("au plus 70 % : $a", a <= FondAccueil.VOILE_MAX + 0.001f)
        // Les titres ET les noms d'applis (encre2) gardent 4,5:1.
        claire.forEach { f -> listOf(encreClaire, encre2Claire).forEach { assertTrue(FondAccueil.contraste(FondAccueil.melange(f, fondSombre, a), it) >= FondAccueil.CONTRASTE_MIN) } }
    }

    @Test fun photoTresSombreSousUnThemeClair() {
        val sombre = listOf(0xFF000000.toInt(), 0xFF101418.toInt())
        val a = FondAccueil.voile(sombre, fondClair, listOf(encreSombre))
        sombre.forEach { assertTrue(FondAccueil.contraste(FondAccueil.melange(it, fondClair, a), encreSombre) >= FondAccueil.CONTRASTE_MIN) }
    }

    @Test fun leDecideurRenforceJamaisMoinsQueLeMinimum() {
        val claire = listOf(0xFFFFFFFF.toInt())
        val base = FondAccueil.voile(claire, fondSombre, listOf(encreClaire, encre2Claire))
        assertEquals(base + 0.15f, FondAccueil.voile(claire, fondSombre, listOf(encreClaire, encre2Claire), renfort = 0.15f), 0.001f)
    }

    @Test fun contrasteWcag() {
        assertEquals(21.0, FondAccueil.contraste(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, FondAccueil.contraste(fondSombre, fondSombre), 0.0001)
    }
}
