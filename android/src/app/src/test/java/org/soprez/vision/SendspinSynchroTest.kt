// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client Sendspin natif, lot 3 : la synchro du son (spec Sendspin, « Playback Synchronization ») — rien sous 1 ms,
 * correction douce bornée à 0,5 % de vitesse, recalage d'un coup seulement pour un grand écart.
 */
class SendspinSynchroTest {
    /** Banc Musique (2026-10-12) : un écart absurde (horloge pas synchronisée, +1921 s) n'écrit jamais 32 min de silence. */
    /** Banc Musique : un flux livré 3,3 s en retard est recalé, pas jeté ; un petit retard reste corrigé comme avant. */
    @Test fun flux_en_retard_recale() {
        assertEquals(3_348_000L, SendspinSynchro.reancrage(3_348_000L))
        assertEquals(0L, SendspinSynchro.reancrage(107_000L))
        assertEquals(0L, SendspinSynchro.reancrage(-186_000L))
        assertEquals(0L, SendspinSynchro.reancrage(324_000L))
    }

    @Test fun silence_borne_a_une_demi_seconde() {
        val d = SendspinSynchro.decider(-1_921_000_000L, 4_410, 44_100)
        assertTrue(d.silence > 80_000_000)
        assertEquals(22_050, SendspinSynchro.silenceParPas(d.silence, 44_100))
        assertEquals(300, SendspinSynchro.silenceParPas(300, 44_100))
    }

    @Test fun rien_sous_une_milliseconde() {
        assertEquals(SendspinSynchro.Decision(), SendspinSynchro.decider(900, 960, 48_000))
        assertEquals(SendspinSynchro.Decision(), SendspinSynchro.decider(-999, 960, 48_000))
    }

    @Test fun correction_douce_bornee_a_un_demi_pour_cent() {
        // 5 ms de retard sur un bloc de 20 ms (960 trames) : au plus 960 / 200 = 4 trames retirées.
        assertEquals(SendspinSynchro.Decision(retirer = 4), SendspinSynchro.decider(5_000, 960, 48_000))
        // 2 ms d'avance : 96 trames d'écart, 4 doublées au plus.
        assertEquals(SendspinSynchro.Decision(doubler = 4), SendspinSynchro.decider(-2_000, 960, 48_000))
        // Petit bloc : au moins une trame par bloc.
        assertEquals(SendspinSynchro.Decision(retirer = 1), SendspinSynchro.decider(1_500, 100, 48_000))
    }

    @Test fun recalage_pour_un_grand_ecart() {
        // Démarrage : le premier bloc doit sortir dans 500 ms → 24 000 trames de silence d'abord.
        assertEquals(SendspinSynchro.Decision(silence = 24_000, recalage = true), SendspinSynchro.decider(-500_000, 960, 48_000))
        // Coupure : 100 ms de retard → 4 410 trames jetées (44,1 kHz).
        assertEquals(SendspinSynchro.Decision(jeter = 4_410, recalage = true), SendspinSynchro.decider(100_000, 960, 44_100))
    }

    @Test fun retraits_et_doublons_repartis_sans_perdre_le_reste() {
        val pcm = ShortArray(20) { it.toShort() }  // 10 trames stéréo : (0,1) (2,3) … (18,19)
        val moins = SendspinSynchro.corriger(pcm, 2, retirer = 1, doubler = 0)
        assertEquals(18, moins.size)
        assertArrayEquals(shortArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), moins.copyOfRange(0, 10))  // trame 5 retirée
        assertArrayEquals(shortArrayOf(12, 13), moins.copyOfRange(10, 12))
        val plus = SendspinSynchro.corriger(pcm, 2, retirer = 0, doubler = 2)
        assertEquals(24, plus.size)
        // Les deux voies restent appariées (jamais une trame coupée en deux).
        for (i in plus.indices step 2) assertEquals(plus[i] + 1, plus[i + 1].toInt())
        assertArrayEquals(pcm, SendspinSynchro.corriger(pcm, 2, 0, 0))
    }

    @Test fun heure_de_sortie_prevue() {
        // Avec horodatage de la sortie : trame 48 000 sortie à 10 s → trame 96 000 à 11 s.
        assertEquals(11_000_000L, SendspinSynchro.sortiePrevue(96_000, 48_000, 48_000, 10_000_000, 0, 0, 0))
        // Sans horodatage : maintenant + ce qui reste dans la sortie + latence nominale.
        assertEquals(5_000_000L + 100_000 + 60_000, SendspinSynchro.sortiePrevue(9_600, 48_000, null, null, 5_000_000, 4_800, 60_000))
        assertTrue(SendspinSynchro.decider(SendspinSynchro.RECALAGE_US - 1, 960, 48_000).recalage.not())
    }
}
