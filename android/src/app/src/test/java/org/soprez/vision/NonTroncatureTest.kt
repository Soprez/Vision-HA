// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Règle du propriétaire : aucun texte porteur de sens coupé sans moyen de lire la suite.
 * Les écrans sont construits en code ; ce test lit les sources Kotlin et refuse :
 *  - une ligne unique forcée ou des points de suspension (isSingleLine, maxLines = 1, ellipsize…),
 *    hors des exceptions justifiées ci-dessous ;
 *  - une hauteur figée (px/dp) sur un texte ou un bouton ;
 *  - un fabricant de texte ou de bouton qui ne pose pas « pas de césure » ;
 *  - une boîte de dialogue au style Android par défaut (AlertDialog.Builder hors de Ui.dialogue).
 */
class NonTroncatureTest {
    private val racine: File = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision"))
        .firstOrNull { it.isDirectory } ?: error("Sources introuvables depuis ${File(".").absolutePath}")

    private val sources: Map<String, String> by lazy {
        racine.listFiles { f -> f.name.endsWith(".kt") }!!.associate { it.name to it.readText() }
    }

    /**
     * Exceptions gardées : les étiquettes d'icônes d'applis (2 lignes au plus, comme le lanceur
     * Android). Chacune doit rester lisible en entier : contentDescription = nom complet, posé
     * juste après, et le nom complet affiché par l'appui long (menu ou titre de boîte).
     */
    private val exceptionsEtiquettes = listOf(
        "LanceurActivity.kt" to "val libelle = texte(nom, tv(22f)",                 // case de l'accueil TV
        "LanceurActivity.kt" to "contenu.addView(texte(nom, 11.5f",                 // case de l'accueil téléphone
        "LanceurActivity.kt" to "cell.addView(texte(Accueil.etiquette(this, pkg)",  // appli dans un dossier ouvert
        "LanceurActivity.kt" to "cell.addView(texte(nom, 11.5f",                    // tiroir de toutes les applis
    )

    /**
     * Une ligne avec ellipse, gardée : le message d'accueil libre de la télé (écrit par l'administrateur, de longueur
     * quelconque). Le texte entier est en contentDescription, posé avec le texte.
     */
    private val exceptionsUneLigne = listOf(
        "LanceurActivity.kt" to "if (tele) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }",
        // Écrans Musique (décision du projet, 1.86.4 à 1.86.13) : titres, artistes et enceintes sur une ligne avec « … »,
        // pour garder la file et la recherche lisibles à la télécommande ; le texte entier est dit en description.
        "ChoixMusiqueActivity.kt" to "maxLines",
        "PartyActivity.kt" to "maxLines",
        "MiniLecteur.kt" to "maxLines",
        // Recette visuelle 1.87.0 : nom d'entité venu de Home Assistant (une ligne, « … », nom complet en description),
        // et l'heure du widget horloge, qui rapetisse pour tenir sur une ligne (jamais de « … »).
        "VueWidgetHa.kt" to "texte(t, taille, couleur, police).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = t }",
        "Ui.kt" to "tv.maxLines = 1; tv.setHorizontallyScrolling(false); tv.ellipsize = null",
        // 1.88.0 (l'auteur : « liste tronquée avec +N, détail complet à l'appui ») : une ligne du détail d'un widget, et le choix
        // d'une liste ; le texte entier en description, le détail complet en touchant le widget.
        "VueWidgetHa.kt" to "maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = WidgetsHa.texteLigne(l)",
        "VueWidgetHa.kt" to "maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = if (pilote) context.getString(R.string.wha_choisir",
    )

    /** Hauteurs figées gardées : décor sans texte porteur de sens (lettres d'aperçu dans des ronds). */
    private val exceptionsHauteurs = listOf(
        "CouleursActivity.kt" to "rang.addView(texte(l, 18f",  // aperçu du lanceur : « Y », « S », « P » dans des pastilles
    )

    private val troncature = Regex(
        """isSingleLine\s*=\s*true|setSingleLine\(\s*(true)?\s*\)|singleLine\s*=\s*"true"|maxLines\s*=\s*1\b|setMaxLines\(\s*1\s*\)|""" +
        """ellipsize\s*=\s*(android\.text\.)?(TextUtils\.)?TruncateAt|setEllipsize\(\s*(android\.text\.)?(TextUtils\.)?TruncateAt|setLines\(|\blines\s*=\s*\d"""
    )

    @Test fun aucun_texte_tronque_hors_exceptions() {
        val fautes = ArrayList<String>()
        for ((nom, texte) in sources) {
            val lignes = texte.lines()
            lignes.forEachIndexed { i, l ->
                val code = l.substringBefore("//")
                if (!troncature.containsMatchIn(code) && !Regex("""maxLines\s*=\s*\d""").containsMatchIn(code)) return@forEachIndexed
                if (exceptionsUneLigne.any { (f, motif) -> f == nom && l.contains(motif) }) return@forEachIndexed
                val exception = exceptionsEtiquettes.any { (f, motif) -> f == nom && l.contains(motif) }
                if (!exception) { fautes += "$nom:${i + 1}: ${l.trim().take(140)}"; return@forEachIndexed }
                // Exception d'étiquette : au plus 2 lignes, et le nom complet en description d'accessibilité.
                if (!Regex("""maxLines\s*=\s*2\b""").containsMatchIn(code)) fautes += "$nom:${i + 1}: étiquette d'icône sans maxLines = 2"
                val suite = lignes.subList(i, minOf(lignes.size, i + 3)).joinToString("\n")
                if (!suite.contains("contentDescription")) fautes += "$nom:${i + 1}: étiquette d'icône sans contentDescription (nom complet)"
            }
        }
        if (fautes.isNotEmpty()) fail("Textes coupés (voir NonTroncatureTest) :\n" + fautes.joinToString("\n"))
    }

    @Test fun les_exceptions_existent_encore() {
        // Une exception qui ne correspond plus à rien doit être retirée de la liste.
        for ((f, motif) in exceptionsEtiquettes + exceptionsHauteurs + exceptionsUneLigne)
            assertTrue("Exception périmée : $f « $motif »", sources[f]?.contains(motif) == true)
    }

    @Test fun pas_de_hauteur_figee_sur_un_texte() {
        // LayoutParams(largeur, px(…)/dp(…)) passé avec un texte, un bouton ou une « ligne » : la hauteur doit suivre le texte.
        val figee = Regex("""(texte|bouton|ligne|pas|Ui\.texte|Ui\.bouton\w*)\(.*LayoutParams\((?:[^,()]|\([^()]*\))+,\s*(px|dp|Ui\.dp|t|tv)\(""")
        val fautes = ArrayList<String>()
        for ((nom, texte) in sources) texte.lines().forEachIndexed { i, l ->
            if (figee.containsMatchIn(l) && exceptionsHauteurs.none { (f, m) -> f == nom && l.contains(m) })
                fautes += "$nom:${i + 1}: ${l.trim().take(140)}"
        }
        if (fautes.isNotEmpty()) fail("Hauteurs figées autour d'un texte :\n" + fautes.joinToString("\n"))
    }

    /** Le corps d'une fonction : de sa déclaration à la déclaration suivante du même niveau. */
    private fun corps(fichier: String, signature: String): String {
        val s = sources[fichier] ?: error("$fichier absent")
        val debut = s.indexOf(signature).also { assertTrue("$signature absente de $fichier", it >= 0) }
        val suite = Regex("""\n {4}(private |internal )?(fun |val |var |/\*\*|class |override )""").find(s, debut + signature.length)
        return s.substring(debut, suite?.range?.first ?: s.length)
    }

    @Test fun la_regle_sans_cesure_est_centrale() {
        val regle = corps("Ui.kt", "fun <T : TextView> sansCesure(")
        assertTrue("Ui.sansCesure doit couper la césure", regle.contains("HYPHENATION_FREQUENCY_NONE"))
        assertTrue("Ui.sansCesure doit poser une coupure de ligne sans césure", regle.contains("BREAK_STRATEGY_SIMPLE") || regle.contains("BREAK_STRATEGY_HIGH_QUALITY"))
        val fabricants = listOf(
            "Ui.kt" to "fun texte(ctx: Context",
            "Ui.kt" to "fun chip(",
            "Ui.kt" to "fun boutonPrimaire(",
            "Ui.kt" to "fun boutonSecondaire(",
            "LanceurActivity.kt" to "private fun texte(",
            "ReglagesTvActivity.kt" to "private fun texte(",
            "TableauEditeurActivity.kt" to "private fun texte(",
            "CouleursActivity.kt" to "private fun texte(",
            "VueWidgetHa.kt" to "private fun texte(",
            "EditeurWidgetHaActivity.kt" to "private fun texte(",
        )
        for ((f, sig) in fabricants)
            assertTrue("$f · $sig doit appeler sansCesure", corps(f, sig).contains("sansCesure("))
        // Les boutons du lanceur passent par son texte() : ils héritent de la règle.
        assertTrue(corps("LanceurActivity.kt", "private fun bouton(").contains("texte("))
        // Les boutons centraux : plus de ligne unique forcée.
        for (sig in listOf("fun boutonPrimaire(", "fun boutonSecondaire("))
            assertTrue("$sig ne doit plus forcer une ligne", !troncature.containsMatchIn(corps("Ui.kt", sig)))
    }

    @Test fun toutes_les_boites_suivent_le_theme() {
        val fautes = ArrayList<String>()
        for ((nom, texte) in sources) {
            if (nom == "Ui.kt") continue
            texte.lines().forEachIndexed { i, l -> if (l.contains("AlertDialog.Builder(")) fautes += "$nom:${i + 1}" }
        }
        if (fautes.isNotEmpty()) fail("Boîtes au style Android par défaut (employer Ui.dialogue) :\n" + fautes.joinToString("\n"))
        val habiller = corps("Ui.kt", "fun habiller(")
        assertTrue("Les boîtes doivent prendre le fond du thème", habiller.contains("setBackgroundDrawable"))
        val vue = corps("Ui.kt", "private fun habillerVue(")
        assertTrue("Les textes des boîtes ne doivent jamais être coupés", vue.contains("ellipsize = null") && vue.contains("Int.MAX_VALUE"))
        assertTrue(vue.contains("sansCesure("))
    }
}
