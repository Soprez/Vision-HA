// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.view.WindowManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 17-10 (retour d'essai) : l'écran de veille de Vision et l'écran gardé allumé, par appareil, depuis Home Assistant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EcranAppareilTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun garde(a: android.app.Activity) = (a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    @Test fun defauts_surs_rien_ne_change() {
        assertEquals(true, EcranAppareil.veilleVision(ctx))
        assertEquals(false, EcranAppareil.garderAllume(ctx))
        assertEquals(true, Modules.actif(ctx, Modules.VEILLE))
        // Bloc absent (Home Assistant plus ancien) : rien ne change.
        assertEquals(false, EcranAppareil.releve(ctx, null))
        assertEquals(true, Modules.actif(ctx, Modules.VEILLE))
        assertEquals(false, garde(Robolectric.buildActivity(LanceurActivity::class.java).setup().get()))
    }

    @Test fun veille_coupee_pour_cet_appareil() {
        assertEquals(true, EcranAppareil.releve(ctx, JSONObject().put("veille_vision", false).put("garder_allume", false)))
        // Toutes les entrées de la veille passent par ce contrôle (accueil, service, écran de veille du système).
        assertEquals(false, Modules.actif(ctx, Modules.VEILLE))
        Veille.ouvrir(ctx, demande = true)
        assertEquals(false, Veille.ouverte)
        // Les autres modules ne bougent pas.
        assertEquals(true, Modules.actif(ctx, "blocage"))
        // HA la rouvre.
        EcranAppareil.releve(ctx, JSONObject().put("veille_vision", true))
        assertEquals(true, Modules.actif(ctx, Modules.VEILLE))
    }

    @Test fun ecran_garde_allume_sur_l_accueil() {
        EcranAppareil.releve(ctx, JSONObject().put("veille_vision", true).put("garder_allume", true))
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().get()
        assertEquals(true, garde(a))
        // HA le retire : au prochain retour sur l'accueil, l'écran Android peut de nouveau se mettre en veille.
        EcranAppareil.releve(ctx, JSONObject().put("garder_allume", false))
        val c = Robolectric.buildActivity(LanceurActivity::class.java).setup()
        c.pause().resume()
        assertEquals(false, garde(c.get()))
    }
}
