// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soprez.vision.FocusMusique.Action as A
import java.io.File

/**
 * 1.93.06 (point 6a, retour d'essai : Charlie jouait à Fortnite sur HDMI 3 et la musique de Vision continuait) : passer
 * sur une entrée de la télé (HDMI, TV en direct) met la musique en pause, même quand Android ne retire pas le focus
 * audio. Même réglage que les applis : toujours (défaut), certaines sources ou applis, jamais ; reprise au retour en
 * option.
 */
class FocusSourcesTeleTest {
    private val moi = "org.soprez.vision"

    @Test fun les_entrees_de_la_tele_sont_reconnues() {
        // TCL (MediaTek), Google TV et Android TV d'origine, Philips, Amlogic.
        for (p in listOf("com.mediatek.wwtv.tvcenter", "com.google.android.tv", "com.android.tv", "org.droidtv.playtv", "com.droidlogic.tvinput"))
            assertTrue(p, SourcesTele.estEntree(p, "", emptySet(), moi))
        // Un fabricant inconnu : le paquet qui déclare l'entrée HDMI au système, ou un écran « Hdmi… » / « LiveTv… ».
        assertTrue(SourcesTele.estEntree("com.fabricant.entrees", "", setOf("com.fabricant.entrees"), moi))
        assertTrue(SourcesTele.estEntree("com.fabricant.ui", "com.fabricant.ui.HdmiPassthroughActivity", emptySet(), moi))
        assertTrue(SourcesTele.estEntree("com.fabricant.ui", "com.fabricant.ui.LiveTvActivity", emptySet(), moi))
        // Pas une entrée : une appli (le jeu tourne sur la console, pas sur la télé), les réglages, Vision elle-même.
        assertFalse(SourcesTele.estEntree("com.epicgames.fortnite", "com.epicgames.fortnite.MainActivity", emptySet(), moi))
        assertFalse(SourcesTele.estEntree("com.android.tv.settings", "com.android.tv.settings.MainSettings", emptySet(), moi))
        assertFalse(SourcesTele.estEntree(moi, "org.soprez.vision.LanceurActivity", setOf(moi), moi))
    }

    @Test fun pause_par_defaut_en_passant_sur_hdmi() {
        assertEquals(A.PAUSE, FocusMusique.decisionSource(FocusMusique.TOUJOURS, emptySet(), false, entree = true, pauseParSource = false, joue = true))
        // Rien ne joue : rien à mettre en pause.
        assertEquals(A.RIEN, FocusMusique.decisionSource(FocusMusique.TOUJOURS, emptySet(), false, entree = true, pauseParSource = false, joue = false))
    }

    @Test fun certaines_sources_ou_applis_et_jamais() {
        val avec = setOf(SourcesTele.JETON)
        assertEquals(A.PAUSE, FocusMusique.decisionSource(FocusMusique.APPLIS, avec, false, true, false, true))
        assertEquals(A.RIEN, FocusMusique.decisionSource(FocusMusique.APPLIS, setOf("com.netflix.ninja"), false, true, false, true))
        assertEquals(A.RIEN, FocusMusique.decisionSource(FocusMusique.JAMAIS, avec, true, true, false, true))
        // Le jeton a la forme d'un nom de paquet : Home Assistant (musique_regler, « applis ») l'accepte.
        assertTrue(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(SourcesTele.JETON))
    }

    @Test fun reprise_au_retour_seulement_si_demandee() {
        assertEquals(A.REPRENDRE, FocusMusique.decisionSource(FocusMusique.TOUJOURS, emptySet(), reprise = true, entree = false, pauseParSource = true, joue = false))
        assertEquals(A.RIEN, FocusMusique.decisionSource(FocusMusique.TOUJOURS, emptySet(), reprise = false, entree = false, pauseParSource = true, joue = false))
        // Une pause qui ne venait pas de l'entrée (l'utilisateur, une autre appli) ne repart pas toute seule.
        assertEquals(A.RIEN, FocusMusique.decisionSource(FocusMusique.TOUJOURS, emptySet(), reprise = true, entree = false, pauseParSource = false, joue = false))
    }

    @Test fun branche_sur_l_accessibilite_et_le_lecteur() {
        val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
        assertTrue(File(src, "VisionAccessibility.kt").readText().contains("FocusMusique.premierPlan(this, pkg"))
        // Sans accessibilité : l'historique d'usage, relu toutes les 2 s pendant la lecture.
        assertTrue(File(src, "FocusMusique.kt").readText().contains("Usage.premierPlan(ctx)"))
        // Après une longue pause (MA arrête le lecteur au bout de 30 s), la reprise passe par Home Assistant, gardée.
        assertTrue(File(src, "LecteurSlim.kt").readText().contains("else if (etat == \"arrêt\") ctxApp?.let { relancerParHa(it) }"))
    }
}
