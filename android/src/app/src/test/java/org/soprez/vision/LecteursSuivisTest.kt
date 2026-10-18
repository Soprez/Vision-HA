package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctif (retour d'essai) : la carte « en cours de lecture » de l'accueil ne montre que les lecteurs de cet appareil, la Party
 * de cet écran, ou ceux choisis ou pilotés depuis l'appli ; jamais un lecteur d'une autre personne, d'un autre lieu
 * ou d'un banc d'essai.
 */
class LecteursSuivisTest {
    private val propre = "Pixel d'essai · Vision"

    @Test fun lecteurDeTestQuiJoueAilleursCache() {
        // Le cas de l'auteur : pas de Party, Home Assistant renvoie le lecteur du banc qui joue ; rien de piloté ici.
        assertFalse(LecteursSuivis.permis("media_player.banc_essai_vision", "Banc d'essai · Vision", actif = false, ici = false,
            nomPropre = propre, suivis = emptySet()))
    }

    @Test fun lecteurDuneAutrePersonneCacheMemePendantSaParty() {
        assertFalse(LecteursSuivis.permis("media_player.salon_de_lou", "Salon de Lou", actif = true, ici = false,
            nomPropre = propre, suivis = emptySet()))
    }

    @Test fun lecteurDeCetAppareil() {
        assertTrue(LecteursSuivis.permis("media_player.pixel_de_ja_vision", "Pixel d'essai · Vision", actif = false, ici = false,
            nomPropre = propre, suivis = emptySet()))
    }

    @Test fun partySurCetEcran() {
        assertTrue(LecteursSuivis.permis("media_player.salon", "Salon", actif = true, ici = true, nomPropre = propre, suivis = emptySet()))
    }

    @Test fun lecteurPiloteDepuisLAppli() {
        assertTrue(LecteursSuivis.permis("media_player.salon", "Salon", actif = true, ici = false, nomPropre = propre,
            suivis = setOf("media_player.salon")))
    }

    @Test fun aucuneLecture() {
        assertFalse(LecteursSuivis.permis("", "", actif = false, ici = false, nomPropre = propre, suivis = setOf("media_player.salon")))
    }
}
