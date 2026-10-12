// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 1.77.0 (règle du projet : un clic met l'affichage à jour tout de suite) : le retour visible d'un geste.
 *
 * Chaque bouton de l'appli se retient au moment du clic ([retenir], appelé par les fabriques de boutons) ;
 * l'écran qui envoie une action le passe en « En cours… » ([enCours]) jusqu'à la réponse du serveur, puis se
 * redessine depuis cette réponse. Un échec relâche le bouton ([relacher]) et l'écran dit pourquoi, en clair.
 */
object Geste {
    /** « En cours… », dans la langue de l'appareil (comparé au texte du bouton : même source des deux côtés). */
    val EN_COURS: String get() = Tr.s(R.string.commun_en_cours)

    /** Le résultat d'un envoi : réussi ou non, et la phrase à montrer. */
    data class Resultat(val ok: Boolean, val texte: String)

    private var dernier: WeakReference<TextView>? = null
    private var texteAvant: CharSequence? = null
    private var touche = 0L

    fun retenir(v: TextView) { dernier = WeakReference(v); touche = android.os.SystemClock.elapsedRealtime() }

    /** Le dernier bouton touché (il y a moins de 20 s : jamais un bouton d'avant) passe en « En cours… », désactivé. */
    fun enCours() {
        if (android.os.SystemClock.elapsedRealtime() - touche > 20_000L) return
        val v = dernier?.get() ?: return
        if (v.text?.toString() == EN_COURS) return
        texteAvant = v.text
        v.text = EN_COURS
        v.isEnabled = false
        v.alpha = 0.7f
    }

    /** Après un échec, le bouton revient tel qu'il était (l'écran n'a pas été redessiné). */
    fun relacher() {
        val v = dernier?.get() ?: return
        if (v.text?.toString() == EN_COURS) texteAvant?.let { v.text = it }
        v.isEnabled = true
        v.alpha = 1f
    }

    /** L'état attendu d'une entité après une action, pour l'afficher sans attendre Home Assistant (null : inconnu). */
    fun etatAttendu(etat: String, service: String): String? = when (service) {
        "turn_on" -> "on"
        "turn_off" -> "off"
        "toggle" -> when (etat) { "on" -> "off"; "off" -> "on"; else -> null }
        "open_cover" -> "open"
        "close_cover" -> "closed"
        "lock" -> "locked"
        "unlock" -> "unlocked"
        else -> null
    }

    /** Un refus du serveur, en clair : jamais un code brut. */
    fun messageEchec(code: Int): String = when (code) {
        401, 403 -> Tr.s(R.string.geste_echec_droits)
        404 -> Tr.s(R.string.geste_echec_perime)
        409 -> Tr.s(R.string.geste_echec_interdit)
        in 400..499 -> Tr.s(R.string.geste_echec_refuse)
        else -> Tr.s(R.string.geste_echec_reseau)
    }

    /** Une liste JSON sans l'élément d'identifiant « id » (une demande réglée disparaît tout de suite). */
    fun sans(liste: org.json.JSONArray?, id: String): org.json.JSONArray {
        val sortie = org.json.JSONArray()
        if (liste == null) return sortie
        for (i in 0 until liste.length()) {
            val o = liste.optJSONObject(i) ?: continue
            if (o.optString("id") != id) sortie.put(o)
        }
        return sortie
    }
}
