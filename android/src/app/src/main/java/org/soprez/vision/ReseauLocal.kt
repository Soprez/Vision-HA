// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants

/**
 * 1.93.06 (TV du salon, 18/10) : le réseau qui a VRAIMENT une connectivité IP.
 *
 * Un câble Ethernet branché mais sans adresse (box éteinte, pas de DHCP) pouvait être pris pour le réseau de
 * l'appareil alors que le Wi-Fi avait la sienne : le DNS de Vision partait dans le vide, et les messages disaient
 * « Home Assistant injoignable, réessaie » sans cause. Ici :
 * - [choisir] : le réseau par défaut s'il est validé par Android et a une adresse, sinon un autre réseau validé ;
 *   jamais un VPN (le tunnel de Vision lui-même) ;
 * - [Suivi] : suit les changements (NetworkCallback) et prévient quand le réseau choisi change ;
 * - [message] : « réseau coupé » (aucun réseau validé, ou ENETUNREACH) se distingue de « Home Assistant ne répond pas ».
 */
object ReseauLocal {

    /** Ce qu'Android dit d'un réseau, réduit à ce qui décide (pur, pour les essais). */
    data class Candidat(
        val id: Long,
        val internet: Boolean,
        val valide: Boolean,
        val vpn: Boolean,
        val adresse: Boolean,
    ) {
        val utilisable: Boolean get() = internet && valide && !vpn && adresse
    }

    /** Le réseau à suivre : le réseau par défaut s'il est utilisable, sinon le premier réseau utilisable, sinon aucun. */
    fun choisir(candidats: List<Candidat>, defaut: Long?): Long? {
        candidats.firstOrNull { it.id == defaut && it.utilisable }?.let { return it.id }
        return candidats.firstOrNull { it.utilisable }?.id
    }

    enum class Cause { RESEAU_COUPE, HA_MUET }

    /** ENETUNREACH (« Network is unreachable ») quelque part dans la chaîne des causes : l'appareil n'a pas de route. */
    fun sansRoute(e: Throwable?): Boolean {
        var c = e
        var n = 0
        while (c != null && n++ < 8) {
            if (c is ErrnoException && c.errno == OsConstants.ENETUNREACH) return true
            val m = c.message.orEmpty()
            if ("ENETUNREACH" in m || "Network is unreachable" in m) return true
            c = c.cause
        }
        return false
    }

    /** Pur : la connectivité locale d'abord ; si elle est bonne, c'est Home Assistant qui ne répond pas. */
    fun cause(aUnReseau: Boolean, e: Throwable?): Cause =
        if (!aUnReseau || sansRoute(e)) Cause.RESEAU_COUPE else Cause.HA_MUET

    fun cause(ctx: Context, e: Throwable?): Cause = cause(aUnReseau(ctx), e)

    /** Le texte à montrer quand Home Assistant n'a pas été joint : la cause, et ce qui reste vrai (rien n'a changé). */
    fun message(ctx: Context, e: Throwable?): String = when (cause(ctx, e)) {
        Cause.RESEAU_COUPE -> messageCoupe(ctx)
        Cause.HA_MUET -> ctx.getString(R.string.reseau_ha_muet)
    }

    fun messageCoupe(ctx: Context): String = ctx.getString(
        if (ReglagesTvActivity.estTele(ctx)) R.string.reseau_coupe_tele else R.string.reseau_coupe_appareil)

    /** Un réseau validé, avec une adresse, existe-t-il ? (hors VPN) */
    fun aUnReseau(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT < 23) {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            } else {
                @Suppress("DEPRECATION")
                cm.allNetworks.any { candidat(cm, it, it.networkHandle).utilisable }
            }
        } catch (_: Exception) { true }  // dans le doute, ne pas accuser le réseau
    }

    /** Le réseau validé à utiliser maintenant (null : aucun, ou Android trop ancien pour le savoir). */
    fun reseauValide(ctx: Context): Network? {
        if (Build.VERSION.SDK_INT < 23) return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            @Suppress("DEPRECATION")
            val tous = cm.allNetworks.toList()
            val id = choisir(tous.map { candidat(cm, it, it.networkHandle) }, cm.activeNetwork?.networkHandle)
            tous.firstOrNull { it.networkHandle == id }
        } catch (_: Exception) { null }
    }

    private fun candidat(cm: ConnectivityManager, n: Network, id: Long): Candidat {
        val caps = cm.getNetworkCapabilities(n)
        return Candidat(
            id = id,
            internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            valide = Build.VERSION.SDK_INT >= 23 && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            adresse = aUneAdresse(cm.getLinkProperties(n)),
        )
    }

    /** Une adresse routable (ni de lien local, ni de bouclage) : un câble branché sans DHCP n'en a pas. */
    fun aUneAdresse(lp: LinkProperties?): Boolean =
        lp?.linkAddresses?.any { !it.address.isLinkLocalAddress && !it.address.isLoopbackAddress } == true

    /**
     * Suit le réseau validé : rappelé (sur un fil d'Android) à chaque changement du réseau choisi, y compris vers
     * « aucun ». Android 7 (API 24) et plus ; avant, [demarrer] ne fait rien et le système choisit seul.
     */
    class Suivi(private val ctx: Context, private val change: (Network?) -> Unit) {
        private var rappel: ConnectivityManager.NetworkCallback? = null
        private var rappelDefaut: ConnectivityManager.NetworkCallback? = null
        @Volatile var actuel: Network? = null
            private set

        fun demarrer() {
            if (Build.VERSION.SDK_INT < 24 || rappel != null) return
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val r = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = revoir()
                override fun onLost(network: Network) = revoir()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = revoir()
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = revoir()
            }
            val d = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = revoir()
                override fun onLost(network: Network) = revoir()
            }
            try {
                cm.registerNetworkCallback(NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), r)  // la demande par défaut exclut déjà les VPN (NOT_VPN)
                rappel = r
                cm.registerDefaultNetworkCallback(d)
                rappelDefaut = d
            } catch (_: Exception) {}
            revoir()
        }

        fun arreter() {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            for (r in listOfNotNull(rappel, rappelDefaut)) try { cm.unregisterNetworkCallback(r) } catch (_: Exception) {}
            rappel = null
            rappelDefaut = null
        }

        @Synchronized
        private fun revoir() {
            val n = reseauValide(ctx)
            if (n == actuel) return
            actuel = n
            try { change(n) } catch (_: Exception) {}
        }
    }
}
