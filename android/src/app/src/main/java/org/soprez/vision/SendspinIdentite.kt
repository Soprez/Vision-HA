// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * Client Sendspin natif (lot 1) : l'identité de ce lecteur et ses clés partagées (PSK).
 *
 * - Identité : une paire X25519 tirée une fois par appareil ; la clé publique (base64url, 43 caractères) est le
 *   `client_id` : Music Assistant reconnaît ainsi le lecteur d'une fois à l'autre (et son entité dans Home Assistant).
 * - PSK : Sentinel (publique, lecture sans appairage), clé d'appairage (jeton), clés long terme (une par serveur,
 *   données par un appairage réussi). Toutes restent dans les préférences privées de l'appli.
 * Sans Android pour les essais : [Stock] remplace les préférences.
 */
class SendspinIdentite(private val stock: Stock) {
    interface Stock {
        fun lire(cle: String): String?
        fun ecrire(cle: String, valeur: String?)
    }

    class Psk(val psk: ByteArray, val categorie: String, val serverId: String? = null) {
        val id: String = SendspinNoise.pskId(psk)
    }

    val paire: SendspinNoise.Paire
    val clientId: String

    init {
        val sk = stock.lire(CLE_IDENTITE)?.let { runCatching { SendspinNoise.deB64url(it) }.getOrNull() }?.takeIf { it.size == 32 }
        paire = if (sk != null) SendspinNoise.Paire(sk, SendspinNoise.publique(sk))
        else SendspinNoise.paire().also { stock.ecrire(CLE_IDENTITE, SendspinNoise.b64url(it.prive)) }
        clientId = SendspinNoise.b64url(paire.publique)
    }

    private val sentinelle = Psk(SendspinNoise.SENTINEL, SENTINELLE)

    fun longTermes(): List<Psk> {
        val l = Json.lire(stock.lire(CLE_LONG_TERME) ?: "[]") as? List<*> ?: return emptyList()
        return l.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val p = runCatching { SendspinNoise.deB64url(m["psk"] as String) }.getOrNull() ?: return@mapNotNull null
            Psk(p, LONG_TERME, m["server_id"] as? String)
        }
    }

    fun appairage(): Psk? = stock.lire(CLE_APPAIRAGE)?.let { runCatching { SendspinNoise.deB64url(it) }.getOrNull() }
        ?.takeIf { it.size == 32 }?.let { Psk(it, APPAIRAGE) }

    /** La PSK désignée par le serveur (message 1), ou null si inconnue. */
    fun psk(id: String): Psk? = (listOf(sentinelle) + listOfNotNull(appairage()) + longTermes()).firstOrNull { it.id == id }

    /** Un appairage réussi : la clé long terme de ce serveur (remplace l'ancienne du même serveur). */
    @Synchronized fun ajouterLongTerme(psk: ByteArray, serverId: String) {
        val l = longTermes().filter { it.serverId != serverId } + Psk(psk, LONG_TERME, serverId)
        stock.ecrire(CLE_LONG_TERME, Json.ecrire(l.map { linkedMapOf("psk" to SendspinNoise.b64url(it.psk), "server_id" to it.serverId) }))
    }

    @Synchronized fun oublier(id: String) {
        val l = longTermes().filter { it.id != id }
        stock.ecrire(CLE_LONG_TERME, Json.ecrire(l.map { linkedMapOf("psk" to SendspinNoise.b64url(it.psk), "server_id" to it.serverId) }))
    }

    /** La clé d'appairage (créée au premier besoin). */
    @Synchronized fun cleAppairage(): ByteArray = appairage()?.psk ?: ByteArray(32).also {
        java.security.SecureRandom().nextBytes(it); stock.ecrire(CLE_APPAIRAGE, SendspinNoise.b64url(it))
    }

    val appaire: Boolean get() = longTermes().isNotEmpty()

    /** Échecs de PIN de suite (après 10, un geste sur place est exigé) ; gardés d'un démarrage à l'autre. */
    var echecsPin: Int
        get() = stock.lire(CLE_ECHECS)?.toIntOrNull() ?: 0
        set(v) = stock.ecrire(CLE_ECHECS, v.toString())

    companion object {
        const val SENTINELLE = "sentinel"
        const val APPAIRAGE = "pairing"
        const val LONG_TERME = "long_term"
        private const val CLE_IDENTITE = "identite"
        private const val CLE_LONG_TERME = "psk_long_terme"
        private const val CLE_APPAIRAGE = "psk_appairage"
        private const val CLE_ECHECS = "echecs_pin"

        /** L'identité de cet appareil, gardée dans ses préférences privées. */
        fun de(ctx: Context): SendspinIdentite {
            val p = ctx.applicationContext.getSharedPreferences("sendspin", 0)
            return SendspinIdentite(object : Stock {
                override fun lire(cle: String) = p.getString(cle, null)
                override fun ecrire(cle: String, valeur: String?) { p.edit().putString(cle, valeur).apply() }
            })
        }
    }
}
