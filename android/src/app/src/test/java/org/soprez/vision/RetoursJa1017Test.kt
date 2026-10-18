// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Retours de l'auteur du 17/10 (lot 1) : zone sûre « Ajuster l'écran », pluie de la veille. */
class RetoursJa1017Test {
    @Test fun zone_sure_au_defaut_rien_ne_bouge() {
        // Chaque écran garde son dessin au défaut (5 %) : la marge est exactement l'ancienne.
        assertEquals((1920 * 0.05f).toInt(), ZoneSure.marge(1920, 0.05f, ZoneSure.DEFAUT))
        assertEquals((1080 * 0.06f).toInt(), ZoneSure.marge(1080, 0.06f, ZoneSure.DEFAUT))
        // Une télé qui rogne davantage : tout s'écarte de l'écart (9 % : +4 % de la largeur).
        assertEquals((1920 * 0.09f).toInt(), ZoneSure.marge(1920, 0.05f, 9))
        assertEquals((1920 * 0.04f).toInt(), ZoneSure.marge(1920, 0f, 9))
        // Une télé qui ne rogne pas : jamais de marge négative.
        assertEquals(0, ZoneSure.marge(1920, 0f, 0))
        // Une valeur d'une autre version (ou corrompue) reste dans les bornes.
        assertEquals(ZoneSure.MAX, ZoneSure.borner(40)); assertEquals(ZoneSure.MIN, ZoneSure.borner(-3))
    }

    @Test fun zone_sure_echelle_de_la_veille() {
        assertEquals(1f, ZoneSure.echelle(5, 5), 0.0001f)
        assertEquals(0.92f, ZoneSure.echelle(9, 7), 0.0001f)   // la plus serrée des deux, sans déformer
        assertEquals(0.86f, ZoneSure.echelle(12, 12), 0.0001f)
        assertEquals(0.75f, ZoneSure.echelle(25, 25), 0.0001f) // bornée
    }

    @Test fun ajuster_l_ecran_aux_fleches() {
        // ▶ et ▲ agrandissent le cadre (moins de marge), ◀ et ▼ le resserrent ; 1 % par appui, borné.
        assertEquals(4 to 5, AjusterEcranActivity.appliquer(KeyEvent.KEYCODE_DPAD_RIGHT, 5, 5))
        assertEquals(6 to 5, AjusterEcranActivity.appliquer(KeyEvent.KEYCODE_DPAD_LEFT, 5, 5))
        assertEquals(5 to 4, AjusterEcranActivity.appliquer(KeyEvent.KEYCODE_DPAD_UP, 5, 5))
        assertEquals(5 to 6, AjusterEcranActivity.appliquer(KeyEvent.KEYCODE_DPAD_DOWN, 5, 5))
        assertEquals(0 to 5, AjusterEcranActivity.appliquer(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 5))
        assertEquals(ZoneSure.MAX to 5, AjusterEcranActivity.appliquer(KeyEvent.KEYCODE_DPAD_LEFT, ZoneSure.MAX, 5))
        assertNull(AjusterEcranActivity.appliquer(KeyEvent.KEYCODE_BACK, 5, 5))
    }

    @Test fun pluie_de_la_veille_seulement_si_prevue() {
        assertFalse(PluieVeille.aMontrer(null, false, listOf(0, 0, 0)))   // « pas de pluie » : rien
        assertTrue(PluieVeille.aMontrer(null, true, emptyList()))
        assertTrue(PluieVeille.aMontrer(20, false, emptyList()))
        assertTrue(PluieVeille.aMontrer(null, false, listOf(0, 1)))
    }

    /** Point 15 : un saut dans le titre (position, « saut ») ne rebâtit plus l'écran du lecteur — la file ne saute plus. */
    @Test fun seek_ne_rebatit_pas_le_lecteur() {
        val m = Party.Maintenant("playing", "Groovy Beat", "Artiste", "Album", "", 0.5, 240.0, 30.0, 1000.0, "")
        val a = Party.Etat(true, "tele", "Salon", true, "lecteur", "Salon", m, emptyList(), true, emptyList(), emptyList(), 5)
        val b = a.copy(maintenant = m.copy(position = 182.0, positionTs = 1007.0), relire = 1, saut = Saut.Info(182.0, 0.4))
        assertEquals(Party.pourAffichage(a), Party.pourAffichage(b))
        // Un autre titre, lui, change l'écran.
        assertFalse(Party.pourAffichage(a) == Party.pourAffichage(a.copy(maintenant = m.copy(titre = "Autre"))))
    }

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test fun zone_sure_partout_sur_la_tele_et_six_langues() {
        // Plus aucune marge d'écran télé écrite en dur (fraction de la largeur ou de la hauteur) : tout passe par ZoneSure.
        val ecrans = listOf("LanceurActivity", "PartyActivity", "ReglagesTvActivity", "ChoixMusiqueActivity", "CouleursActivity",
            "SetupActivity", "TableauEditeurActivity")
        for (e in ecrans) {
            val s = File(main, "java/org/soprez/vision/$e.kt").readText()
            assertFalse("$e : marge en dur", Regex("""\((dm|resources\.displayMetrics)\.(width|height)Pixels \* 0\.0[0-9]+f\)\.toInt\(\)""").containsMatchIn(s))
        }
        assertTrue(File(main, "java/org/soprez/vision/VeilleView.kt").readText().contains("ZoneSure.echelle(context)"))
        assertTrue(File(main, "AndroidManifest.xml").readText().contains(".AjusterEcranActivity"))
        val cles = { d: String -> Regex("name=\"(zonesure_[a-z_0-9]+)\"").findAll(File(main, "res/$d/strings_zone_sure.xml").readText()).map { it.groupValues[1] }.toSet() }
        val ref = cles("values")
        for (d in listOf("values-fr", "values-de", "values-es", "values-it", "values-nl")) assertEquals(d, ref, cles(d))
    }

    /** Point 17 : le fond animé part léger sur une télé d'Android 9, et descend tout seul quand l'appareil ne suit pas. */
    @Test fun fond_profil_de_performance() {
        val normal = ProfilFond(tele = true, modeste = false, ancien = false)
        assertEquals(0, normal.niveau); assertEquals(FondLecteur.cadence(true, false), normal.cadence())
        assertEquals(1, ProfilFond(tele = true, modeste = false, ancien = true).niveau)  // la TCL (Android 9)
        assertEquals(0, ProfilFond(tele = false, modeste = false, ancien = true).niveau)  // un vieux téléphone : mesuré
        assertEquals(1, ProfilFond(tele = false, modeste = true, ancien = false).niveau)
        // Un appareil qui tient sa cadence (42 ms pour 24 images/s) reste au niveau 0.
        repeat(200) { assertFalse(normal.noter(42)) }
        assertEquals(0, normal.niveau)
        // Un appareil qui n'en fait que 12 par seconde (83 ms) : un cran plus bas, puis un autre ; jamais au-delà de 2.
        val lent = ProfilFond(tele = true, modeste = false, ancien = false)
        var descentes = 0
        repeat(300) { if (lent.noter(83)) descentes++ }
        assertEquals(1, descentes); assertEquals(1, lent.niveau)  // 15 images/s visées : 83 ms < 1,5 × 67 ms, il tient
        repeat(300) { lent.noter(160) }
        assertEquals(2, lent.niveau); assertEquals(10, lent.cadence())
        repeat(300) { lent.noter(500) }
        assertEquals(2, lent.niveau)
        assertTrue(lent.detail() < 1f)
        // Une pause (écran caché) ne compte pas.
        val pause = ProfilFond(tele = true, modeste = false, ancien = false)
        repeat(100) { pause.noter(5000) }
        assertEquals(0, pause.niveau)
    }
}
