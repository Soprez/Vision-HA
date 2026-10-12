// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Refonte UX, lot 1 (APP-03) : jamais d'inscription sans confirmation explicite.
 *  - la règle ([Liaison.peutInscrire]) : une clé donnée par la personne, ou « Se relier » touché ;
 *  - trouver un serveur ([Decouverte.chercher]) ne pose aucun réglage et ne contacte personne ;
 *  - seul l'écran de liaison appelle [Decouverte.relier], et l'accueil ne lance plus rien tout seul ;
 *  - l'ancien drapeau « sans_cle », posé sans rien demander par les versions d'avant, n'autorise plus rien.
 */
class LiaisonTest {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(nom: String) = File(src, nom).readText()

    /** Le corps d'une fonction Kotlin (du `fun nom(` à l'accolade fermante correspondante). */
    private fun corps(code: String, nom: String): String {
        val debut = code.indexOf("fun $nom(")
        assertTrue("fonction $nom absente", debut >= 0)
        var i = code.indexOf('{', debut); var n = 0
        val d = i
        while (i < code.length) { if (code[i] == '{') n++ else if (code[i] == '}') { n--; if (n == 0) return code.substring(d, i + 1) }; i++ }
        error("fonction $nom non fermée")
    }

    @After fun nettoyer() = Liaison.oublierEcartes()

    @Test fun aucune_inscription_sans_cle_ni_confirmation() {
        assertFalse(Liaison.peutInscrire("", false))
        assertFalse(Liaison.peutInscrire("   ", false))
        assertTrue(Liaison.peutInscrire("", true))
        assertTrue(Liaison.peutInscrire("abcd1234", false))
    }

    @Test fun le_serveur_trouve_et_son_nom() {
        assertNull(Liaison.serveur("Chez X", emptyList()))
        val s = Liaison.serveur("", listOf("https://ha.exemple.org", "http://192.168.1.10:8123"))!!
        assertEquals("https://ha.exemple.org", s.externe)
        assertEquals("http://192.168.1.10:8123", s.interne)
        assertEquals("http://192.168.1.10:8123", s.adresse)
        assertEquals("192.168.1.10:8123", s.affiche())
        assertEquals("Bureau", Liaison.serveur(" Bureau ", listOf("http://10.0.0.2:8123"))!!.affiche())
    }

    @Test fun pas_celui_ci_ecarte_le_serveur() {
        val s = Liaison.serveur("", listOf("http://192.168.1.10:8123"))!!
        assertFalse(Liaison.estEcarte(s))
        Liaison.ecarter(s)
        assertTrue(Liaison.estEcarte(s))
        assertTrue(Liaison.estEcarte(Liaison.serveur("autre nom", listOf("http://192.168.1.10:8123"))!!))
        assertFalse(Liaison.estEcarte(Liaison.serveur("", listOf("http://192.168.1.30:8123"))!!))
    }

    @Test fun chercher_ne_pose_rien_et_ne_contacte_personne() {
        val c = corps(lire("Decouverte.kt"), "chercher")
        for (interdit in listOf("sansCle", "AgentService", "Net.", "urlExterne", "urlInterne", "cleInscription", "enroll"))
            assertFalse("Decouverte.chercher touche à « $interdit »", c.contains(interdit))
        val r = corps(lire("Decouverte.kt"), "relier")
        assertTrue(r.contains("sansCle = true") && r.contains("AgentService.demarrer"))
    }

    @Test fun seul_l_ecran_de_liaison_relie() {
        val fautes = ArrayList<String>()
        src.listFiles { f -> f.name.endsWith(".kt") }!!.forEach { f ->
            val t = f.readText()
            if (f.name != "Decouverte.kt" && f.name != "SetupActivity.kt" && t.contains("Decouverte.relier(")) fautes += f.name
            if (t.contains("inscrireSeul")) fautes += "${f.name} : inscrireSeul"
        }
        assertEquals(emptyList<String>(), fautes)
        // Dans l'écran, « relier » ne part que du bouton « Se relier ».
        val setup = lire("SetupActivity.kt")
        val i = setup.indexOf("Decouverte.relier(")
        assertTrue(i > 0 && setup.lastIndexOf("R.string.liaison_se_relier", i) in (i - 200) until i)
        assertFalse("l'accueil ne lance plus de recherche ni d'inscription", lire("LanceurActivity.kt").contains("Decouverte.chercher"))
    }

    @Test fun l_agent_respecte_la_regle_et_l_ancien_drapeau_est_ignore() {
        assertTrue(corps(lire("AgentService.kt"), "inscrire").contains("Liaison.peutInscrire(cfg.cleInscription, cfg.sansCle)"))
        val cfg = lire("Config.kt")
        assertTrue(cfg.contains("getBoolean(\"sans_cle_confirme\", false)"))
        assertFalse(cfg.contains("getBoolean(\"sans_cle\","))
    }

    /** APP-05 : la recherche réseau s'arrête au bout de 15 s, et l'écran propose Réessayer / Autres méthodes. */
    @Test fun la_recherche_finit_au_bout_de_15_s() {
        assertEquals(15_000L, Decouverte.DELAI_MS)
        assertTrue(lire("Decouverte.kt").contains("main.postDelayed({ conclure() }, DELAI_MS)"))
        val setup = corps(lire("SetupActivity.kt"), "carteDecouverte")
        assertTrue(setup.contains("R.string.commun_reessayer") && setup.contains("R.string.liaison_autres_methodes"))
    }

    /** APP-02 : une voie principale d'abord (QR), la recherche ensuite, les autres voies repliées par défaut. */
    @Test fun bienvenue_une_voie_principale_puis_autres_methodes_repliees() {
        val e = corps(lire("SetupActivity.kt"), "ecranConnexion")
        assertTrue(e.indexOf("cartePrincipale(camera)") in 0 until e.indexOf("carteDecouverte()"))
        assertTrue(e.contains("visibility = if (autresDepliees) View.VISIBLE else View.GONE"))
        assertTrue(lire("SetupActivity.kt").contains("private var autresDepliees = false"))
        // Sur TV, le premier focus va à l'action principale.
        assertTrue(e.contains("findViewWithTag<View>(\"bienvenue_principal\")?.requestFocus()"))
    }
}
