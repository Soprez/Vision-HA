// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.SystemClock
import android.test.InstrumentationTestCase

/**
 * 1.86.11 : Vision, lecteur de Music Assistant (SlimProto). Le juge est le banc Python (le vrai serveur aioslimproto de
 * Music Assistant, sur le PC) : il pilote Vision et écrit « BANC OK » ; ici on relie Vision au banc et on suit l'état.
 */
class LecteurSlimTest : InstrumentationTestCase() {
    fun testLecteurPiloteParLeServeur() {
        val ctx = instrumentation.targetContext
        LecteurSlim.arreter(); SystemClock.sleep(500)
        LecteurSlim.demarrer(ctx, "10.0.2.2")
        val vus = LinkedHashSet<String>()
        val fin = SystemClock.elapsedRealtime() + 150_000
        while (SystemClock.elapsedRealtime() < fin) {
            vus.add(LecteurSlim.etat + (if (LecteurSlim.connecte) "" else " (non relié)"))
            if ("lecture" in vus && LecteurSlim.etat == "arrêt" && vus.contains("pause")) break
            SystemClock.sleep(200)
        }
        LecteurSlim.arreter()
        assertTrue("états vus : $vus", "lecture" in vus)  // le juge détaillé est le banc Python (banc_slim.py)
    }
}
