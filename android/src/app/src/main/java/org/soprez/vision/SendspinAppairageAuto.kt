// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * 1.90.1 / HA 2.4.5 (retour d'essai) : quand demander à Home Assistant l'appairage automatique avec Music Assistant, et quel PIN
 * lui envoyer. Sans Android : testé en JVM ([LecteurSendspin] s'en sert).
 */
object SendspinAppairageAuto {
    /** Une demande au plus toutes les 2 min (Home Assistant en refuse une par minute de toute façon). */
    const val INTERVALLE_MS = 120_000L

    /** Demander l'appairage : relié à MA, pas encore appairé (confiance « none » dite par la poignée de main : jamais
     *  avant, où elle est inconnue), aucun appairage en cours (un appairage fini ou abandonné ne bloque plus les
     *  nouvelles demandes, 1.90.2). */
    fun demarrer(connecte: Boolean, confiance: String, appairage: String?, pin: String?, dernier: Long, maintenant: Long): Boolean =
        connecte && confiance == "none" && appairage !in EN_COURS && pin == null && (dernier == 0L || maintenant - dernier > INTERVALLE_MS)

    private val EN_COURS = setOf("commence", "attente_geste")

    /** 1.90.2 (exigence ferme de l'auteur : « jamais le code à l'écran ») : le PIN à afficher. Jamais pendant l'appairage
     *  automatique, qu'il réussisse, échoue ou traîne ; seulement en appairage à la main ([manuel] : demandé dans
     *  Réglages › Décideurs 🔒), et jamais une fois appairé (confiance « user »). */
    fun pinAMontrer(confiance: String, pin: String?, manuel: Boolean): String? =
        pin?.takeIf { manuel && confiance != "user" }

    /** L'écran « autoriser à la télécommande » : lui aussi seulement en appairage à la main. */
    fun gesteAMontrer(confiance: String, appairage: String?, manuel: Boolean): Boolean =
        manuel && confiance != "user" && appairage == "attente_geste"

    /** L'état montré dans Réglages › Décideurs (jamais de code) : appairé, connexion en cours, ou échec. */
    enum class Statut { APPAIRE, CONNEXION, ECHEC }

    fun statut(connecte: Boolean, confiance: String, echec: String?): Statut = when {
        connecte && confiance == "user" -> Statut.APPAIRE
        echec != null -> Statut.ECHEC
        else -> Statut.CONNEXION
    }

    /** Une réponse de Home Assistant qui dit que l'appairage automatique ne peut pas aboutir (null : réussi ou en cours). */
    fun echec(etape: String, resultat: String): String? = when {
        resultat == "ok" -> null
        etape == "demarrer" && resultat == "trop_vite" -> null  // une demande déjà en cours
        else -> resultat.ifEmpty { "injoignable" }
    }

    /** Le PIN à envoyer à Home Assistant : un nouveau PIN affiché, une seule fois. */
    fun pinAEnvoyer(pin: String?, dejaEnvoye: String?): String? = pin?.takeIf { it != dejaEnvoye }
}
