// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Les textes de l'écran de blocage (logique pure, testée) : ce qui est fermé, pourquoi,
 * jusqu'à quand. Complets, neutres (ni parent ni enfant), sans rien de tronqué.
 */
object TextesBlocage {
    /**
     * Refonte lot 6 (B10 / APP-33) : l'écran se lit « Fermé par : {raison}, jusqu'à {heure} ».
     * [fermePar] : qui ou quoi a fermé (le planning, la limite de temps, « Tout fermer », un Décideur…), ou la raison
     * reçue telle quelle ; [detail] : la raison donnée par le serveur quand [fermePar] est la source (sinon vide) ;
     * [jusqua] : l'heure de réouverture (« demain 07:00 ») ou, sans heure prévue, qui peut rouvrir.
     */
    class Textes(val titre: String, val fermePar: String, val detail: String, val jusqua: String, private val aUneHeure: Boolean, private val appli: Boolean) {
        /** Compatibilité : la raison affichée (ancienne ligne « Pourquoi »). */
        val pourquoi: String get() = fermePar
        val quand: String get() = jusqua

        /** L'étiquette de la première ligne : « Fermé par » (appareil) ou « Fermée par » (appli). */
        fun etiquette(): String = Tr.s(if (appli) R.string.lot6_ferme_par_appli else R.string.lot6_ferme_par_appareil)

        /** Une phrase pour la lecture vocale : « Fermé par : le planning, jusqu'à 21:30 ». */
        fun resume(): String = if (aUneHeure) Tr.s(R.string.lot6_resume_jusqua, etiquette(), sansPoint(fermePar), jusqua)
            else Tr.s(R.string.lot6_resume, etiquette(), sansPoint(fermePar))

        private fun sansPoint(s: String) = s.trimEnd('.')
    }

    /** Qui ou quoi a fermé, d'après le code du motif envoyé par Home Assistant. */
    private val SOURCE = mapOf(
        "plage" to R.string.lot6_source_plage,
        "temps" to R.string.lot6_source_temps,
        "moyenne" to R.string.lot6_source_moyenne,
        "regle" to R.string.lot6_source_regle,
        "age" to R.string.lot6_source_age,
        "categorie" to R.string.lot6_source_categorie,
        "maison" to R.string.lot6_source_maison,
        "securite" to R.string.lot6_source_securite,
        "concentration" to R.string.lot6_source_concentration,
        "lieu" to R.string.lot6_source_lieu,
    )

    /** La source d'un code connu (« Le planning », « Un décideur »…), ou null. */
    fun source(code: String): String? = when (val c = code.trim().lowercase()) {
        "regle" -> Tr.s(R.string.lot6_source_regle, Vocabulaire.decideur())
        "maison" -> Tr.s(R.string.lot6_source_maison, Tr.s(R.string.parents_pause_generale))
        else -> SOURCE[c]?.let { Tr.s(it) }
    }

    /**
     * L'heure de réouverture pour la ligne « Jusqu'à » : « 21:30 », « demain 07:00 », « mardi 14 octobre, 07:00 ».
     * [maintenant] : pour les essais.
     */
    fun heure(ms: Long, maintenant: Long = System.currentTimeMillis()): String {
        val c = java.util.Calendar.getInstance(); c.timeInMillis = maintenant
        val auj = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        c.add(java.util.Calendar.DAY_OF_YEAR, 1); val dem = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        c.timeInMillis = ms; val jour = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        val h = Tr.heure(ms)
        return when (jour) {
            auj -> Tr.s(R.string.lot6_heure_aujourdhui, h)
            dem -> Tr.s(R.string.lot6_heure_demain, h)
            else -> Tr.s(R.string.lot6_heure_date, Tr.date(ms, "EEEEdMMMM"), h)
        }
    }

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

    /**
     * [reouverture] : l'heure déjà mise en forme par [heure] (vide s'il n'y en a pas). [raisonPassee] : la raison avec
     * laquelle l'écran a été ouvert (APP-33 : gardée quand le relevé n'en dit pas plus, au lieu d'un texte générique).
     */
    fun de(verrouille: Boolean, message: String, reglageProtege: Boolean, appli: String,
           motif: String, codeMotif: String, reouverture: String, raisonPassee: String = ""): Textes {
        val aUneHeure = reouverture.isNotEmpty()
        val jusquaAppareil = if (aUneHeure) reouverture else Tr.s(R.string.blocage_pas_de_reouverture, Vocabulaire.decideur())
        val passee = raisonPassee.trim().takeIf { it.isNotEmpty() && !generique(it.lowercase()) }
        if (reglageProtege) return Textes(
            Tr.s(R.string.blocage_reglage_titre),
            Tr.s(R.string.blocage_reglage_pourquoi), "",
            if (verrouille) jusquaAppareil else "", verrouille && aUneHeure, appli = false)
        val msg = message.trim()
        if (verrouille) return Textes(
            Tr.s(R.string.blocage_appareil_titre),
            msg.takeIf { it.isNotEmpty() && !generique(it.lowercase()) }?.let { phrase(it) }
                ?: passee?.let { phrase(it) }
                ?: Tr.s(R.string.blocage_appareil_pourquoi),
            "", jusquaAppareil, aUneHeure, appli = false)
        val nom = appli.trim()
        val titre = if (nom.isNotEmpty()) Tr.s(R.string.blocage_appli_titre_nom, nom) else Tr.s(R.string.blocage_appli_titre)
        val code = codeMotif.trim().lowercase()
        val raison = motif.trim().takeIf { it.isNotEmpty() }?.let { phrase(it) }
        val source = source(code)
        // Une appli : l'heure du prochain changement ne vaut que pour une fermeture par le planning ou « Tout fermer ».
        val jusquaAppli = if (code == "plage" || code == "maison") reouverture else ""
        if (source != null) {
            // La source d'abord ; dessous, la raison du serveur, ou l'explication du code (sauf quand elle redit la source).
            val detail = raison ?: if (code == "regle") "" else PAR_CODE[code]?.let { Tr.s(it, Vocabulaire.decideur()) } ?: ""
            return Textes(titre, source, detail, jusquaAppli, jusquaAppli.isNotEmpty(), appli = true)
        }
        val pourquoi = raison ?: passee?.let { phrase(it) } ?: Tr.s(R.string.blocage_appli_pourquoi)
        return Textes(titre, pourquoi, "", "", false, appli = true)
    }

    /** Une raison reçue telle quelle, avec sa majuscule et son point. */
    private fun phrase(s: String): String {
        val t = s.trim().replaceFirstChar { it.uppercaseChar() }
        return if (t.last() in ".!?…") t else "$t."
    }
}
