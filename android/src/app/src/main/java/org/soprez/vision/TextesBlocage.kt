// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Les textes de l'écran de blocage (logique pure, testée) : ce qui est fermé, pourquoi,
 * jusqu'à quand. Complets, neutres (ni parent ni enfant), sans rien de tronqué.
 */
object TextesBlocage {
    class Textes(val titre: String, val pourquoi: String, val quand: String)

    /** Les motifs que Home Assistant envoie avec un code (« code|texte ») : un libellé de repli si le texte manque. */
    private val PAR_CODE = mapOf(
        "plage" to R.string.blocage_code_plage,
        "temps" to R.string.blocage_code_temps,
        "moyenne" to R.string.blocage_code_moyenne,
        "regle" to R.string.blocage_code_regle,
        "age" to R.string.blocage_code_age,
        "categorie" to R.string.blocage_code_categorie,
        "maison" to R.string.blocage_code_maison,
        "securite" to R.string.blocage_code_securite,
        "concentration" to R.string.blocage_code_concentration,
        "lieu" to R.string.blocage_code_lieu,
    )

    /**
     * Message générique du serveur, qui n'explique rien : on ne le répète pas comme raison.
     * Comparaison avec ce que Home Assistant envoie (données, pas texte affiché) : les messages français
     * des serveurs d'avant l'i18n, et les mêmes messages dans la langue de l'appareil.
     */
    private val GENERIQUES_FR = setOf("accès fermé", "bloqué", "cette appli est bloquée")  // i18n-exception : données du serveur
    private fun generique(m: String): Boolean = m in GENERIQUES_FR ||
        m == Tr.s(R.string.blocage_generique_acces_ferme).lowercase() || m == Tr.s(R.string.blocage_generique_appli_bloquee).lowercase()

    fun de(verrouille: Boolean, message: String, reglageProtege: Boolean, appli: String,
           motif: String, codeMotif: String, reouverture: String): Textes {
        val quandAppareil = if (reouverture.isNotEmpty()) Tr.s(R.string.blocage_reouverture_prevue, reouverture)
            else Tr.s(R.string.blocage_pas_de_reouverture, Vocabulaire.decideur())
        if (reglageProtege) return Textes(
            Tr.s(R.string.blocage_reglage_titre),
            Tr.s(R.string.blocage_reglage_pourquoi),
            if (verrouille) quandAppareil else "")
        val msg = message.trim()
        if (verrouille) return Textes(
            Tr.s(R.string.blocage_appareil_titre),
            if (msg.isNotEmpty() && !generique(msg.lowercase())) phrase(msg)
                else Tr.s(R.string.blocage_appareil_pourquoi),
            quandAppareil)
        val nom = appli.trim()
        val titre = if (nom.isNotEmpty()) Tr.s(R.string.blocage_appli_titre_nom, nom) else Tr.s(R.string.blocage_appli_titre)
        val pourquoi = motif.trim().takeIf { it.isNotEmpty() }?.let { phrase(it) }
            ?: PAR_CODE[codeMotif.trim().lowercase()]?.let { Tr.s(it, Vocabulaire.decideur()) }
            ?: Tr.s(R.string.blocage_appli_pourquoi)
        return Textes(titre, pourquoi, "")
    }

    /** Une raison reçue telle quelle, avec sa majuscule et son point. */
    private fun phrase(s: String): String {
        val t = s.trim().replaceFirstChar { it.uppercaseChar() }
        return if (t.last() in ".!?…") t else "$t."
    }
}
