// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.93.06 (retour d'essai) : l'accueil de la télé, mode Modification (bandeau, Retour, entrée par « Modifier l'accueil »). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccueilModesTeleTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun tele() { shadowOf(ctx.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true) }

    private fun bandeau(a: LanceurActivity): View? = a.window.decorView.findViewWithTag("bandeau_modification")

    private fun touche(a: LanceurActivity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Test fun utilisation_par_defaut_sans_bandeau() {
        assertTrue(ReglagesTvActivity.estTele(ctx))
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().get()
        assertNull("pas de bandeau hors Modification", bandeau(a))
    }

    @Test fun utilisation_le_cadre_d_un_widget_a_commandes_ne_prend_pas_le_focus() {
        ctx.getSharedPreferences("lanceur", android.content.Context.MODE_PRIVATE).edit()
            .putString("tele_disposition", "vertical").putString("tele_colonne", "centre")
            .putString("tele_zone_g", "[\"widget:temps\"]").putBoolean("tele_zone_g_active", true).commit()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val cadre = a.window.decorView.findViewWithTag<View>("carte_widget_temps")
        assertNotNull(cadre)
        cadre.requestFocus()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val f = cadre.findFocus()  // (Robolectric ne donne pas le focus de fenêtre : currentFocus reste vide)
        assertTrue("le focus passe du cadre à sa commande (« Du temps »), pas au cadre : $f", f != null && f !== cadre && f is android.widget.TextView)
    }

    @Test fun modifier_l_accueil_ouvre_la_modification_et_retour_la_quitte() {
        val i = Intent(ctx, LanceurActivity::class.java).putExtra(LanceurActivity.EXTRA_MODIFIER, true)
        val a = Robolectric.buildActivity(LanceurActivity::class.java, i).setup().get()
        val b = bandeau(a)
        assertNotNull("le bandeau du mode Modification est montré", b)
        assertEquals(ctx.getString(R.string.accueil_bandeau_choisir), (b as android.widget.TextView).text.toString())
        touche(a, KeyEvent.KEYCODE_BACK)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull("Retour sans cadre saisi : on revient en Utilisation", bandeau(a))
        assertTrue("l'accueil reste ouvert", !a.isFinishing)
    }
}
