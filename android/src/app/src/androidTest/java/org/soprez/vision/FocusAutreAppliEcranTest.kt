// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase

/**
 * 1.93.06 (point 6) sur le banc : une autre appli prend le son (comme YouTube, qui refuse de tourner sur l'émulateur) :
 * la musique de Vision se met en pause, et ne repart pas toute seule quand l'autre son s'arrête (reprise non par
 * défaut). La musique est lancée par Music Assistant sur le lecteur d'essai pendant l'essai (il l'attend 120 s).
 *
 *   adb shell am instrument -w -e class org.soprez.vision.FocusAutreAppliEcranTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class FocusAutreAppliEcranTest : InstrumentationTestCase() {
    private val ctx: Context get() = instrumentation.targetContext

    private fun attendre(ms: Long, condition: () -> Boolean): Boolean {
        val fin = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < fin) { if (condition()) return true; SystemClock.sleep(250) }
        return condition()
    }

    fun testUneAutreAppliPrendLeSon() {
        if (Build.VERSION.SDK_INT < 26) return
        assertTrue("la musique n'a pas démarré (lancer la lecture sur le lecteur d'essai)", attendre(120_000) { LecteurMusique.etat == "lecture" })
        assertTrue("Vision n'a pas le focus audio", attendre(5_000) { FocusMusique.detenu })
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val autre = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { }.build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, am.requestAudioFocus(autre))
        try {
            assertTrue("la musique n'est pas en pause (${LecteurMusique.etat})", attendre(5_000) { LecteurMusique.etat == "pause" })
            assertTrue(FocusMusique.pauseParFocus)
        } finally { am.abandonAudioFocusRequest(autre) }
        // Reprise non réglée (défaut) : la musique reste en pause quand l'autre son s'arrête.
        SystemClock.sleep(8_000)
        assertEquals("pause", LecteurMusique.etat)
    }
}
