// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Le lieu vient du serveur (« lieu » : Maison, École…) : aucun texte visible ne dit « maison » en dur.
 * Seules les clés techniques (codes JSON « maison ») et la valeur par défaut Config.LIEU_DEFAUT restent.
 */
class VocabulaireLieuTest {
    private val racine: File = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision"))
        .firstOrNull { it.isDirectory } ?: error("Sources introuvables")

    private val chaine = Regex(""""([^"\\]|\\.)*"""")
    /** Clés et codes du protocole, pas du texte affiché. */
    private val techniques = setOf("\"maison\"", "\"Maison\"")

    @Test fun aucun_maison_en_dur_dans_les_textes() {
        val fautes = ArrayList<String>()
        racine.listFiles { f -> f.name.endsWith(".kt") }!!.forEach { f ->
            f.readLines().forEachIndexed { i, l ->
                val code = l.substringBefore("//")
                chaine.findAll(code).map { it.value }.filter { it.contains("maison", ignoreCase = true) && it !in techniques }
                    .forEach { fautes += "${f.name}:${i + 1}: $it" }
                if (code.contains("\"Maison\"") && !code.contains("LIEU_DEFAUT")) fautes += "${f.name}:${i + 1}: « Maison » hors de Config.LIEU_DEFAUT"
            }
        }
        if (fautes.isNotEmpty()) fail("« maison » en dur (utiliser Config.lieu ou une tournure neutre) :\n" + fautes.joinToString("\n"))
    }
}
