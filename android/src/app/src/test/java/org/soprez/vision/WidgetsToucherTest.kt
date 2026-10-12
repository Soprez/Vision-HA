// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.90.0 (retour d'essai) : « Au toucher » des widgets — réglage de HA, choix du widget, droits, chemins sûrs, catalogue. */
class WidgetsToucherTest {
    private fun entite(toucher: String = WidgetsHa.TOUCHER_ROLE, chemin: String = "", ouvrir: Boolean = false) =
        WidgetsHa.Entite("light.salon", "Salon", "light", "", "", "", "on", emptyMap(), listOf("toggle"), emptyList(),
            toucher = toucher, chemin = chemin, ouvrir = ouvrir)

    private fun widget(toucher: String = WidgetsHa.TOUCHER_HA, chemin: String = "") =
        WidgetsHa.Reglage("w1", listOf("light.salon"), WidgetsHa.INTERRUPTEUR, 2, 1, toucher = toucher, chemin = chemin)

    @Test fun chemins_surs() {
        assertEquals("/lovelace/0", WidgetsHa.cheminSur(" /lovelace/0 "))
        assertEquals("/lovelace?more-info-entity-id=light.salon", WidgetsHa.cheminSur(OuvrirHa.cheminDetails("light.salon")))
        for (faux in listOf("", "lovelace", "//exemple.invalid", "https://exemple.invalid", "/a b", "/x\\y", "javascript:x", "/" + "a".repeat(250)))
            assertNull(faux, WidgetsHa.cheminSur(faux))
    }

    @Test fun adresse_web_sans_appli_compagnon() {
        // La dernière adresse qui a répondu (l'interne à la maison, l'externe dehors), puis le chemin.
        assertEquals("http://192.168.1.11:8123/lovelace/0", OuvrirHa.adresseWeb(listOf("http://192.168.1.11:8123/", "https://exemple.exemple.org"), "/lovelace/0"))
        assertEquals("https://exemple.exemple.org/jardin", OuvrirHa.adresseWeb(listOf("https://exemple.exemple.org"), "/jardin"))
        assertNull(OuvrirHa.adresseWeb(emptyList(), "/jardin"))
        assertNull(OuvrirHa.adresseWeb(listOf("https://exemple.exemple.org"), "https://ailleurs.invalid"))
    }

    @Test fun reglage_de_home_assistant() {
        // Sans choix du widget : ce que HA a décidé pour cet appareil (droits déjà appliqués par HA).
        assertEquals(WidgetsHa.TOUCHER_ROLE to "", WidgetsHa.toucher(widget(), entite()))
        assertEquals(WidgetsHa.TOUCHER_VUE to "/jardin/arrosage", WidgetsHa.toucher(widget(), entite(WidgetsHa.TOUCHER_VUE, "/jardin/arrosage")))
        assertEquals(WidgetsHa.TOUCHER_DETAILS to "", WidgetsHa.toucher(null, entite(WidgetsHa.TOUCHER_DETAILS)))
        assertEquals(WidgetsHa.TOUCHER_RIEN to "", WidgetsHa.toucher(widget(), entite(WidgetsHa.TOUCHER_RIEN)))
        // Un chemin faux venu du serveur : l'action du rôle.
        assertEquals(WidgetsHa.TOUCHER_ROLE to "", WidgetsHa.toucher(widget(), entite(WidgetsHa.TOUCHER_VUE, "https://x")))
    }

    @Test fun choix_du_widget_sans_depasser_les_droits() {
        // Le choix du widget passe avant celui de HA…
        assertEquals(WidgetsHa.TOUCHER_RIEN to "", WidgetsHa.toucher(widget(WidgetsHa.TOUCHER_RIEN), entite(WidgetsHa.TOUCHER_VUE, "/a", ouvrir = true)))
        assertEquals(WidgetsHa.TOUCHER_VUE to "/b/c", WidgetsHa.toucher(widget(WidgetsHa.TOUCHER_VUE, "/b/c"), entite(ouvrir = true)))
        assertEquals(WidgetsHa.TOUCHER_DETAILS to "", WidgetsHa.toucher(widget(WidgetsHa.TOUCHER_DETAILS), entite(ouvrir = true)))
        // … mais sans le droit d'ouvrir (Demandeur), détails et vues retombent sur l'action du rôle.
        assertEquals(WidgetsHa.TOUCHER_ROLE to "", WidgetsHa.toucher(widget(WidgetsHa.TOUCHER_VUE, "/b/c"), entite(ouvrir = false)))
        assertEquals(WidgetsHa.TOUCHER_ROLE to "", WidgetsHa.toucher(widget(WidgetsHa.TOUCHER_DETAILS), entite(ouvrir = false)))
        // Une vue choisie avec un chemin faux : l'action du rôle.
        assertEquals(WidgetsHa.TOUCHER_ROLE to "", WidgetsHa.toucher(widget(WidgetsHa.TOUCHER_VUE, "lovelace"), entite(ouvrir = true)))
    }

    @Test fun reglage_garde_et_relu() {
        val r = widget(WidgetsHa.TOUCHER_VUE, "/jardin")
        val relu = WidgetsHa.decoder(WidgetsHa.encoder(r))!!
        assertEquals(WidgetsHa.TOUCHER_VUE, relu.toucher)
        assertEquals("/jardin", relu.chemin)
        // Un ancien réglage (sans « toucher ») : comme dans Home Assistant.
        val ancien = WidgetsHa.decoder("""{"id":"w2","entites":["light.salon"],"style":"interrupteur","w":2,"h":1}""")!!
        assertEquals(WidgetsHa.TOUCHER_HA, ancien.toucher)
    }

    @Test fun catalogue_lu() {
        val texte = """{"ok":true,"entites":[{"entity_id":"light.salon","nom":"Salon","toucher":"vue","chemin":"/a/b","ouvrir":true}],
            "vues_ha":[{"chemin":"/a","titre":"Aperçu"},{"chemin":"https://x","titre":"Faux"},{"chemin":"/a/b","titre":"Aperçu › Maison"}]}"""
        val e = WidgetsHa.analyserEntites(texte).first()
        assertEquals(Triple(WidgetsHa.TOUCHER_VUE, "/a/b", true), Triple(e.toucher, e.chemin, e.ouvrir))
        assertEquals(listOf("Aperçu" to "/a", "Aperçu › Maison" to "/a/b"), WidgetsHa.vuesHa(texte))
        // Un ancien serveur (sans ces champs) : l'action du rôle, sans droit d'ouvrir.
        val vieux = WidgetsHa.analyserEntites("""{"entites":[{"entity_id":"light.salon"}]}""").first()
        assertEquals(Triple(WidgetsHa.TOUCHER_ROLE, "", false), Triple(vieux.toucher, vieux.chemin, vieux.ouvrir))
    }
}
