// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Un VPN strictement local : il ne capte que le DNS, rien d'autre ne passe par
 * lui. Chaque requête est inspectée. Un domaine bloqué reçoit « n'existe pas » ;
 * les moteurs de recherche sont redirigés vers leur version sûre (SafeSearch) ;
 * tout le reste est transmis au résolveur choisi, sans y toucher.
 *
 * On ne route qu'une seule adresse DNS virtuelle dans le tunnel : le trafic réel
 * (vidéo, jeux) ne traverse jamais Vision, donc pas de ralentissement.
 */
class DnsVpnService : VpnService() {

    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var tourne = false
    private var pompe: Thread? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == STOP) { arreterTout(); return START_NOT_STICKY }
        if (tourne) return START_STICKY
        demarrerTunnel()
        return START_STICKY
    }

    override fun onDestroy() { arreterTout(); super.onDestroy() }
    override fun onRevoke() { arreterTout(); super.onRevoke() }

    private fun demarrerTunnel() {
        val b = Builder()
            .setSession("Vision")
            .addAddress(ADRESSE_TUN, 32)
            .addDnsServer(DNS_VIRTUEL)
            .addRoute(DNS_VIRTUEL, 32)
            .setMtu(1500)
        // On s'exclut nous-mêmes : l'agent parle à HA sans repasser par le tunnel.
        try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
        // Les applis de télé qui refusent tout réseau « VPN » (OQEE / Free TV sur Android 9 ne voit plus le Wi-Fi) :
        // elles passent à côté du tunnel. Leur blocage reste celui des applis, pas du DNS.
        for (pkg in HORS_TUNNEL) try { b.addDisallowedApplication(pkg) } catch (_: Exception) {}
        // Les réseaux sous le tunnel restent ceux du système (par défaut) : déclarer le réseau actif à la main
        // pouvait désigner l'ancien tunnel au redémarrage du VPN et casser la résolution sur Android 9.
        val t = try { b.establish() } catch (_: Exception) { null } ?: run {
            actif = false; stopSelf(); return
        }
        tunnel = t
        tourne = true
        actif = true
        pompe = Thread { boucle(t) }.apply { isDaemon = true; start() }
    }

    private fun arreterTout() {
        tourne = false
        actif = false
        pompe?.interrupt()
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        stopSelf()
    }

    // ---------------------------------------------------- Boucle paquets

    private fun boucle(t: ParcelFileDescriptor) {
        val entree = FileInputStream(t.fileDescriptor)
        val sortie = FileOutputStream(t.fileDescriptor)
        val tampon = ByteArray(32767)
        while (tourne) {
            val n = try { entree.read(tampon) } catch (_: Exception) { break }
            if (n <= 0) { Thread.sleep(5); continue }
            try { traiter(tampon, n, sortie) } catch (_: Exception) {}
        }
    }

    /** On ne gère que l'IPv4 + UDP + port 53. Le reste, on l'ignore (rien d'autre n'est routé). */
    private fun traiter(paquet: ByteArray, taille: Int, sortie: FileOutputStream) {
        if (taille < 28) return
        val version = (paquet[0].toInt() shr 4) and 0xF
        if (version != 4) return
        val ihl = (paquet[0].toInt() and 0xF) * 4
        if (paquet[9].toInt() and 0xFF != 17) return           // UDP
        val portDst = ((paquet[ihl + 2].toInt() and 0xFF) shl 8) or (paquet[ihl + 3].toInt() and 0xFF)
        if (portDst != 53) return

        val dnsDebut = ihl + 8
        val dns = paquet.copyOfRange(dnsDebut, taille)
        val question = lireQuestion(dns) ?: return
        val (hote, finNom) = question
        val qtype = if (finNom + 2 < dns.size) u16(dns, finNom + 1) else 0

        enregistrer(hote)
        val (bloque, dangereux) = Etat.domaineBloque(hote)

        val reponseDns: ByteArray = when {
            bloque -> {
                if (dangereux) synchronized(Etat.alertes) { Etat.alertes.add(hote) } else Etat.noterBlocage(hote)
                nxdomain(dns)
            }
            else -> {
                // SafeSearch ne vaut que pour les adresses (A, AAAA) : une autre
                // question (HTTPS, TXT…) passe telle quelle, sur le nom d'origine.
                val cible = if (qtype == 1 || qtype == 28) safeSearch(hote) else null
                if (cible == null) {
                    resoudre(dns) ?: return
                } else {
                    val amont = resoudre(reecrire(dns, cible))
                    (if (amont != null) recoller(amont, dns, cible) else null)
                        ?: (resoudre(dns) ?: return)
                }
            }
        }
        ecrireReponse(paquet, ihl, dnsDebut, reponseDns, sortie)
    }

    private fun enregistrer(hote: String) {
        if (hote.isEmpty()) return
        synchronized(Etat.sitesVus) {
            if (Etat.sitesVus.size < 400) Etat.sitesVus.add(hote.lowercase(Locale.ROOT))
        }
    }

    // ------------------------------------------------- Résolution amont

    private fun resoudre(requete: ByteArray): ByteArray? {
        val serveurs = (Etat.dns + REPLIS).distinct()
        for (s in serveurs) {
            try {
                DatagramSocket().use { sock ->
                    protect(sock)
                    sock.soTimeout = 4000
                    val adr = InetAddress.getByName(s)
                    sock.send(DatagramPacket(requete, requete.size, adr, 53))
                    val buf = ByteArray(2048)
                    val rep = DatagramPacket(buf, buf.size)
                    sock.receive(rep)
                    return buf.copyOf(rep.length)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // ---------------------------------------------------- SafeSearch

    /**
     * Hôte à rediriger vers sa version sûre, sinon null. Seulement si Home
     * Assistant le demande pour cet appareil, et seulement sur les hôtes
     * exacts que Google, Bing et DuckDuckGo documentent : rediriger tout
     * `*.google.com` casserait le Play Store, Gmail et le reste.
     */
    private fun safeSearch(hote: String): String? {
        if (!Etat.safeSearch) return null
        return SAFE[hote.lowercase(Locale.ROOT).trimEnd('.')]
    }

    /** Remplace le nom demandé par le nom « sûr » dans une copie de la requête. */
    private fun reecrire(dns: ByteArray, cible: String): ByteArray {
        val sortie = ByteBuffer.allocate(dns.size + cible.length + 8)
        sortie.put(dns, 0, 12)                                 // en-tête inchangé
        for (part in cible.split('.')) {
            sortie.put(part.length.toByte()); sortie.put(part.toByteArray(Charsets.US_ASCII))
        }
        sortie.put(0)
        // Recopie qtype + qclass depuis la question d'origine.
        var i = 12
        while (i < dns.size && dns[i].toInt() != 0) i += (dns[i].toInt() and 0xFF) + 1
        i += 1
        sortie.put(dns, i, 4)
        return sortie.array().copyOf(sortie.position())
    }

    /**
     * Rebâtit une réponse pour le nom **d'origine** à partir de celle obtenue
     * pour le nom sûr : la question d'origine, un CNAME vers le nom sûr, puis
     * les adresses. Android rejette une réponse dont la question ne correspond
     * pas à sa demande — c'est ce qui laissait YouTube sans connexion.
     * Les noms sont réécrits sans compression : les pointeurs de la réponse
     * amont ne vaudraient plus rien une fois les octets décalés.
     */
    private fun recoller(amont: ByteArray, requeteOrig: ByteArray, cible: String): ByteArray? {
        val (nomOrig, finNom) = lireQuestion(requeteOrig) ?: return null
        val finQuestion = finNom + 5                           // octet nul + qtype + qclass
        if (finQuestion > requeteOrig.size || amont.size < 12) return null

        var off = 12
        repeat(u16(amont, 4)) { off = sauterNom(amont, off) ?: return null; off += 4 }
        val enregistrements = ArrayList<ByteArray>()
        repeat(u16(amont, 6)) {
            val (nom, apres) = lireNom(amont, off) ?: return null
            if (apres + 10 > amont.size) return null
            val type = u16(amont, apres)
            val classe = u16(amont, apres + 2)
            val ttl = amont.copyOfRange(apres + 4, apres + 8)
            val lenData = u16(amont, apres + 8)
            val debutData = apres + 10
            if (debutData + lenData > amont.size) return null
            val data: ByteArray? = when (type) {
                1, 28 -> amont.copyOfRange(debutData, debutData + lenData)
                5 -> encoderNom(lireNom(amont, debutData)?.first ?: return null)
                else -> null
            }
            if (data != null) enregistrements.add(rr(nom, type, classe, ttl, data))
            off = debutData + lenData
        }

        val sortie = java.io.ByteArrayOutputStream(512)
        sortie.write(requeteOrig, 0, 2)                        // identifiant du client
        sortie.write(0x81)                                     // QR=1, RD=1
        sortie.write(0x80 or (amont[3].toInt() and 0x0F))      // RA=1, RCODE amont
        val nb = 1 + enregistrements.size
        sortie.write(0); sortie.write(1)                       // 1 question
        sortie.write(nb shr 8); sortie.write(nb and 0xFF)      // réponses
        sortie.write(0); sortie.write(0); sortie.write(0); sortie.write(0)
        sortie.write(requeteOrig, 12, finQuestion - 12)        // question d'origine
        val ttlCname = byteArrayOf(0, 0, 0, 60)
        sortie.write(rr(nomOrig, 5, 1, ttlCname, encoderNom(cible)))
        for (e in enregistrements) sortie.write(e)
        return sortie.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Un enregistrement complet, nom en clair. */
    private fun rr(nom: String, type: Int, classe: Int, ttl: ByteArray, data: ByteArray): ByteArray {
        val n = encoderNom(nom)
        val b = ByteBuffer.allocate(n.size + 10 + data.size)
        b.put(n)
        b.putShort(type.toShort()); b.putShort(classe.toShort())
        b.put(ttl)
        b.putShort(data.size.toShort())
        b.put(data)
        return b.array()
    }

    private fun encoderNom(nom: String): ByteArray {
        val b = ByteBuffer.allocate(nom.length + 2)
        for (part in nom.trimEnd('.').split('.')) {
            if (part.isEmpty()) continue
            b.put(part.length.toByte()); b.put(part.toByteArray(Charsets.US_ASCII))
        }
        b.put(0)
        return b.array().copyOf(b.position())
    }

    /** Lit un nom (compression comprise) ; renvoie le nom et l'offset qui suit dans le flux. */
    private fun lireNom(b: ByteArray, depart: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = depart
        var suivant = -1
        var sauts = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0) { i += 1; break }
            if (len and 0xC0 == 0xC0) {
                if (i + 1 >= b.size || ++sauts > 20) return null
                if (suivant < 0) suivant = i + 2
                i = ((len and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF)
                continue
            }
            if (i + 1 + len > b.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return sb.toString() to (if (suivant >= 0) suivant else i)
    }

    private fun sauterNom(b: ByteArray, depart: Int): Int? = lireNom(b, depart)?.second

    // ---------------------------------------------------- Fabrication DNS

    private fun nxdomain(requete: ByteArray): ByteArray {
        val r = requete.copyOf()
        r[2] = (0x81).toByte()                                 // QR=1, RD=1
        r[3] = (0x83).toByte()                                 // RA=1, RCODE=3 (NXDOMAIN)
        r[6] = 0; r[7] = 0; r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
        return r
    }

    private fun lireQuestion(dns: ByteArray): Pair<String, Int>? {
        if (dns.size < 13) return null
        val sb = StringBuilder()
        var i = 12
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0) return null                 // compression dans une question : rare
            if (i + len + 1 > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i + 1, len, Charsets.US_ASCII))
            i += len + 1
        }
        return sb.toString() to i
    }

    /** Réécrit l'en-tête IP/UDP en inversant source et destination, et renvoie. */
    private fun ecrireReponse(orig: ByteArray, ihl: Int, dnsDebut: Int, dns: ByteArray, sortie: FileOutputStream) {
        val total = ihl + 8 + dns.size
        val p = ByteArray(total)
        System.arraycopy(orig, 0, p, 0, ihl + 8)
        System.arraycopy(dns, 0, p, dnsDebut, dns.size)

        // Longueur IP totale.
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        // Inverse les adresses IP (octets 12..15 source, 16..19 dest).
        for (k in 0 until 4) { val t = p[12 + k]; p[12 + k] = p[16 + k]; p[16 + k] = t }
        // Inverse les ports (source et dest sur 2 octets chacun).
        for (k in 0 until 2) { val t = p[ihl + k]; p[ihl + k] = p[ihl + 2 + k]; p[ihl + 2 + k] = t }
        // Longueur UDP.
        val lenUdp = 8 + dns.size
        p[ihl + 4] = (lenUdp shr 8).toByte(); p[ihl + 5] = lenUdp.toByte()
        p[ihl + 6] = 0; p[ihl + 7] = 0                         // somme UDP optionnelle en IPv4

        // Somme de contrôle IP.
        p[10] = 0; p[11] = 0
        var somme = 0L
        var k = 0
        while (k < ihl) { somme += ((p[k].toInt() and 0xFF) shl 8) or (p[k + 1].toInt() and 0xFF); k += 2 }
        while (somme shr 16 != 0L) somme = (somme and 0xFFFF) + (somme shr 16)
        val inv = somme.inv().toInt()
        p[10] = (inv shr 8).toByte(); p[11] = inv.toByte()

        sortie.write(p, 0, total)
        sortie.flush()
    }

    companion object {
        @Volatile var actif = false
        private const val ADRESSE_TUN = "10.111.0.1"
        private const val DNS_VIRTUEL = "10.111.0.53"
        private const val STOP = "org.soprez.vision.STOP_VPN"
        private val REPLIS = listOf("1.1.1.3", "1.0.0.3")      // Cloudflare familial par défaut
        /** Applis laissées hors du tunnel DNS (elles refusent un réseau VPN). */
        private val HORS_TUNNEL = listOf(
            "net.oqee.androidtv.store", "net.oqee.androidtv", "net.oqee.android",
            // Vidéo à la demande : derrière un VPN, elles affichent « problème de connexion ».
            "com.netflix.ninja", "com.netflix.mediaclient",
            "com.wbd.stream", "com.hbo.hbonow", "com.hbo.max.android.tv",
            "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient",
            "com.canal.android.canal", "com.apple.atve.androidtv.appletv", "com.cbs.ca", "com.paramountplus.android",
            "tv.molotov.app", "fr.tf1.mytf1", "fr.francetv.pluzz", "fr.m6.m6replay", "com.arte.tv", "tv.arte.plus7",
            // Musique en ligne : derrière le tunnel, seuls les titres téléchargés se lançaient.
            "com.spotify.music", "com.spotify.tv.android", "deezer.android.app", "deezer.android.tv",
            "com.apple.android.music", "com.amazon.mp3", "com.soundcloud.android", "com.qobuz.music", "com.aspiro.tidal",
        )

        /**
         * Hôtes exacts vers leur version sûre, tels que documentés par Google
         * (SafeSearch VIP, YouTube Restricted Mode), Bing et DuckDuckGo.
         */
        private val SAFE = hashMapOf(
            "google.com" to "forcesafesearch.google.com",
            "www.google.com" to "forcesafesearch.google.com",
            "google.fr" to "forcesafesearch.google.com",
            "www.google.fr" to "forcesafesearch.google.com",
            "youtube.com" to "restrict.youtube.com",
            "www.youtube.com" to "restrict.youtube.com",
            "m.youtube.com" to "restrict.youtube.com",
            "youtubei.googleapis.com" to "restrict.youtube.com",
            "youtube.googleapis.com" to "restrict.youtube.com",
            "www.youtube-nocookie.com" to "restrict.youtube.com",
            "bing.com" to "strict.bing.com",
            "www.bing.com" to "strict.bing.com",
            "duckduckgo.com" to "safe.duckduckgo.com",
            "www.duckduckgo.com" to "safe.duckduckgo.com",
        )

        /** Le VPN a-t-il déjà été autorisé par l'utilisateur ? */
        fun autorise(ctx: Context): Boolean = prepare(ctx) == null

        fun demarrer(ctx: Context) {
            if (!autorise(ctx)) return
            try { ctx.startService(Intent(ctx, DnsVpnService::class.java)) } catch (_: Exception) {}
        }

        fun arreter(ctx: Context) {
            try { ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(STOP)) } catch (_: Exception) {}
        }
    }
}
