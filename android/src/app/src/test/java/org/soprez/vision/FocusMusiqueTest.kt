// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.93.06 (retour d'essai) : la musique de Vision face au son des autres applis (focus audio). */
class FocusMusiqueTest {
    private val yt = "com.google.android.youtube.tv"
    private fun d(mode: String, c: Int, premier: String? = yt, applis: Set<String> = setOf(yt), reprise: Boolean = false,
                  pause: Boolean = false, baisse: Boolean = false) =
        FocusMusique.decision(mode, applis, reprise, c, premier, pause, baisse)

    @Test fun toujours_pause_des_qu_une_autre_appli_prend_le_son() {
        assertEquals(FocusMusique.Action.PAUSE, d(FocusMusique.TOUJOURS, AudioManager.AUDIOFOCUS_LOSS, premier = "com.autre"))
        assertEquals(FocusMusique.Action.PAUSE, d(FocusMusique.TOUJOURS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, premier = null))
    }

    @Test fun jamais_ne_touche_a_rien() {
        for (c in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
            assertEquals(FocusMusique.Action.RIEN, d(FocusMusique.JAMAIS, c))
    }

    @Test fun applis_seulement_pour_celles_de_la_liste() {
        assertEquals(FocusMusique.Action.PAUSE, d(FocusMusique.APPLIS, AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(FocusMusique.Action.RIEN, d(FocusMusique.APPLIS, AudioManager.AUDIOFOCUS_LOSS, premier = "com.autre"))
        // Appli inconnue (pas d'accès « usage ») : on ne coupe pas.
        assertEquals(FocusMusique.Action.RIEN, d(FocusMusique.APPLIS, AudioManager.AUDIOFOCUS_LOSS, premier = null))
    }

    @Test fun un_son_bref_baisse_puis_remonte() {
        assertEquals(FocusMusique.Action.BAISSER, d(FocusMusique.TOUJOURS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(FocusMusique.Action.REMONTER, d(FocusMusique.TOUJOURS, AudioManager.AUDIOFOCUS_GAIN, baisse = true))
    }

    @Test fun reprise_seulement_si_demandee_et_si_vision_a_mis_en_pause() {
        assertEquals(FocusMusique.Action.REPRENDRE, d(FocusMusique.TOUJOURS, AudioManager.AUDIOFOCUS_GAIN, reprise = true, pause = true))
        assertEquals(FocusMusique.Action.RIEN, d(FocusMusique.TOUJOURS, AudioManager.AUDIOFOCUS_GAIN, reprise = false, pause = true))
        // Une pause de l'utilisateur (pas de Vision) ne repart jamais toute seule.
        assertEquals(FocusMusique.Action.RIEN, d(FocusMusique.TOUJOURS, AudioManager.AUDIOFOCUS_GAIN, reprise = true, pause = false))
    }

    @Test fun reprise_apres_une_perte_pour_de_bon_seulement_apres_deux_silences() {
        assertFalse(FocusMusique.silenceSuffisant(0))
        assertFalse(FocusMusique.silenceSuffisant(1))
        assertTrue(FocusMusique.silenceSuffisant(2))
    }
}
