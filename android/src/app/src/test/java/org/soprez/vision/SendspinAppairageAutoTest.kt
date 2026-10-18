// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.90.1 (retour d'essai) : l'appairage Sendspin automatique — quand le demander à Home Assistant, quel PIN lui envoyer. */
class SendspinAppairageAutoTest {
    private val m = 1_000_000L

    @Test fun demande_seulement_si_relie_et_pas_appaire() {
        assertTrue(SendspinAppairageAuto.demarrer(true, "none", null, null, 0L, m))
        assertFalse(SendspinAppairageAuto.demarrer(false, "none", null, null, 0L, m))       // pas relié à MA
        assertFalse(SendspinAppairageAuto.demarrer(true, "user", null, null, 0L, m))        // déjà appairé
        assertFalse(SendspinAppairageAuto.demarrer(true, "none", "commence", null, 0L, m))  // appairage en cours
        assertFalse(SendspinAppairageAuto.demarrer(true, "none", "attente_geste", null, 0L, m))
        // 1.90.2 : un appairage abandonné (ou fini sans confiance) ne bloque plus une nouvelle demande.
        assertTrue(SendspinAppairageAuto.demarrer(true, "none", "abandon", null, 0L, m))
        assertFalse(SendspinAppairageAuto.demarrer(true, "none", null, "482913", 0L, m))   // PIN déjà affiché
    }

    /** Banc Musique (2026-10-12) : à la reconnexion, le lecteur est relié avant la poignée de main ; sa confiance est
     *  alors inconnue et rien ne doit partir vers HA (un lecteur déjà appairé redemandait l'appairage à chaque retour). */
    @Test fun rien_avant_la_poignee_de_main() {
        assertEquals(SendspinClient.CONFIANCE_INCONNUE, SendspinClient.Etat(connecte = true).confiance)
        assertFalse(SendspinAppairageAuto.demarrer(true, SendspinClient.Etat(connecte = true).confiance, null, null, 0L, m))
        assertFalse(SendspinAppairageAuto.demarrer(true, SendspinClient.Etat().copy(connecte = true).confiance, null, null, 0L, m))
    }

    @Test fun une_demande_toutes_les_deux_minutes() {
        assertFalse(SendspinAppairageAuto.demarrer(true, "none", null, null, m, m + 60_000))
        assertTrue(SendspinAppairageAuto.demarrer(true, "none", null, null, m, m + SendspinAppairageAuto.INTERVALLE_MS + 1))
    }

    @Test fun chaque_pin_envoye_une_fois() {
        assertEquals("482913", SendspinAppairageAuto.pinAEnvoyer("482913", null))
        assertNull(SendspinAppairageAuto.pinAEnvoyer("482913", "482913"))
        assertEquals("105377", SendspinAppairageAuto.pinAEnvoyer("105377", "482913"))
        assertNull(SendspinAppairageAuto.pinAEnvoyer(null, "482913"))
    }

    @Test fun pin_jamais_affiche_hors_appairage_a_la_main() {
        // 1.90.2 (exigence ferme de l'auteur) : appairage automatique, qu'il réussisse, échoue ou traîne : jamais le code.
        assertNull(SendspinAppairageAuto.pinAMontrer("none", "482913", manuel = false))
        assertFalse(SendspinAppairageAuto.gesteAMontrer("none", "attente_geste", manuel = false))
        // À la main (Réglages › Décideurs 🔒) : le code s'affiche, sauf une fois appairé.
        assertEquals("482913", SendspinAppairageAuto.pinAMontrer("none", "482913", manuel = true))
        assertNull(SendspinAppairageAuto.pinAMontrer("user", "482913", manuel = true))
        assertNull(SendspinAppairageAuto.pinAMontrer("none", null, manuel = true))
        assertTrue(SendspinAppairageAuto.gesteAMontrer("none", "attente_geste", manuel = true))
        assertFalse(SendspinAppairageAuto.gesteAMontrer("user", "attente_geste", manuel = true))
    }

    @Test fun statut_sans_code() {
        assertEquals(SendspinAppairageAuto.Statut.APPAIRE, SendspinAppairageAuto.statut(true, "user", null))
        assertEquals(SendspinAppairageAuto.Statut.CONNEXION, SendspinAppairageAuto.statut(false, "none", null))
        assertEquals(SendspinAppairageAuto.Statut.ECHEC, SendspinAppairageAuto.statut(true, "none", "droits"))
        assertNull(SendspinAppairageAuto.echec("pin", "ok"))
        assertNull(SendspinAppairageAuto.echec("demarrer", "trop_vite"))
        assertEquals("refus", SendspinAppairageAuto.echec("pin", "refus"))
        assertEquals("injoignable", SendspinAppairageAuto.echec("pin", ""))
    }
}
