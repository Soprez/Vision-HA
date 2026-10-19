// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 1.93.06 (l'auteur : « le menu du bas s'ouvre en glissant sur toute la barre ») : un glissement lent vers le haut parti d'un
 * bord vide de la barre du bas ouvre le tiroir. Avant, la barre ne suivait pas le doigt dans ce cas (un écouteur de
 * toucher prenait tout) : seul un geste vif et long ouvrait.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MenuBasToucherTest {
    private fun ouvert(f: View) = f.translationY < f.height * 0.05f

    @Test fun glisser_lentement_depuis_un_bord_vide_de_la_barre_ouvre_le_tiroir() {
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        shadowOf(Looper.getMainLooper()).idle()
        val poignee = a.window.decorView.findViewWithTag<View>("poignee_tiroir")
        val barre = poignee.parent as View
        val feuille = a.window.decorView.findViewWithTag<View>("tiroir_feuille")
        val pos = IntArray(2); barre.getLocationInWindow(pos)
        val d = a.resources.displayMetrics.density
        // Un bord vide de la barre : à 6 dp du bord gauche (marge intérieure de 16 dp), au milieu de sa hauteur.
        val x = pos[0] + 6 * d; var y = pos[1] + barre.height / 2f
        val t0 = SystemClock.uptimeMillis()
        a.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0))
        // 100 dp vers le haut en 1 s : lent (0,1 dp/ms), mais plus que 48 dp.
        for (k in 1..20) {
            y -= 5 * d
            a.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + k * 50L, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        a.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 1_050L, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue("le tiroir doit être ouvert (translationY = ${feuille.translationY}, hauteur ${feuille.height})", ouvert(feuille))
    }

    @Test fun un_appui_sur_la_poignee_ouvre_le_tiroir() {
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        shadowOf(Looper.getMainLooper()).idle()
        val poignee = a.window.decorView.findViewWithTag<View>("poignee_tiroir")
        assertTrue("zone d'appui d'au moins 48 dp", poignee.minimumHeight >= (48 * a.resources.displayMetrics.density).toInt())
        poignee.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(ouvert(a.window.decorView.findViewWithTag("tiroir_feuille")))
    }
}
