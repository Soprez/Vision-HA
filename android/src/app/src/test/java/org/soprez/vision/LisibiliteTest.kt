// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lot 6 (B9 / APP-20, APP-22) : garde-fou de contraste, nom des couleurs, émojis non lus. */
class LisibiliteTest {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(nom: String) = File(src, nom).readText()

    @After fun langue() { TrJvm.langue = "fr" }

    @Test fun texte_ajuste_jusqua_4_5() {
        val fond = 0xFFEFE6D6.toInt(); val carte = 0xFFFBF6EC.toInt()
        // Gris clair sur beige : illisible, rapproché du noir juste assez.
        val a = Lisibilite.ajusterTexte(0xFFB0A8A0.toInt(), fond, carte)
        assertTrue(a.change && a.atteint)
        assertTrue(a.contraste >= 4.5f)
        assertTrue(Themes.contrasteTexte(a.couleur, fond, carte) >= 4.5f)
        assertTrue("assombri, pas inversé", Themes.luminance(a.couleur) < Themes.luminance(0xFFB0A8A0.toInt()))
        // Déjà lisible : inchangé.
        val b = Lisibilite.ajusterTexte(0xFF2A2026.toInt(), fond, carte)
        assertFalse(b.change); assertEquals(0xFF2A2026.toInt(), b.couleur)
        // Fond sombre : on éclaircit.
        val c = Lisibilite.ajusterTexte(0xFF404040.toInt(), 0xFF17121C.toInt(), 0xFF251C2D.toInt())
        assertTrue(c.atteint && Themes.luminance(c.couleur) > Themes.luminance(0xFF404040.toInt()))
        // Fond noir et cartes blanches : aucune couleur ne va sur les deux, on le dit.
        assertFalse(Lisibilite.ajusterTexte(0xFF808080.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()).atteint)
    }

    @Test fun accent_ajuste_jusqua_3() {
        val a = Lisibilite.ajusterAccent(0xFFE2B24A.toInt(), 0xFFFFFFFF.toInt())
        assertTrue(a.change && a.contraste >= 3f && Themes.contraste(a.couleur, 0xFFFFFFFF.toInt()) >= 3f)
        assertFalse(Lisibilite.ajusterAccent(0xFF8C2F4B.toInt(), 0xFFFFFFFF.toInt()).change)
    }

    @Test fun noms_des_couleurs() {
        assertEquals("noir", Lisibilite.nomCouleur(0xFF000000.toInt()))
        assertEquals("blanc", Lisibilite.nomCouleur(0xFFFFFFFF.toInt()))
        assertEquals("bleu foncé", Lisibilite.nomCouleur(0xFF0F1830.toInt()))
        assertEquals("beige", Lisibilite.nomCouleur(0xFFEFE6D6.toInt()))
        assertEquals("rouge", Lisibilite.nomCouleur(0xFFE02020.toInt()))
        assertEquals("vert foncé", Lisibilite.nomCouleur(0xFF10201A.toInt()))
        assertEquals("bleu", Lisibilite.nomCouleur(0xFF6FA8E8.toInt()))
        assertEquals("jaune", Lisibilite.nomCouleur(0xFFE2D040.toInt()))
        // Toutes les pastilles de Couleurs ont un nom, jamais un code.
        val pastilles = Regex("\"(#[0-9A-F]{6})\"").findAll(lire("CouleursActivity.kt")).map { it.groupValues[1] }.toList()
        assertTrue(pastilles.size >= 20)
        for (h in pastilles) {
            val n = Lisibilite.nomCouleur(java.lang.Long.parseLong(h.drop(1), 16).toInt() or 0xFF000000.toInt())
            assertFalse(h, n.contains("#") || n.isBlank())
        }
        TrJvm.langue = "en"; assertEquals("dark blue", Lisibilite.nomCouleur(0xFF0F1830.toInt()))
        TrJvm.langue = "nl"; assertEquals("donkerblauw", Lisibilite.nomCouleur(0xFF0F1830.toInt()))
        assertTrue(lire("CouleursActivity.kt").contains("Lisibilite.nomCouleur(c)"))
    }

    @Test fun emojis_non_lus() {
        assertEquals("Réservé aux décideurs", Lisibilite.sansEmojis("Réservé aux décideurs 🔒"))
        assertEquals("Payez-moi un café", Lisibilite.sansEmojis("☕ Payez-moi un café"))
        assertEquals("Envoyé", Lisibilite.sansEmojis("✅ Envoyé"))
        assertEquals("Un message", Lisibilite.sansEmojis("✉️ Un message"))
        assertEquals("Rien à retirer · ici ›", Lisibilite.sansEmojis("Rien à retirer · ici ›"))
        assertEquals("", Lisibilite.sansEmojis("🔒"))
        assertTrue(lire("Ui.kt").contains("setAccessibilityDelegate(LectureSansEmojis)"))
    }

    @Test fun cibles_et_descriptions() {
        assertTrue(lire("ParentsActivity.kt").contains("entete.minimumHeight = Ui.dp(this, Ui.CIBLE_MIN)"))
        assertTrue(lire("LanceurActivity.kt").contains("contentDescription = getString(R.string.tableauediteur_renommer)"))
        assertTrue(lire("MessageActivity.kt").contains("IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(lire("CouleursActivity.kt").contains("Lisibilite.ajusterTexte(th.encre, th.fond, th.carte)"))
    }
}
