// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soprez.vision.ModeAccueilTele.Effet as E
import org.soprez.vision.ModeAccueilTele.Etape as S

/** 1.93.06 (retour d'essai) : l'accueil de la télé, modes Utilisation et Modification. */
class ModeAccueilTeleTest {
    private var t = 0L
    private val m = ModeAccueilTele { t }

    @Test fun utilisation_par_defaut_et_ok_n_y_fait_rien_de_special() {
        assertFalse(m.modification)
        assertEquals(E.RIEN, m.ok("w:chauffage", true))
        assertEquals(E.RIEN, m.retour())
    }

    @Test fun ok_saisit_deplace_redimensionne_puis_valide() {
        m.entrer()
        assertEquals(E.SAISIR, m.ok("w:chauffage", true))
        assertEquals(S.DEPLACER, m.etape); assertEquals("w:chauffage", m.saisi)
        assertEquals(E.VERS_REDIM, m.ok("w:chauffage", true))
        assertEquals(S.REDIMENSIONNER, m.etape)
        assertEquals(E.VALIDER, m.ok("w:chauffage", true))
        assertEquals(S.CHOISIR, m.etape); assertNull(m.saisi)
        assertTrue("on reste en Modification pour un autre cadre", m.modification)
    }

    @Test fun un_cadre_qui_ne_change_pas_de_taille_se_valide_apres_le_deplacement() {
        m.entrer()
        assertEquals(E.SAISIR, m.ok("com.app", false))
        assertEquals(E.VALIDER, m.ok("com.app", false))
        assertEquals(S.CHOISIR, m.etape)
    }

    @Test fun retour_annule_l_etape_puis_quitte() {
        m.entrer()
        m.ok("w:a", true); m.ok("w:a", true)
        assertEquals(E.ANNULER, m.retour())
        assertEquals(S.CHOISIR, m.etape); assertNull(m.saisi); assertTrue(m.modification)
        assertEquals(E.QUITTER, m.retour())
        assertFalse(m.modification)
    }

    @Test fun ok_sans_cadre_ne_saisit_rien() {
        m.entrer()
        assertEquals(E.RIEN, m.ok(null, false))
        assertEquals(S.CHOISIR, m.etape)
    }

    @Test fun sortie_automatique_apres_une_minute_sans_touche() {
        m.entrer()
        t = ModeAccueilTele.INACTIVITE_MS - 1
        assertFalse(m.expire())
        m.toucher()
        t += ModeAccueilTele.INACTIVITE_MS - 1
        assertFalse("une touche remet le compte à zéro", m.expire())
        t += 1
        assertTrue(m.expire())
        m.quitter()
        assertFalse("hors Modification, rien n'expire", m.expire())
    }

    @Test fun appui_long_sur_ok() {
        assertFalse(ModeAccueilTele.appuiLong(0, 2_000))
        assertFalse(ModeAccueilTele.appuiLong(3, ModeAccueilTele.APPUI_LONG_MS - 1))
        assertTrue(ModeAccueilTele.appuiLong(1, ModeAccueilTele.APPUI_LONG_MS))
    }

    @Test fun deplacer_dans_une_liste() {
        assertEquals(listOf("b", "a", "c"), ModeAccueilTele.deplacer(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("a", "c", "b"), ModeAccueilTele.deplacer(listOf("a", "b", "c"), "c", -1))
        assertEquals("au bord : rien ne bouge", listOf("a", "b", "c"), ModeAccueilTele.deplacer(listOf("a", "b", "c"), "a", -1))
        assertEquals(listOf("a", "b"), ModeAccueilTele.deplacer(listOf("a", "b"), "z", 1))
    }

    @Test fun redimensionner_dans_les_bornes() {
        assertEquals(3 to 1, ModeAccueilTele.redimensionner(2, 1, 1, 0, 1, 1, 4, 2))
        assertEquals(2 to 2, ModeAccueilTele.redimensionner(2, 1, 0, 1, 1, 1, 4, 2))
        assertEquals("pas plus que le maximum", 4 to 2, ModeAccueilTele.redimensionner(4, 2, 1, 1, 1, 1, 4, 2))
        assertEquals("pas moins que le minimum", 2 to 1, ModeAccueilTele.redimensionner(2, 1, -1, -1, 2, 1, 4, 2))
    }
}
