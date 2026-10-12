// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * i18n, lot C1 (première moitié de l'appli Android) :
 *  - chaque clé de `strings_lot1.xml` existe dans les 6 langues, avec les mêmes champs (%1$s, %2$d…) ;
 *  - chaque pluriel a au moins « one » et « other » partout, et les mêmes champs dans chaque forme ;
 *  - aucun mot interdit (vocabulaire neutre) dans les textes du lot, sauf la table des catégories (données) ;
 *  - plus de texte français en dur dans les fichiers Kotlin du lot (détecteur heuristique).
 */
class I18nLot1Test {
    private val langues = listOf("fr", "en", "de", "es", "it", "nl")
    private fun dossier(l: String) = TrJvm.dossierDe(l)

    private fun fichier(l: String): TrJvm.Textes {
        val f = File(TrJvm.res, "${dossier(l)}/strings_lot1.xml")
        assertTrue("absent : $f", f.isFile)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val chaines = LinkedHashMap<String, String>()
        val s = doc.getElementsByTagName("string")
        for (i in 0 until s.length) { val e = s.item(i) as org.w3c.dom.Element; chaines[e.getAttribute("name")] = TrJvm.brut(e.textContent) }
        val pluriels = LinkedHashMap<String, Map<String, String>>()
        val p = doc.getElementsByTagName("plurals")
        for (i in 0 until p.length) {
            val e = p.item(i) as org.w3c.dom.Element
            val items = e.getElementsByTagName("item")
            pluriels[e.getAttribute("name")] = (0 until items.length).associate {
                val x = items.item(it) as org.w3c.dom.Element; x.getAttribute("quantity") to TrJvm.brut(x.textContent)
            }
        }
        return TrJvm.Textes(chaines, pluriels)
    }

    private val champ = Regex("""%(\d+\$)?[sdf]""")
    private fun champs(t: String) = champ.findAll(t).map { it.value }.sorted().toList()

    @After fun remettre() { TrJvm.langue = "fr" }

    @Test fun chaque_cle_dans_les_six_langues_avec_les_memes_champs() {
        val ref = fichier("en")
        val fautes = ArrayList<String>()
        for (l in langues) {
            val t = fichier(l)
            (ref.chaines.keys - t.chaines.keys).forEach { fautes += "$l : chaîne absente $it" }
            (t.chaines.keys - ref.chaines.keys).forEach { fautes += "$l : chaîne en trop $it" }
            (ref.pluriels.keys - t.pluriels.keys).forEach { fautes += "$l : pluriel absent $it" }
            for ((k, v) in ref.chaines) t.chaines[k]?.let { if (champs(it) != champs(v)) fautes += "$l : champs différents pour $k : ${champs(it)} ≠ ${champs(v)}" }
            for ((k, formes) in t.pluriels) {
                if ("one" !in formes || "other" !in formes) fautes += "$l : $k sans one/other"
                val attendus = champs(ref.pluriels[k]?.get("other") ?: "")
                // La forme « one » peut omettre le nombre (« Une autorisation manque ») ; les autres le gardent.
                formes.forEach { (q, v) -> if (champs(v) != attendus && !(q == "one" && champs(v).isEmpty())) fautes += "$l : $k[$q] champs ${champs(v)} ≠ $attendus" }
            }
            t.chaines.forEach { (k, v) -> if (v.isBlank()) fautes += "$l : $k vide" }
        }
        if (fautes.isNotEmpty()) fail(fautes.joinToString("\n"))
    }

    @Test fun pas_de_collision_avec_les_autres_fichiers_de_ressources() {
        val miennes = fichier("en").let { it.chaines.keys + it.pluriels.keys }
        val autres = File(TrJvm.res, "values").listFiles { f -> f.name.endsWith(".xml") && f.name != "strings_lot1.xml" }!!
            .flatMap { Regex("""name="([^"]+)"""").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }.toSet()
        assertEquals(emptySet<String>(), miennes intersect autres)
    }

    /** Vocabulaire neutre (glossaire, « Mots interdits ») : la table des catégories est une donnée de Home Assistant. */
    @Test fun vocabulaire_neutre_dans_toutes_les_langues() {
        val interdits = Regex("""\b(famille|maison|parents?|enfants?|family|house|kids?|child(ren)?|familie|zuhause|haus|eltern|kind(er)?|""" +
            """familia|casa|hogar|padres|hijos?|niños?|famiglia|genitori|figli[oa]?|bambin[oi]|gezin|thuis|huis|ouders|kinderen)\b""", RegexOption.IGNORE_CASE)
        val fautes = ArrayList<String>()
        for (l in langues) {
            val t = fichier(l)
            val textes = t.chaines.filterKeys { !it.startsWith("categorie_") } + t.pluriels.flatMap { (k, f) -> f.map { (q, v) -> "$k[$q]" to v } }
            textes.forEach { (k, v) ->
                // « deep house » : un genre musical (nom d'une radio), pas une maison.
                val sans = v.replace("Home Assistant", "").replace(Regex("(?i)home screen|deep house"), "")
                interdits.find(sans)?.let { fautes += "$l : $k : « ${it.value} »" }
                if (Regex("""(?i)\bhome\b""").containsMatchIn(sans) && !sans.contains("Home ×2") && !sans.contains("Home-") &&
                    !Regex("""(?i)home (button|key|-taste)|tasto home|schermata home|home di vision|home ×""").containsMatchIn(sans)) fautes += "$l : $k : « home »"
            }
        }
        if (fautes.isNotEmpty()) fail(fautes.joinToString("\n"))
    }

    // ------------------------------------------------------------------ textes en dur

    private val sources: File = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision"))
        .firstOrNull { it.isDirectory } ?: error("Sources introuvables")

    /** Les fichiers Kotlin du lot C1 (liste de la mission, plus les nouveaux fichiers partagés). */
    private val lot = listOf(
        "SetupActivity.kt", "ParentsActivity.kt", "WidgetsHa.kt", "Deverrouillage.kt", "DeverrouillageParentActivity.kt",
        "MessageEnvoiActivity.kt", "GestionAppareil.kt", "TextesBlocage.kt", "DeverrouillageActivity.kt", "AgentService.kt",
        "Party.kt", "EconomiseurTv.kt", "ActionsAppli.kt", "MessagesV2.kt", "Local.kt", "CategoriesActivity.kt", "Garde.kt",
        "Accueil.kt", "WidgetHaConfigActivity.kt", "StatutNotif.kt", "FermesActivity.kt", "ReponseReceiver.kt", "Demandes.kt",
        "Geste.kt", "WidgetsSysteme.kt", "Temps.kt", "Preavis.kt", "Decouverte.kt", "Themes.kt", "Pauses.kt", "Meteo.kt",
        "Config.kt", "Bulle.kt", "VeilleWeb.kt", "RetourActivity.kt", "ReglesVeille.kt", "Migration.kt", "GardeActivity.kt",
        "CadreRedim.kt", "Appairage.kt", "WidgetsAndroid.kt", "VisionApp.kt", "Vignettes.kt", "VeilleService.kt",
        "VeilleActivity.kt", "Veille.kt", "Usage.kt", "Ui.kt", "Reglages.kt", "Redim.kt", "Polices.kt", "Pochettes.kt",
        "Paquets.kt", "Pageur.kt", "NettoyageAccueil.kt", "Musique.kt", "MessageReponseReceiver.kt", "Logos.kt",
        "IconeMeteo.kt", "GrilleAccueil.kt", "Glisser.kt", "GestesVeille.kt", "FondAnime.kt", "ApkProvider.kt",
        "Tr.kt", "Categories.kt", "Vocabulaire.kt",
    )

    /**
     * Exceptions justifiées (ni texte d'interface, ni phrase à traduire) :
     *  - noms propres du glossaire : Vision (et VISION en capitales décoratives), Android, PC ;
     *  - noms de thèmes : identifiants enregistrés (Theme("…"), défaut « Beige »), traduits à l'affichage par Themes.libelle ;
     *  - « Maison » : Config.LIEU_DEFAUT, valeur de données (le nom du lieu vient du serveur) ;
     *  - identifiants techniques : noms de classes d'écrans, en-tête VisionAndroid, extra DeviceAdminAdd, fuseau UTC,
     *    algorithme EC, réglage WebView SetJavaScriptEnabled, squelettes de date (EEEEdMMMM…).
     * Toute autre exception se marque sur sa ligne par un commentaire « i18n-exception : raison ».
     */
    private val permis = setOf("Vision", "VISION", "Android", "PC", "Beige", "Maison", "VisionAndroid", "DeviceAdminAdd", "UTC", "EC",
        "SetJavaScriptEnabled", "LanceurActivity", "VeilleActivity", "RetourActivity", "SetupActivity")
    private val squelette = Regex("""[EMdyHhmsaLcjk]+""")
    private val chaine = Regex(""""((?:[^"\\]|\\.)*)"""")
    private val accent = Regex("""[À-ÿ«»…’]""")
    private val deuxMots = Regex("""[A-Za-zÀ-ÿ]{2,}[ '’][A-Za-zÀ-ÿ]{2,}""")
    private val motMajuscule = Regex("""\s*[A-ZÀ-Ý][A-Za-zÀ-ÿ]+[ .:…!?]*""")

    private fun suspecte(s: String): Boolean {
        val t = s.replace(Regex("""\$\{[^}]*\}|\$[a-zA-Z_]+"""), " ")
        if (t.trim() in permis || squelette.matches(t)) return false
        if (!Regex("[A-Za-zÀ-ÿ]{2,}").containsMatchIn(t)) return false
        return accent.containsMatchIn(t) || deuxMots.containsMatchIn(t) || motMajuscule.matches(t)
    }

    /** Retire les commentaires (bloc et fin de ligne) sans toucher aux chaînes ; garde les numéros de ligne. */
    private fun sansCommentaires(texte: String): List<String> {
        val out = StringBuilder()
        var i = 0; var dans = false
        while (i < texte.length) {
            val c = texte[i]
            if (!dans && texte.startsWith("/*", i)) { val j = texte.indexOf("*/", i + 2).let { if (it < 0) texte.length else it + 2 }; repeat(texte.substring(i, j).count { it == '\n' }) { out.append('\n') }; i = j; continue }
            if (!dans && texte.startsWith("//", i)) { val j = texte.indexOf('\n', i).let { if (it < 0) texte.length else it }; i = j; continue }
            if (c == '"') dans = !dans
            else if (c == '\\' && dans && i + 1 < texte.length) { out.append(c).append(texte[i + 1]); i += 2; continue }
            else if (c == '\n') dans = false
            out.append(c); i++
        }
        return out.toString().split('\n')
    }

    @Test fun pas_de_francais_en_dur_dans_le_lot() {
        val fautes = ArrayList<String>()
        for (nom in lot) {
            val f = File(sources, nom)
            if (!f.isFile) continue
            val brut = f.readLines()
            val code = sansCommentaires(f.readText())
            code.forEachIndexed { i, l ->
                val origine = brut.getOrNull(i).orEmpty()
                if (origine.contains("i18n-exception")) return@forEachIndexed
                if (Regex("""\bLog\.[dviwe]\(|@Suppress|@SuppressLint|const val TAG""").containsMatchIn(l)) return@forEachIndexed
                // Identifiants de thème (le premier argument de Theme(…)) : données, traduites par Themes.libelle.
                val ligne = l.replace(Regex("""Theme\("[^"]*""""), "Theme(X")
                    .replace(Regex(""""[^"]*" to R\.string\.theme_"""), "X to R.string.theme_")  // la table Themes.LIBELLES : identifiants
                chaine.findAll(ligne).map { it.groupValues[1] }.filter { suspecte(it) }.forEach { fautes += "$nom:${i + 1}: \"$it\"" }
            }
        }
        if (fautes.isNotEmpty()) fail("Texte en dur dans le lot C1 :\n" + fautes.joinToString("\n"))
    }

    // ------------------------------------------------------------------ rendu dans les langues

    @Test fun les_textes_purs_suivent_la_langue() {
        TrJvm.langue = "de"
        assertEquals("Dieses Gerät ist gesperrt", TextesBlocage.de(true, "", false, "", "", "", "").titre)
        assertEquals("in 5 Minuten", Preavis.quand(5))
        assertEquals("1 Std. 30 Min.", Tr.duree(90))
        assertEquals("Nur für Entscheider.", Party.messageErreur(403, null))
        assertEquals("Spiele", Categories.libelle("Jeux"))
        assertEquals("Soziale Medien", Categories.libelle("Réseaux sociaux"))
        assertEquals("Mon étiquette", Categories.libelle("Mon étiquette"))
        assertEquals("Nachtblau", Themes.libelle("Bleu nuit"))
        assertEquals("21,5°C", WidgetsHa.valeurAffichee("21.5", "°C", "sensor", "temperature"))
        TrJvm.langue = "en"
        assertEquals("21.5°C", WidgetsHa.valeurAffichee("21.5", "°C", "sensor", "temperature"))
        assertEquals("Only for deciders.", Party.messageErreur(403, null))
        assertEquals("3 songs up next", Party.aSuivreTexte(3))
        TrJvm.langue = "fr"
        assertEquals("1 h 30", Tr.duree(90))
        assertEquals("Réservé aux décideurs.", Party.messageErreur(403, null))
    }

    @Test fun le_vocabulaire_se_surcharge_en_un_seul_point() {
        TrJvm.langue = "fr"
        try {
            Vocabulaire.surcharge = mapOf("decideurs" to "responsables")
            assertEquals("Réservé aux responsables.", Party.messageErreur(403, null))
        } finally { Vocabulaire.surcharge = emptyMap() }
        assertFalse(Party.messageErreur(403, null).contains("responsables"))
    }
}
