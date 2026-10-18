// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FAILLE DE SÉCURITÉ (l'auteur, 16-10) : un Décideur a déverrouillé Alex en touchant la demande, sans empreinte ni code.
 * Ces essais prouvent qu'aucun chemin de l'appli ne peut accorder sans une authentification fraîche et unique :
 * - la règle (quelles requêtes accordent) ;
 * - la preuve : une seule fois, fraîche, oubliée à l'extinction de l'écran ;
 * - le verrou est dans Net.post / postUne / postBytes, AVANT tout envoi : un chemin oublié échoue fermé ;
 * - exigerOctroi ne passe jamais par la fenêtre de grâce ; chaque écran qui accorde l'appelle.
 */
class OctroiTest {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(nom: String) = File(src, nom).readText()

    @After fun nettoyer() = Octroi.oublier()

    @Test fun la_regle_des_requetes_qui_accordent() {
        val p = "/api/pc_parental/parent"
        // Accorder, déverrouiller, prolonger, autoriser, ouvrir : oui (et toute action inconnue : prudence).
        for (a in listOf("ouvrir", "annuler", "temps", "partage", "attribuer", "limite", "plage", "logiciel", "pause_max",
                "lier_domaine", "retirer_autorisation", "fin_pause_generale", "reprise_autorisations", "inconnue"))
            assertTrue(a, Octroi.estOctroi(p, a, "", 0))
        for (d in listOf("temporaire", "accorder", "toujours", "exception", ""))
            assertTrue("acces $d", Octroi.estOctroi(p, "acces", d, 30))
        assertTrue(Octroi.estOctroi(p, "autoriser", "temporaire", 30))
        assertTrue(Octroi.estOctroi(p, "etiquette", "autoriser", 0))
        assertTrue(Octroi.estOctroi(p, "etiquette", "neutre", 0))
        assertTrue(Octroi.estOctroi("/api/pc_parental/reponse", "", "", 15))
        assertTrue(Octroi.estOctroi("/api/pc_parental/deverrouillage/decision", "", "approuver", 20))
        assertTrue(Octroi.estOctroi("https://x/api/pc_parental/appairage/decideur/decision", "accepter", "accepter", 0))
        // Refuser, fermer, lire : non.
        assertFalse(Octroi.estOctroi(p, "acces", "non", 0))
        for (a in listOf("", "categorie", "recherche", "recents", "canaux", "fermer", "message", "concentration", "pause_generale"))
            assertFalse(a, Octroi.estOctroi(p, a, "", 0))
        assertFalse(Octroi.estOctroi(p, "etiquette", "bloquer", 0))
        assertFalse(Octroi.estOctroi(p, "autoriser", "bloquer", 0))
        assertFalse(Octroi.estOctroi("/api/pc_parental/reponse", "", "", 0))
        assertFalse(Octroi.estOctroi("/api/pc_parental/deverrouillage/decision", "", "refuser", 20))
        assertFalse(Octroi.estOctroi("/api/pc_parental/appairage/decideur/decision", "refuser", "refuser", 0))
        assertFalse(Octroi.estOctroi("/api/pc_parental/veille", "", "", 0))
    }

    @Test fun la_preuve_sert_une_fois_et_vieillit() {
        assertFalse("sans preuve", Octroi.consommer(1_000))
        Octroi.delivrer(1_000)
        assertTrue(Octroi.consommer(2_000))
        assertFalse("déjà consommée", Octroi.consommer(2_001))
        Octroi.delivrer(1_000)
        assertFalse("périmée", Octroi.consommer(1_000 + Octroi.VALIDITE_MS + 1))
        assertFalse(Octroi.consommer(1_000 + Octroi.VALIDITE_MS + 2))
        Octroi.delivrer(5_000)
        Octroi.oublier()
        assertFalse("oubliée (écran éteint)", Octroi.consommer(5_001))
    }

    @Test fun le_verrou_est_avant_tout_envoi() {
        val net = lire("Net.kt")
        for (f in listOf("fun post(", "fun postUne(", "fun postBytes(")) {
            val corps = net.substringAfter(f).substringBefore("\n    }")
            val verrou = corps.indexOf("Octroi.estOctroi(")
            val envoi = listOf("openConnection", "envoyerUne(", "postUne(").map { corps.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
            assertTrue("$f : verrou d'octroi absent", verrou >= 0)
            assertTrue("$f : le verrou doit précéder l'envoi", verrou < envoi)
            assertTrue("$f : la preuve doit être consommée", corps.contains("Octroi.consommer()"))
        }
        // Aucune autre connexion HTTP de l'appli ne parle aux adresses qui accordent.
        val adresses = Regex("""pc_parental/(parent|reponse|deverrouillage/decision|appairage/decideur/decision)""")
        for (f in src.listFiles()!!.filter { it.extension == "kt" && it.name != "Net.kt" && it.name != "Octroi.kt" }) {
            val t = f.readText()
            if (t.contains("openConnection")) assertFalse("${f.name} : connexion directe vers une adresse d'octroi", adresses.containsMatchIn(t))
        }
    }

    @Test fun pas_de_fenetre_de_grace_pour_accorder() {
        val garde = lire("Garde.kt")
        val octroi = garde.substringAfter("fun exigerOctroi(").substringBefore("\n    }")
        assertFalse("exigerOctroi ne doit pas consulter la fenêtre de grâce", octroi.contains("ouverte()"))
        assertTrue(octroi.contains("Octroi.delivrer()"))
        // La preuve n'est délivrée nulle part ailleurs.
        val delivrances = src.listFiles()!!.filter { it.extension == "kt" && it.name != "Octroi.kt" }
            .sumOf { Regex("""Octroi\.delivrer\(""").findAll(it.readText()).count() }
        assertEquals(1, delivrances)
        assertTrue(garde.substringAfter("fun fermer()").substringBefore("\n").contains("Octroi.oublier()"))
        // Le raccourci des essais instrumentés n'est jamais mis à vrai dans l'appli.
        for (f in src.listFiles()!!.filter { it.extension == "kt" })
            assertFalse(f.name, Regex("""preuveEssai\s*=\s*true""").containsMatchIn(f.readText()))
        assertTrue(garde.contains("private var preuveEssai = false"))
    }

    /** Proposition validée en essai (16-10) : un appareil qui devient Décideur choisit son code tout de suite ; plus
     *  jamais « choisir le code au premier octroi, puis accorder aussitôt ». */
    @Test fun un_decideur_a_toujours_un_code() {
        assertTrue(Garde.codeRequis(setOf("gerer"), aUnCode = false))
        assertTrue(Garde.codeRequis(setOf("approuver", "temps"), aUnCode = false))
        assertFalse(Garde.codeRequis(setOf("gerer", "approuver"), aUnCode = true))
        assertFalse(Garde.codeRequis(setOf("protege", "demander_acces"), aUnCode = false))
        assertFalse(Garde.codeRequis(emptySet(), aUnCode = false))
        val garde = lire("Garde.kt")
        val octroi = garde.substringAfter("fun exigerOctroi(").substringBefore("\n    }")
        assertTrue("sans code (ni ici ni dans HA) : on l'impose, sans accorder",
            octroi.contains("if (!cfg.aUnCode && CodeDecideur.codeHa(act) != true) { imposerCode(act, apresChoix = annule); return }"))
        assertFalse("exigerOctroi ne fait jamais choisir le code puis accorder", octroi.contains("choisirCode("))
        val impose = garde.substringAfter("private fun imposerCode(").substringBefore("\n    }")
        assertFalse("le choix du code ne délivre aucune preuve", impose.contains("Octroi.delivrer"))
        assertTrue("le choix du code n'ouvre pas la fenêtre de grâce", impose.contains("jusqua = 0L"))
        // Le rôle reçu de HA est noté à chaque relevé ; les écrans d'un Décideur imposent le code à l'ouverture.
        assertTrue(lire("Profil.kt").contains("Garde.noterRole("))
        for (f in listOf("LanceurActivity.kt", "ParentsActivity.kt", "SetupActivity.kt"))
            assertTrue(f, lire(f).substringAfter("override fun onResume()").substringBefore("\n    }").contains("Garde.exigerCodeDecideur(this)"))
    }

    /** Décision de l'auteur (16-10) : un seul code de déverrouillage par Décideur, vérifié par Home Assistant (appli et
     *  tableau) ; l'empreinte locale migre vers HA ; l'empreinte digitale reste locale. */
    @Test fun un_seul_code_verifie_par_home_assistant() {
        // Vérifier le code n'est pas un octroi (sinon le verrou réseau bloquerait sa propre preuve).
        assertFalse(Octroi.estOctroi("/api/pc_parental/parent", "code_deverrouillage", "", 0))
        val garde = lire("Garde.kt")
        val code = garde.substringAfter("private fun demanderCode(").substringBefore("\n    }")
        assertTrue("le code tapé est vérifié par CodeDecideur (HA)", code.contains("CodeDecideur.verifier(act"))
        assertFalse("plus de vérification seulement locale", code.contains("cfg.verifierCode("))
        assertTrue("le code choisi part dans HA", garde.substringAfter("private fun choisirCode(").substringBefore("\n    }").contains("CodeDecideur.definir(act"))
        val decideur = lire("CodeDecideur.kt")
        assertTrue(decideur.contains("\"op\", \"verifier\"") && decideur.contains("\"op\", \"migrer\"") && decideur.contains("cfg.empreinteLocale()"))
        // L'empreinte locale est le même calcul que Home Assistant (octroi.empreinte_appli) : SHA-256 de « vision:<code> ».
        assertTrue(lire("Config.kt").contains("digest((\"vision:\" + code).toByteArray())"))
        // Au relevé, ce que dit HA passe avant le rôle (le code requis en dépend).
        val profil = lire("Profil.kt")
        assertTrue(profil.indexOf("CodeDecideur.releve(ctx, r)") in 0 until profil.indexOf("Garde.noterRole("))
        // Changer son code : dans HA, avec l'ancien.
        assertTrue(lire("SetupActivity.kt").contains("Garde.saisirCodeActuel(this) { changer(it) }"))
    }

    /** Chaque chemin connu qui accorde demande l'empreinte ou le code à chaque fois. */
    @Test fun chaque_chemin_qui_accorde_est_garde() {
        val attendus = mapOf(
            "LanceurActivity.kt" to "Garde.exigerOctroi(",        // widget Demandes de l'accueil
            "GardeActivity.kt" to "Garde.exigerOctroi(",          // notification de l'appli Vision
            "MessageActivity.kt" to "Garde.exigerOctroi(",        // demande en plein écran
            "ParentsActivity.kt" to "Garde.exigerPour(",          // À décider, appareils, +30 min
            "FichePersonneActivity.kt" to "Garde.exigerOctroi(",  // fiche personne, +30 min
            "DeverrouillageParentActivity.kt" to "Garde.exigerPour(",
            "DetailFermesActivity.kt" to "Garde.exigerOctroi(",
            "ExceptionsActivity.kt" to "Garde.exigerPour(",
            "CategorieActivity.kt" to "Garde.exigerPour(",
            "CategoriesActivity.kt" to "Garde.exigerPour(",
            "NouvelAppareilActivity.kt" to "Garde.exigerOctroi(",
        )
        for ((f, appel) in attendus) assertTrue("$f : $appel attendu", lire(f).contains(appel))
        // La notification ne répond jamais sans passer par l'écran de garde.
        val recepteur = lire("ReponseReceiver.kt").substringAfter("override fun onReceive").substringBefore("\n    }")
        assertTrue(recepteur.contains("GardeActivity.intent") && !recepteur.contains("Net.post"))
    }
}
