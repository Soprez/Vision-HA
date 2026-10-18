// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Désinstallation d'une appli : l'accueil enregistré l'oublie, et le garde-fou de l'écran de confirmation. */
class NettoyageAccueilTest {
    private val vlc = "org.videolan.vlc"
    private val yt = "com.google.android.youtube"
    private val spotify = "com.spotify.music"

    private fun etat(dock: List<String>? = listOf(yt, vlc)) = NettoyageAccueil.EtatAccueil(
        places = listOf(
            Accueil.Place(vlc, 0, 0, 1, 1),
            Accueil.Place(yt, 1, 0, 1, 1),
            Accueil.Place("dossier:aa", 2, 0, 1, 1),
            Accueil.Place("dossier:bb", 3, 0, 1, 1),
            Accueil.Place("widget:horloge", 0, 1, 4, 2),
            Accueil.Place("widget:android:7", 0, 3, 2, 2),
            Accueil.Place("widget:android:9", 2, 3, 2, 2),
        ),
        dossiers = listOf(
            Accueil.Dossier("aa", "Vidéo", mutableListOf(vlc, spotify)),
            Accueil.Dossier("bb", "Seul", mutableListOf(vlc)),
        ),
        ordre = listOf(yt, vlc, "dossier:aa", "dossier:bb"),
        dock = dock,
        masquees = setOf(vlc, "com.autre"),
        connues = setOf(vlc, yt, spotify),
    )

    @Test fun appli_supprimee_quitte_tout_l_accueil() {
        val r = NettoyageAccueil.retirer(vlc, etat()) { id -> id == 7 }
        assertTrue(r.change)
        val cles = r.etat.places.map { it.cle }
        assertFalse(vlc in cles)
        assertTrue(yt in cles)
        // Le dossier qui garde une appli reste ; celui qui n'avait qu'elle disparaît, case et ordre compris.
        assertTrue("dossier:aa" in cles)
        assertFalse("dossier:bb" in cles)
        assertEquals(listOf("aa"), r.etat.dossiers.map { it.id })
        assertEquals(listOf(spotify), r.etat.dossiers[0].pkgs)
        assertEquals(listOf(yt, "dossier:aa"), r.etat.ordre)
        assertEquals(listOf(yt), r.etat.dock)
        // 17-10 (l'auteur, point 6) : une appli retirée de l'accueil le reste, même après une absence passagère.
        assertEquals(setOf(vlc, "com.autre"), r.etat.masquees)
        // Réinstallée plus tard, elle reviendra comme une nouvelle appli.
        assertEquals(setOf(yt, spotify), r.etat.connues)
        // Son widget Android est retiré et rendu à libérer ; les autres restent.
        assertEquals(listOf(7), r.widgetsALiberer)
        assertFalse("widget:android:7" in cles)
        assertTrue("widget:android:9" in cles)
        assertTrue("widget:horloge" in cles)
    }

    @Test fun les_places_gardent_leur_position() {
        val r = NettoyageAccueil.retirer(vlc, etat()) { false }
        val w = r.etat.places.first { it.cle == "widget:android:9" }
        assertEquals(listOf(2, 3, 2, 2), listOf(w.col, w.row, w.w, w.h))
    }

    @Test fun appli_inconnue_ne_change_rien() {
        val r = NettoyageAccueil.retirer("com.absente", etat()) { false }
        assertFalse(r.change)
        assertTrue(r.widgetsALiberer.isEmpty())
        assertEquals(7, r.etat.places.size)
    }

    @Test fun dock_par_defaut_reste_par_defaut() {
        val r = NettoyageAccueil.retirer(vlc, etat(dock = null)) { false }
        assertEquals(null, r.etat.dock)
    }

    @Test fun etat_d_origine_intact() {
        val e = etat()
        NettoyageAccueil.retirer(vlc, e) { true }
        assertEquals(listOf(vlc, spotify), e.dossiers[0].pkgs)
        assertEquals(7, e.places.size)
    }

    // ---------------------------------------------------------------- garde-fou de la désinstallation

    private val inst = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")
    private val vision = "org.soprez.vision"

    @Test fun confirmation_demandee_depuis_vision_passe() {
        assertTrue(NettoyageAccueil.ecranLaisse("com.google.android.packageinstaller", inst, vlc, vision, 3_000))
        assertTrue(NettoyageAccueil.ecranLaisse("com.google.android.packageinstaller", inst, vlc, vision, 3_000,
            "VLC\nVoulez-vous désinstaller cette application ?", "VLC"))
    }

    @Test fun jamais_pour_vision_elle_meme() {
        assertFalse(NettoyageAccueil.ecranLaisse("com.google.android.packageinstaller", inst, vision, vision, 1_000))
        // Demande faite pour VLC, mais l'écran affiché est celui de Vision : la protection reste.
        assertFalse(NettoyageAccueil.ecranLaisse("com.google.android.packageinstaller", inst, vlc, vision, 1_000,
            "Vision\nVoulez-vous désinstaller cette application ?", "VLC"))
        // Une appli au nom très court contenu dans « Vision » ne sert pas de passe-droit.
        assertFalse(NettoyageAccueil.ecranLaisse("com.google.android.packageinstaller", inst, "com.vi", vision, 1_000,
            "Vision\nDésinstaller ?", "Vi"))
    }

    @Test fun appli_dont_le_nom_contient_vision() {
        assertTrue(NettoyageAccueil.ecranLaisse("com.android.packageinstaller", inst, "com.tv.vision.plus", vision, 1_000,
            "Vision Plus\nDésinstaller ?", "Vision Plus"))
    }

    @Test fun hors_fenetre_ou_hors_installateur() {
        assertFalse(NettoyageAccueil.ecranLaisse("com.google.android.packageinstaller", inst, vlc, vision, NettoyageAccueil.FENETRE_MS + 1))
        assertFalse(NettoyageAccueil.ecranLaisse("com.android.settings", inst, vlc, vision, 1_000))
        assertFalse(NettoyageAccueil.ecranLaisse("com.google.android.packageinstaller", inst, null, vision, 1_000))
        assertFalse(NettoyageAccueil.ecranLaisse("com.google.android.packageinstaller", inst, vlc, vision, -5))
    }
}
