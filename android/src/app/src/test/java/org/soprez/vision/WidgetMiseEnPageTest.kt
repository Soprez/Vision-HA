// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.91.3 (l'auteur, version suivante) : la mise en page des widgets — alignements, taille de la valeur (« remplir » ou
 * S / M / L / XL), titre et icône ; une seule configuration, dans Home Assistant ; défaut : grande et centrée.
 */
class WidgetMiseEnPageTest {
    private val defaut = WidgetsHa.MiseEnPage()

    @Test fun defaut_grande_et_centree() {
        assertEquals(WidgetsHa.CENTRE, defaut.alignH); assertEquals(WidgetsHa.CENTRE, defaut.alignV)
        assertEquals(WidgetsHa.REMPLIR, defaut.taille); assertTrue(defaut.titre && defaut.icone)
        // Rien reçu de HA (version d'avant) : le défaut.
        assertEquals(defaut, WidgetsHa.miseEnPageDe(emptyMap()))
        val e = WidgetsHa.entiteDe(mapOf("entity_id" to "sensor.t", "nom" to "Température"))!!
        assertEquals(defaut, e.miseEnPage)
    }

    @Test fun lue_depuis_ha() {
        val m = WidgetsHa.miseEnPageDe(mapOf("align_h" to "droite", "align_v" to "bas", "taille" to "xl", "titre" to false, "icone" to false))
        assertEquals(WidgetsHa.MiseEnPage(WidgetsHa.DROITE, WidgetsHa.BAS, "xl", false, false), m)
        // Valeurs inconnues ou mal typées : le défaut, clé par clé.
        assertEquals(defaut.copy(alignV = WidgetsHa.HAUT), WidgetsHa.miseEnPageDe(mapOf("align_h" to "milieu", "align_v" to "haut", "taille" to "xxl", "titre" to "non")))
        // Avec l'entité, et l'aller-retour vers HA.
        val e = WidgetsHa.entiteDe(mapOf("entity_id" to "sensor.t", "mise_en_page" to mapOf("taille" to "s", "align_h" to "gauche")))!!
        assertEquals(defaut.copy(taille = "s", alignH = WidgetsHa.GAUCHE), e.miseEnPage)
        assertEquals(m, WidgetsHa.miseEnPageDe(WidgetsHa.versHa(m)))
    }

    @Test fun apercu_de_l_editeur_jamais_enregistre() {
        val e = WidgetsHa.entiteDe(mapOf("entity_id" to "sensor.t", "mise_en_page" to mapOf("taille" to "m")))!!
        val r = WidgetsHa.Reglage("w1", listOf("sensor.t"), WidgetsHa.TUILE, 2, 2)
        assertEquals("celle de HA", "m", WidgetsHa.miseEnPage(r, e).taille)
        val apercu = r.copy(apercu = defaut.copy(taille = "xl"))
        assertEquals("l'aperçu de l'éditeur passe avant", "xl", WidgetsHa.miseEnPage(apercu, e).taille)
        // Le réglage local ne garde jamais la mise en page : elle vit dans HA.
        assertNull(WidgetsHa.decoder(WidgetsHa.encoder(apercu))!!.apercu)
    }

    @Test fun styles_concernes() {
        // Les styles à titre et valeur suivent la mise en page ; les autres gardent la leur.
        assertEquals(listOf(WidgetsHa.TUILE, WidgetsHa.REGLAGE, WidgetsHa.INTERRUPTEUR, WidgetsHa.JAUGE, WidgetsHa.OUVERTURE), WidgetsHa.STYLES_MISE_EN_PAGE)
        assertTrue(WidgetsHa.BOUTON !in WidgetsHa.STYLES_MISE_EN_PAGE)
    }

    @Test fun tailles_fixes() {
        val t = listOf("s", "m", "l", "xl").map { WidgetsHa.tailleSp(it, false) }
        assertTrue("S < M < L < XL : $t", t.zipWithNext().all { (a, b) -> a < b })
        assertTrue("un petit widget un cran plus bas", WidgetsHa.tailleSp("xl", true) < WidgetsHa.tailleSp("xl", false))
    }

    @Test fun remplir_la_plus_grande_qui_tient() {
        // Texte de 200 px de large et 120 px de haut à 100 px : dans 400 × 150, la hauteur décide (125).
        assertEquals(125f, WidgetsHa.tailleQuiTient(200f, 120f, 400f, 150f, 10f, 500f), 0.01f)
        // Dans 300 × 600, la largeur décide (150).
        assertEquals(150f, WidgetsHa.tailleQuiTient(200f, 120f, 300f, 600f, 10f, 500f), 0.01f)
        // Bornes.
        assertEquals(500f, WidgetsHa.tailleQuiTient(10f, 10f, 4000f, 4000f, 10f, 500f), 0.01f)
        assertEquals(10f, WidgetsHa.tailleQuiTient(2000f, 120f, 20f, 600f, 10f, 500f), 0.01f)
        assertEquals("pas encore de place", 10f, WidgetsHa.tailleQuiTient(200f, 120f, 0f, 0f, 10f, 500f), 0.01f)
    }
}
