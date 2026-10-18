// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.91.0 (retour d'essai) : la musique de la veille — quand la veille demande sa playlist, et quand elle ne lance rien. */
class VeilleAudioTest {
    @Test fun desactiveeParDefautRienNeSeLance() {
        assertFalse(VeilleAudio.decider(choisie = false, nuit = false, autreAppli = false, lecteurJoue = false, dejaDemandee = false))
    }

    @Test fun playlistChoisieElleSeLanceUneSeuleFoisParVeille() {
        assertTrue(VeilleAudio.decider(true, false, false, false, false))
        assertFalse("une seule demande par veille", VeilleAudio.decider(true, false, false, false, true))
    }

    @Test fun leLecteurJoueDejaSaMusiqueContinue() {
        assertFalse(VeilleAudio.decider(true, nuit = false, autreAppli = false, lecteurJoue = true, dejaDemandee = false))
    }

    @Test fun laNuitOuQuandUneAutreAppliJoueRien() {
        assertFalse(VeilleAudio.decider(true, nuit = true, autreAppli = false, lecteurJoue = false, dejaDemandee = false))
        assertFalse(VeilleAudio.decider(true, nuit = false, autreAppli = true, lecteurJoue = false, dejaDemandee = false))
    }

    @Test fun listeDesPlaylistsLueSansRienEnDur() {
        val l = VeilleAudio.garder(listOf(VeilleAudio.Playlist("library://playlist/7", "Disney classique", "x"),
            VeilleAudio.Playlist("", "sans uri"), VeilleAudio.Playlist("library://playlist/8", "")))
        assertEquals(listOf("Disney classique"), l.map { it.nom })
    }
}
