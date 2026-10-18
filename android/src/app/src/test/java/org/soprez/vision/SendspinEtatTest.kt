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

    private fun progres(titre: String, pos: Long, vitesse: Int, ts: Long = decalage) = Json.objet(
        """{"metadata":{"timestamp":$ts,"title":"$titre","progress":{"track_progress":$pos,"track_duration":104000,"playback_speed":$vitesse}}}""")!!

    /** Banc Musique (2026-10-12, mesuré contre MA 2.10.6) : pause à 12,959 s, 8,7 s de pause, MA annonce 20,711 s. */
    @Test fun la_pause_ne_fait_pas_avancer_la_barre() {
        var e = SendspinClient.etatServeur(SendspinClient.Etat(), progres("A", 3, 1000), ::versLocal, 0L)
        e = SendspinClient.etatServeur(e, progres("A", 12_959, 0), ::versLocal, 10_000_000L)
        e = SendspinClient.etatServeur(e, progres("A", 20_711, 1000), ::versLocal, 18_700_000L)
        assertEquals(12_959L, e.positionMs)
        // L'écart reste retiré pour les annonces suivantes du même titre…
        e = SendspinClient.etatServeur(e, progres("A", 40_711, 1000), ::versLocal, 38_700_000L)
        assertEquals(32_959L, e.positionMs)
        // Fin du titre annoncée par MA (sa durée, à l'arrêt) : telle quelle.
        assertEquals(104_000L, SendspinClient.etatServeur(e, progres("A", 104_000, 0), ::versLocal, 99_000_000L).positionMs)
        // … et disparaît au titre suivant.
        e = SendspinClient.etatServeur(e, progres("B", 0, 1000), ::versLocal, 50_000_000L)
        assertEquals(0L, e.positionMs); assertEquals(0L, e.decalageMs)
    }

    @Test fun une_reprise_juste_ne_change_rien() {
        var e = SendspinClient.etatServeur(SendspinClient.Etat(), progres("A", 12_959, 0), ::versLocal, 10_000_000L)
        e = SendspinClient.etatServeur(e, progres("A", 13_000, 1000), ::versLocal, 18_700_000L)
        assertEquals(13_000L, e.positionMs)
        // Un vrai saut (« seek ») pendant la pause, sans rapport avec sa durée : gardé tel quel.
        e = SendspinClient.etatServeur(SendspinClient.Etat(), progres("A", 12_959, 0), ::versLocal, 10_000_000L)
        e = SendspinClient.etatServeur(e, progres("A", 60_000, 1000), ::versLocal, 18_700_000L)
        assertEquals(60_000L, e.positionMs)
    }

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
