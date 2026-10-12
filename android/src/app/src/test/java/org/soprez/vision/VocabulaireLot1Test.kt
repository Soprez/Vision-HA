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
 * Refonte UX, lot 1 (A6 / APP-11 / EX-12) : vocabulaire neutre jusqu'au bout.
 *  - plus aucun « gestionnaire » (ni manager, Verwalter, gestor, gestore) dans les textes (« beheerder » reste : en
 *    néerlandais, c'est aussi l'administrateur de Home Assistant) : le mot vient du
 *    Vocabulaire ({decideur}) ;
 *  - le Vocabulaire réglé dans Home Assistant (champ « vocabulaire » du relevé) remplace les mots par défaut ;
 *  - ni « famille » ni « maison » : salutation, titre de l'espace de gestion, nom du lieu par défaut.
 */
class VocabulaireLot1Test {
    private val langues = listOf("en", "fr", "de", "es", "it", "nl")
    private val src = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @After fun remettre() { Vocabulaire.surcharge = emptyMap(); TrJvm.langue = "fr" }

    @Test fun plus_de_gestionnaire_dans_les_textes() {
        val interdit = Regex("""(?i)\b(gestionnaires?|managers?|verwalter\w*|gestor(es)?|gestore|gestori)\b""")
        val fautes = ArrayList<String>()
        for (l in langues) {
            val t = TrJvm.dossier(TrJvm.dossierDe(l))
            t.chaines.forEach { (k, v) -> interdit.find(v)?.let { fautes += "$l : $k : « ${it.value} »" } }
            t.pluriels.forEach { (k, f) -> f.values.forEach { v -> interdit.find(v)?.let { fautes += "$l : $k : « ${it.value} »" } } }
        }
        if (fautes.isNotEmpty()) fail("Mot de rôle en dur (passer par {decideur}) :\n" + fautes.joinToString("\n"))
    }

    @Test fun le_mot_du_decideur_vient_du_vocabulaire() {
        TrJvm.langue = "fr"
        assertEquals("Fermée par un décideur.", Tr.s(R.string.blocage_code_regle, Vocabulaire.decideur()))
        Vocabulaire.surcharge = Vocabulaire.filtrer(mapOf("decideur" to " responsable ", "Decideur" to "Responsable", "inconnu" to "x", "demandeur" to 3))
        assertEquals(mapOf("decideur" to "responsable"), Vocabulaire.surcharge)
        assertEquals("Fermée par un responsable.", Tr.s(R.string.blocage_code_regle, Vocabulaire.decideur()))
        assertEquals("Responsable", Vocabulaire.Decideur())
        assertEquals(30, Vocabulaire.filtrer(mapOf("decideurs" to "x".repeat(50)))["decideurs"]!!.length)
        // Le relevé est lu par l'agent.
        assertTrue(File(src, "java/org/soprez/vision/AgentService.kt").readText().contains("Vocabulaire.recevoir(this, r.optJSONObject(\"vocabulaire\"))"))
        assertTrue(File(src, "java/org/soprez/vision/VisionApp.kt").readText().contains("Vocabulaire.charger(this)"))
    }

    @Test fun ni_famille_ni_maison_a_l_ecran() {
        val interdits = Regex("""(?i)\b(famil\w*|maison|house|haus|zuhause|casa|hogar|gezin|thuis|huis)\b""")
        for (l in langues) {
            val t = TrJvm.dossier(TrJvm.dossierDe(l)).chaines
            for (k in listOf("lieu_defaut", "manifeste_maison", "lanceur_salut_jour_seul", "lanceur_salut_soir_seul", "lanceur_gestion"))
                t[k]?.let { assertFalse("$l : $k = $it", interdits.containsMatchIn(it)) }
        }
        assertEquals("Lieu", Config.LIEU_DEFAUT)
        val manifeste = File(src, "AndroidManifest.xml").readText()
        assertFalse(Regex("""(?i)maison|famille|gestionnaire""").containsMatchIn(manifeste.replace("manifeste_maison", "").replace(Migration.ANCIEN, "")))
    }
}
