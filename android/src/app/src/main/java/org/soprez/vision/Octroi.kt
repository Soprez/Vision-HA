// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.SystemClock

/**
 * FAILLE DE SÉCURITÉ (l'auteur, 16-10) : depuis son téléphone de Décideur, l'auteur a déverrouillé Alex en touchant la demande,
 * sans empreinte ni code — la fenêtre de grâce de la garde (2 min par défaut) laissait passer.
 *
 * Règle : tout geste qui déverrouille ou accorde (accepter une demande, déverrouiller, prolonger, autoriser, ouvrir,
 * valider un appareil…) exige À CHAQUE FOIS l'empreinte ou le code, sans délai de grâce, quel que soit le chemin
 * (notification, widget, tuile, À décider, fiche personne…).
 *
 * Garantie structurelle : [Net.post] ne laisse partir une requête d'octroi que si une preuve d'authentification
 * fraîche (moins de [VALIDITE_MS]) et À USAGE UNIQUE a été délivrée juste avant par [Garde.exigerOctroi]. Un chemin
 * oublié échoue donc fermé (rien n'est envoyé) au lieu d'accorder sans preuve. Refuser, fermer, lire : libres.
 */
object Octroi {
    /** Le temps de faire partir la requête après l'empreinte (réseau lent, deux adresses). Pas une fenêtre de grâce :
     *  la preuve ne sert qu'une fois. */
    const val VALIDITE_MS = 60_000L

    /** Les gestes de /parent qui restreignent ou ne changent rien aux droits : pas de preuve en plus de la garde. */
    val RESTRICTIFS = setOf("fermer", "message", "concentration", "pause_generale")

    /** Les lectures de /parent (aucun état ne change). */
    val LECTURES = setOf("", "categorie", "recherche", "recents", "canaux", "code_deverrouillage")

    /** Les décisions qui ferment (un élément, une catégorie). */
    val BLOCAGES = setOf("bloquer", "bloque", "non", "fermer")

    @Volatile private var delivree = 0L

    /** Après une empreinte ou un code juste (jamais depuis la fenêtre de grâce). */
    @Synchronized fun delivrer(maintenant: Long = SystemClock.elapsedRealtime()) { delivree = maintenant }

    /** Consomme la preuve : vrai une seule fois, et seulement si elle est fraîche. */
    @Synchronized fun consommer(maintenant: Long = SystemClock.elapsedRealtime()): Boolean {
        val ok = delivree > 0L && maintenant >= delivree && maintenant - delivree <= VALIDITE_MS
        delivree = 0L
        return ok
    }

    /** Pour les essais. */
    @Synchronized fun oublier() { delivree = 0L }

    /**
     * Cette requête accorde-t-elle quelque chose ? Pur : le chemin et quelques champs du corps.
     * [action] : « action » du corps ; [decision] : « decision » (acces, deverrouillage) ou « action » (appairage) ;
     * [minutes] : « minutes » (réponse à une demande de temps).
     */
    fun estOctroi(chemin: String, action: String, decision: String, minutes: Int): Boolean {
        val c = chemin.substringBefore('?').trimEnd('/')
        return when {
            c.endsWith("/api/pc_parental/parent") -> when {
                action in LECTURES -> false
                action in RESTRICTIFS -> false
                action == "acces" -> decision != "non"
                // Ranger un élément ou une catégorie : « bloquer » restreint ; autoriser, neutre (qui peut rouvrir)… accordent.
                action in setOf("autoriser", "etiquette") -> decision !in BLOCAGES
                else -> true // ouvrir, annuler, temps, autoriser, etiquette, partage, attribuer, limite, plage,
                // logiciel, pause_max, lier_domaine, retirer_autorisation, fin_pause_generale, reprise_autorisations…
            }
            c.endsWith("/api/pc_parental/reponse") -> minutes > 0
            c.endsWith("/api/pc_parental/deverrouillage/decision") -> decision !in setOf("non", "refuser", "refus", "")
            // 1.93.06 (Déverrouillage simplifié) : déverrouiller à son initiative accorde ; annuler, non.
            c.endsWith("/api/pc_parental/deverrouillage/initiative") -> decision != "annuler"
            c.endsWith("/api/pc_parental/appairage/decideur/decision") -> decision !in setOf("refuser", "non", "")
            else -> false
        }
    }

    fun estOctroi(chemin: String, corps: org.json.JSONObject): Boolean = estOctroi(
        chemin,
        corps.optString("action"),
        corps.optString("decision").ifEmpty { if (chemin.contains("appairage/decideur")) corps.optString("action") else corps.optString("etat") },
        corps.optInt("minutes", 0),
    )

    /** Levée par [Net.post] quand une requête d'octroi part sans preuve fraîche : rien n'a été envoyé. */
    class SansPreuve : Net.Echec(401, "auth")
}
