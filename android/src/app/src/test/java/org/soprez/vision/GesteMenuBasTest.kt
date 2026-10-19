// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.93.06 (retour d'essai) : le menu du bas s'ouvre dès que le geste dépasse 0,11 dp/ms ou une distance de 48 dp. */
class GesteMenuBasTest {
    @Test fun un_geste_vif_vers_le_haut_ouvre_meme_court() {
        assertTrue(GesteMenuBas.finitOuvert(-0.12f, 10f, ouvrant = true))
        assertFalse("0,11 tout juste : pas encore", GesteMenuBas.finitOuvert(-0.11f, 10f, ouvrant = true))
    }

    @Test fun un_geste_lent_ouvre_des_48_dp() {
        assertTrue(GesteMenuBas.finitOuvert(-0.02f, 48f, ouvrant = true))
        assertFalse(GesteMenuBas.finitOuvert(-0.02f, 47f, ouvrant = true))
        // Avant, il fallait 30 % de la course (environ 230 dp) : 100 dp lents n'ouvraient pas ; maintenant si.
        assertTrue(GesteMenuBas.finitOuvert(0f, 100f, ouvrant = true))
    }

    @Test fun un_geste_vif_vers_le_bas_referme() {
        assertFalse(GesteMenuBas.finitOuvert(0.2f, 120f, ouvrant = true))
        assertFalse(GesteMenuBas.finitOuvert(0.2f, -10f, ouvrant = false))
    }

    @Test fun depuis_la_feuille_ouverte_la_meme_regle_pour_refermer() {
        assertTrue("un petit mouvement lent ne referme pas", GesteMenuBas.finitOuvert(0.05f, -20f, ouvrant = false))
        assertFalse("48 dp vers le bas referment", GesteMenuBas.finitOuvert(0.05f, -48f, ouvrant = false))
        assertTrue("un geste vif vers le haut la garde ouverte", GesteMenuBas.finitOuvert(-0.3f, -60f, ouvrant = false))
    }

    @Test fun fin_du_mouvement_courte() {
        assertTrue(GesteMenuBas.duree(1f, 2000f, 0f) <= 280L)
        assertTrue(GesteMenuBas.duree(0.5f, 2000f, 5000f) in 140L..280L)
        assertTrue(GesteMenuBas.duree(1f, 2000f, 400f) <= 280L)
    }
}
