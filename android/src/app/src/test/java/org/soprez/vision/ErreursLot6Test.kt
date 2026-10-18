// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lot 6 (APP-37, APP-24) : l'invite biométrique annulée referme l'écran ; les erreurs disent quoi faire. */
class ErreursLot6Test {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(nom: String) = File(src, nom).readText()

    @Test fun biometrie_annulee() {
        assertEquals(Biometrie.Suite.ANNULER, Biometrie.suiteErreur(10))  // Retour, toucher à côté
        assertEquals(Biometrie.Suite.ANNULER, Biometrie.suiteErreur(5))
        assertEquals(Biometrie.Suite.ANNULER, Biometrie.suiteErreur(3))
        assertEquals(Biometrie.Suite.CODE, Biometrie.suiteErreur(7))      // trop d'essais : le code
        assertEquals(Biometrie.Suite.CODE, Biometrie.suiteErreur(9))
        assertEquals(Biometrie.Suite.RIEN, Biometrie.suiteErreur(13))     // « Code de gestion » : son bouton s'en charge
        assertTrue(lire("Biometrie.kt").contains("override fun onAuthenticationError"))
        assertTrue(lire("Garde.kt").contains("annule = annule)"))
        // GardeActivity (transparente) se ferme quand on annule.
        assertTrue(lire("GardeActivity.kt").contains("annule = { finish() }"))
    }

    @Test fun erreurs_sans_code_http() {
        val l1 = listOf("Deverrouillage.kt" to "R.string.dev_demande_refusee, e.code", "Deverrouillage.kt" to "R.string.dev_refuse_code, e.code",
            "AgentService.kt" to "R.string.agent_vision_ne_repond_pas, e.code", "AgentService.kt" to "e.message ?: e.javaClass.simpleName",
            "WidgetsHa.kt" to "R.string.wha_err_autre, code", "VeilleView.kt" to "R.string.veilleview_injoignable, erreur")
        for ((f, motif) in l1) assertFalse("$f : $motif", lire(f).contains(motif))
        for (k in listOf("lot6_err_demande_refusee", "lot6_err_code_refuse", "lot6_err_vision_ne_repond_pas", "lot6_err_injoignable", "lot6_err_widget", "lot6_veille_injoignable")) {
            for (l in listOf("en", "fr", "de", "es", "it", "nl")) {
                val t = TrJvm.dossier(TrJvm.dossierDe(l)).chaines[k] ?: error("$k absent en $l")
                assertFalse("$k ($l) sans code", Regex("""%\d[${'$'}]d|\(\d{3}\)""").containsMatchIn(t))
            }
        }
    }
}
