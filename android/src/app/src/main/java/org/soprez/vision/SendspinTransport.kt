// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Client Sendspin natif (lot 1) : la couche de transport, sans Android (essayée en JUnit, SendspinTransportTest).
 *
 * Déroulé (spec « messaging.md », comme sendspin-js 5.0.0 face à Music Assistant 2.10.6) :
 *  1. Vision → `client/init` (clair : client_id, version 1, suite ChaChaPoly) ;
 *  2. serveur → `server/init` (clair : server_id) puis `noise/handshake` (message 1, avec psk_id et psk_category) ;
 *  3. Vision choisit la PSK désignée (Sentinel, appairage ou long terme), répond par le message 2 ;
 *  4. tout le reste est chiffré, un message WebSocket binaire par message Noise : 1er octet = type (0 = JSON,
 *     2 = fragment à suivre, 3 = dernier fragment, 4 et plus = rôles : audio, pochette…).
 * `server/activate` est vérifié ici (activités permises selon la PSK) ; le reste remonte à [SendspinClient].
 */
class SendspinTransport(
    private val identite: SendspinIdentite,
    private val sortie: Sortie,
    private val accesNonAppaire: Boolean = true,
) {
    /** Ce que le transport fait sur la socket, et ce qu'il remonte. */
    interface Sortie {
        fun texte(t: String)
        fun binaire(b: ByteArray)
        fun fermer(raison: String)
        fun poignee(info: Info) {}
        fun controle(type: String, charge: Map<String, Any?>)
        fun role(b: ByteArray)
    }

    data class Info(val categorie: String, val serverId: String, val confiance: String)

    private enum class Etat { REPOS, ATTENTE_INIT, ATTENTE_NOISE1, TRANSPORT }
    @Volatile private var etat = Etat.REPOS
    private var poignee: SendspinNoise.Poignee? = null
    private var session: SendspinNoise.Session? = null
    private var choisie: SendspinIdentite.Psk? = null
    var serverId = ""; private set
    private var initClient = ByteArray(0)
    private var dernierHash = ByteArray(0)
    /** Le hash de la dernière poignée de main (le PIN et la session CPace s'y rattachent). */
    val hashPoignee: ByteArray get() = dernierHash
    private var fragType = -1
    private var frag: java.io.ByteArrayOutputStream? = null
    private var activeVue = false
    var roles: List<String>? = null; private set
    var activites: List<String> = emptyList(); private set

    /** Le journal des messages envoyés (débogage) ; sans Android, pour rester essayable en JUnit. */
    var trace: ((String) -> Unit)? = null

    val pret get() = etat == Etat.TRANSPORT && activeVue
    val info: Info? get() = choisie?.let { Info(it.categorie, serverId, if (it.categorie == SendspinIdentite.LONG_TERME) "user" else "none") }

    fun demarrer() {
        etat = Etat.REPOS; poignee = null; session = null; choisie = null; serverId = ""; frag = null; fragType = -1
        activeVue = false; roles = null; activites = emptyList(); dernierHash = ByteArray(0)
        val t = Json.ecrire(linkedMapOf("type" to "client/init",
            "payload" to linkedMapOf("client_id" to identite.clientId, "version" to 1, "suite" to "25519_ChaChaPoly_SHA256")))
        initClient = t.toByteArray(Charsets.UTF_8)
        etat = Etat.ATTENTE_INIT
        sortie.texte(t)
    }

    fun ferme() { etat = Etat.REPOS; session = null }

    /** Un message texte du serveur (seulement pendant la poignée de main). */
    fun recuTexte(t: String) {
        try {
            if (etat == Etat.TRANSPORT) return echec("texte en clair après la poignée de main")
            val o = Json.objet(t) ?: return echec("JSON illisible")
            val type = o["type"] as? String
            val p = o["payload"] as? Map<*, *> ?: emptyMap<String, Any?>()
            when {
                etat == Etat.ATTENTE_INIT && type == "server/init" -> {
                    if ((p["version"] as? Number)?.toInt() != 1) return echec("version ${p["version"]}")
                    serverId = p["server_id"] as? String ?: return echec("server_id absent")
                    val rs = SendspinNoise.deB64url(serverId)
                    if (rs.size != SendspinNoise.DH) return echec("server_id invalide")
                    poignee = SendspinNoise.Poignee(false, initClient + t.toByteArray(Charsets.UTF_8), identite.paire, rs)
                    etat = Etat.ATTENTE_NOISE1
                }
                etat == Etat.ATTENTE_NOISE1 && type == "noise/handshake" -> noise1(SendspinNoise.deB64url(p["data"] as? String ?: ""))
                type == "server/error" -> echec("refus du serveur : ${p["reason"]}")
                else -> echec("message inattendu $type")
            }
        } catch (e: Exception) { echec("poignée de main : ${e.message}") }
    }

    private fun choisirPsk(charge1: ByteArray): SendspinIdentite.Psk? {
        val o = Json.objet(String(charge1, Charsets.UTF_8)) ?: return null
        val id = o["psk_id"] as? String ?: return null
        val e = identite.psk(id) ?: return null
        // Une clé long terme n'est valable que pour le serveur qui l'a donnée.
        if (e.categorie == SendspinIdentite.LONG_TERME && e.serverId != null && e.serverId != serverId) return null
        return e
    }

    private fun noise1(m: ByteArray) {
        val hs = poignee ?: return echec("pas de poignée")
        val e = choisirPsk(hs.lire1(m)) ?: return echec("PSK inconnue")
        hs.psk(e.psk)
        val m2 = hs.ecrire2("{}".toByteArray())
        sortie.texte(Json.ecrire(linkedMapOf("type" to "noise/handshake", "payload" to linkedMapOf("data" to SendspinNoise.b64url(m2)))))
        session = hs.session(); choisie = e; dernierHash = hs.hash; etat = Etat.TRANSPORT
        sortie.poignee(info!!)
    }

    /** Un message binaire du serveur. `recuA` : l'heure de réception (horloge du lecteur, µs) pour l'horloge et l'audio. */
    fun recuBinaire(b: ByteArray) {
        if (etat != Etat.TRANSPORT) return echec("binaire pendant la poignée de main")
        val clair = try { session!!.dechiffrer(b) } catch (_: Exception) { return echec("authentification Noise") }
        if (clair.isEmpty()) return echec("message vide")
        try { distribuer(clair) } catch (e: Exception) { /* message illisible : ignoré, comme sendspin-js */ }
    }

    private fun distribuer(c: ByteArray) {
        when (c[0].toInt() and 0xFF) {
            0 -> { if (frag != null) return echec("message entier pendant un fragment"); json(String(c, 1, c.size - 1, Charsets.UTF_8)) }
            2 -> if (frag == null) {
                if (c.size < 2 || c[1].toInt() == 2 || c[1].toInt() == 3) return echec("fragment invalide")
                fragType = c[1].toInt() and 0xFF
                frag = java.io.ByteArrayOutputStream().apply { write(c, 2, c.size - 2) }
            } else {
                frag!!.write(c, 1, c.size - 1)
                if (frag!!.size() > MAX_REASSEMBLAGE) { frag = null; return echec("message trop grand") }
            }
            3 -> {
                val f = frag ?: return echec("fin de fragment sans début")
                f.write(c, 1, c.size - 1); frag = null
                val tout = byteArrayOf(fragType.toByte()) + f.toByteArray()
                if (fragType == 0) json(String(tout, 1, tout.size - 1, Charsets.UTF_8)) else sortie.role(tout)
            }
            else -> { if (frag != null) return echec("binaire pendant un fragment"); sortie.role(c) }
        }
    }

    private fun json(t: String) {
        val o = Json.objet(t) ?: return
        val type = o["type"] as? String ?: return
        @Suppress("UNCHECKED_CAST") val p = (o["payload"] as? Map<String, Any?>) ?: emptyMap()
        when (type) {
            "noise/handshake" -> rePoignee(p["data"] as? String ?: return echec("re-poignée vide"))
            "server/activate" -> activer(p)
            "server/unpair" -> desappairer()
            else -> sortie.controle(type, p)
        }
    }

    /** Le serveur relance la poignée de main dans le canal chiffré (après un appairage, ou pour changer de clés). */
    private fun rePoignee(data: String) {
        val hs = SendspinNoise.Poignee(false, dernierHash, identite.paire, SendspinNoise.deB64url(serverId))
        val e = choisirPsk(hs.lire1(SendspinNoise.deB64url(data))) ?: return echec("PSK inconnue (re-poignée)")
        hs.psk(e.psk)
        val m2 = hs.ecrire2("{}".toByteArray())
        envoyer(linkedMapOf("type" to "noise/handshake", "payload" to linkedMapOf("data" to SendspinNoise.b64url(m2))))
        session = hs.session(); choisie = e; dernierHash = hs.hash; activeVue = false; roles = null
        sortie.poignee(info!!)
    }

    @Suppress("UNCHECKED_CAST")
    private fun activer(p: Map<String, Any?>) {
        val r = p["active_roles"] as? List<String>
        if (!activeVue && r == null) return auRevoir("unauthorized")
        if (r != null) roles = r
        val act = (p["activities"] as? List<String>) ?: emptyList()
        val refus = autoriser(choisie!!.categorie, act.toSet(), !roles.isNullOrEmpty(), accesNonAppaire)
        if (refus != null) return auRevoir(refus)
        activeVue = true; activites = act
        sortie.controle("server/activate", p)
    }

    private fun desappairer() {
        val e = choisie ?: return
        if (e.categorie != SendspinIdentite.LONG_TERME) return
        identite.oublier(e.id)
        auRevoir("unpaired")
    }

    fun auRevoir(raison: String) {
        try { envoyer(linkedMapOf("type" to "client/goodbye", "payload" to linkedMapOf("reason" to raison))) } catch (_: Exception) {}
        sortie.fermer(raison)
    }

    /** Un message JSON chiffré (type 0). */
    fun envoyer(msg: Map<String, Any?>) {
        val s = session ?: return
        if (msg["type"] != "client/time") trace?.invoke("→ ${Json.ecrire(msg).take(300)}")
        val corps = Json.ecrire(msg).toByteArray(Charsets.UTF_8)
        val clair = ByteArray(corps.size + 1).also { System.arraycopy(corps, 0, it, 1, corps.size) }
        require(clair.size <= MAX_CLAIR) { "message de contrôle trop long" }
        sortie.binaire(s.chiffrer(clair))
    }

    private fun echec(pourquoi: String) {
        etat = Etat.REPOS; session = null
        sortie.fermer(pourquoi)
    }

    companion object {
        const val MAX_CLAIR = 65535 - 16
        const val MAX_REASSEMBLAGE = 4 * 1024 * 1024

        /**
         * Les activités que le serveur peut déclarer selon la PSK (règles de sendspin-js 5.0.0, « activate-authorization ») ;
         * null si c'est permis, sinon la raison du `client/goodbye`.
         */
        fun autoriser(categorie: String, act: Set<String>, rolesActifs: Boolean, nonAppaire: Boolean): String? {
            fun permis(a: Set<String>, na: Boolean): Boolean = when (categorie) {
                SendspinIdentite.LONG_TERME -> if ("pairing" in a) a.size == 1 else a.all { it == "playback" || it == "management" }
                SendspinIdentite.APPAIRAGE -> a == setOf("pairing")
                else -> a.isEmpty() || a == setOf("pairing") || (a == setOf("playback") && na)
            }
            fun admissible(na: Boolean) = permis(act, na) && (!rolesActifs || permis(act + "playback", na))
            if (admissible(nonAppaire)) return null
            if (categorie == SendspinIdentite.SENTINELLE && !nonAppaire && admissible(true)) return "pairing_required"
            return "unauthorized"
        }
    }
}
