// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.85.0 (chantier Générique) : une maison en 172.16/12, en IPv6 ou sous un nom « .lan » est bien locale. */
class ReseauTest {
    @Test fun adressesDeLaMaison() {
        for (u in listOf("http://192.168.1.10:8123", "http://10.0.0.5", "https://172.20.3.4:8123/x", "http://homeassistant.local:8123",
                "http://ha.lan:8123", "http://ha.home.arpa", "http://[fd00::2]:8123/", "http://[fe80::1]:8123", "http://localhost:8123", "http://169.254.1.2"))
            assertTrue(u, Reseau.estPrivee(u))
    }

    @Test fun adressesPubliques() {
        for (u in listOf("https://ha.exemple.org:8123", "https://8.8.8.8", "https://172.32.0.1", "https://[2001:db8::1]:8123", "https://fdroid.org", "https://192.169.1.1"))
            assertFalse(u, Reseau.estPrivee(u))
    }
}
