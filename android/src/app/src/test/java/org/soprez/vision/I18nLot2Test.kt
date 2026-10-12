// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * i18n, lot C2 (seconde moitié de l'appli Android, socle `strings.xml`, manifeste) :
 *  - chaque clé de `strings.xml` et de `strings_lot2.xml` existe dans les 6 dossiers (values = anglais, values-fr…) ;
 *  - mêmes champs positionnels (%1$s, %2$d…) d'une langue à l'autre ; pluriels : mêmes quantités partout, et la forme
 *    « one » n'emploie que des champs de « other » (elle peut taire le nombre : « CONTRÔLE ») ; tableaux : même nombre ;
 *  - aucun mot interdit du vocabulaire neutre dans ces textes ;
 *  - plus de texte français en dur dans les fichiers Kotlin du lot (détecteur heuristique, exceptions commentées).
 */
class I18nLot2Test {
    private val langues = listOf("en", "fr", "de", "es", "it", "nl")
    private val fichiers = listOf("strings.xml", "strings_lot2.xml")

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
        if (fautes.isNotEmpty()) fail("Ressources du lot C2 :\n" + fautes.joinToString("\n"))
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

    /** Les références françaises gardées pour les essais JVM restent celles de values-fr. */
    @Test fun references_francaises_alignees_sur_values_fr() {
        val fr = lire("fr", "strings_lot2.xml").chaines
        val paris = TimeZone.getTimeZone("Europe/Paris")
        val fin = java.time.Instant.parse("2026-10-09T17:45:00Z").toEpochMilli()
        assertEquals(String.format(Locale.FRANCE, fr.getValue("pausegenerale_banniere"), PauseGenerale.heure(fin, paris)), PauseGenerale.banniere(fin, paris))
        assertEquals("Un marqueur interne, jamais affiché, doit rester tel quel", "Réglage protégé par Vision", BlockActivity.RAISON_REGLAGE)
    }

    // ------------------------------------------------------------------ texte français en dur dans le Kotlin du lot

    private val lot = listOf(
        "LanceurActivity.kt", "ReglagesTvActivity.kt", "VeilleView.kt", "PartyActivity.kt", "ExceptionsActivity.kt", "MiseAJour.kt",
        "ScanActivity.kt", "AppairageActivity.kt", "EditeurWidgetHaActivity.kt", "VueWidgetHa.kt", "BlockActivity.kt", "Json.kt",
        "TableauEditeurActivity.kt", "ChoixMusiqueActivity.kt", "Acces.kt", "DetailFermesActivity.kt", "MessagesRiches.kt",
        "VisionAccessibility.kt", "CouleursActivity.kt", "MessageActivity.kt", "Messages.kt", "AccueilDefaut.kt", "InstallReceiver.kt",
        "WidgetsHaServeur.kt", "PauseGenerale.kt", "Etat.kt", "Profil.kt", "Flux.kt", "Biometrie.kt", "SelecteurWidgets.kt", "Modules.kt",
        "Net.kt", "EcranVerrou.kt", "CamerasCouche.kt", "BootReceiver.kt", "Reseau.kt", "Palette.kt", "Identite.kt", "DnsVpnService.kt",
        "Assistant.kt", "AdminReceiver.kt",
    )

    /** Noms propres et valeurs techniques, jamais traduits (glossaire). */
    private val nomsPropres = setOf("Vision", "VISION", "Home Assistant", "Pronote", "Valorant", "Rocket League", "Accept", "Silent")

    /** Exceptions justifiées : (fichier, littéral). */
    private val exceptions = setOf(
        // Fichiers entiers de messages techniques, jamais affichés (Json.objet() les avale ; Erreur sert au débogage).
        "Json.kt" to "*",
        // Marqueur interne passé dans l'Intent (BlockActivity.RAISON_REGLAGE) : TextesBlocage écrit le titre affiché.
        "BlockActivity.kt" to "Réglage protégé par Vision",
        // Clé interne de regroupement (source vide) ; l'affichage passe par choixmusique_dans_bibliotheque.
        "ChoixMusiqueActivity.kt" to "Bibliothèque",
        // Référence française d'avant l'i18n, gardée pour PauseGeneraleTest (« 19h45 ») ; alignée sur values-fr par ce test.
        "PauseGenerale.kt" to "Tout est fermé jusqu'à {…}",
        // Identifiant de geste du serveur (Modules.gestesGestion), pas un texte affiché.
        "Modules.kt" to "à qui",
        // Modèle envoyé à Home Assistant comme donnée (« host ») quand le fabricant est inconnu.
        "Identite.kt" to "Appareil Android",
        // Nom de sport reçu de Home Assistant (donnée), comparé pour choisir une couleur.
        "VeilleView.kt" to "Formule 1",
        // Thème par défaut (donnée, nom de thème de Themes.kt), pas un texte.
        "VeilleView.kt" to "Beige",
        // Assemblages de textes déjà traduits (découpe en lignes, valeur et unité de Home Assistant), sans mot propre.
        "VeilleView.kt" to "\$courante \$mot",
        "VeilleView.kt" to "\$n \$unite",
        // Suite d'une ligne de journal (Log.i sur deux lignes).
        "InstallReceiver.kt" to "legacy={…} extras={…}",
        // En-têtes et repli techniques des requêtes HTTP.
        "Net.kt" to "application/json; charset=utf-8", "Net.kt" to "HTTP \$code",
        "WidgetsHaServeur.kt" to "application/json; charset=utf-8", "WidgetsHaServeur.kt" to "HTTP \$code",
        // Message d'annotation @Deprecated, pas un texte affiché.
        "LanceurActivity.kt" to "startActivityForResult : le choix et le réglage d'un widget Android passent par là",
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
                if (no in sautees || Regex("""\bLog\.[diwev]\(""").containsMatchIn(l) || l.trimStart().startsWith("@")) continue
                if (!suspect(t) || t.trim() in nomsPropres || (nom to t) in exceptions) continue
                fautes += "$nom:$no : « $t »"
            }
        }
        if (fautes.isNotEmpty()) fail("Texte français en dur (à passer en ressource, ou exception commentée) :\n" + fautes.joinToString("\n"))
    }

    /**
     * 1.87.0 (PartyEcransTest, « Clé TV Mi· hors ligne ») : Android retire l'espace au début et à la fin d'un texte de
     * ressource ; une espace voulue à ces endroits doit être entre guillemets (« " · hors ligne" »).
     */
    @Test fun aucune_espace_perdue_en_bord_de_texte() {
        val motif = Regex("<string name=\"([^\"]+)\"[^>]*>(\\s[^<]*|[^<]*\\s)</string>")
        val fautes = ArrayList<String>()
        TrJvm.res.listFiles { d -> d.isDirectory && d.name.startsWith("values") }!!.forEach { d ->
            d.listFiles { f -> f.name.endsWith(".xml") }!!.forEach { f ->
                motif.findAll(f.readText()).forEach { m ->
                    if (!m.groupValues[2].trim().startsWith("\"")) fautes += "${d.name}/${f.name} : ${m.groupValues[1]}"
                }
            }
        }
        assertTrue("Espace en bord de texte, perdue par Android (mettre le texte entre guillemets) :\n" + fautes.joinToString("\n"), fautes.isEmpty())
    }
}
