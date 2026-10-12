// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client Sendspin natif (lot 1, GO de l'auteur le 2026-10-12) : le chiffrement de bout en bout du protocole, Noise
 * `Noise_KKpsk2_25519_ChaChaPoly_SHA256` (spec Sendspin « connection.md », référence sendspin-js 5.0.0 et aiosendspin
 * 9.0.0, celles que parle Music Assistant 2.10.6).
 *
 * Music Assistant est l'initiateur, Vision le répondeur. Les clés statiques sont connues des deux côtés d'avance
 * (`client_id` = clé publique X25519 de Vision, `server_id` = celle du serveur) ; la PSK (Sentinel publique, ou clé
 * d'appairage, ou clé long terme) est mêlée à la fin du 2e message. X25519, ChaCha20-Poly1305 et SHA-512 viennent de
 * BouncyCastle (Android ne les a qu'à partir des API 28 à 33 ; Vision part de l'API 21).
 */
object SendspinNoise {
    const val DH = 32
    const val TAG = 16
    private val alea = SecureRandom()

    class Paire(val prive: ByteArray, val publique: ByteArray)

    fun paire(): Paire {
        val sk = ByteArray(DH).also { X25519.generatePrivateKey(alea, it) }
        return Paire(sk, publique(sk))
    }

    fun publique(prive: ByteArray): ByteArray = ByteArray(DH).also { X25519.scalarMultBase(prive, 0, it, 0) }

    /** Diffie-Hellman X25519 ; un résultat nul (point faible) est refusé. */
    fun dh(prive: ByteArray, publique: ByteArray): ByteArray {
        require(publique.size == DH) { "clé publique de ${publique.size} octets" }
        val r = ByteArray(DH)
        X25519.scalarMult(prive, 0, publique, 0, r, 0)
        require(r.any { it.toInt() != 0 }) { "échange X25519 nul" }
        return r
    }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run { parts.forEach { update(it) }; digest() }

    fun hmac256(cle: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(cle, "HmacSHA256")); parts.forEach { update(it) }; doFinal() }

    /** HKDF de Noise (2 ou 3 sorties de 32 octets). */
    fun hkdf(ck: ByteArray, ikm: ByteArray, n: Int): List<ByteArray> {
        val tmp = hmac256(ck, ikm)
        val o1 = hmac256(tmp, byteArrayOf(1))
        val o2 = hmac256(tmp, o1, byteArrayOf(2))
        if (n == 2) return listOf(o1, o2)
        val o3 = hmac256(tmp, o2, byteArrayOf(3))
        return listOf(o1, o2, o3)
    }

    /** ChaChaPoly de Noise : nonce de 12 octets = 4 zéros + compteur 64 bits petit-boutiste. */
    private fun nonce(n: Long): ByteArray = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (n ushr (8 * i)).toByte() }

    fun chiffrer(k: ByteArray, n: Long, ad: ByteArray, clair: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(k), TAG * 8, nonce(n), ad))
        val out = ByteArray(c.getOutputSize(clair.size))
        val l = c.processBytes(clair, 0, clair.size, out, 0)
        c.doFinal(out, l)
        return out
    }

    /** Lève une exception si l'authentification échoue (message altéré, rejoué ou mauvaise clé). */
    fun dechiffrer(k: ByteArray, n: Long, ad: ByteArray, chiffre: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(k), TAG * 8, nonce(n), ad))
        val out = ByteArray(c.getOutputSize(chiffre.size))
        val l = c.processBytes(chiffre, 0, chiffre.size, out, 0)
        val t = c.doFinal(out, l)
        return if (l + t == out.size) out else out.copyOf(l + t)
    }

    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** base64url sans remplissage (identifiants, PSK, poignée de main) ; aussi lu avec « + / = » (base64 standard). */
    fun b64url(b: ByteArray): String {
        val sb = StringBuilder((b.size * 4 + 2) / 3)
        var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i)
            var v = 0
            for (k in 0 until 3) v = (v shl 8) or (if (k < n) b[i + k].toInt() and 0xFF else 0)
            for (k in 0..n) sb.append(ALPHA[(v ushr (18 - 6 * k)) and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    fun deB64url(s: String): ByteArray {
        val propre = s.trim().trimEnd('=').replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(propre.length * 3 / 4)
        var v = 0; var bits = 0
        for (c in propre) {
            val d = ALPHA.indexOf(c)
            require(d >= 0) { "base64 invalide" }
            v = (v shl 6) or d; bits += 6
            if (bits >= 8) { bits -= 8; out.write((v ushr bits) and 0xFF) }
        }
        return out.toByteArray()
    }

    /** base64 standard avec remplissage (le `codec_header` FLAC). */
    fun deB64(s: String): ByteArray = deB64url(s)

    /** PSK Sentinel publique : SHA-256("sendspin-sentinel-psk-v1"). */
    val SENTINEL: ByteArray = sha256("sendspin-sentinel-psk-v1".toByteArray())

    /** psk_id = base64url(SHA-256("sendspin-psk-id-v1" || PSK)). */
    fun pskId(psk: ByteArray): String = b64url(sha256("sendspin-psk-id-v1".toByteArray(), psk))

    // ------------------------------------------------------------------ états de Noise

    class Chiffre {
        private var k: ByteArray? = null
        var n = 0L; private set
        fun cle(c: ByteArray?) { k = c; n = 0 }
        val aCle get() = k != null
        fun chiffrer(ad: ByteArray, clair: ByteArray): ByteArray {
            val c = k ?: return clair
            return chiffrer(c, n, ad, clair).also { n++ }
        }
        fun dechiffrer(ad: ByteArray, chiffre: ByteArray): ByteArray {
            val c = k ?: return chiffre
            return dechiffrer(c, n, ad, chiffre).also { n++ }
        }
    }

    class Symetrique(nomProtocole: String) {
        var ck: ByteArray; var h: ByteArray
        val chiffre = Chiffre()
        init {
            val nom = nomProtocole.toByteArray()
            h = if (nom.size <= 32) nom.copyOf(32) else sha256(nom)
            ck = h
        }
        fun melerHash(d: ByteArray) { h = sha256(h, d) }
        fun melerCle(ikm: ByteArray) { val (c, t) = hkdf(ck, ikm, 2); ck = c; chiffre.cle(t.copyOf(32)) }
        fun melerCleEtHash(ikm: ByteArray) { val (c, th, tk) = hkdf(ck, ikm, 3); ck = c; melerHash(th); chiffre.cle(tk.copyOf(32)) }
        fun chiffrerEtHash(p: ByteArray): ByteArray = chiffre.chiffrer(h, p).also { melerHash(it) }
        fun dechiffrerEtHash(c: ByteArray): ByteArray = chiffre.dechiffrer(h, c).also { melerHash(c) }
        fun separer(): Pair<Chiffre, Chiffre> {
            val (k1, k2) = hkdf(ck, ByteArray(0), 2)
            return Chiffre().apply { cle(k1.copyOf(32)) } to Chiffre().apply { cle(k2.copyOf(32)) }
        }
    }

    /**
     * La poignée de main KKpsk2 : message 1 (initiateur) « e, es, ss » ; message 2 (répondeur) « e, ee, se, psk ».
     * Vision est répondeur ; l'initiateur ne sert qu'aux essais (même code des deux côtés, comme aiosendspin).
     */
    class Poignee(val initiateur: Boolean, prologue: ByteArray, private val s: Paire, private val rs: ByteArray,
                  private var psk: ByteArray? = null, private val eFixe: Paire? = null) {
        private val sym = Symetrique("Noise_KKpsk2_25519_ChaChaPoly_SHA256")
        private var e: Paire? = null
        private var re: ByteArray? = null
        init {
            sym.melerHash(prologue)
            sym.melerHash(if (initiateur) s.publique else rs)
            sym.melerHash(if (initiateur) rs else s.publique)
        }
        val hash: ByteArray get() = sym.h
        fun psk(p: ByteArray) { psk = p }

        private fun dhJeton(j: String): ByteArray = when (j) {
            "ee" -> dh(e!!.prive, re!!)
            "ss" -> dh(s.prive, rs)
            "es" -> if (initiateur) dh(e!!.prive, rs) else dh(s.prive, re!!)
            else -> if (initiateur) dh(s.prive, re!!) else dh(e!!.prive, rs)  // "se"
        }

        private fun ecrire(jetons: List<String>, charge: ByteArray): ByteArray {
            var out = ByteArray(0)
            for (j in jetons) when (j) {
                "e" -> { val p = eFixe ?: paire(); e = p; sym.melerHash(p.publique); sym.melerCle(p.publique); out += p.publique }
                "psk" -> sym.melerCleEtHash(psk ?: error("PSK absente"))
                else -> sym.melerCle(dhJeton(j))
            }
            return out + sym.chiffrerEtHash(charge)
        }

        private fun lire(jetons: List<String>, msg: ByteArray): ByteArray {
            var i = 0
            for (j in jetons) when (j) {
                "e" -> { require(msg.size >= i + DH) { "message de poignée trop court" }
                    val r = msg.copyOfRange(i, i + DH); i += DH; re = r; sym.melerHash(r); sym.melerCle(r) }
                "psk" -> sym.melerCleEtHash(psk ?: error("PSK absente"))
                else -> sym.melerCle(dhJeton(j))
            }
            return sym.dechiffrerEtHash(msg.copyOfRange(i, msg.size))
        }

        fun ecrire1(charge: ByteArray) = ecrire(MSG1, charge)
        fun lire1(msg: ByteArray) = lire(MSG1, msg)
        fun ecrire2(charge: ByteArray) = ecrire(MSG2, charge)
        fun lire2(msg: ByteArray) = lire(MSG2, msg)

        /** Après le message 2 : la session de transport (envoi, réception) de ce côté. */
        fun session(): Session {
            val (c1, c2) = sym.separer()
            return if (initiateur) Session(c1, c2) else Session(c2, c1)
        }
    }

    private val MSG1 = listOf("e", "es", "ss")
    private val MSG2 = listOf("e", "ee", "se", "psk")

    /** Le transport chiffré : un message de transport = un message WebSocket binaire. */
    class Session(private val envoi: Chiffre, private val reception: Chiffre) {
        @Synchronized fun chiffrer(clair: ByteArray): ByteArray = envoi.chiffrer(ByteArray(0), clair)
        fun dechiffrer(chiffre: ByteArray): ByteArray = reception.dechiffrer(ByteArray(0), chiffre)
    }
}
