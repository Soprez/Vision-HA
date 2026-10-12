// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client Sendspin natif, lot 6 : l'appairage par PIN. Les vecteurs viennent d'aiosendspin 9.0.0 (cpace 0.1.0), aléas
 * fixés : Vision doit tomber sur le même PIN, les mêmes parts CPace, la même clé de session et les mêmes étiquettes.
 */
class SendspinAppairageTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun octets(a: Int, b: Int) = ByteArray(b - a) { (a + it).toByte() }

    private val h = octets(200, 232); private val na = octets(1, 33); private val nb = octets(33, 65)

    @Test fun pin_et_cpace_comme_aiosendspin() {
        assertEquals("456640", SendspinAppairage.derivePin(h, na, nb, 6))
        assertArrayEquals(hex("da47dd124ef65bc64d3fc29e3a0f42e4e236be054c3c37c64b2419b1c3ae5ed0"), SendspinAppairage.engagement(nb))
        val sid = SendspinAppairage.sidPake(h, 3)
        assertArrayEquals(hex("73656e647370696e2d706169722d70616b652d7631c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e700000003"), sid)
        assertArrayEquals(hex("c46606e633d68097c2fec9f0fbb545c70a551a128aa249c76536c81e11e96b0f"),
            SendspinAppairage.CPace.generateur("456640".toByteArray(), ByteArray(0), sid))
        val a = SendspinAppairage.CPace.commencer(true, "456640".toByteArray(), sid, "server".toByteArray(), "client".toByteArray(), scalaire = octets(70, 102))
        val b = SendspinAppairage.CPace.commencer(false, "456640".toByteArray(), sid, "server".toByteArray(), "client".toByteArray(), scalaire = octets(102, 134))
        assertArrayEquals(hex("a1c69eb434057f837de25fd5e53d0f8bd6232c44ac4d391f0fef9f0c8bd67933"), a.part)
        assertArrayEquals(hex("b8c5058a6abd3547de5b6424a87ece3b50a718e48b453c001664d0120304416d"), b.part)
        a.deriver(b.part); b.deriver(a.part)
        assertArrayEquals(hex("76471dca63223dfa0f3291a2c438a265d0110b1d8c82e3e7a9c78975ca6afa8c3df88b4f2347c84078d866f47afa727b6417c1814289b7438fcab950b06ebb55"), b.isk)
        assertArrayEquals(hex("ddb48b7df503271433254509997dcf79636df5afc598a2ac02d8a858e443f70b49822ef7215f0e1510c7cb867c8104e7f558675b292d2ac7b198d2991eaed58e"), a.etiquette())
        assertArrayEquals(hex("cfbdaeee9a95d7b3c87641433979f6303bfd736b11ad4691e8958e4d48634fe8e9931c68026e056de2c42762611437691198ad807c713618b94bcee8ab9e1bf9"), b.etiquette())
        assertTrue(b.verifier(a.etiquette())); assertTrue(a.verifier(b.etiquette()))
        assertFalse(b.verifier(b.etiquette()))  // réflexion refusée
        // La clé long terme emballée par la clé de CPace : mêmes octets.
        val k = SendspinNoise.sha256("sendspin-pair-psk-wrap-v1".toByteArray(), sid, b.isk)
        assertArrayEquals(hex("15c9c66eaa8f3e08133324827d1b76737934e0046393b58ffb6090fb37cbe18984e370b5703791e1116e0ba806d4ee95"),
            SendspinNoise.chiffrer(k, 0, ByteArray(0), octets(140, 172)))
    }

    // --------------------------------------------------------------- déroulé complet face à un faux serveur

    private class Memoire : SendspinIdentite.Stock {
        val m = HashMap<String, String?>()
        override fun lire(cle: String) = m[cle]
        override fun ecrire(cle: String, valeur: String?) { m[cle] = valeur }
    }

    private class Banc(val categorie: String = SendspinIdentite.SENTINELLE) : SendspinAppairage.Deps {
        val envoyes = ArrayList<Map<String, Any?>>()
        val evenements = ArrayList<String>()
        var pinAffiche: String? = null
        var ferme = false
        var nEchecs = 0
        override fun envoyer(msg: Map<String, Any?>) { envoyes.add(msg) }
        override fun fermer() { ferme = true }
        override val identite = SendspinIdentite(Memoire())
        override fun serverId() = "srv"
        override fun categorie() = categorie
        override fun hashPoignee() = ByteArray(32) { 7 }
        override fun pin(pin: String?) { pinAffiche = pin }
        override fun evenement(e: String, detail: String?) { evenements.add(e + (detail?.let { ":$it" } ?: "")) }
        override fun echecs() = nEchecs
        override fun poserEchecs(n: Int) { nEchecs = n }
        override fun minuter(ms: Long, f: () -> Unit): () -> Unit = {}
        @Suppress("UNCHECKED_CAST") fun charge(type: String) = envoyes.last { it["type"] == type }["payload"] as Map<String, Any?>
    }

    /** Le serveur (initiateur CPace) avec le PIN qu'il croit lu sur l'écran. */
    private fun derouler(banc: Banc, app: SendspinAppairage, pinTape: (String) -> String): Boolean {
        assertTrue(app.activation(listOf("pairing"), mapOf("method" to "dynamic_pin", "pin_length" to 6)))
        val commit = SendspinNoise.deB64url(banc.charge("client/pair-init")["commit_B"] as String)
        val nonceA = ByteArray(32) { 3 }
        app.pairInit(mapOf("nonce_A" to SendspinNoise.b64url(nonceA)))
        val pin = banc.pinAffiche!!
        assertEquals(6, pin.length)
        val sid = SendspinAppairage.sidPake(banc.hashPoignee(), 1)
        val srv = SendspinAppairage.CPace.commencer(true, pinTape(pin).toByteArray(), sid, "server".toByteArray(), "client".toByteArray())
        app.pairAuth(mapOf("pake_msg_1" to SendspinNoise.b64url(srv.part)))
        srv.deriver(SendspinNoise.deB64url(banc.charge("client/pair-auth")["pake_msg_2"] as String))
        app.pairConfirm(mapOf("server_kc" to SendspinNoise.b64url(srv.etiquette())))
        if (banc.envoyes.last()["type"] == "pair/abort") return false
        val conf = banc.charge("client/pair-confirm")
        val nb = SendspinNoise.deB64url(conf["nonce_B"] as String)
        assertArrayEquals(commit, SendspinAppairage.engagement(nb))           // l'aléa révélé tient l'engagement
        assertEquals(pin, SendspinAppairage.derivePin(banc.hashPoignee(), nonceA, nb, 6))
        assertTrue(srv.verifier(SendspinNoise.deB64url(conf["client_kc"] as String)))
        // La clé long terme : le serveur la déballe avec la clé tirée de CPace.
        val k = SendspinNoise.sha256("sendspin-pair-psk-wrap-v1".toByteArray(), sid, srv.isk)
        val psk = SendspinNoise.dechiffrer(k, 0, ByteArray(0), SendspinNoise.deB64url(banc.charge("client/pair-finalize")["wrapped_psk"] as String))
        app.pairFinalize()
        assertEquals("srv", banc.identite.psk(SendspinNoise.pskId(psk))?.serverId)
        return true
    }

    @Test fun bon_pin_appaire_mauvais_pin_refuse() {
        val banc = Banc(); val app = SendspinAppairage(banc)
        assertEquals(listOf("pairing_psk", "dynamic_pin"), app.methodes().map { it["method"] })
        // Mauvais PIN : abandon « pin_mismatch », un échec compté, rien d'enregistré, le PIN quitte l'écran.
        assertFalse(derouler(banc, app) { p -> (p.toInt() + 1).toString().padStart(6, '0').takeLast(6) })
        assertEquals("pin_mismatch", banc.charge("pair/abort")["reason"])
        assertEquals(1, banc.nEchecs); assertFalse(banc.identite.appaire); assertNull(banc.pinAffiche)
        // Bon PIN : appairé, compteur remis à zéro.
        val banc2 = Banc(); val app2 = SendspinAppairage(banc2)
        assertTrue(derouler(banc2, app2) { it })
        assertTrue(banc2.identite.appaire); assertEquals(0, banc2.nEchecs)
        assertTrue(banc2.evenements.contains("fini"))
    }

    @Test fun apres_dix_echecs_un_geste_sur_place() {
        val banc = Banc().apply { nEchecs = 10 }
        val app = SendspinAppairage(banc)
        assertTrue(app.activation(listOf("pairing"), mapOf("method" to "dynamic_pin", "pin_length" to 6)))
        assertEquals("client/pair-pending", banc.envoyes.last()["type"])
        assertEquals("attente_geste", banc.evenements.last())
        app.ouvrirFenetre()
        assertEquals("client/pair-init", banc.envoyes.last()["type"])
        assertNotNull(banc.charge("client/pair-init")["commit_B"])
    }

    @Test fun methodes_et_longueurs_refusees() {
        val banc = Banc(); val app = SendspinAppairage(banc)
        app.activation(listOf("pairing"), mapOf("method" to "dynamic_pin", "pin_length" to 4))
        assertEquals("pin_length_unacceptable", banc.charge("pair/abort")["reason"])
        app.activation(listOf("pairing"), mapOf("method" to "static_pin"))
        assertEquals("method_not_supported", banc.charge("pair/abort")["reason"])
        // La clé d'appairage n'est permise que si la poignée l'a utilisée.
        app.activation(listOf("pairing"), mapOf("method" to "pairing_psk"))
        assertEquals("method_not_supported", banc.charge("pair/abort")["reason"])
        val b2 = Banc(SendspinIdentite.APPAIRAGE); val a2 = SendspinAppairage(b2)
        a2.activation(listOf("pairing"), mapOf("method" to "pairing_psk"))
        assertNotNull(b2.charge("client/pair-finalize")["long_term_psk"])
        // Sans méthode : serveur non conforme, la liaison se ferme.
        app.activation(listOf("pairing"), null); assertTrue(banc.ferme)
        // Fin de l'activité d'appairage : rien n'est consommé, le protocole reprend.
        assertFalse(app.activation(listOf("playback"), null))
    }
}
