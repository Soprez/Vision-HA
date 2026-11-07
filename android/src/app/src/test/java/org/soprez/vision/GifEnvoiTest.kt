// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.94 (point 4) : le bouton GIF de « Envoyer un message » : seulement si Home Assistant dit Giphy prêt ; la recherche passe par HA. */
class GifEnvoiTest {
    private fun source(f: String) = listOf(File("src/main/java/org/soprez/vision/$f"), File("app/src/main/java/org/soprez/vision/$f")).first { it.isFile }.readText()

    @Test fun le_bouton_suit_le_champ_giphy_de_home_assistant() {
        val s = source("MessageEnvoiActivity.kt")
        assertTrue(s.contains("d.optBoolean(\"giphy\")") && s.contains("tag = \"gif_bouton\""))
        // La recherche part chez Home Assistant (jamais chez Giphy) ; le choix part dans le message (champ gif).
        assertTrue(s.contains("/api/pc_parental/giphy") && !s.contains("api.giphy.com"))
        assertTrue(s.contains("put(\"gif\", gif)"))
        assertTrue(s.contains("Powered by GIPHY"))
    }
}
