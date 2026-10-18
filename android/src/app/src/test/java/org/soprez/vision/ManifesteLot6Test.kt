// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lot 6 (APP-32) : une seule icône de Vision dans le lanceur de la télé, la même entrée que sur le téléphone. */
class ManifesteLot6Test {
    private val manifeste = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()

    @Test fun une_seule_entree_tv() {
        assertEquals(1, Regex("android.intent.category.LEANBACK_LAUNCHER").findAll(manifeste).count())
        val setup = manifeste.substringAfter("android:name=\".SetupActivity\"").substringBefore("</activity>")
        assertTrue(setup.contains("android.intent.category.LEANBACK_LAUNCHER") && setup.contains("android.intent.category.LAUNCHER"))
        val lanceur = manifeste.substringAfter("android:name=\".LanceurActivity\"").substringBefore("</activity>")
        assertTrue("l'accueil reste un HOME", lanceur.contains("android.intent.category.HOME"))
    }
}
