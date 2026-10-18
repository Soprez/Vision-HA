// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.90.2 (retour d'essai) : pochettes préchargées, cache disque borné, nouveaux essais au lieu d'un échec retenu pour de bon. */
class CachePochettesTest {
    @Test fun borne_en_octets_puis_en_nombre_les_plus_anciennes_partent() {
        val f = listOf(Triple("a", 8L, 1L), Triple("b", 8L, 3L), Triple("c", 8L, 2L))
        assertEquals(listOf("a"), CachePochettes.aRetirer(f, maxOctets = 16, maxFichiers = 10))
        assertEquals(listOf("a", "c"), CachePochettes.aRetirer(f, maxOctets = 100, maxFichiers = 1))
        assertTrue(CachePochettes.aRetirer(f, maxOctets = 100, maxFichiers = 10).isEmpty())
    }

    @Test fun noms_de_fichiers_stables_et_sans_adresse() {
        val n = CachePochettes.nom("relais:abc123")
        assertEquals(40, n.length)
        assertEquals(n, CachePochettes.nom("relais:abc123"))
        assertTrue(n.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun nouveaux_essais_rapides_puis_on_attend_un_nouveau_titre() {
        assertEquals(800L, Pochettes.attente(1))
        assertTrue(Pochettes.ATTENTES_MS.sum() < 60_000)  // tout se joue en moins d'une minute
        assertNull(Pochettes.attente(Pochettes.ATTENTES_MS.size + 1))
    }

    @Test fun cle_de_titre_sans_casse() {
        assertEquals(Party.cleTitre("SpongeBob SquarePants ", "Geek Music"), Party.cleTitre("spongebob squarepants", " geek music"))
    }

    /** Campagne (15-10) : on ne décharge qu'à la fermeture du dernier écran Musique, pas à celle d'un écran remplacé. */
    @Test fun dechargement_seulement_au_dernier_ecran_musique() {
        while (!Pochettes.ecranFerme(true)) {}  // compteur à zéro
        Pochettes.ecranOuvert()                 // le lecteur
        Pochettes.ecranOuvert()                 // le suivant, ouvert avant la destruction du premier
        assertTrue(!Pochettes.ecranFerme(true)) // le premier détruit : le second garde ses pochettes
        assertTrue(!Pochettes.ecranFerme(false)) // rotation : l'ancien est détruit sans se fermer pour de bon…
        Pochettes.ecranOuvert()                  // … puis le nouveau naît
        assertTrue(Pochettes.ecranFerme(true))  // le dernier fermé pour de bon : tout se décharge
        assertTrue(Pochettes.ecranFerme(true))  // jamais négatif
    }
}
