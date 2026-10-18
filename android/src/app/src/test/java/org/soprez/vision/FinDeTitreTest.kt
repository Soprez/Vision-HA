// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * l'auteur (16/10, 1.93.0 : « un morceau s'est terminé et la lecture s'est arrêtée là ») : le lecteur Classique dit
 * « décodeur prêt » (STMd) dès que le flux est entièrement reçu, même quand la durée est inconnue (flux de Music
 * Assistant sans longueur) ; avant, STMd ne partait qu'à la fin, collé à STMu, et la file s'arrêtait.
 */
class FinDeTitreTest {
    @Test fun flux_recu_suffit_meme_sans_duree() {
        assertTrue(LecteurSlim.decodeurPretMaintenant(deja = false, dureeMs = -1, positionMs = 5_000, fluxRecu = true))
        assertTrue(LecteurSlim.decodeurPretMaintenant(false, 0, 5_000, true))
    }

    @Test fun duree_inconnue_et_flux_en_cours_rien() {
        // Le cas de la production : durée inconnue — avant, rien jusqu'à la fin du titre.
        assertFalse(LecteurSlim.decodeurPretMaintenant(false, -1, 200_000, false))
    }

    @Test fun la_duree_reste_un_secours_et_une_seule_fois() {
        assertTrue(LecteurSlim.decodeurPretMaintenant(false, 200_000, 195_000, false))
        assertFalse(LecteurSlim.decodeurPretMaintenant(false, 200_000, 150_000, false))
        assertFalse(LecteurSlim.decodeurPretMaintenant(deja = true, dureeMs = 200_000, positionMs = 195_000, fluxRecu = true))
    }
}
