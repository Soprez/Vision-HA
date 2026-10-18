// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Le mode « Liste alphabétique » (téléphone) : lettres, tri, index et loupe, et la disposition de la télé. */
class ListeAlphaTest {

    @Test fun lettre_replie_les_accents_et_range_chiffres_et_symboles_sous_diese() {
        assertEquals('E', ListeAlpha.lettre("Écoute"))
        assertEquals('O', ListeAlpha.lettre("Œuvres"))
        assertEquals('A', ListeAlpha.lettre("  äpfel"))
        assertEquals('S', ListeAlpha.lettre("ßtadt"))
        assertEquals('#', ListeAlpha.lettre("2048"))
        assertEquals('#', ListeAlpha.lettre("Ωmega"))
        assertEquals('#', ListeAlpha.lettre(""))
        assertEquals('Y', ListeAlpha.lettre("«YouTube»"))
    }

    @Test fun trier_par_lettre_puis_selon_la_langue_diese_a_la_fin() {
        val e = ListeAlpha.trier(listOf("a" to "zoom", "b" to "3D", "c" to "Écoute", "d" to "easy", "e" to "Agenda"), Locale.FRENCH)
        assertEquals(listOf("Agenda", "easy", "Écoute", "zoom", "3D"), e.map { it.nom })
        assertEquals(mapOf('A' to 0, 'E' to 1, 'Z' to 3, '#' to 4), ListeAlpha.premiers(e))
        assertEquals(setOf('A', 'E', 'Z', '#'), ListeAlpha.presentes(e))
    }

    @Test fun indexA_borne() {
        val n = ListeAlpha.LETTRES.size
        assertEquals(0, ListeAlpha.indexA(-5f, 270f))
        assertEquals(n - 1, ListeAlpha.indexA(9999f, 270f))
        assertEquals(1, ListeAlpha.indexA(15f, 270f))
        assertEquals(0, ListeAlpha.indexA(10f, 0f))
    }

    @Test fun loupe_decroit_avec_la_distance() {
        assertEquals(1.9f, ListeAlpha.echelle(5, 5), 0f)
        assertEquals(1.5f, ListeAlpha.echelle(4, 5), 0f)
        assertEquals(1.2f, ListeAlpha.echelle(7, 5), 0f)
        assertEquals(1f, ListeAlpha.echelle(9, 5), 0f)
        assertEquals(1f, ListeAlpha.echelle(5, null), 0f)
        assertEquals(0f, ListeAlpha.decalage(5, null), 0f)
        assertEquals(1f, ListeAlpha.decalage(5, 5), 0f)
    }

    @Test fun cible_et_voisine_sautent_les_lettres_grisees() {
        val p = setOf('B', 'M', '#')
        assertEquals('M', ListeAlpha.cible('M', p))
        assertEquals('M', ListeAlpha.cible('K', p))
        assertEquals('B', ListeAlpha.cible('C', p))
        assertNull(ListeAlpha.cible('A', emptySet()))
        assertEquals('M', ListeAlpha.voisine('B', 1, p))
        assertEquals('#', ListeAlpha.voisine('M', 1, p))
        assertNull(ListeAlpha.voisine('#', 1, p))
        assertEquals('B', ListeAlpha.voisine('M', -1, p))
        assertNull(ListeAlpha.voisine('B', -1, p))
    }

    @Test fun zones_de_la_tele_selon_la_colonne() {
        assertEquals(listOf(DispositionAccueil.ZONE_D), DispositionAccueil.zonesPossibles(DispositionAccueil.COLONNE_GAUCHE))
        assertEquals(listOf(DispositionAccueil.ZONE_G), DispositionAccueil.zonesPossibles(DispositionAccueil.COLONNE_DROITE))
        assertEquals(listOf(DispositionAccueil.ZONE_G, DispositionAccueil.ZONE_D), DispositionAccueil.zonesPossibles(DispositionAccueil.COLONNE_CENTRE))
        assertEquals(false, DispositionAccueil.zoneVisible(listOf("meteo"), false))
        assertEquals(false, DispositionAccueil.zoneVisible(emptyList(), true))
        assertEquals(true, DispositionAccueil.zoneVisible(listOf("meteo"), true))
    }
}
