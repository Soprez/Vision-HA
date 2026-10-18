// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Refonte UX, lot 6 (B10 / APP-08) : une seule série de durées pour demander du temps, et une seule série de
 * réponses pour le Décideur, quel que soit l'écran (écran de blocage, accueil, espace Moi, déverrouillage ;
 * notification, plein écran, espace gestion). Avant : trois jeux de durées et trois jeux de réponses.
 *
 * Logique pure (sauf les libellés, qui passent par [Tr]) : essayée en JUnit (DureesTest).
 */
object Durees {
    /** Ce qu'un Demandeur peut demander, partout. */
    val DEMANDE = listOf(15, 30, 60)

    /** Ce qu'un Décideur peut accorder par « Autre durée… », partout (plus « jusqu'à la prochaine plage » si elle vient). */
    val REPONSE = listOf(15, 30, 60, 120)

    /** L'accès temporaire à une appli ou un site (« 1 h ») : la même durée dans la notification, le plein écran et la gestion. */
    const val ACCES_TEMPORAIRE = 60

    /** La durée accordée d'un geste quand la demande ne dit pas combien elle veut (serveur plus ancien). */
    const val PAR_DEFAUT = 30

    /** Ce que le bouton « Accorder … » accorde : ce qui est demandé, sinon [PAR_DEFAUT]. */
    fun accordee(demandee: Int): Int = if (demandee > 0) demandee else PAR_DEFAUT

    /** « 15 min », « 30 min », « 1 h »… dans la langue de l'appareil. */
    fun libelles(minutes: List<Int>): List<String> = minutes.map { Tr.duree(it) }

    /** Les trois réponses du Décideur à une demande de temps, dans l'ordre : accorder ce qui est demandé, une autre durée, refuser. */
    fun reponsesTemps(demandee: Int): List<String> =
        listOf(Tr.s(R.string.parents_accorder, Tr.duree(accordee(demandee))), Tr.s(R.string.temps_autre_duree), Tr.s(R.string.adec_refuser))

    /** Les trois réponses du Décideur à une demande d'accès : pour un temps, toujours, refuser. */
    fun reponsesAcces(): List<String> =
        listOf(Tr.duree(ACCES_TEMPORAIRE), Tr.s(R.string.parents_autoriser), Tr.s(R.string.adec_refuser))
}
