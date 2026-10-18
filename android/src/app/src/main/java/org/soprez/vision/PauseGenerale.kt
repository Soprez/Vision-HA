// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pause générale (point 14.2) : depuis l'espace gestion, un geste ferme d'un coup tous les appareils protégés
 * (les Demandeurs) pour une durée — le dîner, les devoirs — et « Reprendre » les rouvre (retour au planning).
 * Ce n'est pas la pause de Vision (module « pause »), qui lève la protection d'un appareil.
 *
 * Home Assistant reçoit `action: "pause_generale"` (minutes, 1 à 240) ou `"fin_pause_generale"` sur
 * /api/pc_parental/parent et répond, comme toujours, avec l'état de la maison, dont
 * `"pause_generale": {"fin": horodatage en secondes}` pendant une pause, sinon null.
 *
 * La partie pure (lecture de l'état, texte de la bannière, décisions) est essayée en JUnit (PauseGeneraleTest).
 */
object PauseGenerale {
    const val ACTION = "pause_generale"
    const val FIN = "fin_pause_generale"
    const val MINUTES_MAX = 240
    /** Refonte UX, lot 2 (A8) : les durées proposées, toutes dans la limite. */
    val DUREES = listOf(15, 30, 60, 120, 240)

    /** L'heure de fin (ms) si elle est à venir ; null sinon (pas de pause, ou pause échue). */
    fun finMs(finSecondes: Long?, maintenantMs: Long): Long? =
        finSecondes?.takeIf { it > 0 }?.let { it * 1000 }?.takeIf { it > maintenantMs }

    /** Le champ `pause_generale` d'une réponse de l'espace gestion. */
    fun lire(r: JSONObject, maintenantMs: Long = System.currentTimeMillis()): Long? {
        val o = r.optJSONObject("pause_generale") ?: return null
        if (!o.has("fin")) return null
        return finMs(o.optLong("fin"), maintenantMs)
    }

    /** L'heure de fin à la façon du téléphone (langue, 12 ou 24 h). */
    fun heure(ms: Long): String = Tr.heure(ms)

    /** « Pause générale jusqu'à 19:45 », dans la langue de l'appareil. */
    fun banniere(finMs: Long): String = Tr.s(R.string.pausegenerale_banniere, heure(finMs))

    /**
     * Référence française d'avant l'i18n, avec un fuseau imposé : gardée pour les essais JVM
     * (PauseGeneraleTest, « 19h45 ») ; l'appli emploie [heure] et [banniere] sans fuseau, qui suivent la locale.
     */
    fun heure(ms: Long, tz: TimeZone): String =
        SimpleDateFormat("HH'h'mm", Locale.FRANCE).apply { timeZone = tz }.format(Date(ms))

    /** Référence française (voir [heure] avec fuseau) : « Tout est fermé jusqu'à 19h45 » (lot 1, APP-09 : « Tout fermer »). */
    fun banniere(finMs: Long, tz: TimeZone): String = "Tout est fermé jusqu'à ${heure(finMs, tz)}"

    /** Le bouton se montre si le module est actif et que le rôle gère (liste vide : serveur ancien, l'espace suffit). */
    fun visible(moduleActif: Boolean, fonctions: Set<String>): Boolean =
        moduleActif && (fonctions.isEmpty() || "gerer" in fonctions)

    fun dureeValide(minutes: Int): Boolean = minutes in 1..MINUTES_MAX
}
