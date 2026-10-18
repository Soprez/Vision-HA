package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.91.2 (retour d'essai) : le défilement, option de chaque widget — défaut, lecture d'un widget d'avant, aller-retour. */
class WidgetDefilementTest {
    @Test fun parDefautActiveEtMoyenne() {
        val r = WidgetsHa.Reglage("a", listOf("sensor.x"), WidgetsHa.TUILE, 2, 2)
        assertTrue(r.defilement)
        assertEquals(WidgetsHa.HAUTEUR_MOYENNE, r.hauteur)
    }

    @Test fun unWidgetDAvantDefile() {
        // Réglage écrit avant la 1.91.2 (sans les deux champs).
        val ancien = "{\"id\":\"a\",\"entites\":[\"sensor.x\"],\"style\":\"tuile\",\"w\":2,\"h\":2}"
        val r = WidgetsHa.decoder(ancien)!!
        assertTrue(r.defilement)
        assertEquals(WidgetsHa.HAUTEUR_MOYENNE, r.hauteur)
    }

    @Test fun allerRetour() {
        val r = WidgetsHa.Reglage("a", listOf("sensor.x"), WidgetsHa.TUILE, 2, 2, defilement = false, hauteur = WidgetsHa.HAUTEUR_GRANDE)
        val l = WidgetsHa.decoder(WidgetsHa.encoder(r))!!
        assertFalse(l.defilement)
        assertEquals(WidgetsHa.HAUTEUR_GRANDE, l.hauteur)
    }

    @Test fun hauteurs() {
        assertEquals(3, WidgetsHa.lignesMax(WidgetsHa.HAUTEUR_COMPACTE))
        assertEquals(6, WidgetsHa.lignesMax(WidgetsHa.HAUTEUR_MOYENNE))
        assertEquals(10, WidgetsHa.lignesMax(WidgetsHa.HAUTEUR_GRANDE))
    }
}
