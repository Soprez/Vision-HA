// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Lot Accueil (point 6) : aucun composant Android au style par défaut. Les interrupteurs, glissières, cases à cocher
 * et sélecteurs de nombre se créent par les fabricants thémés de Ui.kt (Ui.interrupteur, Ui.glissiere,
 * Ui.caseACocher, Ui.selecteurNombre) ; les autres composants natifs sans fabricant Vision (bouton radio, liste
 * déroulante, bouton bascule, notation) sont refusés tant qu'il n'en existe pas un dans Ui.kt.
 */
class ComposantsThemesTest {
    private val racine: File = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision"))
        .firstOrNull { it.isDirectory } ?: error("Sources introuvables depuis ${File(".").absolutePath}")

    private val sources: Map<String, String> by lazy {
        racine.listFiles { f -> f.name.endsWith(".kt") }!!.associate { it.name to it.readText() }
    }

    /** Instanciation directe d'un composant natif (pas `ArrayList<CheckBox>()`, ni un import). */
    private val natif = Regex("""(?<![\w.<])(?:android\.widget\.)?(Switch|SeekBar|CheckBox|NumberPicker|RadioButton|RadioGroup|ToggleButton|Spinner|RatingBar)\(""")

    /**
     * Exceptions justifiées : (fichier, motif de la ligne). Aucune aujourd'hui. Une exception doit dire pourquoi le
     * fabricant de Ui.kt ne convient pas (ex. sous-classe qui doit hériter du composant natif).
     */
    private val exceptions = emptyList<Pair<String, String>>()

    @Test fun composants_natifs_crees_par_ui_seulement() {
        val fautes = ArrayList<String>()
        for ((nom, texte) in sources) {
            if (nom == "Ui.kt") continue
            texte.lines().forEachIndexed { i, l ->
                val code = l.substringBefore("//")
                if (code.trimStart().startsWith("*") || code.trimStart().startsWith("import ")) return@forEachIndexed
                val m = natif.find(code) ?: return@forEachIndexed
                if (exceptions.any { (f, motif) -> f == nom && l.contains(motif) }) return@forEachIndexed
                fautes += "$nom:${i + 1}: ${m.groupValues[1]}( — passer par le fabricant thémé de Ui.kt : ${l.trim().take(120)}"
            }
        }
        if (fautes.isNotEmpty()) fail("Composants Android au style par défaut :\n" + fautes.joinToString("\n"))
    }

    @Test fun les_fabricants_existent() {
        val ui = sources["Ui.kt"] ?: error("Ui.kt introuvable")
        for (f in listOf("fun interrupteur(", "fun glissiere(", "fun caseACocher(", "fun selecteurNombre(", "fun habillerSelecteur("))
            assertTrue("Ui.kt : $f manquant", ui.contains(f))
    }

    @Test fun les_exceptions_existent_encore() {
        for ((f, motif) in exceptions) assertTrue("Exception périmée : $f « $motif »", sources[f]?.contains(motif) == true)
    }
}
