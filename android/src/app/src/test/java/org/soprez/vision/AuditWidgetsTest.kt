// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.93.06 (retour d'essai) : audit des widgets (libellés, unités, échelles, valeurs) et catalogue unique du téléphone et de la télé.
 * Mesuré sur le HA de l'auteur le 18/10 : « Vitesse d'envoi » de la box vaut 0,03535 Mbit/s et s'affichait « 0 Mbit/s ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class AuditWidgetsTest {
    @Test fun petites_valeurs_avec_deux_chiffres_significatifs() {
        assertEquals("0,035 Mbit/s", WidgetsHa.valeurAffichee("0.03535", "Mbit/s", "sensor", "data_rate"))
        assertEquals("1,8 Mbit/s", WidgetsHa.valeurAffichee("1.79648", "Mbit/s", "sensor", "data_rate"))
        assertEquals("0,5 kWh", WidgetsHa.valeurAffichee("0.5", "kWh", "sensor", "energy"))
        assertEquals("0,0012", WidgetsHa.nombre(0.0012))
        // Le reste ne change pas.
        assertEquals("21,5°C", WidgetsHa.valeurAffichee("21.5", "°C", "sensor", "temperature"))
        assertEquals("270 rpm", WidgetsHa.valeurAffichee("270", "rpm", "sensor", ""))
        assertEquals("0", WidgetsHa.nombre(0.0))
        assertEquals("12,3", WidgetsHa.nombre(12.34))
    }

    @Test fun graduations_rondes() {
        assertEquals(listOf(0.0, 2.0), WidgetsHa.graduations(-0.1, 2.1, 3))
        assertEquals(listOf(20.0, 25.0), WidgetsHa.graduations(18.4, 26.2, 3).filter { it >= 20 }.take(2))
        val g = WidgetsHa.graduations(0.02, 2.2, 3)
        assertTrue("dans les bornes : $g", g.all { it in 0.02..2.2 })
        assertTrue("au moins deux graduations : $g", g.size >= 2)
        assertTrue("rien si l'écart est nul", WidgetsHa.graduations(5.0, 5.0).isEmpty())
    }

    @Test fun reperes_de_temps_toutes_les_6_h_et_chaque_jour() {
        val h = 3_600_000L
        // Paris en été (UTC+2) : fenêtre de 24 h finissant à 08:30 locale (06:30 UTC).
        val fin = 6 * h + h / 2; val debut = fin - 24 * h
        val reperes = WidgetsHa.reperesTemps(debut, fin, "24h") { 2 * h }
        assertEquals("12:00, 18:00, 00:00 et 06:00 locales", 4, reperes.size)
        reperes.forEach { assertEquals("aligné sur l'heure locale", 0L, (it + 2 * h) % (6 * h)) }
        val semaine = WidgetsHa.reperesTemps(fin - 7 * 24 * h, fin, "7j") { 2 * h }
        assertEquals(7, semaine.size)
        semaine.forEach { assertEquals("minuit local", 0L, (it + 2 * h) % (24 * h)) }
    }

    @Test fun un_seul_catalogue_et_une_seule_regle() {
        assertTrue(CatalogueWidgets.permet("chauffage", emptySet(), true, "enfant", null))
        assertFalse("module coupé", CatalogueWidgets.permet("chauffage", emptySet(), false, "parent", null))
        assertFalse("réservé à un autre profil", CatalogueWidgets.permet("demandes", setOf("parent"), true, "enfant", null))
        // La règle des zones de la télé est la même (sans l'horloge stylisée, déjà dans le catalogue des zones).
        val l = Accueil.WIDGETS.map { Triple(it.code, it.publics, true) }
        val zones = DispositionAccueil.widgetsVisionPermis(l, "parent", null)
        val panneau = CatalogueWidgets.permis(Accueil.WIDGETS, "parent", { true }, null).map { it.code }.filter { it != ZoneHaut.HORLOGE }
        assertEquals(panneau, zones)
    }
}
