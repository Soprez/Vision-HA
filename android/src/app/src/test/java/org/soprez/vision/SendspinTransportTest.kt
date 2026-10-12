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
 * Client Sendspin natif, lot 1 : Noise KKpsk2 et transport. Le vecteur vient de la bibliothèque « noise » de Python
 * utilisée par aiosendspin 9.0.0 (côté Music Assistant), avec des clés et des éphémères fixes : Vision doit produire
 * exactement les mêmes octets.
 */
class SendspinTransportTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun octets(a: Int, b: Int) = ByteArray(b - a) { (a + it).toByte() }

    private val sSrv = octets(1, 33); private val sCli = octets(33, 65); private val eSrv = octets(65, 97); private val eCli = octets(97, 129)
    private val prologue = "{\"type\":\"client/init\"}{\"type\":\"server/init\"}".toByteArray()
    private fun paire(sk: ByteArray) = SendspinNoise.Paire(sk, SendspinNoise.publique(sk))

    @Test fun meme_poignee_que_aiosendspin() {
        assertArrayEquals(hex("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c"), SendspinNoise.publique(sSrv))
        val cli = SendspinNoise.Poignee(false, prologue, paire(sCli), SendspinNoise.publique(sSrv), eFixe = paire(eCli))
        val m1 = hex("64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d48466d3935f1b7ca29eba9acd42d05c937518dc34123f5c9919af0a0ab771dc63")
        assertEquals("{\"psk_id\":\"x\"}", String(cli.lire1(m1)))
        cli.psk(SendspinNoise.SENTINEL)
        assertArrayEquals(hex("244fe3b963e899dd295baffce248d3530f3a9a7479ba063002680ebfe7adad491455fbd654b60d4466d27f3d6a9c9262ffa8"), cli.ecrire2("{}".toByteArray()))
        assertArrayEquals(hex("ed0b4db9bc81c063568af1ebb20088f5ce136e499a0de8def5b87a4fe40b1db6"), cli.hash)
        val s = cli.session()
        assertEquals("\u0000{\"type\":\"server/hello\"}", String(s.dechiffrer(hex("095f84ff9ed731ea1725c896d99fc7616616bd88c630044ca92da8e549c076f39f8f17dd6aabfeb1"))))
        assertArrayEquals(hex("2563aa42c91d016e06b492e3fc0a037cf501fb"), s.chiffrer("\u0000{}".toByteArray()))
    }

    @Test fun psk_sentinel_et_identifiant_publies() {
        assertArrayEquals(hex("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3"), SendspinNoise.SENTINEL)
        assertEquals("GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo", SendspinNoise.pskId(SendspinNoise.SENTINEL))
        for (n in 0..40) { val b = ByteArray(n) { (it * 37 + n).toByte() }; assertArrayEquals(b, SendspinNoise.deB64url(SendspinNoise.b64url(b))) }
        assertArrayEquals(byteArrayOf(-5, -1), SendspinNoise.deB64("+/8="))
    }

    @Test fun message_altere_refuse() {
        val k = ByteArray(32) { 7 }
        val c = SendspinNoise.chiffrer(k, 3, ByteArray(0), "bonjour".toByteArray())
        assertEquals("bonjour", String(SendspinNoise.dechiffrer(k, 3, ByteArray(0), c)))
        c[0] = (c[0] + 1).toByte()
        assertTrue(runCatching { SendspinNoise.dechiffrer(k, 3, ByteArray(0), c) }.isFailure)
    }

    // ------------------------------------------------------------- transport complet face à un faux serveur

    private class Memoire : SendspinIdentite.Stock {
        val m = HashMap<String, String?>()
        override fun lire(cle: String) = m[cle]
        override fun ecrire(cle: String, valeur: String?) { m[cle] = valeur }
    }

    /** Un serveur minimal, initiateur Noise, comme Music Assistant. */
    private inner class FauxServeur(val psk: ByteArray, val pskId: String) : SendspinTransport.Sortie {
        val s = SendspinNoise.paire()
        val serverId = SendspinNoise.b64url(s.publique)
        val textes = ArrayList<String>(); val controles = ArrayList<Pair<String, Map<String, Any?>>>(); val roles = ArrayList<ByteArray>()
        var ferme: String? = null
        var session: SendspinNoise.Session? = null
        var hs: SendspinNoise.Poignee? = null
        lateinit var client: SendspinTransport
        override fun texte(t: String) { textes.add(t); repondre(t) }
        override fun binaire(b: ByteArray) { val c = session!!.dechiffrer(b); textes.add("binaire:" + String(c, 1, c.size - 1)) }
        override fun fermer(raison: String) { ferme = raison }
        override fun controle(type: String, charge: Map<String, Any?>) { controles.add(type to charge) }
        override fun role(b: ByteArray) { roles.add(b) }
        private fun repondre(t: String) {
            val o = Json.objet(t)!!
            val p = o["payload"] as Map<*, *>
            when (o["type"]) {
                "client/init" -> {
                    val init = "{\"type\":\"server/init\",\"payload\":{\"server_id\":\"$serverId\",\"version\":1}}"
                    hs = SendspinNoise.Poignee(true, t.toByteArray() + init.toByteArray(), s, SendspinNoise.deB64url(p["client_id"] as String), psk)
                    val m1 = hs!!.ecrire1("{\"psk_id\":\"$pskId\",\"psk_category\":\"sn\"}".toByteArray())
                    client.recuTexte(init)
                    client.recuTexte("{\"type\":\"noise/handshake\",\"payload\":{\"data\":\"${SendspinNoise.b64url(m1)}\"}}")
                }
                "noise/handshake" -> { assertEquals("{}", String(hs!!.lire2(SendspinNoise.deB64url(p["data"] as String)))); session = hs!!.session() }
            }
        }
        fun envoyer(type: Int, corps: ByteArray) = client.recuBinaire(session!!.chiffrer(byteArrayOf(type.toByte()) + corps))
        fun json(t: String) = envoyer(0, t.toByteArray())
    }

    @Test fun poignee_puis_messages_chiffres_et_fragments() {
        val id = SendspinIdentite(Memoire())
        val srv = FauxServeur(SendspinNoise.SENTINEL, SendspinNoise.pskId(SendspinNoise.SENTINEL))
        srv.client = SendspinTransport(id, srv)
        srv.client.demarrer()
        assertNull(srv.ferme); assertNotNull(srv.session)
        assertEquals("sentinel", srv.client.info!!.categorie)
        assertEquals(srv.serverId, srv.client.serverId)
        srv.json("{\"type\":\"server/hello\",\"payload\":{\"name\":\"MA\"}}")
        assertEquals("server/hello", srv.controles.last().first)
        // Activation : lecture sans appairage permise (accès non appairé).
        srv.json("{\"type\":\"server/activate\",\"payload\":{\"activities\":[\"playback\"],\"active_roles\":[\"player@v1\"]}}")
        assertTrue(srv.client.pret); assertNull(srv.ferme)
        // Audio (type 4) et message en fragments (2, 2, 3).
        srv.envoyer(4, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(4, 1, 2, 3), srv.roles.last())
        srv.envoyer(2, byteArrayOf(0) + "{\"type\":\"server/state\",".toByteArray())
        srv.envoyer(2, "\"payload\":{\"metadata\":".toByteArray())
        srv.envoyer(3, "{\"title\":\"Titre\"}}}".toByteArray())
        val (type, charge) = srv.controles.last()
        assertEquals("server/state", type)
        assertEquals("Titre", ((charge["metadata"] as Map<*, *>)["title"]))
        // Un message chiffré de Vision (type 0, JSON) arrive lisible au serveur.
        srv.client.envoyer(linkedMapOf("type" to "client/state", "payload" to linkedMapOf("available" to true)))
        assertEquals("{\"type\":\"client/state\",\"payload\":{\"available\":true}}", srv.textes.last().removePrefix("binaire:"))
    }

    @Test fun psk_inconnue_ou_message_altere_ferment() {
        val srv = FauxServeur(ByteArray(32) { 9 }, SendspinNoise.pskId(ByteArray(32) { 9 }))
        srv.client = SendspinTransport(SendspinIdentite(Memoire()), srv)
        srv.client.demarrer()
        assertEquals("PSK inconnue", srv.ferme)
        val ok = FauxServeur(SendspinNoise.SENTINEL, SendspinNoise.pskId(SendspinNoise.SENTINEL))
        ok.client = SendspinTransport(SendspinIdentite(Memoire()), ok)
        ok.client.demarrer()
        val c = ok.session!!.chiffrer(byteArrayOf(0) + "{}".toByteArray()); c[3] = (c[3] + 1).toByte()
        ok.client.recuBinaire(c)
        assertEquals("authentification Noise", ok.ferme)
    }

    @Test fun activites_permises_selon_la_psk() {
        val S = SendspinIdentite.SENTINELLE; val L = SendspinIdentite.LONG_TERME; val A = SendspinIdentite.APPAIRAGE
        assertNull(SendspinTransport.autoriser(S, setOf("playback"), true, true))
        assertEquals("pairing_required", SendspinTransport.autoriser(S, setOf("playback"), true, false))
        assertNull(SendspinTransport.autoriser(S, setOf("pairing"), false, false))
        assertEquals("unauthorized", SendspinTransport.autoriser(S, setOf("playback", "pairing"), true, true))
        assertNull(SendspinTransport.autoriser(L, setOf("playback", "management"), true, false))
        assertEquals("unauthorized", SendspinTransport.autoriser(A, setOf("playback"), true, true))
        assertNull(SendspinTransport.autoriser(A, setOf("pairing"), false, true))
    }

    @Test fun identite_gardee_et_cle_long_terme_par_serveur() {
        val m = Memoire()
        val a = SendspinIdentite(m); val b = SendspinIdentite(m)
        assertEquals(43, a.clientId.length); assertEquals(a.clientId, b.clientId)
        assertFalse(a.appaire)
        val psk = ByteArray(32) { 3 }
        a.ajouterLongTerme(psk, "srv1")
        assertTrue(SendspinIdentite(m).appaire)
        assertEquals("srv1", SendspinIdentite(m).psk(SendspinNoise.pskId(psk))!!.serverId)
        a.oublier(SendspinNoise.pskId(psk))
        assertNull(SendspinIdentite(m).psk(SendspinNoise.pskId(psk)))
        assertNotNull(a.psk(SendspinNoise.pskId(SendspinNoise.SENTINEL)))
    }
}
