// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.test.InstrumentationTestCase

/**
 * 1.90.3 (télé d'essai) : contre le vrai Home Assistant (IP passée par `-e hote 10.1.1.x`, sinon rien n'est
 * essayé) — le certificat présenté sur l'IP donne l'adresse HTTPS, vérifiée par une vraie connexion ; l'IP brute en
 * http ne répond pas quand HA n'écoute qu'en HTTPS.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.HoteLocalReelTest -e hote 10.1.1.11 org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class HoteLocalReelTest : InstrumentationTestCase() {
    private fun hote(): String? = (instrumentation as? android.test.InstrumentationTestRunner)?.arguments?.getString("hote")

    fun testAdresseHttpsDepuisLeCertificat() {
        val ip = hote() ?: return
        val noms = HoteLocal.nomsCertificat(ip, 8123)
        assertTrue("aucun nom de certificat sur $ip:8123", noms.isNotEmpty())
        val https = HoteLocal.adressesHttps(ip, 8123)
        assertEquals("adresse HTTPS : $https (noms : $noms)", 1, https.size)
        assertTrue(https[0].startsWith("https://") && !Reseau.estPrivee(https[0]))
        assertTrue(HoteLocal.estLaNotre(HoteLocal.Annonce(ip, 8123, emptyList()), noms, https))
    }
}
