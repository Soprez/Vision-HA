// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.90.1 (l'auteur : « +24 ») : le nombre de lignes du détail d'une tuile suit la place réelle, « +N » compris. */
class ListeDetailTest {
    private val h = List(27) { 20 }

    @Test fun autant_de_lignes_que_la_place() {
        // 200 px : 10 lignes de 20 tiendraient, mais « +N » (18 px) doit tenir aussi : 9 lignes.
        assertEquals(9, ListeDetail.combien(h, 0, 27, 18, 200))
        // Plus de place, plus de lignes : pas de constante.
        assertEquals(19, ListeDetail.combien(h, 0, 27, 18, 400))
    }

    @Test fun tout_tient_sans_plus() {
        assertEquals(27, ListeDetail.combien(h, 0, 27, 18, 27 * 20))
    }

    @Test fun lignes_non_recues_comptent_dans_plus() {
        // 27 reçues mais 40 au total côté serveur : même si les 27 tiennent, « +13 » doit avoir sa place.
        assertEquals(26, ListeDetail.combien(h, 0, 40, 18, 27 * 20))
    }

    @Test fun defilement_tele() {
        // À partir de la ligne 20 : il en reste 7, qui tiennent avec la place.
        assertEquals(7, ListeDetail.combien(h, 20, 27, 18, 200))
        assertEquals(0, ListeDetail.combien(h, 0, 27, 18, 10))
    }
}
