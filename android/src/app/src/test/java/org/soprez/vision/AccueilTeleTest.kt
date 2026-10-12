// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.75.0 : l'accueil télé part vide à l'installation, et seulement là. */
class AccueilTeleTest {
    @Test
    fun installationNeuvePartVide() = assertEquals(Accueil.MODE_CHOISIES, Accueil.modeTeleDepart(premiereInstallation = true, dejaPersonnalise = false))

    @Test
    fun miseAJourGardeLAccueil() = assertEquals(Accueil.MODE_MASQUEES, Accueil.modeTeleDepart(premiereInstallation = false, dejaPersonnalise = false))

    @Test
    fun accueilRetoucheGarde() = assertEquals(Accueil.MODE_MASQUEES, Accueil.modeTeleDepart(premiereInstallation = true, dejaPersonnalise = true))
}
