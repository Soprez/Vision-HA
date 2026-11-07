// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.94 : l'attente avant de refaire une demande, côté appli (le calcul, et les écrans qui la montrent). */
class AccesAttenteTest {
    private val t0 = 1_000_000_000L
    private val min = 60_000L

    @Test fun apres_l_envoi_l_attente_court_sans_attendre_le_releve() {
        assertEquals(t0 + 5 * min, Acces.finAttente(emptyMap(), t0, 5, "apps", "roblox", t0 + 1_000))
        assertEquals(0L, Acces.finAttente(emptyMap(), t0, 5, "apps", "roblox", t0 + 5 * min))   // échue
        assertEquals(0L, Acces.finAttente(emptyMap(), t0, 0, "apps", "roblox", t0 + 1_000))      // pas d'attente réglée
        assertEquals(0L, Acces.finAttente(emptyMap(), null, 5, "apps", "roblox", t0))            // rien d'envoyé
    }

    @Test fun le_releve_du_serveur_compte_et_le_plus_tard_gagne() {
        val releve = mapOf("apps:roblox" to t0 + 9 * min)
        assertEquals(t0 + 9 * min, Acces.finAttente(releve, t0, 5, "apps", "roblox", t0 + 1_000))
        assertEquals(0L, Acces.finAttente(releve, null, 5, "apps", "minecraft", t0 + 1_000))     // une autre demande n'est pas retenue
        assertEquals(0L, Acces.finAttente(releve, null, 5, "sites", "roblox", t0 + 1_000))       // un autre genre non plus
    }

    @Test fun un_site_retenu_retient_ses_sous_domaines() {
        val releve = mapOf("sites:exemple.fr" to t0 + 5 * min)
        assertEquals(t0 + 5 * min, Acces.finAttente(releve, null, 5, "sites", "cours.exemple.fr", t0 + 1_000))
        assertEquals(0L, Acces.finAttente(releve, null, 5, "sites", "autre.fr", t0 + 1_000))
    }

    @Test fun la_demande_de_temps_a_sa_propre_cle() {
        assertEquals("temps", Acces.TEMPS); assertEquals("demande", Acces.TEMPS_NOM)
        assertEquals(t0 + 5 * min, Acces.finAttente(emptyMap(), t0, 5, Acces.TEMPS, Acces.TEMPS_NOM, t0 + 1_000))
    }

    private fun source(f: String): String = listOf(File("src/main/java/org/soprez/vision/$f"), File("app/src/main/java/org/soprez/vision/$f"))
        .first { it.isFile }.readText()

    @Test fun les_ecrans_montrent_l_attente_et_le_releve_la_porte() {
        assertTrue(source("AgentService.kt").contains("Acces.poserRedemande(r.optJSONArray(\"redemande\")"))
        for (f in listOf("BlockActivity.kt", "FermesActivity.kt", "DemandesAppareil.kt", "LanceurActivity.kt"))
            assertTrue(f, source(f).contains("Acces.attenteTexte"))
        // Le bouton reste visible, grisé : BlockActivity et FermesActivity le désactivent plutôt que de le retirer.
        assertTrue(source("Ui.kt").contains("fun boutonEnAttente(") && source("Ui.kt").contains("isEnabled = false"))
        assertTrue(source("BlockActivity.kt").contains("Ui.boutonEnAttente("))
        assertTrue(source("FermesActivity.kt").contains("Ui.boutonEnAttente("))
    }
}
