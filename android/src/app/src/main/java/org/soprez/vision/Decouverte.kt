// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/**
 * Trouve Home Assistant sur le réseau local (annonce mDNS `_home-assistant._tcp`).
 *
 * Refonte UX, lot 1 (APP-03) : chercher n'engage à rien. [chercher] ne touche ni aux réglages ni au serveur
 * trouvé : il rend un [Serveur], et l'écran demande « Se relier à « … » ? » [Se relier] / [Pas celui-ci].
 * Seul [relier], appelé après le choix explicite de la personne devant l'écran, pose l'adresse et autorise
 * l'inscription sans clé ([Liaison.peutInscrire]). Avant (jusqu'à 1.86.15), le premier lancement contactait le
 * serveur trouvé et tentait de s'y inscrire sans rien demander.
 */
object Decouverte {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var enCours = false
    /** Ce qui s'est passé, pour l'écran : vide, « recherche », ou la raison de l'échec. */
    @Volatile var etat = ""
    /** Le dernier serveur trouvé, pas encore accepté ni écarté (l'écran pose la question). */
    @Volatile var trouve: Liaison.Serveur? = null
    /** La recherche est allée au bout du délai sans rien trouver (APP-05 : l'écran propose Réessayer / Autres méthodes). */
    @Volatile var finie = false

    private val attentes = ArrayList<(Liaison.Serveur?) -> Unit>()
    private fun rendre(s: Liaison.Serveur?) {
        val l = synchronized(attentes) { ArrayList(attentes).also { attentes.clear() } }
        l.forEach { try { it(s) } catch (_: Exception) {} }
    }

    /** APP-05 : au-delà, la recherche s'arrête et l'écran le dit (avant : 9 s, sans fin visible sur certaines télés). */
    const val DELAI_MS = 15_000L

    /** Une adresse du réseau local (et non l'adresse publique). */
    fun estLocale(url: String): Boolean = Reseau.estPrivee(url)

    /**
     * Cherche Home Assistant sur le réseau, au plus [DELAI_MS]. Aucun effet : ni réglage posé, ni requête au serveur.
     * `fin(serveur)` : le serveur trouvé (non écarté), ou null.
     */
    fun chercher(ctx: Context, fin: (Liaison.Serveur?) -> Unit) {
        val app = ctx.applicationContext
        if (Config(app).inscrit) { fin(null); return }
        synchronized(attentes) { attentes.add(fin); if (enCours) return }
        enCours = true; finie = false; trouve = null; etat = Tr.s(R.string.decouverte_recherche_delai, (DELAI_MS / 1000).toInt())
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) { enCours = false; finie = true; etat = Tr.s(R.string.decouverte_impossible); rendre(null); return }
        val urls = LinkedHashSet<String>()
        var nom = ""
        var termine = false
        var ecouteur: NsdManager.DiscoveryListener? = null

        fun conclure() {
            if (termine) return
            termine = true
            try { ecouteur?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
            enCours = false
            val s = Liaison.serveur(nom, urls)
            if (s == null || Liaison.estEcarte(s)) {
                finie = true
                etat = Tr.s(R.string.decouverte_introuvable)
                rendre(null); return
            }
            trouve = s
            etat = Tr.s(R.string.liaison_question, s.affiche())
            rendre(s)
        }

        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { main.post { conclure() } }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            fun attribut(k: String) = try { si.attributes[k]?.let { String(it, Charsets.UTF_8).trim().trimEnd('/') } ?: "" } catch (_: Exception) { "" }
                            // L'adresse publique d'abord (certificat valide), puis l'interne, puis l'adresse brute.
                            for (k in listOf("external_url", "internal_url", "base_url")) attribut(k).takeIf { it.startsWith("http") }?.let { urls.add(it) }
                            @Suppress("DEPRECATION")
                            val ip = si.host?.hostAddress
                            if (!ip.isNullOrEmpty() && !ip.contains(':')) { urls.add("http://$ip:${si.port}") }
                            if (nom.isEmpty()) nom = attribut("location_name").ifEmpty { si.serviceName.orEmpty() }
                            main.postDelayed({ conclure() }, 400)
                        }
                    })
                } catch (_: Exception) {}
            }
        }
        ecouteur = l
        try { nsd.discoverServices("_home-assistant._tcp", NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { conclure(); return }
        main.postDelayed({ conclure() }, DELAI_MS)
    }

    /**
     * La personne a touché « Se relier » : l'adresse est posée et l'inscription sans clé est permise. Home Assistant
     * ne l'accepte que depuis le réseau local et si les inscriptions sont ouvertes.
     */
    fun relier(ctx: Context, s: Liaison.Serveur) {
        val app = ctx.applicationContext
        val cfg = Config(app)
        cfg.urlExterne = s.externe; cfg.urlInterne = s.interne
        cfg.cleInscription = ""
        cfg.sansCle = true
        trouve = null
        etat = Tr.s(R.string.decouverte_trouve)
        AgentService.demarrer(app)
    }

    /** « Pas celui-ci » : ce serveur n'est plus proposé (le temps que l'appli tourne). */
    fun ecarter(s: Liaison.Serveur) {
        Liaison.ecarter(s)
        trouve = null; finie = true
        etat = Tr.s(R.string.liaison_ecarte)
    }
}
