// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modules activables : lecture de la liste, absence = tout actif, décisions « afficher / agir » par module. */
class ModulesTest {
    private fun s(vararg ids: String): Set<String> = ids.toSet()

    @Test fun lecture_de_la_liste() {
        assertNull(Modules.normaliser(null))
        assertEquals(s("plages", "veille"), Modules.normaliser(listOf(" Plages ", "veille", "", "VEILLE")))
        assertEquals(emptySet<String>(), Modules.normaliser(emptyList()))
    }

    @Test fun rangement_dans_les_preferences() {
        assertNull(Modules.encoder(null))
        assertNull(Modules.decoder(null))
        assertEquals("filtrage,plages", Modules.encoder(s("plages", "filtrage")))
        assertEquals(s("plages", "filtrage"), Modules.decoder("filtrage,plages"))
        // Liste vide reçue : tout est coupé (et non « champ absent »).
        assertEquals("", Modules.encoder(emptySet()))
        assertEquals(emptySet<String>(), Modules.decoder(""))
        for (l in listOf(null, emptySet(), s("plages"), Modules.TOUS.toSet()))
            assertEquals(l, Modules.decoder(Modules.encoder(l)))
    }

    @Test fun absence_tout_actif() {
        for (id in Modules.TOUS) assertTrue(id, Modules.actifDans(null, id))
        assertTrue(Modules.regles(null)); assertTrue(Modules.horaires(null))
        assertEquals(Modules.TOUS.toSet(), Modules.effectifs(null))
        assertEquals("*", Modules.signature(null))
    }

    @Test fun liste_vide_tout_coupe() {
        for (id in Modules.TOUS) assertFalse(id, Modules.actifDans(emptySet(), id))
        assertFalse(Modules.regles(emptySet())); assertFalse(Modules.horaires(emptySet()))
    }

    @Test fun regles_et_horaires() {
        assertTrue(Modules.regles(s("blocage")))
        assertFalse(Modules.horaires(s("blocage")))
        assertTrue(Modules.horaires(s("temps")))
        assertTrue(Modules.horaires(s("plages")))
        assertFalse(Modules.regles(s("filtrage", "messages", "veille")))
    }

    @Test fun changements() {
        assertEquals(s("filtrage"), Modules.coupes(null, Modules.TOUS.toSet() - "filtrage"))
        assertEquals(emptySet<String>(), Modules.coupes(null, null))
        assertEquals(emptySet<String>(), Modules.repris(null, null))
        assertEquals(s("protections"), Modules.repris(s("plages"), s("plages", "protections")))
        assertEquals(Modules.TOUS.toSet() - "veille", Modules.coupes(null, s("veille")))
        // Retour à un serveur ancien (champ absent) : tout reprend.
        assertEquals(Modules.TOUS.toSet() - "veille", Modules.repris(s("veille"), null))
    }

    @Test fun widgets_de_l_accueil() {
        val tous = Modules.TOUS.toSet()
        for (code in listOf("horloge", "temps", "demandes", "enfants", "chauffage", "camera", "android:12"))
            assertTrue(code, Modules.widgetVisible(null, code) && Modules.widgetVisible(tous, code))
        val rien = emptySet<String>()
        assertTrue(Modules.widgetVisible(rien, "horloge"))
        for (code in listOf("temps", "demandes", "enfants", "infos", "devoirs", "ecole", "chauffage", "fioul", "camera", "batteries", "maison", "android:12"))
            assertFalse(code, Modules.widgetVisible(rien, code))
        assertTrue(Modules.widgetVisible(s("widgets"), "android:3"))
        assertFalse(Modules.widgetVisible(s("entites"), "android:3"))
        // Widgets Home Assistant : suivent le module « entites » (masqués, pas supprimés), pas le module « widgets ».
        assertTrue(Modules.widgetVisible(s("entites"), "ha:k3x9"))
        assertFalse(Modules.widgetVisible(s("widgets"), "ha:k3x9"))
        assertTrue(Modules.widgetVisible(null, "ha:k3x9"))
        assertTrue(Modules.widgetVisible(s("entites"), "chauffage"))
        assertTrue(Modules.widgetVisible(s("temps"), "temps"))
        assertTrue(Modules.widgetVisible(s("plages"), "temps"))
        assertFalse(Modules.widgetVisible(s("blocage"), "temps"))
        assertTrue(Modules.widgetVisible(s("blocage"), "enfants"))
        assertTrue(Modules.widgetVisible(s("demandes"), "demandes"))
    }

    @Test fun tableaux_de_veille() {
        assertTrue(Modules.tableauVeille(emptySet(), "horloge"))
        assertFalse(Modules.tableauVeille(s("veille"), "dash"))
        assertTrue(Modules.tableauVeille(s("veille", "veille_tableaux"), "dash"))
        assertTrue(Modules.tableauVeille(null, "dash"))
    }

    @Test fun notifications_du_releve() {
        val rien = emptySet<String>()
        assertFalse(Modules.notificationPermise(rien, false, false, false))
        assertTrue(Modules.notificationPermise(s("messages"), false, false, false))
        assertFalse(Modules.notificationPermise(s("messages"), true, false, false))
        assertTrue(Modules.notificationPermise(s("demandes"), true, false, false))
        assertTrue(Modules.notificationPermise(s("demandes"), false, true, false))
        assertFalse(Modules.notificationPermise(s("demandes", "messages"), false, false, true))
        assertTrue(Modules.notificationPermise(s("deverrouillage"), false, false, true))
        assertTrue(Modules.notificationPermise(null, true, true, true))
    }

    @Test fun gestes_de_l_espace_gestion() {
        assertEquals(listOf("planning", "limites", "à qui", "pause"), Modules.gestesGestion(null, android = true))
        assertEquals(listOf("planning", "limites", "à qui"), Modules.gestesGestion(null, android = false))
        assertEquals(listOf("à qui"), Modules.gestesGestion(emptySet(), android = true))
        assertEquals(listOf("limites", "à qui", "pause"), Modules.gestesGestion(s("temps", "pause"), android = true))
    }

    @Test fun tous_les_identifiants_du_serveur() {
        assertEquals(listOf("plages", "temps", "blocage", "filtrage", "demandes", "deverrouillage", "double_validation", "pause",
            "messages", "veille", "veille_tableaux", "entites", "accueil", "widgets", "habillage", "optimisations", "protections",
            "pause_generale"), Modules.TOUS)
    }
}
