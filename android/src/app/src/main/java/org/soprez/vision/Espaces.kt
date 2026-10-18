// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Refonte UX, lot 5 (etude-ux/05-architecture-cible.md §3) : les espaces de l'écran principal de l'appli.
 *
 * Décideur : Aujourd'hui, Personnes, Appareils, Réglages — les mêmes, dans le même ordre, sur téléphone (barre en
 * bas), tablette (rail à gauche) et télé (rail à gauche, grandes rubriques). Demandeur : Moi et Réglages (le groupe
 * « Pour tous » ; le groupe réservé reste derrière le code).
 *
 * Sans Android : la logique est essayée par EspacesTest (JVM). Les codes sont aussi les valeurs de
 * SetupActivity.EXTRA_ESPACE et ce qui est gardé à la recréation de l'écran.
 */
object Espaces {
    const val AUJOURDHUI = "aujourdhui"
    const val PERSONNES = "personnes"
    const val APPAREILS = "appareils"
    const val REGLAGES = "reglages"
    const val MOI = "moi"

    /** Les espaces du rôle, dans l'ordre affiché (4 au plus : la barre du téléphone les montre tous, texte compris). */
    fun pour(decideur: Boolean): List<String> =
        if (decideur) listOf(AUJOURDHUI, PERSONNES, APPAREILS, REGLAGES) else listOf(MOI, REGLAGES)

    /** Le premier espace du rôle : celui où Retour ramène, et d'où Retour quitte. */
    fun accueil(decideur: Boolean): String = pour(decideur).first()

    /**
     * L'espace à montrer : celui gardé à la recréation de l'écran [garde], sinon celui demandé par l'intention
     * [demande], sinon le premier du rôle. Un espace d'un autre rôle passe à son équivalent (Aujourd'hui ↔ Moi ;
     * Personnes et Appareils, absents chez le Demandeur, ramènent à Moi).
     */
    fun initial(decideur: Boolean, demande: String?, garde: String?): String {
        for (e in listOf(garde, demande)) {
            if (e.isNullOrEmpty()) continue
            return valide(decideur, e)
        }
        return accueil(decideur)
    }

    /** [e] s'il existe pour ce rôle, sinon son équivalent (le rôle a pu changer pendant que l'écran était ouvert). */
    fun valide(decideur: Boolean, e: String): String = when {
        e in pour(decideur) -> e
        e == REGLAGES -> REGLAGES
        else -> accueil(decideur)
    }

    /**
     * Retour depuis [courant] : l'espace où remonter, ou null quand on est déjà au premier (Retour fait alors ce
     * qu'il faisait avant : quitter l'écran principal). Jamais de sortie directe depuis un sous-espace.
     */
    fun retour(decideur: Boolean, courant: String): String? {
        val accueil = accueil(decideur)
        return if (valide(decideur, courant) != accueil) accueil else null
    }

    /** La navigation selon le type d'appareil (Identite.type) : barre en bas sur téléphone, rail à gauche ailleurs. */
    const val BARRE = "barre"
    const val RAIL = "rail"
    const val RAIL_TELE = "rail_tele"
    fun navigation(type: String): String = when (type) {
        "tv" -> RAIL_TELE
        "tablette" -> RAIL
        else -> BARRE
    }
}
