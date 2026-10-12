// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Client Sendspin natif, lot 4 : métadonnées, position et commandes venues de Music Assistant (`server/state`),
 * telles qu'aiosendspin 9.0.0 les envoie (progress en ms, vitesse × 1000, timestamp en µs du serveur).
 */
class SendspinEtatTest {
    private val decalage = 1_000_000_000L  // serveur en avance de 1000 s sur l'appareil
    private fun versLocal(s: Long) = s - decalage

    @Test fun titre_position_et_commandes() {
        val p = Json.objet("""{"metadata":{"timestamp":${decalage + 5_000_000},"title":"Titre","artist":"Artiste","album":"Album",
            "artwork_url":"http://x","progress":{"track_progress":30000,"track_duration":200000,"playback_speed":1000}},
            "controller":{"supported_commands":["play","pause","next","volume","mute"]}}""")!!
        val e = SendspinClient.etatServeur(SendspinClient.Etat(), p, ::versLocal)
        assertEquals("Titre", e.titre); assertEquals("Artiste", e.artiste); assertEquals("Album", e.album)
        assertEquals(200_000L, e.dureeMs)
        // 2 s après l'horodatage de MA : 32 s.
        assertEquals(32_000L, SendspinClient.position(e, 7_000_000))
        // Jamais au-delà de la durée.
        assertEquals(200_000L, SendspinClient.position(e, 500_000_000))
        assertEquals(listOf("play", "pause", "next", "volume", "mute"), e.commandes)
    }

    @Test fun pause_et_champs_absents_gardes() {
        val e1 = SendspinClient.etatServeur(SendspinClient.Etat(), Json.objet("""{"metadata":{"timestamp":${decalage},"title":"A",
            "progress":{"track_progress":1000,"track_duration":0,"playback_speed":0}}}""")!!, ::versLocal)
        // En pause (vitesse 0) la position ne bouge pas ; durée 0 = flux sans fin.
        assertEquals(1_000L, SendspinClient.position(e1, 60_000_000)); assertNull(e1.dureeMs)
        // Un message sans titre garde le titre ; progress null efface la position.
        val e2 = SendspinClient.etatServeur(e1, Json.objet("""{"metadata":{"timestamp":${decalage},"progress":null}}""")!!, ::versLocal)
        assertEquals("A", e2.titre); assertNull(SendspinClient.position(e2, 0))
    }
}
