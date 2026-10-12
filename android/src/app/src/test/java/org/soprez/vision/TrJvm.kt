// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.w3c.dom.Element
import java.io.File
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * La source de textes des essais JVM (sans Android) : `Tr` la trouve d'elle-même quand aucun contexte
 * n'est posé. Elle lit les fichiers XML de `res/values-<langue>` comme Android le ferait : la langue
 * choisie d'abord, puis `values/` (anglais, la langue de repli). Français par défaut, pour que les essais
 * existants gardent leurs textes ; un essai peut changer `langue` le temps d'une vérification.
 */
object TrJvm : Tr.Source {
    @Volatile var langue: String = "fr"

    val res: File = listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
        ?: error("Ressources introuvables")

    class Textes(val chaines: Map<String, String>, val pluriels: Map<String, Map<String, String>>)

    private val cache = HashMap<String, Textes>()

    /** Toutes les chaînes et tous les pluriels d'un dossier (`values`, `values-de`…). */
    @Synchronized fun dossier(nom: String): Textes = cache.getOrPut(nom) {
        val chaines = LinkedHashMap<String, String>()
        val pluriels = LinkedHashMap<String, Map<String, String>>()
        File(res, nom).listFiles { f -> f.name.endsWith(".xml") }?.sortedBy { it.name }?.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val s = doc.getElementsByTagName("string")
            for (i in 0 until s.length) {
                val e = s.item(i) as Element
                chaines[e.getAttribute("name")] = brut(e.textContent)
            }
            val p = doc.getElementsByTagName("plurals")
            for (i in 0 until p.length) {
                val e = p.item(i) as Element
                val items = e.getElementsByTagName("item")
                pluriels[e.getAttribute("name")] = (0 until items.length).associate {
                    val it2 = items.item(it) as Element
                    it2.getAttribute("quantity") to brut(it2.textContent)
                }
            }
        }
        Textes(chaines, pluriels)
    }

    /** Les échappements d'Android (\' \" \n \@ \?) rendus comme à l'affichage. */
    fun brut(t: String): String {
        val b = StringBuilder()
        var i = 0
        val s = t.trim()
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val d = s[i + 1]
                b.append(when (d) { 'n' -> '\n'; 't' -> '\t'; else -> d })
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }

    fun dossierDe(langue: String) = if (langue == "en") "values" else "values-$langue"

    override fun locale(): Locale = Locale.forLanguageTag(langue)

    /** Le nom d'une ressource d'après son numéro (R.string, R.plurals). */
    private fun nom(classe: Class<*>, id: Int): String =
        classe.fields.firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }?.name
            ?: error("Ressource $id introuvable dans ${classe.simpleName}")

    fun chaine(nom: String): String =
        dossier(dossierDe(langue)).chaines[nom] ?: dossier("values").chaines[nom] ?: error("Chaîne $nom absente")

    override fun s(id: Int, args: Array<out Any?>): String {
        val t = chaine(nom(R.string::class.java, id))
        return if (args.isEmpty()) t else String.format(locale(), t, *args)
    }

    /** Les règles de pluriel utiles ici : le français met 0 et 1 au singulier, les autres seulement 1. */
    fun quantite(langue: String, n: Int): String =
        if (n == 1 || (langue == "fr" && n == 0)) "one" else "other"

    override fun p(id: Int, n: Int, args: Array<out Any?>): String {
        val nom = nom(R.plurals::class.java, id)
        val formes = dossier(dossierDe(langue)).pluriels[nom] ?: dossier("values").pluriels[nom] ?: error("Pluriel $nom absent")
        val t = formes[quantite(langue, n)] ?: formes["other"]!!
        return String.format(locale(), t, *args)
    }

    override fun heure(ms: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale()).format(Date(ms))
}
