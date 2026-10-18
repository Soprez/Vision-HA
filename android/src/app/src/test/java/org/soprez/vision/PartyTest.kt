// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.81.0 (point 14 bis) : Mode Party et widget Lecteur, la logique sans Android. */
class PartyTest {
    private fun m(etat: String = "playing", pos: Double? = 60.0, ts: Double? = 1000.0, duree: Double? = 200.0, titre: String = "") =
        Party.Maintenant(etat, titre, "", "", "", 0.3, duree, pos, ts, "")

    @Test fun laPositionAvanceSeulePendantLaLecture() {
        assertEquals(70.0, Party.position(m(), 1010.0)!!, 0.001)
        assertEquals(60.0, Party.position(m(etat = "paused"), 1010.0)!!, 0.001)
        assertEquals(200.0, Party.position(m(), 5000.0)!!, 0.001)  // jamais au-delà du morceau
        assertNull(Party.position(m(pos = null), 1010.0))
    }

    @Test fun lesTempsSeLisent() {
        assertEquals("1:24", Party.temps(84.0))
        assertEquals("1:02:05", Party.temps(3725.0))
        assertEquals("", Party.temps(null))
    }

    @Test fun rienEnLectureSeDitEnClair() {
        assertEquals("Enceinte indisponible", Party.titreAffiche(m(etat = "unavailable")))
        assertEquals("Rien en lecture", Party.titreAffiche(m(etat = "idle")))
        assertEquals("Un titre", Party.titreAffiche(m(titre = "Un titre")))
    }

    @Test fun leVolumeResteBorne() {
        assertEquals(0.35, Party.volumeApres(0.3, 1), 0.0001)
        assertEquals(1.0, Party.volumeApres(0.98, 1), 0.0001)
        assertEquals(0.0, Party.volumeApres(0.02, -1), 0.0001)
    }

    @Test fun lesEchecsSontEnFrancais() {
        assertEquals("Réservé aux décideurs.", Party.messageErreur(403, null))
        assertEquals("Home Assistant ne répond pas : rien n'a changé, réessaie.", Party.messageErreur(0, null))
    }

    private val salon = WidgetsHa.Entite("media_player.salon", "Salon", "media_player", "", "", "", "playing",
        mapOf("media_title" to "Titre", "media_artist" to "Artiste", "volume_level" to 0.4, "pochette" to "ab12"),
        listOf("media_play_pause", "volume_set"), emptyList())

    @Test fun leLecteurNeProposeQueCeQuiEstPermis() {
        assertEquals("media_play_pause", WidgetsHa.serviceLecteur(salon, "lecture"))
        assertNull(WidgetsHa.serviceLecteur(salon, "suivant"))  // pas dans la liste blanche
        assertEquals(0.45, WidgetsHa.volumeApres(salon, 0.4, 1)!!, 0.0001)
        assertNull(WidgetsHa.volumeApres(salon.copy(actions = listOf("media_play_pause")), 0.4, 1))
    }

    @Test fun leLecteurSuitLeClicToutDeSuite() {
        val st = WidgetsHa.EtatEntite("playing", salon.attributs)
        val l = WidgetsHa.lecture(st)
        assertEquals("Titre", l.titre); assertEquals("Artiste", l.artiste); assertEquals("ab12", l.pochette)
        assertEquals("paused", WidgetsHa.etatApres(st, "media_play_pause").etat)
        assertEquals(0.6, WidgetsHa.etatApres(st, "volume_set", 0.6).attributs["volume_level"])
    }

    @Test fun unMediaPlayerProposeLeLecteurDAbord() {
        assertEquals(WidgetsHa.LECTEUR, WidgetsHa.stylesParDomaine("media_player", "", "playing").first())
        assertEquals(4 to 2, WidgetsHa.taillesPermises(WidgetsHa.LECTEUR).first())
    }
    /** 1.87.1 : une pochette Music Assistant passe par Home Assistant (référence), jamais en http direct. */
    @Test fun lesPochettesPassentParHomeAssistant() {
        assertEquals("relais:3f9a0c", Party.imagePour("3f9a0c", "http://10.1.1.11:8095/imageproxy?path=x"))
        assertEquals("", Party.imagePour("", "http://10.1.1.11:8095/imageproxy?path=x"))  // jamais http
        assertEquals("https://i.scdn.co/image/ab", Party.imagePour("", "https://i.scdn.co/image/ab"))
        assertEquals("", Party.imagePour("../etc", ""))  // une référence se limite à [A-Za-z0-9_-]
        assertEquals("", Party.imagePour("a".repeat(65), ""))
    }

    /** 1.87.2 (retour d'essai) : pas de volume pour une télé qui joue elle-même (« · Vision ») ; une autre enceinte le garde. */
    @Test fun leVolumeDUneTeleVisionEstCeluiDeLaTelecommande() {
        assertFalse(Party.volumeReglable("Télé du bureau · Vision"))
        assertFalse(Party.volumeReglable("Téléviseur du salon · Vision "))
        assertTrue(Party.volumeReglable("Salon"))
        assertTrue(Party.volumeReglable("Toute la maison (groupe)"))
        assertTrue(Party.volumeReglable(""))
        // Client Sendspin natif (règles du projet) : un groupe a toujours son volume de groupe, même mené par une télé
        // « · Vision » ; le détail par enceinte seulement s'il y a au moins une enceinte réglable (pas une « · Vision »).
        val seul = Party.Maintenant("playing", "T", "", "", "", 1.0, null, null, null, "")
        val groupe = seul.copy(volume = 0.4, groupe = true, membres = listOf(Party.Membre("media_player.tele", "Télé · Vision", null),
            Party.Membre("media_player.sonos", "Sonos", 0.3)))
        assertFalse(Party.volumeReglable("Télé · Vision", seul))
        assertTrue(Party.volumeReglable("Télé · Vision", groupe))
        assertTrue(Party.detailVolumes(groupe))
        assertFalse(Party.detailVolumes(groupe.copy(membres = listOf(Party.Membre("media_player.tele", "Télé · Vision", null)))))
        assertFalse(Party.detailVolumes(seul))
    }
}
