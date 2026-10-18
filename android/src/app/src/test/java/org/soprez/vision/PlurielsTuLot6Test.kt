// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lot 6 (A9 / APP-31) : pluriels par quantité dans les six langues, « tu » partout en français. */
class PlurielsTuLot6Test {
    @After fun langue() { TrJvm.langue = "fr" }

    /** Le pluriel tel qu'Android l'affiche (les guillemets qui gardent la double espace ne se voient pas). */
    private fun p(id: Int, n: Int, vararg a: Any) = Tr.p(id, n, *a).removeSurrounding("\"")

    @Test fun pluriels() {
        assertEquals("Salon  · 1/1 carte", p(R.plurals.lot6_nom_cartes, 1, "Salon", 1, 1))
        assertEquals("Salon  · 2/3 cartes", p(R.plurals.lot6_nom_cartes, 3, "Salon", 2, 3))
        assertEquals("1 sur 3 accordée", Tr.p(R.plurals.lot6_n_sur_m_accordees, 1, 1, 3))
        assertEquals("2 sur 3 accordées", Tr.p(R.plurals.lot6_n_sur_m_accordees, 2, 2, 3))
        TrJvm.langue = "en"
        assertEquals("Salon  · 1/1 card", p(R.plurals.lot6_nom_cartes, 1, "Salon", 1, 1))
        assertEquals("Salon  · 0/2 cards", p(R.plurals.lot6_nom_cartes, 2, "Salon", 0, 2))
        // Chaque pluriel du lot 6 existe dans les six langues, avec ses deux formes.
        for (l in listOf("en", "fr", "de", "es", "it", "nl")) {
            val p = TrJvm.dossier(TrJvm.dossierDe(l)).pluriels
            for (k in listOf("lot6_nom_cartes", "lot6_n_sur_m_accordees")) assertTrue("$l $k", p[k]?.keys == setOf("one", "other"))
        }
    }

    /** Le français tutoie partout : plus d'impératif ni de pronom en « vous ». */
    @Test fun tutoiement() {
        val chaines = TrJvm.dossier("values-fr").chaines
        val vous = Regex("""\b(vous|votre|vos)\b|\b\w+(ez)\b""", RegexOption.IGNORE_CASE)
        val permis = setOf("chez", "assez", "rendez", "nez")
        // l'auteur (14/10) : la carte de don garde mot pour mot « ☕ Payez-moi un café » (formule de Buy Me a Coffee).
        val exceptions = setOf("soutien_carte")
        val fautes = chaines.filter { (k, v) -> k !in exceptions && vous.findAll(v).any { it.value.lowercase() !in permis } }.keys
        assertTrue("Vouvoiement : $fautes", fautes.isEmpty())
    }
}
