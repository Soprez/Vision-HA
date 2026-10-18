// SPDX-License-Identifier: MIT
package org.soprez.vision

import kotlin.math.abs
import kotlin.math.min

/**
 * Client Sendspin natif (lot 3) : la décision de synchro, sans Android (essayée en JUnit, SendspinSynchroTest).
 *
 * Avant d'écrire un bloc de PCM dans la sortie audio, on compare l'heure à laquelle sa première trame SORTIRA
 * (prévue d'après l'horodatage de la sortie audio) à l'heure à laquelle elle DOIT sortir (horodatage du serveur
 * traduit par l'horloge). Stratégie suggérée par la spec Sendspin (« Suggested correction strategy ») :
 *  - écart < [TOLERANCE_US] : rien à faire ;
 *  - écart modéré : retirer (en retard) ou doubler (en avance) quelques trames, au plus 1 sur [PAS_MAX] (0,5 % de
 *    vitesse au plus, inaudible) ;
 *  - grand écart (démarrage, coupure réseau) : recalage d'un coup — silence ajouté si en avance, trames jetées si
 *    en retard. Rare par construction.
 */
object SendspinSynchro {
    const val TOLERANCE_US = 1_000L
    const val RECALAGE_US = 80_000L
    const val PAS_MAX = 200

    /**
     * Ce qu'il faut faire avant d'écrire un bloc de [trames] trames.
     * [silence] : trames de silence à écrire avant ; [jeter] : trames à jeter au début du bloc ;
     * [retirer] / [doubler] : trames à retirer ou à doubler, réparties dans le bloc (correction douce).
     */
    data class Decision(val silence: Int = 0, val jeter: Int = 0, val retirer: Int = 0, val doubler: Int = 0, val recalage: Boolean = false)

    /** [ecartUs] = heure prévue de sortie − heure voulue (positif : en retard). */
    fun decider(ecartUs: Long, trames: Int, debitHz: Int): Decision {
        val n = ((abs(ecartUs) * debitHz + 500_000) / 1_000_000).toInt()
        return when {
            abs(ecartUs) < TOLERANCE_US || n == 0 -> Decision()
            abs(ecartUs) >= RECALAGE_US -> if (ecartUs > 0) Decision(jeter = n, recalage = true) else Decision(silence = n, recalage = true)
            ecartUs > 0 -> Decision(retirer = min(n, maxOf(1, trames / PAS_MAX)))
            else -> Decision(doubler = min(n, maxOf(1, trames / PAS_MAX)))
        }
    }

    /**
     * Banc Musique (2026-10-12, l'auteur : « par moments le son ne sort plus alors que la progression avance ») : le silence à
     * écrire en une fois est borné à une demi-seconde ; le bloc est ensuite réévalué. Avant, un horodatage absurde (horloge
     * pas encore synchronisée : +1921 s mesurés) faisait écrire des dizaines de minutes de silence d'un coup.
     */
    fun silenceParPas(silence: Int, debitHz: Int): Int = min(silence, maxOf(1, debitHz / 2))

    /** Au-delà de ce retard, on ne jette plus : on se recale (voir [reancrage]). */
    const val REANCRAGE_US = 500_000L

    /**
     * Banc Musique (2026-10-12, mesuré contre MA 2.10.6) : au démarrage d'un titre YouTube, MA envoie des morceaux déjà
     * en retard de 3 à 5 s (horodatés au début de son flux, livrés après le chargement), et le retard grandit pendant le
     * chargement. Les jeter laissait la télé muette pendant que la progression avançait. Au-delà de [REANCRAGE_US], le
     * flux est recalé de ce retard (joué un peu plus tard que prévu) ; rend le décalage à ajouter (µs), 0 sinon.
     * (Essayé et écarté : recaler dès 80 ms en début de flux s'emballe — 202 recalages, 211 sous-alimentations.)
     */
    fun reancrage(ecartUs: Long): Long = if (ecartUs > REANCRAGE_US) ecartUs else 0L

    /**
     * Applique retraits et doublons sur un bloc PCM entrelacé (16 bits, [canaux] canaux), répartis régulièrement :
     * une trame retirée ou doublée de-ci de-là ne s'entend pas.
     */
    fun corriger(pcm: ShortArray, canaux: Int, retirer: Int, doubler: Int): ShortArray {
        val trames = pcm.size / canaux
        if (retirer <= 0 && doubler <= 0 || trames < 2) return pcm
        val k = if (retirer > 0) min(retirer, trames - 1) else doubler
        val pas = trames / (k + 1)
        val out = ShortArray((trames + if (retirer > 0) -k else k) * canaux)
        var o = 0; var fait = 0
        for (t in 0 until trames) {
            val marque = fait < k && t == pas * (fait + 1)
            if (marque && retirer > 0) { fait++; continue }
            System.arraycopy(pcm, t * canaux, out, o, canaux); o += canaux
            if (marque) { System.arraycopy(pcm, t * canaux, out, o, canaux); o += canaux; fait++ }
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    /**
     * L'heure (µs locales) à laquelle sortira la trame n° [trame] (comptée depuis la création de la sortie), d'après
     * le dernier horodatage de la sortie audio ([tsTrame] sortie à [tsUs]) ; sans horodatage, d'après la tête de
     * lecture et la latence nominale.
     */
    fun sortiePrevue(trame: Long, debitHz: Int, tsTrame: Long?, tsUs: Long?, maintenantUs: Long, tete: Long, latenceUs: Long): Long =
        if (tsTrame != null && tsUs != null) tsUs + (trame - tsTrame) * 1_000_000L / debitHz
        else maintenantUs + (trame - tete) * 1_000_000L / debitHz + latenceUs
}
