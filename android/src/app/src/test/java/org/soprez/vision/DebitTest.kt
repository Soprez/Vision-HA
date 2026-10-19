// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.93.06 (photo de l'auteur, widgets de la box de la veille) : « 1,7 Mbit/s » en réception contre « 0,1 MB/s » en envoi.
 * Un débit réseau se montre toujours en Mbit/s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class DebitTest {
    @Test fun les_debits_passent_en_mbit_s() {
        assertEquals(0.8, Debit.normaliser(0.1, "MB/s").first, 1e-9)
        assertEquals(Debit.MBITS, Debit.normaliser(0.1, "MB/s").second)
        assertEquals(1.7, Debit.normaliser(1700.0, "kbit/s").first, 1e-9)
        assertEquals(322.0, Debit.normaliser(322.0, "Mbit/s").first, 1e-9)
        assertEquals(8.388608, Debit.normaliser(1.0, "MiB/s").first, 1e-9)
        assertEquals(0.08, Debit.normaliser(10.0, "ko/s").first, 1e-9)
        // Ce qui n'est pas un débit ne bouge pas.
        assertEquals(21.5 to "°C", Debit.normaliser(21.5, "°C"))
        assertEquals(3.0 to "MB", Debit.normaliser(3.0, "MB"))
        assertFalse(Debit.estDebit("kWh"))
    }

    @Test fun reception_et_envoi_dans_la_meme_unite() {
        val reception = WidgetsHa.valeurAffichee("1.7", "Mbit/s", "sensor", "data_rate")
        val envoi = WidgetsHa.valeurAffichee("0.1", "MB/s", "sensor", "data_rate")
        assertEquals("1,7 Mbit/s", reception)
        assertEquals("0,8 Mbit/s", envoi)
    }

    @Test fun la_courbe_et_la_veille_suivent() {
        val h = Debit.historique(WidgetsHa.Historique.Nombres("MB/s", listOf(WidgetsHa.Point(0L, 0.5), WidgetsHa.Point(1L, 2.0))))
        assertTrue(h is WidgetsHa.Historique.Nombres)
        h as WidgetsHa.Historique.Nombres
        assertEquals(Debit.MBITS, h.unite)
        assertEquals(listOf(4.0, 16.0), h.points.map { it.v })
        val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
        assertTrue(File(src, "VeilleView.kt").readText().contains("Debit.facteur(u)"))
        assertTrue(File(src, "VueWidgetHa.kt").readText().contains("Debit.historique(h)"))
        // La veille n'écrit plus « 24 h » entre le minimum et le maximum : échelle à gauche et heures dessous.
        assertFalse(File(src, "VeilleView.kt").readText().contains("\"24 h\""))
    }
}
