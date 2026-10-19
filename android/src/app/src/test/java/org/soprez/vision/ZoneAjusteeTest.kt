// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.93.06.1 (retour d'essai) : une zone de widgets de côté tient toujours dans sa hauteur ; jamais agrandie. */
class ZoneAjusteeTest {
    @Test fun un_contenu_qui_tient_reste_a_sa_taille_et_centre() {
        assertEquals(1f, ZoneAjustee.facteur(600, 900), 0f)
        assertEquals(1f, ZoneAjustee.facteur(900, 900), 0f)
        assertEquals(150f, ZoneAjustee.position(600, 900, 1f), 0f)
    }

    @Test fun un_contenu_trop_haut_est_reduit_d_un_seul_tenant() {
        // La zone de gauche de la télé du salon : horloge, chauffage et consigne (1 170 px) dans 900 px.
        val k = ZoneAjustee.facteur(1170, 900)
        assertEquals(900f / 1170f, k, 1e-6f)
        assertEquals(900f, 1170 * k, 0.5f)
        assertEquals(0f, ZoneAjustee.position(1170, 900, k), 0.5f)
    }

    @Test fun cas_limites() {
        assertEquals(1f, ZoneAjustee.facteur(0, 900), 0f)
        assertEquals(1f, ZoneAjustee.facteur(500, 0), 0f)
    }
}
