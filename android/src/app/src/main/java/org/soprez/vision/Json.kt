// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Un petit lecteur et écrivain JSON sans Android (org.json n'existe pas dans les essais JUnit) :
 * les réponses /widgets/… et les réglages des widgets Home Assistant passent par lui, si bien que
 * leur analyse est essayée telle quelle (WidgetsHaTest).
 *
 * Objet → Map (ordre gardé), tableau → List, nombre → Double, texte → String, true/false → Boolean, null → null.
 */
object Json {
    class Erreur(message: String) : Exception(message)

    fun lire(texte: String): Any? {
        val l = Lecteur(texte)
        l.blancs()
        val v = l.valeur()
        l.blancs()
        if (l.i < texte.length) throw Erreur("JSON : texte en trop à la position ${l.i}")
        return v
    }

    /** Lit un objet ; null si le texte n'en est pas un (ou est illisible). */
    @Suppress("UNCHECKED_CAST")
    fun objet(texte: String?): Map<String, Any?>? = try { if (texte.isNullOrBlank()) null else lire(texte) as? Map<String, Any?> } catch (_: Exception) { null }

    fun ecrire(v: Any?): String = StringBuilder().also { ecrireDans(it, v) }.toString()

    private fun ecrireDans(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> chaine(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Number -> { val d = v.toDouble(); if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d) }
            is Map<*, *> -> {
                sb.append('{'); var premier = true
                for ((k, x) in v) { if (!premier) sb.append(','); premier = false; chaine(sb, k.toString()); sb.append(':'); ecrireDans(sb, x) }
                sb.append('}')
            }
            is Iterable<*> -> { sb.append('['); var premier = true; for (x in v) { if (!premier) sb.append(','); premier = false; ecrireDans(sb, x) }; sb.append(']') }
            else -> chaine(sb, v.toString())
        }
    }

    private fun chaine(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Lecteur(val s: String) {
        var i = 0
        fun blancs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun valeur(): Any? {
            if (i >= s.length) throw Erreur("JSON : fin inattendue")
            return when (val c = s[i]) {
                '{' -> objet()
                '[' -> tableau()
                '"' -> chaine()
                't' -> mot("true", true)
                'f' -> mot("false", false)
                'n' -> mot("null", null)
                else -> if (c == '-' || c.isDigit()) nombre() else throw Erreur("JSON : caractère inattendu « $c » à la position $i")
            }
        }
        private fun mot(m: String, v: Any?): Any? { if (!s.startsWith(m, i)) throw Erreur("JSON : « $m » attendu"); i += m.length; return v }
        private fun nombre(): Double {
            val d = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(d, i).toDoubleOrNull() ?: throw Erreur("JSON : nombre illisible")
        }
        private fun chaine(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Erreur("JSON : texte non fermé")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw Erreur("JSON : échappement incomplet")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { if (i + 4 > s.length) throw Erreur("JSON : \\u incomplet"); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        private fun objet(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            blancs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                blancs()
                if (i >= s.length || s[i] != '"') throw Erreur("JSON : clé attendue à la position $i")
                val k = chaine()
                blancs()
                if (i >= s.length || s[i] != ':') throw Erreur("JSON : « : » attendu")
                i++; blancs()
                m[k] = valeur()
                blancs()
                if (i >= s.length) throw Erreur("JSON : objet non fermé")
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw Erreur("JSON : « , » ou « } » attendu") }
            }
        }
        private fun tableau(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            blancs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                blancs(); l.add(valeur()); blancs()
                if (i >= s.length) throw Erreur("JSON : tableau non fermé")
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw Erreur("JSON : « , » ou « ] » attendu") }
            }
        }
    }
}
