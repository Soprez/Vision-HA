// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import java.io.File

/**
 * 1.93.06 (incident du salon, 18/10, 17:50) : une autre appli joue sur la télé (une appli de replay), le lecteur se reconnecte, Music
 * Assistant relance la lecture : la musique de Vision doit RESTER MUETTE. Sur le banc, lecteur d'essai seulement.
 *
 * Le banc pilote Music Assistant (« allumer » puis « lecture » sur le lecteur d'essai) à chaque repère « ESSAI-LIAISON » du
 * journal VisionEssai ; avant chaque liaison, l'essai attend que le banc écrive le nom de l'étape dans
 * /data/local/tmp/vision-relier (le fichier appartient au shell : l'appli ne peut que le lire) :
 *   adb shell "echo reconnexion > /data/local/tmp/vision-relier"   (puis redemarrage, puis temoin)
 *   1. la musique joue (lancée par le banc), puis une autre « appli » prend le son et joue (AudioTrack) ;
 *   2. reconnexion du lecteur (retour du réseau) : la relance de Music Assistant est refusée, Vision reste en pause ;
 *   3. lecteur arrêté puis relancé (comme après une réinstallation) : pareil ;
 *   4. témoin : l'autre son s'arrête ; la relance a lieu.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.LiaisonAutreSonTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class LiaisonAutreSonTest : InstrumentationTestCase() {
    private val ctx: Context get() = instrumentation.targetContext
    private val signal = File("/data/local/tmp/vision-relier")

    private fun attendre(ms: Long, condition: () -> Boolean): Boolean {
        val fin = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < fin) { if (condition()) return true; SystemClock.sleep(250) }
        return condition()
    }

    private fun relier(etape: String, comment: () -> Unit) {
        assertTrue("le banc n'a pas donné le signal ($etape)", attendre(180_000) { (try { signal.readText().trim() } catch (_: Exception) { "" }) == etape })
        val avant = LecteurSlim.liaisons
        comment()
        assertTrue("pas de nouvelle liaison ($etape)", attendre(30_000) { LecteurSlim.liaisons > avant && LecteurSlim.connecte })
        Log.i("VisionEssai", "ESSAI-LIAISON $etape")
    }

    /** Pendant [ms], la musique de Vision ne joue jamais. */
    private fun resteMuette(etape: String, ms: Long) {
        val fin = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < fin) {
            assertFalse("la musique est repartie par-dessus l'autre son ($etape)", LecteurMusique.etat == "lecture")
            SystemClock.sleep(200)
        }
    }

    fun testLaMusiqueResteMuetteSiAutreChoseJoue() {
        if (Build.VERSION.SDK_INT < 26) return
        assertEquals("le banc doit être sur SlimProto", LecteurMusique.SLIM, LecteurMusique.moteur(ctx))
        assertTrue("la musique n'a pas démarré (lancer la lecture sur le lecteur d'essai)", attendre(180_000) { LecteurMusique.etat == "lecture" })
        // 1. Une autre appli prend le son et joue (comme une appli de replay).
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributs).setOnAudioFocusChangeListener { }.build()
        val taux = 44_100
        val tampon = ShortArray(taux)  // une seconde de silence, en boucle : le système compte un son en cours
        val autre = AudioTrack.Builder().setAudioAttributes(attributs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(taux).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(tampon.size * 2).build()
        autre.write(tampon, 0, tampon.size)
        autre.setLoopPoints(0, tampon.size, -1)
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, am.requestAudioFocus(focus))
        autre.play()
        try {
            assertTrue("la musique n'est pas en pause (${LecteurMusique.etat})", attendre(8_000) { LecteurMusique.etat == "pause" })
            assertTrue("l'autre son n'est pas vu", attendre(5_000) { FocusMusique.autreSonEnCours(ctx) })
            Log.i("VisionEssai", "ESSAI-PRET autre son en cours")
            // 2. Reconnexion (retour du réseau).
            relier("reconnexion") { LecteurSlim.reseauChange(true) }
            resteMuette("reconnexion", 30_000)
            // 3. Lecteur arrêté puis relancé (comme après une réinstallation).
            relier("redemarrage") { LecteurMusique.arreter(); SystemClock.sleep(1_500); LecteurMusique.demarrer(ctx) }
            resteMuette("redemarrage", 30_000)
        } finally {
            autre.stop(); autre.release(); am.abandonAudioFocusRequest(focus)
        }
        // 4. Témoin : plus rien d'autre ne joue : la relance de Music Assistant a lieu.
        assertTrue("l'autre son est encore vu", attendre(10_000) { !FocusMusique.autreSonEnCours(ctx) })
        relier("temoin") { LecteurMusique.arreter(); SystemClock.sleep(1_500); LecteurMusique.demarrer(ctx) }
        assertTrue("la relance sans autre son n'a pas eu lieu (${LecteurMusique.etat})", attendre(30_000) { LecteurMusique.etat == "lecture" })
    }
}
