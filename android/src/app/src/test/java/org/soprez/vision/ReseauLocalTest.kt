// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 1.93.06 (TV du salon, 18/10) : le VPN DNS suit le réseau qui a vraiment une connectivité IP, et les messages
 * distinguent « le réseau de la télé est coupé » de « Home Assistant ne répond pas ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class ReseauLocalTest {
    private val ETH = 1L
    private val WIFI = 2L
    private val TUNNEL = 3L

    private fun eth(adresse: Boolean, valide: Boolean = adresse) = ReseauLocal.Candidat(ETH, true, valide, false, adresse)
    private fun wifi(valide: Boolean = true) = ReseauLocal.Candidat(WIFI, true, valide, false, true)
    private val tunnel = ReseauLocal.Candidat(TUNNEL, true, true, true, true)

    @Test fun un_ethernet_branche_sans_adresse_ne_prend_pas_la_place_du_wifi() {
        // Le cas du salon : le câble est branché (et même réseau « par défaut »), mais sans adresse IP.
        assertEquals(WIFI, ReseauLocal.choisir(listOf(eth(adresse = false), wifi()), defaut = ETH))
        // Branché avec une adresse mais pas encore validé par Android : le Wi-Fi validé reste choisi.
        assertEquals(WIFI, ReseauLocal.choisir(listOf(eth(adresse = true, valide = false), wifi()), defaut = ETH))
    }

    @Test fun le_reseau_par_defaut_valide_garde_la_priorite() {
        assertEquals(ETH, ReseauLocal.choisir(listOf(wifi(), eth(adresse = true)), defaut = ETH))
        assertEquals(WIFI, ReseauLocal.choisir(listOf(wifi(), eth(adresse = true)), defaut = WIFI))
    }

    @Test fun quand_le_reseau_par_defaut_change_le_choix_rebascule() {
        val reseaux = listOf(eth(adresse = true), wifi())
        assertEquals(ETH, ReseauLocal.choisir(reseaux, defaut = ETH))
        // Le câble perd son adresse (box éteinte) : on passe au Wi-Fi sans attendre.
        assertEquals(WIFI, ReseauLocal.choisir(listOf(eth(adresse = false), wifi()), defaut = ETH))
        // Il la retrouve et redevient le réseau par défaut : retour sur le câble.
        assertEquals(ETH, ReseauLocal.choisir(reseaux, defaut = ETH))
    }

    @Test fun jamais_un_vpn_ni_un_reseau_non_valide() {
        assertNull(ReseauLocal.choisir(listOf(tunnel), defaut = TUNNEL))
        assertNull(ReseauLocal.choisir(listOf(eth(adresse = false), wifi(valide = false)), defaut = WIFI))
        assertEquals(WIFI, ReseauLocal.choisir(listOf(tunnel, wifi()), defaut = TUNNEL))
    }

    @Test fun enetunreach_veut_dire_reseau_coupe() {
        val errno = ErrnoException("connect", OsConstants.ENETUNREACH)
        assertTrue(ReseauLocal.sansRoute(ConnectException("failed to connect").apply { initCause(errno) }))
        assertTrue(ReseauLocal.sansRoute(ConnectException("connect failed: ENETUNREACH (Network is unreachable)")))
        assertFalse(ReseauLocal.sansRoute(SocketTimeoutException("timeout")))
        assertFalse(ReseauLocal.sansRoute(null))
    }

    @Test fun la_connectivite_locale_est_testee_d_abord() {
        val delai = SocketTimeoutException("timeout")
        assertEquals(ReseauLocal.Cause.RESEAU_COUPE, ReseauLocal.cause(aUnReseau = false, e = delai))
        assertEquals(ReseauLocal.Cause.RESEAU_COUPE,
            ReseauLocal.cause(aUnReseau = true, e = ConnectException("connect failed: ENETUNREACH")))
        assertEquals(ReseauLocal.Cause.HA_MUET, ReseauLocal.cause(aUnReseau = true, e = delai))
    }

    @Test fun les_messages_disent_la_cause_sans_reessaie() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val coupe = ctx.getString(R.string.reseau_coupe_tele)
        val muet = ctx.getString(R.string.reseau_ha_muet)
        assertEquals("Le réseau de la télé est coupé (vérifie le câble ou le Wi-Fi).", coupe)
        assertTrue(muet.startsWith("Home Assistant ne répond pas"))
        for (t in listOf(coupe, muet, ctx.getString(R.string.reseau_coupe_appareil))) {
            assertFalse(t, t.contains("réessa", ignoreCase = true))
        }
    }

    @Test fun le_vpn_et_les_ecrans_suivent_le_reseau_valide() {
        val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
        val vpn = File(src, "DnsVpnService.kt").readText()
        assertTrue(vpn.contains("ReseauLocal.Suivi(this)") && vpn.contains("bindSocket(sock)") && vpn.contains("setUnderlyingNetworks"))
        // Pixel 8a de Alex : un VPN facturé à l'usage (défaut depuis Android 10) coupait les applis limitées en arrière-plan.
        assertTrue(vpn.contains("if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)"))
        assertTrue("setMetered avant establish", vpn.indexOf("setMetered(false)") < vpn.indexOf("b.establish()"))
        // Plus de « rien n'a changé, réessaie » quand on sait que le réseau est coupé.
        for (f in listOf("Acces.kt", "CategorieActivity.kt", "CategoriesActivity.kt", "DetailFermesActivity.kt",
                "ExceptionsActivity.kt", "ReponseReceiver.kt")) {
            val t = File(src, f).readText()
            assertTrue(f, t.contains("ReseauLocal.message("))
            assertFalse(f, t.contains("R.string.commun_ha_injoignable_rien") || t.contains("R.string.commun_lot2_injoignable"))
        }
    }
}
