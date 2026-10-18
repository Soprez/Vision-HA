// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Réveil : les textes en 6 langues (res/values-…/strings_reveil.xml). Mêmes clés partout, mêmes champs (%1$s, %1$d),
 * apostrophes échappées (sinon aapt refuse), pas de texte vide, vocabulaire neutre (jamais famille, maison, parent,
 * enfant ni leurs équivalents), et chaque clé employée par le code existe.
 */
class ReveilTextesTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val langues = listOf("values", "values-fr", "values-de", "values-es", "values-it", "values-nl")
    private val interdits = Regex("""famil|maison|parent|enfant|\bkids?\b|\bchild|eltern|\bkind(er)?\b|gezin|ouders|famiglia|genitor|bambin|padres|niñ|hijo""", RegexOption.IGNORE_CASE)

    private fun lire(langue: String): Map<String, String> =
        Regex("""<string name="([a-z_]+)">(.*?)</string>""").findAll(File(res, "$langue/strings_reveil.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun champs(t: String): List<String> = Regex("""%\d\$[sd]""").findAll(t).map { it.value }.sorted().toList()

    @Test fun memesClesEtChampsDansLesSixLangues() {
        val ref = lire("values")
        assertTrue(ref.size >= 50)
        for (l in langues) {
            val t = lire(l)
            assertEquals("clés de $l", ref.keys, t.keys)
            for ((k, v) in t) {
                assertTrue("$l/$k vide", v.isNotBlank())
                assertEquals("champs de $l/$k", champs(ref.getValue(k)), champs(v))
                assertFalse("apostrophe non échappée dans $l/$k", Regex("""(?<!\\)'""").containsMatchIn(v))
                assertFalse("mot interdit dans $l/$k : $v", interdits.containsMatchIn(v))
            }
        }
    }

    @Test fun chaqueCleDuCodeExiste() {
        val code = File(res.parentFile, "java/org/soprez/vision").listFiles { f -> f.name.startsWith("Reveil") || f.name in setOf("SetupActivity.kt", "ReglagesTvActivity.kt") }!!
            .joinToString("\n") { it.readText() }
        val utilisees = Regex("""R\.string\.(reveils?_[a-z_]+)""").findAll(code).map { it.groupValues[1] }.toSet()
        val ref = lire("values").keys
        assertTrue(utilisees.isNotEmpty())
        assertEquals("clés employées mais absentes", emptySet<String>(), utilisees - ref)
        assertEquals("clés inutilisées", emptySet<String>(), ref - utilisees)
    }
}
