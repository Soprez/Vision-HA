// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 15-10 (retours d'essai) :
 * - widget Demandes : le total (liste coupée à quatre) et ce qui se décide par lots comptent ;
 * - widgets intégrés proposés selon les données du lieu (« widgets_disponibles » de Home Assistant), rien en dur.
 */
class RetoursJa1015Test {
    @Test fun demandes_total_et_lots() {
        assertEquals(0, Demandes.total(0, null, 0))
        // Ancien Home Assistant (pas de total) : la liste.
        assertEquals(2, Demandes.total(2, null, 0))
        // La liste est coupée à quatre, le total ne l'est pas.
        assertEquals(6, Demandes.total(4, 6, 0))
        // Un appareil à valider compte (une demande de liaison), même sans demande une à une.
        assertEquals(1, Demandes.total(0, 0, 1))
        // Un lot vide ou sans libellé ne compte pas.
        assertTrue(Demandes.lotsValides(listOf("rien" to 0, "" to 3)).isEmpty())
    }

    /** 16-10 (l'auteur : « 1 demande en attente », et aucune demande à l'ouverture) : « 229 éléments à classer » n'est pas
     *  une demande ; seuls les appareils à valider s'ajoutent au compte, les autres lots gardent leur ligne. */
    @Test fun les_lots_ne_sont_pas_des_demandes() {
        // Chez l'auteur le 16-10 : aucune demande, un seul lot « 229 éléments à classer » → 0 demande (avant : « 1 demande »).
        assertEquals(0, Demandes.total(0, 0, Demandes.appareilsAValider(listOf("classer" to 229))))
        val lots = listOf("appairage" to 2, "classer" to 5, "logiciels" to 3)
        assertEquals(2, Demandes.appareilsAValider(lots))
        assertEquals(3, Demandes.total(1, 1, Demandes.appareilsAValider(lots)))
        assertEquals(0, Demandes.appareilsAValider(listOf("appairage" to -1)))
    }

    @Test fun widgets_selon_les_donnees_du_lieu() {
        // Home Assistant n'a jamais envoyé la liste (ancienne version) : tout reste proposé.
        for (w in Accueil.DEPENDANTS) assertTrue(w, Accueil.disponible(w, null))
        val lieu = setOf("temps", "demandes", "horloge", "batteries")
        assertTrue(Accueil.disponible("horloge", lieu))
        assertTrue(Accueil.disponible("batteries", lieu))
        for (w in listOf("chauffage", "fioul", "ecole", "camera", "enfants", "maison", "infos", "devoirs")) assertFalse(w, Accueil.disponible(w, lieu))
        // L'horloge stylisée, le temps d'écran et les demandes ne dépendent pas des données du lieu.
        assertTrue(Accueil.disponible(ZoneHaut.HORLOGE, emptySet()))
        assertTrue(Accueil.disponible("temps", emptySet()))
        assertTrue(Accueil.disponible("demandes", emptySet()))
    }
}
