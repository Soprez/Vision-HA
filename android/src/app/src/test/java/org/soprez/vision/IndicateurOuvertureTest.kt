// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** l'auteur (15/10) : l'indicateur des ouvertures de la veille — défauts sûrs, « seulement ouvert », couleurs, état inconnu. */
class IndicateurOuvertureTest {
    private val I = IndicateurOuverture

    @Test fun defauts_surs() {
        val r = IndicateurOuverture.Reglages()
        assertEquals(I.PASTILLE, r.style)
        assertEquals(I.ROUGE, r.ouvert)
        assertEquals(I.VERT, r.ferme)
        assertFalse(r.seulementOuvert)
        // Par défaut, ouvert, fermé et inconnu ont tous un indicateur.
        for (on in listOf(true, false, null)) assertTrue("$on", I.montrer(r, on))
    }

    @Test fun seulement_ouvert_et_aucun() {
        val r = IndicateurOuverture.Reglages(seulementOuvert = true)
        assertTrue(I.montrer(r, true))
        assertFalse(I.montrer(r, false))
        assertTrue("un capteur muet garde son indicateur neutre", I.montrer(r, null))
        for (on in listOf(true, false, null)) assertFalse(I.montrer(IndicateurOuverture.Reglages(style = I.AUCUN), on))
    }

    @Test fun couleurs() {
        val r = IndicateurOuverture.Reglages(ouvert = I.ORANGE, ferme = I.GRIS)
        assertEquals(I.ORANGE, I.couleurEtat(r, true))
        assertEquals(I.GRIS, I.couleurEtat(r, false))
        assertNull("inconnu : jamais la couleur « fermé »", I.couleurEtat(r, null))
        val accent = 0xFF123456.toInt(); val gris = 0xFF777777.toInt(); val neutre = 0xFFC48A1E.toInt()
        assertEquals(accent, I.argb(I.ACCENT, accent, gris, neutre))
        assertEquals(gris, I.argb(I.GRIS, accent, gris, neutre))
        assertEquals(neutre, I.argb(null, accent, gris, neutre))
        assertEquals(6, I.COULEURS.toSet().size)
        assertEquals(4, I.STYLES.toSet().size)
    }
}
