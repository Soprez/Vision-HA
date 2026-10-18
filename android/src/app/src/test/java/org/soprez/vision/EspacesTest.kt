// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refonte UX, lot 5 (etude-ux/05-architecture-cible.md §3) : les espaces de l'écran principal, sans Android.
 * Décideur : Aujourd'hui, Personnes, Appareils, Réglages ; Demandeur : Moi, Réglages ; même ordre partout ;
 * l'espace gardé l'emporte sur celui demandé ; Retour remonte au premier espace avant de quitter.
 */
class EspacesTest {
    @Test fun les_espaces_du_decideur_dans_l_ordre() {
        assertEquals(listOf(Espaces.AUJOURDHUI, Espaces.PERSONNES, Espaces.APPAREILS, Espaces.REGLAGES), Espaces.pour(true))
    }

    @Test fun le_demandeur_ne_voit_que_moi_et_reglages() {
        assertEquals(listOf(Espaces.MOI, Espaces.REGLAGES), Espaces.pour(false))
    }

    @Test fun quatre_onglets_au_plus() {
        assertTrue(Espaces.pour(true).size <= 4)
        assertTrue(Espaces.pour(false).size <= 4)
    }

    @Test fun premier_espace_selon_le_role() {
        assertEquals(Espaces.AUJOURDHUI, Espaces.accueil(true))
        assertEquals(Espaces.MOI, Espaces.accueil(false))
    }

    @Test fun initial_sans_rien_le_premier_du_role() {
        assertEquals(Espaces.AUJOURDHUI, Espaces.initial(true, null, null))
        assertEquals(Espaces.MOI, Espaces.initial(false, "", null))
    }

    @Test fun initial_l_espace_demande() {
        assertEquals(Espaces.PERSONNES, Espaces.initial(true, Espaces.PERSONNES, null))
        assertEquals(Espaces.REGLAGES, Espaces.initial(false, Espaces.REGLAGES, null))
    }

    @Test fun initial_l_espace_garde_l_emporte_sur_la_demande() {
        assertEquals(Espaces.APPAREILS, Espaces.initial(true, Espaces.PERSONNES, Espaces.APPAREILS))
    }

    @Test fun un_espace_d_un_autre_role_passe_a_son_equivalent() {
        assertEquals(Espaces.MOI, Espaces.initial(false, Espaces.PERSONNES, null))
        assertEquals(Espaces.MOI, Espaces.valide(false, Espaces.AUJOURDHUI))
        assertEquals(Espaces.AUJOURDHUI, Espaces.valide(true, Espaces.MOI))
        assertEquals(Espaces.REGLAGES, Espaces.valide(false, Espaces.REGLAGES))
        assertEquals(Espaces.AUJOURDHUI, Espaces.valide(true, "inconnu"))
    }

    @Test fun retour_remonte_au_premier_espace_puis_rend_la_main() {
        assertEquals(Espaces.AUJOURDHUI, Espaces.retour(true, Espaces.REGLAGES))
        assertEquals(Espaces.AUJOURDHUI, Espaces.retour(true, Espaces.PERSONNES))
        assertEquals(Espaces.MOI, Espaces.retour(false, Espaces.REGLAGES))
        assertNull(Espaces.retour(true, Espaces.AUJOURDHUI))
        assertNull(Espaces.retour(false, Espaces.MOI))
    }

    @Test fun navigation_selon_le_type_d_appareil() {
        assertEquals(Espaces.BARRE, Espaces.navigation("telephone"))
        assertEquals(Espaces.RAIL, Espaces.navigation("tablette"))
        assertEquals(Espaces.RAIL_TELE, Espaces.navigation("tv"))
    }

    /** Les libellés des espaces : présents dans les six langues, sans mot interdit (vérifié aussi par I18nUx1Test). */
    @Test fun libelles_des_espaces_dans_les_six_langues() {
        for (d in listOf("values", "values-fr", "values-de", "values-es", "values-it", "values-nl")) {
            val t = TrJvm.dossier(d).chaines
            for (k in listOf("espace_aujourdhui", "espace_personnes", "espace_appareils", "espace_reglages", "espace_moi"))
                assertTrue("$d : $k", !t[k].isNullOrBlank())
        }
    }
}
