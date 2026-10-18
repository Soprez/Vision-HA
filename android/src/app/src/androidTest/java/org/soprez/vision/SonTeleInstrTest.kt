// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import android.test.InstrumentationTestCase

/**
 * 1.90.2 (l'auteur : notifications muettes sur la télé du bureau) : le son d'un message sur une télé sort de l'appli, sur
 * le canal média (volume de la télé), sans jamais toucher au volume ni à la sourdine du système.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.SonTeleInstrTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class SonTeleInstrTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    fun testCarillonEtAlarmeSurLeCanalMediaSansToucherAuVolume() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val avant = volumes(am)
        val muetAvant = am.isStreamMute(AudioManager.STREAM_MUSIC)
        assertEquals(AudioAttributes.USAGE_MEDIA, SonTele.attributs().usage)
        for ((son, boucle) in listOf(R.raw.vision_carillon to false, R.raw.vision_alarme to true)) {
            assertTrue("son pas lancé", lancer(son, boucle))
            SystemClock.sleep(300)
            assertTrue("le son ne joue pas", SonTele.joue)
            arreterSon()
            assertFalse(SonTele.joue)
        }
        val apres = volumes(am)
        assertEquals("un volume système a changé", avant, apres)
        assertEquals(muetAvant, am.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    // Hors des méthodes « test… » : JUnit 3 prendrait leurs lambdas pour des essais.
    private fun volumes(am: AudioManager): List<Pair<Int, Int>> {
        val l = ArrayList<Pair<Int, Int>>()
        for (s in 0..10) try { l.add(s to am.getStreamVolume(s)) } catch (_: Exception) {}
        return l
    }

    private fun lancer(son: Int, boucle: Boolean): Boolean {
        var ok = false
        instrumentation.runOnMainSync { ok = SonTele.jouer(ctx, son, boucle) }
        return ok
    }

    private fun arreterSon() = instrumentation.runOnMainSync { SonTele.arreter() }
}
