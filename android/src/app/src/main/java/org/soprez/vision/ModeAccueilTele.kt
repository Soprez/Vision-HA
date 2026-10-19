// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * 1.93.06 (retour d'essai) : l'accueil de la télé a deux modes.
 *
 * - **UTILISATION** (par défaut) : les flèches vont directement sur les commandes des widgets qui en ont (−/+ d'une
 *   consigne, lecture du lecteur, cases des devoirs, boutons des demandes…) ; OK agit. Un widget sans commande garde son
 *   cadre (OK : son action ou son détail).
 * - **MODIFICATION** : on y entre par un appui long sur OK, ou par « Modifier l'accueil ». On va de cadre en cadre
 *   (applis, widgets) ; OK saisit le cadre, les flèches le **déplacent** ; OK encore : on le **redimensionne** (s'il peut
 *   changer de taille) ; OK valide. Retour annule l'étape en cours (le cadre revient où il était), puis quitte. Un bandeau
 *   le dit en haut de l'écran ; sans touche pendant [INACTIVITE_MS], on revient en UTILISATION (l'étape en cours est
 *   annulée, ce qui a été validé reste).
 *
 * Ce fichier ne contient que la logique (pure, testée en JVM : ModeAccueilTeleTest) ; l'écran est dans LanceurActivity.
 */
class ModeAccueilTele(private val horloge: () -> Long) {
    enum class Mode { UTILISATION, MODIFICATION }
    enum class Etape { CHOISIR, DEPLACER, REDIMENSIONNER }

    /** Ce que l'écran doit faire après une touche. */
    enum class Effet { RIEN, SAISIR, VERS_REDIM, VALIDER, ANNULER, QUITTER }

    var mode = Mode.UTILISATION; private set
    var etape = Etape.CHOISIR; private set
    /** La clé du cadre saisi (DEPLACER, REDIMENSIONNER). */
    var saisi: String? = null; private set
    private var dernierGeste = 0L

    val modification: Boolean get() = mode == Mode.MODIFICATION

    fun entrer() { mode = Mode.MODIFICATION; etape = Etape.CHOISIR; saisi = null; toucher() }

    fun quitter() { mode = Mode.UTILISATION; etape = Etape.CHOISIR; saisi = null }

    /** Une touche (n'importe laquelle) : l'inactivité repart de zéro. */
    fun toucher() { dernierGeste = horloge() }

    /** Rien depuis [INACTIVITE_MS] en MODIFICATION : il faut revenir en UTILISATION. */
    fun expire(): Boolean = modification && horloge() - dernierGeste >= INACTIVITE_MS

    /** OK sur le cadre [cle] ; [redimensionnable] : ce cadre peut changer de taille. */
    fun ok(cle: String?, redimensionnable: Boolean): Effet {
        if (!modification) return Effet.RIEN
        toucher()
        return when (etape) {
            Etape.CHOISIR -> if (cle == null) Effet.RIEN else { saisi = cle; etape = Etape.DEPLACER; Effet.SAISIR }
            Etape.DEPLACER -> if (redimensionnable) { etape = Etape.REDIMENSIONNER; Effet.VERS_REDIM } else valider()
            Etape.REDIMENSIONNER -> valider()
        }
    }

    /** Retour : annule l'étape en cours ; sans étape en cours, quitte la modification. */
    fun retour(): Effet {
        if (!modification) return Effet.RIEN
        toucher()
        return if (etape == Etape.CHOISIR) { quitter(); Effet.QUITTER } else { etape = Etape.CHOISIR; saisi = null; Effet.ANNULER }
    }

    private fun valider(): Effet { etape = Etape.CHOISIR; saisi = null; return Effet.VALIDER }

    companion object {
        const val INACTIVITE_MS = 60_000L
        /** Un appui sur OK tenu au moins ce temps (avec répétition de la touche) : appui long. */
        const val APPUI_LONG_MS = 600L

        /** Appui long sur OK : la touche se répète et elle est tenue depuis [APPUI_LONG_MS]. Pur. */
        fun appuiLong(repetitions: Int, tenueMs: Long): Boolean = repetitions > 0 && tenueMs >= APPUI_LONG_MS

        /** [liste] avec [cle] avancée ([sens] = +1) ou reculée (−1) d'un rang ; inchangée au bord ou si absente. Pur. */
        fun <T> deplacer(liste: List<T>, cle: T, sens: Int): List<T> {
            val i = liste.indexOf(cle)
            val j = i + sens
            if (i < 0 || j < 0 || j >= liste.size) return liste
            return liste.toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }
        }

        /** La taille après une flèche : ◄► la largeur, ▲▼ la hauteur, dans les bornes. Pur. */
        fun redimensionner(w: Int, h: Int, dw: Int, dh: Int, minW: Int, minH: Int, maxW: Int, maxH: Int): Pair<Int, Int> =
            (w + dw).coerceIn(minW, maxOf(minW, maxW)) to (h + dh).coerceIn(minH, maxOf(minH, maxH))
    }
}
