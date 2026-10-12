// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.bouncycastle.math.ec.rfc7748.X25519
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client Sendspin natif (lot 6) : l'appairage avec Music Assistant, sans Android (essayé en JUnit).
 *
 * Méthodes offertes dans `client/hello` : la clé d'appairage (`pairing_psk`, toujours) et le PIN affiché
 * (`dynamic_pin`, 6 chiffres au moins, montré sur l'écran). Déroulé du PIN (sendspin-js 5.0.0 / aiosendspin 9.0.0) :
 *  1. `server/activate` (activité « pairing », méthode, longueur du PIN) → Vision envoie `client/pair-init` avec
 *     l'engagement SHA-256 de son aléa nonce_B ;
 *  2. `server/pair-init` (nonce_A) → le PIN est dérivé du hash de la poignée Noise et des deux aléas ; il s'affiche,
 *     l'opérateur le tape dans Music Assistant ;
 *  3. CPace X25519/SHA-512 (`pair-auth`, `pair-confirm`) : le serveur ne prouve sa connaissance du PIN qu'à l'issue ;
 *     un mauvais PIN → `pair/abort` « pin_mismatch » et compteur d'échecs ;
 *  4. `client/pair-finalize` : une clé long terme neuve, chiffrée par la clé dérivée de CPace → gardée par serveur.
 * Après 10 échecs de suite (ou un PIN de moins de 6 chiffres), un geste sur place est exigé ([ouvrirFenetre]).
 */
class SendspinAppairage(private val d: Deps) {
    interface Deps {
        fun envoyer(msg: Map<String, Any?>)
        fun fermer()
        val identite: SendspinIdentite
        fun serverId(): String
        fun categorie(): String
        fun hashPoignee(): ByteArray
        /** Le PIN à montrer (null : le retirer de l'écran). */
        fun pin(pin: String?)
        /** « attente_geste », « commence », « fini », « abandon » (avec la raison). */
        fun evenement(e: String, detail: String? = null)
        fun echecs(): Int
        fun poserEchecs(n: Int)
        /** Une minuterie ; rend de quoi l'annuler. */
        fun minuter(ms: Long, f: () -> Unit): () -> Unit
    }

    private enum class Phase { REPOS, ATTENTE_GESTE, ATTENTE_INIT, ATTENTE_AUTH, ATTENTE_CONFIRM, ATTENTE_FIN }
    private var phase = Phase.REPOS
    private var methode: String? = null
    private var cpace: CPace? = null
    private var nonceB: ByteArray? = null
    private var sid: ByteArray? = null
    private var pskEnAttente: ByteArray? = null
    private var longueurPin = 0
    private var essai = 0
    private var activations = 0
    private var fenetre = false
    private var annulerMinuterie: (() -> Unit)? = null
    private var annulerFenetre: (() -> Unit)? = null

    val enCours get() = phase != Phase.REPOS

    fun methodes(): List<Map<String, Any?>> = listOf(linkedMapOf("method" to "pairing_psk"),
        linkedMapOf("method" to "dynamic_pin", "out_channels" to listOf("display"), "min_pin_length" to PIN_MIN))

    val escalade: Boolean get() = d.echecs() >= ESCALADE

    /** Un `server/activate` : vrai s'il concerne l'appairage (le reste du protocole attend). */
    fun activation(activites: List<String>, appairage: Map<*, *>?): Boolean {
        if ("pairing" !in activites) { abandonner("server_cancelled"); return false }
        abandonner("superseded")
        activations++; essai = activations
        val m = appairage?.get("method") as? String
        if (m == null) { echec(); return true }
        val pskAppairage = d.categorie() == SendspinIdentite.APPAIRAGE
        if ((m == "pairing_psk") != pskAppairage || m !in listOf("pairing_psk", "dynamic_pin")) { abandon("method_not_supported"); return true }
        methode = m
        if (m == "pairing_psk") { finaliser(); phase = Phase.ATTENTE_FIN; minuter(); d.evenement("commence"); return true }
        val l = (appairage["pin_length"] as? Number)?.toInt()
        if (l == null || (appairage["pin_length"] as Number).toDouble() != l.toDouble()) { echec(); return true }
        if (l < PIN_MIN || l > PIN_MAX) { abandon("pin_length_unacceptable"); return true }
        longueurPin = l
        if ((escalade || l < PIN_COURT) && !fenetre) {
            phase = Phase.ATTENTE_GESTE
            d.envoyer(msg("client/pair-pending", "pairing_index" to essai))
            d.evenement("attente_geste")
            return true
        }
        commencer()
        return true
    }

    /** Le geste sur place (télécommande) qui autorise un appairage exigeant (5 min). */
    fun ouvrirFenetre() {
        if (phase == Phase.ATTENTE_GESTE) { commencer(); return }
        fenetre = true
        annulerFenetre?.invoke()
        annulerFenetre = d.minuter(FENETRE_MS) { fenetre = false }
    }

    fun annuler() { if (phase != Phase.REPOS) abandon("user_cancelled") }

    private fun commencer() {
        fenetre = false; annulerFenetre?.invoke(); annulerFenetre = null
        minuter()
        d.evenement("commence")
        val nb = ByteArray(32).also { ALEA.nextBytes(it) }
        nonceB = nb
        phase = Phase.ATTENTE_INIT
        d.envoyer(msg("client/pair-init", "pairing_index" to essai, "commit_B" to SendspinNoise.b64url(engagement(nb))))
    }

    fun pairInit(p: Map<*, *>) {
        if (phase == Phase.REPOS) return
        if (phase != Phase.ATTENTE_INIT || methode != "dynamic_pin") return echec()
        val na = decoder(p["nonce_A"], 32) ?: return echec()
        val h = d.hashPoignee()
        val pin = derivePin(h, na, nonceB!!, longueurPin)
        val s = sidPake(h, essai)
        sid = s
        cpace = CPace.commencer(false, pin.toByteArray(), s, AD_SERVEUR, AD_CLIENT)
        phase = Phase.ATTENTE_AUTH
        d.pin(pin)
    }

    fun pairAuth(p: Map<*, *>) {
        if (phase == Phase.REPOS) return
        val c = cpace
        if (phase != Phase.ATTENTE_AUTH || c == null) return echec()
        val y = decoder(p["pake_msg_1"], 32) ?: return echec()
        d.envoyer(msg("client/pair-auth", "pake_msg_2" to SendspinNoise.b64url(c.part)))
        try { c.deriver(y) } catch (_: Exception) { return echec() }
        phase = Phase.ATTENTE_CONFIRM
    }

    fun pairConfirm(p: Map<*, *>) {
        if (phase == Phase.REPOS) return
        val c = cpace
        if (phase != Phase.ATTENTE_CONFIRM || c == null) return echec()
        val kc = decoder(p["server_kc"], 64) ?: return echec()
        if (!c.verifier(kc)) { d.poserEchecs(d.echecs() + 1); return abandon("pin_mismatch") }
        d.poserEchecs(0)
        d.envoyer(msg("client/pair-confirm", "client_kc" to SendspinNoise.b64url(c.etiquette()), "nonce_B" to SendspinNoise.b64url(nonceB!!)))
        phase = Phase.ATTENTE_FIN
        finaliser()
    }

    fun pairFinalize() {
        val psk = pskEnAttente ?: return
        d.identite.ajouterLongTerme(psk, d.serverId())
        nettoyer()
        d.evenement("fini")
    }

    fun abandonServeur(raison: String) {
        if (phase == Phase.REPOS && pskEnAttente == null) return
        nettoyer(); d.evenement("abandon", raison)
    }

    fun remettre() { nettoyer(); activations = 0; essai = 0 }

    private fun finaliser() {
        val psk = ByteArray(32).also { ALEA.nextBytes(it) }
        pskEnAttente = psk
        val c = cpace; val s = sid
        if (c != null && s != null) {
            val k = SendspinNoise.sha256("sendspin-pair-psk-wrap-v1".toByteArray(), s, c.isk)
            d.envoyer(msg("client/pair-finalize", "wrapped_psk" to SendspinNoise.b64url(SendspinNoise.chiffrer(k, 0, ByteArray(0), psk))))
        } else d.envoyer(msg("client/pair-finalize", "long_term_psk" to SendspinNoise.b64url(psk)))
    }

    private fun minuter() { annulerMinuterie?.invoke(); annulerMinuterie = d.minuter(ESSAI_MS) { abandon("attempt_timeout") } }

    private fun abandon(raison: String) {
        nettoyer()
        d.envoyer(msg("pair/abort", "reason" to raison))
        d.evenement("abandon", raison)
    }

    private fun echec() { nettoyer(); d.fermer() }

    private fun abandonner(detail: String) {
        if (phase == Phase.REPOS && pskEnAttente == null) return
        nettoyer(); d.evenement("abandon", detail)
    }

    private fun nettoyer() {
        annulerMinuterie?.invoke(); annulerMinuterie = null
        if (methode == "dynamic_pin") d.pin(null)
        pskEnAttente = null; phase = Phase.REPOS; methode = null; cpace = null; sid = null; nonceB = null; longueurPin = 0
    }

    private fun decoder(v: Any?, taille: Int): ByteArray? =
        (v as? String)?.let { runCatching { SendspinNoise.deB64url(it) }.getOrNull() }?.takeIf { it.size == taille }

    private fun msg(type: String, vararg p: Pair<String, Any?>) = linkedMapOf("type" to type, "payload" to linkedMapOf(*p))

    companion object {
        const val PIN_MIN = 6
        const val PIN_MAX = 12
        const val PIN_COURT = 6
        const val ESCALADE = 10
        const val ESSAI_MS = 120_000L
        const val FENETRE_MS = 300_000L
        private val ALEA = SecureRandom()
        private val AD_SERVEUR = "server".toByteArray()
        private val AD_CLIENT = "client".toByteArray()

        fun engagement(nonce: ByteArray): ByteArray = SendspinNoise.sha256("sendspin-pair-commit-v1".toByteArray(), nonce)

        /** Le PIN : SHA-256(étiquette ‖ hash ‖ nonce_A ‖ nonce_B), en nombre, modulo 10^longueur, complété de zéros. */
        fun derivePin(h: ByteArray, na: ByteArray, nb: ByteArray, longueur: Int): String {
            val n = BigInteger(1, SendspinNoise.sha256("sendspin-pin-derive-v1".toByteArray(), h, na, nb))
            return n.mod(BigInteger.TEN.pow(longueur)).toString().padStart(longueur, '0')
        }

        fun sidPake(h: ByteArray, index: Int): ByteArray =
            "sendspin-pair-pake-v1".toByteArray() + h + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())
    }

    /** CPace X25519/SHA-512 (draft-irtf-cfrg-cpace), comme sendspin-js et la bibliothèque « cpace » d'aiosendspin. */
    class CPace private constructor(private val initiateur: Boolean, private var scalaire: ByteArray?, private val sid: ByteArray,
                                    private val ada: ByteArray, private val adb: ByteArray, generateur: ByteArray) {
        val part: ByteArray = mult(scalaire!!, generateur)
        private var cleMac: ByteArray? = null
        private var ya: ByteArray? = null
        private var yb: ByteArray? = null
        var isk: ByteArray = ByteArray(0); private set

        fun deriver(pair: ByteArray) {
            val s = scalaire ?: error("deriver() une seule fois")
            scalaire = null
            require(pair.size == 32)
            val k = mult(s, pair)
            if (initiateur) { ya = part; yb = pair } else { ya = pair; yb = part }
            val transcript = lv(ya!!, ada) + lv(yb!!, adb)
            isk = sha512(lv("CPace255_ISK".toByteArray(), sid, k) + transcript)
            cleMac = sha512("CPaceMac".toByteArray() + sid + isk)
        }

        fun etiquette(): ByteArray = mac(true)

        fun verifier(t: ByteArray): Boolean {
            val attendu = mac(false)
            if (MessageDigest.isEqual(attendu, mac(true))) return false  // réflexion
            return MessageDigest.isEqual(t, attendu)
        }

        private fun mac(propre: Boolean): ByteArray {
            val ini = propre == initiateur
            val m = Mac.getInstance("HmacSHA512"); m.init(SecretKeySpec(cleMac ?: error("deriver() d'abord"), "HmacSHA512"))
            return m.doFinal(lv(if (ini) ya!! else yb!!, if (ini) ada else adb))
        }

        companion object {
            private val DEUX = BigInteger.valueOf(2)  // BigInteger.TWO n'existe qu'à partir d'Android 13
            private val Q = DEUX.pow(255) - BigInteger.valueOf(19)
            private val A = BigInteger.valueOf(486662)
            private val Z = DEUX

            fun commencer(initiateur: Boolean, prs: ByteArray, sid: ByteArray, ada: ByteArray, adb: ByteArray, ci: ByteArray = ByteArray(0),
                          scalaire: ByteArray? = null): CPace =
                CPace(initiateur, scalaire ?: ByteArray(32).also { ALEA.nextBytes(it) }, sid, ada, adb, generateur(prs, ci, sid))

            fun sha512(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(b)

            fun prefixe(d: ByteArray): ByteArray {
                var n = d.size
                val p = java.io.ByteArrayOutputStream()
                while (true) { p.write(if (n < 128) n else (n and 0x7f) or 0x80); n = n shr 7; if (n == 0) break }
                return p.toByteArray() + d
            }

            fun lv(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, x -> acc + prefixe(x) }

            fun generateur(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
                val dsi = "CPace255".toByteArray()
                val zeros = maxOf(0, 128 - 1 - prefixe(prs).size - prefixe(dsi).size)
                val h = sha512(lv(dsi, prs, ByteArray(zeros), ci, sid)).copyOf(32)
                return elligator2(decodeU(h))
            }

            private fun decodeU(v: ByteArray): BigInteger {
                val u = v.copyOf(); u[31] = (u[31].toInt() and 0x7f).toByte()
                return BigInteger(1, u.reversedArray())
            }

            private fun encodeU(x: BigInteger): ByteArray {
                val be = x.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
                return ByteArray(32).also { out -> for (i in be.indices) out[i] = be[be.size - 1 - i] }
            }

            fun elligator2(r: BigInteger): ByteArray {
                val rq = r.mod(Q)
                val v = A.negate().multiply(BigInteger.ONE.add(Z.multiply(rq).multiply(rq)).mod(Q).modPow(Q - DEUX, Q)).mod(Q)
                val eps = v.pow(3).add(A.multiply(v.pow(2))).add(v).mod(Q).modPow((Q - BigInteger.ONE).shiftRight(1), Q)
                val x = eps.multiply(v).subtract(BigInteger.ONE.subtract(eps).mod(Q).multiply(A).multiply(DEUX.modPow(Q - DEUX, Q))).mod(Q)
                return encodeU(x)
            }

            /** X25519 ; un résultat nul (point de petit ordre) est refusé. */
            fun mult(scalaire: ByteArray, point: ByteArray): ByteArray {
                val r = ByteArray(32)
                X25519.scalarMult(scalaire, 0, point, 0, r, 0)
                require(r.any { it.toInt() != 0 }) { "point de petit ordre" }
                return r
            }
        }
    }
}
