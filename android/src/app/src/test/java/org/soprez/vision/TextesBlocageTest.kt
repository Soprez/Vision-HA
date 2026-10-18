// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'écran de blocage dit ce qui est fermé, pourquoi et jusqu'à quand, en mots neutres. */
class TextesBlocageTest {
    private fun de(verrouille: Boolean = false, message: String = "", reglage: Boolean = false, appli: String = "",
                   motif: String = "", code: String = "", reouverture: String = "", passee: String = "") =
        TextesBlocage.de(verrouille, message, reglage, appli, motif, code, reouverture, passee)

    @Test fun appareil_ferme_avec_raison_et_heure() {
        val t = de(verrouille = true, message = "temps d'écran écoulé", reouverture = "demain 07h00")
        assertEquals("Cet appareil est fermé", t.titre)
        assertEquals("Temps d'écran écoulé.", t.pourquoi)
        assertEquals("demain 07h00", t.quand)
        // Lot 6 (B10) : « Fermé par : {raison}, jusqu'à {heure} ».
        assertEquals("Fermé par", t.etiquette())
        assertEquals("Fermé par : Temps d'écran écoulé, jusqu'à demain 07h00", t.resume())
    }

    /** Lot 6 (APP-33) : la raison passée à l'écran reste, quand le relevé n'en dit pas plus. */
    @Test fun raison_passee_gardee() {
        val t = de(verrouille = true, message = "", passee = "Fermé par le planning du soir")
        assertEquals("Fermé par le planning du soir.", t.fermePar)
        // Un message générique ne remplace pas la raison passée ; un message précis du relevé l'emporte.
        assertEquals("Fermé par le planning du soir.", de(verrouille = true, message = "Accès fermé", passee = "Fermé par le planning du soir").fermePar)
        assertEquals("Pause du dîner.", de(verrouille = true, message = "pause du dîner", passee = "Fermé par le planning du soir").fermePar)
        // Une raison passée générique ne remplace pas le texte de repli.
        assertEquals("Les règles de Vision ferment cet appareil en ce moment.", de(verrouille = true, passee = "Accès fermé").fermePar)
        // Appli sans motif ni code : la raison passée plutôt que le texte générique.
        assertEquals("Fermée pendant les devoirs.", de(appli = "YouTube", passee = "fermée pendant les devoirs").fermePar)
        assertEquals("Les règles de Vision la ferment en ce moment.", de(appli = "YouTube", passee = "Cette appli est bloquée").fermePar)
    }

    /** Lot 6 (B10) : la source d'abord (planning, limite, catégorie, « Tout fermer », Décideur), la raison du serveur dessous. */
    @Test fun source_puis_raison() {
        val a = de(appli = "YouTube", motif = "plage du soir", code = "plage", reouverture = "21:30")
        assertEquals("Le planning", a.fermePar)
        assertEquals("Plage du soir.", a.detail)
        assertEquals("21:30", a.jusqua)
        assertEquals("Fermée par : Le planning, jusqu'à 21:30", a.resume())
        val b = de(appli = "YouTube", code = "maison", reouverture = "19:45")
        assertEquals("« Tout fermer », pour tous les appareils", b.fermePar)
        assertEquals("Fermée pour tous les appareils.", b.detail)
        assertEquals("19:45", b.jusqua)
        val c = de(appli = "YouTube", code = "regle", reouverture = "21:30")
        assertEquals("Un décideur", c.fermePar)
        assertEquals("", c.detail)
        assertEquals("", c.jusqua)
        assertEquals("Fermée par : Un décideur", c.resume())
        assertEquals("Sa catégorie", de(appli = "X", code = "categorie").fermePar)
        assertEquals("La limite de temps du jour", de(appli = "X", code = "temps").fermePar)
    }

    @Test fun heure_de_reouverture() {
        val maintenant = java.util.Calendar.getInstance().apply { set(2026, 9, 12, 18, 0, 0) }.timeInMillis
        val ce_soir = maintenant + 3 * 3600_000L
        val demain = maintenant + 13 * 3600_000L
        assertEquals(Tr.heure(ce_soir), TextesBlocage.heure(ce_soir, maintenant))
        assertEquals("demain " + Tr.heure(demain), TextesBlocage.heure(demain, maintenant))
        assertTrue(TextesBlocage.heure(maintenant + 3 * 86_400_000L, maintenant).endsWith(", " + Tr.heure(maintenant + 3 * 86_400_000L)))
    }

    @Test fun appareil_ferme_sans_raison_ni_heure() {
        val t = de(verrouille = true, message = "Accès fermé")
        assertEquals("Les règles de Vision ferment cet appareil en ce moment.", t.pourquoi)
        assertTrue(t.quand.startsWith("Pas d'heure de réouverture prévue"))
    }

    @Test fun appli_avec_motif_du_serveur_ou_code() {
        val a = de(appli = "YouTube", motif = "Hors des plages de l'agenda", code = "plage")
        assertEquals("YouTube n'est pas disponible", a.titre)
        // Lot 6 (B10) : la source, puis la raison du serveur dessous.
        assertEquals("Le planning", a.pourquoi)
        assertEquals("Hors des plages de l'agenda.", a.detail)
        assertEquals("", a.quand)
        assertEquals("Hors des plages de l'agenda.", de(appli = "YouTube", motif = "Hors des plages de l'agenda").pourquoi)
        val b = de(appli = "YouTube", code = "regle")
        assertEquals("Un décideur", b.pourquoi)
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
