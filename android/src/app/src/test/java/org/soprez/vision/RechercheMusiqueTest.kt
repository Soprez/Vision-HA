// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retours de l'auteur (15-10) : recherche instantanée, suggestions, icônes de source, clavier par défaut. */
class RechercheMusiqueTest {
    @Test fun leTexteNormalise() {
        assertEquals("daft punk", RechercheMusique.normaliser("  daft   punk "))
        assertFalse(RechercheMusique.assezLong(" d "))
        assertTrue(RechercheMusique.assezLong("da"))
    }

    @Test fun memeCleSansCasseNiEspaces() {
        val a = RechercheMusique.cle("Daft  Punk", setOf("track", "album"), emptySet(), false)
        assertEquals(a, RechercheMusique.cle("daft punk ", setOf("album", "track"), emptySet(), false))
        assertFalse(a == RechercheMusique.cle("daft punk", setOf("album", "track"), emptySet(), true))
    }

    @Test fun groupesDansLOrdreDeYouTubeMusic() {
        val l = listOf("album" to 1, "track" to 2, "radio" to 3, "artist" to 4, "track" to 5, "track" to 6, "track" to 7, "track" to 8, "inconnu" to 9)
        val g = RechercheMusique.grouper(l, { it.first }, apercu = true)
        assertEquals(listOf("track", "artist", "album", "radio", "inconnu"), g.map { it.first })
        assertEquals(RechercheMusique.APERCU_PAR_GROUPE, g[0].second.size)
        assertEquals(5, g[0].third)
        assertEquals(5, RechercheMusique.grouper(l, { it.first }, apercu = false)[0].second.size)
    }

    @Test fun recentsSansDoublonLePlusRecentDabord() {
        var r = emptyList<String>()
        for (t in listOf("Angèle", "daft punk", "angèle ", "x", "Stromae")) r = RechercheMusique.ajouterRecent(r, t)
        assertEquals(listOf("Stromae", "angèle", "daft punk"), r)
        assertEquals(10, (1..15).fold(emptyList<String>()) { acc, i -> RechercheMusique.ajouterRecent(acc, "titre $i") }.size)
    }

    @Test fun suggestionsSelonLaSaisie() {
        val recents = listOf("Daft Punk", "Angèle")
        val serveur = listOf("Get Lucky", "daft punk", "Around the World")
        assertEquals(listOf("Daft Punk", "Angèle", "Get Lucky", "Around the World"), RechercheMusique.suggestions(recents, serveur, ""))
        // Début de mot d'abord, puis « contient » ; jamais le texte déjà tapé.
        assertEquals(listOf("Around the World"), RechercheMusique.suggestions(recents, serveur, "wor"))
        assertEquals(emptyList<String>(), RechercheMusique.suggestions(listOf("daft"), emptyList(), "Daft"))
    }

    @Test fun actionsSousLeTitreQuandEllesNeTiennentPas() {
        // Télé, colonne des résultats de 860 px : trois boutons allemands (≈ 1100 px) ne tiennent pas ; un seul, si.
        assertFalse(RechercheMusique.actionsSurLaLigne(860, listOf(420, 470, 90), 90))
        assertTrue(RechercheMusique.actionsSurLaLigne(860, listOf(200), 90))
        assertFalse(RechercheMusique.actionsSurLaLigne(0, listOf(10), 0))
        // Téléphone 360 dp (carte ≈ 888 px à 3x) : « Ensuite » et « ⋮ » tiennent à côté du titre.
        assertTrue(RechercheMusique.actionsSurLaLigne(888, listOf(306, 126), 180))
    }

    @Test fun memoireCourte() {
        val m = RechercheMusique.Memoire<String>(max = 2, dureeMs = 1000)
        m.garder("a", "A", 0); m.garder("b", "B", 10); m.garder("c", "C", 20)
        assertNull("le plus ancien sort", m.lire("a", 30))
        assertEquals("B", m.lire("b", 30))
        assertNull("périmé", m.lire("c", 2000))
    }

    @Test fun iconesDeSource() {
        val accent = 0x123456
        assertEquals(IconeSource.Forme.LECTURE, IconeSource.pour("ytmusic", accent).first)
        assertEquals(IconeSource.Forme.ONDES, IconeSource.pour("spotify", accent).first)
        assertEquals(IconeSource.Forme.LIVRES to accent, IconeSource.pour("library", accent))
        assertEquals(IconeSource.Forme.LIVRES, IconeSource.pour("", accent).first)
        assertEquals(IconeSource.Forme.DOSSIER, IconeSource.pour("filesystem_local", accent).first)
        assertEquals(IconeSource.Forme.NOTE to accent, IconeSource.pour("plex", accent))
    }

    @Test fun clavierRecommandeSelonLAppareil() {
        assertEquals(ReglagesMusique.CLAVIER_VISION, ReglagesMusique.clavierParDefaut(tele = true))
        assertEquals(ReglagesMusique.CLAVIER_ANDROID, ReglagesMusique.clavierParDefaut(tele = false))
        assertEquals(28, ClavierTele.LETTRES.size)
        assertEquals(ClavierTele.LETTRES.size, ClavierTele.SIGNES.size)
    }
}
