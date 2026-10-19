// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.DispositionAccueil
import org.soprez.vision.LanceurActivity
import org.soprez.vision.WidgetsHaStock
import org.soprez.vision.ZoneHaut

/**
 * 17-10 (l'auteur, point 20) : le widget de consigne, posé dans une zone de l'accueil de la télé, se règle à la télécommande :
 * le focus arrive sur − (anneau d'or), ► passe à +, ◄ revient à −. Cause : un widget posé dans une zone partait toujours
 * en tuile (sans − ni +) ; il part maintenant du premier style que propose Home Assistant. Ici, Home Assistant permet à l'appareil de régler la
 * valeur (« set_value » dans le catalogue) ; sans ce droit, il n'y a ni − ni + et OK ouvre le menu — c'est le réglage
 * du widget dans Home Assistant (« qui peut agir ») qui décide.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetTeleCommandeCaptureTest {
    private fun focus(a: Activity): View? = a.window.decorView.findFocus()

    /** Une touche, comme sur l'appareil : d'abord l'écran (qui peut la garder), puis la navigation du focus que fait
     *  ViewRootImpl (absente de Robolectric) : chercher la vue voisine dans ce sens et lui donner le focus. */
    private fun touche(a: Activity, code: Int) {
        val prise = a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        val sens = when (code) { KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT; KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN; else -> 0 }
        if (!prise && sens != 0) focus(a)?.focusSearch(sens)?.requestFocus(sens)
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Test fun consigne_reglable_a_la_telecommande() {
        val t = Recette.TAILLES.first { it.nom == "tv-1080p" }
        Recette.poser(t, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ""; urlExterne = ""; urlActive = "" }
        WidgetsHaStock.garderCatalogue(ctx, javaClass.getResource("/captures/catalogue_widgets.json")!!.readText())
        val code = ZoneHaut.PREFIXE_HA + "input_number.consigne"
        DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_VERTICAL)
        // La colonne des applis à gauche (le défaut) : la zone de droite.
        DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_D, listOf(code))
        DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_D, true)
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        val cadre = a.window.decorView.findViewWithTag<View>(code)
        assertNotNull("le widget de consigne n'est pas dans la zone", cadre)
        cadre.requestFocusFromTouch()
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("le focus doit aller sur −", "widget_moins", focus(a)?.tag)
        touche(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("► doit passer à +", "widget_plus", focus(a)?.tag)
        touche(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("◄ doit revenir à −", "widget_moins", focus(a)?.tag)
        Recette.capturer(a.window.decorView, "consigne-telecommande", t, 1.0f)
    }
}
