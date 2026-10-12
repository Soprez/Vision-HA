// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lot accueil (point 8) : l'accueil d'origine derrière le code, tant que Vision tient l'accueil et que le module est actif. */
class AccueilOrigineTest {
    @Test
    fun codeQuandVisionTientLAccueil() = assertTrue(AccueilDefaut.codePourOrigine(moduleAccueil = true, visionTientAccueil = true))

    @Test
    fun libreQuandLeModuleEstCoupeDansHa() = assertFalse(AccueilDefaut.codePourOrigine(moduleAccueil = false, visionTientAccueil = true))

    @Test
    fun libreQuandVisionNeTientPasLAccueil() = assertFalse(AccueilDefaut.codePourOrigine(moduleAccueil = true, visionTientAccueil = false))
}
