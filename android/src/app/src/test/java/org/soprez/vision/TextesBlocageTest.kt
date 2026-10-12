// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'écran de blocage dit ce qui est fermé, pourquoi et jusqu'à quand, en mots neutres. */
class TextesBlocageTest {
    private fun de(verrouille: Boolean = false, message: String = "", reglage: Boolean = false, appli: String = "",
                   motif: String = "", code: String = "", reouverture: String = "") =
        TextesBlocage.de(verrouille, message, reglage, appli, motif, code, reouverture)

    @Test fun appareil_ferme_avec_raison_et_heure() {
        val t = de(verrouille = true, message = "temps d'écran écoulé", reouverture = "demain à 07h00")
        assertEquals("Cet appareil est fermé", t.titre)
        assertEquals("Temps d'écran écoulé.", t.pourquoi)
        assertEquals("Réouverture prévue demain à 07h00.", t.quand)
    }

    @Test fun appareil_ferme_sans_raison_ni_heure() {
        val t = de(verrouille = true, message = "Accès fermé")
        assertEquals("Les règles de Vision ferment cet appareil en ce moment.", t.pourquoi)
        assertTrue(t.quand.startsWith("Pas d'heure de réouverture prévue"))
    }

    @Test fun appli_avec_motif_du_serveur_ou_code() {
        val a = de(appli = "YouTube", motif = "Hors des plages de l'agenda", code = "plage")
        assertEquals("YouTube n'est pas disponible", a.titre)
        assertEquals("Hors des plages de l'agenda.", a.pourquoi)
        assertEquals("", a.quand)
        val b = de(appli = "YouTube", code = "regle")
        assertEquals("Fermée par un décideur.", b.pourquoi)
        val c = de()
        assertEquals("Cette appli n'est pas disponible", c.titre)
    }

    @Test fun reglage_protege() {
        val t = de(reglage = true, appli = "Paramètres")
        assertEquals("Ce réglage est protégé", t.titre)
        assertEquals("", t.quand)
    }

    @Test fun vocabulaire_neutre() {
        val tous = listOf(de(verrouille = true), de(appli = "X"), de(reglage = true)) +
            listOf("plage", "temps", "moyenne", "regle", "age", "categorie", "maison", "securite").map { de(appli = "X", code = it) }
        for (t in tous) for (s in listOf(t.titre, t.pourquoi, t.quand)) {
            val l = s.lowercase()
            assertFalse(s, Regex("\\b(parent|parents|enfant|enfants|maison)\\b").containsMatchIn(l))
            assertFalse(s, s.contains("…"))
        }
    }
}
