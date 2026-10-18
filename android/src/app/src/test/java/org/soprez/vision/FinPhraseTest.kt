package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** Lot 6 : une abréviation en fin de phrase ne double pas son point ; les points de suspension restent. */
class FinPhraseTest {
    @Test fun pointDouble() = assertEquals("Heute noch 1 Std. 20 Min.", Tr.finPhrase("Heute noch 1 Std. 20 Min.."))
    @Test fun suspension() = assertEquals("Chargement...", Tr.finPhrase("Chargement..."))
    @Test fun rienAChanger() = assertEquals("Encore 20 min.", Tr.finPhrase("Encore 20 min."))
}
