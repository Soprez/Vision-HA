// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.93.06 (l'auteur, retour 40, TV du salon) : pendant la lecture, au bout d'un certain temps, le bouton « Pause » redevenait
 * « Lecture » alors que la musique jouait. Mesuré dans Home Assistant (17-10) : « Fine Day Anthem » (194 s) parti à
 * 15:38:01, Music Assistant affichait déjà « BAM BAM » à 15:39:05 ; « Body Heat » (201 s) remplacé 64 s après son début
 * aussi. Cause : « décodeur prêt » (STMd) partait dès le flux entièrement reçu (≈ 1 min), et Music Assistant passait au
 * titre suivant ; son titre, sa position et son état dérivaient ensuite.
 */
class EtatLecteurDecaleTest {
    @Test fun stmd_pas_une_minute_apres_le_debut_quand_la_duree_est_connue() {
        // Le cas mesuré : 194 s, flux reçu à 60 s — plus de STMd à ce moment-là.
        assertFalse(LecteurSlim.decodeurPretMaintenant(false, -1, 64_000, fluxRecu = true, dureeHaMs = 194_000))
        assertFalse(LecteurSlim.decodeurPretMaintenant(false, -1, 178_000, fluxRecu = true, dureeHaMs = 194_000))
        // 15 s avant la fin réelle : Music Assistant a le temps de préparer la suite.
        assertTrue(LecteurSlim.decodeurPretMaintenant(false, -1, 179_000, fluxRecu = true, dureeHaMs = 194_000))
        // Fin dépassée (durée de HA un peu courte) : toujours dit, une seule fois.
        assertTrue(LecteurSlim.decodeurPretMaintenant(false, -1, 200_000, fluxRecu = false, dureeHaMs = 194_000))
        assertFalse(LecteurSlim.decodeurPretMaintenant(true, -1, 200_000, fluxRecu = true, dureeHaMs = 194_000))
        // Durée inconnue de HA : la règle d'avant (flux reçu) reste le secours, pour que la file ne s'arrête pas.
        assertTrue(LecteurSlim.decodeurPretMaintenant(false, -1, 64_000, fluxRecu = true))
    }

    /**
     * Le cas en boucle (TV du salon, 17-10, 18:01–18:06, journal de la TV) : HA affichait « Burning Ambition » (position
     * figée à 5 s, repeat: all) pendant que le son rejouait « Bad Times » (168 s) en boucle. À chaque fin : reset à
     * 18:01:00, 18:03:50, 18:06:42, puis ~5 s plus tard strm f/q/s/u — la fin sans suite prête (attente de 4 s, demande
     * « suite » à HA de la 1.93.04), et Music Assistant, déjà passé au titre suivant par un STMd envoyé à ~60 s, relançait
     * l'ancien flux. Avec la durée connue, STMd part une seule fois, avant la fin et jamais à 60 s : la suite est prête à
     * temps, la fin sans suite (et la relance en boucle) ne se produit plus.
     */
    @Test fun boucle_bad_times_la_suite_est_demandee_a_temps_une_seule_fois() {
        val duree = 168_000L
        fun premierStmd(dureeHa: Long): Int? {
            var deja = false
            var premier: Int? = null
            for (pos in 0..168_000 step 1_000) {
                val fluxRecu = pos >= 60_000  // le flux entier est reçu vers une minute
                if (LecteurSlim.decodeurPretMaintenant(deja, -1, pos, fluxRecu, dureeHa)) { deja = true; if (premier == null) premier = pos }
            }
            return premier
        }
        // Avant (sans durée) : STMd à 60 s — Music Assistant passe au titre suivant 108 s trop tôt (le défaut mesuré).
        assertEquals(60_000, premierStmd(0L))
        // Après : une seule fois, 15 s avant la fin réelle (153 s), assez tôt pour que la suite soit envoyée avant la fin.
        val apres = premierStmd(duree)!!
        assertEquals(153_000, apres)
        assertTrue("la suite doit être demandée avant la fin du titre", apres < duree - 5_000)
    }

    /**
     * La boucle vue dans Music Assistant (17-10, 18:20, music_assistant.get_queue sur la file 02:00:00:00:00:0a) :
     * 29 titres, repeat all, current_index bloqué à 2 (« Burning Ambition », temps écoulé figé à 5 s) alors que le son
     * jouait « Bad Times » (index 3) ; chaque suite redonnait « Bad Times », les ajouts (index 4 et +) ne passaient jamais.
     * Journal de la télé : chaque relance de MA est « strm f, q, s (démarrage 2), u » — chargé sans démarrer, puis « u ».
     * Vision répondait « STMr » (reprise) à ce « u » : MA ne voyait jamais le titre COMMENCER et gardait son index.
     * Squeezelite (la référence de SlimProto) répond « STMs » (titre démarré).
     */
    @Test fun relance_de_music_assistant_le_titre_commence_stms() {
        // Chargé sans démarrage (démarrage 2) puis « u » : le titre commence.
        assertEquals("STMs", LecteurSlim.reponseAuDemarrage(chargeSansDemarrage = true, enPause = false))
        // Une vraie pause qui reprend : reprise.
        assertEquals("STMr", LecteurSlim.reponseAuDemarrage(chargeSansDemarrage = false, enPause = true))
        assertEquals("STMr", LecteurSlim.reponseAuDemarrage(chargeSansDemarrage = false, enPause = false))
    }

    @Test fun le_bouton_suit_le_lecteur_reel_de_cet_appareil() {
        // HA croit la lecture arrêtée (ou en pause) mais le lecteur Classique de cet appareil joue : « Pause ».
        assertEquals("playing", Party.etatAffiche("idle", lecteurLocal = true, localJoue = true, demandeEnCours = false))
        assertEquals("playing", Party.etatAffiche("paused", lecteurLocal = true, localJoue = true, demandeEnCours = false))
        // Une pause demandée ici, pas encore confirmée : on ne la contredit pas.
        assertEquals("paused", Party.etatAffiche("paused", lecteurLocal = true, localJoue = true, demandeEnCours = true))
        // Un autre lecteur (enceinte, Chromecast), ou le lecteur local arrêté : l'état de HA.
        assertEquals("idle", Party.etatAffiche("idle", lecteurLocal = false, localJoue = true, demandeEnCours = false))
        assertEquals("paused", Party.etatAffiche("paused", lecteurLocal = true, localJoue = false, demandeEnCours = false))
        assertEquals("playing", Party.etatAffiche("playing", lecteurLocal = true, localJoue = false, demandeEnCours = false))
    }
}
