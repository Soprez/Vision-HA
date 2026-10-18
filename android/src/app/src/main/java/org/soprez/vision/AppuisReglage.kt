// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.Handler
import android.os.Looper

/**
 * 17-10 (l'auteur : « 20,5 remplacé par 20,0 en 3 s », 15/10 à 18 h 13 ; deux set_value de Vision à 3 s d'écart) : les appuis
 * − / + d'une valeur réglée (consigne…), sur le téléphone, la télé et le widget de l'écran d'accueil.
 * - chaque appui part de la DERNIÈRE valeur demandée (récente), jamais d'un état peut-être périmé (catalogue ancien,
 *   relecture qui arrive avant la confirmation de Home Assistant) ;
 * - les appuis rapprochés ([REGROUPER_MS]) sont regroupés : un seul envoi, avec la valeur finale ;
 * - la valeur demandée reste affichée jusqu'à ce que Home Assistant la confirme (au plus [MEMOIRE_MS]) : l'affichage ne
 *   revient jamais en arrière entre-temps, ce qui pousserait à appuyer encore.
 * La logique est pure (horloge passée en paramètre) ; seul [programmer] utilise le fil principal.
 */
object AppuisReglage {
    const val REGROUPER_MS = 600L
    const val MEMOIRE_MS = 10_000L

    private class Demande(val valeur: Double, val quand: Long)

    private val demandes = HashMap<String, Demande>()
    private val envois = HashMap<String, Runnable>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun nombre(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    /** L'état à partir duquel compter : la valeur demandée récemment, sinon l'état connu. */
    @Synchronized fun base(eid: String, st: WidgetsHa.EtatEntite, maintenant: Long): WidgetsHa.EtatEntite {
        val d = demandes[eid] ?: return st
        if (maintenant - d.quand > MEMOIRE_MS) { demandes.remove(eid); return st }
        return st.copy(etat = WidgetsHa.nombre(d.valeur))
    }

    /** Un appui (sens −1 ou +1) : la nouvelle valeur demandée, ou null s'il n'y a rien à régler. */
    @Synchronized fun appui(e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, sens: Int, maintenant: Long): Double? {
        val v = WidgetsHa.valeurApres(e, base(e.entityId, st, maintenant), sens) ?: return null
        demandes[e.entityId] = Demande(v, maintenant)
        return v
    }

    /** L'état à montrer : la valeur demandée tant que Home Assistant ne l'a pas confirmée (au plus [MEMOIRE_MS]). */
    @Synchronized fun affiche(eid: String, st: WidgetsHa.EtatEntite, maintenant: Long): WidgetsHa.EtatEntite {
        val d = demandes[eid] ?: return st
        val v = nombre(st.etat)
        if (v != null && Math.abs(v - d.valeur) < 1e-6) { demandes.remove(eid); return st }
        return base(eid, st, maintenant)
    }

    /** Un refus de Home Assistant : la demande est oubliée, l'état réel revient. */
    @Synchronized fun oublier(eid: String) { demandes.remove(eid) }

    /** Programme l'envoi : le dernier appui remplace les précédents, un seul part après [REGROUPER_MS] sans appui. */
    fun programmer(eid: String, envoi: () -> Unit) {
        synchronized(envois) {
            envois.remove(eid)?.let { main.removeCallbacks(it) }
            val r = Runnable { synchronized(envois) { envois.remove(eid) }; envoi() }
            envois[eid] = r
            main.postDelayed(r, REGROUPER_MS)
        }
    }

    /** Pour les essais : tout oublier. */
    @Synchronized fun vider() {
        demandes.clear()
        synchronized(envois) { envois.values.forEach { main.removeCallbacks(it) }; envois.clear() }
    }
}
