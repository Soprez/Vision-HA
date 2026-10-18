// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.91.2 : les styles d'horloge retrouvés — les huit de Vision (HorlogesVeille, lot veille du 11-10) et les horloges des
 * thèmes (StylesHorloge.WEB, copiées de la page de veille dans assets/horloges) : liste, anciens noms, noms traduits,
 * heure en toutes lettres, et la copie des horloges web à jour de la liste.
 */
class HorlogesStylesTest {
    private fun fichier(chemin: String): File = listOf(File("src/main/$chemin"), File("app/src/main/$chemin")).first { it.isFile }

    @Test fun la_liste_des_styles() {
        assertEquals(listOf("anneau", "fine", "grasse", "analogique", "classique", "mots", "flip", "empilee"), HorlogesVeille.STYLES)
        assertEquals(28, StylesHorloge.WEB.size)
        assertEquals(36, StylesHorloge.TOUS.size)
        assertEquals("rien en double", StylesHorloge.TOUS.size, StylesHorloge.TOUS.toSet().size)
        // Une horloge de thème ne se confond jamais avec un style de Vision.
        StylesHorloge.WEB.forEach { assertTrue(StylesHorloge.estWeb(StylesHorloge.PREFIXE_WEB + it)); assertEquals(it, StylesHorloge.cleWeb(StylesHorloge.PREFIXE_WEB + it)) }
        HorlogesVeille.STYLES.forEach { assertFalse(StylesHorloge.estWeb(it)) }
    }

    @Test fun les_horloges_web_copiees_sont_celles_de_la_liste() {
        // Les clés de HORLOGES dans la copie de la page de veille : exactement la liste, dans le même ordre.
        val js = fichier("assets/horloges/horloges.js").readText()
        val debut = js.indexOf("const HORLOGES = {"); val fin = js.indexOf("const CSS_HORLOGES")
        assertTrue(debut in 0 until fin)
        val cles = Regex("""(?m)^  "?([a-z0-9-]+)"?: \{""").findAll(js.substring(debut, fin)).map { it.groupValues[1] }.toList()
        assertEquals(StylesHorloge.WEB, cles)
        assertTrue("la page de l'appli lit cle, couleurs et date", fichier("assets/horloges/horloge.html").readText().let { "HORLOGES[cle]" in it && "--v-" in it && "sans-date" in it })
    }

    @Test fun les_anciens_noms_gardent_le_choix() {
        assertEquals("fine", StylesHorloge.normaliser("chiffres"))
        assertEquals("analogique", StylesHorloge.normaliser("aiguilles"))
        StylesHorloge.TOUS.forEach { assertEquals(it, StylesHorloge.normaliser(it)) }
        assertEquals("anneau", StylesHorloge.normaliser(null))
        assertEquals("anneau", StylesHorloge.normaliser(""))
        assertEquals("anneau", StylesHorloge.normaliser("t-inconnue"))
        assertEquals("anneau", StylesHorloge.normaliser("theme"))
    }

    /** Les chaînes d'un fichier de ressources : nom → texte. */
    private fun chaines(dossier: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(fichier("res/$dossier/strings_horloge_styles.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun chaque_style_a_un_nom_dans_chaque_langue() {
        val langues = listOf("values", "values-fr", "values-de", "values-es", "values-it", "values-nl").associateWith { chaines(it) }
        val noms = HorlogesVeille.STYLES.map { "horloge_$it" }
        val autres = listOf("horloge_style", "horloge_style_veille", "horloge_comme_accueil", "horloge_comme_accueil_titre", "horloge_styles_vision",
            "horloge_horloges_themes", "horloge_style_aide", "horloge_vision_defaut", "horloge_du_theme")
        for ((l, c) in langues) {
            for (cle in noms + autres) assertTrue("$cle absent ($l)", c[cle].orEmpty().isNotBlank())
            assertEquals("noms tous différents ($l)", noms.size, noms.map { c[it] }.toSet().size)
            assertTrue("%1\$s dans horloge_du_theme ($l)", "%1\$s" in c["horloge_du_theme"].orEmpty())
            // Vocabulaire neutre.
            c.values.forEach { v -> listOf("maison", "parent", "enfant", "famille").forEach { assertFalse("« $it » dans « $v »", v.lowercase().contains(it)) } }
        }
        listOf("fine", "grasse", "analogique", "classique", "mots", "flip").forEach { assertNotEquals(it, langues["values-fr"]!!["horloge_$it"], langues["values"]!!["horloge_$it"]) }
        assertEquals(HorlogesVeille.STYLES.size, HorlogesVeille.STYLES.map { HorlogesVeille.nomRes(it) }.toSet().size)
    }

    @Test fun douze_ou_vingt_quatre_heures() {
        // 24 h : deux chiffres ; 12 h : sans zéro, midi et minuit valent 12.
        assertEquals("09", HorlogesVeille.chiffresHeure(9, true)); assertEquals("14", HorlogesVeille.chiffresHeure(14, true))
        assertEquals("00", HorlogesVeille.chiffresHeure(0, true))
        assertEquals("9", HorlogesVeille.chiffresHeure(9, false)); assertEquals("2", HorlogesVeille.chiffresHeure(14, false))
        assertEquals("12", HorlogesVeille.chiffresHeure(0, false)); assertEquals("12", HorlogesVeille.chiffresHeure(12, false))
        assertEquals("11", HorlogesVeille.chiffresHeure(23, false))
        // Le repère n'existe que sur 12 h, dans la langue de l'appareil ; matin jusqu'à 11 h 59, après-midi dès midi.
        assertEquals("", HorlogesVeille.repere12h(14, true, java.util.Locale.US))
        assertEquals("AM", HorlogesVeille.repere12h(0, false, java.util.Locale.US))
        assertEquals("AM", HorlogesVeille.repere12h(11, false, java.util.Locale.US))
        assertEquals("PM", HorlogesVeille.repere12h(12, false, java.util.Locale.US))
        assertEquals("PM", HorlogesVeille.repere12h(23, false, java.util.Locale.US))
        val de = java.text.DateFormatSymbols.getInstance(java.util.Locale.GERMAN).amPmStrings
        assertEquals(de[1], HorlogesVeille.repere12h(15, false, java.util.Locale.GERMAN))
        // Une heure hors bornes reste lisible.
        assertEquals("1", HorlogesVeille.chiffresHeure(25, false)); assertEquals("23", HorlogesVeille.chiffresHeure(-1, true))
    }

    @Test fun l_heure_en_francais() {
        assertArrayEquals(arrayOf("", "dix heures", "vingt-cinq", "du soir"), HorlogesVeille.enMots(22, 25, "fr"))
        assertArrayEquals(arrayOf("", "une heure", "et quart", "du matin"), HorlogesVeille.enMots(1, 15, "fr"))
        assertArrayEquals(arrayOf("", "trois heures", "et demie", "de l'après-midi"), HorlogesVeille.enMots(15, 30, "fr"))
        assertArrayEquals(arrayOf("", "midi", "moins le quart", ""), HorlogesVeille.enMots(11, 45, "fr"))
        assertArrayEquals(arrayOf("", "minuit", "moins dix", ""), HorlogesVeille.enMots(23, 50, "fr"))
        assertArrayEquals(arrayOf("", "midi", "vingt et une", ""), HorlogesVeille.enMots(12, 21, "fr-FR"))
    }

    @Test fun l_heure_en_anglais_et_les_autres_langues() {
        assertArrayEquals(arrayOf("twenty-five past", "ten", "", "at night"), HorlogesVeille.enMots(22, 25, "en"))
        assertArrayEquals(arrayOf("quarter to", "twelve", "", "in the afternoon"), HorlogesVeille.enMots(11, 45, "en"))
        assertArrayEquals(HorlogesVeille.enMots(18, 40, "en"), HorlogesVeille.enMots(18, 40, "de"))
        for (h in 0..23) for (m in 0..59) for (l in listOf("fr", "en")) assertTrue("$h:$m $l", HorlogesVeille.enMots(h, m, l)[1].isNotBlank())
    }
}
