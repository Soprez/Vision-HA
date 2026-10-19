// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.93.06 (l'auteur : « appliquer réellement la transition de 4 s entre les titres, sans conflit avec le décodeur prêt envoyé
 * 15 s avant la fin ») : le fondu enchaîné du lecteur Classique.
 */
class LecteurFonduTest {
    @Test fun fondu_seulement_si_music_assistant_le_demande_et_si_la_fin_est_connue() {
        assertTrue(LecteurSlim.fonduEnchaine('1', 4_000, 194_000))
        assertFalse("sans transition", LecteurSlim.fonduEnchaine('0', 4_000, 194_000))
        assertFalse("durée nulle", LecteurSlim.fonduEnchaine('1', 0, 194_000))
        assertFalse("fin inconnue : enchaînement sans blanc d'Android", LecteurSlim.fonduEnchaine('1', 4_000, 0))
        assertFalse("fondu d'entrée ou de sortie seul : pas de chevauchement", LecteurSlim.fonduEnchaine('2', 4_000, 194_000))
    }

    @Test fun le_fondu_part_4_s_avant_la_fin_bien_apres_le_decodeur_pret() {
        assertFalse(LecteurSlim.debutFondu(10_000, 4_000))
        assertTrue(LecteurSlim.debutFondu(4_000, 4_000))
        assertTrue(LecteurSlim.debutFondu(3_200, 4_000))
        // Le décodeur prêt (15 s avant la fin) arrive avant : la suite est préparée quand le fondu commence.
        assertTrue(LecteurSlim.AVANCE_STMD_MS > 4_000 + 5_000)
        assertFalse(LecteurSlim.debutFondu(15_000, 4_000))
    }

    @Test fun en_pause_la_suite_attend_la_reprise() {
        // Banc, 18/10 : pause à 257 s sur 284, la suite arrive 12 s plus tard ; elle ne doit pas partir toute seule.
        assertTrue(LecteurSlim.enchaineALaSuite(depuisFlush = false, aUnLecteur = true, etat = "pause"))
        assertTrue(LecteurSlim.enchaineALaSuite(depuisFlush = false, aUnLecteur = true, etat = "lecture"))
        // Après un flush (titre choisi, saut) ou sans titre en cours : un nouveau flux remplace, comme avant.
        assertFalse(LecteurSlim.enchaineALaSuite(depuisFlush = true, aUnLecteur = true, etat = "pause"))
        assertFalse(LecteurSlim.enchaineALaSuite(depuisFlush = false, aUnLecteur = false, etat = "arrêt"))
        // Et pendant une pause, « décodeur prêt » n'est pas annoncé : Music Assistant n'envoie pas la suite.
        assertFalse(LecteurSlim.stmdPermis("pause"))
        assertTrue(LecteurSlim.stmdPermis("lecture"))
    }

    @Test fun volumes_a_puissance_constante() {
        assertEquals(1f to 0f, LecteurSlim.volumesFondu(0f).let { (a, b) -> Math.round(a * 1000) / 1000f to Math.round(b * 1000) / 1000f })
        assertEquals(0f to 1f, LecteurSlim.volumesFondu(1f).let { (a, b) -> Math.round(a * 1000) / 1000f to Math.round(b * 1000) / 1000f })
        for (i in 0..20) {
            val (s, e) = LecteurSlim.volumesFondu(i / 20f)
            assertEquals("pas de creux au milieu", 1f, s * s + e * e, 0.001f)
        }
        val (s, e) = LecteurSlim.volumesFondu(0.5f)
        assertEquals(s, e, 0.001f)
    }
}
