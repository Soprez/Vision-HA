// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.soprez.vision.AjusterEcranActivity
import org.soprez.vision.ZoneSure

/** 17-10 (l'auteur, point 12) : l'écran « Ajuster l'écran » à 5 % et à 9 %, télé 720p et 1080p, polices 100 et 130 %. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AjusterEcranCaptureTest(private val taille: Recette.Taille, private val police: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parametres(): List<Array<Any>> = Recette.combinaisons(Recette.TAILLES.filter { it.tele })
    }

    @Test fun cadre_a_5_et_9_pour_cent() {
        for (zone in listOf(0, 5)) {
            Recette.poser(taille, police)
            ZoneSure.poser(RuntimeEnvironment.getApplication(), zone, zone)
            val a = Robolectric.buildActivity(AjusterEcranActivity::class.java).setup().visible().get()
            val defauts = Recette.capturer(a.window.decorView, "ajuster-ecran-$zone", taille, police)
            a.finish()
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }
}
