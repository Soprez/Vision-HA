// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase

/**
 * 1.93.06 (Pixel 8a de Alex : Snapchat sans réseau depuis le 13/10, « APP_BACKGROUND|METERED_USER_RESTRICTED ») : le
 * VPN DNS de Vision ne doit pas être « facturé à l'usage ». Sinon, depuis Android 10, tun0 rend le Wi-Fi facturé, et les
 * applis aux données d'arrière-plan limitées perdent le réseau. Sur un émulateur en Wi-Fi (non facturé) :
 * - le réseau VPN a NET_CAPABILITY_NOT_METERED ;
 * - tun0 n'est pas dans les « Metered ifaces » de dumpsys connectivity.
 * Le VPN est autorisé par appops (sans la boîte de dialogue), puis arrêté et l'autorisation rendue.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.VpnNonFactureTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class VpnNonFactureTest : InstrumentationTestCase() {
    private val ctx: Context get() = instrumentation.targetContext

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { String(it.readBytes()) }

    fun testLeVpnPrendLEtatNonFactureDuWifi() {
        if (Build.VERSION.SDK_INT < 29) return  // setMetered n'existe qu'à partir d'Android 10
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        try {
            assertTrue("le VPN n'est pas autorisé", DnsVpnService.autorise(ctx))
            // Dans l'appli, le service de l'agent (au premier plan) démarre le VPN ; ici, une liste blanche temporaire.
            shell("cmd deviceidle tempwhitelist -d 60000 ${ctx.packageName}")
            DnsVpnService.demarrer(ctx)
            // Vision est exclue de son propre tunnel : on lit l'état du système (dumpsys), comme sur le Pixel de Alex.
            var vpn = ""
            var factures = ""
            val fin = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < fin) {
                val dump = shell("dumpsys connectivity")
                vpn = dump.lineSequence().firstOrNull { it.contains("NetworkAgentInfo{") && it.contains("VPN CONNECTED") && it.contains("tun0") }.orEmpty()
                factures = dump.lineSequence().firstOrNull { it.contains("Metered ifaces") }.orEmpty()
                // Le tunnel prend l'état de son réseau sous-jacent dès que celui-ci est déclaré.
                if (vpn.isNotEmpty() && vpn.contains("UnderlyingNetworks: [")) break
                SystemClock.sleep(500)
            }
            assertTrue("pas de réseau VPN connecté", vpn.isNotEmpty())
            val capacites = Regex("Capabilities: ([A-Z_&]+)").find(vpn.substringAfter("Transports: VPN"))?.groupValues?.get(1).orEmpty()
            assertTrue("le VPN est facturé à l'usage : $capacites", "NOT_METERED" in capacites.split('&'))
            assertTrue("le VPN ne déclare pas son réseau sous-jacent : $vpn", vpn.contains("UnderlyingNetworks: ["))
            assertFalse("tun0 compte comme facturé : $factures", factures.contains("tun0"))
            // Une appli aux données d'arrière-plan limitées (comme Snapchat chez Alex), en Wi-Fi et VPN actif : le
            // système ne la bloque pas (METERED_USER_RESTRICTED ne vaut que sur un réseau facturé).
            val (pkg, uid) = listOf("com.android.chrome", "com.google.android.apps.messaging", "com.android.vending").firstNotNullOfOrNull { p ->
                Regex("package:${Regex.escape(p)} uid:(\\d+)").find(shell("pm list packages -U $p"))?.groupValues?.get(1)?.let { p to it }
            } ?: (null to null)
            android.util.Log.i("VpnNonFactureTest", "appli limitée en arrière-plan : $pkg ($uid)")
            if (uid != null) {
                shell("cmd netpolicy add restrict-background-blacklist $uid")
                try {
                    SystemClock.sleep(1_000)
                    // netpolicy garde la raison « METERED_USER_RESTRICTED » sur l'appli ; ConnectivityService ne l'applique
                    // que sur un réseau facturé. Le critère est donc celui du relevé du Pixel : le tunnel et ses interfaces
                    // ne sont pas facturés, même avec une appli limitée (trace dans le journal, pour la remise).
                    val ligne = Regex("(?i)\\buid[=: ]+$uid\\b")
                    val etat = shell("dumpsys netpolicy").lineSequence().filter { ligne.containsMatchIn(it) }.map { it.trim().take(300) }.toList()
                    android.util.Log.i("VpnNonFactureTest", "uid $uid limité en arrière-plan : ${etat.joinToString(" | ")}")
                    val apres = shell("dumpsys connectivity").lineSequence().firstOrNull { it.contains("Metered ifaces") }.orEmpty()
                    assertFalse("tun0 facturé avec une appli limitée : $apres", apres.contains("tun0"))
                } finally { shell("cmd netpolicy remove restrict-background-blacklist $uid") }
            }
        } finally {
            DnsVpnService.arreter(ctx)
            SystemClock.sleep(1_500)
            shell("appops set ${ctx.packageName} ACTIVATE_VPN default")
        }
        // L'arrêt du VPN ne fait plus planter l'appli (le test tourne dans son processus : il serait mort ici).
        assertFalse("le VPN tourne encore", shell("dumpsys connectivity").lineSequence().any { it.contains("VPN CONNECTED") && it.contains("org.soprez.vision") })
    }
}
