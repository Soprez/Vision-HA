// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lanceur (liste alphabétique, zone du haut, télé Vertical) : les textes nouveaux, dans `strings_lanceur.xml` :
 *  - chaque clé existe dans les 6 dossiers (values = anglais, values-fr…), mêmes champs positionnels, mêmes pluriels ;
 *  - aucun mot interdit du vocabulaire neutre ;
 *  - plus de texte français en dur dans les fichiers Kotlin apparus depuis (les autres sont couverts par les lots 1 et 2).
 */
class I18nLanceurTest {
    private val langues = listOf("en", "fr", "de", "es", "it", "nl")
    private val fichiers = listOf("strings_lanceur.xml")

    private class Res(val chaines: Map<String, String>, val pluriels: Map<String, Map<String, String>>, val tableaux: Map<String, List<String>>)

    private fun lire(langue: String, nom: String): Res {
        val f = File(TrJvm.res, "${TrJvm.dossierDe(langue)}/$nom")
        assertTrue("absent : $f", f.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val chaines = LinkedHashMap<String, String>()
        val s = doc.getElementsByTagName("string")
        for (i in 0 until s.length) { val e = s.item(i) as Element; chaines[e.getAttribute("name")] = TrJvm.brut(e.textContent) }
        val pluriels = LinkedHashMap<String, Map<String, String>>()
        val p = doc.getElementsByTagName("plurals")
        for (i in 0 until p.length) {
            val e = p.item(i) as Element
            val items = e.getElementsByTagName("item")
            pluriels[e.getAttribute("name")] = (0 until items.length).associate { val x = items.item(it) as Element; x.getAttribute("quantity") to TrJvm.brut(x.textContent) }
        }
        val tableaux = LinkedHashMap<String, List<String>>()
        val a = doc.getElementsByTagName("string-array")
        for (i in 0 until a.length) {
            val e = a.item(i) as Element
            val items = e.getElementsByTagName("item")
            tableaux[e.getAttribute("name")] = (0 until items.length).map { TrJvm.brut(items.item(it).textContent) }
        }
        return Res(chaines, pluriels, tableaux)
    }

    private val champ = Regex("""%(\d+)\$[-0-9.,]*([sdf])""")
    private fun champs(t: String): List<String> = champ.findAll(t).map { "%" + it.groupValues[1] + "\$" + it.groupValues[2] }.toList().sorted()

    @Test fun chaque_cle_existe_dans_les_six_langues_avec_les_memes_champs() {
        val fautes = ArrayList<String>()
        for (nom in fichiers) {
            val ref = lire("en", nom)
            assertTrue("$nom vide", ref.chaines.isNotEmpty())
            for (l in langues) {
                val r = lire(l, nom)
                for ((k, v) in ref.chaines) {
                    val t = r.chaines[k]
                    if (t == null) { fautes += "$nom [$l] : $k manquante"; continue }
                    if (t.isBlank()) fautes += "$nom [$l] : $k vide"
                    if (champs(t) != champs(v)) fautes += "$nom [$l] : $k champs ${champs(t)} ≠ ${champs(v)}"
                }
                (r.chaines.keys - ref.chaines.keys).forEach { fautes += "$nom [$l] : $it en trop (absente de values/)" }
                for ((k, formes) in ref.pluriels) {
                    val f = r.pluriels[k]
                    if (f == null) { fautes += "$nom [$l] : pluriel $k manquant"; continue }
                    if (f.keys != formes.keys) fautes += "$nom [$l] : pluriel $k quantités ${f.keys} ≠ ${formes.keys}"
                    if (!f.containsKey("other")) { fautes += "$nom [$l] : pluriel $k sans « other »"; continue }
                    if (champs(f["other"]!!) != champs(formes["other"]!!)) fautes += "$nom [$l] : pluriel $k champs différents"
                    f["one"]?.let { if (!champs(f["other"]!!).containsAll(champs(it))) fautes += "$nom [$l] : pluriel $k, « one » a un champ inconnu" }
                }
                for ((k, items) in ref.tableaux) {
                    val t = r.tableaux[k]
                    if (t == null) fautes += "$nom [$l] : tableau $k manquant"
                    else if (t.size != items.size) fautes += "$nom [$l] : tableau $k, ${t.size} éléments ≠ ${items.size}"
                }
            }
        }
        if (fautes.isNotEmpty()) fail("Ressources du lanceur :\n" + fautes.joinToString("\n"))
    }

    /** Vocabulaire neutre (docs/i18n/vocabulaire.md) ; « Home Assistant », « home screen » et la touche Home restent permis. */
    @Test fun aucun_mot_interdit() {
        val interdits = Regex("""(?i)\b(famil\w*|maison|parents?|enfants?|house|zuhause|haus|eltern|kind(er)?|casa|hogar|padres|niñ[oa]s?|hij[oa]s?|genitori|figli\w*|bambin\w*|gezin|thuis|huis|ouders|child\w*|kids?)\b""")
        val permis = Regex("""(?i)home assistant|home-assistant""")
        val fautes = ArrayList<String>()
        for (nom in fichiers) for (l in langues) {
            val r = lire(l, nom)
            val textes = r.chaines.map { it.key to it.value } + r.pluriels.flatMap { (k, f) -> f.values.map { k to it } } + r.tableaux.flatMap { (k, t) -> t.map { k to it } }
            for ((k, t) in textes) {
                val m = interdits.find(permis.replace(t, "")) ?: continue
                fautes += "$nom [$l] $k : « ${m.value} » dans « $t »"
            }
            for ((k, t) in textes) if (Regex("""(?i)\bhome\b""").containsMatchIn(permis.replace(t, "")) &&
                !Regex("""(?i)home screen|home button|press home|home twice|home-taste|home-knop|tasto home|schermata home|su home|op home|zweimal home""").containsMatchIn(t))
                fautes += "$nom [$l] $k : « home » dans « $t »"
        }
        if (fautes.isNotEmpty()) fail("Mots interdits :\n" + fautes.joinToString("\n"))
    }

    // ------------------------------------------------------------------ texte français en dur dans le Kotlin du lot

    private val lot = listOf(
        "DispositionAccueil.kt", "IndexAlpha.kt", "ListeAlpha.kt",
    )

    /** Noms propres et valeurs techniques, jamais traduits (glossaire). */
    private val nomsPropres = setOf("Vision", "VISION", "Home Assistant", "Pronote", "Valorant", "Rocket League", "Accept", "Silent")

    /** Exceptions justifiées : (fichier, littéral). */
    private val exceptions = setOf(
        // En-têtes HTTP et fin de flux des événements serveur (protocole), jamais affichés.
        "FluxMusique.kt" to "application/json; charset=utf-8", "FluxMusique.kt" to "event: fin",
        // Protocole SlimProto (présentation du lecteur à Music Assistant) et requête HTTP lue dans la commande.
        "LecteurSlim.kt" to "Model=squeezelite,ModelName=Vision,Firmware=Vision-{…},MaxSampleRate=48000,mp3",
        "LecteurSlim.kt" to "^GET (\\\\S+) HTTP",
    )

    /**
     * Listes de motifs de reconnaissance (mots des écrans d'Android ou des raisons de Home Assistant, dans chaque langue) :
     * des données, pas des textes affichés. Le détecteur saute leurs lignes.
     */
    private val blocsMotifs = listOf("val DESINSTALLATION", "val motsSensibles", "val MOTS_TEMPS")

    private fun littéraux(src: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0; var ligne = 1; val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '\n' -> { ligne++; i++ }
                src.startsWith("//", i) -> { val j = src.indexOf('\n', i); i = if (j < 0) n else j }
                src.startsWith("/*", i) -> { val j = src.indexOf("*/", i + 2); ligne += src.substring(i, j).count { it == '\n' }; i = j + 2 }
                c == '\'' -> { var j = i + 1; while (j < n && src[j] != '\'') j += if (src[j] == '\\') 2 else 1; i = j + 1 }
                c == '"' -> {
                    val brut = src.startsWith("\"\"\"", i); val debut = ligne
                    var j = i + if (brut) 3 else 1
                    val b = StringBuilder()
                    while (j < n) {
                        if (brut && src.startsWith("\"\"\"", j)) { j += 3; break }
                        if (!brut && src[j] == '"') { j++; break }
                        if (!brut && src[j] == '\\') { b.append(src, j, minOf(n, j + 2)); j += 2; continue }
                        if (src.startsWith("\${", j)) {
                            var prof = 0; var k = j + 1
                            while (k < n) { if (src[k] == '{') prof++ else if (src[k] == '}') { prof--; if (prof == 0) break }; k++ }
                            ligne += src.substring(j, k).count { it == '\n' }; b.append("{…}"); j = k + 1; continue
                        }
                        if (src[j] == '\n') ligne++
                        b.append(src[j]); j++
                    }
                    out += debut to b.toString(); i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun suspect(s: String): Boolean {
        val t = s.trim()
        if (!Regex("""[A-Za-zÀ-ÿ]{2,}""").containsMatchIn(t)) return false
        if (Regex("""[À-ÿ]""").containsMatchIn(t)) return true
        if (Regex("""[A-ZÀ-Ý][a-zà-ÿ]{2,}[.…!?]?""").matches(t)) return true
        return t.contains(' ') && Regex("""[A-Za-zÀ-ÿ]{2,}""").findAll(t).count() >= 2
    }

    @Test fun plus_de_francais_en_dur_dans_le_kotlin_du_lot() {
        val racine = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
        val fautes = ArrayList<String>()
        for (nom in lot) {
            if ((nom to "*") in exceptions) continue
            val src = File(racine, nom).readText().replace("\r\n", "\n")
            val lignes = src.lines()
            // Les lignes des listes de motifs (de la déclaration à la parenthèse fermante).
            val sautees = HashSet<Int>()
            lignes.forEachIndexed { k, l ->
                if (blocsMotifs.any { l.contains(it) }) { var m = k; while (m < lignes.size) { sautees += m + 1; if (lignes[m].trim() == ")") break; m++ } }
            }
            for ((no, t) in littéraux(src)) {
                val l = lignes[no - 1]
                if (no in sautees || l.contains("i18n-exception") || Regex("""\bLog\.[diwev]\(""").containsMatchIn(l) || l.trimStart().startsWith("@")) continue
                if (!suspect(t) || t.trim() in nomsPropres || (nom to t) in exceptions) continue
                fautes += "$nom:$no : « $t »"
            }
        }
        if (fautes.isNotEmpty()) fail("Texte français en dur (à passer en ressource, ou exception commentée) :\n" + fautes.joinToString("\n"))
    }
}
