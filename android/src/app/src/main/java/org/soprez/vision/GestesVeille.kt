// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Ce que l'écran de veille comprend, où qu'il soit affiché (rêve du système,
 * couche par-dessus une appli, activité) :
 *  - gauche / droite (télécommande) ou glisser (doigt) : tableau précédent, suivant ;
 *  - OK ou toucher : figer le tableau, puis reprendre le défilement ;
 *  - les touches lecture (pause, suivant…) vont à l'appli qui joue dessous ;
 *  - tout le reste (Retour, glisser vers le haut…) referme la veille.
 */
class GestesVeille(private val ctx: Context, private val vue: () -> VeilleView?, private val fermer: () -> Unit) {

    private val detecteur = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { vue()?.basculerPause(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val dx = e2.x - (e1?.x ?: e2.x); val dy = e2.y - (e1?.y ?: e2.y)
            if (abs(dx) > abs(dy) && abs(dx) > 80) { vue()?.naviguer(if (dx < 0) +1 else -1); return true }
            if (dy < -140 && abs(dy) > abs(dx)) { fermer(); return true }
            return false
        }
    })

    fun toucher(e: MotionEvent): Boolean { detecteur.onTouchEvent(e); return true }

    /** Toujours consommée ; déclenche la fermeture quand la touche n'a pas de sens ici. */
    fun touche(e: KeyEvent): Boolean {
        if (e.keyCode in MEDIA) {
            Flux.touche(ctx, e)
            vue()?.signaler()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> vue()?.naviguer(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> vue()?.naviguer(+1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> vue()?.basculerPause()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> vue()?.signaler()
            else -> fermer()
        }
        return true
    }

    companion object {
        private val MEDIA = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        )
    }
}
