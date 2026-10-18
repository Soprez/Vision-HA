// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.SystemClock
import android.test.InstrumentationTestCase

/**
 * 1.90.4 (télé d'essai : lecteur « squeezeplay: … » sans nom) : relié au vrai Music Assistant (`-e hote 10.1.1.x`,
 * sinon rien n'est essayé), le lecteur Classique d'essai « Essai nom Classique · Vision » reste relié 25 s, le temps
 * des renvois du nom ; on vérifie ensuite dans Home Assistant que le lecteur porte ce nom. Config remise à la fin.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.LecteurSlimNomTest -e hote 10.1.1.11 org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class LecteurSlimNomTest : InstrumentationTestCase() {
    private fun hote(): String? = (instrumentation as? android.test.InstrumentationTestRunner)?.arguments?.getString("hote")

    fun testNomRenvoyeApresConnexion() {
        val h = hote() ?: return
        val ctx = instrumentation.targetContext
        val cfg = Config(ctx); val avant = cfg.nom
        try {
            cfg.nom = "Essai nom Classique"
            LecteurSlim.arreter(); SystemClock.sleep(500)
            LecteurSlim.demarrer(ctx, h)
            val fin = SystemClock.elapsedRealtime() + 10_000
            while (!LecteurSlim.connecte && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(200)
            assertTrue("pas relié à Music Assistant ($h:3483)", LecteurSlim.connecte)
            SystemClock.sleep(((instrumentation as? android.test.InstrumentationTestRunner)?.arguments?.getString("duree")?.toLongOrNull() ?: 25L) * 1000)
        } finally {
            LecteurSlim.arreter(); cfg.nom = avant
        }
    }
}
