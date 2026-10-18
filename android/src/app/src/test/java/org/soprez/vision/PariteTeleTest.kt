// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 17-10 (l'auteur : « tous les widgets du mobile doivent être disponibles sur les télés, avec la même fonction ») : un seul
 * catalogue. Chaque widget du mobile (widgets de Vision de l'accueil, widgets de la zone du haut, styles des widgets
 * Home Assistant — ceux de l'écran d'accueil Android sont les mêmes styles) doit être posable sur la télé, pour le même
 * profil. Un widget ajouté au mobile sans passer par le catalogue commun fait échouer cet essai.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PariteTeleTest {
    /** Tout est permis (modules actifs, données du lieu présentes) : on compare les catalogues, pas les filtres. */
    private fun mobile(profil: String): Set<String> =
        Accueil.WIDGETS.filter { it.publics.isEmpty() || profil in it.publics }.map { it.code }.toSet()

    private fun tele(profil: String): Set<String> {
        val vision = DispositionAccueil.widgetsVisionPermis(Accueil.WIDGETS.map { Triple(it.code, it.publics, true) }, profil, null)
        // L'horloge stylisée est déjà au catalogue des zones (code de la zone du haut).
        val zones = DispositionAccueil.catalogueZone().toSet()
        return vision.toSet() + zones.filter { it == ZoneHaut.HORLOGE }
    }

    @Test fun chaque_widget_de_vision_du_mobile_existe_sur_la_tele() {
        for (profil in listOf("enfant", "parent", "")) {
            val manquants = mobile(profil) - tele(profil)
            assertEquals("profil « $profil » : widgets du mobile absents de la télé", emptySet<String>(), manquants)
        }
    }

    @Test fun chaque_widget_de_la_zone_du_haut_existe_sur_la_tele() {
        val manquants = ZoneHaut.catalogue(false).toSet() - DispositionAccueil.catalogueZone().toSet()
        assertEquals("zone du haut : widgets du mobile absents des zones de la télé", emptySet<String>(), manquants)
    }

    @Test fun chaque_style_de_widget_ha_est_dessine_et_pilotable_sur_la_tele() {
        // Les widgets Home Assistant de la télé (zones, rangée, zone du haut) passent tous par la même vue que le mobile
        // (VueWidgetHa), non en lecture seule ; chaque style du mobile doit y avoir un dessin.
        val sansDessin = WidgetsHa.STYLES.filter { it !in STYLES_DESSINES }
        assertEquals("styles HA du mobile sans dessin sur la télé", emptyList<String>(), sansDessin)
        // Le catalogue des zones accepte tout widget HA partagé (« ha:<entité> »), quel que soit son style.
        val code = ZoneHaut.PREFIXE_HA + "input_number.essai"
        assertEquals(true, code in DispositionAccueil.catalogueZone(listOf(code)))
    }

    companion object {
        /** Les styles que VueWidgetHa.maj sait dessiner (le reste retombe sur la tuile). */
        val STYLES_DESSINES = setOf(WidgetsHa.TUILE, WidgetsHa.BOUTON, WidgetsHa.INTERRUPTEUR, WidgetsHa.JAUGE, WidgetsHa.GRAPHIQUE,
            WidgetsHa.METEO, WidgetsHa.OUVERTURE, WidgetsHa.MINI_CARTE, WidgetsHa.LECTEUR, WidgetsHa.REGLAGE)
    }
}
