// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 1.90.3 (retour terrain, télé d'essai) :
 * - reliée par l'adresse externe (https://ha.exemple.org), la télé n'avait aucune adresse du réseau local : son lecteur
 *   de musique (Squeezelite ou Sendspin, qui parlent à Music Assistant sur le réseau local) ne démarrait jamais ;
 * - la découverte trouvait « http://10.1.1.11:8123 » alors que Home Assistant n'écoute qu'en HTTPS.
 *
 * Ici : l'annonce réseau de Home Assistant (`_home-assistant._tcp`) donne son IP ; on ne la retient que si c'est bien
 * NOTRE Home Assistant (adresse annoncée identique, ou certificat présenté sur cette IP, chaîne validée normalement,
 * au nom de l'adresse externe connue). Le certificat sert aussi à trouver l'adresse HTTPS à proposer à la liaison
 * ([adressesHttps]) : on ne fait confiance à cette adresse qu'après une vraie connexion HTTPS vérifiée.
 */
object HoteLocal {
    private const val TAG = "VisionHoteLocal"
    private const val INTERVALLE_MS = 10 * 60_000L
    @Volatile private var dernierEssai = 0L
    @Volatile private var enCours = false

    /** Une annonce de Home Assistant : IP, port, adresses annoncées. */
    data class Annonce(val ip: String, val port: Int, val urls: List<String>)

    // ------------------------------------------------------------------ pur (testé en JVM)

    /** L'hôte d'une adresse, en minuscules (vide si illisible). */
    fun hote(url: String): String = Reseau.hote(url).lowercase()

    /** Un nom de certificat couvre-t-il cet hôte ? (« *.exemple.org » couvre un seul niveau). */
    fun couvre(nom: String, hote: String): Boolean {
        val n = nom.lowercase().trim(); val h = hote.lowercase().trim()
        if (n.isEmpty() || h.isEmpty()) return false
        if (!n.startsWith("*.")) return n == h
        val reste = n.removePrefix("*.")
        return h.endsWith(".$reste") && !h.removeSuffix(".$reste").contains('.')
    }

    /** Cette annonce est-elle celle du Home Assistant de l'appareil ? (adresses de la config : externe, active…) */
    fun estLaNotre(annonce: Annonce, nomsCertificat: List<String>, adressesConnues: List<String>): Boolean {
        val hotes = adressesConnues.map(::hote).filter { it.isNotEmpty() }.toSet()
        if (hotes.isEmpty()) return false
        if (annonce.urls.any { hote(it) in hotes }) return true
        return hotes.any { h -> !Reseau.estPrivee("http://$h") && nomsCertificat.any { couvre(it, h) } }
    }

    /** Les adresses HTTPS à essayer, d'après les noms du certificat (jamais un nom générique « *. »). */
    fun candidats(nomsCertificat: List<String>, port: Int): List<String> =
        nomsCertificat.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("*.") && it.contains('.') }
            .flatMap { if (port == 443) listOf("https://$it") else listOf("https://$it", "https://$it:$port") }.distinct()

    // ------------------------------------------------------------------ réseau

    /** Les annonces `_home-assistant._tcp` du réseau, en [attente] ms au plus. Hors fil principal. */
    fun annonces(ctx: Context, attente: Long = 6_000): List<Annonce> {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val sortie = java.util.Collections.synchronizedList(ArrayList<Annonce>())
        val fin = CountDownLatch(1)
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { fin.countDown() }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            fun attribut(k: String) = try { si.attributes[k]?.let { String(it, Charsets.UTF_8).trim().trimEnd('/') } ?: "" } catch (_: Exception) { "" }
                            @Suppress("DEPRECATION")
                            val ip = si.host?.hostAddress ?: return
                            if (ip.contains(':')) return
                            sortie.add(Annonce(ip, si.port, listOf("external_url", "internal_url", "base_url").map(::attribut).filter { it.startsWith("http") }))
                        }
                    })
                } catch (_: Exception) {}
            }
        }
        try { nsd.discoverServices("_home-assistant._tcp", NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { return emptyList() }
        fin.await(attente, TimeUnit.MILLISECONDS)
        try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
        return ArrayList(sortie)
    }

    /**
     * Les noms (DNS) du certificat présenté par cette IP. La chaîne est validée par le magasin du système ; seul le
     * nom n'est pas comparé ici (on s'adresse à une IP). Vide : pas de TLS, ou chaîne non reconnue.
     */
    fun nomsCertificat(ip: String, port: Int): List<String> = try {
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { s ->
            s.connect(InetSocketAddress(ip, port), 3_000); s.soTimeout = 4_000
            s.startHandshake()
            val c = s.session.peerCertificates.firstOrNull() as? X509Certificate
            c?.subjectAlternativeNames?.filter { (it[0] as? Int) == 2 }?.mapNotNull { it[1] as? String }.orEmpty()
        }
    } catch (_: Exception) { emptyList() }

    /** Home Assistant répond-il à cette adresse ? (`/api/` : 401 sans jeton, ou 200.) Vérification HTTPS normale. */
    fun repond(url: String): Boolean = try {
        val c = URL(url.trimEnd('/') + "/api/").openConnection() as HttpURLConnection
        c.connectTimeout = 4_000; c.readTimeout = 4_000; c.instanceFollowRedirects = false
        try { c.responseCode in setOf(200, 401) } finally { c.disconnect() }
    } catch (_: Exception) { false }

    /** Pour la découverte : les adresses HTTPS vérifiées de ce Home Assistant (d'après son certificat). */
    fun adressesHttps(ip: String, port: Int): List<String> =
        candidats(nomsCertificat(ip, port), port).filter(::repond).take(1)

    /**
     * Cherche l'IP locale de Home Assistant (au plus une fois toutes les 10 min), et la retient dans la config si c'est
     * bien le nôtre. Sans effet si l'appareil n'est pas relié.
     */
    fun chercher(ctx: Context) {
        val app = ctx.applicationContext
        val maintenant = SystemClock.elapsedRealtime()
        if (enCours || (dernierEssai != 0L && maintenant - dernierEssai < INTERVALLE_MS)) return
        val cfg = Config(app)
        if (cfg.id.isEmpty()) return
        enCours = true; dernierEssai = maintenant
        Thread {
            try {
                val connues = listOf(cfg.urlExterne, cfg.urlActive, cfg.urlInterne).filter { it.isNotEmpty() }
                for (a in annonces(app)) {
                    if (!Reseau.estPrivee("http://${a.ip}")) continue
                    if (estLaNotre(a, nomsCertificat(a.ip, a.port), connues)) {
                        cfg.hoteLocal = a.ip
                        Log.i(TAG, "adresse locale de Home Assistant : ${a.ip}")
                        return@Thread
                    }
                }
                Log.i(TAG, "adresse locale de Home Assistant introuvable")
            } finally { enCours = false }
        }.apply { isDaemon = true }.start()
    }
}
