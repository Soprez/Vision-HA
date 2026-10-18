// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Veille sur télé : économiseur du système, et repli de Vision sur son accueil. */
class ReglesVeilleTest {
    @Test fun lecture_de_screensaver_components() {
        assertEquals(true, ReglesVeille.economiseurEstVision("org.soprez.vision/.VeilleService"))
        assertEquals(true, ReglesVeille.economiseurEstVision("com.google.android.backdrop/.Backdrop, org.soprez.vision/org.soprez.vision.VeilleService"))
        assertEquals(false, ReglesVeille.economiseurEstVision("com.google.android.backdrop/.Backdrop"))
        assertEquals(false, ReglesVeille.economiseurEstVision(null))
        assertEquals(false, ReglesVeille.economiseurEstVision("com.exemple.autre/.VeilleService"))
        assertNull(ReglesVeille.economiseurEstVision("x", lisible = false))
    }

    @Test fun delai_sur_l_accueil() {
        assertEquals(5, ReglesVeille.delaiAccueil(5, true, 10))     // délai choisi : il l'emporte
        assertEquals(0, ReglesVeille.delaiAccueil(0, true, 10))     // économiseur = Vision : le système s'en charge
        assertEquals(10, ReglesVeille.delaiAccueil(0, false, 10))   // repli
        assertEquals(10, ReglesVeille.delaiAccueil(0, null, 10))    // illisible : repli
        assertEquals(0, ReglesVeille.delaiAccueil(0, false, 0))     // repli « jamais »
    }

    // ------------------------------------------------------------------ Veille-lot (point 5) : le choix du chemin

    private fun faits(vision: Boolean? = false, actif: Boolean? = true, ecrire: Boolean = false, reglage: Boolean = true,
                      masque: Boolean = false, acces: Boolean = false, accueil: Boolean = false) =
        ReglesVeille.Faits(vision, actif, ecrire, reglage, masque, acces, accueil)

    @Test fun deja_vision() {
        val p = ReglesVeille.plan(faits(vision = true))
        assertEquals(ReglesVeille.Chemin.DEJA_VISION, p.chemin)
        assertEquals(ReglesVeille.Repli.INUTILE, p.repli)
        // Activation inconnue : on fait confiance au choix.
        assertEquals(ReglesVeille.Chemin.DEJA_VISION, ReglesVeille.plan(faits(vision = true, actif = null)).chemin)
    }

    @Test fun vision_choisi_mais_economiseur_eteint() {
        // L'économiseur est coupé : la télé ne rêvera jamais, il faut l'allumer (et le repli tient d'ici là).
        val p = ReglesVeille.plan(faits(vision = true, actif = false, acces = true))
        assertEquals(ReglesVeille.Chemin.REGLAGE, p.chemin)
        assertEquals(ReglesVeille.Repli.ACCUEILS, p.repli)
    }

    @Test fun ecriture_directe_l_emporte() {
        // Le droit d'écrire (pm grant) : un appui, même sur Google TV.
        assertEquals(ReglesVeille.Chemin.ECRIRE, ReglesVeille.plan(faits(ecrire = true, masque = true, reglage = false)).chemin)
    }

    @Test fun reglage_de_la_tele_quand_il_propose_vision() {
        assertEquals(ReglesVeille.Chemin.REGLAGE, ReglesVeille.plan(faits(vision = false)).chemin)
        assertEquals(ReglesVeille.Chemin.REGLAGE, ReglesVeille.plan(faits(vision = null)).chemin)   // illisible : on essaie le réglage
    }

    @Test fun google_tv_ou_sans_reglage_commande_adb() {
        assertEquals(ReglesVeille.Chemin.ADB, ReglesVeille.plan(faits(masque = true)).chemin)        // Chromecast avec Google TV
        assertEquals(ReglesVeille.Chemin.ADB, ReglesVeille.plan(faits(reglage = false)).chemin)      // aucun réglage d'économiseur
    }

    @Test fun repli_selon_ce_que_vision_voit() {
        assertEquals(ReglesVeille.Repli.ACCUEILS, ReglesVeille.plan(faits(acces = true, accueil = true)).repli)
        assertEquals(ReglesVeille.Repli.ACCUEIL_VISION, ReglesVeille.plan(faits(accueil = true)).repli)
        assertEquals(ReglesVeille.Repli.AUCUN, ReglesVeille.plan(faits()).repli)
    }

    @Test fun delai_sur_les_accueils_tiers() {
        val partout = ReglesVeille.plan(faits(masque = true, acces = true))
        assertEquals(10, ReglesVeille.delaiAccueilsTiers(0, partout, 10))
        assertEquals(5, ReglesVeille.delaiAccueilsTiers(5, partout, 10))
        assertEquals(0, ReglesVeille.delaiAccueilsTiers(0, partout, 0))
        assertEquals(0, ReglesVeille.delaiAccueilsTiers(0, ReglesVeille.plan(faits(accueil = true)), 10))   // sans accessibilité : jamais ailleurs
        assertEquals(0, ReglesVeille.delaiAccueilsTiers(0, ReglesVeille.plan(faits(vision = true, acces = true)), 10))
    }

    @Test fun economiseur_par_defaut_et_commandes() {
        // « screensaver_components » vide : c'est l'économiseur par défaut qui joue.
        assertEquals(true, ReglesVeille.economiseurEffectifEstVision(null, "org.soprez.vision/.VeilleService"))
        assertEquals(false, ReglesVeille.economiseurEffectifEstVision("", "com.google.android.apps.tv.dreamx/.Dream"))
        assertEquals(false, ReglesVeille.economiseurEffectifEstVision("com.google.android.backdrop/.Backdrop", "org.soprez.vision/.VeilleService"))
        val c = ReglesVeille.commandesAdb("org.soprez.vision", "org.soprez.vision/.VeilleService")
        assertEquals("adb shell pm grant org.soprez.vision android.permission.WRITE_SECURE_SETTINGS", c[0])
        assertEquals("adb shell settings put secure screensaver_components org.soprez.vision/.VeilleService", c[1])
        assertEquals("adb shell settings put secure screensaver_enabled 1", c[2])
    }

    @Test fun reglage_qui_tourne() {
        var v = ReglesVeille.REPLI_DEFAUT
        val vus = HashSet<Int>()
        repeat(ReglesVeille.DELAIS_REPLI.size) { vus.add(v); v = ReglesVeille.repliSuivant(v) }
        assertEquals(ReglesVeille.DELAIS_REPLI.toSet(), vus)
        assertEquals("Jamais", ReglesVeille.libelleRepli(0))
    }
}
