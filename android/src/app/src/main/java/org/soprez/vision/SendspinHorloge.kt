// SPDX-License-Identifier: MIT
package org.soprez.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Client Sendspin natif (lot 2) : l'horloge partagée avec Music Assistant. Chaque morceau d'audio porte l'heure du
 * serveur (µs) à laquelle il doit sortir ; ce filtre (Kalman à deux dimensions, décalage + dérive) la traduit en heure
 * de l'appareil. Portage tel quel du « time-filter » de Sendspin (aiosendspin 9.0.0, `client/time_sync.py`, lui-même
 * portage d'ESPHome), exigé par la spec pour tenir la synchro entre enceintes.
 *
 * Mesure NTP : Vision envoie `client/time` (t1), le serveur répond `server/time` (t2 reçu, t3 émis), Vision reçoit en
 * t4. Décalage = ((t2 − t1) + (t3 − t4)) / 2, erreur max = ((t4 − t1) − (t3 − t2)) / 2.
 * Sans Android : l'heure locale est donnée par l'appelant (µs, horloge monotone de l'appareil).
 */
class SendspinHorloge(processStdDev: Double = 0.0, forgetFactor: Double = 2.0, driftProcessStdDev: Double = 1e-11) {
    private class Element(val maj: Long = 0, val decalage: Double = 0.0, val derive: Double = 0.0, val avecDerive: Boolean = false)

    private val varianceProcessus = processStdDev * processStdDev
    private val varianceDerive = driftProcessStdDev * driftProcessStdDev
    private val oubli = forgetFactor * forgetFactor
    private var derniereMaj = 0L
    var mesures = 0; private set
    private var decalage = 0.0
    private var derive = 0.0
    private var covDecalage = Double.POSITIVE_INFINITY
    private var covDecalageDerive = 0.0
    private var covDerive = 0.0
    @Volatile private var courant = Element()

    /** Une réponse `server/time` reçue en [t4] (µs locales). */
    @Synchronized fun serveurTime(t1: Long, t2: Long, t3: Long, t4: Long) {
        val mesure = ((t2 - t1) + (t3 - t4)) / 2.0
        val erreur = ((t4 - t1) - (t3 - t2)) / 2.0
        maj(mesure.roundToLong(), erreur.roundToLong(), t4)
    }

    @Synchronized fun maj(mesure: Long, erreurMax: Long, quand: Long) {
        if (quand <= derniereMaj) return
        val dt = (quand - derniereMaj).toDouble()
        derniereMaj = quand
        val ecart = erreurMax.toDouble() * MAX_ERROR_SCALE
        val varMesure = ecart * ecart
        if (mesures <= 0) {
            mesures++
            decalage = mesure.toDouble(); covDecalage = varMesure; derive = 0.0
            courant = Element(derniereMaj, decalage, derive)
            return
        }
        if (mesures == 1) {
            mesures++
            derive = (mesure - decalage) / dt
            decalage = mesure.toDouble()
            covDerive = (covDecalage + varMesure) / (dt * dt)
            covDecalage = varMesure
            courant = Element(derniereMaj, decalage, derive)
            return
        }
        val prevu = decalage + derive * dt
        var nCovDerive = covDerive + dt * varianceDerive
        var nCovDecDer = covDecalageDerive + covDerive * dt
        var nCovDec = covDecalage + 2 * covDecalageDerive * dt + covDerive * dt * dt + dt * varianceProcessus
        val residu = mesure - prevu
        if (mesures < 100) mesures++
        else if (abs(residu) > erreurMax * ADAPTIVE_FORGETTING_CUTOFF) {
            nCovDerive *= oubli; nCovDecDer *= oubli; nCovDec *= oubli
        }
        val incertitude = 1.0 / max(nCovDec + varMesure, 1e-9)
        val gainDec = nCovDec * incertitude
        val gainDer = nCovDecDer * incertitude
        decalage = prevu + gainDec * residu
        derive += gainDer * residu
        covDerive = nCovDerive - gainDer * nCovDecDer
        covDecalageDerive = nCovDecDer - gainDer * nCovDec
        covDecalage = nCovDec - gainDec * nCovDec
        courant = Element(derniereMaj, decalage, derive, derive * derive > DRIFT_SIGNIFICANCE_SQ * covDerive)
    }

    /** Heure du serveur (µs) → heure de l'appareil (µs). */
    fun versLocal(serveur: Long): Long {
        val e = courant
        val d = if (e.avecDerive) e.derive else 0.0
        return ((serveur.toDouble() - e.decalage + d * e.maj) / (1.0 + d)).roundToLong()
    }

    /** Heure de l'appareil (µs) → heure du serveur (µs). */
    fun versServeur(local: Long): Long {
        val e = courant
        val d = if (e.avecDerive) e.derive else 0.0
        return local + (e.decalage + d * (local - e.maj)).roundToLong()
    }

    val synchro: Boolean @Synchronized get() = mesures >= 2 && !covDecalage.isInfinite()
    /** Écart type estimé (µs). */
    val erreur: Long @Synchronized get() = if (covDecalage.isInfinite()) Long.MAX_VALUE else sqrt(covDecalage).roundToLong()

    /** Prochain `client/time` : 200 ms tant que la synchro n'est pas faite, puis de 0,5 à 3 s selon l'erreur. */
    fun intervalleMs(): Long = when {
        !synchro -> 200
        erreur < 1_000 -> 3_000
        erreur < 2_000 -> 1_000
        erreur < 5_000 -> 500
        else -> 200
    }

    @Synchronized fun remettre() {
        mesures = 0; derniereMaj = 0; decalage = 0.0; derive = 0.0
        covDecalage = Double.POSITIVE_INFINITY; covDecalageDerive = 0.0; covDerive = 0.0; courant = Element()
    }

    private companion object {
        const val ADAPTIVE_FORGETTING_CUTOFF = 3.0
        const val MAX_ERROR_SCALE = 0.5
        const val DRIFT_SIGNIFICANCE_SQ = 4.0
    }
}
