// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 1.94 : « Music Assistant » à l'accueil : ce qu'on ouvre (appli, réseau local, ingress), la tuile au catalogue, le bloc de Home Assistant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicAssistantTest {
    private val info = MusicAssistant.Info(8095, "/d5369777_music_assistant", "")

    @Test fun hote_local_seulement_les_adresses_du_reseau_local() {
        assertEquals("10.1.1.20", MusicAssistant.hoteLocal("http://10.1.1.20:8123"))
        assertEquals("192.168.77.9", MusicAssistant.hoteLocal("http://192.168.77.9:8123"))
        assertEquals("10.0.0.5", MusicAssistant.hoteLocal("http://10.0.0.5"))
        assertEquals("172.20.1.2", MusicAssistant.hoteLocal("https://172.20.1.2:8123/"))
        assertEquals("maison.local", MusicAssistant.hoteLocal("http://maison.local:8123"))
        for (ko in listOf("https://ha.exemple.org", "http://172.32.1.2", "http://8.8.8.8", "http://192.169.1.1", "http://192.168.1", "", "pas une adresse"))
            assertNull(ko, MusicAssistant.hoteLocal(ko))
    }

    @Test fun l_appli_music_assistant_d_abord() {
        assertEquals(MusicAssistant.Ouverture.App("io.music_assistant.companion"),
            MusicAssistant.choisir(info, "io.music_assistant.companion", "http://10.1.1.20:8123"))
    }

    @Test fun sur_le_reseau_local_l_interface_web_a_l_adresse_locale_de_ha() {
        assertEquals(MusicAssistant.Ouverture.Web("http://10.1.1.20:8095/"), MusicAssistant.choisir(info, null, "http://10.1.1.20:8123"))
        assertEquals(MusicAssistant.Ouverture.Web("http://10.0.0.5:8097/"), MusicAssistant.choisir(info.copy(port = 8097), null, "http://10.0.0.5:8123/"))
    }

    @Test fun hors_du_reseau_local_l_ingress_de_home_assistant() {
        assertEquals(MusicAssistant.Ouverture.Web("https://ha.exemple.org/d5369777_music_assistant"),
            MusicAssistant.choisir(info, null, "https://ha.exemple.org/"))
    }

    @Test fun rien_si_rien_n_est_joignable() {
        assertEquals(MusicAssistant.Ouverture.Aucune, MusicAssistant.choisir(info.copy(ingress = ""), null, "https://ha.exemple.org"))
        assertEquals(MusicAssistant.Ouverture.Aucune, MusicAssistant.choisir(info, null, ""))
        assertEquals(MusicAssistant.Ouverture.Aucune, MusicAssistant.choisir(info.copy(port = 0, ingress = "pas-un-chemin"), null, "http://10.1.1.20:8123"))
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun le_bloc_de_home_assistant_est_retenu_oublie_ou_laisse() {
        MusicAssistant.noter(ctx, JSONObject().put("musique_ma", JSONObject().put("installe", true).put("port", 8097).put("ingress", "/x_music_assistant").put("lecteur", "media_player.tele")))
        assertEquals(MusicAssistant.Info(8097, "/x_music_assistant", "media_player.tele"), MusicAssistant.info(ctx))
        // Un ancien Home Assistant (pas de clé) : on ne sait pas, rien ne change.
        MusicAssistant.noter(ctx, JSONObject().put("autre", 1))
        assertNotNull(MusicAssistant.info(ctx))
        // Home Assistant dit « non » (Music Assistant absent, ou rôle sans le droit) : oublié, la tuile ne se propose plus.
        MusicAssistant.noter(ctx, JSONObject().put("musique_ma", JSONObject.NULL))
        assertNull(MusicAssistant.info(ctx))
    }

    @Test fun la_tuile_n_est_au_catalogue_que_si_home_assistant_la_permet() {
        val widgets = Accueil.WIDGETS.filter { it.code == "ma" }
        assertEquals(1, widgets.size)
        // Home Assistant a envoyé la liste, sans « ma » (pas installé, ou rôle sans droit) : pas dans le catalogue.
        assertTrue(CatalogueWidgets.permis(widgets, "parent", { true }, setOf("temps", "demandes")).isEmpty())
        // Avec « ma » : posable, par le même catalogue sur le téléphone et sur la télé.
        assertEquals(listOf("ma"), CatalogueWidgets.permis(widgets, "parent", { true }, setOf("temps", "ma")).map { it.code })
        assertEquals(listOf("ma"), CatalogueWidgets.permis(widgets, "enfant", { true }, setOf("ma")).map { it.code })
        // Pas de liste du tout (ancien Home Assistant) : comme les autres, proposée.
        assertTrue(Accueil.disponible("ma", null))
        assertFalse(Accueil.disponible("ma", setOf("temps")))
    }

    @Test fun les_branchements() {
        fun source(f: String) = listOf(File("src/main/java/org/soprez/vision/$f"), File("app/src/main/java/org/soprez/vision/$f")).first { it.isFile }.readText()
        assertTrue(source("Party.kt").contains("MusicAssistant.proposer"))
        assertTrue(source("LanceurActivity.kt").contains("\"ma\" -> widgetMusicAssistant()") && source("LanceurActivity.kt").contains("MusicAssistant.noter(this, r)"))
        assertTrue(source("VisionApp.kt").contains("MusicAssistant.suivre(this)"))
        val manifeste = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertTrue(manifeste.contains(".MaWebActivity") && manifeste.contains("io.music_assistant.companion"))
    }
}
