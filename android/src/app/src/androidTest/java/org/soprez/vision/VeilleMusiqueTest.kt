// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.json.JSONObject

/** 1.86.14 (retour d'essai) : pas de veille Vision tant que le lecteur Music Assistant de la télé joue. */
class VeilleMusiqueTest : InstrumentationTestCase() {
    override fun tearDown() {
        val ctx = instrumentation.targetContext
        VeilleMusique.releve(ctx, false)
        fermer()
        super.tearDown()
    }

    // Hors des méthodes « test… » : JUnit 3 prendrait les lambdas Kotlin pour des tests.
    private fun ouvrir(demande: Boolean) { val ctx = instrumentation.targetContext; instrumentation.runOnMainSync { Veille.ouvrir(ctx, demande) } }
    private fun fermer() { instrumentation.runOnMainSync { Veille.fermer() } }

    fun testPasDeVeilleAutomatiquePendantLaMusique() {
        val ctx = instrumentation.targetContext
        val tele = ReglagesTvActivity.estTele(ctx)
        // Le relevé de Home Assistant dit « ça joue » (lecture lancée d'un téléphone, Vision derrière le récepteur Cast).
        VeilleMusique.releve(ctx, true)
        assertTrue(VeilleMusique.enLecture())
        if (!tele) { assertFalse("un téléphone n'est jamais concerné", VeilleMusique.bloque(ctx)); return }
        assertTrue(VeilleMusique.bloque(ctx))
        ouvrir(false)
        SystemClock.sleep(800)
        assertFalse("la veille s'est ouverte pendant la musique", Veille.ouverte)
        // Une demande explicite (deux appuis sur Accueil, la bulle, les réglages) passe toujours.
        if (Veille.permise(ctx) && Modules.actif(ctx, Modules.VEILLE)) {
            ouvrir(true)
            SystemClock.sleep(800)
            assertTrue("la demande explicite n'a pas ouvert la veille", Veille.ouverte)
            fermer()
            SystemClock.sleep(500)
        }
        // La musique s'arrête : la veille automatique revient.
        VeilleMusique.releve(ctx, false)
        assertFalse(VeilleMusique.bloque(ctx))
        if (Veille.permise(ctx) && Modules.actif(ctx, Modules.VEILLE)) {
            ouvrir(false)
            SystemClock.sleep(800)
            assertTrue("la veille ne revient pas après la musique", Veille.ouverte)
        }
    }

    fun testReleveAncienOublie() {
        // Home Assistant se tait plus de 2 min : on ne bloque plus (jamais de télé allumée à tort).
        VeilleMusique.releve(instrumentation.targetContext, true, System.currentTimeMillis() - 130_000)
        assertFalse(VeilleMusique.enLecture())
    }

    fun testLeReleveDeLaPartyPorteLEtat() {
        val ctx = instrumentation.targetContext
        if (!ReglagesTvActivity.estTele(ctx)) return
        val tuile = ctx.getSharedPreferences("party", 0).getBoolean("tuile", false)
        Party.releve(ctx, JSONObject().put("tuile", tuile).put("musique_joue", true).put("depuis", 0))
        assertTrue(VeilleMusique.bloque(ctx))
        Party.releve(ctx, JSONObject().put("tuile", tuile).put("musique_joue", false).put("depuis", 0))
        assertFalse(VeilleMusique.bloque(ctx))
    }
}
